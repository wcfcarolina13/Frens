package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardEscapePolicyTest {

    private static final String STOP = StopFeedbackPolicy.MANUAL_STOP_REASON;
    private static final String RESPAWN = "§cCurrent task aborted due to respawn.";

    @Test
    void noLatchByDayProceeds() {
        assertEquals(GuardEscapePolicy.Decision.PROCEED,
                GuardEscapePolicy.decide(false, null, false, false, 20, false));
    }

    @Test
    void manualStopHoldsEvenWhenAGateWouldSuppress() {
        assertEquals(GuardEscapePolicy.Decision.HOLD,
                GuardEscapePolicy.decide(true, STOP, false, false, 20, false));
        assertEquals(GuardEscapePolicy.Decision.HOLD,
                GuardEscapePolicy.decide(true, "§c" + STOP, false, true, 20, true));
    }

    @Test
    void staleLatchByDayIsCleared() {
        assertEquals(GuardEscapePolicy.Decision.CLEAR_AND_PROCEED,
                GuardEscapePolicy.decide(true, RESPAWN, false, false, 20, false));
        assertEquals(GuardEscapePolicy.Decision.CLEAR_AND_PROCEED,
                GuardEscapePolicy.decide(true, "", false, false, 20, false));
        assertEquals(GuardEscapePolicy.Decision.CLEAR_AND_PROCEED,
                GuardEscapePolicy.decide(true, null, false, false, 20, false));
    }

    @Test
    void latchOwnedByARunningTaskIsLeftAlone() {
        assertEquals(GuardEscapePolicy.Decision.PROCEED,
                GuardEscapePolicy.decide(true, RESPAWN, true, false, 20, false));
    }

    @Test
    void nightSuppressesUnlessHungry() {
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_NIGHT,
                GuardEscapePolicy.decide(false, null, false, true, 20, false));
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_NIGHT,
                GuardEscapePolicy.decide(false, null, false, true, 4, false));
        assertEquals(GuardEscapePolicy.Decision.PROCEED,
                GuardEscapePolicy.decide(false, null, false, true, 3, false));
        assertEquals(GuardEscapePolicy.Decision.PROCEED,
                GuardEscapePolicy.decide(false, null, false, true, 0, false));
    }

    @Test
    void staleLatchAtNightIsSuppressedNotCleared() {
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_NIGHT,
                GuardEscapePolicy.decide(true, RESPAWN, false, true, 10, false));
    }

    @Test
    void hungryAtNightWithStaleLatchClears() {
        assertEquals(GuardEscapePolicy.Decision.CLEAR_AND_PROCEED,
                GuardEscapePolicy.decide(true, RESPAWN, false, true, 2, false));
    }

    @Test
    void insideBaseSuppresses() {
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_BASE,
                GuardEscapePolicy.decide(false, null, false, false, 20, true));
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_BASE,
                GuardEscapePolicy.decide(true, RESPAWN, false, false, 20, true));
        // Hunger overrides the night gate only, never the base gate.
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_BASE,
                GuardEscapePolicy.decide(false, null, false, true, 1, true));
    }

    @Test
    void nightIsCheckedBeforeBase() {
        assertEquals(GuardEscapePolicy.Decision.SUPPRESS_NIGHT,
                GuardEscapePolicy.decide(false, null, false, true, 20, true));
    }

    @Test
    void recentresWhenGuardPointWasInTheHole() {
        assertTrue(GuardEscapePolicy.shouldRecentre(60.0, 64.0));
        assertTrue(GuardEscapePolicy.shouldRecentre(63.0, 64.0));
    }

    @Test
    void keepsGuardPointAtOrAboveTheBot() {
        assertFalse(GuardEscapePolicy.shouldRecentre(64.0, 64.0));
        assertFalse(GuardEscapePolicy.shouldRecentre(63.5, 64.0));
        assertFalse(GuardEscapePolicy.shouldRecentre(70.0, 64.0));
    }
}
