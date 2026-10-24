package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void botsLogOffWhenCantSleepIsCapturedAndApplied() throws Exception {
        ManualConfig cfg = newRealConfig();
        assertTrue(cfg.isBotsLogOffWhenCantSleepEnabled(), "default on");
        assertEquals(Boolean.TRUE, SharedConfig.capture(cfg).botsLogOffWhenCantSleep);

        cfg.setBotsLogOffWhenCantSleepEnabled(false);
        SharedConfig off = SharedConfig.capture(cfg);
        assertEquals(Boolean.FALSE, off.botsLogOffWhenCantSleep);

        ManualConfig other = newRealConfig();
        off.applyTo(other);
        assertFalse(other.isBotsLogOffWhenCantSleepEnabled());

        // A snapshot from an older peer (field absent) leaves the local value alone.
        new SharedConfig().applyTo(other);
        assertFalse(other.isBotsLogOffWhenCantSleepEnabled());
    }

    @Test
    void diskFileMissingTheLogOffFieldKeepsTheDefaultOn() {
        // load() parses with a plain Gson over the private constructor, so the initializer survives.
        ManualConfig fromOld = new Gson().fromJson("{\"gameplayTipsEnabled\":false}", ManualConfig.class);
        assertTrue(fromOld.isBotsLogOffWhenCantSleepEnabled());
        assertFalse(fromOld.isGameplayTipsEnabled());

        ManualConfig fromOff = new Gson().fromJson("{\"botsLogOffWhenCantSleep\":false}", ManualConfig.class);
        assertFalse(fromOff.isBotsLogOffWhenCantSleepEnabled());
        ManualConfig back = new Gson().fromJson(new Gson().toJson(fromOff), ManualConfig.class);
        assertFalse(back.isBotsLogOffWhenCantSleepEnabled());
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

    @Test
    void captureNeverCarriesAServerWorldKey() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.setRemoteAuthoritative(true);
        cfg.setRemoteWorldKey("Nirn#4a9f00d9");

        assertNull(SharedConfig.capture(cfg).serverWorldKey);
        assertFalse(net.wcfcarolina13.network.ConfigJsonUtil.configToJson(cfg).contains("serverWorldKey"));
        assertTrue(net.wcfcarolina13.network.ConfigJsonUtil.configToJson(cfg, "Nirn#4a9f00d9")
                .contains("\"serverWorldKey\":\"Nirn#4a9f00d9\""));
    }

    @Test
    void remoteClientKeepsTheServerWorldKeyUntilDisconnect() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.setRemoteAuthoritative(true);
        String json = net.wcfcarolina13.network.ConfigJsonUtil.configToJson(newRealConfig(), "Nirn#4a9f00d9");

        assertTrue(net.wcfcarolina13.network.ConfigJsonUtil.applyConfigJson(json, cfg));
        assertEquals("Nirn#4a9f00d9", cfg.getRemoteWorldKey());
        assertEquals("Nirn#4a9f00d9", cfg.botControlEditWorldKey(null));
        assertFalse(new Gson().toJson(cfg).contains("Nirn#4a9f00d9"), "transient key leaked into the config JSON");

        // A sync without a key (a client save echoed back) leaves it alone.
        new SharedConfig().applyTo(cfg);
        assertEquals("Nirn#4a9f00d9", cfg.getRemoteWorldKey());

        cfg.setRemoteAuthoritative(false);
        assertNull(cfg.getRemoteWorldKey());
        assertNull(cfg.botControlEditWorldKey(null));
    }

    @Test
    void singlePlayerIgnoresTheSyncedKeyAndUsesItsOwnWorld() throws Exception {
        ManualConfig cfg = newRealConfig();
        SharedConfig sync = new SharedConfig();
        sync.serverWorldKey = "Nirn#4a9f00d9";
        sync.applyTo(cfg);

        assertNull(cfg.getRemoteWorldKey());
        assertEquals("Local#1", cfg.botControlEditWorldKey("Local#1"));
    }

    @Test
    void sharedSaveKeepsUntouchedDefaultOutOfTheFile() throws Exception {
        ManualConfig cfg = newRealConfig();
        SharedConfig snapshot = new Gson().fromJson(
                net.wcfcarolina13.network.ConfigJsonUtil.configToJson(cfg), SharedConfig.class);
        assertEquals("unified", snapshot.pathfinder);
        assertEquals(Boolean.FALSE, snapshot.pathfinderExplicitlySet);

        snapshot.applyTo(cfg);

        assertEquals("unified", cfg.getPathfinder());
        assertFalse(cfg.isPathfinderExplicitlySet());
        assertFalse(cfg.toSavedJson().has("pathfinder"));
        assertFalse(cfg.toSavedJson().has("pathfinderExplicitlySet"));
        assertTrue(cfg.toSavedJson().has("textDialogueEnabled"));
    }

    @Test
    void explicitDefaultSurvivesSharedSaveAndFileReload() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.setPathfinder("unified");
        SharedConfig.capture(cfg).applyTo(cfg);

        assertTrue(cfg.isPathfinderExplicitlySet());
        assertEquals("unified", cfg.toSavedJson().get("pathfinder").getAsString());
        ManualConfig reloaded = new Gson().fromJson(cfg.toSavedJson(), ManualConfig.class);
        assertTrue(reloaded.isPathfinderExplicitlySet());
        assertEquals("unified", reloaded.getPathfinder());
    }

    @Test
    void legacyFilesAndPeersKeepTheirExplicitChoice() throws Exception {
        for (String choice : List.of("unified", "baritone", "classic")) {
            String json = "{\"pathfinder\":\"" + choice + "\"}";
            ManualConfig loaded = new Gson().fromJson(json, ManualConfig.class);
            assertTrue(loaded.isPathfinderExplicitlySet());
            assertEquals(choice, loaded.toSavedJson().get("pathfinder").getAsString());

            ManualConfig target = newRealConfig();
            new Gson().fromJson(json, SharedConfig.class).applyTo(target);
            assertTrue(target.isPathfinderExplicitlySet());
            assertEquals(choice, target.toSavedJson().get("pathfinder").getAsString());
        }
    }

    @Test
    void disconnectRestoresImplicitDefaultWithoutPinningIt() throws Exception {
        ManualConfig cfg = newRealConfig();
        SharedConfig local = SharedConfig.capture(cfg);
        ManualConfig remote = newRealConfig();
        remote.setPathfinder("baritone");
        SharedConfig.capture(remote).applyTo(cfg);
        assertEquals("baritone", cfg.getPathfinder());
        assertTrue(cfg.isPathfinderExplicitlySet());

        local.applyTo(cfg);

        assertEquals("unified", cfg.getPathfinder());
        assertFalse(cfg.isPathfinderExplicitlySet());
        assertFalse(cfg.toSavedJson().has("pathfinder"));
        cfg.setPathfinderChoice(net.wcfcarolina13.PathFinding.PathFinderChoice.UNIFIED);
        assertEquals("unified", cfg.toSavedJson().get("pathfinder").getAsString());
    }
}
