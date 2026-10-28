package io.dramatrix.plugin.minimaxh3fl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiniMax H3 首尾帧图生视频（fl2va）的 <b>prompt 拼接类</b>。
 *
 * <p>职责单一：把「本镜视觉蓝图 + 分类参考」拼成 MiniMax H3 <b>普通模式（FL2VA）</b>的 prompt 格式，
 * 与 {@link MiniMaxH3FlApiJsonBuilder}（工作流拼接）分离，与同族插件保持同一种代码组织方式。</p>
 *
 * <h3>为什么是「普通模式」而不是全能参考模式</h3>
 * <p>MiniMax H3 的 prompt 按任务类型分两种格式：</p>
 * <ul>
 *   <li><b>普通模式（T2VA / I2VA / FL2VA / L2VA）</b>：三个核心字段
 *       {@code integrated_multimodal_description} / {@code overall_soundscape} /
 *       {@code non_diegetic_music}；<b>没有参考图编号</b>，但有图像对齐指令（说明首帧 / 尾帧是什么、
 *       镜头如何从首帧过渡到尾帧）。</li>
 *   <li><b>全能参考模式</b>：六段 + {@code <Picture k>} / {@code <Subject k>} 锚点 ——
 *       那是 ref2va（{@code MiniMaxH3PromptBuilder}）的契约，本类<b>不涉及</b>，也绝不输出
 *       {@code <Subject k> is the figure in <Picture k>} 这类锚点句。</li>
 * </ul>
 * <p>因此本类的输出结构与 {@code MiniMaxH3T2VPromptBuilder} 同形：{@code scene} → {@code characters}
 * → {@code keyframes} → {@code integrated_multimodal_description} → {@code overall_soundscape}
 * → {@code non_diegetic_music} → {@code constraints}，其中 {@code scene} / {@code characters} /
 * {@code keyframes} / {@code constraints} 是官方三段之外的自定段（与 t2v 插件同一种做法，
 * 为空时整段省略）。</p>
 *
 * <p><b>不能直接调用 {@code MiniMaxH3T2VPromptBuilder}</b>：入参类型不同 ——
 * {@code Image2VideoRefPlugin.buildPrompt(VideoContext)} 吃 {@link ShotVisualPlan}（逐拍蓝图），
 * {@code Text2VideoPlugin.buildPrompt(Text2VideoContext)} 吃 {@code Text2VideoSpec}。</p>
 *
 * <h3>两条硬约束</h3>
 * <ol>
 *   <li><b>绝不返回 {@code null}</b>：worker 路径 {@code VideoWroklerHelper#buildFinalPrompt} 拿到
 *       {@code null} 会直接抛 {@code NonRetryableException}（只有 prompt 优化路径才有 legacy 兜底）。
 *       蓝图缺失时降级到「场景描述 / 风格」兜底并打 WARN，而不是把任务炸掉。</li>
 *   <li><b>台词不重复权威</b>：蓝图 {@code beats[].dialogue} 只携带 script {@code id} 与呈现方式，
 *       台词原文按 {@code id} 从 {@code VideoContext.textSegments} 解析（与 ref 版同一口径）。</li>
 * </ol>
 */
final class MiniMaxH3FlPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxH3FlPromptBuilder.class);

    /** 无风格依据时的兜底风格（H3 的常见取值，写在 [Shot 1] 开头）。 */
    private static final String DEFAULT_STYLE = "Live-action, cinematic";

    /** {@code soundscape} 缺失时的兜底环境声（与同族插件同一句）。 */
    private static final String DEFAULT_SOUNDSCAPE =
            "Very soft ambient atmosphere at a low, background volume throughout the video.";

    /** 无配乐时的占位（指南规定无音乐写 N/A）。 */
    private static final String NO_MUSIC = "N/A";

    /** 画面总述段的英文标签：整段不变的那一层（机位 / 构图 / 布景 / 主体站位）。 */
    private static final String SCENE_LABEL = "scene: ";

    /** 人物描述段的英文标签：逐个出场角色的外观（一次性定义）。 */
    private static final String CHARACTERS_LABEL = "characters: ";

    /** 关键帧对齐段的英文标签：说明首帧 / 尾帧是什么、镜头如何从首帧过渡到尾帧。 */
    private static final String KEYFRAMES_LABEL = "keyframes: ";

    /** 约束段的英文标签：一致性要求 + 缺陷黑名单。 */
    private static final String CONSTRAINTS_LABEL = "constraints: ";

    /**
     * 约束段内容：一致性 + 缺陷黑名单。
     * <p>首尾帧场景下「不许在首末两端跳变」尤其重要 —— 模型有时会在第一帧之后重新构图，
     * 让首帧只闪一帧就变样。</p>
     */
    private static final String DEFAULT_CONSTRAINTS =
            "Keep every character's face, hairstyle and costume identical throughout the video, with no identity drift. "
                    + "Keep the lighting, colour grading and background steady across the whole video, with no flicker and no drifting highlights. "
                    + "Hold the composition at the very beginning and at the very end: do not re-frame, zoom or shift abruptly on the first or the last beat. "
                    + "No subtitles, captions, watermarks, logos, UI overlays or any on-screen text. "
                    + "No extra characters, no cuts to unrelated locations and no scene changes beyond what is described above.";

    private MiniMaxH3FlPromptBuilder() {
    }

    /**
     * 从本镜视觉蓝图 + 分类参考拼 MiniMax H3 普通模式（FL2VA）prompt。
     *
     * @return 拼好的 prompt（<b>永不为 {@code null}</b>，蓝图缺失时降级到风格 / 场景兜底）
     */
    static VideoPromptResult build(VideoContext videoContext) {
        ShotVisualPlan plan = videoContext == null ? null : videoContext.getVisualPlan();
        VideoReference reference = videoContext == null ? null : videoContext.getReference();
        VideoReference.Ref firstFrameRef = reference == null ? null : reference.getFirstFrame();
        VideoReference.Ref lastFrameRef = reference == null ? null : reference.getLastFrame();
        boolean hasFirstFrame = hasImage(firstFrameRef);
        boolean hasLastFrame = hasImage(lastFrameRef);

        String sceneDesc = plan == null ? null : StrUtil.trim(plan.getSceneDesc());
        String style = resolveStyle(plan, videoContext);
        List<ShotVisualPlan.Beat> beats = usableBeats(plan);

        log.info("[MINIMAX_H3_FL] build prompt: beats={}, sceneDesc={}, firstFrame={}, lastFrame={}, characters={}",
                beats.size(), StrUtil.isNotBlank(sceneDesc), hasFirstFrame, hasLastFrame,
                CollUtil.size(reference == null ? null : reference.getCharacters()));

        if (beats.isEmpty()) {
            log.warn("[MINIMAX_H3_FL] 本镜蓝图无可用 beats，prompt 降级为「场景 / 风格」兜底（不返回 null）");
        }

        StringBuilder sb = new StringBuilder();

        // 【画面】画面总述：整段始终不变的那一层。为空则整段省略。
        if (StrUtil.isNotBlank(sceneDesc)) {
            sb.append(SCENE_LABEL).append(sentence(sceneDesc)).append("\n\n");
        }

        // 【人物】人物描述：逐个出场角色的外观（一次性定义），顺序 = 参考列表顺序；空列表整段省略。
        // 一个角色一行（标签只在首行）—— 多角色挤在一行时模型容易把两个人的特征混成一个人。
        List<String> characterLines = collectCharacterLines(plan, reference);
        if (!characterLines.isEmpty()) {
            sb.append(CHARACTERS_LABEL).append(String.join("\n", characterLines)).append("\n\n");
        }

        // 【关键帧】图像对齐指令：fl2va 与 t2v 的唯一结构性差别，没有它模型不知道首帧/尾帧的含义。
        String keyframes = keyframeInstruction(hasFirstFrame, hasLastFrame, firstFrameRef, lastFrameRef);
        if (StrUtil.isNotBlank(keyframes)) {
            sb.append(KEYFRAMES_LABEL).append(keyframes).append("\n\n");
        }

        // 【官方字段一】integrated_multimodal_description：逐拍展开为 [Shot 1..N]
        sb.append("integrated_multimodal_description: ");
        sb.append(buildDescription(beats, style, videoContext));

        // 【官方字段二 / 三】音景与配乐（规划层写了就用，没写才兜底）
        sb.append("\n\noverall_soundscape: ")
                .append(StrUtil.blankToDefault(plan == null ? null : StrUtil.trim(plan.getSoundscape()),
                        generateSoundscape(sceneDesc)))
                .append("\n\nnon_diegetic_music: ")
                .append(StrUtil.blankToDefault(plan == null ? null : StrUtil.trim(plan.getMusic()), NO_MUSIC));

        // 【约束】一致性要求 + 缺陷黑名单，独立成段放在最后（官方三段之后）
        sb.append("\n\n").append(CONSTRAINTS_LABEL).append(DEFAULT_CONSTRAINTS);

        String prompt = sb.toString().trim();
        if (StrUtil.isBlank(prompt)) {
            // 理论上不可达（DEFAULT_STYLE 保证 description 非空），留一道保险：宁可给一个泛化 prompt，
            // 也不要返回 null 把 worker 的 NonRetryableException 炸出来。
            log.warn("[MINIMAX_H3_FL] prompt 拼装结果为空，降级为最小可用 prompt");
            prompt = "integrated_multimodal_description: [Shot 1] " + DEFAULT_STYLE + ", a single continuous shot.\n\n"
                    + "overall_soundscape: " + DEFAULT_SOUNDSCAPE + "\n\nnon_diegetic_music: " + NO_MUSIC;
        }
        return VideoPromptResult.builder()
                .prompt(prompt)
                // 参考对象原样透传：本插件不消费 refImageNames（不挂 ref_images 组），
                // 但调用方要用它取首帧/尾帧的路径去上传，改写它只会让两边不一致。
                .reference(reference)
                .build();
    }

    // ======================== 各段拼装 ========================

    /**
     * 逐拍展开 {@code integrated_multimodal_description}。
     * <p>首拍不带时间戳、紧跟整体风格（指南 4.1）；后续拍带 {@code At MM:SS.mmm,} 与切换动词。
     * 运镜写成镜头内的自然英文动作，不在句末堆标签。</p>
     */
    private static String buildDescription(List<ShotVisualPlan.Beat> beats, String style, VideoContext ctx) {
        Map<String, Integer> speakerIndex = new LinkedHashMap<>();
        List<Map<String, Object>> textSegments = ctx == null ? null : ctx.getTextSegments();
        boolean hasAnyDialogue = hasAnyDialogue(textSegments);

        if (beats.isEmpty()) {
            // 兜底：蓝图缺失时至少给出一段静态镜头描述，保证 prompt 非空
            StringBuilder fallback = new StringBuilder();
            fallback.append("[Shot 1] ").append(style).append(", a single continuous shot.");
            if (!hasAnyDialogue) {
                fallback.append(' ').append(SILENT_LINE);
            }
            return fallback.toString();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < beats.size(); i++) {
            ShotVisualPlan.Beat beat = beats.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("[Shot ").append(i + 1).append("] ");

            if (i == 0) {
                sb.append(style).append(", ");
            } else {
                String at = formatTime(beatStartSeconds(beat.getTime()));
                if (at != null) {
                    sb.append("At ").append(at).append(", ");
                }
                sb.append(Boolean.TRUE.equals(beat.getHardCut())
                        ? "the camera hard-cuts to a new framing "
                        : "the camera cuts to ");
            }

            sb.append(sentence(beat.getDesc()));

            // 运镜：cameraMotion（动态运镜）优先，缺省回退 camera（静态取景）
            String motion = StrUtil.blankToDefault(StrUtil.trim(beat.getCameraMotion()), StrUtil.trim(beat.getCamera()));
            if (StrUtil.isNotBlank(motion)) {
                sb.append(" The camera ").append(stripTrailingPeriod(motion)).append('.');
            }

            appendBeatPhrases(sb, beat.getCharacters(), true);
            appendBeatPhrases(sb, beat.getProps(), false);

            appendDialogue(sb, beat, textSegments, speakerIndex);
        }

        if (!hasAnyDialogue) {
            sb.append(' ').append(SILENT_LINE);
        }
        return sb.toString();
    }

    /** 本拍角色 / 道具的动作描述（与 ref 版同一做法：它们是 LLM 为本拍写的一次性动作短语）。 */
    private static void appendBeatPhrases(StringBuilder sb, List<?> items, boolean character) {
        if (CollUtil.isEmpty(items)) {
            return;
        }
        for (Object item : items) {
            String desc = null;
            if (character && item instanceof ShotVisualPlan.BeatCharacter c) {
                desc = StrUtil.trim(c.getDesc());
            } else if (!character && item instanceof ShotVisualPlan.BeatProp p) {
                desc = StrUtil.trim(p.getDesc());
            }
            if (StrUtil.isNotBlank(desc)) {
                sb.append(' ').append(sentence(desc));
            }
        }
    }

    private static final String SILENT_LINE =
            "No spoken dialogue in this shot; all characters remain silent, and only the ambient soundscape is audible.";

    /**
     * 本拍台词：{@code beat.dialogue[]} 只给 script id 与呈现方式，原文从 {@code textSegments} 按 id 解析。
     * <p>说话人用稳定 ID {@code (S1)}，身份短语与说话方式放在 {@code <d>} 之外，
     * {@code <d>} 内只放语言标签与逐字原文；画外音（{@code onScreen=false} 或 thought/voiceover 类文本）加
     * {@code in an off-screen voiceover}。</p>
     */
    private static void appendDialogue(StringBuilder sb, ShotVisualPlan.Beat beat,
                                       List<Map<String, Object>> textSegments,
                                       Map<String, Integer> speakerIndex) {
        if (CollUtil.isEmpty(beat.getDialogue()) || CollUtil.isEmpty(textSegments)) {
            return;
        }
        for (ShotVisualPlan.DialogueRef ref : beat.getDialogue()) {
            if (ref == null || StrUtil.isBlank(ref.getId())) {
                continue;
            }
            Map<String, Object> seg = findSegment(textSegments, ref.getId());
            if (seg == null) {
                continue;
            }
            String text = StrUtil.trim(strOf(seg.get("text")));
            if (StrUtil.isBlank(text)) {
                continue;
            }
            String speakerId = StrUtil.trim(strOf(seg.get("speaker")));
            Integer sx = StrUtil.isBlank(speakerId) ? null
                    : speakerIndex.computeIfAbsent(speakerId, k -> speakerIndex.size() + 1);

            sb.append(' ');
            if (StrUtil.isNotBlank(speakerId)) {
                sb.append(speakerId).append(' ');
            }
            if (sx != null) {
                sb.append('(').append('S').append(sx).append(") ");
            }
            sb.append(StrUtil.blankToDefault(StrUtil.trim(ref.getDelivery()), "says"));
            if (isOffScreen(seg, ref)) {
                sb.append(" in an off-screen voiceover");
            }
            sb.append(": <d>[").append(detectLanguage(text)).append("] ").append(text).append("</d>");
        }
    }

    /**
     * 首尾帧对齐指令。三种组合分别给不同措辞 —— 「只给首帧」是 fl2va 的合法用法，
     * 不能因为缺尾帧就省略整段。
     */
    private static String keyframeInstruction(boolean hasFirstFrame, boolean hasLastFrame,
                                             VideoReference.Ref firstFrameRef, VideoReference.Ref lastFrameRef) {
        if (!hasFirstFrame && !hasLastFrame) {
            return "";
        }
        if (hasFirstFrame && hasLastFrame) {
            return "The video starts exactly on the provided first-frame image and ends exactly on the provided "
                    + "last-frame image. Keep each frame's subject identity, framing, composition and lighting"
                    + caveat(firstFrameRef) + caveat(lastFrameRef)
                    + " Move smoothly and continuously from the first frame to the last one, with no jump, reset or morphing in between.";
        }
        if (hasFirstFrame) {
            return "The video starts exactly on the provided first-frame image. Keep its subject identity, framing, "
                    + "composition and lighting at the very beginning" + caveat(firstFrameRef)
                    + " and develop continuously and naturally from it.";
        }
        return "The video ends exactly on the provided last-frame image. Keep its subject identity, framing, "
                + "composition and lighting at the very end" + caveat(lastFrameRef)
                + " and arrive at it smoothly and continuously.";
    }

    /** 参考项自带描述时补一句「它是什么」，把用户的意图带进图像对齐指令。 */
    private static String caveat(VideoReference.Ref ref) {
        String desc = ref == null ? null : StrUtil.trim(ref.getDesc());
        return StrUtil.isBlank(desc) ? "" : ", as described: " + stripTrailingPeriod(desc);
    }

    // ======================== 取值 / 兜底 ========================

    /** 可用拍：{@code desc} 非空的 beat（空拍不占 {@code [Shot N]} 的号，避免断号）。 */
    private static List<ShotVisualPlan.Beat> usableBeats(ShotVisualPlan plan) {
        List<ShotVisualPlan.Beat> out = new ArrayList<>();
        if (plan == null || CollUtil.isEmpty(plan.getBeats())) {
            return out;
        }
        for (ShotVisualPlan.Beat beat : plan.getBeats()) {
            if (beat != null && StrUtil.isNotBlank(beat.getDesc())) {
                out.add(beat);
            }
        }
        return out;
    }

    /**
     * 风格取值：顶层 {@code model_style}（规划层产出，随内容走）优先，
     * 其次 {@code plan.style}（本镜固定风格后缀，worker 直取），
     * 再其次作品级 {@code stylePrompt}，最后内置兜底。
     */
    private static String resolveStyle(ShotVisualPlan plan, VideoContext ctx) {
        String model = plan == null ? null : StrUtil.trim(plan.getModelStyle());
        if (StrUtil.isNotBlank(model)) {
            return stripTrailingPeriod(model);
        }
        String style = plan == null ? null : StrUtil.trim(plan.getStyle());
        if (StrUtil.isNotBlank(style)) {
            return stripTrailingPeriod(style);
        }
        String global = ctx == null ? null : StrUtil.trim(ctx.getStylePrompt());
        return StrUtil.isBlank(global) ? DEFAULT_STYLE : stripTrailingPeriod(global);
    }

    /**
     * 人物描述行：优先用参考项自带的锚定描述（角色外观关键词），缺省回退蓝图里的「id → 名字」。
     * <p>fl2va 没有 {@code <Picture k>} 编号，因此这里只写外观描述本身，不带任何锚点前缀。</p>
     */
    private static List<String> collectCharacterLines(ShotVisualPlan plan, VideoReference reference) {
        List<String> lines = new ArrayList<>();
        if (reference != null && CollUtil.isNotEmpty(reference.getCharacters())) {
            for (VideoReference.Ref ref : reference.getCharacters()) {
                if (ref == null) {
                    continue;
                }
                String desc = StrUtil.trim(ref.getDesc());
                if (StrUtil.isNotBlank(desc)) {
                    lines.add(sentence(desc));
                    continue;
                }
                String name = nameOf(plan, ref.getId());
                if (StrUtil.isNotBlank(name)) {
                    lines.add(sentence(name));
                }
            }
        }
        if (lines.isEmpty() && plan != null && CollUtil.isNotEmpty(plan.getCharacterNames())) {
            for (String name : plan.getCharacterNames().values()) {
                if (StrUtil.isNotBlank(name)) {
                    lines.add(sentence(name));
                }
            }
        }
        return lines;
    }

    private static String nameOf(ShotVisualPlan plan, String characterId) {
        if (plan == null || StrUtil.isBlank(characterId) || CollUtil.isEmpty(plan.getCharacterNames())) {
            return null;
        }
        return plan.getCharacterNames().get(characterId);
    }

    private static boolean hasImage(VideoReference.Ref ref) {
        return ref != null && StrUtil.isNotBlank(ref.getImageUrl());
    }

    private static boolean hasAnyDialogue(List<Map<String, Object>> textSegments) {
        if (CollUtil.isEmpty(textSegments)) {
            return false;
        }
        for (Map<String, Object> seg : textSegments) {
            if (seg != null && StrUtil.isNotBlank(StrUtil.trim(strOf(seg.get("text"))))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> findSegment(List<Map<String, Object>> pool, String id) {
        for (Map<String, Object> seg : pool) {
            if (seg != null && id.equals(strOf(seg.get("id")))) {
                return seg;
            }
        }
        return null;
    }

    /** 画外音判定：显式 {@code onScreen=false}，或文本类型本身就是内心独白 / 旁白。 */
    private static boolean isOffScreen(Map<String, Object> seg, ShotVisualPlan.DialogueRef ref) {
        if (Boolean.FALSE.equals(ref.getOnScreen())) {
            return true;
        }
        String type = StrUtil.trimToEmpty(strOf(seg.get("type"))).toLowerCase();
        return type.contains("thought") || type.contains("voiceover") || type.contains("inner")
                || type.contains("narration") || type.contains("system");
    }

    /** 语言标签：按首个命中字符判断中 / 日 / 其它（英文）。 */
    private static String detectLanguage(String text) {
        if (StrUtil.isBlank(text)) {
            return "English";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) {
                return "Japanese";
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return "Chinese";
            }
        }
        return "English";
    }

    /** 按场景描述给一句兜底音景（与同族插件同一套关键词表）。 */
    private static String generateSoundscape(String sceneDesc) {
        if (StrUtil.isBlank(sceneDesc)) {
            return DEFAULT_SOUNDSCAPE;
        }
        String lower = sceneDesc.toLowerCase();
        if (lower.contains("rain")) {
            return "Light, gentle rain at a quiet, low volume in the background, with only faint distant thunder and sparse water drops.";
        }
        if (lower.contains("street") || lower.contains("alley") || lower.contains("road") || lower.contains("city")) {
            return "Muffled city traffic in the far background at a very low volume, kept subtle so speech remains clean and clear.";
        }
        if (lower.contains("forest") || lower.contains("woods") || lower.contains("garden") || lower.contains("tree")) {
            return "Faint leaves rustling in a light breeze with sparse, distant birdsong, kept quiet and low in the background.";
        }
        if (lower.contains("night") || lower.contains("dark") || lower.contains("dusk")) {
            return "Barely noticeable night ambience at a very low volume, with faint distant sounds and gentle air movement in the background.";
        }
        if (lower.contains("indoor") || lower.contains("room") || lower.contains("café") || lower.contains("cafe")
                || lower.contains("hall") || lower.contains("corridor")) {
            return "Very quiet indoor room tone with a faint low ventilation hum, kept in the background so dialogue stays clear.";
        }
        if (lower.contains("wind") || lower.contains("breeze")) {
            return "Soft wind that is muted and low in volume, gently brushing loose fabric and nearby objects in the background.";
        }
        if (lower.contains("ocean") || lower.contains("sea") || lower.contains("beach") || lower.contains("wave")) {
            return "Distant waves that are muted and gentle in the background, with a very light coastal breeze throughout.";
        }
        return DEFAULT_SOUNDSCAPE;
    }

    // ======================== 时间 / 文本工具 ========================

    /**
     * 拍起点（秒）。蓝图 {@code beat.time} 是「本镜内相对时间区间」，形如 {@code "0-2s"}，
     * 这里只取区间起点并转成指南要求的 {@code MM:SS.mmm}。
     *
     * @return 秒数；无法解析时返回 {@code null}（调用方退化为省略时间戳子句）
     */
    private static Double beatStartSeconds(String time) {
        String t = StrUtil.trim(time);
        if (StrUtil.isBlank(t)) {
            return null;
        }
        if (t.regionMatches(true, 0, "At ", 0, 3)) {
            t = StrUtil.trim(t.substring(3));
        }
        // MM:SS(.mmm)
        if (t.matches("^\\d+:\\d+(?:\\.\\d+)?.*")) {
            int colon = t.indexOf(':');
            String mm = t.substring(0, colon);
            String rest = t.substring(colon + 1);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (Character.isDigit(c) || c == '.') {
                    digits.append(c);
                } else {
                    break;
                }
            }
            try {
                return Double.parseDouble(mm) * 60 + Double.parseDouble(digits.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // 前导数字（"0-2s" / "2s" / "2.5s"）
        int end = 0;
        while (end < t.length() && (Character.isDigit(t.charAt(end)) || t.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Double.parseDouble(t.substring(0, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 秒 → 指南要求的 {@code MM:SS.mmm}（如 5 秒 → {@code 00:05.000}）。 */
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

    private static String strOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 去掉句尾句点（拼装时统一补，避免出现 {@code ".."}）。 */
    private static String stripTrailingPeriod(String text) {
        String s = StrUtil.trim(text);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 保证句子以句点结尾。 */
    private static String sentence(String text) {
        String s = StrUtil.trim(text);
        if (StrUtil.isBlank(s)) {
            return "";
        }
        return s.endsWith(".") || s.endsWith("!") || s.endsWith("?") || s.endsWith("…") ? s : s + ".";
    }
}
