package net.wcfcarolina13.GameAI.services;

/**
 * Whether a guarding or patrolling bot that is wedged below the surface runs its surface escape.
 *
 * <p>A guard bot has no task ticket, so nothing clears a leftover abort latch (external-teleport
 * detector, a respawn, an interrupted hobby). Every pillar step and walk then aborts at step 0 and
 * the bot never climbs out (finding 5). Latch handling is {@link SurvivalLatchPolicy}: a manual
 * {@code /bot stop} holds, any other latch with no task running is stale and is cleared.
 *
 * <p>The guard order is an explicit owner command, so the hobby gates "commander nearby" and
 * "post-task grace" do not apply here. Two gates stay: night or a thunderstorm (unless the bot is
 * hungry, food 3 or less) and being underground inside a registered base.
 *
 * <p>Order: HOLD beats every gate. The suppress gates beat clearing the latch, so a stale latch is
 * left alone while the escape would not run anyway and is cleared on the first unsuppressed check.
 */
public final class GuardEscapePolicy {

    /** Food at or below this overrides the night gate (same threshold as the hobby escape). */
    public static final int HUNGRY_FOOD_LEVEL = 3;

    public enum Decision {
        /** Manual stop: stay put until the next command. */
        HOLD,
        /** Stale latch with no task: clear it, then run the escape. */
        CLEAR_AND_PROCEED,
        /** Night or thunderstorm and not hungry: wait it out. */
        SUPPRESS_NIGHT,
        /** Underground inside a registered base: surfacing would route through the structure. */
        SUPPRESS_BASE,
        /** Run the escape. */
        PROCEED
    }

    private GuardEscapePolicy() {
    }

    /**
     * @param latched      whether the bot's abort latch is set
     * @param latchReason  the latch reason (may be null or empty)
     * @param hasActiveTask whether a task ticket is running for the bot
     * @param nightOrStorm night in a dimension with a day cycle, or a thunderstorm
     * @param food         the bot's food level
     * @param insideBase   whether the bot stands inside a registered base
     */
    public static Decision decide(boolean latched, String latchReason, boolean hasActiveTask,
                                  boolean nightOrStorm, int food, boolean insideBase) {
        SurvivalLatchPolicy.Decision latch = SurvivalLatchPolicy.decide(latched, latchReason, hasActiveTask);
        if (latch == SurvivalLatchPolicy.Decision.HOLD) {
            return Decision.HOLD;
        }
        if (nightOrStorm && food > HUNGRY_FOOD_LEVEL) {
            return Decision.SUPPRESS_NIGHT;
        }
        if (insideBase) {
            return Decision.SUPPRESS_BASE;
        }
        return latch == SurvivalLatchPolicy.Decision.CLEAR_AND_PROCEED
                ? Decision.CLEAR_AND_PROCEED
                : Decision.PROCEED;
    }
}
