package io.dramatrix.plugin.qwenimage21;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 参考图槽位的<b>唯一真源</b>：场景（占 1）→ 角色 → 资产。
 *
 * <p>工作流接线（{@link Qwen21WorkflowBuilder}）与 prompt 槽位编号（{@link Qwen21PromptBuilder}）
 * 两侧共用本类。接口接线把图接到 {@code TextEncodeQwenImage21.images.image_N}，prompt 把
 * {@code <imageN>} 指到相应的参考图 —— 任何一处顺序 / 过滤不一致都会让 prompt 里的图号指到别的图。</p>
 */
final class ReferenceSlotPlanner {

    /** TextEncodeQwenImage21 的 {@code images} 接入上限（实测 12 张）。 */
    static final int MAX_REFERENCE_IMAGES = 12;

    /** 临时角色前缀：其参考图不接入 TextEncode（与文字兜底口径一致）。 */
    static final String TEMP_CHAR_PREFIX = "temp_char_";

    /**
     * 临时资产前缀。与 {@code io.dramatrix.worker.AssetInvocationHelper#TEMP_ASSET_PREFIX} 一致
     * （插件模块不依赖 dramatrix-domain，故此处按字面量同步；改动请两侧同步）。
     * 临时资产无 Asset 节点、图不可控，不接参考图，继续走文字描述。
     */
    static final String TEMP_ASSET_PREFIX = "__temp_asset__:";

    private ReferenceSlotPlanner() {
    }

    /**
     * 参考图接线顺序：场景（占 1）→ 角色 → 资产。两组各自按传入顺序遍历，只给
     * {@code hasImage} 为真的 id 分配槽位；临时资产（{@link #TEMP_ASSET_PREFIX}）与
     * （默认）临时角色（{@link #TEMP_CHAR_PREFIX}）被跳过，超过 {@link #MAX_REFERENCE_IMAGES}
     * 的部分不再分配（= 降级走文字描述）。
     *
     * @param hasScene       场景图是否作为 image1 接入
     * @param includeTempChar 是否把临时角色（{@code temp_char_*}）也纳入槽位（由插件决定）
     * @return id → 槽位号（1-based，含场景偏移）；迭代顺序 = 槽位顺序。不在其中 = 不占槽位
     */
    static Map<String, Integer> plan(
            boolean hasScene,
            boolean includeTempChar,
            List<String> characterIds,
            Predicate<String> characterHasImage,
            List<String> assetIds,
            Predicate<String> assetHasImage
    ) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        int next = hasScene ? 2 : 1;
        next = planGroup(slots, characterIds, characterHasImage, includeTempChar ? null : TEMP_CHAR_PREFIX, next);
        planGroup(slots, assetIds, assetHasImage, TEMP_ASSET_PREFIX, next);
        return slots;
    }

    /** 给一组 id 按序分配槽位，返回下一个可用槽位号。 */
    private static int planGroup(
            Map<String, Integer> slots, List<String> ids,
            Predicate<String> hasImage, String skipPrefix, int next
    ) {
        if (CollUtil.isEmpty(ids) || hasImage == null) {
            return next;
        }
        for (String id : ids) {
            if (next > MAX_REFERENCE_IMAGES) {
                break;
            }
            if (StrUtil.isBlank(id)
                    || (skipPrefix != null && id.startsWith(skipPrefix))
                    || slots.containsKey(id)
                    || !hasImage.test(id)) {
                continue;
            }
            slots.put(id, next++);
        }
        return next;
    }
}