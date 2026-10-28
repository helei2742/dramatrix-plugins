package io.dramatrix.plugin.seedvr2video;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.VideoEditInput;
import io.dramatrix.plugin.interfaces.VideoEditPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * SeedVR2 3B INT8 视频修复/超清适配器。
 * 对应 workflow: utility_seedvr2_3b_int8_upscale_video（ComfyUI 原生 SeedVR2 模板）。
 *
 * <p>链路：LoadVideo →（可选 Video Slice）→ GetVideoComponents（拆 images / audio / fps）
 * → lanczos 预放大（scale by multiplier）→ SeedVR2Preprocess → VAEEncodeTiled
 * → KSampler（单步，steps=1/cfg=1）→ VAEDecodeTiled → SeedVR2PostProcessing（色彩校正）
 * → CreateVideo（合回原音频与原 fps）→ SaveVideo。</p>
 *
 * <p>无提示词参与，因此实现 {@link VideoEditPlugin}（不带 buildPrompt）；
 * 全部语义参数经绑定（workflow.binding.json / 内置默认）落到节点字段，
 * 输入 DTO 字段均为包装类型，{@code null} 表示保留工作流自带默认值。</p>
 *
 * <h3>两条不可改的默认行为（改前务必想清楚）</h3>
 * <ol>
 *   <li><b>音轨与帧率由工作流天然保留</b>：{@code CreateVideo} 的 {@code audio} / {@code fps}
 *       接自 {@code GetVideoComponents} 的输出。因此「输出视频保留原声与原帧率」不是本适配器
 *       的功劳，也不需要任何参数；若要「丢弃音轨 / 改帧率」，那是<b>新增</b>能力而不是改默认。</li>
 *   <li><b>分块采样（Split Latent）默认关闭</b>：{@code PrimitiveBoolean} 初始为 {@code false}，
 *       即走不分块的直通路径。它是显存不足时的降级开关（{@code temporalChunk}），不要改默认值。</li>
 * </ol>
 */
@Slf4j
public class SeedVR2UpscaleVideoAdapter extends AbstractComfyUIPlugin implements VideoEditPlugin {

    /** 内置默认绑定：对应默认 workflow.json 的节点（按 classType 定位）。 */
    private static final String DEFAULT_BINDING = """
    {
      "bindings": [
        { "parameter": "video", "target": { "match": "classType", "classType": "LoadVideo", "field": "file" } },
        { "parameter": "scale_multiplier", "target": { "match": "classType", "classType": "ResizeImageMaskNode", "field": "resize_type.multiplier" }, "transform": "float" },
        { "parameter": "seed", "target": { "match": "classType", "classType": "KSampler", "field": "seed" }, "transform": "int" },
        { "parameter": "sampler_name", "target": { "match": "classType", "classType": "KSampler", "field": "sampler_name" } },
        { "parameter": "scheduler", "target": { "match": "classType", "classType": "KSampler", "field": "scheduler" } },
        { "parameter": "denoise", "target": { "match": "classType", "classType": "KSampler", "field": "denoise" }, "transform": "float" },
        { "parameter": "color_correction_method", "target": { "match": "classType", "classType": "SeedVR2PostProcessing", "field": "color_correction_method" } },
        { "parameter": "slice_start", "target": { "match": "nodeId", "nodeId": "66:79", "field": "start_time" }, "transform": "float" },
        { "parameter": "slice_duration", "target": { "match": "nodeId", "nodeId": "66:79", "field": "duration" }, "transform": "float" }
      ],
      "modelSlots": [
        {
          "group": "seedvr2-diffusion",
          "title": "SeedVR2 3B 扩散模型",
          "default": "seedvr2_3b_int8_convrot.safetensors",
          "targets": [ { "match": "classType", "classType": "UNETLoader", "field": "unet_name" } ]
        },
        {
          "group": "seedvr2-vae",
          "title": "SeedVR2 VAE",
          "default": "seedvr2_ema_vae_fp16.safetensors",
          "targets": [ { "match": "classType", "classType": "VAELoader", "field": "vae_name" } ]
        }
      ]
    }
    """;

    /** 切片开关节点类（ComfySwitchNode）；工作流里有两个，靠 on_false 是否指向 LoadVideo 区分。 */
    private static final String SWITCH_CLASS = "ComfySwitchNode";
    /** 分块采样开关节点类（只有一个）。 */
    private static final String PRIMITIVE_BOOL_CLASS = "PrimitiveBoolean";
    /** 主输入节点类。 */
    private static final String LOAD_VIDEO_CLASS = "LoadVideo";

