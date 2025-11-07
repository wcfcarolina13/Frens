package net.shasankp000.ChatUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatContextManager {
    private static final Map<UUID, ClarificationState> pendingClarifications = new HashMap<>();
    private static final Map<UUID, ConfirmationState> pendingConfirmations = new HashMap<>();

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
        pendingConfirmations.put(playerUUID, state);
    }

    public static ConfirmationState getPendingConfirmation(UUID playerUUID) {
        return pendingConfirmations.get(playerUUID);
    }

    public static void clearPendingConfirmation(UUID playerUUID) {
        pendingConfirmations.remove(playerUUID);
    }

    public static boolean isAwaitingConfirmation(UUID playerUUID) {
        return pendingConfirmations.containsKey(playerUUID);
    }
}
