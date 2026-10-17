package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealOrderPolicyTest {

    /** BotFleeService.SEAL_BLOCKS order as of 1.1.252. */
    private static final List<String> TODAY = List.of(
            "minecraft:cobblestone", "minecraft:stone", "minecraft:dirt", "minecraft:cobbled_deepslate",
            "minecraft:deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
            "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:netherrack",
            "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "minecraft:gravel", "minecraft:sand",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks");

    @Test
    void withPickaxeKeepsTodaysOrder() {
        assertEquals(TODAY, SealOrderPolicy.order(TODAY, true, false));
        assertEquals(TODAY, SealOrderPolicy.order(TODAY, true, true));
    }

    @Test
    void withoutPickaxeHandBlocksFirstThenPlanksThenPickaxeBlocks() {
        List<String> out = SealOrderPolicy.order(TODAY, false, false);
        assertEquals(TODAY.size(), out.size());
        assertEquals(List.of("minecraft:dirt", "minecraft:sand", "minecraft:gravel"), out.subList(0, 3));
        for (int i = 3; i < 12; i++) {
            assertTrue(out.get(i).endsWith("_planks"), "slot " + i + " was " + out.get(i));
        }
        assertEquals("minecraft:oak_planks", out.get(3));
        assertEquals("minecraft:netherrack", out.get(12));
        assertEquals("minecraft:calcite", out.get(13));
        assertEquals("minecraft:cobbled_deepslate", out.get(out.size() - 1));
        assertEquals("minecraft:deepslate", out.get(out.size() - 2));
        assertEquals("minecraft:cobblestone", out.get(out.size() - 3));
    }

    @Test
    void stoneFamilyKeepsInputOrderAndSitsBeforeCobblestone() {
        List<String> out = SealOrderPolicy.order(TODAY, false, false);
        int stone = out.indexOf("minecraft:stone");
        int tuff = out.indexOf("minecraft:tuff");
        int basalt = out.indexOf("minecraft:basalt");
        int cobble = out.indexOf("minecraft:cobblestone");
        assertTrue(basalt < stone && stone < tuff && tuff < cobble);
    }

    @Test
    void axeDoesNotChangeTheOrder() {
        assertEquals(SealOrderPolicy.order(TODAY, false, false), SealOrderPolicy.order(TODAY, false, true));
    }

    @Test
    void cobblestoneOnlyStillSeals() {
        assertEquals(List.of("minecraft:cobblestone"),
                SealOrderPolicy.order(List.of("minecraft:cobblestone"), false, false));
    }

    @Test
    void unknownIdsGoLastInInputOrder() {
        List<String> in = List.of("mod:b", "minecraft:cobblestone", "mod:a", "minecraft:dirt");
        assertEquals(List.of("minecraft:dirt", "minecraft:cobblestone", "mod:b", "mod:a"),
                SealOrderPolicy.order(in, false, false));
    }

    @Test
    void capOrderDropsFallingBlocksAndKeepsTheRest() {
        java.util.Set<String> falling = java.util.Set.of("minecraft:sand", "minecraft:gravel");
        List<String> noPick = SealOrderPolicy.withoutFalling(SealOrderPolicy.order(TODAY, false, false), falling);
        assertEquals(TODAY.size() - 2, noPick.size());
        assertEquals("minecraft:dirt", noPick.get(0));
        assertEquals("minecraft:oak_planks", noPick.get(1));
        assertTrue(!noPick.contains("minecraft:sand") && !noPick.contains("minecraft:gravel"));
        assertEquals(List.of("minecraft:cobblestone"),
                SealOrderPolicy.withoutFalling(List.of("minecraft:gravel", "minecraft:cobblestone"), falling));
        assertEquals(List.of(), SealOrderPolicy.withoutFalling(List.of("minecraft:sand"), falling));
        assertEquals(List.of("minecraft:sand"), SealOrderPolicy.withoutFalling(List.of("minecraft:sand"), null));
    }

    @Test
    void emptyOrNullGivesEmpty() {
        assertEquals(List.of(), SealOrderPolicy.order(List.of(), false, false));
        assertEquals(List.of(), SealOrderPolicy.order(null, true, false));
    }
}
