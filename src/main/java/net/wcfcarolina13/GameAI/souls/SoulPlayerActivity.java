package net.wcfcarolina13.GameAI.souls;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-activity awareness for souls (Option C): a tiny per-player record of the most recent
 * observable action (block broken, entity attacked), plus a pure formatter that joins the
 * player's instantaneous states with that action. Notes arrive from the existing Frens event
 * hooks on the server thread; reads happen at snapshot capture. One record per player — souls
 * only ever surface "what are they doing right now", never a history.
 */
final class SoulPlayerActivity {

    private SoulPlayerActivity() {
    }

    /** Actions older than this are no longer "current" activity. */
    static final long ACTION_WINDOW_MS = 30_000L;

    private record LastAction(String description, long atEpochMs) {
    }

    private static final Map<UUID, LastAction> LAST_ACTIONS = new ConcurrentHashMap<>();
    /** Last public-chat line per player — the banter director's quiet-window signal. */
    private static final Map<UUID, Long> LAST_CHAT_AT = new ConcurrentHashMap<>();

    static void noteChat(UUID playerId, long atEpochMs) {
        LAST_CHAT_AT.put(playerId, atEpochMs);
    }

    /** @return epoch ms of the player's last public chat line, or 0 when unknown. */
    static long lastChatAt(UUID playerId) {
        Long at = LAST_CHAT_AT.get(playerId);
        return at == null ? 0L : at;
    }

    static void noteBlockBreak(UUID playerId, String blockName, long atEpochMs) {
        LAST_ACTIONS.put(playerId, new LastAction("broke " + blockName, atEpochMs));
    }

    static void noteAttack(UUID playerId, String targetName, long atEpochMs) {
        LAST_ACTIONS.put(playerId, new LastAction("attacked a " + targetName, atEpochMs));
    }

    static Optional<String> recentAction(UUID playerId, long nowEpochMs) {
        LastAction last = LAST_ACTIONS.get(playerId);
        if (last == null || nowEpochMs - last.atEpochMs() > ACTION_WINDOW_MS) {
            return Optional.empty();
        }
        long secondsAgo = Math.max(0, (nowEpochMs - last.atEpochMs()) / 1000L);
        return Optional.of(last.description() + " " + secondsAgo + "s ago");
    }

    /** Joins instantaneous states and the recent action into one prompt fragment ("" = idle). */
    static String describe(List<String> states, String recentAction) {
        String statesPart = String.join(", ", states);
        if (statesPart.isEmpty()) {
            return recentAction == null ? "" : recentAction;
        }
        if (recentAction == null || recentAction.isEmpty()) {
            return statesPart;
        }
        return statesPart + "; " + recentAction;
    }

    static void clear() {
        LAST_ACTIONS.clear();
        LAST_CHAT_AT.clear();
    }
}
