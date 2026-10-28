package io.dramatrix.plugin.indextts2;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
 * IndexTTS2 文生音频适配器。
 */
public class IndexTTS2AudioAdapter extends AbstractComfyUIPlugin implements Text2AudioPlugin {

    public IndexTTS2AudioAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    /**
     * 构造 IndexTTS2 工作流。
     *
     * <p><b>情绪走哪条通道</b>：{@link Text2AudioInput#emotions}（8 维向量），逐段取
     * {@link Text2AudioInput#emotionAt}。本插件在 {@code adapter.json} 里声明了
     * {@code extra.emotion = { type: "VECTOR", dims: [...] }} —— 前端「情绪」面板按该声明渲染
     * 编辑器，取值由宿主写进请求体的 {@code groups[].emotion}，经
     * {@code DesignTTSGenHandler} 归集到 {@code emotions} 列表（并在业务侧压缩到 0~0.6）。
     * 声明只描述「编辑器长什么样」（维度 key 与区间），<b>不参与</b>取值传递。</p>
     *
     * <p>情感统一走该向量路径（{@code emoTexts} 忽略）：强情感指令/长描述会干扰音色还原，
     * 向量强度已在业务侧压缩到 0~0.6，这里只做防御性夹逼。</p>
     */
    @Override
    public JSONObject buildApiJson(Text2AudioInput input) {

        // 构造 IndexTTS2 分段：按索引一一对应 音色 / 文本 / 情感向量 / 语速
        List<IndexTTS2Segment> segments = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            segments.add(IndexTTS2Segment.builder()
                    .timbre(input.timbreAt(i))
                    .prompt(input.promptAt(i))
                    .emotions(input.emotionAt(i))
                    .speed(input.speedAt(i))
                    .build());
        }
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;

