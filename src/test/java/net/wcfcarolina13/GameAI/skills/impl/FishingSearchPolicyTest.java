package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

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
}
