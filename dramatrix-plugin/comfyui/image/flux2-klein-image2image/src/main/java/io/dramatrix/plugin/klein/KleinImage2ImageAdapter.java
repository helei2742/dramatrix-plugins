package io.dramatrix.plugin.klein;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2ImageInput;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Image2ImageWithLoraPlugin;

/**
 * Klein 图生图适配器（薄壳）。
 * 对应 workflow: ▶klein-图生图.json
 *
 * <p>本类只实现插件接口并转发，所有组装逻辑按职责拆到同包构建类：</p>
 * <ul>
 *   <li>{@link KleinPromptBuilder} —— 首帧 prompt 拼接：从本镜统一视觉蓝图组装 FLUX.2
 *       官方结构化 JSON（{@code scene / subjects / style / lighting / camera}）；</li>
 *   <li>{@link KleinWorkflowBuilder} —— 工作流拼接：把 {@link Image2ImageInput} 组装成可 POST
 *       到 ComfyUI 的 API dict，并为参考图动态接线 ReferenceLatent 链。</li>
 * </ul>
 *
 * <p>最终生图提示词由 {@link #buildPrompt} 产出；参考图与角色的对应关系由
 * {@link KleinWorkflowBuilder} 按索引接线，不写进 prompt 文本。</p>
 */
public class KleinImage2ImageAdapter extends AbstractComfyUIPlugin implements Image2ImageWithLoraPlugin {

    public KleinImage2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Image2ImageInput input) {
        return KleinWorkflowBuilder.build(this, input);
    }

    @Override
    public String buildPrompt(Image2ImagePromptRequest request) {
        return KleinPromptBuilder.build(request);
    }
}
