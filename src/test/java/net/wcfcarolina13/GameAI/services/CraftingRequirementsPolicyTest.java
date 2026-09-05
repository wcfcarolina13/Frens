package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftingRequirementsPolicy.Missing;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingRequirementsPolicyTest {

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    void exactMatchLeavesNothingMissing() {
        assertTrue(CraftingRequirementsPolicy.missing(map("oak_planks", 6), map("oak_planks", 6)).isEmpty());
        assertTrue(CraftingRequirementsPolicy.missing(map("oak_planks", 6), map("oak_planks", 9)).isEmpty());
    }

    @Test
    void absentHaveKeyCountsAsZero() {
        List<Missing> m = CraftingRequirementsPolicy.missing(map("stick", 2), map());
        assertEquals(1, m.size());
        assertEquals(new Missing("stick", 2, 0), m.get(0));
        assertEquals(2, m.get(0).delta());
    }

    @Test
    void nullHaveMapTreatedAsEmpty() {
        List<Missing> m = CraftingRequirementsPolicy.missing(map("stick", 1), null);
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).have());
    }

    @Test
    void zeroAndNegativeNeedsAreIgnored() {
        assertTrue(CraftingRequirementsPolicy.missing(map("coal", 0, "stick", -3), map()).isEmpty());
    }

    @Test
    void insertionOrderIsPreserved() {
        List<Missing> m = CraftingRequirementsPolicy.missing(
                map("white_wool", 3, "oak_planks", 3, "stick", 4),
                map("oak_planks", 1));
        assertEquals(List.of("white_wool", "oak_planks", "stick"),
                m.stream().map(Missing::item).toList());
    }

    @Test
    void singleItemWordingUsesSingularWhenOneIsMissing() {
        String msg = CraftingRequirementsPolicy.format("torch",
                CraftingRequirementsPolicy.missing(map("stick", 1), map()));
        assertEquals("Can't craft torch: need 1 more stick", msg);
    }

    @Test
    void multiItemJoinMatchesSpec() {
        String msg = CraftingRequirementsPolicy.format("bed",
                CraftingRequirementsPolicy.missing(map("white_wool", 3, "oak_planks", 3),
                        map("oak_planks", 1)));
        assertEquals("Can't craft bed: need 3 more white wool, 2 more oak planks", msg);
    }

    @Test
    void pluralRuleOnlyAppendsWhenNeededAndNotAlreadyPlural() {
        assertEquals("Can't craft fence: need 2 more sticks",
                CraftingRequirementsPolicy.format("fence",
                        CraftingRequirementsPolicy.missing(map("stick", 2), map())));
        assertEquals("Can't craft door: need 5 more oak planks",
                CraftingRequirementsPolicy.format("door",
                        CraftingRequirementsPolicy.missing(map("oak_planks", 6), map("oak_planks", 1))));
    }

    @Test
    void formatReturnsEmptyWhenNothingMissing() {
        assertEquals("", CraftingRequirementsPolicy.format("bed", List.of()));
        assertEquals("", CraftingRequirementsPolicy.format("bed", null));
        assertEquals("", CraftingRequirementsPolicy.format("bed", List.of(new Missing("stick", 1, 4))));
    }

    @Test
    void massNounsAreNotPluralised() {
        assertEquals("Can't craft torch: need 3 more coal or charcoal",
                CraftingRequirementsPolicy.format("torch",
                        CraftingRequirementsPolicy.missing(map("coal or charcoal", 3), map())));
    }

    @Test
    void underscoresBecomeSpacesInTheCraftedThingToo() {
        assertEquals("Can't craft crafting table: need 4 more oak planks",
                CraftingRequirementsPolicy.format("crafting_table",
                        CraftingRequirementsPolicy.missing(map("oak_planks", 4), map())));
    }
}
