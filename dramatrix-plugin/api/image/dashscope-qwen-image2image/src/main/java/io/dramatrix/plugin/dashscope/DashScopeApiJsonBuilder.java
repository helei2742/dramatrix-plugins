package io.dramatrix.plugin.dashscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.dto.Image2ImageInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope Qwen 图生图的<b>请求体拼接</b>：把 {@link Image2ImageInput} 组装成
 * {@code {model, input:{messages:[{role, content:[{image},...,{text}]}]}, parameters:{...}}}。
 *
 * <p>从 {@link DashScopeImage2ImageAdapter#buildApiJson} 提取出来，职责单一：做<b>纯请求体映射</b>
 * —— 不发 HTTP、不持凭据、不读文件（鉴权 / 并发 / 超时 / 结果下载由宿主侧
 * {@code DashScopeMediaClient} 处理），编译期只依赖 {@code dramatrix-plugin-api}。</p>
 *
 * <h3>参考图用占位符传递</h3>
 * <p>{@link Image2ImageInput#getSceneImage()} 承载的是宿主侧给的「资源引用」（DirectDashMode 下
 * 是本地文件绝对路径）。本类没有 HTTP 能力、无法读文件转 Base64，故把参考图写成
 * {@code media-file://<路径>}，由宿主替换成 {@code data:<mime>;base64,...}。多图时按
 * 图1..图N 顺序（场景图 + 角色参考图）拼接进 content；latent 初始图与 LoRA 在本插件忽略。</p>
 */
final class DashScopeApiJsonBuilder {

    /** 媒体占位符前缀，须与宿主 {@code DashScopeMediaClient} 侧保持一致 */
    private static final String MEDIA_PLACEHOLDER = "media-file://";

    private DashScopeApiJsonBuilder() {
    }

    /**
     * 组装原生请求体。
     *
     * @param input    图生图入参
     * @param defaults adapter.json 的 {@code extra.defaults}（options 未给时的兜底值，可为空 Map）
     * @throws IllegalArgumentException 缺 prompt / 缺参考图 / 参考图数量超模型上限
     */
    static JSONObject build(Image2ImageInput input, Map<String, Object> defaults) {
        if (StrUtil.isBlank(input.getPrompt()) && StrUtil.isBlank(strVal(input.getOptions(), "prompt"))) {
            throw new IllegalArgumentException("DashScope 图生图需要 prompt（描述如何编辑底图）");
        }
        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        Map<String, Object> defs = defaults == null ? Map.of() : defaults;

        String model = str(opts, defs, "modelName", "qwen-image-3.0-pro");

        List<String> refs = checkedReferences(input, model);
        String prompt = StrUtil.isNotBlank(input.getPrompt()) ? input.getPrompt()
                : strVal(opts, "prompt");

        JSONObject body = new JSONObject();
        body.set("model", model);

        // qwen / wan2.7 / wan2.6 统一产出 DashScope 原生 input.messages：
        // content 为 [{image:U},{text:X}] 原生元素，字段顺序即图1..图N、末尾接文本（宿主替换 media-file 占位为 base64）
        JSONObject nativeInput = new JSONObject();
        JSONArray content = new JSONArray();
        for (String reference : refs) {
            content.add(new JSONObject().set("image", MEDIA_PLACEHOLDER + reference));
        }
        content.add(new JSONObject().set("text", prompt));
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", content));
        nativeInput.set("messages", messages);
        body.set("input", nativeInput);

        // 生成参数统一放入 parameters（宿主 toNativeBody 原样透传已有 input、并归并 parameters）
        JSONObject params = new JSONObject();
        String negative = StrUtil.isNotBlank(input.getNegativePrompt())
                ? input.getNegativePrompt() : str(opts, defs, "negativePrompt", null);
        if (StrUtil.isNotBlank(negative)) {
            params.set("negative_prompt", negative);
        }
        params.set("prompt_extend", bool(opts, defs, "promptExtend"));
        params.set("watermark", bool(opts, defs, "watermark"));
        String size = resolveSize(model, input.getWidth(), input.getHeight());
        if (size != null) {
            params.set("size", size);
        }
        int n = input.getN() != null ? input.getN() : intOrNull(opts, defs, "n", 1);
        params.set("n", n);
        Integer seed = resolveSeed(input, opts, defs);
        if (seed != null) {
            params.set("seed", seed);
        }
        body.set("parameters", params);
        return body;
    }

    /**
     * 有序参考图（图1..图N）：场景图打头，随后按 {@code characterIds} 顺序追角色参考图；
     * 未提供 characterIds 时按 characterImages 的插入顺序兜底。仅为保留「提示词里的图N」与数组下标一致的确定性。
     */
    private static List<String> orderedReferences(Image2ImageInput input) {
        List<String> refs = new ArrayList<>();
        String scene = input.getSceneImage();
        if (StrUtil.isNotBlank(scene)) {
            refs.add(scene);
        }
        Map<String, String> images = input.getCharacterImages();
        if (images != null && !images.isEmpty()) {
            List<String> ids = input.getCharacterIds();
            if (ids != null && !ids.isEmpty()) {
                for (String id : ids) {
                    String ref = images.get(id);
                    if (StrUtil.isNotBlank(ref)) {
                        refs.add(ref);
                    }
                }
            } else if (input.getCharacterImages() instanceof LinkedHashMap) {
                images.forEach((k, v) -> {
                    if (StrUtil.isNotBlank(v)) {
                        refs.add(v);
                    }
                });
            } else {
                // 非 LinkedHashMap 时无法保证顺序，按值排序求得确定性
                images.values().stream()
                        .filter(StrUtil::isNotBlank)
                        .sorted()
                        .forEach(refs::add);
            }
        }
        return refs;
    }

    /**
     * 按所选模型校验输入图数量（上限 <b>动态</b>、不静默截断），不合法直接报错。
     * <ul>
     *   <li>qwen-image / qwen-image-edit 全系：1～3 张</li>
     *   <li>wan2.7-image-pro、wan2.7-image：0～9 张（0 张即文生图模式，本类图生图仍要求至少 1 张参考图）</li>
     *   <li>wan2.6-image：图像编辑 1～4 张</li>
     *   <li>其余未知模型兜底按 3 张</li>
     * </ul>
     */
    private static List<String> checkedReferences(Image2ImageInput input, String model) {
        List<String> refs = orderedReferences(input);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException(
                    "DashScope 图生图需要至少一张参考图（sceneImage 或 characterImages 至少给一个，参见宿主 uploadImageOrVoice）");
        }
        int max = maxInputImages(model);
        if (refs.size() > max) {
            throw new IllegalArgumentException(
                    "模型 %s 最多支持 %d 张输入图，当前传入 %d 张（场景图 + 角色参考图）；请减少参考图或换用支持更多输入图的模型"
                            .formatted(model, max, refs.size()));
        }
        return refs;
    }

    /** 各模型输入图数量上限（官方规格，动态）。 */
    private static int maxInputImages(String model) {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("wan2.7-image")) {
            return 9;
        }
        if (m.startsWith("wan2.6-image")) {
            return 4;
        }
        // qwen-image / qwen-image-edit 全系、未知模型默认
        return 3;
    }

    // ════════════════════════════ 参数取值 ════════════════════════════

    /** 优先级：调用方 options > adapter.json defaults */
    private static Object pick(Map<String, Object> opts, Map<String, Object> defs, String key) {
        Object v = opts.get(key);
        return v != null ? v : defs.get(key);
    }

    private static String str(Map<String, Object> o, Map<String, Object> d, String key, String fallback) {
        Object v = pick(o, d, key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static String strVal(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** configSchema 用 SELECT 暴露布尔值，故统一按字符串解析。 */
    private static boolean bool(Map<String, Object> o, Map<String, Object> d, String key) {
        Object v = pick(o, d, key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static int intOrNull(Map<String, Object> o, Map<String, Object> d, String key, int fallback) {
        Object v = pick(o, d, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }

    /** 一级字段优先于 options —— 调用方直接 set 时以它为准；都未给时不写 seed（交给厂商随机）。 */
    private static Integer resolveSeed(Image2ImageInput input, Map<String, Object> o, Map<String, Object> d) {
        if (input.getSeed() != null) {
            return input.getSeed();
        }
        Object v = pick(o, d, "seed");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 无效 seed 忽略
            }
        }
        return null;
    }

    // ════════════════════════════ size 约束 ════════════════════════════

    /** 基于画布宽高，按所选模型做等比缩放，产出 "W*H"；无可达宽高或无需写 size 时返回 null。 */
    private static String resolveSize(String model, Integer w, Integer h) {
        SizeProfile p = profileFor(model);
        if (p.presets != null) { // 仅固定预设的模型：按画布宽高比就近选档
            if (w == null || h == null || w <= 0 || h <= 0) return p.def;
            return nearestPreset(p.presets, w, h);
        }
        if (w == null || h == null || w <= 0 || h <= 0) return p.def; // def 为 null 时交给模型自动
        double wd = w, hd = h;
        if (p.ratioMax > 0) {
            if (wd > p.ratioMax * hd) wd = p.ratioMax * hd;
            else if (hd > p.ratioMax * wd) hd = p.ratioMax * wd;
        }
        if (wd * hd > p.maxArea) {
            double s = Math.sqrt(p.maxArea / (wd * hd));
            wd *= s;
            hd *= s;
        }
        double ms = Math.max(wd, hd);
        if (ms > p.max) {
            double s = p.max / ms;
            wd *= s;
            hd *= s;
        }
        double mn = Math.min(wd, hd);
        if (p.min > 0 && mn < p.min) {
            double s = p.min / mn;
            wd *= s;
            hd *= s;
            if (wd * hd > p.maxArea) {
                double s2 = Math.sqrt(p.maxArea / (wd * hd));
                wd *= s2;
                hd *= s2;
            }
        }
        return toStep(wd) + "*" + toStep(hd);
    }

    private static int toStep(double v) {
        return Math.max(8, (int) Math.round(v / 8d) * 8);
    }

    /** 就近固定预设：按宽高比离差的 log 最小选择。 */
    private static String nearestPreset(List<String> presets, int w, int h) {
        double target = (double) w / h;
        String best = presets.getFirst();
        double bestDiff = Double.MAX_VALUE;
        for (String p : presets) {
            int idx = p.indexOf('*');
            int pw = Integer.parseInt(p.substring(0, idx));
            int ph = Integer.parseInt(p.substring(idx + 1));
            double diff = Math.abs(Math.log(target / ((double) pw / ph)));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = p;
            }
        }
        return best;
    }

    /** 依据 DashScope 生图各系模型的 size 约束选取配置。 */
    private static SizeProfile profileFor(String model) {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("qwen-image-max") || m.startsWith("qwen-image-plus")) {
            return new SizeProfile(0, 0, 0, 0, "1664*928",
                    List.of("1664*928", "1472*1104", "1328*1328", "1104*1472", "928*1664"));
        }
        if (m.startsWith("qwen-image-3")) return new SizeProfile(512, 2048, 2048L * 2048, 8, null, null);
        if (m.startsWith("qwen-image-2") || m.equals("qwen-image")) {
            return new SizeProfile(512, 2048, 2048L * 2048, 0, "2048*2048", null);
        }
        if (m.equals("wan2.7-image-pro")) return new SizeProfile(768, 4096, 4096L * 4096, 8, "2048*2048", null);
        if (m.equals("wan2.7-image")) return new SizeProfile(768, 2048, 2048L * 2048, 8, "2048*2048", null);
        if (m.equals("wan2.6-image")) return new SizeProfile(768, 1280, 1280L * 1280, 4, "1024*1024", null);
        if (m.equals("wan2.6-t2i") || m.equals("wan2.5-t2i-preview")) {
            return new SizeProfile(1280, 1440, 1440L * 1440, 4, "1280*1280", null);
        }
        if (m.startsWith("wan")) return new SizeProfile(512, 1440, 1440L * 1440, 0, "1024*1024", null);
        return new SizeProfile(512, 2048, 2048L * 2048, 0, "2048*2048", null);
    }

    /** 一类模型的 size 约束：min=max=area=ratioMax 均取 0 表示不适用（走 presets）。 */
    private record SizeProfile(int min, int max, long maxArea, int ratioMax, String def, List<String> presets) {
    }
}
