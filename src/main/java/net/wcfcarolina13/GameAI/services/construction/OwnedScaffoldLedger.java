package net.wcfcarolina13.GameAI.services.construction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure, thread-safe store behind {@link OwnedScaffoldRegistry}: per bot UUID, per dimension id,
 * the packed block positions ({@code BlockPos.asLong()}) that bot placed as own scaffold. No
 * Minecraft types, so it is testable under JUnit.
 *
 * <p>Ownership is strictly per bot: {@link #isOwned} for one bot never sees another bot's entries.
 */
public final class OwnedScaffoldLedger {

    private final Map<UUID, Map<String, Set<Long>>> byBot = new ConcurrentHashMap<>();

    /** @return true when the position was not yet owned by this bot */
    public boolean register(UUID bot, String dimension, long packedPos) {
        if (bot == null || dimension == null) {
            return false;
        }
        return byBot.computeIfAbsent(bot, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(dimension, ignored -> ConcurrentHashMap.newKeySet())
                .add(packedPos);
    }

    public boolean isOwned(UUID bot, String dimension, long packedPos) {
        if (bot == null || dimension == null) {
            return false;
        }
        Map<String, Set<Long>> dims = byBot.get(bot);
        if (dims == null) {
            return false;
        }
        Set<Long> positions = dims.get(dimension);
        return positions != null && positions.contains(packedPos);
    }

    /** @return true when the position was owned by this bot and is now forgotten */
    public boolean forget(UUID bot, String dimension, long packedPos) {
        if (bot == null || dimension == null) {
            return false;
        }
        Map<String, Set<Long>> dims = byBot.get(bot);
        if (dims == null) {
            return false;
        }
        Set<Long> positions = dims.get(dimension);
        return positions != null && positions.remove(packedPos);
    }

    /** Snapshot of this bot's owned positions in one dimension (empty, never null). */
    public List<Long> positions(UUID bot, String dimension) {
        if (bot == null || dimension == null) {
            return List.of();
        }
        Map<String, Set<Long>> dims = byBot.get(bot);
        Set<Long> positions = dims == null ? null : dims.get(dimension);
        return positions == null ? List.of() : new ArrayList<>(positions);
    }

    /** Every bot that owns at least one position in {@code dimension}. */
    public List<UUID> botsWithPositions(String dimension) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Set<Long>>> e : byBot.entrySet()) {
            Set<Long> positions = e.getValue().get(dimension);
            if (positions != null && !positions.isEmpty()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Replaces the whole ledger with {@code saved} for one dimension (a new server or save: the
     * previous save's entries must never carry over). Null keys and values are skipped.
     */
    public void replaceAll(String dimension, Map<UUID, ? extends Collection<Long>> saved) {
        byBot.clear();
        if (dimension == null || saved == null) {
            return;
        }
        for (Map.Entry<UUID, ? extends Collection<Long>> e : saved.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            for (Long packed : e.getValue()) {
                if (packed != null) {
                    register(e.getKey(), dimension, packed);
                }
            }
        }
    }

    public void clear() {
        byBot.clear();
    }
}
