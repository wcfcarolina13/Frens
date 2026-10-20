package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ShoreStepPolicy.Cell;
import net.wcfcarolina13.GameAI.services.ShoreStepPolicy.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShoreStepPolicyTest {

    private static Side rock(int side, int depth) {
        return new Side(side, true, Cell.MINEABLE, Cell.MINEABLE, false, depth);
    }

    @Test
    void finding48PocketPicksTheShallowestRockSide() {
        // Walls of natural rock on all four sides; the east column reaches sky soonest.
        List<Side> sides = List.of(rock(0, 9), rock(1, 12), rock(2, 4), rock(3, 7));
        assertEquals(2, ShoreStepPolicy.choose(sides));
    }

    @Test
    void depthTieGoesToTheSideWithFewerBlocksToMine() {
        Side two = rock(0, 5);
        Side one = new Side(1, true, Cell.MINEABLE, Cell.OPEN, false, 5);
        assertEquals(1, ShoreStepPolicy.choose(List.of(two, one)));
        assertEquals(2, ShoreStepPolicy.blocksToMine(two));
        assertEquals(1, ShoreStepPolicy.blocksToMine(one));
    }

    @Test
    void fullTieGoesToTheLowestSideIndex() {
        assertEquals(1, ShoreStepPolicy.choose(List.of(rock(3, 5), rock(1, 5))));
    }

    @Test
    void sideWithoutAFloorIsSkipped() {
        Side noFloor = new Side(0, false, Cell.OPEN, Cell.OPEN, false, 1);
        assertEquals(1, ShoreStepPolicy.choose(List.of(noFloor, rock(1, 20))));
    }

    @Test
    void blockedCellRulesOutTheSide() {
        Side protectedFeet = new Side(0, true, Cell.BLOCKED, Cell.OPEN, false, 1);
        Side protectedHead = new Side(1, true, Cell.OPEN, Cell.BLOCKED, false, 1);
        assertEquals(ShoreStepPolicy.NO_SIDE, ShoreStepPolicy.choose(List.of(protectedFeet, protectedHead)));
    }

    @Test
    void hazardNearRulesOutTheSide() {
        Side flooding = new Side(0, true, Cell.MINEABLE, Cell.MINEABLE, true, 1);
        assertEquals(3, ShoreStepPolicy.choose(List.of(flooding, rock(3, 30))));
    }

    @Test
    void unknownDepthRanksLast() {
        assertEquals(1, ShoreStepPolicy.choose(List.of(rock(0, -1), rock(1, 40))));
        assertEquals(0, ShoreStepPolicy.choose(List.of(rock(0, -1))));
    }

    @Test
    void noSidesOrNullGivesNoSide() {
        assertEquals(ShoreStepPolicy.NO_SIDE, ShoreStepPolicy.choose(List.of()));
        assertEquals(ShoreStepPolicy.NO_SIDE, ShoreStepPolicy.choose(null));
    }

    @Test
    void neverMoreThanTwoBlocks() {
        assertEquals(2, ShoreStepPolicy.MAX_MINED_BLOCKS);
        assertEquals(0, ShoreStepPolicy.blocksToMine(new Side(0, true, Cell.OPEN, Cell.OPEN, false, 3)));
    }
}
