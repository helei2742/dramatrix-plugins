package io.dramatrix.plugin.dashscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2AudioInput;
import io.dramatrix.plugin.interfaces.Text2AudioPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * DashScope（阿里云百炼）声音复刻 TTS 适配器（{@code kind=api}，{@code runtime=api}）。
 *
 * <h3>职责边界</h3>
 * <p><b>只做请求体映射</b>：不发 HTTP、不持凭据、不读文件。本类由插件 JAR 经独立
 * {@code URLClassLoader} 实例化，编译期只依赖 {@code dramatrix-plugin-api}。鉴权、音频读盘 /
 * Base64、音色复刻的提交与持久化全部由宿主侧处理。</p>
 *
 * <h3>两个方法、同一时刻（都在合成链路里）</h3>
 * <ul>
 *   <li>{@link #buildApiJson(Text2AudioInput)} —— 产出<b>合成</b>请求体，参考音色<b>原样</b>填成
 *       {@code input.voice}。若它是参考音频引用，宿主会在出网前复刻并把 voice id 写回该字段。</li>
 *   <li>{@link #buildEnrollment(String, String, Map)} —— 产出<b>复刻</b>请求体（{@code target_model}、
 *       协议分支、音色名只有本类知道），由宿主在合成请求出网前调用并提交。返回 {@code null} = 无需复刻。</li>
 * </ul>
 * <p><b>职责分工</b>：本类只说「请求体长什么样」；「什么时候复刻、结果存哪、怎么复用」全在宿主
 * {@code DashScopeMediaClient#t2sWithTimbers} 内部。</p>
 *
 * <h3>产出的形状</h3>
 * <pre>
 * 创建音色（buildEnrollment → POST /services/audio/tts/customization）
 * Qwen-TTS:    { "model":"qwen-voice-enrollment",   "input":{"action":"create",
 *                "target_model":"qwen3-tts-vc-…", "preferred_name":"&lt;sha256 前 16 位&gt;",
 *                "audio":{"data":"media-file://&lt;本地路径&gt;"}} }
 * CosyVoice:   { "model":"voice-enrollment",        "input":{"action":"create_voice",
 *                "target_model":"cosyvoice-v3.5-plus", "prefix":"&lt;sha256 前 10 位&gt;",
 *                "url":"media-file://&lt;本地路径&gt;"}}   ← 该系只收公网 URL，宿主上传后替换
 *
 * 合成（buildApiJson → 原生协议，**端点由模型家族决定**）
 * Qwen-TTS 系（qwen3-tts-*）    POST /services/aigc/multimodal-generation/generation
 *   { "model":"qwen3-tts-vc-…", "input":{"text":"…","voice":"&lt;voice id&gt;","language_type":"Auto"} }
 * Qwen-Audio-TTS / CosyVoice 系  POST /services/audio/tts/SpeechSynthesizer
 *   { "model":"cosyvoice-v3-flash", "input":{"text":"…","voice":"&lt;voice id&gt;",
 *                                            "format":"wav","sample_rate":24000} }
 * </pre>
 *
 * <p>两套端点的请求体字段<b>也不一样</b>：多模态生成端点用 {@code language_type}，SpeechSynthesizer 用
 * {@code format} / {@code sample_rate}（都必填、且没有 {@code language_type}）。把 CosyVoice 的模型名打到
 * 多模态生成端点，只会换回 {@code 400 InvalidParameter: url error, please check url！} —— 报「url 错」，
 * 极易被误判成 baseUrl 配置问题，实际是模型与端点不匹配。端点由宿主按同一个模型前缀判据选择
 * （{@code ProtocolHandler#ttsPathOf}），两处必须一起改。</p>
 *
 * <h3>参考音色引用的三种形态</h3>
 * <ul>
 *   <li>{@code media-file://<本地绝对路径>}：需复刻。两支协议都<b>原样透传</b>，由宿主换成能出网的形态：
 *       Qwen-TTS 系读本地文件转 Base64；CosyVoice 系先上传百炼文件空间换可访问 URL。</li>
 *   <li>{@code http(s)://…}：需复刻，直接作为复刻输入。</li>
 *   <li>其它字符串：视为<b>已复刻好的 voice id</b> / 系统音色，无需复刻。</li>
 * </ul>
 *
 * @author helei
 */
@Slf4j
public class DashScopeVoiceCloneTtsAdapter extends AbstractComfyUIPlugin implements Text2AudioPlugin {

    /** 媒体占位符前缀，须与宿主 {@code DashScopeMediaClient} 侧保持一致 */
    private static final String MEDIA_PLACEHOLDER = "media-file://";

    /** Qwen-TTS（qwen3-tts-vc-*）复刻协议 */
    private static final String QWEN_ENROLL_MODEL = "qwen-voice-enrollment";
    /** Qwen-Audio-TTS / CosyVoice 复刻协议 */
    private static final String COSY_ENROLL_MODEL = "voice-enrollment";

    /** Qwen-TTS 系模型前缀（决定复刻协议分支） */
    private static final String QWEN_TTS_VC_PREFIX = "qwen3-tts-vc";

    /**
     * Qwen-TTS 系的<b>模型家族</b>前缀：决定合成端点与合成请求体形状。
     *
     * <p>比 {@link #QWEN_TTS_VC_PREFIX} 宽 —— 复刻只对 {@code qwen3-tts-vc-*}（可复刻音色的模型）成立，
     * 而「走哪个合成端点」是整族 {@code qwen3-tts-*}（含 {@code qwen3-tts-flash} / {@code -instruct-flash}）
     * 的共同属性。宿主的 {@code ProtocolHandler#ttsPathOf} 用的是同一个前缀。</p>
     */
    private static final String QWEN_TTS_PREFIX = "qwen3-tts";

    /** SpeechSynthesizer 端点的默认输出格式（本地 WAV 便于后续拼接与资产入库） */
    private static final String DEFAULT_FORMAT = "wav";
    /** SpeechSynthesizer 端点的默认采样率（与官方示例一致） */
    private static final int DEFAULT_SAMPLE_RATE = 24000;

    /** Qwen-TTS 的 {@code preferred_name} 上限（官方：不超过 16 个字符） */
    private static final int PREFERRED_NAME_MAX = 16;
    /** CosyVoice / Qwen-Audio-TTS 的 {@code prefix} 上限（官方：不超过 10 个字符） */
    private static final int PREFIX_MAX = 10;

    public DashScopeVoiceCloneTtsAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    // ════════════════════════════ 上传期：创建音色 ════════════════════════════

    /**
     * 产出「创建音色」请求体，交由宿主在上传期提交。
     *
     * <p>名称取自 {@code nameSeed}（= 参考音频文件 sha256）：按协议取前 {@value #PREFERRED_NAME_MAX}
     * 位（Qwen-TTS）或前 {@value #PREFIX_MAX} 位（CosyVoice 系）。sha256 是十六进制，天然满足
     * 「仅字母数字」的字符集要求。种子缺失 / 清洗后为空时回落 {@code adapter.json} 里的
     * {@code voiceName}，保证不会因缺参而失败。</p>
     */
    @Override
    public JSONObject buildEnrollment(String voiceRef, String nameSeed, Map<String, Object> options) {
        if (StrUtil.isBlank(voiceRef)) {
            return null;
        }
        boolean local = voiceRef.startsWith(MEDIA_PLACEHOLDER);
        boolean publicUrl = voiceRef.startsWith("http://") || voiceRef.startsWith("https://");
        if (!local && !publicUrl) {
            return null; // 已是复刻好的 voice id / 系统音色，无需复刻
        }

        Map<String, Object> opts = options == null ? Map.of() : options;
        Map<String, Object> defs = defaults();
        String model = str(opts, defs, "modelName", null);
        if (StrUtil.isBlank(model)) {
            throw new IllegalArgumentException(
                    "未指定合成模型：复刻请求体的 target_model 必须与后续合成用的模型一致，请在插件参数里选 modelName（或在 adapter.json 的 extra.defaults 里给默认值）");
        }

        String fallbackName = voiceName(opts, defs);
        JSONObject enrollInput = new JSONObject();
        String enrollModel;
        if (model.startsWith(QWEN_TTS_VC_PREFIX)) {
            enrollModel = QWEN_ENROLL_MODEL;
            enrollInput.set("action", "create");
            enrollInput.set("target_model", model);
            enrollInput.set("preferred_name", seededName(nameSeed, PREFERRED_NAME_MAX, prefix(fallbackName)));
            enrollInput.set("audio", new JSONObject().set("data", voiceRef));
        } else {
            enrollModel = COSY_ENROLL_MODEL;
            enrollInput.set("action", "create_voice");
            enrollInput.set("target_model", model);
            enrollInput.set("prefix", seededName(nameSeed, PREFIX_MAX, prefix(fallbackName)));
            enrollInput.set("url", voiceRef);
        }

        return new JSONObject()
                .set("model", enrollModel)
                .set("input", enrollInput);
    }

    /**
     * 用内容指纹派生音色名：只取字母数字、按协议截断到 {@code maxLen}。
     * 种子为空（宿主未提供指纹）时回落 {@code fallback}，不因缺参失败。
     */
    private static String seededName(String nameSeed, int maxLen, String fallback) {
        String cleaned = nameSeed == null ? "" : nameSeed.replaceAll("[^0-9A-Za-z]", "");
        if (StrUtil.isBlank(cleaned)) {
            return fallback;
        }
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }

    // ════════════════════════════ 合成期：文生音频 ════════════════════════════

    @Override
    public JSONObject buildApiJson(Text2AudioInput input) {
        if (input == null || input.size() == 0) {
            throw new IllegalArgumentException("DashScope 声音复刻 TTS 需要 prompts（每段一条文本）");
        }
        if (input.size() > 1) {
            throw new IllegalArgumentException("DashScope 该端点一次只合成一段文本，当前传入 %d 段；宿主应按 Text2AudioInput#segments() 逐段调用本方法".formatted(input.size()));
        }

        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        Map<String, Object> defs = defaults();

        String model = str(opts, defs, "modelName", null);
        if (StrUtil.isBlank(model)) {
            throw new IllegalArgumentException("未指定合成模型：请在插件参数里选 modelName（或在 adapter.json 的 extra.defaults 里给默认值）");
        }

        String text = input.promptAt(0);
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("DashScope 声音复刻 TTS 的第 1 段文本为空");
        }

        // 音色引用：本段 timbre > 插件默认音色（系统音色或已复刻好的 voice id）
        String voiceRef = StrUtil.isNotBlank(input.timbreAt(0))
                ? input.timbreAt(0) : str(opts, defs, "defaultVoice", null);
        if (StrUtil.isBlank(voiceRef)) {
            throw new IllegalArgumentException("未提供音色：该分段没有参考音色，且插件参数里未设置 defaultVoice（系统音色名或已复刻的 voice id）");
        }

        boolean knownRef = voiceRef.startsWith(MEDIA_PLACEHOLDER) || voiceRef.startsWith("http://")
                || voiceRef.startsWith("https://");
        if (!knownRef && looksLikeAudioFile(voiceRef)) {
            throw new IllegalArgumentException("参考音色 [%s] 看着是本地文件名，既不是音色 id 也不是 %s 引用；请检查调用方是否绕过了宿主的上传流程".formatted(voiceRef, MEDIA_PLACEHOLDER));
        }

        JSONObject inputBody = new JSONObject();
        inputBody.set("text", text);
        inputBody.set("voice", voiceRef);
        if (isQwenTtsFamily(model)) {
            // Qwen-TTS 系（多模态生成端点）：语种靠 language_type 指定
            String languageType = str(opts, defs, "languageType", "Auto");
            if (StrUtil.isNotBlank(languageType)) {
                inputBody.set("language_type", languageType);
            }
        } else {
            // Qwen-Audio-TTS / CosyVoice 系（SpeechSynthesizer 端点）：该端点<b>没有</b> language_type，
            // 取而代之的是输出格式与采样率，两个都必填 —— 少一个同样是「url error」这种指不到原因的错误。
            inputBody.set("format", str(opts, defs, "format", DEFAULT_FORMAT));
            inputBody.set("sample_rate", intOf(opts, defs, "sampleRate", DEFAULT_SAMPLE_RATE));
        }

        JSONObject body = new JSONObject();
        body.set("model", model);
        body.set("input", inputBody);
        return body;
    }

    // ════════════════════════════ 参数取值 ════════════════════════════

    /** 是否 Qwen-TTS 系（决定合成端点与请求体形状；与宿主 {@code ProtocolHandler#ttsPathOf} 同源）。 */
    private static boolean isQwenTtsFamily(String model) {
        return StrUtil.startWith(StrUtil.nullToEmpty(model), QWEN_TTS_PREFIX);
    }

    /** 音色名（种子缺失时的回落值）。 */
    private static String voiceName(Map<String, Object> opts, Map<String, Object> defs) {
        return str(opts, defs, "voiceName", "dramatrix");
    }

    /** 是否「看着像本地音频文件」（带音频扩展名）。 */
    private static boolean looksLikeAudioFile(String ref) {
        String lower = ref.toLowerCase();
        return lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".m4a");
    }

    /**
     * CosyVoice 系的 {@code prefix} 只允许数字与英文字母、且不超过 10 个字符；
     * 这里按官方约束清洗（截断），避免用户填了中文/下划线时在厂商侧才失败。
     */
    private static String prefix(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^0-9A-Za-z]", "");
        if (StrUtil.isBlank(cleaned)) {
            cleaned = "dramatrix";
        }
        return cleaned.length() > PREFIX_MAX ? cleaned.substring(0, PREFIX_MAX) : cleaned;
    }

    /** 优先级：调用方 options > adapter.json defaults */
    private static Object pick(Map<String, Object> opts, Map<String, Object> defs, String key) {
        Object v = opts.get(key);
        return v != null ? v : defs.get(key);
    }

    private static String str(Map<String, Object> o, Map<String, Object> d, String key, String fallback) {
        Object v = pick(o, d, key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** 优先级同上；填了非法值就回落 {@code fallback}（为一个可被默认值救回的填错让整条合成失败不值）。 */
    private static int intOf(Map<String, Object> o, Map<String, Object> d, String key, int fallback) {
        Object v = pick(o, d, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaults() {
        Map<String, Object> extra = getMetadata().getExtra();
        if (extra == null) {
            return Map.of();
        }
        Object d = extra.get("defaults");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
