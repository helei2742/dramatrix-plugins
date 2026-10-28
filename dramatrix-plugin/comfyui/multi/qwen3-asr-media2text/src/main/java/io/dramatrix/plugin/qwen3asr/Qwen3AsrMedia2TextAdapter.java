package io.dramatrix.plugin.qwen3asr;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Media2TextInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Media2TextPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Qwen3-ASR 语音转文本插件适配器（{@code category = speech2text}）。
 *
 * <p>对接 <b>kaushiknishchay/ComfyUI-Qwen3-ASR</b>（v1.0.4）的两个节点，把
 * {@link Media2TextInput}（音频）映射成
 * {@code LoadAudio → Qwen3ASRTranscriber → ShowText|pysssss}（text 模式）或
 * {@code LoadAudio → Qwen3ForcedAlignerConfig → Qwen3ASRTranscriber → ShowText|pysssss}
 * （subtitle 模式）。</p>
 *
 * <h3>为什么是这个节点包，而不是 1038lab/ComfyUI-QwenASR</h3>
 * <p>本节点包走<b>官方 PyPI 包</b> {@code qwen-asr==0.0.6}（{@code from qwen_asr import Qwen3ASRModel}），
 * 该包硬钉 {@code transformers==4.57.6} + {@code accelerate==1.12.0} —— 与本项目
 * {@code comfyui-requirements-lock.txt} 同属 4.57 分支，只差一个补丁版，锁里对齐即可。
 * 而 1038lab 那套用 HF 原生类（{@code AutoModelForMultimodalLM}），只在 transformers 5.x 存在，
 * 与本项目锁（4.57.x）不可调和，装上会把同环境 IndexTTS2 的 {@code OffloadedCache} 打坏。</p>
 *
 * <p>两者的<b>权重也不通用</b>：本节点包读的是非 {@code -hf} 的旧检查点
 * （{@code Qwen/Qwen3-ASR-0.6B}，config 里是嵌套的 {@code thinker_config}），
 * 且必须放在 {@code models/diffusion_models/Qwen3-ASR/<模型目录>} 下 —— 节点是用
 * {@code folder_paths.get_filename_list("diffusion_models")} 反推目录名的。</p>
 *
 * <h3>输出模式（{@code outputMode}）</h3>
 * <table border="1">
 *   <tr><th>取值</th><th>建哪些节点</th><th>取哪个输出口</th><th>结果形状</th></tr>
 *   <tr><td>{@code text}（缺省）</td><td>转写节点</td><td>{@code text}(0)</td>
 *       <td>纯转写原文</td></tr>
 *   <tr><td>{@code subtitle}</td><td>转写节点 + 强制对齐器节点</td><td>{@code timestamps}(1)</td>
 *       <td>每行 {@code [起 - 止] 文本}（秒，两位小数）</td></tr>
 * </table>
 *
 * <p>两种模式用<b>不同的节点集</b>而不是同一套图的不同输出口：强制对齐器是一个独立模型，
 * 纯转写时没必要加载它。因此 {@code text} 模式下工作流里<b>压根没有</b>对齐器节点，
 * {@code forced_aligner} 这个输入键也不会下发。</p>
 *
 * <p><b>★ 对齐器缺失时回落 text 模式（不是产出垃圾）。</b>本节点包在没有对齐器时，
 * {@code timestamps} 输出口会回一句字面量 {@code "No timestamps generated."} —— 把它当字幕结果
 * 写进产物是静默垃圾。故 {@code outputMode=subtitle} 但 {@code forcedAligner=None/空} 时，
 * 适配器<b>回落</b> {@code text} 模式并打一条 warn（要真字幕就装上对齐器或改选模型）。</p>
 *
 * <h3>关于 prompt</h3>
 * <p>Qwen3-ASR 的节点<b>没有</b>提示词输入 —— 转写不需要指令。因此本适配器忽略
 * {@link Media2TextInput#getPrompt()}（只记一条 debug 日志），也不做「prompt 必填」校验；
 * 调用方（如数据集标注链路）为了复用统一入口而必须传的占位 prompt 不会被下发到工作流。</p>
 *
 * <h3>模型槽</h3>
 * <p>ASR 模型由 {@code workflow.binding.json} 的 {@code qwen3-asr} 模型槽决定；调用方在
 * {@code options.model} 里显式指定的值优先级最高。</p>
 *
 * <p><b>★ 模型选择只有模型槽这一个入口。</b>{@code extra.defaults} 刻意不声明 {@code model} ——
 * {@code MediaUnderstandService.mergeOptions} 会把 defaults 合并进 options，声明了就等于每次请求
 * 都"显式"覆盖模型槽，槽的选择将永久失效。{@code configSchema} 里同样刻意<b>不列</b> model 下拉：
 * {@code ExecutorConfigDrawer#applyPluginDefaults} 会把 schema 字段的缺省值一并存进配置并在每次
 * 提交时下发，效果与前者一模一样（先例：本插件上一版就有这个下拉，等于把槽废掉了）。</p>
 *
 * <p><b>★ 槽按「角色标题」锚定，不按 {@code class_type}。</b>当前两种模式用的都是
 * {@code Qwen3ASRTranscriber}，按类锚本可命中；但同一家族的另一个节点包（1038lab）就是把
 * 「转写」与「字幕」拆成两个类，将来本节点包也可能分叉。角色始终是「ASR 转写节点」，
 * 故绑定用 {@code match: title}，两个 schema 的 {@code _meta.title} 由同一个常量
 * {@link #NODE_TITLE} 生成、<b>必须完全一致</b> —— 它就是模型槽的定位锚点，改标题即等于拆掉模型槽。</p>
 *
 * <h3>为什么强制对齐器不是模型槽</h3>
 * <p>{@code forcedAligner} 只在 {@code subtitle} 模式下存在（{@code text} 模式的工作流里压根没有
 * 对齐器节点）。做成模型槽的话，{@code applyModelSlots} 每次纯文本构建都会因为「找不到该节点」
 * 而打一条 warn —— 批量转写会刷屏。故它是普通插件参数，直接写进节点；对齐器模型本体仍声明在
 * {@code dependencies.json} 里参与依赖校验与下载。</p>
 *
 * <h3>越界参数就地夹紧（带 warn）</h3>
 * <p>{@code chunk_size} / {@code overlap} / {@code max_new_tokens} 在节点里是带范围校验的 INT，
 * ComfyUI 对超限值是<b>直接拒收整条 prompt</b>。批量转写时那等于整批失败，故这里夹到节点声明的
 * 合法区间并打 warn（用户看得见拦在哪），而不是把非法值透传下去炸掉。</p>
 *
 * <p>另外 {@code overlap >= chunk_size} 会让节点的 {@code step_samples = chunk_samples - overlap_samples}
 * 变成 0 或负数 —— 节点里那一步是整数除法，会直接抛 {@code ZeroDivisionError}。
 * 故 {@code chunk_size > 0} 时把 {@code overlap} 夹到 {@code chunk_size - 1} 并 warn。</p>
 */
@Slf4j
public class Qwen3AsrMedia2TextAdapter extends AbstractComfyUIPlugin implements Media2TextPlugin {

    /** 模型槽分组名（必须与 workflow.binding.json 的 modelSlots[].group 一致） */
    private static final String MODEL_SLOT = "qwen3-asr";

    /** 输出模式：纯文本转写（只建转写节点，不加载对齐器） */
    public static final String MODE_TEXT = "text";
    /** 输出模式：词级时间戳字幕（额外建对齐器节点，取 timestamps 输出口） */
    public static final String MODE_SUBTITLE = "subtitle";

    /** {@code Qwen3ASRTranscriber} 的输出口序号：RETURN_NAMES = text, timestamps */
    private static final int TEXT_OUTPUT_SLOT = 0;
    private static final int TIMESTAMPS_OUTPUT_SLOT = 1;

    /**
     * 业务节点的「角色标题」——两种模式共用，也是 {@code workflow.binding.json} 里模型槽的定位锚点。
     * <p><b>不要给两种模式起不同标题</b>：绑定按标题定位，标题一分叉，总有一种模式定位不到，
     * 该模式下用户选中的模型会被静默忽略（详见类注释「模型槽」一节）。</p>
     */
    private static final String NODE_TITLE = "Qwen3-ASR 转写";

    /** 对齐器节点标题（与转写节点区分开，避免模型槽误命中） */
    private static final String ALIGNER_TITLE = "Qwen3-ASR 强制对齐器";

    /**
     * 硬保底模型：节点读的是 {@code models/diffusion_models} 下的「两段路径」
     * （{@code Qwen3-ASR/<模型目录>}，由 {@code folder_paths.get_filename_list("diffusion_models")} 反推）。
     * 模型槽未配置 / 定位失败时仍能跑；正常路径由槽覆盖成 dependencies.json 的 {@code workflowValue}。
     */
    private static final String DEFAULT_MODEL = "Qwen3-ASR/Qwen3-ASR-0.6B";
    private static final String DEFAULT_PRECISION = "bf16";
    private static final String DEFAULT_LANGUAGE = "auto";
    private static final String DEFAULT_DEVICE = "cuda";

    /** 强制对齐器：字幕模式必需（不装就只能拿到一句 "No timestamps generated."） */
    private static final String DEFAULT_ALIGNER = "Qwen3-ASR/Qwen3-ForcedAligner-0.6B";
    /** 对齐器「不加载」哨兵值（配置里选它 = 不建对齐器节点） */
    private static final String ALIGNER_NONE = "None";

    /** 节点里是 COMBO，值不在列表内 ComfyUI 直接拒收 ⇒ 越界就地回落 + warn */
    private static final Set<String> PRECISIONS = Set.of("bf16", "fp16", "fp32");
    private static final Set<String> DEVICES = Set.of("cuda", "cpu");

    /** 分块：{@code chunk_size=0} = 不分块（节点原样支持）；上限取自节点 INPUT_TYPES */
    private static final int CHUNK_SIZE_MIN = 0;
    private static final int CHUNK_SIZE_MAX = 300;
    private static final int OVERLAP_MIN = 0;
    private static final int OVERLAP_MAX = 10;
    private static final int MAX_NEW_TOKENS_MIN = 1;
    private static final int MAX_NEW_TOKENS_MAX = 4096;

    private static final int DEFAULT_CHUNK_SIZE = 30;
    private static final int DEFAULT_OVERLAP = 2;
    private static final int DEFAULT_MAX_NEW_TOKENS = 256;

    public Qwen3AsrMedia2TextAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Media2TextInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (StrUtil.isBlank(input.getAudioFile())) {
            throw new IllegalArgumentException(
                    "Qwen3-ASR 语音转文本需要音频输入（audioFile 为空）。本插件只支持 AUDIO 模态；"
                            + "图/视频理解请改用 category=understanding 的 media2text 插件");
        }

        AsrParams params = toParams(input);
        JSONObject workflow = buildWorkflow(params);

        // 请求级覆盖：options.model 显式传入时优先级最高（覆盖模型槽）；
        // 未显式传（走硬保底）才由槽当前选择决定 model_name 字段。
        String explicitModel = strOpt(input.getOptions(), "model");
        Map<String, String> override = StrUtil.isNotBlank(explicitModel)
                ? Map.of(MODEL_SLOT, explicitModel) : null;
        applyModelSlots(workflow, override);

        log.debug("[Qwen3-ASR] 提交: audio={}, mode={}, model={}, precision={}, device={}, language={}, "
                        + "aligner={}, chunkSize={}, overlap={}, maxNewTokens={}",
                params.audioFile(), params.mode(), params.model(), params.precision(), params.device(),
                params.language(), params.subtitle() ? params.forcedAligner() : "(未加载)",
                params.chunkSize(), params.overlap(), params.maxNewTokens());
        return workflow;
    }

    // ════════════════════════════ 参数映射 ════════════════════════════

    /**
     * 三级取值：调用方 options &gt; adapter.json 的 extra.defaults &gt; 类内硬保底。
     * <p>options 里还混着 {@code MediaUnderstandService} / {@code ExecutorConfigDrawer} 注入的
     * 通用参数（maxTokens / temperature / topP / repetitionPenalty / seed）——Qwen3-ASR 节点不接这些，
     * 这里只取自己声明的 key，多出来的键自然被忽略。</p>
     */
    private AsrParams toParams(Media2TextInput input) {
        Map<String, Object> defs = safeDefaults();
        Map<String, Object> opts = input.getOptions();

        if (StrUtil.isNotBlank(input.getPrompt())) {
            log.debug("[Qwen3-ASR] 忽略 prompt（Qwen3-ASR 节点无提示词输入）: len={}", input.getPrompt().length());
        }

        String mode = pick(opts, defs, "outputMode", MODE_TEXT);
        if (!MODE_SUBTITLE.equalsIgnoreCase(mode) && !MODE_TEXT.equalsIgnoreCase(mode)) {
            log.warn("[Qwen3-ASR] 未知 outputMode={}，按 {} 处理", mode, MODE_TEXT);
            mode = MODE_TEXT;
        } else {
            mode = mode.toLowerCase(Locale.ROOT);
        }
        boolean subtitle = MODE_SUBTITLE.equals(mode);

        String aligner = pick(opts, defs, "forcedAligner", DEFAULT_ALIGNER);
        if (subtitle && isAlignerDisabled(aligner)) {
            // 不产出垃圾：节点在对齐器缺失时会把 timestamps 输出口填成 "No timestamps generated."
            log.warn("[Qwen3-ASR] outputMode=subtitle 但 forcedAligner={} ⇒ 节点不会产出时间戳"
                            + "（timestamps 只会回一句 \"No timestamps generated.\"），已回落 outputMode={}",
                    StrUtil.isBlank(aligner) ? "(空)" : aligner, MODE_TEXT);
            subtitle = false;
            mode = MODE_TEXT;
        }

        String precision = pickEnum(opts, defs, "precision", PRECISIONS, DEFAULT_PRECISION);
        String device = pickEnum(opts, defs, "device", DEVICES, DEFAULT_DEVICE);

        int chunkSize = clamp(pickInt(opts, defs, "chunkSize", DEFAULT_CHUNK_SIZE),
                CHUNK_SIZE_MIN, CHUNK_SIZE_MAX, "chunkSize");
        int overlap = clamp(pickInt(opts, defs, "overlap", DEFAULT_OVERLAP),
                OVERLAP_MIN, OVERLAP_MAX, "overlap");
        // step = chunk - overlap 是节点里的整数除法，overlap >= chunk 会 ZeroDivisionError
        if (chunkSize > 0 && overlap >= chunkSize) {
            int fixed = Math.max(OVERLAP_MIN, chunkSize - 1);
            log.warn("[Qwen3-ASR] overlap={} >= chunkSize={} 会让节点的分块步长变成 0（整数除零），已夹到 {}",
                    overlap, chunkSize, fixed);
            overlap = fixed;
        }

        return new AsrParams(
                input.getAudioFile(),
                Math.max(1, input.getStartNodeId()),
                mode,
                subtitle,
                pick(opts, defs, "model", DEFAULT_MODEL),
                precision,
                pick(opts, defs, "language", DEFAULT_LANGUAGE),
                device,
                pickBool(opts, defs, "flashAttention2", false),
                chunkSize,
                overlap,
                clamp(pickInt(opts, defs, "maxNewTokens", DEFAULT_MAX_NEW_TOKENS),
                        MAX_NEW_TOKENS_MIN, MAX_NEW_TOKENS_MAX, "maxNewTokens"),
                aligner);
    }

    /** 对齐器是否被显式关掉（哨兵值 {@code None} 或空串） */
    private static boolean isAlignerDisabled(String aligner) {
        return StrUtil.isBlank(aligner) || ALIGNER_NONE.equalsIgnoreCase(aligner.trim());
    }

    /**
     * 工作流入参（不可变，构建期一次性定型）。
     * <p>对齐器相关项只在 {@code subtitle=true} 时下发到工作流。</p>
     */
    private record AsrParams(
            String audioFile,
            int startNodeId,
            String mode,
            boolean subtitle,
            String model,
            String precision,
            String language,
            String device,
            boolean flashAttention2,
            int chunkSize,
            int overlap,
            int maxNewTokens,
            String forcedAligner) {
    }

    // ════════════════════════════ 工作流构建 ════════════════════════════

    private static final String LOAD_AUDIO_SCHEMA = """
            {
                "inputs": {
                    "audio": "",
                    "audioUI": ""
                },
                "class_type": "LoadAudio",
                "_meta": {
                    "title": "加载音频"
                }
            }
            """;

    /**
     * {@code Qwen3ForcedAlignerConfig} 的全部 required 输入（RETURN_TYPES = QWEN3_ALIGNER_CONF）。
     * <p>它的 {@code model_name} 与转写节点同源（同一个 {@code get_qwen_model_list()} 下拉），
     * 但指向<b>对齐器</b>模型，故不走模型槽（只在 subtitle 模式存在）。</p>
     */
    private static final String ALIGNER_SCHEMA = """
            {
                "inputs": {
                    "model_name": "Qwen3-ASR/Qwen3-ForcedAligner-0.6B",
                    "device": "cuda",
                    "precision": "bf16",
                    "flash_attention_2": false
                },
                "class_type": "Qwen3ForcedAlignerConfig",
                "_meta": {
                    "title": "%s"
                }
            }
            """.formatted(ALIGNER_TITLE);

    /**
     * {@code Qwen3ASRTranscriber} 的全部 required 输入
     * （RETURN_TYPES = STRING, STRING → RETURN_NAMES = text, timestamps）。
     * <p>{@code forced_aligner} 是 <b>optional</b> 的连线输入，只在 subtitle 模式下写入；
     * ComfyUI 会拒收未声明的输入键，而它是可选的 —— 不下发就等于不加载对齐器。</p>
     */
    private static final String TRANSCRIBER_SCHEMA = """
            {
                "inputs": {
                    "model_name": "Qwen3-ASR/Qwen3-ASR-0.6B",
                    "language": "auto",
                    "device": "cuda",
                    "precision": "bf16",
                    "max_new_tokens": 256,
                    "flash_attention_2": false,
                    "chunk_size": 30,
                    "overlap": 2
                },
                "class_type": "Qwen3ASRTranscriber",
                "_meta": {
                    "title": "%s"
                }
            }
            """.formatted(NODE_TITLE);

    private static final String SHOW_TEXT_SCHEMA = """
            {
                "inputs": {
                },
                "class_type": "ShowText|pysssss",
                "_meta": {
                    "title": "Show Text 🐍"
                }
            }
            """;

    /**
     * 组装工作流。
     *
     * <p>节点号按图序分配：{@code LoadAudio → [Qwen3ForcedAlignerConfig] → Qwen3ASRTranscriber → ShowText}，
     * 所以 text 模式是 1/2/3、subtitle 模式是 1/2/3/4（对齐器插在中间）。</p>
     *
     * <p>末级 {@code ShowText|pysssss} 不是装饰：文本结果正是通过它写进 ComfyUI history 的
     * {@code outputs[<id>]["text"]}，{@code AbstractComfyUIClient.getTextResult} 只认这个形状 ——
     * 去掉它，「跑成功了但取不到文本」。</p>
     */
    private static JSONObject buildWorkflow(AsrParams p) {
        JSONObject workflow = new JSONObject();
        int nodeId = p.startNodeId();

        String audioNodeId = String.valueOf(nodeId++);
        String alignerNodeId = p.subtitle() ? String.valueOf(nodeId++) : null;
        String transcriberNodeId = String.valueOf(nodeId++);
        String showTextNodeId = String.valueOf(nodeId++);

        workflow.set(audioNodeId, buildLoadAudioNode(p.audioFile()));

        if (p.subtitle()) {
            JSONObject aligner = new JSONObject(ALIGNER_SCHEMA);
            JSONObject ai = aligner.getJSONObject("inputs");
            ai.set("model_name", StrUtil.blankToDefault(p.forcedAligner(), DEFAULT_ALIGNER));
            ai.set("device", p.device());
            ai.set("precision", p.precision());
            ai.set("flash_attention_2", p.flashAttention2());
            workflow.set(alignerNodeId, aligner);
        }

        JSONObject transcriber = new JSONObject(TRANSCRIBER_SCHEMA);
        fillTranscriberInputs(transcriber.getJSONObject("inputs"), p, audioNodeId, alignerNodeId);
        workflow.set(transcriberNodeId, transcriber);

        JSONObject showText = new JSONObject(SHOW_TEXT_SCHEMA);
        showText.getJSONObject("inputs")
                .set("text", List.of(transcriberNodeId, p.subtitle() ? TIMESTAMPS_OUTPUT_SLOT : TEXT_OUTPUT_SLOT));
        workflow.set(showTextNodeId, showText);

        return workflow;
    }

    private static void fillTranscriberInputs(JSONObject inputs, AsrParams p, String audioNodeId, String alignerNodeId) {
        inputs.set("audio", List.of(audioNodeId, 0));
        inputs.set("model_name", p.model());
        inputs.set("language", p.language());
        inputs.set("device", p.device());
        inputs.set("precision", p.precision());
        inputs.set("max_new_tokens", p.maxNewTokens());
        inputs.set("flash_attention_2", p.flashAttention2());
        inputs.set("chunk_size", p.chunkSize());
        inputs.set("overlap", p.overlap());

        if (!p.subtitle()) {
            return;
        }
        // 只在字幕模式挂对齐器：text 模式下这个键不下发（节点会按 None 处理，但少写一个键更干净）
        inputs.set("forced_aligner", List.of(alignerNodeId, 0));
    }

    private static JSONObject buildLoadAudioNode(String audioFile) {
        JSONObject node = new JSONObject(LOAD_AUDIO_SCHEMA);
        node.getJSONObject("inputs").set("audio", audioFile);
        return node;
    }

    // ════════════════════════════ 防御性工具 ════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeDefaults() {
        Object raw = getMetaExtra("defaults");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        log.warn("adapter.json extra.defaults 格式异常: plugin=qwen3-asr-media2text");
        return Map.of();
    }

    private static String pick(Map<String, Object> opts, Map<String, Object> defs,
                               String key, String fallback) {
        Object val = opts == null ? null : opts.get(key);
        if (val == null) val = defs == null ? null : defs.get(key);
        if (val == null) return fallback;
        String s = String.valueOf(val);
        return s.isBlank() ? fallback : s;
    }

    /**
     * 取 COMBO 类参数：值必须落在 {@code allowed} 里，否则回落 {@code fallback} 并 warn。
     * <p>节点的这些输入是固定列表，透传非法值会被 ComfyUI 整条拒收 —— 批量转写时等于整批失败。</p>
     */
    private static String pickEnum(Map<String, Object> opts, Map<String, Object> defs,
                                   String key, Set<String> allowed, String fallback) {
        String v = pick(opts, defs, key, fallback);
        if (allowed.contains(v.toLowerCase(Locale.ROOT))) {
            return v.toLowerCase(Locale.ROOT);
        }
        log.warn("[Qwen3-ASR] {}={} 不在节点支持的取值 {} 内，按 {} 处理", key, v, allowed, fallback);
        return fallback;
    }

    /** 越界就地夹紧 + warn（节点对 INT 有范围校验，超限 ComfyUI 直接拒收） */
    private static int clamp(int value, int lo, int hi, String key) {
        if (value < lo) {
            log.warn("[Qwen3-ASR] {}={} 低于节点允许下限 {}，已夹到 {}", key, value, lo, lo);
            return lo;
        }
        if (value > hi) {
            log.warn("[Qwen3-ASR] {}={} 超过节点允许上限 {}，已夹到 {}", key, value, hi, hi);
            return hi;
        }
        return value;
    }

    private static boolean pickBool(Map<String, Object> opts, Map<String, Object> defs,
                                    String key, boolean fallback) {
        Boolean b = toBool(raw(opts, defs, key));
        return b != null ? b : fallback;
    }

    private static int pickInt(Map<String, Object> opts, Map<String, Object> defs,
                               String key, int fallback) {
        Integer i = toInt(raw(opts, defs, key));
        return i != null ? i : fallback;
    }

    /** options 优先、defaults 兜底；两者都没有返回 null。 */
    private static Object raw(Map<String, Object> opts, Map<String, Object> defs, String key) {
        Object val = opts == null ? null : opts.get(key);
        return val != null ? val : (defs == null ? null : defs.get(key));
    }

    /** 取调用方显式给的字符串选项；缺失 / 非字符串 / 空白一律返回 null。 */
    private static String strOpt(Map<String, Object> opts, String key) {
        Object v = opts == null ? null : opts.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
