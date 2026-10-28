package io.dramatrix.plugin.wanbernini;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.*;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoReference;
import io.dramatrix.plugin.interfaces.Image2VideoRefPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WAN Bernini 图生视频适配器（参考图/首尾帧）。
 * 对应 workflow: Wan+Bernini+Studio+加速版工作流.json
 * <p>
 * 动态创建 LoadImage + ImageResizeKJv2 节点，连线到 BerniniStudio 节点。
 */
public class WanBerniniAdapter extends AbstractComfyUIPlugin implements Image2VideoRefPlugin {

    private static final String LOAD_IMAGE_TEMPLATE = """
            {
                "inputs": { "image": "" },
                "class_type": "LoadImage",
                "_meta": { "title": "加载图像" }
            }
            """;

    private static final String RESIZE_NODE_TEMPLATE = """
            {
                "inputs": {
                    "image": ["__PLACEHOLDER__", 0],
                    "width": ["227", 0],
                    "height": ["228", 0],
                    "upscale_method": "nearest-exact",
                    "keep_proportion": "stretch",
                    "pad_color": "0, 0, 0",
                    "crop_position": "center",
                    "divisible_by": 2,
                    "device": "cpu"
                },
                "class_type": "ImageResizeKJv2",
                "_meta": { "title": "Resize Image v2" }
            }
            """;

    private static final String DEFAULT_NEGATIVE_PROMPT =
            "色调艳丽，过曝，静态，细节模糊不清，字幕，风格，作品，画作，画面，静止，"
                    + "整体发灰，最差质量，低质量，JPEG压缩残留，丑陋的，残缺的，多余的手指，"
                    + "画得不好的手部，画得不好的脸部，畸形的，毁容的，形态畸形的肢体，手指融合，"
                    + "静止不动的画面，杂乱的背景，三条腿，背景人很多，倒着走";

    // ======================== Sage Attention 加速开关 ========================

    /** 加速节点的 class_type（工作流中的 node 13 / 14） */
    private static final String SAGE_ATTENTION_CLASS = "PathchSageAttentionKJ";

    /** 关闭态写入的 sage_attention 值 */
    private static final String SAGE_ATTENTION_OFF = "disabled";

    /** 开启态写入的 sage_attention 默认值 */
    private static final String SAGE_ATTENTION_ON = "auto";

    /** 配置项名，adapter.json extra 与入参 options 通用（兼容驼峰、下划线两种写法） */
    private static final List<String> SAGE_ATTENTION_KEYS = List.of("sageAttention", "sage_attention");

    /** 参考图数量上限配置项名（兼容驼峰、下划线两种写法） */
    private static final List<String> MAX_REF_IMAGES_KEYS = List.of("maxRefImages", "max_ref_images");

    /** Bernini Studio 默认参考图数量上限 */
    private static final int DEFAULT_MAX_REF_IMAGES = 6;

