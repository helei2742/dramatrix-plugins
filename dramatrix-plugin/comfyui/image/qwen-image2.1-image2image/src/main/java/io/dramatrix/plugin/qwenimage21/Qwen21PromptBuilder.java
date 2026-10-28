package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.PluginDraftView;
import io.dramatrix.plugin.PluginJsonUtil;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qwen Image 2.1 图生图的<b>首帧 prompt 拼接</b>：从 LLM 结构化草稿组装成一段<b>两段式中文编辑指令</b>
 * （不是 JSON）。参考图按官方约定写成 {@code <image1>}…（{@code <image1>} = 场景底板，其后依次 =
 * 出镜角色、入画道具），只指向参考图、不复述外貌，其余内容以一句总括保持子句兜住。
 *
 * <p>槽位与工作流接线严格同序，且两侧共用 {@link ReferenceSlotPlanner}（唯一真源）。</p>
 *
 * <p>纯静态、无状态：入参 {@link Image2ImagePromptRequest} 自足，可脱离适配器单独复用与测试。</p>
 */
final class Qwen21PromptBuilder {

    /** 序数词：无参考图角色的稳定指代（上限 {@link ReferenceSlotPlanner#MAX_REFERENCE_IMAGES} 个主体，够用）。 */
    private static final String[] ORDINALS = {
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"
    };

    /**
     * 分镜机位词表 → 中文落地写法（**机械映射，不是语义判断**，符合「Java 只组装」铁律）。
     * <p>镜像 {@code ChapterEventShotSchema.Camera} 的注释词表（shot_type 9 值 / angle 4 值），
     * 改词表请与 shot-gen、cutshot 两处 prompt 一同同步。</p>
     */
    private static final Map<String, String> SHOT_TYPE_CN = Map.ofEntries(
            Map.entry("close", "近景"),
            Map.entry("extreme close", "大特写"),
            Map.entry("medium", "中景"),
            Map.entry("wide", "全景"),
            Map.entry("over-shoulder", "过肩视角"),
            Map.entry("pov", "第一人称主观视角"),
            Map.entry("top", "高角度俯拍"),
            Map.entry("low", "低角度仰拍"),
            Map.entry("fisheye", "鱼眼视角")
    );

    /** 分镜 {@code angle} 词表 → 中文（4 值，见 {@link #SHOT_TYPE_CN} 说明）。 */
    private static final Map<String, String> ANGLE_CN = Map.of(
            "eye_level", "平视",
            "low_angle", "低角度",
            "high_angle", "高角度",
            "dutch_angle", "倾斜构图"
    );

    /** 身份保持子句：指向参考图，不复述外貌（复述会让模型重绘脸与服装）。 */
    private static final String IDENTITY_KEEP_CLAUSE = "保持各参考图中人物的面部特征、发型、体型与服装不变";

    /**
     * 收尾构图 + 视线约束（恒为最后一条，位置最新鲜、对分布影响最大）。
     * <p>AI 默认把人物摆成正面对镜头的海报站位并直视观众（"看镜头"），与影视分镜的观感相悖：
     * 分镜帧里人物的视线应落在画面内的对象上。POV / 对镜独白是唯一例外（那时"看镜头"正是要求）。</p>
     */
    private static final String ANTI_HERO_CLAUSE =
            "不要排成海报式队列或对称正面站桩，不要让人物直视镜头"
                    + "（本镜是第一人称主观视角或对镜独白时除外），"
                    + "人物视线落在画面内的人或物上，构图保持不对称、有前后景层次";

    private Qwen21PromptBuilder() {
    }

