package io.dramatrix.plugin.minimaxh3fl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首尾帧插件（fl2va）工作流拼接的绑定 / 挂载回归。
 *
 * <p>覆盖三类最容易静默失效的行为：</p>
 * <ol>
 *   <li><b>内置默认绑定可解析</b>：{@code DEFAULT_BINDING} 只在插件目录缺 {@code workflow.binding.json}
 *       时兜底，而 {@code PluginRegistry} 正常都会读文件，所以这条分支平时走不到 ——
 *       常量一旦写成片段（如漏最外层 {@code {}}），线上只会在文件缺失时炸掉。
 *       因此本测试<b>刻意不注入 binding</b>。注意：绑定一旦生效，每个已声明参数都必须能定位到节点，
 *       所以测试工作流必须包含全部落点（否则 {@code resolveNodeTarget} 直接抛「绑定定位失败」）。</li>
 *   <li><b>首尾帧挂到主节点的 {@code first_frame} / {@code last_frame}</b>：这是本插件与 ref2va 的
 *       唯一区别。同时验证「模板里旧的挂载连线会被清掉」—— 否则不给首帧时会把模板占位图当首帧提交。</li>
 *   <li><b>宽高 / 帧数不被 Java 覆盖</b>：宽高默认由 {@code ResolutionSelector} 连线给出，
 *       帧数由工作流内表达式对齐 {@code 17k+5}；Java 只写上游 {@code PrimitiveFloat}。</li>
 * </ol>
 *
 * <p>测试工作流在真实模板的基础上多挂了一条 {@code last_frame → 117} 的旧连线（真实模板只有
 * {@code first_frame → 114}），用来把「两个组的旧挂载都会被清空」这件事一起测掉。</p>
 */
class MiniMaxH3FlDefaultBindingTest {

    /** 只保留绑定会触碰到的落点：104=主节点(anchor)、15=seed、91=fps、9=steps、111=duration、114=占位首帧。 */
    private static final String WORKFLOW = """
            {
              "6":   { "class_type": "UNETLoader", "inputs": { "unet_name": "m.safetensors" } },
              "9":   { "class_type": "BasicScheduler", "inputs": { "steps": 20, "model": ["6", 0] } },
              "15":  { "class_type": "RandomNoise", "inputs": { "noise_seed": 0 } },
              "91":  { "class_type": "CreateVideo", "inputs": { "fps": 24 } },
              "104": { "class_type": "MiniMaxH3ImageToVideo",
                       "inputs": { "prompt": "", "width": ["115", 0], "height": ["115", 1],
                                   "length": ["107", 1],
                                   "first_frame": ["114", 0], "last_frame": ["117", 0] } },
              "107": { "class_type": "ComfyMathExpression", "inputs": { "expression": "x", "values.a": ["111", 0] } },
              "111": { "class_type": "PrimitiveFloat", "inputs": { "value": 5 } },
              "114": { "class_type": "LoadImage", "inputs": { "image": "" } },
              "117": { "class_type": "LoadImage", "inputs": { "image": "leftover.png" } },
              "115": { "class_type": "ResolutionSelector", "inputs": { "aspect_ratio": "1:1 (Square)" } }
            }
            """;

    private static final String MAIN = "104";

    /** 不带 workflow.binding.json 的适配器：绑定为 null，构建时走内置默认绑定。 */
    private static MiniMaxH3FlAdapter adapterWithoutBindingFile() {
        PluginMetadata metadata = new PluginMetadata();
        metadata.setName("minimax-h3-i2v-fl");
        MiniMaxH3FlAdapter adapter = new MiniMaxH3FlAdapter(metadata);
        adapter.setWorkflowJson(WORKFLOW);
        return adapter;
    }

    private static JSONObject inputsOf(JSONObject api, String nodeId) {
        return api.getJSONObject(nodeId).getJSONObject("inputs");
    }

    /** 取出挂载点的节点号（形如 {@code ["2000", 0]}）。 */
    private static String mountNodeId(JSONObject api, String inputName) {
        JSONArray mount = inputsOf(api, MAIN).getJSONArray(inputName);
        assertNotNull(mount, inputName + " 未挂载");
        return mount.getStr(0);
    }

    /** 各用例共用的入参起点：只给一条提示词，其余走默认。 */
    private static Image2VideoRefInput.Image2VideoRefInputBuilder base() {
        return Image2VideoRefInput.builder().prompts(List.of("hello"));
    }

    /** 内置默认绑定可解析，且 prompt / seed / fps / steps 落到预期节点。 */
    @Test
    @DisplayName("内置默认绑定：prompt / seed / fps / steps 落到预期节点")
    void defaultBinding_appliesParametersWhenBindingFileMissing() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(
                base().steps(12).seed(42).fps(24).build());