    /** 内置默认绑定：对应默认 workflow.json 的节点。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "BerniniStudio", "field": "prompt" } },
        { "parameter": "negative_prompt", "target": { "match": "classType", "classType": "BerniniStudio", "field": "negative_prompt" } },
        { "parameter": "length", "target": { "match": "classType", "classType": "BerniniStudio", "field": "length" }, "transform": "int" },
        { "parameter": "fps", "target": { "match": "classType", "classType": "CreateVideo", "field": "fps" }, "transform": "int" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSamplerAdvanced", "field": "noise_seed", "order": 0 }, "transform": "int" },
        { "parameter": "width", "target": { "match": "nodeId", "nodeId": "227", "field": "value" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "nodeId", "nodeId": "228", "field": "value" }, "transform": "int" }
      ],
      "anchor": { "id": "bernini_node", "schema": { "match": "classType", "classType": "BerniniStudio" } },
      "lora": {
        "startNodes": [
          { "match": "nodeId", "nodeId": "5" },
          { "match": "nodeId", "nodeId": "12" }
        ]
      }
    }
    """;

    public WanBerniniAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public int maxRefImages() {
        Object raw = null;
        for (String key : MAX_REF_IMAGES_KEYS) {
            raw = getMetaExtra(key);
            if (raw != null) break;
        }
        if (raw instanceof Number n) {
            return n.intValue() > 0 ? n.intValue() : Integer.MAX_VALUE;
        }
        if (raw != null) {
            try {
                int v = Integer.parseInt(StrUtil.trim(String.valueOf(raw)));
                return v > 0 ? v : Integer.MAX_VALUE;
            } catch (NumberFormatException ignored) {
                // 非法配置回退默认值
            }
        }
        return DEFAULT_MAX_REF_IMAGES;
    }

    @Override
    public JSONObject buildApiJson(Image2VideoRefInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        // 1. 正向提示词 → 绑定 prompt（BerniniStudio）
        if (input.prompt() != null) {
            applyBinding(api, "prompt", input.prompt());
        }

        // 2. 负向提示词 → 绑定 negative_prompt（BerniniStudio），缺省用默认负向词
        if (input.negativePrompt() != null) {
            applyBinding(api, "negative_prompt", input.negativePrompt());
        } else {
            applyBinding(api, "negative_prompt", DEFAULT_NEGATIVE_PROMPT);
        }

        // 3. 宽高 → 绑定 width/height（node 227/228 PrimitiveInt）
        int width = input.getWidth() != null && input.getWidth() > 0 ? input.getWidth() : 848;
        int height = input.getHeight() != null && input.getHeight() > 0 ? input.getHeight() : 480;
        applyBinding(api, "width", width);
        applyBinding(api, "height", height);

        // 4. FPS → 绑定 fps（CreateVideo）
        if (input.getFps() != null && input.getFps() > 0) {
            applyBinding(api, "fps", input.getFps());
        }

        // 5. 总帧数 → 绑定 length（BerniniStudio）
        if (input.getLength() != null && input.getLength() > 0) {
            applyBinding(api, "length", input.getLength());
        } else if (input.getDuration() != null && input.getDuration() > 0) {
            int fps = input.getFps() != null && input.getFps() > 0 ? input.getFps() : 24;
            int frames = toWanLength(input.getDuration(), fps);
            applyBinding(api, "length", frames);
        }

        // 6. Seed → 绑定 seed（noise_seed，空/0 则随机）
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? cn.hutool.core.util.RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs(seed));

        // 7. Sage Attention 加速开关 → node 13 / 14（按 class_type 扫描）
        applySageAttention(api, input);

        // 8. 动态创建 LoadImage + ImageResizeKJv2 节点，连线到 BerniniStudio（anchor 定位）
        List<String> refImageNames = input.getRefImageNames();
        if (CollUtil.isNotEmpty(refImageNames)) {
            int imageCount = Math.min(refImageNames.size(), maxRefImages());
            String berniniId = resolveAnchorNodeId(api);
            JSONObject berniniInputs = api.getJSONObject(berniniId).getJSONObject("inputs");
            int nextNodeId = 1000;

            for (int i = 0; i < imageCount; i++) {
                String imgId = String.valueOf(nextNodeId++);
                String resizeId = String.valueOf(nextNodeId++);

                // LoadImage 节点
                JSONObject imgNode = new JSONObject(LOAD_IMAGE_TEMPLATE);
                imgNode.getJSONObject("inputs").set("image", refImageNames.get(i));
                api.set(imgId, imgNode);

                // ImageResizeKJv2 节点
                JSONObject resizeNode = new JSONObject(RESIZE_NODE_TEMPLATE);
                resizeNode.getJSONObject("inputs").set("image", List.of(imgId, 0));
                api.set(resizeId, resizeNode);

                // 连线: bernini.image0 / image1 / ...
                berniniInputs.set("image" + i, List.of(resizeId, 0));
            }
        }

        // 9. LoRA → 原生 LoraLoaderModelOnly 串联（无 LoRA 自动跳过）
        applyLorasFromBinding(api, input.getLoras());

        // 10. 模型槽注入：高/低 DiT、VAE、CLIP（无 modelSlots 时 no-op）
        applyModelSlots(api);

        return api;
    }

    // ======================== Sage Attention ========================

    /**
     * 按参数统一开关工作流中所有 PathchSageAttentionKJ 节点（node 13 / 14）。
     * <p>
     * 开启：{@code sage_attention = auto}（或自定义 kernel），{@code allow_compile = true}<br>
     * 关闭：{@code sage_attention = disabled}，{@code allow_compile = false}
     * <p>
     * 按 class_type 扫描而非写死节点号，工作流增删加速节点时无需改代码。
     */
    private void applySageAttention(JSONObject api, Image2VideoRefInput input) {
        String mode = resolveSageAttentionMode(input);
        boolean enabled = !SAGE_ATTENTION_OFF.equals(mode);

        for (String nodeId : api.keySet()) {
            JSONObject node = api.getJSONObject(nodeId);
            if (node == null || !SAGE_ATTENTION_CLASS.equals(node.getStr("class_type"))) {
                continue;
            }
            JSONObject inputs = node.getJSONObject("inputs");
            if (inputs == null) {
                continue;
            }
            inputs.set("sage_attention", mode);
            inputs.set("allow_compile", enabled);
        }
    }

