package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.PluginDraftView;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Qwen Image 2.1 图生图的<b>多格分镜故事板 prompt 拼接</b>。
 *
 * <p>从 LLM 输出的 JSON 草稿（结构见 {@code storyboard.md}：{@code characters:[{id}]} +
 * {@code frameCharacterIds} + {@code motions[]}）组装成给图片模型的成品多格 prompt。与首帧
 * {@link Qwen21PromptBuilder} 的差异：首帧画<b>一帧</b>，此处把 {@code motions} 逐格铺开成
 * <b>一张被细白色分隔线切成的宫格分镜故事板</b>。</p>
 *
 * <p>槽位复用 {@link ReferenceSlotPlanner}（唯一真源）：<code>&lt;image1&gt;</code> = 场景底图，
 * 其后角色/资产按序编号，正文里的 {@code id} 一律确定性换成 <code>&lt;imageN&gt;</code>，不残留裸 id。</p>
 *
 * <p><b>逐格槽位预算</b>（见 {@link PanelRefs}）：{@code <imageN>} 是"把参考图 N 里这个主体画进来"的
 * 生成指令，不是"这个名字指谁"的说明；同一个人的槽位在一格里写第二遍，模型就会把他<b>再画一份</b>
 * （一格多出一个一模一样的人）。因此本类把「槽位」只挂在角色自己的入画子句上，其余提及一律退化成
 * 无槽位的名字（画框外的人连名字都不写）。</p>
 */
public final class Qwen21StoryboardPromptBuilder {

    /** 英文序数：无参考图角色（temp_char_* / 群体）的稳定指代（上限 12 个主体，够用）。 */
    private static final String[] EN_ORDINALS = {
            "first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth"
    };

    /** 英文基数（格数 ≤ 16，够用）。 */
    private static final String[] EN_NUMBERS = {
            "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen"
    };

    /**
     * 参考声明里<b>单个角色外貌</b>的字数上限（超出按子句边界截断）。
     *
     * <p>DB 的 {@code toMinimalPrompt()} 可能是一整段（gender/age/clothes/face/hair/body/height/
     * weapon/accessories 顺次拼出来的），多格故事板的 head 本来就有结构句 + 风格 + 场景 + 环境 +
     * 光线 + 道具句，原样再塞两段外貌会把 prompt 撑得很长——而"锚定"这件事并不需要整段：
     * 身份关键信息（性别/年龄感/服色/发色）都在最前面，越往后的细节越像"再画一个这样的人"的生成指令。
     * 故按 {@code toMinimalPrompt()} 的固有顺序截前段。</p>
     */
    private static final int MAX_APPEARANCE_CHARS = 80;

    /** 外貌截断时的子句边界（中英文逗号 / 分号 / 顿号 / 句号）。 */
    private static final Pattern APPEARANCE_BOUNDARY = Pattern.compile("[，,；;、。.]");

