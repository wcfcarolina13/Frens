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

    public record Entry(String dimension, long pos, String rawItem, String cookedItem, int owed, long recordedTick) {
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
            return new Entry(dimension, pos, rawItem, cookedItem, owed, nowTick);
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
            return owed <= 0 ? null : new Entry(old.dimension(), old.pos(), old.rawItem(), old.cookedItem(), owed, old.recordedTick());
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
