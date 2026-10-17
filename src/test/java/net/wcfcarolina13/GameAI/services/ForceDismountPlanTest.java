package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForceDismountPlanTest {

    @Test
    void boatsAndMountsWithoutALeadSkipTheFenceSteps() {
        assertFalse(ForceDismountPlan.needsFenceLookup(false, false));
        assertFalse(ForceDismountPlan.needsFenceLookup(true, false));
        assertFalse(ForceDismountPlan.needsFenceProvision(false, true, false));
        assertFalse(ForceDismountPlan.needsFenceProvision(true, false, false));
    }

    @Test
    void aLeadReadyLooksForAFenceAndProvisionsOneOnlyWhenNoneIsInReach() {
        assertTrue(ForceDismountPlan.needsFenceLookup(true, true));
        assertTrue(ForceDismountPlan.needsFenceProvision(true, true, false));
        assertFalse(ForceDismountPlan.needsFenceProvision(true, true, true));
    }

    @Test
    void fallbackSaysTryAgainWithoutDashesOrSemicolons() {
        assertEquals("Bob couldn't dismount. Try again.", ForceDismountPlan.fallbackMessage("Bob", true));
        assertEquals("The bot couldn't dismount. Try again.", ForceDismountPlan.fallbackMessage(null, true));
        for (boolean mounted : new boolean[] {true, false}) {
            String msg = ForceDismountPlan.fallbackMessage("Bob", mounted);
            assertFalse(msg.contains("\u2014") || msg.contains(";"));
        }
    }

    @Test
    void fallbackAfterTheDismountOnlyDoubtsTheSecure() {
        assertEquals("Bob dismounted but couldn't secure the animal. Try /bot leash again.",
                ForceDismountPlan.fallbackMessage("Bob", false));
    }
}
