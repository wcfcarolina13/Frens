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

    // ── Chest registry adoption (finding 40) ────────────────────────────

    @Test
    void chestStoreAdoptsWhenACheckedPositionHoldsAChest() {
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, null, "New World#aa", 4, 1));
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, "New World#aa", "New World#aa", 2, 2));
    }

    @Test
    void chestStoreStartsEmptyWhenNoCheckedPositionHoldsAChest() {
        // Sandbox 1.1.274: a second "New World" adopted the first one's chests by name alone.
        WorldStoreKeyPolicy.Adoption a = WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, null, "New World#bb", 3, 0);
        assertEquals(START_EMPTY_NOT_THIS_SAVE, a);
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(a), "the empty store sticks, no claim is recorded");
    }

    @Test
    void chestStoreWithNothingCheckedAdoptsAsBefore() {
        // A legacy entry with zero live records (or none readable) adopts as decide does.
        assertEquals(ADOPT_LEGACY, WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, null, "x#1", 0, 0));
    }

    @Test
    void chestStoreCheckNeverOverridesTheOtherOutcomes() {
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decideChestStore(true, true, 90_000, null, "x#1", 4, 0));
        assertEquals(START_EMPTY_NO_LEGACY, WorldStoreKeyPolicy.decideChestStore(false, false, 90_000, null, "x#1", 4, 0));
        assertEquals(START_EMPTY_NEW_WORLD, WorldStoreKeyPolicy.decideChestStore(false, true, 10, null, "x#1", 4, 4));
        assertEquals(START_EMPTY_CLAIMED, WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, "y#2", "x#1", 4, 0));
        assertEquals(START_EMPTY_CLAIMED, WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, "y#2", "x#1", 0, 0),
                "nothing checked: the foreign claim stands");
    }

    @Test
    void aChestFoundHereOverridesAnotherSavesClaim() {
        // 1.1.274: same-named "New World#bb" took the claim; "New World#aa" has the chests.
        WorldStoreKeyPolicy.Adoption a = WorldStoreKeyPolicy.decideChestStore(false, true, 90_000, "New World#bb", "New World#aa", 3, 1);
        assertEquals(ADOPT_LEGACY, a);
        assertTrue(WorldStoreKeyPolicy.mustCreateOwnStore(a));
    }

    @Test
    void aChestFoundHereDoesNotOverrideABrandNewWorldOrAnOwnStore() {
        assertEquals(START_EMPTY_NEW_WORLD, WorldStoreKeyPolicy.decideChestStore(false, true, 10, "y#2", "x#1", 4, 1));
        assertEquals(USE_OWN, WorldStoreKeyPolicy.decideChestStore(true, true, 90_000, "y#2", "x#1", 4, 1));
    }

    @Test
    void emptyOwnChestStoreIsReplacedOnlyWhenAChestIsHere() {
        assertTrue(WorldStoreKeyPolicy.shouldAdoptOverEmptyOwnChestStore(0, true, 1),
                "the 1.1.274 sandbox case: empty own store, chests present");
        assertFalse(WorldStoreKeyPolicy.shouldAdoptOverEmptyOwnChestStore(0, true, 0), "no chest here");
        assertFalse(WorldStoreKeyPolicy.shouldAdoptOverEmptyOwnChestStore(0, false, 1), "no legacy entry");
    }

    @Test
    void ownChestStoreWithRecordsIsNeverOverwritten() {
        assertFalse(WorldStoreKeyPolicy.shouldAdoptOverEmptyOwnChestStore(1, true, 1));
        assertFalse(WorldStoreKeyPolicy.shouldAdoptOverEmptyOwnChestStore(40, true, 1));
    }

    @Test
    void chestStoreMatchesDecideExceptAForeignClaimWhenAChestIsThere() {
        for (boolean own : new boolean[]{true, false}) {
            for (boolean legacy : new boolean[]{true, false}) {
                for (long time : new long[]{0, 1199, 1200, 500_000}) {
                    for (String claimant : new String[]{null, "", "x#1", "y#2"}) {
                        WorldStoreKeyPolicy.Adoption plain = WorldStoreKeyPolicy.decide(own, legacy, time, claimant, "x#1");
                        // A verified chest turns another save's claim into an adoption.
                        WorldStoreKeyPolicy.Adoption expected = plain == START_EMPTY_CLAIMED ? ADOPT_LEGACY : plain;
                        assertEquals(expected,
                                WorldStoreKeyPolicy.decideChestStore(own, legacy, time, claimant, "x#1", 4, 1));
                    }
                }
            }
        }
    }

    @Test
    void chestCheckKeepsGoingPastStaleRecordsAndStopsAtTheFirstChest() {
        // The owner's first four live records are moved chests; the fifth is still there.
        List<Integer> probed = new java.util.ArrayList<>();
        WorldStoreKeyPolicy.ChestCheck c = WorldStoreKeyPolicy.checkChestPositions(
                List.of(1, 2, 3, 4, 5, 6, 7), p -> { probed.add(p); return p == 5; });
        assertEquals(new WorldStoreKeyPolicy.ChestCheck(5, 5, 1), c);
        assertEquals(List.of(1, 2, 3, 4, 5), probed, "nothing is read after the first chest");
    }

    @Test
    void chestCheckTriesAtMostSixteenPositions() {
        List<Integer> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(i);
        int[] reads = {0};
        WorldStoreKeyPolicy.ChestCheck c = WorldStoreKeyPolicy.checkChestPositions(many, p -> { reads[0]++; return p == 30; });
        assertEquals(WorldStoreKeyPolicy.MAX_CHEST_POSITIONS_CHECKED, 16);
        assertEquals(new WorldStoreKeyPolicy.ChestCheck(16, 16, 0), c);
        assertEquals(16, reads[0]);
        assertEquals(1, WorldStoreKeyPolicy.checkChestPositions(many, p -> p == 15).chestLike(), "the 16th still counts");
    }

    @Test
    void chestCheckCountsUnreadablePositionsAsTriedNotChecked() {
        // Worker thread: chunk not loaded -> null.
        WorldStoreKeyPolicy.ChestCheck c = WorldStoreKeyPolicy.checkChestPositions(List.of(1, 2, 3), p -> null);
        assertEquals(new WorldStoreKeyPolicy.ChestCheck(3, 0, 0), c);
        assertEquals(new WorldStoreKeyPolicy.ChestCheck(3, 1, 1),
                WorldStoreKeyPolicy.checkChestPositions(List.of(1, 2, 3), p -> p == 3 ? Boolean.TRUE : null));
        assertEquals(WorldStoreKeyPolicy.ChestCheck.NONE, WorldStoreKeyPolicy.checkChestPositions(List.<Integer>of(), p -> true));
    }

    @Test
    void wrongChestClaimIsReleasedOnlyByItsHolderWithCheckedEmptyPositions() {
        assertTrue(WorldStoreKeyPolicy.shouldReleaseChestClaim("x#1", "x#1", 4, 0));
        assertFalse(WorldStoreKeyPolicy.shouldReleaseChestClaim("x#1", "x#1", 4, 1), "a chest is there: the claim is right");
        assertFalse(WorldStoreKeyPolicy.shouldReleaseChestClaim("x#1", "x#1", 0, 0), "nothing checked: no evidence");
        assertFalse(WorldStoreKeyPolicy.shouldReleaseChestClaim("y#2", "x#1", 4, 0), "another save's claim");
        assertFalse(WorldStoreKeyPolicy.shouldReleaseChestClaim(null, "x#1", 4, 0));
    }

    @Test
    void copiedChestRecordsAreRetiredWhenTheClaimIsElsewhereAndNoChestIsHere() {
        assertTrue(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 7, true, 16, 0));
        assertTrue(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 1, true, 1, 0));
    }

    @Test
    void copiedChestRecordsAreKeptWhenAnyConditionFails() {
        assertFalse(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(true, 7, true, 16, 0), "this save holds the claim");
        assertFalse(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 0, true, 16, 0), "no own live records (a fully retired copy)");
        assertFalse(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 7, false, 16, 0),
                "a chest this save registered itself: never touched");
        assertFalse(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 7, true, 0, 0), "nothing checked: no evidence");
        assertFalse(WorldStoreKeyPolicy.shouldRetireCopiedChestRecords(false, 7, true, 3, 1), "an own recorded chest is here");
    }
}
