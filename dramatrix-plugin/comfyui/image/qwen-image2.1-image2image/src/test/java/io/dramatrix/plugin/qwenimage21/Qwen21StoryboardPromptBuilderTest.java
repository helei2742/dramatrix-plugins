package io.dramatrix.plugin.qwenimage21;

import io.dramatrix.plugin.dto.Image2ImagePromptRequest;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.ShotVisualPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qwen21Image2ImageAdapter#buildStoryboardPrompt} 的拼接口径测试。
 *
 * <p>锁定的核心不变量（改动拼接逻辑时一旦破就不再是"多格分镜故事板"了）：</p>
 * <ol>
 *   <li><b>宫格由 motions 条数推导</b>：9 条 → 9宫格；结构句写在开头，形如
 *       {@code A single image divided into a 3×3 grid of nine panels by thin white divider lines}。</li>
 *   <li><b>身份锚定用 {@code <imageN>} 槽位 + 角色名</b>：{@code 防御者 comes from <image2>}，不用裸 id。</li>
 *   <li><b>逐格以行列坐标开头、一格一行</b>：{@code In the panel at row 1, column 1, …}；
 *       <b>不写 {@code Shot N：} 标签</b>
 *       （Qwen-Image 文字渲染极强，标签会被当成要渲染的文字）。</li>
 *   <li><b>画质词/比例/分辨率不进正文</b>（官方口径，比例只由画布尺寸决定）；
 *       逐格 {@code cut to …} 转场语必须剥掉；收尾为一句总构图 + {@code No text inside panels.}。</li>
 * </ol>
 */
class Qwen21StoryboardPromptBuilderTest {

    private static Qwen21Image2ImageAdapter adapter() {
        return new Qwen21Image2ImageAdapter(new PluginMetadata());
    }

    /** 9 格示例：场景 1 + 反击方/进攻方两角色，motions 9 条。 */
    private static final String LLM_NINE_MOTIONS = """
            {"characters":[
              {"id":"char_defender","appearance":""},
              {"id":"char_attacker","appearance":""}
            ],
             "frameCharacterIds":["char_defender","char_attacker"],
             "scene":"夜晚的竞技场，冷调灯光，四周观众席",
             "motions":[
              {"camera":"全景平视","action":"char_defender 与 char_attacker 在场景中央对峙","mood":"气氛紧绷"},
              {"camera":"中景侧拍","action":"char_attacker 率先挥拳猛击","mood":"凌厉"},
              {"camera":"近景仰拍","action":"char_defender 侧身灵巧躲开攻击","mood":"锐利"},
              {"camera":"中近景跟拍","action":"两人近身连续交锋，char_defender 被逼退几步","mood":"激烈"},
              {"camera":"全景俯拍","action":"char_attacker 猛冲上前","mood":"气势逼人"},
              {"camera":"中景侧跟","action":"char_defender 借助场景障碍物闪避并反手一击","mood":"干净利落"},
              {"camera":"中景平视","action":"两人拉开距离各自调整呼吸","mood":"张力拉满"},
              {"camera":"特写推近","action":"char_defender 一记重击精准命中","mood":"凶狠"},
              {"camera":"全景侧拍","action":"char_attacker 重重倒在地上，char_defender 保持警戒","mood":"沉寂"}
             ],
             "lighting":"冷调夜光",
             "style":"电影写实",
             "preserve":"跨格角色面容、体型、服装及场景结构一致"}""";

    private static Image2ImagePromptRequest nineMotions() {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_defender", "image1");
        refs.put("char_attacker", "image2");
        ShotVisualPlan plan = ImageDraftFixtures.storyboardPlan(LLM_NINE_MOTIONS);
        plan.setCharacterNames(Map.of("char_defender", "防御者", "char_attacker", "攻击者"));
        return Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_defender", "char_attacker"))
                .characterImageRefs(refs)
                .scenePrompt("夜晚的竞技场")
                .build();
    }

    @Test
    void buildStoryboard_derives9CellGridFromNineMotions() {
        String prompt = adapter().buildStoryboardPrompt(nineMotions());
        assertNotNull(prompt);
        assertTrue(prompt.startsWith("A single image divided into a 3×3 grid of nine panels by thin white divider lines, "
                + "reading order left to right, top to bottom."));
    }

