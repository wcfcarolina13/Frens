package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowStepTransitionPolicyTest {

    @Test
    void cellChoiceFollowsStepDirection() {
        assertEquals(FollowStepTransitionPolicy.HeadroomCell.FROM_UP2, FollowStepTransitionPolicy.headroomCell(1));
        assertEquals(FollowStepTransitionPolicy.HeadroomCell.TO_UP2, FollowStepTransitionPolicy.headroomCell(-1));
        assertEquals(FollowStepTransitionPolicy.HeadroomCell.NONE, FollowStepTransitionPolicy.headroomCell(0));
    }

    @Test
    void stepDownUnderLeavesIsRejected() {
        // Finding 47: Fol at 61,77,266 stepping down toward 60,76,267; to.up(2) = 60,78,267 = oak_leaves.
        assertFalse(FollowStepTransitionPolicy.allows(-1, true, false));
    }

    @Test
    void stepDownWithClearHeadroomIsAllowed() {
        assertTrue(FollowStepTransitionPolicy.allows(-1, true, true));
    }

    @Test
    void stepDownAtSnapshotTopEdgeIsAllowed() {
        // Out of bounds is unknown, not blocked: keeps step-downs the planner allowed before.
        assertTrue(FollowStepTransitionPolicy.allows(-1, false, false));
    }

    @Test
    void stepUpNeedsInBoundsPassableLaunchHeadroom() {
        assertTrue(FollowStepTransitionPolicy.allows(1, true, true));
        assertFalse(FollowStepTransitionPolicy.allows(1, true, false));
        // Unchanged rule: out of bounds blocks a step up.
        assertFalse(FollowStepTransitionPolicy.allows(1, false, false));
        assertFalse(FollowStepTransitionPolicy.allows(1, false, true));
    }

    @Test
    void levelStepIsAlwaysAllowed() {
        assertTrue(FollowStepTransitionPolicy.allows(0, true, false));
        assertTrue(FollowStepTransitionPolicy.allows(0, false, false));
        assertTrue(FollowStepTransitionPolicy.allows(0, true, true));
    }
}
