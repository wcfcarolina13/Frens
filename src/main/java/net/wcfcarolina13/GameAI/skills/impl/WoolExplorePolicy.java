package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Pure give-up rule for the wool skill's sheep exploration (finding 35).
 *
 * <p>An explore cycle that could not move the bot anywhere is "stuck". Before this policy the
 * skill re-ran stuck cycles back to back with no pause until its 30-minute cap, because a stuck
 * cycle reported success and never marked a zone visited. {@link WoolSkill} now counts
 * consecutive stuck cycles, pauses {@link #STUCK_PAUSE_MS} between them, and stops once
 * {@link #shouldGiveUp} says so. A cycle that moves the bot resets the count.
 */
public final class WoolExplorePolicy {

    /** Consecutive stuck explore cycles after which the wool run stops. */
    public static final int MAX_STUCK_CYCLES = 3;
    /** Pause between stuck cycles (worker thread). */
    public static final long STUCK_PAUSE_MS = 1500L;

    private WoolExplorePolicy() {
    }

    public static boolean shouldGiveUp(int stuckCycles) {
        return stuckCycles >= MAX_STUCK_CYCLES;
    }
}
