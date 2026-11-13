package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookSoonEligibilityPolicyTest {

    @Test
    void eligibleBotIsAllowedWithOrWithoutARun() {
        assertTrue(CookSoonEligibilityPolicy.allows(null, false));
        assertTrue(CookSoonEligibilityPolicy.allows(null, true));
    }

    @Test
    void ownFuelRunDoesNotEndTheHold() {
        assertTrue(CookSoonEligibilityPolicy.allows("task running", true));
        assertTrue(CookSoonEligibilityPolicy.allows("mode FOLLOW", true));
        assertTrue(CookSoonEligibilityPolicy.allows(CookSoonEligibilityPolicy.MODE_PREFIX + "GUARD", true));
    }

    @Test
    void busyWithoutAFuelRunStillEndsTheHold() {
        assertFalse(CookSoonEligibilityPolicy.allows("task running", false));
        assertFalse(CookSoonEligibilityPolicy.allows("mode FOLLOW", false));
    }

    @Test
    void otherReasonsEndTheHoldEvenDuringARun() {
        for (String reason : new String[] {"gone", "sleeping", "no server world", "not in the Overworld",
                "fleeing or sheltering"}) {
            assertFalse(CookSoonEligibilityPolicy.allows(reason, true), reason);
            assertFalse(CookSoonEligibilityPolicy.allows(reason, false), reason);
        }
    }

    @Test
    void afterRunReasonNamesTheFirstFailingGate() {
        assertEquals("the run was stopped",
                CookSoonEligibilityPolicy.noCookAfterRunReason(false, false, null, 8, 10, true));
        assertEquals("still no fuel",
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, false, null, 8, 10, true));
        assertEquals("not eligible (sleeping)",
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, "sleeping", 8, 10, true));
        assertEquals("food 16 is above the cook level 10",
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 16, 10, true));
        assertEquals("no cookable food left",
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 10, 10, false));
        assertEquals("the furnace cook did not start",
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 10, 10, true));
    }

    @Test
    void afterRunReasonsHaveNoEmDashOrSemicolon() {
        String[] reasons = {
                CookSoonEligibilityPolicy.noCookAfterRunReason(false, false, null, 8, 10, true),
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, false, null, 8, 10, true),
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, "task running", 8, 10, true),
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 16, 10, true),
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 10, 10, false),
                CookSoonEligibilityPolicy.noCookAfterRunReason(true, true, null, 10, 10, true)};
        for (String r : reasons) {
            assertFalse(r.contains("—") || r.contains(";"), r);
        }
    }
}
