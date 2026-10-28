package io.dramatrix.plugin.qwen21inpaint;

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
 * Qwen Image 2.1 局部重绘适配器（裁剪 → 干净裁剪图当参考图送模型 → 采样期 noise_mask 限域 → 缝合回原图）。
 *
 * <h2>链路（节点号对应 workflow.json）</h2>
 * <pre>
 *  20 LoadImage(原图) ─┐
 *  21 LoadImage(遮罩) → 22 ImageToMask ──→ 23 InpaintCropImproved    （0=stitcher、1=干净裁剪图、2=裁剪遮罩）
 *                                            │  output_target_width/height = cropTargetSize（正方形，默认 768）
 *                                            │  mask_expand_pixels=50 / mask_blend_pixels=32 / context=1.2
 *  23,1 → 33 SplitImageWithAlpha ─┬→ 1 TextEncodeQwenImage21.images.image_1   ← 干净裁剪图（唯一消费者）
 *                                 └→ 24 VAEEncode(干净裁剪图) ──→ 25 SetLatentNoiseMask ← 23,2
 *
 *  23,2 ────────────────────────────────────────→ 25 SetLatentNoiseMask.mask
 *  6 KSampler ← 1(positive/negative) + 25(latent) + 2(UNETLoader) → 9 VAEDecode
 *  9 → 26 InpaintStitchImproved ← 23,0(stitcher) ──→ 10 SaveImageAdvanced
 *
 *  40 LoadImage(参考图) → 1.images.image_2        ← 仅当 ImageEditInput.referenceImage 非空时挂上
 * </pre>
 *
 * <h2>为什么 image_1 是「干净裁剪图」</h2>
 * <p>Qwen Image 2.1 是 <b>instruction-based edit</b> 模型：它没有模型级 mask 通道，参考图一律走
 * {@code TextEncodeQwenImage21} 的 {@code images.image_N} 槽位（官方 image-edit 模板即
 * {@code image_1} + {@code image_2} + 提示词里的 {@code <image1>}/{@code <image2>}）。
 * 局部重绘的正确接法是<b>两条线各管一件事</b>：</p>
 * <ol>
 *   <li><b>「要改成什么」</b>走参考图 + 提示词 —— {@code image_1} = <b>干净裁剪图</b>，
 *       模型能直接看见那块区域原来是什么，提示词用自然语义描述目标（如「去掉草莓，桌面干净」）；</li>
 *   <li><b>「只能改哪里」</b>走采样期约束 —— {@code SetLatentNoiseMask} 把裁剪遮罩外的像素
 *       每步逐像素冻结（{@code comfy/samplers.py} 的 {@code KSamplerX0Inpaint}），
 *       {@code InpaintStitchImproved} 再把结果缝回原图。</li>
 * </ol>
 * <p>⚠️ <b>别再回到「把遮罩涂成纯色送 image_1」的老路</b>（历史版本做过，已下线）：那等于逼模型
 * 「猜那块绿色底下是什么」，既容易把填充色复刻进成品（于是又要接一整条 {@code DrawMaskOnImage →
 * ColorToMask → MaskComposite → ImageCompositeMasked} 色键兜底链），又让提示词必须写成
 * 「绿色区域改成…」这种反直觉形式。官方口径下提示词<b>不需要指代任何颜色</b>。</p>
 *
 * <h2>固定采样分辨率（cropTargetSize）</h2>
 * <p>{@code InpaintCropImproved.output_target_width/height} 是<b>精确的最终裁剪尺寸</b>（不是上限）：
 * 节点会先把上下文区按目标宽高比扩缩、再把裁剪结果重采样到该尺寸。旧版把它接成「输入图尺寸」，
 * 于是 1920×1080 的原图会让模型在 ~2MP 的画布上采样（远超 Qwen 2.1 约 1MP 的原生训练分辨率）
 * → 糊、重复、显存爆；且为了凑 16:9，上下文区被撑到接近整图，等于整图重绘。
 * 现固定为正方形 {@code cropTargetSize}（默认 768，对齐 32）：小块遮罩会被放大到 768² 再生成、
 * 然后被缝合节点缩回原位，大块遮罩则缩到 768² 生成 —— 模型始终在自己熟悉的尺度上工作。</p>
 *
 * <h2>尺寸一致性（硬约束）</h2>
 * <p>采样画布来自 {@code VAEEncode(裁剪图)}，与 {@code image_1}（同一张裁剪图）天然同尺寸。
 * {@code TextEncodeQwenImage21.resolution}（参考图缩放预算）必须为 0：非 0 会二次缩放 {@code image_1}，
 * 而画布与缝合节点仍按裁剪几何工作 → 内容缝错位（见 {@link #resolveReferenceResolution()}）。
 * 追加的 {@code image_2} 参考图尺寸可以与 {@code image_1} 不同 —— 官方模板就是这么用的，
 * {@code resolution=0} 下节点按各自原尺寸编码，不参与画布几何。</p>
 *
 * <h2>遮罩语义</h2>
 * <p>裁剪遮罩（膨胀 {@code maskExpandPixels} + 羽化 {@code maskBlendPixels}）被<b>两处</b>消费：
 * {@code SetLatentNoiseMask} 加噪范围、缝合时「哪块换成重绘结果」。
 * {@code SetLatentNoiseMask} 的语义是 {@code out = out * denoise_mask + latent * (1 - denoise_mask)}：
 * {@code 1} = 重新生成、{@code 0} = 每步回填并冻结。</p>
 *
 * <h2>提示词</h2>
 * <p>按自然语义描述「裁剪区最终该长什么样」，其余内容由缝合节点从原图保留。
 * <b>不要</b>写「绿色区域」之类 —— 图上没有绿色（模型看到的是干净裁剪图）。</p>
 *
 * <h2>额外参考图（可选）</h2>
 * <p>{@link ImageEditInput#getReferenceImage()} 非空时挂到 {@code images.image_2}（见
 * {@link #attachReferenceImage}）：与遮罩无关的第二张图，供模型参考风格 / 主体，
 * 提示词里可用 {@code <image2>} 指代。为空则该 LoadImage 节点无人引用、被
 * {@link #pruneUnreachable} 剪掉。</p>
 *
 * @author helei
 */
@Slf4j
public class Qwen21InpaintImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    private static final String NODE_SAVE = "SaveImageAdvanced";
    /** 文本编码节点（参考图槽位 {@code images.image_N} 挂在它身上） */
    private static final String NODE_TEXT_ENCODE = "TextEncodeQwenImage21";
    /** 参考图 LoadImage 的绑定参数名（同时用于定位节点号） */
    private static final String BINDING_REFERENCE_IMAGE = "reference_image";
    /** 文本编码节点的参考图槽位前缀（Qwen Image 2.1 支持 image_1..N） */
    private static final String INPUT_IMAGE_SLOT_PREFIX = "images.image_";

    /** adapter.json extra：文本编码器文件名（模型槽优先于它） */
    private static final String EXTRA_CLIP_NAME = "clipName";
    /** adapter.json extra：裁剪目标尺寸的倍数对齐值 */
    private static final String EXTRA_OUTPUT_PADDING = "outputPadding";
    /** adapter.json extra：InpaintCropImproved 计算设备（cpu / gpu） */
    private static final String EXTRA_DEVICE_MODE = "deviceMode";
    /** adapter.json extra：TextEncodeQwenImage21 的参考图缩放预算（必须为 0，见类注释） */
    private static final String EXTRA_REFERENCE_RESOLUTION = "referenceResolution";
    /** adapter.json extra：InpaintCropImproved 的上下文扩展倍数 */
    private static final String EXTRA_CONTEXT_EXTEND_FACTOR = "contextExtendFactor";

    /** 工作流自带默认（extra 缺失时的兜底，与 workflow.json 一致） */
    private static final String FALLBACK_CLIP_NAME = "qwen3vl_8b_int8_convrot.safetensors";
    private static final String FALLBACK_DEFAULT_MASK_CHANNEL = "red";
    private static final String FALLBACK_OUTPUT_PADDING = "32";
    private static final String FALLBACK_DEVICE_MODE = "cpu (compatible)";
    private static final int FALLBACK_REFERENCE_RESOLUTION = 0;
    /** 上下文扩展倍数兜底（与 workflow.json / adapter.json 的 1.2 一致） */
    private static final double FALLBACK_CONTEXT_EXTEND_FACTOR = 1.2;

    /** paramSchema 私有参数 key：送模型的裁剪采样边长（正方形） */
    private static final String PARAM_CROP_TARGET_SIZE = "cropTargetSize";

    /** 采样边长兜底（paramSchema 条目缺失时用；与 workflow.json 的 768 一致） */
    private static final int FALLBACK_CROP_TARGET_SIZE = 768;
    /** 采样边长合法区间 */
    private static final int MIN_CROP_TARGET_SIZE = 256;
    private static final int MAX_CROP_TARGET_SIZE = 2048;

    /** 画布/参考图尺寸对齐步长：裁剪目标必须是它的倍数，latent 尺寸才与 image_1 一致 */
    private static final int ALIGNMENT_STEP = 32;
    /** 合法的 output_padding 取值（皆为 32 的倍数；0/8/16 会破坏对齐，见校验逻辑） */
    private static final Set<String> ALIGNED_PADDING_OPTIONS = Set.of("32", "64", "128", "256", "512");

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
        { "parameter": "mask_expand_pixels", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "mask_expand_pixels" }, "transform": "int" },
        { "parameter": "mask_blend_pixels", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "mask_blend_pixels" }, "transform": "int" },
        { "parameter": "context_extend_factor", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "context_from_mask_extend_factor" }, "transform": "float" },
        { "parameter": "crop_target_width", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "output_target_width" }, "transform": "int" },
        { "parameter": "crop_target_height", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "output_target_height" }, "transform": "int" },
        { "parameter": "output_padding", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "output_padding" } },
        { "parameter": "device_mode", "target": { "match": "classType", "classType": "InpaintCropImproved", "field": "device_mode" } },
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

    public Qwen21InpaintImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        ImageEditInput input = in == null ? new ImageEditInput() : in;

        // LoRA 已收拢进 input（服务端由 loraNames 通道解析回填）
        List<LoraBundle> loras = input.getLoras() == null ? List.of() : input.getLoras();

        // 1. 原图 / 遮罩图 → 两个 LoadImage（同 classType 双节点，按 title 分别绑定）
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }
        if (StrUtil.isNotBlank(input.getMaskImage())) {
            applyBinding(api, "mask_image", input.getMaskImage());
        }

        // 2. 遮罩选取通道（请求参数优先，其次 paramSchema 声明的默认值，最后工作流默认）
        applyBinding(api, "mask_channel", resolveMaskChannel(input));

        // 3. 裁剪几何：遮罩膨胀 / 边缘羽化 / 上下文扩展倍数
        //     膨胀+羽化后的裁剪遮罩同时决定：SetLatentNoiseMask 的加噪范围、缝合时「哪块换成重绘结果」，
        //     以及涂色分支开启时的涂色范围与色键限域。
        if (input.getMaskExpandPixels() != null) {
            applyBinding(api, "mask_expand_pixels", input.getMaskExpandPixels());
        }
        if (input.getMaskBlendPixels() != null) {
            applyBinding(api, "mask_blend_pixels", input.getMaskBlendPixels());
        }
        applyBinding(api, "context_extend_factor", resolveContextExtendFactor(input));

        // 3.1 采样目标尺寸（cropTargetSize）：裁剪区被重采样到「该边长 × 该边长」再送模型采样。
        //     ⚠️ 这是**精确尺寸**（不是上限），也是本插件相对旧版最大的质量改动 —— 旧版接「输入图尺寸」
        //     会让模型在 ~2MP 画布上采样（远超 Qwen 2.1 约 1MP 原生分辨率）并为了凑宽高比把上下文撑到近整图。
        int cropTarget = resolveCropTargetSize(input);
        applyBinding(api, "crop_target_width", cropTarget);
        applyBinding(api, "crop_target_height", cropTarget);

        // 3.2 尺寸对齐倍数：把裁剪目标向上取整到 32 的倍数，保证裁剪图宽高能被 VAE 整除（下采样 8×）
        applyBinding(api, "output_padding", resolveOutputPadding());

        // 3.3 计算设备（InpaintCropImproved.device_mode）：extra 配置（插件作者可改，默认 cpu）
        applyBinding(api, "device_mode", extraStr(EXTRA_DEVICE_MODE, FALLBACK_DEVICE_MODE));

        // 4. 参考图缩放预算（内部固定 0：保持各参考图自身尺寸，保证画布与 image_1 同尺寸）
        applyBinding(api, "reference_resolution", resolveReferenceResolution());

        // 5. 插件固有配置（adapter.json extra）：文本编码器（模型槽优先）
        applyBinding(api, "clip_name", extraStr(EXTRA_CLIP_NAME, FALLBACK_CLIP_NAME));

        // 6. 模型槽（UI 选择 / 持久化 model.selection.json）压过第 5 步的 extra 默认
        applyModelSlots(api);

        // 7. 提示词（局部重绘指令，描述裁剪区最终该长什么样）
        if (StrUtil.isNotBlank(input.getPrompt())) {
            applyBinding(api, "prompt", input.getPrompt().trim());
        }
        String negativePrompt = optStr(input, "negativePrompt", "negative_prompt");
        if (StrUtil.isNotBlank(negativePrompt)) {
            applyBinding(api, "negative_prompt", negativePrompt);
        }

        // 8. 缓存设备 / 精度 / 保存格式（paramSchema 私有参数 → options）
        String cacheDevice = optStr(input, "cacheDevice", "cache_device");
        if (StrUtil.isNotBlank(cacheDevice)) {
            applyBinding(api, "cache_device", cacheDevice);
        }
        String dtype = optStr(input, "dtype");
        if (StrUtil.isNotBlank(dtype)) {
            applyBinding(api, "dtype", dtype);
        }
        String saveFormat = optStr(input, "saveFormat", "save_format");
        if (StrUtil.isNotBlank(saveFormat)) {
            applyBinding(api, "save_format", saveFormat);
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

        // 11. LoRA 动态串联：按 binding 的 lora.startNodes（UNETLoader）在其消费者之间插入
        //      LoraLoaderModelOnly；空列表完全不改链路
        applyLorasFromBinding(api, loras);

        // 12. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 13. 额外参考图（ImageEditInput.referenceImage）：挂到 TextEncode 的下一个空槽位 image_2。
        //     服务端已按 referenceImagePath 上传并回填文件名；为空 = 不接该槽位（LoadImage 节点随后被剪掉）。
        //     ⚠️ image_1 / 缝合输入的接线是 workflow.json 里的静态接线（干净裁剪图 / 解码结果），
        //     本适配器不再改写 —— 这里只有「参考图」是动态的。
        attachReferenceImage(api, input);

        // 14. 校验遮罩必填（避免交付「无选区重绘」的全图重绘结果）
        validateMask(input);

        // 15. 剪掉不可达节点，保证 SaveImageAdvanced 单输出语义
        //     （没传参考图时，那个 LoadImage 无人引用 ⇒ 一并剪掉）
        pruneUnreachable(api, resolveNodeIdByClassType(api, NODE_SAVE));

        return api;
    }

    // ===================== 参考图接线 =====================

    /**
     * 把额外参考图接到 {@code TextEncodeQwenImage21.images.image_2}。
     *
     * <p>参考图 LoadImage 节点是 workflow.json 里的固定节点（{@code 40}），这里只写文件名 + 接线；
     * 未传参考图时该节点无人引用、会被 {@link #pruneUnreachable} 剪掉，ComfyUI 不会执行到它。
     * 节点号不写死：按 {@code reference_image} 绑定定位，用户换工作流 / 换 binding 后自动跟随。</p>
     *
     * <p>⚠️ 槽位号必须是 <b>2</b>（{@code image_1} 已被裁剪图占用）。Qwen Image 2.1 的
     * {@code TextEncodeQwenImage21} 支持 {@code image_1..N}，提示词里可用 {@code <image2>} 指代本图。</p>
     */
    private void attachReferenceImage(JSONObject api, ImageEditInput input) {
        if (StrUtil.isBlank(input.getReferenceImage())) {
            return;
        }
        String textId = tryResolveClassType(api, NODE_TEXT_ENCODE);
        if (textId == null) {
            log.warn("[Qwen21Inpaint] 工作流缺少 {}，参考图无法接线，已忽略", NODE_TEXT_ENCODE);
            return;
        }
        String[] target;
        try {
            target = resolveNodeTarget(api, BINDING_REFERENCE_IMAGE);
        } catch (IllegalArgumentException e) {
            log.warn("[Qwen21Inpaint] 参考图 LoadImage 节点定位失败（binding 的 {} 未命中），已忽略本次参考图: {}",
                    BINDING_REFERENCE_IMAGE, e.getMessage());
            return;
        }
        if (target == null || target.length == 0 || StrUtil.isBlank(target[0])) {
            log.warn("[Qwen21Inpaint] binding 未声明 {}，参考图无法接线，已忽略", BINDING_REFERENCE_IMAGE);
            return;
        }
        applyBinding(api, BINDING_REFERENCE_IMAGE, input.getReferenceImage());
        api.getJSONObject(textId).getJSONObject("inputs")
                .set(INPUT_IMAGE_SLOT_PREFIX + "2", List.of(target[0], 0));
    }

    /** 按 classType 定位节点号；未命中返回 {@code null}（不抛，供「可选分支」探测用）。 */
    private static String tryResolveClassType(JSONObject api, String classType) {
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node != null && classType.equals(node.getStr("class_type"))) {
                return id;
            }
        }
        return null;
    }

    // ===================== 参数解析（私有参数 / extra） =====================

    /**
     * 遮罩选取通道（{@code ImageToMask.channel}）：{@code red} / {@code green} / {@code blue} /
     * {@code alpha} / {@code luminance}。
     *
     * <p>取值链：显式字段 {@link ImageEditInput#getMaskChannel()} → {@code ImageEditInput.options}
     * 的 {@code maskChannel / mask_channel}（前端弹层实际改的值）→ {@code adapter.json} 的
     * {@code extra.paramSchema[maskChannel].defaultValue}（用户可见默认值的<b>唯一</b>声明源，
     * 见 {@link #paramSchemaDefault}）→ 工作流图内默认。</p>
     *
     * <p>这里刻意<b>不</b>另设 {@code extra.defaultMaskChannel}：同一个默认值在
     * {@code extra} 与 {@code paramSchema} 各写一遍，就会出现「前端播种的是 A、适配器兜底的是 B」，
     * 改一处忘一处。</p>
     */
    private String resolveMaskChannel(ImageEditInput input) {
        String v = StrUtil.isNotBlank(input.getMaskChannel())
                ? input.getMaskChannel().trim()
                : optStr(input, "maskChannel", "mask_channel");
        if (StrUtil.isBlank(v)) {
            v = paramSchemaDefault("maskChannel");
        }
        return StrUtil.isBlank(v) ? FALLBACK_DEFAULT_MASK_CHANNEL : v;
    }

    /**
     * 送模型的裁剪采样边长（正方形，{@code InpaintCropImproved.output_target_width/height}）。
     *
     * <p>取值链：{@code ImageEditInput.options} 的 {@code cropTargetSize / crop_target_size}
     * → {@code adapter.json} 的 {@code extra.paramSchema[cropTargetSize].defaultValue}
     * → {@link #FALLBACK_CROP_TARGET_SIZE}。越界值 clamp 到 {@value #MIN_CROP_TARGET_SIZE}~{@value #MAX_CROP_TARGET_SIZE}，
     * 再向上对齐到 {@link #ALIGNMENT_STEP} 的倍数（节点侧还有 {@code output_padding} 兜底对齐）。</p>
     */
    private int resolveCropTargetSize(ImageEditInput input) {
        String raw = optStr(input, PARAM_CROP_TARGET_SIZE, "crop_target_size");
        if (StrUtil.isBlank(raw)) {
            raw = paramSchemaDefault(PARAM_CROP_TARGET_SIZE);
        }
        int v = FALLBACK_CROP_TARGET_SIZE;
        if (StrUtil.isNotBlank(raw)) {
            try {
                v = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                log.warn("[Qwen21Inpaint] cropTargetSize={} 无法解析为整数，回退 {}", raw, FALLBACK_CROP_TARGET_SIZE);
                v = FALLBACK_CROP_TARGET_SIZE;
            }
        }
        if (v < MIN_CROP_TARGET_SIZE || v > MAX_CROP_TARGET_SIZE) {
            int clamped = Math.clamp(v, MIN_CROP_TARGET_SIZE, MAX_CROP_TARGET_SIZE);
            log.warn("[Qwen21Inpaint] cropTargetSize={} 越界（{}~{}），已 clamp 到 {}",
                    v, MIN_CROP_TARGET_SIZE, MAX_CROP_TARGET_SIZE, clamped);
            v = clamped;
        }
        // 向上对齐到 32 的倍数：裁剪图同时是采样画布 VAEEncode 的输入，宽高必须能被 VAE 整除（下采样 8×）
        int aligned = ((v + ALIGNMENT_STEP - 1) / ALIGNMENT_STEP) * ALIGNMENT_STEP;
        if (aligned != v) {
            log.debug("[Qwen21Inpaint] cropTargetSize={} 向上对齐到 {}", v, aligned);
        }
        return aligned;
    }

    /**
     * 上下文扩展倍数（{@code InpaintCropImproved.context_from_mask_extend_factor}）：把「基于遮罩算出的
     * 上下文框」向四周按倍数放大，为模型提供额外参考。官方工作流用 1.2（= 每边多取 10%）。
     *
     * <p>取值链：{@code ImageEditInput.options} 的 {@code contextExtendFactor / context_extend_factor}
     * → {@code adapter.json} 的 {@code extra.contextExtendFactor} → 1.2。小于 1 一律按 1 处理
     * （节点侧 {@code < 1.01} 即不扩展）。</p>
     */
    private double resolveContextExtendFactor(ImageEditInput input) {
        Double requested = toDouble(optRaw(input, "contextExtendFactor", "context_extend_factor"));
        Object extra = getMetaExtra(EXTRA_CONTEXT_EXTEND_FACTOR);
        Double fallback = toDouble(extra);
        double v = requested != null ? requested : (fallback != null ? fallback : FALLBACK_CONTEXT_EXTEND_FACTOR);
        if (v < 1.0) {
            log.warn("[Qwen21Inpaint] contextExtendFactor={} < 1（节点按不扩展处理），已归一到 1.0", v);
            return 1.0;
        }
        return v;
    }

    /**
     * 读 {@code adapter.json → extra.paramSchema[]} 里指定 key 的 {@code defaultValue}。
     *
     * <p>把「用户可见的默认值」收敛到一处：paramSchema 既是前端 {@code ParamForm} 的播种来源，
     * 也是适配器缺省时的兜底来源，避免同一个默认值在 Java 常量、{@code extra}、{@code paramSchema}
     * 里各写一遍、改一处忘一处。</p>
     *
     * @return 该 key 的 {@code defaultValue}；paramSchema 缺失 / 无此项 / defaultValue 为空时返回 {@code null}
     */
    public String paramSchemaDefault(String key) {
        Object raw = getMetaExtra("paramSchema");
        if (!(raw instanceof Iterable<?> items)) {
            return null;
        }
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> item)) {
                continue;
            }
            if (!key.equals(String.valueOf(item.get("key")))) {
                continue;
            }
            Object dv = item.get("defaultValue");
            if (dv == null) {
                return null;
            }
            String s = String.valueOf(dv).trim();
            return StrUtil.isBlank(s) ? null : s;
        }
        return null;
    }

    /**
     * 裁剪目标的尺寸对齐倍数（{@code InpaintCropImproved.output_padding}）：必须是 32 的倍数，
     * 保证裁剪图（同时是 image_1 与采样画布 VAEEncode 的输入）宽高规整、
     * 能被 VAE 整除（下采样 8×）。取到非法值时降级为 32 并 WARN（对齐是硬约束，宁可覆盖部署者的错误配置）。
     */
    private String resolveOutputPadding() {
        String v = extraStr(EXTRA_OUTPUT_PADDING, FALLBACK_OUTPUT_PADDING);
        if (!ALIGNED_PADDING_OPTIONS.contains(v)) {
            log.warn("[Qwen21Inpaint] extra.{}={} 不是 {} 的倍数（合法值 {}），已回退 {}；"
                            + "否则裁剪图尺寸不是 {} 的倍数，无法被 VAE 整除、latent 几何也会不规整",
                    EXTRA_OUTPUT_PADDING, v, ALIGNMENT_STEP, ALIGNED_PADDING_OPTIONS,
                    FALLBACK_OUTPUT_PADDING, ALIGNMENT_STEP);
            return FALLBACK_OUTPUT_PADDING;
        }
        return v;
    }

    /**
     * 参考图缩放预算（{@code TextEncodeQwenImage21.resolution}）：本插件必须为 0。
     *
     * <p>采样画布来自 {@code VAEEncode(裁剪图)}，与 {@code image_1}（同一张裁剪图）天然同尺寸。
     * 一旦 {@code resolution} 非 0，{@code image_1} 会被二次缩放，而画布与缝合节点
     * （{@code InpaintCropImproved} 的 stitcher）仍按裁剪输出的几何工作，
     * 两者不再对应 → 重绘内容会被缝到错误的位置/比例。</p>
     *
     * <p>追加的 {@code image_2} 参考图尺寸可以与 {@code image_1} 不同：{@code resolution=0} 下
     * 节点按各图自身尺寸编码（官方 image-edit 模板即「人像 + 衣服」两张不同尺寸的图），
     * 参考图不参与画布几何。</p>
     */
    private int resolveReferenceResolution() {
        Integer v = extraInt(EXTRA_REFERENCE_RESOLUTION, FALLBACK_REFERENCE_RESOLUTION);
        int resolution = v == null ? FALLBACK_REFERENCE_RESOLUTION : v;
        if (resolution != FALLBACK_REFERENCE_RESOLUTION) {
            log.warn("[Qwen21Inpaint] extra.{}={}（非 0）会二次缩放 image_1；"
                            + "而采样画布与缝合节点仍按裁剪输出几何工作，两者不再对应 → 重绘结果会错位",
                    EXTRA_REFERENCE_RESOLUTION, resolution);
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

    /** 读 adapter.json extra 的整数配置；缺失/非数字时返回 {@code fallback}。 */
    private Integer extraInt(String key, Integer fallback) {
        Object v = getMetaExtra(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            log.warn("[Qwen21Inpaint] extra.{}={} 无法解析为整数，回退 {}", key, v, fallback);
            return fallback;
        }
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

    // ===================== 校验 / 清理 =====================

    /**
     * 校验遮罩输入非空，避免「遮罩图为空」导致模型收到空选区而非局部重绘的误用。
     * 缺遮罩时工作流的 LoadImage 会拿到空文件名、由 ComfyUI 运行时报错，这里只留可读告警。
     */
    private static void validateMask(ImageEditInput input) {
        if (StrUtil.isNotBlank(input.getMaskImage())) {
            return;
        }
        log.warn("[Qwen21Inpaint] 未提供 maskImage（遮罩图），将按工作流自带遮罩重绘，请确认这是预期行为");
    }

    /**
     * 剪掉从根节点（SaveImageAdvanced）不可达的节点。
     * <p>ComfyUI 不执行不可达节点，但 history.outputs 里若混入 PreviewImage 会把结果解析到 temp 图，
     * 故显式清理，保证「单输出」语义。本插件还借它做<b>分支裁剪</b>：未选中的那条
     * （涂色/色键兜底，或未被使用的参考图 LoadImage）整支不可达、会被一并剪掉。</p>
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
