package net.shasankp000.ChatUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ChatContextManager {
    private static final Map<UUID, ClarificationState> pendingClarifications = new HashMap<>();
    private static final Map<UUID, LinkedHashMap<String, ConfirmationState>> pendingConfirmations = new HashMap<>();

    public static void setPendingClarification(UUID playerUUID, String originalMessage, String clarifyingQuestion, String botName) {
        pendingClarifications.put(playerUUID, new ClarificationState(originalMessage, clarifyingQuestion, botName));
    }

    public static ClarificationState getPendingClarification(UUID playerUUID) {
        return pendingClarifications.get(playerUUID);
    }

    public static void clearPendingClarification(UUID playerUUID) {
        pendingClarifications.remove(playerUUID);
    }

    public static boolean isAwaitingClarification(UUID playerUUID) {
        return pendingClarifications.containsKey(playerUUID);
    }

    public static void setPendingConfirmation(UUID playerUUID, ConfirmationState state) {
        if (playerUUID == null || state == null || state.botName == null || state.botName.isBlank()) {
            return;
        }
        pendingConfirmations
                .computeIfAbsent(playerUUID, id -> new LinkedHashMap<>())
                .put(state.botName.toLowerCase(Locale.ROOT), state);
    }

    public static ConfirmationState getPendingConfirmation(UUID playerUUID, String aliasHint) {
        LinkedHashMap<String, ConfirmationState> map = pendingConfirmations.get(playerUUID);
        if (map == null || map.isEmpty()) {
            return null;
        }
        if (aliasHint != null && !aliasHint.isBlank()) {
            return map.get(aliasHint.toLowerCase(Locale.ROOT));
        }
        String lastKey = null;
        for (String key : map.keySet()) {
            lastKey = key;
        }
        return lastKey != null ? map.get(lastKey) : null;
    }

    public static void clearPendingConfirmation(UUID playerUUID, String alias) {
        LinkedHashMap<String, ConfirmationState> map = pendingConfirmations.get(playerUUID);
        if (map == null || map.isEmpty()) {
            return;
        }
        if (alias != null && !alias.isBlank()) {
            map.remove(alias.toLowerCase(Locale.ROOT));
        } else {
            String lastKey = null;
            for (String key : map.keySet()) {
                lastKey = key;
            }
            if (lastKey != null) {
                map.remove(lastKey);
            }
        }
        if (map.isEmpty()) {
            pendingConfirmations.remove(playerUUID);
        }
    }

    public static boolean isAwaitingConfirmation(UUID playerUUID) {
        LinkedHashMap<String, ConfirmationState> map = pendingConfirmations.get(playerUUID);
        return map != null && !map.isEmpty();
    }

    public static Map<String, ConfirmationState> snapshotConfirmations(UUID playerUUID) {
        LinkedHashMap<String, ConfirmationState> map = pendingConfirmations.get(playerUUID);
        if (map == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(map);
    }
}
