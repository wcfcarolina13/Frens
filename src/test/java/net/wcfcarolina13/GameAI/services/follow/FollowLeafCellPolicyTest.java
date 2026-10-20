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
}
