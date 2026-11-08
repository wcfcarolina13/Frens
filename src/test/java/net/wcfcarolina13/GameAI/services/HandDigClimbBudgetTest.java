package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HandDigClimbBudgetTest {

    @Test
    void aStepIsBudgetedAtItsWorstCase() {
        assertEquals(35_000L, HandDigStairPolicy.STEP_BUDGET_MILLIS);
    }

    @Test
    void theBudgetGrowsWithTheClimbAndStopsAtTheStepCap() {
        assertEquals(60_000L, HandDigStairPolicy.climbBudgetMillis(1, 60_000L));     // short climbs keep the old cap
        assertEquals(140_000L, HandDigStairPolicy.climbBudgetMillis(4, 60_000L));
        assertEquals(12 * 35_000L, HandDigStairPolicy.climbBudgetMillis(24, 60_000L)); // MAX_STEPS
        assertEquals(60_000L, HandDigStairPolicy.climbBudgetMillis(0, 60_000L));
    }

    @Test
    void onlyAHandDiggingBotGetsTheBudget() {
        assertEquals(140_000L, HandDigStairPolicy.runtimeCapMillis(true, 140_000L, 60_000L));
        assertEquals(60_000L, HandDigStairPolicy.runtimeCapMillis(false, 140_000L, 60_000L));
    }

    @Test
    void theAscentLoopReadsTheCap() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java"));
        assertTrue(src.contains("HandDigStairPolicy.runtimeCapMillis("));
    }

    @Test
    void handDigBudgetStaysOnceAStepWasDugByHand() {
        assertTrue(HandDigStairPolicy.handDigging(0, 0));
        assertFalse(HandDigStairPolicy.handDigging(0, 5));
        // The dug dirt gave it blocks: still a hand-dig climb, the budget does not drop to 60 s.
        assertTrue(HandDigStairPolicy.handDigging(2, 3));
        long budget = HandDigStairPolicy.climbBudgetMillis(6, 60_000L);
        assertEquals(budget, HandDigStairPolicy.runtimeCapMillis(
                HandDigStairPolicy.handDigging(2, 3), budget, 60_000L));
    }
}
