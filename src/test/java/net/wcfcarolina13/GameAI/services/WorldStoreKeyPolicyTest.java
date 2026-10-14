package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_CLAIMED;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_NEW_WORLD;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_NO_LEGACY;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.USE_OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStoreKeyPolicyTest {

    private static final String OLD_SAVE = "/Users/x/instances/1.21.11/minecraft/saves/test 1";
    private static final String NEW_SAVE = "/Users/x/instances/1.21.11/minecraft/saves/test 1 (1)";

    /** The pre-1.1.234 inline formula from BotWorldStateService.worldKey, kept here as the oracle. */
    private static String inlineFingerprint(String levelName, String path) {
        String level = (levelName == null || levelName.isBlank()) ? "default" : levelName.trim();
        String hash = "unknown";
        if (path != null) {
            hash = Integer.toUnsignedString(path.toLowerCase(Locale.ROOT).hashCode(), 16);
        }
        return level + "#" + hash;
    }

    @Test
    void fingerprintIsByteIdenticalToTheOldWorldStateKey() {
        for (String level : new String[]{"test 1", "  Nirn  ", "", null, "Ünïcode Wörld"}) {
            for (String path : new String[]{OLD_SAVE, NEW_SAVE, "C:\\Saves\\World", null}) {
                assertEquals(inlineFingerprint(level, path),
                        WorldStoreKeyPolicy.fingerprint(level, WorldStoreKeyPolicy.savePathHash(path)));
            }
        }
    }

    @Test
    void sameLevelNameDifferentSaveFoldersGetDifferentFingerprints() {
        String a = WorldStoreKeyPolicy.fingerprint("test 1", WorldStoreKeyPolicy.savePathHash(OLD_SAVE));
        String b = WorldStoreKeyPolicy.fingerprint("test 1", WorldStoreKeyPolicy.savePathHash(NEW_SAVE));
        assertNotEquals(a, b);
        assertTrue(a.startsWith("test 1#"));
    }

    @Test
    void savePathHashIsCaseInsensitive() {
        assertEquals(WorldStoreKeyPolicy.savePathHash("/Saves/World"), WorldStoreKeyPolicy.savePathHash("/saves/world"));
    }

    @Test
    void keyAppendsDimension() {
        assertEquals("test 1#abc:minecraft:overworld", WorldStoreKeyPolicy.key("test 1", "abc", "minecraft:overworld"));
        assertEquals("default#unknown:unknown", WorldStoreKeyPolicy.key(null, null, null));
    }

    @Test
    void legacyLevelDimensionKeyMatchesOldBotHomeFormat() {
        assertEquals("test 1:minecraft:overworld",
                WorldStoreKeyPolicy.legacyLevelDimensionKey("test 1", "minecraft:overworld"));
        assertEquals("unknown:unknown", WorldStoreKeyPolicy.legacyLevelDimensionKey(null, null));
        // Not trimmed: must equal what older builds wrote.
        assertEquals(" a :minecraft:the_nether", WorldStoreKeyPolicy.legacyLevelDimensionKey(" a ", "minecraft:the_nether"));
    }

    @Test
    void safeDirNameMatchesTheOldDimensionDirectoryScheme() {
        // The live instance's legacy directory name for the overworld.
        assertEquals("minecraft_overworld__41d0e9b1", WorldStoreKeyPolicy.safeDirName("minecraft:overworld"));
        assertEquals("unknown_world__0", WorldStoreKeyPolicy.safeDirName(null));
        assertEquals("unknown_world__0", WorldStoreKeyPolicy.safeDirName(" "));
        String dir = WorldStoreKeyPolicy.safeDirName("test 1#b5d170e5");
        assertTrue(dir.matches("[A-Za-z0-9._-]+"), dir);
        assertNotEquals(dir, WorldStoreKeyPolicy.safeDirName("test_1#b5d170e5"));
    }

    @Test
    void brandNewThresholdIsOneMinuteOfGameTime() {
        assertTrue(WorldStoreKeyPolicy.isBrandNewWorld(0));
        assertTrue(WorldStoreKeyPolicy.isBrandNewWorld(1199));
        assertFalse(WorldStoreKeyPolicy.isBrandNewWorld(1200));
    }

    @Test
    void ownStoreAlwaysWins() {
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decide(true, true, 0, null, "a#1"));
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decide(true, true, 50_000, "b#2", "a#1"));
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decide(true, false, 50_000, null, "a#1"));
    }

    @Test
    void noLegacyStartsEmpty() {
        assertEquals(START_EMPTY_NO_LEGACY, WorldStoreKeyPolicy.decide(false, false, 50_000, null, "a#1"));
    }

    @Test
    void brandNewWorldNeverAdoptsEvenWhenLegacyIsUnclaimed() {
        assertEquals(START_EMPTY_NEW_WORLD, WorldStoreKeyPolicy.decide(false, true, 0, null, "a#1"));
        assertEquals(START_EMPTY_NEW_WORLD, WorldStoreKeyPolicy.decide(false, true, 1199, null, "a#1"));
    }

    @Test
    void oldWorldAdoptsUnclaimedLegacy() {
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decide(false, true, 1200, null, "a#1"));
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decide(false, true, 1200, "  ", "a#1"));
    }

    @Test
    void oldWorldReAdoptsLegacyItAlreadyClaimed() {
        // Second store of the same save (zones then locks share one marker), or a lost own file.
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decide(false, true, 90_000, "a#1", "a#1"));
    }

    @Test
    void legacyClaimedByAnotherSaveIsNotAdopted() {
        assertEquals(START_EMPTY_CLAIMED, WorldStoreKeyPolicy.decide(false, true, 90_000, "b#2", "a#1"));
    }

    @Test
    void everyNonOwnOutcomeExceptNoLegacyCreatesTheOwnStoreSoTheDecisionSticks() {
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(START_EMPTY_NEW_WORLD));
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(START_EMPTY_CLAIMED));
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(ADOPT_LEGACY));
        assertFalse(WorldStoreKeyPolicy.mustCreateOwnStore(USE_OWN));
        assertFalse(WorldStoreKeyPolicy.mustCreateOwnStore(START_EMPTY_NO_LEGACY));
    }

    @Test
    void newWorldThatStartedEmptyDoesNotAdoptOnItsSecondLoad() {
        // First load: brand-new, starts empty and creates its own store.
        WorldStoreKeyPolicy.Adoption first = WorldStoreKeyPolicy.decide(false, true, 0, null, "test 1#new");
        assertEquals(START_EMPTY_NEW_WORLD, first);
        boolean ownStoreExists = WorldStoreKeyPolicy.mustCreateOwnStore(first);
        // Second load, an hour of play later, legacy still unclaimed.
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decide(ownStoreExists, true, 72_000, null, "test 1#new"));
    }
}