    /**
     * 从 LLM 结构化草稿组装 Qwen Image 2.1 的最终 prompt —— <b>两段式中文编辑指令</b>（非 JSON）：
     * <pre>
     * 主体定义：
     * &lt;image1&gt;（场景参考图）：环境底板，只取用其环境结构、空间关系、材质与光线基调；…
     * &lt;image2&gt;（角色参考图）中的人物，处于奔跑状态，身体前倾、双臂摆动向前冲
     * &lt;image3&gt;（道具参考图）中的物件，停在半空，尚未放下
     * 详细描述：
     * 底板只取上述场景参考图的环境…；&lt;image2&gt;中的人物位于右前景，朝向&lt;image3&gt;中的物件；取景：…。
     * 光线：…
     * 画面风格：…
     * 保持参考图中人物的面部特征不变；画面中只出现上述人物…；不要排成海报式队列…
     * </pre>
     * <p>口径参考 MiniMax「全能参考模式」的 {@code subject_definitions} + {@code detailed_description}
     * 两段（其六段式里与单帧图片最相关的两段，落到本插件时标签中文化为 {@code 主体定义} / {@code 详细描述}）：<b>第一段一行一个主体、只答「是谁 + 此刻在做什么」，
     * 第二段主行写空间与镜头、光线与风格各自独立成行</b>。这样"谁是谁"只在第一段由槽位标签定义一次，
     * 第二段纯消费指代，不会因为同一主体被反复描述而被模型画成好几份。</p>
     *
     * <p>组装口径（对应 Qwen 官方 prompt_rewrite 的 edit 规范）：</p>
     * <ol>
     *   <li><b>画布锚定在前</b>：场景参考图作为 {@code 主体定义} 首行，声明只取环境结构/材质/光线基调；</li>
     *   <li><b>身份靠指向、不靠文字</b>：出镜角色与入画道具按顺序指向 {@code <image2>}…（官方要求多图输入用
     *       {@code <image1>}… 槽位标签），外貌不复述；无参考图的（临时角色（插件未纳入时 {@code temp_char_*}）、{@code __temp_asset__:}、
     *       群体、资产图未就绪）才用 appearance 文字兜底，且<b>只在定义行写一次</b>，跨条目的指代一律写对方 id，
     *       由 {@link #resolveRefs} 确定性换成槽位或稳定序数（见 {@link #frameOrdinalLabel}），
     *       让画面里「谁是谁 / 哪个是哪个」只剩一个通道（有参考图就一个字外貌都不写，
     *       否则等同于"再画一个"的生成指令）；</li>
     *   <li><b>只描述需要变的部分</b>：身体状态/动作进定义行，站位/朝向/镜头（含相机物理位置与构图）/光线/风格/道具/preserve
     *       进描述行，其余内容用一句总括保持子句兜住（官方：一处 blanket preservation clause 优于逐点复述）；</li>
     *   <li><b>反英雄帧兜底</b>：描述行结尾无条件追加「不排成海报式队列 / 不正面站桩 / 构图上不对称」子句，
     *       避免单人帧被画成正面对镜头的英雄照（与 prompt.md 规则 1 同一口径）；</li>
     *   <li><b>不写分辨率与宽高比</b>——尺寸由管线（width/height 绑定）决定，写进 prompt 只会互相打架。</li>
     * </ol>
     *
     * <p>槽位与 {@code Qwen21WorkflowBuilder.build} 的接线<b>严格同序</b>，且两侧共用
     * {@link ReferenceSlotPlanner}（唯一真源）：{@code <image1>} = 场景图（有场景时），其后依次是角色
     * （按 {@code characterIds} 顺序）与正式资产（按 LLM {@code assets[]} 顺序），仅给「确实会挂上参考图」的
     * id 编号（跳过后缀 {@code __temp_asset__:}、默认未纳入的临时角色、无图 id、超出
     * {@link ReferenceSlotPlanner#MAX_REFERENCE_IMAGES} 的部分）；全画面只有 1 张参考图时按官方约定不用
     * 槽位标签，改用自然语言指代（{@code N=1} 不写标签）。</p>
     *
     * @return 可直接作为 {@code TextEncodeQwenImage21.prompt} 的两段式指令；LLM 输出不可解析时返回 {@code null}
     */
    static String build(Image2ImagePromptRequest request, boolean includeTempChar) {
        if (request == null) {
            return null;
        }
        // 唯一真源 = 本镜蓝图；草稿视图由 PluginDraftView 统一映射（图片侧不再有自己的 LLM 草稿）
        JSONObject json = PluginDraftView.firstFrame(request.getVisualPlan());
        if (json == null || json.isEmpty()) {
            return null;
        }

        // 1. characters[] → id → 原始条目（action / position / appearance）
        Map<String, JSONObject> charsById = new LinkedHashMap<>();
        JSONArray chars = json.getJSONArray("characters");
        if (CollUtil.isNotEmpty(chars)) {
            for (Object o : chars) {
                if (!(o instanceof JSONObject c)) {
                    continue;
                }
                String id = c.getStr("id");
                if (StrUtil.isNotBlank(id)) {
                    charsById.putIfAbsent(id, c);
                }
            }
        }

        // 2. 出镜顺序：优先用管线给的 characterIds（= frameCharacterIds ∩ characters，即真正会接线的顺序）
        List<String> frameIds = new ArrayList<>();
        if (CollUtil.isNotEmpty(request.getCharacterIds())) {
            frameIds.addAll(request.getCharacterIds());
        } else {
            JSONArray frame = json.getJSONArray("frameCharacterIds");
            if (CollUtil.isNotEmpty(frame)) {
                for (Object o : frame) {
                    String id = o == null ? null : o.toString();
                    if (StrUtil.isNotBlank(id) && charsById.containsKey(id) && !frameIds.contains(id)) {
                        frameIds.add(id);
                    }
                }
            }
            if (frameIds.isEmpty()) {
                frameIds.addAll(charsById.keySet());
            }
        }

        // 2.1 资产顺序：取 LLM assets[] 的 id 顺序 —— 与管线传给插件的 assetIds 同源同序
        //     （管线也是从 LLM assets[] 里取 id），这是资产槽位不错位的前提。
        List<String> assetIds = new ArrayList<>();
        Map<String, JSONObject> assetsById = new LinkedHashMap<>();
        JSONArray assetArray = json.getJSONArray("assets");
        if (CollUtil.isNotEmpty(assetArray)) {
            for (Object o : assetArray) {
                if (!(o instanceof JSONObject a)) {
                    continue;
                }
                String id = a.getStr("id");
                if (StrUtil.isNotBlank(id) && !assetIds.contains(id)) {
                    assetIds.add(id);
                    assetsById.put(id, a);
                }
            }
        }

        // 3. 参考图槽位：与工作流接线<b>同序</b> —— 场景占 1，随后角色、再资产。
        //    两侧共用 ReferenceSlotPlanner（唯一真源），任何一处顺序/过滤不一致都会让 <imageN> 指到别的图。
        Map<String, String> charImageRefs = request.getCharacterImageRefs() == null
                ? Map.of() : request.getCharacterImageRefs();
        Map<String, String> assetImageRefs = request.getAssetImageRefs() == null
                ? Map.of() : request.getAssetImageRefs();
        boolean hasScene = StrUtil.isNotBlank(request.getScenePrompt());
        Map<String, Integer> slotById = ReferenceSlotPlanner.plan(
                hasScene, includeTempChar,
                frameIds, charImageRefs::containsKey,
                assetIds, assetImageRefs::containsKey
        );
        // 拆回角色/资产两张表：角色子句、道具子句、开场白、resolveRefs 都需要区分"是人还是物"
        Map<String, Integer> charSlotById = new LinkedHashMap<>();
        Map<String, Integer> assetSlotById = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : slotById.entrySet()) {
            if (assetsById.containsKey(e.getKey())) {
                assetSlotById.put(e.getKey(), e.getValue());
            } else {
                charSlotById.put(e.getKey(), e.getValue());
            }
        }
        // 官方约定：只有 1 张输入图时不写 <imageN> 标签，改用自然语言指代
        boolean tagged = slotById.size() + (hasScene ? 1 : 0) >= 2;

