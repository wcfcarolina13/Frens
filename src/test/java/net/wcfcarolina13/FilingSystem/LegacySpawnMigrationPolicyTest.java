package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Never calls {@code save()} / {@code load()}: these tests must not touch disk. */
class LegacySpawnMigrationPolicyTest {

    private static final String LEGACY = LegacySpawnMigrationPolicy.LEGACY_KEY;

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static ManualConfig.BotSpawn spawn(String levelName) {
        return new ManualConfig.BotSpawn(levelName, "minecraft:overworld", 1, 64, 2, 0f, 0f);
    }

    /** Runs the two private load steps in load order: migrate, then alias normalisation. */
    private static void runLoadSteps(ManualConfig cfg) throws Exception {
        Method migrate = ManualConfig.class.getDeclaredMethod("migrateLegacyToPerWorld");
        migrate.setAccessible(true);
        migrate.invoke(cfg);
        Method normalize = ManualConfig.class.getDeclaredMethod("normalizeAliasBackedMaps");
        normalize.setAccessible(true);
        normalize.invoke(cfg);
    }

    @Test
    void globalSpawnMovesUnderItsLevelName() {
        ManualConfig.BotSpawn jake = spawn("Nirn");
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>(Map.of("Jake", jake));
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();

        LegacySpawnMigrationPolicy.Result result = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("Jake"), result.migrated());
        assertTrue(result.dropped().isEmpty());
        assertEquals(Map.of("Nirn", jake), byWorld.get("Jake"));
    }

    @Test
    void spawnWithoutLevelNameIsDropped() {
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>();
        global.put("Jake", spawn(null));
        global.put("Bob", spawn("  "));
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();

        LegacySpawnMigrationPolicy.Result result = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertEquals(2, result.dropped().size());
        assertTrue(result.dropped().containsAll(List.of("Jake", "Bob")));
        assertTrue(result.migrated().isEmpty());
        assertTrue(byWorld.isEmpty());
    }

    @Test
    void aliasWithPerWorldSpawnKeepsItAndReportsTheDrop() {
        ManualConfig.BotSpawn perWorld = spawn("Nirn#1");
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>(Map.of("Jake", spawn("Nirn")));
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();
        byWorld.put("Jake", new HashMap<>(Map.of("Nirn#1", perWorld)));

        LegacySpawnMigrationPolicy.Result result = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("Jake"), result.dropped());
        assertTrue(result.migrated().isEmpty());
        assertEquals(Map.of("Nirn#1", perWorld), byWorld.get("Jake"));
    }

    @Test
    void aliasesMatchIgnoringCaseAndWhitespace() {
        ManualConfig.BotSpawn perWorld = spawn("Nirn#1");
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>(Map.of(" jake ", spawn("Nirn")));
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();
        byWorld.put("JAKE", new HashMap<>(Map.of("Nirn#1", perWorld)));

        LegacySpawnMigrationPolicy.Result result = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("jake"), result.dropped());
        assertEquals(1, byWorld.size());
        assertEquals(Map.of("Nirn#1", perWorld), byWorld.get("JAKE"));
    }

    @Test
    void nullAndBlankEntriesAreSkipped() {
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>();
        global.put(null, spawn("Nirn"));
        global.put("  ", spawn("Nirn"));
        global.put("Bob", null);
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();

        LegacySpawnMigrationPolicy.Result result = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertFalse(result.changed());
        assertTrue(byWorld.isEmpty());
        assertFalse(LegacySpawnMigrationPolicy.migrate(null, byWorld).changed());
    }

    @Test
    void runningTwiceChangesNothingMore() {
        Map<String, ManualConfig.BotSpawn> global = new HashMap<>(Map.of("Jake", spawn("Nirn"), "Bob", spawn("Nirn")));
        Map<String, Map<String, ManualConfig.BotSpawn>> byWorld = new HashMap<>();
        byWorld.put("Bob", new HashMap<>(Map.of("Nirn#1", spawn("Nirn#1"))));

        LegacySpawnMigrationPolicy.migrate(global, byWorld);
        Map<String, Map<String, ManualConfig.BotSpawn>> afterFirst = new HashMap<>();
        byWorld.forEach((k, v) -> afterFirst.put(k, new HashMap<>(v)));

        LegacySpawnMigrationPolicy.Result second = LegacySpawnMigrationPolicy.migrate(global, byWorld);

        assertTrue(second.migrated().isEmpty());
        assertEquals(List.of("Bob"), second.dropped());
        assertEquals(afterFirst, byWorld);
    }

    @Test
    void purgeRemovesLegacySlotsAndEmptiedAliases() {
        Map<String, Map<String, String>> byWorld = new HashMap<>();
        byWorld.put("Jake", new HashMap<>(Map.of(LEGACY, "old")));
        byWorld.put("Bob", new HashMap<>(Map.of(LEGACY, "old", "Nirn#1", "real")));

        List<String> purged = LegacySpawnMigrationPolicy.purgeLegacySlots(byWorld);

        assertEquals(2, purged.size());
        assertTrue(purged.containsAll(List.of("Jake", "Bob")));
        assertEquals(Map.of("Bob", Map.of("Nirn#1", "real")), byWorld);
    }

    @Test
    void purgeLeavesRealSlotsAlone() {
        Map<String, Map<String, String>> byWorld = new HashMap<>();
        byWorld.put("Jake", new HashMap<>(Map.of("Nirn#1", "a", "Nirn", "b")));
        byWorld.put("Bob", null);

        assertTrue(LegacySpawnMigrationPolicy.purgeLegacySlots(byWorld).isEmpty());
        assertEquals(Map.of("Nirn#1", "a", "Nirn", "b"), byWorld.get("Jake"));
        assertTrue(LegacySpawnMigrationPolicy.purgeLegacySlots(null).isEmpty());
    }

    @Test
    void loadStepsEmptyTheGlobalKeyFromTheSavedJson() throws Exception {
        ManualConfig cfg = newRealConfig();
        Field global = ManualConfig.class.getDeclaredField("botSpawnPoints");
        global.setAccessible(true);
        ManualConfig.BotSpawn jake = spawn("Nirn");
        global.set(cfg, new HashMap<>(Map.of("Jake", jake)));
        cfg.getBotSpawnPointsByWorld().put("Bob", new HashMap<>(Map.of(LEGACY, spawn(null))));

        runLoadSteps(cfg);

        assertNull(global.get(cfg));
        assertSame(jake, cfg.getBotSpawn("jake", "Nirn"));
        assertFalse(cfg.getBotSpawnPointsByWorld().containsKey("Bob"));
        assertFalse(new Gson().toJson(cfg).contains("\"botSpawnPoints\""));

        // A file that loaded the key as an empty map loses it too.
        global.set(cfg, new HashMap<>());
        runLoadSteps(cfg);
        assertNull(global.get(cfg));
        assertFalse(new Gson().toJson(cfg).contains("\"botSpawnPoints\""));
    }
}
