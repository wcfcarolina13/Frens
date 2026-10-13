package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.skills.support.WoodcutAxeBootstrapPolicy.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the bare-handed first-axe threshold. No net.minecraft types. */
class WoodcutAxeBootstrapPolicyTest {

    private static final boolean TABLE = true;
    private static final boolean NO_TABLE = false;

    @Test
    void emptyInventoryPunches() {
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(0, 0, 0, NO_TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(0, 0, 0, TABLE));
    }

    @Test
    void withoutTableNinePlanksNeededSoTwoLogsAreShort() {
        assertEquals(9, WoodcutAxeBootstrapPolicy.planksNeeded(0, NO_TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(1, 0, 0, NO_TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(2, 0, 0, NO_TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(3, 0, 0, NO_TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(2, 1, 0, NO_TABLE));
    }

    @Test
    void tableInReachSavesFourPlanks() {
        assertEquals(5, WoodcutAxeBootstrapPolicy.planksNeeded(0, TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(2, 0, 0, TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(1, 1, 0, TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(1, 0, 0, TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(0, 4, 0, TABLE));
    }

    @Test
    void twoSticksSkipTheStickCraftButOneDoesNot() {
        assertEquals(3, WoodcutAxeBootstrapPolicy.planksNeeded(2, TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(1, 0, 2, TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(0, 3, 2, TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(0, 2, 2, TABLE));
        assertEquals(5, WoodcutAxeBootstrapPolicy.planksNeeded(1, TABLE));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(0, 4, 1, TABLE));
        assertEquals(7, WoodcutAxeBootstrapPolicy.planksNeeded(5, NO_TABLE));
        assertEquals(Step.HAVE_ENOUGH_CRAFT, WoodcutAxeBootstrapPolicy.next(2, 0, 5, NO_TABLE));
    }

    @Test
    void negativeCountsReadAsZero() {
        assertEquals(0, WoodcutAxeBootstrapPolicy.planksAvailable(-3, -1));
        assertEquals(Step.PUNCH_LOG, WoodcutAxeBootstrapPolicy.next(-1, -1, -1, TABLE));
    }

    @Test
    void onlyAmbientRunsMayNotBootstrap() {
        assertFalse(WoodcutAxeBootstrapPolicy.allowBootstrap("ambient"));
        assertFalse(WoodcutAxeBootstrapPolicy.allowBootstrap("AMBIENT"));
        assertFalse(WoodcutAxeBootstrapPolicy.allowBootstrap(" Ambient "));
        assertTrue(WoodcutAxeBootstrapPolicy.allowBootstrap(null));
        assertTrue(WoodcutAxeBootstrapPolicy.allowBootstrap(""));
        assertTrue(WoodcutAxeBootstrapPolicy.allowBootstrap("hunt_prerequisite"));
        assertTrue(WoodcutAxeBootstrapPolicy.allowBootstrap("system"));
    }
}
