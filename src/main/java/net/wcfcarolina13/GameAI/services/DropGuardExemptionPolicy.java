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

    /**
     * Guard and patrol: a cell ahead whose stand level is {@link #GUARD_MIN_DEPTH} or more below the
     * ground being guarded ({@code referenceY}, the guard point's Y) is a drop the bot does not take
     * unless its target is down there. Depth is measured from that reference, not from the bot's
     * current level: the pit a guard climbed out of is reached by its own hand-dug stair, three
     * 1-block steps (120, 119, 118, 117 in the sandbox scan 2026-11-08), so no single step is ever
     * 2 deep from where the bot stands. With the reference, the first tread (1 below) is fine and the
     * second (2 below) is refused, on the ground and in the air alike. {@code currentLevel}: the
     * level the bot stands at (or would land at) in its own column.
     */
    public static boolean refusesStep(int standLevelAhead, int currentLevel, int referenceY, boolean targetDownThere) {
        // Only a step down is refused: a guard already below the reference (it followed a target
        // down, or was knocked into a dip) still walks level or climbs back.
        return !targetDownThere && standLevelAhead < currentLevel
                && referenceY - standLevelAhead >= GUARD_MIN_DEPTH;
    }

    /**
     * Whether an {@link #exempt} drop stays exempt once the straight line toward the target has
     * been read by {@link BlindWalkGuardPolicy}. {@link #exempt} looks only at where the target
     * stands, never at the drop itself: a commander on the far rim of a ravine is grounded and level
     * with the bot, so a come walked the bot straight off the edge (sandbox 2026-11-09). A line the
     * blind-walk guard refuses (within its refuse horizon: a fall of more than
     * {@link BlindWalkGuardPolicy#MAX_SAFE_DROP} that does not end in water, lava, a deadly floor,
     * or deep water) is not exempt, so the drop guard sidesteps or stops at the edge as it does for
     * any other drop. A 1-3 block step down and a drop into shallow water stay exempt. A null
     * verdict (nothing sampled) keeps the old answer.
     */
    public static boolean exemptAfterBlindLine(boolean exempt, BlindWalkGuardPolicy.Verdict lineVerdict) {
        return exempt && lineVerdict != BlindWalkGuardPolicy.Verdict.REFUSE;
    }

    /** The lowest stand level a guard's bypass step may land on: one below the reference at most. */
    public static int minBypassStandY(int referenceY) {
        return referenceY - GUARD_MIN_DEPTH + 1;
    }

    /** How far ahead of the feet an airborne guard's heading cell is judged, in blocks. */
    public static final double AIRBORNE_PROBE_BLOCKS = 1.0D;

    /**
     * Whether a guarding bot in the air must lose its forward push (Bradley's ruling 2026-11-08,
     * option (a): "Check holes in the air too"). The ground drop guard only runs on the ground; a
     * guard jumping off the lip into its stair dip was carried by its combat forward input, still in
     * the air, on down the stair into the pit (sandbox trace 2026-11-08). {@code refused}: the heading
     * cell's stand level is a refused step ({@link #refusesStep}). Guard and patrol only: a following
     * bot jumping a gap is left alone.
     */
    public static boolean haltInAir(boolean stayOutOfHoles, boolean onGround, boolean inWaterOrVehicle,
                                    boolean refused) {
        return stayOutOfHoles && !onGround && !inWaterOrVehicle && refused;
    }

    /**
     * In the air, the bot's own column is already a drop: its feet block is {@link #FOLLOW_MIN_DEPTH}
     * or more above where it would land there ({@code standHere}). Measured from the landing level,
     * not the feet, so the top of a hop over the canopy (feet up to a block higher) still reads as
     * over support.
     */
    public static boolean airOverDrop(int feetBlockY, int standHere) {
        return feetBlockY - standHere >= FOLLOW_MIN_DEPTH;
    }

    /**
     * In the air, the heading column is a drop: where the bot would land there ({@code standAhead})
     * is {@link #FOLLOW_MIN_DEPTH} or more below where it would land in its own column. Stand level
     * against stand level, so a hop does not make a 2-block step read as 3.
     */
    public static boolean airHeadingIntoDrop(int standHere, int standAhead) {
        return standHere - standAhead >= FOLLOW_MIN_DEPTH;
    }

    /**
     * Follow (and come/go) in the air: drop the forward momentum when the bot is still over
     * support but its heading cell is a drop of {@link #FOLLOW_MIN_DEPTH} or more that the ground
     * guard would not let it walk into ({@code exemptOnGround}: what {@link #exempt} and
     * {@link #exemptAfterBlindLine} say for the same front cell and target). The ground guard only
     * runs on the ground, and a bot hopping across an uneven leaf canopy is airborne at nearly
     * every check: it hopped off a 7-block canopy edge toward its commander below, every guard
     * check skipped (sandbox trace 2026-11-09, 6 HP). Once over the drop already
     * ({@code overDropNow}) nothing is changed: stopping there only drops it straight down.
     * Follow has no planned gap-jump move, so the only thing this costs is an improvised hop
     * across a deep gap, which now lands back on the edge it left.
     */
    public static boolean followHaltInAir(boolean onGround, boolean inWaterOrVehicle, boolean overDropNow,
                                          boolean headingIntoDrop, boolean exemptOnGround) {
        return !onGround && !inWaterOrVehicle && !overDropNow && headingIntoDrop && !exemptOnGround;
    }
}
