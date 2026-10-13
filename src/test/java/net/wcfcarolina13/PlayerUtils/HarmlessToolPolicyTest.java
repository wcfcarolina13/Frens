package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmlessToolPolicyTest {

    @Test
    void emptyHandIsAlwaysHarmless() {
        assertTrue(HarmlessToolPolicy.isHarmlessFallback(true, false, false, 1.0f));
    }

    @Test
    void nonDurableNonWeaponItemAtHandSpeedIsHarmless() {
        // e.g. a dirt block or a stick
        assertTrue(HarmlessToolPolicy.isHarmlessFallback(false, false, false, 1.0f));
    }

    @Test
    void damageableToolIsNeverHarmlessEvenAtHandSpeed() {
        // copper axe on dirt / stone: speed 1.0 but it still loses durability
        assertFalse(HarmlessToolPolicy.isHarmlessFallback(false, false, true, 1.0f));
    }

    @Test
    void weaponIsNotHarmless() {
        assertFalse(HarmlessToolPolicy.isHarmlessFallback(false, true, true, 1.0f));
        assertFalse(HarmlessToolPolicy.isHarmlessFallback(false, true, false, 1.0f));
    }

    @Test
    void itemFasterThanHandIsNotAFallback() {
        assertFalse(HarmlessToolPolicy.isHarmlessFallback(false, false, false, 2.0f));
    }

    @Test
    void emptyHeldIsKept() {
        assertTrue(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(true, false, false, false));
    }

    @Test
    void heldDamageableNotEffectiveIsDropped() {
        // copper axe held while mining dirt or leaves
        assertFalse(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(false, true, false, false));
    }

    @Test
    void heldDamageableEffectiveIsKept() {
        // hoe held on leaves
        assertTrue(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(false, true, true, false));
    }

    @Test
    void heldNonDurableItemIsKept() {
        assertTrue(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(false, false, false, false));
    }

    @Test
    void heldWeaponIsNeverKept() {
        assertFalse(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(false, true, true, true));
        assertFalse(HarmlessToolPolicy.keepHeldWhenNoEffectiveTool(false, true, false, true));
    }

    @Test
    void effectiveMeansStrictlyFasterThanHand() {
        assertFalse(HarmlessToolPolicy.isEffective(1.0f));
        assertFalse(HarmlessToolPolicy.isEffective(0.5f));
        assertTrue(HarmlessToolPolicy.isEffective(1.5f));
    }

    @Test
    void mainInventorySwapOnlyForEffectiveTool() {
        assertFalse(HarmlessToolPolicy.shouldSwapInFromMainInventory(1.0f));
        assertFalse(HarmlessToolPolicy.shouldSwapInFromMainInventory(0.0f));
        assertTrue(HarmlessToolPolicy.shouldSwapInFromMainInventory(4.0f));
    }

    @Test
    void axeKeywordDoesNotMatchPickaxe() {
        assertFalse(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.stone_pickaxe", "axe"));
        assertTrue(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.copper_axe", "axe"));
        assertTrue(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.stone_pickaxe", "pickaxe"));
        assertTrue(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.iron_shovel", "shovel"));
        assertTrue(HarmlessToolPolicy.matchesToolKeyword("ITEM.MINECRAFT.IRON_AXE", "Axe"));
    }

    @Test
    void keywordMatchRejectsBlankInputs() {
        assertFalse(HarmlessToolPolicy.matchesToolKeyword(null, "axe"));
        assertFalse(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.iron_axe", null));
        assertFalse(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.iron_axe", " "));
        assertFalse(HarmlessToolPolicy.matchesToolKeyword("item.minecraft.dirt", "shovel"));
    }

    @Test
    void heldStaysOnlyWhenNoHarmlessItemAndNoEmptySlot() {
        assertTrue(HarmlessToolPolicy.keepHeldAsLastResort(false, false, false));
        assertFalse(HarmlessToolPolicy.keepHeldAsLastResort(true, false, false));
        assertFalse(HarmlessToolPolicy.keepHeldAsLastResort(false, true, false));
        assertFalse(HarmlessToolPolicy.keepHeldAsLastResort(true, true, false));
    }

    @Test
    void lastResortNeverKeepsAWeapon() {
        assertFalse(HarmlessToolPolicy.keepHeldAsLastResort(false, false, true));
    }
}
