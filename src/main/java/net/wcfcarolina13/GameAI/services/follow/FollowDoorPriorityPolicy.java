package net.wcfcarolina13.GameAI.services.follow;

/**
 * Pure-logic policy: when follow's door handling (door plans, door escape, stagnant door-open,
 * stagnant replan) stays switched on, versus when follow drops it for direct pursuit of a visible
 * commander ("skip the door magnet").
 *
 * <p>No Minecraft imports. Stagnation counts are follow ticks, and the follow loop runs every
 * 200 ms ({@code AutoFaceEntity.LOOP_INTERVAL_MS}), not every server tick: 3 stagnant ticks is
 * about 0.6 s of not changing block, which a walking or sprinting bot never does.
 *
 * <p>1.1.223: Jake stood ~9 s pushing into an oak fence beside a closed gate because the direct-route
 * probe only ran within 6 blocks of the nav goal. At 10+ blocks {@code directBlocked} was forced false,
 * the commander was visible through the fence, so every tick skipped the door magnet and cleared
 * the door plan and waypoints before any stuck handling could run.
 */
public final class FollowDoorPriorityPolicy {

    /** Nav-goal distance (squared) within which the direct route is always probed. */
    public static final double ALWAYS_PROBE_DIST_SQ = 36.0D;

    /** Previous-tick stagnation (follow ticks, 200 ms each) at which the probe runs at any range. */
    public static final int STAGNANT_PROBE_TICKS = 3;

    /** Stagnation (follow ticks) at which a visible commander no longer suppresses door handling. */
    public static final int STAGNANT_KEEP_DOORS_TICKS = 4;

    /** Squared distance from which the old rule skipped doors even without line of sight. */
    public static final double LONG_RANGE_DIST_SQ = 900.0D;

    private FollowDoorPriorityPolicy() {
    }

    /** {@link #shouldProbeDirectBlocked(double, int, boolean)} with no active door plan. */
    public static boolean shouldProbeDirectBlocked(double progressDistSq, int priorStagnant) {
        return shouldProbeDirectBlocked(progressDistSq, priorStagnant, false);
    }

    /**
     * Whether to raycast the direct route to the nav goal this tick.
     *
     * @param progressDistSq squared distance from the bot's block to the nav goal block
     * @param priorStagnant  the previous tick's stagnation (max of distance- and block-stagnant ticks)
     * @param doorPlanActive a follow door plan is in progress; keep probing so the plan is not
     *                       cancelled the moment the bot starts walking toward the door
     */
    public static boolean shouldProbeDirectBlocked(double progressDistSq, int priorStagnant, boolean doorPlanActive) {
        return progressDistSq <= ALWAYS_PROBE_DIST_SQ
                || priorStagnant >= STAGNANT_PROBE_TICKS
                || doorPlanActive;
    }

    /**
     * Whether follow should drop door plans and waypoints and chase the commander directly.
     * Mirrors the pre-1.1.223 rule (never when either side is sealed; yes when the route is not
     * blocked and the commander is visible or 30+ blocks away) and adds: never while the bot has
     * been stagnant for {@link #STAGNANT_KEEP_DOORS_TICKS} or more, so the stuck handling runs.
     */
    public static boolean shouldSkipDoorMagnet(boolean canSee,
                                               boolean directBlocked,
                                               double targetDistSq,
                                               boolean botSealed,
                                               boolean commanderSealed,
                                               int stagnant) {
        if (botSealed || commanderSealed) {
            return false;
        }
        if (stagnant >= STAGNANT_KEEP_DOORS_TICKS) {
            return false;
        }
        return !directBlocked && (canSee || targetDistSq >= LONG_RANGE_DIST_SQ);
    }
}
