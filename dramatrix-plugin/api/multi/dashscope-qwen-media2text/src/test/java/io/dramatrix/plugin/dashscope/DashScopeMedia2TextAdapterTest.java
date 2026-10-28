package io.dramatrix.plugin.dashscope;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.constants.Modality;
import io.dramatrix.plugin.dto.Media2TextInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 适配器契约测试：钉住「插件产出什么形状」。
 *
 * <p>这不是重复覆盖宿主侧的逻辑——宿主侧那个 {@code DashScopeComfyUIClient} 的契约测试用的是
 * <b>手写</b>请求体。真正决定线上形状的是本类，所以这里直接加载<b>真实的
 * {@code adapter.json}</b>，让「默认值 / 可选模型 / 模态声明」这几处配置漂移也能被测试抓到。</p>
 *
 * @author helei
 */
class DashScopeMedia2TextAdapterTest {

    /** 测试以模块目录为工作目录，故用相对路径直接读真实配置文件（而非另造一份夹具）。 */
    private static final File ADAPTER_JSON =
            new File("src/main/java/io/dramatrix/plugin/dashscope/adapter.json");

    private DashScopeMedia2TextAdapter adapter;

    @BeforeEach
    void setUp() {
        assertTrue(ADAPTER_JSON.exists(), "adapter.json 不存在: " + ADAPTER_JSON.getAbsolutePath());
        PluginMetadata metadata = JSONUtil.toBean(
                FileUtil.readString(ADAPTER_JSON, StandardCharsets.UTF_8), PluginMetadata.class);
        adapter = new DashScopeMedia2TextAdapter(metadata);
    }

    // ════════════════════════════ adapter.json 自检 ════════════════════════════

    @Test
    @DisplayName("adapter.json：kind=api / vendor=dashscope / 模态声明正确")
    void metadataIsWellFormed() {
        assertEquals("api", adapter.getMetadata().getKind(), "kind 必须是 api，否则会被当成 ComfyUI 插件");
        // 🔴 这个值与宿主侧反射出的厂商规格表是**跨模块契约**：BackendConfigService
        // 通过 MediaBackendProperties#getApiBackendClasses() 反射各厂商类上的 @VendorExtension(value=...)
        // 得到支持集合。通道的 vendor（BackendConfig.vendor，已不再经 extra 传递）必须一字不差地
        // 等于某厂商类注解声明的 vendor，否则插件永远匹配不上那条通道。
        // 两头都是精确匹配（PluginSelector.vendorOfBackend 用 equals），改这里必须同步改那边。
        assertEquals("dashscope", adapter.getMetadata().getVendor(),
                "插件 vendor 必须等于宿主侧某厂商 backend 类 @VendorExtension 声明的厂商标识");
        assertEquals("media2text", adapter.getMetadata().getType());
        assertEquals(Set.of(Modality.TEXT, Modality.IMAGE, Modality.VIDEO), adapter.supportedModalities());
    }

    @Test
    @DisplayName("可选模型里不得出现只支持文本 API 的 qwen3.7-max")
    void modelOptionsExcludeTextOnlyModel() {
        JSONArray schema = (JSONArray) adapter.getMetadata().getExtra().get("configSchema");
        JSONObject modelField = null;
        for (Object o : schema) {
            JSONObject f = (JSONObject) o;
            if ("modelName".equals(f.getStr("key"))) {
                modelField = f;
            }
        }
        assertNotNull(modelField, "configSchema 必须有 modelName");
        JSONArray options = modelField.getJSONArray("options");
        assertFalse(options.contains("qwen3.7-max"),
                "qwen3.7-max 只支持文本 API，放进多模态插件的可选项里等于埋雷");
        assertTrue(options.contains("qwen3.8-flash"));
    }

    // ════════════════════════════ 请求体形状 ════════════════════════════