        JSONObject workflow = IndexTTS2WorkflowBuilder.build(
                segments,
                IndexTTS2WorkflowBuilder.DEFAULT_START_NODE_ID,
                seed,
                input.getOptions()
        );
        // 模型槽注入：按当前选择覆盖 easy downloadIndexTTSAndLoadModel.model（无 modelSlots 时 no-op）
        applyModelSlots(workflow);
        return workflow;
    }


    /**
     * IndexTTS2 情感工作流 JSON 构建器。
     * <p>
     * 根据输入的音频分段列表，动态生成 ComfyUI workflow JSON，
     * 支持 N 个分段的独立音色、文本和情感向量，<b>每个分段独立输出音频文件，不在 workflow 内拼接</b>。
     * ComfyUI 端为每个分段生成一个 {@code SaveAudioMP3}，业务侧按
     * {@code voice_seg_<index>} 段序号前缀下载并按输入顺序回填。
     * </p>
     *
     * <h3>工作流结构</h3>
     * <pre>
     *   [模型加载器] (共享)
     *        │
     *   ┌────┴──────────┬──────────────┐
     *   │ 分段 0        │ 分段 1  ...  │ 分段 N-1
     *   │ LoadAudio     │ LoadAudio    │ LoadAudio
     *   │ EmotionVector │ EmotionVector│ EmotionVector
     *   │ TTSGenerate   │ TTSGenerate  │ TTSGenerate
     *   │ SaveAudioMP3  │ SaveAudioMP3 │ SaveAudioMP3   ← 各自独立保存
     *   └───────────────┴──────────────┴──────────────┘
     * </pre>
     *
     * @author helei
     */
    public static class IndexTTS2WorkflowBuilder {
        private static final int DEFAULT_SEED = 1;
        private static final String MODEL_LOADER_SCHEMA = """
            {
                "inputs": {
                    "model": "IndexTTS-2",
                    "download_from": "modelscope"
                },
                "class_type": "easy downloadIndexTTSAndLoadModel",
                "_meta": {
                    "title": "IndexTTS 模型加载器"
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

        private static final String EMOTION_VECTOR_SCHEMA = """
            {
                "inputs": {
                    "voice_name": "",
                    "use_random": false,
                    "Happy": 0,
                    "Angry": 0,
                    "Sad": 0,
                    "Fear": 0,
                    "Hate": 0,
                    "Low": 0,
                    "Surprise": 0,
                    "Neutral": 0
                },
                "class_type": "easy indexTTSEmotionVector",
                "_meta": {
                    "title": "音色情感向量"
                }
            }
            """;

        private static final String TTS_GENERATE_SCHEMA = """
            {
                "inputs": {
                    "text": "",
                    "unload_model": false,
                    "do_sample": true,
                    "temperature": 0.8,
                    "top_p": 0.9,
                    "top_k": 30,
                    "num_beams": 3,
                    "repetition_penalty": 3,
                    "length_penalty": 0,
                    "max_mel_tokens": 1815,
                    "max_tokens_per_sentence": 120,
                    "speech_speed": 1,
                    "seed": 3,
                    "indextts_model": ["", 0],
                    "emotions": ["", 0]
                },
                "class_type": "easy indexTTSGenerate",
                "_meta": {
                    "title": "IndexTTS 生成"
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

        /**
         * ComfyUI emotion vector 节点支持的 8 种情感维度
         */
        public static final List<String> EMOTION_KEYS = List.of(
                "Happy", "Angry", "Sad", "Fear", "Hate", "Low", "Surprise", "Neutral"
        );

        /** 情感单维上限：官方提示 >1.0 的情感强度会干扰克隆音色还原，统一压到 0.6 */
        private static final double MAX_EMOTION_VALUE = 0.6;
        /** 官方约束：8 维情感向量总和上限（Σ ≤ 1.5） */
        private static final double MAX_EMOTION_SUM = 1.5;

        /** 默认起始节点 ID，避免与模板中已有的节点 ID 冲突 */
        public static final int DEFAULT_START_NODE_ID = 1000;

        // ==================== 公开构建方法 ====================

        /**
         * 从分段列表构建工作流 JSON（每个分段独立输出音频文件，不在 workflow 内拼接）。
         *
         * @param segments    音频分段列表，长度 ≥ 1
         * @param startNodeId 起始节点 ID
         * @param seed        随机种子
         * @param extra       业务额外参数
         * @return ComfyUI workflow JSONObject
         */
        public static JSONObject build(
                List<IndexTTS2Segment> segments,
                int startNodeId,
                int seed,
                Map<String, Object> extra
        ) {
            if (segments == null || segments.isEmpty()) {
                throw new IllegalArgumentException("segments must not be empty");
            }

            JSONObject workflow = new JSONObject();
            int nodeId = startNodeId;

            // ---- 1. 模型加载器（所有分段共享） ----
            String modelNodeId = String.valueOf(nodeId++);
            workflow.set(modelNodeId, new JSONObject(MODEL_LOADER_SCHEMA));

            // ---- 2. 为每个分段生成 LoadAudio / EmotionVector / TTSGenerate / SaveAudioMP3 ----
            for (int segIdx = 0; segIdx < segments.size(); segIdx++) {
                IndexTTS2Segment segment = segments.get(segIdx);

                // 2a. LoadAudio
                String audioNodeId = String.valueOf(nodeId++);
                JSONObject audioNode = new JSONObject(LOAD_AUDIO_SCHEMA);
                audioNode.getJSONObject("inputs").set("audio", segment.getTimbre());
                workflow.set(audioNodeId, audioNode);

                // 2b. 情绪节点：统一走 8 维情绪向量（避免强情感文本干扰音色还原）。
                String emotionNodeId = String.valueOf(nodeId++);
                JSONObject emotionNode = new JSONObject(EMOTION_VECTOR_SCHEMA);
                JSONObject emotionInputs = emotionNode.getJSONObject("inputs");
                applyEmotions(emotionInputs, segment.getEmotions());
                emotionInputs.set("reference_audio", List.of(audioNodeId, 0));
                workflow.set(emotionNodeId, emotionNode);

                // 2c. TTSGenerate
                String ttsNodeId = String.valueOf(nodeId++);
                JSONObject ttsNode = mergeTtsNodeSchema(extra);
                JSONObject ttsInputs = ttsNode.getJSONObject("inputs");
                ttsInputs.set("text", segment.getPrompt());
                // 确定性 seed：同一句（文本+音色）始终得到同一音频，可复现、不再"忽好忽坏"；
                ttsInputs.set("seed", seed);
                ttsInputs.set("speech_speed", segment.getSpeed() != null ? segment.getSpeed() : 1.0);
                ttsInputs.set("indextts_model", List.of(modelNodeId, 0));
                ttsInputs.set("emotions", List.of(emotionNodeId, 0));
                workflow.set(ttsNodeId, ttsNode);

                // 2d. SaveAudioMP3（每分段独立保存，filename_prefix 编码段序号以便下载端排序）
                String saveNodeId = String.valueOf(nodeId++);
                JSONObject saveNode = new JSONObject(SAVE_AUDIO_SCHEMA);
                saveNode.getJSONObject("inputs")
                        .set("filename_prefix", "audio/voice_seg_" + segIdx);
                saveNode.getJSONObject("inputs").set("audio", List.of(ttsNodeId, 0));
                workflow.set(saveNodeId, saveNode);
            }

            return workflow;
        }

        /**
         * 使用默认起始节点 ID 构建。
         *
         * @see #DEFAULT_START_NODE_ID
         */
        public static JSONObject build(List<IndexTTS2Segment> segments) {
            return build(segments, DEFAULT_START_NODE_ID, 1, null);
        }

        /**
         * 便捷方法：通过三个平铺列表构建。
         * 三个列表长度必须一致，按索引一一对应。
         *
         * @param timbres  参考音频文件名列表
         * @param prompts  TTS 文本列表
         * @param emotions 情感向量列表，可为 {@code null}（全部使用默认值 0）
         * @param speeds   语速系数列表，可为 {@code null}（默认 1.0）
         */
        public static JSONObject build(
                List<String> timbres,
                List<String> prompts,
                List<Map<String, Double>> emotions,
                List<Double> speeds
        ) {
            return build(timbres, prompts, emotions, speeds, DEFAULT_START_NODE_ID, DEFAULT_SEED);
        }

        /**
         * 便捷方法：通过三个平铺列表 + 自定义起始节点 ID 构建。
         */
        public static JSONObject build(
                List<String> timbres,
                List<String> prompts,
                List<Map<String, Double>> emotions,
                List<Double> speeds,
                int startNodeId
        ) {
            return build(timbres, prompts, emotions, speeds, startNodeId, DEFAULT_SEED);
        }

        /**
         * 便捷方法：通过三个平铺列表 + 自定义起始节点 ID + randomSeed 构建。
         *
         * @param timbres    参考音频文件名列表
         * @param prompts    TTS 文本列表
         * @param emotions   情感向量列表，可为 {@code null}
         * @param speeds     语速系数列表，可为 {@code null}（默认 1.0）
         * @param startNodeId 起始节点 ID
         * @param seed 是否随机生成 seed
         */
        public static JSONObject build(
                List<String> timbres,
                List<String> prompts,
                List<Map<String, Double>> emotions,
                List<Double> speeds,
                int startNodeId,
                int seed
        ) {
            if (timbres == null || prompts == null) {
                throw new IllegalArgumentException("timbres and prompts must not be null");
            }
            if (timbres.size() != prompts.size()) {
                throw new IllegalArgumentException(
                        "timbres size (%d) must equal prompts size (%d)".formatted(timbres.size(), prompts.size()));
            }
            if (emotions != null && emotions.size() != timbres.size()) {
                throw new IllegalArgumentException(
                        "emotions size (%d) must equal timbres size (%d)".formatted(emotions.size(), timbres.size()));
            }

            List<IndexTTS2Segment> segments = new ArrayList<>();
            for (int i = 0; i < timbres.size(); i++) {
                segments.add(IndexTTS2Segment.builder()
                        .timbre(timbres.get(i))
                        .prompt(prompts.get(i))
                        .emotions(emotions != null ? emotions.get(i) : null)
                        .speed(speeds != null && i < speeds.size() ? speeds.get(i) : 1.0)
                        .build());
            }
            return build(segments, startNodeId, seed, null);
        }

        // ==================== 内部工具方法 ====================

        private static JSONObject mergeTtsNodeSchema(Map<String, Object> extra) {
            JSONObject entries = JSONUtil.parseObj(TTS_GENERATE_SCHEMA);
            if (CollUtil.isEmpty(extra)) {
                return entries;
            }
            entries.getJSONObject("inputs").putAll(extra);
            return entries;
        }

        /**
         * 将用户传入的情感 Map 写入 emotion vector 节点的 inputs。
         * 自动做 key 首字母大写归一化，未传入的情感维度保持默认值 0。
         * <p>防御性约束（官方限制）：单维夹逼到 {@link #MAX_EMOTION_VALUE}（>1.0 会干扰音色还原），
         * 且 8 维总和不超过 {@link #MAX_EMOTION_SUM}（超出按比例缩放），避免无效输入。</p>
         */
        private static void applyEmotions(JSONObject inputs, Map<String, Double> emotions) {
            if (emotions == null || emotions.isEmpty()) {
                return;
            }
            // 单维夹逼 + 统计总和
            Map<String, Double> scaled = new HashMap<>();
            double sum = 0.0;
            for (Map.Entry<String, Double> entry : emotions.entrySet()) {
                String key = normalizeEmotionKey(entry.getKey());
                if (key == null || entry.getValue() == null) {
                    continue;
                }
                double v = Math.clamp(entry.getValue(), 0.0, MAX_EMOTION_VALUE);
                scaled.put(key, v);
                sum += v;
            }
            // 官方约束：Σ(v) ≤ MAX_EMOTION_SUM，超出按比例缩放
            if (sum > MAX_EMOTION_SUM) {
                double factor = MAX_EMOTION_SUM / sum;
                scaled.replaceAll((k, v) -> v * factor);
            }
            for (Map.Entry<String, Double> entry : scaled.entrySet()) {
                inputs.set(entry.getKey(), entry.getValue());
            }
        }

        /**
         * 情感 key 归一化：首字母大写，其余小写。
         * 不在 {@link #EMOTION_KEYS} 中的 key 返回 {@code null}。
         */
        private static String normalizeEmotionKey(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.substring(0, 1).toUpperCase() + raw.substring(1).toLowerCase();
            return EMOTION_KEYS.contains(normalized) ? normalized : null;
        }

        /**
         * 基于文本内容 + 音色生成确定性 seed。
         * 相同文本与音色 → 相同 seed（配音可复现）；不同文本或不同角色 → 不同 seed（保留自然韵律差异）。
         * 用 floorMod 保证落在非负整数区间，避免 hashCode 负值直接作为 seed 时模型行为不一致。
         */
        private static int deterministicSeed(String prompt, String timbre) {
            int h = Objects.hash(
                    prompt == null ? "" : prompt,
                    timbre == null ? "" : timbre
            );
            return Math.floorMod(h, 1_000_000);
        }
    }

    /**
     * IndexTTS2 音频分段参数，一个分段 = 一个音色 + 一段台词 + 一组情感向量
     *
     * @author helei
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexTTS2Segment {

        /**
         * 参考音频文件名（已上传到 ComfyUI input 目录）
         */
        private String timbre;

        /**
         * TTS 要合成的文本 / 台词
         */
        private String prompt;

        /**
         * 情感向量，key 为情感名称 (Happy / Angry / Sad / Fear / Hate / Low / Surprise / Neutral)，
         * value 为强度值，缺省视为 0。
         * 情感统一走该向量路径（强度已在业务侧压缩到 0~0.6）。
         */
        private Map<String, Double> emotions;

        /**
         * 语速系数，1.0 为正常语速，>1 加速，<1 减速。
         * 对应 IndexTTS 节点的 speech_speed 参数。
         */
        @Builder.Default
        private Double speed = 1.0;
    }
}