    /** 判「是否只是机位复述」时忽略的虚词（见 {@link #contentWords}）。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "with", "from", "into", "his", "her", "their", "its", "this", "that");

    /**
     * beat {@code desc} 开头的转场语（{@code cut to …} / 「镜头切到…」）。
     * <p>故事板每格是<b>一张定格图</b>，转场语会让模型在同一格里画出"切之前 + 切之后"两个状态
     * （重复人物、串格的直接来源），拼故事板前必须剥掉。</p>
     */
    private static final Pattern LEADING_TRANSITION = Pattern.compile(
            "^(?:(?:the\\s+|a\\s+)?(?:camera\\s+)?(?:cut|cuts|cutting|we\\s+cut|transition)\\s+(?:to|back\\s+to)"
                    + "|(?:镜头|画面)(?:切到|切至|转到|摇到|推近到|拉开到))\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * 角度档朝向短语（图像模型不认）：{@code three-quarter facing frame right} /
     * {@code in profile facing frame left} / {@code three-quarter turned away from the camera}。
     * <p>口径见 {@code first-frame.md}：图像模型只认「朝向谁 / 背对镜头 / 正面朝向镜头」，
     * 「三分之四 / 侧身面朝画面左」这类角度档写了等于没写。只匹配带 camera/frame/forward 落点的短语，
     * 因此 {@code facing char_b} / {@code turned toward 大长老} 这种「朝向谁」的写法不会被误改。</p>
     */
    private static final Pattern FACING_CLAUSE = Pattern.compile(
            "(?i),?\\s*((?:in\\s+profile\\s+)?"
                    + "(?:three[-\\s]?quarter(?:ed|s)?\\s+|half\\s+|slightly\\s+|partial\\s+)?"
                    + "(?:facing|turned|looking)\\s+(?:away\\s+from\\s+)?(?:the\\s+)?"
                    + "(?:camera|viewer|frame\\s*(?:left|right)?|screen\\s*(?:left|right)|forward))");

    /** 画面左右朝向（判「双人互视」用：一人朝画面右、另一人朝画面左 = 两人对着）。 */
    private static final Pattern FACING_FRAME_RIGHT = Pattern.compile("(?i)\\bframe\\s*right\\b|\\bto\\s+the\\s+right\\b");
    private static final Pattern FACING_FRAME_LEFT = Pattern.compile("(?i)\\bframe\\s*left\\b|\\bto\\s+the\\s+left\\b");

    private Qwen21StoryboardPromptBuilder() {
    }

    /** 无参考图角色的英文序数（超限回退阿拉伯数字）。 */
    private static String enOrdinal(int n) {
        return n >= 1 && n <= EN_ORDINALS.length ? EN_ORDINALS[n - 1] : String.valueOf(n);
    }

    /** 宫格格数的英文基数（超限回退阿拉伯数字）。 */
    private static String enNumber(int n) {
        return n >= 1 && n <= EN_NUMBERS.length ? EN_NUMBERS[n - 1] : String.valueOf(n);
    }

    /**
     * 组装成品故事板 prompt。宫格版式由 {@code motions} 条数推导（4/6/9/12/16…）。
     *
     * <p>按 Qwen-Image 官方 prompt 口径铺陈（官方 {@code prompt_rewrite} 系统词 + 本项目既有
     * {@code scene-image/grid} 九宫格模板的写法）：<b>先结构句</b>（几宫格、分隔线、阅读顺序）→
     * <b>再全板共享的背景/光线/身份锚定</b> → <b>逐格走图</b>（{@code In the panel at row R, column C,}
     * 行列坐标开头、一格一行）→
     * <b>一句总构图收尾</b> + {@code No text inside panels.}。</p>
     *
     * <p>四条硬口径：① 比例/分辨率/画质词一律不进正文（比例只由画布尺寸决定，"2K"这类词官方明令禁止）；
     * ② 不写 {@code Shot N：} 这类标签（Qwen-Image 文字渲染极强，标签容易被当成要渲染的文字画进图）；
     * ③ 逐格的 {@code desc} 开头若是 {@code cut to …} 转场语，必须剥掉——故事板每格是一张定格图，
     * 转场语会让模型在同一格里画出"切之前 + 切之后"两个状态；
     * ④ <b>每格每个入画角色的 {@code <imageN>} 槽位只出现一次</b>（见 {@link PanelRefs}）——
     * 槽位是"把参考图里的人画进来"的生成指令，重复出现就会把同一个人画成两份。</p>
     *
     * @param request        图生图 prompt 上下文（llmResult 为 storyboard.md 约定的 JSON 草稿）
     * @param includeTempChar 是否把临时角色（{@code temp_char_*}）也纳入槽位（由插件决定）
     * @return 成品多格故事板 prompt；LLM 输出不可解析 / 无 motions 时返回 {@code null}
     */
    static String build(Image2ImagePromptRequest request, boolean includeTempChar) {
        if (request == null) {
            return null;
        }
        // 唯一真源 = 本镜蓝图；宫格格数 = beats.size()（与视频侧 [Shot 1..N] 同源）
        JSONObject json = PluginDraftView.storyboard(request.getVisualPlan());
        if (json == null || json.isEmpty()) {
            return null;
        }

        JSONArray motions = json.getJSONArray("motions");
        if (CollUtil.isEmpty(motions)) {
            return null;
        }

        // 1. 槽位：场景占 1，角色/资产按序编号（复用 ReferenceSlotPlanner 唯一真源）
        Map<String, String> charImageRefs = request.getCharacterImageRefs() == null
                ? Map.of() : request.getCharacterImageRefs();
        Map<String, String> assetImageRefs = request.getAssetImageRefs() == null
                ? Map.of() : request.getAssetImageRefs();
        Map<String, String> charAppearancePrompts = request.getCharAppearancePrompts() == null
                ? Map.of() : request.getCharAppearancePrompts();
        boolean hasScene = StrUtil.isNotBlank(request.getScenePrompt()) || StrUtil.isNotBlank(json.getStr("scene"));

        List<String> frameIds = readFrameIds(request);
        List<String> assetIds = new ArrayList<>(assetImageRefs.keySet());

        Map<String, Integer> slotById = ReferenceSlotPlanner.plan(
                hasScene, includeTempChar,
                frameIds, charImageRefs::containsKey,
                assetIds, assetImageRefs::containsKey
        );
        // id → <imageN> 指代：替换正文里的裸 id（长 id 先行，避免前缀撞车）
        Map<String, String> labelById = new LinkedHashMap<>();
        if (hasScene) {
            labelById.put("scene", "<image1>");   // 场景环境锚点恒为 image1
        }
        for (Map.Entry<String, Integer> e : slotById.entrySet()) {
            labelById.put(e.getKey(), "<image" + e.getValue() + ">");
        }
        // 无参考图出镜角色（temp_char_* / 群体）：正文用稳定序数指代（外貌只在锚定句写一次，
        // 防止把外貌复述成"再画一个这样的人"——凭空多人的主因），不留裸 id。
        int tempOrdinal = 1;
        for (String id : frameIds) {
            if (StrUtil.isBlank(id) || slotById.containsKey(id)
                    || StrUtil.isBlank(charAppearancePrompts.get(id))) {
                continue;
            }
            labelById.put(id, "the " + enOrdinal(tempOrdinal++) + " extra figure in frame");
        }

        // 3. 身份锚定（<imageN> 通配 + 名字；替代旧「角色身份严格锚定参考图」与「图N为…锚点」）
        Map<String, String> charNames = new LinkedHashMap<>();
        JSONObject namesObj = json.getJSONObject("characterNames");
        if (namesObj != null) {
            for (Map.Entry<String, ?> e : namesObj.entrySet()) {
                String name = e.getValue() == null ? null : e.getValue().toString();
                if (StrUtil.isNotBlank(name)) {
                    charNames.put(e.getKey(), name);
                }
            }
        }
        // 3.0 全板共享的背景 / 光线 / 风格（对齐 Qwen-Image 官方写法：先结构，再背景，再逐区走图；
        //     比例与分辨率一律不进正文——它们只走画布尺寸）
        String sceneText = StrUtil.blankToDefault(Qwen21PromptBuilder.clean(json.getStr("scene")),
                Qwen21PromptBuilder.clean(request.getScenePrompt()));
        String lighting = StrUtil.blankToDefault(Qwen21PromptBuilder.clean(json.getStr("lighting")),
                Qwen21PromptBuilder.clean(request.getLighting()));
        String style = StrUtil.blankToDefault(Qwen21PromptBuilder.clean(json.getStr("style")),
                Qwen21PromptBuilder.clean(request.getFixPrompt()));

        List<String> head = new ArrayList<>();
        head.add(gridStructure(motions.size()));
        if (StrUtil.isNotBlank(style)) {
            head.add("The style is " + style + ".");
        }
        if (StrUtil.isNotBlank(sceneText)) {
            head.add("The setting is " + sceneText + ".");
        }
        if (hasScene) {
            head.add("The environment comes from <image1> as the single background of every panel: same architecture, "
                    + "same colour mood, same lighting logic.");
        }
        if (StrUtil.isNotBlank(lighting)) {
            head.add("Lighting is " + lighting + ".");
        }
        // 3.1 身份锚定：角色「名字 (<imageN>)（外貌）」、道具「<imageN>」。
        //     外貌在这里写一次，作为 <imageN> 槽位绑定之外的**文字兜底**：多角色同框时槽位绑定一旦
        //     松动（多格故事板里"某角色只在部分格出镜"时最明显），模型手里至少还有一段可回退的
        //     文字，而不是把最先绑定的那个人套到后面每一格。
        //     ⚠️ 只在锚定句写一次、逐格绝不复述——逐格复述等于又下几遍"再画一个这样的人"（凭空多人）。
        //     外貌按 MAX_APPEARANCE_CHARS 截断（见 truncateAppearance）：DB 给的是整段，
        //     原样拼会把 prompt 撑长，而身份关键信息本来就在最前面。
        List<String> charTaken = new ArrayList<>();
        for (Map.Entry<String, Integer> e : slotById.entrySet()) {
            String id = e.getKey();
            if (assetIds.contains(id)) {
                continue;
            }
            String name = charNames.getOrDefault(id, id);
            String appearance = truncateAppearance(Qwen21PromptBuilder.clean(charAppearancePrompts.get(id)));
            charTaken.add(name + " comes from <image" + e.getValue() + ">"
                    + (StrUtil.isNotBlank(appearance) ? " (" + appearance + ")" : ""));
        }
        if (!charTaken.isEmpty()) {
            head.add(String.join(", ", charTaken)
                    + " — facial features, hairstyles, costume styles and colour schemes stay identical in every panel, "
                    + "changing only in expression, pose and interaction.");
        }
        List<String> assetTaken = new ArrayList<>();
        for (Map.Entry<String, Integer> e : slotById.entrySet()) {
            if (!assetIds.contains(e.getKey())) {
                continue;
            }
            assetTaken.add("<image" + e.getValue() + ">");
        }
        if (!assetTaken.isEmpty()) {
            head.add("The props come from " + String.join(", ", assetTaken)
                    + ", keeping the same shape and material in every panel.");
        }

        // 富指代表：机位/动作/情绪等自由文本里的角色 id → 「名字 (<imageN>)」（更可读），
        // 资产 → 「<imageN>」；presentSubjects 仍用纯 <imageN>（labelById）作逐格指代。
        Map<String, String> richLabelById = new LinkedHashMap<>(labelById);
        for (Map.Entry<String, Integer> e : slotById.entrySet()) {
            String id = e.getKey();
            if (assetIds.contains(id)) {
                richLabelById.put(id, "<image" + e.getValue() + ">");
            } else {
                String name = charNames.get(id);
                // 有名字 → 「名字 (<imageN>)」；无名 → 退化成纯 <imageN>，避免裸 id 外泄
                richLabelById.put(id, StrUtil.isNotBlank(name)
                        ? name + " (<image" + e.getValue() + ">)"
                        : "<image" + e.getValue() + ">");
            }
        }

        // 4. 逐格走图：位置词开头、一格一行、句间用英文句号；
        //    不写「Shot N：」这类标签（Qwen-Image 文字渲染极强，标签容易被当成要渲染的文字画进图）
        List<String> openers = panelOpeners(motions.size());
        StringBuilder shots = new StringBuilder();
        for (int i = 0; i < motions.size(); i++) {
            JSONObject m = motions.getJSONObject(i);
            if (m == null) {
                continue;
            }
            String cam = Qwen21PromptBuilder.clean(m.getStr("camera"));
            String action = stripTransition(Qwen21PromptBuilder.clean(m.getStr("action")), cam);
            String mood = Qwen21PromptBuilder.clean(m.getStr("mood"));

            // 本格入画者逐格标注：一个角色只拼一条子句「名字 (<imageN>) 位置与朝向, 动作/表情」——
            // 位置与朝向整体来自 characters[].position（模型产出，原样拼），desc 只留动作/表情/姿态，
            // camera 只写镜头本身；因此同一件事不会既在 camera 里列一遍、又在角色子句里列一遍。
            JSONArray beatChars = m.getJSONArray("characters");
            JSONArray beatProps = m.getJSONArray("props");
            // 本格引用预算：角色的 <imageN> 槽位只挂在他自己的入画子句上，其余提及只给名字（详见 PanelRefs）
            PanelRefs panelRefs = PanelRefs.of(beatChars, beatProps, richLabelById, charNames, assetIds);
            Map<String, String> placement = characterOverrides(beatChars, charNames);
            String chars = presentSubjects(beatChars, charNames, labelById, placement);
            String props = presentSubjects(beatProps, Map.of(), labelById);
            // 机位句：本格没有这个人时只剥掉提及，机位本身要留（每格都得有景别）
            String camText = panelRefs.resolve(cam);
            // 画面级 desc 本就不许写人（B1）：写的是"画框外的人"时整句丢弃——剥剩下的是没有主语的
            // 半句话，模型会自己补一个人出来（又一次凭空多人）；人物动作一律由他自己的入画子句承载
            String actionText = panelRefs.resolve(action);
            if (panelRefs.droppedPerson()) {
                actionText = "";
            }
            String moodText = panelRefs.resolve(mood);

            List<String> clauses = new ArrayList<>();
            if (StrUtil.isNotBlank(camText)) {
                clauses.add(camText);
            }
            if (StrUtil.isNotBlank(actionText)) {
                clauses.add(actionText);
            }
            if (StrUtil.isNotBlank(chars)) {
                clauses.add(panelRefs.resolve(chars));
            }
            if (StrUtil.isNotBlank(props)) {
                clauses.add(panelRefs.resolve(props));
            }
            if (StrUtil.isNotBlank(moodText)) {
                clauses.add("The mood is " + moodText);
            }
            clauses.removeIf(StrUtil::isBlank);
            if (clauses.isEmpty()) {
                // 兜底：整格都被「未入画者」滤空（LLM 违约：空镜的 desc/camera 里写了人）——
                // 这一格仍要占位，否则格数与首句声明的宫格版式对不上
                clauses.add("a still frame of the same scene");
            }
            if (!shots.isEmpty()) {
                shots.append('\n');
            }
            shots.append(openers.get(Math.min(i, openers.size() - 1)))
                    .append(' ').append(sentenceJoin(clauses)).append('.');
        }

        // 5. 组装成品：结构句 + 全板共享的背景/光线/身份 →（换行）逐格走图 →（换行）收尾一句 + 无文字
        //    不再写"2K高清/横宽/专业分镜故事板"这类画质词与元指令（官方口径：只描述画面里有什么）
        return String.join(" ", head) + "\n" + shots + "\n" + layoutClosing(motions.size())
                + " Every figure appears exactly once per panel; no duplicated or additional people."
                + " No text inside panels.";
    }

    /**
     * 出镜角色 id 顺序：直接用管线给的 {@code characterIds}。
     * <p>旧实现优先读 LLM 草稿的 {@code frameCharacterIds}；规划上移后蓝图不再携带该字段
     * （它属于 {@code IMAGE_BUILD} 的产物，规划期不可得），管线传入的 {@code characterIds}
     * 就是同一份口径（{@code ImageWorkerInvocationV4} 按第 1 拍 characters 得出后传下来）。</p>
     */
    private static List<String> readFrameIds(Image2ImagePromptRequest request) {
        return CollUtil.isEmpty(request.getCharacterIds()) ? List.of() : request.getCharacterIds();
    }

    /**
     * 宫格排布，形如 {@code 4x4}：结构句、逐格行列坐标与收尾句都用它推导行/列数。
     * <p>表里是已跑通的常用格数（就近取整、不把单格压扁）；宫格数放开后其它格数走
     * {@link #autoLayout} 自动取近似方形排布。</p>
     */
    private static String gridLayout(int n) {
        return switch (n) {
            case 1 -> "1x1";
            case 2 -> "2x1";
            case 3 -> "3x1";
            case 4 -> "2x2";
            case 5 -> "2x3";
            case 6 -> "2x3";
            case 9 -> "3x3";
            case 12 -> "3x4";
            case 16 -> "4x4";
            case 7, 8 -> "2x4";
            case 10, 11 -> "3x4";
            default -> autoLayout(n);
        };
    }

    /**
     * 自由格数的排布：取「最小的、接近正方形」的网格。
     * <p>画布宽高比固定时，单格宽高比 = 画布宽高比 × 行数 ÷ 列数——行列越接近，单格越接近原画幅
     * （16:9 画布 → 单格也接近 16:9）；细长条版式会把单格压扁。行列数按「先横向、不细高」优先。</p>
     */
    private static String autoLayout(int n) {
        if (n <= 1) {
            return "1x1";
        }
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        while (rows > cols) {
            cols++;
            rows = (int) Math.ceil((double) n / cols);
        }
        return rows + "x" + cols;
    }

    /** 首句：画布结构（形如 {@code A single image divided into a 3×3 grid of nine panels…}）。 */
    private static String gridStructure(int n) {
        if (n <= 1) {
            return "A single image holding one panel.";
        }
        String layout = gridLayout(n);
        int rows = layoutRows(layout);
        int cols = layoutCols(layout);
        String count = enNumber(n);
        return rows * cols == n
                ? "A single image divided into a " + rows + "×" + cols + " grid of " + count
                        + " panels by thin white divider lines, reading order left to right, top to bottom."
                : "A single image holding " + count + " panels separated by thin white divider lines, arranged in a "
                        + rows + "×" + cols + " layout, reading order left to right, top to bottom.";
    }

    /** 收尾句：整体构图 + 跨格一致性（官方口径：结尾只留一句总述）。 */
    private static String layoutClosing(int n) {
        if (n <= 1) {
            return "The overall composition is a single panel with one scene, one lighting setup and one character identity.";
        }
        String layout = gridLayout(n);
        return "The overall composition is one " + layoutRows(layout) + "×" + layoutCols(layout)
                + " storyboard sheet of equal panels, every panel sharing the same scene, lighting and character "
                + "identity, with only expressions, poses and interactions changing from panel to panel.";
    }

    /**
     * 逐格位置开场白：一律用显式行列坐标（{@code In the panel at row 2, column 3,}）。
     *
     * <p>不用 {@code top-left} 这类方位复合词：方位词需要模型自己把"上排左起第三个"换算成格子，
     * 格数不是满版式时（如 7 条走 2×4 兜底）尤其容易串格；行列坐标是直接可数的。
     * 阅读顺序由首句的 {@code reading order left to right, top to bottom} 交代。
     * 分格数填不满版式时只取前 n 个位置（按阅读顺序）。</p>
     */
    private static List<String> panelOpeners(int n) {
        String layout = gridLayout(n);
        int rows = layoutRows(layout);
        int cols = layoutCols(layout);
        List<String> out = new ArrayList<>();
        if (rows == 1 && cols == 1) {
            out.add("In the panel,");
            return out;
        }
        for (int r = 0; r < rows && out.size() < n; r++) {
            for (int c = 0; c < cols && out.size() < n; c++) {
                out.add("In the panel at row " + (r + 1) + ", column " + (c + 1) + ",");
            }
        }
        return out;
    }

    private static int layoutRows(String layout) {
        return Integer.parseInt(layout.substring(0, layout.indexOf('x')));
    }

    private static int layoutCols(String layout) {
        return Integer.parseInt(layout.substring(layout.indexOf('x') + 1));
    }

    /**
     * 把逐格里的短语拼成句群：逐条去尾标点后用 {@code ". "} 连接（调用方补末尾句号）。
     * <p>首条紧跟「In the … panel,」保持原样（句中），其后的条目是独立句子，首字母大写。</p>
     */
    private static String sentenceJoin(List<String> clauses) {
        List<String> cleaned = new ArrayList<>();
        for (String clause : clauses) {
            String text = StrUtil.trim(clause);
            while (StrUtil.isNotBlank(text) && ".;；。".indexOf(text.charAt(text.length() - 1)) >= 0) {
                text = StrUtil.trim(text.substring(0, text.length() - 1));
            }
            if (StrUtil.isNotBlank(text)) {
                cleaned.add(text);
            }
        }
        for (int i = 1; i < cleaned.size(); i++) {
            cleaned.set(i, capitalizeFirst(cleaned.get(i)));
        }
        return String.join(". ", cleaned);
    }

    /**
     * 剥掉 beat {@code desc} 开头的转场语（{@code cut to …} / 「镜头切到…」），见 {@link #LEADING_TRANSITION}。
     *
     * <p>剥完若剩下的只是把机位又说了一遍（与 {@code camera} 词面高度重合，例如
     * {@code cut to a low over-shoulder framing of the two elder figures} 对照
     * {@code over-shoulder, low angle, on the two elders}），整句丢弃——机位已经在前面写过一次；
     * 若剩下的是新信息（{@code the young man's eyes opening wider}）则保留。</p>
     */
    private static String stripTransition(String desc, String camera) {
        if (StrUtil.isBlank(desc)) {
            return desc;
        }
        String text = desc.trim();
        Matcher matcher = LEADING_TRANSITION.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        String rest = text.substring(matcher.end()).trim();
        if (rest.isEmpty() || restatesFraming(rest, camera)) {
            return "";
        }
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    /**
     * 本格入画者的子句覆盖（{@code id → 「位置与朝向, 动作/表情」}）。两条路径：
     *
     * <ol>
     *   <li><b>新契约</b>（{@code characters[].position} 非空）：模型产出的 {@code position}
     *       <b>原样拼在动作之前</b>，Java 不做任何解析/改写（措辞由视觉规划 prompt 的 B9 保证）；</li>
     *   <li><b>存量蓝图</b>（无 {@code position}）：保留旧路径 —— 从 {@code desc} 里识别角度档，
     *       双人互视（一人朝画面右、另一人朝画面左）改写成 {@code turned toward {对方名字}}，
     *       单人帧去掉角度档词、只留 {@code in profile} 这类读得懂的表述。</li>
     * </ol>
     *
     * @return id → 覆盖后的 desc；没改写的 id 不在表里（{@code presentSubjects} 按原值兜底）
     */
    private static Map<String, String> characterOverrides(JSONArray characters, Map<String, String> charNames) {
        if (CollUtil.isEmpty(characters)) {
            return Map.of();
        }
        List<JSONObject> entries = new ArrayList<>();
        for (Object o : characters) {
            if (o instanceof JSONObject c && StrUtil.isNotBlank(c.getStr("id"))) {
                entries.add(c);
            }
        }
        if (entries.isEmpty()) {
            return Map.of();
        }
        // 存量蓝图（无 position 字段）的互视判定：一人朝画面右、另一人朝画面左 = 两人对着
        String leftId = null;
        String rightId = null;
        for (JSONObject c : entries) {
            if (StrUtil.isNotBlank(c.getStr("position"))) {
                continue;   // 新契约的条目不走这条路径
            }
            String desc = Qwen21PromptBuilder.clean(c.getStr("action"));
            if (FACING_FRAME_RIGHT.matcher(desc).find()) {
                rightId = c.getStr("id");
            } else if (FACING_FRAME_LEFT.matcher(desc).find()) {
                leftId = c.getStr("id");
            }
        }
        boolean mutual = leftId != null && rightId != null && !leftId.equals(rightId);

        Map<String, String> out = new LinkedHashMap<>();
        for (JSONObject c : entries) {
            String id = c.getStr("id");
            String desc = Qwen21PromptBuilder.clean(c.getStr("action"));
            String position = StrUtil.trim(c.getStr("position"));
            if (StrUtil.isNotBlank(position)) {
                // 新契约：模型给的「位置与朝向」原样拼在动作之前
                out.put(id, mergeClause(position, desc));
                continue;
            }
            String counterpart = null;
            if (mutual) {
                String otherId = id.equals(leftId) ? rightId : (id.equals(rightId) ? leftId : null);
                if (otherId != null) {
                    counterpart = StrUtil.blankToDefault(charNames.get(otherId), "the other figure in frame");
                }
            }
            String rewritten = rewriteFacing(desc, counterpart);
            if (!rewritten.equals(desc)) {
                out.put(id, rewritten);
            }
        }
        return out;
    }

    /** 位置与朝向子句 + 动作/表情（任一为空就只留另一个）。 */
    private static String mergeClause(String clause, String desc) {
        if (StrUtil.isBlank(clause)) {
            return desc;
        }
        return StrUtil.isBlank(desc) ? clause : clause + ", " + desc;
    }

    /** 短语清理：压空白、去掉因删词留下的孤立标点（含被删到只剩尾巴逗号的情形）。 */
    private static String tidyClause(String text) {
        return text.replaceAll("\\s+", " ")
                .replaceAll("\\s+([,;.])", "$1")
                .replaceAll(",\\s*,", ",")
                .replaceAll("^[,;]\\s*", "")
                .replaceAll("[,;]+$", "")
                .trim();
    }

    /** 单条 {@code desc} 的朝向改写（有 {@code counterpart} = 双人互视，改写成"朝向谁"）。 */
    private static String rewriteFacing(String desc, String counterpart) {
        if (StrUtil.isBlank(desc)) {
            return desc;
        }
        Matcher matcher = FACING_CLAUSE.matcher(desc);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String phrase = matcher.group(1).toLowerCase();
            boolean profile = phrase.startsWith("in profile");
            boolean away = phrase.contains("away") || phrase.contains("back to");
            String replacement;
            if (away) {
                replacement = profile ? ", in profile, turned away from the camera" : ", turned away from the camera";
            } else if (StrUtil.isNotBlank(counterpart)) {
                replacement = profile ? ", in profile, turned toward " + counterpart
                        : ", turned toward " + counterpart;
            } else {
                replacement = profile ? ", in profile" : "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return tidyClause(sb.toString());
    }

    /** 剥掉转场后的残余句是否只是机位复述：与 {@code camera} 的实词词面重合 ≥ 1/2。 */
    private static boolean restatesFraming(String rest, String camera) {
        Set<String> restWords = contentWords(rest);
        if (restWords.isEmpty() || StrUtil.isBlank(camera)) {
            return false;
        }
        Set<String> cameraWords = contentWords(camera);
        long hit = restWords.stream().filter(cameraWords::contains).count();
        return hit * 2 >= restWords.size();
    }

    /** 实词集合（小写、去标点、去 2 字以下的词与少量虚词；中文字符按整段成词，天然不会误命中）。 */
    private static Set<String> contentWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String word : text.toLowerCase().split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * 截断参考声明里的外貌文字，见 {@link #MAX_APPEARANCE_CHARS}。
     *
     * <p>两条落笔口径：<b>① 按子句边界截</b>（最后一个 {@code ，,；;、。.} 处断，避免留半句让模型自行
     * 补全）；边界太靠前（&lt; 上限 1/3）时退而求其次按最后一个空格断，再不行才硬截——总之宁可少一个
     * 子句，也不要留"半句话"。<b>② 不追加省略号</b>：Qwen-Image 文字渲染极强，{@code …} 有被当成
     * 要渲染的文字画进图的风险（与"不写 {@code Shot N：} 标签"同一口径）。</p>
     *
     * @param appearance 已经 {@link Qwen21PromptBuilder#clean(String)} 过（压平空白、去重复标点）的外貌
     * @return 截断后的外貌；入参空白时返回空串
     */
    private static String truncateAppearance(String appearance) {
        if (StrUtil.isBlank(appearance)) {
            return "";
        }
        String text = appearance.trim();
        if (text.length() <= MAX_APPEARANCE_CHARS) {
            return text;
        }
        String head = text.substring(0, MAX_APPEARANCE_CHARS);
        Matcher boundary = APPEARANCE_BOUNDARY.matcher(head);
        int cut = -1;
        while (boundary.find()) {
            cut = boundary.start();
        }
        if (cut < MAX_APPEARANCE_CHARS / 3) {
            int space = head.lastIndexOf(' ');
            cut = space >= MAX_APPEARANCE_CHARS / 2 ? space : -1;
        }
        if (cut > 0) {
            head = head.substring(0, cut);
        }
        return head.replaceAll("[，,；;、。.]+$", "").trim();
    }

    private static String capitalizeFirst(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * 把某格入画的角色/道具拼成一段「{名字}（<imageN>）动作，…」（例：{@code 林婉（<image2>）独自走入画面}）。
     * <p>角色名字取 {@code charNames}（缺省回退 id）；道具无名字，用「道具」+序号作标签。
     * 名称/指代尽量用槽位（{@code <imageN>}）或稳定序数；只列确有锚定指代的条目，杜绝裸 id。</p>
     *
     * @param subjects  {@code m.characters[]} 或 {@code m.props[]}（元素含 {@code id}/{@code action}）
     * @param charNames id → 人类可读名（道具传空 map）
     * @param labelById id → 画面指代
     * @return 拼好的「,」分隔清单；无可写条目返回空串
     */
    private static String presentSubjects(JSONArray subjects, Map<String, String> charNames, Map<String, String> labelById) {
        return presentSubjects(subjects, charNames, labelById, Map.of());
    }

    private static String presentSubjects(JSONArray subjects, Map<String, String> charNames,
                                          Map<String, String> labelById, Map<String, String> descOverride) {
        if (CollUtil.isEmpty(subjects)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int propIdx = 0;
        for (Object o : subjects) {
            if (!(o instanceof JSONObject sub)) {
                continue;
            }
            String id = sub.getStr("id");
            String token = labelById.get(id);
            if (StrUtil.isBlank(token)) {
                continue;   // 无锚定指代（无参考图且外貌缺失）→ 跳过，不写裸 id
            }
            String desc = StrUtil.blankToDefault(descOverride.get(id), Qwen21PromptBuilder.clean(sub.getStr("action")));
            String subjectLabel = StrUtil.blankToDefault(charNames.get(id), id);
            if (charNames.isEmpty()) {
                // 道具：没有名字，用「prop N」作标签
                subjectLabel = "prop " + (++propIdx);
            }
            parts.add(StrUtil.isNotBlank(desc)
                    ? subjectLabel + " (" + token + ") " + desc
                    : subjectLabel + " (" + token + ")");
        }
        return String.join("; ", parts);
    }

    /**
     * <b>一格的引用预算</b> —— 「每个入画角色的 {@code <imageN>} 槽位在一格里只写一次」的唯一实现。
     *
     * <h3>为什么需要</h3>
     * {@code <imageN>} 不是"这个名字指谁"的说明，而是"把参考图 N 里这个主体画进来"的<b>生成指令</b>
     * （与首帧 {@link Qwen21PromptBuilder} 的 {@code <image2>中的人物} 同源）。视觉规划的
     * {@code characters[].position} 会写「朝向 {@code char_b}」，文本侧被换成
     * {@code turned toward 楚宗主 (<image3>)} ——于是同一格里 {@code <image3>} 出现第二遍（"朝向"的目标
     * 一次 + 该角色自己的入画子句一次），模型就会把这个人<b>再画一份</b>：这正是"一个格子里多出一个
     * 一模一样的人"的来源。首帧侧早就有对应兜底（未出镜者降级成"画面外的人物"），故事板侧原先没有。
     *
     * <h3>口径</h3>
     * <ol>
     *   <li><b>槽位只挂在角色自己的入画子句上</b>（那一条写全了「在场景哪 + 在画面哪 + 景深 + 朝哪 + 动作」）；
     *       其余提及（别人的 {@code position} 里的"朝向谁"、{@code desc}/{@code camera} 里的同一角色）
     *       只写名字 —— 名字由 head 的「{名字} comes from {@code <imageN>}」绑定，是无槽位复述；</li>
     *   <li><b>不在本格入画名单里的人一律不写</b>（{@code characters[]} 字段存在时名单权威，空数组 = 空镜）：
     *       写了模型就会把参考图里的人另画一份进来。朝向语境降级成 {@code off-screen}（B9 词表），
     *       其余语境直接剥掉该次提及；</li>
     *   <li>无参考图槽位的序数别名（{@code the first extra figure in frame}）本身就是无槽位指代，可复用；
     *       "有槽位但没名字"时无指代可用，第二次提及直接剥掉（宁可不写，也不再多画一个人）。</li>
     * </ol>
     *
     * <p>道具不参与预算：道具名不在本类入参里，本格道具也几乎只在 {@code props[]} 子句里出现一次；
     * 槽位只出现在那条子句上。</p>
     */
    private static final class PanelRefs {

        /** 朝向语（判断"这个人是不是被当成视线落点提及"）：命中 → 画框外的人降级成 {@code off-screen}。 */
        private static final Pattern DIRECTIONAL = Pattern.compile("(?i)\\b(?:toward|towards|facing|to|at)$");

        /**
         * 引用被剥掉后留下的孤立关系词（{@code turned toward,} / {@code medium shot, on}）：
         * 连同前导标点一起清理，避免成品出现"半句话"。
         */
        private static final Pattern DANGLING_RELATION = Pattern.compile(
                "(?i)(^|[,;])\\s*(?:beside|toward|towards|facing|from|with|at|on|by|in|into|behind|beyond|across|of|for)\\s*(?=[,;]|$)");

        private final Map<String, String> firstLabelById;
        private final Map<String, String> repeatLabelById;
        private final Set<String> inPanelCharIds;
        private final Set<String> assetIds;
        /** {@code characters[]} 字段是否存在：存在（含空数组）→ 入画名单权威，可判"画框外"。 */
        private final boolean panelScoped;
        /** 本格已经占掉槽位的 id（入画角色的名额在逐格解析<b>之前</b>就被他自己的入画子句占住）。 */
        private final Set<String> used = new LinkedHashSet<>();
        /** 上一次 {@link #resolve} 是否剥掉了"画框外的人"的提及（desc 据此整句丢弃）。 */
        private boolean droppedPerson;

        private PanelRefs(Map<String, String> firstLabelById, Map<String, String> repeatLabelById,
                          Set<String> inPanelCharIds, Set<String> assetIds, boolean panelScoped) {
            this.firstLabelById = firstLabelById;
            this.repeatLabelById = repeatLabelById;
            this.inPanelCharIds = inPanelCharIds;
            this.assetIds = assetIds;
            this.panelScoped = panelScoped;
        }

        static PanelRefs of(JSONArray beatChars, JSONArray beatProps, Map<String, String> richLabelById,
                            Map<String, String> charNames, List<String> assetIds) {
            Set<String> inPanel = idsOf(beatChars);
            Map<String, String> repeat = new LinkedHashMap<>();
            for (String id : inPanel) {
                String name = charNames.get(id);
                if (StrUtil.isNotBlank(name)) {
                    repeat.put(id, name);
                    continue;
                }
                String rich = richLabelById.getOrDefault(id, "");
                // 序数别名（无槽位）可直接复述；有槽位却没名字 → 无指代可用，第二次提及剥掉
                repeat.put(id, rich.contains("<image") ? "" : rich);
            }
            PanelRefs refs = new PanelRefs(richLabelById, repeat, inPanel,
                    new LinkedHashSet<>(assetIds == null ? List.of() : assetIds), beatChars != null);
            refs.used.addAll(inPanel);   // 槽位预留给他自己的入画子句（presentSubjects 里已经写上了）
            return refs;
        }

        /** 逐条子句解析引用：id → 画面指代（角色首提及=富指代、重复=名字、画框外=不写）。 */
        String resolve(String text) {
            droppedPerson = false;
            if (StrUtil.isBlank(text) || CollUtil.isEmpty(firstLabelById)) {
                return text;
            }
            // 长 id 先行，避免 char_a 命中 char_ab 的前缀
            List<String> ids = new ArrayList<>(firstLabelById.keySet());
            ids.sort((a, b) -> Integer.compare(b.length(), a.length()));
            String out = text;
            for (String id : ids) {
                int from = 0;
                while (true) {
                    int at = out.indexOf(id, from);
                    if (at < 0) {
                        break;
                    }
                    int end = at + id.length();
                    if (!bounded(out, at, end)) {
                        from = end;
                        continue;
                    }
                    // 只换 id 本体、保留原有空格：id 两侧常是英文词边界（`char_a 走进屋内`），
                    // 连空格一起吃掉会拼出 `(<imageN>)与` 这种黏连
                    String replacement = label(id, out, at);
                    out = out.substring(0, at) + replacement + out.substring(end);
                    from = at + replacement.length();
                }
            }
            return stripDanglingRelations(tidyClause(out));
        }

        /** 单次提及的指代（见类注释的三条口径）。 */
        private String label(String id, String text, int at) {
            if (!assetIds.contains(id) && panelScoped && !inPanelCharIds.contains(id)) {
                // 画框外的人：写了模型就会把参考图里的人另画一份进这一格
                if (DIRECTIONAL.matcher(text.substring(0, at).trim()).find()) {
                    return "off-screen";
                }
                droppedPerson = true;
                return "";
            }
            return used.add(id)
                    ? StrUtil.blankToDefault(firstLabelById.get(id), "")
                    : StrUtil.blankToDefault(repeatLabelById.get(id), "");
        }

        /** 上一次 {@link #resolve} 是否剥掉了"画框外的人"（见 {@link #label}）。 */
        boolean droppedPerson() {
            return droppedPerson;
        }

        /** id 两侧必须是 ASCII 词边界（中文紧邻不算黏连：{@code char_a走进屋内} 仍要换掉）。 */
        private static boolean bounded(String text, int at, int end) {
            return (at == 0 || !asciiWord(text.charAt(at - 1)))
                    && (end >= text.length() || !asciiWord(text.charAt(end)));
        }

        private static boolean asciiWord(char c) {
            return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }

        private static String stripDanglingRelations(String text) {
            String out = text;
            String before;
            do {
                before = out;
                out = DANGLING_RELATION.matcher(out).replaceAll("$1");
            } while (!out.equals(before));
            return tidyClause(out);
        }

        private static Set<String> idsOf(JSONArray subjects) {
            Set<String> ids = new LinkedHashSet<>();
            if (CollUtil.isEmpty(subjects)) {
                return ids;
            }
            for (Object o : subjects) {
                if (o instanceof JSONObject sub && StrUtil.isNotBlank(sub.getStr("id"))) {
                    ids.add(sub.getStr("id"));
                }
            }
            return ids;
        }
    }
}
