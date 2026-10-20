package net.wcfcarolina13.GameAI;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the door-plan movement rules extracted after the 2026-08-28 field session, where a
 * bot following the commander down an indoor staircase pinned itself at a double door for ~5s.
 *
 * <p>Root cause (one cause, two symptoms): while a plan was stepping through an open door, the
 * bot force-jumped on EVERY grounded tick. Under a low ceiling the jump made it perpetually
 * airborne at the 2-high doorway (head collision → no horizontal entry), and the vertical
 * bouncing flipped its BlockPos Y between 65 and 66 each jump, resetting the full-BlockPos
 * stuck counter — so the 24-tick stuck-abort never fired and only the plan TTL (then the
 * wolf-teleport rescue) recovered it.
 */
class FollowDoorPlanRulesTest {

    @Test
    void stuckTrackingIgnoresVerticalBouncing() {
        BlockPos ground = new BlockPos(252, 65, 1284);
        BlockPos midJump = new BlockPos(252, 66, 1284);
        assertTrue(FollowDoorRules.samePlanColumn(ground, midJump),
                "a jump in place must still count as stuck — Y is not progress");
        assertTrue(FollowDoorRules.samePlanColumn(ground, ground));
        assertFalse(FollowDoorRules.samePlanColumn(ground, new BlockPos(253, 65, 1284)),
                "horizontal movement is progress");
        assertFalse(FollowDoorRules.samePlanColumn(ground, new BlockPos(252, 65, 1285)));
        assertFalse(FollowDoorRules.samePlanColumn(null, ground),
                "no previous sample means not stuck");
    }

    @Test
    void doorJumpFiresOnlyOnGenuineStagnationNotEveryTick() {
        // Tick 0 of a healthy step-through: no jump. The old behavior (jump whenever
        // stepping && door open && on ground) is exactly what pinned the bot under a
        // low ceiling, so "stepping" alone must never be a jump reason.
        assertFalse(FollowDoorRules.shouldForceDoorJump(0),
                "a progressing bot must not jump into a 2-high doorway");
        assertFalse(FollowDoorRules.shouldForceDoorJump(7),
                "below the stagnation threshold, keep walking");
        // At the threshold the bot has provably stalled on something at the threshold
        // (door leaf collision, pressure plate, overhanging stair) — now the jump helps.
        assertTrue(FollowDoorRules.shouldForceDoorJump(8));
        assertTrue(FollowDoorRules.shouldForceDoorJump(20));
    }

    private static final double EPS = 1e-9;
    private static final double TOL = FollowDoorRules.DOORWAY_ALIGN_TOLERANCE;

    @Test
    void noForcedJumpUnderABlockedLintel() {
        // Sandbox S9 1.1.271: oak door under a stone lintel; the jump at stuck>=8 only
        // pinned the bot airborne against the lintel.
        assertFalse(FollowDoorRules.shouldForceDoorJump(30, true));
        assertFalse(FollowDoorRules.shouldForceDoorJump(8, true));
    }

    @Test
    void jumpStillAllowedWithHeadroom() {
        assertFalse(FollowDoorRules.shouldForceDoorJump(7, false));
        assertTrue(FollowDoorRules.shouldForceDoorJump(8, false));
        assertTrue(FollowDoorRules.shouldForceDoorJump(30, false));
    }

    @Test
    void offLaneBotLinesUpSidewaysBeforeEnteringXAxisDoor() {
        // S9 geometry: door cell 50,_,50, step 49,_,50, bot pressed at x=51.30.
        FollowDoorRules.DoorwayAim aim = FollowDoorRules.doorwayAim(
                51.30, 50.80, 50.5, 50.5, 49.5, 50.5, TOL);
        assertTrue(aim.liningUp());
        assertEquals(51.30, aim.aimX(), EPS, "sideways only: stay level with the bot along the passage");
        assertEquals(50.5, aim.aimZ(), EPS, "onto the door axis");
        assertEquals(0.30, aim.lateralOffset(), EPS);
    }

    @Test
    void alignedBotAimsAtTheStepCell() {
        FollowDoorRules.DoorwayAim aim = FollowDoorRules.doorwayAim(
                51.30, 50.55, 50.5, 50.5, 49.5, 50.5, TOL);
        assertFalse(aim.liningUp());
        assertEquals(49.5, aim.aimX(), EPS);
        assertEquals(50.5, aim.aimZ(), EPS);
    }

    @Test
    void zAxisDoorLinesUpOnX() {
        // door 10,_,20, step 10,_,21 (passage along +Z); bot 0.3 off in X, still short of the door.
        FollowDoorRules.DoorwayAim aim = FollowDoorRules.doorwayAim(
                10.80, 19.70, 10.5, 20.5, 10.5, 21.5, TOL);
        assertTrue(aim.liningUp());
        assertEquals(10.5, aim.aimX(), EPS);
        assertEquals(19.70, aim.aimZ(), EPS);

        FollowDoorRules.DoorwayAim aligned = FollowDoorRules.doorwayAim(
                10.55, 19.70, 10.5, 20.5, 10.5, 21.5, TOL);
        assertFalse(aligned.liningUp());
        assertEquals(10.5, aligned.aimX(), EPS);
        assertEquals(21.5, aligned.aimZ(), EPS);
    }

    @Test
    void botAlreadyInTheDoorCellKeepsGoing() {
        // Centre inside the door cell: sideways correction there only scrapes the frame.
        FollowDoorRules.DoorwayAim aim = FollowDoorRules.doorwayAim(
                50.60, 50.80, 50.5, 50.5, 49.5, 50.5, TOL);
        assertFalse(aim.liningUp());
        assertEquals(49.5, aim.aimX(), EPS);
    }