    @Test
    @DisplayName("纯文本：messages 是数组，enable_thinking 在请求体顶层（不是 extra_body）")
    void buildsTextOnlyBody() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("说点什么")));

        assertEquals("qwen3.8-flash", body.getStr("model"), "应取 adapter.json 的 defaults");
        // 🔴 messages 必须是数组：写成 new JSONArray().add(x) 会得到 boolean true
        assertInstanceOf(JSONArray.class, body.get("messages"), "messages 必须是数组");
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(1, messages.size());
        JSONObject message = messages.getJSONObject(0);
        assertEquals("user", message.getStr("role"));
        JSONArray content = message.getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getStr("type"));
        assertEquals("说点什么", content.getJSONObject(0).getStr("text"));

        assertFalse(body.getBool("enable_thinking"), "默认必须关掉 thinking：视觉打标是机械任务，thinking 按输出价计费");
        assertEquals(2048, body.getInt("max_tokens"));
        assertEquals(0.6, body.getDouble("temperature"), 1e-9);
        // extra_body 是 Python SDK 概念，裸 HTTP 下不能出现
        assertFalse(body.containsKey("extra_body"));
    }

    @Test
    @DisplayName("图像：媒体元素在前、文本在后，占位符是 media-file://<本地绝对路径>（DashScope 的 imageFile 承载本地路径）")
    void buildsImageBodyWithPlaceholder() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("描述这张图").imageFile("/tmp/cat.png")));

        JSONArray content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        assertEquals(2, content.size(), "媒体元素 + 文本");

        JSONObject media = content.getJSONObject(0);
        assertEquals("image_url", media.getStr("type"));
        assertEquals("media-file:///tmp/cat.png", media.getJSONObject("image_url").getStr("url"),
                "插件没有 HTTP 能力，只能传占位符由宿主直接读本地路径转 Base64");

        assertEquals("描述这张图", content.getJSONObject(1).getStr("text"));
    }

    @Test
    @DisplayName("视频：video_url + fps")
    void buildsVideoBodyWithFps() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("描述视频").videoFile("/tmp/clip.mp4")));

        JSONObject media = body.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0);
        assertEquals("video_url", media.getStr("type"));
        assertEquals("media-file:///tmp/clip.mp4", media.getJSONObject("video_url").getStr("url"));
        assertEquals(2, media.getInt("fps"), "fps 应取 defaults.videoFps");
    }

    // ════════════════════════════ 参数优先级 ════════════════════════════

    @Test
    @DisplayName("options 覆盖 defaults；一等字段再覆盖 options")
    void optionPrecedence() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompt("hi")
                .options(Map.of("modelName", "qwen3.7-plus", "enableThinking", "true", "maxTokens", 100))));

        assertEquals("qwen3.7-plus", body.getStr("model"));
        assertTrue(body.getBool("enable_thinking"), "SELECT 传的是字符串 \"true\"，也要能解析成布尔");
        assertEquals(100, body.getInt("max_tokens"));

        JSONObject withFirstClass = adapter.buildApiJson(input(b -> b
                .prompt("hi")
                .maxTokens(77)
                .options(Map.of("maxTokens", 100))));
        assertEquals(77, withFirstClass.getInt("max_tokens"), "input.maxTokens 优先于 options");
    }

    // ════════════════════════════ 拒绝矩阵 ════════════════════════════

    @Test
    @DisplayName("qwen3.7-max 收到图像时必须拦下（extra.modalities 表达不了「这个模型不支持」）")
    void rejectsTextOnlyModelForImage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b
                        .prompt("看图").imageFile("cat.png").options(Map.of("modelName", "qwen3.7-max")))));
        assertTrue(ex.getMessage().contains("qwen3.7-max"));
    }

    @Test
    @DisplayName("qwen-vl-* 收到视频时必须拦下")
    void rejectsQwenVlForVideo() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b
                        .prompt("看视频").videoFile("clip.mp4").options(Map.of("modelName", "qwen-vl-max")))));
    }

    @Test
    @DisplayName("AUDIO 模态本期未开放，必须显式拒绝而不是静默丢掉音频")
    void rejectsAudio() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.buildApiJson(input(b -> b.prompt("听").audioFile("a.wav"))));
        assertTrue(ex.getMessage().contains("AUDIO"));
    }

    @Test
    @DisplayName("prompt 为空必须抛异常；空文件名按契约退化成 TEXT（守在上游）")
    void rejectsBlankPrompt() {
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(" "))));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(null))));
    }

    @Test
    @DisplayName("边界：imageFile 为空 ⇒ primaryModality()=TEXT（模态推断只看字段非空，不猜调用方意图）")
    void blankMediaFallsBackToTextModality() {
        Media2TextInput input = input(b -> b.prompt("描述").imageFile(""));

        assertEquals(Modality.TEXT, input.primaryModality());
        // 🔴 因此「调用方本意是 IMAGE、但上传失败丢了文件名」这种静默降级，插件<b>无法</b>察觉——
        //    守卫必须在宿主侧：MediaUnderstandService 在解析媒体引用后立即校验
        //    「声明的 modality 非 TEXT 却拿不到文件名」并抛错。
        JSONObject body = adapter.buildApiJson(input);
        assertEquals(1, body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").size(),
                "退化成 TEXT 后请求体里只有文本元素");
    }

    // ════════════════════════════ 夹具 ════════════════════════════

    /** 以 builder 回调方式构造入参，避免每个用例重复 new。 */
    private static Media2TextInput input(java.util.function.Consumer<Media2TextInput.Media2TextInputBuilder> customizer) {
        Media2TextInput.Media2TextInputBuilder b = Media2TextInput.builder();
        customizer.accept(b);
        return b.build();
    }
}
