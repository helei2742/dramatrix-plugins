package io.dramatrix.plugin.minimaxh3;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.interfaces.Image2VideoRefPlugin;

/**
 * MiniMax H3 图生视频适配器（参考图）。对应 workflow: video_minimax_h3_r2v.json。
 * <p>
 * 薄壳：仅实现插件接口并转发，所有组装逻辑按职责拆到同包类，避免全部 Java 逻辑塞在一个类里：
 * <ul>
 *   <li>{@link MiniMaxH3ApiJsonBuilder} —— 工作流拼接：把提示词 / 参考图 / LoRA / 各开关组装成
 *       可 POST 到 ComfyUI 的 API dict，并动态创建参考图/视频/音频 loader 节点接线到
 *       {@code MiniMaxH3ReferenceToVideo}；</li>
 *   <li>{@link MiniMaxH3PromptBuilder} —— prompt 拼接：把 LLM 结构化草稿组装成 MiniMax 全能参考模式
 *       六段格式 prompt，并处理对白音色 / 中段参考帧 / 参考视频等。</li>
 * </ul>
 */
public class MiniMaxH3Adapter extends AbstractComfyUIPlugin implements Image2VideoRefPlugin {

    private static final int MAX_REF_IMAGES = 9;

    public MiniMaxH3Adapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public int maxRefImages() {
        return MAX_REF_IMAGES;
    }

    @Override
    public JSONObject buildApiJson(Image2VideoRefInput input) {
        return MiniMaxH3ApiJsonBuilder.build(this, input);
    }

    @Override
    public VideoPromptResult buildPrompt(VideoContext videoContext) {
        return MiniMaxH3PromptBuilder.build(this, videoContext);
    }
}