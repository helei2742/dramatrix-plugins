package io.dramatrix.plugin.birefnet;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.ImageEditInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.ImageEditPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BiRefNet 智能抠图适配器（对应 ComfyUI 原生模板 BiRefNet: Remove Background）。
 *
 * <p>基础链路（workflow.json 里的固定锚点）：
 * {@code LoadImage} → {@code RemoveBackground}（配合 {@code LoadBackgroundRemovalModel}）
 * 输出<b>前景遮罩</b>（白=人像/主体，黑=背景），最终统一接到 {@code SaveImage}。</p>
 *
 * <p>合成链路<b>按 {@link ImageEditInput#getMode() 模式}动态构造节点</b>
 * （模板尾部节点可复用，缺节点时按内置模板新建，最后把不可达节点剪掉）：</p>
 * <table>
 *   <tr><th>mode</th><th>链路</th><th>结果</th></tr>
 *   <tr><td>{@code solid}（默认）</td>
 *       <td>GetImageSize → EmptyImage(纯色) → ImageCompositeMasked(dest=纯色底, source=原图, mask=前景)</td>
 *       <td>去除背景后的人像落在纯色背景上（缺省纯白，{@code backgroundColor} 可配）</td></tr>
 *   <tr><td>{@code remove_person}</td>
 *       <td>① 未指定颜色：JoinImageWithAlpha(原图, 前景遮罩) → 人像区域透明、原背景保留<br>
 *           ② 指定颜色：GetImageSize → EmptyImage(纯色) → InvertMask(前景) → ImageCompositeMasked</td>
 *       <td>去除人物（人物区域透明，或填充指定颜色）</td></tr>
 *   <tr><td>{@code replace_background}</td>
 *       <td>LoadImage(背景图) → ImageScale(对齐尺寸) → ImageCompositeMasked(dest=背景图, source=原图, mask=前景)</td>
 *       <td>把人放回指定背景图里</td></tr>
 * </table>
 *
 * <p>无提示词参与，因此实现 {@link ImageEditPlugin}（不带 buildPrompt）。</p>
 *
 * <p>注意：{@code JoinImageWithAlpha} 内部会对 alpha 取反（{@code alpha = 1 - mask}），
 * 故「人像区域透明」直接喂前景遮罩即可；这不是笔误。</p>
 */
public class BiRefNetCutoutImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    // ===================== 模式 =====================

    /** 模式①：去除背景，人像落在纯色背景上（默认） */
    public static final String MODE_SOLID = "solid";
    /** 模式②：去除人物（人物区域透明或填充指定颜色，保留原背景） */
    public static final String MODE_REMOVE_PERSON = "remove_person";
    /** 模式③：替换背景（人像合成到前端指定的背景图） */
    public static final String MODE_REPLACE_BACKGROUND = "replace_background";

    /** 颜色标记：透明（不做纯色填充） */
    private static final Set<String> TRANSPARENT_TOKENS = Set.of("transparent", "none", "alpha");

    /** 常用颜色名 → 0xRRGGBB（chroma = 抠像绿幕） */
    private static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
            Map.entry("white", 0xFFFFFF),
            Map.entry("black", 0x000000),
            Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080),
            Map.entry("red", 0xFF0000),
            Map.entry("green", 0x00FF00),
            Map.entry("chroma", 0x00FF00),
            Map.entry("blue", 0x0000FF)
    );

    private static final int COLOR_WHITE = 0xFFFFFF;
    /** 动态新建节点号下限（避开工作流自带的小号节点） */
    private static final int DYNAMIC_NODE_BASE = 100;

    // ===================== ComfyUI 节点类型 =====================

    private static final String NODE_LOAD_IMAGE = "LoadImage";
    private static final String NODE_LOAD_BG_MODEL = "LoadBackgroundRemovalModel";
    private static final String NODE_REMOVE_BACKGROUND = "RemoveBackground";
    private static final String NODE_GET_IMAGE_SIZE = "GetImageSize";
    private static final String NODE_EMPTY_IMAGE = "EmptyImage";
    private static final String NODE_INVERT_MASK = "InvertMask";
    private static final String NODE_JOIN_ALPHA = "JoinImageWithAlpha";
    private static final String NODE_IMAGE_SCALE = "ImageScale";
    private static final String NODE_COMPOSITE = "ImageCompositeMasked";
    private static final String NODE_SAVE_IMAGE = "SaveImage";

    // ===================== 动态建节点模板 =====================
    // 连接线占位会在装配时被覆盖，这里只保证节点结构合法。

    private static final String LOAD_BG_MODEL_TEMPLATE = """
    {
        "inputs": { "bg_removal_name": "birefnet.safetensors" },
        "class_type": "LoadBackgroundRemovalModel",
        "_meta": { "title": "加载背景移除模型" }
    }
    """;

    private static final String REMOVE_BACKGROUND_TEMPLATE = """
    {
        "inputs": { "image": ["1", 0], "bg_removal_model": ["2", 0] },
        "class_type": "RemoveBackground",
        "_meta": { "title": "移除背景（生成前景遮罩）" }
    }
    """;

    private static final String GET_IMAGE_SIZE_TEMPLATE = """
    {
        "inputs": { "image": ["1", 0] },
        "class_type": "GetImageSize",
        "_meta": { "title": "获取图像尺寸" }
    }
    """;

    private static final String EMPTY_IMAGE_TEMPLATE = """
    {
        "inputs": { "width": 1024, "height": 1024, "batch_size": 1, "color": 16777215 },
        "class_type": "EmptyImage",
        "_meta": { "title": "纯色背景" }
    }
    """;

    private static final String INVERT_MASK_TEMPLATE = """
    {
        "inputs": { "mask": ["1", 0] },
        "class_type": "InvertMask",
        "_meta": { "title": "反转遮罩（取背景区域）" }
    }
    """;

    private static final String JOIN_ALPHA_TEMPLATE = """
    {
        "inputs": { "image": ["1", 0], "alpha": ["1", 0] },
        "class_type": "JoinImageWithAlpha",
        "_meta": { "title": "输出透明人像区域" }
    }
    """;

    private static final String LOAD_IMAGE_TEMPLATE = """
    {
        "inputs": { "image": "" },
        "class_type": "LoadImage",
        "_meta": { "title": "加载背景图" }
    }
    """;

    private static final String SCALE_TEMPLATE = """
    {
        "inputs": {
            "image": ["1", 0],
            "upscale_method": "lanczos",
            "width": 1024,
            "height": 1024,
            "crop": "center"
        },
        "class_type": "ImageScale",
        "_meta": { "title": "背景图对齐尺寸" }
    }
    """;

    private static final String COMPOSITE_TEMPLATE = """
    {
        "inputs": {
            "destination": ["1", 0],
            "source": ["1", 0],
            "mask": ["1", 0],
            "x": 0,
            "y": 0,
            "resize_source": false
        },
        "class_type": "ImageCompositeMasked",
        "_meta": { "title": "合成（人像 + 背景）" }
    }
    """;

    private static final String SAVE_IMAGE_TEMPLATE = """
    {
        "inputs": { "filename_prefix": "cutout", "images": ["1", 0] },
        "class_type": "SaveImage",
        "_meta": { "title": "保存图像" }
    }
    """;

    /** 内置默认绑定：图片落点 + 抠图模型槽（对应默认 workflow.json）。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "image", "target": { "match": "classType", "classType": "LoadImage", "field": "image" } }
      ],
      "modelSlots": [
        {
          "group": "birefnet-bg-removal",
          "default": "birefnet.safetensors",
          "targets": [
            { "match": "classType", "classType": "LoadBackgroundRemovalModel", "field": "bg_removal_name" }
          ]
        }
      ]
    }
    """;

    public BiRefNetCutoutImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        ImageEditInput input = in == null ? new ImageEditInput() : in;
        // 动态建节点游标：从工作流已有最大节点号之后开始，避免冲突
        Seq seq = new Seq(nextNodeIdBase(api));

        // 1. 输入图 → LoadImage（落点由绑定声明，缺省保留工作流自带值）
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }
        String subjectId = resolveOrCreate(api, NODE_LOAD_IMAGE, null, seq);

        // 2. 抠图模型：模型槽（UI 选择）先落，请求参数显式指定时覆盖
        applyModelSlots(api);
        String modelId = resolveOrCreate(api, NODE_LOAD_BG_MODEL, LOAD_BG_MODEL_TEMPLATE, seq);
        if (StrUtil.isNotBlank(input.getBgRemovalModel())) {
            nodeInputs(api, modelId).set("bg_removal_name", input.getBgRemovalModel());
        }

        // 3. 抠图 → 前景遮罩（白=人像/主体）；连线一并纠正，避免用户 override 丢线
        String maskId = resolveOrCreate(api, NODE_REMOVE_BACKGROUND, REMOVE_BACKGROUND_TEMPLATE, seq);
        JSONObject maskInputs = nodeInputs(api, maskId);
        maskInputs.set("image", List.of(subjectId, 0));
        maskInputs.set("bg_removal_model", List.of(modelId, 0));

        // 4. 按模式动态构造合成链路（从遮罩往下）
        String outputId = switch (normalizeMode(input.getMode())) {
            case MODE_REMOVE_PERSON -> buildRemovePerson(api, input, subjectId, maskId, seq);
            case MODE_REPLACE_BACKGROUND -> buildReplaceBackground(api, input, subjectId, maskId, seq);
            default -> buildSolidBackground(api, input, subjectId, maskId, seq);
        };

        // 5. 输出节点接线（history 只产出一个 output 图，前端按 output 类型取）
        String saveId = resolveOrCreate(api, NODE_SAVE_IMAGE, SAVE_IMAGE_TEMPLATE, seq);
        nodeInputs(api, saveId).set("images", List.of(outputId, 0));

        // 6. 剪掉不可达节点：模板里被替换掉的尾部、以及 PreviewImage（temp 图会干扰结果解析）
        pruneUnreachable(api, saveId);

        return api;
    }

    // ===================== 各模式的链路装配 =====================

    /**
     * 模式①：去除背景 —— 人像（source=原图，mask=前景遮罩）合成到纯色底图上。
     */
    private String buildSolidBackground(
            JSONObject api, ImageEditInput in, String subjectId, String maskId, Seq seq
    ) {
        int color = resolveColor(in.getBackgroundColor(), COLOR_WHITE);
        String sizeId = ensureImageSize(api, subjectId, seq);
        String backgroundId = ensureEmptyImage(api, sizeId, color, seq);
        return ensureComposite(api, backgroundId, subjectId, maskId, seq);
    }

    /**
     * 模式②：去除人物 —— 人物区域透明（未指定颜色）或填充指定颜色，原背景保留。
     */
    private String buildRemovePerson(
            JSONObject api, ImageEditInput in, String subjectId, String maskId, Seq seq
    ) {
        String rawColor = in.getBackgroundColor();
        if (isTransparent(rawColor)) {
            // JoinImageWithAlpha 内部取反 alpha：喂前景遮罩 → 人像透明、原背景不透明
            String joinId = createNode(api, JOIN_ALPHA_TEMPLATE, seq);
            JSONObject joinInputs = nodeInputs(api, joinId);
            joinInputs.set("image", List.of(subjectId, 0));
            joinInputs.set("alpha", List.of(maskId, 0));
            return joinId;
        }
        int color = resolveColor(rawColor, COLOR_WHITE);
        String sizeId = ensureImageSize(api, subjectId, seq);
        String backgroundId = ensureEmptyImage(api, sizeId, color, seq);
        // 遮罩取反 → 1=背景区域（取原图），0=人物区域（保留纯色底 = 人物被抹掉）
        String invertedMaskId = ensureInvertMask(api, maskId, seq);
        return ensureComposite(api, backgroundId, subjectId, invertedMaskId, seq);
    }

    /**
     * 模式③：替换背景 —— 背景图（LoadImage → 尺寸对齐）作底，人像按前景遮罩贴上去。
     */
    private String buildReplaceBackground(
            JSONObject api, ImageEditInput in, String subjectId, String maskId, Seq seq
    ) {
        String backgroundImage = in.getBackgroundImage();
        if (StrUtil.isBlank(backgroundImage)) {
            throw new IllegalArgumentException(
                    "抠图模式 replace_background 必须提供背景图：请传 backgroundImage（ComfyUI input 文件名）"
                            + " 或 backgroundImageAssetId（图片资产 id）");
        }
        String sizeId = ensureImageSize(api, subjectId, seq);

        String backgroundLoadId = createNode(api, LOAD_IMAGE_TEMPLATE, seq);
        nodeInputs(api, backgroundLoadId).set("image", backgroundImage);

        String scaledBackgroundId = createNode(api, SCALE_TEMPLATE, seq);
        JSONObject scaleInputs = nodeInputs(api, scaledBackgroundId);
        scaleInputs.set("image", List.of(backgroundLoadId, 0));
        scaleInputs.set("width", List.of(sizeId, 0));
        scaleInputs.set("height", List.of(sizeId, 1));

        return ensureComposite(api, scaledBackgroundId, subjectId, maskId, seq);
    }

    // ===================== 节点装配助手 =====================

    /**
     * 复用工作流中已有节点（按 classType 唯一命中）；缺失时按模板新建。
     *
     * @param template 缺失时新建用的节点模板；{@code null} 表示该节点必须已存在
     */
    private String resolveOrCreate(JSONObject api, String classType, String template, Seq seq) {
        try {
            return resolveNodeIdByClassType(api, classType);
        } catch (IllegalArgumentException e) {
            if (template == null) {
                throw e;
            }
            return createNode(api, template, seq);
        }
    }

    /** 按模板新建节点，返回新节点号。 */
    private static String createNode(JSONObject api, String template, Seq seq) {
        String id = String.valueOf(seq.next());
        api.set(id, new JSONObject(template));
        return id;
    }

    /** 取节点 inputs（不存在则补建）。 */
    private static JSONObject nodeInputs(JSONObject api, String nodeId) {
        JSONObject node = api.getJSONObject(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("工作流节点不存在: " + nodeId);
        }
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) {
            inputs = new JSONObject();
            node.set("inputs", inputs);
        }
        return inputs;
    }

    /** GetImageSize：拿原图宽高（供纯色底图 / 背景图尺寸对齐）。 */
    private String ensureImageSize(JSONObject api, String imageId, Seq seq) {
        String sizeId = resolveOrCreate(api, NODE_GET_IMAGE_SIZE, GET_IMAGE_SIZE_TEMPLATE, seq);
        nodeInputs(api, sizeId).set("image", List.of(imageId, 0));
        return sizeId;
    }

    /** EmptyImage：纯色底图，尺寸接 GetImageSize 的 width/height 端口（0/1）。 */
    private String ensureEmptyImage(JSONObject api, String sizeId, int rgb, Seq seq) {
        String backgroundId = resolveOrCreate(api, NODE_EMPTY_IMAGE, EMPTY_IMAGE_TEMPLATE, seq);
        JSONObject inputs = nodeInputs(api, backgroundId);
        inputs.set("width", List.of(sizeId, 0));
        inputs.set("height", List.of(sizeId, 1));
        inputs.set("batch_size", 1);
        inputs.set("color", rgb);
        return backgroundId;
    }

    /** InvertMask：遮罩取反（前景 → 背景）。 */
    private String ensureInvertMask(JSONObject api, String maskId, Seq seq) {
        String invertId = resolveOrCreate(api, NODE_INVERT_MASK, INVERT_MASK_TEMPLATE, seq);
        nodeInputs(api, invertId).set("mask", List.of(maskId, 0));
        return invertId;
    }

    /**
     * ImageCompositeMasked：{@code mask=1} 取 source，{@code mask=0} 保留 destination。
     * 尺寸已对齐，故固定 {@code x=y=0}、{@code resize_source=false}。
     */
    private String ensureComposite(
            JSONObject api, String destinationId, String sourceId, String maskId, Seq seq
    ) {
        String compositeId = resolveOrCreate(api, NODE_COMPOSITE, COMPOSITE_TEMPLATE, seq);
        JSONObject inputs = nodeInputs(api, compositeId);
        inputs.set("destination", List.of(destinationId, 0));
        inputs.set("source", List.of(sourceId, 0));
        inputs.set("mask", List.of(maskId, 0));
        inputs.set("x", 0);
        inputs.set("y", 0);
        inputs.set("resize_source", false);
        return compositeId;
    }

    /**
     * 剪掉从根节点（SaveImage）不可达的节点。
     * <p>ComfyUI 本身不会执行不可达节点，但 history.outputs 里若混入 PreviewImage 会把结果解析到
     * temp 图，故这里显式清理，保证「单输出」语义。</p>
     */
    private static void pruneUnreachable(JSONObject api, String rootId) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!reachable.add(id)) {
                continue;
            }
            JSONObject node = api.getJSONObject(id);
            JSONObject inputs = node == null ? null : node.getJSONObject("inputs");
            if (inputs == null) {
                continue;
            }
            for (Object value : inputs.values()) {
                if (value instanceof List<?> ref && !ref.isEmpty()) {
                    String refId = String.valueOf(ref.getFirst());
                    if (api.containsKey(refId)) {
                        stack.push(refId);
                    }
                }
            }
        }
        for (String id : new ArrayList<>(api.keySet())) {
            if (!reachable.contains(id)) {
                api.remove(id);
            }
        }
    }

    /** 动态节点号起点：已有最大数字节点号 + 1（下限 {@value #DYNAMIC_NODE_BASE}）。 */
    private static int nextNodeIdBase(JSONObject api) {
        int max = 0;
        for (String key : api.keySet()) {
            try {
                max = Math.max(max, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // 子图导出的 "19:13" 这类非数字节点号：不参与自增
            }
        }
        return Math.max(max + 1, DYNAMIC_NODE_BASE);
    }

    // ===================== 参数归一化 =====================

    /** 归一化模式（兼容常见别名）；未知值直接报错，避免静默回退到错误模式。 */
    private static String normalizeMode(String mode) {
        if (StrUtil.isBlank(mode)) {
            return MODE_SOLID;
        }
        String m = mode.trim().toLowerCase().replace('-', '_');
        return switch (m) {
            case "solid", "color", "color_background", "solid_background", "remove_background" -> MODE_SOLID;
            case "remove_person", "remove_people", "person_removal", "erase_person", "remove_subject" ->
                    MODE_REMOVE_PERSON;
            case "replace_background", "background", "bg", "compose_background", "image_background" ->
                    MODE_REPLACE_BACKGROUND;
            default -> throw new IllegalArgumentException(
                    "不支持的抠图模式 mode=" + mode + "（可选 solid / remove_person / replace_background）");
        };
    }

    /** 是否为「透明」标记（remove_person 模式缺省值）。 */
    private static boolean isTransparent(String rawColor) {
        return StrUtil.isBlank(rawColor) || TRANSPARENT_TOKENS.contains(rawColor.trim().toLowerCase());
    }

    /**
     * 解析颜色为 0xRRGGBB 整数（EmptyImage.color 语义）：
     * 颜色名 / {@code #RRGGBB} / {@code 0xRRGGBB} / 6 位十六进制 / 十进制。
     *
     * @param fallback 颜色为空时的兜底值
     */
    private static int resolveColor(String rawColor, int fallback) {
        if (StrUtil.isBlank(rawColor)) {
            return fallback;
        }
        String value = rawColor.trim().toLowerCase();
        Integer named = NAMED_COLORS.get(value);
        if (named != null) {
            return named;
        }
        int radix = 10;
        if (value.startsWith("#")) {
            value = value.substring(1);
            radix = 16;
        } else if (value.startsWith("0x")) {
            value = value.substring(2);
            radix = 16;
        } else if (value.length() == 6 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            radix = 16;
        }
        long rgb;
        try {
            rgb = Long.parseLong(value, radix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析背景颜色: " + rawColor + "（支持 #RRGGBB / 0xRRGGBB / 十进制 / 颜色名）");
        }
        if (rgb < 0 || rgb > COLOR_WHITE) {
            throw new IllegalArgumentException("背景颜色超出 0x000000-0xFFFFFF 范围: " + rawColor);
        }
        return (int) rgb;
    }

    /** 动态节点号自增游标。 */
    private static final class Seq {
        private int next;

        private Seq(int next) {
            this.next = next;
        }

        private int next() {
            return next++;
        }
    }
}
