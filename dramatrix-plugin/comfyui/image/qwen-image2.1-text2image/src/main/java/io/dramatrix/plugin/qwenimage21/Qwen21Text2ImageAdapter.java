package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;

/**
 * Qwen Image 2.1 文生图适配器。
 *
 * <p>对应 ComfyUI 原生文生图工作流（参考 workspace/image_qwen_image_2_1_t2i (1).json）：
 * TextEncodeQwenImage21 将 prompt/negative_prompt 编码为 KSampler 的正负条件，
 * EmptyLatentImage 提供画布（宽高可绑定），UNETLoader 直接作为模型（该 T2I 工作流无 cache 节点）。</p>
 *
 * <p>节点落点由「绑定（workflow.binding.json / 内置默认）」决定，不再硬编码在代码里：
 * 用户可在 UI 换自己的工作流并重新绑定 prompt/negative/lora/尺寸/seed。</p>
 */
public class Qwen21Text2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    /** 内置默认绑定：对应默认 workflow.json（Qwen Image 2.1 文生图标准流）的节点。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "prompt" } },
        { "parameter": "negative_prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "negative_prompt" } },
        { "parameter": "width", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "width" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "height" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "save_format", "target": { "match": "classType", "classType": "SaveImageAdvanced", "field": "format" } }
      ],
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      }
    }
    """;

    public Qwen21Text2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        // 1. 正向提示词 → 绑定 prompt
        applyBinding(api, "prompt", input.getPrompt() != null ? input.getPrompt() : "");

        // 2. 负向提示词 → 绑定 negative_prompt（未填则置空串）
        applyBinding(api, "negative_prompt", input.getNegativePrompt() != null ? input.getNegativePrompt() : "");

        // 3. LoRA → 原生 LoraLoader 串联（空列表自动跳过，不动工作流）
        applyLorasFromBinding(api, input.getLoras());

        // 4. steps / cfg → 绑定 KSampler（null 则保留工作流默认）
        Integer steps = input.getSteps();
        if (steps != null) {
            applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg();
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }

        // 5. 宽高 → 绑定 width/height
        if (input.getWidth() != null && input.getWidth() > 0) {
            applyBinding(api, "width", input.getWidth());
        }
        if (input.getHeight() != null && input.getHeight() > 0) {
            applyBinding(api, "height", input.getHeight());
        }

        // 6. Seed → 绑定 seed（空/0 则随机）
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", seed);

        // 7. 保存格式 → SaveImageAdvanced.format（png/exr/avif，来自 options.saveFormat）
        String saveFormat = toStringOrNull(optionsValue(input, "saveFormat"));
        if (StrUtil.isBlank(saveFormat)) {
            saveFormat = toStringOrNull(optionsValue(input, "save_format"));
        }
        if (StrUtil.isNotBlank(saveFormat)) {
            applyBinding(api, "save_format", saveFormat);
        }

        // 模型槽注入：按当前选择覆盖模型名（无 modelSlots 时 no-op）
        applyModelSlots(api);

        return api;
    }

    /** 从 options 中按 key 取值（null-safe）。 */
    private static Object optionsValue(Text2ImageInput input, String key) {
        return input.getOptions() == null ? null : input.getOptions().get(key);
    }

    private static String toStringOrNull(Object v) {
        return v == null ? null : v.toString();
    }
}