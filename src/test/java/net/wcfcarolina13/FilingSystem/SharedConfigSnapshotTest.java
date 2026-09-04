package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the dedicated-server config-sync guard: the pre-sync snapshot must round-trip the local
 * globals back on disconnect, {@code remoteAuthoritative} must never be serialized, and
 * {@code capture(cfg).applyTo(cfg)} on the same instance must not lose collection contents.
 *
 * <p>Never calls {@code save()} / {@code load()} — these tests must not touch disk.
 */
class SharedConfigSnapshotTest {

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void disconnectSnapshotRestoresLocalGlobals() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.setSoulsEnabled(true);
        cfg.setTextDialogueEnabled(true);
        cfg.setFortBufferRadius(9);
        cfg.getMutedTextCategories().clear();
        cfg.getMutedTextCategories().add("GENERAL");
        cfg.getOrCreateBotControl("Jake", "world:local").setGameMode("creative");

        // Snapshot taken on the first remote sync.
        SharedConfig preSync = SharedConfig.capture(cfg);

        // Server's globals land on top of the client's in-memory config.
        SharedConfig remote = new SharedConfig();
        remote.soulsEnabled = false;
        remote.textDialogueEnabled = false;
        remote.fortBufferRadius = 32;
        remote.mutedTextCategories = List.of("COMBAT");
        remote.applyTo(cfg);

        assertFalse(cfg.isSoulsEnabled());
        assertEquals(32, cfg.getFortBufferRadius());
        assertEquals(List.of("COMBAT"), cfg.getMutedTextCategories());

        // Disconnect: the local values come back.
        preSync.applyTo(cfg);

        assertTrue(cfg.isSoulsEnabled());
        assertTrue(cfg.isTextDialogueEnabled());
        assertEquals(9, cfg.getFortBufferRadius());
        assertEquals(List.of("GENERAL"), cfg.getMutedTextCategories());
        // Outer key is the bot alias, inner key is the world.
        Map<String, ManualConfig.BotControlSettings> jake = cfg.getBotControlsByWorld().get("Jake");
        assertTrue(jake != null && jake.containsKey("world:local"));
        assertEquals("creative", jake.get("world:local").getGameMode());
    }

    @Test
    void selfApplyKeepsBotControlsAndTextMask() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.getMutedTextCategories().clear();
        cfg.getMutedTextCategories().add("GENERAL");
        cfg.getMutedTextCategories().add("COMBAT");
        cfg.getOrCreateBotControl("Bob", "world:local").setGameMode("creative");

        SharedConfig.capture(cfg).applyTo(cfg);

        assertEquals(List.of("GENERAL", "COMBAT"), cfg.getMutedTextCategories());
        Map<String, ManualConfig.BotControlSettings> bob = cfg.getBotControlsByWorld().get("Bob");
        assertTrue(bob != null && bob.containsKey("world:local"), "self-apply dropped the bot controls");
        assertEquals("creative", bob.get("world:local").getGameMode());
    }

    @Test
    void remoteAuthoritativeFlagIsNotSerialized() throws Exception {
        ManualConfig cfg = newRealConfig();
        assertFalse(cfg.isRemoteAuthoritative());
        cfg.setRemoteAuthoritative(true);
        assertTrue(cfg.isRemoteAuthoritative());

        String json = new Gson().toJson(cfg);
        assertFalse(json.contains("remoteAuthoritative"), "transient flag leaked into the config JSON");

        cfg.setRemoteAuthoritative(false);
        assertFalse(cfg.isRemoteAuthoritative());
    }
}
