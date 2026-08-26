package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped recorder of unaddressed chat spoken near soul-bound bots (local-chat spec §4).
 * One bounded ring per player; each entry carries the set of bots that were in earshot when the
 * line was spoken, so a bot only ever reads back what it actually witnessed.
 *
 * <p>In-memory only — nothing here is ever written to disk. The single write site (the Frens
 * public-chat callback) is gated by {@code soulLocalChatEnabled}, so while the toggle is off this
 * class holds nothing and every consumer reads an empty list.
 *
 * <p>Shaped after {@link SoulPlayerActivity}: static facade, server-thread writes, concurrent map
 * for safe reads, {@link #clear()} for shutdown and tests.
 */
final class SoulLocalMemory {

    /** Most overheard lines retained per player; the oldest is dropped past this. */
    static final int MAX_ENTRIES_PER_PLAYER = 8;
    /** Lines older than this are no longer "recently overheard". */
    static final long TTL_MS = 600_000L; // 10 min

    private SoulLocalMemory() {
    }

    private record Overheard(String line, Set<UUID> witnesses, long atEpochMs) {
    }

    private static final Map<UUID, Deque<Overheard>> BY_PLAYER = new ConcurrentHashMap<>();

    /**
     * Records one overheard line. No-op for a blank line or an empty witness set — a line nobody
     * heard is not a memory.
     */
    static void note(UUID playerId, String line, Set<UUID> witnessBotIds, long atEpochMs) {
        if (playerId == null || line == null || line.isBlank()
                || witnessBotIds == null || witnessBotIds.isEmpty()) {
            return;
        }
        Deque<Overheard> ring = BY_PLAYER.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(new Overheard(line, Set.copyOf(witnessBotIds), atEpochMs));
            while (ring.size() > MAX_ENTRIES_PER_PLAYER) {
                ring.removeFirst();
            }
        }
    }

    /** @return this player's lines that {@code botId} witnessed and that are inside the TTL,
     *  oldest first; never {@code null}. */
    static List<String> witnessedBy(UUID botId, UUID playerId, long nowEpochMs) {
        if (botId == null || playerId == null) {
            return List.of();
        }
        Deque<Overheard> ring = BY_PLAYER.get(playerId);
        if (ring == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        synchronized (ring) {
            for (Overheard entry : ring) {
                if (nowEpochMs - entry.atEpochMs() <= TTL_MS && entry.witnesses().contains(botId)) {
                    lines.add(entry.line());
                }
            }
        }
        return List.copyOf(lines);
    }

    /** Drops one player's ring — called when they disconnect. */
    static void forget(UUID playerId) {
        if (playerId != null) {
            BY_PLAYER.remove(playerId);
        }
    }

    static void clear() {
        BY_PLAYER.clear();
    }
}
