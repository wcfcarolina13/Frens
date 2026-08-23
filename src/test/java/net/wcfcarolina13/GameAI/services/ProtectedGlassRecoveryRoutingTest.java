package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedGlassRecoveryRoutingTest {

    @Test
    void protectedGlassHelperRecognizesVanillaGlassVariants() {
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.glass"));
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.blue_stained_glass"));
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.tinted_glass"));
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.glass_pane"));
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.iron_bars"));
        assertTrue(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.chain"));
        assertFalse(ProtectedStructureBlockHelper.isProtectedGlassLikeTranslationKey("block.minecraft.stone"));
    }

    @Test
    void mineEscapePrefersSnapForGlassLikeObstructions() {
        assertTrue(BotStuckService.shouldSnapProtectedGlassDuringMineEscapeKeys(
                null,
                null,
                "block.minecraft.glass_pane",
                null
        ));
        assertTrue(BotStuckService.shouldSnapProtectedGlassDuringMineEscapeKeys(
                null,
                null,
                "block.minecraft.chain",
                null
        ));

        assertFalse(BotStuckService.shouldSnapProtectedGlassDuringMineEscapeKeys(
                null,
                null,
                "block.minecraft.chest",
                null
        ));

        assertFalse(BotStuckService.shouldSnapProtectedGlassDuringMineEscapeKeys(
                null,
                null,
                "block.minecraft.stone",
                null
        ));
    }

    @Test
    void burialRescuePrefersSnapOnlyForGlassLikeBlocks() {
        assertTrue(BotRescueService.shouldPreferProtectedGlassSnapKeys(
                "block.minecraft.glass_pane",
                null
        ));
        assertTrue(BotRescueService.shouldPreferProtectedGlassSnapKeys(
                "block.minecraft.iron_bars",
                null
        ));

        assertFalse(BotRescueService.shouldPreferProtectedGlassSnapKeys(
                "block.minecraft.chest",
                null
        ));

        assertFalse(BotRescueService.shouldPreferProtectedGlassSnapKeys(
                "block.minecraft.stone",
                null
        ));
    }
}