    /** 色彩校正方式白名单（COMBO 输入，ComfyUI 对列表外的值会拒收整条 prompt）。 */
    private static final Set<String> COLOR_CORRECTION_METHODS = Set.of("lab", "wavelet", "adain", "none");

    /** 放大倍率范围（ResizeImageMaskNode 的 multiplier 有范围校验）。 */
    private static final double MIN_MULTIPLIER = 0.01;
    private static final double MAX_MULTIPLIER = 4.0;

    public SeedVR2UpscaleVideoAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(VideoEditInput in) {
        ensureDefaultBinding(DEFAULT_BINDING);
        JSONObject api = new JSONObject(workflowJson);

        VideoEditInput input = in == null ? new VideoEditInput() : in;

        // 0. 主输入必填 —— 缺了要响亮失败，并提示「该走哪条链路」
        if (StrUtil.isBlank(input.getVideo())) {
            throw new IllegalArgumentException(
                    "视频编辑需要视频输入（video 为空）；若要做参考生视频请走 image2video_ref 链路");
        }
        applyBinding(api, "video", input.getVideo());

        warnUnsupported(input);

        // 1. 可选参数覆盖（null = 保留工作流默认）
        if (input.getScaleMultiplier() != null) {
            applyBinding(api, "scale_multiplier", clampMultiplier(input.getScaleMultiplier()));
        }
        if (StrUtil.isNotBlank(input.getSamplerName())) {
            applyBinding(api, "sampler_name", input.getSamplerName());
        }
        if (StrUtil.isNotBlank(input.getScheduler())) {
            applyBinding(api, "scheduler", input.getScheduler());
        }
        if (input.getDenoise() != null) {
            applyBinding(api, "denoise", clampDenoise(input.getDenoise()));
        }
        if (StrUtil.isNotBlank(input.getColorCorrectionMethod())) {
            applyBinding(api, "color_correction_method", pickColorCorrection(input.getColorCorrectionMethod()));
        }

        // 2. Seed：input 优先（已由服务端兜底）；null/0 表示随机
        Integer s = input.getSeed();
        int seed = (s == null || s == 0) ? RandomUtil.randomInt(0, Integer.MAX_VALUE) : s;
        applyBinding(api, "seed", Math.abs((long) seed));

        // 3. 时长裁剪：参数透传 + **开关联动**
        //    ★ 只写 Video Slice 的参数而不改 ComfySwitchNode 的 switch ⇒ 用户填了「截取 N 秒」
        //      却拿到整段视频，且**无任何报错**（on_false 分支走 LoadVideo 原样输出）。
        applySlice(api, input);

        // 4. 分块采样开关（显存不足时的降级路径）
        if (input.getTemporalChunk() != null) {
            applyPrimitiveBoolean(api, input.getTemporalChunk());
        }

        // 5. 模型槽（binding.modelSlots；未配置时为 no-op）
        applyModelSlots(api);

        return api;
    }

    // ════════════════════════ 切片：参数 + 开关 ════════════════════════

