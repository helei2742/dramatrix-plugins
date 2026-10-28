package io.dramatrix.plugin.dashscope;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.PluginDraftView;
import io.dramatrix.plugin.dto.Image2ImagePromptRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope Qwen 图生图的<b>提示词拼接</b>：把本镜统一视觉蓝图（{@code request.visualPlan} 首帧）
 * 拼成一句自然语言编辑指令。
 *
 * <p>从 {@link DashScopeImage2ImageAdapter#buildPrompt} 提取出来。DashScope 多模态编辑可直接在
 * 文本里写「图1 / 图2 / 图3」指代参考图，因此这里把蓝图简化拼成单句指令，编号与
 * {@link DashScopeApiJsonBuilder} 的 content 次序严格一致：<b>图1 = 场景参考图，
 * 图2..图N = 角色参考图</b>（按 characterIds 顺序）。</p>
 *
 * <p>只消费蓝图的 {@code characters[]} 与 {@code frameCharacterIds[]} 两个结构，其余字段
 * （camera / lighting / fixPrompt 等）直接透传为文本段落。<b>输出的是纯文本 prompt</b>，
 * 不是 ComfyUI 结构化 JSON。</p>
 */
final class DashScopePromptBuilder {

    private DashScopePromptBuilder() {
    }

    /**
     * @return 可直接作为 content.text 编辑指令的字符串；蓝图不可解析时返回 {@code null}
     */
    static String build(Image2ImagePromptRequest request) {
        // 唯一真源 = 本镜蓝图（草稿视图由 PluginDraftView 统一映射）
        JSONObject json = PluginDraftView.firstFrame(request.getVisualPlan());
        if (json == null || json.isEmpty()) {
            return null;
        }

        // 1. 只保留 LLM 判定「出镜」的角色（frameCharacterIds 命中者）
        JSONArray chars = json.getJSONArray("characters");
        JSONArray frameIds = json.getJSONArray("frameCharacterIds");
        Map<String, String> actionById = new LinkedHashMap<>();
        Map<String, String> posById = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(chars)) {
            for (Object o : chars) {
                if (!(o instanceof JSONObject ob)) {
                    continue;
                }
                String id = ob.getStr("id");
                if (StrUtil.isBlank(id) || (frameIds != null && !frameIds.contains(id))) {
                    continue;
                }
                String action = ob.getStr("action");
                if (StrUtil.isBlank(action)) {
                    action = ob.getStr("description");
                }
                if (StrUtil.isNotBlank(action)) {
                    actionById.put(id, action);
                }
                String pos = ob.getStr("position");
                if (StrUtil.isNotBlank(pos)) {
                    posById.put(id, pos);
                }
            }
        }

        // 2. 场景 = 图1。
        String scene = StrUtil.isNotBlank(request.getScenePrompt()) ? request.getScenePrompt() : json.getStr("scene");
        StringBuilder sb = new StringBuilder(sceneHint(scene));

        // 3. 角色 = 图2..N，按 characterIds 顺序编号。
        List<String> ids = request.getCharacterIds();
        if (CollUtil.isEmpty(ids)) {
            ids = new ArrayList<>(actionById.keySet());
        }
        int img = 2;
        for (String id : ids) {
            String action = actionById.get(id);
            if (StrUtil.isBlank(action)) {
                continue;
            }
            sb.append("，将图").append(img).append("中的").append(np(action));
            String pos = posById.get(id);
            if (StrUtil.isNotBlank(pos)) {
                sb.append("，位于").append(np(pos));
            }
            img++;
        }

        // 4. 其余上下文透传为文本段落（有则在用，缺省不写）。
        if (StrUtil.isNotBlank(request.getCameraPrompt())) {
            sb.append("，镜头：").append(np(request.getCameraPrompt()));
        }
        if (StrUtil.isNotBlank(request.getLighting())) {
            sb.append("，光线：").append(np(request.getLighting()));
        }
        if (StrUtil.isNotBlank(request.getFixPrompt())) {
            sb.append("，整体风格：").append(np(request.getFixPrompt()));
        }
        return sb.toString();
    }

    /** 场景文本：有内容直接可用，缺省给一句中性兜底（至少保证图1 有可见主语）。 */
    private static String sceneHint(String scene) {
        return StrUtil.isBlank(scene) ? "在图1的本画面场景中" : "在图1的" + np(scene) + "场景中";
    }

    /** 压平/清洗成可直接混入单行文本的片段。 */
    private static String np(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
