package net.wcfcarolina13.PlayerUtils;

/**
 * Pure decisions for {@code MiningTool}'s harmless-fallback equip (no Minecraft types).
 *
 * <p>1.1.291 review: the fallback equip is a 2 s server-thread hop, and a timeout returned the same
 * {@code false} as "nothing safe to hold", so one slow server tick aborted mining with "could not
 * equip a safe tool". A timed-out hop is cancelled before it runs, so it is retried once; only a
 * second miss aborts, and with its own message so logs can tell the two cases apart.
 */
public final class HarmlessFallbackPolicy {

    /** What one fallback hop reported. */
    public enum Outcome {
        /** The hop ran and the bot now holds an empty hand or a safe non-weapon item. */
        EQUIPPED,
        /** The hop ran (or threw on the server) and nothing safe ended up in the hand. */
        NO_SAFE_ITEM,
        /** The hop never ran: it timed out (and was cancelled), was rejected, or had no server. */
        TIMED_OUT
    }

    /** What the caller should do next. */
    public enum Next {
        PROCEED,
        RETRY,
        ABORT_NO_SAFE_ITEM,
        ABORT_SERVER_BUSY
    }

    /** Hops allowed in total: the first try plus one retry after a timeout. */
    public static final int MAX_ATTEMPTS = 2;

    public static final String NO_SAFE_ITEM_MESSAGE =
            "⚠️ Cannot mine: could not equip a safe tool or empty hand.";
    public static final String SERVER_BUSY_MESSAGE =
            "⚠️ Cannot mine: the server was too busy to switch tools.";

    private HarmlessFallbackPolicy() {
    }

    /**
     * Decides the next step after hop number {@code attempt} (1-based) reported {@code outcome}.
     * A null outcome is treated as a hop that did not run.
     */
    public static Next decide(Outcome outcome, int attempt) {
        if (outcome == Outcome.EQUIPPED) {
            return Next.PROCEED;
        }
        if (outcome == Outcome.NO_SAFE_ITEM) {
            return Next.ABORT_NO_SAFE_ITEM;
        }
        return attempt < MAX_ATTEMPTS ? Next.RETRY : Next.ABORT_SERVER_BUSY;
    }

    /** The mining-result refusal for an abort, or null when {@code next} is not an abort. */
    public static String abortMessage(Next next) {
        if (next == Next.ABORT_NO_SAFE_ITEM) {
            return NO_SAFE_ITEM_MESSAGE;
        }
        if (next == Next.ABORT_SERVER_BUSY) {
            return SERVER_BUSY_MESSAGE;
        }
        return null;
    }
}
