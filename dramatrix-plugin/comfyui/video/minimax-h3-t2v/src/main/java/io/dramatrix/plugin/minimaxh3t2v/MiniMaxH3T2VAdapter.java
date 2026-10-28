package io.dramatrix.plugin.minimaxh3t2v;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2VideoContext;
import io.dramatrix.plugin.dto.Text2VideoInput;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.interfaces.Text2VideoPlugin;

/**
 * MiniMax H3 文生视频适配器。对应 workflow: {@code video_minimax_h3_t2v.json}。
 * <p>
 * 薄壳：只实现插件接口并转发，组装逻辑按职责拆到同包类，与同目录的图生视频插件
 * （{@code minimax-h3-i2v}）保持同一种代码组织方式：
 * <ul>
 *   <li>{@link MiniMaxH3T2VApiJsonBuilder} —— 工作流拼接：把提示词 / 宽高 / 帧数 / 时长 / seed /
 *       步数 / 用户 LoRA / 模型槽组装成可 POST 到 ComfyUI 的 API dict；</li>
 *   <li>{@link MiniMaxH3T2VPromptBuilder} —— prompt 拼接：把「文生视频通用结构」组装成
 *       MiniMax H3 普通模式（T2VA）三段格式 prompt。</li>
 * </ul>
 * <p>
 * 与图生视频的差别只有「输入面」：文生视频没有参考图 / 参考视频 / 参考音频要挂载，
 * 因此没有锚点挂载与参考槽位编号那一套。
 */
public class MiniMaxH3T2VAdapter extends AbstractComfyUIPlugin implements Text2VideoPlugin {

    public MiniMaxH3T2VAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2VideoInput input) {
        return MiniMaxH3T2VApiJsonBuilder.build(this, input);
    }

    /**
     * 接管 prompt 构建：把模型无关的文生视频通用结构拼成 H3 普通模式（T2VA）格式。
     * 结构为空时返回 {@code null}，由调用方走 legacy 拼接。
     */
    @Override
    public VideoPromptResult buildPrompt(Text2VideoContext videoContext) {
        return MiniMaxH3T2VPromptBuilder.build(this, videoContext);
    }
}
