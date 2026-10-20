package net.wcfcarolina13.GameAI;

import net.minecraft.util.math.BlockPos;

/**
 * Pure movement rules for the follow-mode door plan, extracted from {@code BotEventHandler}
 * after the 2026-08-28 field session so they can be unit-tested without class-loading the
 * (bootstrap-heavy) event handler.
 *
 * <p>Root cause they encode (one cause, two symptoms): while a plan was stepping through an
 * open door the bot force-jumped on EVERY grounded tick. Under a low ceiling the jump made it
 * perpetually airborne at the 2-high doorway (head collision → no horizontal entry), and the
 * bounce flipped its BlockPos Y each jump, resetting the full-BlockPos stuck counter — so the
 * stuck-abort never fired and only the plan TTL (then the wolf-teleport rescue) recovered it.
 */
public final class FollowDoorRules {

    /** Horizontal-stagnation ticks before a doorway jump is warranted. */
    public static final int STUCK_JUMP_TICKS = 8;

    private FollowDoorRules() {
    }

    /**
     * {@link #shouldForceDoorJump(int, boolean)} for a doorway with a clear lintel. The door plan
     * itself calls the two-argument form (and never jumps while lining up); this is the
     * stagnation threshold alone.
     */
    public static boolean shouldForceDoorJump(int horizontalStuckTicks) {
        return shouldForceDoorJump(horizontalStuckTicks, false);
    }

    /**
     * Jump ONLY on genuine horizontal stagnation at the doorway — never merely because a
     * step-through is in progress. The historical jump-while-stepping motivations (the door's
     * 3-pixel leaf collision, pressure plates, overhanging stair thresholds) are all stall
     * scenarios, so jump-on-stagnation covers them a few ticks later. The stuck count excludes
     * ticks spent waiting for the door to open and ticks of sideways lining-up progress
     * ({@link #nextStuckTicks}).
     *
     * <p>Never when the cell above the doorway's head cell (the lintel, {@code doorBase.up(2)})
     * blocks movement. A 1.8-tall bot in a two-high doorway has 0.2 of headroom; a jump there
     * leaves it airborne with its head against the lintel at the threshold, so it can never
     * enter the door column (sandbox S9 1.1.271: a 45 s stall at an oak doorway with a stone lintel).
     */
    public static boolean shouldForceDoorJump(int horizontalStuckTicks, boolean lintelBlocked) {
        return !lintelBlocked && horizontalStuckTicks >= STUCK_JUMP_TICKS;
    }

    /** Lateral offset from the doorway lane within which a stepping bot walks straight in. */
    public static final double DOORWAY_ALIGN_TOLERANCE = 0.08D;

    /**
     * Where a stepping bot should steer this tick.
     *
     * @param aimX            steering target X
     * @param aimZ            steering target Z
     * @param lateralOffset   signed distance of the bot from the doorway lane, across the passage
     * @param liningUp        true while the bot is moving sideways onto the lane before entering
     */
    public record DoorwayAim(double aimX, double aimZ, double lateralOffset, boolean liningUp) {
    }

    /** {@link #doorwayAim(double, double, double, double, double, double, double, double)} on the cell centre line. */
    public static DoorwayAim doorwayAim(double botX, double botZ, double doorX, double doorZ,
                                        double stepX, double stepZ, double tol) {
        return doorwayAim(botX, botZ, doorX, doorZ, stepX, stepZ, 0.0D, tol);
    }

    /**
     * Line up with a doorway before walking through it. A 0.6-wide bot fits an open door's
     * 0.81-wide gap with ~0.1 to spare per side; steering straight at the step cell from off
     * the centre line pressed bots into the frame edge until the stuck-abort fired.
     *
     * <p>All coordinates are block-space doubles; {@code door*} and {@code step*} are the
     * centres of the door cell and the step-through cell. The passage axis is whichever of X/Z
     * separates door and step. {@code laneOffset} shifts the lane across the passage to the
     * middle of the clear gap (see {@link #openDoorLaneOffset}); 0 means the cell centre line.
     *
     * <p>While the bot's centre is still short of the door cell and it is more than
     * {@code tol} off the lane, the aim is the lane point level with the bot (move sideways
     * only). Otherwise the aim is the step cell's along-axis position on the lane.
     */
    public static DoorwayAim doorwayAim(double botX, double botZ, double doorX, double doorZ,
                                        double stepX, double stepZ, double laneOffset, double tol) {
        double ax = stepX - doorX;
        double az = stepZ - doorZ;
        boolean alongX = Math.abs(ax) >= Math.abs(az);
        double dir = Math.signum(alongX ? ax : az);
        if (dir == 0.0D) {
            return new DoorwayAim(stepX, stepZ, 0.0D, false);
        }
        double lane = (alongX ? doorZ : doorX) + laneOffset;
        double botAlong = alongX ? botX : botZ;
        double botLat = alongX ? botZ : botX;
        double offset = botLat - lane;
        double progress = (botAlong - (alongX ? doorX : doorZ)) * dir;
        boolean inOrPastDoorCell = progress > -0.5D;
        boolean liningUp = !inOrPastDoorCell && Math.abs(offset) > tol;
        double aimAlong = liningUp ? botAlong : (alongX ? stepX : stepZ);
        return alongX
                ? new DoorwayAim(aimAlong, lane, offset, liningUp)
                : new DoorwayAim(lane, aimAlong, offset, liningUp);
    }

