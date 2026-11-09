package net.wcfcarolina13.PlayerUtils;

/**
 * Pure retry rule for {@code MiningTool.switchToTool}'s server-thread hops (no Minecraft types).
 *
 * <p>The swap is a 2 s server hop. A timeout returned the same {@code false} as "the tool is no
 * longer in that slot", so one slow server tick made the bot mine with its harmless fallback (an
 * empty hand or a non-tool) instead of the pickaxe it holds: slower, and stone or ore mined by hand
 * drops nothing. So a timed-out hop is tried once more, as {@link HarmlessFallbackPolicy} does for
 * the fallback equip.
 *
 * <p>A timed-out hop is cancelled only if it is still queued. One the server thread had already
 * started finishes after the timeout, and for a main-inventory slot it has already swapped the
 * tool into the hotbar, so the retry would find the slot changed. Each switch step therefore
 * reports the switch as done when the tool is already in the main hand, which keeps the retry
 * idempotent.
 */
public final class ToolSwitchPolicy {

    /** What one tool-switch hop reported. */
    public enum Outcome {
        /** The hop ran and the tool is in the main hand. */
        SWITCHED,
        /** The hop ran and the tool could not be selected (slot changed, step threw). */
        NOT_SWITCHED,
        /**
         * The hop did not answer: it timed out (cancelled if still queued, else it finishes late),
         * was interrupted, was rejected, or had no server.
         */
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

    /**
     * The tail of the INFO line logged when {@code switchToTool} stops after {@code attempts} hops,
     * or null when the last hop answered (nothing to log). An interrupted worker (cancelled by
     * {@code /bot stop}) is reported as interrupted, not as a server timeout.
     */
    public static String giveUpDetail(Outcome outcome, int attempts, boolean interrupted) {
        if (outcome != null && outcome != Outcome.TIMED_OUT) {
            return null;
        }
        if (interrupted) {
            return "interrupted (worker cancelled)";
        }
        return "timed out " + attempts + (attempts == 1 ? " time" : " times");
    }
}