        // 4. 逐项组装子句：MiniMax 六段式的两段落笔
        //    ① 主体定义：一行一个主体 —— 「是谁（槽位/稳定序数）+ 此刻在做什么」；
        //    ② 详细描述：只写空间与镜头 —— 位置、朝向、取景、光线、约束。
        //    所有子句统一过一层「id → 参考图槽位」指代替换：画面里"谁是谁/哪个是哪个"只允许这一个通道，
        //    避免把外貌文字写进 prompt 被模型读成"再画一个这样的"的生成指令（凭空多人的主因）。
        List<String> clauses = new ArrayList<>();   // 详细描述段·主行（底板口径 / 各主体位置朝向 / 身份保持 / 道具 / 取景）
        List<String> subjectDefs = new ArrayList<>();   // 主体定义段
        List<String> midLines = new ArrayList<>();      // 详细描述段·独立成行的全局属性（光线 / 风格）
        List<String> tailClauses = new ArrayList<>();   // 详细描述段·收尾约束行（保留 / 排他 / 反英雄帧）
        RefContext refCtx = new RefContext(
                charSlotById, assetSlotById, tagged, frameIds,
                charsById, assetsById,
                request.getCharAppearancePrompts(), request.getAssetAppearancePrompts());
        UnaryOperator<String> refs = s -> resolveRefs(s, refCtx);

