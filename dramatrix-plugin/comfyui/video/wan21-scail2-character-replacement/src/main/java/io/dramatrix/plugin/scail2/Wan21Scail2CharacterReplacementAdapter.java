package io.dramatrix.plugin.scail2;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import io.dramatrix.plugin.AbstractComfyUIPlugin;
import io.dramatrix.plugin.dto.PluginMetadata;
import io.dramatrix.plugin.dto.VideoEditInput;
import io.dramatrix.plugin.interfaces.VideoEditPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaces a tracked person in a video with the character shown in a reference image. */
public class Wan21Scail2CharacterReplacementAdapter extends AbstractComfyUIPlugin implements VideoEditPlugin {

    private static final List<String> PROMPT_NODES = List.of("213:3", "262:258");
    private static final List<String> NEGATIVE_PROMPT_NODES = List.of("213:4", "262:257");
    private static final List<String> TRACK_TEXT_NODES = List.of("213:191", "213:212", "262:242", "262:261");
    private static final List<String> TRACK_NODES = List.of("213:196", "213:198", "262:244", "262:246");
    private static final List<String> MASK_NODES = List.of("213:197", "262:245");
    private static final List<String> GENERATION_NODES = List.of("213:114", "262:259");
    private static final List<String> SEED_NODES = List.of("213:19", "262:227");
    private static final List<String> FAST_MODE_NODES = List.of("213:172", "262:255");
    private static final List<String> FAST_LORA_NODES = List.of("213:11", "262:216");
    private static final List<String> DETAIL_LORA_NODES = List.of("213:318", "262:319");
    private static final Set<String> SORT_ORDERS = Set.of("none", "left_to_right", "area");

    private static final String DEFAULT_PROMPT =
            "Replace the selected person in the driving video with the character shown in the reference image. " +
            "Preserve the original movement, pose, camera, and background.";
    private static final String DEFAULT_NEGATIVE_PROMPT =
            "Avoid duplicate people, flicker, distorted anatomy, and changes to the background or camera.";

    public Wan21Scail2CharacterReplacementAdapter(PluginMetadata metadata) {
        super(metadata);
    }

