package net.wcfcarolina13.GameAI.services.follow;

/**
 * Pure-logic policy: the height gap at which follow treats a no-path as a vertical problem.
 *
 * <p>No Minecraft imports.
 *
 * <p>Two places used to disagree. The regroup prompt ("having trouble reaching you") counted a
 * no-path as vertical from a 3-block gap, but the reroute that tries goals near the commander at
 * the bot's level, the midpoint and the commander's level ({@code tryVerticalSeparationFallbackPlan})
 * ran only from 6 blocks. At a 3 to 5 block gap the bot prompted for a regroup without ever trying
 * the reroute (backlog "Follow vertical recovery", headless run 2026-11-07 at dy=4). Both now read
 * this one gate, so the reroute is always tried before the prompt.
 */
public final class FollowVerticalGapPolicy {

    /** Smallest |botY - targetY| that counts as a vertical separation. */
    public static final int MIN_VERTICAL_GAP = 3;

    private FollowVerticalGapPolicy() {
    }

    public static boolean isVerticalSeparation(int botY, int targetY) {
        return Math.abs(botY - targetY) >= MIN_VERTICAL_GAP;
    }

    /** A reroute goal must bring the bot at least this much closer to the commander (blocks, 3D). */
    public static final double MIN_FALLBACK_GAIN = 2.0D;

    /**
     * Whether a reroute goal is worth a plan: it is at least {@link #MIN_FALLBACK_GAIN} closer to the
     * target than the bot is now. Once the bot stands at the foot of the commander's block, the goal
     * "near the commander at the bot's level" is where it already is: a plan there succeeds, clears
     * the no-path streak and the stagnation count, and the regroup prompt never comes (sandbox
     * 2026-11-08, commander on a 4-high block with no way up: planner-fallback every 2.5 s, no prompt
     * in 75 s, where main prompted at 14 s). Skipping that goal lets the no-path streak build.
     */
    public static boolean fallbackGoalGainsOnTarget(double botX, double botY, double botZ,
                                                    double goalX, double goalY, double goalZ,
                                                    double targetX, double targetY, double targetZ) {
        double botToTarget = Math.sqrt(sq(botX - targetX) + sq(botY - targetY) + sq(botZ - targetZ));
        double goalToTarget = Math.sqrt(sq(goalX - targetX) + sq(goalY - targetY) + sq(goalZ - targetZ));
        return botToTarget - goalToTarget >= MIN_FALLBACK_GAIN;
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Whether a follow plan clears the no-path streak that drives the regroup prompt. A plan to the
     * commander does; a reroute plan does not, because the direct path still failed. Otherwise any
     * reroute that lands (to a goal at the block's foot, or a partial plan toward a midpoint) reset
     * the streak every few seconds and the bot waited at the foot with no prompt and no auto-regroup.
     */
    public static boolean rerouteClearsNoPathStreak(boolean viaReroute) {
        return !viaReroute;
    }
}
