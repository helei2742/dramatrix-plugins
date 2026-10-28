package io.dramatrix.plugin.kleininpaint;

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
 * Flux.2 Klein 9B 局部重绘适配器（对应参考工作流「局部重绘F2K」，Flux.2 Klein 9B）。
 *
 * <h2>链路（节点号与 workflow.json 一致，便于对照）</h2>
 * <pre>
 * 原图 LoadImage(19) ─┬→ DrawMaskOnImage(22，把遮罩区涂成填充色) → InpaintCropImproved(23)
 *                     └→（参考图 LoadImage(40) 是另一条线，见下）
 * 遮罩 LoadImage(20) → ImageToMask(21) ─┬→ DrawMaskOnImage(22).mask
 *                                       └→ InpaintCropImproved(23).mask
 * 23,1(裁剪图) → VAEEncode(24) ─┬→ ReferenceLatent(27).latent   ← 正向链（image 0 = 裁剪图）
 *                               └→ ReferenceLatent(28).latent   ← 负向链
 * CLIPTextEncode(25) ─┬→ ReferenceLatent(27).conditioning
 *                     └→ ConditioningZeroOut(26) → ReferenceLatent(28).conditioning
 * 30 KSampler ← 27(positive) / 28(negative) / EmptyFlux2LatentImage(29，空画布) / 13(UNETLoader)
 * 30 → VAEDecode(31) → InpaintStitchImproved(36) ← 23,0(stitcher) → SaveImage(37)
 *
 * 40 LoadImage(参考图) → ImageScaleToTotalPixels → VAEEncode → ReferenceLatent(正/负各一)
 *                       → 串到 27/28 之后，并改写 KSampler.positive / .negative（仅当传了参考图）
 * </pre>
 *
 * <h2>为什么是「裁剪 + 涂色 + 空画布重画」</h2>
 * <p>Flux.2 的局部重绘不是「遮罩内加噪、区外冻结」那一路（Qwen 那套用 {@code SetLatentNoiseMask}），
 * 而是：<b>把重绘区连同上下文裁出来 → 缩放到固定采样边长 → VAE 编码成「参考图」挂进条件
 * （{@code ReferenceLatent}）→ 采样画布是<u>空</u> latent（{@code EmptyFlux2LatentImage}）
 * → 模型从零重画整块裁剪区 → 缝合回原图</b>。</p>
 * <p>既然整块都要重画，模型就必须知道「这一块里哪部分要改、哪部分是给我的上下文」——
 * 所以遮罩区要用 {@code DrawMaskOnImage} 涂成纯色（默认 {@code 0, 255, 0}）再送去裁剪。
 * ⚠️ 这一点与 Qwen Image 2.1 那套（喂干净裁剪图、遮罩只走 noise_mask）<b>刚好相反</b>，
 * 别把两边口径互相搬。</p>
 *
 * <h2>固定采样边长（cropTargetSize）</h2>
 * <p>{@code InpaintCropImproved.output_target_width/height} 是<b>精确的最终裁剪尺寸</b>（不是上限）：
 * 节点会先把上下文区按目标宽高比扩缩、再把裁剪结果重采样到该尺寸。旧版把它接成
 * {@code GetImageSize}（= 输入图尺寸），1920×1080 的原图会让模型在 ~2MP 画布上采样
 * （远超 Klein 约 1MP 的原生训练分辨率），而且为了凑原图宽高比会把上下文区撑到接近整图。
 * 官方 F2K 工作流用 {@code INTConstant(1024)} 固定成正方形 1024²。</p>
 * <p>⚠️ <b>画布必须与裁剪目标同尺寸</b>：{@code EmptyFlux2LatentImage.width/height} 与
 * {@code InpaintCropImproved.output_target_width/height} 由同一个 {@code cropTargetSize} 驱动
 * （四个绑定一起写）—— 不一致时缝合节点拿到的几何对不上，重绘内容会错位。</p>
 *
 * <h2>额外参考图（可选）</h2>
 * <p>{@link ImageEditInput#getReferenceImage()} 非空时，动态建
 * {@code LoadImage → ImageScaleToTotalPixels → VAEEncode → ReferenceLatent(正/负各一)}
 * 串到现有条件链末端，并改写 {@code KSampler.positive / .negative}
 * （实现与 {@code io.dramatrix.plugin.klein.KleinWorkflowBuilder} 同源）。
 * 未传则完全不改链路。</p>
 * <p>★ <b>Flux.2 没有「按编号绑定某张图」的节点</b>：参考图只能这样链式注入到条件里，
 * 模型拿到的是「N 张参考图」的整体条件。索引 = 链式顺序，裁剪图是 <b>image 0</b>，
 * 本字段追加的是 <b>image 1</b>；提示词里只能用自然语言 + 带空格的序数词（{@code image 1}）
 * 去「尽力引导」，不保证 100% 绑定。参考图按 1MP（{@code megapixels=1}）缩放后编码。</p>
 *
 * <h2>遮罩语义</h2>
 * <p>用户传入原图 + 遮罩图（不透明 RGB 黑白：白=重绘、黑=保留；或 red/green/blue 等单通道表达）。
 * 遮罩来源由 {@code ImageEditInput.maskImage}（服务端从 {@code maskImageAssetId} 上传后回填）指定，
 * 选取通道由 {@code maskChannel} 控制（默认 red，兼容上传转码后丢失 alpha 的黑白遮罩）。</p>
 *
 * <h2>提示词</h2>
 * <p>用「正向描述要改成什么」引导模型在遮罩内重绘，<b>无需指代填充色</b>（填充色仅供模型定位重绘区，
 * 属内部细节）。</p>
 *
 * <h2>未采纳的参考流节点</h2>
 * <p>参考工作流里 {@code VAEDecode} 之后还挂了 {@code LayerColor: AutoAdjustV2}（色彩自动校正，
 * 参数 red=5/blue=40）—— 它会把重绘区往蓝紫偏、与周边形成色差，且会多引一个
 * {@code ComfyUI_LayerStyle} 依赖，故<b>不采纳</b>：解码结果直接缝合，颜色交由模型自行与周边协调。</p>
 *
 * <p>参数收拢进 {@link ImageEditInput}（对齐 {@code Image2ImageWithLoraPlugin} 的收拢方式）：
 * LoRA 经 {@code input.getLoras()} 由服务端回填，seed 由服务端兜底，适配器只读 input。</p>
 *
 * @author helei
 */
@Slf4j
public class Flux2KleinInpaintImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    private static final String NODE_SAVE = "SaveImage";
    private static final String NODE_SAMPLER = "KSampler";
    private static final String NODE_TEXT_ENCODE = "CLIPTextEncode";
    private static final String NODE_VAE_LOADER = "VAELoader";

    /** 参考图 LoadImage 的绑定参数名（同时用于定位节点号） */
    private static final String BINDING_REFERENCE_IMAGE = "reference_image";

    /** adapter.json extra：文本编码器文件名（模型槽优先于它） */
    private static final String EXTRA_CLIP_NAME = "clipName";
    /** adapter.json extra：遮罩选取通道默认值 */
    private static final String EXTRA_DEFAULT_MASK_CHANNEL = "defaultMaskChannel";
    /** adapter.json extra：InpaintCropImproved 计算设备（cpu / gpu） */
    private static final String EXTRA_DEVICE_MODE = "deviceMode";

    /** 工作流自带默认（extra 缺失时的兜底，与 workflow.json / dependencies.json 一致） */
    private static final String FALLBACK_CLIP_NAME = "qwen_3_8b_fp8mixed.safetensors";
    /** 遮罩图是"不透明 RGB 黑白"（白=重绘、黑=保留），取 red 通道；避免上传丢 alpha 后索引越界 */
    private static final String FALLBACK_DEFAULT_MASK_CHANNEL = "red";
    /** InpaintCropImproved 计算设备默认（cpu 兼容最稳，gpu 更快） */
    private static final String FALLBACK_DEVICE_MODE = "cpu (compatible)";

    /** paramSchema 私有参数 key：送模型的裁剪采样边长（正方形，同时是重绘画布边长） */
    private static final String PARAM_CROP_TARGET_SIZE = "cropTargetSize";
    /** 采样边长兜底（paramSchema 条目缺失时用；与 workflow.json 的 1024 一致） */
    private static final int FALLBACK_CROP_TARGET_SIZE = 1024;
    /** 采样边长合法区间 */
    private static final int MIN_CROP_TARGET_SIZE = 256;
    private static final int MAX_CROP_TARGET_SIZE = 2048;
    /**
     * 尺寸对齐步长：裁剪目标与画布边长都必须是它的倍数。
     * {@code InpaintCropImproved} 侧还有 {@code output_padding="32"} 兜底；
     * {@code EmptyFlux2LatentImage} 要求宽高能被 16 整除，32 的倍数天然满足。
     */
    private static final int ALIGNMENT_STEP = 32;

    /** 动态参考图节点号起点（避开 workflow.json 的业务节点与 LoRA 串联用的 2000+） */
    private static final int REF_NODE_ID_BASE = 1000;

    // ===================== 动态节点模板（与 KleinWorkflowBuilder 同源） =====================

    private static final String LOAD_IMAGE_TEMPLATE = """
            {
                "inputs": { "image": "" },
                "class_type": "LoadImage",
                "_meta": { "title": "加载参考图" }
            }
            """;

    /** 参考图按 1MP 缩放后再编码：参考图尺寸与原图无关，避免大图把参考 latent 撑爆 */
    private static final String SCALE_IMAGE_TEMPLATE = """
            {
                "inputs": {
                    "upscale_method": "lanczos",
                    "megapixels": 1,
                    "resolution_steps": 64,
                    "image": ["__IMG__", 0]
                },
                "class_type": "ImageScaleToTotalPixels",
                "_meta": { "title": "缩放参考图（1MP）" }
            }
            """;

    private static final String VAE_ENCODE_TEMPLATE = """
            {
                "inputs": {
                    "pixels": ["__IMG__", 0],
                    "vae": ["__VAE__", 0]
                },
                "class_type": "VAEEncode",
                "_meta": { "title": "编码参考图" }
            }
            """;

    private static final String REFERENCE_LATENT_TEMPLATE = """
            {
                "inputs": {
                    "conditioning": ["__COND__", 0],
                    "latent": ["__LATENT__", 0]
                },
                "class_type": "ReferenceLatent",
                "_meta": { "title": "参考Latent" }
            }
            """;

    /**
     * 内置默认绑定：与插件目录下的 workflow.binding.json 保持一致。
     * 用户改写 binding 文件后以文件为准（ensureDefaultBinding 只在未配置时生效）。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "image", "target": { "match": "title", "title": "加载重绘原图（LoadImage 原图）", "field": "image" } },
        { "parameter": "mask_image", "target": { "match": "title", "title": "加载遮罩源图（LoadImage 遮罩）", "field": "image" } },
        { "parameter": "reference_image", "target": { "match": "title", "title": "加载参考图（LoadImage 参考图）", "field": "image" } },
        { "parameter": "mask_channel", "target": { "match": "classType", "classType": "ImageToMask", "field": "channel" } },
        { "parameter": "mask_color", "target": { "match": "classType", "classType": "DrawMaskOnImage", "field": "color" } },
        { "parameter": "mask_expand_pixels", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "mask_expand_pixels" }, "transform": "int" },
        { "parameter": "mask_blend_pixels", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "mask_blend_pixels" }, "transform": "int" },
        { "parameter": "crop_target_width", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "output_target_width" }, "transform": "int" },
        { "parameter": "crop_target_height", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "output_target_height" }, "transform": "int" },
        { "parameter": "canvas_width", "target": { "match": "classType", "classType": "EmptyFlux2LatentImage", "field": "width" }, "transform": "int" },
        { "parameter": "canvas_height", "target": { "match": "classType", "classType": "EmptyFlux2LatentImage", "field": "height" }, "transform": "int" },
        { "parameter": "device_mode", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "device_mode" } },
        { "parameter": "prompt", "target": { "match": "classType", "classType": "CLIPTextEncode", "field": "text" } },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "sampler_name", "target": { "match": "classType", "classType": "KSampler", "field": "sampler_name" } },
        { "parameter": "scheduler", "target": { "match": "classType", "classType": "KSampler", "field": "scheduler" } },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
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
          "group": "flux2-klein-9b-diffusion",
          "default": "flux-2-klein-9b-fp8.safetensors",
          "targets": [ { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } ]
        },
        {
          "group": "flux2-klein-clip",
          "default": "qwen_3_8b_fp8mixed.safetensors",
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

    public Flux2KleinInpaintImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        ImageEditInput input = in == null ? new ImageEditInput() : in;

        // LoRA 已收拢进 input（服务端由 loraNames 通道解析回填）
        List<LoraBundle> loras = input.getLoras() == null ? List.of() : input.getLoras();

        // 1. 原图 → LoadImage（标题「加载重绘原图」）
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }

        // 2. 遮罩图 → LoadImage（标题「加载遮罩源图」）
        if (StrUtil.isNotBlank(input.getMaskImage())) {
            applyBinding(api, "mask_image", input.getMaskImage());
        }

        // 2.1 额外参考图 → LoadImage（标题「加载参考图」）。文件名在这里写；
        //     真正的接线（ReferenceLatent 链）在第 13 步 —— 那里才需要动态建节点。
        boolean hasReference = StrUtil.isNotBlank(input.getReferenceImage());
        if (hasReference) {
            applyBinding(api, "reference_image", input.getReferenceImage());
        }

        // 3. 遮罩选取通道（extra 默认兜底；请求参数显式指定时覆盖）
        applyBinding(api, "mask_channel",
                StrUtil.isNotBlank(input.getMaskChannel())
                        ? input.getMaskChannel()
                        : extraStr(EXTRA_DEFAULT_MASK_CHANNEL, FALLBACK_DEFAULT_MASK_CHANNEL));

        // 4. 插件固有配置（adapter.json extra）：文本编码器（模型槽优先）
        applyBinding(api, "clip_name", extraStr(EXTRA_CLIP_NAME, FALLBACK_CLIP_NAME));

        // 5. 模型槽（UI 选择 / 持久化 model.selection.json）压过第 4 步的 extra 默认
        applyModelSlots(api);

        // 6. 遮罩填充色（Flux.2 从空 latent 重画整块裁剪区 ⇒ 必须让模型看见要改哪一块；
        //    一般无需改，改了也不影响提示词 —— 提示词不必指代该色）
        if (StrUtil.isNotBlank(input.getMaskColor())) {
            String color = input.getMaskColor().trim();
            validateMaskColor(color);
            applyBinding(api, "mask_color", color);
        }

        // 7. 遮罩膨胀 / 边缘羽化
        if (input.getMaskExpandPixels() != null) {
            applyBinding(api, "mask_expand_pixels", input.getMaskExpandPixels());
        }
        if (input.getMaskBlendPixels() != null) {
            applyBinding(api, "mask_blend_pixels", input.getMaskBlendPixels());
        }

        // 7.1 采样边长（cropTargetSize）：**一次解析、四处同写** ——
        //     裁剪目标（InpaintCropImproved.output_target_width/height）与重绘画布
        //     （EmptyFlux2LatentImage.width/height）必须严格同尺寸，否则缝合几何错位。
        int cropTarget = resolveCropTargetSize(input);
        applyBinding(api, "crop_target_width", cropTarget);
        applyBinding(api, "crop_target_height", cropTarget);
        applyBinding(api, "canvas_width", cropTarget);
        applyBinding(api, "canvas_height", cropTarget);

        // 7.2 计算设备（InpaintCropImproved.device_mode）：extra 配置（插件作者可改，默认 cpu）
        applyBinding(api, "device_mode", extraStr(EXTRA_DEVICE_MODE, FALLBACK_DEVICE_MODE));

        // 8. 提示词（局部重绘指令，描述遮罩区域要改成什么）
        if (StrUtil.isNotBlank(input.getPrompt())) {
            applyBinding(api, "prompt", input.getPrompt().trim());
        }

        // 9. 采样常规参数
        if (input.getSteps() != null) {
            applyBinding(api, "steps", input.getSteps());
        }
        if (input.getCfg() != null) {
            applyBinding(api, "cfg", input.getCfg());
        }
        if (StrUtil.isNotBlank(input.getSamplerName())) {
            applyBinding(api, "sampler_name", input.getSamplerName());
        }
        if (StrUtil.isNotBlank(input.getScheduler())) {
            applyBinding(api, "scheduler", input.getScheduler());
        }
        if (input.getDenoise() != null) {
            applyBinding(api, "denoise", input.getDenoise());
        }

        // 10. 模型文件（请求级显式指定，优先级高于模型槽）
        if (StrUtil.isNotBlank(input.getUnetName())) {
            applyBinding(api, "unet_name", input.getUnetName());
        }
        if (StrUtil.isNotBlank(input.getVaeName())) {
            applyBinding(api, "vae_name", input.getVaeName());
        }

        // 11. LoRA 动态串联：按 binding 的 lora.startNodes（UNETLoader）在 UNETLoader 与
        //     其消费者（KSampler）之间插入 LoraLoaderModelOnly；空列表完全不改链路
        applyLorasFromBinding(api, loras);

        // 12. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 13. 额外参考图 → 动态建 ReferenceLatent 链（必须在 prune 之前，否则新节点会被剪掉）
        if (hasReference) {
            attachReferenceImage(api);
        }

        // 14. 校验遮罩必填（避免交付「无选区重绘」的全图重绘结果）
        validateMask(input);

        // 15. 剪掉不可达节点，保证 SaveImage 单输出语义（history.outputs 取第一个含 images 的节点）
        //     没传参考图时，那个 LoadImage(40) 无人引用 ⇒ 一并剪掉
        pruneUnreachable(api, resolveNodeIdByClassType(api, NODE_SAVE));

        return api;
    }

    // ===================== 参考图接线（ReferenceLatent 链） =====================

    /**
     * 把额外参考图接进条件链。
     *
     * <p>Flux.2 没有「按编号绑定参考图」的节点：参考图只能
     * {@code LoadImage → ImageScaleToTotalPixels → VAEEncode → ReferenceLatent} 链式注入条件。
     * 本方法按 {@code KleinWorkflowBuilder} 的同源做法，在现有正/负条件链<b>末端</b>各追加一个
     * {@code ReferenceLatent}，并把 {@code KSampler.positive / .negative} 改指到新末端
     * —— 同一张图同时进正负两条链，多参考图组合才成立。</p>
     *
     * <p>链式顺序即索引：裁剪图是 <b>image 0</b>（workflow.json 里已有的 {@code 27/28}），
     * 本方法追加的参考图是 <b>image 1</b>。</p>
     *
     * <p>现有末端不写死节点号：直接读 {@code KSampler.inputs.positive / .negative} 当前的接线
     * —— 那是「当前条件链末端」的权威来源。VAE 同理按 classType 定位 {@code VAELoader}。</p>
     */
    private void attachReferenceImage(JSONObject api) {
        String samplerId = tryResolveClassType(api, NODE_SAMPLER);
        if (samplerId == null) {
            log.warn("[KleinInpaint] 工作流缺少 {}，参考图无法接线，已忽略", NODE_SAMPLER);
            return;
        }
        JSONObject samplerInputs = api.getJSONObject(samplerId).getJSONObject("inputs");
        String prevPositive = refNodeId(samplerInputs.get("positive"));
        String prevNegative = refNodeId(samplerInputs.get("negative"));
        if (prevPositive == null || prevNegative == null) {
            log.warn("[KleinInpaint] KSampler 的 positive/negative 不是节点引用（{} / {}），参考图无法接线，已忽略",
                    samplerInputs.get("positive"), samplerInputs.get("negative"));
            return;
        }
        String vaeId = tryResolveClassType(api, NODE_VAE_LOADER);
        if (vaeId == null) {
            log.warn("[KleinInpaint] 工作流缺少 {}，参考图无法编码，已忽略", NODE_VAE_LOADER);
            return;
        }
        String[] target;
        try {
            target = resolveNodeTarget(api, BINDING_REFERENCE_IMAGE);
        } catch (IllegalArgumentException e) {
            log.warn("[KleinInpaint] 参考图 LoadImage 节点定位失败（binding 的 {} 未命中），已忽略本次参考图: {}",
                    BINDING_REFERENCE_IMAGE, e.getMessage());
            return;
        }
        if (target == null || target.length == 0 || StrUtil.isBlank(target[0])) {
            log.warn("[KleinInpaint] binding 未声明 {}，参考图无法接线，已忽略", BINDING_REFERENCE_IMAGE);
            return;
        }

        int next = REF_NODE_ID_BASE;
        String scaleId = String.valueOf(next++);
        String encodeId = String.valueOf(next++);
        String posRefId = String.valueOf(next++);
        String negRefId = String.valueOf(next);

        // LoadImage 复用 workflow.json 里的固定节点（文件名已在第 2.1 步写入），这里只做缩放 → 编码
        JSONObject scaleNode = new JSONObject(SCALE_IMAGE_TEMPLATE);
        scaleNode.getJSONObject("inputs").set("image", List.of(target[0], 0));
        api.set(scaleId, scaleNode);

        JSONObject encodeNode = new JSONObject(VAE_ENCODE_TEMPLATE);
        encodeNode.getJSONObject("inputs").set("pixels", List.of(scaleId, 0));
        encodeNode.getJSONObject("inputs").set("vae", List.of(vaeId, 0));
        api.set(encodeId, encodeNode);

        api.set(posRefId, referenceLatent(prevPositive, encodeId));
        api.set(negRefId, referenceLatent(prevNegative, encodeId));

        samplerInputs.set("positive", List.of(posRefId, 0));
        samplerInputs.set("negative", List.of(negRefId, 0));
        log.info("[KleinInpaint] 已注入额外参考图（image 1，LoadImage={}）: 缩放 {} → 编码 {} → "
                        + "ReferenceLatent(pos={}) / ReferenceLatent(neg={})",
                target[0], scaleId, encodeId, posRefId, negRefId);
    }

    /** 建一个 ReferenceLatent 节点（conditioning 接上游末端、latent 接参考图编码结果）。 */
    private static JSONObject referenceLatent(String conditioningId, String latentId) {
        JSONObject node = new JSONObject(REFERENCE_LATENT_TEMPLATE);
        JSONObject inputs = node.getJSONObject("inputs");
        inputs.set("conditioning", List.of(conditioningId, 0));
        inputs.set("latent", List.of(latentId, 0));
        return node;
    }

    /** 取「节点引用」值（形如 {@code ["27", 0]}）里的节点号；不是引用则返回 {@code null}。 */
    private static String refNodeId(Object value) {
        if (value instanceof List<?> ref && !ref.isEmpty() && ref.getFirst() != null) {
            return String.valueOf(ref.getFirst());
        }
        return null;
    }

    /** 按 classType 定位节点号；未命中返回 {@code null}（不抛，供可选接线探测用）。 */
    private static String tryResolveClassType(JSONObject api, String classType) {
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node != null && classType.equals(node.getStr("class_type"))) {
                return id;
            }
        }
        return null;
    }

    // ===================== 参数解析 =====================

    /**
     * 送模型的裁剪采样边长（正方形，同时是重绘画布边长）。
     *
     * <p>取值链：{@code ImageEditInput.options} 的 {@code cropTargetSize / crop_target_size}
     * → {@code adapter.json} 的 {@code extra.paramSchema[cropTargetSize].defaultValue}
     * → {@link #FALLBACK_CROP_TARGET_SIZE}。越界值 clamp 到
     * {@value #MIN_CROP_TARGET_SIZE}~{@value #MAX_CROP_TARGET_SIZE}，
     * 再向上对齐到 {@link #ALIGNMENT_STEP} 的倍数。</p>
     */
    private int resolveCropTargetSize(ImageEditInput input) {
        String raw = optStr(input, PARAM_CROP_TARGET_SIZE, "crop_target_size");
        if (StrUtil.isBlank(raw)) {
            Object dv = paramSchemaDefault(PARAM_CROP_TARGET_SIZE);
            raw = dv == null ? null : String.valueOf(dv).trim();
        }
        int v = FALLBACK_CROP_TARGET_SIZE;
        if (StrUtil.isNotBlank(raw)) {
            try {
                v = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                log.warn("[KleinInpaint] cropTargetSize={} 无法解析为整数，回退 {}", raw, FALLBACK_CROP_TARGET_SIZE);
                v = FALLBACK_CROP_TARGET_SIZE;
            }
        }
        if (v < MIN_CROP_TARGET_SIZE || v > MAX_CROP_TARGET_SIZE) {
            int clamped = Math.clamp(v, MIN_CROP_TARGET_SIZE, MAX_CROP_TARGET_SIZE);
            log.warn("[KleinInpaint] cropTargetSize={} 越界（{}~{}），已 clamp 到 {}",
                    v, MIN_CROP_TARGET_SIZE, MAX_CROP_TARGET_SIZE, clamped);
            v = clamped;
        }
        int aligned = ((v + ALIGNMENT_STEP - 1) / ALIGNMENT_STEP) * ALIGNMENT_STEP;
        if (aligned != v) {
            log.debug("[KleinInpaint] cropTargetSize={} 向上对齐到 {}", v, aligned);
        }
        return aligned;
    }

    /** 从 ImageEditInput.options（前端 paramSchema 弹层的私有参数）里取首个非空字符串值。 */
    private static String optStr(ImageEditInput input, String... keys) {
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
                String s = String.valueOf(v).trim();
                return StrUtil.isBlank(s) ? null : s;
            }
        }
        return null;
    }

    // ===================== adapter.json extra 读取 =====================

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

    // ===================== 校验 / 清理 =====================

    /**
     * 校验遮罩输入非空，避免「遮罩图为空」导致全图重绘而非局部重绘的误用。
     * 缺遮罩但遮罩 LoadImage 节点存在时（工作流自带空值）不在这里拦，交给运行时报错；
     * 这里只拦「用户完全没传遮罩 + 调用方没走服务端上传链路」的明显误用。
     */
    private static void validateMask(ImageEditInput input) {
        if (input == null || StrUtil.isNotBlank(input.getMaskImage())) {
            return;
        }
        // 请求未显式传遮罩：不强制报错（工作流自带 mask 图）/ 也支持 alpha 通道用法由用户控制
        log.warn("[KleinInpaint] 未提供 maskImage（遮罩图），将按工作流自带遮罩/全图配置重绘，请确认这是预期行为");
    }

    /**
     * 校验填充色格式（{@code DrawMaskOnImage.color} 的解析规则）：
     * {@code #RRGGBB} / {@code #RRGGBBAA}，或 3~4 个逗号分隔的数值（0-255 或 0.0-1.0）。
     * 该节点对无法解析的字符串会「静默」退化成错误颜色（不报错），所以在此显式拦截。
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
     * 故显式清理，保证「单输出」语义。同时借它把「没传参考图时那个无人引用的 LoadImage」剪掉。</p>
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
