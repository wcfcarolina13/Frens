package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftRequestNamePolicy.Parsed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftRequestNamePolicyTest {

    private static void assertTool(String input, String kind, String material) {
        Parsed p = CraftRequestNamePolicy.parse(input);
        assertEquals(kind, p.genericName(), input);
        assertEquals(material, p.material(), input);
        assertTrue(p.hasMaterial(), input);
    }

    private static void assertPlain(String input, String expectedName) {
        Parsed p = CraftRequestNamePolicy.parse(input);
        assertEquals(expectedName, p.genericName(), input);
        assertNull(p.material(), input);
        assertFalse(p.hasMaterial(), input);
    }

    @Test
    void fieldCaseStoneAxeUnderscoreAndSpace() {
        assertTool("stone_axe", "axe", "stone");
        assertTool("stone axe", "axe", "stone");
    }

    @Test
    void woodAndWoodenBothMapToWood() {
        assertTool("wooden_pickaxe", "pickaxe", "wood");
        assertTool("wood pickaxe", "pickaxe", "wood");
        assertTool("wooden sword", "sword", "wood");
    }

    @Test
    void goldAndGoldenBothMapToGold() {
        assertTool("golden_hoe", "hoe", "gold");
        assertTool("gold hoe", "hoe", "gold");
    }

    @Test
    void everyTierAndKind() {
        assertTool("iron_sword", "sword", "iron");
        assertTool("diamond_shovel", "shovel", "diamond");
        assertTool("stone_pickaxe", "pickaxe", "stone");
        assertTool("netherite_axe", "axe", "netherite");
    }

    @Test
    void pickaxeIsNotReadAsAxe() {
        assertTool("iron_pickaxe", "pickaxe", "iron");
    }

    @Test
    void minecraftPrefixIsStripped() {
        assertTool("minecraft:stone_axe", "axe", "stone");
        assertTool("MINECRAFT:Diamond_Sword", "sword", "diamond");
        assertPlain("minecraft:oak_planks", "oak_planks");
    }

    @Test
    void caseHyphensAndExtraSpacesNormalise() {
        assertTool("  Stone   AXE ", "axe", "stone");
        assertTool("Iron-Shovel", "shovel", "iron");
        assertTool("diamond__hoe", "hoe", "diamond");
    }

    @Test
    void pluralToolKindsAreAccepted() {
        assertTool("stone axes", "axe", "stone");
        assertTool("iron_pickaxes", "pickaxe", "iron");
    }

    @Test
    void bareToolNamesCarryNoMaterial() {
        assertPlain("axe", "axe");
        assertPlain("Pickaxe", "pickaxe");
    }

    @Test
    void nonToolNamesPassThrough() {
        assertPlain("oak_planks", "oak_planks");
        assertPlain("torch", "torch");
        assertPlain("crafting table", "crafting_table");
        assertPlain("carrot_on_a_stick", "carrot_on_a_stick");
        assertPlain("warped_fungus_on_a_stick", "warped_fungus_on_a_stick");
        assertPlain("leather_chestplate", "leather_chestplate");
        assertPlain("iron_ingot", "iron_ingot");
        assertPlain("stone_pressure_plate", "stone_pressure_plate");
    }

    @Test
    void unknownNamesPassThrough() {
        assertPlain("flux_capacitor", "flux_capacitor");
        assertPlain("copper_axe", "copper_axe");
        assertPlain("iron_chestplate", "iron_chestplate");
    }

    @Test
    void emptyAndNullAreSafe() {
        assertPlain(null, "");
        assertPlain("", "");
        assertPlain("   ", "");
        assertPlain("_axe", "axe");
    }
}
