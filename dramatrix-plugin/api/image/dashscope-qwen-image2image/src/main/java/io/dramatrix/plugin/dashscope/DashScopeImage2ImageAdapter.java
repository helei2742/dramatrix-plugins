package io.dramatrix.plugin.dashscope;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2ImageInput;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Image2ImageWithLoraPlugin;

import java.util.Map;

/**
 * DashScope Qwen-Image 图生图适配器（{@code kind=api}，{@code runtime=api}）—— 薄壳。
 *
 * <h3>职责边界</h3>
 * <p><b>只做转发</b>：本类由插件 JAR 经独立 {@code URLClassLoader} 实例化，自身不发 HTTP、
 * 不持凭据、不读文件。鉴权、并发、超时与结果下载全部由宿主侧的 {@code DashScopeMediaClient} 处理。</p>
 *
 * <p>组装逻辑按职责拆到同包构建类：</p>
 * <ul>
 *   <li>{@link DashScopePromptBuilder} —— 提示词拼接：把本镜统一视觉蓝图拼成一句「图1 / 图2」
 *       指代的自然语言编辑指令，编号与请求体 content 次序严格一致；</li>
 *   <li>{@link DashScopeApiJsonBuilder} —— 请求体拼接：产出 OpenAI 兼容 messages 形态的
 *       {@code {model, input:{messages}, parameters:{...}}}，含参考图占位符、size 约束与参数取值。</li>
 * </ul>
 *
 * @author helei
 */
public class DashScopeImage2ImageAdapter extends AbstractComfyUIPlugin implements Image2ImageWithLoraPlugin {

    public DashScopeImage2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public String buildPrompt(Image2ImagePromptRequest request) {
        return DashScopePromptBuilder.build(request);
    }

    @Override
    public JSONObject buildApiJson(Image2ImageInput input) {
        return DashScopeApiJsonBuilder.build(input, defaultOptions());
    }

    /** adapter.json 的 {@code extra.defaults}：调用方 options 未给时的兜底值。 */
    private Map<String, Object> defaultOptions() {
        Map<String, Object> defaults = getMetaExtra("defaults");
        return defaults == null ? Map.of() : defaults;
    }
}
