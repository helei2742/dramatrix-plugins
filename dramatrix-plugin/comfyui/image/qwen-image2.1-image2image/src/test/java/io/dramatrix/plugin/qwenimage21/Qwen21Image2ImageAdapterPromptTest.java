package io.dramatrix.plugin.qwenimage21;

import io.dramatrix.plugin.dto.Image2ImagePromptRequest;
import io.dramatrix.plugin.dto.PluginMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qwen21Image2ImageAdapter#buildPrompt} 的拼接口径测试。
 *
 * <p>锁定的核心不变量（改动组装逻辑时这几条一旦破就不再是"首帧指令"了）：</p>
 * <ol>
 *   <li><b>槽位与接线同序</b>：{@code <image1>} = 场景图，{@code <image2>…} = 按 {@code characterIds} 顺序
 *       真正挂上参考图的角色；跳过 {@code temp_char_}、无图角色、超出 {@code MAX_REFERENCE_IMAGES}(10) 的部分。
 *       错一格就等于把 prompt 里的"A"指到参考图里的"B"。</li>
 *   <li><b>有参考图的角色不写一个字外貌</b>：身份只靠指向 {@code <imageN>}。写外貌 = 让模型"再画一个
 *       长这样的人"，是多角色帧凭空多人的主因。</li>
 *   <li><b>跨角色指代只走 id → 槽位一条通道</b>：{@code position}/{@code action}/{@code camera} 里出现的
 *       对方 id 必须被换成槽位，且不许残留裸 id；替换要连两侧空白一起吃掉。</li>
 *   <li><b>单图输入不写槽位标签</b>（官方约定 N=1 时用自然语言指代）。</li>
 *   <li><b>结尾无条件兜底</b>：排他子句 + 反英雄帧子句；单段无换行、以「。」收尾。</li>
 * </ol>
 *
 * <p><b>资产（道具）与角色同口径</b>：有参考图的正式资产只写槽位指代、零外貌；无图的（临时资产 /
 * 资产图未就绪 / 超槽位上限）退化为文字描述。槽位顺序 场景 → 角色 → 资产，与
 * {@code applyReferenceImages} 共用 {@code planRefSlots} 这一唯一真源。</p>
 *
 * <p><b>已知边界</b>：id 替换池 = 角色/资产槽位表 ∪ {@code characters[]} ∪ {@code assets[]} ∪
 * {@code frameCharacterIds[]}。一个四处都不在、却被别人 {@code action}/{@code position} 引用的 id 会原样留在
 * prompt 里（模型读不懂这种机械 token）。这属于 LLM 违约输出（prompt.md 明令禁止），
 * 组装侧刻意不做正则兜底扫描——那属于 Java 侧猜测，违背"语义判断下沉 LLM"的项目铁律。</p>
 */
class Qwen21Image2ImageAdapterPromptTest {

    /** 与 adapter 内常量同文案：身份保持只出现一句总括，不逐点复述外貌。 */
    private static final String IDENTITY_KEEP = "保持各参考图中人物的面部特征、发型、体型与服装不变";
    private static final String CLAUSE_EXCLUSIVE = "画面中只出现上述人物，不出现额外人物或人群";
    private static final String CLAUSE_NOBODY = "画面中不出现任何人物或人群";
    /** 与 adapter 的 {@code ANTI_HERO_CLAUSE} 同文案（收尾构图 + 不看镜头，位置最新鲜）。 */
    private static final String CLAUSE_ANTI_HERO = "不要排成海报式队列或对称正面站桩，不要让人物直视镜头"
            + "（本镜是第一人称主观视角或对镜独白时除外），"
            + "人物视线落在画面内的人或物上，构图保持不对称、有前后景层次";

    /**
     * 与 adapter 的 {@code MAX_REFERENCE_IMAGES} 手工同步（adapter 里是 private，测不到）。
     * 改 adapter 的槽位上限时**必须**同步这里，否则边界用例会失去意义。
     * 真机上 TextEncodeQwenImage21 的 {@code images} 实测上限为 12。
     */
    private static final int MAX_REFERENCE_IMAGES = 12;

    private static Qwen21Image2ImageAdapter adapter() {
        return new Qwen21Image2ImageAdapter(new PluginMetadata());
    }

    // ======================== 用例 1：场景 + 2 个有参考图的角色（全字段） ========================

    private static final String LLM_SCENE_TWO_CHARS = """
            {"characters":[
              {"id":"char_chu_tian","facing":"三分之四背向镜头","posture":"站立",
               "action":"右手按在剑柄上，视线扫向 char_su_wan 身后的殿门方向",
               "position":"中景中央偏左，位于石柱前，离镜头约四米"},
              {"id":"char_su_wan","facing":"侧身面朝画面右","posture":"蹲跪",
               "action":"一只手撑地，抬头看向 char_chu_tian",
               "position":"中景右侧偏后，在 char_chu_tian 的斜后方，比 char_chu_tian 小一半"}
            ],
            "frameCharacterIds":["char_chu_tian","char_su_wan"],
            "scene":"夜晚的大殿，高耸石柱，青铜灯焰，地面浮着薄尘",
            "camera":{"shot":"中景","angle":"平视",
              "cameraAt":"char_chu_tian 右后方约两米、胸口高度","target":"char_chu_tian 的肩背与前方殿门",
              "framing":"人物压在左三分之一线、右侧石柱形成纵深，前景柱沿压住画面右上角"},
            "lighting":"青铜灯暖光自画面右下打来，面部左侧留深影",
            "assets":[{"id":"asset_yu_pai","action":"悬在他腰间，随动作轻晃","position":"左前景"}],
            "preserve":"他腰间的旧玉牌必须保留"}""";