    @Test
    void buildStoryboard_anchorsIdentityViaFigureNamesAndLeaksNoId() {
        String prompt = adapter().buildStoryboardPrompt(nineMotions());
        assertNotNull(prompt);
        // 身份锚定：环境=图1，角色按槽位接 图2/图3，标签用角色名（非裸 id）
        assertTrue(prompt.contains("The environment comes from <image1> as the single background of every panel: "
                + "same architecture, same colour mood, same lighting logic."));
        assertTrue(prompt.contains("防御者 comes from <image2>, 攻击者 comes from <image3> — facial features, hairstyles, "
                + "costume styles and colour schemes stay identical in every panel, "
                + "changing only in expression, pose and interaction."));
        // 正文里的 id 必须被换成 <imageN> 或名字（动作里引用了对方 id）
        assertTrue(prompt.contains("In the panel at row 1, column 1, 全景平视. "
                + "防御者 (<image2>) 与 攻击者 (<image3>) 在场景中央对峙. The mood is 气氛紧绷."));
        // 用了名字锚点后，裸 id 完全不出现在文本里
        assertFalse(prompt.contains("char_defender"));
        assertFalse(prompt.contains("char_attacker"));
    }

    @Test
    void buildStoryboard_shotsSeparatedByNewlineAndCarryNoLabels() {
        String prompt = adapter().buildStoryboardPrompt(nineMotions());
        assertNotNull(prompt);
        assertTrue(prompt.contains("In the panel at row 1, column 1, 全景平视."));
        // 每格独立成行、以行列坐标开头（行优先）：第 2 格 = row 1, column 2；第 9 格 = row 3, column 3
        assertTrue(prompt.contains("\nIn the panel at row 1, column 2, 中景侧拍."));
        assertTrue(prompt.contains("\nIn the panel at row 3, column 3, 全景侧拍."));
        // 标签与画质词一律不出现（会被当文字画进图 / 与官方口径冲突）
        assertFalse(prompt.contains("Shot "));
        assertFalse(prompt.contains("2K"));
        assertFalse(prompt.contains("横宽"));
        assertFalse(prompt.contains("转场至"));
        assertTrue(prompt.endsWith("No text inside panels."));
    }