    /**
     * Offset of the clear gap's centre from the cell centre, across the passage, given the
     * door's collision extent on that axis in block-local coordinates [0, 1]. An open door
     * leaves a 3/16 slab on one side, so the gap centre sits 3/32 off the cell centre, away
     * from the slab. A shape spanning half the cell or more (a closed door across the
     * passage) or none at all gives 0.
     */
    public static double openDoorLaneOffset(double slabMin, double slabMax) {
        if (!(slabMax > slabMin) || slabMax - slabMin >= 0.5D) {
            return 0.0D;
        }
        double lo = Math.max(0.0D, slabMin);
        double hi = Math.min(1.0D, slabMax);
        double gapCentre = (1.0D - hi) >= lo ? (hi + 1.0D) / 2.0D : lo / 2.0D;
        return gapCentre - 0.5D;
    }

    /** Players (bots or the followed player) within this squared distance of a door keep it open. */
    public static final double DOOR_CLOSE_CLEARANCE_SQ = 4.0D;

    /**
     * Close-behind must not shut the door on someone else using it (sandbox S9: a door closed on
     * a following bot two blocks away). Checked on every close path ({@link #closeBehind}).
     *
     * @param nearestOtherPlayerDistSq squared block distance from the door to the nearest other
     *                                 player, or {@link Double#POSITIVE_INFINITY} if none
     */
    public static boolean mayCloseStuckNearDoor(double nearestOtherPlayerDistSq) {
        return nearestOtherPlayerDistSq > DOOR_CLOSE_CLEARANCE_SQ;
    }

    /** Smallest shrink of the lateral offset in one tick that counts as lining-up progress (blocks). */
    public static final double LINE_UP_PROGRESS_EPSILON = 1.0E-3D;

    /**
     * True when this tick's distance from the doorway lane is smaller than last tick's by more
     * than {@link #LINE_UP_PROGRESS_EPSILON}. A missing previous reading (NaN) is not progress.
     */
    public static boolean liningUpProgressed(double prevAbsOffset, double curAbsOffset) {
        return !Double.isNaN(prevAbsOffset) && curAbsOffset < prevAbsOffset - LINE_UP_PROGRESS_EPSILON;
    }

    /**
     * The door plan's stuck count after this tick. Moving to another column resets it. In the same
     * column it grows, except on a tick where the bot got closer to the doorway lane while lining
     * up: sliding sideways inside one block is progress, and counting it spent the 24-tick abort
     * budget before the bot ever reached the door (fix wave after 1.1.277). Ticks spent waiting
     * at the door for it to open are not counted at all (the plan's own timeout caps that wait).
     */
    public static int nextStuckTicks(int stuckTicks, boolean sameColumn, boolean liningUpProgressed) {
        if (!sameColumn) {
            return 0;
        }
        return liningUpProgressed ? stuckTicks : stuckTicks + 1;
    }

    /** Close-behind's last attempt (retries every 520 ms, so about 11 s of trying). */
    public static final int CLOSE_BEHIND_MAX_ATTEMPT = 20;

    /** Close-behind's verdict for one attempt, given who else is at the door. */
    public enum CloseBehind {
        /** Nobody else at the door: close it. */
        CLOSE,
        /** Someone is in or next to the doorway: retry later. */
        WAIT,
        /** Someone stayed at the door through every attempt: leave it open. */
        LEAVE_OPEN
    }

    /**
     * Every close-behind path (crossed the door plane, abandoned it far away, or stuck near it)
     * checks this right before closing, so a door is never shut on a bot or player in it. After
     * {@code maxAttempt} ({@link #CLOSE_BEHIND_MAX_ATTEMPT}) the door is left open rather than
     * retried forever.
     *
     * @param nearestOtherPlayerDistSq as {@link #mayCloseStuckNearDoor}
     */
    public static CloseBehind closeBehind(double nearestOtherPlayerDistSq, int attempt, int maxAttempt) {
        if (mayCloseStuckNearDoor(nearestOtherPlayerDistSq)) {
            return CloseBehind.CLOSE;
        }
        return attempt >= maxAttempt ? CloseBehind.LEAVE_OPEN : CloseBehind.WAIT;
    }

    /** Same horizontal column (X/Z), Y ignored — vertical bouncing is not progress. */
    public static boolean samePlanColumn(BlockPos prev, BlockPos cur) {
        return prev != null && cur != null && prev.getX() == cur.getX() && prev.getZ() == cur.getZ();
    }
}
