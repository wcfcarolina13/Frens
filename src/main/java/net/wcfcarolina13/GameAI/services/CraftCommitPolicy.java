package net.wcfcarolina13.GameAI.services;

/**
 * The decisions around {@code CraftingHelper.commitCraft}: one craft's ingredient consumes and its
 * output insert run together in one server-thread hop, so a worker never leaves the pack with the
 * output but not the ingredients taken (or the other way round). Pure: no Minecraft types.
 *
 * <p>The hop reports a {@link HopResult}; a hop that never ran (the worker's wait ran out and the
 * queued task was cancelled before it started) reports {@code null}. Room for the output is made on
 * the caller's thread before the hop ({@code makeRoomForOutput}, which may walk to a chest from a
 * worker), and again, forced, when the insert left something over.
 */
public final class CraftCommitPolicy {

    /**
     * How long a worker waits for the server to run the commit. Generous on purpose: the hop itself
     * is a few slot writes, so running out means the server is genuinely stalled, and nothing
     * happened (the task was cancelled unstarted).
     */
    public static final long HOP_TIMEOUT_MS = 5000L;

    private CraftCommitPolicy() {
    }

    /** How one commit ended for the craft that asked for it. */
    public enum Outcome {
        /** Ingredients taken and the output in the pack (or dropped at the bot's feet). */
        CRAFTED,
        /** A consume fell short: nothing was taken past that point and no output was made. */
        ABORTED,
        /** The server did not run the hop in time; the pack is untouched. */
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
     * Whether a log-to-plank conversion needs the make-room call before its hop. Taking the whole
     * stack frees that slot, and planks that fit one stack go there, as they did when the planks
     * were handed out after the decrement; a partial take, or more planks than one stack, needs
     * room found first.
     *
     * @param held     logs in the slot
     * @param use      logs the conversion takes from it
     * @param produced planks it makes
     * @param maxStack a plank stack's size
     */
    public static boolean roomNeededBeforeHop(int held, int use, int produced, int maxStack) {
        boolean freesSlot = use >= held;
        return !(freesSlot && produced <= maxStack);
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
