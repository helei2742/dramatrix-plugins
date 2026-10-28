package io.dramatrix.plugin.minimaxh3;

import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全能参考（ref2va）prompt 的<b>时间口径</b>回归。
 *
 * <p>背景：本插件此前走 {@code sb.append("At ").append(seg.time)}，把蓝图里的<b>原始区间</b>
 * （{@code "1.5-3s"}）直接打进 prompt，即 {@code [Shot 2] At 1.5-3s, ...}。而官方指南
 * {@code doc/minimax/prompt.md} §5.1 要求的是 {@code [Shot N] At MM:SS.mmm, ...}，语义为
 * <b>本拍的开始（切换）时刻</b>，不是区间。首尾帧插件（{@code MiniMaxH3FlPromptBuilder}）一直
 * 是合规的 {@code formatTime(beatStartSeconds(...))}，两个同族插件因此长期存在两套时间口径 ——
 * 且本模块对时间文本<b>零测试覆盖</b>（既有测试只验绑定落点），所以在这里逐条钉死。</p>
 *
 * <p>同类回归见首尾帧插件的 {@code MiniMaxH3FlPromptBuilderTest} 第 3 节（T1~T4）。两处断言
 * 的期望文本刻意保持一致，任何一侧被改回原始区间都会立刻失败。</p>
 */
class MiniMaxH3PromptTimeFormatTest {

    // ══════════════════════════════════════════════
    // 1. 逐拍时间戳：官方指南 5.1 的硬格式
    // ══════════════════════════════════════════════

    @Test
    @DisplayName("P1 首拍不带时间戳，第二拍起为 At MM:SS.mmm（指南 5.1）")
    void firstShotHasNoTimestamp() {
        String p = prompt(List.of(
                beat("0-1.5s", "dust lifts off the wet stone"),
                beat("1.5-3s", "the light goes flat")), null);

        assertFalse(p.contains("[Shot 1] At "), "首拍不得带时间戳（指南 5.1）: " + p);
        assertTrue(p.contains("[Shot 2] At 00:01.500,"),
                "第二拍必须是「本拍起点」的 MM:SS.mmm: " + p);
    }

    @Test
    @DisplayName("P2 蓝图原始区间不得原样进 prompt（这是本次修掉的形态）")
    void rawRangeNeverLeaksIntoPrompt() {
        String p = prompt(List.of(
                beat("0-1.5s", "dust lifts off the wet stone"),
                beat("1.5-3s", "the light goes flat"),
                beat("3-4.5s", "the shadows shift across the stone"),
                beat("4.5-6s", "the light goes flat and the square settles into stillness")), null);

        assertFalse(p.contains("At 0-1.5s"), "蓝图区间不得原样进 prompt: " + p);
        assertFalse(p.contains("At 1.5-3s"), "蓝图区间不得原样进 prompt: " + p);
        assertFalse(p.contains("At 3-4.5s"), "蓝图区间不得原样进 prompt: " + p);
        assertFalse(p.contains("At 4.5-6s"), "蓝图区间不得原样进 prompt: " + p);

        // 起点逐个对上（这正是用户报障时看到的那条时间线）
        assertTrue(p.contains("[Shot 2] At 00:01.500,"), p);
        assertTrue(p.contains("[Shot 3] At 00:03.000,"), p);
        assertTrue(p.contains("[Shot 4] At 00:04.500,"), p);
    }

    @Test
    @DisplayName("P3 多拍时间严格递增，且跨分钟进位正确（60s → 01:00.000）")
    void timestampsRollOverMinutes() {
        String p = prompt(List.of(
                beat("0-20s", "dust lifts off the wet stone"),
                beat("20-40s", "the puddle ripples outward"),
                beat("40-60s", "the shadows shift across the stone"),
                beat("60-61.5s", "the light goes flat")), null);

        assertTrue(p.contains("[Shot 2] At 00:20.000,"), p);
        assertTrue(p.contains("[Shot 3] At 00:40.000,"), p);
        assertTrue(p.contains("[Shot 4] At 01:00.000,"), "60 秒必须进位成 01:00.000: " + p);
    }

    @Test
    @DisplayName("P4 time 解析不出时省略 At 子句，但本拍描述照常输出")
    void unparsableTimeOmitsClause() {
        String p = prompt(List.of(
                beat("0-1s", "dust lifts off the wet stone"),
                beat("oops", "the light goes flat")), null);

        assertFalse(p.contains("[Shot 2] At "), "解析不出时间就不写 At 子句: " + p);
        assertTrue(p.contains("[Shot 2] the light goes flat"), p);
    }

    // ══════════════════════════════════════════════
    // 2. 中段参考帧落点：同一个全片时间轴，必须同一口径
    // ══════════════════════════════════════════════

    @Test
    @DisplayName("P5 参考帧落点（全片绝对时间点）也用 MM:SS.mmm，三处措辞一致")
    void refFrameAnchorsUseMmSsMmm() {
        String p = prompt(List.of(
                beat("0-1.5s", "dust lifts off the wet stone"),
                beat("1.5-3s", "the light goes flat")),
                List.of(VideoReference.Ref.builder()
                        .imageUrl("/media/asset/903/mid.png")
                        .insertTime(2.0)
                        .desc("the letter is folded")
                        .build()));

        // ① subject_definitions 里的 <Picture k> 说明
        assertTrue(p.contains("must visually anchor the shot at 00:02.000"),
                "参考帧落点必须用 MM:SS.mmm: " + p);
        // ② summary 里的 <Subject k> 说明
        assertTrue(p.contains("(reference frame at 00:02.000)"),
                "summary 里的落点必须同口径: " + p);
        // ③ 逐拍描述里的到达锚点帧子句
        assertTrue(p.contains("At 00:02.000, the visual reaches the anchor frame"),
                "锚点帧子句必须同口径: " + p);

        // 不得残留「秒 + s」的时长形态（insertTimeLine 是绝对时间点，不是时长）
        assertFalse(p.contains("at 2s"), "不得退回「秒 + s」形态: " + p);
    }

    // ─── 夹具 ───

    private static ShotVisualPlan.Beat beat(String time, String desc) {
        return ShotVisualPlan.Beat.builder().time(time).desc(desc).build();
    }

    /** 只给 adapter 空元数据：prompt 拼接只读 {@code metaExtra}，不需要工作流与绑定。 */
    private static MiniMaxH3Adapter adapter() {
        PluginMetadata metadata = new PluginMetadata();
        metadata.setName("minimax-h3-i2v");
        return new MiniMaxH3Adapter(metadata);
    }

    private static String prompt(List<ShotVisualPlan.Beat> beats, List<VideoReference.Ref> midFrames) {
        VideoContext ctx = VideoContext.builder()
                .visualPlan(ShotVisualPlan.builder()
                        .shotId(1L)
                        .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                        .duration(6.0)
                        .sceneDesc("a moonlit rooftop")
                        .style("cinematic, 35mm")
                        .beats(beats)
                        .build())
                .reference(VideoReference.builder().midFrames(midFrames).build())
                .stylePrompt("cinematic, 35mm")
                .build();

        VideoPromptResult result = MiniMaxH3PromptBuilder.build(adapter(), ctx);
        assertNotNull(result, "有蓝图时 build 不应为 null");
        assertNotNull(result.getPrompt(), "prompt 不应为 null");
        return result.getPrompt();
    }
}
