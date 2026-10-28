package io.dramatrix.plugin.minimaxh3fl;

import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.interfaces.Image2VideoRefPlugin;

/**
 * MiniMax H3 图生视频适配器（首尾帧 / fl2va）。
 *
 * <p>薄壳：只实现插件接口并转发，组装逻辑按职责拆到同包类，与同族插件
 * （{@code minimax-h3-i2v} ref2va / {@code minimax-h3-t2v} 纯文生视频）保持同一种代码组织方式：
 * <ul>
 *   <li>{@link MiniMaxH3FlApiJsonBuilder} —— 工作流拼接：把提示词 / 时长 / 宽高 / seed / 步数 /
 *       用户 LoRA / 模型槽 / <b>首帧与尾帧</b>组装成可 POST 到 ComfyUI 的 API dict；</li>
 *   <li>{@link MiniMaxH3FlPromptBuilder} —— prompt 拼接：把视觉蓝图组装成 MiniMax H3
 *       <b>普通模式（FL2VA）</b>格式 prompt，并补上首尾帧的图像对齐指令。</li>
 * </ul>
 *
 * <h3>与 ref2va（{@code minimax-h3-i2v}）的区别</h3>
 * <p>ref2va 用 {@code MiniMaxH3ReferenceToVideo} 主节点 + {@code ref_images.ref_image_{i}}
 * 多图<b>参考</b>（进条件、不约束像素位置，prompt 里用 {@code <Picture k>} 编号锚定）；
 * 本插件用 {@code MiniMaxH3ImageToVideo} 主节点 + {@code first_frame} / {@code last_frame}
 * 两个<b>关键帧</b>输入（经 VAE 编码直接约束画面首末像素）。两者语义、挂载点、prompt 契约都不同，
 * 因此是两份工作流、两个插件，共用同一份 fl2va 权重。
 *
 * <h3>路由姿态：{@code routable = false}</h3>
 * <p>本插件与 ref2va 共用 {@code type = "image2video_ref"}，候选全集由「实现了哪个插件接口」决定
 * （{@code PluginType} 从不参与候选过滤），因此隔离手段是 {@code adapter.json} 里的
 * {@code "routable": false}：<b>不参与自动择优</b>（L5 类别/模态、L6 order-max 全部跳过），
 * 但<b>仍可被显式点名</b>（请求级 {@code requestedWorkflow} / 小说级 {@code WorkflowConfig.workflow}）。
 *
 * <p><b>不要覆写 {@link #maxRefImages()}</b>：本插件不挂 {@code ref_images} 组，参考图上限对它没有意义；
 * 而调用方的 {@code trimRefImagesToPluginLimit} 丢弃顺序是「中段参考帧 → 首帧 → 道具 → 角色」——
 * 上限设小了首帧会第一个被丢掉，首尾帧静默失效。
 */
public class MiniMaxH3FlAdapter extends AbstractComfyUIPlugin implements Image2VideoRefPlugin {

    public MiniMaxH3FlAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Image2VideoRefInput input) {
        return MiniMaxH3FlApiJsonBuilder.build(this, input);
    }

    @Override
    public VideoPromptResult buildPrompt(VideoContext videoContext) {
        return MiniMaxH3FlPromptBuilder.build(videoContext);
    }
}
