package io.dramatrix.plugin.gpt;

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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GPT 文生图适配器契约测试：钉住「插件产出什么形状」。
 *
 * <h3>为什么这样测</h3>
 * <p>插件真正决定线上请求体形状的是本类，而非宿主侧手写的契约测试，故这里直接加载<b>真实的
 * {@code adapter.json}</b>，让「默认值 / 可选模型」配置漂移也被测试抓到。</p>
 *
 * <p>形状承接宿主 {@code GptMediaRequestHandler#generateImages}：请求体会被<b>原样</b>
 * POST 到 {@code {baseUrl}/images/generations}（OpenAI 官方不存在形状转换），
 * 因此断言的是「真正发出去的字段」。其中最关键的一条是
 * <b>绝不能出现 {@code messages}</b> —— 那是对话端点的形状，混进来只会换回一句
 * {@code 400 unsupported}，而报错指不回这里。</p>
 *
 * <h3>size 是本插件唯一的"算法"，故用独立实现交叉验证</h3>
 * <p>gpt-image-2 系接受<b>任意</b>分辨率，插件会对不合法的输入做等比修正。这里刻意
 * <b>另写一份</b> {@link #assertLegal(String)}（不调用被测代码的任何常量），把
 * 「发出去的每一个 size 都满足官方四条约束」变成可执行断言 —— 否则"修复逻辑"与"校验逻辑"
 * 同源，写错了也测不出来。</p>
 *
 * @author helei
 */
class GptImageText2ImageAdapterTest {

    /** 测试以模块目录为工作目录，故用相对路径直接读真实配置文件（而非另造一份夹具）。 */
    private static final File ADAPTER_JSON =
            new File("src/main/java/io/dramatrix/plugin/gpt/adapter.json");

    // ── 官方四条约束（测试侧独立副本，故意不从被测类取） ──
    private static final int STEP = 16;
    private static final int MAX_EDGE = 3840;
    private static final long MIN_PIXELS = 655_360L;
    private static final long MAX_PIXELS = 8_294_400L;
    private static final int RATIO_MAX = 3;

    private GptImageText2ImageAdapter adapter;

    @BeforeEach
    void setUp() {
        assertTrue(ADAPTER_JSON.exists(), "adapter.json 不存在: " + ADAPTER_JSON.getAbsolutePath());
        PluginMetadata metadata = JSONUtil.toBean(
                FileUtil.readString(ADAPTER_JSON, StandardCharsets.UTF_8), PluginMetadata.class);
        adapter = new GptImageText2ImageAdapter(metadata);
    }

    // ════════════════════════════ adapter.json 自检 ════════════════════════════

    @Test
    @DisplayName("adapter.json：kind=api / vendor=gpt / type=text2image_with_lora / baseModel=GPT-Image")
    void metadataIsWellFormed() {
        assertEquals("api", adapter.getMetadata().getKind(), "kind 必须是 api，否则会被当成 ComfyUI 插件");
        // 跨模块契约：插件 vendor 必须等于宿主 GptBackend 上 @VendorExtension(value="gpt") 声明的厂商标识，
        // 否则那条通道永远匹配不上（PluginSelector.vendorOfBackend 用 equals，精索匹配）。
        assertEquals("gpt", adapter.getMetadata().getVendor());
        assertEquals("text2image_with_lora", adapter.getMetadata().getType());
        // ComfyBaseModel 是闭集枚举，fromString 遇未知值抛异常 → 这个名字必须先在枚举里登记
        assertEquals("GPT-Image", adapter.getMetadata().getBaseModel().getDisplayName());
    }

    @Test
    @DisplayName("paramSchema 的模型下拉只放 2.0 / 2.5 两代，且默认值在选项内")
    void schemaExposesSupportedModels() {
        JSONObject modelField = field("modelName");
        JSONArray options = modelField.getJSONArray("options");
        assertTrue(options.contains("gpt-image-2"));
        assertTrue(options.contains("gpt-image-2.5-flare"));
        assertTrue(options.contains("gpt-image-2.5-sunburst"));
        assertTrue(options.contains(modelField.getStr("defaultValue")), "默认模型必须在可选项里");
        assertEquals(3, options.size(), "只支持 2.0 / 2.5 两代；多出来的选项会让用户选中后走进 UNKNOWN 降级");
    }

    @Test
    @DisplayName("paramSchema 暴露 size（TEXT）—— 任意分辨率能力必须能从表单传进来")
    void schemaExposesSize() {
        JSONObject sizeField = field("size");
        assertEquals("TEXT", sizeField.getStr("type"));
        assertTrue(sizeField.getStr("placeholder").contains("16"),
                "占位符要写清 16 倍数等约束，否则用户只能靠 400 报错猜");
    }

    // ════════════════════════════ 请求体形状 ════════════════════════════

    @Test
    @DisplayName("默认：扁平 prompt（不是 messages），带 size/quality/output_format/background")
    void buildsDefaultBody() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("一只坐着的橘猫")));

        assertEquals("gpt-image-2", body.getStr("model"), "应取 adapter.json 的 defaults");
        assertEquals("一只坐着的橘猫", body.getStr("prompt"));
        // 🔴 生图端点吃扁平 prompt；messages 是对话端点的形状，出现即代表走错端点
        assertFalse(body.containsKey("messages"), "Images API 不能出现对话端点的 messages 字段");

        assertEquals("1024x1024", body.getStr("size"), "未给宽高时用官方常用尺寸的正方形档（生成最快）");
        assertLegal(body.getStr("size"));
        assertEquals(1, body.getInt("n"));
        assertEquals("auto", body.getStr("quality"));
        assertEquals("png", body.getStr("output_format"));
        assertEquals("auto", body.getStr("background"));
        // style 是 dall-e-3 独有；response_format 生图端点不吃（gpt-image 系恒回 Base64）
        assertFalse(body.containsKey("style"), "style 不是 gpt-image 系的参数，发给它会 400");
        assertFalse(body.containsKey("response_format"), "gpt-image 系不支持 response_format");
        assertFalse(body.containsKey("seed"), "Images API 没有 seed 参数（照发会 400 Unrecognized argument）");
    }

    // ════════════════════════════ size：任意分辨率 ════════════════════════════

    @Test
    @DisplayName("显式且合法的 size 必须原样下发（一个像素都不改），含宿主「宽*高」分隔符与 auto")
    void honorsLegalExplicitSize() {
        // 任意分辨率能力的关键：1280x720 不是官方"常用尺寸"，但满足四条约束 → 必须照发
        assertEquals("1280x720", sizeOf(b -> b.size("1280x720")), "合法的自定义尺寸不能被吸附到档位");
        assertEquals("2048x1152", sizeOf(b -> b.size("2048x1152")));
        assertEquals("3840x2160", sizeOf(b -> b.size("3840x2160")), "满像素上限的 4K 也是合法值");
        assertEquals("2160x3840", sizeOf(b -> b.size("2160x3840")));

        // 宿主 Text2ImageInput#size 的约定是「宽*高」，必须同样命中，不能因分隔符不同而静默退化
        assertEquals("1024x1536", sizeOf(b -> b.size("1024*1536")), "「宽*高」形态应归一为 x 形态下发");
        // auto 是官方允许的取值，必须透传（而不是被当成"解析失败"）
        assertEquals("auto", sizeOf(b -> b.size("auto")));
        assertEquals("auto", sizeOf(b -> b.size("AUTO")));
    }

    @Test
    @DisplayName("options.size 通道：前端参数表单填的分辨率同样生效（一等字段为空时才走它）")
    void honorsSizeFromOptions() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("x").width(1024).height(1024)
                .options(Map.of("size", "2048x2048"))));
        assertEquals("2048x2048", body.getStr("size"), "options.size 应优先于画布宽高");

        // 一等字段在场时压过 options（与其它参数同一优先级口径）
        JSONObject firstClass = adapter.buildApiJson(input(b -> b.prompt("x").size("1536x1024")
                .options(Map.of("size", "2048x2048"))));
        assertEquals("1536x1024", firstClass.getStr("size"));
    }

    @Test
    @DisplayName("🔴 不合法的显式 size 会被修正（而不是丢弃、也不是让生成失败），且修正结果必然合法")
    void repairsIllegalExplicitSize() {
        // 1080 不是 16 的倍数 —— 官方文档里点名的经典坑
        String from1080 = sizeOf(b -> b.size("1920x1080"));
        assertLegal(from1080);
        assertNotEquals("1920x1080", from1080, "1080 不是 16 的倍数，不能照发");
        assertRatioClose(16, 9, from1080);

        // 低于像素下限、超单边、超 3:1、非 16 倍数 —— 逐个都要落到合法区间
        for (String bad : List.of("512x512", "100x100", "4096x2160", "2560x640", "8000x1000", "1023x1023", "1x1")) {
            String fixed = sizeOf(b -> b.size(bad));
            assertLegal(fixed);
        }
    }

    @Test
    @DisplayName("面积双向夹逼：过小的画布被抬到像素下限，4096x4096 被压到面积上限")
    void clampsTotalPixels() {
        // 100x100 = 10,000 px，远低于下限 → 等比放大到最近的合法正方形
        assertEquals("816x816", sizeOf(b -> b.size("100x100")), "应抬到 ≥655,360 像素的最近 16 倍数档");
        // 4096x4096 = 16.7M px，超上限 → 缩到 2880x2880（= 8,294,400，正好是上限）
        assertEquals("2880x2880", sizeOf(b -> b.size("4096x4096")), "应压到像素上限");
        assertLegal("2880x2880");
    }

    @Test
    @DisplayName("画布宽高按比例推导：不再粗化到 1536x1024，而是保留画布比例与量级")
    void derivesSizeFromCanvasKeepingAspect() {
        String sixteen9 = sizeOf(b -> b.width(1920).height(1080));
        assertLegal(sixteen9);
        assertRatioClose(16, 9, sixteen9);
        assertNotEquals("1536x1024", sixteen9, "1920x1080 画布不该被吸附到 1536x1024 小档");

        assertRatioClose(9, 16, sizeOf(b -> b.width(1080).height(1920)));
        assertRatioClose(1, 1, sizeOf(b -> b.width(1024).height(1024)));
        assertRatioClose(1, 1, sizeOf(b -> b.width(3000).height(3000)));

        // 乱填/缺失画布 → 常用尺寸兜底，不能产出 null 或非法值
        assertEquals("1024x1024", sizeOf(b -> b.width(0).height(0)));
        assertEquals("1024x1024", sizeOf(b -> b.width(null).height(null)));
    }

    @Test
    @DisplayName("🔴 穷举一批刁钻画布：发出去的 size 必须 100% 满足官方四条约束，且落在默认 maxSize 内")
    void everyDerivedSizeSatisfiesOfficialRules() {
        int[][] canvases = {
                {1, 1}, {7, 900}, {9, 4096}, {16, 16}, {100, 3000}, {320, 480}, {512, 768}, {640, 480},
                {720, 1280}, {1080, 1920}, {1280, 720}, {1366, 768}, {1440, 2560}, {1920, 1080},
                {2048, 2048}, {2560, 1440}, {3000, 3000}, {3840, 2160}, {4096, 2160}, {4096, 4096},
                {5000, 1000}, {6000, 6000}, {7680, 4320}, {10000, 10000},
        };
        for (int[] c : canvases) {
            String derived = sizeOf(b -> b.width(c[0]).height(c[1]));
            assertLegal(derived);
            // 默认上限（2560x1440）足够大，任何画布都装得下厂商的像素下限 → 这里不该触发"放弃上限"的兜底
            assertFitsDefaultBox(derived);
            // 比例意图必须被保留（等比修正的承诺），"合法但把 16:9 变成 1:1" 视为回归
            double wanted = (double) c[0] / c[1];
            double got = ratioOf(derived);
            double bent = Math.max(wanted, got) / Math.min(wanted, got);
            assertTrue(bent <= 1.06 || wanted > RATIO_MAX || wanted < 1d / RATIO_MAX,
                    "画布 " + c[0] + "x" + c[1] + " 推导出 " + derived + "，比例偏离过大");
        }
    }

    // ════════════════════════════ maxSize：可配置的推导上限 ════════════════════════════

    @Test
    @DisplayName("🔴 默认 maxSize=2560x1440：画布推导不再产出 2K 以上（官方实验区间之外）的图")
    void defaultMaxSizeCapsDerivedSize() {
        // 4096x4096 若只按厂商面积上限会得到 2880x2880（8.29MP）——默认上限把它压到 1440x1440（2.07MP）
        assertEquals("1440x1440", sizeOf(b -> b.width(4096).height(4096)));
        assertEquals("1440x1440", sizeOf(b -> b.width(10000).height(10000)));
        // 恰好等于上限的画布原样通过（上限是"不超过"，不是"必须是"）
        assertEquals("2560x1440", sizeOf(b -> b.width(3840).height(2160)));
        assertEquals("2560x1440", sizeOf(b -> b.width(2560).height(1440)));
        // 竖版：受 1440 的高度上限约束 → 等比缩到 810x1440，16 对齐后 816x1440
        assertEquals("816x1440", sizeOf(b -> b.width(1080).height(1920)));
        // 上限之内的画布不受影响（这条是回归护栏：上限不能把正常尺寸也改了）
        assertEquals("1920x1088", sizeOf(b -> b.width(1920).height(1080)));
    }

    @Test
    @DisplayName("🔴 显式 size 不受 maxSize 裁剪（显式即意图），只按厂商四条约束判")
    void explicitSizeWinsOverMaxSize() {
        // 2048x2048 高 2048 > 上限 1440，但显式给出 → 原样下发
        assertEquals("2048x2048", sizeOf(b -> b.size("2048x2048")));
        assertEquals("3840x2160", sizeOf(b -> b.size("3840x2160")));
        // 同一组画布：显式给 4K 走 4K，不给则被上限收到 2560x1440
        assertEquals("3840x2160", sizeOf(b -> b.size("3840x2160").width(3840).height(2160)));
        assertEquals("2560x1440", sizeOf(b -> b.width(3840).height(2160)));
    }

    @Test
    @DisplayName("maxSize 可配置：off/none/0 = 不限（只剩厂商 3840），也可收紧到 1024x1024")
    void maxSizeIsConfigurable() {
        // 不限 → 回到厂商的四条硬约束：4096x4096 按面积上限修成 2880x2880
        for (String unlimited : List.of("off", "none", "0", "0x0")) {
            assertEquals("2880x2880", sizeOf(b -> b.width(4096).height(4096)
                    .options(Map.of("maxSize", unlimited))), "maxSize=" + unlimited + " 应视为不限");
        }
        // 收到 1024x1024
        assertEquals("1024x1024", sizeOf(b -> b.width(4096).height(4096)
                .options(Map.of("maxSize", "1024x1024"))));
        // 放到厂商单边上限 → 7680x4320 画布给到 3840x2160
        assertEquals("3840x2160", sizeOf(b -> b.width(7680).height(4320)
                .options(Map.of("maxSize", "3840x2160"))));
        // 超过厂商单边无意义：等效于 3840 单边
        assertEquals("2880x2880", sizeOf(b -> b.width(4096).height(4096)
                .options(Map.of("maxSize", "8192x8192"))));
    }

    @Test
    @DisplayName("maxSize 配置写坏 → 回到默认 2560x1440（不静默变成「不限」）")
    void maxSizeGarbageFallsBackToDefault() {
        for (String bad : List.of("abc", "2560", "1x", "-16x-16")) {
            assertEquals("1440x1440", sizeOf(b -> b.width(4096).height(4096)
                    .options(Map.of("maxSize", bad))), "maxSize=" + bad + " 应回默认上限");
        }
    }

    @Test
    @DisplayName("🔴 上限小到装不下厂商像素下限时，忽略该上限（画布照常推导），而不是发出违反厂商约束的尺寸")
    void infeasibleMaxSizeRelaxesToVendorRules() {
        // 800x800 的框最大只有 640,000 px < 厂商下限 655,360 → 该上限下不存在任何合法尺寸
        String derived = sizeOf(b -> b.width(1024).height(1024).options(Map.of("maxSize", "800x800")));
        assertLegal(derived);
        assertEquals("1024x1024", derived, "应忽略不可行的上限，改按画布 + 厂商约束推导（而不是缩到 816x816 那种两头不靠的值）");
    }

    @Test
    @DisplayName("显式但非法的 size 也不吃 maxSize —— 修正是按厂商约束做的")
    void illegalExplicitSizeIgnoresMaxSize() {
        // 4096x4096 显式但非法 → 按厂商面积上限修成 2880x2880（而不是被 maxSize 收到 1440x1440）
        assertEquals("2880x2880", sizeOf(b -> b.size("4096x4096")));
    }

    @Test
    @DisplayName("无法解析的 size 文本不报错，改为按画布推导（排障日志留痕）")
    void unparseableSizeFallsBackToCanvas() {
        assertLegal(sizeOf(b -> b.size("big").width(1920).height(1080)));
        assertLegal(sizeOf(b -> b.size("1024").width(1920).height(1080)));
        assertEquals("1024x1024", sizeOf(b -> b.size("1024x")), "半截值不该被当成尺寸，也不能崩");
    }

    // ════════════════════════════ 家族矩阵 ════════════════════════════

    @Test
    @DisplayName("quality 档位按代区分：2.5 认 xhigh/max，2.0 收到它们时降级为 auto（不硬失败）")
    void qualityTiersDifferByGeneration() {
        assertEquals("xhigh", qualityOf("gpt-image-2.5-sunburst", "xhigh"));
        assertEquals("max", qualityOf("gpt-image-2.5-flare", "max"));
        assertEquals("high", qualityOf("gpt-image-2", "high"));
        // 2.0 没有 xhigh/max → 降级到家族默认 auto + WARN，而不是让整次生成失败
        assertEquals("auto", qualityOf("gpt-image-2", "xhigh"));
        assertEquals("auto", qualityOf("gpt-image-2", "hd"), "dall-e 系的档位同样不认");
    }

    @Test
    @DisplayName("background=transparent 配 output_format=jpeg 会 400 → 降 background 为 auto，保住 jpeg")
    void transparentBackgroundDegradesForJpeg() {
        JSONObject body = adapter.buildApiJson(input(b -> b
                .prompt("x")
                .options(Map.of("background", "transparent", "outputFormat", "jpeg"))));

        assertEquals("jpeg", body.getStr("output_format"), "格式是用户更明确的诉求（体积），保留");
        assertEquals("auto", body.getStr("background"), "透明只有 png/webp 能承载，故降掉 background");

        JSONObject png = adapter.buildApiJson(input(b -> b
                .prompt("x")
                .options(Map.of("background", "transparent", "outputFormat", "png"))));
        assertEquals("transparent", png.getStr("background"), "png 下透明是完全合法的，不该被无谓降级");
    }

    @Test
    @DisplayName("n 收敛到 10；≤0 抬到 1")
    void clampsN() {
        assertEquals(10, adapter.buildApiJson(input(b -> b.prompt("x").n(50))).getInt("n"));
        assertEquals(1, adapter.buildApiJson(input(b -> b.prompt("x").n(0))).getInt("n"));
        assertEquals(3, adapter.buildApiJson(input(b -> b.prompt("x").n(3))).getInt("n"));
    }

    @Test
    @DisplayName("🔴 不在支持范围的模型（gpt-image-1 / dall-e / 网关别名）→ 只发 model/prompt 并降级")
    void unsupportedModelFallsBackToMinimalBody() {
        for (String model : List.of("gpt-image-1", "gpt-image-1-mini", "dall-e-3", "dall-e-2", "my-gateway-alias-v2")) {
            JSONObject body = adapter.buildApiJson(input(b -> b
                    .prompt("x")
                    .width(1920)
                    .height(1080)
                    .n(3)
                    .options(Map.of("modelName", model, "quality", "high"))));

            assertEquals(model, body.getStr("model"));
            assertEquals("x", body.getStr("prompt"));
            assertEquals(2, body.size(),
                    "老两代的 size 规则与本插件不同，硬套比不发更危险 → 只应剩 model + prompt，实际: " + body);
        }
    }

    // ════════════════════════════ 通道缺口（刻意不静默） ════════════════════════════

    @Test
    @DisplayName("🔴 seed 任何家族都不下发 —— Images API 整个端点没有 seed 参数，照发会 400")
    void seedIsNeverSent() {
        assertFalse(adapter.buildApiJson(input(b -> b.prompt("x").seed(42)))
                .containsKey("seed"));
        assertFalse(adapter.buildApiJson(input(b -> b.prompt("x")
                        .options(Map.of("modelName", "gpt-image-2.5-flare", "seed", 42))))
                .containsKey("seed"));
    }

    @Test
    @DisplayName("negativePrompt 不下发（Images API 无此能力）")
    void negativePromptIsNotSent() {
        JSONObject body = adapter.buildApiJson(input(b -> b.prompt("x").negativePrompt("低质量")));
        assertFalse(body.containsKey("negative_prompt"));
        assertFalse(body.containsKey("negativePrompt"));
    }

    @Test
    @DisplayName("prompt 为空必须抛异常")
    void rejectsBlankPrompt() {
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(" "))));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(input(b -> b.prompt(null))));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildApiJson(null));
    }

    // ════════════════════════════ 夹具与断言工具 ════════════════════════════

    /** 取请求体里的 size；prompt 由本方法预置（只关心尺寸的用例不必重复写 prompt）。 */
    private String sizeOf(Consumer<Text2ImageInput.Text2ImageInputBuilder> customizer) {
        return adapter.buildApiJson(input(b -> {
            b.prompt("x");
            customizer.accept(b);
        })).getStr("size");
    }

    private String qualityOf(String model, String quality) {
        return adapter.buildApiJson(input(b -> b.prompt("x")
                .options(Map.of("modelName", model, "quality", quality)))).getStr("quality");
    }

    /** 按 key 取 paramSchema 里的字段定义。 */
    private JSONObject field(String key) {
        JSONArray schema = (JSONArray) adapter.getMetadata().getExtra().get("paramSchema");
        for (Object o : schema) {
            JSONObject f = (JSONObject) o;
            if (key.equals(f.getStr("key"))) {
                return f;
            }
        }
        return fail("paramSchema 必须声明 " + key);
    }

    /**
     * 官方四条约束的<b>独立</b>实现（不从被测类取任何常量）：
     * 两边为 16 的倍数、单边 ≤ 3840、长短边比 ≤ 3:1、总像素 ∈ [655,360, 8,294,400]。
     */
    private static void assertLegal(String size) {
        assertNotNull(size, "size 不能为 null");
        assertTrue(size.matches("\\d+x\\d+"), "size 必须是「宽x高」形态，实际: " + size);
        int idx = size.indexOf('x');
        int w = Integer.parseInt(size.substring(0, idx));
        int h = Integer.parseInt(size.substring(idx + 1));
        assertEquals(0, w % STEP, "宽必须是 " + STEP + " 的倍数: " + size);
        assertEquals(0, h % STEP, "高必须是 " + STEP + " 的倍数: " + size);
        assertTrue(Math.max(w, h) <= MAX_EDGE, "单边不得超过 " + MAX_EDGE + ": " + size);
        assertTrue(Math.max(w, h) <= (long) RATIO_MAX * Math.min(w, h), "长短边比不得超过 3:1: " + size);
        long area = (long) w * h;
        assertTrue(area >= MIN_PIXELS, "总像素低于下限 " + MIN_PIXELS + ": " + size);
        assertTrue(area <= MAX_PIXELS, "总像素高于上限 " + MAX_PIXELS + ": " + size);
    }

    /** 断言 size 落在插件默认的 maxSize（2560x1440）之内。 */
    private static void assertFitsDefaultBox(String size) {
        int idx = size.indexOf('x');
        int w = Integer.parseInt(size.substring(0, idx));
        int h = Integer.parseInt(size.substring(idx + 1));
        assertTrue(w <= 2560 && h <= 1440, "推导结果应落在默认 maxSize 2560x1440 内，实际: " + size);
    }

    /** 断言 size 的宽高比与期望比接近（容忍 16 对齐带来的微小偏差）。 */
    private static void assertRatioClose(int rw, int rh, String size) {
        double wanted = (double) rw / rh;
        double got = ratioOf(size);
        double bent = Math.max(wanted, got) / Math.min(wanted, got);
        assertTrue(bent <= 1.01, "比例偏离过大：期望 " + rw + ":" + rh + "，实际 " + size);
    }

    private static double ratioOf(String size) {
        int idx = size.indexOf('x');
        return (double) Integer.parseInt(size.substring(0, idx)) / Integer.parseInt(size.substring(idx + 1));
    }

    /** 以 builder 回调方式构造入参，避免每个用例重复 new。 */
    private static Text2ImageInput input(Consumer<Text2ImageInput.Text2ImageInputBuilder> customizer) {
        Text2ImageInput.Text2ImageInputBuilder b = Text2ImageInput.builder();
        if (customizer != null) {
            customizer.accept(b);
        }
        return b.build();
    }
}
