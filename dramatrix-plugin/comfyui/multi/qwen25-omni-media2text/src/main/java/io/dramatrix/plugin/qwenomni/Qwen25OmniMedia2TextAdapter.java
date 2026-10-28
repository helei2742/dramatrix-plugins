package io.dramatrix.plugin.qwenomni;

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
 * Qwen2.5-Omni 多模态理解插件适配器。
 *
 * <p>将 {@link Media2TextInput} 映射为内部 {@link QwenOmniWorkflowInput}
 * → {@link QwenOmniWorkflowBuilder} 构建 ComfyUI API JSON。
 * <p>支持的模态：TEXT, IMAGE, AUDIO, VIDEO（视频在节点不可用时自动降级为图像）。
 *
 * <h3>迁移说明</h3>
 * {@code QwenOmniWorkflowInput} 与 {@code QwenOmniWorkflowBuilder} 从
 * {@code dramatrix-infrastructure/.../qwenomni/} 原样迁移，
 * JSON 操作已从 fastjson 改写为 hutool。
 * 移除了对 {@code QwenOmniOptions} 的依赖（fromOptions 方法）。
 *
 * <h3>视频降级</h3>
 * ComfyUI 侧 VideoUploader 节点可能未安装。通过
 * {@code adapter.json} 的 {@code extra.features.videoSupport} 控制：
 * <ul>
 *   <li>{@code AUTO} — 自动探测（默认启用视频）</li>
 *   <li>{@code DISABLED} — 总是降级为图像理解</li>
 *   <li>{@code FORCE} — 总是启用视频（无降级）</li>
 * </ul>
 */
@Slf4j
public class Qwen25OmniMedia2TextAdapter extends AbstractComfyUIPlugin implements Media2TextPlugin {

    /** 视频支持状态（从 adapter.json 读取，仅首次解析一次） */
    private volatile String videoSupport;

