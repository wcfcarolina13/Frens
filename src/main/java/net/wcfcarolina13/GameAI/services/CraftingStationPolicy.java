package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
    /** How long a table craft that failed for want of planks holds back the next try (and its chest pulls). */
    public static final long TABLE_NO_PLANKS_RETRY_MS = 15_000L;
    /** Planks one crafting table takes. */
    public static final int TABLE_PLANKS = 4;

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
     * Whether a crafting-table line ("I couldn't place a crafting table here.", "I need a crafting
     * table placed nearby", a materials line) goes out now.
     *
     * <p>An automatic craft (a skill sub-step on a worker, an idle hobby or RideSync on the server
     * thread) sends at most one per bot per 30 s ({@code cooldownActive}): an every-second caller
     * would otherwise send it every second, and one tool craft once tried a station per material
     * (1.1.234 playthrough #2). A player's own {@code /bot craft} ({@code explicitRequest}) is never
     * held back by a cooldown an earlier craft armed, and sends the first such line of the call
     * only, so a station failure and the "I need a crafting table" after it are one line.
     */
    public static boolean shouldSendTableLine(boolean explicitRequest,
                                              boolean saidTableLineThisCall,
                                              boolean cooldownActive) {
        return explicitRequest ? !saidTableLineThisCall : !cooldownActive;
    }

    /**
     * Whether a player's own craft ends with {@link #fallbackLine}: it made nothing, nothing was
     * said during the call, and it was not stopped (a stop already answers for itself).
     */
    public static boolean needsFallbackLine(int crafted, boolean saidAnything, boolean aborted) {
        return crafted <= 0 && !saidAnything && !aborted;
    }

    /** "I couldn't craft stone axe." for a player's craft that failed without saying why. */
    public static String fallbackLine(String item) {
        String label = item == null || item.isBlank() ? "that" : item.replace('_', ' ').trim();
        return "I couldn't craft " + label + ".";
    }

    /**
     * Whether to write the "no table in reach, not walking" line now: never written for this bot
     * ({@code lastLogMs} null), or last written at least {@link #DECLINE_LOG_INTERVAL_MS} ago.
     */
    public static boolean shouldLogDecline(long nowMs, Long lastLogMs) {
        return lastLogMs == null || nowMs - lastLogMs >= DECLINE_LOG_INTERVAL_MS;
    }

    /**
     * Whether ensureCraftingStation may try to craft a table now. A bot holding the planks for one
     * ({@code planksHeld} at least {@link #TABLE_PLANKS}) always may. Otherwise it waits out the
     * window a craft that failed for want of planks armed ({@code noPlanksUntilMs}, null when none),
     * because each try pulls from nearby chests and an every-second caller would pull every second.
     */
    public static boolean mayTryTableCraft(Long noPlanksUntilMs, long nowMs, int planksHeld) {
        return planksHeld >= TABLE_PLANKS || noPlanksUntilMs == null || nowMs >= noPlanksUntilMs;
    }

    /**
     * Which tool materials a craft tries, in order. A named material ({@code strict}) is tried as
     * given so its shortfall line can be reported. Otherwise the materials the bot holds enough of
     * for one tool come first (a bot holding only wood used to craft a table for a diamond axe
     * before reaching wood), then the rest in their usual order, because each try also pulls its
     * material from a chest and a bot with an empty inventory still has to reach the chest wood or
     * cobble. A station failure stops the whole loop in the caller, so the extra tries never mean
     * extra table attempts.
     */
    public static <T> List<T> materialsToTry(List<T> ordered,
                                             Predicate<T> heldEnough,
                                             boolean strict) {
        if (ordered == null || ordered.isEmpty()) {
            return List.of();
        }
        if (strict) {
            return ordered;
        }
        List<T> out = new ArrayList<>(ordered.size());
        for (T material : ordered) {
            if (heldEnough.test(material)) {
                out.add(material);
            }
        }
        for (T material : ordered) {
            if (!out.contains(material)) {
                out.add(material);
            }
        }
        return out;
    }

    /** Planks on hand for a wooden head: loose planks plus four per log. */
    public static int plankEquivalent(int planks, int logs) {
        return Math.max(0, planks) + 4 * Math.max(0, logs);
    }

    /**
     * Planks a bed craft must have before anything is crafted or placed: 3 per bed, plus the 4 a
     * crafting table takes when no table is reachable and one has to be crafted first. Counting the
     * table up front stops it from eating the bed's planks after the check passed.
     */
    public static int bedPlanksNeeded(int beds, boolean tableMustBeCrafted) {
        return 3 * Math.max(1, beds) + (tableMustBeCrafted ? TABLE_PLANKS : 0);
    }

    /**
     * Whether a table candidate is skipped because the bot failed to reach it {@code ageMs} ago:
     * only the failed position, and only inside the cooldown (finding 13, 1.1.274). Any other
     * table is still tried. A negative age (the clock went back) counts as expired, as in
     * {@link LogThrottle#due}.
     */
    public static boolean skipForReachCooldown(long failedPosLong, long candidatePosLong, long ageMs, long cooldownMs) {
        return failedPosLong == candidatePosLong && ageMs >= 0 && ageMs < cooldownMs;
    }

    /**
     * Throttle key for a player's "I don't know how to craft X" line: one slot per bot and item
     * name, case-insensitive (finding 7, 1.1.274). {@code unknownCraftKey(bot, "")} is the prefix
     * every key of that bot starts with.
     */
    public static String unknownCraftKey(String botId, String label) {
        String item = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
        return botId + "|" + item;
    }
}
