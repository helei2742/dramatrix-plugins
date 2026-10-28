package io.dramatrix.plugin.minimaxh3;

import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全能参考（ref2va）prompt 里<b>逐拍角色的位置与朝向</b>（{@code characters[].position}）的渲染口径。
 *
 * <p>位置与朝向只在 {@code position} 里写一次，**原样拼在动作之前**：{@code <Subject k>, 位置与朝向, 动作/表情.}
 * —— Java 不做任何解析/词表映射，措辞由视觉规划 prompt 保证；其中的角色 id 由 {@code resolveSubjectRefs}
 * 换成 {@code <Subject k>}。无 {@code position} 的存量蓝图只渲染动作，不产生空子句。</p>
 */
class MiniMaxH3PositionPromptTest {

    @Test
    @DisplayName("Q1 position 渲染在动作之前：<Subject k>, 位置与朝向, 动作")
    void positionRenderedBeforeDesc() {
        String p = prompt(beat("0-2s", "dust lifts off the wet stone",
                ch("char_a", "at the door, left of frame in the foreground, turned toward char_b", "hand rising, jaw tight"),
                ch("char_b", "beyond the doorway, right of frame, turned toward char_a", "shoulders squared")));

        assertTrue(p.contains("<Subject 1>, at the door, left of frame in the foreground, "
                + "turned toward <Subject 2>, hand rising, jaw tight."), p);
        assertTrue(p.contains("<Subject 2>, beyond the doorway, right of frame, "
                + "turned toward <Subject 1>, shoulders squared."), p);
    }

    @Test
    @DisplayName("Q2 无 position 的存量蓝图：只渲染动作，不产生空子句")
    void blankPositionOmitsClause() {
        String p = prompt(beat("0-2s", "the candle gutters", ch("char_a", null, "shoulders squared")));

        assertTrue(p.contains("<Subject 1>, shoulders squared."), p);
    }

    // ─── 夹具 ───

    private static ShotVisualPlan.BeatCharacter ch(String id, String position, String desc) {
        return ShotVisualPlan.BeatCharacter.builder()
                .characterId(id)
                .position(position)
                .desc(desc)
                .build();
    }

    private static ShotVisualPlan.Beat beat(String time, String desc, ShotVisualPlan.BeatCharacter... chars) {
        return ShotVisualPlan.Beat.builder()
                .time(time)
                .desc(desc)
                .characters(List.of(chars))
                .props(List.of())
                .build();
    }

    /** 只给 adapter 空元数据：prompt 拼接只读 {@code metaExtra}，不需要工作流与绑定。 */
    private static MiniMaxH3Adapter adapter() {
        PluginMetadata metadata = new PluginMetadata();
        metadata.setName("minimax-h3-i2v");
        return new MiniMaxH3Adapter(metadata);
    }

    private static String prompt(ShotVisualPlan.Beat... beats) {
        VideoContext ctx = VideoContext.builder()
                .visualPlan(ShotVisualPlan.builder()
                        .shotId(1L)
                        .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                        .duration(2.0)
                        .sceneDesc("a moonlit rooftop")
                        .style("cinematic, 35mm")
                        .groupCharacterIds(List.of("char_a", "char_b"))
                        .beats(List.of(beats))
                        .build())
                .reference(VideoReference.builder()
                        .characters(List.of(
                                VideoReference.Ref.builder().id("char_a").imageUrl("/media/asset/1/a.png").build(),
                                VideoReference.Ref.builder().id("char_b").imageUrl("/media/asset/2/b.png").build()))
                        .build())
                .stylePrompt("cinematic, 35mm")
                .build();

        VideoPromptResult result = MiniMaxH3PromptBuilder.build(adapter(), ctx);
        assertNotNull(result, "有蓝图时 build 不应为 null");
        assertNotNull(result.getPrompt(), "prompt 不应为 null");
        return result.getPrompt();
    }
}
