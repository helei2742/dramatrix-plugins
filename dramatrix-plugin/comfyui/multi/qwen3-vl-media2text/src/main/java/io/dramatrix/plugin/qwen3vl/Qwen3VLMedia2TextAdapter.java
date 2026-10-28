package io.dramatrix.plugin.qwen3vl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.*;
import io.dramatrix.plugin.dto.Media2TextInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Media2TextPlugin;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Qwen3-VL 图像理解插件适配器。
 *
 * <p>将 {@link Media2TextInput} 映射为内部 {@link QwenVLWorkflowInput}
 * → {@link QwenVLWorkflowBuilder} 构建 ComfyUI API JSON。
 * <p>支持的模态：TEXT, IMAGE。
 *
 * <h3>迁移说明</h3>
 * {@code QwenVLWorkflowInput} 与 {@code QwenVLWorkflowBuilder} 从
 * {@code dramatrix-infrastructure/.../qwenvl/} 原样迁移，
 * JSON 操作已从 fastjson 改写为 hutool。
 * 移除了对 {@code QwenVLOptions} 的依赖（fromOptions 方法），
 * 改为通过 {@code toRequest()} 统一从 {@code Media2TextInput} 映射。
 */
@Slf4j
public class Qwen3VLMedia2TextAdapter extends AbstractComfyUIPlugin implements Media2TextPlugin {

