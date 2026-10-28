package io.dramatrix.plugin.minimaxmusic3;

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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMax Music 3 音乐生成适配器。
 * <p>
 * <b>可调参数</b>（含默认值）全部声明在 {@code adapter.json} 的 {@code extra.paramSchema} 里：
 * 前端据此渲染「额外参数」表单，本类据此取缺省值，{@link MusicGenRequest} 里用户显式提交的值覆盖它。
 * <b>模型文件名</b>由 {@code workflow.binding.json} 的 {@code modelSlots} 注入（{@code applyModelSlots}），
 * 不在 {@code adapter.json} 里重复声明。
 * <p>
 * 与 AceStep 1.5 不同，MiniMax Music 3 的文本指令是<b>结构化 Caption</b>（官方
 * music-caption-rewriter skill 规范）：三个固定标题 —— {@code ### Global Metadata}、
 * {@code ### Vocal Details}、{@code ### Arrangement}；Lyrics 走独立字段且只把
 * {@code [verse]}/{@code [chorus]} 等括号标签当作可执行的曲式指令。本适配器据此
 * 将请求中的描述 + bpm/keyscale 等结构化参数拼装成三段式 Caption。
 */
public class MiniMaxMusic3Adapter extends AbstractComfyUIPlugin
        implements MusicGenPlugin {

    public MiniMaxMusic3Adapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(MusicGenRequest request, PluginContext ctx) {
        // 插件参数：前端按 paramSchema 提交，经 MusicGenRequest.extra 原样透传
        //（未提交的键回落 adapter.json 的 paramSchema.defaultValue）
        Map<String, Object> params = request.getExtra() == null ? Map.of() : request.getExtra();
        int maxDuration = getMetaExtra("maxDuration") != null
                ? ((Number) getMetaExtra("maxDuration")).intValue() : 240;

        // ═══ 采样参数：请求侧（一等字段 / 插件参数）优先 → paramSchema.defaultValue → 硬编码保底 ═══
        int    steps    = request.getSteps() != null && request.getSteps() > 0
                ? request.getSteps() : resolveInt("steps", 30);
        double cfgScale = request.getCfgScale() != null && request.getCfgScale() > 0
                ? request.getCfgScale() : resolveDouble("cfg", 1.7);
        double denoise  = resolveDouble("denoise", toDouble(params.get("denoise")), 1.0);
        String sampler  = resolveStr("sampler", asStr(params.get("sampler")), "euler");
        String sched    = resolveStr("scheduler", asStr(params.get("scheduler")), "simple");
        int    topK     = resolveInt("topK", toInt(params.get("topK")), 50);

        // ═══ seed: 0 或 null → 随机 ═══
        int seed = request.getSeed() != null && request.getSeed() != 0
                ? request.getSeed() : RandomUtil.randomInt(0, Integer.MAX_VALUE);

        // ═══ duration 校验（模型最长约 300s） ═══
        double duration = request.getDuration() != null ? request.getDuration() : 30.0;
        if (duration < 5.0) duration = 5.0;
        if (duration > maxDuration) duration = maxDuration;

        // ═══ 结构化 Caption + 歌词（纯音乐 BGM 无歌词时自动生成段落结构，防止长音频后段风格漂移） ═══
        String lyrics  = buildLyrics(request);
        String caption = buildCaption(request, lyrics);

        // ═══ 动态生成 ComfyUI 节点图 ═══
        JSONObject api = MiniMaxMusic3WorkflowBuilder.build(
                caption, lyrics, duration,
                seed, steps, cfgScale, topK,
                sampler, sched, denoise
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

    // ═══════════════════════════════════════════
    // Caption 构建（官方 music-caption-rewriter 三段式）
    // ═══════════════════════════════════════════

    private static final String H_GLOBAL = "### Global Metadata";
    private static final String H_VOCAL  = "### Vocal Details";
    private static final String H_ARR    = "### Arrangement";

    /** 歌词中可执行的段落标签 */
    private static final Pattern TAG_PATTERN = Pattern.compile("\\[([A-Za-z][A-Za-z -]*)]");

    /**
     * 将请求组装为 MiniMax Music 3 结构化 Caption。
     * <p>若 prompt 已包含三个官方标题（说明已由 LLM/skill 生成），直接透传；
     * 否则围绕 prompt 拼装三段式 caption，并把 bpm / keyscale / 拍号注入 Global Metadata。
     * {@code lyrics} 为最终生效的歌词（可能已由 {@link #buildLyrics} 自动生成段落结构）。</p>
     */
    private String buildCaption(MusicGenRequest req, String lyrics) {
        String prompt = req.getPrompt() == null ? "" : req.getPrompt().trim();
        if (prompt.contains(H_GLOBAL) || prompt.contains(H_VOCAL) || prompt.contains(H_ARR)) {
            return prompt;
        }

        StringBuilder sb = new StringBuilder();
        appendGlobalMetadata(sb, req, prompt);
        appendVocalDetails(sb, req);
        appendArrangement(sb, req, lyrics);
        return sb.toString();
    }

    private void appendGlobalMetadata(StringBuilder sb, MusicGenRequest req, String prompt) {
        // LLM 优化输出（MusicPromptOptimizer 写入）优先，原始 prompt 兜底
        boolean ambient = req.getMusicType() == MusicCategory.AMBIENT;
        String genre = StrUtil.blankToDefault(req.getGenre(), prompt);
        String mood = req.getMood();
        String instrumentation = req.getInstrumentation();

        sb.append(H_GLOBAL).append("\n");
        if (ambient) {
            // MiniMax 是音乐模型：显式压制歌曲结构，强调非旋律 soundscape，让它贴近氛围声场而非写成歌
            sb.append("Genre and subgenres: ambient / atmospheric soundscape");
            if (StrUtil.isNotBlank(genre)) {
                sb.append(" — ").append(genre);
            }
            sb.append("; not a song, no lead melody, no harmonic song structure, no fixed beat or drums.\n");
        } else {
            sb.append("Genre and subgenres: ")
                    .append(genre.isEmpty() ? "as implied by the requested mood and the lyrics" : genre)
                    .append(".\n");
        }
        if (StrUtil.isNotBlank(mood)) {
            sb.append("Mood: ").append(mood).append(".\n");
        }
        if (req.getBpm() != null) {
            sb.append("Tempo: ").append(req.getBpm()).append(" BPM.\n");
        }
        if (StrUtil.isNotBlank(req.getKeyScale())) {
            sb.append("Key and scale: ").append(req.getKeyScale()).append(".\n");
        }
        if (StrUtil.isNotBlank(req.getTimeSignature())) {
            sb.append("Meter: ").append(req.getTimeSignature()).append(".\n");
        }
        if (StrUtil.isNotBlank(instrumentation)) {
            sb.append("Instrumentation: ").append(instrumentation).append(".\n");
        }
        if (ambient) {
            sb.append("Evolution: a slowly breathing soundscape — layers enter, swell and recede "
                    + "independently over time, no dramatic build or drop, no musical climax.\n");
            sb.append("Sonics & production: spacious, organic and natural, with gentle reverb and "
                    + "field-recording-like textures; layers should feel continuous and real, "
                    + "not like a musical production.\n\n");
        } else {
            sb.append("Emotional progression: open with the mood described above, build naturally "
                    + "through the middle sections, and resolve with a satisfying close.\n");
            sb.append("Sonics & production: consistent with the described genre, mood and instrumentation; "
                    + "keep the mix clean, balanced and tasteful, supporting the melody without overpowering it.\n\n");
        }
    }

    private void appendVocalDetails(StringBuilder sb, MusicGenRequest req) {
        boolean instrumental = isInstrumental(req);
        boolean hasLyrics = StrUtil.isNotBlank(req.getLyrics());
        String vocalStyle = req.getVocalStyle();   // LLM 优化输出（仅 BGM 且有歌词时）

        sb.append(H_VOCAL).append("\n");
        if (instrumental) {
            sb.append("Instrumental piece: no vocals.");
            if (StrUtil.isNotBlank(req.getInstrumentation())) {
                sb.append(" The lead melodic role is carried by the texture implied by the instrumentation: ")
                        .append(req.getInstrumentation());
            } else {
                sb.append(" The lead melodic role is carried by the primary instrument implied by the genre.");
            }
            sb.append("\n\n");
            return;
        }
        if (hasLyrics) {
            sb.append("Lead: a single lead vocalist performing the provided lyrics");
            if (StrUtil.isNotBlank(vocalStyle)) {
                sb.append(", ").append(vocalStyle);
            }
            sb.append(".\n");
            sb.append("Harmony/backing: add subtle backing or harmony vocals only where they support "
                    + "the arrangement, never overpowering the lead.\n");
            sb.append("Vocal FX: restrained; light reverb and gentle saturation to match the mood, "
                    + "no heavy processing.\n\n");
        } else {
            sb.append("Vocals requested but no lyrics provided: compose a wordless vocal melody "
                    + "(humming or vowel sounds)");
            if (StrUtil.isNotBlank(vocalStyle)) {
                sb.append(", ").append(vocalStyle);
            }
            sb.append(".\n\n");
        }
    }

    private void appendArrangement(StringBuilder sb, MusicGenRequest req, String lyrics) {
        boolean instrumental = isInstrumental(req);
        sb.append(H_ARR).append("\n");
        if (req.getMusicType() == MusicCategory.AMBIENT) {
            // Ambient：描述声场层次随时间的呼吸演化，而非 verse/chorus 式歌曲段落
            sb.append("Evolving soundscape, not a song: each sonic layer (foreground, midground, background) "
                    + "enters, swells and recedes on its own slow cycle; no verse or chorus, no musical climax; "
                    + "the whole texture breathes in and out until it fades to silence.\n");
            return;
        }
        List<String> tags = extractSectionTags(lyrics);
        if (tags.isEmpty()) {
            sb.append("Build the piece as a coherent emotional arc: a gentle intro that establishes "
                    + "the mood, a main body where layers enter and develop gradually, a subtle lift "
                    + "toward the climax, and a calm outro that resolves the piece. Keep transitions "
                    + "smooth and natural.\n");
            return;
        }
        for (String tag : tags) {
            String guide = sectionGuide(tag, instrumental);
            if (guide != null) {
                sb.append(tag).append(": ").append(guide).append("\n");
            }
        }
    }

    /** 按顺序提取歌词中的段落标签（小写归一、连字符转空格） */
    private static List<String> extractSectionTags(String lyrics) {
        List<String> tags = new ArrayList<>();
        if (lyrics == null || lyrics.isBlank()) return tags;
        Matcher m = TAG_PATTERN.matcher(lyrics);
        while (m.find()) {
            tags.add(normalizeTag(m.group(1)));
        }
        return tags;
    }

    private static String normalizeTag(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
    }

    /** 段落标签 → 曲式引导；instrumental 时使用器乐化措辞（不出现人声语义）；未知标签返回 null */
    private static String sectionGuide(String tag, boolean instrumental) {
        String verseGuide = instrumental
                ? "intimate and lighter, keep the focus on the lead instrument"
                : "intimate and lighter, keep the focus on the vocal / lead line";
        String chorusGuide = instrumental
                ? "fullest energy; the main theme blooms with the full arrangement"
                : "fullest energy; the hook lands with the main melody, backing vocals open up";
        return switch (tag) {
            case "intro" -> "sparse and atmospheric, establish the key and mood with a short motif";
            case "verse" -> verseGuide;
            case "pre-chorus" -> "add gentle tension and lift toward the hook";
            case "chorus" -> chorusGuide;
            case "final chorus", "final-chorus" -> "reprise the main theme with added warmth and lift";
            case "bridge" -> "thin the texture for contrast, a moment of reflection";
            case "interlude" -> "instrumental breathing room between sections";
            case "instrumental" -> "let the instruments carry the melody, no vocals";
            case "solo" -> "feature the lead instrument with an expressive solo";
            case "hook", "refrain" -> "short repeated melodic hook that ties the song together";
            case "breakdown" -> "drop the layers, leave a sparse core before rebuilding";
            case "build" -> "layer instruments and dynamics to build energy";
            case "outro" -> "fade gradually to a calm, resolved ending";
            default -> null;
        };
    }

    // ═══════════════════════════════════════════
    // 纯音乐 BGM 段落结构自动生成
    // ═══════════════════════════════════════════

    /**
     * 生成最终传给 MiniMax 的歌词字段。
     * <p>纯音乐 BGM 且无歌词时，自动生成带段落标签的曲式结构（[intro]/[verse]/[chorus]/[outro]，
     * 括号内为演奏提示，无实际歌词内容 → 模型不会演唱）。段落标签是模型唯一的曲式锚点，
     * 缺失时长较长音频后段会失去结构约束、出现风格漂移；这是官方/社区已验证的稳定性要点。</p>
     */
    private String buildLyrics(MusicGenRequest req) {
        if (StrUtil.isNotBlank(req.getLyrics())) {
            return req.getLyrics();
        }
        if (req.getMusicType() == MusicCategory.BGM && isInstrumental(req)) {
            return buildInstrumentalSections(req.getDuration() != null ? req.getDuration() : 30.0);
        }
        return "";
    }

    /** 按目标时长生成纯音乐 BGM 的段落标签结构（时长越长段落越多） */
    private static String buildInstrumentalSections(double duration) {
        StringBuilder sb = new StringBuilder();
        sb.append("[intro]\n");
        sb.append("(establish the main theme softly)\n\n");
        if (duration >= 45) {
            sb.append("[verse]\n");
            sb.append("(main theme, intimate and quiet)\n\n");
            sb.append("[chorus]\n");
            sb.append("(main theme blooms, fuller arrangement)\n\n");
            sb.append("[verse]\n");
            sb.append("(return to quiet)\n\n");
            sb.append("[chorus]\n");
            sb.append("(fullest and warmest)\n\n");
        } else {
            sb.append("[instrumental]\n");
            sb.append("(develop the main theme with layers)\n\n");
        }
        sb.append("[outro]\n");
        sb.append("(fade to a calm resolution)");
        return sb.toString();
    }

    /**
     * 是否纯音乐：显式纯音乐提示（prompt/genre/mood/instrumentation）→ 是；
     * 有歌词或人声风格（LLM 优化输出）或描述点名要人声 → 否；否则保守默认纯音乐。
     */
    private boolean isInstrumental(MusicGenRequest req) {
        if (hasInstrumentalHint(req.getPrompt()) || hasInstrumentalHint(req.getGenre())
                || hasInstrumentalHint(req.getMood()) || hasInstrumentalHint(req.getInstrumentation())) {
            return true;
        }
        if (StrUtil.isNotBlank(req.getLyrics()) || StrUtil.isNotBlank(req.getVocalStyle())
                || hasVocalHint(req.getPrompt())) {
            return false;
        }
        return true;
    }

    /** 文本中是否出现“纯音乐/无歌词”类提示 */
    private static boolean hasInstrumentalHint(String s) {
        if (s == null) return false;
        String p = s.toLowerCase(Locale.ROOT);
        return p.contains("instrumental") || p.contains("no vocals")
                || p.contains("纯音乐") || p.contains("无歌词") || p.contains("无人声");
    }

    /** 文本中是否明确点名要人声 */
    private static boolean hasVocalHint(String s) {
        if (s == null) return false;
        String p = s.toLowerCase(Locale.ROOT);
        return p.contains("vocal") || p.contains("voice") || p.contains("sing")
                || p.contains("人声") || p.contains("演唱") || p.contains("歌唱")
                || p.contains("女声") || p.contains("男声");
    }

    // 取值链见基类 AbstractComfyUIPlugin#resolveInt / #resolveDouble / #resolveStr
    //（请求侧 → paramSchema.defaultValue → 硬编码保底）

    // ═══════════════════════════════════════════
    // WorkflowBuilder — 动态构建 ComfyUI 节点图
    // ═══════════════════════════════════════════

    /**
     * MiniMax Music 3 音乐生成工作流动态构建器。
     *
     * <pre>
     *   [1000] UNETLoader               —— 加载扩散模型
     *     │
     *   [1001] CLIPLoader (type=minimax) —— 加载文本编码器
     *     │
     *   [1002] MiniMaxMusic3TextEncode  —— caption + lyrics + max_duration 文本编码
     *     │
     *   [1003] ConditioningZeroOut      —— 负向条件置零
     *     │
     *   [1004] EmptyMiniMaxMusic3LatentAudio —— 空潜空间（seconds）
     *     │
     *   [1005] KSampler                 —— 扩散采样
     *     │
     *   [1006] VAELoader                —— 加载 VAE
     *     │
     *   [1007] VAEDecodeAudio           —— 潜空间解码为音频
     *     │
     *   [1008] SaveAudioAdvanced        —— 保存 MP3
     * </pre>
     */
    public static class MiniMaxMusic3WorkflowBuilder {

        public static final int DEFAULT_START_NODE_ID = 1000;

        // ─── 节点模板定义 ───
        // ★ 模型文件名（unet / clip / vae）由 `workflow.binding.json` 的 modelSlots 提供，
        //   由 applyModelSlots 覆盖；模板里的字面值只是「binding 缺失」时的兜底，与 binding.default 同值。

        private static final String UNET_LOADER = """
                {
                    "inputs": {
                        "unet_name": "minimax_music3_dit_fp16.safetensors",
                        "weight_dtype": "default"
                    },
                    "class_type": "UNETLoader",
                    "_meta": { "title": "UNet加载器" }
                }
                """;

        private static final String CLIP_LOADER = """
                {
                    "inputs": {
                        "clip_name": "minimax_music3_text_encoder_pruned_int8_convrot.safetensors",
                        "type": "minimax",
                        "device": "default"
                    },
                    "class_type": "CLIPLoader",
                    "_meta": { "title": "CLIP加载器" }
                }
                """;

        private static final String TEXT_ENCODE = """
                {
                    "inputs": {
                        "clip": ["", 0],
                        "caption": "",
                        "lyrics": "",
                        "seed": 0,
                        "max_duration": 30.0,
                        "cfg_scale": 1.7,
                        "top_k": 50
                    },
                    "class_type": "MiniMaxMusic3TextEncode",
                    "_meta": { "title": "MiniMax Music 3 文本编码" }
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
                        "seconds": 30.0,
                        "batch_size": 1
                    },
                    "class_type": "EmptyMiniMaxMusic3LatentAudio",
                    "_meta": { "title": "Empty MiniMax Music 3 Latent Audio" }
                }
                """;

        private static final String K_SAMPLER = """
                {
                    "inputs": {
                        "seed": 0,
                        "steps": 30,
                        "cfg": 1.7,
                        "sampler_name": "euler",
                        "scheduler": "simple",
                        "denoise": 1.0,
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
                        "vae_name": "minimax_music3_dav.safetensors"
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
                        "audio": ["", 0],
                        "filename_prefix": "audio/audio_minimax_music3",
                        "format": "mp3",
                        "format.quality": "V0"
                    },
                    "class_type": "SaveAudioAdvanced",
                    "_meta": { "title": "保存音频 (MP3)" }
                }
                """;

        /**
         * 构建完整工作流 JSON。
         *
         * <p>★ 模型文件名（unet / clip / vae）不在这里 —— 由 {@code workflow.binding.json} 的
         * {@code modelSlots} 提供，{@code applyModelSlots} 注入。输出路径 / 格式（filenamePrefix /
         * format / quality）是工作流侧固定配置，直接写在模板里。</p>
         *
         * @param caption   MiniMax 结构化 Caption（三段式）
         * @param lyrics    带段落标签的歌词（可为空）
         * @param duration  目标时长（秒），同时写入 max_duration 与 latent seconds
         * @param seed      随机种子
         * @param steps     扩散步数
         * @param cfgScale  CFG 引导强度
         * @param topK      Top-K 采样
         * @param sampler   采样器名称
         * @param scheduler 调度器名称
         * @param denoise   降噪强度
         */
        public static JSONObject build(
                String caption, String lyrics, double duration,
                int seed, int steps, double cfgScale, int topK,
                String sampler, String scheduler, double denoise
        ) {
            JSONObject workflow = new JSONObject();
            int nid = DEFAULT_START_NODE_ID;

            // [1000] UNETLoader（unet_name / weight_dtype 由模型槽注入）
            String unetNodeId = String.valueOf(nid++);
            JSONObject unet = loadSchema(UNET_LOADER);
            workflow.set(unetNodeId, unet);

            // [1001] CLIPLoader（clip_name 由模型槽注入）
            String clipNodeId = String.valueOf(nid++);
            JSONObject clip = loadSchema(CLIP_LOADER);
            workflow.set(clipNodeId, clip);

            // [1002] MiniMaxMusic3TextEncode
            String encodeNodeId = String.valueOf(nid++);
            JSONObject encode = loadSchema(TEXT_ENCODE);
            JSONObject encInputs = encode.getJSONObject("inputs");
            encInputs.set("clip", nodeRef(clipNodeId));
            encInputs.set("caption", caption != null ? caption : "");
            encInputs.set("lyrics", lyrics != null ? lyrics : "");
            encInputs.set("seed", seed);
            encInputs.set("max_duration", duration);
            encInputs.set("cfg_scale", cfgScale);
            encInputs.set("top_k", topK);
            workflow.set(encodeNodeId, encode);

            // [1003] ConditioningZeroOut（负向条件）
            String negNodeId = String.valueOf(nid++);
            JSONObject neg = loadSchema(CONDITIONING_ZERO_OUT);
            neg.getJSONObject("inputs").set("conditioning", nodeRef(encodeNodeId));
            workflow.set(negNodeId, neg);

            // [1004] EmptyMiniMaxMusic3LatentAudio
            String latentNodeId = String.valueOf(nid++);
            JSONObject latent = loadSchema(EMPTY_LATENT);
            latent.getJSONObject("inputs").set("seconds", duration);
            workflow.set(latentNodeId, latent);

            // [1005] KSampler
            String samplerNodeId = String.valueOf(nid++);
            JSONObject kSampler = loadSchema(K_SAMPLER);
            JSONObject ksInputs = kSampler.getJSONObject("inputs");
            ksInputs.set("seed", seed);
            ksInputs.set("steps", steps);
            ksInputs.set("cfg", cfgScale);
            ksInputs.set("sampler_name", sampler != null ? sampler : "euler");
            ksInputs.set("scheduler", scheduler != null ? scheduler : "simple");
            ksInputs.set("denoise", denoise);
            ksInputs.set("model", nodeRef(unetNodeId));
            ksInputs.set("positive", nodeRef(encodeNodeId));
            ksInputs.set("negative", nodeRef(negNodeId));
            ksInputs.set("latent_image", nodeRef(latentNodeId));
            workflow.set(samplerNodeId, kSampler);

            // [1006] VAELoader（vae_name 由模型槽注入）
            String vaeLoadNodeId = String.valueOf(nid++);
            JSONObject vaeLoad = loadSchema(VAE_LOADER);
            workflow.set(vaeLoadNodeId, vaeLoad);

            // [1007] VAEDecodeAudio
            String decodeNodeId = String.valueOf(nid++);
            JSONObject decode = loadSchema(VAE_DECODE);
            decode.getJSONObject("inputs").set("samples", nodeRef(samplerNodeId));
            decode.getJSONObject("inputs").set("vae", nodeRef(vaeLoadNodeId));
            workflow.set(decodeNodeId, decode);

            // [1008] SaveAudioAdvanced（filename_prefix / format / format.quality = 模板里的固定值）
            String saveNodeId = String.valueOf(nid++);
            JSONObject save = loadSchema(SAVE_AUDIO);
            save.getJSONObject("inputs").set("audio", nodeRef(decodeNodeId));
            workflow.set(saveNodeId, save);

            return workflow;
        }

        // ─── 辅助方法 ───

        private static JSONObject loadSchema(String json) {
            return JSONUtil.parseObj(json);
        }

        private static List<Object> nodeRef(String nodeId) {
            List<Object> ref = new ArrayList<>(2);
            ref.add(nodeId);
            ref.add(0);
            return ref;
        }

    }
}
