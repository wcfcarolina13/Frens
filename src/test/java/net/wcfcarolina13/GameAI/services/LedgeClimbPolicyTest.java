package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.LedgeClimbPolicy.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgeClimbPolicyTest {

    @Test
    void finding49PocketHasRoomToRise() {
        // Water at y=102, ledge top 103.0, stone ceiling at 105 over the pocket column.
        assertTrue(LedgeClimbPolicy.canRiseOnto(102.9, 103.0, 105.0));
        assertTrue(LedgeClimbPolicy.canRiseOnto(102.66, 103.0, 105.0));
        assertTrue(LedgeClimbPolicy.canRiseOnto(102.9, 103.0, 105.0, 1.8));
    }

    @Test
    void lowCeilingStopsTheRise() {
        assertFalse(LedgeClimbPolicy.canRiseOnto(102.9, 103.0, 104.5));
        assertFalse(LedgeClimbPolicy.canRiseOnto(102.9, 103.0, 104.79));
    }

    @Test
    void alreadyAboveTheLedgeOnlyNeedsCurrentHeadroom() {
        assertTrue(LedgeClimbPolicy.canRiseOnto(103.1, 103.0, 105.0));
        assertFalse(LedgeClimbPolicy.canRiseOnto(103.5, 103.0, 105.0));
    }

    @Test
    void badInputCannotRise() {
        assertFalse(LedgeClimbPolicy.canRiseOnto(Double.NaN, 103.0, 105.0));
        assertFalse(LedgeClimbPolicy.canRiseOnto(102.9, 103.0, 105.0, 0.0));
    }

    @Test
    void standingOnTheStandIsDone() {
        assertEquals(Step.DONE, LedgeClimbPolicy.next(103.0, 103.0, true, true, false, 100, 3000));
        // Done wins even at the deadline.
        assertEquals(Step.DONE, LedgeClimbPolicy.next(103.0, 103.0, true, true, true, 5000, 3000));
    }

    @Test
    void onGroundElsewhereKeepsClimbing() {
        // On the pool floor or a different cell: not done.
        assertEquals(Step.HOLD_JUMP_FORWARD, LedgeClimbPolicy.next(101.0, 103.0, true, false, false, 100, 3000));
    }

    @Test
    void pressedAgainstTheLipBelowTheTopBoosts() {
        assertEquals(Step.BOOST, LedgeClimbPolicy.next(102.8, 103.0, false, false, true, 200, 3000));
    }

    @Test
    void collisionAboveTheTopDoesNotBoost() {
        assertEquals(Step.HOLD_JUMP_FORWARD, LedgeClimbPolicy.next(103.05, 103.0, false, false, true, 200, 3000));
    }

    @Test
    void floatingFreeHoldsJumpAndForward() {
        assertEquals(Step.HOLD_JUMP_FORWARD, LedgeClimbPolicy.next(102.7, 103.0, false, false, false, 0, 3000));
    }

    @Test
    void timeoutGivesUp() {
        assertEquals(Step.GIVE_UP, LedgeClimbPolicy.next(102.7, 103.0, false, false, true, 3000, 3000));
        assertEquals(Step.GIVE_UP, LedgeClimbPolicy.next(103.0, 103.0, true, false, false, 3500, 3000));
    }
}
