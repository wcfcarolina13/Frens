package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
