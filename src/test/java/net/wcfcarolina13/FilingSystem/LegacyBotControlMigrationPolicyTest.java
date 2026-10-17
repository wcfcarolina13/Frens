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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Never calls {@code save()} / {@code load()}: these tests must not touch disk. */
class LegacyBotControlMigrationPolicyTest {

    private static final String LEGACY = LegacyBotControlMigrationPolicy.LEGACY_KEY;

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void globalOnlyAliasMovesUnderLegacy() {
        Map<String, String> global = new HashMap<>(Map.of("Jake", "jake-settings"));
        Map<String, Map<String, String>> byWorld = new HashMap<>();

        LegacyBotControlMigrationPolicy.Result result = LegacyBotControlMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("Jake"), result.migrated());
        assertTrue(result.dropped().isEmpty());
        assertEquals(Map.of(LEGACY, "jake-settings"), byWorld.get("Jake"));
    }

    @Test
    void aliasWithPerWorldEntryKeepsPerWorldAndReportsTheDrop() {
        Map<String, String> global = new HashMap<>(Map.of("Jake", "global"));
        Map<String, Map<String, String>> byWorld = new HashMap<>();
        byWorld.put("Jake", new HashMap<>(Map.of("Nirn#1", "per-world")));

        LegacyBotControlMigrationPolicy.Result result = LegacyBotControlMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("Jake"), result.dropped());
        assertTrue(result.migrated().isEmpty());
        assertEquals(Map.of("Nirn#1", "per-world"), byWorld.get("Jake"));
    }

    @Test
    void aliasesMatchIgnoringCaseAndWhitespace() {
        Map<String, String> global = new HashMap<>(Map.of(" jake ", "global"));
        Map<String, Map<String, String>> byWorld = new HashMap<>();
        byWorld.put("JAKE", new HashMap<>(Map.of("Nirn#1", "per-world")));

        LegacyBotControlMigrationPolicy.Result result = LegacyBotControlMigrationPolicy.migrate(global, byWorld);

        assertEquals(List.of("jake"), result.dropped());
        assertEquals(1, byWorld.size());
        assertEquals(Map.of("Nirn#1", "per-world"), byWorld.get("JAKE"));
    }

    @Test
    void nullAndBlankEntriesAreSkipped() {
        Map<String, String> global = new HashMap<>();
        global.put(null, "a");
        global.put("  ", "b");
        global.put("Bob", null);
        Map<String, Map<String, String>> byWorld = new HashMap<>();

        LegacyBotControlMigrationPolicy.Result result = LegacyBotControlMigrationPolicy.migrate(global, byWorld);

        assertFalse(result.changed());
        assertTrue(byWorld.isEmpty());
        assertFalse(LegacyBotControlMigrationPolicy.migrate(null, byWorld).changed());
    }

    @Test
    void runningTwiceChangesNothingMore() {
        Map<String, String> global = new HashMap<>(Map.of("Jake", "jake", "Bob", "bob"));
        Map<String, Map<String, String>> byWorld = new HashMap<>();
        byWorld.put("Bob", new HashMap<>(Map.of("Nirn#1", "bob-world")));

        LegacyBotControlMigrationPolicy.migrate(global, byWorld);
        Map<String, Map<String, String>> afterFirst = new HashMap<>();
        byWorld.forEach((k, v) -> afterFirst.put(k, new HashMap<>(v)));

        LegacyBotControlMigrationPolicy.Result second = LegacyBotControlMigrationPolicy.migrate(global, byWorld);

        assertTrue(second.migrated().isEmpty());
        assertEquals(afterFirst, byWorld);
    }

    @Test
    void legacyEntryIsTakenByTheFirstWorldOnly() {
        Map<String, String> worldMap = new HashMap<>(Map.of(LEGACY, "old"));

        assertEquals("old", LegacyBotControlMigrationPolicy.claimLegacy(worldMap, "Nirn#1"));
        assertNull(LegacyBotControlMigrationPolicy.claimLegacy(worldMap, "Other#2"));
        assertEquals(Map.of("Nirn#1", "old"), worldMap);
        assertNull(LegacyBotControlMigrationPolicy.claimLegacy(worldMap, " "));
    }

    @Test
    void configAccessorHandsLegacyToTheFirstWorldAndFreshDefaultsToTheNext() throws Exception {
        ManualConfig cfg = newRealConfig();
        ManualConfig.BotControlSettings legacy = new ManualConfig.BotControlSettings();
        legacy.setGameMode("creative");
        cfg.getBotControlsByWorld().put("Jake", new HashMap<>(Map.of(LEGACY, legacy)));

        assertSame(legacy, cfg.getOrCreateBotControl("jake", "Nirn#1"));
        ManualConfig.BotControlSettings second = cfg.getOrCreateBotControl("Jake", "Other#2");
        assertNotSame(legacy, second);
        assertFalse(cfg.getBotControlsByWorld().get("Jake").containsKey(LEGACY));
    }

    @Test
    void blankWorldKeyGivesDetachedDefaultsAndNoStoredEntry() throws Exception {
        ManualConfig cfg = newRealConfig();

        ManualConfig.BotControlSettings detached = cfg.getOrCreateBotControl("Jake", null);
        detached.setGameMode("creative");

        assertTrue(cfg.getBotControlsByWorld().isEmpty());
        assertNotSame(detached, cfg.getOrCreateBotControl("Jake", " "));
        assertNull(cfg.getEffectiveBotControl("Jake", null));
        assertNull(cfg.getEffectiveBotControl("Jake", "Nirn#1"));
    }

    @Test
    void migrationEmptiesTheGlobalKeyFromTheSavedJson() throws Exception {
        ManualConfig cfg = newRealConfig();
        Field global = ManualConfig.class.getDeclaredField("botControls");
        global.setAccessible(true);
        Map<String, ManualConfig.BotControlSettings> old = new HashMap<>();
        old.put("Jake", new ManualConfig.BotControlSettings());
        global.set(cfg, old);

        Method migrate = ManualConfig.class.getDeclaredMethod("migrateLegacyToPerWorld");
        migrate.setAccessible(true);
        migrate.invoke(cfg);

        assertNull(global.get(cfg));
        assertTrue(cfg.getBotControlsByWorld().get("Jake").containsKey(LEGACY));
        assertFalse(new Gson().toJson(cfg).contains("\"botControls\""));

        // A file that loaded the key as an empty map loses it too.
        global.set(cfg, new HashMap<>());
        migrate.invoke(cfg);
        assertNull(global.get(cfg));
    }
}