    @Override
    public JSONObject buildApiJson(VideoEditInput input) {
        if (StrUtil.isBlank(workflowJson)) {
            throw new IllegalStateException("SCAIL-2 工作流未加载");
        }
        VideoEditInput edit = input == null ? new VideoEditInput() : input;
        if (StrUtil.isBlank(edit.getVideo())) {
            throw new IllegalArgumentException("角色替换需要输入视频");
        }
        List<String> references = edit.getRefImages();
        if (references == null || references.isEmpty() || StrUtil.isBlank(references.getFirst())) {
            throw new IllegalArgumentException("角色替换需要一张参考图片");
        }

        JSONObject api = new JSONObject(workflowJson);
        setInput(api, "155", "file", edit.getVideo());
        setInput(api, "30", "image", references.getFirst());

        Map<String, Object> params = edit.getOptions() == null ? Map.of() : edit.getOptions();
        String prompt = stringParam(params, "positivePrompt", DEFAULT_PROMPT);
        String negativePrompt = stringParam(params, "negativePrompt", DEFAULT_NEGATIVE_PROMPT);
        String targetText = stringParam(params, "targetText", "person");
        setInputs(api, PROMPT_NODES, "text", prompt);
        setInputs(api, NEGATIVE_PROMPT_NODES, "text", negativePrompt);
        setInputs(api, TRACK_TEXT_NODES, "text", targetText);

        double threshold = clamp(doubleParam(params, "detectionThreshold", 0.5), 0.05, 1.0);
        int maxObjects = clamp(intParam(params, "maxObjects", 4), 1, 16);
        int detectInterval = clamp(intParam(params, "detectInterval", 1), 1, 60);
        for (String nodeId : TRACK_NODES) {
            setInput(api, nodeId, "detection_threshold", threshold);
            setInput(api, nodeId, "max_objects", maxObjects);
            setInput(api, nodeId, "detect_interval", detectInterval);
        }

        String indices = stringParam(params, "objectIndices", "").trim();
        if (!indices.matches("[0-9,\\s]*")) indices = "";
        String sortBy = stringParam(params, "sortBy", "left_to_right");
        if (!SORT_ORDERS.contains(sortBy)) sortBy = "left_to_right";
        for (String nodeId : MASK_NODES) {
            setInput(api, nodeId, "object_indices", indices);
            setInput(api, nodeId, "sort_by", sortBy);
        }

        int width = alignedDimension(intParam(params, "width", 896));
        int height = alignedDimension(intParam(params, "height", 512));
        setInputs(api, List.of("213:178", "262:236"), "value", width);
        setInputs(api, List.of("213:179", "262:237"), "value", height);

        double poseStrength = clamp(doubleParam(params, "poseStrength", 1.0), 0.0, 2.0);
        double poseStart = clamp(doubleParam(params, "poseStart", 0.0), 0.0, 1.0);
        double poseEnd = clamp(doubleParam(params, "poseEnd", 1.0), 0.0, 1.0);
        if (poseStart > poseEnd) {
            double swap = poseStart;
            poseStart = poseEnd;
            poseEnd = swap;
        }
        int frameOffset = clamp(intParam(params, "frameOffset", 0), 0, 4096);
        for (String nodeId : GENERATION_NODES) {
            setInput(api, nodeId, "pose_strength", poseStrength);
            setInput(api, nodeId, "pose_start", poseStart);
            setInput(api, nodeId, "pose_end", poseEnd);
            setInput(api, nodeId, "video_frame_offset", frameOffset);
        }

        int requestedSeed = Math.max(0, intParam(params, "seed", 0));
        int seed = requestedSeed == 0 ? RandomUtil.randomInt(1, Integer.MAX_VALUE) : requestedSeed;
        for (String nodeId : SEED_NODES) setInput(api, nodeId, "noise_seed", Math.abs((long) seed));

        boolean fastMode = boolParam(params, "fastMode", true);
        setInputs(api, FAST_MODE_NODES, "value", fastMode);
        double fastLoraStrength = clamp(doubleParam(params, "fastLoraStrength", 0.8), 0.0, 2.0);
        double detailLoraStrength = clamp(doubleParam(params, "detailLoraStrength", 1.0), 0.0, 2.0);
        for (String nodeId : FAST_LORA_NODES) setInput(api, nodeId, "strength_model", fastLoraStrength);
        for (String nodeId : DETAIL_LORA_NODES) setInput(api, nodeId, "strength_model", detailLoraStrength);
        applyModelSlots(api);
        return api;
    }

    private String stringParam(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        if (value == null) value = paramSchemaDefault(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        if (value == null) value = paramSchemaDefault(key);
        Integer parsed = toInt(value);
        return parsed == null ? fallback : parsed;
    }

    private double doubleParam(Map<String, Object> params, String key, double fallback) {
        Object value = params.get(key);
        if (value == null) value = paramSchemaDefault(key);
        Double parsed = toDouble(value);
        return parsed == null ? fallback : parsed;
    }

    private boolean boolParam(Map<String, Object> params, String key, boolean fallback) {
        Object value = params.get(key);
        if (value == null) value = paramSchemaDefault(key);
        Boolean parsed = toBool(value);
        return parsed == null ? fallback : parsed;
    }

    private static int alignedDimension(int dimension) {
        int clamped = clamp(dimension, 256, 1536);
        return Math.max(256, (clamped / 32) * 32);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static void setInputs(JSONObject api, List<String> nodeIds, String field, Object value) {
        for (String nodeId : nodeIds) setInput(api, nodeId, field, value);
    }

    private static void setInput(JSONObject api, String nodeId, String field, Object value) {
        JSONObject node = api.getJSONObject(nodeId);
        if (node == null) throw new IllegalArgumentException("工作流节点不存在: " + nodeId);
        JSONObject inputs = node.getJSONObject("inputs");
        if (inputs == null) {
            inputs = new JSONObject();
            node.set("inputs", inputs);
        }
        inputs.set(field, value);
    }
}