    /** 注意：这里的英文外貌是"若被读进 prompt 就说明泄漏"的探针，不是期望输出。 */
    private static Image2ImagePromptRequest sceneTwoChars() {
        Map<String, String> charAppearance = new LinkedHashMap<>();
        charAppearance.put("char_chu_tian", "tall, slender, black robe");
        charAppearance.put("char_su_wan", "young woman, pale blue dress");
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put("asset_yu_pai", "一枚残缺的青色古玉牌，表面有裂纹");
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_chu_tian", "image1");   // value 只作"有图"信号，槽位由组装侧按顺序重算
        refs.put("char_su_wan", "image2");

        return Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(LLM_SCENE_TWO_CHARS))
                .characterIds(List.of("char_chu_tian", "char_su_wan"))
                .charAppearancePrompts(charAppearance)
                .assetAppearancePrompts(assetAppearance)
                .characterImageRefs(refs)
                .scenePrompt("夜晚的大殿，高耸石柱，青铜灯焰,")
                .cameraPrompt("medium shot,eye_level angle,static motion,")
                .lighting("青铜灯暖光")
                .fixPrompt("电影感写实，东方玄幻，冷调高对比")
                .build();
    }

    @Test
    void buildPrompt_sceneAndTwoReferencedCharacters_slotsCountFromTwo() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        // ① 两段式骨架：主体定义在前，场景占 image1，并声明"人物只来自角色参考图"
        assertTrue(prompt.startsWith("主体定义：\n<image1>（场景参考图）：环境底板，只取用其环境结构、空间关系、材质与光线基调；"));
        assertTrue(prompt.contains("画面中出现的人物只来自角色参考图，道具只来自道具参考图"));
        // ② 槽位：场景占 1，两个角色依次 2、3；主体定义行一次性承载 动作，站位，朝向，姿态
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，右手按在剑柄上"));
        assertTrue(prompt.contains("<image3>（角色参考图）中的人物，一只手撑地"));
        assertTrue(prompt.contains("中景中央偏左，位于石柱前，离镜头约四米"));   // char1 站位进主体定义
        assertTrue(prompt.contains("中景右侧偏后"));                              // char2 站位进主体定义
        // ③ 朝向 / 身体状态按固定顺序（动作，站位，朝向，姿态）渲染在主体定义行尾
        assertTrue(prompt.contains("三分之四背向镜头，站立"));
        assertTrue(prompt.contains("侧身面朝画面右，蹲跪"));
        // ④ 同一角色在所有字段里指向同一个槽位（跨角色引用均解析成同一槽位）
        assertTrue(prompt.contains("视线扫向<image3>中的人物身后的殿门方向"));
        assertTrue(prompt.contains("在<image2>中的人物的斜后方，比<image2>中的人物小一半"));
        assertTrue(prompt.contains("抬头看向<image2>中的人物"));
        // 取景句来自分镜 cameraPrompt（medium/eye_level → 中景，平视）；草稿 5 段式 camera 已不映射，cameraAt/target/framing 不渲染
        assertTrue(prompt.contains("取景：中景，平视"));
        assertFalse(prompt.contains("相机位于"));
        assertFalse(prompt.contains("右后方约两米"));
    }

    @Test
    void buildPrompt_referencedCharacters_neverLeakAppearanceText() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        // 核心：有参考图的角色，外貌一个字都不许进 prompt（否则 = "再画一个这样的人"的生成指令）
        assertFalse(prompt.contains("tall, slender, black robe"));
        assertFalse(prompt.contains("young woman, pale blue dress"));
        // 且不许残留裸 id（裸 id 模型读不懂，等于没写指代）
        assertFalse(prompt.contains("char_chu_tian"));
        assertFalse(prompt.contains("char_su_wan"));
    }

    @Test
    void buildPrompt_alwaysAppendsIdentityKeepAndAntiHeroClauses() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        assertTrue(prompt.contains(IDENTITY_KEEP));
        assertTrue(prompt.contains(CLAUSE_EXCLUSIVE));
        assertTrue(prompt.contains(CLAUSE_ANTI_HERO));
        // 兜底子句必须是最后两条，别被后续字段插队
        assertTrue(prompt.endsWith(CLAUSE_ANTI_HERO + "。"));
        // 身份保持子句在全篇只出现一次（点状复述会引发漂移）
        assertEquals(1, countOccurrences(prompt, IDENTITY_KEEP));
    }

    @Test
    void buildPrompt_usesTwoSectionSkeletonAndEndsWithFullStop() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        // 新契约：两段式骨架（主体定义：/详细描述：），不再是单段无换行，故只校验骨架存在 + 收尾句号
        assertTrue(prompt.contains("主体定义："));
        assertTrue(prompt.contains("详细描述："));
        assertEquals(prompt.trim(), prompt);
        assertTrue(prompt.endsWith("。"));
        // 拼接残留检查：不应出现连续分号 / 逗号 / 逗分
        assertFalse(prompt.contains("；；"));
        assertFalse(prompt.contains("，，"));
        assertFalse(prompt.contains("，；"));
    }

    @Test
    void buildPrompt_cameraIsRenderedAsFiveFieldClause() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        // 取景句来自分镜 cameraPrompt（medium/eye_level → 中景，平视）。
        // 草稿的 5 段式 camera 已不再映射：其 cameraAt/target/framing 一个字都不许渲染。
        assertTrue(prompt.contains("取景：中景，平视"));
        assertFalse(prompt.contains("相机位于"));
        assertFalse(prompt.contains("右后方约两米"));
        assertFalse(prompt.contains("构图："));
    }

    @Test
    void buildPrompt_plannedCameraOverridesLlmSelfChosenShotType() {
        // 用户实测的坏例形态：分镜锁定 over-shoulder + low_angle，首帧草稿却自拟成
        // 「中全景 + 平视 + 两人左右对称」——正是"人物总在看镜头 / 全片一个机位"的来源。
        // 取景必须以分镜为准，草稿只能补 cameraAt/target/framing。
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"三分之四背向镜头","posture":"站立","action":"看向 char_b","position":"左前景"},
                   {"id":"char_b","facing":"侧身面朝画面左","posture":"坐","action":"回视 char_a","position":"右中景"}
                 ],
                 "frameCharacterIds":["char_a","char_b"],
                 "scene":"深夜的书房，案上残烛",
                 "camera":{"shot":"中全景，两人一左一右对称站位","angle":"平视",
                   "cameraAt":"两人正前方约三米、胸口高度","target":"两人的脸",
                   "framing":"人物左右分布，背景窗棂压住画面上缘"}}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        refs.put("char_b", "image2");
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a", "char_b"))
                .characterImageRefs(refs)
                .scenePrompt("深夜的书房")
                .cameraPrompt("over-shoulder shot,low_angle angle,push motion,")
                .build());

        assertNotNull(prompt);
        // 分镜机位胜出：过肩视角 + 低角度（取景句由 cameraPrompt 驱动）
        assertTrue(prompt.contains("取景：过肩视角，低角度"));
        // 草稿自拟的景别 / 角度一个字都不许留下
        assertFalse(prompt.contains("中全景"));
        assertFalse(prompt.contains("平视"));
        // 草稿的 5 段式 camera 不映射：cameraAt/target 不渲染
        assertFalse(prompt.contains("相机位于"));
    }

    @Test
    void buildPrompt_plannedCameraAbsent_rendersNoCameraClause() {
        // 分镜没给机位（cameraPrompt 缺失）且无草稿 5 段式 camera（新契约已不再映射）：
        // 取景句整体不渲染，不静默臆造机位
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"侧身面朝画面右","posture":"站立","action":"看向门外","position":"中景偏左"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "scene":"雨夜的青石长街",
                 "camera":{"shot":"全景，道路向画面右上角收束","angle":"低角度",
                   "cameraAt":"街口地面齐膝高度","target":"长街尽头的灯笼","framing":"路面斜向伸向画面右上"}}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(refs)
                .scenePrompt("雨夜的青石长街")
                .build());

        assertNotNull(prompt);
        // 没有 cameraPrompt 驱动、草稿 5 段式 camera 也不再映射 → 不存在「取景：…」取景句
        assertFalse(prompt.contains("取景："));
        assertFalse(prompt.contains("相机位于"));
    }

    @Test
    void buildPrompt_plannedTopWithHighAngle_doesNotRepeatAngle() {
        // 分镜表的固定搭配 top+high_angle（"高角度"已被"高角度俯拍"吸收）不应渲染成
        // "高角度俯拍，高角度"；同理 low+low_angle。
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"三分之四背向镜头","posture":"蹲跪","action":"被围在中央","position":"画面中下部的小身影"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "scene":"雪地空地",
                 "camera":{"shot":"中景","angle":"平视","cameraAt":"正上方约四米","target":"雪地上的身影","framing":"地面占主体，四周留出空雪"}}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(refs)
                .scenePrompt("雪地空地")
                .cameraPrompt("top shot,high_angle angle,aerial motion,")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("取景：高角度俯拍"));
        assertFalse(prompt.contains("高角度俯拍，高角度"));
        // 草稿 5 段式 camera 不映射：cameraAt 不渲染
        assertFalse(prompt.contains("相机位于"));
    }

    @Test
    void buildPrompt_plannedPov_rendersSubjectiveCameraWording() {        // pov 是最容易被首帧"改正"的机位：必须原样落地成第一人称主观视角
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"背对镜头","posture":"行走","action":"沿长廊向深处走去","position":"中景偏左的背向身影"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "scene":"深夜的木质长廊",
                 "camera":{"shot":"第一人称主观视角","angle":"平视","cameraAt":"廊道起点、与视线等高","target":"长廊深处的身影","framing":"两侧门框向内收束"}}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(refs)
                .scenePrompt("深夜的木质长廊")
                .cameraPrompt("pov shot,eye_level angle,static motion,")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("取景：第一人称主观视角，平视"));
    }

    @Test
    void buildPrompt_finalClauseForbidsEyeContactWithCamera() {
        // "人物总在看镜头"是图像模型的默认毛病：收尾（位置最新鲜）必须压住它，并给 POV 留例外
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        assertTrue(prompt.contains("不要让人物直视镜头"));
        assertTrue(prompt.contains("人物视线落在画面内的人或物上"));
        assertTrue(prompt.contains("本镜是第一人称主观视角或对镜独白时除外"));
    }

    @Test
    void buildPrompt_sceneFieldKeepsLightingOutOfItsOwnChannel() {
        String prompt = adapter().buildPrompt(sceneTwoChars());
        assertNotNull(prompt);

        // scene / lighting / style 分属两条通道，各自只出现一次，避免自相矛盾
        assertTrue(prompt.contains("夜晚的大殿，高耸石柱，青铜灯焰，地面浮着薄尘"));
        assertTrue(prompt.contains("光线：青铜灯暖光"));
        assertTrue(prompt.contains("画面风格：电影感写实，东方玄幻，冷调高对比"));
        assertTrue(prompt.contains("画面中的一枚残缺的青色古玉牌，表面有裂纹，悬在他腰间，随动作轻晃，左前景"));
        // preserve 字段已按设计收敛删除：不再生成保留子句
        assertFalse(prompt.contains("必须保留"));
    }

    // ======================== 用例 2：纯环境帧（1 张参考图 → 不写槽位标签） ========================

    @Test
    void buildPrompt_sceneOnly_singleReferenceImageHasNoSlotTags() {
        String llm = """
                {"scene":"雨夜的青石长街，湿亮反光的路面，两侧酒旗被风掀起",
                 "camera":{"shot":"空街纵深的低机位全景，道路向画面右上角收束","angle":"低角度",
                   "cameraAt":"街口地面齐膝高度，贴近左侧墙角","target":"长街尽头的灯笼",
                   "framing":"路面斜向伸向画面右上，左侧酒旗压住前景区"},
                 "lighting":"两盏街灯硬光，雨丝在灯下可见"}""";
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of())
                .scenePrompt("雨夜的青石长街")
                .build());

        assertNotNull(prompt);
        // 官方约定：全画面只有 1 张输入图时不写 <imageN> 标签
        assertFalse(prompt.contains("<image"));
        assertTrue(prompt.startsWith("主体定义：\n场景参考图：环境底板，只取用其环境结构、空间关系、材质与光线基调；"));
        // 无人帧：排他子句取"不出现任何人物"分支
        assertTrue(prompt.contains(CLAUSE_NOBODY));
        assertTrue(prompt.endsWith(CLAUSE_ANTI_HERO + "。"));
        // 没有出镜角色 → 不追加身份保持子句
        assertFalse(prompt.contains(IDENTITY_KEEP));
    }

    @Test
    void buildPrompt_emptyCharacterIds_fallsBackToCharactersOrderForSlots() {
        // 蓝图链路：管线传入的 characterIds 是唯一的「出镜顺序」来源（由 ImageWorkerInvocationV4
        // 按第 1 拍 characters 得出）。它为空的兜底 = characters[] 的书写顺序。
        // 注意：旧草稿里的 frameCharacterIds 通道已随蓝图移除（那是 IMAGE_BUILD 的产物，规划期不可得）。
        String llm = """
                {"characters":[
                   {"id":"char_b","facing":"背对镜头","posture":"站立","action":"看向 char_a","position":"右侧"},
                   {"id":"char_a","facing":"面朝镜头","posture":"站立","action":"看向 char_b","position":"左侧"}
                 ],
                 "frameCharacterIds":["char_a","char_b"],
                 "scene":"城门口"}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        refs.put("char_b", "image2");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of())          // 管线没给顺序 → 回退 characters[] 书写顺序（char_b 在前）
                .characterImageRefs(refs)
                .scenePrompt("城门口")
                .build());

        assertNotNull(prompt);
        // 场景占 image1；随后按 characters[] 顺序：char_b → image2，char_a → image3
        // 新契约：主体定义行一次性承载 动作，站位，朝向，姿态
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，看向<image3>中的人物，右侧，背对镜头，站立"));
        assertTrue(prompt.contains("<image3>（角色参考图）中的人物，看向<image2>中的人物，左侧，面朝镜头，站立"));
    }

    // ======================== 用例 3：无场景 + 群体 + temp 角色 ========================

    @Test
    void buildPrompt_noScene_groupAndTempCharFallBackToAppearanceText() {
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"背对镜头","posture":"蹲跪",
                    "action":"一只手撑地，抬头望向 group_villagers 的方向","position":"左前景，画面下缘"},
                   {"type":"group","id":"group_villagers","appearance":"十余名披着湿斗篷的村民",
                    "posture":"站立","facing":"三分之四背向镜头","action":"在远处围成一圈","position":"背景中央"},
                   {"id":"temp_char_1712","action":"站在门边抱着胳膊","position":"右中景"}
                 ],
                 "frameCharacterIds":["char_a","group_villagers","temp_char_1712"],
                 "camera":{"shot":"低机位中全景","angle":"低角度","cameraAt":"蹲者右前方约一米、贴近地面","target":"他撑地的手"}}""";

        Map<String, String> charAppearance = new LinkedHashMap<>();
        charAppearance.put("temp_char_1712", "瘦高，灰布短打，左眉有一道旧疤");
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");   // 仅 1 张图（无场景）→ 仍不写槽位标签

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a", "group_villagers", "temp_char_1712"))
                .charAppearancePrompts(charAppearance)
                .characterImageRefs(refs)
                .scenePrompt("")
                .build());

        assertNotNull(prompt);
        // 无场景 → 两段式骨架，主体定义首行即角色（无场景参考图标签）
        assertTrue(prompt.startsWith("主体定义：\n角色参考图中的人物"));
        // 只有 1 张参考图 → 不用槽位标签，用自然语言指代
        assertFalse(prompt.contains("<image"));
        assertTrue(prompt.contains("角色参考图中的人物，一只手撑地"));
        assertTrue(prompt.contains("左前景，画面下缘"));

        // 无参考图的出镜角色：文字是唯一来源，必须写外貌
        // 群体无图 → 稳定序数指代（自带 appearance 不再单独渲染）
        assertTrue(prompt.contains("画面中第二个出镜人物"));
        assertTrue(prompt.contains("在远处围成一圈，背景中央"));
        // temp_char 用管线外貌
        assertTrue(prompt.contains("画面中第三个出镜人物（瘦高，灰布短打，左眉有一道旧疤）"));
        assertTrue(prompt.contains("站在门边抱着胳膊，右中景"));

        // 跨角色引用（指向群众）也要换成序数指代，且不留空白断裂
        assertTrue(prompt.contains("抬头望向画面中第二个出镜人物的方向"));
        // 旧输出缺 facing/posture 时降级：只跳过缺失子句，不炸
        assertFalse(prompt.contains("处于状态"));
        assertFalse(prompt.contains("朝向为，"));
        assertTrue(prompt.contains("站在门边抱着胳膊"));
        assertTrue(prompt.contains(IDENTITY_KEEP));   // 有 1 个角色挂了参考图
    }

    // ======================== 用例 4：回归——distinct 绝不泄漏（用户实测反馈） ========================

    /**
     * 真实反馈数据：A 的 position 里用「"比灰袍少年小一半"」引用了 B 的 {@code distinct}，
     * 而「灰袍少年」正是 A 自己的 {@code distinct}（写入时写反了）。
     * 旧实现会把这两个外貌词拼进 prompt → 画面里出现两个"灰袍少年"。
     * 现口径：有参考图的角色一个字外貌都不写，跨角色关系只认 id。
     */
    @Test
    void buildPrompt_distinctIsNeverRenderedWhenCharacterHasReferenceImage() {
        String llm = """
                {"characters":[
                  {"id":"char_ai_wei_ke_er_man","facing":"三分之四背向镜头","posture":"蹲跪","distinct":"灰袍少年",
                   "action":"一只手撑在雪地上，另一只手护住胸前的行囊","position":"左前景，贴近画面下缘"},
                  {"id":"char_mao_yi_nan","facing":"侧身面朝画面右","posture":"站立","distinct":"黑斗篷男子",
                   "action":"握着灯绳，正回头看向 char_ai_wei_ke_er_man",
                   "position":"右中景偏后，比 char_ai_wei_ke_er_man 小一半，被飘雪拉开距离"}
                ],
                "frameCharacterIds":["char_ai_wei_ke_er_man","char_mao_yi_nan"],
                "scene":"风雪夜的山道，两侧是覆雪的岩壁，地面脚印被新雪半掩",
                "camera":{"shot":"中全景，人物分居画面左右，中间空出雪道纵深","angle":"平视略高",
                  "cameraAt":"char_mao_yi_nan 右后方约三米、头部高度","target":"两人之间的雪道纵深",
                  "framing":"左前景人物压住左下角，右中景人物形成第二层"},
                "lighting":"一盏提灯的暖光自画面右侧打来"}""";

        Map<String, String> appearance = new LinkedHashMap<>();
        appearance.put("char_ai_wei_ke_er_man", "灰袍少年");   // 管线外貌里也带了同一份"外貌"
        appearance.put("char_mao_yi_nan", "黑斗篷男子");
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_ai_wei_ke_er_man", "image1");
        refs.put("char_mao_yi_nan", "image2");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_ai_wei_ke_er_man", "char_mao_yi_nan"))
                .charAppearancePrompts(appearance)
                .characterImageRefs(refs)
                .scenePrompt("风雪夜的山道")
                .build());

        assertNotNull(prompt);
        // 关键回归断言：distinct 与外貌文字一个字都不能出现
        assertFalse(prompt.contains("灰袍少年"));
        assertFalse(prompt.contains("黑斗篷男子"));
        // 跨角色关系被解析成槽位（且两侧空白被吃掉，读起来不断裂）
        assertTrue(prompt.contains("比<image2>中的人物小一半，被飘雪拉开距离"));
        assertTrue(prompt.contains("正回头看向<image2>中的人物"));
        // 草稿 5 段式 camera 已不映射，cameraPrompt 缺失 → 取景句整体不渲染
        assertFalse(prompt.contains("相机位于"));
        // 替换后不应留下「中的人物 」（槽位指代与后文之间存在空格 = 漏吃空白）
        assertFalse(prompt.contains("中的人物 "));
        assertFalse(prompt.contains("中的人群 "));
    }

    @Test
    void buildPrompt_crossReferencePrefersLongerIdToAvoidPrefixCollision() {
        // char_a 是 char_ab 的前缀：替换必须长 id 先行，否则 char_ab 会被切成「<image2>中的人物b」
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"面朝镜头","posture":"站立","action":"看向 char_ab","position":"左侧"},
                   {"id":"char_ab","facing":"背对镜头","posture":"站立","action":"看向 char_a","position":"右侧"}
                 ],
                 "frameCharacterIds":["char_a","char_ab"],
                 "scene":"街角"}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_a", "image1");
        refs.put("char_ab", "image2");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a", "char_ab"))
                .characterImageRefs(refs)
                .scenePrompt("街角")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，看向<image3>中的人物，左侧，面朝镜头，站立"));
        assertTrue(prompt.contains("<image3>（角色参考图）中的人物，看向<image2>中的人物，右侧，背对镜头，站立"));
        assertFalse(prompt.contains("中的人物b"));
        assertFalse(prompt.contains("char_"));
    }

    // ======================== 用例 5：槽位上限与边界 ========================

    @Test
    void buildPrompt_referenceSlotsNeverExceedMaxReferenceImages() {
        int max = MAX_REFERENCE_IMAGES;
        List<String> ids = new ArrayList<>();
        StringBuilder chars = new StringBuilder();
        Map<String, String> refs = new LinkedHashMap<>();
        for (int i = 1; i <= max + 2; i++) {
            String id = "char_" + i;
            ids.add(id);
            refs.put(id, "image" + i);
            // 末尾 2 个角色刻意排到最后，用于验证被截断后不再占用槽位
            chars.append(i > 1 ? "," : "").append(
                    "{\"id\":\"").append(id).append("\",\"facing\":\"背对镜头\",\"posture\":\"站立\",\"position\":\"第")
                    .append(i).append("位\"}");
        }
        String llm = "{\"characters\":[" + chars + "],\"frameCharacterIds\":["
                + String.join(",", ids.stream().map(s -> "\"" + s + "\"").toList())
                + "],\"scene\":\"大殿\"}";

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(ids)
                .characterImageRefs(refs)
                .scenePrompt("大殿")
                .build());

        assertNotNull(prompt);
        // 场景占 image1，角色最多占到 image{max}（= 第 max-1 个角色）；不许出现 max+1 及以后
        assertTrue(prompt.contains("<image" + max + ">（角色参考图）中的人物，第" + (max - 1) + "位"));
        assertFalse(prompt.contains("<image" + (max + 1) + ">"));
        assertFalse(prompt.contains("<image" + (max + 2) + ">"));
        // 超限角色仍要出现在画面里（只是身份走文字兜底，不能整条丢掉）
        assertTrue(prompt.contains("第" + max + "位"));
        assertTrue(prompt.contains("第" + (max + 1) + "位"));
    }

    @Test
    void buildPrompt_tempCharPrefixedIdNeverConsumesReferenceSlot() {
        // 即使管线把 temp_char_* 塞进 refs（不该发生），组装侧也必须忽略它，否则槽位整体错位
        String llm = """
                {"characters":[
                   {"id":"char_ok","facing":"面朝镜头","posture":"站立","action":"站着","position":"左侧"},
                   {"id":"temp_char_9","facing":"背对镜头","posture":"站立","action":"站在门口","position":"右侧"}
                 ],
                 "frameCharacterIds":["char_ok","temp_char_9"],
                 "scene":"门厅"}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_ok", "image1");
        refs.put("temp_char_9", "image2");     // 脏数据：临时角色不该有参考图

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_ok", "temp_char_9"))
                .characterImageRefs(refs)
                .scenePrompt("门厅")
                .build());

        assertNotNull(prompt);
        // 正式角色稳定占 image2，不因脏数据错位
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，站着，左侧"));
        // includeTempChar=true：有参考图的临时角色也按序入槽（image3），不丢条、不错位
        assertTrue(prompt.contains("<image3>（角色参考图）中的人物，站在门口，右侧"));
        // 不残留裸 id，也不走"另有…人物"的文字兜底（有图即指向参考图）
        assertFalse(prompt.contains("temp_char_9"));
        assertFalse(prompt.contains("画面中另有"));
    }

    // ======================== 用例 6：坏输入 ========================

    @Test
    void buildPrompt_nullRequestReturnsNull() {
        assertNull(adapter().buildPrompt(null));
    }

    @Test
    void buildPrompt_unparsableDraftYieldsNullPlanReturnsNull() {
        // 草稿不可解析（旧链路里由插件清洗 JSON）：规划上移后由 VISUAL_PLAN worker 负责，
        // 插件侧只需保证「拿不到可用蓝图 ⇒ 显式返回 null」，不许拼半截
        assertNull(adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan("模型跑题了")).build()));
    }

    @Test
    void buildPrompt_emptyDraftYieldsNullPlanReturnsNull() {
        assertNull(adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan("{}")).build()));
    }

    @Test
    void buildPrompt_whitespaceInFieldsIsCollapsedAndChineseKept() {
        // clean() 必须保留中文（不能走 FLUX 的 sanitizePromptPart），并把换行/连续空白压平
        String llm = """
                {"characters":[
                   {"id":"char_x","facing":"背对镜头","posture":"站立",
                    "action":"  ① 抬手\\n\\n按在窗框上  ","position":"左前景"}
                 ],
                 "frameCharacterIds":["char_x"],
                 "scene":"  深夜\\n\\n的书房  "}""";
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_x"))
                .characterImageRefs(Map.of("char_x", "image1"))
                .scenePrompt("深夜的书房")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("深夜 的书房"));        // 中文原样保留、空白压平
        assertTrue(prompt.contains("抬手 按在窗框上"));
    }

    @Test
    void buildPrompt_characterMissingFacingAndPostureRendersWithoutEmptyClauses() {
        // 旧 LLM 输出（无 facing/posture）：降级为只渲染 position，不产生「处于状态」「朝向为，」这类空子句
        String llm = """
                {"characters":[{"id":"char_old","action":"站在门边","position":"右中景"}],
                 "frameCharacterIds":["char_old"],
                 "scene":"门厅"}""";
        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_old"))
                .characterImageRefs(Map.of("char_old", "image1"))
                .scenePrompt("门厅")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，站在门边，右中景"));
        assertFalse(prompt.contains("处于状态"));
        assertFalse(prompt.contains("朝向为，"));
        assertFalse(prompt.contains("朝向为；"));
    }

    @Test
    void buildPrompt_offscreenCharacterReferenceBecomesOutOfFramePhrase() {
        // 引用了「在 characters[] 里声明、但没进 frameCharacterIds[]」的角色 = 要求把它画出来；
        // 必须换成"画面外的人物"，而不是留裸 id 或凭空生成外貌
        String llm = """
                {"characters":[
                   {"id":"char_in","facing":"背对镜头","posture":"站立","action":"回头看向 char_out","position":"左侧"},
                   {"id":"char_out","facing":"面朝镜头","posture":"站立","action":"站在门外","position":"画外"}
                 ],
                 "frameCharacterIds":["char_in"],
                 "scene":"长廊"}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_in", "image1");
        refs.put("char_out", "image2");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_in"))
                .characterImageRefs(refs)
                .scenePrompt("长廊")
                .build());

        assertNotNull(prompt);
        assertTrue(prompt.contains("回头看向画面外的人物"));
        assertFalse(prompt.contains("char_out"));
        // 未入画者不许被渲染成一条角色子句（"提到就等于要求画出来"）
        assertFalse(prompt.contains("画面外的人物位于"));
    }

    @Test
    void buildPrompt_idPresentOnlyInFrameCharacterIds_isStillResolved() {
        // 回归：id 池必须包含 frameCharacterIds[]（LLM 明确点过名的出镜角色），
        // 只收 characters[] ∪ slotById 会让这类 id 漏在外面 → prompt 里残留裸 id
        String llm = """
                {"characters":[
                   {"id":"char_in","facing":"背对镜头","posture":"站立","action":"回头看向 char_ghost","position":"左侧"}
                 ],
                 "frameCharacterIds":["char_in","char_ghost"],
                 "scene":"长廊"}""";
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("char_in", "image1");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_in", "char_ghost"))
                .characterImageRefs(refs)
                .scenePrompt("长廊")
                .build());

        assertNotNull(prompt);
        assertFalse(prompt.contains("char_ghost"));
        assertTrue(prompt.contains("回头看向"));
    }

    // ======================== 用例 7：资产（道具）接入参考图 ========================

    /**
     * C1：资产有参考图 → 身份指向 {@code <imageN>（道具参考图）}，<b>一个字外貌都不写</b>。
     * 与角色同一条铁律：写外貌 = 让模型"再画一个这样的物件"。
     */
    @Test
    void buildPrompt_assetWithReferenceImage_usesSlotAndWritesNoAppearance() {
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"面朝镜头","posture":"站立",
                    "action":"握着 asset_sword，指节发白","position":"中景中央"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "assets":[
                   {"id":"asset_sword","action":"被他握在手里，剑尖朝下","position":"画面左下角，剑身斜向伸向中央"}
                 ],
                 "scene":"雨夜的庭院"}""";
        Map<String, String> charRefs = new LinkedHashMap<>();
        charRefs.put("char_a", "image1");
        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put("asset_sword", "image1");
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put("asset_sword", "一把七尺长的黑鞘长剑，剑格嵌青玉");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(charRefs)
                .assetImageRefs(assetRefs)
                .assetAppearancePrompts(assetAppearance)
                .scenePrompt("雨夜的庭院")
                .build());

        assertNotNull(prompt);
        // 槽位：场景=1、角色=2、资产=3
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，握着<image3>中的物件，指节发白"));
        assertTrue(prompt.contains("<image3>（道具参考图）中的物件，被他握在手里，剑尖朝下，画面左下角，剑身斜向伸向中央"));
        // 核心：有参考图的资产，外貌文字零出现
        assertFalse(prompt.contains("七尺长的黑鞘长剑"));
        assertFalse(prompt.contains("剑格嵌青玉"));
        assertFalse(prompt.contains("asset_sword"));
        // 开场白同时声明人物与道具的来源
        assertTrue(prompt.contains("画面中出现的人物只来自角色参考图，道具只来自道具参考图"));
        // 排他子句同步扩到道具
        assertTrue(prompt.contains("道具也只出现上述那些，不凭空多出物件"));
        // 角色动作里引用资产 id → 解析成物件槽位
        assertTrue(prompt.contains("握着<image3>中的物件，指节发白"));
    }

    /**
     * C2：资产无参考图（资产图未就绪 / 临时资产）→ 退化走文字描述，与改动前行为一致。
     */
    @Test
    void buildPrompt_assetWithoutReferenceImage_fallsBackToTextDescription() {
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"背对镜头","posture":"站立","action":"站着","position":"左侧"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "assets":[
                   {"id":"asset_has","action":"悬在腰间","position":"左前景"},
                   {"id":"asset_no","action":"摆在案上","position":"右侧"}
                 ],
                 "scene":"书房"}""";
        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put("asset_has", "image1");   // 只有这一个资产生成了图
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put("asset_has", "一枚残缺的青色古玉牌");
        assetAppearance.put("asset_no", "一只三足青铜香炉");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .assetImageRefs(assetRefs)
                .assetAppearancePrompts(assetAppearance)
                .scenePrompt("书房")
                .build());

        assertNotNull(prompt);
        // 有图的走槽位、零外貌
        assertTrue(prompt.contains("<image3>（道具参考图）中的物件，悬在腰间，左前景"));
        assertFalse(prompt.contains("残缺的青色古玉牌"));
        // 无图的走文字，且不占槽位
        assertTrue(prompt.contains("画面中的一只三足青铜香炉，摆在案上，右侧"));
        assertFalse(prompt.contains("<image4>"));
    }

    /**
     * C3：槽位优先级 —— 角色优先于资产。场景 1 张 + 角色占满剩余槽位后，
     * 有图的资产也必须被截断（改走文字），而不是把角色挤掉。
     */
    @Test
    void buildPrompt_whenSlotsExhausted_charactersWinAndAssetsDegradeToText() {
        // 场景占 1，可分配给角色/资产的名额 = max - 1；这里让角色刚好占满
        int charCount = MAX_REFERENCE_IMAGES - 1;
        List<String> charIds = new ArrayList<>();
        StringBuilder chars = new StringBuilder();
        Map<String, String> charRefs = new LinkedHashMap<>();
        for (int i = 1; i <= charCount; i++) {
            String id = "char_" + i;
            charIds.add(id);
            charRefs.put(id, "image" + i);
            chars.append(i > 1 ? "," : "").append(
                    "{\"id\":\"").append(id).append("\",\"facing\":\"背对镜头\",\"posture\":\"站立\",\"position\":\"第")
                    .append(i).append("位\"}");
        }
        String llm = "{\"characters\":[" + chars + "],\"frameCharacterIds\":["
                + String.join(",", charIds.stream().map(s -> "\"" + s + "\"").toList())
                + "],\"assets\":[{\"id\":\"asset_x\",\"action\":\"被举着\",\"position\":\"右前景\"}],\"scene\":\"大殿\"}";

        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put("asset_x", "image1");     // 有图，但槽位已被角色占满
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put("asset_x", "一面铜镜");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(charIds)
                .characterImageRefs(charRefs)
                .assetImageRefs(assetRefs)
                .assetAppearancePrompts(assetAppearance)
                .scenePrompt("大殿")
                .build());

        assertNotNull(prompt);
        // 最后一个角色拿到 max 号槽位（= 角色胜出）
        assertTrue(prompt.contains("<image" + MAX_REFERENCE_IMAGES + ">（角色参考图）中的人物，第"
                + (MAX_REFERENCE_IMAGES - 1) + "位"));
        // 资产被截断：不占槽位、改走文字，且不丢条
        assertFalse(prompt.contains("<image" + (MAX_REFERENCE_IMAGES + 1) + ">"));
        assertTrue(prompt.contains("画面中的一面铜镜，被举着，右前景"));
    }

    /**
     * C4：接线与槽位的<b>同序</b>校验 —— 场景 1 → 角色 2..N → 资产紧随其后。
     * 这是「prompt 里的图号必须指到那张图」的核心断言（buildApiJson 与 buildPrompt 共用唯一真源）。
     */
    @Test
    void buildPrompt_assetSlotsFollowCharacterSlotsInOrder() {
        String llm = """
                {"characters":[
                   {"id":"char_1","facing":"背对镜头","posture":"站立","action":"站着","position":"左"},
                   {"id":"char_2","facing":"面朝镜头","posture":"站立","action":"坐着","position":"右"}
                 ],
                 "frameCharacterIds":["char_1","char_2"],
                 "assets":[
                   {"id":"asset_1","action":"插在地上","position":"左前景"},
                   {"id":"asset_2","action":"悬在半空","position":"右中景"}
                 ],
                 "scene":"荒野"}""";
        Map<String, String> charRefs = new LinkedHashMap<>();
        charRefs.put("char_1", "image1");
        charRefs.put("char_2", "image2");
        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put("asset_1", "image1");
        assetRefs.put("asset_2", "image2");
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put("asset_1", "一柄插地的长枪");
        assetAppearance.put("asset_2", "一盏悬浮的鬼火灯笼");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_1", "char_2"))
                .characterImageRefs(charRefs)
                .assetImageRefs(assetRefs)
                .assetAppearancePrompts(assetAppearance)
                .scenePrompt("荒野")
                .build());

        assertNotNull(prompt);
        // 严格顺序：场景=1、char_1=2、char_2=3、asset_1=4、asset_2=5
        assertTrue(prompt.contains("<image2>（角色参考图）中的人物，站着，左"));
        assertTrue(prompt.contains("<image3>（角色参考图）中的人物，坐着，右"));
        assertTrue(prompt.contains("<image4>（道具参考图）中的物件，插在地上，左前景"));
        assertTrue(prompt.contains("<image5>（道具参考图）中的物件，悬在半空，右中景"));
        // 两个资产的外貌都不许出现
        assertFalse(prompt.contains("插地的长枪"));
        assertFalse(prompt.contains("悬浮的鬼火灯笼"));
        // 全篇不该残留任何一个裸 id
        assertFalse(prompt.contains("char_"));
        assertFalse(prompt.contains("asset_"));
    }

    /**
     * 临时资产（真实前缀 {@code __temp_asset__:}）即使被管线误塞进 refs，也绝不能占槽位；
     * 位置必须留给后面的正式资产。
     */
    @Test
    void buildPrompt_tempAssetPrefixNeverConsumesReferenceSlot() {
        String tempAssetId = "__temp_asset__:1001:0";
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"背对镜头","posture":"站立","action":"站着","position":"左"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "assets":[
                   {"id":"__temp_asset__:1001:0","action":"压在他肩上","position":"左前景"},
                   {"id":"asset_formal","action":"悬在腰间","position":"右侧"}
                 ],
                 "scene":"山道"}""";
        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put(tempAssetId, "image1");     // 脏数据：临时资产不该有参考图
        assetRefs.put("asset_formal", "image2");
        Map<String, String> assetAppearance = new LinkedHashMap<>();
        assetAppearance.put(tempAssetId, "一条锈迹斑斑的铁链");
        assetAppearance.put("asset_formal", "一枚青玉令牌");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .assetImageRefs(assetRefs)
                .assetAppearancePrompts(assetAppearance)
                .scenePrompt("山道")
                .build());

        assertNotNull(prompt);
        // 正式资产拿到紧接着角色的槽位（3），临时资产被跳过不占位（其图不可控，永不入槽）
        assertTrue(prompt.contains("<image3>（道具参考图）中的物件，悬在腰间，右侧"));
        assertFalse(prompt.contains("<image4>"));
        // 临时资产退化走文字
        assertTrue(prompt.contains("画面中的一条锈迹斑斑的铁链，压在他肩上，左前景"));
        assertFalse(prompt.contains(tempAssetId));
    }

    /** 单图输入（无场景）时资产同样不写槽位标签，用自然语言指代 */
    @Test
    void buildPrompt_singleImageInput_assetUsesNaturalLanguageReference() {
        String llm = """
                {"characters":[
                   {"id":"char_a","facing":"背对镜头","posture":"站立","action":"站着","position":"左"}
                 ],
                 "frameCharacterIds":["char_a"],
                 "assets":[{"id":"asset_1","action":"被他握紧","position":"左前景"}]}""";
        Map<String, String> assetRefs = new LinkedHashMap<>();
        assetRefs.put("asset_1", "image1");

        String prompt = adapter().buildPrompt(Image2ImagePromptRequest.builder()
                .visualPlan(ImageDraftFixtures.firstFramePlan(llm))
                .characterIds(List.of("char_a"))
                .characterImageRefs(Map.of("char_a", "image1"))
                .assetImageRefs(assetRefs)
                .scenePrompt("")
                .build());

        assertNotNull(prompt);
        // 全画面只有角色 + 资产共 2 张图 → 仍会打标签（>=2），且验证"无场景时编号从 1 起"
        assertTrue(prompt.contains("<image1>（角色参考图）中的人物，站着，左"));
        assertTrue(prompt.contains("<image2>（道具参考图）中的物件，被他握紧，左前景"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
