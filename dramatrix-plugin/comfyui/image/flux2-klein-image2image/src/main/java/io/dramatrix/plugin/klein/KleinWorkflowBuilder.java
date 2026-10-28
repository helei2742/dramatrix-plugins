package io.dramatrix.plugin.klein;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.Image2ImageInput;
import io.dramatrix.plugin.dto.LoraBundle;

import java.util.List;
import java.util.Map;

/**
 * Klein 图生图的<b>工作流拼接</b>：把业务入参 {@link Image2ImageInput} 组装成一个可 POST 到
 * ComfyUI 的 API dict（基于 {@code workflow.json} 的节点结构）。
 *
 * <p>从 {@link KleinImage2ImageAdapter#buildApiJson} 提取出来，职责单一：基于默认
 * {@code workflow.json}（经绑定机制声明的参数落点）逐个写入提示词 / 宽高 / steps / cfg /
 * seed / denoise，并为参考图动态创建
 * {@code LoadImage → ImageScaleToTotalPixels → VAEEncode → ReferenceLatent} 节点串接到
 * 正向（19）/ 负向（62）条件链上。</p>
 *
 * <p>依赖适配器（{@link KleinImage2ImageAdapter}）暴露的基类能力：绑定应用
 * （{@code AbstractComfyUIPlugin#applyBinding}）、LoRA 串联
 * （{@code applyLorasFromBinding}）、模型槽注入（{@code applyModelSlots}）、
 * 默认绑定兜底（{@code ensureDefaultBinding}）与生效工作流（{@code getWorkflowJson}）。</p>
 */
final class KleinWorkflowBuilder {

    private static final String LOAD_IMAGE_TEMPLATE = """
            {
                "inputs": { "image": "" },
                "class_type": "LoadImage",
                "_meta": { "title": "加载图像" }
            }
            """;

    private static final String SCALE_IMAGE_TEMPLATE = """
            {
                "inputs": {
                    "upscale_method": "lanczos",
                    "megapixels": 1,
                    "resolution_steps": 64,
                    "image": ["__PLACEHOLDER__", 0]
                },
                "class_type": "ImageScaleToTotalPixels",
                "_meta": { "title": "缩放图像（像素）" }
            }
            """;

    private static final String LATENT_RESIZE_TEMPLATE = """
            {
                "inputs": {
                    "width": 1024,
                    "height": 1024,
                    "upscale_method": "nearest-exact",
                    "keep_proportion": "stretch",
                    "pad_color": "0, 0, 0",
                    "crop_position": "center",
                    "divisible_by": 2,
                    "device": "cpu",
                    "image": ["__PLACEHOLDER__", 0]
                },
                "class_type": "ImageResizeKJv2",
                "_meta": { "title": "Resize Image v2" }
            }
            """;

    private static final String VAE_ENCODE_TEMPLATE = """
            {
                "inputs": {
                    "pixels": ["__PLACEHOLDER__", 0],
                    "vae": ["10", 0]
                },
                "class_type": "VAEEncode",
                "_meta": { "title": "VAE编码" }
            }
            """;

    private static final String REFERENCE_LATENT_TEMPLATE = """
            {
                "inputs": {
                    "conditioning": ["__CONDITIONING__", 0],
                    "latent": ["__LATENT__", 0]
                },
                "class_type": "ReferenceLatent",
                "_meta": { "title": "参考Latent" }
            }
            """;

    private static final double DEFAULT_LATENT_DENOISE = 0.75;

