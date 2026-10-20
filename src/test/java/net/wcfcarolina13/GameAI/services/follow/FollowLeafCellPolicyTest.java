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
        assertArrayEquals(new int[] {0, 1, 2, 3}, heights);
        // Finding 56: bot feet at 73, grass support at 74, leaves at 75 and 76.
        assertEquals(75, 73 + heights[2]);
        assertEquals(76, 73 + heights[3]);
    }

    @Test
    void lowerLeafIsChosenBeforeRaisedHeadLeaf() {
        int[] heights = FollowLeafCellPolicy.heights(true);
        assertEquals(2, java.util.Arrays.stream(heights).filter(y -> y >= 2).findFirst().orElseThrow());
    }
}
