package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftingRequirementsPolicy.Missing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCraftShortfallPolicyTest {

    @Test
    void fieldCaseStoneAxeWithThreeSticksAndNoCobble() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("cobblestone", 3, 2, 1, 0, 3, 0, false);
        assertTrue(ToolCraftShortfallPolicy.isShort(reqs));
        assertEquals("I need 3 cobblestone and 2 sticks for a stone axe — I have 3 sticks.",
                ToolCraftShortfallPolicy.message("stone axe", 1, reqs));
    }

    @Test
    void nothingHeldSaysSo() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("iron_ingot", 3, 2, 1, 0, 0, 0, false);
        assertEquals("I need 3 iron ingots and 2 sticks for an iron pickaxe — I have none of that.",
                ToolCraftShortfallPolicy.message("iron pickaxe", 1, reqs));
    }

    @Test
    void allIngredientsPresentIsNotShortAndMessageEmpty() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("cobblestone", 3, 2, 1, 5, 2, 0, false);
        assertFalse(ToolCraftShortfallPolicy.isShort(reqs));
        assertEquals("", ToolCraftShortfallPolicy.message("stone axe", 1, reqs));
    }

    @Test
    void sparePlanksCoverMissingSticks() {
        // No sticks, but 2 planks make 4 sticks: only the cobblestone is short.
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("cobblestone", 3, 2, 1, 1, 0, 2, false);
        assertEquals(2, reqs.get(1).have());
        assertEquals(0, reqs.get(1).delta());
        assertEquals(2, reqs.get(0).delta());
    }

    @Test
    void woodenToolSetsHeadPlanksAsideBeforeCountingStickPlanks() {
        // 3 planks: all go to the head, none left for sticks.
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("planks", 3, 2, 1, 3, 0, 3, true);
        assertEquals(0, reqs.get(0).delta());
        assertEquals(2, reqs.get(1).delta());
        assertEquals("I need 3 planks and 2 sticks for a wooden axe — I have 3 planks.",
                ToolCraftShortfallPolicy.message("wooden axe", 1, reqs));
    }

    @Test
    void woodenToolWithEnoughPlanksForBoth() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("planks", 3, 2, 1, 5, 0, 5, true);
        assertFalse(ToolCraftShortfallPolicy.isShort(reqs));
    }

    @Test
    void multipleCraftsScaleTheRecipeAndPluraliseTheTool() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("diamond", 2, 1, 2, 1, 0, 0, false);
        assertEquals(4, reqs.get(0).need());
        assertEquals(2, reqs.get(1).need());
        assertEquals("I need 4 diamonds and 2 sticks for 2 diamond swords — I have 1 diamond.",
                ToolCraftShortfallPolicy.message("diamond sword", 2, reqs));
    }

    @Test
    void singleStickIsNotPluralised() {
        List<Missing> reqs = ToolCraftShortfallPolicy.requirements("gold_ingot", 2, 1, 1, 0, 1, 0, false);
        assertEquals("I need 2 gold ingots and 1 stick for a golden sword — I have 1 stick.",
                ToolCraftShortfallPolicy.message("golden sword", 1, reqs));
    }

    @Test
    void nullRequirementsAreNotShort() {
        assertFalse(ToolCraftShortfallPolicy.isShort(null));
        assertEquals("", ToolCraftShortfallPolicy.message("stone axe", 1, null));
    }
}
