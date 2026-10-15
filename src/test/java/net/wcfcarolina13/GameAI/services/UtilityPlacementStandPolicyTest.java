package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the utility placement stand/place decision. No net.minecraft types. */
class UtilityPlacementStandPolicyTest {

    private static final UtilityPlacementStandPolicy.Action PLACE_NOW = UtilityPlacementStandPolicy.Action.PLACE_NOW;
    private static final UtilityPlacementStandPolicy.Action STEP = UtilityPlacementStandPolicy.Action.STEP_TO_STAND;
    private static final UtilityPlacementStandPolicy.Action RELOCATE = UtilityPlacementStandPolicy.Action.RELOCATE;

    @Test
    void footprintNearEdgeReachesNeighbourCell() {
        // Field case 1.1.234: bot block 42,65,24 standing near the +x edge (x 42.5..43.1).
        assertTrue(UtilityPlacementStandPolicy.footprintOverlapsCell(42, 43, 65, 66, 24, 24, 43, 65, 24));
        assertFalse(UtilityPlacementStandPolicy.footprintOverlapsCell(42, 42, 65, 66, 24, 24, 43, 65, 24));
    }

    @Test
    void footprintCoversHeadCellButNotAboveIt() {
        assertTrue(UtilityPlacementStandPolicy.footprintOverlapsCell(10, 10, 64, 65, 10, 10, 10, 65, 10));
        assertFalse(UtilityPlacementStandPolicy.footprintOverlapsCell(10, 10, 64, 65, 10, 10, 10, 66, 10));
        assertFalse(UtilityPlacementStandPolicy.footprintOverlapsCell(10, 10, 64, 65, 10, 10, 10, 63, 10));
    }

    @Test
    void standEqualToPlaceOverlaps() {
        assertTrue(UtilityPlacementStandPolicy.standOverlapsPlace(43, 65, 24, 43, 65, 24));
    }

    @Test
    void standBelowPlaceOverlapsAtHeadHeight() {
        assertTrue(UtilityPlacementStandPolicy.standOverlapsPlace(43, 64, 24, 43, 65, 24));
    }

    @Test
    void standAbovePlaceOrBesideItIsClear() {
        assertFalse(UtilityPlacementStandPolicy.standOverlapsPlace(43, 66, 24, 43, 65, 24));
        assertFalse(UtilityPlacementStandPolicy.standOverlapsPlace(42, 65, 24, 43, 65, 24));
        assertFalse(UtilityPlacementStandPolicy.standOverlapsPlace(43, 65, 25, 43, 65, 24));
    }

    @Test
    void clearBotPlacesDirectlyWithoutRepositioning() {
        assertEquals(PLACE_NOW, UtilityPlacementStandPolicy.decide(false, true, 42, 65, 24, 43, 65, 24));
        assertEquals(PLACE_NOW, UtilityPlacementStandPolicy.decide(false, false, 0, 0, 0, 43, 65, 24));
    }

    @Test
    void fieldCaseStepsBackToPlannedStandNotOntoPlaceCell() {
        // Summary: place=43,65,24 stand=42,65,24, bot block 42,65,24 but hitbox over 43.
        assertEquals(STEP, UtilityPlacementStandPolicy.decide(true, true, 42, 65, 24, 43, 65, 24));
    }

    @Test
    void plannedStandThatHoldsPlaceCellRelocates() {
        assertEquals(RELOCATE, UtilityPlacementStandPolicy.decide(true, true, 43, 65, 24, 43, 65, 24));
        assertEquals(RELOCATE, UtilityPlacementStandPolicy.decide(true, true, 43, 64, 24, 43, 65, 24));
    }

    @Test
    void noPlannedStandRelocates() {
        assertEquals(RELOCATE, UtilityPlacementStandPolicy.decide(true, false, 0, 0, 0, 43, 65, 24));
    }

    @Test
    void craftingHelperRoutesRetryThroughPolicyAndSkipsBotOccupiedCells() throws IOException {
        String src = Files.readString(Paths.get(
                "src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("UtilityPlacementStandPolicy.decide("), "retry must use the policy");
        assertTrue(src.contains("\"bot-occupies\""), "planner must skip cells the bot's hitbox reaches into");
        assertTrue(src.contains("findRepositionStandClearOf(world, bot.getBlockPos(), placeAt, placeAt)"),
                "relocation during the retry must exclude the place cell");
    }
}
