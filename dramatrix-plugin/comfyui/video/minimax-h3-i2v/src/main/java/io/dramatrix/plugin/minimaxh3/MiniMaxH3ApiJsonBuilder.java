package io.dramatrix.plugin.minimaxh3;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Image2VideoRefInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniMax H3 图生视频的工作流（API JSON）拼接类。
 * <p>
 * 从 {@link MiniMaxH3Adapter#buildApiJson} 拆出，职责单一：基于默认 {@code workflow.json}
 * （经绑定机制声明的参数落点）写入提示词 / 宽高 / 帧数 / seed / LoRA / Sage Attention，
 * 并按 anchor 动态创建参考图/视频/音频 loader 节点接线到
 * {@code MiniMaxH3ReferenceToVideo}。依赖基类暴露的绑定能力，通过 {@link MiniMaxH3Adapter} 访问。
 */
final class MiniMaxH3ApiJsonBuilder {

    private static final List<String> AUDIO_TRIM_DURATION_KEYS = List.of("audioTrimDuration", "audio_trim_duration");

    private static final int DEFAULT_AUDIO_TRIM_DURATION = 20;

    // ======================== Sage Attention 加速开关 ========================

    private static final String SAGE_ATTENTION_OFF = "disabled";

    private static final String SAGE_ATTENTION_ON = "auto";

    private static final List<String> SAGE_ATTENTION_KEYS = List.of("sageAttention", "sage_attention");

    /**
     * 内置默认绑定：prompt / fps / seed 直接映射；ref_images / ref_videos / ref_audios
     * 通过 anchor（锚定主节点 MiniMaxH3ReferenceToVideo）声明动态挂载。
     * <p>
     * 仅在插件目录下没有 {@code workflow.binding.json} 时生效（{@code ensureDefaultBinding} 只在
     * {@code binding == null} 时兜底），因此必须是<b>完整可解析的 JSON 对象</b>——此前漏了最外层
     * 的 <code>{ }</code>，一旦绑定文件缺失就会以「A JSONObject text must begin with '{'」直接炸掉生成。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "nodeId", "nodeId": "138", "field": "value" } },
        { "parameter": "seed", "target": { "match": "nodeId", "nodeId": "129", "field": "noise_seed" } },
        { "parameter": "fps", "target": { "match": "nodeId", "nodeId": "130", "field": "fps" }, "transform": "int" },
        { "parameter": "sage_node", "target": { "match": "classType", "classType": "PathchSageAttentionKJ" } },
        { "parameter": "steps", "target": { "match": "classType", "classType": "BasicScheduler", "field": "steps" }, "transform": "int" }
      ],
      "anchor": {
        "id": "video_node",
        "schema": { "match": "classType", "classType": "MiniMaxH3ReferenceToVideo" },
        "groups": [
          { "param": "ref_images", "pattern": "ref_images.ref_image_{i}", "loader": { "classType": "LoadImage", "field": "image" }, "max": 9 },
          { "param": "ref_videos", "pattern": "ref_videos.ref_video_{i}", "loader": { "classType": "VHS_LoadVideo", "field": "video", "inputs": { "force_rate": 0, "frame_load_cap": 0, "skip_first_frames": 0, "select_every_nth": 1, "custom_width": 0, "custom_height": 0 } }, "max": 3 },
          {
            "param": "ref_audios",
            "pattern": "ref_audios.ref_audio_{i}",
            "loader": { "classType": "LoadAudio", "field": "audio" },
            "chain": [
              { "classType": "TrimAudioDuration", "field": "audio", "outField": "audio", "inputs": { "start_index": 0, "duration": 20 } }
            ],
            "max": 3
          }
        ]
      },
      "lora": {
        "startNodes": [
          { "match": "nodeId", "nodeId": "127" }
        ]
      }
    }
    """;

    private MiniMaxH3ApiJsonBuilder() {
    }

    static JSONObject build(MiniMaxH3Adapter adapter, Image2VideoRefInput input) {
        adapter.ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(adapter.getWorkflowJson());

        // 1. 正向提示词 → 绑定 prompt
        if (input.prompt() != null) {
            adapter.applyBinding(api, "prompt", input.prompt());
        }

        // 2. 锚定主节点：MiniMaxH3ReferenceToVideo 由 anchor 绑定定位
        String main = adapter.resolveAnchorNodeId(api);
        JSONObject mainInputs = api.getJSONObject(main).getJSONObject("inputs");
        if (mainInputs == null) {
            mainInputs = new JSONObject();
            api.getJSONObject(main).set("inputs", mainInputs);
        }

        // 3. 宽高/帧数 → 主节点 inputs
        int width = input.getWidth() != null && input.getWidth() > 0 ? input.getWidth() : 832;
        int height = input.getHeight() != null && input.getHeight() > 0 ? input.getHeight() : 480;
        mainInputs.set("width", width);
        mainInputs.set("height", height);
        if (input.getLength() != null && input.getLength() > 0) {
            mainInputs.set("length", input.getLength());
        } else if (input.getDuration() != null && input.getDuration() > 0) {
            int fps = input.getFps() != null && input.getFps() > 0 ? input.getFps() : 24;
            mainInputs.set("length", (int) Math.round(input.getDuration() * fps));
        }

        // 4. FPS / 5. Seed → 绑定解析（未绑定则 no-op）
        if (input.getFps() != null && input.getFps() > 0) {
            adapter.applyBinding(api, "fps", input.getFps());
        }
        injectSeedBinding(adapter, api, "seed", input);

        // 6. Sage Attention 加速开关（按 class_type 扫描）
        applySageAttention(adapter, api, input);
        // 7. 用户 LoRA → 原生 LoraLoaderModelOnly 串联（无 LoRA 自动跳过）
        adapter.applyLorasFromBinding(api, input.getLoras());
        // 7.1 采样步数 → scheduler 绑定（可选 binding，未配置则沿用工作流默认值）
        if (input.getSteps() != null && input.getSteps() > 0) {
            adapter.applyBinding(api, "steps", input.getSteps());
        }

        // 8. 移除模板占位 LoadImage（image 为空的），随后由 applyAnchoredReferences 重新挂接
        removePlaceholderLoadImages(api);

        // 9. 锚定挂载：ref_images / ref_videos / ref_audios（建节点 + 接线由 anchor 绑定驱动）
        overrideAudioTrimDuration(adapter, resolveAudioTrimDuration(adapter, input));
        adapter.applyAnchoredReferences(api, Map.of(
                "ref_images", input.getRefImageNames() == null ? List.of() : input.getRefImageNames(),
                "ref_videos", input.getRefVideoNames() == null ? List.of() : input.getRefVideoNames(),
                "ref_audios", input.getAudioRefNames() == null ? List.of() : input.getAudioRefNames()
        ));

        // 模型槽注入：按当前选择覆盖 UNETLoader.unet_name 与 CLIPLoader.clip_name（无 modelSlots 时 no-op）
        adapter.applyModelSlots(api);

        return api;
    }

    /**
     * 随机处理后的 seed：null/0 生成随机正整数，再按绑定写入。
     */
    private static void injectSeedBinding(MiniMaxH3Adapter adapter, JSONObject api, String parameter, Image2VideoRefInput input) {
        if (adapter.resolveNodeTarget(api, parameter) == null) return;
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        adapter.applyBinding(api, parameter, Math.abs(seed));
    }

    /**
     * 移除模板中 image 为空的占位 LoadImage 节点（137/139/141/142 等），换工作流也通用。
     */
    private static void removePlaceholderLoadImages(JSONObject api) {
        List<String> toRemove = new ArrayList<>();
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node == null || !"LoadImage".equals(node.getStr("class_type"))) continue;
            JSONObject inputs = node.getJSONObject("inputs");
            Object img = inputs == null ? null : inputs.get("image");
            if (img == null || (img instanceof String s && s.isBlank())) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) api.remove(id);
    }

    /**
     * 运行时把解析出的音频裁剪时长写进 ref_audios 组 chain 的 duration 输入。
     */
    private static void overrideAudioTrimDuration(MiniMaxH3Adapter adapter, int duration) {
        JSONObject binding = adapter.getMetaBinding();
        if (binding == null) return;
        JSONObject anchor = binding.getJSONObject("anchor");
        if (anchor == null) return;
        JSONArray groups = anchor.getJSONArray("groups");
        if (groups == null) return;
        for (Object o : groups) {
            JSONObject g = (JSONObject) o;
            if (!"ref_audios".equals(g.getStr("param"))) continue;
            JSONArray chain = g.getJSONArray("chain");
            if (chain == null || chain.isEmpty()) continue;
            JSONObject step = (JSONObject) chain.getFirst();
            JSONObject stepInputs = step.getJSONObject("inputs");
            if (stepInputs != null) stepInputs.set("duration", duration);
        }
    }

    // ======================== Sage Attention（可选 binding 节点） ========================

    /**
     * 按绑定定位 PathchSageAttentionKJ 节点并应用加速开关。未声明 sage_node 绑定则跳过。
     */
    private static void applySageAttention(MiniMaxH3Adapter adapter, JSONObject api, Image2VideoRefInput input) {
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
    private static String resolveSageAttentionMode(MiniMaxH3Adapter adapter, Image2VideoRefInput input) {
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

    /**
     * 解析参考音频裁剪时长，优先级：input.options → adapter.json.extra → 默认 20 秒。
     */
    private static int resolveAudioTrimDuration(MiniMaxH3Adapter adapter, Image2VideoRefInput input) {
        Object raw = null;
        if (input != null) {
            for (String key : AUDIO_TRIM_DURATION_KEYS) {
                raw = input.option(key);
                if (raw != null) break;
            }
        }
        if (raw == null) {
            for (String key : AUDIO_TRIM_DURATION_KEYS) {
                raw = adapter.getMetaExtra(key);
                if (raw != null) break;
            }
        }
        if (raw instanceof Number n) {
            int value = n.intValue();
            return value > 0 ? value : DEFAULT_AUDIO_TRIM_DURATION;
        }
        if (raw != null) {
            try {
                int value = Integer.parseInt(StrUtil.trim(String.valueOf(raw)));
                return value > 0 ? value : DEFAULT_AUDIO_TRIM_DURATION;
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_AUDIO_TRIM_DURATION;
    }
}