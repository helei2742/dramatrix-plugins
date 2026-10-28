package io.dramatrix.plugin.minimaxh3t2v;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.dramatrix.plugin.dto.Text2VideoContext;
import io.dramatrix.plugin.dto.Text2VideoSpec;
import io.dramatrix.plugin.dto.VideoPromptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMax H3 文生视频的 <b>prompt 拼接类</b>。
 *
 * <p>职责单一：把「文生视频通用结构」{@link Text2VideoSpec} 拼成 MiniMax H3
 * <b>普通模式（T2VA）</b>的 prompt 格式。与 {@link MiniMaxH3T2VApiJsonBuilder}（工作流拼接）
 * 分离，与同族图生视频插件的 {@code MiniMaxH3PromptBuilder} 保持同一种代码组织方式。</p>
 *
 * <h3>为什么 T2VA 只有三段核心字段</h3>
 * <p>MiniMax H3 的 prompt 按任务类型分两种格式：</p>
 * <ul>
 *   <li><b>普通模式（T2VA / I2VA / FL2VA / L2VA）</b>：三个核心字段
 *       {@code integrated_multimodal_description} / {@code overall_soundscape} /
 *       {@code non_diegetic_music}。T2VA 没有参考图像，因此<b>没有图像对齐指令</b>，直接以三个核心字段开头。</li>
 *   <li><b>全能参考模式（图片/视频/音频参考）</b>：六段
 *       {@code subject_definitions} / {@code summary} / {@code retention_analysis} /
 *       {@code detailed_description} / {@code overall_soundscape} / {@code non_diegetic_music}
 *       —— 由图生视频插件 {@code MiniMaxH3PromptBuilder} 负责，本类不涉及。</li>
 * </ul>
 *
 * <h3>三段之外的三段附加段（本插件自定）</h3>
 * <p>通用结构里的 {@code scene}（画面总述）、{@code characters}（人物描述）与 {@code constraints}（约束）
 * 没有对应的 H3 官方字段，但它们是「整段不变的那一层」「人物外观的一次性定义」与「一致性 / 缺陷黑名单」，
 * 写进 prompt 对生成质量有实质收益。本类把它们各自包成一段<b>英文标签段</b>，与官方三段的 {@code xxx: } 形态保持一致，
 * 顺序为：{@code scene} → {@code characters} → {@code integrated_multimodal_description}
 * → {@code overall_soundscape} → {@code non_diegetic_music} → {@code constraints}。</p>
 * <p>附加段都<b>为空时整段省略</b>，输出与「只有官方三段」的老形态逐字一致（向后兼容）。</p>
 *
 * <h3>T2VA 的格式要点（本类逐条落实）</h3>
 * <ol>
 *   <li>首镜写作 {@code [Shot 1]}，<b>不带时间戳</b>，紧跟整体风格（{@code Live-action, cinematic, …}）；</li>
 *   <li>后续镜写作 {@code [Shot N] At MM:SS.mmm, the camera cuts to …}，
 *       时间严格递增、落在视频时长内；切换方式按 {@code transition} 选动词
 *       （cut / cross-dissolve / fade / wipe）；</li>
 *   <li>运镜写成镜头内的自然英文动作（{@code The camera pushes in with small amplitude at slow speed …}），
 *       <b>不</b>在句末堆标签；</li>
 *   <li>说话人用稳定 ID {@code (S1)}，身份短语与说话方式放在 {@code <d>} 之外，
 *       {@code <d>} 内只放语言标签与逐字原文；画外音用 {@code in an off-screen voiceover}；</li>
 *   <li>{@code overall_soundscape} 只写环境声 / 肢体动作声 / 非语言人声，
 *       {@code non_diegetic_music} 只写观众能听到的配乐；对白不在这两处重复。</li>
 * </ol>
 *
 * @see Text2VideoSpec
 */
