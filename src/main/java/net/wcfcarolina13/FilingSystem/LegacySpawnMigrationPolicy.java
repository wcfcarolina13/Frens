package net.wcfcarolina13.FilingSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure rules for retiring the old global {@code botSpawnPoints} map (alias to spawn) in favour of
 * {@code botSpawnPointsByWorld} (alias to world key to spawn). No Minecraft types.
 *
 * <p>A global spawn moves under its own level name as the world key. A spawn with no level name
 * cannot be placed in any world and is dropped, and so is a global spawn whose alias already has
 * per-world spawns: the per-world spawns win. Old {@code _legacy} world slots, left by the
 * previous migration, are removed by {@link #purgeLegacySlots}: they belong to no world.
 */
public final class LegacySpawnMigrationPolicy {

    /** World key the previous migration used for a spawn with no level name. */
    static final String LEGACY_KEY = "_legacy";

    private LegacySpawnMigrationPolicy() {
    }

    /** What {@link #migrate} did: aliases moved under their level name, and aliases dropped. */
    public record Result(List<String> migrated, List<String> dropped) {
        public boolean changed() {
            return !migrated.isEmpty() || !dropped.isEmpty();
        }
    }

    /**
     * Moves every global spawn into {@code byWorld} (mutated in place) under its level name.
     * Aliases match ignoring case and surrounding whitespace. Null or blank aliases and null
     * spawns are skipped. {@code global} is only read. Running it again over the same maps
     * changes nothing more.
     */
    public static Result migrate(Map<String, ManualConfig.BotSpawn> global,
                                 Map<String, Map<String, ManualConfig.BotSpawn>> byWorld) {
        List<String> migrated = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        if (global == null || global.isEmpty() || byWorld == null) {
            return new Result(migrated, dropped);
        }
        for (Map.Entry<String, ManualConfig.BotSpawn> entry : global.entrySet()) {
            String alias = entry.getKey();
            ManualConfig.BotSpawn spawn = entry.getValue();
            if (alias == null || alias.isBlank() || spawn == null) {
                continue;
            }
            String levelName = spawn.levelName();
            String existingKey = resolveAliasKey(byWorld, alias);
            Map<String, ManualConfig.BotSpawn> existing = existingKey != null ? byWorld.get(existingKey) : null;
            if (levelName != null && !levelName.isBlank() && existing != null && existing.get(levelName) == spawn) {
                continue; // already moved by an earlier run
            }
            if (levelName == null || levelName.isBlank() || (existing != null && !existing.isEmpty())) {
                dropped.add(alias.trim());
                continue;
            }
            Map<String, ManualConfig.BotSpawn> worldMap = new HashMap<>();
            worldMap.put(levelName, spawn);
            if (existingKey != null) {
                byWorld.remove(existingKey);
            }
            byWorld.put(alias.trim(), worldMap);
            migrated.add(alias.trim());
        }
        return new Result(migrated, dropped);
    }

    /**
     * Removes every {@code _legacy} world slot from {@code byWorld} (mutated in place), and any
     * alias left with no slots. Returns the aliases that lost a {@code _legacy} slot.
     */
    public static <T> List<String> purgeLegacySlots(Map<String, Map<String, T>> byWorld) {
        List<String> purged = new ArrayList<>();
        if (byWorld == null || byWorld.isEmpty()) {
            return purged;
        }
        Iterator<Map.Entry<String, Map<String, T>>> it = byWorld.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Map<String, T>> entry = it.next();
            Map<String, T> worldMap = entry.getValue();
            if (worldMap == null || !worldMap.containsKey(LEGACY_KEY)) {
                continue;
            }
            worldMap.remove(LEGACY_KEY);
            purged.add(entry.getKey());
            if (worldMap.isEmpty()) {
                it.remove();
            }
        }
        return purged;
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
