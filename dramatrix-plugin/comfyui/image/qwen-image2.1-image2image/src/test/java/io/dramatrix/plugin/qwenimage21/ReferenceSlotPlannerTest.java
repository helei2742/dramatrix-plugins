package io.dramatrix.plugin.qwenimage21;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReferenceSlotPlanner} 槽位分配：临时角色默认跳过、由插件（{@code includeTempChar}）决定是否纳入。
 */
class ReferenceSlotPlannerTest {

    @Test
    void includeTempCharFalse_skipsTempCharacters() {
        Map<String, Integer> slots = ReferenceSlotPlanner.plan(
                true, false,
                List.of("char_a", "temp_char_x"), id -> true,
                List.of(), id -> true);
        // 场景占 1，char_a 占 2，temp_char_x 被跳过
        assertEquals(Map.of("char_a", 2), slots);
        assertFalse(slots.containsKey("temp_char_x"));
    }

    @Test
    void includeTempCharTrue_takesReferenceSlotInOrder() {
        Map<String, Integer> slots = ReferenceSlotPlanner.plan(
                true, true,
                List.of("char_a", "temp_char_x", "char_b"), id -> true,
                List.of(), id -> true);
        // 场景占 1，按序 char_a=2、temp_char_x=3、char_b=4
        assertEquals(2, slots.get("char_a"));
        assertEquals(3, slots.get("temp_char_x"));
        assertEquals(4, slots.get("char_b"));
    }

    @Test
    void tempAddAssetAlwaysSkipped() {
        Map<String, Integer> slots = ReferenceSlotPlanner.plan(
                false, true,
                List.of("char_a"), id -> true,
                List.of("__temp_asset__:1:0"), id -> true);
        // 即使 includeTempChar=true，临时资产仍不占槽位（其图不可控）
        assertTrue(slots.containsKey("char_a"));
        assertFalse(slots.containsKey("__temp_asset__:1:0"));
    }
}