    public Qwen3VLMedia2TextAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Media2TextInput input) {
        if (StrUtil.isBlank(input.getImageFile())) {
            throw new IllegalArgumentException("Qwen3-VL 需要图像输入");
        }
        QwenVLWorkflowInput req = toRequest(input);
        JSONObject workflow = new QwenVLWorkflowBuilder().build(req);
        // 请求级覆盖：若调用方在 options 里显式传了 modelName，它优先级最高，覆盖模型槽选择；
        // 未显式传（走 defaults 兜底）时，才由模型槽当前选择决定 model_name。
        String explicitModel = input.getOptions() == null ? null : (String) input.getOptions().get("modelName");
        Map<String, String> override = StrUtil.isNotBlank(explicitModel) ? Map.of("qwen-vl", explicitModel) : null;
        applyModelSlots(workflow, override);
        return workflow;
    }

    // ════════════════════════════ 参数映射 ════════════════════════════

    private QwenVLWorkflowInput toRequest(Media2TextInput input) {
        Map<String, Object> defs = safeDefaults();
        Map<String, Object> opts = input.getOptions();

        QwenVLWorkflowInput req = new QwenVLWorkflowInput();
        req.setPrompt(input.getPrompt());
        req.setImageFile(input.getImageFile());
        req.setStartNodeId(input.getStartNodeId());

        // 模型参数
        String modelName = pick(opts, defs, "modelName", "Qwen3-VL-8B-Instruct");
        req.setModelName(QwenVLWorkflowInput.ModelName.fromString(modelName));

        String quantization = pick(opts, defs, "quantization", "None (FP16)");
        req.setQuantization(QwenVLWorkflowInput.Quantization.fromString(quantization));

        String attention = pick(opts, defs, "attentionMode", "auto");
        req.setAttentionMode(QwenVLWorkflowInput.AttentionMode.fromString(attention));

        // 生成参数
        req.setMaxTokens(pickInt(opts, defs, "maxTokens", 2048));
        req.setTemperature(pickDouble(opts, defs, "temperature", 0.6));
        req.setTopP(pickDouble(opts, defs, "topP", 0.9));
        req.setRepetitionPenalty(pickDouble(opts, defs, "repetitionPenalty", 1.2));
        req.setSeed(pickInt(opts, defs, "seed", 1));

        return req;
    }

    // ════════════════════════════ 防御性工具 ════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeDefaults() {
        Object raw = getMetaExtra("defaults");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        log.warn("adapter.json extra.defaults 格式异常: plugin=qwen3-vl-media2text");
        return Map.of();
    }

    private static String pick(Map<String, Object> opts, Map<String, Object> defs,
                                String key, String fallback) {
        Object val = null;
        if (opts != null) val = opts.get(key);
        if (val == null && defs != null) val = defs.get(key);
        if (val == null) return fallback;
        return val.toString();
    }

    private static int pickInt(Map<String, Object> opts, Map<String, Object> defs,
                                String key, int fallback) {
        Object val = null;
        if (opts != null) val = opts.get(key);
        if (val == null && defs != null) val = defs.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && StrUtil.isNotBlank(s)) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static double pickDouble(Map<String, Object> opts, Map<String, Object> defs,
                                      String key, double fallback) {
        Object val = null;
        if (opts != null) val = opts.get(key);
        if (val == null && defs != null) val = defs.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s && StrUtil.isNotBlank(s)) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    // ════════════════════════════ 内部类 ════════════════════════════

    /**
     * QwenVL 工作流入参（从 infra 迁移）。
     * 移除了 {@code fromOptions(QwenVLOptions)} 方法。
     */
    @Setter
    @Getter
    public static class QwenVLWorkflowInput {

        // --- getters ---
        private ModelName modelName = ModelName.QWEN3_VL_8B_INSTRUCT;
        private Quantization quantization = Quantization.NONE;
        private AttentionMode attentionMode = AttentionMode.AUTO;
        private boolean useTorchCompile = false;
        private String device = "auto";

        private String prompt = "";

        private Integer maxTokens = 512;
        private Double temperature = 0.6;
        private Double topP = 0.9;
        private Integer numBeams = 1;
        private Double repetitionPenalty = 1.2;

        private Integer frameCount = 16;
        private boolean keepModelLoaded = true;
        private Integer seed = 1;

        private String imageFile;

        private boolean saveText = true;
        private int startNodeId = 1;

        @Getter
        public enum ModelName {
            QWEN3_VL_2B_INSTRUCT("Qwen3-VL-2B-Instruct"),
            QWEN3_VL_2B_THINKING("Qwen3-VL-2B-Thinking"),
            QWEN3_VL_2B_INSTRUCT_FP8("Qwen3-VL-2B-Instruct-FP8"),
            QWEN3_VL_2B_THINKING_FP8("Qwen3-VL-2B-Thinking-FP8"),
            QWEN3_VL_4B_INSTRUCT("Qwen3-VL-4B-Instruct"),
            QWEN3_VL_4B_THINKING("Qwen3-VL-4B-Thinking"),
            QWEN3_VL_4B_INSTRUCT_FP8("Qwen3-VL-4B-Instruct-FP8"),
            QWEN3_VL_4B_THINKING_FP8("Qwen3-VL-4B-Thinking-FP8"),
            QWEN3_VL_8B_INSTRUCT("Qwen3-VL-8B-Instruct"),
            QWEN3_VL_8B_THINKING("Qwen3-VL-8B-Thinking"),
            QWEN3_VL_8B_INSTRUCT_FP8("Qwen3-VL-8B-Instruct-FP8"),
            QWEN3_VL_8B_THINKING_FP8("Qwen3-VL-8B-Thinking-FP8"),
            QWEN3_VL_32B_INSTRUCT("Qwen3-VL-32B-Instruct"),
            QWEN3_VL_32B_THINKING("Qwen3-VL-32B-Thinking"),
            QWEN3_VL_32B_INSTRUCT_FP8("Qwen3-VL-32B-Instruct-FP8"),
            QWEN3_VL_32B_THINKING_FP8("Qwen3-VL-32B-Thinking-FP8"),
            QWEN25_VL_3B_INSTRUCT("Qwen2.5-VL-3B-Instruct"),
            QWEN25_VL_7B_INSTRUCT("Qwen2.5-VL-7B-Instruct"),
            ;

            private final String value;

            ModelName(String value) { this.value = value; }

            /** 从字符串匹配枚举（兼容 "Qwen3-VL-8B-Instruct" 与 "Qwen3_VL_8B_INSTRUCT"） */
            public static ModelName fromString(String s) {
                if (s == null || s.isBlank()) return QWEN3_VL_8B_INSTRUCT;
                for (ModelName m : values()) {
                    if (m.name().replace('_', '-').equalsIgnoreCase(s.replace('_', '-'))) return m;
                }
                log.warn("未知模型名: {}, 回退到 {}", s, QWEN3_VL_8B_INSTRUCT);
                return QWEN3_VL_8B_INSTRUCT;
            }
        }

        @Getter
        public enum Quantization {
            INT4("4-bit (VRAM-friendly)"),
            INT8("8-bit (Balanced)"),
            NONE("None (FP16)"),
            ;

            private final String value;

            Quantization(String value) { this.value = value; }

            public static Quantization fromString(String s) {
                if (s == null) return NONE;
                if (s.contains("4-bit")) return INT4;
                if (s.contains("8-bit")) return INT8;
                if (s.contains("FP16") || s.equalsIgnoreCase("none")) return NONE;
                try { return valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return NONE; }
            }
        }

        @Getter
        public enum AttentionMode {
            AUTO("auto"),
            SAGE("sage"),
            FLASH_ATTENTION_2("flash_attention_2"),
            SDPA("sdpa"),
            ;

            private final String value;

            AttentionMode(String value) { this.value = value; }

            public static AttentionMode fromString(String s) {
                if (s == null) return AUTO;
                try { return valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return AUTO; }
            }
        }
    } // end QwenVLWorkflowInput

    /**
     * QwenVL 工作流构建器（hutool 改写版）。
     * 从 dramatrix-infrastructure 原样迁移，JSON 操作改为 hutool。
     */
    public static class QwenVLWorkflowBuilder {

        private static final String DEFAULT_PRESET_PROMPT = "\uD83D\uDDBC\uFE0F Detailed Description";

        private static final String QWEN_VL_MODEL_SCHEMA = """
                {
                    "inputs": {
                        "model_name": "Qwen3-VL-2B-Instruct",
                        "quantization": "None (FP16)",
                        "attention_mode": "auto",
                        "use_torch_compile": false,
                        "device": "auto",
                        "preset_prompt": "🖼️ Detailed Description",
                        "custom_prompt": "",
                        "max_tokens": 512,
                        "temperature": 0.6,
                        "top_p": 0.9,
                        "num_beams": 1,
                        "repetition_penalty": 1.2,
                        "frame_count": 16,
                        "keep_model_loaded": true,
                        "seed": 1
                    },
                    "class_type": "AILab_QwenVL_Advanced",
                    "_meta": {
                        "title": "QwenVL (Advanced)"
                    }
                }
                """;

        private static final String LOAD_IMAGE_SCHEMA = """
                {
                    "inputs": {
                        "image": ""
                    },
                    "class_type": "LoadImage",
                    "_meta": {
                        "title": "加载图像"
                    }
                }
                """;

        private static final String SHOW_TEXT_SCHEMA = """
                {
                    "inputs": {
                    },
                    "class_type": "ShowText|pysssss",
                    "_meta": {
                        "title": "Show Text 🐍"
                    }
                }
                """;

        public JSONObject build(QwenVLWorkflowInput input) {
            validate(input);

            JSONObject workflow = new JSONObject();
            int nodeId = input.getStartNodeId();

            // 1. LoadImage
            String imageNodeId = String.valueOf(nodeId++);
            workflow.set(imageNodeId, buildLoadImageNode(input.getImageFile()));

            // 2. AILab_QwenVL_Advanced
            String modelNodeId = String.valueOf(nodeId++);
            workflow.set(modelNodeId, buildModelNode(input, imageNodeId));

            // 3. ShowText
            if (input.isSaveText()) {
                workflow.set(String.valueOf(nodeId++), buildShowTextNode(modelNodeId));
            }

            return workflow;
        }

        private static JSONObject buildLoadImageNode(String imageFile) {
            JSONObject node = new JSONObject(LOAD_IMAGE_SCHEMA);
            node.getJSONObject("inputs").set("image", imageFile);
            return node;
        }

        private static JSONObject buildModelNode(QwenVLWorkflowInput input, String imageNodeId) {
            JSONObject node = new JSONObject(QWEN_VL_MODEL_SCHEMA);
            JSONObject inputs = node.getJSONObject("inputs");

            inputs.set("model_name", input.getModelName().getValue());
            inputs.set("quantization", input.getQuantization().getValue());
            inputs.set("attention_mode", input.getAttentionMode().getValue());
            inputs.set("use_torch_compile", input.isUseTorchCompile());
            inputs.set("device", input.getDevice());

            inputs.set("preset_prompt", DEFAULT_PRESET_PROMPT);
            inputs.set("custom_prompt", input.getPrompt());

            inputs.set("max_tokens", input.getMaxTokens());
            inputs.set("temperature", input.getTemperature());
            inputs.set("top_p", input.getTopP());
            inputs.set("num_beams", input.getNumBeams());
            inputs.set("repetition_penalty", input.getRepetitionPenalty());

            inputs.set("frame_count", input.getFrameCount());
            inputs.set("keep_model_loaded", input.isKeepModelLoaded());
            inputs.set("seed", input.getSeed());

            inputs.set("image", List.of(imageNodeId, 0));

            return node;
        }

        private static JSONObject buildShowTextNode(String modelNodeId) {
            JSONObject node = new JSONObject(SHOW_TEXT_SCHEMA);
            node.getJSONObject("inputs").set("text", List.of(modelNodeId, 0));
            return node;
        }

        private static void validate(QwenVLWorkflowInput input) {
            if (input == null) {
                throw new IllegalArgumentException("input must not be null");
            }
            if (input.getImageFile() == null || input.getImageFile().isBlank()) {
                throw new IllegalArgumentException("imageFile must not be blank for VL model");
            }
            if (input.getStartNodeId() < 1) {
                throw new IllegalArgumentException("startNodeId must be >= 1, got: " + input.getStartNodeId());
            }
        }
    } // end QwenVLWorkflowBuilder
}
