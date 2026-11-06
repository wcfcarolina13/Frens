package net.wcfcarolina13.PlayerUtils;

/**
 * Pure retry rule for {@code MiningTool.switchToTool}'s server-thread hops (no Minecraft types).
 *
 * <p>The swap is a 2 s server hop. A timeout returned the same {@code false} as "the tool is no
 * longer in that slot", so one slow server tick made the bot mine with its harmless fallback (an
 * empty hand or a non-tool) instead of the pickaxe it holds: slower, and stone or ore mined by hand
 * drops nothing. A timed-out hop is cancelled before it runs, so it is safe to try once more, as
 * {@link HarmlessFallbackPolicy} does for the fallback equip.
 */
public final class ToolSwitchPolicy {

    /** What one tool-switch hop reported. */
    public enum Outcome {
        /** The hop ran and the tool is in the main hand. */
        SWITCHED,
        /** The hop ran and the tool could not be selected (slot changed, step threw). */
        NOT_SWITCHED,
        /** The hop never ran: it timed out (and was cancelled), was rejected, or had no server. */
        TIMED_OUT
    }

    /** Hops allowed in total: the first try plus one retry after a timeout. */
    public static final int MAX_ATTEMPTS = 2;

    private ToolSwitchPolicy() {
    }

    /**
     * Whether to send hop number {@code attempt + 1} after hop {@code attempt} (1-based) reported
     * {@code outcome}. An interrupted worker is being cancelled, so it does not wait another 2 s.
     */
    public static boolean retry(Outcome outcome, int attempt, boolean interrupted) {
        return (outcome == null || outcome == Outcome.TIMED_OUT) && !interrupted && attempt < MAX_ATTEMPTS;
    }
}
