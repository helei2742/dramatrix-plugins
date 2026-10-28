package io.dramatrix.plugin.minimaxh3;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.dto.ShotVisualPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 测试夹具：把「旧 {@code motions[]} 形态」的 LLM 输出改写成统一视觉蓝图（{@link ShotVisualPlan}）。
 *
 * <p><b>仅测试用。</b>生产链路已不存在 {@code motions[]}（规划上移到 {@code VISUAL_PLAN} worker，
 * 插件只消费 {@code VideoContext.visualPlan}）；保留旧形态夹具是为了让既有的 prompt 断言
 * （Picture/Subject 槽位编号、逐段文本、对白绑定、硬切描述、参考视频精简 prompt）在 SPI 迁移后
 * 仍逐条可比对。</p>
 *
 * <p>两个刻意的对齐点：</p>
 * <ul>
 *   <li>{@code groupCharacterIds} = <b>全部</b> motions 的角色并集（不按 shotId 过滤），对齐旧实现
 *       {@code filterSegs = collectMotionSegments(obj, null)} 的槽位口径；</li>
 *   <li>fixture 里没有 {@code dialogue} 键的 motion，其 {@code Beat.dialogue} 置为 {@code null}
 *       （而非空列表），以保留「未声明对白 ⇒ 走说话人兜底匹配」这条容忍分支的覆盖。</li>
 * </ul>
 */
final class BlueprintFixtures {

    private BlueprintFixtures() {
    }

    /** 按 shotId 过滤出本镜的 beats；{@code filterShotId} 为 null 时保留全部。 */
    static ShotVisualPlan plan(String motionsJson, Long filterShotId) {
        JSONObject obj = JSONUtil.parseObj(motionsJson);
        JSONArray motions = obj.getJSONArray("motions");

        List<ShotVisualPlan.Beat> beats = new ArrayList<>();
        Set<String> groupCharIds = new LinkedHashSet<>();
        if (motions != null) {
            for (Object o : motions) {
                JSONObject m = (JSONObject) o;
                JSONArray chars = m.getJSONArray("characters");
                if (chars != null) {
                    for (Object c : chars) {
                        String id = ((JSONObject) c).getStr("character_id");
                        if (id != null && !id.isBlank()) {
                            groupCharIds.add(id);
                        }
                    }
                }
                Long shotId = m.getLong("shotId");
                if (filterShotId != null && (shotId == null || !shotId.equals(filterShotId))) {
                    continue;
                }
                JSONArray dialogue = m.getJSONArray("dialogue");
                beats.add(ShotVisualPlan.Beat.builder()
                        .time(m.getStr("time"))
                        .desc(m.getStr("desc"))
                        .camera(m.getStr("camera"))
                        .mood(m.getStr("mood"))
                        .hardCut(firstBool(m, "hard_cut", "hardCut", "cut"))
                        .characters(toCharacters(chars))
                        .props(toProps(m.getJSONArray("props")))
                        .dialogue(dialogue == null ? null : toDialogue(dialogue))
                        .build());
            }
        }

        return ShotVisualPlan.builder()
                .shotId(filterShotId == null ? 1L : filterShotId)
                .sceneDesc(sceneDesc(obj))
                .beats(beats)
                .groupCharacterIds(new ArrayList<>(groupCharIds))
                .build();
    }

    /** 不过滤（多镜全量）—— 等价旧 {@code filterShotId == null}。 */
    static ShotVisualPlan plan(String motionsJson) {
        return plan(motionsJson, null);
    }

    private static String sceneDesc(JSONObject obj) {
        JSONObject scene = obj.getJSONObject("scene");
        return scene == null ? null : scene.getStr("desc");
    }

    private static List<ShotVisualPlan.BeatCharacter> toCharacters(JSONArray arr) {
        List<ShotVisualPlan.BeatCharacter> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            JSONObject c = (JSONObject) o;
            out.add(ShotVisualPlan.BeatCharacter.builder()
                    .characterId(c.getStr("character_id"))
                    // 位置与朝向（蓝图字段 position；旧草稿写作 facing）
                    .position(firstNonBlank(c.getStr("position"), c.getStr("facing")))
                    .desc(c.getStr("desc"))
                    .build());
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static List<ShotVisualPlan.BeatProp> toProps(JSONArray arr) {
        List<ShotVisualPlan.BeatProp> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            JSONObject p = (JSONObject) o;
            out.add(ShotVisualPlan.BeatProp.builder()
                    .assetId(p.getStr("asset_id"))
                    .desc(p.getStr("desc"))
                    .build());
        }
        return out;
    }

    private static List<ShotVisualPlan.DialogueRef> toDialogue(JSONArray arr) {
        List<ShotVisualPlan.DialogueRef> out = new ArrayList<>();
        for (Object o : arr) {
            JSONObject d = (JSONObject) o;
            Object onScreen = d.get("on_screen");
            out.add(ShotVisualPlan.DialogueRef.builder()
                    .id(d.getStr("id"))
                    .onScreen(onScreen == null || Boolean.TRUE.equals(toBool(onScreen)))
                    .speak(d.getStr("speak"))
                    .delivery(d.getStr("delivery"))
                    .build());
        }
        return out;
    }

    private static Boolean firstBool(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object v = obj.get(key);
            if (v != null) {
                return toBool(v);
            }
        }
        return Boolean.FALSE;
    }

    private static Boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }
}
