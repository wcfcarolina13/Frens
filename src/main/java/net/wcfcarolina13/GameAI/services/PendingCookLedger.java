package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Food each bot left cooking: which furnace, which raw and cooked item, and how many cooked items it
 * is still owed. Written by {@link SmeltingService} when it loads a furnace and debited whenever the
 * bot takes that food back; read by {@link BotAutoCookingService} to go back for it. Server thread
 * only, in memory only; cleared at server stop. No Minecraft imports: positions are
 * {@code BlockPos.asLong()}, dimensions and items their identifier strings.
 */
public final class PendingCookLedger {

    /**
     * @param lastSeenOutput the owed cooked item's output count at the last furnace observation, or -1
     *                       when unknown (just recorded); lowered by the bot's own takes so a later
     *                       observation never counts them as someone else's
     * @param failedTrips    collection trips in a row that took nothing
     */
    public record Entry(String dimension, long pos, String rawItem, String cookedItem, int owed, long recordedTick,
                        int lastSeenOutput, int failedTrips) {
    }

    private static final Map<UUID, Map<String, Entry>> ENTRIES = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private PendingCookLedger() {
    }

    private static String key(String dimension, long pos) {
        return dimension + "|" + pos;
    }

    /**
     * Adds {@code count} cooked items owed at this furnace. The same cooked item adds to the entry
     * and refreshes its tick; a different item replaces it (the old food is no longer in that slot).
     *
     * @return what is owed there now
     */
    public static int record(UUID bot, String dimension, long pos, String rawItem, String cookedItem,
                             int count, long nowTick) {
        if (bot == null || dimension == null || rawItem == null || cookedItem == null || count <= 0) {
            return 0;
        }
        Map<String, Entry> forBot = ENTRIES.computeIfAbsent(bot, id -> new ConcurrentHashMap<>());
        Entry merged = forBot.compute(key(dimension, pos), (k, old) -> {
            int owed = old != null && old.cookedItem().equals(cookedItem) ? old.owed() + count : count;
            // A fresh load: baseline unknown until the next observation, trip count restarts.
            return new Entry(dimension, pos, rawItem, cookedItem, owed, nowTick, -1, 0);
        });
        return merged.owed();
    }

    /** What the bot is owed of {@code cookedItem} at this furnace (0 when none or another item). */
    public static int owed(UUID bot, String dimension, long pos, String cookedItem) {
        Entry entry = get(bot, dimension, pos);
        return entry != null && entry.cookedItem().equals(cookedItem) ? entry.owed() : 0;
    }

    public static Entry get(UUID bot, String dimension, long pos) {
        if (bot == null || dimension == null) {
            return null;
        }
        Map<String, Entry> forBot = ENTRIES.get(bot);
        return forBot == null ? null : forBot.get(key(dimension, pos));
    }

    /**
     * The bot took {@code taken} of {@code cookedItem} from this furnace (any path). Removes the
     * entry at 0.
     *
     * @return what is still owed there
     */
    public static int debit(UUID bot, String dimension, long pos, String cookedItem, int taken) {
        if (bot == null || dimension == null || cookedItem == null || taken <= 0) {
            return owed(bot, dimension, pos, cookedItem);
        }
        Map<String, Entry> forBot = ENTRIES.get(bot);
        if (forBot == null) {
            return 0;
        }
        Entry left = forBot.computeIfPresent(key(dimension, pos), (k, old) -> {
            if (!old.cookedItem().equals(cookedItem)) {
                return old;
            }
            int owed = old.owed() - taken;
            // The bot's own take lowers the baseline by all it took, so the next observation does
            // not read the drop as someone else's.
            int seen = old.lastSeenOutput() < 0 ? -1 : Math.max(0, old.lastSeenOutput() - taken);
            return owed <= 0 ? null : new Entry(old.dimension(), old.pos(), old.rawItem(), old.cookedItem(), owed,
                    old.recordedTick(), seen, old.failedTrips());
        });
        if (forBot.isEmpty()) {
            ENTRIES.remove(bot, forBot);
        }
        return left != null && left.cookedItem().equals(cookedItem) ? left.owed() : 0;
    }

