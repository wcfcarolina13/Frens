package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ArmorCraftPolicy.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorCraftPolicyTest {

    @Test
    void eachMetalNamesItsFourPiecesWithTheVanillaCounts() {
        // data/minecraft/recipe/<material>_<piece>.json; minecraft.wiki Helmet/Chestplate/Leggings/Boots grids.
        assertEquals(new Piece("minecraft:iron_helmet", "minecraft:iron_ingot", 5), ArmorCraftPolicy.piece("iron_helmet"));
        assertEquals(new Piece("minecraft:iron_chestplate", "minecraft:iron_ingot", 8), ArmorCraftPolicy.piece("iron_chestplate"));
        assertEquals(new Piece("minecraft:iron_leggings", "minecraft:iron_ingot", 7), ArmorCraftPolicy.piece("iron_leggings"));
        assertEquals(new Piece("minecraft:iron_boots", "minecraft:iron_ingot", 4), ArmorCraftPolicy.piece("iron_boots"));
        assertEquals(new Piece("minecraft:golden_helmet", "minecraft:gold_ingot", 5), ArmorCraftPolicy.piece("golden_helmet"));
        assertEquals(new Piece("minecraft:diamond_boots", "minecraft:diamond", 4), ArmorCraftPolicy.piece("diamond_boots"));
        assertEquals(new Piece("minecraft:diamond_chestplate", "minecraft:diamond", 8), ArmorCraftPolicy.piece("diamond_chestplate"));
    }

    @Test
    void goldIsAcceptedForGoldenAndTypedNamesNormaliseFirst() {
        assertEquals(ArmorCraftPolicy.piece("golden_leggings"), ArmorCraftPolicy.piece("gold_leggings"));
        assertEquals(new Piece("minecraft:iron_helmet", "minecraft:iron_ingot", 5),
                ArmorCraftPolicy.piece(CraftRequestNamePolicy.normalize("minecraft:Iron Helmet")));
        // The craft-name parser leaves armour alone (it is not a tool kind).
        assertEquals("diamond_boots", CraftRequestNamePolicy.parse("diamond boots").genericName());
        assertFalse(CraftRequestNamePolicy.parse("diamond boots").hasMaterial());
    }

    @Test
    void leatherNetheriteAndOtherNamesAreNotMetalArmour() {
        assertNull(ArmorCraftPolicy.piece("leather_helmet"), "leather keeps craftLeatherArmor");
        assertNull(ArmorCraftPolicy.piece("netherite_helmet"));
        assertNull(ArmorCraftPolicy.piece("chainmail_boots"), "chainmail has no crafting recipe");
        assertNull(ArmorCraftPolicy.piece("iron_pickaxe"));
        assertNull(ArmorCraftPolicy.piece("helmet"));
        assertNull(ArmorCraftPolicy.piece("iron_"));
        assertNull(ArmorCraftPolicy.piece(null));
    }

    @Test
    void netheriteArmourIsReportedForTheSmithingLine() {
        assertTrue(ArmorCraftPolicy.isNetheriteArmor("netherite_chestplate"));
        assertTrue(ArmorCraftPolicy.isNetheriteArmor("netherite_boots"));
        assertFalse(ArmorCraftPolicy.isNetheriteArmor("netherite_pickaxe"));
        assertFalse(ArmorCraftPolicy.isNetheriteArmor("diamond_boots"));
        assertFalse(ArmorCraftPolicy.isNetheriteArmor(null));
    }
}
