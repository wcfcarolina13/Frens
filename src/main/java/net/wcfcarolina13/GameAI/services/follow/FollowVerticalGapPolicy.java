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
}
