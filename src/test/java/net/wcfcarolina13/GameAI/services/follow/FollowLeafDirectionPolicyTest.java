package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FollowLeafDirectionPolicyTest {

    @Test
    void zeroOffsetHasNoCandidates() {
        assertEquals(0, FollowLeafDirectionPolicy.candidates(0, 0).length);
    }

    @Test
    void axisAlignedGoalHasOneCandidate() {
        assertArrayEquals(new int[][] {{1, 0}}, FollowLeafDirectionPolicy.candidates(4, 0));
        assertArrayEquals(new int[][] {{0, -1}}, FollowLeafDirectionPolicy.candidates(0, -3));
    }

    @Test
    void exactDiagonalChecksBothSidesXFirst() {
        assertArrayEquals(new int[][] {{1, 0}, {0, 1}}, FollowLeafDirectionPolicy.candidates(1, 1));
        assertArrayEquals(new int[][] {{-1, 0}, {0, -1}}, FollowLeafDirectionPolicy.candidates(-2, -2));
    }

    @Test
    void largerOffsetComesFirst() {
        assertArrayEquals(new int[][] {{0, 1}, {-1, 0}}, FollowLeafDirectionPolicy.candidates(-1, 3));
        assertArrayEquals(new int[][] {{1, 0}, {0, -1}}, FollowLeafDirectionPolicy.candidates(5, -2));
    }
}