    /** 内置默认绑定：对应默认 workflow.json 的节点（按 title/classType 定位）。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "prompt", "target": { "match": "title", "title": "CLIP文本编码", "field": "text" } },
        { "parameter": "negative_prompt", "target": { "match": "title", "title": "Negative Prompt", "field": "text" } },
        { "parameter": "width", "target": { "match": "title", "title": "空Latent图像（Flux2）", "field": "width" }, "transform": "int" },
        { "parameter": "height", "target": { "match": "title", "title": "空Latent图像（Flux2）", "field": "height" }, "transform": "int" },
        { "parameter": "steps", "target": { "match": "classType", "classType": "KSampler", "field": "steps" }, "transform": "int" },
        { "parameter": "cfg", "target": { "match": "classType", "classType": "KSampler", "field": "cfg" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" }
      ],
      "lora": {
        "startNodes": [
          { "match": "nodeId", "nodeId": "13" }
        ]
      }
    }
    """;

    private KleinWorkflowBuilder() {
    }

    /**
     * 组装 API dict：入参 → 绑定落点 + 动态参考图节点（节点号从 1000 起）。
     *
     * @param adapter 插件实例（提供绑定应用 / LoRA / 模型槽等宿主能力）
     * @param input   图生图入参
     */
    static JSONObject build(KleinImage2ImageAdapter adapter, Image2ImageInput input) {
        adapter.ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(adapter.getWorkflowJson());

        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        List<LoraBundle> loras = input.getLoras() == null ? List.of() : input.getLoras();

        // 1. 正向提示词 → 绑定 prompt（统一入参优先，缺省走 options.prompt / api 默认）
        String prompt = StrUtil.isNotBlank(input.getPrompt())
                ? input.getPrompt()
                : (String) opts.get("prompt");
        if (StrUtil.isNotBlank(prompt)) {
            adapter.applyBinding(api, "prompt", prompt);
        }

        // 2. 负向提示词 → 绑定 negative_prompt：FLUX.2 不支持否定提示
        adapter.applyBinding(api, "negative_prompt", "");

        // 3. LoRA → 原生 LoraLoader 串联（空列表自动跳过，不动工作流）
        adapter.applyLorasFromBinding(api, loras);

        // 4. 宽高 → 绑定 width/height (EmptyFlux2LatentImage)
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

        // 5. 图片输入（动态建节点，锚点固定于 90/19/62）
        boolean hasLatentImage = applyStructuredImageInputs(api, input);

        // 5.1 steps / cfg → 绑定 KSampler（null 则保留工作流默认）
        Integer steps = input.getSteps() != null ? input.getSteps() : numOpt(opts, "steps", Integer.class);
        if (steps != null) {
            adapter.applyBinding(api, "steps", steps);
        }
        Double cfg = input.getCfg() != null ? input.getCfg() : numOpt(opts, "cfg", Double.class);
        if (cfg != null) {
            adapter.applyBinding(api, "cfg", cfg);
        }

        // 6. Seed → 绑定 seed
        Integer s = input.getSeed() != null ? input.getSeed() : numOpt(opts, "seed", Integer.class);
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        adapter.applyBinding(api, "seed", Math.abs(seed));

        // 7. denoise → 绑定 denoise（优先级：options.denoise > input.strength > 默认））
        Double strength = input.getStrength() != null ? input.getStrength() : numOpt(opts, "strength", Double.class);
        Double denoise = numOpt(opts, "denoise", Double.class);
        Double resolved = denoise != null ? denoise : strength;
        if (!hasLatentImage) {
            adapter.applyBinding(api, "denoise", 1.0);
        } else if (resolved != null) {
            adapter.applyBinding(api, "denoise", resolved);
        } else {
            adapter.applyBinding(api, "denoise", DEFAULT_LATENT_DENOISE);
        }

        // 8. 模型槽注入：按当前选择覆盖 UNETLoader.unet_name（无 modelSlots 时 no-op）
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
            if (v instanceof String s) return type.cast(Integer.valueOf(s.trim()));
            return null;
        }
        if (type == Double.class) {
            if (v instanceof Number n) return type.cast(n.doubleValue());
            if (v instanceof String s) return type.cast(Double.valueOf(s.trim()));
            return null;
        }
        return null;
    }

    /**
     * 使用结构化图片输入构建节点。
     * 场景图固定作为 image0 加入 ReferenceLatent 条件链，角色参考图依次作为
     * image1、image2、...；可选的 latentImage 单独作为 KSampler.latent_image。
     *
     * @return 是否存在 latent 初始图（决定 denoise 取值）
     */
    private static boolean applyStructuredImageInputs(JSONObject api, Image2ImageInput images) {
        if (images == null) {
            return false;
        }

        JSONObject samplerInputs = AbstractComfyUIPlugin.navigateTo(api, "18.inputs");
        int nextNodeId = 1000;
        String prevPositive = "19";
        String prevNegative = "62";
        boolean hasLatentImage = false;

        if (StrUtil.isNotBlank(images.getLatentImage())) {
            nextNodeId = addLatentImage(api, samplerInputs, images.getLatentImage(), nextNodeId);
            hasLatentImage = true;
        }

        if (StrUtil.isNotBlank(images.getSceneImage())) {
            ReferenceChain sceneChain = addReferenceImage(
                    api, images.getSceneImage(), prevPositive, prevNegative, nextNodeId
            );
            prevPositive = sceneChain.positiveNodeId;
            prevNegative = sceneChain.negativeNodeId;
            nextNodeId = sceneChain.nextNodeId;
        }

        List<String> characterIds = images.getCharacterIds() != null
                ? images.getCharacterIds()
                : List.of();
        Map<String, String> characterImages = images.getCharacterImages();
        if (CollUtil.isNotEmpty(characterIds)) {
            for (String strId : characterIds) {
                if (StrUtil.isBlank(strId) || strId.startsWith("temp_char_")) {
                    continue;
                }
                String imageName = characterImages != null ? characterImages.get(strId) : null;
                if (StrUtil.isBlank(imageName)) {
                    continue;
                }
                ReferenceChain characterChain = addReferenceImage(
                        api, imageName, prevPositive, prevNegative, nextNodeId
                );
                prevPositive = characterChain.positiveNodeId;
                prevNegative = characterChain.negativeNodeId;
                nextNodeId = characterChain.nextNodeId;
            }
        }

        if (!"19".equals(prevPositive) || !"62".equals(prevNegative)) {
            samplerInputs.set("positive", List.of(prevPositive, 0));
            samplerInputs.set("negative", List.of(prevNegative, 0));
        }
        return hasLatentImage;
    }

    /**
     * 添加可选的 KSampler 初始 latent 图：
     * LoadImage → ImageResizeKJv2 → VAEEncode → KSampler.latent_image。
     *
     * @return 下一个可用节点号
     */
    private static int addLatentImage(
            JSONObject api, JSONObject samplerInputs, String imageName, int nextNodeId
    ) {
        String loadId = String.valueOf(nextNodeId++);
        String resizeId = String.valueOf(nextNodeId++);
        String encodeId = String.valueOf(nextNodeId++);

        JSONObject loadNode = new JSONObject(LOAD_IMAGE_TEMPLATE);
        loadNode.getJSONObject("inputs").set("image", imageName);
        api.set(loadId, loadNode);

        JSONObject resizeNode = new JSONObject(LATENT_RESIZE_TEMPLATE);
        JSONObject resizeInputs = resizeNode.getJSONObject("inputs");
        JSONObject emptyLatentInputs = AbstractComfyUIPlugin.navigateTo(api, "90.inputs");
        resizeInputs.set("image", List.of(loadId, 0));
        resizeInputs.set("width", emptyLatentInputs.getInt("width", 1024));
        resizeInputs.set("height", emptyLatentInputs.getInt("height", 1024));
        api.set(resizeId, resizeNode);

        JSONObject encodeNode = new JSONObject(VAE_ENCODE_TEMPLATE);
        encodeNode.getJSONObject("inputs").set("pixels", List.of(resizeId, 0));
        api.set(encodeId, encodeNode);

        samplerInputs.set("latent_image", List.of(encodeId, 0));
        return nextNodeId;
    }

    /**
     * 添加一张 ReferenceLatent 参考图：
     * LoadImage → ImageScaleToTotalPixels → VAEEncode → ReferenceLatent。
     * 同一张图同时进入正向/负向 ReferenceLatent 链，保证 FLUX.2 多参考图组合可用。
     */
    private static ReferenceChain addReferenceImage(
            JSONObject api,
            String imageName,
            String prevPositive,
            String prevNegative,
            int nextNodeId
    ) {
        String loadId = String.valueOf(nextNodeId++);
        String scaleId = String.valueOf(nextNodeId++);
        String encodeId = String.valueOf(nextNodeId++);
        String positiveRefId = String.valueOf(nextNodeId++);
        String negativeRefId = String.valueOf(nextNodeId++);

        JSONObject loadNode = new JSONObject(LOAD_IMAGE_TEMPLATE);
        loadNode.getJSONObject("inputs").set("image", imageName);
        api.set(loadId, loadNode);

        JSONObject scaleNode = new JSONObject(SCALE_IMAGE_TEMPLATE);
        scaleNode.getJSONObject("inputs").set("image", List.of(loadId, 0));
        api.set(scaleId, scaleNode);

        JSONObject encodeNode = new JSONObject(VAE_ENCODE_TEMPLATE);
        encodeNode.getJSONObject("inputs").set("pixels", List.of(scaleId, 0));
        api.set(encodeId, encodeNode);

        JSONObject positiveRefNode = new JSONObject(REFERENCE_LATENT_TEMPLATE);
        positiveRefNode.getJSONObject("inputs").set("conditioning", List.of(prevPositive, 0));
        positiveRefNode.getJSONObject("inputs").set("latent", List.of(encodeId, 0));
        api.set(positiveRefId, positiveRefNode);

        JSONObject negativeRefNode = new JSONObject(REFERENCE_LATENT_TEMPLATE);
        negativeRefNode.getJSONObject("inputs").set("conditioning", List.of(prevNegative, 0));
        negativeRefNode.getJSONObject("inputs").set("latent", List.of(encodeId, 0));
        api.set(negativeRefId, negativeRefNode);

        return new ReferenceChain(positiveRefId, negativeRefId, nextNodeId);
    }

    /**
     * ReferenceLatent 双链的当前端点。
     */
    private static final class ReferenceChain {
        private final String positiveNodeId;
        private final String negativeNodeId;
        private final int nextNodeId;

        private ReferenceChain(String positiveNodeId, String negativeNodeId, int nextNodeId) {
            this.positiveNodeId = positiveNodeId;
            this.negativeNodeId = negativeNodeId;
            this.nextNodeId = nextNodeId;
        }
    }
}
