package io.dramatrix.plugin.dashscope;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 文生图适配器契约测试：钉住「插件产出什么形状」。
 *
 * <h3>为什么这样测</h3>
 * <p>插件真正决定线上请求体形状的是本类，而非宿主侧手写的契约测试，故这里直接加载<b>真实的
 * {@code adapter.json}</b>，让「默认值 / 可选模型」配置漂移也被测试抓到。</p>
 *
 * <p>请求体形状承接宿主 {@code DashScopeMediaClient.toNativeBody()}：除 {@code model}/{@code messages}
 * 之外的字段会整体落入原生请求的 {@code parameters}，因此本测试断言这些参数在<b>请求体顶层</b>。</p>
 *
 * @author helei
 */
class DashScopeText2ImageAdapterTest {

    /** 测试以模块目录为工作目录，故用相对路径直接读真实配置文件（而非另造一份夹具）。 */
    private static final File ADAPTER_JSON =
            new File("src/main/java/io/dramatrix/plugin/dashscope/adapter.json");

    private DashScopeText2ImageAdapter adapter;

    @BeforeEach
    void setUp() {
        assertTrue(ADAPTER_JSON.exists(), "adapter.json 不存在: " + ADAPTER_JSON.getAbsolutePath());
        PluginMetadata metadata = JSONUtil.toBean(
                FileUtil.readString(ADAPTER_JSON, StandardCharsets.UTF_8), PluginMetadata.class);
        adapter = new DashScopeText2ImageAdapter(metadata);
    }

    // ════════════════════════════ adapter.json 自检 ════════════════════════════

    @Test
    @DisplayName("adapter.json：kind=api / vendor=dashscope / type=text2image_with_lora")
    void metadataIsWellFormed() {
        assertEquals("api", adapter.getMetadata().getKind(), "kind 必须是 api，否则会被当成 ComfyUI 插件");
        // 跨模块契约：插件 vendor 必须等于宿主某厂商 backend 类 @VendorExtension 声明的厂商标识，
        // 否则那条通道永远匹配不上（PluginSelector.vendorOfBackend 用 equals，精确匹配）。
        assertEquals("dashscope", adapter.getMetadata().getVendor());
        assertEquals("text2image_with_lora", adapter.getMetadata().getType());
    }

    @Test
    @DisplayName("默认模型必须是走原生 multimodal-generation 端点能用的 qwen-image-2.0-pro")
    void defaultModelIsEndpointCompatible() {
        JSONArray schema = (JSONArray) adapter.getMetadata().getExtra().get("paramSchema");
        for (Object o : schema) {
            JSONObject f = (JSONObject) o;
            if ("modelName".equals(f.getStr("key"))) {
                JSONArray options = f.getJSONArray("options");
                assertTrue(options.contains("qwen-image-2.0-pro"));
                assertTrue(options.contains("qwen-image-2.0"));
            }
        }
    }

    // ════════════════════════════ 请求体形状 ════════════════════════════

    @Test
    @DisplayName("默认：model=2.0-pro，messages 是单条 user+text，参数字段在请求体顶层")
    void buildsDefaultBody() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("一只坐着的橘猫")));

        assertEquals("qwen-image-2.0-pro", body.getStr("model"), "应取 adapter.json 的 defaults");
        assertInstanceOf(JSONArray.class, body.get("messages"), "messages 必须是数组");
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(1, messages.size());
        JSONObject message = messages.getJSONObject(0);
        assertEquals("user", message.getStr("role"));
        JSONArray content = message.getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getStr("type"));
        assertEquals("一只坐着的橘猫", content.getJSONObject(0).getStr("text"));

        // 除 model/messages 外全部落在请求体顶层，宿主 toNativeBody 会收进 parameters
        assertFalse(body.getBool("prompt_extend"), "默认关掉智能改写：分镜希望输出紧贴输入 prompt，而非被改写润色");
        assertFalse(body.getBool("watermark"), "默认无水印");
        assertEquals("2048*2048", body.getStr("size"));
        assertEquals(1, body.getInt("n"));
        assertFalse(body.containsKey("negative_prompt"), "未填反向提示词时不写该字段");
        assertFalse(body.containsKey("seed"), "未填种子时不写该字段（交给厂商随机）");
    }

    @Test
    @DisplayName("显式传入的反向提示词 / 宽高 / 张数 / 种子会写进请求体，宽高超限按模型约束等比缩放")
    void honorExplicitFields() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompt("海边灯塔")
                .negativePrompt("低质量，模糊")
                .width(3072)
                .height(3072)
                .n(3)
                .seed(42)));

        assertEquals("低质量，模糊", body.getStr("negative_prompt"));
        // 正方形 3072*3072 超出 qwen-image-2.0 的 2048*2048 上限，等比收敛到 2048*2048
        assertEquals("2048*2048", body.getStr("size"));
        assertEquals(3, body.getInt("n"));
        assertEquals(42, body.getInt("seed"));
    }

    // ════════════════════════════ 参数优先级 ════════════════════════════

    @Test
    @DisplayName("options 覆盖 defaults；一等字段再覆盖 options；分辨率由画布宽高比就近选固定预设")
    void optionPrecedence() {
        JSONObject viaOptions = adapter.buildApiJson(input(b -> b
                .prompt("x")
                .width(1920)
                .height(1080)
                .options(Map.of("modelName", "qwen-image-max", "promptExtend", "true", "watermark", "true", "n", 2))));
        assertEquals("qwen-image-max", viaOptions.getStr("model"));
        assertTrue(viaOptions.getBool("prompt_extend"), "SELECT 传字符串 \"true\" 也要能解析成布尔");
        assertTrue(viaOptions.getBool("watermark"));
        // 16:9 画布 → 就近固定预设 1664*928
        assertEquals("1664*928", viaOptions.getStr("size"));
        assertEquals(2, viaOptions.getInt("n"));

        JSONObject viaFirstClass = adapter.buildApiJson(input(b -> b
                .prompt("x")
                .n(6)
                .options(Map.of("n", 2))));
        assertEquals(6, viaFirstClass.getInt("n"), "input.n 优先于 options");
    }

    // ════════════════════════════ 拒绝矩阵 ════════════════════════════

    @Test
    @DisplayName("prompt 为空必须抛异常")
    void rejectsBlankPrompt() {
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(" "))));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(null))));
    }

    // ════════════════════════════ 夹具 ════════════════════════════

    /** 以 builder 回调方式构造入参，避免每个用例重复 new。 */
    private static Text2ImageInput input(Consumer<Text2ImageInput.Text2ImageInputBuilder> customizer) {
        Text2ImageInput.Text2ImageInputBuilder b = Text2ImageInput.builder();
        customizer.accept(b);
        return b.build();
    }
}