package io.dramatrix.plugin.dashscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * DashScope Qwen-Image 文生图适配器（{@code kind=api}，{@code runtime=api}）。
 *
 * <h3>职责边界</h3>
 * <p><b>只做请求体映射</b>：不发 HTTP、不持凭据、不读文件。本类由插件 JAR 经独立
 * {@code URLClassLoader} 实例化，编译期只依赖 {@code dramatrix-plugin-api}，拿不到宿主的
 * Spring 容器。鉴权、并发、超时与结果下载全部由宿主侧的
 * {@code DashScopeMediaClient} 处理。</p>
 *
 * <h3>产出的形状：OpenAI 兼容 messages</h3>
 * <p>统一产出 {@code {model, messages:[{role:"user", content:[{type:"text", text}]}], negative_prompt?, prompt_extend, watermark, size, n?, seed?}}。
 * 这与图片理解插件同构，宿主 {@code DashScopeMediaClient.toNativeBody()} 会机械转换为
 * {@code {model, input:{messages}, parameters:{...}}}（qwen-image 的<code>/services/aigc/multimodal-generation/generation</code>
 * 原生通道即此形状），因此文生图可复用现有 DashScope 通道、<b>协议差异不下沉到插件</b>。</p>
 *
 * <h3>无 LoRA</h3>
 * <p>虽路由到 {@code text2image_with_lora}（与 ComfyUI 文生图共用业务键 {@code TEXT2IMAGE}），
 * 但 API 直连厂商无 LoRA 概念，本适配器忽略一切 LoRA 注入。</p>
 *
 * @author helei
 */
@Slf4j
public class DashScopeText2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    public DashScopeText2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        if (StrUtil.isBlank(input.getPrompt())) {
            throw new IllegalArgumentException("DashScope 文生图需要 prompt");
        }
        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        Map<String, Object> defs = defaults();

        String model = str(opts, defs, "modelName", "qwen-image-2.0-pro");

        JSONObject body = new JSONObject();
        body.set("model", model);

        // 单轮单条 user 消息，content 只装一个 text 元素（qwen-image 原生只认一个 text，多余报错）
        JSONArray content = new JSONArray();
        content.add(new JSONObject().set("type", "text").set("text", input.getPrompt()));
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", content));
        body.set("messages", messages);

        // model/messages 之外的字段会被宿主 toNativeBody 整体落入 parameters
        String negative = StrUtil.isNotBlank(input.getNegativePrompt())
                ? input.getNegativePrompt() : str(opts, defs, "negativePrompt", null);
        if (StrUtil.isNotBlank(negative)) {
            body.set("negative_prompt", negative);
        }
        body.set("prompt_extend", bool(opts, defs, "promptExtend"));
        body.set("watermark", bool(opts, defs, "watermark"));
        String size = resolveSize(model, input.getWidth(), input.getHeight());
        if (size != null) {
            body.set("size", size);
        }
        int n = input.getN() != null ? input.getN() : intOrNull(opts, defs, "n", 1);
        body.set("n", n);
        Integer seed = resolveSeed(input, opts, defs);
        if (seed != null) {
            body.set("seed", seed);
        }
        return body;
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

    /** 按所选模型做等比缩放，产出 "W*H"。无可达宽高或无需写 size 时返回 null。 */
    private static String resolveSize(String model, Integer w, Integer h) {
        SizeProfile p = profileFor(model);
        if (p.presets != null) { // 仅固定预设的模型：按画布宽高比就近选档
            if (w == null || h == null || w <= 0 || h <= 0) return p.def;
            return nearestPreset(p.presets, w, h);
        }
        if (w == null || h == null || w <= 0 || h <= 0) return p.def; // def 为 null 时交给模型自动，不写 size
        double wd = w, hd = h;
        // 1) 宽高比约束：过长边按比例缩短（保证比例落在 [1/ratioMax, ratioMax]）
        if (p.ratioMax > 0) {
            if (wd > p.ratioMax * hd) wd = p.ratioMax * hd;
            else if (hd > p.ratioMax * wd) hd = p.ratioMax * wd;
        }
        // 2) 总像素上限
        if (wd * hd > p.maxArea) {
            double s = Math.sqrt(p.maxArea / (wd * hd));
            wd *= s;
            hd *= s;
        }
        // 3) 单边上限
        double ms = Math.max(wd, hd);
        if (ms > p.max) {
            double s = p.max / ms;
            wd *= s;
            hd *= s;
        }
        // 4) 单边下限：过小才抬升，抬升后若超总面积再收敛一次
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

    /** 就近固定预设：按宽高比离差的 log 最小选择，避免大画幅被无谓裁切。 */
    private static String nearestPreset(List<String> presets, int w, int h) {
        double target = (double) w / h;
        String best = presets.get(0);
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

    /** 一级字段优先于 options —— 调用方直接 set 时以它为准；都未给时不写 seed（交给厂商随机）。 */
    private static Integer resolveSeed(Text2ImageInput input, Map<String, Object> o, Map<String, Object> d) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaults() {
        Map<String, Object> extra = getMetadata().getExtra();
        if (extra == null) {
            return Map.of();
        }
        Object d = extra.get("defaults");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}