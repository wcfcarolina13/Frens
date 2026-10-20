package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowWaypointCompressionPolicyTest {

    /** Kept interior indices for a path given as {x, y, z} triples. */
    private static List<Integer> kept(int[][] path) {
        int n = path.length;
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = path[i][0];
            ys[i] = path[i][1];
            zs[i] = path[i][2];
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (FollowWaypointCompressionPolicy.mustKeep(xs, ys, zs, i)) {
                out.add(i);
            }
        }
        return out;
    }

    @Test
    void straightLevelPathKeepsNothingExtra() {
        assertEquals(List.of(), kept(new int[][] {{0, 64, 0}, {1, 64, 0}, {2, 64, 0}, {3, 64, 0}}));
    }

    @Test
    void lShapedPathKeepsTheCorner() {
        assertEquals(List.of(2), kept(new int[][] {{0, 64, 0}, {1, 64, 0}, {2, 64, 0}, {2, 64, 1}, {2, 64, 2}}));
    }

    @Test
    void stepDownThenSidewaysKeepsTheLanding() {
        // Start, step east and down, then turn south: the landing is the corner.
        assertEquals(List.of(1), kept(new int[][] {{0, 64, 0}, {1, 63, 0}, {1, 63, 1}, {1, 63, 2}}));
    }

    @Test
    void stepDownOnAStraightKeepsTheNodeBeforeIt() {
        assertEquals(List.of(1), kept(new int[][] {{0, 64, 0}, {1, 64, 0}, {2, 63, 0}, {3, 63, 0}}));
    }

    @Test
    void stepUpKeepsTheNodeBeforeIt() {
        assertEquals(List.of(2), kept(new int[][] {{0, 64, 0}, {1, 64, 0}, {2, 64, 0}, {3, 65, 0}, {4, 65, 0}}));
    }

    @Test
    void endpointsAreNeverReported() {
        int[] xs = {0, 1, 1};
        int[] ys = {64, 64, 64};
        int[] zs = {0, 0, 1};
        assertFalse(FollowWaypointCompressionPolicy.mustKeep(xs, ys, zs, 0));
        assertFalse(FollowWaypointCompressionPolicy.mustKeep(xs, ys, zs, 2));
    }

    @Test
    void staircaseDownsampleKeepsTheFirstCornerAndItsNeighbours() {
        // Straight staircase: every interior node is the node before a Y change.
        int[][] raw = new int[20][];
        for (int i = 0; i < 20; i++) {
            raw[i] = new int[] {i, 64 + i, 0};
        }
        List<Integer> keptRaw = kept(raw);
        assertEquals(1, keptRaw.get(0)); // raw node 1 is the first corner
        // Points = kept interior nodes plus the end node; the first corner is point 0.
        int size = keptRaw.size() + 1;
        int[] idx = FollowWaypointCompressionPolicy.selectIndices(size, 6, 0, 0);
        assertEquals(6, idx.length);
        assertEquals(0, idx[0]);
        assertEquals(1, idx[1]); // plain even sampling jumped from point 0 to point 4
        assertEquals(size - 1, idx[idx.length - 1]);
    }

    @Test
    void downsampleKeepsBothNeighboursOfALaterCorner() {
        int[] idx = FollowWaypointCompressionPolicy.selectIndices(20, 7, 0, 5);
        List<Integer> list = new ArrayList<>();
        for (int i : idx) {
            list.add(i);
        }
        assertEquals(7, idx.length);
        assertEquals(0, idx[0]);
        assertTrue(list.containsAll(List.of(4, 5, 6)));
        assertEquals(19, idx[idx.length - 1]);
    }

    @Test
    void downsampleWithoutACornerStillSpansToTheEnd() {
        int[] idx = FollowWaypointCompressionPolicy.selectIndices(19, 6, 0, -1);
        assertEquals(6, idx.length);
        assertEquals(0, idx[0]);
        assertEquals(18, idx[5]);
        for (int i = 1; i < idx.length; i++) {
            assertTrue(idx[i] > idx[i - 1]);
        }
    }

    @Test
    void downsampleKeepsThePinnedLeadIn() {
        // Two pinned, then the start, the corner and the point before it fill the budget.
        assertArrayEquals(new int[] {0, 1, 2, 7, 8, 19},
                FollowWaypointCompressionPolicy.selectIndices(20, 6, 2, 8));
    }

    @Test
    void shortListsAreUntouched() {
        assertEquals(4, FollowWaypointCompressionPolicy.selectIndices(4, 6, 0, 1).length);
    }
}
