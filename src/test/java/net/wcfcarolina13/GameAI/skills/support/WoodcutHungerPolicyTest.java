package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.skills.support.WoodcutHungerPolicy.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Pure tests for the woodcut hunger warning and pause. No net.minecraft types. */
class WoodcutHungerPolicyTest {

    @Test
    void wellFedDoesNothing() {
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(20, 0, false));
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(11, 0, false));
    }

    @Test
    void lowWithNoFoodWarnsOnce() {
        assertEquals(Action.WARN_ONCE, WoodcutHungerPolicy.decide(10, 0, false));
        assertEquals(Action.WARN_ONCE, WoodcutHungerPolicy.decide(6, 0, false));
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(10, 0, true));
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(6, 0, true));
    }

    @Test
    void lowWithFoodDoesNotWarn() {
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(10, 3, false));
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(6, 1, false));
    }

    @Test
    void starvingAlwaysAsksForThePauseCheck() {
        // The caller tries to eat first; a bot with food eats instead of pausing.
        assertEquals(Action.PAUSE, WoodcutHungerPolicy.decide(5, 0, false));
        assertEquals(Action.PAUSE, WoodcutHungerPolicy.decide(5, 0, true));
        assertEquals(Action.PAUSE, WoodcutHungerPolicy.decide(2, 8, false));
        assertEquals(Action.PAUSE, WoodcutHungerPolicy.decide(0, 0, false));
    }

    @Test
    void customThresholdsAreUsed() {
        assertEquals(Action.WARN_ONCE, WoodcutHungerPolicy.decide(12, 0, false, 12, 4));
        assertEquals(Action.NONE, WoodcutHungerPolicy.decide(5, 0, true, 12, 4));
        assertEquals(Action.PAUSE, WoodcutHungerPolicy.decide(4, 0, true, 12, 4));
    }

    @Test
    void linesHaveNoDashesOrSemicolons() {
        for (String line : new String[] {WoodcutHungerPolicy.NO_FOOD_WARNING_LINE, WoodcutHungerPolicy.STARVING_PAUSE_LINE}) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains("–"), line);
            assertFalse(line.contains(";"), line);
        }
    }
}
