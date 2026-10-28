package io.dramatrix.plugin.seedvr2;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.ImageEditInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.ImageEditPlugin;

/**
 * SeedVR2 3B INT8 图像修复/超清适配器。
 * 对应 workflow: SeedVR2 3B Int8 Upscale Image（ComfyUI 原生模板）。
 *
 * <p>链路：LoadImage → JoinImageWithAlpha → lanczos 预放大（scale by multiplier）
 * → SeedVR2Preprocess → VAEEncodeTiled → KSampler（单步，steps=1/cfg=1）
 * → VAEDecodeTiled → SeedVR2PostProcessing（色彩校正）→ SaveImage。</p>
 *
 * <p>无提示词参与，因此实现 {@link ImageEditPlugin}（不带 buildPrompt）；
 * 全部语义参数经绑定（workflow.binding.json / 内置默认）落到节点字段，
 * 输入 DTO 字段均为包装类型，{@code null} 表示保留工作流自带默认值。</p>
 */
public class SeedVR2UpscaleImageAdapter extends AbstractComfyUIPlugin implements ImageEditPlugin {

    /** 内置默认绑定：对应默认 workflow.json 的节点（按 classType 定位）。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "image", "target": { "match": "classType", "classType": "LoadImage", "field": "image" } },
        { "parameter": "scale_multiplier", "target": { "match": "classType", "classType": "ResizeImageMaskNode", "field": "resize_type.multiplier" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "sampler_name", "target": { "match": "classType", "classType": "KSampler", "field": "sampler_name" } },
        { "parameter": "scheduler", "target": { "match": "classType", "classType": "KSampler", "field": "scheduler" } },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" },
        { "parameter": "unet_name", "target": { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } },
        { "parameter": "vae_name", "target": { "match": "classType", "classType": "VAELoader", "field": "vae_name" } },
        { "parameter": "color_correction_method", "target": { "match": "classType", "classType": "SeedVR2PostProcessing", "field": "color_correction_method" } }
      ]
    }
    """;

    public SeedVR2UpscaleImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(ImageEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        ImageEditInput input = in == null ? new ImageEditInput() : in;

        // 1. 输入图像 → LoadImage
        if (StrUtil.isNotBlank(input.getImage())) {
            applyBinding(api, "image", input.getImage());
        }

        // 2. 可选参数覆盖（null = 保留工作流默认）
        if (input.getScaleMultiplier() != null) {
            applyBinding(api, "scale_multiplier", input.getScaleMultiplier());
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
        if (StrUtil.isNotBlank(input.getUnetName())) {
            applyBinding(api, "unet_name", input.getUnetName());
        }
        if (StrUtil.isNotBlank(input.getVaeName())) {
            applyBinding(api, "vae_name", input.getVaeName());
        }
        if (StrUtil.isNotBlank(input.getColorCorrectionMethod())) {
            applyBinding(api, "color_correction_method", input.getColorCorrectionMethod());
        }

        // 3. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 4. 模型槽（binding.modelSlots 未配置时 no-op，预留模型选择能力）
        applyModelSlots(api);

        return api;
    }
}
