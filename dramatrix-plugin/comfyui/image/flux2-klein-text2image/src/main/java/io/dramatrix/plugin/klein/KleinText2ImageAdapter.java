package io.dramatrix.plugin.klein;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;

/**
 * Klein 文生图适配器（绑定驱动示例）。
 * 节点落点由「绑定（workflow.binding.json / 内置默认）」决定，不再硬编码在代码里：
 * 用户可在 UI 换自己的工作流并重新绑定 prompt/negative/lora/尺寸/seed。
 */
public class KleinText2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    /** 内置默认绑定：对应默认 workflow.json（Klein 文生图标准流）的节点号。用户未配置绑定时使用。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "title", "title": "CLIP文本编码", "field": "text" } },
        { "parameter": "negative_prompt", "target": { "match": "title", "title": "Negative Prompt", "field": "text" } },
        { "parameter": "width", "target": { "match": "title", "title": "简单尺寸设置", "field": "宽度" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "title", "title": "简单尺寸设置", "field": "高度" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" }
      ],
      "lora": {
        "startNodes": [
          { "match": "nodeId", "nodeId": "13" }
        ]
      }
    }
    """;

    public KleinText2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        // 1. 正向提示词 → 绑定 prompt
        applyBinding(api, "prompt", input.getPrompt() != null ? input.getPrompt() : "");

        // 2. 负向提示词 → 绑定 negative_prompt（未填则不覆盖工作流默认）
        applyBinding(api, "negative_prompt", input.getNegativePrompt() != null ? input.getNegativePrompt() : "");

        // 3. LoRA → 原生 LoraLoader 串联（空列表自动跳过，不动工作流）
        applyLorasFromBinding(api, input.getLoras());

        // 3.5 steps / cfg → 绑定 KSampler（null 则保留工作流默认）
        Integer steps = input.getSteps();
        if (steps != null) {
            applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg();
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }

        // 4. 宽高 → 绑定 width/height
        if (input.getWidth() != null && input.getWidth() > 0) {
            applyBinding(api, "width", input.getWidth());
        }
        if (input.getHeight() != null && input.getHeight() > 0) {
            applyBinding(api, "height", input.getHeight());
        }

        // 5. Seed → 绑定 seed（空/0 则随机）
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", seed);

        // 模型槽注入：按当前选择覆盖 UNETLoader.unet_name（无 modelSlots 时 no-op）
        applyModelSlots(api);

        return api;
    }
}
