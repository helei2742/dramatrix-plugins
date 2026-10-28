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
 * 成稿风格取值（{@code resolveStyle}）的优先级与措辞口径。
 *
 * <p>链路：规划层把「本片视觉风格」写进蓝图顶层 {@code model_style}（B 块，随内容走）→ 成稿侧
 * 优先用它替换作品级 {@code stylePrompt}。为什么必须优先：作品级 {@code stylePrompt} 是静态配置，
 * 对具体内容一无所知，而「讲解视频要干净克制 / 剧情片要电影感」只有看过内容才判得对。</p>
 *
 * <p>另一条：模型产出的风格**原样输出**（不加 {@code a cinematic,} 前缀）。历史行为是给配置来的
 * 风格补前缀（保证存量作品观感不变），但模型风格本身就是完整判断，再补「电影感」会把
 * 「干净克制的幻灯片」拧回电影质感。</p>
 */
class MiniMaxH3ModelStylePromptTest {

    private static final String MODEL_STYLE =
            "flat editorial slide design, near-black ink background, warm off-white type, fine grain";
    private static final String CONFIG_STYLE = "cinematic, 35mm";

    @Test
    @DisplayName("S1 modelStyle 优先于 plan.style / stylePrompt，且原样输出（不加 cinematic 前缀）")
    void modelStyleWinsVerbatim() {
        String p = prompt(MODEL_STYLE, CONFIG_STYLE, CONFIG_STYLE);

        assertTrue(p.contains("The target video uses " + MODEL_STYLE + "."), p);
        assertFalse(p.contains("a cinematic, flat editorial"), "模型风格不得被补上 cinematic 前缀：" + p);
    }

    @Test
    @DisplayName("S2 modelStyle 为空 → 回落 plan.style（存量观感不变）")
    void fallsBackToPlanStyle() {
        String p = prompt(null, CONFIG_STYLE, "handheld documentary");

        assertTrue(p.contains("The target video uses " + CONFIG_STYLE + "."), p);
        assertFalse(p.contains("handheld documentary"), "plan.style 存在时不该用到 stylePrompt：" + p);
    }

    @Test
    @DisplayName("S3 plan.style 不含 cinematic → 沿用历史行为补前缀")
    void configStyleKeepsCinematicPrefix() {
        String p = prompt(null, "35mm film, muted palette", null);

        assertTrue(p.contains("The target video uses a cinematic, 35mm film, muted palette."), p);
    }

    @Test
    @DisplayName("S4 modelStyle / plan.style 都空 → 回落 stylePrompt（同样补前缀）")
    void fallsBackToContextStyle() {
        String p = prompt(null, null, "handheld documentary");

        assertTrue(p.contains("The target video uses a cinematic, handheld documentary."), p);
    }

    @Test
    @DisplayName("S5 三处都空 → 内置兜底 cinematic style")
    void fallsBackToBuiltinDefault() {
        String p = prompt(null, null, null);

        assertTrue(p.contains("The target video uses cinematic style."), p);
    }

    // ─── 夹具 ───

    private static MiniMaxH3Adapter adapter() {
        PluginMetadata metadata = new PluginMetadata();
        metadata.setName("minimax-h3-i2v");
        return new MiniMaxH3Adapter(metadata);
    }

    private static String prompt(String modelStyle, String planStyle, String ctxStyle) {
        VideoContext ctx = VideoContext.builder()
                .visualPlan(ShotVisualPlan.builder()
                        .shotId(1L)
                        .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                        .duration(2.0)
                        .sceneDesc("a moonlit rooftop")
                        .modelStyle(modelStyle)
                        .style(planStyle)
                        .groupCharacterIds(List.of("char_a"))
                        .beats(List.of(ShotVisualPlan.Beat.builder()
                                .time("0-2s")
                                .desc("dust lifts off the wet stone")
                                .characters(List.of())
                                .props(List.of())
                                .build()))
                        .build())
                .reference(VideoReference.builder()
                        .characters(List.of(VideoReference.Ref.builder()
                                .id("char_a").imageUrl("/media/asset/1/a.png").build()))
                        .build())
                .stylePrompt(ctxStyle)
                .build();

        VideoPromptResult result = MiniMaxH3PromptBuilder.build(adapter(), ctx);
        assertNotNull(result, "有蓝图时 build 不应为 null");
        assertNotNull(result.getPrompt(), "prompt 不应为 null");
        return result.getPrompt();
    }
}
