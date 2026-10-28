package io.dramatrix.plugin.stableaudio3;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.constants.MusicCategory;
import io.dramatrix.plugin.dto.MusicGenRequest;
import io.dramatrix.plugin.dto.PluginContext;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.MusicGenPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable Audio 3 Medium 音乐 / 音效生成适配器。
 *
 * <p>与 ace15-music 的“动态拼装节点图”不同，本适配器直接加载
 * {@code adapter.json} 指向的 {@code workflow.json}（即 {@code audio_stable_audio_3_medium.json}），
 * 随后仅按需改写其中指定节点的输入值，<b>不做任何节点拓扑的动态构建</b>。</p>
 *
 * <p><b>可调参数</b>（含默认值）全部声明在 {@code adapter.json} 的 {@code extra.paramSchema} 里：
 * 前端据此渲染「额外参数」表单，本类据此取缺省值，用户显式提交的值（{@code request} 的一等字段 /
 * {@code request.extra}）覆盖它。<b>模型文件名</b>由 {@code workflow.binding.json} 的
 * {@code modelSlots} 注入，不在 {@code adapter.json} 里重复声明。</p>
 *
 * <p>改写映射（仅关心以下参数，其余保持 workflow 原值）：</p>
 * <ul>
 *   <li>{@code request.seed}（回退 paramSchema 的 {@code seed}）→ KSampler(52:3).inputs.seed</li>
 *   <li>{@code request.steps} / {@code request.cfgScale}（回退 paramSchema 的 {@code steps}/{@code cfg}）→ KSampler(52:3).inputs.steps/cfg</li>
 *   <li>{@code userReprompt}              → Boolean 开关(52:35).inputs.value</li>
 *   <li>{@code reprompt_category}（按 musicType 推导）→ CustomCombo 类别(52:43).inputs.choice</li>
 *   <li>{@code textMaxLength} / {@code textSamplingMode} / {@code textTemperature} / {@code textTopK} /
 *       {@code textTopP} / {@code textMinP} / {@code textRepetitionPenalty} / {@code textSeed} /
 *       {@code textPresencePenalty}        → TextGenerate(52:28).inputs.*（含扁平键 sampling_mode.*）</li>
 *   <li>CheckpointLoaderSimple(52:25) / 音频 CLIP(52:26) / Qwen CLIP(52:29) 的文件名 → <b>模型槽</b>注入</li>
 *   <li>{@code request.duration}          → 时长 PrimitiveFloat(52:36).inputs.value</li>
 *   <li>{@code request.prompt}(user_input)→ USER_INPUT PrimitiveString(52:31).inputs.value</li>
 * </ul>
 */
