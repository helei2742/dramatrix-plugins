package io.dramatrix.plugin.klein;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.PluginDraftView;
import io.dramatrix.plugin.PluginJsonUtil;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Klein 图生图的<b>提示词拼接</b>：从本镜统一视觉蓝图（{@code request.visualPlan} 首帧）
 * 组装 FLUX.2 官方结构化 JSON，产出物直接作为正向 CLIP 文本（node 19）的输入。
 *
 * <p>从 {@link KleinImage2ImageAdapter#buildPrompt} 提取出来，职责单一：
 * 输出 FLUX.2 官方 schema 字段 {@code scene / subjects(type, description, pose, position) /
 * style / lighting / camera(angle, distance)}。参考图与角色的对应关系由
 * {@link KleinWorkflowBuilder} 按索引接线，<b>不写进 prompt 文本</b>；角色外貌（最小集）
 * 进 {@code subjects[].description}、动作进 {@code subjects[].pose}，便于模型在无 mask 的
 * 本地路径下自行匹配参考图。</p>
 */
final class KleinPromptBuilder {

    /**
     * FLUX 排他性约束后缀：追加到 scene 末尾，阻止 FLUX 凭空渲染额外人物。
     * 与 prompt.md 的「FLUX exclusivity constraint」保持一致。
     */
    private static final String FLUX_EXCLUSIVITY_CLAUSE =
            " The space is empty except for the framed subjects. No crowd, no additional people, no unseen characters.";

    private KleinPromptBuilder() {
    }

    /**
     * 从蓝图首帧 + 管线 raw 数据拼接 Klein 最终 image prompt（FLUX.2 官方结构化 JSON）。
     *
     * @return compact JSON 字符串；蓝图不可解析或无任何有效字段时返回 {@code null}
     */
    static String build(Image2ImagePromptRequest request) {
        Map<String, String> charAppearancePrompts = request.getCharAppearancePrompts();
        Map<String, String> assetAppearancePrompts = request.getAssetAppearancePrompts();
        String lighting = request.getLighting();
        String fixPrompt = request.getFixPrompt();

        // 唯一真源 = 本镜蓝图（草稿视图由 PluginDraftView 统一映射）
        JSONObject json = PluginDraftView.firstFrame(request.getVisualPlan());
        if (json == null || json.isEmpty()) return null;

        // 1. 解析 characters（可选 —— 缺省为纯场景帧）
        JSONArray charDescArray = json.getJSONArray("characters");

        Map<String, String> descById = new LinkedHashMap<>();
        Map<String, String> positionById = new LinkedHashMap<>();
        Map<String, String> groupAppearanceById = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(charDescArray)) {
            for (Object o : charDescArray) {
                JSONObject obj = (JSONObject) o;
                String id = obj.getStr("id");
                String desc = obj.getStr("action");
                String type = obj.getStr("type");
                if (StrUtil.isBlank(id) || StrUtil.isBlank(desc)) continue;
                descById.put(id, desc);
                String pos = obj.getStr("position");
                if (StrUtil.isNotBlank(pos)) positionById.put(id, pos);
                if ("group".equals(type)) {
                    String appearance = obj.getStr("appearance");
                    groupAppearanceById.put(id, StrUtil.isNotBlank(appearance) ? appearance : "multiple figures");
                }
            }
        }

        // 2. 角色顺序
        List<String> characterIds = request.getCharacterIds();

        // 2.1 解析 assets（可选）：id + 画面中的位置/动作；外观由管线传入的 originalLLmResult 补充
        JSONArray assetArray = json.getJSONArray("assets");
        Map<String, String> assetActionById = new LinkedHashMap<>();
        Map<String, String> assetPositionById = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(assetArray)) {
            for (Object o : assetArray) {
                JSONObject obj = (JSONObject) o;
                String id = obj.getStr("id");
                if (StrUtil.isBlank(id)) continue;
                String action = obj.getStr("action");
                if (StrUtil.isBlank(action)) {
                    action = obj.getStr("description");
                }
                if (StrUtil.isNotBlank(action)) {
                    assetActionById.put(id, action);
                }
                String pos = obj.getStr("position");
                if (StrUtil.isNotBlank(pos)) {
                    assetPositionById.put(id, pos);
                }
            }
        }

        // 3. 组装生图 JSON
        JSONObject out = new JSONObject();

        // 3.1 scene：只回答 Where；摄像机交给 camera。
        // 参考图（image0=场景，image1..N=角色）由管线按索引接线，不写进 prompt。
        // FLUX 负向约束（兜底）：scene 末尾追加“禁止额外人物”句，防止 FLUX 把 position 里
        // 引用到的未出场角色 / 环境细节误渲染成第 4 个甚至更多人物（见 prompt.md「FLUX exclusivity constraint」）。
        boolean hasSubjects = CollUtil.isNotEmpty(characterIds);
        String scene = getJsonText(json, "scene");
        if (StrUtil.isBlank(scene)) {
            scene = PluginJsonUtil.sanitizePromptPart(request.getScenePrompt());
        }
        if (StrUtil.isBlank(scene)) {
            scene = "The scene follows the environment reference image.";
        }
        // 追加相机几何关系（相机在哪 / 对着谁），强制 FLUX 按指定视角取景，
        // 否则缺少取景锚点时 FLUX 默认所有主体正面朝镜头。
        // 来自 LLM 输出 camera.cameraAt / camera.target，是 FLUX 一定会读到的文本通道。
        String cameraGeo = buildCameraGeometryText(json.getJSONObject("camera"));
        if (StrUtil.isNotBlank(cameraGeo)) {
            scene = scene + " " + cameraGeo;
        }
        scene = appendFluxExclusivityClause(scene, hasSubjects);
        out.set("scene", scene);

        // 3.3 subjects：这个 shot 中人物/道具如何出现。官方 schema 字段：
        // type(角色=character / 道具=object)、description(外貌)、pose(动作)、position(景别)。
        // 不写 reference / character 锚点 —— 参考图绑定由管线接线完成。
        JSONArray subjects = new JSONArray();
        if (CollUtil.isNotEmpty(characterIds)) {
            for (String id : characterIds) {
                if (StrUtil.isBlank(id)) {
                    continue;
                }

                String pose = PluginJsonUtil.sanitizePromptPart(descById.get(id));
                String groupAppearance = groupAppearanceById.get(id);
                if (StrUtil.isNotBlank(groupAppearance)) {
                    pose = joinPromptParts(pose, groupAppearance);
                }
                // 外貌（最小集）进 description；保留角色 ID 前缀（如 char_xxx）——
                // 后续绑定 LoRA 时依赖该 ID 匹配角色并自行剥离，此处不可提前去掉。
                String appearance = charAppearancePrompts != null ? charAppearancePrompts.get(id) : null;
                String position = PluginJsonUtil.sanitizePromptPart(positionById.get(id));

                JSONObject subject = new JSONObject();
                subject.set("type", "character");
                if (StrUtil.isNotBlank(appearance)) {
                    subject.set("description", id + "," + sanitizeAppearance(appearance));
                }
                if (StrUtil.isNotBlank(pose)) {
                    subject.set("pose", pose);
                }
                if (StrUtil.isNotBlank(position)) {
                    subject.set("position", position);
                }
                subjects.add(subject);
            }
        }

        for (Map.Entry<String, String> entry : assetActionById.entrySet()) {
            String id = entry.getKey();
            String appearance = assetAppearancePrompts != null ? assetAppearancePrompts.get(id) : null;
            if (StrUtil.isBlank(appearance)) {
                continue;
            }
            String pose = PluginJsonUtil.sanitizePromptPart(entry.getValue());
            String position = PluginJsonUtil.sanitizePromptPart(assetPositionById.get(id));

            JSONObject subject = new JSONObject();
            subject.set("type", "object");
            subject.set("description", appearance);
            if (StrUtil.isNotBlank(pose)) {
                subject.set("pose", pose);
            }
            if (StrUtil.isNotBlank(position)) {
                subject.set("position", position);
            }
            subjects.add(subject);
        }

        if (!subjects.isEmpty()) {
            out.set("subjects", subjects);
        }

        // 3.4 camera：只描述本镜头重新决定的构图（官方 schema：angle / distance / lens）。
        JSONObject cameraJson = json.getJSONObject("camera");
        JSONObject camera = buildCamera(cameraJson);
        out.set("camera", camera);

        // 3.5 style / lighting（官方 schema 字段）。
        if (StrUtil.isNotBlank(fixPrompt)) {
            out.set("style", PluginJsonUtil.sanitizePromptPart(fixPrompt));
        }
        if (StrUtil.isNotBlank(lighting)) {
            out.set("lighting", PluginJsonUtil.sanitizePromptPart(lighting));
        }

        if (out.isEmpty()) return null;
        return out.toString();   // compact JSON → node 19
    }

    /**
     * 拼接两个英文 prompt 片段。
     */
    private static String joinPromptParts(String first, String second) {
        String a = PluginJsonUtil.sanitizePromptPart(first);
        String b = PluginJsonUtil.sanitizePromptPart(second);
        if (StrUtil.isBlank(a)) {
            return b;
        }
        if (StrUtil.isBlank(b)) {
            return a;
        }
        return a + ", " + b;
    }

    /**
     * 清洗角色外貌 prompt 中的英文脏 token，<b>保留中文原文</b>，再交给 FLUX。
     * 处理 {@code toMinimalPrompt()} 逗号拼接时带入的非自然语言残留：
     * <ul>
     *   <li>数组符号 [] / [[]]（如 clothes 字段序列化残留）</li>
     *   <li>下划线 token（tall_180 -> "tall 180"，slender_tall -> "slender tall"，twenty_two -> "twenty two"）</li>
     *   <li>孤立数字（"180" -> 空，避免无单位数字干扰）</li>
     * </ul>
     * 注意：本方法<b>不</b>清洗中文（区别于 {@link PluginJsonUtil#sanitizePromptPart}），
     * 角色外貌描述若为中文将原样保留，避免被洗空后只剩角色 ID。
     */
    private static String sanitizeAppearance(String s) {
        if (StrUtil.isBlank(s)) {
            return "";
        }
        String a = s.trim();
        a = a.replaceAll("\\[\\s*]", " ")
                .replaceAll("[\\[\\]]", " ")
                .replaceAll("\\b\\d+\\b", " ")
                .replaceAll("\\s*;\\s*", ", ")
                .replaceAll("\\s*,\\s*,+", ", ")
                .replaceAll("^\\s*,+|,+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return a;
    }

    /**
     * 读取 LLM JSON 中的字符串字段并清洗。
     */
    private static String getJsonText(JSONObject json, String key) {
        return json != null
                ? PluginJsonUtil.sanitizePromptPart(json.getStr(key))
                : "";
    }

    private static String getJsonTextOrDefault(JSONObject json, String key, String defaultValue) {
        String value = getJsonText(json, key);
        return StrUtil.isNotBlank(value) ? value : defaultValue;
    }

    /**
     * 给 scene 末尾追加 FLUX 排他性约束（禁止额外人物）。
     * 若 scene 已含同类约束（LLM 已按 prompt.md 输出），则不再重复追加，避免冗余。
     * 有出场人物时用完整句；纯场景帧仅追加“无人”约束，避免“except for the framed subjects”自相矛盾。
     */
    private static String appendFluxExclusivityClause(String scene, boolean hasSubjects) {
        if (StrUtil.isBlank(scene)) {
            return scene;
        }
        String lower = scene.toLowerCase();
        if (lower.contains("no additional people")
                || lower.contains("no extra people")
                || lower.contains("no crowd")) {
            return scene;
        }
        if (hasSubjects) {
            return scene + " Do not arrange the characters into a poster-like lineup. "
                    + FLUX_EXCLUSIVITY_CLAUSE;
        }
        return scene + " No people, no crowd, no additional figures.";
    }

    /**
     * 从 LLM camera 块抽取「相机在哪 / 对着谁」几何关系，拼接成一句英文放进 scene，
     * 强制 FLUX 按指定视角取景，避免默认全员正面朝镜头。
     * 这两个字段不经过 {@link #buildCamera}（只透传 angle/distance），必须通过 scene 文本通道注入。
     */
    private static String buildCameraGeometryText(JSONObject cameraJson) {
        if (cameraJson == null) {
            return "";
        }
        String cameraAt = getJsonText(cameraJson, "cameraAt");
        String target = getJsonText(cameraJson, "target");
        if (StrUtil.isBlank(cameraAt) && StrUtil.isBlank(target)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("The camera is");
        if (StrUtil.isNotBlank(cameraAt)) {
            sb.append(" positioned ").append(cameraAt);
        }
        if (StrUtil.isNotBlank(target)) {
            sb.append(sb.length() > "The camera is".length() ? ", aimed at " : " aimed at ")
                    .append(target);
        }
        sb.append(".");
        return sb.toString();
    }

    /**
     * 从 LLM 结构化输出直接生成 camera 字段（FLUX.2 官方 schema：angle / distance / lens）。
     * LLM 输出的 shot 对应 distance（景别），angle 对应机位俯仰；lens 无对应字段，缺省。
     */
    private static JSONObject buildCamera(JSONObject cameraJson) {
        JSONObject camera = new JSONObject();
        camera.set("angle", getJsonTextOrDefault(cameraJson, "angle", "eye level"));
        camera.set("distance", getJsonTextOrDefault(cameraJson, "shot", "medium shot"));
        return camera;
    }
}
