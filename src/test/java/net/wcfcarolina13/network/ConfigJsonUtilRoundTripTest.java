package net.wcfcarolina13.network;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the shared-config snapshot: capture -> JSON -> apply.
 * ManualConfig is built through its private constructor (no file I/O); save()/load()
 * are never called.
 */
class ConfigJsonUtilRoundTripTest {

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** Sets every shared field to a value distinct from the constructor default. */
    private static ManualConfig populated() throws Exception {
        ManualConfig cfg = newRealConfig();
        cfg.setTextDialogueEnabled(false);
        cfg.setVoicedDialogueEnabled(false);
        cfg.setTextCategoryMuted("banter", true);
        cfg.setTextCategoryMuted("combat", true);
        cfg.setGameplayTipsEnabled(false);
        cfg.setIdleHobbiesAnywhereEnabled(true);
        cfg.setPathfinder("classic"); // syncs the legacy flag to false
        cfg.setFortifyForcePlaceEnabled(true);
        cfg.setGlobalTeleportDuringSkills(Boolean.TRUE);
        cfg.setFortBufferRadius(17);
        cfg.setUndergroundLingerMinutes(9);
        cfg.setUndergroundProximityBlocks(41);
        cfg.setSurvivalRecruitmentMode(true);
        cfg.setSoulsEnabled(true);
        cfg.setSoulPartyEnabled(true);
        cfg.setSoulBanterEnabled(false);
        cfg.setSoulLocalChatEnabled(true);
        cfg.setSoulBanterActiveEnabled(true);
        cfg.setSoulMemoryDigestEnabled(false);
        cfg.setSoulNoveltyRejectionEnabled(true);
        cfg.setSoulRelationsEnabled(true);
        cfg.setSoulStructuredOutputEnabled(true);
        cfg.setDialogueScriptedRate(11);
        cfg.setSoulBanterIdleRate(22);
        cfg.setSoulBanterActiveRate(33);
        cfg.setSoulLocalRate(44);
        cfg.setSoulVoiceEnabled(true);
        return cfg;
    }

    private static void assertSharedFieldsEqual(ManualConfig expected, ManualConfig actual) {
        assertEquals(expected.isTextDialogueEnabled(), actual.isTextDialogueEnabled());
        assertEquals(expected.isVoicedDialogueEnabled(), actual.isVoicedDialogueEnabled());
        assertEquals(expected.getMutedTextCategories(), actual.getMutedTextCategories());
        assertEquals(expected.isGameplayTipsEnabled(), actual.isGameplayTipsEnabled());
        assertEquals(expected.isIdleHobbiesAnywhereEnabled(), actual.isIdleHobbiesAnywhereEnabled());
        assertEquals(expected.isBaritonePathfinderEnabled(), actual.isBaritonePathfinderEnabled());
        assertEquals(expected.getPathfinder(), actual.getPathfinder());
        assertEquals(expected.isFortifyForcePlaceEnabled(), actual.isFortifyForcePlaceEnabled());
        assertEquals(expected.getGlobalTeleportDuringSkills(), actual.getGlobalTeleportDuringSkills());
        assertEquals(expected.getFortBufferRadius(), actual.getFortBufferRadius());
        assertEquals(expected.getUndergroundLingerMinutes(), actual.getUndergroundLingerMinutes());
        assertEquals(expected.getUndergroundProximityBlocks(), actual.getUndergroundProximityBlocks());
        assertEquals(expected.isSurvivalRecruitmentMode(), actual.isSurvivalRecruitmentMode());
        assertEquals(expected.isSoulsEnabled(), actual.isSoulsEnabled());
        assertEquals(expected.isSoulPartyEnabled(), actual.isSoulPartyEnabled());
        assertEquals(expected.isSoulBanterEnabled(), actual.isSoulBanterEnabled());
        assertEquals(expected.isSoulLocalChatEnabled(), actual.isSoulLocalChatEnabled());
        assertEquals(expected.isSoulBanterActiveEnabled(), actual.isSoulBanterActiveEnabled());
        assertEquals(expected.isSoulMemoryDigestEnabled(), actual.isSoulMemoryDigestEnabled());
        assertEquals(expected.isSoulNoveltyRejectionEnabled(), actual.isSoulNoveltyRejectionEnabled());
        assertEquals(expected.isSoulRelationsEnabled(), actual.isSoulRelationsEnabled());
        assertEquals(expected.isSoulStructuredOutputEnabled(), actual.isSoulStructuredOutputEnabled());
        assertEquals(expected.getDialogueScriptedRate(), actual.getDialogueScriptedRate());
        assertEquals(expected.getSoulBanterIdleRate(), actual.getSoulBanterIdleRate());
        assertEquals(expected.getSoulBanterActiveRate(), actual.getSoulBanterActiveRate());
        assertEquals(expected.getSoulLocalRate(), actual.getSoulLocalRate());
        assertEquals(expected.isSoulVoiceEnabled(), actual.isSoulVoiceEnabled());
    }