    public Qwen25OmniMedia2TextAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Media2TextInput input) {
        // 视频降级检查
        if (StrUtil.isNotBlank(input.getVideoFile())) {
            if (isVideoDisabled()) {
                log.warn("VideoUploader 节点不可用，视频降级为图像理解: plugin=qwen25-omni-media2text");
                input.setImageFile(input.getVideoFile());
                input.setVideoFile(null);
            }
        }
        QwenOmniWorkflowInput req = toRequest(input);
        JSONObject workflow = new QwenOmniWorkflowBuilder().build(req);
        // 请求级覆盖：运行方显式传的 modelType / quantization 优先级最高，覆盖对应模型槽；未显式传（走 defaults 兜底）才由槽当前选择决定。
        Map<String, String> override = null;
        Map<String, Object> opts = input.getOptions();
        if (opts != null) {
            Map<String, String> tmp = new HashMap<>();
            Object m = opts.get("modelType");
            Object q = opts.get("quantization");
            if (m != null) tmp.put("qwen25-omni-model", String.valueOf(m));
            if (q != null) tmp.put("qwen25-omni-quant", String.valueOf(q));
            if (!tmp.isEmpty()) override = tmp;
        }
        applyModelSlots(workflow, override);
        return workflow;
    }

    // ════════════════════════════ 视频降级 ════════════════════════════

    private boolean isVideoDisabled() {
        if (videoSupport == null) {
            synchronized (this) {
                if (videoSupport == null) {
                    videoSupport = resolveVideoSupport();
                }
            }
        }
        return "DISABLED".equalsIgnoreCase(videoSupport);
    }

    private String resolveVideoSupport() {
        Object features = getMetaExtra("features");
        if (features instanceof Map<?, ?> m) {
            Object vs = m.get("videoSupport");
            if (vs instanceof String s && !s.isBlank()) return s;
        }
        return "AUTO"; // 默认启用
    }

    // ════════════════════════════ 参数映射 ════════════════════════════

    private QwenOmniWorkflowInput toRequest(Media2TextInput input) {
        Map<String, Object> defs = safeDefaults();
        Map<String, Object> opts = input.getOptions();

        QwenOmniWorkflowInput req = new QwenOmniWorkflowInput();
        req.setPrompt(input.getPrompt());
        req.setStartNodeId(input.getStartNodeId());

        // 媒体文件
        req.setImageFile(input.getImageFile());
        req.setAudioFile(input.getAudioFile());
        req.setVideoFile(input.getVideoFile());

        // 模型参数
        String modelType = pick(opts, defs, "modelType", "Qwen2.5-Omni-7B");
        req.setModelType(QwenOmniWorkflowInput.ModelType.fromString(modelType));

        String quantization = pick(opts, defs, "quantization", "👍 4-bit (VRAM-friendly)");
        req.setQuantization(QwenOmniWorkflowInput.Quantization.fromString(quantization));

        // 生成参数
        req.setMaxTokens(pickInt(opts, defs, "maxTokens", 2048));
        req.setTemperature(pickDouble(opts, defs, "temperature", 0.4));
        req.setTopP(pickDouble(opts, defs, "topP", 0.9));
        req.setRepetitionPenalty(pickDouble(opts, defs, "repetitionPenalty", 1.0));

        // 音频输出
        String audioOutput = pick(opts, defs, "audioOutputMode", "🔇None (No Audio)");
        req.setAudioOutputMode(QwenOmniWorkflowInput.AudioOutput.fromString(audioOutput));

        String audioSource = pick(opts, defs, "audioSource", "🎧 Separate Audio Input");
        req.setAudioSource(QwenOmniWorkflowInput.AudioSource.fromString(audioSource));

        return req;
    }

    // ════════════════════════════ 防御性工具 ════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeDefaults() {
        Object raw = getMetaExtra("defaults");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        log.warn("adapter.json extra.defaults 格式异常: plugin=qwen25-omni-media2text");
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
     * Qwen Omni 工作流入参（从 infra 迁移）。
     * 移除了 {@code fromOptions(QwenOmniOptions)} 方法。
     */
    @Setter
    @Getter
    public static class QwenOmniWorkflowInput {

        // --- getters ---
        private ModelType modelType = ModelType.Qwen2_5_Omni_7B;
        private Quantization quantization = Quantization.int4;
        private String prompt;

        private Integer maxTokens = 1024;
        private Double temperature = 0.4;
        private Double topP = 0.9;
        private Double repetitionPenalty = 1.0;

        private AudioOutput audioOutputMode = AudioOutput.none;
        private AudioSource audioSource = AudioSource.separate;

        private String imageFile;
        private String audioFile;
        private String videoFile;

        private boolean saveText = true;
        private boolean saveAudio = false;
        private String audioFilenamePrefix = "audio/ComfyUI";
        private int startNodeId = 1;

        @Getter
        public enum ModelType {
            Qwen2_5_Omni_3B("Qwen2.5-Omni-3B"),
            Qwen2_5_Omni_7B("Qwen2.5-Omni-7B"),
            ;

            private final String value;

            ModelType(String value) { this.value = value; }

            public static ModelType fromString(String s) {
                if (s == null || s.isBlank()) return Qwen2_5_Omni_7B;
                for (ModelType m : values()) {
                    if (m.value.equals(s) || m.name().equals(s)) return m;
                }
                log.warn("未知模型类型: {}, 回退到 {}", s, Qwen2_5_Omni_7B);
                return Qwen2_5_Omni_7B;
            }
        }

        @Getter
        public enum Quantization {
            int4("👍 4-bit (VRAM-friendly)"),
            int8("⚖️ 8-bit (Balanced Precision)"),
            none("🚫 None (Original Precision)"),
            ;

            private final String value;

            Quantization(String value) { this.value = value; }

            public static Quantization fromString(String s) {
                if (s == null) return int4;
                if (s.contains("4-bit")) return int4;
                if (s.contains("8-bit")) return int8;
                if (s.contains("None") || s.equalsIgnoreCase("none")) return none;
                try { return valueOf(s.toLowerCase()); } catch (IllegalArgumentException e) { return int4; }
            }
        }

        @Getter
        public enum AudioOutput {
            none("🔇None (No Audio)"),
            female("👱‍♀️Chelsie (Female)"),
            male("👨‍🦰Ethan (Male)"),
            ;

            private final String value;

            AudioOutput(String value) { this.value = value; }

            public static AudioOutput fromString(String s) {
                if (s == null) return none;
                if (s.contains("None")) return none;
                if (s.contains("Female") || s.contains("Chelsie")) return female;
                if (s.contains("Male") || s.contains("Ethan")) return male;
                try { return valueOf(s.toLowerCase()); } catch (IllegalArgumentException e) { return none; }
            }
        }

        @Getter
        public enum AudioSource {
            separate("🎧 Separate Audio Input"),
            video("🎬 Video Built-in Audio"),
            ;

            private final String value;

            AudioSource(String value) { this.value = value; }

            public static AudioSource fromString(String s) {
                if (s == null) return separate;
                if (s.contains("Video") || s.contains("Built-in")) return video;
                return separate;
            }
        }
    } // end QwenOmniWorkflowInput

    /**
     * Qwen Omni 工作流构建器（hutool 改写版）。
     * 从 dramatrix-infrastructure 原样迁移，JSON 操作改为 hutool。
     */
    public static class QwenOmniWorkflowBuilder {

        private static final String QWEN_OMNI_SCHEMA = """
                {
                    "inputs": {
                        "model_name": "Qwen2.5-Omni-7B",
                        "quantization": "👍 4-bit (VRAM-friendly)",
                        "prompt": "",
                        "audio_output": "🔇None (No Audio)",
                        "audio_source": "🎧 Separate Audio Input",
                        "max_tokens": 132,
                        "temperature": 0.4,
                        "top_p": 0.9,
                        "repetition_penalty": 1
                    },
                    "class_type": "QwenOmniCombined",
                    "_meta": {
                        "title": "Qwen Omni Combined🐼"
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

        private static final String LOAD_AUDIO_SCHEMA = """
                {
                    "inputs": {
                        "audio": "",
                        "audioUI": ""
                    },
                    "class_type": "LoadAudio",
                    "_meta": {
                        "title": "加载音频"
                    }
                }
                """;

        private static final String VIDEO_UPLOADER_SCHEMA = """
                {
                    "inputs": {},
                    "class_type": "VideoUploader",
                    "_meta": {
                        "title": "Video Uploader🐼"
                    }
                }
                """;

        private static final String SAVE_TEXT_SCHEMA = """
                {
                    "inputs": {
                    },
                    "class_type": "ShowText|pysssss",
                    "_meta": {
                      "title": "Show Text 🐍"
                    }
                }
                """;

        private static final String SAVE_AUDIO_SCHEMA = """
                {
                    "inputs": {
                        "filename_prefix": "audio/ComfyUI",
                        "audioUI": ""
                    },
                    "class_type": "SaveAudio",
                    "_meta": {
                        "title": "保存音频"
                    }
                }
                """;

        private static final String AUDIO_OUTPUT_ENABLED = QwenOmniWorkflowInput.AudioOutput.male.getValue();

        public JSONObject build(QwenOmniWorkflowInput input) {
            validate(input);

            JSONObject workflow = new JSONObject();
            int nodeId = input.getStartNodeId();

            String imageNodeId = null;
            String audioNodeId = null;
            String videoNodeId = null;

            // 1. Optional input nodes
            if (input.getImageFile() != null && !input.getImageFile().isBlank()) {
                String nid = String.valueOf(nodeId++);
                workflow.set(nid, buildLoadImageNode(input.getImageFile()));
                imageNodeId = nid;
            }

            if (input.getAudioFile() != null && !input.getAudioFile().isBlank()) {
                String nid = String.valueOf(nodeId++);
                workflow.set(nid, buildLoadAudioNode(input.getAudioFile()));
                audioNodeId = nid;
            }

            if (input.getVideoFile() != null && !input.getVideoFile().isBlank()) {
                String nid = String.valueOf(nodeId++);
                workflow.set(nid, buildVideoUploaderNode(input.getVideoFile()));
                videoNodeId = nid;
            }

            // 2. QwenOmniCombined
            String qwenNodeId = String.valueOf(nodeId++);
            workflow.set(qwenNodeId, buildQwenOmniNode(input, imageNodeId, audioNodeId, videoNodeId));

            // 3. Output nodes
            if (input.isSaveText()) {
                workflow.set(String.valueOf(nodeId++), buildSaveTextNode(qwenNodeId));
            }

            if (input.isSaveAudio()) {
                workflow.set(String.valueOf(nodeId++), buildSaveAudioNode(input, qwenNodeId));
            }

            return workflow;
        }

        private static JSONObject buildLoadImageNode(String imageFile) {
            JSONObject node = new JSONObject(LOAD_IMAGE_SCHEMA);
            node.getJSONObject("inputs").set("image", imageFile);
            return node;
        }

        private static JSONObject buildLoadAudioNode(String audioFile) {
            JSONObject node = new JSONObject(LOAD_AUDIO_SCHEMA);
            node.getJSONObject("inputs").set("audio", audioFile);
            return node;
        }

        private static JSONObject buildVideoUploaderNode(String videoFile) {
            JSONObject node = new JSONObject(VIDEO_UPLOADER_SCHEMA);
            node.getJSONObject("inputs").set("video", videoFile);
            return node;
        }

        private static JSONObject buildQwenOmniNode(
                QwenOmniWorkflowInput input,
                String imageNodeId,
                String audioNodeId,
                String videoNodeId
        ) {
            JSONObject node = new JSONObject(QWEN_OMNI_SCHEMA);
            JSONObject inputs = node.getJSONObject("inputs");

            inputs.set("model_name", input.getModelType().getValue());
            inputs.set("quantization", input.getQuantization().getValue());
            inputs.set("prompt", input.getPrompt());
            inputs.set("max_tokens", input.getMaxTokens());
            inputs.set("temperature", input.getTemperature());
            inputs.set("top_p", input.getTopP());
            inputs.set("repetition_penalty", input.getRepetitionPenalty());

            if (input.isSaveAudio()) {
                inputs.set("audio_output", AUDIO_OUTPUT_ENABLED);
            } else {
                inputs.set("audio_output", input.getAudioOutputMode().getValue());
            }

            inputs.set("audio_source", input.getAudioSource().getValue());

            if (imageNodeId != null) {
                inputs.set("image", List.of(imageNodeId, 0));
            }
            if (audioNodeId != null) {
                inputs.set("audio", List.of(audioNodeId, 0));
            }
            if (videoNodeId != null) {
                inputs.set("video_path", List.of(videoNodeId, 0));
            }

            return node;
        }

        private static JSONObject buildSaveTextNode(String qwenNodeId) {
            JSONObject node = new JSONObject(SAVE_TEXT_SCHEMA);
            node.getJSONObject("inputs").set("text", List.of(qwenNodeId, 0));
            return node;
        }

        private static JSONObject buildSaveAudioNode(QwenOmniWorkflowInput input, String qwenNodeId) {
            JSONObject node = new JSONObject(SAVE_AUDIO_SCHEMA);
            JSONObject inputs = node.getJSONObject("inputs");
            inputs.set("filename_prefix", input.getAudioFilenamePrefix());
            inputs.set("audio", List.of(qwenNodeId, 1));
            return node;
        }

        private static void validate(QwenOmniWorkflowInput input) {
            if (input == null) {
                throw new IllegalArgumentException("input must not be null");
            }
            if (input.getPrompt() == null || input.getPrompt().isBlank()) {
                throw new IllegalArgumentException("prompt must not be blank");
            }
            if (!input.isSaveText() && !input.isSaveAudio()) {
                throw new IllegalArgumentException(
                        "at least one output must be enabled: saveText or saveAudio");
            }
            if (input.getStartNodeId() < 1) {
                throw new IllegalArgumentException("startNodeId must be >= 1, got: " + input.getStartNodeId());
            }
        }
    } // end QwenOmniWorkflowBuilder
}
