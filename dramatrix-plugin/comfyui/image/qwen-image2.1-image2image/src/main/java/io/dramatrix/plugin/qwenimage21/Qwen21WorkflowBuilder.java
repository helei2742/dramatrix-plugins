package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2ImageInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qwen Image 2.1 图生图的<b>工作流拼接</b>：把业务入参 {@link Image2ImageInput} 组装成一个可
 * POST 到 ComfyUI 的 API dict（{@code JsonTypeConverter} 结构）。
 *
 * <p>从 {@link Qwen21Image2ImageAdapter#buildApiJson} 提取出来，职责单一：基于默认
 * {@code workflow.json}（经绑定机制声明的参数落点）逐个写入提示词 / 宽高 / seed / denoise，
 * 并为参考图动态创建空间 {@code LoadImage} 节点接到 {@code TextEncodeQwenImage21.images.image_1..N}。
 * 槽位顺序与 prompt 一侧共用 {@link ReferenceSlotPlanner}（唯一真源）。</p>
 *
 * <p>依赖适配器（{@code Qwen21Image2ImageAdapter}）暴露的基类能力：绑定应用
 * （{@code AbstractComfyUIPlugin#applyBinding}）、节点导航（{@code navigateTo}）、模型槽注入
 * （{@code applyModelSlots}）与生效工作流（{@code getWorkflowJson}）。</p>
 */
final class Qwen21WorkflowBuilder {

    private static final String LOAD_IMAGE_TEMPLATE = """
            {
                "inputs": { "image": "" },
                "class_type": "LoadImage",
                "_meta": { "title": "加载图像" }
            }
            """;

    /**
     * 内置默认绑定：对应默认 workflow.json 的节点（按 classType 定位）。
     * <p>与 qwen-text2image 的默认绑定同构，仅参数落点不同。</p>
     */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "prompt" } },
        { "parameter": "negative_prompt", "target": { "match": "classType", "classType": "TextEncodeQwenImage21", "field": "negative_prompt" } },
        { "parameter": "width", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "width" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "classType", "classType": "EmptyLatentImage", "field": "height" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" },
        { "parameter": "cache_device", "target": { "match": "classType", "classType": "QwenImage21Cache", "field": "device" } },
        { "parameter": "dtype", "target": { "match": "classType", "classType": "QwenImage21Cache", "field": "dtype" } },
        { "parameter": "save_format", "target": { "match": "classType", "classType": "SaveImageAdvanced", "field": "format" } }
      ],
      "lora": {
        "startNodes": [
          { "match": "classType", "classType": "UNETLoader" }
        ]
      },
      "modelSlots": [
        { "group": "qwen-image-2.1-diffusion", "targets": [ { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } ] },
        { "group": "qwen-image-2.1-clip", "targets": [ { "match": "classType", "classType": "CLIPLoader", "field": "clip_name" } ] },
        { "group": "qwen-image-2.1-vae", "targets": [ { "match": "classType", "classType": "VAELoader", "field": "vae_name" } ] }
      ]
    }
    """;

    private static final double DEFAULT_EDIT_DENOISE = 1.0;

    private Qwen21WorkflowBuilder() {
    }

    /**
     * 组装 API dict。详见 {@link Qwen21Image2ImageAdapter#buildApiJson} 的文档：
     * 单一 checkpoint 同时支持文生图与图像编辑，参考图经 {@code TextEncodeQwenImage21} 的
     * {@code images.image_1..N} 编码为时序保持条件，由此节点输出的 conditioning 作 KSampler 底图。
     *
     * <p>与 Klein 实现一致，本 workflow <b>不含图片加载节点</b>：参考图在 {@link #applyReferenceImages}
     * 中按入参动态创建 {@code LoadImage} 节点并接线到 TextEncode 的 image_N 槽位。</p>
     */
    static JSONObject build(Qwen21Image2ImageAdapter adapter, Image2ImageInput input) {
        adapter.ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(adapter.getWorkflowJson());

        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();

        // 1. 正向提示词 → TextEncodeQwenImage21.prompt（统一入参优先，缺省走 options.prompt）
        String prompt = StrUtil.isNotBlank(input.getPrompt())
                ? input.getPrompt()
                : (String) opts.get("prompt");
        if (StrUtil.isNotBlank(prompt)) {
            adapter.applyBinding(api, "prompt", prompt);
        }

        // 2. 负向提示词 → negative_prompt
        String negativePrompt = StrUtil.isNotBlank(input.getNegativePrompt())
                ? input.getNegativePrompt()
                : (String) opts.get("negative_prompt");
        if (StrUtil.isNotBlank(negativePrompt)) {
            adapter.applyBinding(api, "negative_prompt", negativePrompt);
        }

        // 3. cacheDevice / dtype → QwenImage21Cache.device / .dtype
        String cacheDevice = StrUtil.toStringOrNull(opts.get("cacheDevice"));
        if (StrUtil.isBlank(cacheDevice)) {
            cacheDevice = StrUtil.toStringOrNull(opts.get("cache_device"));
        }
        if (StrUtil.isNotBlank(cacheDevice)) {
            adapter.applyBinding(api, "cache_device", cacheDevice);
        }
        String dtype = StrUtil.toStringOrNull(opts.get("dtype"));
        if (StrUtil.isNotBlank(dtype)) {
            adapter.applyBinding(api, "dtype", dtype);
        }

        // 3.5 保存格式 → SaveImageAdvanced.format（png/exr/avif）
        String saveFormat = StrUtil.toStringOrNull(opts.get("saveFormat"));
        if (StrUtil.isBlank(saveFormat)) {
            saveFormat = StrUtil.toStringOrNull(opts.get("save_format"));
        }
        if (StrUtil.isNotBlank(saveFormat)) {
            adapter.applyBinding(api, "save_format", saveFormat);
        }

        // 4. 宽高 → EmptyLatentImage
        Integer width = input.getWidth() != null && input.getWidth() > 0
                ? input.getWidth() : numOpt(opts, "width", Integer.class);
        Integer height = input.getHeight() != null && input.getHeight() > 0
                ? input.getHeight() : numOpt(opts, "height", Integer.class);
        if (width != null && width > 0) {
            adapter.applyBinding(api, "width", width);
        }
        if (height != null && height > 0) {
            adapter.applyBinding(api, "height", height);
        }

        // 宽高即出图画布：请求方显式给出宽高时，切 ComfySwitchNode 走 EmptyLatentImage（已绑定
        // 上述宽高）作为 KSampler 底图，而非 TextEncodeQwenImage21 输出的「首张参考图尺寸」空
        // latent —— 否则图生图输出分辨率会被第一张参考图原图绑架（如 960×1920）。
        if (width != null && width > 0 && height != null && height > 0) {
            AbstractComfyUIPlugin.navigateTo(api, "7.inputs").set("switch", true);
        }

        // 5. 参考图动态接线 → TextEncodeQwenImage21.images.image_1..N（空集合则不动工作流）
        boolean hasReference = applyReferenceImages(adapter, api, input);

        // 6. steps / cfg → KSampler
        Integer steps = input.getSteps() != null ? input.getSteps() : numOpt(opts, "steps", Integer.class);
        if (steps != null) {
            adapter.applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg() != null ? input.getCfg() : numOpt(opts, "cfg", Double.class);
        if (cfg != null) {
            adapter.applyBinding(api, "cfg", cfg);
        }

        // 7. Seed → KSampler
        Integer s = input.getSeed() != null ? input.getSeed() : numOpt(opts, "seed", Integer.class);
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        adapter.applyBinding(api, "seed", Math.abs(seed));

        // 8. denoise → KSampler（有参考图时按 strength/denoise 取值，否则整图重绘取 1）
        Double strength = input.getStrength() != null ? input.getStrength() : numOpt(opts, "strength", Double.class);
        Double denoise = numOpt(opts, "denoise", Double.class);
        Double resolved = denoise != null ? denoise : strength;
        if (!hasReference) {
            adapter.applyBinding(api, "denoise", 1.0);
        } else if (resolved != null) {
            adapter.applyBinding(api, "denoise", resolved);
        } else {
            adapter.applyBinding(api, "denoise", DEFAULT_EDIT_DENOISE);
        }

        // 9. 模型槽注入：按当前选择覆盖模型名（无 modelSlots 时 no-op）
        adapter.applyModelSlots(api);

        return api;
    }

    /** 从 options 里按类型读取数值参数；缺省或类型不符 → null。 */
    private static <T> T numOpt(Map<String, Object> opts, String key, Class<T> type) {
        Object v = opts.get(key);
        if (v == null) {
            return null;
        }
        if (type == Integer.class) {
            if (v instanceof Number n) return type.cast(n.intValue());
            if (v instanceof String str && StrUtil.isNotBlank(str)) return type.cast(Integer.valueOf(str.trim()));
            return null;
        }
        if (type == Double.class) {
            if (v instanceof Number n) return type.cast(n.doubleValue());
            if (v instanceof String str) return type.cast(Double.valueOf(str.trim()));
            return null;
        }
        return null;
    }

    /**
     * 将场景图 + 角色/资产参考图按序接线到 TextEncodeQwenImage21 的 {@code images.image_1..N}。
     * 每张图一个动态创建的空间 LoadImage 节点，nodeId 从 1000 起递增（避开工作流既有节点）。
     * 返回是否接入至少一张参考图。
     */
    private static boolean applyReferenceImages(
            Qwen21Image2ImageAdapter adapter, JSONObject api, Image2ImageInput input
    ) {
        if (input == null) {
            return false;
        }
        JSONObject textInputs = AbstractComfyUIPlugin.navigateTo(api, "1.inputs");
        boolean hasScene = StrUtil.isNotBlank(input.getSceneImage());

        // id → 文件名合并表：角色与资产的 id 不会撞车，合并后无需按前缀分派
        Map<String, String> nameById = new LinkedHashMap<>();
        if (input.getCharacterImages() != null) {
            nameById.putAll(input.getCharacterImages());
        }
        if (input.getAssetImages() != null) {
            nameById.putAll(input.getAssetImages());
        }

        Map<String, Integer> slotById = ReferenceSlotPlanner.plan(
                hasScene, adapter.includeTempCharactersInReference(),
                input.getCharacterIds(), id -> StrUtil.isNotBlank(nameById.get(id)),
                input.getAssetIds(), id -> StrUtil.isNotBlank(nameById.get(id))
        );

        int nextNodeId = 1000;
        if (hasScene) {
            nextNodeId = attachReferenceImage(api, textInputs, input.getSceneImage(), 1, nextNodeId);
        }
        // 迭代顺序天然是槽位升序；这里显式用 slotById 的槽位号做 image_N 的下标，
        // 保证与 ReferenceSlotPlanner.plan 的编号严格一致（不靠"数数"）。
        for (Map.Entry<String, Integer> entry : slotById.entrySet()) {
            nextNodeId = attachReferenceImage(api, textInputs, nameById.get(entry.getKey()), entry.getValue(), nextNodeId);
        }
        return hasScene || !slotById.isEmpty();
    }

    /** 创建 LoadImage 节点并入图（images.image_{index} → [loadId,0]）。 */
    private static int attachReferenceImage(
            JSONObject api, JSONObject textInputs, String imageName, int index, int nextNodeId
    ) {
        String loadId = String.valueOf(nextNodeId++);
        JSONObject loadNode = new JSONObject(LOAD_IMAGE_TEMPLATE);
        loadNode.getJSONObject("inputs").set("image", imageName);
        api.set(loadId, loadNode);
        textInputs.set("images.image_" + index, List.of(loadId, 0));
        return nextNodeId;
    }
}