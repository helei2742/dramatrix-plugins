package io.dramatrix.plugin.dashscope;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2AudioInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 声音复刻 TTS 适配器契约测试：钉住「插件产出什么形状」。
 *
 * <h3>两个方法、同一时刻（合成链路内部），分开钉</h3>
 * <ul>
 *   <li>{@link DashScopeVoiceCloneTtsAdapter#buildEnrollment} —— 产出「创建音色」请求体，宿主在合成请求
 *       出网<b>之前</b>调用它并提交、把返回的 voice id 写回合成体。协议分支（Qwen-TTS 的
 *       {@code qwen-voice-enrollment} + Data URL vs CosyVoice 的 {@code voice-enrollment} + url）、
 *       音色命名规则都只在这里。</li>
 *   <li>{@link DashScopeVoiceCloneTtsAdapter#buildApiJson} —— 产出「合成」请求体。音色值<b>原样填入</b>
 *       {@code input.voice}（宿主已在<b>进插件之前</b>把参考音色换成 voice id，本类不判不拦）。唯一拦的是
 *       <b>裸文件名</b> —— 宿主会把本地音频包成 {@code media-file://}，裸路径说明绕过了上传流程。
 *       请求体形状按模型家族分两支：{@code qwen3-tts-*} 用 {@code language_type}，
 *       Qwen-Audio-TTS / CosyVoice 用 {@code format} + {@code sample_rate}。</li>
 * </ul>
 *
 * <p>测试直接加载<b>真实的 {@code adapter.json}</b>，让默认值与可选模型的漂移也能被抓到。</p>
 *
 * @author helei
 */
class DashScopeVoiceCloneTtsAdapterTest {

    /** 测试以模块目录为工作目录，故用相对路径直接读真实配置文件（而非另造一份夹具）。 */
    private static final File ADAPTER_JSON =
            new File("src/main/java/io/dramatrix/plugin/dashscope/adapter.json");

    /**
     * 本地参考音色引用 —— 与宿主实际给出的形态一致：DashScope 通道的
     * {@code upload(VOICE)} 会给本地绝对路径加上 {@code media-file://} 前缀。
     */
    private static final String LOCAL_VOICE_REF = "media-file:///tmp/dramatrix/voice/narrator.mp3";

    /** 参考音频的内容指纹（宿主算出来的文件 sha256），音色名由它派生。 */
    private static final String HASH = "9f2c1a77b0d4e6385c1f0a2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60";

    private static final String DEFAULT_MODEL = "qwen3-tts-vc-2026-01-22";

    /** 与 {@link #HASH} 前 16 位（Qwen-TTS 的 {@code preferred_name} 上限）一致。 */
    private static final String SEED_16 = HASH.substring(0, 16);
    /** 与 {@link #HASH} 前 10 位（CosyVoice 的 {@code prefix} 上限）一致。 */
    private static final String SEED_10 = HASH.substring(0, 10);

    private DashScopeVoiceCloneTtsAdapter adapter;

    @BeforeEach
    void setUp() {
        assertTrue(ADAPTER_JSON.exists(), "adapter.json 不存在: " + ADAPTER_JSON.getAbsolutePath());
        PluginMetadata metadata = JSONUtil.toBean(
                FileUtil.readString(ADAPTER_JSON, StandardCharsets.UTF_8), PluginMetadata.class);
        adapter = new DashScopeVoiceCloneTtsAdapter(metadata);
    }

    // ════════════════════════════ adapter.json 自检 ════════════════════════════

    @Test
    @DisplayName("adapter.json：kind=api / vendor=dashscope / type=text2audio")
    void metadataIsWellFormed() {
        assertEquals("api", adapter.getMetadata().getKind(), "kind 必须是 api，否则会被当成 ComfyUI 插件");
        // 跨模块契约：插件 vendor 必须等于宿主 DashScope backend 声明的厂商标识，否则那条通道永远匹配不上
        assertEquals("dashscope", adapter.getMetadata().getVendor());
        assertEquals("text2audio", adapter.getMetadata().getType());
    }

    // ════════════════════════════ 创建音色（复刻）════════════════════════════

    @Test
    @DisplayName("🔴 本地参考音频 + Qwen-TTS：qwen-voice-enrollment + audio.data，音色名取 sha256 前 16 位")
    void enrollsLocalFileWithQwenTts() {
        JSONObject enroll = adapter.buildEnrollment(LOCAL_VOICE_REF, HASH, Map.of());

        assertNotNull(enroll, "本地文件必须先复刻，就该产出复刻请求体");
        assertEquals("qwen-voice-enrollment", enroll.getStr("model"));
        JSONObject in = enroll.getJSONObject("input");
        assertEquals("create", in.getStr("action"));
        assertEquals(DEFAULT_MODEL, in.getStr("target_model"),
                "target_model 必须等于默认合成模型，否则音色建出来绑不上");
        assertEquals(SEED_16, in.getStr("preferred_name"),
                "厂商不允许自定义 id，只能给名称；名称取内容指纹前 16 位（官方上限），同一段音频可复现");
        assertFalse(in.containsKey("prefix"), "Qwen-TTS 协议用 preferred_name，不该混入 CosyVoice 的 prefix");
        assertEquals(LOCAL_VOICE_REF, in.getJSONObject("audio").getStr("data"),
                "占位符交宿主读盘转 Base64 Data URL（Qwen-TTS 支持 Data URL，故本地文件可用）");
    }

    @Test
    @DisplayName("🔴 公网 URL + CosyVoice：voice-enrollment + prefix + url，音色名取 sha256 前 10 位")
    void enrollsPublicUrlWithCosyVoice() {
        String url = "https://cdn.example.com/narrator.wav";
        JSONObject enroll = adapter.buildEnrollment(
                url, HASH, Map.of("modelName", "cosyvoice-v3.5-plus"));

        assertNotNull(enroll);
        assertEquals("voice-enrollment", enroll.getStr("model"), "CosyVoice 系是另一套复刻协议");
        JSONObject in = enroll.getJSONObject("input");
        assertEquals("create_voice", in.getStr("action"));
        assertEquals("cosyvoice-v3.5-plus", in.getStr("target_model"));
        assertEquals(SEED_10, in.getStr("prefix"), "CosyVoice 的 prefix 官方上限 10 位");
        assertFalse(in.containsKey("preferred_name"));
        assertEquals(url, in.getStr("url"), "该协议直接吃公网 URL，不转 Base64");
        assertFalse(in.containsKey("audio"));
    }

    @Test
    @DisplayName("音色名由指纹派生：非字母数字被清洗、按协议截断；指纹缺失时回落 adapter.json 的 voiceName")
    void enrollmentNameIsSanitizedAndTruncated() {
        // 指纹带分隔符（真实 sha256 是纯十六进制，这里刻意加大清洗压力）
        JSONObject withSeed = adapter.buildEnrollment(
                LOCAL_VOICE_REF, "9f2c-1a77-b0d4-e638-5c1f-0a2b-3d4e-5f60-7182-93a4", Map.of());
        assertEquals("9f2c1a77b0d4e638", withSeed.getJSONObject("input").getStr("preferred_name"),
                "只保留字母数字后取前 16 位");

        // 指纹缺失 → 回落 voiceName；CosyVoice 侧还要再清洗（prefix 只允许字母数字）
        JSONObject fallback = adapter.buildEnrollment(
                "https://cdn.example.com/n.wav", null,
                Map.of("modelName", "cosyvoice-v3.5-plus", "voiceName", "drama_2026_长"));
        assertEquals("drama2026", fallback.getJSONObject("input").getStr("prefix"),
                "下划线与中文都不被 CosyVoice 接受，必须清洗掉");
        assertEquals("dramatrix", adapter.buildEnrollment(LOCAL_VOICE_REF, "", Map.of())
                        .getJSONObject("input").getStr("preferred_name"),
                "指纹与 voiceName 都没有时给一个能用的兜底名，不因缺参失败");
    }

    @Test
    @DisplayName("🔴 CosyVoice 系 + 本地文件：原样透传 url，由宿主先上传换取可访问链接（插件不拒绝、也不要求用户自己找服务器）")
    void cosyVoicePassesLocalRefThroughForHostToUpload() {
        JSONObject enroll = adapter.buildEnrollment(
                LOCAL_VOICE_REF, HASH, Map.of("modelName", "cosyvoice-v3.5-plus"));

        assertNotNull(enroll, "本地参考音频同样要产出复刻请求体，否则宿主没机会上传换链接");
        assertEquals("voice-enrollment", enroll.getStr("model"));
        JSONObject in = enroll.getJSONObject("input");
        assertEquals("create_voice", in.getStr("action"));
        assertEquals("cosyvoice-v3.5-plus", in.getStr("target_model"));
        assertEquals(SEED_10, in.getStr("prefix"));
        assertEquals(LOCAL_VOICE_REF, in.getStr("url"),
                "插件只说「用这个引用」；换成能出网的形态是宿主的事（它会先传百炼文件空间拿链接）");
        assertFalse(in.containsKey("audio"), "该协议没有 audio 字段，别把 Qwen-TTS 的形状带过来");
    }

    @Test
    @DisplayName("已是 voice id / 系统音色 / 引用为空 → 不产出复刻请求体（返回 null）")
    void enrollmentIsSkippedWhenNotNeeded() {
        assertNull(adapter.buildEnrollment("qwen3-tts-vc-2026-01-22-dramatrix-abc123", HASH, Map.of()),
                "已是复刻好的 voice id，再建一个音色就是白花钱");
        assertNull(adapter.buildEnrollment("Cherry", HASH, Map.of()), "系统音色同样无需复刻");
        assertNull(adapter.buildEnrollment(null, HASH, Map.of()));
        assertNull(adapter.buildEnrollment("  ", HASH, Map.of()));
    }

    @Test
    @DisplayName("没配 modelName 时拒绝复刻：没有 target_model 就无法把音色绑到合成模型")
    void enrollmentRequiresTargetModel() {
        // 用一份没有 extra.defaults 的元信息，模拟「无任何默认值可兜底」的插件配置
        DashScopeVoiceCloneTtsAdapter bare = new DashScopeVoiceCloneTtsAdapter(new PluginMetadata());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bare.buildEnrollment(LOCAL_VOICE_REF, HASH, Map.of()));
        assertTrue(e.getMessage().contains("modelName"), "报错要点名缺哪个参数: " + e.getMessage());
    }

    // ════════════════════════════ 合成期：文生音频 ════════════════════════════

    @Test
    @DisplayName("默认：model 取 defaults，input.text/input.voice 为单段，语种默认 Auto")
    void buildsDefaultBody() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompts(List.of("今天天气怎么样？"))
                .timbres(List.of("qwen3-tts-vc-2026-01-22-dramatrix-abc123"))));

        assertEquals(DEFAULT_MODEL, body.getStr("model"), "应取 adapter.json 的 defaults");
        JSONObject in = body.getJSONObject("input");
        assertNotNull(in, "DashScope TTS 用原生 input（不是 OpenAI 的 messages）");
        assertEquals("今天天气怎么样？", in.getStr("text"));
        assertEquals("qwen3-tts-vc-2026-01-22-dramatrix-abc123", in.getStr("voice"),
                "已是 voice id，原样填入即可");
        assertEquals("Auto", in.getStr("language_type"));
    }

    @Test
    @DisplayName("🔴 音频引用在合成体里必须原样透传 —— 换 voice id 是宿主在进插件之前就做完的活，插件不判不拦")
    void passesAudioReferenceThrough() {
        // 宿主链路：解析音色（必要时复刻）→ 把 voice id 交给插件构建请求体 → 出网前再兜底一次。
        // 插件若在这里拒绝音频引用，那些「插件实现 buildEnrollment 但解析失败」的场景就再没有机会
        // 走到宿主那道响亮失败的防线，只会死在一句看不出原因的插件异常上。
        for (String ref : List.of(LOCAL_VOICE_REF, "https://cdn.example.com/narrator.wav")) {
            JSONObject body = adapter.buildApiJson(input(b -> b
                    .prompts(List.of("第一句")).timbres(List.of(ref))));
            assertEquals(ref, body.getJSONObject("input").getStr("voice"),
                    "原样透传，宿主才有机会用同一个引用去复刻: " + ref);
        }
    }

    @Test
    @DisplayName("🔴 非 Qwen-TTS 系（CosyVoice / Qwen-Audio-TTS）：body 用 format + sample_rate，不得混入 language_type")
    void buildsSpeechSynthesizerBodyForCosyVoice() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompts(List.of("我家的后面有一个很大的花园。"))
                .timbres(List.of("cosyvoice-v3-flash-5eb97da88f-ad65ade3938d443b86e0706d40e5a92b"))
                .options(Map.of("modelName", "cosyvoice-v3-flash"))));

        assertEquals("cosyvoice-v3-flash", body.getStr("model"));
        JSONObject in = body.getJSONObject("input");
        assertEquals("wav", in.getStr("format"), "该端点必须给输出格式");
        assertEquals(24000, in.getInt("sample_rate"), "该端点必须给采样率");
        assertFalse(in.containsKey("language_type"),
                "SpeechSynthesizer 端点没有 language_type 字段，带上它只会换回一个指不到原因的错误: " + in);
    }

    @Test
    @DisplayName("CosyVoice 侧的 format / sampleRate 可由 options 覆盖（defaults 只是兜底）")
    void speechSynthesizerFormatIsOverridable() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompts(List.of("第一句"))
                .timbres(List.of("cosyvoice-v3-flash-x-1"))
                .options(Map.of("modelName", "cosyvoice-v3-flash", "format", "mp3", "sampleRate", "16000"))));

        JSONObject in = body.getJSONObject("input");
        assertEquals("mp3", in.getStr("format"));
        assertEquals(16000, in.getInt("sample_rate"), "数字型参数写成字符串也要认");
    }

    @Test
    @DisplayName("绕过宿主上传的裸本地路径必须拒绝，而不是把路径当 voice id 送进厂商")
    void rejectsBareLocalPath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(
                input(b -> b.prompts(List.of("第一句"))
                        .timbres(List.of("/tmp/dramatrix/voice/narrator.mp3")))));

        assertTrue(e.getMessage().contains("media-file://"), "应说明正确的引用形态: " + e.getMessage());
    }

    @Test
    @DisplayName("分段未给音色时回落到 defaultVoice（系统音色 / 已复刻 id）")
    void fallsBackToDefaultVoice() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompts(List.of("第一句"))
                .timbres(List.of())
                .options(Map.of("defaultVoice", "Cherry"))));

        assertEquals("Cherry", body.getJSONObject("input").getStr("voice"));
    }

    @Test
    @DisplayName("多段入参必须拒绝：该端点一次只合成一段，宿主应逐段调用")
    void rejectsMultiSegment() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(
                input(b -> b.prompts(List.of("第一句", "第二句"))
                        .timbres(List.of("Cherry", "Cherry")))));

        assertTrue(e.getMessage().contains("segments()"), "应指出正确的调用方式: " + e.getMessage());
    }

    @Test
    @DisplayName("文本为空 / 无音色 / 无模型 都必须抛异常，不静默产出半成品请求体")
    void rejectsIncompleteInput() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b.prompts(List.of(" ")).timbres(List.of("Cherry")))));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b.prompts(List.of("第一句")))));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b.prompts(List.of("第一句"))
                        .timbres(List.of("Cherry"))
                        .options(Map.of("modelName", " ")))));
    }

    @Test
    @DisplayName("adapter.json 的 modelName 候选必须包含默认值，且默认模型支持本地音频复刻")
    void defaultModelSupportsLocalEnrollment() {
        JSONArray schema = (JSONArray) adapter.getMetadata().getExtra().get("paramSchema");
        for (Object o : schema) {
            JSONObject f = (JSONObject) o;
            if ("modelName".equals(f.getStr("key"))) {
                assertTrue(f.getJSONArray("options").contains(DEFAULT_MODEL));
            }
        }
        // 默认模型必须是 Qwen-TTS 系：只有它支持 Data URL 复刻，本地参考音频才能开箱即用
        assertTrue(adapter.buildEnrollment(LOCAL_VOICE_REF, HASH, Map.of())
                .getJSONObject("input").getStr("target_model").startsWith("qwen3-tts-vc"));
    }

    // ════════════════════════════ 夹具 ════════════════════════════

    /** 以 builder 回调方式构造入参，避免每个用例重复 new。 */
    private static Text2AudioInput input(Consumer<Text2AudioInput.Text2AudioInputBuilder> customizer) {
        Text2AudioInput.Text2AudioInputBuilder b = Text2AudioInput.builder();
        customizer.accept(b);
        return b.build();
    }
}
