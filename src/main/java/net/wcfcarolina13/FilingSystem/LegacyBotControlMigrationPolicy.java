package net.wcfcarolina13.FilingSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure rules for retiring the old global {@code botControls} map (alias to settings) in favour of
 * {@code botControlsByWorld} (alias to world key to settings). No Minecraft types.
 *
 * <p>A global entry whose alias has no per-world entries yet moves under the {@link #LEGACY_KEY}
 * world key, and the first world that asks for that alias claims it ({@link #claimLegacy}). A
 * global entry whose alias already has per-world entries is dropped: the per-world settings win.
 */
public final class LegacyBotControlMigrationPolicy {

    /** World key that holds a migrated global entry until the first real world claims it. */
    public static final String LEGACY_KEY = "_legacy";

    private LegacyBotControlMigrationPolicy() {
    }

    /** What {@link #migrate} did: aliases moved under {@code _legacy}, and aliases dropped. */
    public record Result(List<String> migrated, List<String> dropped) {
        public boolean changed() {
            return !migrated.isEmpty() || !dropped.isEmpty();
        }
    }

    /**
     * Moves every global entry into {@code byWorld} (mutated in place). Aliases match ignoring
     * case and surrounding whitespace. Null or blank aliases and null settings are skipped.
     * {@code global} is only read. Running it again over the same maps changes nothing more.
     */
    public static <T> Result migrate(Map<String, T> global, Map<String, Map<String, T>> byWorld) {
        List<String> migrated = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        if (global == null || global.isEmpty() || byWorld == null) {
            return new Result(migrated, dropped);
        }
        for (Map.Entry<String, T> entry : global.entrySet()) {
            String alias = entry.getKey();
            T settings = entry.getValue();
            if (alias == null || alias.isBlank() || settings == null) {
                continue;
            }
            String existingKey = resolveAliasKey(byWorld, alias);
            Map<String, T> existing = existingKey != null ? byWorld.get(existingKey) : null;
            if (existing == null || existing.isEmpty()) {
                Map<String, T> worldMap = new HashMap<>();
                worldMap.put(LEGACY_KEY, settings);
                if (existingKey != null) {
                    byWorld.remove(existingKey);
                }
                byWorld.put(alias.trim(), worldMap);
                migrated.add(alias.trim());
            } else if (!existing.containsKey(LEGACY_KEY) || existing.get(LEGACY_KEY) != settings) {
                dropped.add(alias.trim());
            }
        }
        return new Result(migrated, dropped);
    }

    /**
     * Moves the {@code _legacy} entry of one alias to {@code worldKey} and returns it, or returns
     * null when there is none. The first world to call this takes it, later worlds get null.
     */
    public static <T> T claimLegacy(Map<String, T> worldMap, String worldKey) {
        if (worldMap == null || worldKey == null || worldKey.isBlank() || LEGACY_KEY.equals(worldKey)) {
            return null;
        }
        T legacy = worldMap.remove(LEGACY_KEY);
        if (legacy != null) {
            worldMap.put(worldKey, legacy);
        }
        return legacy;
    }

    private static <T> String resolveAliasKey(Map<String, T> map, String alias) {
        if (map.isEmpty()) {
            return null;
        }
        String trimmed = alias.trim();
        if (map.containsKey(trimmed)) {
            return trimmed;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        for (String key : map.keySet()) {
            if (key != null && key.trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return key;
            }
        }
        return null;
    }
}
