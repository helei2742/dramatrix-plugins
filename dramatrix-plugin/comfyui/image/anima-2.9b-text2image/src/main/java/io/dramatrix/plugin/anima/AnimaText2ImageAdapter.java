package io.dramatrix.plugin.anima;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;

import java.util.Map;

/**
 * Anima 2.9B 文生图适配器。
 *
 * <p>基于 NVIDIA Cosmos 架构的动漫/非写实插画模型，文本编码器为 Qwen3-0.6B，
 * 支持自然语言 + Danbooru 标签混合输入；不接受任何输入图像（纯文生图）。</p>
 *
 * <p>工作流：UNETLoader(Anima-2.9B) + CLIPLoader(qwen_3_06b) + VAELoader(qwen_image_vae)
 * → ModelSamplingAuraFlow(shift) → KSampler（UNETLoader 13 作为 LoRA 起始节点）。分辨率需为 16 的倍数。</p>
 */
public class AnimaText2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    /** 内置默认绑定：对应默认 workflow.json 的节点（按 title/classType 定位）。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        {
          "parameter": "prompt",
          "target": {
            "match": "title",
            "title": "正向提示词",
            "field": "text"
          }
        },
        {
          "parameter": "negative_prompt",
          "target": {
            "match": "title",
            "title": "负向提示词",
            "field": "text"
          }
        },
        {
          "parameter": "unet_name",
          "target": {
            "match": "title",
            "title": "加载扩散模型",
            "field": "unet_name"
          }
        },
        {
          "parameter": "steps",
          "target": {
            "match": "classType",
            "classType": "KSampler",
            "field": "steps"
          },
          "transform": "int"
        },
        {
          "parameter": "cfg",
          "target": {
            "match": "classType",
            "classType": "KSampler",
            "field": "cfg"
          },
          "transform": "float"
        },
        {
          "parameter": "seed",
          "target": {
            "match": "classType",
            "classType": "KSampler",
            "field": "seed"
          },
          "transform": "int"
        },
        {
          "parameter": "width",
          "target": {
            "match": "title",
            "title": "空Latent图像",
            "field": "width"
          },
          "transform": "int"
        },
        {
          "parameter": "height",
          "target": {
            "match": "title",
            "title": "空Latent图像",
            "field": "height"
          },
          "transform": "int"
        }
      ],
      "lora": {
        "startNodes": [
          {
            "match": "nodeId",
            "nodeId": "13"
          }
        ]
      }
    }
    """;

    public AnimaText2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();

        // 1. 正向提示词 → 绑定 prompt（Anima 接受自然语言 / Danbooru 标签 / @画师）
        if (StrUtil.isNotBlank(input.getPrompt())) {
            applyBinding(api, "prompt", input.getPrompt());
        }

        // 2. 负向提示词 → 绑定 negative_prompt：有则覆盖，无则保留工作流默认
        if (StrUtil.isNotBlank(input.getNegativePrompt())) {
            applyBinding(api, "negative_prompt", input.getNegativePrompt());
        }

        // 3. LoRA → 原生 LoraLoader 串联（空列表自动跳过，不动工作流）
        applyLorasFromBinding(api, input.getLoras());

        // 4. 可配置参数：unet_name / steps / cfg（优先 input.options，回退 adapter.json extra）
        String unet = resolveString(opts, "unet_name");
        if (StrUtil.isNotBlank(unet)) {
            applyBinding(api, "unet_name", unet);
        }
        Integer steps = input.getSteps();
        if (steps != null) {
            applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg();
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }

        // 5. 宽高 → 绑定 width/height；Anima 要求宽高为 16 的倍数
        if (input.getWidth() != null && input.getWidth() > 0) {
            applyBinding(api, "width", input.getWidth());
        }
        if (input.getHeight() != null && input.getHeight() > 0) {
            applyBinding(api, "height", input.getHeight());
        }

        // 6. Seed → 绑定 seed（空/0 则随机）
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? cn.hutool.core.util.RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs(seed));

        // 7. 模型槽注入：按当前选择覆盖 UNETLoader/CLIPLoader/VAELoader 的模型字段（无 modelSlots 时 no-op）。
        //    放在最后，优先于 extra 默认（第 4 步的 unet_name）；未选择时保持原值。
        applyModelSlots(api);

        return api;
    }

    /**
     * 读取可配置参数：优先 input.options（运行时透传），回退到 adapter.json 的 extra（元数据默认值）。
     */
    private String resolveString(Map<String, Object> opts, String key) {
        Object runtime = opts.get(key);
        if (runtime != null && StrUtil.isNotBlank(String.valueOf(runtime))) {
            return String.valueOf(runtime);
        }
        Object meta = getMetaExtra(key);
        return meta != null ? String.valueOf(meta) : null;
    }

    private Double resolveNumber(Map<String, Object> opts, String key) {
        Object v = opts.get(key);
        if (v == null) {
            v = getMetaExtra(key);
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && StrUtil.isNotBlank(s)) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}