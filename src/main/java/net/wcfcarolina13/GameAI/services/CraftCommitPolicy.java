package net.wcfcarolina13.GameAI.services;

/**
 * The decisions around {@code CraftingHelper.commitCraft}: one craft's ingredient consumes and its
 * output insert run together in one server-thread hop, so a worker never leaves the pack with the
 * output but not the ingredients taken (or the other way round). Pure: no Minecraft types.
 *
 * <p>The hop reports a {@link HopResult}; a hop that did not complete reports {@code null}. Room
 * for output that did not fit is made afterwards on the caller's thread ({@code makeRoomForOutput},
 * which may walk to a chest from a worker), once normally and once forced, so it can never offload
 * the ingredients the craft is about to use.
 */
public final class CraftCommitPolicy {

    /**
     * How long a worker waits for the server to run the commit. Generous on purpose: the hop itself
     * is a few slot writes, so running out means the server is genuinely stalled; an unstarted task
     * is abandoned and never runs.
     */
    public static final long HOP_TIMEOUT_MS = 5000L;

    private CraftCommitPolicy() {
    }

    /** How one commit ended for the craft that asked for it. */
    public enum Outcome {
        /** Ingredients taken and the output in the pack (or dropped at the bot's feet). */
        CRAFTED,
        /** A consume fell short (or threw): the pack is restored as it was and no output was made. */
        ABORTED,
        /** The hop did not complete: normally it never ran and the pack is untouched; if it was still
         *  running (SupplyServerHop UNFINISHED) it may yet finish, and the craft still reports 0. */
        TIMED_OUT
    }

    /**
     * What the server-thread step reports back.
     *
     * @param aborted  a consume reported too few ingredients; the step stopped before the insert
     * @param leftover output that did not fit the pack after the consumes (0 when all went in)
     */
    public record HopResult(boolean aborted, int leftover) {
        public static final HopResult ABORTED = new HopResult(true, 0);

        public static HopResult done(int leftover) {
            return new HopResult(false, Math.max(0, leftover));
        }
    }

    /** The craft's outcome from the hop's report; {@code null} is a hop that never ran. */
    public static Outcome outcome(HopResult hop) {
        if (hop == null) {
            return Outcome.TIMED_OUT;
        }
        return hop.aborted() ? Outcome.ABORTED : Outcome.CRAFTED;
    }

    /** Whether output is left to place (forced room, then a second hop that inserts and drops the rest). */
    public static boolean needsSecondHop(HopResult hop) {
        return hop != null && !hop.aborted() && hop.leftover() > 0;
    }

    /** What the craft reports as made: its count only when the commit went through. */
    public static int crafted(Outcome outcome, int count) {
        return outcome == Outcome.CRAFTED ? Math.max(0, count) : 0;
    }

    /**
     * For a slot scan on the caller's thread: whether the loop goes on after one conversion's
     * outcome. A timed-out hop means a stalled server, so the scan stops; an aborted one means the
     * slot changed under the scan, and the next slot is tried.
     */
    public static boolean continueScan(Outcome outcome) {
        return outcome != Outcome.TIMED_OUT;
    }
}