        // 4.1 场景 → 主体定义 首行；底板口径作为 详细描述 的开场
        String scene = StrUtil.blankToDefault(clean(json.getStr("scene")), clean(request.getScenePrompt()));
        if (hasScene) {
            subjectDefs.add(refs.apply(refTag(tagged, 1, "场景参考图")
                    + "：环境底板，只取用其环境结构、空间关系、材质与光线基调"
                    + (StrUtil.isNotBlank(scene) ? "；" + scene : "")));
            clauses.add("底板只取上述场景参考图的环境，不照搬其机位与构图（本帧机位、景别与构图按下方取景重设）；"
                    + "画面中出现的人物只来自角色参考图，道具只来自道具参考图");
        } else {
            if (StrUtil.isNotBlank(scene)) {
                subjectDefs.add(refs.apply("场景：" + scene));
            }
            clauses.add("无场景底板，环境按剧情新建");
        }

        // 4.2 出镜角色：定义行进 主体定义（谁 + 在做什么），位置/朝向进 详细描述
        //      position = 模型产出的「在场景哪 + 在画面哪 + 朝哪」，**原样拼**（Java 不解析），
        //      其中的 id 由下面的 refs 换成槽位
        boolean hasRefChar = false;
        for (String id : frameIds) {
            JSONObject c = charsById.get(id);
            if (c == null) {
                continue;
            }
            String action = clean(c.getStr("action"));
            String posture = clean(c.getStr("posture"));
            String position = clean(c.getStr("position"));
            boolean group = "group".equals(c.getStr("type"));
            Integer slot = charSlotById.get(id);

            // 定义行用「槽位 + 角色」全称；描述行用短指代（与 resolveRefs 的换法一致），同一主体只有一种写法
            String defLabel;
            if (slot != null) {
                hasRefChar = true;
                // 有参考图 → 身份完全靠指向参考图；群体条目例外（它自带整体外貌名词短语，且没有单张参考图）
                String subject = group
                        ? StrUtil.blankToDefault(clean(c.getStr("appearance")), "人群")
                        : "人物";
                defLabel = refTag(tagged, slot, "角色参考图") + "中的" + subject;
            } else {
                // 无参考图（temp_char_* / 群众）→ 外貌只在这里写一次，之后一律用稳定序数指代
                Map<String, String> charAppearances = refCtx.charAppearances();
                String appear = StrUtil.blankToDefault(
                        clean(c.getStr("appearance")),
                        charAppearances == null ? "" : clean(charAppearances.get(id)));
                if (StrUtil.isBlank(appear)) {
                    appear = clean(c.getStr("distinct"));
                }
                String ordinal = resolveRefSubject(id, refCtx);
                defLabel = StrUtil.isNotBlank(appear) ? ordinal + "（" + appear + "）" : ordinal;
            }
            // 身体状态 → 动作：顺序固定，缺项跳过（prompt.md 要求必填）
            StringBuilder def = new StringBuilder(defLabel);
            if (StrUtil.isNotBlank(posture)) {
                def.append("，处于").append(posture).append("状态");
            }
            if (StrUtil.isNotBlank(action)) {
                def.append("，").append(action);
            }
            subjectDefs.add(refs.apply(def.toString()));

            // 描述行：位置 + 朝向（不再复述动作状态）
            StringBuilder desc = new StringBuilder(resolveRefSubject(id, refCtx));
            if (StrUtil.isNotBlank(position)) {
                desc.append("，").append(position);
            }
            clauses.add(refs.apply(desc.toString()));
        }

        // 4.3 身份保持（一句总括，避免点状复述引发漂移）
        if (hasRefChar) {
            clauses.add(IDENTITY_KEEP_CLAUSE);
        }

        // 4.4 道具：主体定义进 主体定义，摆位进 详细描述
        //     有参考图槽位 → 身份指向 <imageN>（道具参考图），一个字外貌都不写；
        //     无槽位（临时资产 / 资产图未就绪 / 超上限）→ 退化为文字描述
        appendAssets(subjectDefs, clauses, assetIds, assetsById, refCtx, refs);