    /**
     * 解析 sage_attention 取值，优先级：
     * input.options（单次请求覆盖） → adapter.json 的 extra（静态配置） → 默认开启。
     */
    private String resolveSageAttentionMode(Image2VideoRefInput input) {
        Object raw = null;
        if (input != null) {
            for (String key : SAGE_ATTENTION_KEYS) {
                raw = input.option(key);
                if (raw != null) break;
            }
        }
        if (raw == null) {
            for (String key : SAGE_ATTENTION_KEYS) {
                raw = getMetaExtra(key);
                if (raw != null) break;
            }
        }
        return toSageAttentionMode(raw);
    }

    /**
     * 参数值归一化：布尔/开关字符串 → auto / disabled；其余原样透传（自定义 kernel）。
     */
    private static String toSageAttentionMode(Object raw) {
        if (raw == null) return SAGE_ATTENTION_ON;
        if (raw instanceof Boolean b) return b ? SAGE_ATTENTION_ON : SAGE_ATTENTION_OFF;

        String v = StrUtil.trim(String.valueOf(raw));
        if (StrUtil.isBlank(v)) return SAGE_ATTENTION_ON;

        return switch (v.toLowerCase()) {
            case "false", "off", "no", "0", "disable", "disabled", "none" -> SAGE_ATTENTION_OFF;
            case "true", "on", "yes", "1", "enable", "enabled", "auto" -> SAGE_ATTENTION_ON;
            // 例如 sageattn_qk_int8_pv_fp16_cuda，直接透传给 ComfyUI 节点
            default -> v;
        };
    }

    /**
     * 秒 → WAN 帧数换算
     */
    private static int toWanLength(double duration, int fps) {
        int frames = (int) Math.round(duration * fps);
        if (frames <= 1) return 5;
        return 1 + (int) Math.ceil((frames - 1) / 4.0) * 4;
    }

    // ======================== Prompt 构建 ========================