    @Test
    void everySharedFieldSurvivesTheRoundTrip() throws Exception {
        ManualConfig source = populated();
        ManualConfig target = newRealConfig();

        String json = ConfigJsonUtil.configToJson(source);
        assertTrue(ConfigJsonUtil.applyConfigJson(json, target));

        assertSharedFieldsEqual(source, target);
    }

    @Test
    void jsonCarriesNoSecretsOrHostLocalFields() throws Exception {
        String json = ConfigJsonUtil.configToJson(populated());
        for (String forbidden : List.of(
                "openAIKey", "claudeKey", "geminiKey", "grokKey", "customApiKey",
                "ollamaBaseUrl", "botSkins", "mutedVoiceCategories")) {
            assertFalse(json.contains(forbidden), "JSON must not contain " + forbidden + ": " + json);
        }
    }

    @Test
    void malformedJsonReturnsFalseAndLeavesTargetUntouched() throws Exception {
        ManualConfig target = populated();
        ManualConfig pristine = populated();

        assertFalse(ConfigJsonUtil.applyConfigJson("{not json", target));
        assertFalse(ConfigJsonUtil.applyConfigJson(null, target));
        assertFalse(ConfigJsonUtil.applyConfigJson("   ", target));
        assertFalse(ConfigJsonUtil.applyConfigJson("[1,2,3]", target));
        assertFalse(ConfigJsonUtil.applyConfigJson("{}", null));

        assertSharedFieldsEqual(pristine, target);
    }

    @Test
    void retiredLegacyLlmKeyFromAnOlderPeerIsIgnored() throws Exception {
        ManualConfig target = newRealConfig();

        assertTrue(ConfigJsonUtil.applyConfigJson(
                "{\"defaultLlmWorldEnabled\":true,\"textDialogueEnabled\":false}", target));

        assertFalse(target.isTextDialogueEnabled());
        assertFalse(ConfigJsonUtil.configToJson(target).contains("defaultLlmWorldEnabled"));
    }

    @Test
    void emptyObjectAppliesCleanlyWithoutChangingAnything() throws Exception {
        ManualConfig target = populated();
        ManualConfig pristine = populated();

        assertTrue(ConfigJsonUtil.applyConfigJson("{}", target));

        assertSharedFieldsEqual(pristine, target);
    }

    @Test
    void soulRelationsToggleTravelsInBothDirections() throws Exception {
        ManualConfig defaults = newRealConfig();
        assertFalse(defaults.isSoulRelationsEnabled(), "default off");
        assertFalse(defaults.isSoulStructuredOutputEnabled(), "default off");

        ManualConfig target = newRealConfig();
        assertTrue(ConfigJsonUtil.applyConfigJson("{\"soulRelationsEnabled\":true}", target));
        assertTrue(target.isSoulRelationsEnabled());
        assertTrue(ConfigJsonUtil.applyConfigJson("{\"soulRelationsEnabled\":false}", target));
        assertFalse(target.isSoulRelationsEnabled());
        assertTrue(ConfigJsonUtil.applyConfigJson("{\"soulStructuredOutputEnabled\":true}", target));
        assertTrue(target.isSoulStructuredOutputEnabled());
        assertTrue(ConfigJsonUtil.applyConfigJson("{\"soulStructuredOutputEnabled\":false}", target));
        assertFalse(target.isSoulStructuredOutputEnabled());
    }

    @Test
    void unknownFieldsAreIgnoredAndKnownOnesStillApply() throws Exception {
        ManualConfig target = newRealConfig();
        assertTrue(ConfigJsonUtil.applyConfigJson(
                "{\"totallyUnknownField\":42,\"soulsEnabled\":true}", target));
        assertTrue(target.isSoulsEnabled());
    }

    @Test
    void missingFieldKeepsTheTargetValue() throws Exception {
        ManualConfig target = populated();
        assertTrue(ConfigJsonUtil.applyConfigJson("{\"soulsEnabled\":false}", target));
        assertFalse(target.isSoulsEnabled());
        // untouched neighbours keep their populated values
        assertEquals(17, target.getFortBufferRadius());
        assertTrue(target.isSoulPartyEnabled());
    }