        // 4.5 镜头 / 光线 / 风格 / preserve
        //     光线与画面风格是全局属性，独立成行，不混进主体的位置叙述里
        String camera = buildCameraText(json.getJSONObject("camera"), request.getCameraPrompt());
        if (StrUtil.isNotBlank(camera)) {
            clauses.add(refs.apply("取景：" + camera));
        }
        String lighting = StrUtil.blankToDefault(clean(request.getLighting()), clean(json.getStr("lighting")));
        if (StrUtil.isNotBlank(lighting)) {
            midLines.add("光线：" + lighting + "。");
        }
        if (StrUtil.isNotBlank(request.getFixPrompt())) {
            midLines.add("画面风格：" + clean(request.getFixPrompt()) + "。");
        }
        String preserve = clean(json.getStr("preserve"));
        if (StrUtil.isNotBlank(preserve)) {
            // preserve 本身就写成祈使句（"保留案面上的残烛…"），不再加前缀，避免"保留：保留…"
            tailClauses.add(refs.apply(preserve));
        }

        // 4.6 排他 + 反英雄帧子句：本帧只放点过名的人物与道具，且不许排成海报式正面队列
        StringBuilder exclusivity = new StringBuilder(frameIds.isEmpty()
                ? "画面中不出现任何人物或人群"
                : "画面中只出现上述人物，不出现额外人物或人群");
        if (!assetIds.isEmpty()) {
            exclusivity.append("，道具也只出现上述那些，不凭空多出物件");
        }
        tailClauses.add(exclusivity.toString());
        tailClauses.add(ANTI_HERO_CLAUSE);

