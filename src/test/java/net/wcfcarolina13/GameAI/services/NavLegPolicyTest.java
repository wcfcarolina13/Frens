package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavLegPolicyTest {

    @Test
    void continuesWhileLegsMakeProgress() {
        assertTrue(NavLegPolicy.continueLegs(1, 10.0, false, false));
        assertTrue(NavLegPolicy.continueLegs(1, NavLegPolicy.MIN_PROGRESS_BLOCKS, false, false));
    }

    @Test
    void stopsWithoutProgress() {
        assertFalse(NavLegPolicy.continueLegs(1, 2.9, false, false));
        assertFalse(NavLegPolicy.continueLegs(1, -4.0, false, false));
    }

    @Test
    void stopsAtMaxLegs() {
        assertTrue(NavLegPolicy.continueLegs(NavLegPolicy.MAX_LEGS - 1, 10.0, false, false));
        assertFalse(NavLegPolicy.continueLegs(NavLegPolicy.MAX_LEGS, 10.0, false, false));
        assertFalse(NavLegPolicy.continueLegs(NavLegPolicy.MAX_LEGS, 10.0, true, false));
    }

    @Test
    void rememberedFailedFirstHopReplansWithoutProgress() {
        assertTrue(NavLegPolicy.continueLegs(2, 0.0, true, false));
    }

    @Test
    void abortAlwaysStops() {
        assertFalse(NavLegPolicy.continueLegs(1, 10.0, false, true));
        assertFalse(NavLegPolicy.continueLegs(1, 10.0, true, true));
    }
}
