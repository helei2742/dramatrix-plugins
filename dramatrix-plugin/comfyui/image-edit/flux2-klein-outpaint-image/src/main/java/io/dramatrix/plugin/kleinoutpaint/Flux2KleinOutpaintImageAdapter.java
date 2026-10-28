package io.dramatrix.plugin.kleinoutpaint;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.ImageEditInput;
import io.dramatrix.plugin.dto.LoraBundle;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.ImageEditPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flux.2 Klein 9B 扩图适配器（对应工作流 {@code 扩图工作流-Flux-2-klein-9b.json}）。
 *
 * <p>与「无提示词」的编辑型插件（SeedVR2 超清 / BiRefNet 抠图）不同，扩图<b>需要提示词参与</b>：
 * 工作流先把扩边区用填充色画到图上，再由提示词指代该色（默认 {@code 去除绿色部分}），
 * 模型据「参考潜变量里的原图 + 色块」把画面延展开。因此本插件虽然实现
 * {@link ImageEditPlugin}（不带 {@code buildPrompt}，不解析 LLM 输出），
 * 但 {@code prompt} 是一等参数，必须显式传入或沿用工作流默认值。</p>
 *
 * <p>静态链路（节点号与参考工作流一致，便于对照）：</p>
 * <pre>
 * LoadImage → ImageScaleToTotalPixels（按总像素/放大倍数缩放）
 *   → ImagePadForOutpaint（左/上/右/下扩边 + feathering，输出 IMAGE + MASK）
 *   → DrawMaskOnImage（把扩边 MASK 以填充色画到图上，供提示词指代）
 *      ├→ GetImageSize ─┬→ Flux2Scheduler(width,height, steps)
 *      │                └→ EmptyFlux2LatentImage(width,height) → SamplerCustomAdvanced.latent_image
 *      └→ VAEEncode → ReferenceLatent（正/负两路，注入参考图）
 * CLIPTextEncode(prompt) → ConditioningZeroOut → 负向 ReferenceLatent
 * UNETLoader → CFGGuider(positive/negative) → SamplerCustomAdvanced
 * KSamplerSelect + RandomNoise + Flux2Scheduler → SamplerCustomAdvanced
 *   → VAEDecode → ColorMatch（色彩对齐原图）→ SaveImage
 * </pre>
 *
 * <p><b>LoRA 是动态注入的</b>：工作流里<b>不预置任何 LoRA 节点</b>。调用方经既有
 * {@code loraNames} 通道传入后，{@link #buildApiJson(ImageEditInput)}
 * 用 {@code applyLorasFromBinding} 按 binding 的 {@code lora.startNodes}（UNETLoader）
 * 在 UNETLoader 与 CFGGuider 之间串联 {@code LoraLoaderModelOnly}；空列表即完全不改动链路。</p>
 *
 * <p><b>参数分三层</b>（优先级由低到高）：</p>
 * <ol>
 *   <li>工作流自带默认值（workflow.json）；</li>
 *   <li><b>可调参数</b>（adapter.json 的 {@code extra.paramSchema}，前端「额外参数」弹层按它渲染）：
 *       缩放率 {@code megapixels} / 四边扩边 {@code padLeft·padTop·padRight·padBottom} / 羽化
 *       {@code padFeathering} / 输出宽高 {@code width·height}（留空跟随）/ 填充色 {@code maskColor} /
 *       扩图指令 {@code prompt} / {@code steps} / {@code cfg} / {@code samplerName} / {@code seed} /
 *       缩放插值 {@code upscaleMethod} / 色彩校正 {@code colorMatchMethod}·{@code colorMatchStrength}；
 *       缺省值<b>只</b>声明在 paramSchema（唯一出处），适配器经 {@link #numParam}/{@link #intParam}/
 *       {@link #strParam} 兜底，不在 {@code extra} 里再抄一份（抄两份就会「前端播种的是 A、适配器兜底的是 B」）；</li>
 *   <li>请求参数（{@link ImageEditInput}）：与上面同一批键的 <b>typed 字段</b>（Agent 动作走这条，
 *       顶层显式传参优先于 options）。</li>
 * </ol>
 * <p>插件<b>作者级</b>配置仍留在 {@code extra} 顶层（<b>不进</b> paramSchema）：{@code clipName}
 * （文本编码器，模型槽优先于它）、{@code resolutionSteps}（尺寸对齐步长，1 = 不对齐）——
 * 这些不是用户生成时该调的参数。</p>
 * <p>装配顺序（后者覆盖前者）：工作流默认 → paramSchema 缺省 / {@code extra} → 模型槽（UI 选择，
 * 含持久化 {@code model.selection.json}）→ 请求参数。即「请求参数 &gt; 模型槽 &gt;
 * 插件默认 &gt; 工作流默认」。</p>
 *
 * @author helei
 */
@Slf4j
public class Flux2KleinOutpaintImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    /**
     * 内置默认绑定：与插件目录下的 workflow.binding.json 保持一致。
     * 用户改写 binding 文件后以文件为准（{@link #ensureDefaultBinding} 只在未配置时生效）。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "image", "target": { "match": "classType", "classType": "LoadImage", "field": "image" } },
        { "parameter": "megapixels", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "megapixels" }, "transform": "float" },
        { "parameter": "upscale_method", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "upscale_method" } },
        { "parameter": "resolution_steps", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "resolution_steps" }, "transform": "int" },
        { "parameter": "pad_left", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "left" }, "transform": "int" },
        { "parameter": "pad_top", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "top" }, "transform": "int" },
        { "parameter": "pad_right", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "right" }, "transform": "int" },
        { "parameter": "pad_bottom", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "bottom" }, "transform": "int" },
        { "parameter": "pad_feathering", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "feathering" }, "transform": "int" },
        { "parameter": "mask_color", "target": { "match": "classType", "classType": "DrawMaskOnImage", "field": "color" } },
        { "parameter": "prompt", "target": { "match": "classType", "classType": "CLIPTextEncode", "field": "text" } },
        { "parameter": "steps", "target": { "match": "classType", "classType": "Flux2Scheduler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "CFGGuider", "field": "cfg" }, "transform": "float" },
        { "parameter": "sampler_name", "target": { "match": "classType", "classType": "KSamplerSelect", "field": "sampler_name" } },
        { "parameter": "seed", "target": { "match": "classType", "classType": "RandomNoise", "field": "noise_seed" }, "transform": "int" },
        { "parameter": "unet_name", "target": { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } },
        { "parameter": "clip_name", "target": { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" } },
        { "parameter": "vae_name", "target": { "match": "classType", "classType": "VAELoader", "field": "vae_name" } },
        { "parameter": "color_match_method", "target": { "match": "classType", "classType": "ColorMatch", "field": "method" } },
        { "parameter": "color_match_strength", "target": { "match": "classType", "classType": "ColorMatch", "field": "strength" }, "transform": "float" }
      ],
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      },
      "modelSlots": [
        {
          "group": "flux2-klein-9b-diffusion",
          "default": "flux-2-klein-9b-fp8.safetensors",
          "targets": [ { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } ]
        },
        {
          "group": "flux2-klein-clip",
          "default": "qwen_3_8b.safetensors",
          "targets": [ { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" } ]
        },
        {
          "group": "flux2-klein-vae",
          "default": "flux2-vae.safetensors",
          "targets": [ { "match": "classType", "classType": "VAELoader", "field": "vae_name" } ]
        }
      ]
    }
    """;

    private static final String NODE_PAD = "ImagePadForOutpaint";
    private static final String NODE_SAVE = "SaveImage";

    // ===================== adapter.json extra / paramSchema 配置键 =====================

    /** adapter.json extra 顶层：缩放尺寸对齐步长（作者级，不进 paramSchema） */
    private static final String EXTRA_RESOLUTION_STEPS = "resolutionSteps";
    /** adapter.json extra 顶层：文本编码器文件名（模型槽优先于它） */
    private static final String EXTRA_CLIP_NAME = "clipName";

    /** paramSchema 私有参数 key：缩放插值算法 */
    private static final String PARAM_UPSCALE_METHOD = "upscaleMethod";
    /** paramSchema 私有参数 key：输出色彩校正算法 */
    private static final String PARAM_COLOR_MATCH_METHOD = "colorMatchMethod";
    /** paramSchema 私有参数 key：输出色彩校正强度 */
    private static final String PARAM_COLOR_MATCH_STRENGTH = "colorMatchStrength";

    /**
     * {@code ImageScaleToTotalPixels.upscale_method} 的合法取值（= ComfyUI 核心节点的 COMBO 选项，
     * 顺序与节点一致）。⚠️ COMBO 值**原样透传**给 ComfyUI，非法值会让整条 prompt 被拒收 ——
     * 故这里做白名单校验，非法值回退缺省并 WARN。
     */
    private static final Set<String> UPSCALE_METHODS =
            Set.of("nearest-exact", "bilinear", "area", "bicubic", "lanczos");
    /** {@code ColorMatch.method} 的合法取值（= KJNodes 的 COMBO 选项，顺序与节点一致）。 */
    private static final Set<String> COLOR_MATCH_METHODS =
            Set.of("mkl", "hm", "reinhard", "mvgd", "hm-mvgd-hm", "hm-mkl-hm");

    // ===================== 工作流自带默认（extra / paramSchema 缺失时的兜底，与 workflow.json 一致） =====================

    private static final String FALLBACK_UPSCALE_METHOD = "lanczos";
    private static final int FALLBACK_RESOLUTION_STEPS = 1;
    private static final String FALLBACK_CLIP_NAME = "qwen_3_8b.safetensors";
    private static final String FALLBACK_COLOR_MATCH_METHOD = "mkl";
    private static final double FALLBACK_COLOR_MATCH_STRENGTH = 0.4;

    public Flux2KleinOutpaintImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    /** 无 LoRA 重载：等价于 {@code buildApiJson(input, null, ctx)}。 */
    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        ImageEditInput input = in == null ? new ImageEditInput() : in;

        // LoRA 已收拢进 input（服务端由 loraNames 通道解析回填）
        List<LoraBundle> loras = input.getLoras() == null ? List.of() : input.getLoras();

        // 1. 输入图（落点由绑定声明，缺省保留工作流自带值）
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }

        // 2. 可调参数（paramSchema 声明驱动）+ 作者级配置（adapter.json extra）
        //    取值链 = 请求侧 options（前端「额外参数」弹层提交）→ paramSchema.defaultValue → 硬编码保底。
        //    ⚠️ 缺省值只在 paramSchema 里写一份，适配器不另设 extra 同名项（抄两份必然漂移）。
        applyBinding(api, "upscale_method", resolveEnum(
                input, PARAM_UPSCALE_METHOD, "upscale_method", UPSCALE_METHODS, FALLBACK_UPSCALE_METHOD));
        applyBinding(api, "color_match_method", resolveEnum(
                input, PARAM_COLOR_MATCH_METHOD, "color_match_method", COLOR_MATCH_METHODS,
                FALLBACK_COLOR_MATCH_METHOD));
        applyBinding(api, "color_match_strength", resolveDouble(
                PARAM_COLOR_MATCH_STRENGTH,
                optDouble(input, PARAM_COLOR_MATCH_STRENGTH, "color_match_strength"),
                FALLBACK_COLOR_MATCH_STRENGTH));

        // 作者级配置：这两项不进 paramSchema（用户不该调）
        applyBinding(api, "resolution_steps", extraInt(EXTRA_RESOLUTION_STEPS, FALLBACK_RESOLUTION_STEPS));
        applyBinding(api, "clip_name", extraStr(EXTRA_CLIP_NAME, FALLBACK_CLIP_NAME));

        // 3. 模型槽（UI 选择 / 持久化 model.selection.json）压过第 2 步的 extra 默认；
        //    无 modelSlots 或该组无变体时 no-op，此时 extra.clipName 作为兜底生效
        applyModelSlots(api);

        // 4. 缩放（缩放率 / 目标总像素）
        Double megapixels = numParam(input, "megapixels", input.getMegapixels());
        if (megapixels != null) {
            applyBinding(api, "megapixels", megapixels);
        }

        // 5. 扩充画板：左 / 上 / 右 / 下 + 羽化
        applyIntBinding(api, "pad_left", intParam(input, "padLeft", "pad_left", input.getPadLeft()));
        applyIntBinding(api, "pad_top", intParam(input, "padTop", "pad_top", input.getPadTop()));
        applyIntBinding(api, "pad_right", intParam(input, "padRight", "pad_right", input.getPadRight()));
        applyIntBinding(api, "pad_bottom", intParam(input, "padBottom", "pad_bottom", input.getPadBottom()));
        applyIntBinding(api, "pad_feathering",
                intParam(input, "padFeathering", "pad_feathering", input.getPadFeathering()));

        // 5.1 输出画布宽高（**opt-in 覆盖**）：留空 = 保留工作流里「GetImageSize(扩边后图) → 画布」的连线，
        //     即画布尺寸 = 缩放后尺寸 + 四边扩边；显式给了才把连线换成字面量。
        //     ⚠️ 这里不走绑定：一个参数要同时落 Flux2Scheduler 与 EmptyFlux2LatentImage 两个节点的
        //     width/height（共 4 处），而绑定是「一个参数 → 一个节点字段」，故在 Java 侧直接改图。
        overrideCanvasSize(api, intParam(input, "width", "width", null),
                intParam(input, "height", "height", null));

        // 6. 填充色（画在扩边区，供 prompt 指代）——格式非法会静默画错颜色，这里提前拦
        String maskColor = strParam(input, "maskColor", "mask_color", input.getMaskColor());
        if (StrUtil.isNotBlank(maskColor)) {
            String color = maskColor.trim();
            validateMaskColor(color);
            applyBinding(api, "mask_color", color);
        }

        // 7. 提示词（扩图指令，须指代填充色）
        String prompt = strParam(input, "prompt", "prompt", input.getPrompt());
        if (StrUtil.isNotBlank(prompt)) {
            applyBinding(api, "prompt", prompt.trim());
        }

        // 8. 采样常规参数
        applyIntBinding(api, "steps", intParam(input, "steps", "steps", input.getSteps()));
        Double cfg = numParam(input, "cfg", input.getCfg());
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }
        String samplerName = strParam(input, "samplerName", "sampler_name", input.getSamplerName());
        if (StrUtil.isNotBlank(samplerName)) {
            applyBinding(api, "sampler_name", samplerName);
        }

        // 9. 模型文件（请求级显式指定，优先级高于模型槽）
        if (StrUtil.isNotBlank(input.getUnetName())) {
            applyBinding(api, "unet_name", input.getUnetName());
        }
        if (StrUtil.isNotBlank(input.getVaeName())) {
            applyBinding(api, "vae_name", input.getVaeName());
        }

        // 10. LoRA 动态串联：按 binding 的 lora.startNodes（UNETLoader）在 UNETLoader 与
        //     CFGGuider 之间插入 LoraLoaderModelOnly；空列表完全不改链路（工作流无 LoRA 节点）
        applyLorasFromBinding(api, loras);

        // 11. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = intParam(input, "seed", "seed", input.getSeed());
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 12. 校验扩边（读到实际生效值，含工作流默认），避免交付「等于没扩」的结果
        validatePadding(api);

        // 13. 剪掉不可达节点，保证 SaveImage 单输出语义（history.outputs 取第一个含 images 的节点）
        pruneUnreachable(api, resolveNodeIdByClassType(api, NODE_SAVE));

        return api;
    }

    // ===================== paramSchema 参数取值（typed → options → paramSchema → 保底） =====================

    /**
     * 数值型生成参数的取值链：<b>typed 字段</b>（顶层请求参数，Agent 动作走这条）→
     * <b>options</b>（前端「额外参数」弹层按 paramSchema 提交的 pluginParams）→
     * <b>paramSchema.defaultValue</b>（用户可见默认值的唯一出处）。
     *
     * <p>★ 为什么 typed 优先：顶层参数是「显式请求」，而 options 里可能混着前端播种的 paramSchema 默认值；
     * 顶层显式传了就以它为准。前端面板这条路 typed 通常为 null（这些键不在 Handler 的白名单里），
     * 于是自然落到 options。</p>
     */
    private Double numParam(ImageEditInput input, String key, Double typed) {
        if (typed != null) {
            return typed;
        }
        Double v = optDouble(input, key, camelToSnake(key));
        return v != null ? v : paramSchemaDouble(key);
    }

    /** {@link #numParam} 的整数版（options 里可能是小数，取整）。 */
    private Integer intParam(ImageEditInput input, String key, String alias, Integer typed) {
        if (typed != null) {
            return typed;
        }
        Double v = optDouble(input, key, alias);
        // ⚠️ 必须写成显式 if，**不要**合成 `v != null ? v.intValue() : paramSchemaInt(key)`：
        //   两个分支一个是 int、一个是 Integer ⇒ 条件表达式按**二元数值提升**取 int 为结果类型
        //   ⇒ paramSchemaInt 返回 null 时会在「装箱成 Integer」之前先被拆箱，直接 NPE。
        //   而 paramSchema 里「留空跟随」的参数（width / height）本来就没有 defaultValue，必然走这一支。
        if (v != null) {
            return v.intValue();
        }
        return paramSchemaInt(key);
    }

    /** {@link #numParam} 的字符串版；三处都为空时返回 {@code null}（= 保留工作流默认）。 */
    private String strParam(ImageEditInput input, String key, String alias, String typed) {
        if (StrUtil.isNotBlank(typed)) {
            return typed;
        }
        String v = optStr(input, key, alias);
        return v != null ? v : paramSchemaString(key);
    }

    /** 值为 {@code null} 时跳过绑定（= 保留工作流自带值）。 */
    private void applyIntBinding(JSONObject api, String parameter, Integer value) {
        if (value != null) {
            applyBinding(api, parameter, value);
        }
    }

    /**
     * 覆盖采样画布宽高：把 {@code Flux2Scheduler} 与 {@code EmptyFlux2LatentImage} 的 width/height
     * 从工作流的 {@code GetImageSize} 连线改成字面量。
     *
     * <p>⚠️ 不走绑定：一个「输出宽高」要同时落 <b>4 处</b>（两个节点 × width/height），
     * 而绑定机制是「一个参数 → 一个节点字段」。只给单个方向时另一方向仍跟随扩边结果。</p>
     *
     * <p>⚠️ 语义提醒：留空才是推荐用法（画布 = 缩放后尺寸 + 四边扩边，与参考图严格同尺寸）；
     * 显式指定会让画布与参考图尺寸不一致 ⇒ 扩边内容不再按扩边位置对齐。</p>
     */
    private void overrideCanvasSize(JSONObject api, Integer width, Integer height) {
        if (width == null && height == null) {
            return;
        }
        for (String classType : List.of("Flux2Scheduler", "EmptyFlux2LatentImage")) {
            String id = tryResolveClassType(api, classType);
            if (id == null) {
                log.warn("[KleinOutpaint] 工作流缺少 {}，输出宽高无法覆盖该节点", classType);
                continue;
            }
            JSONObject inputs = api.getJSONObject(id).getJSONObject("inputs");
            if (inputs == null) {
                continue;
            }
            if (width != null) {
                inputs.set("width", width);
            }
            if (height != null) {
                inputs.set("height", height);
            }
        }
        log.info("[KleinOutpaint] 显式覆盖输出画布尺寸: width={}, height={}（留空则跟随「缩放+扩边」结果）",
                width, height);
    }

    /** 按 classType 定位节点号；未命中返回 {@code null}（不抛，供可选覆盖探测用）。 */
    private static String tryResolveClassType(JSONObject api, String classType) {
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node != null && classType.equals(node.getStr("class_type"))) {
                return id;
            }
        }
        return null;
    }

    /** camelCase → snake_case（paramSchema key 与节点字段名两种写法都容忍）。 */
    private static String camelToSnake(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 枚举型 paramSchema 参数：走基类的三级取值链（{@link #resolveStr}），再对结果做<b>白名单校验</b>。
     *
     * <p>⚠️ 必须校验：{@code ImageScaleToTotalPixels.upscale_method} / {@code ColorMatch.method}
     * 都是 ComfyUI 的 COMBO 输入，值会<b>原样透传</b> —— 非法值不是「静默用默认」，而是让
     * ComfyUI 把<b>整条 prompt 拒收</b>（报 validation failed）。故非法值在这里回退缺省并 WARN。</p>
     *
     * @param alias snake_case 别名（前端/Agent 可能按节点字段名传，两种写法都容忍）
     */
    private String resolveEnum(
            ImageEditInput input, String key, String alias, Set<String> allowed, String fallback
    ) {
        String v = resolveStr(key, optStr(input, key, alias), fallback);
        if (allowed.contains(v)) {
            return v;
        }
        log.warn("[KleinOutpaint] {}={} 不是合法取值（仅支持 {}），已回退 {}；"
                        + "否则 ComfyUI 会因 COMBO 校验失败拒收整条 prompt",
                key, v, allowed, fallback);
        return fallback;
    }

    /** 从 ImageEditInput.options（前端 paramSchema 弹层的私有参数）里取首个非空字符串值。 */
    private static String optStr(ImageEditInput input, String... keys) {
        Object v = optRaw(input, keys);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return StrUtil.isBlank(s) ? null : s;
    }

    /** 从 ImageEditInput.options 里取首个可解析为小数的值（前端可能传 Number 或字符串）。 */
    private static Double optDouble(ImageEditInput input, String... keys) {
        Object v = optRaw(input, keys);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** options 里按候选 key 顺序取首个非空值（前端可能传 camelCase 或 snake_case）。 */
    private static Object optRaw(ImageEditInput input, String... keys) {
        Map<String, Object> opts = input.getOptions();
        if (opts == null || opts.isEmpty()) {
            return null;
        }
        for (String k : keys) {
            if (k == null) {
                continue;
            }
            Object v = opts.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return v;
            }
        }
        return null;
    }

    // ===================== adapter.json extra 读取（作者级配置） =====================

    /** 读 adapter.json extra 的字符串配置；缺失/空白时返回 {@code fallback}。 */
    @SuppressWarnings("SameParameterValue")
    private String extraStr(String key, String fallback) {
        Object v = getMetaExtra(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return StrUtil.isBlank(s) ? fallback : s;
    }

    /** 读 adapter.json extra 的整数配置；缺失/非法时返回 {@code fallback}。 */
    private int extraInt(String key, int fallback) {
        Object v = getMetaExtra(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && StrUtil.isNotBlank(s)) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落回工作流默认
            }
        }
        return fallback;
    }

    // ===================== 校验 / 清理 =====================

    /**
     * 扩边合法性：各方向 ≥ 0，且至少一个方向 &gt; 0（全 0 等于没扩图，属于参数误用，
     * 提前报错比让 ComfyUI 跑一遍再产出「原图」更容易排查）。
     */
    private static void validatePadding(JSONObject api) {
        String padId;
        try {
            padId = resolveNodeIdByClassType(api, NODE_PAD);
        } catch (IllegalArgumentException e) {
            return;
        }
        JSONObject node = api.getJSONObject(padId);
        JSONObject inputs = node == null ? null : node.getJSONObject("inputs");
        if (inputs == null) {
            return;
        }
        int left = padValue(inputs, "left");
        int top = padValue(inputs, "top");
        int right = padValue(inputs, "right");
        int bottom = padValue(inputs, "bottom");
        if (left < 0 || top < 0 || right < 0 || bottom < 0) {
            throw new IllegalArgumentException(
                    "扩图四边不能为负数：left=" + left + ", top=" + top + ", right=" + right + ", bottom=" + bottom);
        }
        if (left + top + right + bottom <= 0) {
            throw new IllegalArgumentException("扩图至少需要一个方向的扩边大于 0（当前 left/top/right/bottom 全为 0）");
        }
    }

    /** 读取扩边字段（缺失/非数字按 0 计）。 */
    private static int padValue(JSONObject inputs, String field) {
        try {
            Integer v = inputs.getInt(field);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 校验填充色格式（{@code DrawMaskOnImage.color} 的解析规则）：
     * {@code #RRGGBB} / {@code #RRGGBBAA}，或 3~4 个逗号分隔的数值（0-255 或 0.0-1.0）。
     * <p>该节点对无法解析的字符串会「静默」退化成错误颜色（不报错），所以在此显式拦截。</p>
     */
    private static void validateMaskColor(String color) {
        String msg = "填充色格式不合法: " + color + "（支持 #RRGGBB / #RRGGBBAA，或 \"0, 255, 0\" 形式的 RGB(A) 数值）";
        if (color.startsWith("#")) {
            String hex = color.substring(1);
            if ((hex.length() == 6 || hex.length() == 8) && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return;
            }
            throw new IllegalArgumentException(msg);
        }
        String[] parts = color.split(",");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException(msg);
        }
        for (String p : parts) {
            if (StrUtil.isBlank(p)) {
                throw new IllegalArgumentException(msg);
            }
            boolean ok = p.trim().chars().allMatch(c -> Character.isDigit(c) || c == '.');
            if (!ok) {
                throw new IllegalArgumentException(msg);
            }
        }
    }

    /**
     * 剪掉从根节点（SaveImage）不可达的节点。
     * <p>ComfyUI 不执行不可达节点，但 history.outputs 里若混入 PreviewImage 会把结果解析到 temp 图，
     * 故显式清理，保证「单输出」语义。</p>
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
}
