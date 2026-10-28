package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.dto.ShotVisualPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：把「旧图片草稿 JSON」（{@code prompt.md} / {@code storyboard.md} 约定的输出）
 * 改写成统一视觉蓝图（{@link ShotVisualPlan}）。
 *
 * <p><b>仅测试用。</b>图片 worker 不再自带 LLM 草稿（规划上移到 {@code VISUAL_PLAN}），
 * 插件只消费 {@code Image2ImagePromptRequest.visualPlan}；保留旧形态夹具是为了让既有的
 * Qwen21 成稿断言（槽位编号、两段式结构、宫格格数、无裸 id 等）在 SPI 迁移后继续可比对。</p>
 *
 * <p><b>字段收敛</b>（历史决策）：草稿的 {@code action}/{@code position}/{@code posture}
 * 在蓝图里合并成一个短语 {@code desc}（蓝图契约：一短语含「站位+姿态+表情」），
 * 故夹具按 {@code action，position，facing，posture} 顺序拼接，保证这些文字仍出现在成稿里；
 * {@code preserve}/{@code layout}/5 段式 {@code camera} 对象不再映射（见 {@link io.dramatrix.plugin.PluginDraftView} 文档）。</p>
 *
 * <p><b>位置/朝向例外</b>：蓝图把「位置 + 朝向」合并成独立字段 {@code characters[].position}
 * （下游原样拼、不解析）。夹具<b>两处都填</b>——既保留 {@code desc} 里的旧写法
 * （模拟存量蓝图、验证成稿不丢这些文字），又填 {@code position} 字段（验证新通道），
 * 于是两条路径在同一份夹具下都被覆盖。</p>
 */
final class ImageDraftFixtures {

    private ImageDraftFixtures() {
    }

    /** 首帧夹具：草稿 → 单拍蓝图。草稿不可解析 / 无内容时返回 {@code null}（插件应返回 null prompt）。 */
    static ShotVisualPlan firstFramePlan(String draftJson) {
        JSONObject draft = parse(draftJson);
        if (draft == null) {
            return null;
        }
        List<ShotVisualPlan.BeatCharacter> characters = charactersOf(draft.getJSONArray("characters"));
        List<ShotVisualPlan.BeatProp> props = propsOf(draft.getJSONArray("assets"));
        if (characters.isEmpty() && props.isEmpty() && StrUtil.isBlank(draft.getStr("scene"))) {
            return null;
        }
        ShotVisualPlan.Beat beat = ShotVisualPlan.Beat.builder()
                .time("0s")
                .characters(characters)
                .props(props)
                .build();
        return base(draft).beats(List.of(beat)).build();
    }

    /** 故事板夹具：草稿 → 逐拍蓝图（一拍一格）。草稿不可解析 / 无 motions 时返回 {@code null}。 */
    static ShotVisualPlan storyboardPlan(String draftJson) {
        JSONObject draft = parse(draftJson);
        if (draft == null) {
            return null;
        }
        List<ShotVisualPlan.Beat> beats = new ArrayList<>();
        JSONArray motions = draft.getJSONArray("motions");
        if (motions != null) {
            for (Object o : motions) {
                JSONObject m = (JSONObject) o;
                beats.add(ShotVisualPlan.Beat.builder()
                        .time(m.getStr("time"))
                        .camera(m.getStr("camera"))
                        .desc(m.getStr("action"))
                        .mood(m.getStr("mood"))
                        // 逐格名单与蓝图同口径：键<b>缺失</b> = 存量草稿（拿不到逐格入画名单，不做入画过滤）；
                        // 空数组 = 空镜（{@link io.dramatrix.plugin.PluginDraftView#storyboard} 会照样落
                        // {@code []}，故事板成稿据此认定"本格只有这些人"，把画框外的人挡在外面）
                        .characters(m.containsKey("characters") ? charactersOf(m.getJSONArray("characters")) : null)
                        .props(m.containsKey("props") ? propsOf(m.getJSONArray("props")) : null)
                        .build());
            }
        }
        if (beats.isEmpty()) {
            return null;
        }
        return base(draft).beats(beats).build();
    }

    private static JSONObject parse(String draftJson) {
        if (StrUtil.isBlank(draftJson)) {
            return null;
        }
        try {
            JSONObject o = JSONUtil.parseObj(draftJson);
            return o.isEmpty() ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    private static ShotVisualPlan.ShotVisualPlanBuilder base(JSONObject draft) {
        return ShotVisualPlan.builder()
                .shotId(1L)
                .planVersion(ShotVisualPlan.CURRENT_PLAN_VERSION)
                .sceneDesc(draft.getStr("scene"))
                .lighting(draft.getStr("lighting"))
                .style(draft.getStr("style"));
    }

    private static List<ShotVisualPlan.BeatCharacter> charactersOf(JSONArray arr) {
        List<ShotVisualPlan.BeatCharacter> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            JSONObject c = (JSONObject) o;
            String id = c.getStr("id");
            if (StrUtil.isBlank(id)) {
                continue;
            }
            out.add(ShotVisualPlan.BeatCharacter.builder()
                    .characterId(id)
                    // 旧草稿的 position/facing 两键 → 蓝图的 position 一个字段（位置 + 朝向）
                    .position(join(c.getStr("position"), c.getStr("facing")))
                    .desc(join(c.getStr("action"), c.getStr("position"), c.getStr("facing"), c.getStr("posture")))
                    .build());
        }
        return out;
    }

    private static List<ShotVisualPlan.BeatProp> propsOf(JSONArray arr) {
        List<ShotVisualPlan.BeatProp> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            JSONObject p = (JSONObject) o;
            String id = p.getStr("id");
            if (StrUtil.isBlank(id)) {
                continue;
            }
            out.add(ShotVisualPlan.BeatProp.builder()
                    .assetId(id)
                    .desc(join(p.getStr("action"), p.getStr("position"), p.getStr("description")))
                    .build());
        }
        return out;
    }

    /** 按旧草稿的语义把多个片段拼成一个蓝图短语（跳过空项）。 */
    private static String join(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (StrUtil.isNotBlank(p)) {
                kept.add(StrUtil.trim(p));
            }
        }
        return kept.isEmpty() ? null : String.join("，", kept);
    }
}
