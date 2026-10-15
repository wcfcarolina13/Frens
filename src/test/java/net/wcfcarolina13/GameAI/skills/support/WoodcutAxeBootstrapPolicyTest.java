package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.skills.support.WoodcutAxeBootstrapPolicy.CraftFailure;
import net.wcfcarolina13.GameAI.skills.support.WoodcutAxeBootstrapPolicy.CraftFailureCause;
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

    private static CraftFailure failedAt(int x, int y, int z, CraftFailureCause cause) {
        return new CraftFailure(x, y, z, cause);
    }

    @Test
    void craftOnlyOnRealGround() {
        assertTrue(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, null, 0, 64, 0));
        assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(false, false, null, 0, 64, 0), "mid-air or mid-jump");
        assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, true, null, 0, 127, 0), "standing on own pillar");
    }

    @Test
    void anyFailureMayRetryAtADifferentY() {
        for (CraftFailureCause cause : CraftFailureCause.values()) {
            assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, failedAt(0, 64, 0, cause), 0, 64, 0), cause.name());
            assertTrue(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, failedAt(0, 127, 0, cause), 0, 64, 0),
                    "failed up a pillar, now grounded: " + cause);
            assertTrue(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, failedAt(0, 64, 0, cause), 0, 66, 0), cause.name());
            assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(false, false, failedAt(0, 127, 0, cause), 0, 64, 0), cause.name());
        }
    }

    @Test
    void tablePlacementFailureMayRetryFromANewCellOnFlatGround() {
        CraftFailure placement = failedAt(42, 65, 24, CraftFailureCause.TABLE_PLACEMENT);
        assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, placement, 42, 65, 24), "same cell");
        assertTrue(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, placement, 43, 65, 24), "one step in x");
        assertTrue(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, placement, 42, 65, 23), "one step in z");
        assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(false, false, placement, 43, 65, 24), "new cell but airborne");
        assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, true, placement, 43, 65, 24), "new cell on own scaffold");
    }

    @Test
    void otherFailuresStillNeedADifferentY() {
        for (CraftFailureCause cause : new CraftFailureCause[]{CraftFailureCause.MISSING_WOOD, CraftFailureCause.OTHER}) {
            CraftFailure failure = failedAt(42, 65, 24, cause);
            assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, failure, 43, 65, 24), cause.name());
            assertFalse(WoodcutAxeBootstrapPolicy.mayAttemptCraft(true, false, failure, 50, 65, 30), cause.name());
        }
    }

    @Test
    void classifyCraftFailureFromTheInventory() {
        assertEquals(CraftFailureCause.TABLE_PLACEMENT, WoodcutAxeBootstrapPolicy.classifyCraftFailure(true, true));
        assertEquals(CraftFailureCause.TABLE_PLACEMENT, WoodcutAxeBootstrapPolicy.classifyCraftFailure(true, false));
        assertEquals(CraftFailureCause.MISSING_WOOD, WoodcutAxeBootstrapPolicy.classifyCraftFailure(false, false));
        assertEquals(CraftFailureCause.OTHER, WoodcutAxeBootstrapPolicy.classifyCraftFailure(false, true));
    }

    @Test
    void craftFailureEndsRunOnlyWhenNothingChanged() {
        CraftFailure other64 = failedAt(0, 64, 0, CraftFailureCause.OTHER);
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(0, true, false, null, 0, 64, 0), "nothing failed");
        assertTrue(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, false, other64, 0, 64, 0), "ground failure, same spot");
        assertTrue(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, false, other64, 5, 64, 5), "same Y, new cell, not a placement failure");
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, true,
                failedAt(0, 127, 0, CraftFailureCause.OTHER), 0, 127, 0), "still on the pillar: wait for ground");
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, false, false, other64, 0, 64, 0), "airborne: decide later");
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, false,
                failedAt(0, 127, 0, CraftFailureCause.OTHER), 0, 64, 0), "grounded at a new Y may retry");
    }

    @Test
    void tablePlacementFailureDoesNotEndTheRunBeforeTheBotMoves() {
        CraftFailure placement = failedAt(42, 65, 24, CraftFailureCause.TABLE_PLACEMENT);
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, false, placement, 42, 65, 24),
                "still in the failure cell: the next approach moves it");
        assertFalse(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(1, true, false, placement, 43, 65, 24));
    }

    @Test
    void attemptBudgetEndsTheRun() {
        assertEquals(2, WoodcutAxeBootstrapPolicy.MAX_CRAFT_ATTEMPTS);
        for (CraftFailureCause cause : CraftFailureCause.values()) {
            assertTrue(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(2, true, false, failedAt(0, 64, 0, cause), 3, 70, 3), cause.name());
            assertTrue(WoodcutAxeBootstrapPolicy.craftFailureEndsRun(3, false, true, failedAt(0, 64, 0, cause), 3, 70, 3), cause.name());
        }
    }

    @Test
    void regatherAtTreeOncePerTreeWhileShort() {
        assertTrue(WoodcutAxeBootstrapPolicy.mayRegatherAtTree(true, 0, 4, false));
        assertTrue(WoodcutAxeBootstrapPolicy.mayRegatherAtTree(true, 3, 4, false));
        assertFalse(WoodcutAxeBootstrapPolicy.mayRegatherAtTree(true, 4, 4, false), "already has enough");
        assertFalse(WoodcutAxeBootstrapPolicy.mayRegatherAtTree(true, 0, 4, true), "already gathered at this tree");
        assertFalse(WoodcutAxeBootstrapPolicy.mayRegatherAtTree(false, 0, 4, false), "not bootstrapping");
    }

    @Test
    void noPillarWhileBootstrappingWithoutAxe() {
        assertFalse(WoodcutAxeBootstrapPolicy.mayPillar(true, false));
        assertTrue(WoodcutAxeBootstrapPolicy.mayPillar(true, true), "axe arrived mid-bootstrap");
        assertTrue(WoodcutAxeBootstrapPolicy.mayPillar(false, false), "not bootstrapping");
        assertTrue(WoodcutAxeBootstrapPolicy.mayPillar(false, true));
    }

    @Test
    void logsBecomeScaffoldOnlyWithAnAxe() {
        assertFalse(WoodcutAxeBootstrapPolicy.mayConvertLogsToScaffold(false));
        assertTrue(WoodcutAxeBootstrapPolicy.mayConvertLogsToScaffold(true));
    }
}
