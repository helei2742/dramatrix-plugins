package io.dramatrix.plugin.minimaxh3t2v;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Text2VideoInput;

import java.util.List;

/**
 * MiniMax H3 文生视频的工作流（API JSON）拼接类。
 * <p>
 * 职责单一：基于默认 {@code workflow.json}（经绑定机制声明的参数落点）写入提示词 /
 * 宽高 / 帧数 / 时长 / seed / 步数 / 用户 LoRA / 模型槽，
 * 产出可直接 POST 到 ComfyUI {@code /prompt} 的 dict。
 *
 * <h3>本工作流的三个要点</h3>
 * <ol>
 *   <li><b>纯文生视频 = 「不接首尾帧」的 fl2va</b>：主节点是
 *       {@code MiniMaxH3ImageToVideo}，但 {@code image} 输入不接线，画面完全由 prompt 驱动，
 *       成片自带音轨（{@code VAEDecodeAudio → CreateVideo.audio}）。</li>
 *   <li><b>宽高 / 帧数直接落主节点</b>：工作流里不挂 ResolutionSelector / PrimitiveFloat +
 *       数学表达式，{@link Text2VideoInput} 已解析好的 {@code width / height / length}
 *       直接写 {@code MiniMaxH3ImageToVideo} 的 inputs；未给帧数时由 Java 按
 *       {@code duration × fps}（缺省 24fps）换算。</li>
 *   <li><b>模板不带任何 LoRA</b>：{@code UNETLoader} 直连 {@code BasicScheduler / BasicGuider}，
 *       用户 LoRA 由 {@code binding.lora.startNodes}（UNETLoader）在运行时动态串进去——
 *       无 LoRA 时完全不改链路，工作流文件里也不会多出一个"永远没生效"的 LoRA 节点。</li>
 * </ol>
 */
final class MiniMaxH3T2VApiJsonBuilder {

    // ======================== Sage Attention 加速开关 ========================

    private static final String SAGE_ATTENTION_OFF = "disabled";

    private static final String SAGE_ATTENTION_ON = "auto";

    private static final List<String> SAGE_ATTENTION_KEYS = List.of("sageAttention", "sage_attention");

    /**
     * 内置默认绑定：与插件目录下的 {@code workflow.binding.json} 内容一致，
     * 仅在该文件缺失时兜底（{@code ensureDefaultBinding} 只在 {@code binding == null} 时生效）。
     * <p>
     * 必须是<b>完整可解析的 JSON 对象</b>——写成 {@code "bindings": [...]} 这种片段会在
     * 兜底分支上直接抛 {@code A JSONObject text must begin with '{'}，把整条生成链炸掉。
     * <p>
     * 文生视频没有参考资源，因此 {@code anchor.groups} 为空数组：anchor 在这里只用来
     * <b>定位主节点</b>，不承担挂载职责。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "MiniMaxH3ImageToVideo", "field": "prompt" } },
        { "parameter": "seed", "target": { "match": "classType", "classType": "RandomNoise", "field": "noise_seed" }, "transform": "int" },
        { "parameter": "fps", "target": { "match": "classType", "classType": "CreateVideo", "field": "fps" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "BasicScheduler", "field": "steps" }, "transform": "int" },
        { "parameter": "sage_node", "target": { "match": "classType", "classType": "PathchSageAttentionKJ" } }
      ],
      "anchor": {
        "id": "video_node",
        "schema": { "match": "classType", "classType": "MiniMaxH3ImageToVideo" },
        "groups": []
      },
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      }
    }
    """;

    private MiniMaxH3T2VApiJsonBuilder() {
    }

    static JSONObject build(MiniMaxH3T2VAdapter adapter, Text2VideoInput input) {
        adapter.ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(adapter.getWorkflowJson());

        // 1. 正向提示词 → 绑定 prompt（主节点 MiniMaxH3ImageToVideo.prompt）
        String prompt = input == null ? null : input.prompt();
        if (StrUtil.isNotBlank(prompt)) {
            adapter.applyBinding(api, "prompt", prompt);
        }

        // 2. 定位主节点（文生视频无参考资源要挂载，anchor 只用于定位）
        String main = adapter.resolveAnchorNodeId(api);
        JSONObject mainInputs = api.getJSONObject(main).getJSONObject("inputs");
        if (mainInputs == null) {
            mainInputs = new JSONObject();
            api.getJSONObject(main).set("inputs", mainInputs);
        }

        // 3. 宽高：调用方显式给出时覆盖；不给则保留工作流自带默认值（模板无 ResolutionSelector）
        if (input != null && input.getWidth() != null && input.getWidth() > 0) {
            mainInputs.set("width", input.getWidth());
        }
        if (input != null && input.getHeight() != null && input.getHeight() > 0) {
            mainInputs.set("height", input.getHeight());
        }

        // 4. 帧数：直接用 Text2VideoInput 解析好的 length；未给帧数时按 duration × fps（缺省 24）换算
        if (input != null && input.getLength() != null && input.getLength() > 0) {
            mainInputs.set("length", input.getLength());
        } else if (input != null && input.getDuration() != null && input.getDuration() > 0) {
            int fps = input.getFps() != null && input.getFps() > 0 ? input.getFps() : 24;
            mainInputs.set("length", (int) Math.round(input.getDuration() * fps));
        }

        // 5. FPS → 绑定（未绑定则 no-op）
        if (input != null && input.getFps() != null && input.getFps() > 0) {
            adapter.applyBinding(api, "fps", input.getFps());
        }

        // 6. Seed → 绑定（null / 0 随机）
        injectSeedBinding(adapter, api, "seed", input);

        // 7. 采样步数 → 绑定（不传则沿用工作流默认值）
        if (input != null && input.getSteps() != null && input.getSteps() > 0) {
            adapter.applyBinding(api, "steps", input.getSteps());
        }

        // 7.1 Sage Attention 加速开关（按 class_type 扫描，未声明 sage_node 绑定则跳过）
        applySageAttention(adapter, api, input);

        // 8. 用户 LoRA → 从 UNETLoader 起动态串联（空列表自动跳过，模板里本就没有 LoRA 节点）
        adapter.applyLorasFromBinding(api, input == null ? null : input.getLoras());

        // 模型槽注入：按当前选择覆盖 UNETLoader.unet_name / CLIPLoader.clip_name / VAELoader.vae_name
        adapter.applyModelSlots(api);

        return api;
    }

    /**
     * 随机处理后的 seed：null / 0 生成随机正整数，再按绑定写入。
     */
    private static void injectSeedBinding(MiniMaxH3T2VAdapter adapter, JSONObject api, String parameter, Text2VideoInput input) {
        if (adapter.resolveNodeTarget(api, parameter) == null) return;
        Integer s = input == null ? null : input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        adapter.applyBinding(api, parameter, Math.abs(seed));
    }