    /**
     * 从统一视觉蓝图拼接 WAN 模型最终 prompt。
     * <p>
     * 流程：读取本镜蓝图（{@code beats[]} 即逐拍段落）→ 角色/道具 ID 替换为 {@code imageN}
     * → 多角色身份声明 → 追加风格提示 + 防抖后缀。
     * <p>
     * 改造前本方法解析 event 级 {@code motions[]} 大 JSON 并用 {@code filterShotId} 过滤，
     * 现在蓝图进入即本镜的，无需过滤。
     */
    @Override
    public VideoPromptResult buildPrompt(VideoContext videoContext) {
        ShotVisualPlan plan = videoContext.getVisualPlan();
        if (plan == null || CollUtil.isEmpty(plan.getBeats())) {
            return null; // 无蓝图，由调用方降级
        }
        VideoReference reference = videoContext.getReference();
        List<VideoReference.Ref> characterRefs = reference == null ? null : reference.getCharacters();
        List<VideoReference.Ref> assetRefs = reference == null ? null : reference.getAssets();
        VideoReference.Ref sceneRef = reference == null ? null : reference.getScene();
        // 风格：顶层 model_style（规划层产出，随内容走）优先；缺省回落作品级 stylePrompt
        String stylePrompt = StrUtil.blankToDefault(StrUtil.trim(plan.getModelStyle()),
                videoContext.getStylePrompt());

        // 1. 分类参考图 → imageN 编号（与提交给 ComfyUI 的列表顺序一致）：
        //    角色图 image0..N-1 → asset 图 imageN..N+K-1 → 场景图 image{N+K} → 首帧图
        Map<String, String> charImageRefMap = new LinkedHashMap<>();
        Map<String, String> assetImageRefMap = new LinkedHashMap<>();
        int imageIndex = 0;
        if (characterRefs != null) {
            for (VideoReference.Ref ref : characterRefs) {
                if (ref != null && StrUtil.isNotBlank(ref.getId())) {
                    charImageRefMap.put(ref.getId(), "image" + imageIndex++);
                }
            }
        }
        if (assetRefs != null) {
            for (VideoReference.Ref ref : assetRefs) {
                if (ref != null && StrUtil.isNotBlank(ref.getId())) {
                    assetImageRefMap.put(ref.getId(), "image" + imageIndex++);
                }
            }
        }
        int imageRefCount = imageIndex;

        // 2. 组装：scene → beats → camera
        StringBuilder sb = new StringBuilder();

        // 场景
        String sceneDesc = StrUtil.trim(plan.getSceneDesc());
        if (StrUtil.isNotBlank(sceneDesc)) {
            boolean hasSceneImage = sceneRef != null && StrUtil.isNotBlank(sceneRef.getImageUrl());
            if (!hasSceneImage) {
                sb.append("In the scene");
            } else {
                sb.append("In the scene of image").append(imageRefCount);
            }
            sb.append(", ").append(sceneDesc).append(". ");
            if (hasSceneImage) {
                // 场景图只做结构参考：其中的光柱/强逆光会被逐帧重绘，表现为闪，故光照一律以 prompt 为准
                sb.append("Use image").append(imageRefCount)
                        .append(" only for the scene's layout, architecture, materials and props — do not reproduce ")
                        .append("the light shafts, hotspots or shadows visible in it; keep the lighting steady and even.");
            }
            sb.append("\n\n");
        }

        // 逐拍段落
        String overallCamera = null;
        for (ShotVisualPlan.Beat beat : plan.getBeats()) {
            if (beat == null) {
                continue;
            }
            String time = beat.getTime();
            String desc = beat.getDesc();
            String cam = StrUtil.blankToDefault(beat.getCameraMotion(), beat.getCamera());
            if (overallCamera == null && StrUtil.isNotBlank(cam)) {
                overallCamera = cam;
            }

            sb.append(time).append(": ");

            // 该段的角色位置
            if (CollUtil.isNotEmpty(beat.getCharacters()) && !charImageRefMap.isEmpty()) {
                List<String> otherDescs = new ArrayList<>();
                for (ShotVisualPlan.BeatCharacter ch : beat.getCharacters()) {
                    String charId = ch.getCharacterId();
                    String charDesc = ch.getDesc();
                    String imageRef = charImageRefMap.get(charId);
                    if (imageRef != null) {
                        sb.append("The character from ").append(imageRef);
                        if (StrUtil.isNotBlank(charDesc)) {
                            sb.append(" ").append(charDesc);
                        }
                        sb.append(". ");
                    } else if (StrUtil.isNotBlank(charDesc)) {
                        otherDescs.add(charDesc);
                    }
                }
                if (!otherDescs.isEmpty()) {
                    sb.append("Background figures: ").append(String.join("; ", otherDescs)).append(". ");
                }
            }

            // 该段的道具（资产）参考：有参考图的道具绑定 imageN，无参考图的仅文本描述
            if (CollUtil.isNotEmpty(beat.getProps())) {
                for (ShotVisualPlan.BeatProp p : beat.getProps()) {
                    String assetId = p.getAssetId();
                    String pDesc = p.getDesc();
                    String imageRef = assetImageRefMap.get(assetId);
                    String keyword = findDesc(assetRefs, assetId);
                    if (imageRef != null) {
                        sb.append("The object from ").append(imageRef);
                        if (StrUtil.isNotBlank(keyword)) {
                            sb.append(", ").append(keyword);
                        }
                        if (StrUtil.isNotBlank(pDesc)) {
                            sb.append(", ").append(pDesc);
                        }
                        sb.append(". ");
                    } else if (StrUtil.isNotBlank(pDesc)) {
                        sb.append(pDesc).append(". ");
                    }
                }
            }

            if (StrUtil.isNotBlank(desc)) {
                sb.append(desc).append(". ");
            }
            sb.append("\n");
        }

        // 镜头：优先逐拍机位；逐拍都没写才回退本镜权威机位（分镜层）
        if (StrUtil.isNotBlank(overallCamera)) {
            sb.append(overallCamera).append(". ");
        } else {
            String shotCamera = formatCamera(plan.getCamera());
            if (StrUtil.isNotBlank(shotCamera)) {
                sb.append(shotCamera).append(". ");
            }
        }

        String prompt = sb.toString().trim();
        if (StrUtil.isBlank(prompt)) return null;

        // 4. 角色 ID → image_ref 替换
        for (Map.Entry<String, String> entry : charImageRefMap.entrySet()) {
            prompt = prompt.replace(entry.getKey(), entry.getValue());
        }

        // 5. 多角色身份声明（2+ 个有图片角色时，在 prompt 开头声明防止身份融合）
        if (charImageRefMap.size() > 1) {
            List<Map.Entry<String, String>> imageEntries = new ArrayList<>(charImageRefMap.entrySet());
            StringBuilder identityDecl = new StringBuilder();
            identityDecl.append(imageEntries.size()).append(" distinct characters: ");
            int idx = 0;
            for (Map.Entry<String, String> entry : imageEntries) {
                String imageRef = entry.getValue();
                String keyword = findDesc(characterRefs, entry.getKey());
                if (idx > 0) identityDecl.append(", ");
                identityDecl.append(imageRef);
                if (StrUtil.isNotBlank(keyword)) {
                    identityDecl.append(" is ").append(keyword);
                }
                idx++;
            }
            identityDecl.append(". ");
            prompt = identityDecl + prompt;
        }

        // 6. 追加风格提示 + 防抖
        StringBuilder result = new StringBuilder(prompt);
        if (StrUtil.isNotBlank(stylePrompt)) {
            result.append(".\n\n").append(stylePrompt);
        }
        result.append(".\n\nsteady shot, no camera shake, smooth motion.");
        return VideoPromptResult.builder()
                .prompt(result.toString())
                .reference(reference)
                .build();
    }

    /**
     * 本镜权威机位（分镜层 {@code Shot.camera}）→ 英文短语，仅在逐拍都未写机位时兜底。
     */
    private static String formatCamera(Map<String, Object> camera) {
        if (camera == null || camera.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String key : List.of("shot_type", "angle", "motion")) {
            Object v = camera.get(key);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                parts.add(String.valueOf(v));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** 从参考项列表中按 id 查找锚定描述。 */
    private static String findDesc(List<VideoReference.Ref> refs, String id) {
        if (refs == null || StrUtil.isBlank(id)) {
            return null;
        }
        for (VideoReference.Ref ref : refs) {
            if (ref != null && id.equals(ref.getId())) {
                return ref.getDesc();
            }
        }
        return null;
    }
}
