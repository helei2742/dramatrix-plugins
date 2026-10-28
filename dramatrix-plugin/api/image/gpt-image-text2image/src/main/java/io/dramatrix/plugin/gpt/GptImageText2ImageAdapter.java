package io.dramatrix.plugin.gpt;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.Text2ImageInput;
import io.dramatrix.plugin.interfaces.Text2ImagePlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 官方 Images API 文生图适配器（{@code kind=api}，{@code runtime=api}，{@code vendor=gpt}）。
 *
 * <h3>职责边界</h3>
 * <p><b>只做请求体映射</b>：不发 HTTP、不持凭据、不读文件。本类由插件 JAR 经独立
 * {@code URLClassLoader} 实例化，编译期只依赖 {@code dramatrix-plugin-api}，拿不到宿主的
 * Spring 容器。鉴权、超时、Base64 落盘与产物交付全部由宿主侧的
 * {@code GptMediaClient} 处理。</p>
 *
 * <h3>产出的形状：Images API 的扁平体（<b>不是</b> messages）</h3>
 * <pre>
 * {model, prompt, size?, n, quality?, output_format?, background?}
 * </pre>
 * @author helei
 */
@Slf4j
public class GptImageText2ImageAdapter extends AbstractComfyUIPlugin implements Text2ImagePlugin {

    /** 未声明时的兜底模型（adapter.json 的 defaults 通常已给出，此处只兜"配置被删空"） */
    private static final String DEFAULT_MODEL = "gpt-image-2";

    private static final String AUTO = "auto";

    /** 未配置 {@code maxSize} 时的默认上限：2560x1440（官方"非实验区间"的上界，3,686,400 px）。 */
    private static final String DEFAULT_MAX_SIZE = "2560x1440";

    /**
     * {@code maxSize} 里表示「不额外限制」的写法（只剩厂商四条硬约束）。
     * <p>写成集合而非布尔开关：运维手写配置时 {@code off / none / 0 / -} 都会出现，
     * 与其让它落进"解析失败 → 用默认值"，不如明确按"不限"处理。</p>
     */
    private static final List<String> UNLIMITED = List.of("off", "none", "unlimited", "0", "0x0", "-");

    /**
     * 厂商硬约束（gpt-image-2 / 2.5 共用一份，官方口径一致）：
     * {@code step=16}（两边都必须是 16 的整数倍）、单边 ≤ 3840、总像素 [655,360, 8,294,400]、
     * 长短边比 ≤ 3:1。
     * <p>本插件可配置的 {@code maxSize} 只能是它的<b>子集</b>（{@link SizeRules#capped} 保证）。</p>
     */
    private static final SizeRules VENDOR_RULES = new SizeRules(16, 3840, 3840, 3840, 655_360L, 8_294_400L, 3);

    /** 官方「常用尺寸」，<b>七个档位均已核对满足 {@link #VENDOR_RULES}</b>，用作修正失败时的安全落点。 */
    private static final List<String> COMMON_SIZES = List.of(
            "1024x1024", "1536x1024", "1024x1536", "2048x2048", "2048x1152", "3840x2160", "2160x3840");