public class StableAudio3MusicAdapter extends AbstractComfyUIPlugin
        implements MusicGenPlugin {

    // ── workflow 节点 ID（与 audio_stable_audio_3_medium.json 严格对齐）──
    // ★ 模型 / CLIP 加载节点（52:25 checkpoint、52:26 SA CLIP、52:29 Qwen CLIP）不在这里：
    //   它们的文件名由 workflow.binding.json 的 modelSlots 按 nodeId 定位注入，本类不再插手。
    private static final String N_KSAMPLER       = "52:3";
    private static final String N_REPROMPT_BOOL  = "52:35";
    private static final String N_CATEGORY_COMBO = "52:43";
    private static final String N_TEXT_GENERATE  = "52:28";
    private static final String N_DURATION       = "52:36";
    private static final String N_USER_INPUT     = "52:31";

    /** 时长安全上限（秒），与 workflow 提示词模板中“cinematic/ambient 240-300s”对齐 */
    private static final double MAX_DURATION = 300.0;

    /** CustomCombo(52:43) 的可选类别，必须与 workflow 中 option1~option4 完全一致 */
    private static final Set<String> VALID_CATEGORIES =
            Set.of("Music", "Instrument", "SFX", "One-shot");

    public StableAudio3MusicAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(MusicGenRequest request, PluginContext ctx) {
        if (workflowJson == null || workflowJson.isBlank()) {
            throw new IllegalStateException(
                    "workflowJson 未注入，无法构建 API JSON（plugin=" + metadata.getName() + "）");
        }
        // 每次调用都重新解析，避免并发调用共享可变状态
        JSONObject api = JSONUtil.parseObj(workflowJson);

        // 插件参数：前端按 paramSchema 提交，经 MusicGenRequest.extra 原样透传
        //（未提交的键回落 adapter.json 的 paramSchema.defaultValue）
        Map<String, Object> params = (request == null || request.getExtra() == null)
                ? Map.of() : request.getExtra();

        // ── 1. 主种子 → KSampler（请求侧 → paramSchema.seed；null / 0 = 随机）──
        long seed = resolveSeed(request == null ? null : toLong(request.getSeed()));
        inputsOf(api, N_KSAMPLER).set("seed", seed);

        // ── 1.1 steps / cfg → KSampler（请求侧优先 → paramSchema.defaultValue）──
        int steps = (request != null && request.getSteps() != null && request.getSteps() > 0)
                ? request.getSteps()
                : resolveInt("steps", 8);
        double cfg = (request != null && request.getCfgScale() != null && request.getCfgScale() > 0)
                ? request.getCfgScale()
                : resolveDouble("cfg", 1.0);
        inputsOf(api, N_KSAMPLER).set("steps", steps);
        inputsOf(api, N_KSAMPLER).set("cfg", cfg);

        // ── 2. userReprompt → Boolean 开关节点 ──
        boolean reprompt = resolveBool("userReprompt", toBool(params.get("userReprompt")), true);
        inputsOf(api, N_REPROMPT_BOOL).set("value", reprompt);

        // ── 3. reprompt_category → CustomCombo 类别（按音乐类型选择；BGM/Ambient 走 Music，SFX 走 SFX）──
        String category = repromptCategory(request != null ? request.getMusicType() : null);
        inputsOf(api, N_CATEGORY_COMBO).set("choice", category);

        // ── 4~6. CLIP / checkpoint 文件名：一律由 workflow.binding.json 的 modelSlots 注入
        //        （targets 按 classType+nodeId 定位到 52:25 / 52:26 / 52:29，见文末 applyModelSlots）──

        // ── 7. TextGenerate 节点（sampling_mode.* 为扁平键，必须原样 set）──
        JSONObject tgInputs = inputsOf(api, N_TEXT_GENERATE);
        tgInputs.set("max_length",        resolveInt("textMaxLength", toInt(params.get("textMaxLength")), 256));
        tgInputs.set("sampling_mode",     resolveStr("textSamplingMode", asStr(params.get("textSamplingMode")), "on"));
        tgInputs.set("sampling_mode.temperature",
                resolveDouble("textTemperature", toDouble(params.get("textTemperature")), 0.7));
        tgInputs.set("sampling_mode.top_k",              resolveInt("textTopK", toInt(params.get("textTopK")), 64));
        tgInputs.set("sampling_mode.top_p",              resolveDouble("textTopP", toDouble(params.get("textTopP")), 0.95));
        tgInputs.set("sampling_mode.min_p",              resolveDouble("textMinP", toDouble(params.get("textMinP")), 0.05));
        tgInputs.set("sampling_mode.repetition_penalty",
                resolveDouble("textRepetitionPenalty", toDouble(params.get("textRepetitionPenalty")), 1.05));
        tgInputs.set("sampling_mode.seed",               resolveSeed(toLong(params.get("textSeed"))));
        tgInputs.set("sampling_mode.presence_penalty",
                resolveDouble("textPresencePenalty", toDouble(params.get("textPresencePenalty")), 0.0));

        // ── 8. duration（request）→ 时长 PrimitiveFloat 节点 ──
        double duration = (request != null && request.getDuration() != null)
                ? request.getDuration() : 20.0;
        duration = Math.min(duration, MAX_DURATION);
        if (duration < 1.0) duration = 1.0;
        inputsOf(api, N_DURATION).set("value", duration);

        // ── 9. user_input（LLM 优化字段拼接）→ USER_INPUT 节点 ──
        String userPrompt = buildUserPrompt(request);
        if (StrUtil.isNotBlank(userPrompt)) {
            inputsOf(api, N_USER_INPUT).set("value", userPrompt);
        }

        // ── 10. 模型槽注入：按当前选择覆盖 CheckpointLoaderSimple.ckpt_name（无 modelSlots 时 no-op）──
        applyModelSlots(api);

        return api;
    }

    /**
     * 按类别读取对应 LLM 提示词模板（sfx/bgm/ambient-prompt.md）作为 <b>system prompt</b>；
     * 原始输入由 {@code MusicPromptOptimizer} 作为 user 消息独立下发。模板缺失时返回 null（跳过该类别优化）。
     */
    @Override
    public String buildMusicPrompt(MusicCategory category, MusicGenRequest request, PluginContext context) {
        String file = switch (category) {
            case BGM -> "bgm-prompt.md";
            case SFX -> "sfx-prompt.md";
            case AMBIENT -> "ambient-prompt.md";
        };
        return loadPromptResource(file);
    }

    // ───────────────────────────────────────────────
    // 节点 / 输入便捷访问
    // ───────────────────────────────────────────────

    /** 取某节点的 inputs 对象；不存在则抛明确异常 */
    private static JSONObject inputsOf(JSONObject api, String nodeId) {
        JSONObject node = api.getJSONObject(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("workflow 中不存在节点: " + nodeId);
        }
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) {
            throw new IllegalArgumentException("节点 " + nodeId + " 缺少 inputs 字段");
        }
        return inputs;
    }

    // 取值链见基类 AbstractComfyUIPlugin#resolveInt / #resolveDouble / #resolveStr / #resolveBool
    //（请求侧 → paramSchema.defaultValue → 硬编码保底）

    /** seed 为 null 或 0 → 随机长整型种子 */
    private static long resolveSeed(Long seed) {
        if (seed != null && seed != 0L) return seed;
        return RandomUtil.randomLong(0, 9_999_999_999_999_999L);
    }

    /** SA3 reprompt 类别：Music 覆盖器乐曲与 ambient（官方将 ambient 归入 Music 模式）；SFX 走 SFX */
    private static String repromptCategory(MusicCategory category) {
        return normalizeCategory(category == MusicCategory.SFX ? "SFX" : "Music");
    }

    /**
     * 构造 user_input：LLM 优化字段（genre/mood/instrumentation/vocalStyle）优先拼接，原始 prompt 兜底，
     * 并追加 BPM / 调式。SA3 的 user_input 会被内部 Qwen 按类别 reprompt 扩写，给出结构化要素即可。
     */
    private String buildUserPrompt(MusicGenRequest req) {
        if (req == null) return "";
        String base = StrUtil.blankToDefault(req.getGenre(), req.getPrompt());
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(base)) parts.add(base);
        if (StrUtil.isNotBlank(req.getMood())) parts.add(req.getMood());
        if (StrUtil.isNotBlank(req.getInstrumentation())) parts.add(req.getInstrumentation());
        if (StrUtil.isNotBlank(req.getVocalStyle())) parts.add(req.getVocalStyle());
        StringBuilder sb = new StringBuilder(String.join(", ", parts));
        if (req.getBpm() != null) sb.append(", ").append(req.getBpm()).append(" BPM");
        if (StrUtil.isNotBlank(req.getKeyScale())) sb.append(", ").append(req.getKeyScale());
        return sb.toString();
    }

    /** 归一化 reprompt 类别，匹配 workflow 中 CustomCombo 的 option 取值（大小写不敏感） */
    private static String normalizeCategory(String raw) {
        if (raw == null) return "Music";
        String trimmed = raw.trim();
        for (String valid : VALID_CATEGORIES) {
            if (valid.equalsIgnoreCase(trimmed)) return valid;
        }
        return "Music";
    }
}
