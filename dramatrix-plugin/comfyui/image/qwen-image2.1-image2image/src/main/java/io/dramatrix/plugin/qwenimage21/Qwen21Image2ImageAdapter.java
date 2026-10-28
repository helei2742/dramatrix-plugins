package io.dramatrix.plugin.qwenimage21;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2ImageInput;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Image2ImageWithLoraPlugin;

/**
 * Qwen Image 2.1 图生图 / 图像编辑适配器。
 *
 * <p>薄壳：仅实现插件接口并转发，所有组装逻辑按职责拆到同包类，避免全部 Java 逻辑塞在一个类里。
 * 拆分为两类（槽位真源 {@link ReferenceSlotPlanner} 由两侧共享）：</p>
 * <ul>
 *   <li>{@link Qwen21WorkflowBuilder} —— 工作流拼接：把 {@link Image2ImageInput} 组装成可 POST 到
 *       ComfyUI 的 API dict，并用 {@code ReferenceSlotPlanner} 为参考图动态接线到
 *       {@code TextEncodeQwenImage21.images.image_1..N}；</li>
 *   <li>{@link Qwen21PromptBuilder} —— 首帧 prompt 拼接：从 LLM 结构化草稿组装两段式中文编辑指令。
 *       槽位与工作流接线严格同序，共用 {@code ReferenceSlotPlanner}；</li>
 *   <li>{@link Qwen21StoryboardPromptBuilder} —— 多格分镜故事板 prompt 拼接：把 LLM 的
 *       {@code motions[]}/{@code frameCharacterIds} 铺成宫格成品（见 {@link #buildStoryboardPrompt}）。</li>
 * </ul>
 */
public class Qwen21Image2ImageAdapter extends AbstractComfyUIPlugin implements Image2ImageWithLoraPlugin {

    public Qwen21Image2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    /** 临时角色（temp_char_*）有形象图时也纳入生图参考图。 */
    @Override
    public boolean includeTempCharactersInReference() {
        return true;
    }

    @Override
    public JSONObject buildApiJson(Image2ImageInput input) {
        return Qwen21WorkflowBuilder.build(this, input);
    }

    @Override
    public String buildPrompt(Image2ImagePromptRequest request) {
        return Qwen21PromptBuilder.build(request, includeTempCharactersInReference());
    }

    @Override
    public String buildStoryboardPrompt(Image2ImagePromptRequest request) {
        return Qwen21StoryboardPromptBuilder.build(request, includeTempCharactersInReference());
    }
}