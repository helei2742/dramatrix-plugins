package io.dramatrix.plugin.mossimage;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;

/** MOSS-Image 文生图适配器。 */
public class MossImageText2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "TextGenerate", "nodeId": "4", "field": "prompt" } },
        { "parameter": "raw_prompt", "target": { "match": "classType", "classType": "ComfySwitchNode", "nodeId": "5", "field": "on_false" } },
        { "parameter": "negative_prompt", "target": { "match": "classType", "classType": "CLIPTextEncode", "nodeId": "8", "field": "text" } },
        { "parameter": "width", "target": { "match": "classType", "classType": "EmptyFlux2LatentImage", "field": "width" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "classType", "classType": "EmptyFlux2LatentImage", "field": "height" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "prompt_extend", "target": { "match": "classType", "classType": "ComfySwitchNode", "nodeId": "5", "field": "switch" } }
      ],
      "lora": {
        "startNodes": [
          { "match": "nodeId", "nodeId": "1" }
        ]
      },
      "modelSlots": [
        {
          "group": "moss-image-diffusion",
          "title": "MOSS-Image 扩散模型",
          "default": "moss_image_sft_int8_convrot.safetensors",
          "targets": [
            { "match": "classType", "classType": "UNETLoader", "field": "unet_name" }
          ]
        },
        {
          "group": "moss-image-text-encoder",
          "title": "MOSS-Image 文本编码器",
          "default": "qwen3.5_9b_w4a8_gptq.safetensors",
          "targets": [
            { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" }
          ]
        },
        {
          "group": "moss-image-vae",
          "title": "VAE",
          "default": "flux2-vae_small.safetensors",
          "targets": [
            { "match": "classType", "classType": "VAELoader", "field": "vae_name" }
          ]
        }
      }
    }
    """;

    public MossImageText2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);
        String prompt = input.getPrompt() == null ? "" : input.getPrompt();

        // The enhancer and the raw-prompt branch both receive the caller's prompt.
        applyBinding(api, "prompt", prompt);
        applyBinding(api, "raw_prompt", prompt);

        // An empty negative prompt keeps the model-specific default from workflow.json.
        if (StrUtil.isNotBlank(input.getNegativePrompt())) {
            applyBinding(api, "negative_prompt", input.getNegativePrompt());
        }

        applyLorasFromBinding(api, input.getLoras());

        if (input.getSteps() != null) {
            applyBinding(api, "steps", input.getSteps());
        }
        if (input.getCfg() != null) {
            applyBinding(api, "cfg", input.getCfg());
        }
        if (input.getWidth() != null && input.getWidth() > 0) {
            applyBinding(api, "width", input.getWidth());
        }
        if (input.getHeight() != null && input.getHeight() > 0) {
            applyBinding(api, "height", input.getHeight());
        }

        int seed = input.getSeed() == null || input.getSeed() == 0
                ? RandomUtil.randomInt(0, Integer.MAX_VALUE)
                : input.getSeed();
        applyBinding(api, "seed", seed);

        Boolean promptExtend = input.getPromptExtend();
        if (promptExtend == null) {
            Object option = option(input, "promptExtend");
            if (option == null) {
                option = option(input, "enable_prompt_upsampler");
            }
            promptExtend = asBoolean(option);
        }
        if (promptExtend != null) {
            applyBinding(api, "prompt_extend", promptExtend);
        }

        applyModelSlots(api);
        return api;
    }

    private static Object option(Text2ImageInput input, String key) {
        return input.getOptions() == null ? null : input.getOptions().get(key);
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.parseBoolean(text);
            }
        }
        return null;
    }
}
