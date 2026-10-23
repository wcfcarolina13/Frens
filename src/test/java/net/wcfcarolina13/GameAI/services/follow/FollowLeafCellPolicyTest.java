package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FollowLeafCellPolicyTest {
    @Test
    void levelGroundKeepsBodyHeadAndStepUpClearance() {
        assertArrayEquals(new int[] {0, 1, 2}, FollowLeafCellPolicy.heights(false));
    }

    @Test
    void raisedSupportIncludesDestinationFeetAndHead() {
        int[] heights = FollowLeafCellPolicy.heights(true);
        assertArrayEquals(new int[] {1, 2}, heights);
        // Bot feet and the step's support are at 73; destination feet/head are 74/75.
        assertEquals(74, 73 + heights[0]);
        assertEquals(75, 73 + heights[1]);
    }

    @Test
    void lowerLeafIsChosenBeforeRaisedHeadLeaf() {
        int[] heights = FollowLeafCellPolicy.heights(true);
        assertEquals(1, heights[0]);
        assertEquals(2, heights[1]);
    }

    @Test
    void stepUpChecksTheCellOverTheBotsOwnHead() {
        int[] own = FollowLeafCellPolicy.ownColumnHeights(true);
        assertArrayEquals(new int[] {2}, own);
        // Bot feet at 73, head at 74: the jump needs 75 clear (the 75,75,232 leaf in the field log).
        assertEquals(75, 73 + own[0]);
    }

    @Test
    void levelGroundChecksNothingInTheOwnColumn() {
        assertEquals(0, FollowLeafCellPolicy.ownColumnHeights(false).length);
    }

    @Test
    void ownColumnResultIsNotSharedBetweenCalls() {
        int[] first = FollowLeafCellPolicy.ownColumnHeights(true);
        first[0] = 99;
        assertArrayEquals(new int[] {2}, FollowLeafCellPolicy.ownColumnHeights(true));
    }
}
