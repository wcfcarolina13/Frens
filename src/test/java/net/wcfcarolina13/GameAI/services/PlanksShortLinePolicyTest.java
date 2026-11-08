package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanksShortLinePolicyTest {

    @Test
    void fullPackWithLogsNamesThePack() {
        String line = PlanksShortLinePolicy.line(4, true, true);
        assertTrue(line.startsWith("My pack is full"), line);
        assertFalse(line.contains(";") || line.contains("—"), "player-facing punctuation rule");
    }

    @Test
    void otherwiseAsksForPlanksAsBefore() {
        assertEquals("I need 4 planks per craft.", PlanksShortLinePolicy.line(4, false, true));
        assertEquals("I need 4 planks per craft.", PlanksShortLinePolicy.line(4, true, false));
        assertEquals("I need 3 planks per craft.", PlanksShortLinePolicy.line(3, false, false));
    }
}
