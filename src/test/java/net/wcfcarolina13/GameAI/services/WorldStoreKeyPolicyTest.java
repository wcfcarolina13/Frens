package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_CLAIMED;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_NEW_WORLD;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_NO_LEGACY;
import static net.wcfcarolina13.GameAI.services.WorldStoreKeyPolicy.Adoption.START_EMPTY_NOT_THIS_SAVE;
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
    // ── 1.1.234 fix wave #5: the shared zone/lock store is adopted only by the save it belongs to ──

    private static final WorldStoreKeyPolicy.LegacyBase NIRN_SPAWN = new WorldStoreKeyPolicy.LegacyBase("Spawn", 120, 70, -40);
    private static final WorldStoreKeyPolicy.LegacyBase TEST1_SPAWN = new WorldStoreKeyPolicy.LegacyBase("Spawn", 8, 64, 8);
    /** Nirn's legacy zone file: its spawn base mirror plus a drawn zone. */
    private static final List<WorldStoreKeyPolicy.LegacyZone> NIRN_ZONES = List.of(
            new WorldStoreKeyPolicy.LegacyZone("base:spawn", 88, 38, -72, 152, 102, -8),
            new WorldStoreKeyPolicy.LegacyZone("farm", 200, 60, 200, 220, 80, 220));

    private static WorldStoreKeyPolicy.Adoption zoneDecision(boolean hasHomeEntry,
                                                             List<WorldStoreKeyPolicy.LegacyBase> bases,
                                                             long gameTime, String claimant, String fingerprint) {
        boolean belongs = WorldStoreKeyPolicy.legacyZonesBelongToThisSave(hasHomeEntry, bases, NIRN_ZONES);
        return WorldStoreKeyPolicy.decideZoneStore(false, true, gameTime, claimant, fingerprint, belongs);
    }

    @Test
    void nirnWithItsHomeEntryAndMatchingSpawnBaseAdopts() {
        assertEquals(ADOPT_LEGACY, zoneDecision(true, List.of(NIRN_SPAWN), 500_000, null, "Nirn#aa"));
    }

    @Test
    void sameNameBrandNewWorldStartsEmpty() {
        assertEquals(START_EMPTY_NEW_WORLD, zoneDecision(true, List.of(NIRN_SPAWN), 600, null, "Nirn#bb"));
    }

    @Test
    void unrelatedOldWorldWithNoMatchingBaseStartsEmpty() {
        // "test 1" from the 1.1.233 playthrough, opened first: its own auto "spawn" base has the
        // same label but sits elsewhere, so the label alone must not match.
        WorldStoreKeyPolicy.Adoption a = zoneDecision(true, List.of(TEST1_SPAWN), 90_000, null, "test 1#cc");
        assertEquals(START_EMPTY_NOT_THIS_SAVE, a);
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(a), "its own empty store sticks, zones and locks alike");
        assertEquals(START_EMPTY_NOT_THIS_SAVE, zoneDecision(true, List.of(), 90_000, null, "test 1#cc"));
    }

    @Test
    void oldWorldWithNoLegacyHomeEntryStartsEmpty() {
        assertEquals(START_EMPTY_NOT_THIS_SAVE, zoneDecision(false, null, 90_000, null, "other#dd"));
        assertFalse(WorldStoreKeyPolicy.legacyZonesBelongToThisSave(false, null, List.of()));
    }

    @Test
    void alreadyClaimedLegacyStartsEmptyEvenForAMatchingSave() {
        assertEquals(START_EMPTY_CLAIMED, zoneDecision(true, List.of(NIRN_SPAWN), 500_000, "test 1#cc", "Nirn#aa"));
    }

    @Test
    void legacyFileWithoutBaseZonesNeedsOnlyTheHomeEntry() {
        List<WorldStoreKeyPolicy.LegacyZone> drawnOnly = List.of(
                new WorldStoreKeyPolicy.LegacyZone("farm", 0, 0, 0, 5, 5, 5));
        assertTrue(WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, List.of(), drawnOnly));
        assertTrue(WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, null, List.of()));
    }

    @Test
    void baseLabelMatchIsCaseInsensitiveLikeTheAutoZoneLabel() {
        WorldStoreKeyPolicy.LegacyBase upper = new WorldStoreKeyPolicy.LegacyBase(" SPAWN ", 120, 70, -40);
        assertTrue(WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, List.of(upper), NIRN_ZONES));
    }

    @Test
    void ownStoreAndNoLegacyAreUnchangedByTheGuard() {
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decideZoneStore(true, true, 90_000, null, "x#1", false));
        assertEquals(START_EMPTY_NO_LEGACY, WorldStoreKeyPolicy.decideZoneStore(false, false, 90_000, null, "x#1", false));
    }

    // ── the on-disk 1.1.234 data: a majority of base zones must match ─────────────────────────

    private static final List<WorldStoreKeyPolicy.LegacyZone> DISK_ZONES = List.of(
            new WorldStoreKeyPolicy.LegacyZone("base:home", 233, 8, 1254, 313, 88, 1334),
            new WorldStoreKeyPolicy.LegacyZone("base:tower dungeon jungle", 846, 24, 1360, 926, 104, 1440),
            new WorldStoreKeyPolicy.LegacyZone("base:deep dark 1", 259, -39, 1179, 339, 41, 1259),
            new WorldStoreKeyPolicy.LegacyZone("base:spawn", -72, 27, -40, 8, 107, 40),
            new WorldStoreKeyPolicy.LegacyZone("farm", 0, 0, 0, 5, 5, 5));

    @Test
    void nirnOnDiskMatchesThreeOfFourBaseZonesAndAdopts() {
        List<WorldStoreKeyPolicy.LegacyBase> nirn = List.of(
                new WorldStoreKeyPolicy.LegacyBase("home", 273, 48, 1294),
                new WorldStoreKeyPolicy.LegacyBase("deep dark 1", 299, 1, 1219),
                new WorldStoreKeyPolicy.LegacyBase("tower dungeon jungle", 886, 64, 1400),
                new WorldStoreKeyPolicy.LegacyBase("spawn", 304, 74, 1248));
        boolean belongs = WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, nirn, DISK_ZONES);
        assertTrue(belongs);
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decideZoneStore(false, true, 500_000, null, "Nirn#aa", belongs));
    }

    @Test
    void oldTest1OnDiskMatchesOnlySpawnOneOfFourAndStartsEmpty() {
        // Its spawn base is inside base:spawn (re-synced from test 1), but that is 1 of 4.
        List<WorldStoreKeyPolicy.LegacyBase> test1 = List.of(
                new WorldStoreKeyPolicy.LegacyBase("spawn", -32, 67, 0));
        boolean belongs = WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, test1, DISK_ZONES);
        assertFalse(belongs);
        assertEquals(START_EMPTY_NOT_THIS_SAVE,
                WorldStoreKeyPolicy.decideZoneStore(false, true, 90_000, null, "test 1#old", belongs));
    }

    @Test
    void exactlyHalfOfTheBaseZonesIsNotAMajority() {
        List<WorldStoreKeyPolicy.LegacyBase> half = List.of(
                new WorldStoreKeyPolicy.LegacyBase("home", 273, 48, 1294),
                new WorldStoreKeyPolicy.LegacyBase("spawn", -32, 67, 0));
        assertFalse(WorldStoreKeyPolicy.legacyZonesBelongToThisSave(true, half, DISK_ZONES));
    }
}