    @Test
    void buildStoryboard_noTransition() {
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"全景","action":"char_a 站在门口"},
                  {"camera":"中景","action":"char_a 走进屋内"}
                 ]}""";
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(llm)).characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        assertTrue(prompt.contains("\nIn the panel at row 2, column 1, 中景. <image1> 走进屋内."));
        assertFalse(prompt.contains("转场至"));
        assertFalse(leaksInShots(prompt, "char_a"));
    }

    @Test
    void buildStoryboard_shotListsPresentCharactersAndProps() {
        // 每一格除了机位/动作/情绪，还要写明本格入画角色与道具及其动作
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"中景","action":"char_a 走进屋内","mood":"沉稳",
                   "characters":[{"id":"char_a","action":"位于门口，侧身，缓缓抬手"}],
                   "props":[{"id":"asset_sword","action":"斜挎腰间"}]}
                 ]}""";
        ShotVisualPlan plan = ImageDraftFixtures.storyboardPlan(llm);
        plan.setCharacterNames(Map.of("char_a", "林婉"));
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .assetImageRefs(Map.of("asset_sword", "image2"))
                .build());
        assertNotNull(prompt);
        // 身份锚定：角色用名 + <imageN>，道具用 <imageN> 通配
        assertTrue(prompt.contains("林婉 comes from <image1> — facial features, hairstyles, costume styles and "
                + "colour schemes stay identical in every panel, changing only in expression, pose and interaction."));
        assertTrue(prompt.contains("The props come from <image2>, keeping the same shape and material in every panel."));
        // 逐格标注入画角色与道具及其动作：槽位只挂在角色自己的入画子句上，
        // 同一格里的重复提及（desc 里的 char_a）只写名字，槽位不再出现第二遍
        assertTrue(prompt.contains("In the panel, 中景. 林婉 走进屋内."
                + " 林婉 (<image1>) 位于门口，侧身，缓缓抬手. Prop 1 (<image2>) 斜挎腰间. The mood is 沉稳."));
        // 名字/道具标签取代了裸 id
        assertFalse(prompt.contains("char_a"));
        assertFalse(prompt.contains("asset_sword"));
        // 本格 <image1> 只出现一次（第二遍就是"再画一个林婉"）
        assertEquals(1, countInPanel(prompt, "In the panel, 中景.", "<image1>"));
    }

    @Test
    void buildStoryboard_tempCharacterAnchoredByNameAndSlotted() {
        // 临时角色同样进槽位（includeTempCharactersInReference=true）+ 名字锚定
        String llm = """
                {"frameCharacterIds":["char_hero","temp_char_bystander"],
                 "motions":[
                  {"camera":"全景","action":"char_hero 与 temp_char_bystander 并肩而立","mood":"平静",
                   "characters":[
                    {"id":"char_hero","action":"正面持剑备战"},
                    {"id":"temp_char_bystander","action":"右侧结印"}]},
                  {"camera":"中景","action":"temp_char_bystander 侧身退开","mood":"紧张",
                   "characters":[{"id":"temp_char_bystander","action":"左手掐诀，侧身后撤"}]}
                 ]}""";
        ShotVisualPlan plan = ImageDraftFixtures.storyboardPlan(llm);
        plan.setCharacterNames(Map.of("char_hero", "男主", "temp_char_bystander", "白袍路人"));
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_hero", "temp_char_bystander"))
                .characterImageRefs(Map.of("char_hero", "image1", "temp_char_bystander", "image2"))
                .build());
        assertNotNull(prompt);
        assertTrue(prompt.contains("男主 comes from <image1>, 白袍路人 comes from <image2> — facial features, hairstyles, "
                + "costume styles and colour schemes stay identical in every panel, "
                + "changing only in expression, pose and interaction."));
        // 槽位只挂在各自的入画子句上：desc 里对同一格内两人的提及退化成名字
        assertTrue(prompt.contains("In the panel at row 1, column 1, 全景. 男主 与 白袍路人 并肩而立."
                + " 男主 (<image1>) 正面持剑备战; 白袍路人 (<image2>) 右侧结印. The mood is 平静."));
        assertTrue(prompt.contains("In the panel at row 2, column 1, 中景. 白袍路人 侧身退开."
                + " 白袍路人 (<image2>) 左手掐诀，侧身后撤. The mood is 紧张."));
        assertFalse(prompt.contains("char_hero"));
        assertFalse(prompt.contains("temp_char_bystander"));
        assertEquals(1, countInPanel(prompt, "In the panel at row 1, column 1,", "<image2>"));
    }

    @Test
    void buildStoryboard_placesStyleLightingAndClosingSentence() {
        String prompt = adapter().buildStoryboardPrompt(nineMotions());
        assertNotNull(prompt);
        // 风格进开场句、光线单独一句（官方口径），结尾只留一句总构图 + 无文字
        assertTrue(prompt.contains("The style is 电影写实."));
        assertTrue(prompt.contains("The setting is 夜晚的竞技场，冷调灯光，四周观众席."));
        assertTrue(prompt.contains("Lighting is 冷调夜光."));
        assertTrue(prompt.endsWith("The overall composition is one 3×3 storyboard sheet of equal panels, "
                + "every panel sharing the same scene, lighting and character identity, "
                + "with only expressions, poses and interactions changing from panel to panel. "
                + "Every figure appears exactly once per panel; no duplicated or additional people. "
                + "No text inside panels."));
    }

    // ======================== 边界 ========================

    /**
     * 参考声明里的外貌要<b>截断</b>：DB 的 {@code toMinimalPrompt()} 可能是一整段，原样拼进多格故事板
     * 会把 prompt 撑得很长（且越靠后的细节越像"再画一个这样的人"）。锁定四条：
     * ① 截到上限以内；② 是原文的<b>前缀</b>（顺序不乱）；③ 不留半个子句、不留尾逗号、<b>不追加省略号</b>
     * （Qwen-Image 文字渲染强，{@code …} 可能被画进图）；④ 保留的必须是靠前的身份关键信息。
     */
    @Test
    void buildStoryboard_truncatesLongAppearanceWithoutEllipsis() {
        String longAppearance = "男性，三十岁上下，身形高挺，玄色长袍镶银边，腰束玉带，黑发束冠，剑眉星目，"
                + "左手常握一柄青铜长剑，指尖有薄茧，颈间挂一枚旧玉牌，靴面沾着雪泥，袖口绣着家纹，"
                + "外罩旧披风，行走时袍角翻飞，眼神冷冽如霜雪";
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_a", "楚天"))
                .beats(List.of(beat("0-2s", "medium shot, eye level", "the candle gutters",
                        character("char_a", "center frame, facing the camera", "chin raised"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .charAppearancePrompts(Map.of("char_a", longAppearance))
                .build());
        assertNotNull(prompt);
        String marker = "楚天 comes from <image1> (";
        int begin = prompt.indexOf(marker);
        assertTrue(begin >= 0, prompt);
        String appearance = prompt.substring(begin + marker.length(), prompt.indexOf(')', begin));
        // ① 截到上限以内 ② 是原文前缀（顺序不乱） ③ 不留半句 / 尾逗号 / 省略号
        assertTrue(appearance.length() <= 80, appearance);
        assertTrue(longAppearance.startsWith(appearance), appearance);
        assertFalse(appearance.endsWith("，"), appearance);
        assertFalse(appearance.contains("…"), appearance);
        // ④ 靠前的身份关键信息保留，末尾细节丢掉
        assertTrue(appearance.startsWith("男性，三十岁上下"), appearance);
        assertFalse(appearance.contains("眼神冷冽如霜雪"), appearance);
    }

    /**
     * 参考声明里带角色外貌：{@code 楚天 comes from <image1> (外貌)}。
     *
     * <p>这是 {@code <imageN>} 槽位绑定之外的**文字兜底**——多格故事板里"某角色只在部分格出镜"时
     * 槽位绑定最容易松，那时模型手里至少还有一段可回退的文字，而不是把最先绑定的那个人套到后面
     * 每一格。锁定两条：① 外貌进锚定句；② <b>只在锚定句出现一次，逐格绝不复述</b>
     * （逐格复述＝又下几遍"再画一个这样的人"，凭空多人的主因）。</p>
     */
    @Test
    void buildStoryboard_putsAppearanceInTheOpeningReferenceDeclaration() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_chu", "楚天", "char_bai", "白信"))
                .beats(List.of(
                        beat("0-2s", "medium shot, eye level", "dust lifts off the stone floor",
                                character("char_chu", "left of frame, back to camera", "holding the sword steady"),
                                character("char_bai", "right of frame, facing off-screen", "eyes closed tight")),
                        beat("2-4s", "close-up, eye level", "the candle gutters",
                                character("char_bai", "center frame, facing the camera", "lips trembling"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_chu", "char_bai"))
                .characterImageRefs(Map.of("char_chu", "image1", "char_bai", "image2"))
                .charAppearancePrompts(Map.of(
                        "char_chu", "black robe with silver trim, long black hair tied back",
                        "char_bai", "white robe, long unbound black hair"))
                .build());
        assertNotNull(prompt);
        // ① 外貌进参考声明（写一次）
        assertTrue(prompt.contains("楚天 comes from <image1> (black robe with silver trim, long black hair tied back),"
                + " 白信 comes from <image2> (white robe, long unbound black hair) — facial features, hairstyles, "
                + "costume styles and colour schemes stay identical in every panel, "
                + "changing only in expression, pose and interaction."), prompt);
        // ② 逐格不复述
        assertEquals(1, count(prompt, "black robe with silver trim"), prompt);
        assertEquals(1, count(prompt, "white robe, long unbound black hair"), prompt);
        // 锚定句之后（逐格正文里）不许再出现外貌
        assertFalse(leaksInShots(prompt, "black robe with silver trim"), prompt);
    }

    /** 无外貌数据时参考声明保持原样，不多出一对空括号。 */
    @Test
    void buildStoryboard_blankAppearanceKeepsDeclarationUnchanged() {
        String prompt = adapter().buildStoryboardPrompt(nineMotions());
        assertNotNull(prompt);
        assertTrue(prompt.contains("防御者 comes from <image2>, 攻击者 comes from <image3> — facial features, "
                + "hairstyles, costume styles and colour schemes stay identical in every panel, "
                + "changing only in expression, pose and interaction."), prompt);
        assertFalse(prompt.contains("comes from <image2> ()"));
    }

    @Test
    void buildStoryboard_nullRequestReturnsNull() {
        assertNull(adapter().buildStoryboardPrompt(null));
    }

    @Test
    void buildStoryboard_noMotionsReturnsNull() {
        String llm = "{\"characters\":[{\"id\":\"char_a\"}],\"scene\":\"走廊\"}";
        assertNull(adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(llm)).characterIds(List.of("char_a")).build()));
    }

    @Test
    void buildStoryboard_gridCountFallsBackByMotionSize() {
        // 6 条 motions → 6宫格
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"全景","action":"走动"},
                  {"camera":"中景","action":"停下"},
                  {"camera":"近景","action":"回望"},
                  {"camera":"特写","action":"皱眉"},
                  {"camera":"中景","action":"转身"},
                  {"camera":"全景","action":"离去"}
                 ]}""";
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(llm)).characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        assertTrue(prompt.startsWith("A single image divided into a 2×3 grid of six panels by thin white divider lines"));
        assertTrue(prompt.contains("\nIn the panel at row 2, column 3, 全景. 离去."));
    }

    @Test
    void buildStoryboard_freePanelCountsUseAutoLayout() {
        // 宫格数放开：15 条（表外格数）走 autoLayout → 4×4，逐格行列坐标要正好 15 个
        StringBuilder motions = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            motions.append(i == 0 ? "" : ",").append("{\"camera\":\"广角全景\",\"action\":\"dust drifts\"}");
        }
        String many = "{\"frameCharacterIds\":[\"char_a\"],\"motions\":[" + motions + "]}";
        String manyPrompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(many)).characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(manyPrompt);
        assertTrue(manyPrompt.startsWith("A single image holding fifteen panels separated by thin white divider lines, "
                + "arranged in a 4×4 layout, reading order left to right, top to bottom."));
        assertTrue(manyPrompt.contains("In the panel at row 1, column 1, 广角全景."));
        assertTrue(manyPrompt.contains("In the panel at row 4, column 3, 广角全景."));
        assertTrue(manyPrompt.endsWith("No text inside panels."));
    }

    @Test
    void buildStoryboard_transitionTextIsDropped() {
        // transition 字段已不使用：不拼「转场至」，transition 描述也不进成品
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"全景","action":"char_a 站在门口","transition":"上一格全景交代门口，这一格推近"},
                  {"camera":"中景","action":"char_a 走进屋内","transition":"转场衔接说明"}
                 ]}""";
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(llm)).characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        assertTrue(prompt.contains("\nIn the panel at row 2, column 1, 中景. <image1> 走进屋内."));
        assertFalse(prompt.contains("转场"));
        assertFalse(prompt.contains("上一格全景交代门口"));
        assertFalse(leaksInShots(prompt, "char_a"));
    }

    @Test
    void buildStoryboard_rewritesAngleTierFacingIntoWhoTheyFace() {
        // 角度档朝向会让图像模型无从落地（first-frame.md 口径）：
        // 双人互视 → 改写成「朝向谁」；单人 → 去掉角度档，保留 in profile / back to camera
        String llm = """
                {"frameCharacterIds":["char_lin","char_han"],
                 "motions":[
                  {"camera":"over-shoulder, low angle, on the two elders across the hall",
                   "action":"dust lifts off the stone floor",
                   "characters":[
                    {"id":"char_lin","action":"left of frame, three-quarter facing frame right, chin lowered, eyes half-closed"},
                    {"id":"char_han","action":"right of frame, three-quarter facing frame left, shoulders squared"}]}
                 ]}""";
        ShotVisualPlan plan = ImageDraftFixtures.storyboardPlan(llm);
        plan.setCharacterNames(Map.of("char_lin", "大长老", "char_han", "楚宗主"));
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_lin", "char_han"))
                .characterImageRefs(Map.of("char_lin", "image1", "char_han", "image2"))
                .build());
        assertNotNull(prompt);
        assertFalse(prompt.contains("three-quarter"));
        assertTrue(prompt.contains("大长老 (<image1>) left of frame, turned toward 楚宗主, chin lowered, eyes half-closed"));
        assertTrue(prompt.contains("楚宗主 (<image2>) right of frame, turned toward 大长老, shoulders squared"));
    }

    @Test
    void buildStoryboard_soloPanelDropsAngleTierButKeepsReadableFacing() {
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"close-up, eye level, on the seated man","action":"dust drifts through the window light",
                   "characters":[{"id":"char_a","action":"in profile facing frame left, shoulders dropping"}]},
                  {"camera":"medium shot, eye level, on the central figure","action":"the curtain sways",
                   "characters":[{"id":"char_a","action":"three-quarter facing camera, chin lowered"}]},
                  {"camera":"medium shot, slightly low angle, on the elder","action":"the candle gutters",
                   "characters":[{"id":"char_a","action":"back to camera in the foreground, shoulders squared"}]}
                 ]}""";
        ShotVisualPlan plan = ImageDraftFixtures.storyboardPlan(llm);
        plan.setCharacterNames(Map.of("char_a", "大长老"));
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        // 角度档词一律不再出现
        assertFalse(prompt.contains("three-quarter"));
        assertFalse(prompt.contains("facing frame"));
        // 单人帧：背对镜头保留、侧面只留 in profile、正对镜头的角度档整段去掉
        assertTrue(prompt.contains("大长老 (<image1>) back to camera in the foreground, shoulders squared"));
        assertTrue(prompt.contains("大长老 (<image1>) in profile, shoulders dropping"));
        assertTrue(prompt.contains("大长老 (<image1>) chin lowered"));
    }

    @Test
    void buildStoryboard_keepsCutStripping() {
        // 故事板每格是一张定格图：desc 开头的「cut to …」转场语会让模型在同一格里画出
        // "切之前 + 切之后"两个状态（重复人物、串格），必须剥掉
        String llm = """
                {"frameCharacterIds":["char_a"],
                 "motions":[
                  {"camera":"中景，平视","action":"cut to a close-up of the hand and handle","mood":"紧张"},
                  {"camera":"全景","action":"cut to the young man's eyes opening wider","mood":"动容"}
                 ]}""";
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.storyboardPlan(llm)).characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        assertFalse(prompt.contains("cut to"));
        assertTrue(prompt.contains("The young man's eyes opening wider"));
    }

    // ======================== 位置与朝向（characters[].position） ========================

    /**
     * 新契约：一个角色的「位置与朝向」整体来自 {@code characters[].position}，**原样拼在动作之前**、
     * 每格每人只出现一次（`desc` 与 `camera` 都不再承载位置）——这是"位置拼一遍、朝向拼一遍、
     * 读起来乱"的直接修法。Java 不做任何解析/词表映射，措辞由视觉规划 prompt 保证。
     *
     * <p>唯一例外是<b>引用预算</b>：{@code position} 里的「朝向某人」原本会被换成
     * {@code 楚宗主 (<image2>)}，于是同一格里同一个人的槽位出现两遍（"朝向"的目标一次 + 他自己的
     * 入画子句一次），模型照着把这个人<b>再画一份</b>。现在这个提及只写名字，槽位只留在
     * 该角色自己的入画子句上。</p>
     */
    @Test
    void buildStoryboard_rendersPositionFieldAsSingleStagingClause() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_lin", "大长老", "char_han", "楚宗主"))
                .beats(List.of(
                        beat("0-2s", "medium two-shot, eye level, on char_lin", "dust lifts off the stone floor",
                                character("char_lin", "left of frame, turned toward char_han", "chin lowered"),
                                character("char_han", "right of frame, turned toward char_lin", "shoulders squared")),
                        beat("2-4s", "close-up, eye level, on char_lin", "the candle gutters",
                                character("char_lin", "center frame, facing off-screen", "in profile, shoulders dropping")),
                        beat("4-6s", "medium shot, slightly low angle, on char_lin", "the curtain sways",
                                character("char_lin", "right foreground, back to camera", "shoulders squared")),
                        beat("6-8s", "medium shot, eye level, on char_lin", "dust drifts through the window light",
                                character("char_lin", "behind the table, in profile", "chin lowered"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_lin", "char_han"))
                .characterImageRefs(Map.of("char_lin", "image1", "char_han", "image2"))
                .build());
        assertNotNull(prompt);
        // 双人互视：双方都写"朝向对方"（互为镜像），位置与朝向同一条子句里；
        // 但槽位只跟各自的入画子句走 —— "朝向"的目标只写名字（否则同一格会出现两份同一个人）
        assertTrue(prompt.contains("大长老 (<image1>) left of frame, turned toward 楚宗主, chin lowered"));
        assertTrue(prompt.contains("楚宗主 (<image2>) right of frame, turned toward 大长老, shoulders squared"));
        assertEquals(1, countInPanel(prompt, "In the panel at row 1, column 1,", "<image1>"));
        assertEquals(1, countInPanel(prompt, "In the panel at row 1, column 1,", "<image2>"));
        // 位置与朝向原样拼在动作之前
        assertTrue(prompt.contains("大长老 (<image1>) center frame, facing off-screen, in profile, shoulders dropping"));
        assertTrue(prompt.contains("大长老 (<image1>) right foreground, back to camera, shoulders squared"));
        assertTrue(prompt.contains("大长老 (<image1>) behind the table, in profile, chin lowered"));
        // 不残留裸 id
        assertFalse(prompt.contains("char_lin"));
        assertFalse(prompt.contains("char_han"));
    }

    /** 无 {@code position} 的存量蓝图：只渲染动作/表情，不产生空子句。 */
    @Test
    void buildStoryboard_blankPositionRendersDescOnly() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_a", "大长老"))
                .beats(List.of(beat("0-2s", "medium shot, eye level", "the candle gutters",
                        character("char_a", null, "chin lowered, shoulders dropping"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        assertTrue(prompt.contains("大长老 (<image1>) chin lowered, shoulders dropping"));
    }

    // ======================== 逐格引用预算（每格槽位只出现一次） ========================

    /**
     * 用户现场：一格里三个入画者，其中两人 {@code position} 都写「朝向 楚天」。
     * 旧口径把两处「朝向」也换成 {@code 楚天 (<image2>)}，于是这一格里 {@code <image2>} 出现三遍
     * —— 模型照单把楚天画了三份（"一个格子里多出一个一模一样的人"）。现在只有他自己的入画子句带槽位。
     */
    @Test
    void buildStoryboard_eachSlotAppearsOncePerPanel() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .sceneDesc("busy construction site, moving materials")
                .characterNames(Map.of("char_chu", "楚天", "char_zong", "楚宗主", "char_tu", "图风"))
                .beats(List.of(beat("0-2s", "wide shot, high angle", "scrolls and wooden chests shift",
                        character("char_chu", "center frame, mid-ground, full-length, facing off-screen",
                                "arm extended, pointing toward the exit"),
                        character("char_zong", "left of frame, mid-ground, half-length, turned toward char_chu",
                                "gathering scrolls"),
                        character("char_tu", "right of frame, mid-ground, half-length, turned toward char_chu",
                                "hoisting a chest"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_chu", "char_zong", "char_tu"))
                .characterImageRefs(Map.of("char_chu", "image2", "char_zong", "image3", "char_tu", "image4"))
                .build());
        assertNotNull(prompt);
        // 楚天自己的入画子句保留槽位；另外两处「朝向楚天」只写名字
        assertTrue(prompt.contains("楚宗主 (<image3>) left of frame, mid-ground, half-length, turned toward 楚天, "
                + "gathering scrolls"));
        assertTrue(prompt.contains("图风 (<image4>) right of frame, mid-ground, half-length, turned toward 楚天, "
                + "hoisting a chest"));
        // 每格每人一个槽位：<image2> 只在楚天自己的子句里出现一次
        assertEquals(1, countInPanel(prompt, "In the panel,", "<image2>"));
        assertEquals(1, countInPanel(prompt, "In the panel,", "<image3>"));
        assertEquals(1, countInPanel(prompt, "In the panel,", "<image4>"));
    }

    /**
     * 画框外的角色不能写进这一格（{@code characters[]} 存在时名单权威）：
     * 旧口径会把「朝向 char_outside」换成 {@code 某某 (<image3>)}，模型就把参考图里的人另画一份进来。
     * 朝向语境降级成 {@code off-screen}（B9 词表），槽位不出现在这一格。
     */
    @Test
    void buildStoryboard_offPanelFigureIsNotDrawnIntoThePanel() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_a", "楚天", "char_b", "楚宗主"))
                .beats(List.of(
                        beat("0-2s", "medium shot, eye level", "dust lifts off the stone floor",
                                character("char_a", "left of frame, mid-ground, turned toward char_b", "chin raised"),
                                character("char_b", "right of frame, mid-ground, facing off-screen", "shoulders squared")),
                        beat("2-4s", "medium close-up, slightly low angle", "the candle gutters",
                                character("char_a", "left foreground, half-length, turned toward char_b", "jaw tight"))))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a", "char_b"))
                .characterImageRefs(Map.of("char_a", "image1", "char_b", "image2"))
                .build());
        assertNotNull(prompt);
        // 第 1 格两人都在：朝向目标只写名字
        assertTrue(prompt.contains("楚天 (<image1>) left of frame, mid-ground, turned toward 楚宗主, chin raised"));
        assertEquals(1, countInPanel(prompt, "In the panel at row 1, column 1,", "<image2>"));
        // 第 2 格只有楚天：楚宗主不在这一格 → 名字与槽位都不许出现，降级成 off-screen
        String second = panelLine(prompt, "In the panel at row 2, column 1,");
        assertTrue(second.contains("turned toward off-screen"), second);
        assertFalse(second.contains("楚宗主"), second);
        assertFalse(second.contains("<image2>"), second);
    }

    /** 空镜（{@code characters: []}）的 {@code desc} 里写了人 = LLM 违约：人一律不写，格子照旧占位。 */
    @Test
    void buildStoryboard_emptyPanelKeepsItsCellWithoutPeople() {
        ShotVisualPlan plan = ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .characterNames(Map.of("char_a", "楚天"))
                .beats(List.of(ShotVisualPlan.Beat.builder()
                        .time("0-2s")
                        .camera("wide shot, high angle")
                        .desc("char_a 冲进屋内")
                        .characters(List.of())
                        .props(List.of())
                        .dialogue(List.of())
                        .build()))
                .build();
        String prompt = adapter().buildStoryboardPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(plan)
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .build());
        assertNotNull(prompt);
        // 这一格照旧占位（机位句留下），但"画框外的人"与剥剩下的半句话都不进 prompt
        String panel = panelLine(prompt, "In the panel,");
        assertTrue(panel.contains("wide shot, high angle"), panel);
        assertFalse(panel.contains("冲进屋内"), panel);
        assertFalse(panel.contains("楚天"), panel);
        assertFalse(panel.contains("char_a"), panel);
    }

    // ======================== 夹具 ========================

    private static ShotVisualPlan.Beat beat(String time, String camera, String desc,
                                            ShotVisualPlan.BeatCharacter... characters) {
        return ShotVisualPlan.Beat.builder()
                .time(time)
                .camera(camera)
                .desc(desc)
                .mood("calm")
                .hardCut(false)
                .characters(List.of(characters))
                .props(List.of())
                .dialogue(List.of())
                .build();
    }

    private static ShotVisualPlan.BeatCharacter character(String id, String position, String desc) {
        return ShotVisualPlan.BeatCharacter.builder()
                .characterId(id)
                .position(position)
                .desc(desc)
                .build();
    }

    /** id 若出现在逐格正文（首个换行之后）即视为裸 id 泄漏；身份锚定段允许出现。 */
    private static boolean leaksInShots(String prompt, String id) {
        int shotsStart = prompt.indexOf('\n');
        if (shotsStart < 0) {
            shotsStart = prompt.length();
        }
        return prompt.indexOf(id, shotsStart) >= 0;
    }

    /** 取以 {@code opener} 开头的那一格整行文本（到下一个换行为止）；找不到返回空串。 */
    private static String panelLine(String prompt, String opener) {
        int at = prompt.indexOf(opener);
        if (at < 0) {
            return "";
        }
        int end = prompt.indexOf('\n', at);
        return end < 0 ? prompt.substring(at) : prompt.substring(at, end);
    }

    /** 某一格里某指代出现的次数 —— "每格每个角色的槽位只出现一次"的断言口径。 */
    private static int countInPanel(String prompt, String opener, String token) {
        return count(panelLine(prompt, opener), token);
    }

    /** 整串文本里某片段出现的次数 —— "外貌只在锚定句写一次"的断言口径。 */
    private static int count(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }
}
