package net.wcfcarolina13.GameAI.services;

import java.util.List;

/**
 * The name auto-spawn spawns a bot under. Its candidates come from stores whose keys are lower-cased
 * (bot_world_state.json), so after the settings file was reset a bot came back as "alex" for
 * "Alex": a different offline UUID, so a different player. The bot's name as spelled wins when a
 * store recorded it: first the saved world-state name, then the user-cache names for the UUIDs in
 * its inventory snapshot file names. A candidate counts only when it is the same name ignoring case.
 */
public final class AutoSpawnNamePolicy {

    private AutoSpawnNamePolicy() {
    }

    /**
     * @param savedName  the name the world-state entry recorded (authoritative when it matches), or null
     * @param cacheNames user-cache names for the UUIDs in the alias's snapshot file names. One spelled
     *                   with capitals beats an all-lower-case one: a lower-case spelling is what this
     *                   bug itself leaves behind (the "guardy" player it created is cached too).
     */
    public static String spawnName(String alias, String savedName, List<String> cacheNames) {
        if (alias == null) {
            return null;
        }
        String trimmed = alias.trim();
        if (matches(savedName, trimmed)) {
            return savedName.trim();
        }
        String lowerMatch = null;
        if (cacheNames != null) {
            for (String candidate : cacheNames) {
                if (!matches(candidate, trimmed)) {
                    continue;
                }
                String name = candidate.trim();
                if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                    return name;
                }
                lowerMatch = name;
            }
        }
        return lowerMatch != null ? lowerMatch : trimmed;
    }

    private static boolean matches(String candidate, String alias) {
        return candidate != null && candidate.trim().equalsIgnoreCase(alias);
    }
}