    @Test
    void botControlsByWorldRoundTrips() throws Exception {
        ManualConfig source = newRealConfig();
        ManualConfig.BotControlSettings settings =
                source.getOrCreateBotControl("Steve", "minecraft:overworld");
        settings.setVoicedDialogue(true);
        settings.setFollowTeleport(true);

        ManualConfig target = newRealConfig();
        assertTrue(ConfigJsonUtil.applyConfigJson(ConfigJsonUtil.configToJson(source), target));

        Map<String, Map<String, ManualConfig.BotControlSettings>> byWorld =
                target.getBotControlsByWorld();
        assertEquals(1, byWorld.size());
        Map<String, ManualConfig.BotControlSettings> worlds = byWorld.get("Steve");
        assertNotNull(worlds, "expected an entry for alias Steve, got " + byWorld.keySet());
        ManualConfig.BotControlSettings restored = worlds.get("minecraft:overworld");
        assertNotNull(restored);
        assertTrue(restored.isVoicedDialogue());
        assertTrue(restored.isFollowTeleport());
    }

    @Test
    void botControlsReplaceRatherThanMergeIntoTheTarget() throws Exception {
        ManualConfig source = newRealConfig();
        source.getOrCreateBotControl("Alex", "minecraft:overworld").setFollowTeleport(true);

        ManualConfig target = newRealConfig();
        target.getOrCreateBotControl("Stale", "minecraft:nether");

        assertTrue(ConfigJsonUtil.applyConfigJson(ConfigJsonUtil.configToJson(source), target));

        Map<String, Map<String, ManualConfig.BotControlSettings>> byWorld =
                target.getBotControlsByWorld();
        assertEquals(new HashMap<>(Map.of("Alex", byWorld.get("Alex"))).keySet(), byWorld.keySet());
    }

    @Test
    void isValidJsonChecksActualSyntax() {
        assertTrue(ConfigJsonUtil.isValidJson("{}"));
        assertTrue(ConfigJsonUtil.isValidJson("{\"soulsEnabled\":true}"));
        assertFalse(ConfigJsonUtil.isValidJson("{not json"));
        assertFalse(ConfigJsonUtil.isValidJson(null));
        assertFalse(ConfigJsonUtil.isValidJson("  "));
    }

    @Test
    void olderPeerLegacyFlagDoesNotChangeTheFinder() throws Exception {
        ManualConfig target = newRealConfig();
        target.setPathfinder("unified");

        assertTrue(ConfigJsonUtil.applyConfigJson("{\"baritonePathfinderEnabled\":false}", target));
        assertEquals("unified", target.getPathfinder());

        assertTrue(ConfigJsonUtil.applyConfigJson("{\"pathfinder\":\"baritone\"}", target));
        assertEquals("baritone", target.getPathfinder());
        assertTrue(target.isBaritonePathfinderEnabled());
    }

    @Test
    void captureOfTheDefaultCarriesAnExplicitUnified() throws Exception {
        ManualConfig source = newRealConfig(); // pathfinder null = the default
        assertEquals("unified", net.wcfcarolina13.FilingSystem.SharedConfig.capture(source).pathfinder);
        assertTrue(ConfigJsonUtil.configToJson(source).contains("\"pathfinder\": \"unified\"")
                || ConfigJsonUtil.configToJson(source).contains("\"pathfinder\":\"unified\""));

        // A client holding a stale baritone pick takes the server's default.
        ManualConfig client = newRealConfig();
        client.setPathfinder("baritone");
        assertTrue(ConfigJsonUtil.applyConfigJson(ConfigJsonUtil.configToJson(source), client));
        assertEquals(net.wcfcarolina13.PathFinding.PathFinderChoice.UNIFIED, client.getPathfinderChoice());
    }

    @Test
    void preSyncSnapshotRestoresTheLocalChoice() throws Exception {
        for (String local : new String[] {null, "unified", "baritone", "classic"}) {
            ManualConfig cfg = newRealConfig();
            cfg.setPathfinder(local);
            net.wcfcarolina13.PathFinding.PathFinderChoice before = cfg.getPathfinderChoice();
            net.wcfcarolina13.FilingSystem.SharedConfig snapshot = net.wcfcarolina13.FilingSystem.SharedConfig.capture(cfg);

            ManualConfig server = newRealConfig();
            server.setPathfinder(local == null || "unified".equals(local) ? "classic" : "unified");
            assertTrue(ConfigJsonUtil.applyConfigJson(ConfigJsonUtil.configToJson(server), cfg));
            assertEquals(server.getPathfinderChoice(), cfg.getPathfinderChoice());

            snapshot.applyTo(cfg); // leaveRemoteConfig
            assertEquals(before, cfg.getPathfinderChoice());
            // A null local default comes back as an explicit "unified" (same finder).
            assertEquals(local == null ? "unified" : local, cfg.getPathfinder());
        }
    }
}
