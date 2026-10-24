package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.PathFinding.unified.PlanStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SunsetSegmentPolicyTest {

    private static final int AX = 200;
    private static final int AY = 64;
    private static final int AZ = 0;

    /** A straight path along +X from x=1 to x=len (the bot stands at 0,64,0). */
    private static int choose(int len, PlanStatus status) {
        int[] xs = new int[len];
        int[] ys = new int[len];
        int[] zs = new int[len];
        for (int i = 0; i < len; i++) {
            xs[i] = i + 1;
            ys[i] = 64;
        }
        return SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ, xs, ys, zs, status);
    }

    @Test
    void partialEndingJustOutsideRefreshRadiusIsProgress() {
        assertEquals(6, choose(7, PlanStatus.PARTIAL));
    }

    @Test
    void partialEndingOnOrInsideRefreshRadiusIsNotProgress() {
        // A goal at 6 blocks is within reach on the next tick and would reprobe every tick.
        assertEquals(-1, choose(6, PlanStatus.PARTIAL));
        assertEquals(-1, choose(5, PlanStatus.PARTIAL));
    }

    @Test
    void acceptedPartialNeverLiesWithinRefreshRadius() {
        // Every partial goal the policy accepts must be outside the reach the service reprobes at.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -9; dz <= 9; dz++) {
                    int[] xs = {dx};
                    int[] ys = {64 + dy};
                    int[] zs = {dz};
                    int idx = SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ, xs, ys, zs, PlanStatus.PARTIAL);
                    if (idx >= 0) {
                        double sq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        assertTrue(sq > SunsetSegmentPolicy.SEGMENT_REFRESH_REACH_SQ,
                                "accepted partial at " + dx + "," + dy + "," + dz);
                        assertFalse(SunsetSegmentPolicy.shouldReprobe(true,
                                sq <= SunsetSegmentPolicy.SEGMENT_REFRESH_REACH_SQ, 0L, 40L, false));
                    }
                }
            }
        }
    }

    @Test
    void fullAndOldFinderPathsStillNeedEightBlocks() {
        assertEquals(-1, choose(7, PlanStatus.FULL));
        assertEquals(-1, choose(7, null));
        assertEquals(7, choose(8, PlanStatus.FULL));
        assertEquals(7, choose(8, null));
    }

    @Test
    void longPathStopsAtMaxSegmentProgress() {
        assertEquals(23, choose(60, PlanStatus.PARTIAL));
        assertEquals(23, choose(60, PlanStatus.FULL));
    }

    @Test
    void shortPathEndingOnAnchorIsAccepted() {
        int[] xs = {0, 1, 2};
        int[] ys = {64, 64, 64};
        int[] zs = {0, 0, 0};
        assertEquals(2, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, 2, 64, 0, xs, ys, zs, PlanStatus.FULL));
        assertEquals(2, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, 2, 64, 0, xs, ys, zs, null));
    }

    @Test
    void emptyOrStartOnlyPathHasNoGoal() {
        assertEquals(-1, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ,
                new int[0], new int[0], new int[0], PlanStatus.NO_PATH));
        assertEquals(-1, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ,
                new int[]{0}, new int[]{64}, new int[]{0}, PlanStatus.PARTIAL));
        assertEquals(-1, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ,
                null, null, null, PlanStatus.PARTIAL));
    }

    @Test
    void startNodeInPathIsSkipped() {
        int[] xs = {0, 3, 7};
        int[] ys = {64, 64, 64};
        int[] zs = {0, 0, 0};
        assertEquals(2, SunsetSegmentPolicy.chooseSegmentGoal(0, 64, 0, AX, AY, AZ, xs, ys, zs, PlanStatus.PARTIAL));
    }

    @Test
    void reprobeWithoutFailureKeepsTheOldTriggers() {
        assertTrue(SunsetSegmentPolicy.shouldReprobe(false, false, 10L, 40L, false));
        assertTrue(SunsetSegmentPolicy.shouldReprobe(true, true, 10L, 40L, false));
        assertTrue(SunsetSegmentPolicy.shouldReprobe(true, false, 40L, 40L, false));
        assertFalse(SunsetSegmentPolicy.shouldReprobe(true, false, 39L, 40L, false));
    }

    @Test
    void reprobeAfterFailureWaitsForTheReplanTickEvenWithinReach() {
        assertFalse(SunsetSegmentPolicy.shouldReprobe(true, true, 10L, 40L, true));
        assertFalse(SunsetSegmentPolicy.shouldReprobe(false, false, 10L, 40L, true));
        assertTrue(SunsetSegmentPolicy.shouldReprobe(true, true, 40L, 40L, true));
        assertTrue(SunsetSegmentPolicy.shouldReprobe(false, false, 41L, 40L, true));
    }
}