    public GptImageText2ImageAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Text2ImageInput input) {
        if (input == null || StrUtil.isBlank(input.getPrompt())) {
            throw new IllegalArgumentException("GPT 文生图需要 prompt");
        }
        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        Map<String, Object> defs = defaults();

        String model = str(opts, defs, "modelName", DEFAULT_MODEL);
        Family family = Family.of(model);

        JSONObject body = new JSONObject();
        body.set("model", model);
        // 扁平 prompt：Images API 的形状（不是 messages）—— 宿主侧据此走 /images/generations
        body.set("prompt", input.getPrompt());

        if (family == Family.UNKNOWN) {
            log.warn("模型 [{}] 不在本插件支持的 gpt-image-2 / 2.5 之列，已按最保守形态只下发 model/prompt"
                    + "（不发 size/n/quality 等参数，交给厂商默认值）；若这是自建网关的别名，"
                    + "请确认它接受 Images API 形状的请求体", model);
            warnIgnoredInputs(input, model);
            return body;
        }

        // 画布推导路径用的规则 = 厂商硬约束 + 配置的 maxSize 上限（软约束）
        int[] cap = resolveCap(opts, defs);
        SizeRules derived = VENDOR_RULES.capped(cap[0], cap[1]);
        String size = resolveSize(family, explicitSize(input, opts, defs),
                input.getWidth(), input.getHeight(), model, derived);
        if (size != null) {
            body.set("size", size);
        }
        body.set("n", resolveN(family, input, opts, defs));

        String quality = qualityOf(family, opts, model);
        if (quality != null) {
            body.set("quality", quality);
        }
        body.set("output_format", pickFrom(opts, defs, "outputFormat", family.outputFormats, "png", model));
        // background 与 output_format 有交叉约束：transparent 只在 png/webp 下成立
        body.set("background", resolveBackground(family, body.getStr("output_format"), opts, defs, model));

        warnIgnoredInputs(input, model);
        return body;
    }

    // ════════════════════════════ 模型家族 ════════════════════════════

    /**
     * 生图模型的<b>参数能力档案</b>：同一份代码服务两代模型，靠它决定「哪些字段能发、合法取值是什么」。
     *
     * <p>取值逐条对齐 OpenAI Images API 文档；<b>不要把「这个参数别的模型有」当成「这个模型也有」</b>——
     * 多发的字段不会被忽略，而是 {@code 400 Unrecognized request argument}。
     * 两代共用同一份 {@link #VENDOR_RULES}，仅 {@code quality} 档位不同。</p>
     */
    private enum Family {
        /**
         * {@code gpt-image-2}：任意分辨率；quality 为 auto/low/medium/high。
         * 不支持 {@code style}（dall-e-3 独有）与 {@code response_format}（生图恒回 Base64）。
         */
        GPT_IMAGE_2(
                List.of("auto", "low", "medium", "high"), "auto",
                List.of("png", "jpeg", "webp"), List.of("auto", "opaque", "transparent"),
                10),
        /**
         * {@code gpt-image-2.5-*}（flare / sunburst）：与 2.0 同尺寸规则，
         * 但 quality 多出 {@code xhigh} / {@code max} 两档。
         */
        GPT_IMAGE_2_5(
                List.of("auto", "low", "medium", "high", "xhigh", "max"), "auto",
                List.of("png", "jpeg", "webp"), List.of("auto", "opaque", "transparent"),
                10),
        /**
         * 未识别的模型名（{@code gpt-image-1} / {@code dall-e-*} / 自建网关别名等）：
         * 最保守，只发 model/prompt。老两代的 size 规则与本插件不同，硬套比不发更危险。
         */
        UNKNOWN(
                List.of(), null,
                List.of(), List.of(),
                0);

        final List<String> qualities;
        final String defaultQuality;
        final List<String> outputFormats;
        final List<String> backgrounds;
        final int maxN;

        Family(List<String> qualities, String defaultQuality,
               List<String> outputFormats, List<String> backgrounds, int maxN) {
            this.qualities = qualities;
            this.defaultQuality = defaultQuality;
            this.outputFormats = outputFormats;
            this.backgrounds = backgrounds;
            this.maxN = maxN;
        }

        /**
         * 按模型名判定家族。<b>前缀匹配</b>，因为 2.5 的官方 id 带变体后缀
         * （{@code gpt-image-2.5-flare} / {@code gpt-image-2.5-sunburst}），且网关别名常再挂尾巴。
         * <p>顺序要紧：{@code gpt-image-2.5} 必须先于 {@code gpt-image-2} 判，
         * 否则变体前缀会被父代吃掉、丢掉 xhigh/max 两档。</p>
         * <p>判不出来就是 {@link #UNKNOWN}，走最保守形态并 WARN —— 不做「不含 X 就当 Y」的负向推断。</p>
         */
        static Family of(String model) {
            String m = StrUtil.nullToEmpty(model).trim().toLowerCase(Locale.ROOT);
            if (m.startsWith("gpt-image-2.5")) return GPT_IMAGE_2_5;
            if (m.startsWith("gpt-image-2")) return GPT_IMAGE_2;
            return UNKNOWN;
        }
    }

    // ════════════════════════════ 参数取值 ════════════════════════════

    /** 优先级：调用方 options > adapter.json defaults > 兜底常量。 */
    private static Object pick(Map<String, Object> opts, Map<String, Object> defs, String key) {
        Object v = opts.get(key);
        return v != null ? v : defs.get(key);
    }

    private static String str(Map<String, Object> o, Map<String, Object> d, String key, String fallback) {
        Object v = pick(o, d, key);
        String s = v == null ? null : String.valueOf(v).trim();
        return StrUtil.isBlank(s) ? fallback : s;
    }

    /**
     * 取值并在<b>该家族的合法集合</b>内校验；不合法 → WARN + 家族默认值。
     *
     * @param allowed  合法取值集合（空集表示该家族没有这个参数，调用方不会走到这里）
     * @param fallback 家族默认值
     */
    private static String pickFrom(Map<String, Object> opts, Map<String, Object> defs, String key,
                                   List<String> allowed, String fallback, String model) {
        String v = str(opts, defs, key, fallback).toLowerCase(Locale.ROOT);
        if (allowed.contains(v)) {
            return v;
        }
        log.warn("模型 [{}] 不支持 {}={}（合法取值 {}），已改用 {}", model, key, v, allowed, fallback);
        return fallback;
    }

    /** quality 只认调用方显式传入的值（adapter.json 不声明默认，避免换模型时默认值变成非法值）。 */
    private static String qualityOf(Family family, Map<String, Object> opts, String model) {
        if (family.qualities.isEmpty() || family.defaultQuality == null) {
            return null;
        }
        Object raw = opts.get("quality");
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) {
            return family.defaultQuality;
        }
        String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (family.qualities.contains(v)) {
            return v;
        }
        log.warn("模型 [{}] 不支持 quality={}（合法取值 {}），已改用 {}", model, v, family.qualities, family.defaultQuality);
        return family.defaultQuality;
    }

    /**
     * background 与 output_format 的<b>交叉约束</b>：官方要求 {@code transparent} 仅在
     * {@code png}/{@code webp} 下成立，配 {@code jpeg} 会 {@code 400}。
     *
     * <p>处理方式是<b>降掉 background 而不是改格式</b>：格式是用户更明确的诉求（挑 jpeg 通常
     * 是为了体积），而透明只有 png/webp 能承载 —— 保错的那一侧代价更小。</p>
     */
    private static String resolveBackground(Family family, String outputFormat,
                                            Map<String, Object> opts, Map<String, Object> defs, String model) {
        String background = pickFrom(opts, defs, "background", family.backgrounds, AUTO, model);
        if ("transparent".equals(background) && "jpeg".equals(outputFormat)) {
            log.warn("模型 [{}] 的 background=transparent 不支持 output_format=jpeg（透明需要 png/webp），已改用 auto", model);
            return AUTO;
        }
        return background;
    }

    /** 张数：一等字段 > options/defaults；再按家族上限收敛（gpt-image 系 1~10）。 */
    private static int resolveN(Family family, Text2ImageInput input, Map<String, Object> opts, Map<String, Object> defs) {
        int n = input.getN() != null ? input.getN() : intOrNull(opts, defs, "n", 1);
        if (n < 1) {
            return 1;
        }
        if (n > family.maxN) {
            log.warn("模型家族最多返回 {} 张图，请求的 n={} 已收敛为 {}", family.maxN, n, family.maxN);
            return family.maxN;
        }
        return n;
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

    // ════════════════════════════ size：任意分辨率的校验与修正 ════════════════════════════

    /**
     * size 约束档案：厂商四条硬约束 + 本条通道可配置的单边上限。
     *
     * <p>判据只有这<b>一份</b>实现，修正与校验共用 —— 避免出现「修的规则」与「判的规则」两套口径。
     * {@link #ok} 只判厂商四条（违反即 400，是硬的）；{@link #fitsBox} 判本插件的配置上限（软的，
     * 极端配置下可让位，见 {@link #fit}）。</p>
     *
     * @param step           两边必须是的整数倍（16）
     * @param vendorMaxEdge  厂商的单边硬上限（3840，{@link #ok} 用它判"违不违反厂商"）
     * @param maxWidth       本条通道的宽上限（≤ {@code vendorMaxEdge}；由 {@code maxSize} 配置收紧）
     * @param maxHeight      本条通道的高上限（同上）
     * @param minPixels      总像素下限（655,360）
     * @param maxPixels      总像素上限（8,294,400）
     * @param ratioMax       长短边比上限（3，即 ≤ 3:1）
     */
    private record SizeRules(int step, int vendorMaxEdge, int maxWidth, int maxHeight,
                             long minPixels, long maxPixels, int ratioMax) {

        /**
         * 厂商四条约束全满足才为 true（<b>只判厂商</b>，不含本插件的 {@code maxSize} 上限）。
         * <p>判据里必须用 {@code vendorMaxEdge} 而不是 {@code maxWidth/maxHeight}：
         * 后者是本插件的软上限，混进来会让"厂商合法但超我们自己上限"的候选被误判为非法。</p>
         */
        boolean ok(int w, int h) {
            return w > 0 && h > 0
                    && w % step == 0 && h % step == 0
                    && Math.max(w, h) <= vendorMaxEdge
                    && Math.max(w, h) <= (long) ratioMax * Math.min(w, h)
                    && (long) w * h >= minPixels && (long) w * h <= maxPixels;
        }

        /** 是否落在配置的宽高上限之内（软约束）。 */
        boolean fitsBox(int w, int h) {
            return w <= maxWidth && h <= maxHeight;
        }

        /**
         * 这个框<b>装得下</b>厂商的像素下限吗？—— 整数判据（框面积 &lt; 下限即必然无解）。
         *
         * <p>用它而不是"缩放后回乘比较"：{@code 16 * sqrt(655360/16)} 回乘会差 1~2 px，
         * 浮点比较会把 <b>正常</b>的标准上限（2560x1440）误判成"装不下"。<br>
         * ⚠️ 这是<b>必要</b>条件不是充分条件：比例约束还可能让"面积够但目标比例放不进去"
         * （如 1200x1200 的框里塞 3:1 的目标只有 1200x400 = 480,000 px）。那类情形由
         * {@link #snap} 的「只满足厂商约束」那一级 + 常用尺寸兜底吸收，不需要在这里判。</p>
         */
        boolean canFitMinPixels() {
            return (long) maxWidth * maxHeight >= minPixels;
        }

        /**
         * 用配置的 {@code maxSize} 收紧单边上限。
         * <p>只收紧不放大：{@code maxSize} 写 4096x4096 也不会突破厂商的 3840。</p>
         */
        SizeRules capped(int capWidth, int capHeight) {
            int w = Math.min(capWidth, maxWidth);
            int h = Math.min(capHeight, maxHeight);
            return (w == maxWidth && h == maxHeight)
                    ? this
                    : new SizeRules(step, vendorMaxEdge, w, h, minPixels, maxPixels, ratioMax);
        }

        /** 违反厂商约束的具体条目（用于 WARN 说清"差在哪"），全部合法时返回空串。 */
        String problems(int w, int h) {
            StringBuilder sb = new StringBuilder();
            if (w % step != 0 || h % step != 0) {
                sb.append("两边必须是 ").append(step).append(" 的整数倍; ");
            }
            if (Math.max(w, h) > vendorMaxEdge) {
                sb.append("单边不得超过 ").append(vendorMaxEdge).append("px; ");
            }
            if (Math.max(w, h) > (long) ratioMax * Math.min(w, h)) {
                sb.append("长短边比不得超过 ").append(ratioMax).append(":1; ");
            }
            long area = (long) w * h;
            if (area < minPixels || area > maxPixels) {
                sb.append("总像素须在 ").append(minPixels).append("~").append(maxPixels).append(" 之间; ");
            }
            return sb.toString();
        }
    }

    /**
     * 解析配置的宽高上限：{@code options.maxSize} &gt; {@code adapter.json defaults.maxSize}
     * &gt; {@link #DEFAULT_MAX_SIZE}；{@link #UNLIMITED} 里的写法表示不额外限制。
     */
    private static int[] resolveCap(Map<String, Object> opts, Map<String, Object> defs) {
        int vendorEdge = VENDOR_RULES.vendorMaxEdge;
        String raw = str(opts, defs, "maxSize", DEFAULT_MAX_SIZE).toLowerCase(Locale.ROOT);
        if (UNLIMITED.contains(raw)) {
            return new int[]{vendorEdge, vendorEdge};
        }
        int[] cap = parse(normalizeSeparator(raw));
        if (cap == null) {
            log.warn("maxSize=[{}] 无法解析（期望「宽x高」，或 off/none 表示不限），已改用默认 {}", raw, DEFAULT_MAX_SIZE);
            cap = parse(DEFAULT_MAX_SIZE);
        }
        if (cap[0] > vendorEdge || cap[1] > vendorEdge) {
            log.warn("maxSize={}x{} 超过厂商单边上限 {}px，超出部分无效（按 {}x{} 计）",
                    cap[0], cap[1], vendorEdge, Math.min(cap[0], vendorEdge), Math.min(cap[1], vendorEdge));
        }
        return cap;
    }

    /** 一等字段 {@code size} 优先，其次 {@code options.size}（前端 paramSchema 通道），最后 adapter.json defaults。 */
    private static String explicitSize(Text2ImageInput input, Map<String, Object> opts, Map<String, Object> defs) {
        return StrUtil.isNotBlank(input.getSize()) ? input.getSize() : str(opts, defs, "size", null);
    }

    /**
     * 解析最终下发的 size：显式（校验/修正）&gt; 画布宽高（推导）&gt; 默认 {@code 1024x1024}。
     *
     * <p><b>两条路径的判据不同，这是刻意的</b>：显式 size 是用户的明确诉求，只受厂商四条硬约束
     * 约束（{@link #VENDOR_RULES}，故超上限也照发、只留痕）；画布推导是"我们替他决定"，才吃
     * {@code maxSize} 上限（{@code derived}）。</p>
     *
     * <p><b>入参分隔符兼容</b>：宿主 {@link Text2ImageInput#getSize()} 的约定是 {@code "宽*高"}
     * （与 DashScope / ComfyUI 一路），而 OpenAI 要 {@code "宽x高"}。匹配前统一把 {@code *}/{@code ×}
     * 归一为 {@code x}，否则调用方明确给的尺寸会被当成非法值、退回家族默认档（不报错，只表现为
     * "我明明指定了尺寸却没用"）。下发时一律是 OpenAI 的 {@code x} 形态。</p>
     */
    private static String resolveSize(Family family, String explicit, Integer w, Integer h,
                                      String model, SizeRules derived) {
        String normalized = normalizeSeparator(explicit);
        if (normalized != null) {
            if (AUTO.equals(normalized)) {
                return AUTO; // 官方允许 auto：让模型按 prompt 自选
            }
            int[] dim = parse(normalized);
            if (dim == null) {
                log.warn("模型 [{}] 收到无法解析的 size=[{}]（期望「宽x高」或 auto），已改为按画布宽高推导", model, explicit);
            } else if (VENDOR_RULES.ok(dim[0], dim[1])) {
                if (!derived.fitsBox(dim[0], dim[1])) {
                    // 显式优先：不改用户的明确诉求，只留痕（"要收紧要显式改小或调大 maxSize"）
                    log.info("显式 size={} 超过本插件的 maxSize 上限 {}x{}，按「显式优先」原样下发",
                            normalized, derived.maxWidth, derived.maxHeight);
                }
                return normalized; // 合法就一个像素都不改 —— 这正是"任意分辨率"能力的意义
            } else {
                String fixed = fit(VENDOR_RULES, dim[0], dim[1]);
                log.warn("模型 [{}] 不接受 size={}（{}），已修正为 {}", model, normalized, VENDOR_RULES.problems(dim[0], dim[1]), fixed);
                return fixed;
            }
        }
        if (w == null || h == null || w <= 0 || h <= 0) {
            return COMMON_SIZES.getFirst();
        }
        // 上限不可行（框面积 < 厂商像素下限）→ 本次忽略它，否则只会得到一个既不合法、又没尊重画布的尺寸
        SizeRules rules = derived;
        if (!rules.canFitMinPixels()) {
            log.warn("maxSize {}x{} 装不下厂商的像素下限 {}px（该上限下不存在任何合法尺寸），本次推导忽略该上限，只按厂商单边 {}px 计",
                    rules.maxWidth, rules.maxHeight, rules.minPixels, VENDOR_RULES.vendorMaxEdge);
            rules = VENDOR_RULES;
        }
        String fitted = fit(rules, w, h);
        if (!fitted.equals(w + "x" + h)) {
            if (w > rules.maxWidth || h > rules.maxHeight) {
                log.info("画布 {}x{} 超过 maxSize 上限 {}x{}，已等比收窄为 {}（像素越少成本越低；要更大幅面请调大 maxSize）",
                        w, h, rules.maxWidth, rules.maxHeight, fitted);
            } else {
                log.debug("模型 [{}] 按画布宽高 {}x{} 推导出 size={}（画布尺寸不满足 16 倍数/像素区间等约束，已等比修正）",
                        model, w, h, fitted);
            }
        }
        return fitted;
    }

    /**
     * 把任意宽高等比修到约束内，再对齐到 {@code step} 的整数倍。
     *
     * <p>顺序固定：<b>宽高比 → 单边上限 → 总像素（上下限）→ 单边上限（再校一次）</b>。全部是
     * <b>等比</b>缩放，故不改变画布的比例意图，只是整体放大/缩小。最后一次单边校正是必要的：
     * 为抬总像素而放大的中间态可能又越过单边上限。</p>
     *
     * <p>⚠️ 本方法<b>不做</b>"上限装不下像素下限"的取舍（那是调用方 {@link #resolveSize} 的职责，
     * 用整数判据 {@link SizeRules#canFitMinPixels} 先决）。这里最后交给 {@link #snap} ——
     * 它保证了返回值必定满足厂商四条约束，故本方法不会产出非法值。</p>
     */
    private static String fit(SizeRules r, double w, double h) {
        // 1) 宽高比：过长边按比例缩短
        if (w > (double) r.ratioMax * h) {
            w = (double) r.ratioMax * h;
        } else if (h > (double) r.ratioMax * w) {
            h = (double) r.ratioMax * w;
        }
        // 2) 单边上限（含 maxSize）+ 3) 总像素上下限
        double[] a = clampBox(r, w, h);
        double area = a[0] * a[1];
        if (area > r.maxPixels) {
            double s = Math.sqrt((double) r.maxPixels / area);
            a[0] *= s;
            a[1] *= s;
        } else if (area < r.minPixels) {
            double s = Math.sqrt((double) r.minPixels / area);
            a[0] *= s;
            a[1] *= s;
        }
        // 4) 抬面积后又可能出框：再收一次（若因此跌破像素下限，由 snap 的第二级兜底）
        double[] b = clampBox(r, a[0], a[1]);
        return snap(r, b[0], b[1]);
    }

    /** 等比缩到框内（只缩不放）。 */
    private static double[] clampBox(SizeRules r, double w, double h) {
        double s = Math.min(1d, Math.min(r.maxWidth / w, r.maxHeight / h));
        return s < 1d ? new double[]{w * s, h * s} : new double[]{w, h};
    }

    /**
     * 对齐到 {@code step} 的整数倍：在「最近档 ± 一档」这个 3×3 邻域里挑<b>合法且离目标最近</b>的一组。
     *
     * <p>为什么不直接取最近档：16 对齐本身就会破坏另外几条约束（例如 1402x467 最近是 1408x464，
     * 总像素差 2,048 落到下限之下），"对齐后再判一次"才是可靠的做法。</p>
     *
     * <p>两级优先：先要厂商约束 + {@code maxSize} 上限都满足的；一个都没有时（上限太小），
     * 退而接受只满足厂商约束的（上限是软的）；再没有才退到官方 {@link #COMMON_SIZES}
     * （七个档位均已核对合法）。三级兜底保证本方法<b>永远返回合法值</b>。</p>
     */
    private static String snap(SizeRules r, double w, double h) {
        int cw0 = align(w, r.step);
        int ch0 = align(h, r.step);
        int inBoxW = -1;
        int inBoxH = -1;
        double inBoxCost = Double.MAX_VALUE;
        int anyW = -1;
        int anyH = -1;
        double anyCost = Double.MAX_VALUE;
        for (int dw = -1; dw <= 1; dw++) {
            int cw = cw0 + dw * r.step;
            if (cw < r.step) {
                continue;
            }
            for (int dh = -1; dh <= 1; dh++) {
                int ch = ch0 + dh * r.step;
                if (ch < r.step || !r.ok(cw, ch)) {
                    continue;
                }
                // 用 log 比值度量偏差：宽高各偏 1% 与各偏 10px 的代价应可比
                double cost = Math.abs(Math.log((double) cw / w)) + Math.abs(Math.log((double) ch / h));
                if (cost < anyCost) {
                    anyCost = cost;
                    anyW = cw;
                    anyH = ch;
                }
                if (r.fitsBox(cw, ch) && cost < inBoxCost) {
                    inBoxCost = cost;
                    inBoxW = cw;
                    inBoxH = ch;
                }
            }
        }
        if (inBoxW > 0) {
            return inBoxW + "x" + inBoxH;
        }
        if (anyW > 0) {
            return anyW + "x" + anyH;
        }
        return nearestCommonSize(r, w / h);
    }

    private static int align(double v, int step) {
        return Math.max(step, (int) Math.round(v / step) * step);
    }

    /**
     * 兜底：官方「常用尺寸」里宽高比最接近 {@code ratio} 的一档（优先仍落在 {@code maxSize} 内的）。
     */
    private static String nearestCommonSize(SizeRules r, double ratio) {
        String inBox = null;
        String any = null;
        double inBoxDiff = Double.MAX_VALUE;
        double anyDiff = Double.MAX_VALUE;
        for (String p : COMMON_SIZES) {
            int[] d = parse(p);
            if (!r.ok(d[0], d[1])) {
                continue;
            }
            double diff = Math.abs(Math.log(ratio / ((double) d[0] / d[1])));
            if (diff < anyDiff) {
                anyDiff = diff;
                any = p;
            }
            if (r.fitsBox(d[0], d[1]) && diff < inBoxDiff) {
                inBoxDiff = diff;
                inBox = p;
            }
        }
        return inBox != null ? inBox : any;
    }

    /** 把 {@code "1024*1024"} / {@code "1024×1024"} 归一为 {@code "1024x1024"}；空白返回 null。 */
    private static String normalizeSeparator(String s) {
        if (StrUtil.isBlank(s)) {
            return null;
        }
        return s.trim().replace('*', 'x').replace('×', 'x').toLowerCase(Locale.ROOT);
    }

    /** {@code "1024x1024"} → {@code [1024, 1024]}；形状不对（缺分隔符/非数字/非正）返回 null。 */
    private static int[] parse(String s) {
        int idx = s.indexOf('x');
        if (idx <= 0 || idx == s.length() - 1) {
            return null;
        }
        try {
            int w = Integer.parseInt(s.substring(0, idx).trim());
            int h = Integer.parseInt(s.substring(idx + 1).trim());
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ════════════════════════════ 记录本通道接不了的入参 ════════════════════════════

    /**
     * 记录本通道<b>接不了</b>的入参，避免用户以为「填了没生效是 bug」。
     *
     * <ul>
     *   <li>{@code negativePrompt} —— Images API 无反向提示词概念；</li>
     *   <li>{@code seed} —— ⚠️ 该端点<b>整个没有</b> {@code seed} 参数（不是"某些模型不支持"）。
     *       照发会得到 {@code 400 Unrecognized request argument}，故任何家族都不下发。</li>
     * </ul>
     */
    private static void warnIgnoredInputs(Text2ImageInput input, String model) {
        if (StrUtil.isNotBlank(input.getNegativePrompt())) {
            log.debug("GPT Images API 无反向提示词能力，已忽略 negativePrompt: {}",
                    StrUtil.maxLength(input.getNegativePrompt(), 60));
        }
        Integer seed = input.getSeed() != null ? input.getSeed() : seedFromOptions(input);
        if (seed != null) {
            log.debug("GPT Images API 没有 seed 参数（模型 [{}] 也不接受），已丢弃: {}", model, seed);
        }
    }

    /** 从 {@code options} 里探一眼 seed，只为了让上面的 DEBUG 提示能覆盖「走 options 传进来」的情形。 */
    private static Integer seedFromOptions(Text2ImageInput input) {
        Map<String, Object> opts = input.getOptions();
        if (opts == null) {
            return null;
        }
        Object v = opts.get("seed");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
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
