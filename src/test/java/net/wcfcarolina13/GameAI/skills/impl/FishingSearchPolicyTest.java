package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingSearchPolicyTest {

    @Test
    void neverStopsWithoutAUsableSpot() {
        assertFalse(FishingSearchPolicy.stopEvaluating(500, 60_000L, false));
    }

    @Test
    void keepsGoingInsideTheBudget() {
        assertFalse(FishingSearchPolicy.stopEvaluating(10, 200L, true));
        assertFalse(FishingSearchPolicy.stopEvaluating(
                FishingSearchPolicy.MAX_WATER_EVALUATIONS - 1, FishingSearchPolicy.TIME_BUDGET_MS - 1, true));
    }

    @Test
    void stopsAtTheTimeBudgetOnceAUsableSpotExists() {
        assertTrue(FishingSearchPolicy.stopEvaluating(3, FishingSearchPolicy.TIME_BUDGET_MS, true));
        assertTrue(FishingSearchPolicy.stopEvaluating(3, 18_729L, true));
    }

    @Test
    void stopsAtTheEvaluationCapOnceAUsableSpotExists() {
        assertTrue(FishingSearchPolicy.stopEvaluating(FishingSearchPolicy.MAX_WATER_EVALUATIONS, 10L, true));
    }

    @Test
    void optionThatCannotBeatTheBestIsNotChecked() {
        assertFalse(FishingSearchPolicy.worthChecking(false, 0, 5.0D, 5.0D));
        assertFalse(FishingSearchPolicy.worthChecking(false, 0, 6.0D, 5.0D));
        assertTrue(FishingSearchPolicy.worthChecking(false, 0, 4.9D, 5.0D));
        assertTrue(FishingSearchPolicy.worthChecking(false, 0, 100.0D, Double.MAX_VALUE));
    }

    @Test
    void keepsAtMostTwoAlternatesAfterThePrimary() {
        assertTrue(FishingSearchPolicy.worthChecking(true, 1, 50.0D, 0.0D));
        assertTrue(FishingSearchPolicy.worthChecking(true, 2, 50.0D, 0.0D));
        assertFalse(FishingSearchPolicy.worthChecking(true, 1 + FishingSearchPolicy.MAX_STAND_ALTERNATES, 0.0D, 50.0D));
    }

    @Test
    void chestScanCoversTheSearchAreaPlusTheMargin() {
        assertEquals(36, FishingSearchPolicy.chestScanHalfWidth(28));
        assertEquals(FishingSearchPolicy.CHEST_SCAN_MARGIN, FishingSearchPolicy.chestScanHalfWidth(-5));
    }

    @Test
    void chestJustInsideTheBoundIsSeenAndJustOutsideIsNot() {
        int half = FishingSearchPolicy.chestScanHalfWidth(28);
        int ySpan = FishingSearchPolicy.CHEST_SCAN_Y_SPAN;
        assertTrue(FishingSearchPolicy.chestScanCovers(36, 0, -36, half, ySpan));
        assertEquals(16, ySpan, "same vertical reach as the old HOME_CHEST_Y_SPAN");
        assertTrue(FishingSearchPolicy.chestScanCovers(-10, -16, 20, half, ySpan));
        assertTrue(FishingSearchPolicy.chestScanCovers(0, 16, 0, half, ySpan));
        assertFalse(FishingSearchPolicy.chestScanCovers(37, 0, 0, half, ySpan));
        assertFalse(FishingSearchPolicy.chestScanCovers(0, 0, -37, half, ySpan));
        assertFalse(FishingSearchPolicy.chestScanCovers(0, 17, 0, half, ySpan));
        assertFalse(FishingSearchPolicy.chestScanCovers(0, -17, 0, half, ySpan));
    }

    @Test
    void snapshotIsNotNeededForStandsOutsideItsBox() {
        // Box of FollowPathService.capture(world, o, o, true): |dx|,|dz| <= 24, |dy| <= 2.
        assertFalse(FishingSearchPolicy.snapshotNeeded(25, 0, 0, 24, 2, 2.25D));
        assertFalse(FishingSearchPolicy.snapshotNeeded(0, 0, -25, 24, 2, 2.25D));
        assertFalse(FishingSearchPolicy.snapshotNeeded(3, 3, 3, 24, 2, 2.25D));
        assertFalse(FishingSearchPolicy.snapshotNeeded(3, -3, 3, 24, 2, 2.25D));
    }

    @Test
    void snapshotIsNotNeededForTheStandUnderTheBot() {
        assertFalse(FishingSearchPolicy.snapshotNeeded(0, 0, 0, 24, 2, 2.25D));
        assertFalse(FishingSearchPolicy.snapshotNeeded(1, 0, 0, 24, 2, 2.25D));
        assertFalse(FishingSearchPolicy.snapshotNeeded(0, 1, 0, 24, 2, 2.25D));
    }

    @Test
    void snapshotIsNeededForAStandInsideItsBoxAwayFromTheBot() {
        assertFalse(FishingSearchPolicy.snapshotNeeded(1, 0, 1, 24, 2, 2.25D));
        assertTrue(FishingSearchPolicy.snapshotNeeded(2, 0, 0, 24, 2, 2.25D));
        assertTrue(FishingSearchPolicy.snapshotNeeded(24, 2, -24, 24, 2, 2.25D));
        assertTrue(FishingSearchPolicy.snapshotNeeded(-5, -2, 0, 24, 2, 2.25D));
    }

    @Test
    void castScoreIsZeroAtTheIdealDistanceOverDryLand() {
        assertEquals(0.0D, FishingSearchPolicy.castScore(36.0D, 36, 0, 0, 0.0D));
    }

    @Test
    void castScoreRewardsOpenDeepVanillaWater() {
        double shallowEdge = FishingSearchPolicy.castScore(36.0D, 36, 3, 1, 0.0D);
        double openDeep = FishingSearchPolicy.castScore(36.0D, 36, 9, 6, -5.0D);
        assertTrue(openDeep < shallowEdge);
        assertEquals(-3.0D * 0.35 - 0.55, shallowEdge, 1e-12);
    }

    @Test
    void castScoreMatchesTheInlineFormulaItReplaced() {
        // The exact expression FishingSkill computed inline before finding 43.
        double distSq = 50.0D;
        int ideal = 36;
        double inline = Math.abs(distSq - ideal) / (double) ideal - 7 * 0.35 - 4 * 0.55 + -3.0D;
        assertEquals(inline, FishingSearchPolicy.castScore(distSq, ideal, 7, 4, -3.0D));
        assertTrue(FishingSearchPolicy.castScore(16.0D, ideal, 0, 0, 0.0D) > 0.0D);
        assertTrue(FishingSearchPolicy.castScore(100.0D, ideal, 0, 0, 0.0D) > 0.0D);
    }
}
