package io.dramatrix.plugin.minimaxh3fl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Image2VideoRefInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MiniMax H3 首尾帧图生视频（fl2va）的工作流（API JSON）拼接类。
 *
 * <p>职责单一：基于默认 {@code workflow.json}（经绑定机制声明的参数落点）写入提示词 / 时长 /
 * 宽高 / seed / fps / 步数 / 用户 LoRA / 模型槽，并把<b>首帧与尾帧</b>挂到主节点的
 * {@code first_frame} / {@code last_frame} 输入上，产出可直接 POST 到 ComfyUI {@code /prompt} 的 dict。</p>
 *
 * <h3>本工作流的四个要点</h3>
 * <ol>
 *   <li><b>首尾帧是「关键帧」而不是「参考图」</b>：挂到 {@code MiniMaxH3ImageToVideo} 的
 *       {@code first_frame} / {@code last_frame}，经 VAE 编码后直接约束画面首末像素。
 *       因此本插件<b>不消费</b> {@link Image2VideoRefInput#getRefImageNames()}——
 *       多图参考的 {@code ref_images} 组在本插件里不存在，参考图上限（{@code maxRefImages}）也就没有意义。</li>
 *   <li><b>帧数由工作流内的表达式对齐</b>：{@code length} 走
 *       {@code ComfyMathExpression(max(5, round(a * 24)) + (5 - (… % 17)) % 17)}，Java 只把目标秒数写进上游
 *       {@code PrimitiveFloat.value}（{@code duration} 绑定），<b>不</b>直接写主节点 {@code length}——
 *       在 Java 里重复实现 {@code 17k+5} 对齐逻辑会让两处口径漂移。</li>
 *   <li><b>宽高来自 {@code ResolutionSelector} 连线</b>：调用方<b>显式给出</b>才覆盖主节点，
 *       不给就保留连线（模板默认 {@code 1:1 / 0.4MP / 32 对齐}）。</li>
 *   <li><b>模板不带任何 LoRA</b>：{@code UNETLoader} 直连 {@code BasicScheduler / BasicGuider}，
 *       用户 LoRA 由 {@code binding.lora.startNodes}（UNETLoader）在运行时动态串进去。
 *       原导出工作流里的 turbo 8step 开关（{@code ComfySwitchNode} 六件套）已删除：
 *       它会把 {@code BasicScheduler.steps} 变成<b>连线</b>，一旦用 binding 把 {@code steps} 写成字面量
 *       就会覆盖连线、绕过开关（开关说「8 步」实际跑 20 步）。需要 turbo 时见插件文档的 P2 方案。</li>
 * </ol>
 */
final class MiniMaxH3FlApiJsonBuilder {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxH3FlApiJsonBuilder.class);

    /** 调用方未给时长时的兜底（秒）；对应工作流模板里 {@code PrimitiveFloat.value} 的初始值。 */
    private static final double DEFAULT_DURATION = 5d;

    /**
     * 内置默认绑定：与插件目录下的 {@code workflow.binding.json} 内容一致，
     * 仅在该文件缺失时兜底（{@code ensureDefaultBinding} 只在 {@code binding == null} 时生效）。
     * <p>
     * 必须是<b>完整可解析的 JSON 对象</b>——写成片段会在兜底分支上直接抛
     * {@code A JSONObject text must begin with '{'}，把整条生成链炸掉。
     * <p>
     * 两个关键帧组都用<b>无 {@code {i}} 占位符</b>的 {@code pattern}：{@code mountField} 只做
     * {@code "{i}" -> 下标} 的替换，无占位符时原样返回，配合 {@code max: 1} 天然成立。
     * 每次构建都会先清空这两个输入再按需挂载，因此模板里 {@code first_frame} 的旧连线会被自动清掉。
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "MiniMaxH3ImageToVideo", "field": "prompt" } },
        { "parameter": "seed", "target": { "match": "classType", "classType": "RandomNoise", "field": "noise_seed" }, "transform": "int" },
        { "parameter": "fps", "target": { "match": "classType", "classType": "CreateVideo", "field": "fps" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "BasicScheduler", "field": "steps" }, "transform": "int" },
        { "parameter": "duration", "target": { "match": "nodeId", "nodeId": "111", "field": "value" }, "transform": "float" }
      ],
      "anchor": {
        "id": "video_node",
        "schema": { "match": "classType", "classType": "MiniMaxH3ImageToVideo" },
        "groups": [
          { "param": "first_frame", "pattern": "first_frame", "loader": { "classType": "LoadImage", "field": "image" }, "max": 1 },
          { "param": "last_frame", "pattern": "last_frame", "loader": { "classType": "LoadImage", "field": "image" }, "max": 1 }
        ]
      },
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      }
    }
    """;

    private MiniMaxH3FlApiJsonBuilder() {
    }

    static JSONObject build(MiniMaxH3FlAdapter adapter, Image2VideoRefInput input) {
        if (input == null) {
            input = new Image2VideoRefInput();
        }
        adapter.ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(adapter.getWorkflowJson());

        // 1. prompt / seed / fps / steps → 声明式绑定（未声明该绑定时 applyBinding 自动 no-op）
        if (StrUtil.isNotBlank(input.prompt())) {
            adapter.applyBinding(api, "prompt", input.prompt());
        }
        injectSeedBinding(adapter, api, "seed", input);
        if (input.getFps() != null && input.getFps() > 0) {
            adapter.applyBinding(api, "fps", input.getFps());
        }
        if (input.getSteps() != null && input.getSteps() > 0) {
            adapter.applyBinding(api, "steps", input.getSteps());
        }

        // 2. 时长 → 上游 PrimitiveFloat.value；帧数由工作流内表达式对齐到 17k+5，Java 不碰 length
        double duration = input.getDuration() != null && input.getDuration() > 0
                ? input.getDuration() : DEFAULT_DURATION;
        adapter.applyBinding(api, "duration", duration);

        // 3. 定位主节点（anchor）；宽高只在调用方显式给出时覆盖，否则保留 ResolutionSelector 连线
        String main = adapter.resolveAnchorNodeId(api);
        JSONObject mainInputs = api.getJSONObject(main).getJSONObject("inputs");
        if (mainInputs == null) {
            mainInputs = new JSONObject();
            api.getJSONObject(main).set("inputs", mainInputs);
        }
        if (input.getWidth() != null && input.getWidth() > 0) {
            mainInputs.set("width", input.getWidth());
        }
        if (input.getHeight() != null && input.getHeight() > 0) {
            mainInputs.set("height", input.getHeight());
        }

        // 4. 用户 LoRA → 从 UNETLoader 起动态串联（空列表自动跳过，模板里本就没有 LoRA 节点）
        adapter.applyLorasFromBinding(api, input.getLoras());

        // 5. 清掉模板占位 LoadImage（模板里 image 已置空；非空的话这里删不掉，会提交一个依赖不存在文件的节点）
        removePlaceholderLoadImages(api);

        // 6. 首尾帧挂载：为每个非空值新建 LoadImage 并接到主节点 first_frame / last_frame。
        //    两个组都会先清空旧挂载，因此模板里 first_frame 指向占位节点的连线在此被摘掉。
        adapter.applyAnchoredReferences(api, Map.<String, List<?>>of(
                "first_frame", singleOrEmpty(input.getFirstFrameName()),
                "last_frame", singleOrEmpty(input.getLastFrameName())));

        // 6.1 自检：首帧缺失时本工作流会退化成「纯文生视频 / 只给尾帧」，用户只会看到「首尾帧没生效」。
        //     这是唯一能让运维发现「小说级配错插件」的信号（见插件类文档的路由姿态说明），不要静默。
        if (mainInputs.get("first_frame") == null) {
            log.warn("[MINIMAX_H3_FL] 未提供首帧（firstFrameName 为空）：本次{}；若期望首尾帧生效，请检查调用方是否传了首帧、以及小说级 workflow 是否把别的业务配到了本插件上",
                    mainInputs.get("last_frame") == null ? "退化为纯文生视频" : "仅约束尾帧");
        }

        // 7. 模型槽注入：按当前选择覆盖 UNETLoader.unet_name / CLIPLoader.clip_name / VAELoader.vae_name
        adapter.applyModelSlots(api);

        return api;
    }

    /**
     * 随机处理后的 seed：null / 0 生成随机正整数，再按绑定写入。
     */
    private static void injectSeedBinding(MiniMaxH3FlAdapter adapter, JSONObject api, String parameter, Image2VideoRefInput input) {
        if (adapter.resolveNodeTarget(api, parameter) == null) return;
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        adapter.applyBinding(api, parameter, Math.abs(seed));
    }

    /** 单张图 → 长度为 0 或 1 的列表；空 / 全空白视为「不挂载」。 */
    private static List<String> singleOrEmpty(String name) {
        return StrUtil.isBlank(name) ? List.of() : List.of(name.trim());
    }

    /**
     * 删除模板里 {@code image} 为空（null 或空白串）的 {@code LoadImage} 占位节点。
     * <p>
     * 占位节点的 {@code image} 若仍是模板导出的具体文件名，本方法不认它 → 会把一个依赖不存在文件的
     * 节点提交给 ComfyUI。因此模板里的 {@code image} 必须显式写成 {@code ""}。
     */
    private static void removePlaceholderLoadImages(JSONObject api) {
        List<String> toRemove = new ArrayList<>();
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node == null || !"LoadImage".equals(node.getStr("class_type"))) continue;
            JSONObject inputs = node.getJSONObject("inputs");
            Object img = inputs == null ? null : inputs.get("image");
            if (img == null || (img instanceof String s && s.isBlank())) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            api.remove(id);
        }
    }
}
