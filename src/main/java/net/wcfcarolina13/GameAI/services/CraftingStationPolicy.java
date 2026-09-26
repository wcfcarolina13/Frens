package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions behind {@code CraftingHelper.ensureCraftingStation}'s thread split: what a call on
 * the server thread may do to get a crafting table, and how loudly it says so.
 *
 * <p>Off the server thread (a skill or command worker) nothing changes: the bot may walk to a table
 * up to {@link #WORKER_SCAN_HORIZONTAL} blocks away, nudge, carve a placement pocket and relocate to
 * place one. On the server thread (idle hobbies, {@code /bot cook}, the auto-cook tick, RideSync's
 * lead, saddle-stick and fence crafts) none of that can work: a walk only sets velocity, and the
 * tick that would move the bot is the thread that waits, so it can only freeze the server until it
 * times out. There the bot uses a table already in reach, or places one from its inventory on a
 * cell that is ready now, and otherwise gives up for this call.
 *
 * <p>No Minecraft types.
 */
public final class CraftingStationPolicy {

    /** Horizontal half-width of the table scan off the server thread (an 81-block square). */
    public static final int WORKER_SCAN_HORIZONTAL = 40;
    /** Vertical half-height of the table scan off the server thread. */
    public static final int WORKER_SCAN_VERTICAL = 6;
    /**
     * Horizontal half-width of the table scan on the server thread: just enough to hold every block
     * whose center is within the 4.5-block station reach of a bot standing anywhere in its block
     * (a block 5 away has its center exactly 4.5 from the bot's block edge; 6 away is 5.5).
     */
    public static final int TICK_SCAN_HORIZONTAL = 5;
    /** Vertical half-height of the table scan on the server thread, by the same reasoning as {@link #TICK_SCAN_HORIZONTAL}. */
    public static final int TICK_SCAN_VERTICAL = 5;
    /** At most one "no table in reach, not walking" line per bot this often. */
    public static final long DECLINE_LOG_INTERVAL_MS = 30_000L;

    private CraftingStationPolicy() {
    }

    /** Whether the call may walk or nudge the bot toward a table, a stand or a relocation cell. */
    public static boolean mayWalk(boolean onServerThread) {
        return !onServerThread;
    }

    /**
     * Whether the call may mine blocks out of the way to make a placement pocket. Mining progress is
     * posted back to the server thread, so a wait for it there can only time out.
     */
    public static boolean mayCarve(boolean onServerThread) {
        return !onServerThread;
    }

    /** Horizontal half-width of the crafting table scan around the bot. */
    public static int scanHorizontal(boolean onServerThread) {
        return onServerThread ? TICK_SCAN_HORIZONTAL : WORKER_SCAN_HORIZONTAL;
    }

    /** Vertical half-height of the crafting table scan around the bot. */
    public static int scanVertical(boolean onServerThread) {
        return onServerThread ? TICK_SCAN_VERTICAL : WORKER_SCAN_VERTICAL;
    }

    /**
     * Whether a table the bot knows of (remembered, looked at by the commander, or found by the scan)
     * is a candidate: always off the server thread, where the bot can walk to it; on it, only when it
     * is already within reach of where the bot stands.
     */
    public static boolean acceptKnownTable(boolean onServerThread, boolean inReach) {
        return !onServerThread || inReach;
    }

    /**
     * Whether a failed walk to a table arms the shared reach-failure cooldown. A server-thread call
     * never walks, so its miss says nothing about whether a worker could reach the table, and must
     * not hold back a worker that starts a moment later.
     */
    public static boolean armsReachFailCooldown(boolean onServerThread) {
        return !onServerThread;
    }

    /**
     * Whether the per-call step lines keep their usual level (INFO, or WARN for a failed placement).
     * On the server thread they drop to DEBUG: RideSync retries a lead craft every second.
     */
    public static boolean logAtInfo(boolean onServerThread) {
        return !onServerThread;
    }

    /**
     * Whether a "couldn't place a crafting table" chat goes through the shared crafting-table
     * message throttle (at most one such line per bot per 30 s) instead of straight to chat. On the
     * server thread a failed placement no longer takes seconds, so an every-second caller would
     * otherwise send it every second.
     */
    public static boolean throttlesFailureChat(boolean onServerThread) {
        return onServerThread;
    }

    /**
     * Whether to write the "no table in reach, not walking" line now: never written for this bot
     * ({@code lastLogMs} null), or last written at least {@link #DECLINE_LOG_INTERVAL_MS} ago.
     */
    public static boolean shouldLogDecline(long nowMs, Long lastLogMs) {
        return lastLogMs == null || nowMs - lastLogMs >= DECLINE_LOG_INTERVAL_MS;
    }
}