        // 5. 两段式组装（MiniMax 六段式中图片参考最相关的两段，标签用中文）
        //    主体定义：一行一主体（谁 + 在做什么）；
        //    详细描述：主行只写空间与镜头，光线/风格各自独立成行，收尾一行放保持与排他约束。
        //    这样"哪个是哪个"只由第一段的槽位标签定义，第二段只消费指代，不会把外貌复述成"再画一个"。
        StringBuilder out = new StringBuilder("主体定义：\n");
        for (String s : subjectDefs) {
            out.append(s).append('\n');
        }
        out.append("详细描述：\n")
                .append(String.join("；", clauses)).append("。");
        for (String line : midLines) {
            out.append('\n').append(line);
        }
        if (!tailClauses.isEmpty()) {
            out.append('\n').append(String.join("；", tailClauses)).append("。");
        }
        return out.toString();
    }

    /** 参考图指代：多图输入用官方槽位标签 {@code <imageN>（角色）}；单图输入用自然语言指代。 */
    private static String refTag(boolean tagged, int slot, String role) {
        return tagged ? "<image" + slot + ">" + "（" + role + "）" : role;
    }

    /**
     * {@link #resolveRefs} 的查表上下文：槽位表 + 各类"没有槽位时"的兜底文字来源。
     *
     * @param charSlots         角色 id → 槽位号（只含真有参考图的角色）
     * @param assetSlots        资产 id → 槽位号（只含真有参考图的正式资产）
     * @param tagged            是否使用 {@code <imageN>} 槽位标签（全画面只有 1 张输入图时为 false）
     * @param frameCharIds      本帧出镜的角色 id（决定"未出镜"判定）
     * @param charsById         characters[] 原始条目
     * @param assetsById        assets[] 原始条目
     * @param charAppearances   角色 id → 管线给的 DB 外貌文字
     * @param assetAppearances  资产 id → 管线给的 DB 外貌文字
     */
    private record RefContext(
            Map<String, Integer> charSlots,
            Map<String, Integer> assetSlots,
            boolean tagged,
            List<String> frameCharIds,
            Map<String, JSONObject> charsById,
            Map<String, JSONObject> assetsById,
            Map<String, String> charAppearances,
            Map<String, String> assetAppearances
    ) {
    }

    /**
     * 把文本里出现的 id 逐字换成稳定的画面指代：
     * <ul>
     *   <li>有参考图的角色 → {@code <imageN>中的人物}（群体为"中的人群"；单图输入时"参考图中的人物"）；</li>
     *   <li>有参考图的资产 → {@code <imageN>中的物件}（单图输入时"道具参考图中的物件"）；</li>
     *   <li>无参考图的出镜角色 → "画面中第 N 个人物"（稳定序数，外貌只在 {@code 主体定义} 写一次）；</li>
     *   <li>无槽位的资产 → 其外貌文字（此时文字是唯一来源）；</li>
     *   <li>未出镜的角色 → "画面外的人物"（不许把未入画者描述出来，否则会凭空生成）。</li>
     * </ul>
     *
     * <p><b>为什么必须换</b>：id 是机械 token，槽位标签才是真正被接线的指代通道。放自由文本外貌进去，
     * 模型会把它当成"再画一个这样的"的生成指令 —— 多角色/多道具帧凭空多出来的主因。长 id 先替换，
     * 避免 {@code char_a} 命中 {@code char_ab} 的前缀。</p>
     */
    private static String resolveRefs(String text, RefContext ctx) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        // id 池 = 有槽位的 ∪ 声明的（含"声明的但没槽位"→ 走文字兜底）∪ frameCharacterIds
        List<String> ids = new ArrayList<>(ctx.charSlots().keySet());
        ids.addAll(ctx.assetSlots().keySet());
        for (String id : ctx.charsById().keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        for (String id : ctx.assetsById().keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        // frameCharacterIds[] 里可能列出 characters[] 未声明的 id：它们也是 LLM 明确点过名的出镜角色，
        // 必须进池（否则被别人的 position/action 引用时替换不掉，prompt 里会漏一个裸 id）
        for (String id : ctx.frameCharIds()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        ids.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String out = text;
        for (String id : ids) {
            if (StrUtil.isBlank(id) || !out.contains(id)) {
                continue;
            }
            String subject = resolveRefSubject(id, ctx);
            // 连同 id 两侧的空白一起吃掉：中文不需要空格，否则会读出
            // 「扫向 <image3>中的人物 身后」这种被割断的停顿
            out = out.replaceAll("\\s*" + Pattern.quote(id) + "\\s*", Matcher.quoteReplacement(subject));
        }
        return out;
    }

    /** 单个 id 的画面指代（槽位优先 → 稳定序数 → 未出镜兜底）。 */
    private static String resolveRefSubject(String id, RefContext ctx) {
        Integer charSlot = ctx.charSlots().get(id);
        if (charSlot != null) {
            JSONObject c = ctx.charsById().get(id);
            String role = c != null && "group".equals(c.getStr("type")) ? "人群" : "人物";
            return ctx.tagged() ? "<image" + charSlot + ">中的" + role : "参考图中的" + role;
        }
        Integer assetSlot = ctx.assetSlots().get(id);
        if (assetSlot != null) {
            return ctx.tagged() ? "<image" + assetSlot + ">中的物件" : "道具参考图中的物件";
        }
        if (ctx.frameCharIds().contains(id)) {
            // 无参考图的出镜角色：只用「第 N 个」稳定序数指代 —— 它的外貌短语只在 主体定义 写一次。
            // 每复述一次外貌，模型就等于又收到一遍"再画一个这样的人"（凭空多人的主因）。
            JSONObject c = ctx.charsById().get(id);
            return frameOrdinalLabel(id, ctx, c != null && "group".equals(c.getStr("type")));
        }
        if (ctx.assetsById().containsKey(id)) {
            JSONObject a = ctx.assetsById().get(id);
            String alias = a == null ? "" : clean(a.getStr("appearance"));
            if (StrUtil.isBlank(alias) && ctx.assetAppearances() != null) {
                alias = clean(ctx.assetAppearances().get(id));
            }
            return StrUtil.isNotBlank(alias) ? alias : "画面中另一物件";
        }
        return "画面外的人物";
    }

    /**
     * 无参考图出镜角色的稳定指代：按 {@code frameCharacterIds} 顺序编号，全帧（定义行 + 描述行）一致。
     * <p>它替代了「把外貌短语原地复述一遍」的旧做法 —— 外貌只在 {@code 主体定义} 里出现一次，
     * 之后统一用「画面中第 N 个出镜人物」消费，模型不会因为读到多份外貌而把同一个人画成好几份。</p>
     */
    private static String frameOrdinalLabel(String id, RefContext ctx, boolean group) {
        int idx = ctx.frameCharIds().indexOf(id);
        if (idx < 0 || idx >= ORDINALS.length) {
            return group ? "画面中另一组人群" : "画面中另一人物";
        }
        return group
                ? "画面中第" + ORDINALS[idx] + "组人群"
                : "画面中第" + ORDINALS[idx] + "个出镜人物";
    }

    /**
     * 组装取景子句：机位基调（景别 / 角度）+ cameraAt（相机物理位置）/ target（对准主体）/
     * framing（构图与不对称要素）逐项拼接，缺项跳过。
     *
     * <p>🔴 <b>机位基调以分镜已锁定值为准</b>（{@code plannedCamera}）：首帧草稿的
     * {@code camera.shot / camera.angle} <b>只在分镜没给机位时</b>才使用。
     * 旧实现是"草稿优先、分镜仅全空时兜底"，等于让首帧 LLM 把分镜规划好的机位
     * （{@code pov} / {@code over-shoulder} / {@code top} / {@code low} / 远近）重新改写成它自己的
     * 默认构图（正面平视 + 人物左右对称等距居中）—— 分镜层三轮建设出的机位多样性在首帧被抹平，
     * 这也是"人物总在看镜头 / 全片一个机位"的根因。</p>
     *
     * <p>分镜串来自 {@code ChapterEventShotSchema.Camera#buildCamera()}（形如
     * {@code "medium shot,eye_level angle,static motion,"}；该类在 dramatrix-common，插件模块不依赖，
     * 故此处只以文字说明、不做 import），由 {@link #renderPlannedCamera}
     * 机械译成中文；译不出（老数据 / 词表外的值）时整句回退旧行为，不冒进覆盖。</p>
     */
    private static String buildCameraText(JSONObject camera, String plannedCamera) {
        String planned = renderPlannedCamera(plannedCamera);
        String shot = "";
        String angle = "";
        String cameraAt = "";
        String target = "";
        String framing = "";
        if (camera != null) {
            shot = clean(camera.getStr("shot"));
            angle = clean(camera.getStr("angle"));
            cameraAt = clean(camera.getStr("cameraAt"));
            target = clean(camera.getStr("target"));
            framing = clean(camera.getStr("framing"));
        }
        if (StrUtil.isNotBlank(planned)) {
            // 分镜说了算：景别 + 角度换成中文基调，草稿的同名字段让位（只保留三项落地细节）
            shot = planned;
            angle = "";
        }
        // 词表内自带的固定搭配（top+high_angle / low+low_angle）会渲染成"高角度俯拍，高角度"：
        // 角度已被景别吸收，就不再重复。纯字符串包含判断，不涉语义。
        if (StrUtil.isNotBlank(shot) && StrUtil.isNotBlank(angle) && shot.contains(angle)) {
            angle = "";
        }
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(shot)) {
            sb.append(shot);
        }
        if (StrUtil.isNotBlank(angle)) {
            sb.append(!sb.isEmpty() ? "，" : "").append(angle);
        }
        if (StrUtil.isNotBlank(cameraAt)) {
            sb.append(!sb.isEmpty() ? "；" : "").append("相机位于").append(cameraAt);
        }
        if (StrUtil.isNotBlank(target)) {
            sb.append(!sb.isEmpty() ? "，" : "").append("对准").append(target);
        }
        if (StrUtil.isNotBlank(framing)) {
            sb.append(!sb.isEmpty() ? "；" : "").append("构图：").append(framing);
        }
        return sb.toString();
    }

    /**
     * 把分镜机位串机械翻译成中文基调，形如 {@code "过肩视角，低角度"}。
     *
     * <p>{@code Camera#buildCamera()} 的产物是 {@code "<shot_type> shot,<angle> angle,<motion> motion,"}
     * 三个英文 token；{@code motion} 属视频阶段的运镜，单帧不落地，直接忽略。词表外的值原样保留
     * （宁可透出一个陌生英文词，也不要静默丢掉分镜的机位意图）。</p>
     *
     * @return 中文机位基调；分镜未给机位、或两个维度都译不出时返回空串（调用方回退旧行为）
     */
    private static String renderPlannedCamera(String plannedCamera) {
        String planned = clean(plannedCamera);
        if (StrUtil.isBlank(planned)) {
            return "";
        }
        String shot = "";
        String angle = "";
        for (String token : planned.split(",")) {
            String t = token.trim();
            if (t.endsWith(" shot")) {
                String v = t.substring(0, t.length() - " shot".length()).trim();
                shot = SHOT_TYPE_CN.getOrDefault(v, v);
            } else if (t.endsWith(" angle")) {
                String v = t.substring(0, t.length() - " angle".length()).trim();
                angle = ANGLE_CN.getOrDefault(v, v);
            }
        }
        if (StrUtil.isBlank(shot)) {
            return angle;
        }
        if (StrUtil.isBlank(angle)) {
            return shot;
        }
        // 词表内自带的固定搭配（top+high_angle / low+low_angle）会拼成"高角度俯拍，高角度"：
        // 角度已被景别吸收，不再重复。纯字符串包含判断，不涉语义。
        return shot.contains(angle) ? shot : shot + "，" + angle;
    }

    /**
     * 追加道具子句。三分支：
     * <ol>
     *   <li><b>有参考图槽位</b> → 身份指向 {@code <imageN>（道具参考图）}，<b>一个字外貌都不写</b>；</li>
     *   <li>无槽位（临时资产 / 资产图未就绪 / 超出槽位上限）→ 退化为文字描述（DB 外貌优先，其次条目自带）；</li>
     *   <li>既无槽位又无文字 → 整条跳过，不可描述即不臆造。</li>
     * </ol>
     * 道具的动作/位置里若引用了角色或其它资产 id，由 {@code refs} 统一换成参考图槽位。
     */
    private static void appendAssets(
            List<String> subjectDefs, List<String> clauses,
            List<String> assetIds, Map<String, JSONObject> assetsById,
            RefContext ctx, UnaryOperator<String> refs
    ) {
        for (String id : assetIds) {
            JSONObject a = assetsById.get(id);
            Integer slot = ctx.assetSlots().get(id);
            String action = a == null ? "" : StrUtil.blankToDefault(
                    clean(a.getStr("action")), clean(a.getStr("description")));
            String position = a == null ? "" : clean(a.getStr("position"));
            StringBuilder def = new StringBuilder();
            if (slot != null) {
                // 定义行：槽位标签 + 此刻在做什么；摆位留给 详细描述
                def.append(refTag(ctx.tagged(), slot, "道具参考图")).append("中的物件");
                // 描述行用短指代，与角色描述行同一口径
                String shortSubject = ctx.tagged() ? "<image" + slot + ">中的物件" : "道具参考图中的物件";
                StringBuilder desc = new StringBuilder(shortSubject);
                if (StrUtil.isNotBlank(position)) {
                    desc.append("置于").append(position);
                }
                clauses.add(refs.apply(desc.toString()));
            } else {
                // 无槽位 → 外貌是唯一身份来源，只在定义行写一次（含摆位，避免再复述一遍外貌）
                Map<String, String> assetAppearances = ctx.assetAppearances();
                String appearance = assetAppearances == null ? "" : clean(assetAppearances.get(id));
                if (StrUtil.isBlank(appearance) && a != null) {
                    appearance = clean(a.getStr("appearance"));
                }
                if (StrUtil.isBlank(appearance)) {
                    continue;
                }
                def.append("画面中的").append(appearance);
                if (StrUtil.isNotBlank(position)) {
                    def.append("，位于").append(position);
                }
            }
            if (StrUtil.isNotBlank(action)) {
                def.append("，").append(action);
            }
            subjectDefs.add(refs.apply(def.toString()));
        }
    }

    /**
     * 压平单行文本：去换行/制表/连续空白、去序列化残留的数组符号、收敛重复标点，<b>保留中文</b>。
     * <p>与 {@link PluginJsonUtil#sanitizePromptPart} 不同 —— 后者为 FLUX 的纯英文口径会把中文整段清空，
     * 而 Qwen Image 2.1 走中文指令，中文必须原样保留。</p>
     *
     * <p>同包 {@link Qwen21StoryboardPromptBuilder} 复用。</p>
     */
    static String clean(String s) {
        if (StrUtil.isBlank(s)) {
            return "";
        }
        return s.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\[\\s*]", " ")
                .replaceAll("[\\[\\]]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[，,]{2,}", "，")
                .replaceAll("[；;]{2,}", "；")
                .replaceAll("[。.]{2,}", "。")
                .replaceAll("^[，,；;。.\\s]+", "")
                .replaceAll("[，,；;]+$", "")
                .trim();
    }
}