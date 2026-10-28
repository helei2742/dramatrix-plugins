package io.dramatrix.plugin.cosyvoice;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2AudioInput;
import io.dramatrix.plugin.interfaces.Text2AudioPlugin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * CosyVoice3 声音克隆+情感控制文生音频适配器。
 * <p>
 * 基于 CosyVoice3 引擎，支持零样本声音克隆与情感指令控制。
 * 每个分段可独立指定情感指令（instructText），全局共享旁白音色（narratorVoice）。
 * </p>
 *
 * @author helei
 */
public class CosyVoiceAudioAdapter extends AbstractComfyUIPlugin implements Text2AudioPlugin {

    public CosyVoiceAudioAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    /**
     * 构造 CosyVoice3 工作流。
     *
     * <p><b>情绪走哪条通道</b>：{@link Text2AudioInput#emoTexts}（自由文本），逐段取
     * {@link Text2AudioInput#emoTextAt}，最终落到 {@code CosyVoiceEngineNode.instruct_text}。
     * 本插件在 {@code adapter.json} 里声明了 {@code extra.emotion = { type: "TEXT", ... }} ——
     * 前端「情绪」面板按该声明渲染文本编辑器，取值由宿主写进请求体的 {@code groups[].emoText}，
     * 经 {@code DesignTTSGenHandler} 归集到 {@code emoTexts} 列表。
     * 声明只描述「编辑器长什么样」（默认指令 / 占位 / 预设），<b>不参与</b>取值传递。</p>
     */
    @Override
    public JSONObject buildApiJson(Text2AudioInput input) {

        // 构造 CosyVoice 分段：按索引一一对应 音色 / 文本 / 情感指令 / 语速
        List<CosyVoiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            segments.add(CosyVoiceSegment.builder()
                    .timbre(input.timbreAt(i))
                    .prompt(input.promptAt(i))
                    .instructText(input.emoTextAt(i))
                    .speed(input.speedAt(i))
                    .build());
        }

        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;

        // 节点参数：调用方按 paramSchema 提交的 options 优先；未提交时回落到 adapter.json 的 extra
        Map<String, Object> extra = input.getOptions();
        if (extra == null || extra.isEmpty()) {
            extra = getMetadata().getExtra();
        }

