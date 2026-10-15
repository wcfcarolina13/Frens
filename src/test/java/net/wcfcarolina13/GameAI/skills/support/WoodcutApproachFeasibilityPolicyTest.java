package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.skills.support.WoodcutApproachFeasibilityPolicy.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the trapped-woodcut decisions. No net.minecraft types. */
class WoodcutApproachFeasibilityPolicyTest {

    private static final boolean SKY = true;
    private static final boolean NO_SKY = false;
    private static final boolean NEAR_SURFACE = true;
    private static final boolean DEEP = false;

    @Test
    void sandboxCavePocketIsEnclosed() {
        // 1.1.238 log: openSky=false nearSurface=false cardinal=1 blocked=3
        assertTrue(WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, DEEP, 3));
        assertTrue(WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, DEEP, 4));
    }

    @Test
    void shallowDipUnderCanopyIsNotEnclosed() {
        // A 2-deep dip under leaves: no sky, every wall reads blocked, but near the surface.
        assertFalse(WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, NEAR_SURFACE, 4));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, NEAR_SURFACE, 4, false, 0, false, 1));
    }

    @Test
    void dipUnderOpenSkyIsNotEnclosed() {
        assertFalse(WoodcutApproachFeasibilityPolicy.isEnclosed(SKY, NEAR_SURFACE, 4));
        assertFalse(WoodcutApproachFeasibilityPolicy.isEnclosed(SKY, DEEP, 4));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(SKY, DEEP, 4, false, 0, false, 1));
    }

    @Test
    void depthAloneIsNotEnclosure() {
        // Deep and roofed but with open sides (a cave room, a tunnel mouth): not a pocket.
        assertFalse(WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, DEEP, 2));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 2, false, 0, false, 1));
    }

    @Test
    void enclosedWithoutAxeSkipsTarget() {
        assertEquals(Verdict.SKIP_TARGET,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 0, false, 1));
        // Scaffold in hand does not help while pillaring is axe-gated.
        assertEquals(Verdict.SKIP_TARGET,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 16, false, 1));
    }

    @Test
    void enclosedWithAxeButNoScaffoldSkipsTarget() {
        assertEquals(Verdict.SKIP_TARGET,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, true, 0, false, 1));
    }

    @Test
    void enclosedButAbleToClimbProceeds() {
        assertTrue(WoodcutApproachFeasibilityPolicy.canClimb(true, 4));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 4, true, 4, false, 1));
    }

    @Test
    void alreadyOnPillarProceeds() {
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 4, false, 0, true, 1));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(true, true, 5, 0));
    }

    @Test
    void streakStartsGrowsAndResets() {
        assertEquals(1, WoodcutApproachFeasibilityPolicy.nextStuckStreak(true, 0, 0));
        assertEquals(2, WoodcutApproachFeasibilityPolicy.nextStuckStreak(true, 1, 0));
        assertEquals(3, WoodcutApproachFeasibilityPolicy.nextStuckStreak(true, 2, 2));
        // Moved away: a new anchor, streak restarts at 1.
        assertEquals(1, WoodcutApproachFeasibilityPolicy.nextStuckStreak(true, 2, 3));
        // Not enclosed: forget it.
        assertEquals(0, WoodcutApproachFeasibilityPolicy.nextStuckStreak(false, 4, 0));
    }

    @Test
    void twoFailedTargetsFromTheSameSpotExit() {
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(true, false, 1, 0));
        assertEquals(Verdict.EXIT_TRAPPED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(true, false, 2, 0));
        assertEquals(Verdict.EXIT_TRAPPED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(true, false, 2, 2));
    }

    @Test
    void movingOrOpenSkyNeverExits() {
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(true, false, 2, 3));
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.afterFailedApproach(false, false, 5, 0));
    }

    @Test
    void sandboxSequenceExitsOnSecondTarget() {
        boolean enclosed = WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, DEEP, 3);
        int streak = 0;
        streak = WoodcutApproachFeasibilityPolicy.nextStuckStreak(enclosed, streak, 0);
        assertEquals(Verdict.PROCEED, WoodcutApproachFeasibilityPolicy.afterFailedApproach(enclosed, false, streak, 0));
        streak = WoodcutApproachFeasibilityPolicy.nextStuckStreak(enclosed, streak, 1);
        assertEquals(Verdict.EXIT_TRAPPED, WoodcutApproachFeasibilityPolicy.afterFailedApproach(enclosed, false, streak, 1));
    }

    @Test
    void firstTargetFromAnEnclosedSpotGetsOneRealApproach() {
        // No failed target yet from here: walk it, the enclosure read can miss an open side.
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 0, false, 0));
    }

    @Test
    void secondTargetFromTheSameEnclosedSpotIsSkipped() {
        assertEquals(Verdict.SKIP_TARGET,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 0, false, 1));
    }

    @Test
    void sandboxSequenceWalksOnceSkipsOnceThenExits() {
        boolean enclosed = WoodcutApproachFeasibilityPolicy.isEnclosed(NO_SKY, DEEP, 3);
        int streak = 0;
        // Target 1: walked (bounded), fails.
        assertEquals(Verdict.PROCEED,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 0, false, streak));
        streak = WoodcutApproachFeasibilityPolicy.nextStuckStreak(enclosed, streak, 0);
        assertEquals(Verdict.PROCEED, WoodcutApproachFeasibilityPolicy.afterFailedApproach(enclosed, false, streak, 0));
        // Target 2: skipped without walking, and the run ends.
        assertEquals(Verdict.SKIP_TARGET,
                WoodcutApproachFeasibilityPolicy.beforeApproach(NO_SKY, DEEP, 3, false, 0, false, streak));
        streak = WoodcutApproachFeasibilityPolicy.nextStuckStreak(enclosed, streak, 0);
        assertEquals(Verdict.EXIT_TRAPPED, WoodcutApproachFeasibilityPolicy.afterFailedApproach(enclosed, false, streak, 0));
    }

    @Test
    void dryRepositionBudgetIsFourFailuresOnAnySide() {
        assertFalse(WoodcutApproachFeasibilityPolicy.dryRepositionBudgetSpent(0));
        assertFalse(WoodcutApproachFeasibilityPolicy.dryRepositionBudgetSpent(3));
        assertTrue(WoodcutApproachFeasibilityPolicy.dryRepositionBudgetSpent(4));
        assertTrue(WoodcutApproachFeasibilityPolicy.dryRepositionBudgetSpent(9));
    }

    @Test
    void trappedLineHasNoDashesOrSemicolons() {
        String line = WoodcutApproachFeasibilityPolicy.TRAPPED_LINE;
        assertFalse(line.contains("—"));
        assertFalse(line.contains("–"));
        assertFalse(line.contains(";"));
    }
}
