package io.dramatrix.plugin.ace15;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.constants.MusicCategory;
import io.dramatrix.plugin.dto.MusicGenRequest;
import io.dramatrix.plugin.dto.PluginContext;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.MusicGenPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AceStep 1.5 音乐生成适配器。
 * <p>
 * <b>可调参数</b>（含默认值）全部声明在 {@code adapter.json} 的 {@code extra.paramSchema} 里：
 * 前端据此渲染「额外参数」表单，本类据此取缺省值，{@link MusicGenRequest} 里用户显式提交的值覆盖它。
 * <b>模型文件名</b>由 {@code workflow.binding.json} 的 {@code modelSlots} 注入（{@code applyModelSlots}），
 * 不在 {@code adapter.json} 里重复声明。
 */
public class AceStep15MusicAdapter extends AbstractComfyUIPlugin
        implements MusicGenPlugin {

    public AceStep15MusicAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(MusicGenRequest request, PluginContext ctx) {
        // 插件参数：前端按 paramSchema 提交，经 MusicGenRequest.extra 原样透传
        //（未提交的键回落 adapter.json 的 paramSchema.defaultValue）
        Map<String, Object> params = request.getExtra() == null ? Map.of() : request.getExtra();
        int maxDuration = getMetaExtra("maxDuration") != null
                ? ((Number) getMetaExtra("maxDuration")).intValue() : 120;

        // ═══ 采样参数：请求侧（一等字段 / 插件参数）优先 → paramSchema.defaultValue → 硬编码保底 ═══
        int    steps    = request.getSteps() != null && request.getSteps() > 0
                ? request.getSteps() : resolveInt("steps", 8);
        double cfgScale = request.getCfgScale() != null && request.getCfgScale() > 0
                ? request.getCfgScale() : resolveDouble("cfg", 2.0);
        double temp     = resolveDouble("temperature", toDouble(params.get("temperature")), 0.85);
        double topP     = resolveDouble("topP", toDouble(params.get("topP")), 0.9);
        double topK     = resolveDouble("topK", toDouble(params.get("topK")), 0.0);
        double minP     = resolveDouble("minP", toDouble(params.get("minP")), 0.0);
        String sampler  = resolveStr("sampler", asStr(params.get("sampler")), "euler");
        String sched    = resolveStr("scheduler", asStr(params.get("scheduler")), "simple");
        double denoise  = resolveDouble("denoise", toDouble(params.get("denoise")), 1.0);

        // ═══ seed: 0 或 null → 随机 ═══
        int seed = request.getSeed() != null && request.getSeed() != 0
                ? request.getSeed() : RandomUtil.randomInt(0, Integer.MAX_VALUE);

        // ═══ duration 校验 ═══
        double duration = request.getDuration() != null ? request.getDuration() : 20.0;
        if (duration > maxDuration) {
            duration = maxDuration;
        }

        // ═══ 构造 ACE tags（LLM 优化字段拼接）与 lyrics（用户歌词 / [Instrumental] + metadata） ═══
        String tags   = buildTags(request);
        String lyrics = buildLyrics(request);

        // ═══ 动态生成 ComfyUI 节点图 ═══
        JSONObject api = AceStep15WorkflowBuilder.build(
                tags,
                lyrics,
                duration,
                steps, cfgScale, temp, topP, topK, minP,
                sampler, sched, denoise, seed
        );
        // 模型槽注入：模型文件名一律由 workflow.binding.json 的 modelSlots 提供（无 binding 时 no-op，
        // 此时用 builder 模板里的字面兜底值 —— 两者同值）
        applyModelSlots(api);
        return api;
    }

    /**
     * 按类别读取对应 LLM 提示词模板（sfx/bgm/ambient-prompt.md）作为 <b>system prompt</b>；
     * 原始输入由 {@code MusicPromptOptimizer} 作为 user 消息独立下发。模板缺失时返回 null（跳过该类别优化）。
     */
    @Override
    public String buildMusicPrompt(MusicCategory category, MusicGenRequest request, PluginContext context) {
        String file = switch (category) {
            case BGM -> "bgm-prompt.md";
            case SFX -> "sfx-prompt.md";
            case AMBIENT -> "ambient-prompt.md";
        };
        return loadPromptResource(file);
    }

    // 取值链见基类 AbstractComfyUIPlugin#resolveInt / #resolveDouble / #resolveStr
    //（请求侧 → paramSchema.defaultValue → 硬编码保底）

    /**
     * 构造 ACE tags（风格标签串）：LLM 优化字段（genre/mood/instrumentation/vocalStyle）优先拼接，
     * 原始 prompt 兜底，英文逗号分隔。
     */
    private String buildTags(MusicGenRequest req) {
        String base = StrUtil.blankToDefault(req.getGenre(), req.getPrompt());
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(base)) parts.add(base);
        if (StrUtil.isNotBlank(req.getMood())) parts.add(req.getMood());
        if (StrUtil.isNotBlank(req.getInstrumentation())) parts.add(req.getInstrumentation());
        if (StrUtil.isNotBlank(req.getVocalStyle())) parts.add(req.getVocalStyle());
        return String.join(", ", parts);
    }

    /**
     * 构造 ACE lyrics：用户歌词（带段落结构）或 BGM 纯音乐的 {@code [Instrumental]} 标记，
     * 再拼接 bpm/keyscale/timesignature/seed 元数据。
     * <p>注意：SFX / Ambient 不注入 {@code [Instrumental]}——该标签在 ACE 中表示"器乐音乐"，
     * 会把环境声/音效按纯音乐渲染；环境音应完全由 tags 里的自然声场描述驱动。</p>
     */
    private String buildLyrics(MusicGenRequest req) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(req.getLyrics())) {
            sb.append(req.getLyrics().trim()).append("\n\n");
        } else if (req.getMusicType() == MusicCategory.BGM) {
            // BGM 纯音乐：用 [Instrumental] 标记器乐音乐，附乐器名帮助生成
            sb.append("[Instrumental]");
            if (StrUtil.isNotBlank(req.getInstrumentation())) {
                sb.append(" ").append(req.getInstrumentation());
            }
            sb.append("\n\n");
        }
        // SFX / Ambient：无歌词则不注入任何结构标记，由 tags 驱动声场
        sb.append(buildLyricsMeta(req));
        return sb.toString();
    }

    /**
     * 将结构化参数编码为 ACE 的 lyrics metadata 格式。
     * ACE 将 bpm/keyscale/timesignature/seed 作为 metadata 注入到 lyrics 文本。
     */
    private String buildLyricsMeta(MusicGenRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.getBpm() != null)
            sb.append("bpm:      ").append(req.getBpm()).append("\n");
        if (req.getKeyScale() != null)
            sb.append("keyscale: ").append(req.getKeyScale()).append("\n");
        if (req.getLanguage() != null)
            sb.append("language: ").append(req.getLanguage()).append("\n");
        if (req.getTimeSignature() != null)
            sb.append("timesignature: ").append(req.getTimeSignature()).append("\n");
        sb.append("seed:     ").append(req.getSeed()).append("（fixed）\n");
        return sb.toString();
    }

    // ═══════════════════════════════════════════
    // WorkflowBuilder — 动态构建 ComfyUI 节点图
    // ═══════════════════════════════════════════

    /**
     * AceStep 1.5 音乐生成工作流动态构建器。
     *
     * <pre>
     *   [1000] UNETLoader          —— 加载扩散模型
     *     │
     *   [1001] ModelSamplingAuraFlow —— 采样算法
     *     │
     *   [1002] DualCLIPLoader      —— 加载双 CLIP 编码器
     *     │
     *   [1003] TextEncodeAceStepAudio1.5 —— 文本编码（prompt + metadata）
     *     │
     *   [1004] ConditioningZeroOut —— 负向条件置零
     *     │
     *   [1005] EmptyAceStep1.5LatentAudio —— 空潜空间
     *     │
     *   [1006] KSampler            —— 扩散采样
     *     │
     *   [1007] VAELoader           —— 加载 VAE
     *     │
     *   [1008] VAEDecodeAudio      —— 潜空间解码为音频
     *     │
     *   [1009] SaveAudioMP3        —— 保存 MP3
     * </pre>
     */
    public static class AceStep15WorkflowBuilder {

        public static final int DEFAULT_START_NODE_ID = 1000;

        // ─── 节点模板定义 ───
        // ★ 模型文件名（unet / clip1 / clip2 / vae）由 `workflow.binding.json` 的 modelSlots 提供，
        //   由 applyModelSlots 覆盖；模板里的字面值只是「binding 缺失」时的兜底，与 binding.default 同值。

        private static final String UNET_LOADER = """
                {
                    "inputs": {
                        "unet_name": "acestep_v1.5_turbo.safetensors",
                        "weight_dtype": "default"
                    },
                    "class_type": "UNETLoader",
                    "_meta": { "title": "UNet加载器" }
                }
                """;

        private static final String MODEL_SAMPLING = """
                {
                    "inputs": {
                        "shift": 3,
                        "model": ["", 0]
                    },
                    "class_type": "ModelSamplingAuraFlow",
                    "_meta": { "title": "采样算法（AuraFlow）" }
                }
                """;

        private static final String DUAL_CLIP_LOADER = """
                {
                    "inputs": {
                        "clip_name1": "qwen_0.6b_ace15.safetensors",
                        "clip_name2": "qwen_1.7b_ace15.safetensors",
                        "type": "ace",
                        "device": "default"
                    },
                    "class_type": "DualCLIPLoader",
                    "_meta": { "title": "双CLIP加载器" }
                }
                """;

        private static final String TEXT_ENCODE = """
                {
                    "inputs": {
                        "tags": "",
                        "lyrics": "",
                        "seed": 0,
                        "bpm": 120,
                        "duration": 20,
                        "timesignature": "4",
                        "language": "zh",
                        "keyscale": "C major",
                        "generate_audio_codes": true,
                        "cfg_scale": 2,
                        "temperature": 0.85,
                        "top_p": 0.9,
                        "top_k": 0,
                        "min_p": 0,
                        "clip": ["", 0]
                    },
                    "class_type": "TextEncodeAceStepAudio1.5",
                    "_meta": { "title": "TextEncodeAceStepAudio1.5" }
                }
                """;

        private static final String CONDITIONING_ZERO_OUT = """
                {
                    "inputs": {
                        "conditioning": ["", 0]
                    },
                    "class_type": "ConditioningZeroOut",
                    "_meta": { "title": "条件零化" }
                }
                """;

        private static final String EMPTY_LATENT = """
                {
                    "inputs": {
                        "seconds": 20,
                        "batch_size": 1
                    },
                    "class_type": "EmptyAceStep1.5LatentAudio",
                    "_meta": { "title": "Empty Ace Step 1.5 Latent Audio" }
                }
                """;

        private static final String K_SAMPLER = """
                {
                    "inputs": {
                        "seed": 0,
                        "steps": 8,
                        "cfg": 2,
                        "sampler_name": "euler",
                        "scheduler": "simple",
                        "denoise": 1,
                        "model": ["", 0],
                        "positive": ["", 0],
                        "negative": ["", 0],
                        "latent_image": ["", 0]
                    },
                    "class_type": "KSampler",
                    "_meta": { "title": "K采样器" }
                }
                """;

        private static final String VAE_LOADER = """
                {
                    "inputs": {
                        "vae_name": "ace_1.5_vae.safetensors"
                    },
                    "class_type": "VAELoader",
                    "_meta": { "title": "加载VAE" }
                }
                """;

        private static final String VAE_DECODE = """
                {
                    "inputs": {
                        "samples": ["", 0],
                        "vae": ["", 0]
                    },
                    "class_type": "VAEDecodeAudio",
                    "_meta": { "title": "VAE解码（音频）" }
                }
                """;

        private static final String SAVE_AUDIO = """
                {
                    "inputs": {
                        "filename_prefix": "audio/ComfyUI",
                        "quality": "V0",
                        "audio": ["", 0]
                    },
                    "class_type": "SaveAudioMP3",
                    "_meta": { "title": "保存音频 (MP3)" }
                }
                """;

        /**
         * 构建完整工作流 JSON。
         *
         * <p>★ 模型文件名（unet / clip1 / clip2 / vae）不在这里 —— 由
         * {@code workflow.binding.json} 的 {@code modelSlots} 提供，{@code applyModelSlots} 注入。
         * 输出路径 / 编码质量（filenamePrefix / quality）是工作流侧固定配置，直接写在模板里。</p>
         *
         * @param prompt    音乐风格/情绪标签文本
         * @param lyrics    ACE lyrics metadata（bpm/keyscale/timesignature 编码）
         * @param duration  时长（秒）
         * @param steps     扩散步数
         * @param cfgScale  CFG 引导强度
         * @param temp      采样温度
         * @param topP      Nucleus 采样
         * @param topK      Top-K 采样
         * @param minP      Min-P 采样
         * @param sampler   采样器名称
         * @param scheduler 调度器名称
         * @param denoise   降噪强度
         * @param seed      随机种子
         */
        public static JSONObject build(
                String prompt, String lyrics, double duration,
                int steps, double cfgScale, double temp,
                double topP, double topK, double minP,
                String sampler, String scheduler, double denoise,
                int seed
        ) {
            JSONObject workflow = new JSONObject();
            int nid = DEFAULT_START_NODE_ID;

            // [1000] UNETLoader（unet_name / weight_dtype 由模型槽注入）
            String unetNodeId = String.valueOf(nid++);
            JSONObject unet = loadSchema(UNET_LOADER);
            workflow.set(unetNodeId, unet);

            // [1001] ModelSamplingAuraFlow
            String msNodeId = String.valueOf(nid++);
            JSONObject ms = loadSchema(MODEL_SAMPLING);
            ms.getJSONObject("inputs").set("model", nodeRef(unetNodeId));
            workflow.set(msNodeId, ms);

            // [1002] DualCLIPLoader（clip_name1/2 由模型槽注入）
            String clipNodeId = String.valueOf(nid++);
            JSONObject clip = loadSchema(DUAL_CLIP_LOADER);
            workflow.set(clipNodeId, clip);

            // [1003] TextEncodeAceStepAudio1.5
            String encodeNodeId = String.valueOf(nid++);
            JSONObject encode = loadSchema(TEXT_ENCODE);
            JSONObject encInputs = encode.getJSONObject("inputs");
            encInputs.set("tags", prompt != null ? prompt : "");
            encInputs.set("lyrics", lyrics != null ? lyrics : "");
            encInputs.set("seed", seed);
            encInputs.set("duration", duration);
            encInputs.set("cfg_scale", cfgScale);
            encInputs.set("temperature", temp);
            encInputs.set("top_p", topP);
            encInputs.set("top_k", topK);
            encInputs.set("min_p", minP);
            encInputs.set("clip", nodeRef(clipNodeId));
            workflow.set(encodeNodeId, encode);

            // [1004] ConditioningZeroOut（负向条件）
            String negNodeId = String.valueOf(nid++);
            JSONObject neg = loadSchema(CONDITIONING_ZERO_OUT);
            neg.getJSONObject("inputs").set("conditioning", nodeRef(encodeNodeId));
            workflow.set(negNodeId, neg);

            // [1005] EmptyAceStep1.5LatentAudio
            String latentNodeId = String.valueOf(nid++);
            JSONObject latent = loadSchema(EMPTY_LATENT);
            latent.getJSONObject("inputs").set("seconds", duration);
            workflow.set(latentNodeId, latent);

            // [1006] KSampler
            String samplerNodeId = String.valueOf(nid++);
            JSONObject kSampler = loadSchema(K_SAMPLER);
            JSONObject ksInputs = kSampler.getJSONObject("inputs");
            ksInputs.set("seed", seed);
            ksInputs.set("steps", steps);
            ksInputs.set("cfg", cfgScale);
            ksInputs.set("sampler_name", sampler != null ? sampler : "euler");
            ksInputs.set("scheduler", scheduler != null ? scheduler : "simple");
            ksInputs.set("denoise", denoise);
            ksInputs.set("model", nodeRef(msNodeId));
            ksInputs.set("positive", nodeRef(encodeNodeId));
            ksInputs.set("negative", nodeRef(negNodeId));
            ksInputs.set("latent_image", nodeRef(latentNodeId));
            workflow.set(samplerNodeId, kSampler);

            // [1007] VAELoader（vae_name 由模型槽注入）
            String vaeLoadNodeId = String.valueOf(nid++);
            JSONObject vaeLoad = loadSchema(VAE_LOADER);
            workflow.set(vaeLoadNodeId, vaeLoad);

            // [1008] VAEDecodeAudio
            String decodeNodeId = String.valueOf(nid++);
            JSONObject decode = loadSchema(VAE_DECODE);
            decode.getJSONObject("inputs").set("samples", nodeRef(samplerNodeId));
            decode.getJSONObject("inputs").set("vae", nodeRef(vaeLoadNodeId));
            workflow.set(decodeNodeId, decode);

            // [1009] SaveAudioMP3（filename_prefix / quality = 模板里的固定值）
            String saveNodeId = String.valueOf(nid++);
            JSONObject save = loadSchema(SAVE_AUDIO);
            save.getJSONObject("inputs").set("audio", nodeRef(decodeNodeId));
            workflow.set(saveNodeId, save);

            return workflow;
        }

        // ─── 辅助方法 ───

        /**
         * 从 schema JSON 字符串加载为 JSONObject
         */
        private static JSONObject loadSchema(String json) {
            return JSONUtil.parseObj(json);
        }

        /**
         * 构造 ComfyUI 节点引用 [nodeId, outputIndex]
         */
        private static java.util.List<Object> nodeRef(String nodeId) {
            java.util.List<Object> ref = new java.util.ArrayList<>(2);
            ref.add(nodeId);
            ref.add(0);
            return ref;
        }
    }
}
