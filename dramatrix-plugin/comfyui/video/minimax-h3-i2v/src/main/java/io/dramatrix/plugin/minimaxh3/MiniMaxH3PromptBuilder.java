package io.dramatrix.plugin.minimaxh3;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.PluginJsonUtil;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import io.dramatrix.plugin.dto.VideoContext;
import io.dramatrix.plugin.dto.VideoPromptResult;
import io.dramatrix.plugin.dto.VideoReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MiniMax H3 图生视频的<b>prompt 拼接类</b>。
 * <p>
 * 从 {@link MiniMaxH3Adapter#buildPrompt} 拆出，职责单一：把 LLM 结构化 JSON 拼成 MiniMax 全能参考模式
 * 六段格式 prompt（subject_definitions / summary / retention_analysis / detailed_description /
 * overall_soundscape / non_diegetic_music），并处理对白音色 / 中段参考帧 / 参考视频（白模/素材）等。
 * 依赖基类暴露的静态配置读取（{@code getMetaExtra}），通过 {@link MiniMaxH3Adapter} 访问。
 */
final class MiniMaxH3PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxH3PromptBuilder.class);

    /**
     * 场景图只做"结构参考"的说明句：光照不复刻。
     *
     * <p>场景图里常有大光柱 / 强逆光 / 亮斑；视频模型会逐帧重绘这类强光影，光柱边界与亮度会抖
     * （观感就是"闪"）。所以把场景图定性成「布局 / 建筑 / 材质 / 陈设」的来源，光照以 prompt 本体的
     * 光照描述为准，并要求整镜稳定、无闪烁漂移。</p>
     */
    private static final String SCENE_LIGHT_CAVEAT =
            " Use it only for the scene's layout, architecture, materials and props — do not reproduce the light shafts, "
                    + "hotspots or shadows visible in it: keep the lighting steady and even throughout, with no flicker "
                    + "and no drifting highlights.";

    // ======================== 参考图 / 首桢 模式开关 ========================

    /**
     * 参考图模式开关配置项名（兼容驼峰、下划线两种写法）。
     * true（默认）：场景图仅作视觉参考，不锁定首桢；
     * false：沿用旧逻辑，把场景图(+1 幻影编号)声明为 [Shot 1] 首桢锚点。
     */
    private static final List<String> REFERENCE_ONLY_KEYS = List.of("referenceOnly", "reference_only");

    private static final boolean DEFAULT_REFERENCE_ONLY = true;

    private MiniMaxH3PromptBuilder() {
    }

    /**
     * 从 LLM 结构化 JSON 拼接 MiniMax 全能参考模式六段格式 prompt。
     */
    static VideoPromptResult build(
            MiniMaxH3Adapter adapter,
            VideoContext videoContext
    ) {
        ShotVisualPlan plan = videoContext.getVisualPlan();
        VideoReference reference = videoContext.getReference();
        // 「全能参考」入口（裸参考槽位表）非空时，用「槽位 → 分类视图」覆盖下面的分类字段：
        // 图片号 = 槽位号，不做「角色→道具→场景」重排（见 toBareRefsView 的说明）。
        BareRefsView bare = toBareRefsView(reference);
        List<VideoReference.Ref> characterRefs = bare != null ? bare.characters()
                : (reference == null ? null : reference.getCharacters());
        List<VideoReference.Ref> assetRefs = bare != null ? bare.assets()
                : (reference == null ? null : reference.getAssets());
        List<VideoReference.Ref> refFrameRefs = bare != null ? null
                : (reference == null ? null : reference.getMidFrames());
        VideoReference.Ref sceneRef = bare != null ? bare.scene()
                : (reference == null ? null : reference.getScene());
        VideoReference.Ref firstFrameRef = bare != null ? null
                : (reference == null ? null : reference.getFirstFrame());
        // 风格：模型产出的 model_style 优先（见 resolveStyle）
        ResolvedStyle resolvedStyle = resolveStyle(plan, videoContext);
        String stylePrompt = resolvedStyle.text();
        List<String> firstFrameCharacterIds = videoContext.getFirstFrameCharacterIds();
        Set<String> firstFrameIdSet = firstFrameCharacterIds == null
                ? null
                : new LinkedHashSet<>(firstFrameCharacterIds);
        List<Map<String, Object>> textSegments = videoContext.getTextSegments();
        // 白模/素材参考视频（顺序 == <Video k>）；存在时运镜交给参考视频，抑制 LLM 机位（adoptRefCamera 缺省 true）
        List<VideoReference.VideoRef> videoRefs = reference == null ? null : reference.getVideos();
        boolean hasRefVideo = CollUtil.isNotEmpty(videoRefs);
        // 边界自检：本插件只认 VideoContext.reference —— 分类入口（characters/assets/scene/首帧）
        // 与「全能参考」裸参考入口（refSlots）的差别全在这一个对象里。「prompt 里没有 subject 定义」
        // 既可能是调用方没传，也可能是槽位表没被归一化（旧版插件不认 refSlots），两者现象一样、
        // 根因完全不同 —— 这条日志把它们区分开，排障时先看它，别去猜 LLM。
        log.info("[MiniMaxH3] build: refSlots={}, characters={}, assets={}, scene={}, firstFrame={}, refFrames={}, "
                        + "videos={}, beats={}, textSegments={}, firstFrameCharacterIds={}, styleSrc={}",
                CollUtil.size(reference == null ? null : reference.getRefSlots()),
                CollUtil.size(characterRefs), CollUtil.size(assetRefs),
                sceneRef != null, firstFrameRef != null,
                CollUtil.size(refFrameRefs), CollUtil.size(videoRefs),
                CollUtil.size(plan == null ? null : plan.getBeats()),
                CollUtil.size(textSegments),
                firstFrameCharacterIds == null ? "null(不约束)" : firstFrameCharacterIds,
                resolvedStyle.source());
        Object adoptRefCameraRaw = videoContext.option("adoptRefCamera");
        boolean adoptRefCamera = adoptRefCameraRaw == null || toBool(adoptRefCameraRaw);
        // 已配置宫格分镜故事板首帧：场景/首帧参考图是一张多格宫格板，按宫格图口径描述
        boolean gridStoryboard = Boolean.TRUE.equals(videoContext.getGridStoryboard());

        // 白模/素材参考视频存在时走独立精简 prompt
        if (hasRefVideo) {
            return buildRefVideoPrompt(adapter, videoContext);
        }

        // 1. 本镜蓝图（每镜一份，进入即本镜，无需再按 shotId 过滤）；无蓝图不出片
        if (plan == null || CollUtil.isEmpty(plan.getBeats())) {
            return null;
        }
        List<MotionSeg> segs = toMotionSegs(plan.getBeats());
        if (segs.isEmpty()) {
            return null;
        }

        // 2. 参考图槽位所用的「本镜同组角色并集」：由 worker 在规划期算好（主镜 ∪ 子镜），
        //    不再由插件从全组 motions 里自行汇总——否则子镜会丢掉同组角色的槽位编号
        Set<String> usedCharacterIdSet = new LinkedHashSet<>();
        if (firstFrameCharacterIds != null) {
            usedCharacterIdSet.addAll(firstFrameCharacterIds);
        }
        if (plan.getGroupCharacterIds() != null) {
            usedCharacterIdSet.addAll(plan.getGroupCharacterIds());
        }

        List<CharPicRef> allCharPicRefs = buildCharPicRefs(characterRefs);
        List<CharPicRef> charPicRefs;
        if (firstFrameCharacterIds == null) {
            charPicRefs = allCharPicRefs;
        } else {
            charPicRefs = new ArrayList<>();
            int picNum = 1;
            for (CharPicRef ref : allCharPicRefs) {
                if (!usedCharacterIdSet.contains(ref.charId)) {
                    continue;
                }
                // 该分支会压缩列表（丢掉本镜未入画的角色）→ 图片号与 subject 号一起重排，
                // 二者在这里仍然相等（分类链路口径）
                charPicRefs.add(new CharPicRef(ref.charId, picNum, picNum, ref.appearance));
                picNum++;
            }
        }
        List<AssetPicRef> assetPicRefs = buildAssetPicRefs(assetRefs, charPicRefs.size());

        List<String> audioCharacterIds = videoContext.option("audioCharacterIds");
        Map<String, Integer> audioIndexByChar = buildAudioIndexByChar(audioCharacterIds);
        List<String> audioOnlyIds = buildAudioOnlyIds(audioIndexByChar, charPicRefs);
        Map<String, String> speakerSxMap = buildSpeakerSxMap(textSegments, charPicRefs, audioIndexByChar, audioOnlyIds);
        int subjectIdx = 1;
        int assetSubjectIdx = subjectIdx + charPicRefs.size();
        int envSubjectIdx = assetSubjectIdx + assetPicRefs.size();
        int refFrameSubjectIdx = envSubjectIdx + 1;

        // 3. 场景描述：由 worker 从分镜层场景解析并写进蓝图（A 块），插件只读取、不再从 LLM 输出推断
        String sceneDesc = StrUtil.trim(plan.getSceneDesc());
        boolean hasSceneDesc = StrUtil.isNotBlank(sceneDesc);
        boolean hasSceneImage = sceneRef != null && StrUtil.isNotBlank(sceneRef.getImageUrl());
        boolean hasFirstFrameImage = firstFrameRef != null && StrUtil.isNotBlank(firstFrameRef.getImageUrl());
        boolean hasRefFrameImages = CollUtil.isNotEmpty(refFrameRefs);
        // 中段参考帧的续号起点：分类顺序号与「显式槽位号」取大者 —— 裸参考槽位号可以跳号
        // （如槽位 1=人物、2=场景、4=道具），不能拿列表长度当上界。
        int nextPicNum = Math.max(charPicRefs.size() + assetPicRefs.size() + 1,
                maxPicNum(charPicRefs, assetPicRefs) + 1);
        Integer scenePicNum = hasSceneImage ? picNumOf(sceneRef, nextPicNum) : null;
        if (hasSceneImage) {
            nextPicNum = Math.max(nextPicNum, scenePicNum + 1);
        }
        Integer firstFramePicNum = hasFirstFrameImage ? picNumOf(firstFrameRef, nextPicNum) : null;
        if (hasFirstFrameImage) {
            nextPicNum = Math.max(nextPicNum, firstFramePicNum + 1);
        }
        List<RefFramePicRef> refFramePicRefs = new ArrayList<>();
        if (hasRefFrameImages) {
            for (VideoReference.Ref rf : refFrameRefs) {
                if (rf == null || StrUtil.isBlank(rf.getImageUrl())) {
                    continue;
                }
                refFramePicRefs.add(new RefFramePicRef(nextPicNum++, rf.getInsertTime(), rf.getDesc()));
            }
        }
        boolean referenceOnly = resolveReferenceOnly(adapter, videoContext);
        boolean anchorFirstFrame = !referenceOnly && hasFirstFrameImage;
        Integer beginPicNum = anchorFirstFrame ? firstFramePicNum : scenePicNum;

        // 4. 构建六段
        StringBuilder sb = new StringBuilder();

        // 4.1 subject_definitions
        sb.append("subject_definitions:\n");
        for (int i = 0; i < charPicRefs.size(); i++) {
            CharPicRef ref = charPicRefs.get(i);
            int subjNum = subjectIdx + i;
            sb.append("<Subject ").append(subjNum).append("> is the figure in <Picture ")
                    .append(ref.picNum).append(">");
            if (StrUtil.isNotBlank(ref.appearance)) {
                sb.append(", ").append(ref.appearance);
            }
            sb.append(".\n");
        }
        for (int i = 0; i < assetPicRefs.size(); i++) {
            AssetPicRef ref = assetPicRefs.get(i);
            sb.append("<Subject ").append(assetSubjectIdx + i).append("> is the object in <Picture ")
                    .append(ref.picNum).append(">");
            if (StrUtil.isNotBlank(ref.keyword)) {
                sb.append(", ").append(ref.keyword);
            }
            sb.append(".\n");
        }
        if (scenePicNum != null) {
            sb.append("<Subject ").append(envSubjectIdx).append("> is the environment in <Picture ").append(scenePicNum).append(">");
            if (hasSceneDesc) {
                sb.append(", ").append(sceneDesc);
            }
            sb.append('.');
            sb.append(SCENE_LIGHT_CAVEAT);
            sb.append('\n');
        }
        if (gridStoryboard) {
            Integer pic = anchorFirstFrame ? firstFramePicNum : scenePicNum;
            if (pic != null) {
                sb.append("<Picture ").append(pic)
                        .append("> is a multi-panel storyboard board laid out in reading order (left-to-right, ")
                        .append("top-to-bottom), covering the whole scene across its panels; ")
                        .append("render the target video as one continuous shot that progresses through those panels.\n");
            }
        } else if (anchorFirstFrame) {
            sb.append("<Picture ").append(firstFramePicNum).append("> is the first frame of [Shot 1], establishing the scene.\n");
        } else if (scenePicNum != null) {
            sb.append("<Picture ").append(scenePicNum).append("> provides the visual reference for [Shot 1], establishing the scene.\n");
        }
        for (RefFramePicRef rf : refFramePicRefs) {
            String atTime = formatTime(rf.insertTimeLine);
            sb.append("<Picture ").append(rf.picNum)
                    .append("> is a reference frame that must visually anchor the shot");
            if (atTime != null) {
                sb.append(" at ").append(atTime);
            }
            if (StrUtil.isNotBlank(rf.description)) {
                sb.append(": ").append(rf.description);
            }
            sb.append(".\n");
        }
        for (Map.Entry<String, Integer> entry : audioIndexByChar.entrySet()) {
            String charId = entry.getKey();
            Integer audioNum = entry.getValue();
            Integer subjNum = findSubjectNum(charPicRefs, charId);
            String sx = speakerSxMap.get(charId);
            if (sx == null) {
                continue;
            }
            if (subjNum != null) {
                sb.append("<Audio ").append(audioNum).append("> is the voice-timbre reference for <Subject ")
                        .append(subjNum).append(">").append(sx).append(".\n");
            } else {
                sb.append("<Audio ").append(audioNum).append("> is the voice-timbre reference for the off-screen voice ")
                        .append(sx).append(".\n");
            }
        }
        sb.append("\n");

        // 视频参考锚定（白模/素材参考视频：<Video k> 只借动作/运镜，不借外观）
        if (CollUtil.isNotEmpty(videoRefs)) {
            int vk = 1;
            for (VideoReference.VideoRef v : videoRefs) {
                sb.append("<Video ").append(vk).append("> provides the motion & pacing reference ")
                        .append("for the target video (white-model pose); do not inherit its appearance.\n");
                if (CollUtil.isNotEmpty(v.getAnchors())) {
                    for (VideoReference.VideoRef.Anchor a : v.getAnchors()) {
                        if (a == null) continue;
                        Integer subjNum = findSubjectNum(charPicRefs, a.getCharId());
                        if (subjNum == null && StrUtil.isBlank(a.getCharName())) {
                            continue;
                        }
                        sb.append("In <Video ").append(vk).append(">, the figure wearing ")
                                .append(a.getColor()).append(" is ");
                        if (subjNum != null) {
                            sb.append("<Subject ").append(subjNum).append(">");
                            if (StrUtil.isNotBlank(a.getRoleAction())) {
                                sb.append(" (").append(a.getRoleAction()).append(")");
                            }
                        } else {
                            sb.append("character 「").append(a.getCharName()).append("」");
                        }
                        sb.append("; its actions/rhythm are performed by that character.\n");
                    }
                }
                vk++;
            }
            sb.append("\n");
        }

        // 4.2 summary
        sb.append("summary:\n");
        String taskPrefix = audioIndexByChar.isEmpty()
                ? "[keyframe completion + reference generation]"
                : "[keyframe completion + reference generation + audio reference]";
        sb.append(taskPrefix);
        if (beginPicNum != null) {
            String beginVerb = anchorFirstFrame ? "begins from" : "is guided by";
            sb.append(" The target video ").append(beginVerb).append(" <Picture ")
                    .append(beginPicNum).append(">");
        }
        if (!charPicRefs.isEmpty() && (firstFrameIdSet == null || !firstFrameIdSet.isEmpty())) {
            sb.append(" and shows ");
            boolean firstShown = true;
            for (int i = 0; i < charPicRefs.size(); i++) {
                String charId = charPicRefs.get(i).charId;
                if (firstFrameIdSet != null && !firstFrameIdSet.contains(charId)) {
                    continue;
                }
                if (!firstShown) sb.append(", ");
                sb.append("<Subject ").append(subjectIdx + i).append(">");
                firstShown = false;
            }
        }
        if (scenePicNum != null) {
            sb.append(" in <Subject ").append(envSubjectIdx).append(">");
        }
        sb.append(".");
        if (firstFrameIdSet != null && firstFrameIdSet.isEmpty() && !charPicRefs.isEmpty()) {
            sb.append(" No character appears in the opening frame; introduce a character only where detailed_description explicitly places it.");
        }
        if (!audioIndexByChar.isEmpty()) {
            sb.append(" The dialogue delivery follows ");
            appendAudioSummary(sb, audioIndexByChar, speakerSxMap, charPicRefs);
            sb.append(".");
        }
        sb.append("\n\n");

        // 4.3 retention_analysis
        sb.append("retention_analysis:\n");
        for (int i = 0; i < charPicRefs.size(); i++) {
            CharPicRef ref = charPicRefs.get(i);
            int subjNum = subjectIdx + i;
            boolean inFirstFrame = firstFrameIdSet == null || firstFrameIdSet.contains(ref.charId);
            if (inFirstFrame) {
                // 用 <Picture k> 锚定，**不**把 appearance 原样拼进句子。
                // 旧写法「the {appearance} are retained」有两个毛病：① appearance 是外观短语时
                // 单复数/冠词对不上（`the a young man are retained`）；② appearance 若被写成人名
                // （上游 desc 的来源之一）就得到「the Ye Fan are retained」——错的指代、还给模型
                // 喂了个没有锚点的专有名词。外观真源是 <Picture k>，subject_definitions 里已写过一次，
                // 这里再拼一遍也违反「一个事实只有一个出口」。口径与下面的道具行一致。
                sb.append("<Subject ").append(subjNum)
                        .append("> (appears in [Shot 1]): fully_preserved - the figure in <Picture ")
                        .append(ref.picNum).append("> is retained in full");
            } else {
                sb.append("<Subject ").append(subjNum)
                        .append("> (not in the opening frame): retained only when detailed_description explicitly introduces it");
            }
            sb.append(".\n");
        }
        for (int i = 0; i < assetPicRefs.size(); i++) {
            AssetPicRef ref = assetPicRefs.get(i);
            sb.append("<Subject ").append(assetSubjectIdx + i)
                    .append("> (object in the scene): retained - the object's shape, material and scale match <Picture ")
                    .append(ref.picNum)
                    .append("> whenever detailed_description involves it.\n");
        }
        if (scenePicNum != null) {
            sb.append("<Subject ").append(envSubjectIdx)
                    .append("> (appears in [Shot 1]): fully_preserved - the scene is retained.\n");
        }
        for (int i = 0; i < refFramePicRefs.size(); i++) {
            RefFramePicRef rf = refFramePicRefs.get(i);
            int subjNum = refFrameSubjectIdx + i;
            String atTime = formatTime(rf.insertTimeLine);
            sb.append("<Subject ").append(subjNum)
                    .append("> (reference frame");
            if (atTime != null) {
                sb.append(" at ").append(atTime);
            }
            sb.append("): the composition, characters and visual style in <Picture ")
                    .append(rf.picNum)
                    .append("> are preserved");
            if (atTime != null) {
                sb.append(" at that timestamp");
            }
            if (StrUtil.isNotBlank(rf.description)) {
                sb.append(" — ").append(rf.description);
            }
            sb.append(".\n");
        }
        if (anchorFirstFrame && firstFramePicNum != null) {
            sb.append("<Picture ").append(firstFramePicNum)
                    .append("> ([Shot 1] first frame): fully_preserved - the first-frame composition and camera position are matched exactly.\n");
        }
        for (Map.Entry<String, Integer> entry : audioIndexByChar.entrySet()) {
            String charId = entry.getKey();
            Integer audioNum = entry.getValue();
            Integer subjNum = findSubjectNum(charPicRefs, charId);
            String sx = speakerSxMap.get(charId);
            if (sx == null) {
                continue;
            }
            if (subjNum != null) {
                sb.append("<Audio ").append(audioNum).append(">: reference - its vocal timbre guides the dialogue delivery of <Subject ")
                        .append(subjNum).append("> without copying the original signal.\n");
            } else {
                sb.append("<Audio ").append(audioNum).append(">: reference - its vocal timbre guides the off-screen voice ")
                        .append(sx).append(" without copying the original signal.\n");
            }
        }
        sb.append("\n");

        // 4.4 detailed_description
        sb.append("detailed_description:\n");
        String effectiveStyle = StrUtil.isNotBlank(stylePrompt) ? stylePrompt
                : "cinematic style";
        // 模型产出的风格是完整判断，原样输出；配置来的风格沿用历史行为（不加 cinematic 时补前缀）
        if (resolvedStyle.fromModel() || effectiveStyle.toLowerCase().contains("cinematic")) {
            sb.append("The target video uses ").append(effectiveStyle).append(".\n");
        } else {
            sb.append("The target video uses a cinematic, ").append(effectiveStyle).append(".\n");
        }
        sb.append("Unless a shot explicitly states that a character faces the camera, ")
                .append("no character looks into the lens or walks toward it; ")
                .append("characters are staged in depth and act toward each other across frame.\n");

        if (!segs.isEmpty()) {
            List<Map<String, Object>> dialoguePool = null;
            if (textSegments != null) {
                dialoguePool = new ArrayList<>(textSegments.size());
                for (Map<String, Object> ts : textSegments) {
                    if (ts != null) {
                        dialoguePool.add(new LinkedHashMap<>(ts));
                    }
                }
            }
            boolean shotHasDialogue = hasSpeakableDialogue(textSegments);

            for (int i = 0; i < segs.size(); i++) {
                MotionSeg seg = segs.get(i);
                // 本拍时间区间：起点用于逐拍时间戳，区间本身用于中段参考帧的落点判定
                double[] segRange = parseSegmentTimeRange(seg.time, i == 0);

                sb.append("[Shot ").append(i + 1).append("] ");

                if (i == 0) {
                    boolean sceneAnchored = false;
                    if (anchorFirstFrame && firstFramePicNum != null) {
                        sb.append("The shot begins from <Picture ").append(firstFramePicNum).append(">, holding <Subject ")
                                .append(envSubjectIdx).append(">");
                        sceneAnchored = true;
                    } else if (scenePicNum != null) {
                        sb.append("The shot is guided by <Picture ").append(scenePicNum).append(">, holding <Subject ")
                                .append(envSubjectIdx).append(">");
                        sceneAnchored = true;
                    }
                    if (sceneAnchored) {
                        if (hasSceneDesc) {
                            sb.append(", the scene with ").append(sceneDesc);
                        }
                        sb.append(". ");
                    }
                } else {
                    // 逐拍时间戳：官方指南 §5.1 要求 `[Shot N] At MM:SS.mmm,`，语义是**本拍开始（切换）
                    // 时刻**，而不是蓝图里的原始区间（`1.5-3s`）。形态与首尾帧插件
                    // （MiniMaxH3FlPromptBuilder）统一；时间解析不出时整段省略 `At` 子句，
                    // 但硬切描述照常输出。
                    String at = segRange == null ? null : formatTime(segRange[0]);
                    if (at != null) {
                        sb.append("At ").append(at).append(", ");
                    }
                    if (seg.hardCut) {
                        sb.append("the camera hard-cuts to a new framing; ");
                    }
                }

                for (int rfIdx = 0; rfIdx < refFramePicRefs.size(); rfIdx++) {
                    RefFramePicRef rf = refFramePicRefs.get(rfIdx);
                    if (rf.insertTimeLine == null || segRange == null) {
                        continue;
                    }
                    if (rf.insertTimeLine >= segRange[0] && rf.insertTimeLine <= segRange[1]) {
                        int subjNum = refFrameSubjectIdx + rfIdx;
                        String atTime = formatTime(rf.insertTimeLine);
                        if (atTime != null) {
                            sb.append("At ").append(atTime).append(", ");
                        }
                        sb.append("the visual reaches the anchor frame <Subject ").append(subjNum)
                                .append(">, matching the composition and style of <Picture ")
                                .append(rf.picNum).append(">");
                        String desc = StrUtil.trim(rf.description);
                        if (StrUtil.isNotBlank(desc)) {
                            sb.append(": ").append(desc);
                        }
                        sb.append(". ");
                    }
                }

                if (StrUtil.isNotBlank(seg.camera) && !(hasRefVideo && adoptRefCamera)) {
                    String cam = resolveSubjectRefs(seg.camera.trim().toLowerCase(), charPicRefs);
                    if (cam.startsWith("static")) {
                        String rest = cam.substring("static".length())
                                .replaceAll("^shot\\b", "")
                                .replaceAll("^[,;:\\s]+", "")
                                .replaceAll("[,;:\\s]+$", "")
                                .trim();
                        cam = StrUtil.isBlank(rest) ? "holds a static shot" : "holds a static shot, " + rest;
                    }
                    sb.append("The camera ").append(cam).append(". ");
                }

                boolean firstFrameHasNoCharacters = i == 0
                        && firstFrameIdSet != null
                        && firstFrameIdSet.isEmpty();
                if (!firstFrameHasNoCharacters
                        && CollUtil.isNotEmpty(seg.characters)
                        && characterRefs != null) {
                    for (ShotVisualPlan.BeatCharacter ch : seg.characters) {
                        String charId = ch.getCharacterId();
                        // 位置与朝向来自 characters[].position（模型产出，原样拼在动作之前；Java 不解析）
                        String charPosition = StrUtil.trim(ch.getPosition());
                        String charDesc = StrUtil.trim(ch.getDesc());
                        if (i == 0 && firstFrameIdSet != null && !firstFrameIdSet.contains(charId)) {
                            continue;
                        }
                        Integer subjNum = findSubjectNum(charPicRefs, charId);
                        if (subjNum != null) {
                            sb.append("<Subject ").append(subjNum).append(">");
                            if (StrUtil.isNotBlank(charPosition)) {
                                sb.append(", ").append(resolveSubjectRefs(charPosition, charPicRefs));
                            }
                            if (StrUtil.isNotBlank(charDesc)) {
                                sb.append(", ").append(resolveSubjectRefs(charDesc, charPicRefs));
                            }
                            sb.append(". ");
                        }
                    }
                }

                if (CollUtil.isNotEmpty(seg.props)) {
                    for (ShotVisualPlan.BeatProp p : seg.props) {
                        String assetId = p.getAssetId();
                        String pDesc = StrUtil.trim(p.getDesc());
                        Integer subjNum = findAssetSubjectNum(assetPicRefs, assetSubjectIdx, assetId);
                        if (subjNum != null) {
                            sb.append("<Subject ").append(subjNum).append(">");
                            if (StrUtil.isNotBlank(pDesc)) {
                                sb.append(", ").append(resolveSubjectRefs(pDesc, charPicRefs));
                            }
                            sb.append(". ");
                        } else if (StrUtil.isNotBlank(pDesc)) {
                            sb.append(resolveSubjectRefs(pDesc, charPicRefs)).append(". ");
                        }
                    }
                }

                if (StrUtil.isNotBlank(seg.desc)) {
                    sb.append(resolveSubjectRefs(seg.desc, charPicRefs)).append(". ");
                }

                if (dialoguePool != null && !dialoguePool.isEmpty()) {
                    List<DialogueLine> shotDialogue = consumeShotDialogue(
                            dialoguePool, seg, audioIndexByChar, audioOnlyIds
                    );
                    if (!shotDialogue.isEmpty()) {
                        sb.append(buildDialogueLines(
                                shotDialogue, charPicRefs, speakerSxMap, audioIndexByChar, audioOnlyIds
                        ));
                    } else if (!shotHasDialogue) {
                        sb.append("No spoken dialogue in this shot; all characters remain silent. ")
                                .append("Only the ambient soundscape is audible.\n");
                    }
                } else if (!shotHasDialogue) {
                    sb.append("No spoken dialogue in this shot; all characters remain silent. ")
                            .append("Only the ambient soundscape is audible.\n");
                } else {
                    sb.append("\n");
                }

                sb.append("\n");
            }

            if (dialoguePool != null && !dialoguePool.isEmpty()) {
                List<DialogueLine> leftover = toDialogueLines(dialoguePool, charPicRefs);
                if (!leftover.isEmpty()) {
                    sb.append(buildDialogueLines(
                            leftover, charPicRefs, speakerSxMap, audioIndexByChar, audioOnlyIds
                    ));
                }
            }
        } else {
            boolean sceneAnchored = false;
            if (anchorFirstFrame && firstFramePicNum != null) {
                sb.append("[Shot 1] The shot begins from <Picture ").append(firstFramePicNum)
                        .append(">, holding <Subject ").append(envSubjectIdx).append(">");
                sceneAnchored = true;
            } else if (scenePicNum != null) {
                sb.append("[Shot 1] The shot is guided by <Picture ").append(scenePicNum)
                        .append(">, holding <Subject ").append(envSubjectIdx).append(">");
                sceneAnchored = true;
            }
            if (sceneAnchored) {
                if (hasSceneDesc) {
                    sb.append(", the scene with ").append(sceneDesc);
                }
                sb.append(". The camera holds a static shot.\n");
            }
            String sceneShotDialogue = (textSegments != null && !textSegments.isEmpty())
                    ? buildDialogueLines(toDialogueLines(textSegments, charPicRefs),
                            charPicRefs, speakerSxMap, audioIndexByChar, audioOnlyIds)
                    : "";
            if (StrUtil.isNotBlank(sceneShotDialogue)) {
                sb.append(sceneShotDialogue);
            } else {
                sb.append("No spoken dialogue in this shot; all characters remain silent. ")
                        .append("Only the ambient soundscape is audible.\n");
            }
        }

        // 4.5 overall_soundscape（优先用规划层写的整段音景，缺失才按场景描述关键词兜底）
        sb.append("\noverall_soundscape:\n");
        sb.append(StrUtil.blankToDefault(StrUtil.trim(plan.getSoundscape()),
                generateSoundscape(sceneDesc))).append("\n\n");

        // 4.6 non_diegetic_music（优先用规划层写的观众配乐；都没写才落 None）
        sb.append("non_diegetic_music:\n");
        sb.append(StrUtil.blankToDefault(StrUtil.trim(plan.getMusic()), "None")).append("\n");

        String prompt = sb.toString().trim();
        if (StrUtil.isBlank(prompt)) {
            return null;
        }
        return VideoPromptResult.builder()
                .prompt(prompt)
                .reference(buildFinalReference(reference, charPicRefs))
                .build();
    }

    /**
     * 风格取值：顶层 {@code model_style}（规划层产出，<b>随内容走</b>）优先，
     * 其次 {@code plan.style}（作品级固定风格后缀，worker 直取），再其次
     * {@link VideoContext#getStylePrompt()}；都为空时 {@code text} 为 {@code null}，由调用点走内置兜底。
     *
     * <p><b>为什么模型风格优先</b>：作品级 {@code stylePrompt} 是静态配置，对具体内容一无所知；
     * 而「讲解视频要干净克制 / 剧情片要电影感」这类判断，只有看过内容才做得对。</p>
     *
     * <p><b>为什么把来源也带出去（{@code fromModel}）</b>：成稿里那条
     * {@code The target video uses …} 对**配置来的**风格会强加 {@code a cinematic,} 前缀
     * （历史行为，保持兼容）；但模型产出的风格本身就是完整的风格判断，再加「电影感」会把
     * 「干净克制的幻灯片」拧回电影质感 —— 故模型风格**原样输出、不加前缀**。</p>
     */
    private static ResolvedStyle resolveStyle(ShotVisualPlan plan, VideoContext ctx) {
        String model = plan == null ? null : StrUtil.trim(plan.getModelStyle());
        if (StrUtil.isNotBlank(model)) {
            return new ResolvedStyle(model, true, "model");
        }
        String fixed = plan == null ? null : StrUtil.trim(plan.getStyle());
        if (StrUtil.isNotBlank(fixed)) {
            return new ResolvedStyle(fixed, false, "plan");
        }
        String global = ctx == null ? null : StrUtil.trim(ctx.getStylePrompt());
        return new ResolvedStyle(global, false, StrUtil.isBlank(global) ? "default" : "ctx");
    }

    /**
     * 风格取值结果：{@code text} 可为空（调用点走内置兜底）；
     * {@code fromModel} = 是否来自蓝图顶层 {@code model_style}（决定要不要加 {@code a cinematic,} 前缀）；
     * {@code source} = {@code model} / {@code plan} / {@code ctx} / {@code default}，只用于日志排查。
     */
    private record ResolvedStyle(String text, boolean fromModel, String source) {}

    /**
     * 参考视频（白模/素材）存在时的精简 prompt，按模型「参考生视频」口径拼接。
     */
    private static VideoPromptResult buildRefVideoPrompt(MiniMaxH3Adapter adapter, VideoContext videoContext) {
        ShotVisualPlan plan = videoContext.getVisualPlan();
        VideoReference reference = videoContext.getReference();
        List<VideoReference.Ref> characterRefs = reference == null ? null : reference.getCharacters();
        List<VideoReference.Ref> assetRefs = reference == null ? null : reference.getAssets();
        VideoReference.Ref sceneRef = reference == null ? null : reference.getScene();
        List<VideoReference.VideoRef> videoRefs = reference == null ? null : reference.getVideos();
        // 风格：模型产出的 model_style 优先（见 resolveStyle）；ref-video 分支的变量名沿用 stylePrompt
        ResolvedStyle resolvedStyle = resolveStyle(plan, videoContext);
        String stylePrompt = resolvedStyle.text();
        List<Map<String, Object>> textSegments = videoContext.getTextSegments();

        List<CharPicRef> charPicRefs = buildCharPicRefs(characterRefs);
        List<AssetPicRef> assetPicRefs = buildAssetPicRefs(assetRefs, charPicRefs.size());
        List<String> audioCharacterIds = videoContext.option("audioCharacterIds");
        Map<String, Integer> audioIndexByChar = buildAudioIndexByChar(audioCharacterIds);
        List<String> audioOnlyIds = buildAudioOnlyIds(audioIndexByChar, charPicRefs);
        Map<String, String> speakerSxMap = buildSpeakerSxMap(textSegments, charPicRefs, audioIndexByChar, audioOnlyIds);
        String sceneDesc = plan == null ? "" : StrUtil.trim(plan.getSceneDesc());
        boolean hasSceneDesc = StrUtil.isNotBlank(sceneDesc);
        boolean hasSceneImage = sceneRef != null && StrUtil.isNotBlank(sceneRef.getImageUrl());
        int subjectIdx = 1;
        int assetSubjectIdx = subjectIdx + charPicRefs.size();
        int envSubjectIdx = assetSubjectIdx + assetPicRefs.size();
        int nextPicNum = charPicRefs.size() + assetPicRefs.size() + 1;
        Integer scenePicNum = hasSceneImage ? nextPicNum++ : null;

        StringBuilder sb = new StringBuilder();
        sb.append("subject_definitions:\n");
        for (int i = 0; i < charPicRefs.size(); i++) {
            CharPicRef ref = charPicRefs.get(i);
            sb.append("<Subject ").append(subjectIdx + i).append("> is the figure in <Picture ").append(ref.picNum)
                    .append(">, strictly keeping that exact appearance, face and costume.\n");
        }
        for (int i = 0; i < assetPicRefs.size(); i++) {
            AssetPicRef ref = assetPicRefs.get(i);
            sb.append("<Subject ").append(assetSubjectIdx + i).append("> is the object in <Picture ").append(ref.picNum)
                    .append(">, strictly matching that object.\n");
        }
        if (scenePicNum != null) {
            sb.append("<Subject ").append(envSubjectIdx).append("> is the environment in <Picture ").append(scenePicNum).append(">");
            if (hasSceneDesc) {
                sb.append(", ").append(sceneDesc);
            }
            sb.append('.');
            sb.append(SCENE_LIGHT_CAVEAT);
            sb.append('\n');
        }

        int vk = 1;
        assert videoRefs != null;
        for (VideoReference.VideoRef v : videoRefs) {
            sb.append("<Video ").append(vk).append("> provides the action beats, body acting intent and camera pacing; ")
                    .append("do not inherit its appearance. Follow its choreography and timing, but render the movement as ")
                    .append("natural, fluid and lifelike human motion, not a stiff or mechanical mannequin.\n");
            if (CollUtil.isNotEmpty(v.getAnchors())) {
                for (VideoReference.VideoRef.Anchor a : v.getAnchors()) {
                    if (a == null) {
                        continue;
                    }
                    Integer subjNum = findSubjectNum(charPicRefs, a.getCharId());
                    if (subjNum == null && StrUtil.isBlank(a.getCharName())) {
                        continue;
                    }
                    sb.append("In <Video ").append(vk).append(">, the figure wearing ").append(a.getColor()).append(" is ");
                    if (subjNum != null) {
                        sb.append("<Subject ").append(subjNum).append(">");
                    } else {
                        sb.append("character 「").append(a.getCharName()).append("」");
                    }
                    sb.append("; perform its actions and timing naturally, with relaxed, smooth and organic body motion.\n");
                }
            }
            vk++;
        }
        sb.append("\n");

        sb.append("intent: Produce one continuous shot whose action beats, pacing and camera follow the reference video(s), ")
                .append("while every character's movement is smooth, natural and lifelike (avoid stiff, rigid or mechanical motion), ")
                .append("and each character keeps the exact appearance in its reference picture. ")
                .append("Do not add characters, camera or composition beyond the reference video.\n\n");

        sb.append("detailed_description:\n");
        if (StrUtil.isNotBlank(stylePrompt)) {
            // 模型产出的风格原样输出；配置来的风格沿用历史行为（不含 cinematic 时补前缀）
            if (resolvedStyle.fromModel() || stylePrompt.toLowerCase().contains("cinematic")) {
                sb.append("The target video uses ").append(stylePrompt).append(".\n");
            } else {
                sb.append("The target video uses a cinematic, ").append(stylePrompt).append(".\n");
            }
        }
        String dialogue = textSegments != null
                ? buildDialogueLines(toDialogueLines(textSegments, charPicRefs),
                        charPicRefs, speakerSxMap, audioIndexByChar, audioOnlyIds)
                : "";
        if (StrUtil.isNotBlank(dialogue)) {
            sb.append(dialogue);
        } else {
            sb.append("No spoken dialogue in this shot; all characters remain silent. ")
                    .append("Only the ambient soundscape is audible.\n");
        }

        // 音景与配乐：与逐拍路径同一口径 —— 规划层写了就用，没写才兜底
        sb.append("\noverall_soundscape:\n")
                .append(StrUtil.blankToDefault(plan == null ? null : StrUtil.trim(plan.getSoundscape()),
                        generateSoundscape(sceneDesc)))
                .append("\n\n");
        sb.append("non_diegetic_music:\n")
                .append(StrUtil.blankToDefault(plan == null ? null : StrUtil.trim(plan.getMusic()), "None"))
                .append("\n");

        String prompt = sb.toString().trim();
        if (StrUtil.isBlank(prompt)) {
            return null;
        }
        return VideoPromptResult.builder()
                .prompt(prompt)
                .reference(buildFinalReference(reference, charPicRefs))
                .build();
    }

    /**
     * 组装最终参考：角色按 charPicRefs（裁剪/重排后的 Picture 顺序）替换，其余分类原样保留。
     * <p>
     * {@code refSlots}（裸参考槽位表）原样透传 —— 它是「全能参考」入口的权威素材表，
     * 分类字段只是插件内部的归一化视图，不能反过来当成槽位表用。
     */
    private static VideoReference buildFinalReference(VideoReference reference, List<CharPicRef> charPicRefs) {
        if (reference == null) {
            return null;
        }
        Map<String, String> urlById = new LinkedHashMap<>();
        if (reference.getCharacters() != null) {
            for (VideoReference.Ref ref : reference.getCharacters()) {
                if (ref != null && StrUtil.isNotBlank(ref.getId())) {
                    urlById.put(ref.getId(), ref.getImageUrl());
                }
            }
        }
        List<VideoReference.Ref> characters = new ArrayList<>();
        for (CharPicRef ref : charPicRefs) {
            characters.add(VideoReference.Ref.builder()
                    .id(ref.charId)
                    .desc(ref.appearance)
                    .imageUrl(urlById.get(ref.charId))
                    .build());
        }
        return VideoReference.builder()
                .characters(characters)
                .assets(reference.getAssets())
                .scene(reference.getScene())
                .firstFrame(reference.getFirstFrame())
                .midFrames(reference.getMidFrames())
                .audios(reference.getAudios())
                .videos(reference.getVideos())
                .refSlots(reference.getRefSlots())
                .build();
    }

    // ======================== Prompt 构建辅助 ========================

    /**
     * 蓝图 {@code beats[]} → 逐拍段落。改造后本插件的唯一动作来源，
     * 不再解析 {@code shots[].motions[]} 两级 JSON（规划已上移到 {@code VISUAL_PLAN}）。
     */
    private static List<MotionSeg> toMotionSegs(List<ShotVisualPlan.Beat> beats) {
        List<MotionSeg> segs = new ArrayList<>();
        if (beats == null) {
            return segs;
        }
        for (ShotVisualPlan.Beat beat : beats) {
            if (beat == null || StrUtil.isBlank(beat.getDesc())) {
                continue;
            }
            segs.add(new MotionSeg(
                    beat.getTime(),
                    beat.getDesc(),
                    StrUtil.blankToDefault(beat.getCameraMotion(), beat.getCamera()),
                    beat.getCharacters() == null ? List.of() : beat.getCharacters(),
                    beat.getProps() == null ? List.of() : beat.getProps(),
                    beat.getDialogue() != null,
                    toDialogueRefs(beat.getDialogue()),
                    Boolean.TRUE.equals(beat.getHardCut())
            ));
        }
        return segs;
    }

    private static List<DialogueRef> toDialogueRefs(List<ShotVisualPlan.DialogueRef> dialogue) {
        List<DialogueRef> refs = new ArrayList<>();
        if (dialogue == null) {
            return refs;
        }
        for (ShotVisualPlan.DialogueRef d : dialogue) {
            if (d == null || StrUtil.isBlank(d.getId())) {
                continue;
            }
            refs.add(new DialogueRef(d.getId(), !Boolean.FALSE.equals(d.getOnScreen()),
                    d.getDelivery(), d.getSpeak()));
        }
        return refs;
    }

    /**
     * 角色图片引用
     * <p>
     * ⚠️ {@code picNum}（{@code <Picture k>}）与 {@code subjectNum}（{@code <Subject k>}）<b>是两个号</b>：
     * 分类链路下二者恒等（顺序编号），裸参考槽位表下 {@code picNum} = 槽位号、{@code subjectNum} =
     * 该角色在角色列表里的序号 —— 二者不再相等，任何地方都不能拿一个当另一个用。
     */
    private static class CharPicRef {
        final String charId;
        final int picNum;
        final int subjectNum;
        final String appearance;

        CharPicRef(String charId, int picNum, int subjectNum, String appearance) {
            this.charId = charId;
            this.picNum = picNum;
            this.subjectNum = subjectNum;
            this.appearance = appearance;
        }
    }

    /**
     * 中段参考帧的 Picture 引用
     */
    private static class RefFramePicRef {
        final int picNum;
        final Double insertTimeLine;
        final String description;

        RefFramePicRef(int picNum, Double insertTimeLine, String description) {
            this.picNum = picNum;
            this.insertTimeLine = insertTimeLine;
            this.description = description;
        }
    }

    /**
     * 秒 → 官方指南 §5.1 要求的 {@code MM:SS.mmm}（如 2.5 秒 → {@code 00:02.500}）。
     *
     * <p>本插件里<b>所有绝对时间点</b>（逐拍切换时刻、中段参考帧落点）统一走这里，保证一段 prompt
     * 里只有一套时间口径。此前参考帧落点用的是「秒 + s」形态（{@code 2.5s}），而
     * {@code insertTimeLine} 是<b>全片绝对时间点</b>（契约 C5）—— 时长形态既与逐拍时间戳冲突，
     * 读起来也像「持续 2.5 秒」而非「第 2.5 秒」，本身就有歧义。</p>
     *
     * @return {@code null} 表示给不出时间（{@code null} 或负数），调用方省略时间短语
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

    private static double[] parseSegmentTimeRange(String time, boolean isFirst) {
        if (isFirst && StrUtil.isBlank(time)) {
            return new double[]{0.0, Double.POSITIVE_INFINITY};
        }
        if (StrUtil.isBlank(time)) {
            return null;
        }
        String s = StrUtil.trim(time).toLowerCase();
        if (s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            int dash = s.indexOf('-');
            if (dash < 0) {
                double v = Double.parseDouble(s);
                return new double[]{v, v};
            }
            double start = Double.parseDouble(s.substring(0, dash).trim());
            double end = Double.parseDouble(s.substring(dash + 1).trim());
            return new double[]{start, end};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 道具图片引用
     */
    private static class AssetPicRef {
        final String assetId;
        final int picNum;
        final String keyword;

        AssetPicRef(String assetId, int picNum, String keyword) {
            this.assetId = assetId;
            this.picNum = picNum;
            this.keyword = keyword;
        }
    }

    /**
     * motion 段落
     */
    private static class MotionSeg {
        final String time;
        final String desc;
        final String camera;
        final List<ShotVisualPlan.BeatCharacter> characters;
        final List<ShotVisualPlan.BeatProp> props;
        final boolean hasDialogueField;
        final List<DialogueRef> dialogue;
        final boolean hardCut;

        MotionSeg(String time, String desc, String camera,
                  List<ShotVisualPlan.BeatCharacter> characters,
                  List<ShotVisualPlan.BeatProp> props,
                  boolean hasDialogueField, List<DialogueRef> dialogue, boolean hardCut) {
            this.time = time;
            this.desc = desc;
            this.camera = camera;
            this.characters = characters;
            this.props = props;
            this.hasDialogueField = hasDialogueField;
            this.dialogue = dialogue;
            this.hardCut = hardCut;
        }
    }

    /**
     * motion 上声明的台词引用
     */
    private record DialogueRef(String id, boolean onScreen, String delivery, String speak) {}

    /**
     * 已匹配到某 motion 的台词条目及其画面内/外标记与情绪描述。
     */
    private record DialogueLine(Map<String, Object> seg, boolean onScreen, String delivery) {}

    private static List<CharPicRef> buildCharPicRefs(List<VideoReference.Ref> characterRefs) {
        List<CharPicRef> refs = new ArrayList<>();
        if (CollUtil.isEmpty(characterRefs)) {
            return refs;
        }
        for (VideoReference.Ref ref : characterRefs) {
            if (ref == null || StrUtil.isBlank(ref.getId())) {
                continue;
            }
            // 图片号：显式槽位号优先（裸参考），否则按角色列表顺序递增
            refs.add(new CharPicRef(ref.getId(), picNumOf(ref, refs.size() + 1), refs.size() + 1,
                    StrUtil.trim(ref.getDesc())));
        }
        return refs;
    }

    private static List<AssetPicRef> buildAssetPicRefs(List<VideoReference.Ref> assetRefs, int charPicCount) {
        List<AssetPicRef> refs = new ArrayList<>();
        if (CollUtil.isEmpty(assetRefs)) {
            return refs;
        }
        for (VideoReference.Ref ref : assetRefs) {
            if (ref == null || StrUtil.isBlank(ref.getId())) {
                continue;
            }
            refs.add(new AssetPicRef(ref.getId(), picNumOf(ref, charPicCount + refs.size() + 1), ref.getDesc()));
        }
        return refs;
    }

    /** 图片号：显式槽位号优先（裸参考），否则用给定的顺序号 */
    private static int picNumOf(VideoReference.Ref ref, int sequential) {
        return ref != null && ref.getPicNum() != null && ref.getPicNum() > 0 ? ref.getPicNum() : sequential;
    }

    /** 角色 + 道具列表里已用掉的最大图片号（0 = 都没显式槽位号） */
    private static int maxPicNum(List<CharPicRef> chars, List<AssetPicRef> assets) {
        int max = 0;
        for (CharPicRef c : chars) {
            max = Math.max(max, c.picNum);
        }
        for (AssetPicRef a : assets) {
            max = Math.max(max, a.picNum);
        }
        return max;
    }

    /**
     * 裸参考槽位表 → 插件内部的分类视图（「全能参考」入口）。
     *
     * <p>「全能参考」的素材条没有任何分类（用户随手从素材库/本地上传挑的图），
     * 因此分类由<b>前置 LLM 从用户指令文本</b>读出（如「@参考图1 是女主，@参考图2 是场景」），
     * 写进 {@link VideoReference.Slot#getRole()}；本方法只做映射，不做推断：</p>
     * <ul>
     *   <li>{@code environment} → 场景参考（走 {@code <Subject k> is the environment in <Picture k>}
     *       + 场景光照免责句，避免模型复刻场景图里的强光斑导致逐帧闪烁）；</li>
     *   <li>{@code object} → 道具参考；</li>
     *   <li>{@code figure} / 未标注 / 未知 → 人物参考（默认值最安全：模型看得到图，
     *       而「is the figure in」不会把人物描述成道具）。</li>
     * </ul>
     *
     * <p>每条都带 {@link VideoReference.Ref#getPicNum()} = 槽位号，保证 {@code <Picture k>}
     * 与生成请求里第 k 张 {@code refAssetIds} 严格对齐。视频 / 音频槽位不进图片视图
     * （音频经 {@code VideoReference.audios} 另行绑定）。</p>
     *
     * @return null = 本次不是裸参考入口，调用方继续用分类字段
     */
    private static BareRefsView toBareRefsView(VideoReference reference) {
        if (reference == null || CollUtil.isEmpty(reference.getRefSlots())) {
            return null;
        }
        List<VideoReference.Ref> characters = new ArrayList<>();
        List<VideoReference.Ref> assets = new ArrayList<>();
        VideoReference.Ref scene = null;
        for (VideoReference.Slot slot : reference.getRefSlots()) {
            if (slot == null || slot.getSlot() == null || slot.getSlot() <= 0) {
                continue;
            }
            String kind = StrUtil.trimToEmpty(slot.getKind());
            if (StrUtil.isNotBlank(kind) && !"image".equalsIgnoreCase(kind)) {
                continue;
            }
            VideoReference.Ref ref = VideoReference.Ref.builder()
                    .id(StrUtil.blankToDefault(StrUtil.trim(slot.getId()), "ref_" + slot.getSlot()))
                    .desc(StrUtil.trim(slot.getDesc()))
                    .imageUrl(slot.getImageUrl())
                    .picNum(slot.getSlot())
                    .build();
            String role = StrUtil.trimToEmpty(slot.getRole()).toLowerCase();
            if ("environment".equals(role)) {
                if (scene == null) {
                    scene = ref;
                }
            } else if ("object".equals(role)) {
                assets.add(ref);
            } else {
                characters.add(ref);
            }
        }
        return new BareRefsView(characters, assets, scene);
    }

    /** 裸参考槽位表归一化后的分类视图（图片号已在各 {@link VideoReference.Ref} 上带好） */
    private record BareRefsView(List<VideoReference.Ref> characters, List<VideoReference.Ref> assets,
                                VideoReference.Ref scene) {
    }

    private static Integer findAssetSubjectNum(List<AssetPicRef> refs, int baseSubjectIdx, String assetId) {
        if (StrUtil.isBlank(assetId)) {
            return null;
        }
        for (int i = 0; i < refs.size(); i++) {
            if (assetId.equals(refs.get(i).assetId)) {
                return baseSubjectIdx + i;
            }
        }
        return null;
    }

    private static Integer findSubjectNum(List<CharPicRef> refs, String charId) {
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).charId.equals(charId)) {
                return refs.get(i).subjectNum;
            }
        }
        return null;
    }

    /**
     * 把散文里的 {@code character_id} 换成 {@code <Subject k>} 指代。
     * <p>
     * ⚠️ 用的是 {@link CharPicRef#subjectNum} 而<b>不是</b> {@code picNum}：分类链路下二者相等，
     * 但裸参考槽位表下图片号 = 槽位号（可跳号、可与角色序号错位），拿图片号当 subject 号会指错人。
     */
    private static String resolveSubjectRefs(String text, List<CharPicRef> charPicRefs) {
        if (StrUtil.isBlank(text) || CollUtil.isEmpty(charPicRefs)) {
            return text;
        }
        String result = text;
        List<CharPicRef> sorted = new ArrayList<>(charPicRefs);
        sorted.sort(Comparator.comparingInt((CharPicRef r) -> r.charId.length()).reversed());
        for (CharPicRef ref : sorted) {
            result = result.replace(ref.charId, "<Subject " + ref.subjectNum + ">");
        }
        return result;
    }

    private static Set<String> collectCharIds(List<ShotVisualPlan.BeatCharacter> characters) {
        Set<String> ids = new HashSet<>();
        if (CollUtil.isEmpty(characters)) return ids;
        for (ShotVisualPlan.BeatCharacter ch : characters) {
            if (ch != null && StrUtil.isNotBlank(ch.getCharacterId())) {
                ids.add(ch.getCharacterId());
            }
        }
        return ids;
    }

    private static List<DialogueLine> consumeShotDialogue(
            List<Map<String, Object>> pool,
            MotionSeg seg,
            Map<String, Integer> audioIndexByChar,
            List<String> audioOnlyIds
    ) {
        List<DialogueLine> matched = new ArrayList<>();
        if (seg.hasDialogueField) {
            for (DialogueRef ref : seg.dialogue) {
                Map<String, Object> found = findSegById(pool, ref.id());
                if (found == null) {
                    log.warn("[MINIMAX_H3] motion dialogue ref id[{}] not found in shot scripts, skip", ref.id());
                    continue;
                }
                String type = StrUtil.trim(String.valueOf(found.getOrDefault("type", "")));
                if (isVisualScriptType(type) && !isSystemType(type)) {
                    continue;
                }
                boolean onScreen = ref.onScreen() && !isSystemType(type);
                Map<String, Object> voiced = sliceSpokenPart(pool, found, ref.id(), ref.speak());
                matched.add(new DialogueLine(voiced, onScreen, ref.delivery()));
            }
            return matched;
        }

        Set<String> shotCharIds = collectCharIds(seg.characters);
        Iterator<Map<String, Object>> it = pool.iterator();
        while (it.hasNext()) {
            Map<String, Object> segMap = it.next();
            String type = StrUtil.trim(String.valueOf(segMap.getOrDefault("type", "")));

            if (isSystemType(type)) {
                String speaker = resolveSystemSpeaker(segMap, audioOnlyIds);
                if (StrUtil.isBlank(speaker)) {
                    continue;
                }
                matched.add(new DialogueLine(segMap, false, null));
                it.remove();
                continue;
            }

            if (isVisualScriptType(type)) {
                continue;
            }
            String speaker = StrUtil.trim(String.valueOf(segMap.getOrDefault("speaker", "")));
            if (StrUtil.isNotBlank(speaker) && shotCharIds.contains(speaker)) {
                matched.add(new DialogueLine(segMap, true, null));
                it.remove();
            }
        }
        return matched;
    }

    private static Map<String, Object> sliceSpokenPart(
            List<Map<String, Object>> pool,
            Map<String, Object> poolSeg,
            String id,
            String speak
    ) {
        String full = StrUtil.trim(String.valueOf(poolSeg.getOrDefault("text", "")));
        if (StrUtil.isBlank(speak) || StrUtil.isBlank(full)) {
            pool.remove(poolSeg);
            return poolSeg;
        }
        int at = full.indexOf(speak);
        if (at < 0) {
            log.warn("[MINIMAX_H3] dialogue id[{}] speak slice not found in script text, fallback to whole line: {}",
                    id, speak);
            pool.remove(poolSeg);
            return poolSeg;
        }
        Map<String, Object> voiced = new LinkedHashMap<>(poolSeg);
        voiced.put("text", speak);
        String remain = StrUtil.trim(full.substring(0, at) + full.substring(at + speak.length()));
        if (StrUtil.isBlank(remain)) {
            pool.remove(poolSeg);
        } else {
            poolSeg.put("text", remain);
        }
        return voiced;
    }

    private static Map<String, Object> findSegById(List<Map<String, Object>> pool, String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        for (Map<String, Object> seg : pool) {
            Object v = seg.get("id");
            if (v != null && id.equals(String.valueOf(v))) {
                return seg;
            }
        }
        return null;
    }

    private static List<DialogueLine> toDialogueLines(List<Map<String, Object>> textSegments, List<CharPicRef> charPicRefs) {
        List<DialogueLine> out = new ArrayList<>();
        if (textSegments == null) {
            return out;
        }
        for (Map<String, Object> seg : textSegments) {
            String type = StrUtil.trim(String.valueOf(seg.getOrDefault("type", "")));
            String speaker = StrUtil.trim(String.valueOf(seg.getOrDefault("speaker", "")));
            boolean onScreen = !isSystemType(type)
                    && StrUtil.isNotBlank(speaker)
                    && findSubjectNum(charPicRefs, speaker) != null;
            out.add(new DialogueLine(seg, onScreen, null));
        }
        return out;
    }

    private static boolean hasSpeakableDialogue(List<Map<String, Object>> textSegments) {
        if (CollUtil.isEmpty(textSegments)) {
            return false;
        }
        for (Map<String, Object> seg : textSegments) {
            String type = StrUtil.trim(String.valueOf(seg.getOrDefault("type", "")));
            String text = StrUtil.trim(String.valueOf(seg.getOrDefault("text", "")));
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (isSystemType(type) || !isVisualScriptType(type)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> buildAudioIndexByChar(List<String> audioCharacterIds) {
        Map<String, Integer> audioIndexByChar = new LinkedHashMap<>();
        if (CollUtil.isEmpty(audioCharacterIds)) {
            return audioIndexByChar;
        }
        for (int i = 0; i < audioCharacterIds.size(); i++) {
            audioIndexByChar.put(audioCharacterIds.get(i), i + 1);
        }
        return audioIndexByChar;
    }

    private static List<String> buildAudioOnlyIds(
            Map<String, Integer> audioIndexByChar,
            List<CharPicRef> charPicRefs
    ) {
        if (CollUtil.isEmpty(audioIndexByChar)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String charId : audioIndexByChar.keySet()) {
            if (findSubjectNum(charPicRefs, charId) == null) {
                ids.add(charId);
            }
        }
        return ids;
    }

    private static boolean isSystemType(String type) {
        return "system".equals(type);
    }

    private static boolean isVisualScriptType(String type) {
        return !"dialogue".equals(type) && !"thought".equals(type) && !"narration".equals(type);
    }

    private static String resolveSystemSpeaker(Map<String, Object> seg, List<String> audioOnlyIds) {
        String speaker = StrUtil.trim(String.valueOf(seg.getOrDefault("speaker", "")));
        if (StrUtil.isNotBlank(speaker)) {
            return speaker;
        }
        if (CollUtil.isEmpty(audioOnlyIds)) {
            return null;
        }
        return audioOnlyIds.getFirst();
    }

    private static Map<String, String> buildSpeakerSxMap(
            List<Map<String, Object>> textSegments,
            List<CharPicRef> charPicRefs,
            Map<String, Integer> audioIndexByChar,
            List<String> audioOnlyIds
    ) {
        Map<String, String> speakerSxMap = new LinkedHashMap<>();
        if (CollUtil.isEmpty(textSegments)) {
            return speakerSxMap;
        }

        Set<String> onScreenSpeakers = new LinkedHashSet<>();
        Set<String> offScreenSpeakers = new LinkedHashSet<>();
        for (Map<String, Object> seg : textSegments) {
            String type = StrUtil.trim(String.valueOf(seg.getOrDefault("type", "")));
            String speaker = StrUtil.trim(String.valueOf(seg.getOrDefault("speaker", "")));
            String text = StrUtil.trim(String.valueOf(seg.getOrDefault("text", "")));

            if (StrUtil.isBlank(text)) {
                continue;
            }

            if (isSystemType(type)) {
                speaker = resolveSystemSpeaker(seg, audioOnlyIds);
                if (StrUtil.isBlank(speaker)) {
                    continue;
                }
                offScreenSpeakers.add(speaker);
            } else {
                if (isVisualScriptType(type) || StrUtil.isBlank(speaker)) {
                    continue;
                }
                if (findSubjectNum(charPicRefs, speaker) != null) {
                    onScreenSpeakers.add(speaker);
                } else if (audioIndexByChar != null && audioIndexByChar.containsKey(speaker)) {
                    offScreenSpeakers.add(speaker);
                }
            }
        }

        int maxSx = 0;
        for (CharPicRef ref : charPicRefs) {
            if (!onScreenSpeakers.contains(ref.charId)) {
                continue;
            }
            int sx = findSubjectNum(charPicRefs, ref.charId);
            speakerSxMap.put(ref.charId, "(S" + sx + ")");
            maxSx = Math.max(maxSx, sx);
        }
        int nextSx = maxSx + 1;
        for (String speaker : offScreenSpeakers) {
            if (speakerSxMap.containsKey(speaker)) {
                continue;
            }
            speakerSxMap.put(speaker, "(S" + nextSx++ + ")");
        }
        return speakerSxMap;
    }

    private static void appendAudioSummary(
            StringBuilder sb,
            Map<String, Integer> audioIndexByChar,
            Map<String, String> speakerSxMap,
            List<CharPicRef> charPicRefs
    ) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : audioIndexByChar.entrySet()) {
            String charId = entry.getKey();
            Integer subjNum = findSubjectNum(charPicRefs, charId);
            String sx = speakerSxMap.get(charId);
            if (sx == null) {
                continue;
            }
            if (subjNum != null) {
                parts.add("<Audio " + entry.getValue() + "> as the voice-timbre reference for <Subject "
                        + subjNum + ">" + sx);
            } else {
                parts.add("<Audio " + entry.getValue() + "> as the voice-timbre reference for the off-screen voice "
                        + sx);
            }
        }
        sb.append(String.join(" and ", parts));
    }

    private static String buildDialogueLines(
            List<DialogueLine> lines,
            List<CharPicRef> charPicRefs,
            Map<String, String> speakerSxMap,
            Map<String, Integer> audioIndexByChar,
            List<String> audioOnlyIds
    ) {
        StringBuilder sb = new StringBuilder();

        for (DialogueLine dl : lines) {
            Map<String, Object> seg = dl.seg();
            String type = StrUtil.trim(String.valueOf(seg.getOrDefault("type", "")));
            String speaker = StrUtil.trim(String.valueOf(seg.getOrDefault("speaker", "")));
            String text = StrUtil.trim(String.valueOf(seg.getOrDefault("text", "")));
            String delivery = StrUtil.isBlank(dl.delivery()) ? "" : StrUtil.trim(dl.delivery());

            if (StrUtil.isBlank(text)) continue;

            boolean isThought = "thought".equals(type);
            String verb = switch (type) {
                case "narration" -> "narrates";
                default -> "says";
            };
            String head = StrUtil.isNotBlank(delivery) ? delivery : verb;

            if (isSystemType(type)) {
                speaker = resolveSystemSpeaker(seg, audioOnlyIds);
                if (StrUtil.isBlank(speaker)) {
                    continue;
                }
                String sx = speakerSxMap.get(speaker);
                if (sx == null) {
                    continue;
                }
                Integer audioNum = audioIndexByChar == null ? null : audioIndexByChar.get(speaker);
                String lang = detectLanguage(text);
                sb.append("The off-screen voice ").append(sx);
                if (audioNum != null) {
                    sb.append(" using the voice timbre referenced from <Audio ").append(audioNum).append(">");
                }
                sb.append(" ").append(head).append(" in an off-screen voiceover, <d>[").append(lang).append("] ")
                        .append(text).append("</d>\n");
                continue;
            }

            if (isVisualScriptType(type)) {
                continue;
            }
            if (StrUtil.isBlank(speaker)) {
                // 说话人缺失 = 这条台词渲染不出来（没有 <Subject k> / <Audio n> 可挂）。曾经**静默**丢弃：
                // 裸参考入口的「无图角色」（只有音色参考、没有参考图）因上游把 speaker 留空而整条消失，
                // 成稿里只表现为「台词凭空没了」。上游已在 buildTextSegments 侧兜底，这里是最后一道可见性。
                log.warn("[MINIMAX_H3] dialogue dropped: no speaker resolved (text={})",
                        text.length() > 40 ? text.substring(0, 40) + "…" : text);
                continue;
            }

            Integer subjNum = findSubjectNum(charPicRefs, speaker);
            if (subjNum == null) {
                if (dl.onScreen()) {
                    log.warn("[MINIMAX_H3] speaker[{}] declared on_screen but has no subject, downgrade to off-screen", speaker);
                }
            }
            String sx = speakerSxMap.get(speaker);
            if (sx == null) continue;

            String lang = detectLanguage(text);

            if (subjNum != null && dl.onScreen() && !isThought) {
                sb.append("<Subject ").append(subjNum).append("> ").append(sx);
                Integer audioNum = null;
                if (audioIndexByChar != null && (audioNum = audioIndexByChar.get(speaker)) != null) {
                    sb.append(" using the voice timbre referenced from <Audio ").append(audioNum).append(">");
                }
                sb.append(" ").append(head).append(", <d>[").append(lang).append("] ")
                        .append(text).append("</d>\n");
            } else {
                if (isThought && subjNum != null) {
                    sb.append("<Subject ").append(subjNum).append("> ").append(sx);
                } else if (subjNum != null) {
                    sb.append("The off-screen voice of <Subject ").append(subjNum).append("> ").append(sx);
                } else {
                    sb.append("The off-screen voice ").append(sx);
                }
                Integer audioNum = null;
                if (audioIndexByChar != null && (audioNum = audioIndexByChar.get(speaker)) != null) {
                    sb.append(" using the voice timbre referenced from <Audio ").append(audioNum).append(">");
                }
                sb.append(" ").append(head);
                if (isThought) {
                    sb.append(" in an off-screen voiceover (inner monologue), <d>[").append(lang).append("] ")
                            .append(text).append("</d> while the character's lips remain completely closed");
                } else {
                    sb.append(" in an off-screen voiceover, <d>[").append(lang).append("] ")
                            .append(text).append("</d>");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String detectLanguage(String text) {
        if (StrUtil.isBlank(text)) return "English";
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

    private static String generateSoundscape(String sceneDesc) {
        if (StrUtil.isBlank(sceneDesc))
            return "Very soft ambient atmosphere at a low, background volume throughout the video.";
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
        return "Very soft ambient atmosphere at a low, background volume throughout the video.";
    }

    /**
     * 解析 referenceOnly 开关值，优先级：入参 options（单次请求覆盖） → adapter.json extra → 默认开启。
     */
    private static boolean resolveReferenceOnly(MiniMaxH3Adapter adapter, VideoContext videoContext) {
        Object raw = null;
        if (videoContext != null) {
            for (String key : REFERENCE_ONLY_KEYS) {
                raw = videoContext.option(key);
                if (raw != null) break;
            }
        }
        if (raw == null) {
            for (String key : REFERENCE_ONLY_KEYS) {
                raw = adapter.getMetaExtra(key);
                if (raw != null) break;
            }
        }
        return raw == null ? DEFAULT_REFERENCE_ONLY : toBool(raw);
    }

    /**
     * 对象转布尔，支持 Boolean / 字符串开关值
     */
    private static boolean toBool(Object raw) {
        if (raw instanceof Boolean b) return b;
        String v = StrUtil.trim(String.valueOf(raw));
        return !("false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)
                || "no".equalsIgnoreCase(v) || "0".equals(v)
                || "disable".equalsIgnoreCase(v) || "disabled".equalsIgnoreCase(v));
    }
}
