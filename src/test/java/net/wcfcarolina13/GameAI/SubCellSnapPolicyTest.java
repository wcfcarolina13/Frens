package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubCellSnapPolicyTest {

    private static final double HALF = 0.3;
    private static final double D = 1.0E-9;

    /** Player-sized box (0.6 wide, 1.8 tall) standing at y=64, centred on (x, z). */
    private static double[] bot(double x, double z) {
        return SubCellSnapPolicy.box(x - HALF, 64.0, z - HALF, x + HALF, 65.8, z + HALF);
    }

    /** A full block at (x, y, z). */
    private static double[] block(int x, int y, int z) {
        return SubCellSnapPolicy.box(x, y, z, x + 1, y + 1, z + 1);
    }

    /** Stone east of cell (5, 64, 5), both feet and head height. */
    private static final List<double[]> WALL_EAST = List.of(block(6, 64, 5), block(6, 65, 5));

    @Test
    void clampAxisStopsFlushWithAWallAhead() {
        assertEquals(5.7, SubCellSnapPolicy.clampAxis(5.5, 5.95, HALF, 6.0, 7.0), D);
    }

    @Test
    void clampAxisStopsFlushWithAWallBehindWhenMovingWest() {
        assertEquals(5.3, SubCellSnapPolicy.clampAxis(5.5, 5.05, HALF, 4.0, 5.0), D);
    }

    @Test
    void clampAxisLeavesAStepThatDoesNotReachTheWall() {
        assertEquals(5.65, SubCellSnapPolicy.clampAxis(5.5, 5.65, HALF, 6.0, 7.0), D);
    }

    @Test
    void clampAxisRefusesWhenAlreadyFlushOrInside() {
        assertTrue(Double.isNaN(SubCellSnapPolicy.clampAxis(5.7, 5.9, HALF, 6.0, 7.0)));
        assertTrue(Double.isNaN(SubCellSnapPolicy.clampAxis(5.8, 5.9, HALF, 6.0, 7.0)));
    }

    @Test
    void noSolidsLeavesTheStepUnchanged() {
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.5, 5.5), bot(5.95, 5.5), List.of()));
        assertArrayEquals(new double[]{0.45, 0.0},
                SubCellSnapPolicy.resolve(bot(5.5, 5.5), 0.45, 0.0, List.of()), D);
    }

    @Test
    void stepIntoTheWallIsRefusedAndClampedFlush() {
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.5, 5.5), bot(5.95, 5.5), WALL_EAST));
        double[] r = SubCellSnapPolicy.resolve(bot(5.5, 5.5), 0.45, 0.0, WALL_EAST);
        assertArrayEquals(new double[]{0.2, 0.0}, r, D);
        assertEquals(0.0, SubCellSnapPolicy.penetration(SubCellSnapPolicy.offset(bot(5.5, 5.5), r[0], r[1]), WALL_EAST), D);
    }

    @Test
    void fromFivePointSixToFivePointNineIsRefusedOrClampedToFivePointSeven() {
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.6, 5.5), bot(5.9, 5.5), WALL_EAST));
        double[] r = SubCellSnapPolicy.resolve(bot(5.6, 5.5), 0.3, 0.0, WALL_EAST);
        assertEquals(5.7, 5.6 + r[0], D);
    }

    @Test
    void alreadyOverlappedBotMayStepOut() {
        // At 5.8 the hitbox is 0.1 inside the wall; stepping back to 5.6 clears it.
        assertTrue(SubCellSnapPolicy.penetration(bot(5.8, 5.5), WALL_EAST) > 0);
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.8, 5.5), bot(5.6, 5.5), WALL_EAST));
    }

    @Test
    void alreadyOverlappedBotMayReduceOverlapWithoutClearingIt() {
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.9, 5.5), bot(5.8, 5.5), WALL_EAST));
    }

    @Test
    void alreadyOverlappedBotMayNotGoDeeper() {
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.8, 5.5), bot(5.9, 5.5), WALL_EAST));
        assertNull(SubCellSnapPolicy.resolve(bot(5.8, 5.5), 0.1, 0.0, WALL_EAST));
    }

    @Test
    void flushBotPushingIntoTheWallDoesNotMove() {
        assertNull(SubCellSnapPolicy.resolve(bot(5.7, 5.5), 0.2, 0.0, WALL_EAST));
    }

    @Test
    void flushBotMaySlideAlongTheWall() {
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.7, 5.5), bot(5.7, 5.2), WALL_EAST));
    }

    @Test
    void diagonalIntoACornerBlockIsRefusedThenSlidesAlongTheOpenSide() {
        List<double[]> corner = List.of(block(6, 64, 6), block(6, 65, 6));
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.5, 5.5), bot(5.9, 5.9), corner));
        double[] r = SubCellSnapPolicy.resolve(bot(5.5, 5.5), 0.4, 0.4, corner);
        double[] end = SubCellSnapPolicy.offset(bot(5.5, 5.5), r[0], r[1]);
        assertEquals(0.0, SubCellSnapPolicy.penetration(end, corner), D);
        // X goes first and is open (the block is further south), so Z is shortened to flush.
        assertArrayEquals(new double[]{0.4, 0.2}, r, D);
    }

    @Test
    void doubleStepTowardTheWallStopsAtTheFirstStep() {
        double[] first = SubCellSnapPolicy.resolve(bot(5.5, 5.5), 0.45, 0.0, WALL_EAST);
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.5, 5.5), bot(5.95, 5.5), WALL_EAST));
        assertEquals(5.7, 5.5 + first[0], D);
        // The second 0.45 step from the flush position (to 6.15) goes nowhere.
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.7, 5.5), bot(6.15, 5.5), WALL_EAST));
        assertNull(SubCellSnapPolicy.resolve(bot(5.7, 5.5), 0.45, 0.0, WALL_EAST));
        // Unclamped, 5.95 -> 6.40 would be deeper still.
        assertFalse(SubCellSnapPolicy.allowSameCellMove(bot(5.95, 5.5), bot(6.40, 5.5), WALL_EAST));
    }

    @Test
    void floorAndCeilingContactIsNotPenetration() {
        List<double[]> floorAndCeiling = List.of(block(5, 63, 5), block(6, 63, 5), block(5, 66, 5), block(6, 66, 5));
        assertEquals(0.0, SubCellSnapPolicy.penetration(bot(5.9, 5.5), List.of(block(5, 63, 5), block(6, 63, 5))), D);
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.5, 5.5), bot(5.95, 5.5), floorAndCeiling));
    }

    @Test
    void westwardStepIsClampedWithoutBeingTurnedAround() {
        List<double[]> wallWest = List.of(block(4, 64, 5), block(4, 65, 5));
        double[] r = SubCellSnapPolicy.resolve(bot(5.5, 5.5), -0.45, 0.0, wallWest);
        assertArrayEquals(new double[]{-0.2, 0.0}, r, D);
    }

    /** Stone east of the bot along z = 4..6, feet and head rows: one straight wall of six blocks. */
    private static final List<double[]> LONG_WALL_EAST = List.of(
            block(6, 64, 4), block(6, 65, 4), block(6, 64, 5), block(6, 65, 5), block(6, 64, 6), block(6, 65, 6));

    @Test
    void botAlreadyInASideWallMaySlideAlongIt() {
        // 0.1 inside the wall, moving north along it: the overlap with the wall stays the same.
        double before = SubCellSnapPolicy.penetration(bot(5.8, 5.5), LONG_WALL_EAST);
        assertTrue(before > 0);
        assertEquals(before, SubCellSnapPolicy.penetration(bot(5.8, 5.2), LONG_WALL_EAST), D);
        assertTrue(SubCellSnapPolicy.allowSameCellMove(bot(5.8, 5.5), bot(5.8, 5.2), LONG_WALL_EAST));
        assertArrayEquals(new double[]{0.0, -0.3}, SubCellSnapPolicy.resolve(bot(5.8, 5.5), 0.0, -0.3, LONG_WALL_EAST), D);
        // Southward too, where the box crosses from block z=5 into block z=6 of the same wall.
        assertArrayEquals(new double[]{0.0, 0.3}, SubCellSnapPolicy.resolve(bot(5.8, 5.5), 0.0, 0.3, LONG_WALL_EAST), D);
    }

    @Test
    void botInASideWallSlidesButDoesNotGoDeeperOnADiagonal() {
        // Into the wall and along it: the deeper part is dropped, the slide is kept.
        assertArrayEquals(new double[]{0.0, -0.2}, SubCellSnapPolicy.resolve(bot(5.8, 5.5), 0.1, -0.2, LONG_WALL_EAST), D);
        assertNull(SubCellSnapPolicy.resolve(bot(5.8, 5.5), 0.1, 0.0, LONG_WALL_EAST));
    }

    @Test
    void steppingOutOfOneWallIntoAnotherIsRefusedEvenWhenTheTotalGoesDown() {
        // East wall at z = 5 and a north wall block at (5, z = 4). The bot is 0.1 into the east
        // wall; the diagonal step leaves it but clips 0.03 into the north wall.
        List<double[]> walls = List.of(block(6, 64, 5), block(6, 65, 5), block(5, 64, 4), block(5, 65, 4));
        double[] from = bot(5.8, 5.32);
        double[] to = bot(5.65, 5.27);
        assertTrue(SubCellSnapPolicy.penetration(to, walls) < SubCellSnapPolicy.penetration(from, walls));
        assertTrue(SubCellSnapPolicy.penetration(to, walls) > 0);
        assertFalse(SubCellSnapPolicy.allowSameCellMove(from, to, walls));
        double[] r = SubCellSnapPolicy.resolve(from, -0.15, -0.05, walls);
        // Whatever comes back adds nothing to the north wall.
        if (r != null) {
            double[] end = SubCellSnapPolicy.offset(from, r[0], r[1]);
            List<double[]> north = List.of(block(5, 64, 4), block(5, 65, 4));
            assertEquals(0.0, SubCellSnapPolicy.penetration(end, north), D);
            assertArrayEquals(new double[]{-0.15, -0.02}, r, D);
        }
    }

    @Test
    void mergeJoinsTheBlocksOfOneStraightWallButNotACorner() {
        assertEquals(1, SubCellSnapPolicy.mergeCoplanar(LONG_WALL_EAST).size());
        assertArrayEquals(SubCellSnapPolicy.box(6, 64, 4, 7, 66, 7), SubCellSnapPolicy.mergeCoplanar(LONG_WALL_EAST).get(0), D);
        List<double[]> corner = List.of(block(6, 64, 5), block(5, 64, 4));
        assertEquals(2, SubCellSnapPolicy.mergeCoplanar(corner).size());
        List<double[]> gap = List.of(block(6, 64, 4), block(6, 64, 6));
        assertEquals(2, SubCellSnapPolicy.mergeCoplanar(gap).size());
    }
}
