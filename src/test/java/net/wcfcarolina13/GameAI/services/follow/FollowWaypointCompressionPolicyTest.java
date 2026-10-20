package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
