package net.wcfcarolina13.GameAI.services;

import java.util.Locale;

/**
 * Canonical spawn mode for a typed or saved mode name. The only modes are {@code admin} and
 * {@code questing}. Training mode was removed in 1.1.244, so the old {@code training}/{@code train}
 * names (still present in saved configs) load as admin. Pure so it can be tested without Minecraft.
 */
public final class SpawnModePolicy {

    private SpawnModePolicy() {
    }

    /** Returns {@code "admin"}, {@code "questing"}, or {@code null} for blank or unknown input. */
    public static String normalizeOrNull(String rawMode) {
        if (rawMode == null) {
            return null;
        }
        return switch (rawMode.trim().toLowerCase(Locale.ROOT)) {
            case "admin", "play", "training", "train" -> "admin";
            case "questing", "quest" -> "questing";
            default -> null;
        };
    }
}
