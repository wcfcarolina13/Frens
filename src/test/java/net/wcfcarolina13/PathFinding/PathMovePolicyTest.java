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
}