    /**
     * 写入时长裁剪参数，并把切片开关（{@code ComfySwitchNode}）切到对应分支。
     *
     * <p>判据：{@code sliceDuration > 0} ⇒ 开关置 {@code true}（走 Video Slice）；
     * 否则置 {@code false}（走 LoadVideo 原视频）。两个参数**始终写入**（便于工作流手工调试），
     * 但开关决定它们是否生效。</p>
     */
    private void applySlice(JSONObject api, VideoEditInput input) {
        Double start = input.getSliceStart();
        Double duration = input.getSliceDuration();

        boolean sliceEnabled = duration != null && duration > 0;

        if (start != null) {
            applyBinding(api, "slice_start", Math.max(0.0, start));
        }
        if (duration != null) {
            applyBinding(api, "slice_duration", Math.max(0.0, duration));
        }

        String switchId = findSliceSwitchNodeId(api);
        if (switchId == null) {
            // 工作流里没有切片分支（可能是精简版工作流）：只告警，不阻断生成
            log.warn("[SeedVR2-Video] 未找到切片开关节点（on_false 指向 {} 的 {}），时长裁剪参数不会生效",
                    LOAD_VIDEO_CLASS, SWITCH_CLASS);
            return;
        }
        JSONObject node = api.getJSONObject(switchId);
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) {
            inputs = new JSONObject();
            node.set("inputs", inputs);
        }
        inputs.set("switch", sliceEnabled);
        log.info("[SeedVR2-Video] 切片开关={}（start={}, duration={}）", sliceEnabled, start, duration);
    }

    /**
     * 定位「视频切片」那个 {@code ComfySwitchNode}：工作流里有多个同名节点，
     * 判据是它的 {@code on_false} 指向 {@code LoadVideo}（即「不切片时用原视频」）。
     *
     * @return 节点号；找不到返回 {@code null}
     */
    private static String findSliceSwitchNodeId(JSONObject api) {
        String loadVideoId = null;
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node != null && LOAD_VIDEO_CLASS.equals(node.getStr("class_type"))) {
                loadVideoId = id;
                break;
            }
        }
        if (loadVideoId == null) {
            return null;
        }
        for (String id : api.keySet()) {
            JSONObject node = api.getJSONObject(id);
            if (node == null || !SWITCH_CLASS.equals(node.getStr("class_type"))) {
                continue;
            }
            JSONObject inputs = node.getJSONObject("inputs");
            if (inputs == null) {
                continue;
            }
            Object onFalse = inputs.get("on_false");
            if (onFalse instanceof List<?> conn && !conn.isEmpty()
                    && loadVideoId.equals(String.valueOf(conn.getFirst()))) {
                return id;
            }
        }
        return null;
    }

    /** 写入分块采样开关（{@code PrimitiveBoolean.value}）。 */
    private void applyPrimitiveBoolean(JSONObject api, boolean value) {
        String nodeId;
        try {
            nodeId = resolveNodeIdByClassType(api, PRIMITIVE_BOOL_CLASS);
        } catch (IllegalArgumentException e) {
            log.warn("[SeedVR2-Video] 未找到 {} 节点，分块采样参数不生效: {}", PRIMITIVE_BOOL_CLASS, e.getMessage());
            return;
        }
        JSONObject node = api.getJSONObject(nodeId);
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) {
            inputs = new JSONObject();
            node.set("inputs", inputs);
        }
        inputs.set("value", value);
    }

    // ════════════════════════ 参数收敛（越界 / 未知值就地回落 + warn） ════════════════════════

    /**
     * 放大倍率夹紧到 [0.01, 4]。
     * <p>ComfyUI 对超出 min/max 的数值输入是<b>拒收整条 prompt</b>（不是忽略）⇒ 批量处理时整批失败。</p>
     */
    private static double clampMultiplier(double v) {
        if (v < MIN_MULTIPLIER || v > MAX_MULTIPLIER) {
            double clamped = Math.min(MAX_MULTIPLIER, Math.max(MIN_MULTIPLIER, v));
            log.warn("[SeedVR2-Video] scaleMultiplier={} 超出 [{}, {}]，就地夹紧为 {}",
                    v, MIN_MULTIPLIER, MAX_MULTIPLIER, clamped);
            return clamped;
        }
        return v;
    }

    /** 去噪强度夹紧到 [0, 1]。 */
    private static double clampDenoise(double v) {
        if (v < 0 || v > 1) {
            double clamped = Math.min(1.0, Math.max(0.0, v));
            log.warn("[SeedVR2-Video] denoise={} 超出 [0, 1]，就地夹紧为 {}", v, clamped);
            return clamped;
        }
        return v;
    }

    /**
     * 色彩校正方式回落：未知值 ⇒ {@code none} + warn。
     * <p>该输入是 COMBO 类，列表外的值会让 ComfyUI 拒收整条 prompt。</p>
     */
    private static String pickColorCorrection(String v) {
        String s = v.trim().toLowerCase();
        if (COLOR_CORRECTION_METHODS.contains(s)) {
            return s;
        }
        log.warn("[SeedVR2-Video] 未知 colorCorrectionMethod={}，回落 none", v);
        return "none";
    }

    /**
     * 对「本插件不消费但调用方传了」的字段发一次告警。
     * <p>静默忽略会让用户以为设置生效了 —— 尤其 {@code fps}（本插件继承原视频帧率）
     * 与 {@code refImages}/{@code refAudios}（本插件无参考输入口）。</p>
     */
    private static void warnUnsupported(VideoEditInput input) {
        if (input.getFps() != null) {
            log.warn("[SeedVR2-Video] 忽略 fps={}：本插件输出帧率继承原视频（CreateVideo 的 fps 接自 GetVideoComponents）",
                    input.getFps());
        }
        if (StrUtil.isNotBlank(input.getPrompt())) {
            log.warn("[SeedVR2-Video] 忽略 prompt：本插件为纯参数化编辑，无提示词参与");
        }
        if (CollUtil.isNotEmpty(input.getRefImages()) || CollUtil.isNotEmpty(input.getRefImageAssetIds())) {
            log.warn("[SeedVR2-Video] 忽略参考图：本插件没有参考图输入口");
        }
        if (CollUtil.isNotEmpty(input.getRefAudios()) || CollUtil.isNotEmpty(input.getRefAudioAssetIds())) {
            log.warn("[SeedVR2-Video] 忽略参考音频：本插件没有参考音频输入口");
        }
    }
}