        assertEquals("hello", inputsOf(api, MAIN).getStr("prompt"), "prompt → 主节点.prompt");
        assertEquals(42, inputsOf(api, "15").getInt("noise_seed"), "seed → RandomNoise.noise_seed");
        assertEquals(24, inputsOf(api, "91").getInt("fps"), "fps → CreateVideo.fps");
        assertEquals(12, inputsOf(api, "9").getInt("steps"), "steps → BasicScheduler.steps");
    }

    @Test
    @DisplayName("只给首帧：first_frame 挂新建 LoadImage；last_frame 被清空；模板占位 LoadImage 被删")
    void onlyFirstFrame_mountsFirstFrameAndDropsPlaceholder() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(
                base().firstFrameName("f1.png").build());

        String mounted = mountNodeId(api, "first_frame");
        assertTrue(Integer.parseInt(mounted) >= 2000, "首帧应挂到动态新建的 loader 节点（2000+），实际 " + mounted);
        assertEquals("LoadImage", api.getJSONObject(mounted).getStr("class_type"));
        assertEquals("f1.png", inputsOf(api, mounted).getStr("image"));

        assertNull(inputsOf(api, MAIN).get("last_frame"), "未给尾帧时 last_frame 键不应存在");
        assertTrue(api.containsKey("117"), "117 的 image 非空，不该被当成占位节点删除 —— 上一行的断言因此证明的是「旧挂载被清空」");
        assertFalse(api.containsKey("114"), "模板占位 LoadImage(image 为空) 应被删除");
    }

    @Test
    @DisplayName("首帧 + 尾帧：两个键都存在且分别指向两个 LoadImage")
    void bothFrames_mountTwoLoadImages() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(
                base().firstFrameName("f1.png").lastFrameName("l1.png").build());

        String first = mountNodeId(api, "first_frame");
        String last = mountNodeId(api, "last_frame");
        assertFalse(first.equals(last), "首尾帧必须是两个独立的 loader 节点");
        assertEquals("f1.png", inputsOf(api, first).getStr("image"));
        assertEquals("l1.png", inputsOf(api, last).getStr("image"));
    }

    @Test
    @DisplayName("只给尾帧（合法用法）：last_frame 挂载、first_frame 被清空")
    void onlyLastFrame_isSupported() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(
                base().lastFrameName("l1.png").build());

        String mounted = mountNodeId(api, "last_frame");
        assertEquals("l1.png", inputsOf(api, mounted).getStr("image"));
        assertNull(inputsOf(api, MAIN).get("first_frame"), "未给首帧时 first_frame 键不应存在");
        assertFalse(api.containsKey("114"), "模板占位 LoadImage 仍应被删除");
    }

    @Test
    @DisplayName("duration 落在 PrimitiveFloat.value；主节点 length 仍是连线（对齐逻辑留在工作流里）")
    void duration_landsOnPrimitiveFloat_andLengthStaysConnection() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(base().duration(5.0).build());

        Float duration = inputsOf(api, "111").getFloat("value");
        assertNotNull(duration, "duration → PrimitiveFloat.value");
        assertEquals(5.0f, duration.floatValue(), 1e-6f);

        JSONArray length = inputsOf(api, MAIN).getJSONArray("length");
        assertNotNull(length, "length 必须保持为连线（由 ComfyMathExpression 做 17k+5 对齐）");
        assertEquals("107", length.getStr(0));
    }

    @Test
    @DisplayName("未给 duration 时用兜底秒数，仍不写主节点 length")
    void duration_defaultsWhenAbsent() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(base().build());

        assertNotNull(inputsOf(api, "111").getFloat("value"));
        assertEquals(5.0f, inputsOf(api, "111").getFloat("value").floatValue(), 1e-6f);
        assertInstanceOf(JSONArray.class, inputsOf(api, MAIN).get("length"));
    }

    @Test
    @DisplayName("宽高：不给则保留 ResolutionSelector 连线，给了才覆盖成字面量")
    void widthHeight_onlyOverriddenWhenSupplied() {
        JSONObject api = adapterWithoutBindingFile().buildApiJson(base().build());

        JSONArray width = inputsOf(api, MAIN).getJSONArray("width");
        JSONArray height = inputsOf(api, MAIN).getJSONArray("height");
        assertNotNull(width, "未给宽度时应保留 ResolutionSelector 连线");
        assertNotNull(height, "未给高度时应保留 ResolutionSelector 连线");
        assertEquals("115", width.getStr(0));
        assertEquals("115", height.getStr(0));

        JSONObject api2 = adapterWithoutBindingFile().buildApiJson(base().width(1280).height(720).build());
        assertEquals(1280, inputsOf(api2, MAIN).getInt("width"), "显式宽度应覆盖连线");
        assertEquals(720, inputsOf(api2, MAIN).getInt("height"), "显式高度应覆盖连线");
    }

    @Test
    @DisplayName("seed 为空 / 0 时随机出非零值，避免每次出同一段视频")
    void seed_isRandomizedWhenAbsentOrZero() {
        JSONObject zero = adapterWithoutBindingFile().buildApiJson(base().seed(0).build());
        int seed = inputsOf(zero, "15").getInt("noise_seed");
        assertTrue(seed != 0, "seed=0 应被替换为随机正整数");
    }
}