final class MiniMaxH3T2VPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxH3T2VPromptBuilder.class);

    /** 无风格依据时的兜底风格（H3 的常见取值，写在 [Shot 1] 开头）。 */
    private static final String DEFAULT_STYLE = "Live-action, cinematic";

    /** {@code soundscape} 缺失时的兜底环境声（与图生视频插件同一句）。 */
    private static final String DEFAULT_SOUNDSCAPE =
            "Very soft ambient atmosphere at a low, background volume throughout the video.";

    /** {@code music} 缺失 / 明确无配乐时的占位（指南规定无音乐写 N/A）。 */
    private static final String NO_MUSIC = "N/A";

    /**
     * 画面总述段的英文标签（含冒号与空格）。
     * <p>H3 官方三段之外本插件自定的两段之一：整段不变的那一层（机位 / 构图 / 布景 / 主体站位）。</p>
     */
    private static final String SCENE_LABEL = "scene: ";

    /** 约束段的英文标签（含冒号与空格）：一致性要求 + 缺陷黑名单，放在官方三段之后。 */
    private static final String CONSTRAINTS_LABEL = "constraints: ";

    /** 人物描述段的英文标签（含冒号与空格）：逐个出场角色的外观（一次性定义）。 */
    private static final String CHARACTERS_LABEL = "characters: ";

    private MiniMaxH3T2VPromptBuilder() {
    }

    /**
     * 从文生视频通用结构拼 MiniMax H3 普通模式（T2VA）prompt。
     *
     * @return 拼好的 prompt；结构为空（无镜头）时返回 {@code null}，调用方走 legacy 拼接
     */
    static VideoPromptResult build(MiniMaxH3T2VAdapter adapter, Text2VideoContext videoContext) {
        Text2VideoSpec spec = videoContext == null ? null : videoContext.getSpec();
        if (spec == null || CollUtil.isEmpty(spec.getShots())) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // 【画面】画面总述：整段始终不变的那一层（机位与景别 / 构图 / 布景与背景 / 主体站位）。
        // 独立成段放在最前；为空则整段省略 —— 老结构（只有 shots）的输出与改造前逐字一致。
        String scene = StrUtil.trim(spec.getScene());
        if (StrUtil.isNotBlank(scene)) {
            sb.append(SCENE_LABEL).append(sentence(scene)).append("\n\n");
        }

        // 【人物】人物描述：逐个出场角色的外观（一次性定义），顺序 = 出场顺序；空列表整段省略。
        // 一个角色一行（标签只在首行）—— 多角色挤在一行时模型容易把两个人的特征混成一个人。
        if (CollUtil.isNotEmpty(spec.getCharacters())) {
            List<String> described = new ArrayList<>();
            for (String character : spec.getCharacters()) {
                String text = StrUtil.trim(character);
                if (StrUtil.isNotBlank(text)) {
                    described.add(sentence(text));
                }
            }
            if (!described.isEmpty()) {
                sb.append(CHARACTERS_LABEL).append(String.join("\n", described)).append("\n\n");
            }
        }

        sb.append("integrated_multimodal_description: ");

        List<Text2VideoSpec.Shot> shots = spec.getShots();
        int written = 0;
        for (int i = 0; i < shots.size(); i++) {
            Text2VideoSpec.Shot shot = shots.get(i);
            if (shot == null || StrUtil.isBlank(shot.getDescription())) {
                continue;
            }
            // 镜号按「实际写出的镜」递增：中间被跳过的空镜不能让 [Shot N] 出现断号
            int shotNo = ++written;
            if (shotNo > 1) {
                sb.append('\n');
            }
            sb.append("[Shot ").append(shotNo).append("] ");

            if (shotNo == 1) {
                // 首镜不带时间戳，风格紧跟 [Shot 1]（指南 4.1：在 [Shot 1] 开头说明整体风格和初始构图）
                sb.append(resolveStyle(spec, videoContext)).append(", ");
            } else {
                String at = formatTime(shot.getAt());
                if (at != null) {
                    sb.append("At ").append(at).append(", ");
                }
                sb.append(transitionPhrase(shot.getTransition())).append(' ');
            }

            sb.append(sentence(shot.getDescription()));
            String camera = StrUtil.trim(shot.getCamera());
            if (StrUtil.isNotBlank(camera)) {
                sb.append(' ').append(sentence("The camera " + stripTrailingPeriod(camera)));
            }
            appendDialogue(sb, shot);
        }

        if (written == 0) {
            log.warn("[MINIMAX_H3_T2V] 文生视频结构无可用镜头（description 全空），交由调用方走 legacy 拼接");
            return null;
        }

        sb.append("\n\noverall_soundscape: ")
                .append(StrUtil.blankToDefault(StrUtil.trim(spec.getSoundscape()), DEFAULT_SOUNDSCAPE))
                .append("\n\nnon_diegetic_music: ")
                .append(StrUtil.blankToDefault(StrUtil.trim(spec.getMusic()), NO_MUSIC));

        // 【约束】一致性要求 + 缺陷黑名单，独立成段放在最后（官方三段之后）；为空则整段省略。
        String constraints = StrUtil.trim(spec.getConstraints());
        if (StrUtil.isNotBlank(constraints)) {
            sb.append("\n\n").append(CONSTRAINTS_LABEL).append(sentence(constraints));
        }

        String prompt = sb.toString().trim();
        if (StrUtil.isBlank(prompt)) {
            return null;
        }
        return VideoPromptResult.builder().prompt(prompt).build();
    }

    /**
     * 风格取值：{@code spec.style}（内容自身风格，由 LLM 从用户意图推理）优先，
     * 其次作品级 {@code stylePrompt}，最后内置兜底。
     */
    private static String resolveStyle(Text2VideoSpec spec, Text2VideoContext ctx) {
        String style = StrUtil.trim(spec.getStyle());
        if (StrUtil.isNotBlank(style)) {
            return stripTrailingPeriod(style);
        }
        String global = ctx == null ? null : StrUtil.trim(ctx.getStylePrompt());
        return StrUtil.isBlank(global) ? DEFAULT_STYLE : stripTrailingPeriod(global);
    }

    /**
     * 把本镜台词接在镜头描述之后。
     * <p>指南规定：说话人的身份短语、ID、说话方式放在 {@code <d>} 之外；{@code <d>} 内只放
     * 语言标签与用户实际提供的口语内容（逐字保留）。画外音用固定短语
     * {@code says in an off-screen voiceover}。</p>
     */
    private static void appendDialogue(StringBuilder sb, Text2VideoSpec.Shot shot) {
        if (CollUtil.isEmpty(shot.getDialogue())) {
            return;
        }
        for (Text2VideoSpec.Dialogue line : shot.getDialogue()) {
            if (line == null || StrUtil.isBlank(line.getText())) {
                continue;
            }
            String speaker = StrUtil.trim(line.getSpeaker());
            String identity = StrUtil.trim(line.getIdentity());
            String delivery = StrUtil.blankToDefault(StrUtil.trim(line.getDelivery()), "says");
            String language = StrUtil.blankToDefault(StrUtil.trim(line.getLanguage()), "English");

            sb.append(' ');
            if (StrUtil.isNotBlank(identity)) {
                sb.append(stripTrailingPeriod(identity)).append(' ');
            }
            if (StrUtil.isNotBlank(speaker)) {
                sb.append('(').append(speaker).append(") ");
            }
            sb.append(delivery);
            if (Boolean.FALSE.equals(line.getOnScreen())) {
                sb.append(" in an off-screen voiceover");
            }
            sb.append(": <d>[").append(language).append("] ").append(line.getText().trim()).append("</d>");
        }
    }

    /**
     * 切换方式 → 英文动词短语（指南 4.2 的固定表达）。
     */
    private static String transitionPhrase(String transition) {
        String t = StrUtil.trimToEmpty(transition).toLowerCase();
        return switch (t) {
            case "cross-dissolve", "cross_dissolve", "dissolve" -> "the shot cross-dissolves to";
            case "fade" -> "the shot fades to";
            case "wipe" -> "the shot wipes to";
            case "cut", "" -> "the camera cuts to";
            default -> "the shot transitions to";
        };
    }

    /**
     * 秒 → 指南要求的 {@code MM:SS.mmm}（如 5 秒 → {@code 00:05.000}）。
     *
     * @return 格式化后的时间；入参为空或为负时返回 {@code null}（调用方省略时间戳子句）
     */
    private static String formatTime(Double seconds) {
        if (seconds == null || seconds < 0) {
            return null;
        }
        double v = seconds;
        int totalSeconds = (int) Math.floor(v);
        int minutes = totalSeconds / 60;
        int secs = totalSeconds % 60;
        int millis = (int) Math.round((v - totalSeconds) * 1000);
        if (millis >= 1000) {
            millis -= 1000;
            secs += 1;
            if (secs == 60) {
                secs = 0;
                minutes += 1;
            }
        }
        return String.format("%02d:%02d.%03d", minutes, secs, millis);
    }

    /** 去掉句尾句点（拼装时统一补，避免出现 {@code ".."}）。 */
    private static String stripTrailingPeriod(String text) {
        String s = StrUtil.trim(text);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 保证句子以句点结尾（描述句被拆开拼接时用）。 */
    private static String sentence(String text) {
        String s = StrUtil.trim(text);
        if (StrUtil.isBlank(s)) {
            return "";
        }
        return s.endsWith(".") || s.endsWith("!") || s.endsWith("?") || s.endsWith("…") ? s : s + ".";
    }
}
