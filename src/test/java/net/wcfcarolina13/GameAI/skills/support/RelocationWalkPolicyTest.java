package net.wcfcarolina13.GameAI.skills.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure tests for the relocation hop decision. No net.minecraft types. */
class RelocationWalkPolicyTest {

    @Test
    void progressOfAtLeastThreeBlocksEarnsAnotherHop() {
        // Field bug D: 96 blocks out, the walk got ~28 blocks closer before its deadline.
        assertTrue(RelocationWalkPolicy.continueToward(96.0, 68.0, 0));
        assertTrue(RelocationWalkPolicy.continueToward(10.0, 7.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(10.0, 7.1, 0));
    }

    @Test
    void noProgressOrMovingAwayStops() {
        assertFalse(RelocationWalkPolicy.continueToward(40.0, 40.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(40.0, 45.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(Double.NaN, 10.0, 0));
        assertFalse(RelocationWalkPolicy.continueToward(40.0, Double.NaN, 0));
    }

    @Test
    void hopCapEndsTheLoop() {
        assertTrue(RelocationWalkPolicy.continueToward(96.0, 68.0, RelocationWalkPolicy.MAX_EXTRA_HOPS - 1));
        assertFalse(RelocationWalkPolicy.continueToward(96.0, 68.0, RelocationWalkPolicy.MAX_EXTRA_HOPS));
        assertFalse(RelocationWalkPolicy.continueToward(96.0, 68.0, -1));
    }
    @Test
    void relocationBudgetEndsTheAttemptAfterNinetySeconds() {
        // Review M1: 1 + 3 hops of up to 40 s per candidate, over up to 8 blind-walk candidates.
        long start = 1_000_000L;
        assertFalse(RelocationWalkPolicy.budgetExhausted(start, start));
        assertFalse(RelocationWalkPolicy.budgetExhausted(start, start + 89_999L));
        assertTrue(RelocationWalkPolicy.budgetExhausted(start, start + RelocationWalkPolicy.RELOCATION_BUDGET_MS));
        assertTrue(RelocationWalkPolicy.budgetExhausted(start, start + 4L * 40_000L));
        assertTrue(RelocationWalkPolicy.RELOCATION_BUDGET_MS < (1L + RelocationWalkPolicy.MAX_EXTRA_HOPS) * 40_000L,
                "the budget is shorter than one candidate's worst case");
    }

    @Test
    void sunsetStopsAGatedRelocationAtTheWoodcutSunsetTime() {
        assertFalse(RelocationWalkPolicy.pastSunset(true, 11_999L, 12_000));
        assertTrue(RelocationWalkPolicy.pastSunset(true, 12_000L, 12_000));
        assertTrue(RelocationWalkPolicy.pastSunset(true, 3L * 24_000L + 13_000L, 12_000), "day count is ignored");
        assertFalse(RelocationWalkPolicy.pastSunset(true, 5L * 24_000L + 100L, 12_000), "the next morning");
        assertFalse(RelocationWalkPolicy.pastSunset(false, 13_000L, 12_000), "an internal run has no sunset gate");
    }
}
