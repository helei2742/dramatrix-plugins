package io.dramatrix.plugin.qwen21outpaint;

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
 * Qwen Image 2.1 扩图适配器（参数面照 {@code flux2-klein-outpaint-image}，机制按 Qwen 自己的口径走）。
 *
 * <h2>链路（节点号与 workflow.json 一致，便于对照）</h2>
 * <pre>
 * 19 LoadImage(原图) → 12 ImageScaleToTotalPixels(缩放率/插值) → 26 ImagePadForOutpaint(左/上/右/下/羽化)
 *                                                                    ├─ 26,0(扩边图) → 8 DrawMaskOnImage(涂填充色) → 25.images.image_1
 *                                                                    └─ 26,1(扩边区遮罩) → 8.mask
 * 25 TextEncodeQwenImage21(prompt / negative_prompt / resolution=0 / image_1=8,0)
 *      ★ 出参 2 = 跟随 image_1 尺寸的**空 latent** ⇒ 直接当采样画布（官方 image-edit 模板同款）
 *  6 KSampler ← 25,0(正) + 25,1(负) + 25,2(画布) + 5(QwenImage21Cache ← 16 UNETLoader) → 9 VAEDecode
 *  9 → 11 ColorMatch(image_ref=12,0 原图 / image_target=9,0) → 23 SaveImageAdvanced
 *
 * 28 EmptyLatentImage  ← 仅「输出宽 + 输出高」都显式给定时才接进 6.latent_image（否则不可达被 prune）
 * </pre>
 *
 * <h2>★ 整幅重画，不拼原图（这是本插件的核心口径）</h2>
 * <p>扩图的画布是<b>空 latent</b>（{@code denoise=1}），模型<b>整幅从零重画</b>，原图内容只通过
 * {@code image_1} 参考 + 指令（「保持现有图片不变」）保持；输出再经 {@code ColorMatch} 把色彩对齐回原图。
 * 这样成品是<b>一次生成出来的整幅图</b>，颜色天然连续。</p>
 * <p>⚠️ <b>别改成「原图区冻结 + 只重画扩边区」</b>（{@code VAEEncode(扩边图)} + {@code SetLatentNoiseMask}）——
 * 那样成品是「原图像素（还过了一遍 VAE 编解码）＋ 新生成区」两块拼起来的，<b>接缝处会出现明显色差</b>
 * （用户实测反馈：「是不是把原图又合到生成出来的图上了，有明显的色差，不用合的，直接出」）。
 * 局部重绘（{@code qwen-image2.1-inpaint-image}）那边用 noise_mask 是对的（那里要的正是「只改一块、
 * 其余逐像素保留」），**两者别互相搬**。</p>
 *
 * <h2>为什么扩边区要涂色（与局部重绘的口径不同）</h2>
 * <p>Qwen-Image 在<b>模型层面没有 mask 通道</b>（{@code QwenImage.extra_conds} 只读
 * {@code reference_latents} / {@code image_slots}），「要改哪儿」只能靠参考图上的颜色让模型看见：
 * {@code DrawMaskOnImage} 把扩边区涂成纯填充色（默认 {@code 0, 255, 0}）送 {@code image_1}，
 * 提示词里指代该色（默认指令即指代绿色）。</p>
 * <p>⚠️ 局部重绘那边是<b>不涂色</b>的 —— 裁剪区里本来就有真实内容，涂色会把内容盖住、逼模型去猜。
 * 而扩边区<b>本来就没有内容</b>（{@code ImagePadForOutpaint} 填白），涂色只是「标出这块是空的」，
 * 既不盖内容又给了提示词一个可指代的对象。两者别互相搬。</p>
 *
 * <h2>输出宽高（可选覆盖）</h2>
 * <p>默认画布 = {@code TextEncodeQwenImage21} 出参 2，尺寸天然等于「缩放 + 扩边」的结果、与
 * {@code image_1} 同尺寸。显式给定<b>输出宽 + 输出高</b>时，才把画布换成 {@code EmptyLatentImage}
 * （官方 image-edit 模板里也是这个节点做显式画布）。⚠️ 此时画布与参考图尺寸不再一致，扩边内容不会按
 * 扩边位置对齐 —— 只用于「确实要强制出图尺寸」的场合，故两个都要填才生效（只填一个按未填处理并 WARN）。</p>
 *
 * <h2>参数取值链</h2>
 * <p>全部生成参数声明在 {@code adapter.json → extra.paramSchema}（前端「额外参数」弹层按它渲染），
 * 适配器按「typed 字段（顶层请求参数 / Agent 动作）→ {@code ImageEditInput.options}（前端 paramSchema
 * 弹层）→ paramSchema.defaultValue → 类内硬保底」取。⚠️ 别只读 typed：Handler 的显式白名单只提升
 * 一部分键，{@code megapixels / pad* / maskColor / prompt / width / height / upscaleMethod …}
 * <b>只会停在 options 里</b>（详见 {@link #numParam}）。</p>
 *
 * @author helei
 */
@Slf4j
public class Qwen21OutpaintImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    private static final String NODE_SAVE = "SaveImageAdvanced";
    /** 扩边节点（校验四边用） */
    private static final String NODE_PAD = "ImagePadForOutpaint";
    /** 可选画布节点：只有显式输出宽高时才接进采样链路 */
    private static final String NODE_EMPTY_LATENT = "EmptyLatentImage";
    /** 采样画布的入口：默认接 TextEncodeQwenImage21 的出参 2，显式输出宽高时改指 EmptyLatentImage */
    private static final String NODE_SAMPLER = "KSampler";

    /** adapter.json extra（作者级，不进 paramSchema）：缩放尺寸对齐步长 */
    private static final String EXTRA_RESOLUTION_STEPS = "resolutionSteps";
    /** adapter.json extra：文本编码器文件名（模型槽优先于它） */
    private static final String EXTRA_CLIP_NAME = "clipName";
    /** adapter.json extra：TextEncodeQwenImage21 的参考图缩放预算（必须为 0，见 {@link #resolveReferenceResolution()}） */
    private static final String EXTRA_REFERENCE_RESOLUTION = "referenceResolution";

    /** 工作流自带默认（extra / paramSchema 缺失时的兜底，与 workflow.json 一致） */
    private static final String FALLBACK_CLIP_NAME = "qwen3vl_8b_int8_convrot.safetensors";
    private static final int FALLBACK_RESOLUTION_STEPS = 1;
    private static final int FALLBACK_REFERENCE_RESOLUTION = 0;
    private static final String FALLBACK_UPSCALE_METHOD = "lanczos";
    private static final String FALLBACK_COLOR_MATCH_METHOD = "mkl";
    private static final double FALLBACK_COLOR_MATCH_STRENGTH = 0.4;

    /**
     * {@code ImageScaleToTotalPixels.upscale_method} 的合法取值（= ComfyUI 核心节点的 COMBO 选项，
     * 顺序与节点一致）。⚠️ COMBO 值**原样透传**，非法值会让 ComfyUI 把整条 prompt 拒收 —— 故做白名单校验。
     */
    private static final Set<String> UPSCALE_METHODS =
            Set.of("nearest-exact", "bilinear", "area", "bicubic", "lanczos");
    /** {@code ColorMatch.method} 的合法取值（= KJNodes 的 COMBO 选项，顺序与节点一致）。 */
    private static final Set<String> COLOR_MATCH_METHODS =
            Set.of("mkl", "hm", "reinhard", "mvgd", "hm-mvgd-hm", "hm-mkl-hm");

    /**
     * 内置默认绑定：与插件目录下的 workflow.binding.json 保持一致。
     * 用户改写 binding 文件后以文件为准（ensureDefaultBinding 只在未配置时生效）。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "image", "target": { "match": "title", "title": "加载待扩图图像", "field": "image" } },
        { "parameter": "megapixels", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "megapixels" }, "transform": "float" },
        { "parameter": "upscale_method", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "upscale_method" } },
        { "parameter": "resolution_steps", "target": { "match": "classType", "classType": "ImageScaleToTotalPixels", "field": "resolution_steps" }, "transform": "int" },
        { "parameter": "pad_left", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "left" }, "transform": "int" },
        { "parameter": "pad_top", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "top" }, "transform": "int" },
        { "parameter": "pad_right", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "right" }, "transform": "int" },
        { "parameter": "pad_bottom", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "bottom" }, "transform": "int" },
        { "parameter": "pad_feathering", "target": { "match": "classType", "classType": "ImagePadForOutpaint", "field": "feathering" }, "transform": "int" },
        { "parameter": "mask_color", "target": { "match": "classType", "classType": "DrawMaskOnImage", "field": "color" } },
        { "parameter": "canvas_width", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "width" }, "transform": "int" },
        { "parameter": "canvas_height", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "height" }, "transform": "int" },
        { "parameter": "reference_resolution", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "resolution" }, "transform": "int" },
        { "parameter": "prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "prompt" } },
        { "parameter": "negative_prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "negative_prompt" } },
        { "parameter": "cache_device", "target": { "match": "classType", "classType": "QwenImage21Cache", "field": "device" } },
        { "parameter": "dtype", "target": { "match": "classType", "classType": "QwenImage21Cache", "field": "dtype" } },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "sampler_name", "target": { "match": "classType", "classType": "KSampler", "field": "sampler_name" } },
        { "parameter": "scheduler", "target": { "match": "classType", "classType": "KSampler", "field": "scheduler" } },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "color_match_method", "target": { "match": "classType", "classType": "ColorMatch", "field": "method" } },
        { "parameter": "color_match_strength", "target": { "match": "classType", "classType": "ColorMatch", "field": "strength" }, "transform": "float" },
        { "parameter": "save_format", "target": { "match": "classType", "classType": "SaveImageAdvanced", "field": "format" } },
        { "parameter": "unet_name", "target": { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } },
        { "parameter": "clip_name", "target": { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" } },
        { "parameter": "vae_name", "target": { "match": "classType", "classType": "VAELoader", "field": "vae_name" } }
      ],
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      },
      "modelSlots": [
        {
          "group": "qwen-image-2.1-diffusion",
          "default": "qwen_image_2.1_int8_convrot.safetensors",
          "targets": [ { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } ]
        },
        {
          "group": "qwen-image-2.1-clip",
          "default": "qwen3vl_8b_int8_convrot.safetensors",
          "targets": [ { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" } ]
        },
        {
          "group": "qwen-image-2.1-vae",
          "default": "qwen_image_2.1_vae_bf16.safetensors",
          "targets": [ { "match": "classType", "classType": "VAELoader", "field": "vae_name" } ]
        }
      ]
    }
    """;

    public Qwen21OutpaintImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        ImageEditInput input = in == null ? new ImageEditInput() : in;

        // LoRA 已收拢进 input（服务端由 loraNames 通道解析回填）
        List<LoraBundle> loras = input.getLoras() == null ? List.of() : input.getLoras();

        // 1. 原图（落点由绑定声明，缺省保留工作流自带值）
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }

        // 2. 缩放（缩放率 / 插值算法 / 对齐步长）
        Double megapixels = numParam(input, "megapixels", input.getMegapixels());
        if (megapixels != null) {
            applyBinding(api, "megapixels", megapixels);
        }
        applyBinding(api, "upscale_method", resolveEnum(
                input, "upscaleMethod", "upscale_method", UPSCALE_METHODS, FALLBACK_UPSCALE_METHOD));
        // 作者级配置：尺寸对齐步长（用户不该调）
        applyBinding(api, "resolution_steps", extraInt(EXTRA_RESOLUTION_STEPS, FALLBACK_RESOLUTION_STEPS));

        // 3. 扩充画板：左 / 上 / 右 / 下 + 羽化
        applyIntBinding(api, "pad_left", intParam(input, "padLeft", "pad_left", input.getPadLeft()));
        applyIntBinding(api, "pad_top", intParam(input, "padTop", "pad_top", input.getPadTop()));
        applyIntBinding(api, "pad_right", intParam(input, "padRight", "pad_right", input.getPadRight()));
        applyIntBinding(api, "pad_bottom", intParam(input, "padBottom", "pad_bottom", input.getPadBottom()));
        applyIntBinding(api, "pad_feathering",
                intParam(input, "padFeathering", "pad_feathering", input.getPadFeathering()));

        // 4. 输出宽高（可选覆盖；宽高都填才切独立画布，见类注释）
        overrideCanvasSize(api, input);

        // 5. 扩边区填充色（画在扩边区、供 prompt 指代）——格式非法会静默画错颜色，这里提前拦
        String maskColor = strParam(input, "maskColor", "mask_color", input.getMaskColor());
        if (StrUtil.isNotBlank(maskColor)) {
            String color = maskColor.trim();
            validateMaskColor(color);
            applyBinding(api, "mask_color", color);
        }

        // 6. 扩图指令（须指代填充色）+ 反向提示词
        String prompt = strParam(input, "prompt", "prompt", input.getPrompt());
        if (StrUtil.isNotBlank(prompt)) {
            applyBinding(api, "prompt", prompt.trim());
        }
        String negativePrompt = strParam(input, "negativePrompt", "negative_prompt", null);
        if (StrUtil.isNotBlank(negativePrompt)) {
            applyBinding(api, "negative_prompt", negativePrompt.trim());
        }

        // 7. 插件固有配置（adapter.json extra）：文本编码器（模型槽优先）
        applyBinding(api, "clip_name", extraStr(EXTRA_CLIP_NAME, FALLBACK_CLIP_NAME));
        // 7.1 参考图缩放预算：内部固定 0（见 resolveReferenceResolution）
        applyBinding(api, "reference_resolution", resolveReferenceResolution());

        // 8. 模型槽（UI 选择 / 持久化 model.selection.json）压过第 7 步的 extra 默认
        applyModelSlots(api);

        // 9. 采样参数
        applyIntBinding(api, "steps", intParam(input, "steps", "steps", input.getSteps()));
        Double cfg = numParam(input, "cfg", input.getCfg());
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }
        String samplerName = strParam(input, "samplerName", "sampler_name", input.getSamplerName());
        if (StrUtil.isNotBlank(samplerName)) {
            applyBinding(api, "sampler_name", samplerName);
        }
        if (StrUtil.isNotBlank(input.getScheduler())) {
            applyBinding(api, "scheduler", input.getScheduler());
        }
        if (input.getDenoise() != null) {
            applyBinding(api, "denoise", input.getDenoise());
        }
        String cacheDevice = strParam(input, "cacheDevice", "cache_device", null);
        if (StrUtil.isNotBlank(cacheDevice)) {
            applyBinding(api, "cache_device", cacheDevice);
        }
        String dtype = strParam(input, "dtype", "dtype", null);
        if (StrUtil.isNotBlank(dtype)) {
            applyBinding(api, "dtype", dtype);
        }

        // 10. 输出后处理：色彩校正算法 / 强度 + 保存格式
        applyBinding(api, "color_match_method", resolveEnum(
                input, "colorMatchMethod", "color_match_method", COLOR_MATCH_METHODS,
                FALLBACK_COLOR_MATCH_METHOD));
        applyBinding(api, "color_match_strength", resolveDouble(
                "colorMatchStrength",
                optDouble(input, "colorMatchStrength", "color_match_strength"),
                FALLBACK_COLOR_MATCH_STRENGTH));
        String saveFormat = strParam(input, "saveFormat", "save_format", null);
        if (StrUtil.isNotBlank(saveFormat)) {
            applyBinding(api, "save_format", saveFormat);
        }

        // 11. 模型文件（请求级显式指定，优先级高于模型槽）
        if (StrUtil.isNotBlank(input.getUnetName())) {
            applyBinding(api, "unet_name", input.getUnetName());
        }
        if (StrUtil.isNotBlank(input.getVaeName())) {
            applyBinding(api, "vae_name", input.getVaeName());
        }

        // 12. LoRA 动态串联：按 binding 的 lora.startNodes（UNETLoader）在 UNETLoader 与其消费者
        //     （QwenImage21Cache）之间插入 LoraLoaderModelOnly；空列表完全不改链路
        applyLorasFromBinding(api, loras);

        // 13. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = intParam(input, "seed", "seed", input.getSeed());
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 14. 校验扩边（读到实际生效值，含工作流默认），避免交付「等于没扩」的结果
        validatePadding(api);

        // 15. 剪掉不可达节点，保证 SaveImageAdvanced 单输出语义
        //     （没显式指定输出宽高时，那个 EmptyLatentImage 无人引用 ⇒ 一并剪掉）
        pruneUnreachable(api, resolveNodeIdByClassType(api, NODE_SAVE));

        return api;
    }

    // ===================== 输出画布覆盖（可选） =====================

    /**
     * 「输出宽 + 输出高」都显式给定才生效：把 EmptyLatentImage 的宽高写成它们，并把
     * {@code KSampler.latent_image} 从 {@code TextEncodeQwenImage21} 的出参 2（跟随 image_1 尺寸的空 latent）
     * 改指到该空 latent。
     *
     * <p>⚠️ 这是**拓扑改写**（换掉画布来源），不是参数落点，所以走 Java 而不是绑定；
     * 只填一个方向按「未填」处理（半覆盖出来的画布尺寸没有意义）。</p>
     */
    private void overrideCanvasSize(JSONObject api, ImageEditInput input) {
        Integer outW = intParam(input, "width", "width", null);
        Integer outH = intParam(input, "height", "height", null);
        if (outW == null && outH == null) {
            return;
        }
        if (outW == null || outH == null) {
            log.warn("[Qwen21Outpaint] 输出宽高只给了一边（width={}, height={}），已按「未指定」处理；"
                    + "要覆盖画布请把宽和高都填上", outW, outH);
            return;
        }
        String latentId = tryResolveClassType(api, NODE_EMPTY_LATENT);
        String samplerId = tryResolveClassType(api, NODE_SAMPLER);
        if (latentId == null || samplerId == null) {
            log.warn("[Qwen21Outpaint] 工作流缺少 {} 或 {}，无法覆盖输出画布，本次按「跟随扩边尺寸」处理",
                    NODE_EMPTY_LATENT, NODE_SAMPLER);
            return;
        }
        applyBinding(api, "canvas_width", outW);
        applyBinding(api, "canvas_height", outH);
        api.getJSONObject(samplerId).getJSONObject("inputs").set("latent_image", List.of(latentId, 0));
        log.info("[Qwen21Outpaint] 已覆盖输出画布: {}×{}（画布与参考图尺寸不再一致，扩边内容不会按扩边位置对齐）",
                outW, outH);
    }

    // ===================== paramSchema 参数取值（typed → options → paramSchema → 保底） =====================

    /**
     * 数值型生成参数的取值链：<b>typed 字段</b>（顶层请求参数，Agent 动作走这条）→
     * <b>options</b>（前端「额外参数」弹层按 paramSchema 提交的 pluginParams）→
     * <b>paramSchema.defaultValue</b>（用户可见默认值的唯一出处）。
     *
     * <p>★ 为什么必须读 options：Handler 的显式白名单只把一部分键提升到 typed 字段
     * （{@code steps / seed / cfg / denoise / maskChannel / …}），{@code megapixels / pad* / maskColor /
     * prompt / width / height / upscaleMethod} 这些**只会停在 options 里** ⇒ 只读 typed 的症状是
     * 「界面上能改、跑出来没变化」。</p>
     * <p>★ 为什么 typed 优先：顶层参数是「显式请求」，而 options 里可能混着前端播种的缺省值。</p>
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
     * 枚举型 paramSchema 参数：走基类的三级取值链（{@link #resolveStr}），再对结果做<b>白名单校验</b>。
     *
     * <p>⚠️ 必须校验：{@code ImageScaleToTotalPixels.upscale_method} / {@code ColorMatch.method}
     * 都是 ComfyUI 的 COMBO 输入，值会<b>原样透传</b> —— 非法值不是「静默用默认」，而是让
     * ComfyUI 把<b>整条 prompt 拒收</b>（报 validation failed）。故非法值在这里回退缺省并 WARN。</p>
     */
    private String resolveEnum(
            ImageEditInput input, String key, String alias, Set<String> allowed, String fallback
    ) {
        String v = resolveStr(key, optStr(input, key, alias), fallback);
        if (allowed.contains(v)) {
            return v;
        }
        log.warn("[Qwen21Outpaint] {}={} 不是合法取值（仅支持 {}），已回退 {}；"
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

    /** 按 classType 定位节点号；未命中返回 {@code null}（不抛，供可选改写探测用）。 */
    private static String tryResolveClassType(JSONObject api, String classType) {
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node != null && classType.equals(node.getStr("class_type"))) {
                return id;
            }
        }
        return null;
    }

    // ===================== adapter.json extra 读取（作者级配置） =====================

    /**
     * 参考图缩放预算（{@code TextEncodeQwenImage21.resolution}）：本插件必须为 0。
     *
     * <p>采样画布就是该节点的出参 2（尺寸跟随 {@code image_1} = 涂色后的扩边图）⇒ 一旦
     * {@code resolution} 非 0，{@code image_1} 会被二次缩放、画布尺寸跟着变，
     * 而四边扩边（{@code ImagePadForOutpaint}）是按原始像素给的 ⇒ 实际输出与设定的扩边像素对不上。</p>
     */
    private int resolveReferenceResolution() {
        Integer v = extraInt(EXTRA_REFERENCE_RESOLUTION, FALLBACK_REFERENCE_RESOLUTION);
        int resolution = v == null ? FALLBACK_REFERENCE_RESOLUTION : v;
        if (resolution != FALLBACK_REFERENCE_RESOLUTION) {
            log.warn("[Qwen21Outpaint] extra.{}={}（非 0）会二次缩放 image_1，而采样画布尺寸跟随 image_1 ⇒ "
                    + "输出尺寸跟着变、与设定的四边扩边像素对不上", EXTRA_REFERENCE_RESOLUTION, resolution);
        }
        return resolution;
    }

    /** 读 adapter.json extra 的字符串配置；缺失/空白时返回 {@code fallback}。 */
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
     * 剪掉从根节点（SaveImageAdvanced）不可达的节点。
     * <p>ComfyUI 不执行不可达节点，但 history.outputs 里若混入 PreviewImage 会把结果解析到 temp 图，
     * 故显式清理，保证「单输出」语义。同时借它把「没显式指定输出宽高时那个 EmptyLatentImage」剪掉。</p>
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
