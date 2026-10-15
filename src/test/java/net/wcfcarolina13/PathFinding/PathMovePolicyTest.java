package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMovePolicyTest {

    // isExecutable(dy, landingHasFloor, landingBodyClear, landingHeadClear,
    //              originHeadroomClear, landingAboveHeadClear)

    @Test
    void flatMoveOntoStandingCellIsExecutable() {
        assertTrue(PathMovePolicy.isExecutable(0, true, true, true, false, false));
    }

    @Test
    void oneBlockRiseWithHeadroomIsExecutable() {
        assertTrue(PathMovePolicy.isExecutable(1, true, true, true, true, false));
    }

    @Test
    void oneBlockRiseWithoutHeadroomAboveOriginIsRefused() {
        assertFalse(PathMovePolicy.isExecutable(1, true, true, true, false, true));
    }

    @Test
    void twoBlockRiseIsRefusedEvenOnAStandingCell() {
        assertFalse(PathMovePolicy.isExecutable(2, true, true, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(3, true, true, true, true, true));
    }

    @Test
    void floorlessLandingIsRefusedForEveryHeight() {
        assertFalse(PathMovePolicy.isExecutable(0, false, true, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(1, false, true, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(-1, false, true, true, true, true));
    }

    @Test
    void blockedLandingBodyOrHeadIsRefused() {
        assertFalse(PathMovePolicy.isExecutable(0, true, false, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(0, true, true, false, true, true));
        assertFalse(PathMovePolicy.isExecutable(1, true, true, false, true, true));
        assertFalse(PathMovePolicy.isExecutable(-1, true, false, true, true, true));
    }

    @Test
    void oneBlockDropWithClearHeadLaneIsExecutable() {
        assertTrue(PathMovePolicy.isExecutable(-1, true, true, true, false, true));
    }

    @Test
    void oneBlockDropWithBlockedHeadLaneIsRefused() {
        assertFalse(PathMovePolicy.isExecutable(-1, true, true, true, true, false));
    }

    @Test
    void dropsDeeperThanTheMaxAreRefused() {
        assertTrue(PathMovePolicy.MAX_DROP == 1);
        assertFalse(PathMovePolicy.isExecutable(-2, true, true, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(-4, true, true, true, true, true));
    }

    @Test
    void stepUpAndStepDownReadTheSameCellsSoTheRuleIsSymmetric() {
        // Low cell A, high cell B. A->B rise needs A.y+2 clear (originHeadroom);
        // B->A drop needs A.y+2 clear (landingAboveHead). Same cell, same answer.
        for (boolean sharedCellClear : new boolean[]{true, false}) {
            boolean up = PathMovePolicy.isExecutable(1, true, true, true, sharedCellClear, false);
            boolean down = PathMovePolicy.isExecutable(-1, true, true, true, false, sharedCellClear);
            assertTrue(up == down);
        }
    }

    // --- Wading (slice E) ---
    // isExecutable(dy, landingHasFloor, landingBodyClear, landingBodyIsWater, landingHeadClear,
    //              originHeadroomClear, landingAboveHeadClear)
    // landingBodyClear is the DRY read (false for water); landingHeadClear is dry-only too.

    @Test
    void wadingWithAnAirHeadOnAFloorIsExecutable() {
        assertTrue(PathMovePolicy.isExecutable(0, true, false, true, true, false, false));
    }

    @Test
    void wadingWithAWaterHeadIsRefused() {
        // A water head reads as not clear (the head predicate refuses every fluid): no swimming.
        assertFalse(PathMovePolicy.isExecutable(0, true, false, true, false, true, true));
        assertFalse(PathMovePolicy.isExecutable(-1, true, false, true, false, true, true));
    }

    @Test
    void wadingStepUpNeedsHeadroomAboveTheOrigin() {
        assertTrue(PathMovePolicy.isExecutable(1, true, false, true, true, true, false));
        assertFalse(PathMovePolicy.isExecutable(1, true, false, true, true, false, true));
    }

    @Test
    void wadingStepDownNeedsTheHeadLane() {
        assertTrue(PathMovePolicy.isExecutable(-1, true, false, true, true, false, true));
        assertFalse(PathMovePolicy.isExecutable(-1, true, false, true, true, true, false));
    }

    @Test
    void deepWaterWithNoFloorDirectlyBelowIsRefused() {
        // Water below the wading cell is not a solid floor.
        for (int dy = -1; dy <= 1; dy++) {
            assertFalse(PathMovePolicy.isExecutable(dy, false, false, true, true, true, true));
        }
    }

    @Test
    void wadingDoesNotLiftTheRiseOrDropLimits() {
        assertFalse(PathMovePolicy.isExecutable(2, true, false, true, true, true, true));
        assertFalse(PathMovePolicy.isExecutable(-2, true, false, true, true, true, true));
    }

    @Test
    void neitherDryNorWaterBodyIsRefused() {
        assertFalse(PathMovePolicy.isExecutable(0, true, false, false, true, true, true));
    }

    @Test
    void dryOverloadMatchesTheWadingFormWithADryBody() {
        for (int dy = -2; dy <= 2; dy++) {
            for (int bits = 0; bits < 32; bits++) {
                boolean f = (bits & 1) != 0, b = (bits & 2) != 0, h = (bits & 4) != 0;
                boolean o = (bits & 8) != 0, a = (bits & 16) != 0;
                assertTrue(PathMovePolicy.isExecutable(dy, f, b, h, o, a)
                        == PathMovePolicy.isExecutable(dy, f, b, false, h, o, a));
            }
        }
    }

    @Test
    void wadingCostsMoreThanADryStep() {
        assertTrue(PathMovePolicy.wadeCost(true) == PathMovePolicy.WADE_PENALTY);
        assertTrue(PathMovePolicy.WADE_PENALTY > 0.0);
        assertTrue(PathMovePolicy.wadeCost(false) == 0.0);
    }
}
