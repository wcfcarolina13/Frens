package net.wcfcarolina13.GameAI.services;

/**
 * How the follow drop guard treats a drop in front of a moving bot. For follow, a drop counts from
 * {@link #FOLLOW_MIN_DEPTH} blocks, and a grounded target at most 4.5 blocks below lets the bot step
 * down (a cave staircase, the commander just below a ledge).
 *
 * <p>Guard and patrol ({@code stayOutOfHoles}, Bradley's ruling 2026-11-08, option A): a guarding
 * bot does not walk down into a drop {@link #GUARD_MIN_DEPTH} or more blocks deep unless its target
 * is down there; 1-block dips stay fine. A guarding bot that had climbed out of a pit walked back
 * down its own hand-dug stair (each step 1 block, the first a 2-deep drop from the ground) to reach a
 * zombie on the far side, and fell back in (sandbox 2026-11-08). No Minecraft imports.</p>
 */
public final class DropGuardExemptionPolicy {

    public static final int FOLLOW_MIN_DEPTH = 3;
    public static final int GUARD_MIN_DEPTH = 2;

    private DropGuardExemptionPolicy() {
    }

    /** How deep a drop in front must be before the guard steps in (or around). */
    public static int minDepth(boolean stayOutOfHoles) {
        return stayOutOfHoles ? GUARD_MIN_DEPTH : FOLLOW_MIN_DEPTH;
    }

    /**
     * Whether the bot may step into the drop anyway. {@code stepDown}: bot Y minus target Y.
     * Follow: a grounded target within 4.5 blocks below. Guard and patrol: only when the target is
     * down there, that is at least {@link #GUARD_MIN_DEPTH} below the bot (or on that very column).
     */
    public static boolean exempt(boolean stayOutOfHoles, double stepDown, boolean targetGrounded,
                                 int frontX, int frontZ, int targetX, int targetZ) {
        if (!(stepDown >= -0.5D && stepDown <= 4.5D && targetGrounded)) {
            return false;
        }
        if (!stayOutOfHoles) {
            return true;
        }
        return stepDown >= GUARD_MIN_DEPTH || (frontX == targetX && frontZ == targetZ);
    }
}