    public static void remove(UUID bot, String dimension, long pos) {
        if (bot == null || dimension == null) {
            return;
        }
        Map<String, Entry> forBot = ENTRIES.get(bot);
        if (forBot == null) {
            return;
        }
        forBot.remove(key(dimension, pos));
        if (forBot.isEmpty()) {
            ENTRIES.remove(bot, forBot);
        }
    }

    /**
     * The furnace was looked at: {@code outputCooked} of the owed cooked item in the output and
     * {@code inputRaw} of the raw item in the input. Applies {@link PendingCookCollectPolicy#reconcileOwed}
     * and stores the new baseline. Removes the entry at 0.
     *
     * @return what is still owed there (0 when none or no entry)
     */
    public static int observe(UUID bot, String dimension, long pos, int outputCooked, int inputRaw) {
        if (bot == null || dimension == null) {
            return 0;
        }
        Map<String, Entry> forBot = ENTRIES.get(bot);
        if (forBot == null) {
            return 0;
        }
        Entry left = forBot.computeIfPresent(key(dimension, pos), (k, old) -> {
            int owed = PendingCookCollectPolicy.reconcileOwed(old.owed(), old.lastSeenOutput(), outputCooked, inputRaw);
            return owed <= 0 ? null : new Entry(old.dimension(), old.pos(), old.rawItem(), old.cookedItem(), owed,
                    old.recordedTick(), Math.max(0, outputCooked), old.failedTrips());
        });
        if (forBot.isEmpty()) {
            ENTRIES.remove(bot, forBot);
        }
        return left == null ? 0 : left.owed();
    }

    /**
     * A collection trip for this entry ended having taken {@code taken} (0 or less: failed or empty).
     * A take resets the count; a miss adds one. No-op when the entry is gone (a stop cleared it).
     *
     * @return failed trips in a row now, or 0 when there is no entry
     */
    public static int noteTrip(UUID bot, String dimension, long pos, int taken) {
        if (bot == null || dimension == null) {
            return 0;
        }
        Map<String, Entry> forBot = ENTRIES.get(bot);
        if (forBot == null) {
            return 0;
        }
        Entry updated = forBot.computeIfPresent(key(dimension, pos), (k, old) -> new Entry(old.dimension(), old.pos(),
                old.rawItem(), old.cookedItem(), old.owed(), old.recordedTick(), old.lastSeenOutput(),
                taken > 0 ? 0 : old.failedTrips() + 1));
        return updated == null ? 0 : updated.failedTrips();
    }

    /** Forgets every entry of this bot ({@code /bot stop}). A trip in flight ends on its own and takes nothing. */
    public static void clearBot(UUID bot) {
        if (bot != null) {
            ENTRIES.remove(bot);
        }
    }

    public static boolean hasEntries(UUID bot) {
        Map<String, Entry> forBot = bot == null ? null : ENTRIES.get(bot);
        return forBot != null && !forBot.isEmpty();
    }

    /** A snapshot of this bot's entries. */
    public static List<Entry> entriesFor(UUID bot) {
        Map<String, Entry> forBot = bot == null ? null : ENTRIES.get(bot);
        return forBot == null ? List.of() : new ArrayList<>(forBot.values());
    }

    /** Marks a collection trip in flight; false when one already is. */
    public static boolean beginTrip(UUID bot) {
        return bot != null && IN_FLIGHT.add(bot);
    }

    public static void endTrip(UUID bot) {
        if (bot != null) {
            IN_FLIGHT.remove(bot);
        }
    }

    public static boolean tripInFlight(UUID bot) {
        return bot != null && IN_FLIGHT.contains(bot);
    }

    public static void clear() {
        ENTRIES.clear();
        IN_FLIGHT.clear();
    }
}
