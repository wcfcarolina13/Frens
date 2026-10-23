package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client render thread reads the alias maps every tick while the integrated server thread adds
 * a new bot's entries on /bot spawn; HashMaps threw ConcurrentModificationException in
 * getAllBotAliases (1.1.295 sandbox).
 */
class ManualConfigConcurrentAliasTest {

    private static ManualConfig newConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static ManualConfig.BotSpawn spawn() {
        return new ManualConfig.BotSpawn("Nirn", "minecraft:overworld", 1, 64, 2, 0f, 0f);
    }

    @Test
    void readingAliasesWhileAnotherThreadAddsBotsNeverThrows() throws Exception {
        ManualConfig cfg = newConfig();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                int i = 0;
                while (!stop.get()) {
                    String alias = "Bot" + i++;
                    cfg.setBotProfile(alias, UUID.randomUUID().toString());
                    cfg.setOwner(alias, new ManualConfig.BotOwnership(UUID.randomUUID().toString(), "Owner"));
                    cfg.setBotSpawn(alias, "Nirn", spawn());
                    cfg.getOrCreateBotControl(alias, "Nirn");
                    cfg.getOrCreateBotQuestMemory(alias);
                    if (i % 7 == 0) {
                        cfg.removeBotEntry("Bot" + (i - 3));
                    }
                }
            } catch (Throwable t) {
                writerError.set(t);
            }
        }, "fake-server-thread");
        writer.start();
        try {
            long until = System.nanoTime() + 300_000_000L;
            while (System.nanoTime() < until) {
                cfg.getAllBotAliases();
                cfg.getBotGameProfile().keySet().forEach(k -> { });
                cfg.getBotOwnership().keySet().forEach(k -> { });
                cfg.getBotQuestMemory().keySet().forEach(k -> { });
            }
        } finally {
            stop.set(true);
            writer.join(5_000);
        }
        assertNull(writerError.get());
        assertFalse(cfg.getAllBotAliases().isEmpty());
    }

    @Test
    void loadStepsReWrapGsonMapsAsConcurrentAndDropNulls() throws Exception {
        ManualConfig cfg = newConfig();
        Map<String, Map<String, ManualConfig.BotSpawn>> spawns = new LinkedHashMap<>();
        spawns.put("Jake", new HashMap<>(Map.of("Nirn", spawn())));
        spawns.put("Ghost", null);
        setField(cfg, "botSpawnPointsByWorld", spawns);
        setField(cfg, "botControlsByWorld", new LinkedHashMap<>());
        Map<String, String> profiles = new HashMap<>();
        profiles.put("Jake", "uuid-jake");
        profiles.put("Nobody", null);
        setField(cfg, "botGameProfile", profiles);
        setField(cfg, "botOwnership", new HashMap<>());
        setField(cfg, "botQuestMemory", new HashMap<>());

        Method normalize = ManualConfig.class.getDeclaredMethod("normalizeAliasBackedMaps");
        normalize.setAccessible(true);
        normalize.invoke(cfg);

        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotSpawnPointsByWorld());
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotControlsByWorld());
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotGameProfile());
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotOwnership());
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotQuestMemory());
        assertEquals("uuid-jake", cfg.getBotProfileUuid("jake"));
        assertFalse(cfg.getBotGameProfile().containsKey("Nobody"));
        assertFalse(cfg.getBotSpawnPointsByWorld().containsKey("Ghost"));
        assertTrue(cfg.getAllBotAliases().contains("Jake"));
    }

    @Test
    void setterPathsStayConcurrent() throws Exception {
        ManualConfig cfg = newConfig();
        Map<String, String> profiles = new HashMap<>();
        profiles.put("Bob", "uuid-bob");
        profiles.put("Empty", null);
        cfg.setBotGameProfile(profiles);
        cfg.setBotOwnership(new HashMap<>());
        cfg.setOwner("Bob", null);
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotGameProfile());
        assertInstanceOf(ConcurrentHashMap.class, cfg.getBotOwnership());
        assertEquals(Map.of("Bob", "uuid-bob"), cfg.getBotGameProfile());
        assertTrue(cfg.getBotOwnership().isEmpty());
    }

    private static void setField(ManualConfig cfg, String name, Object value) throws Exception {
        var field = ManualConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(cfg, value);
    }
}
