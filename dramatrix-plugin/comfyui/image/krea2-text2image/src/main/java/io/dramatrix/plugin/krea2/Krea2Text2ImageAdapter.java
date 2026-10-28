package io.dramatrix.plugin.krea2;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;

import java.util.Objects;

/**
 * Krea2 白玉 文生图适配器。
 * 对应 workflow: krea2-白玉-文生图.json（plugins/krea2-text2image/workflow.json）。
 * <p>
 * 节点映射：
 * <ul>
 *   <li>2  CheckpointLoaderSimple → model/clip 源，作为 LoRA 起始节点（startNodes）</li>
 *   <li>2  CheckpointLoaderSimple → Krea2-turbo-White_Marble-AIO_V1_BF16</li>
 *   <li>3  CLIPTextEncode         → 正向提示词</li>
 *   <li>4  KSampler               → seed（steps=6 / cfg=1 turbo 蒸馏配置）</li>
 *   <li>5  CLIPTextEncode         → 负向提示词</li>
 *   <li>6  EasySizeSimpleSetting  → 宽度 / 高度</li>
 *   <li>7  EmptyFlux2LatentImage  → 由节点 6 驱动，无需直接改写</li>
 * </ul>
 */
public class Krea2Text2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    /** 内置默认绑定：对应默认 workflow.json 的节点（按 title/classType 定位）。 */
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
          { "match": "nodeId", "nodeId": "2" }
        ]
      }
    }
    """;

    public Krea2Text2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        // 1. 正向提示词 → 绑定 prompt（CLIPTextEncode）
        if (StrUtil.isNotBlank(input.getPrompt())) {
            applyBinding(api, "prompt", input.getPrompt());
        }

        // 2. 负向提示词 → 绑定 negative_prompt
        // Krea2 / White Marble 为 FLUX 系底座，不支持否定提示(negative prompt)；
        // 无条件清空并覆盖 workflow 硬编码默认值；保留负向连接（工作流要求必须有否定链接），文本置空串。
        applyBinding(api, "negative_prompt", "");

        // 3. LoRA → 原生 LoraLoader 串联（空列表自动跳过，不动工作流）
        applyLorasFromBinding(api, input.getLoras());

        // 4. 宽高 → 绑定 width/height（EasySizeSimpleSetting），需同时开启自定义尺寸开关才会生效
        boolean hasWidth = input.getWidth() != null && input.getWidth() > 0;
        boolean hasHeight = input.getHeight() != null && input.getHeight() > 0;
        if (hasWidth) {
            applyBinding(api, "width", input.getWidth());
        }
        if (hasHeight) {
            applyBinding(api, "height", input.getHeight());
        }
        if (hasWidth || hasHeight) {
            editApiJsonNode(api, Objects.requireNonNull(resolveNodeTarget(api, "width"))[0] + ".inputs.启用自定义尺寸", true);
        }

        // 4.5 steps / cfg → 绑定 KSampler（null 则保留 turbo 蒸馏工作流默认）
        Integer steps = input.getSteps();
        if (steps != null) {
            applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg();
        if (cfg != null) {
            applyBinding(api, "cfg", cfg);
        }

        // 5. Seed → 绑定 seed（空/0 则随机）
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? cn.hutool.core.util.RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs(seed));

        // 模型槽注入：按当前选择覆盖 CheckpointLoaderSimple.ckpt_name（无 modelSlots 时 no-op）
        applyModelSlots(api);

        return api;
    }
}
