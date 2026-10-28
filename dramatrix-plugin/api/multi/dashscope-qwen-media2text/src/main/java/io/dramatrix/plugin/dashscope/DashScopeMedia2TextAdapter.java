package io.dramatrix.plugin.dashscope;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.constants.Modality;
import io.dramatrix.plugin.dto.Media2TextInput;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.interfaces.Media2TextPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * DashScope Qwen 多模态理解适配器（{@code kind=api}，{@code runtime=api}）。
 *
 * <h3>职责边界</h3>
 * <p><b>只做请求体映射</b>：不发 HTTP、不持凭据、不读文件。本类由插件 JAR 经独立
 * {@code URLClassLoader} 实例化，编译期只依赖 {@code dramatrix-plugin-api}，拿不到宿主的
 * Spring 容器。因此媒体取回、鉴权、并发、超时全部由宿主侧的
 * {@code DashScopeComfyUIClient} 处理。</p>
 *
 * <h3>产出的形状：OpenAI 兼容</h3>
 * <p>统一产出 OpenAI 兼容通道的请求体（{@code {model, messages, enable_thinking, ...}}）——
 * 它是文档 §2.2 推荐的默认通道：形状最简、qwen3.5–3.8 全系可用、将来接别的厂商可复用同一份代码。
 * 若 backend 的 {@code baseUrl} 指向原生通道，宿主侧会机械转换为
 * {@code {model, input:{messages}, parameters:{...}}}；<b>协议差异不下沉到插件</b>，
 * 插件无需感知自己正跑在哪条通道上。</p>
 *
 * <h3>媒体用占位符传递</h3>
 * <p>{@code Media2TextInput.imageFile} 承载的是宿主侧给的「资源引用」：ComfyUI runtime 是 ComfyUI
 * input 目录文件名，而直连厂商插件（DashScope）是<b>本地文件的绝对路径</b>。插件没有 HTTP 能力、
 * 无法自行读文件转 Base64，故本类把媒体写成 {@code media-file://<引用>}，
 * 由宿主侧把该值替换成 {@code data:<mime>;base64,...}（DashScope 路径即直接读本地文件）。</p>
 *
 * @author helei
 */
@Slf4j
public class DashScopeMedia2TextAdapter extends AbstractComfyUIPlugin implements Media2TextPlugin {

    /** 媒体占位符前缀，须与宿主 {@code DashScopeMediaClient} 侧保持一致 */
    private static final String MEDIA_PLACEHOLDER = "media-file://";

    public DashScopeMedia2TextAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(Media2TextInput input) {
        if (StrUtil.isBlank(input.getPrompt())) {
            throw new IllegalArgumentException("DashScope media2text 需要 prompt");
        }
        Map<String, Object> opts = input.getOptions() == null ? Map.of() : input.getOptions();
        Map<String, Object> defs = defaults();

        String model = str(opts, defs, "modelName", "qwen3.8-flash");
        Modality modality = input.primaryModality();
        assertModelSupports(model, modality);

        JSONArray content = new JSONArray();
        appendMedia(content, input, modality, num(opts, defs, "videoFps", 2d));
        content.add(new JSONObject().set("type", "text").set("text", input.getPrompt()));

        JSONObject body = new JSONObject();
        body.set("model", model);
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "user").set("content", content));
        body.set("messages", messages);
        body.set("enable_thinking", bool(opts, defs, "enableThinking"));
        body.set("max_tokens", (int) resolveMaxTokens(input, opts, defs));
        body.set("temperature", resolveTemperature(input, opts, defs));
        return body;
    }

    // ════════════════════════════ 媒体 ════════════════════════════

    private void appendMedia(JSONArray content, Media2TextInput input, Modality modality, double fps) {
        switch (modality) {
            case IMAGE -> {
                requireNonBlank(input.getImageFile(), "imageFile", modality);
                content.add(mediaEl("image_url", input.getImageFile()));
            }
            case VIDEO -> {
                requireNonBlank(input.getVideoFile(), "videoFile", modality);
                JSONObject el = mediaEl("video_url", input.getVideoFile());
                el.set("fps", fps);
                content.add(el);
            }
            case AUDIO -> throw new IllegalArgumentException(
                    "本期 DashScope 插件未开放 AUDIO 模态；如需音频理解请另开 qwen3.5-omni 插件"
                            + "并把 extra.modalities 加上 AUDIO");
            case TEXT -> {
                // 纯文本，无需媒体
            }
        }
    }

    /** OpenAI 兼容的媒体元素形状：{@code {"type":"image_url","image_url":{"url":"..."}}} */
    private static JSONObject mediaEl(String type, String reference) {
        return new JSONObject()
                .set("type", type)
                .set(type, new JSONObject().set("url", MEDIA_PLACEHOLDER + reference));
    }

    private static void requireNonBlank(String v, String field, Modality modality) {
        if (StrUtil.isBlank(v)) {
            throw new IllegalArgumentException("modality=%s 但 %s 为空——这通常意味着上游媒体上传失败，而不是该模型不需要媒体".formatted(modality, field));
        }
    }

    // ════════════════════════════ (模型 × 模态) 校验矩阵 ════════════════════════════

    /**
     * 二次校验：{@code extra.modalities} 只能声明「各模型能力的并集」，而模型是<b>运行时</b>
     * 才选定的，声明态无法表达"这个模型不支持这种模态"。不在这里拦，就会把不支持多模态的模型
     * 发到多模态端点上，由厂商返回一条与配置脱节的报错。
     */
    private static void assertModelSupports(String model, Modality modality) {
        if (modality == Modality.TEXT) {
            return;
        }
        // qwen3.7-max 家族只支持文本 API（官方原文：only support the text API）
        if (model.startsWith("qwen3.7-max")) {
            throw new IllegalArgumentException("模型 %s 只支持文本 API、不支持多模态；请改用 qwen3.7-plus / qwen3.7-flash".formatted(model));
        }
        if (modality == Modality.VIDEO
                && (model.startsWith("qwen-vl-max") || model.startsWith("qwen-vl-plus")
                || model.startsWith("qwen-vl-ocr"))) {
            throw new IllegalArgumentException("模型 %s 不保证视频理解；请改用 qwen3.8-* 、qwen3.7-plus/flash 或 qwen3-vl-plus/flash".formatted(model));
        }
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

    private static double num(Map<String, Object> o, Map<String, Object> d, String key, double fallback) {
        Object v = pick(o, d, key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }

    /** configSchema 用 SELECT 暴露布尔值，故统一按字符串解析。 */
    private static boolean bool(Map<String, Object> o, Map<String, Object> d, String key) {
        Object v = pick(o, d, key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** 一级字段优先于 options —— 调用方直接 set 时以它为准。 */
    private static double resolveMaxTokens(Media2TextInput input,
                                           Map<String, Object> o, Map<String, Object> d) {
        return input.getMaxTokens() != null ? input.getMaxTokens() : num(o, d, "maxTokens", 2048d);
    }

    private static double resolveTemperature(Media2TextInput input,
                                             Map<String, Object> o, Map<String, Object> d) {
        return input.getTemperature() != null ? input.getTemperature() : num(o, d, "temperature", 0.6d);
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
