package net.wcfcarolina13.GameAI.services.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure, thread-safe store behind {@link OwnedScaffoldRegistry}: per bot UUID, per dimension id,
 * the packed block positions ({@code BlockPos.asLong()}) that bot placed as own scaffold, each with
 * the placed block id and the placement time (epoch ms). No Minecraft types, so it is testable
 * under JUnit.
 *
 * <p>Ownership is strictly per bot: {@link #isOwned} for one bot never sees another bot's entries.
 * An entry without a block id is never stored.
 */
public final class OwnedScaffoldLedger {

    /** One owned cell: the exact block id placed there and when (epoch ms). */
    public record Owned(String blockId, long placedAtMs) {
    }

    private final Map<UUID, Map<String, Map<Long, Owned>>> byBot = new ConcurrentHashMap<>();

    /**
     * @return true when the position was not yet owned by this bot (a re-placement refreshes the
     *         stored id and time and returns false); false for a null or blank block id
     */
    public boolean register(UUID bot, String dimension, long packedPos, String blockId, long placedAtMs) {
        if (bot == null || dimension == null || blockId == null || blockId.isBlank()) {
            return false;
        }
        Owned previous = byBot.computeIfAbsent(bot, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(dimension, ignored -> new ConcurrentHashMap<>())
                .put(packedPos, new Owned(blockId, placedAtMs));
        return previous == null;
    }

    public boolean isOwned(UUID bot, String dimension, long packedPos) {
        return entry(bot, dimension, packedPos) != null;
    }

    /** This bot's entry for the position, or null when it does not own it. */
    public Owned entry(UUID bot, String dimension, long packedPos) {
        Map<Long, Owned> positions = cells(bot, dimension);
        return positions == null ? null : positions.get(packedPos);
    }

    /** @return true when the position was owned by this bot and is now forgotten */
    public boolean forget(UUID bot, String dimension, long packedPos) {
        Map<Long, Owned> positions = cells(bot, dimension);
        return positions != null && positions.remove(packedPos) != null;
    }

    /** Snapshot of this bot's owned positions in one dimension (empty, never null). */
    public List<Long> positions(UUID bot, String dimension) {
        Map<Long, Owned> positions = cells(bot, dimension);
        return positions == null ? List.of() : new ArrayList<>(positions.keySet());
    }

    /** Snapshot of this bot's owned entries in one dimension, position -> entry (empty, never null). */
    public Map<Long, Owned> entries(UUID bot, String dimension) {
        Map<Long, Owned> positions = cells(bot, dimension);
        return positions == null ? Map.of() : new LinkedHashMap<>(positions);
    }

    /** Every bot that owns at least one position in {@code dimension}. */
    public List<UUID> botsWithPositions(String dimension) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Map<Long, Owned>>> e : byBot.entrySet()) {
            Map<Long, Owned> positions = e.getValue().get(dimension);
            if (positions != null && !positions.isEmpty()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Replaces the whole ledger with {@code saved} for one dimension (a new server or save: the
     * previous save's entries must never carry over). Null keys and values, entries without a block
     * id, and entries expired at {@code nowMs} ({@link ScaffoldOwnershipPolicy#isUsableEntry}) are
     * dropped.
     *
     * @return how many saved entries were dropped
     */
    public int replaceAll(String dimension, Map<UUID, ? extends Map<Long, Owned>> saved, long nowMs) {
        byBot.clear();
        if (dimension == null || saved == null) {
            return 0;
        }
        int dropped = 0;
        for (Map.Entry<UUID, ? extends Map<Long, Owned>> e : saved.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            for (Map.Entry<Long, Owned> cell : e.getValue().entrySet()) {
                Owned owned = cell.getValue();
                if (cell.getKey() == null || owned == null
                        || !ScaffoldOwnershipPolicy.isUsableEntry(owned.blockId(), owned.placedAtMs(), nowMs)) {
                    dropped++;
                    continue;
                }
                register(e.getKey(), dimension, cell.getKey(), owned.blockId(), owned.placedAtMs());
            }
        }
        return dropped;
    }

    public void clear() {
        byBot.clear();
    }

    private Map<Long, Owned> cells(UUID bot, String dimension) {
        if (bot == null || dimension == null) {
            return null;
        }
        Map<String, Map<Long, Owned>> dims = byBot.get(bot);
        return dims == null ? null : dims.get(dimension);
    }
}
