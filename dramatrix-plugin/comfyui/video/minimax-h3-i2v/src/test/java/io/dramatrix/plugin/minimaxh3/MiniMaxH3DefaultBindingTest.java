package io.dramatrix.plugin.minimaxh3;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 内置默认绑定（{@code MiniMaxH3ApiJsonBuilder.DEFAULT_BINDING}）的可用性回归。
 *
 * <p>背景：该常量此前漏了最外层的 <code>{ }</code>，是个 {@code "bindings": [...]} 片段。
 * {@code ensureDefaultBinding} 只在插件目录<b>没有</b> {@code workflow.binding.json} 时才兜底，
 * 而 {@code PluginRegistry} 正常都会读该文件并 {@code setBindingJson}，所以线上一直没暴露；
 * 一旦绑定文件缺失，{@code JSONUtil.parseObj} 直接抛
 * {@code A JSONObject text must begin with '{'}，整条生成链炸掉。</p>
 *
 * <p>因此本测试<b>刻意不注入 binding</b>（不调 {@code setBindingJson}），专测那条平时走不到的
 * 兜底分支：内置绑定必须可解析，且各语义参数能落到预期节点。</p>
 */
class MiniMaxH3DefaultBindingTest {

    /** 只保留内置绑定会触碰到的落点：138=prompt、129=seed、130=fps、BasicScheduler=steps、锚点主节点、加速节点。 */
    private static final String WORKFLOW = """
            {
              "129": { "class_type": "KSamplerAdvanced", "inputs": { "noise_seed": 0 } },
              "130": { "class_type": "CreateVideo", "inputs": { "fps": 24 } },
              "138": { "class_type": "PrimitiveStringMultiline", "inputs": { "value": "" } },
              "140": { "class_type": "BasicScheduler", "inputs": { "steps": 25 } },
              "141": { "class_type": "SamplerCustomAdvanced", "inputs": { "sigmas": ["140", 0] } },
              "150": { "class_type": "MiniMaxH3ReferenceToVideo", "inputs": {} },
              "13":  { "class_type": "PathchSageAttentionKJ", "inputs": { "sage_attention": "auto", "allow_compile": true } }
            }
            """;

    /** 不带 workflow.binding.json 的适配器：绑定为 null，构建时走内置默认绑定。 */
    private static MiniMaxH3Adapter adapterWithoutBindingFile() {
        PluginMetadata metadata = new PluginMetadata();
        metadata.setName("minimax-h3-i2v");
        MiniMaxH3Adapter adapter = new MiniMaxH3Adapter(metadata);
        adapter.setWorkflowJson(WORKFLOW);
        return adapter;
    }

    private static JSONObject inputsOf(JSONObject api, String nodeId) {
        return api.getJSONObject(nodeId).getJSONObject("inputs");
    }

    /** 内置默认绑定可解析，且 prompt / seed / fps / steps 落到预期节点。 */
    @Test
    void defaultBinding_appliesParametersWhenBindingFileMissing() {
        Image2VideoRefInput input = Image2VideoRefInput.builder()
                .prompts(List.of("hello"))
                .steps(12)
                .seed(42)
                .fps(24)
                .build();

        JSONObject api = adapterWithoutBindingFile().buildApiJson(input);

        assertEquals("hello", inputsOf(api, "138").getStr("value"), "prompt → 节点 138.value");
        assertEquals(42, inputsOf(api, "129").getInt("noise_seed"), "seed → 节点 129.noise_seed");
        assertEquals(24, inputsOf(api, "130").getInt("fps"), "fps → 节点 130.fps");
        assertEquals(12, inputsOf(api, "140").getInt("steps"), "steps → BasicScheduler.steps");
    }

    /** 加速开关同样走内置绑定（按 class_type 扫描 PathchSageAttentionKJ）。 */
    @Test
    void defaultBinding_appliesSageAttention() {
        Image2VideoRefInput input = Image2VideoRefInput.builder()
                .prompts(List.of("hello"))
                .build();

        JSONObject api = adapterWithoutBindingFile().buildApiJson(input);

        JSONObject sage = inputsOf(api, "13");
        assertNotNull(sage.get("sage_attention"));
        assertEquals("auto", sage.getStr("sage_attention"), "默认开启 → auto");
    }
}