        JSONObject workflow = CosyVoiceWorkflowBuilder.build(
                segments,
                CosyVoiceWorkflowBuilder.DEFAULT_START_NODE_ID,
                seed,
                extra
        );
        // 模型槽注入：按当前选择覆盖 CosyVoiceEngineNode.model_path（无 modelSlots 时 no-op）
        applyModelSlots(workflow);
        return workflow;
    }

    /**
     * CosyVoice3 工作流 JSON 构建器。
     * <p>
     * 根据输入的音频分段列表，动态生成 ComfyUI workflow JSON，
     * 支持 N 个分段的独立文本和情感指令，<b>每个分段独立输出音频文件，不在 workflow 内拼接</b>。
     * ComfyUI 端为每个分段生成一个 {@code SaveAudioMP3}，业务侧按
     * {@code voice_seg_<index>} 段序号前缀下载并按输入顺序回填。
     * </p>
     *
     * <h3>工作流结构</h3>
     * <pre>
     *   ┌──────────────┬──────────────┐
     *   │ 分段 0        │ 分段 1  ...  │ 分段 N-1
     *   │ LoadAudio    │ LoadAudio    │ LoadAudio
     *   │ EngineNode   │ EngineNode   │ EngineNode
     *   │ TTS TextNode │ TTS TextNode │ TTS TextNode
     *   │ SaveAudioMP3 │ SaveAudioMP3 │ SaveAudioMP3   ← 各自独立保存
     *   └──────────────┴──────────────┴──────────────┘
     * </pre>
     *
     * @author helei
     */
    public static class CosyVoiceWorkflowBuilder {
        /**
         * 情感指令的**硬编码保底**（取值链第三级）：请求侧 {@code emoTexts[i]} →
         * {@code adapter.json} 的 {@code extra.emotion.defaultValue}（前端面板据此播种）→ 本常量。
         * 前两级都空时才落到这里，保证 instruct_text 永不为空。
         */
        private static final String DEFAULT_INSTRUCT_TEXT = "请用自然平静的语气说话。";

        /**
         * CosyVoice3 引擎节点模板
         */
        private static final String ENGINE_SCHEMA = """
            {
                "inputs": {
                    "model_path": "Fun-CosyVoice3-0.5B-RL",
                    "device": "auto",
                    "speed": 1,
                    "use_fp16": true,
                    "instruct_text": "",
                    "load_trt": false,
                    "load_vllm": false
                },
                "class_type": "CosyVoiceEngineNode",
                "_meta": {
                    "title": "⚙️ CosyVoice3 Engine"
                }
            }
            """;

        /**
         * 参考音色加载节点模板（全局共享，通过 audio 字段加载旁白音色文件）
         */
        private static final String LOAD_AUDIO_SCHEMA = """
            {
                "inputs": {
                    "audio": ""
                },
                "class_type": "LoadAudio",
                "_meta": {
                    "title": "加载音频"
                }
            }
            """;

        /**
         * TTS 文生音频节点模板（每分段一个）
         */
        private static final String TTS_TEXT_SCHEMA = """
            {
                "inputs": {
                    "text": "",
                    "narrator_voice": "",
                    "seed": 0,
                    "enable_chunking": true,
                    "max_chars_per_chunk": 400,
                    "chunk_combination_method": "crossfade",
                    "silence_between_chunks_ms": 100,
                    "enable_audio_cache": false,
                    "batch_size": 0,
                    "TTS_engine": ["", 0],
                    "opt_narrator": ["", 0]
                },
                "class_type": "UnifiedTTSTextNode",
                "_meta": {
                    "title": "🎤 TTS Text"
                }
            }
            """;

        /**
         * 每分段的独立保存节点模板。{@code filename_prefix} 在 {@link #build} 中按段序号覆盖为
         * {@code audio/voice_seg_<index>}，用于 ComfyUI 端按文件名排序还原输入顺序。
         */
        private static final String SAVE_AUDIO_SCHEMA = """
            {
                "inputs": {
                    "filename_prefix": "audio/ComfyUI",
                    "quality": "V0",
                    "audioUI": ""
                },
                "class_type": "SaveAudioMP3",
                "_meta": {
                    "title": "保存音频 (MP3)"
                }
            }
            """;

        /** 默认起始节点 ID，避免与模板中已有的节点 ID 冲突 */
        public static final int DEFAULT_START_NODE_ID = 1000;

        // ==================== 公开构建方法 ====================

        /**
         * 从分段列表构建工作流 JSON（每个分段独立输出音频文件，不在 workflow 内拼接）。
         *
         * @param segments     音频分段列表，长度 ≥ 1
         * @param startNodeId  起始节点 ID
         * @param seed         随机种子
         * @param extra        全局额外参数（model_path, device, max_chars_per_chunk 等）
         * @return ComfyUI workflow JSONObject
         */
        public static JSONObject build(
                List<CosyVoiceSegment> segments,
                int startNodeId,
                int seed,
                Map<String, Object> extra
        ) {
            if (segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("segments must not be empty");
            }

            // ---- 解析全局参数 ----
            String modelPath = getStringParam(extra, "model_path", "Fun-CosyVoice3-0.5B-RL");
            String device = getStringParam(extra, "device", "auto");
            boolean useFp16 = getBoolParam(extra, "use_fp16", true);
            boolean loadTrt = getBoolParam(extra, "load_trt", false);
            boolean loadVllm = getBoolParam(extra, "load_vllm", false);
            double globalSpeed = getDoubleParam(extra, "speed", 1.0);

            int maxCharsPerChunk = getIntParam(extra, "max_chars_per_chunk", 400);
            String chunkCombinationMethod = getStringParam(extra, "chunk_combination_method", "crossfade");
            int silenceBetweenChunksMs = getIntParam(extra, "silence_between_chunks_ms", 100);
            boolean enableAudioCache = getBoolParam(extra, "enable_audio_cache", false);

            JSONObject workflow = new JSONObject();
            int nodeId = startNodeId;

            // ---- 为每个分段生成 LoadAudio + EngineNode + UnifiedTTSTextNode + SaveAudioMP3 ----
            for (int segIdx = 0; segIdx < segments.size(); segIdx++) {
                CosyVoiceSegment segment = segments.get(segIdx);

                // 1a. LoadAudio（每个分段独立音色）
                String loadAudioNodeId = String.valueOf(nodeId++);
                JSONObject loadAudioNode = new JSONObject(LOAD_AUDIO_SCHEMA);
                loadAudioNode.getJSONObject("inputs").set("audio", segment.getTimbre() != null ? segment.getTimbre() : "");
                workflow.set(loadAudioNodeId, loadAudioNode);

                // 1b. CosyVoiceEngineNode（每个分段独立，支持不同的 instruct_text）
                String engineNodeId = String.valueOf(nodeId++);
                JSONObject engineNode = new JSONObject(ENGINE_SCHEMA);
                JSONObject engineInputs = engineNode.getJSONObject("inputs");
                engineInputs.set("model_path", modelPath);
                engineInputs.set("device", device);
                engineInputs.set("speed", segment.getSpeed() != null ? segment.getSpeed() : globalSpeed);
                engineInputs.set("use_fp16", useFp16);
                engineInputs.set("instruct_text",
                        StrUtil.isNotBlank(segment.getInstructText()) ? segment.getInstructText() : DEFAULT_INSTRUCT_TEXT);
                engineInputs.set("load_trt", loadTrt);
                engineInputs.set("load_vllm", loadVllm);
                workflow.set(engineNodeId, engineNode);

                // 1c. UnifiedTTSTextNode
                String ttsNodeId = String.valueOf(nodeId++);
                JSONObject ttsNode = new JSONObject(TTS_TEXT_SCHEMA);
                JSONObject ttsInputs = ttsNode.getJSONObject("inputs");
                ttsInputs.set("text", segment.getPrompt());
                ttsInputs.set("narrator_voice", "none");
                // 确定性 seed：相同文本 + 音色 → 相同结果，可复现
                ttsInputs.set("seed", deterministicSeed(segment.getPrompt(), segment.getTimbre(), seed));
                ttsInputs.set("enable_chunking", true);
                ttsInputs.set("max_chars_per_chunk", maxCharsPerChunk);
                ttsInputs.set("chunk_combination_method", chunkCombinationMethod);
                ttsInputs.set("silence_between_chunks_ms", silenceBetweenChunksMs);
                ttsInputs.set("enable_audio_cache", enableAudioCache);
                // 连线：TTS_engine → EngineNode output[0], opt_narrator → LoadAudio output[0]
                ttsInputs.set("TTS_engine", List.of(engineNodeId, 0));
                ttsInputs.set("opt_narrator", List.of(loadAudioNodeId, 0));
                workflow.set(ttsNodeId, ttsNode);

                // 1d. SaveAudioMP3（每分段独立保存，filename_prefix 编码段序号以便下载端排序）
                String saveNodeId = String.valueOf(nodeId++);
                JSONObject saveNode = new JSONObject(SAVE_AUDIO_SCHEMA);
                saveNode.getJSONObject("inputs")
                        .set("filename_prefix", "audio/voice_seg_" + segIdx);
                saveNode.getJSONObject("inputs").set("audio", List.of(ttsNodeId, 0));
                workflow.set(saveNodeId, saveNode);
            }

            return workflow;
        }

        // ==================== 便捷方法 ====================

        /**
         * 使用默认起始节点 ID 构建。
         */
        public static JSONObject build(
                List<CosyVoiceSegment> segments,
                int seed,
                Map<String, Object> extra
        ) {
            return build(segments, DEFAULT_START_NODE_ID, seed, extra);
        }

        // ==================== 内部工具方法 ====================

        private static String getStringParam(Map<String, Object> map, String key, String defaultVal) {
            if (map == null) return defaultVal;
            Object v = map.get(key);
            return v != null ? v.toString() : defaultVal;
        }

        private static double getDoubleParam(Map<String, Object> map, String key, double defaultVal) {
            if (map == null) return defaultVal;
            Object v = map.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
            }
            return defaultVal;
        }

        private static boolean getBoolParam(Map<String, Object> map, String key, boolean defaultVal) {
            if (map == null) return defaultVal;
            Object v = map.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return Boolean.parseBoolean((String) v);
            return defaultVal;
        }

        private static int getIntParam(Map<String, Object> map, String key, int defaultVal) {
            if (map == null) return defaultVal;
            Object v = map.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
            }
            return defaultVal;
        }

        /**
         * 生成确定性 seed：相同 prompt + narratorVoice + baseSeed → 相同 seed。
         * 确保相同对话内容可复现，不同内容有自然韵律差异。
         */
        private static int deterministicSeed(String prompt, String narratorVoice, int baseSeed) {
            int h = Objects.hash(
                    prompt == null ? "" : prompt,
                    narratorVoice == null ? "" : narratorVoice,
                    baseSeed
            );
            return Math.floorMod(h, 1_000_000);
        }
    }


    /**
     * CosyVoice 音频分段参数。
     * 每个分段 = 一段台词 + 情感指令 + 语速。
     *
     * @author helei
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CosyVoiceSegment {

        /**
         * 参考音频文件名（已上传到 ComfyUI input 目录），即旁白音色。
         * 每个分段可以有独立的音色。
         */
        private String timbre;

        /**
         * TTS 要合成的文本 / 台词
         */
        private String prompt;

        /**
         * 情感指令文本（自然语言描述，如 "请用非常激动、高兴且难以置信的语气说话"）。
         * 对应 CosyVoiceEngineNode 的 instruct_text 参数。
         * 为空则使用默认指令 "请用自然平静的语气说话。"
         */
        private String instructText;

        /**
         * 语速系数，1.0 为正常语速，>1 加速，<1 减速。
         * 对应 CosyVoiceEngineNode 的 speed 参数。
         */
        @Builder.Default
        private Double speed = 1.0;
    }
}
