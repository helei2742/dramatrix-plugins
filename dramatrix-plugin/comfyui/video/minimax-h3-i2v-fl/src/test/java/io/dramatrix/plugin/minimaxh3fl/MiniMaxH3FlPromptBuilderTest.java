package io.dramatrix.plugin.minimaxh3fl;

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
 * 首尾帧插件（fl2va）prompt 的两组回归：
 * <ol>
 *   <li><b>{@code keyframes:} 对齐段</b>（K1~K6）—— 它是 fl2va 与 t2v 的<b>唯一结构性差别</b>；
 *       没有它，模型不知道「首帧 / 尾帧」是什么意思，只会把两张图当成普通参考图。这段内容的
 *       唯一数据源是 {@link VideoReference#getFirstFrame()} / {@link VideoReference#getLastFrame()}，
 *       而优化链路（{@code VideoPromptOptimizeService#buildVideoReference}）与生成链路
 *       （{@code VideoWroklerHelper#buildVideoReference}）分别负责把它们填上 ——
 *       <b>填不上时不会报错，只会静默少一段</b>，所以在这里把三种组合逐条钉死。</li>
 *   <li><b>逐拍时间戳格式</b>（T1~T4）—— 官方指南 §5.1 要求 {@code [Shot 1]} 不带时间戳、
 *       后续镜头一律 {@code [Shot N] At MM:SS.mmm, ...}（语义 = 本拍<b>开始</b>时刻，非时间区间），
 *       且第 5 行写明该格式对 FL2VA 同样适用。详见第 3 节上方注释。</li>
 * </ol>
 *
 * <p>三种关键帧组合都是合法用法：{@code firstFrame} 单独存在是 fl2va 的常规形态（只有起始定格），
 * 不能因为缺尾帧就省略整段。</p>
 */
class MiniMaxH3FlPromptBuilderTest {

    private static final String FIRST_PATH = "/media/asset/901/first.png";
    private static final String LAST_PATH = "/media/asset/902/last.png";
    private static final String STYLE = "cinematic, 35mm";

    // ─── 夹具 ───

    private static ShotVisualPlan plan() {
        return ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .duration(5.0)
                .sceneDesc("a moonlit rooftop")
                .style(STYLE)
                .beats(List.of(
                        ShotVisualPlan.Beat.builder().time("0-2.5s").desc("dust lifts off the wet stone").build(),
                        ShotVisualPlan.Beat.builder().time("2.5-5s").desc("the light goes flat").build()))
                .build();
    }

    private static VideoReference.Ref ref(String imageUrl, String desc) {
        return VideoReference.Ref.builder().imageUrl(imageUrl).desc(desc).build();
    }

    /** 只给需要的两端（null = 该端没有关键帧） */
    private static String prompt(VideoReference.Ref firstFrame, VideoReference.Ref lastFrame) {
        VideoContext ctx = VideoContext.builder()
                .visualPlan(plan())
                .reference(VideoReference.builder().firstFrame(firstFrame).lastFrame(lastFrame).build())
                .stylePrompt(STYLE)
                .build();
        VideoPromptResult result = MiniMaxH3FlPromptBuilder.build(ctx);
        assertNotNull(result, "build 永不为 null");
        assertNotNull(result.getPrompt(), "build 永不为 null");
        return result.getPrompt();
    }

    // ══════════════════════════════════════════════
    // 1. 三种组合
    // ══════════════════════════════════════════════

    @Test
    @DisplayName("K1 首尾帧都给：一段同时说明「起于首帧」与「终于尾帧」，且夹在 characters 与 description 之间")
    void bothKeyframes() {
        String p = prompt(ref(FIRST_PATH, null), ref(LAST_PATH, null));

        assertTrue(p.contains("keyframes: "), "缺少 keyframes: 段 → 模型不知道首尾帧的含义: " + p);
        assertTrue(p.contains("starts exactly on the provided first-frame image"), p);
        assertTrue(p.contains("ends exactly on the provided last-frame image"), p);
        assertTrue(p.contains("no jump, reset or morphing in between"), "双关键帧要显式禁止中间跳变: " + p);

        // 段序：scene → characters → keyframes → integrated_multimodal_description
        int keyframesAt = p.indexOf("keyframes: ");
        assertTrue(keyframesAt > 0);
        assertTrue(keyframesAt < p.indexOf("integrated_multimodal_description: "),
                "keyframes 段必须排在逐拍描述之前: " + p);
    }

    @Test
    @DisplayName("K2 只给首帧：仍然输出 keyframes 段，且不提尾帧（fl2va 的合法用法）")
    void firstFrameOnly() {
        String p = prompt(ref(FIRST_PATH, null), null);

        assertTrue(p.contains("keyframes: "), "只给首帧不能省略整段: " + p);
        assertTrue(p.contains("starts exactly on the provided first-frame image"), p);
        assertFalse(p.contains("last-frame image"), "没有尾帧就不该提尾帧: " + p);
    }

    @Test
    @DisplayName("K3 只给尾帧：仍然输出 keyframes 段，且不提首帧")
    void lastFrameOnly() {
        String p = prompt(null, ref(LAST_PATH, null));

        assertTrue(p.contains("keyframes: "), p);
        assertTrue(p.contains("ends exactly on the provided last-frame image"), p);
        assertFalse(p.contains("first-frame image"), p);
    }

    @Test
    @DisplayName("K4 回归保护：两端都没有时不出 keyframes 段（非首尾帧模式 prompt 与改造前一致）")
    void noKeyframes() {
        String p = prompt(null, null);

        assertFalse(p.contains("keyframes: "), "无关键帧时不该凭空出现对齐段: " + p);
        assertTrue(p.contains("integrated_multimodal_description: "), "逐拍描述照常输出");
    }

    // ══════════════════════════════════════════════
    // 2. 描述与「有图」判据
    // ══════════════════════════════════════════════

    @Test
    @DisplayName("K5 desc 非空时补「, as described: …」并去掉句尾句点")
    void descIsAppendedAsCaveat() {
        String p = prompt(ref(FIRST_PATH, "a young woman in a red dress."), ref(LAST_PATH, null));

        assertTrue(p.contains(", as described: a young woman in a red dress"), p);
        assertFalse(p.contains("red dress.."), "句尾句点必须剥掉，否则与后续句子连成双点: " + p);
    }

    @Test
    @DisplayName("K6 imageUrl 为空白 = 没有该帧（不靠 desc 判断存在性）")
    void blankImageUrlMeansAbsent() {
        String p = prompt(ref("  ", "a young woman in a red dress"), null);

        assertFalse(p.contains("keyframes: "), "只有 desc 没有图不算关键帧: " + p);
    }

    // ══════════════════════════════════════════════
    // 3. 逐拍时间戳：官方指南 5.1 的硬格式
    // ══════════════════════════════════════════════
    //
    // 指南（doc/minimax/prompt.md §5.1）：`[Shot 1]` 不带时间戳；后续镜头一律
    // `[Shot N] At MM:SS.mmm, ...`，语义是**本拍的开始（切换）时刻**，不是时间区间。
    // 第 5 行又写明这套基础格式对 FL2VA 同样适用。
    //
    // 为什么值得单钉一段：同族的**全能参考**插件（MiniMaxH3PromptBuilder:450）走的是
    // `sb.append("At ").append(seg.time)` —— 直接把蓝图里的原始区间 `1.5-3s` 打进 prompt，
    // **偏离指南且没有任何测试覆盖**。两个插件形态不同，很容易被「对齐成一样」而把这里改错，
    // 所以把 fl 的正确形态钉死。

    @Test
    @DisplayName("T1 首拍不带时间戳，第二拍起为 At MM:SS.mmm（指南 5.1）")
    void firstShotHasNoTimestamp() {
        String p = prompt(null, null);

        assertFalse(p.contains("[Shot 1] At "), "首拍不得带时间戳（指南 5.1）: " + p);
        assertTrue(p.contains("[Shot 2] At 00:02.500, the camera cuts to the light goes flat."),
                "第二拍必须是「本拍起点」的 MM:SS.mmm: " + p);
    }

    @Test
    @DisplayName("T2 不得回退成原始时间区间（那是 ref2va 的形态，非指南要求）")
    void timestampIsStartPointNotRawRange() {
        String p = prompt(null, null);

        assertFalse(p.contains("At 0-2.5s"), "蓝图区间不得原样进 prompt: " + p);
        assertFalse(p.contains("At 2.5-5s"), "蓝图区间不得原样进 prompt: " + p);
        assertTrue(p.contains("At 00:02.500,"), p);
    }

    @Test
    @DisplayName("T3 多拍时间严格递增，且跨分钟进位正确（60s → 01:00.000）")
    void timestampsIncreaseAndRollOverMinutes() {
        String p = promptWithBeats(List.of(
                beat("0-20s", "dust lifts off the wet stone"),
                beat("20-40s", "the puddle ripples outward"),
                beat("40-60s", "the shadows shift across the stone"),
                beat("60-61.5s", "the light goes flat")));

        assertTrue(p.contains("[Shot 2] At 00:20.000,"), p);
        assertTrue(p.contains("[Shot 3] At 00:40.000,"), p);
        assertTrue(p.contains("[Shot 4] At 01:00.000,"), "60 秒必须进位成 01:00.000: " + p);
    }

    @Test
    @DisplayName("T4 time 解析不出时省略 At 子句，但切换动词与描述照常输出")
    void unparsableTimeOmitsClause() {
        String p = promptWithBeats(List.of(
                beat("0-1s", "dust lifts off the wet stone"),
                beat("oops", "the light goes flat")));

        assertFalse(p.contains("[Shot 2] At "), "解析不出时间就不写 At 子句: " + p);
        assertTrue(p.contains("[Shot 2] the camera cuts to the light goes flat."), p);
    }

    // ─── 时间戳夹具 ───

    private static ShotVisualPlan.Beat beat(String time, String desc) {
        return ShotVisualPlan.Beat.builder().time(time).desc(desc).build();
    }

    /** 用指定 beats 拼一次 prompt（不给关键帧，只测逐拍时间戳） */
    private static String promptWithBeats(List<ShotVisualPlan.Beat> beats) {
        VideoContext ctx = VideoContext.builder()
                .visualPlan(ShotVisualPlan.builder()
                        .shotId(1L)
                        .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                        .duration(61.5)
                        .sceneDesc("a moonlit rooftop")
                        .style(STYLE)
                        .beats(beats)
                        .build())
                .reference(VideoReference.builder().firstFrame(null).lastFrame(null).build())
                .stylePrompt(STYLE)
                .build();
        VideoPromptResult result = MiniMaxH3FlPromptBuilder.build(ctx);
        assertNotNull(result, "build 永不为 null");
        assertNotNull(result.getPrompt(), "build 永不为 null");
        return result.getPrompt();
    }
}