    @Test
    void openDoorSlabShiftsTheLaneToTheClearGap() {
        // Open door slab at local [0, 3/16]: clear gap [3/16, 1], centre 19/32.
        assertEquals(3.0 / 32.0, FollowDoorRules.openDoorLaneOffset(0.0, 0.1875), EPS);
        assertEquals(-3.0 / 32.0, FollowDoorRules.openDoorLaneOffset(0.8125, 1.0), EPS);
        // Closed door across the passage spans the cell laterally: no shift.
        assertEquals(0.0, FollowDoorRules.openDoorLaneOffset(0.0, 1.0), EPS);
        assertEquals(0.0, FollowDoorRules.openDoorLaneOffset(0.0, 0.0), EPS);

        // A bot on the cell centre line is within tolerance of 50.5 but 0.094 off the gap
        // centre, so with the slab known it still lines up.
        FollowDoorRules.DoorwayAim aim = FollowDoorRules.doorwayAim(
                51.30, 50.50, 50.5, 50.5, 49.5, 50.5, 3.0 / 32.0, TOL);
        assertTrue(aim.liningUp());
        assertEquals(50.5 + 3.0 / 32.0, aim.aimZ(), EPS);
        FollowDoorRules.DoorwayAim aligned = FollowDoorRules.doorwayAim(
                51.30, 50.60, 50.5, 50.5, 49.5, 50.5, 3.0 / 32.0, TOL);
        assertFalse(aligned.liningUp());
        assertEquals(49.5, aligned.aimX(), EPS);
        assertEquals(50.5 + 3.0 / 32.0, aligned.aimZ(), EPS, "walk in along the gap centre, not the cell centre");
    }

    @Test
    void stuckCloseBehindWaitsForOtherPlayersAtTheDoor() {
        assertFalse(FollowDoorRules.mayCloseStuckNearDoor(1.0), "a bot in the next cell");
        assertFalse(FollowDoorRules.mayCloseStuckNearDoor(4.0), "two blocks away still counts");
        assertTrue(FollowDoorRules.mayCloseStuckNearDoor(5.0));
        assertTrue(FollowDoorRules.mayCloseStuckNearDoor(Double.POSITIVE_INFINITY), "nobody else around");
    }

    // ── stuck count: lining up and waiting for the door (fix wave after 1.1.277) ──

    @Test
    void movingToAnotherColumnResetsTheStuckCount() {
        assertEquals(0, FollowDoorRules.nextStuckTicks(17, false, false));
        assertEquals(0, FollowDoorRules.nextStuckTicks(17, false, true));
    }

    @Test
    void aStallInTheSameColumnCounts() {
        assertEquals(6, FollowDoorRules.nextStuckTicks(5, true, false));
    }

    @Test
    void slidingOntoTheLaneInsideOneBlockDoesNotSpendTheAbortBudget() {
        int stuck = 3;
        // 30 ticks of damped sideways correction inside one block, each closer than the last.
        double offset = 0.45D;
        double prev = Double.NaN;
        for (int tick = 0; tick < 30; tick++) {
            boolean progressed = FollowDoorRules.liningUpProgressed(prev, offset);
            stuck = FollowDoorRules.nextStuckTicks(stuck, true, progressed);
            prev = offset;
            offset *= 0.9D;
        }
        // Only the first tick (no previous reading) counted.
        assertEquals(4, stuck);
        assertTrue(stuck < 24, "the 24-tick abort must not fire while lining up makes progress");
    }

    @Test
    void liningUpThatStopsClosingCounts() {
        assertFalse(FollowDoorRules.liningUpProgressed(0.30D, 0.30D), "no change is no progress");
        assertFalse(FollowDoorRules.liningUpProgressed(0.30D, 0.32D), "drifting away is no progress");
        assertFalse(FollowDoorRules.liningUpProgressed(0.30D, 0.2995D), "within epsilon is no progress");
        assertFalse(FollowDoorRules.liningUpProgressed(Double.NaN, 0.1D), "no previous reading");
        assertTrue(FollowDoorRules.liningUpProgressed(0.30D, 0.25D));
    }

    // ── close-behind on every path ──

    @Test
    void closeBehindClosesWithNobodyAtTheDoor() {
        assertEquals(FollowDoorRules.CloseBehind.CLOSE,
                FollowDoorRules.closeBehind(Double.POSITIVE_INFINITY, 0, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT));
        assertEquals(FollowDoorRules.CloseBehind.CLOSE,
                FollowDoorRules.closeBehind(4.01D, 20, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT));
    }

    @Test
    void closeBehindWaitsForATrailingBotInTheDoorCell() {
        // Trailing bot standing in the door cell: distance 0, even though the closer crossed the plane.
        assertEquals(FollowDoorRules.CloseBehind.WAIT,
                FollowDoorRules.closeBehind(0.0D, 1, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT));
        assertEquals(FollowDoorRules.CloseBehind.WAIT,
                FollowDoorRules.closeBehind(4.0D, 19, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT));
    }

    @Test
    void closeBehindLeavesTheDoorOpenAfterTheCap() {
        assertEquals(20, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT);
        assertEquals(FollowDoorRules.CloseBehind.LEAVE_OPEN,
                FollowDoorRules.closeBehind(1.0D, 20, FollowDoorRules.CLOSE_BEHIND_MAX_ATTEMPT));
    }
}
