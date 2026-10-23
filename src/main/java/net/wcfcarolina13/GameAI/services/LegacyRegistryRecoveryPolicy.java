package net.wcfcarolina13.GameAI.services;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Confirmed recovery keeps an independent copy in the selected save and retains the source. */
public final class LegacyRegistryRecoveryPolicy {
    public enum Result { IMPORTED, TARGET_EXISTS, NO_LEGACY, WRONG_CONFIRMATION, INVALID_TARGET, SAVE_FAILED }
    public record Copy<T>(Result result, T imported) {}
    public record Preview(String legacyKey, String targetKey, int records, boolean targetExists,
                          List<String> entries) {}

    private LegacyRegistryRecoveryPolicy() {}

    public static <T> Copy<T> copyLegacy(Map<String, T> worlds, String legacyKey, String targetKey,
                                         String fingerprint, String confirmation, Function<T, T> copier) {
        if (fingerprint == null || !fingerprint.contains("#") || fingerprint.endsWith("#unknown")
                || fingerprint.endsWith("#") || targetKey == null || targetKey.equals(legacyKey)
                || !targetKey.startsWith(fingerprint + ":")) return new Copy<>(Result.INVALID_TARGET, null);
        if (!fingerprint.equals(confirmation)) return new Copy<>(Result.WRONG_CONFIRMATION, null);
        if (worlds == null || worlds.get(legacyKey) == null) return new Copy<>(Result.NO_LEGACY, null);
        if (worlds.containsKey(targetKey)) return new Copy<>(Result.TARGET_EXISTS, null);
        T legacy = worlds.get(legacyKey);
        T copied = copier.apply(legacy);
        if (copied == null || copied == legacy) return new Copy<>(Result.SAVE_FAILED, null);
        worlds.put(targetKey, copied);
        return new Copy<>(Result.IMPORTED, copied);
    }

    public static <T> boolean rollback(Map<String, T> worlds, String targetKey, T imported) {
        return imported != null && worlds.remove(targetKey, imported);
    }
}