    // ======================== Sage Attention（可选 binding 节点） ========================

    /**
     * 按绑定定位 PathchSageAttentionKJ 节点并应用加速开关。未声明 sage_node 绑定则跳过。
     */
    private static void applySageAttention(MiniMaxH3T2VAdapter adapter, JSONObject api, Text2VideoInput input) {
        String[] target = adapter.resolveNodeTarget(api, "sage_node");
        if (target == null) return; // 无绑定 → 跳过
        JSONObject node = api.getJSONObject(target[0]);
        if (node == null) return;

        String mode = resolveSageAttentionMode(adapter, input);
        boolean enabled = !SAGE_ATTENTION_OFF.equals(mode);
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) return;
        inputs.set("sage_attention", mode);
        inputs.set("allow_compile", enabled);
    }

    /**
     * 解析 sage_attention 取值，优先级：input.options（单次请求覆盖） → adapter.json extra → 默认开启。
     */
    private static String resolveSageAttentionMode(MiniMaxH3T2VAdapter adapter, Text2VideoInput input) {
        Object raw = null;
        if (input != null) {
            for (String key : SAGE_ATTENTION_KEYS) {
                raw = input.option(key);
                if (raw != null) break;
            }
        }
        if (raw == null) {
            for (String key : SAGE_ATTENTION_KEYS) {
                raw = adapter.getMetaExtra(key);
                if (raw != null) break;
            }
        }
        return toSageAttentionMode(raw);
    }

    /**
     * 参数值归一化：布尔/开关字符串 → auto / disabled；其余原样透传（自定义 kernel）。
     */
    private static String toSageAttentionMode(Object raw) {
        if (raw == null) return SAGE_ATTENTION_ON;
        if (raw instanceof Boolean b) return b ? SAGE_ATTENTION_ON : SAGE_ATTENTION_OFF;

        String v = StrUtil.trim(String.valueOf(raw));
        if (StrUtil.isBlank(v)) return SAGE_ATTENTION_ON;

        return switch (v.toLowerCase()) {
            case "false", "off", "no", "0", "disable", "disabled", "none" -> SAGE_ATTENTION_OFF;
            case "true", "on", "yes", "1", "enable", "enabled", "auto" -> SAGE_ATTENTION_ON;
            default -> v;
        };
    }
}
