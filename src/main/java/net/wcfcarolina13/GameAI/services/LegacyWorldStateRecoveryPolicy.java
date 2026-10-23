package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.Optional;

/** Explicit recovery of ambiguous level-name entries without consuming the original. */
public final class LegacyWorldStateRecoveryPolicy {
    public enum Result { IMPORTED, TARGET_EXISTS, NO_LEGACY, WRONG_CONFIRMATION, INVALID_TARGET, SAVE_FAILED }

    private LegacyWorldStateRecoveryPolicy() {}

    public static <T> Optional<T> current(Map<String, T> states, String targetKey) {
        return states == null ? Optional.empty() : Optional.ofNullable(states.get(targetKey));
    }

    public static <T> Result copyLegacy(Map<String, T> states, String legacyKey,
                                        String targetKey, String confirmation) {
        if (!knownTarget(legacyKey, targetKey)) return Result.INVALID_TARGET;
        if (!targetKey.equals(confirmation)) return Result.WRONG_CONFIRMATION;
        if (states == null || states.get(legacyKey) == null) return Result.NO_LEGACY;
        if (states.containsKey(targetKey)) return Result.TARGET_EXISTS;
        states.put(targetKey, states.get(legacyKey));
        return Result.IMPORTED;
    }

    public static <T> boolean clearCurrent(Map<String, T> states, String legacyKey, String targetKey) {
        return states != null && targetKey != null && !targetKey.equals(legacyKey)
                && states.remove(targetKey) != null;
    }

    public static <T> boolean rollback(Map<String, T> states, String targetKey, T imported) {
        return states != null && imported != null && states.remove(targetKey, imported);
    }

    private static boolean knownTarget(String legacyKey, String targetKey) {
        if (targetKey == null || legacyKey == null || targetKey.equals(legacyKey)) return false;
        int separator = targetKey.lastIndexOf('#');
        return separator >= 0 && separator < targetKey.length() - 1
                && !targetKey.substring(separator + 1).equals("unknown");
    }
}
