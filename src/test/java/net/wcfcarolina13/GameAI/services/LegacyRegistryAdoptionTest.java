package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.wcfcarolina13.GameAI.services.LegacyRegistryRecoveryPolicy.Outcome;
import static net.wcfcarolina13.GameAI.services.LegacyRegistryRecoveryPolicy.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** Load-time adoption of legacy buckets (I1) and explicit import from a moved save (I2). */
class LegacyRegistryAdoptionTest {
    private static final String LEVEL = "Nirn";
    private static final String NIRN = "Nirn#1a2b3c";
    private static final String MOVED = "Nirn#ffff01";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final long OLD_WORLD = 5_000_000L;
    private static final long NEW_WORLD = 100L;
    private static final Gson GSON = new Gson();

    static final class Bucket {
        String worldKey;
        List<String> records = new ArrayList<>();
        Bucket(String worldKey, String... records) {
            this.worldKey = worldKey;
            this.records.addAll(List.of(records));
        }
    }

    private static Bucket copy(Bucket source, String targetKey, String dimension) {
        Bucket copied = GSON.fromJson(GSON.toJson(source), Bucket.class);
        copied.worldKey = targetKey;
        return copied;
    }

    private static String colonLegacy(String dim) { return LEVEL + ":" + dim; }
    private static String target(String fingerprint, String dim) { return WorldStoreKeyPolicy.fingerprintKey(fingerprint, dim); }

    private final Map<String, Bucket> worlds = new HashMap<>();
    private final Map<String, String> claims = new HashMap<>();
    private final Set<String> declined = new HashSet<>();

    private LegacyRegistryRecoveryPolicy.Adoption<Bucket> adopt(char separator, String fingerprint, long gameTime) {
        return LegacyRegistryRecoveryPolicy.adoptLegacy(worlds, claims, declined, LEVEL, separator, fingerprint,
                gameTime, LegacyRegistryAdoptionTest::copy);
    }

    @Test
    void existingSaveAdoptsEveryDimensionAsDeepCopyAndClaimsIt() {
        Bucket overworld = new Bucket(colonLegacy(OVERWORLD), "depopulation=false");
        Bucket nether = new Bucket(colonLegacy(NETHER), "fort");
        worlds.put(colonLegacy(OVERWORLD), overworld);
        worlds.put(colonLegacy(NETHER), nether);
        var adoption = adopt(':', NIRN, OLD_WORLD);
        assertFalse(adoption.refused());
        assertTrue(adoption.changed());
        assertEquals(2, adoption.steps().stream().filter(s -> s.outcome() == Outcome.ADOPTED).count());
        Bucket adopted = worlds.get(target(NIRN, OVERWORLD));
        assertNotSame(overworld, adopted);
        assertEquals(target(NIRN, OVERWORLD), adopted.worldKey);
        assertEquals(List.of("depopulation=false"), adopted.records);
        adopted.records.add("changed");
        assertEquals(List.of("depopulation=false"), overworld.records);
        assertSame(overworld, worlds.get(colonLegacy(OVERWORLD)));
        assertEquals(colonLegacy(OVERWORLD), overworld.worldKey);
        assertEquals(target(NIRN, NETHER), worlds.get(target(NIRN, NETHER)).worldKey);
        assertEquals(NIRN, claims.get(colonLegacy(OVERWORLD)));
        assertEquals(NIRN, claims.get(colonLegacy(NETHER)));
        assertTrue(declined.isEmpty());

        var again = adopt(':', NIRN, OLD_WORLD + 20);
        assertFalse(again.changed());
        assertTrue(again.steps().stream().allMatch(s -> s.outcome() == Outcome.USE_OWN));
    }

    @Test
    void slashLegacyKeysAdoptOnlyWithTheirSeparator() {
        worlds.put(LEVEL + "/" + OVERWORLD, new Bucket("furnace"));
        assertTrue(adopt(':', NIRN, OLD_WORLD).steps().isEmpty());
        var adoption = adopt('/', NIRN, OLD_WORLD);
        assertEquals(Outcome.ADOPTED, adoption.steps().getFirst().outcome());
        assertTrue(worlds.containsKey(target(NIRN, OVERWORLD)));
    }

    @Test
    void brandNewSaveDeclinesWithoutBucketAndTheDeclineSticks() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        var adoption = adopt(':', NIRN, NEW_WORLD);
        assertEquals(Outcome.DECLINED_NEW_WORLD, adoption.steps().getFirst().outcome());
        assertTrue(adoption.changed());
        assertFalse(worlds.containsKey(target(NIRN, OVERWORLD)), "no empty bucket is written");
        assertTrue(declined.contains(target(NIRN, OVERWORLD)));
        assertTrue(claims.isEmpty(), "the legacy stays unclaimed for its real save");

        var later = adopt(':', NIRN, OLD_WORLD);
        assertEquals(Outcome.ALREADY_DECLINED, later.steps().getFirst().outcome());
        assertFalse(later.changed());
        assertFalse(worlds.containsKey(target(NIRN, OVERWORLD)));

        var realOwner = adopt(':', MOVED, OLD_WORLD);
        assertEquals(Outcome.ADOPTED, realOwner.steps().getFirst().outcome());
    }

    @Test
    void legacyClaimedByAnotherSaveIsLeftAlone() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        claims.put(colonLegacy(OVERWORLD), MOVED);
        var adoption = adopt(':', NIRN, OLD_WORLD);
        assertEquals(Outcome.CLAIMED_ELSEWHERE, adoption.steps().getFirst().outcome());
        assertFalse(adoption.changed());
        assertFalse(worlds.containsKey(target(NIRN, OVERWORLD)));
        assertEquals(MOVED, claims.get(colonLegacy(OVERWORLD)));
    }

    @Test
    void existingDestinationIsNeverOverwrittenEvenWhenEmpty() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        Bucket own = new Bucket(target(NIRN, OVERWORLD));
        worlds.put(target(NIRN, OVERWORLD), own);
        var adoption = adopt(':', NIRN, OLD_WORLD);
        assertEquals(Outcome.USE_OWN, adoption.steps().getFirst().outcome());
        assertSame(own, worlds.get(target(NIRN, OVERWORLD)));
        assertTrue(own.records.isEmpty());
        assertTrue(claims.isEmpty());
    }

    @Test
    void unknownOrMalformedFingerprintAdoptsNothing() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        for (String fingerprint : new String[]{"Nirn#unknown", "Nirn", "Nirn#", null}) {
            var adoption = adopt(':', fingerprint, OLD_WORLD);
            assertTrue(adoption.refused(), String.valueOf(fingerprint));
            assertFalse(adoption.changed());
        }
        assertEquals(1, worlds.size());
        assertTrue(claims.isEmpty());
        assertTrue(declined.isEmpty());
    }

    @Test
    void otherLevelNamesAndFingerprintedKeysAreNotLegacy() {
        worlds.put("Nirn Two:" + OVERWORLD, new Bucket("a"));
        worlds.put("Nirn:x:" + OVERWORLD, new Bucket("b"));
        worlds.put(target(MOVED, OVERWORLD), new Bucket("c"));
        assertTrue(adopt(':', NIRN, OLD_WORLD).steps().isEmpty());
        assertEquals(3, worlds.size());
        assertEquals(OVERWORLD, WorldStoreKeyPolicy.legacyKeyDimension("Nirn:" + OVERWORLD, "Nirn", ':'));
        assertNull(WorldStoreKeyPolicy.legacyKeyDimension("Nirn#abc:" + OVERWORLD, "Nirn", ':'));
        assertNull(WorldStoreKeyPolicy.legacyKeyDimension("Nirn:unknown", "Nirn", ':'));
        assertEquals(OVERWORLD, WorldStoreKeyPolicy.legacyKeyDimension("unknown/" + OVERWORLD, null, '/'));
    }

    @Test
    void undoAfterFailedSaveRestoresEverything() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        worlds.put(colonLegacy(NETHER), new Bucket("y"));
        var adoption = adopt(':', NIRN, OLD_WORLD);
        LegacyRegistryRecoveryPolicy.undo(adoption, worlds, claims, declined);
        assertEquals(Set.of(colonLegacy(OVERWORLD), colonLegacy(NETHER)), worlds.keySet());
        assertTrue(claims.isEmpty());

        var declinedPass = adopt(':', NIRN, NEW_WORLD);
        LegacyRegistryRecoveryPolicy.undo(declinedPass, worlds, claims, declined);
        assertTrue(declined.isEmpty());
    }

    @Test
    void undoRestoresAPreviousOwnClaim() {
        worlds.put(colonLegacy(OVERWORLD), new Bucket("x"));
        claims.put(colonLegacy(OVERWORLD), NIRN);
        var adoption = adopt(':', NIRN, OLD_WORLD);
        assertEquals(Outcome.ADOPTED, adoption.steps().getFirst().outcome());
        LegacyRegistryRecoveryPolicy.undo(adoption, worlds, claims, declined);
        assertEquals(NIRN, claims.get(colonLegacy(OVERWORLD)));
        assertFalse(worlds.containsKey(target(NIRN, OVERWORLD)));
    }

    // ── I2: siblings ──────────────────────────────────────────────────────

    @Test
    void siblingMatchesOnlySameLevelSameDimensionOtherHash() {
        String own = target(NIRN, OVERWORLD);
        assertTrue(WorldStoreKeyPolicy.isSiblingKey(target(MOVED, OVERWORLD), LEVEL, OVERWORLD, own));
        assertTrue(WorldStoreKeyPolicy.isSiblingKey("Nirn#unknown:" + OVERWORLD, LEVEL, OVERWORLD, own));
        assertTrue(WorldStoreKeyPolicy.isSiblingKey(target(MOVED, OVERWORLD), "  Nirn ", OVERWORLD, own));
        assertFalse(WorldStoreKeyPolicy.isSiblingKey(own, LEVEL, OVERWORLD, own), "not the target itself");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey(target(MOVED, NETHER), LEVEL, OVERWORLD, own), "other dimension");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey("Other#ffff01:" + OVERWORLD, LEVEL, OVERWORLD, own), "other level");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey(colonLegacy(OVERWORLD), LEVEL, OVERWORLD, own), "legacy key");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey("Nirn#zz:" + OVERWORLD, LEVEL, OVERWORLD, own), "not hex");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey("Nirn#:" + OVERWORLD, LEVEL, OVERWORLD, own), "empty hash");
        assertFalse(WorldStoreKeyPolicy.isSiblingKey("Nirn#ab#cd:" + OVERWORLD, LEVEL, OVERWORLD, own));
        assertFalse(WorldStoreKeyPolicy.isSiblingKey(null, LEVEL, OVERWORLD, own));
        assertEquals(List.of("Nirn#00aa:" + OVERWORLD, target(MOVED, OVERWORLD)),
                WorldStoreKeyPolicy.siblingKeys(List.of(own, target(MOVED, OVERWORLD), "Nirn#00aa:" + OVERWORLD,
                        colonLegacy(OVERWORLD)), LEVEL, OVERWORLD, own));
    }

    @Test
    void siblingImportCopiesIntoAbsentTargetAndKeepsSource() {
        Bucket source = new Bucket(target(MOVED, OVERWORLD), "a");
        worlds.put(target(MOVED, OVERWORLD), source);
        String own = target(NIRN, OVERWORLD);
        var copy = LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, target(MOVED, OVERWORLD), LEVEL, OVERWORLD,
                own, NIRN, NIRN, s -> copy(s, own, OVERWORLD));
        assertEquals(IMPORTED, copy.result());
        assertNotSame(source, worlds.get(own));
        assertEquals(own, worlds.get(own).worldKey);
        assertEquals(target(MOVED, OVERWORLD), source.worldKey);
        assertSame(source, worlds.get(target(MOVED, OVERWORLD)));
        assertTrue(LegacyRegistryRecoveryPolicy.rollback(worlds, own, copy.imported()));
        assertFalse(worlds.containsKey(own));
    }

    @Test
    void siblingImportRefusals() {
        String own = target(NIRN, OVERWORLD);
        worlds.put(target(MOVED, OVERWORLD), new Bucket("a"));
        worlds.put(target(MOVED, NETHER), new Bucket("b"));
        worlds.put(colonLegacy(OVERWORLD), new Bucket("c"));
        worlds.put("Other#ffff01:" + OVERWORLD, new Bucket("d"));
        for (String bad : new String[]{target(MOVED, NETHER), colonLegacy(OVERWORLD), "Other#ffff01:" + OVERWORLD, null}) {
            assertEquals(INVALID_SOURCE, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, bad, LEVEL, OVERWORLD,
                    own, NIRN, NIRN, s -> { fail("must not copy"); return null; }).result(), String.valueOf(bad));
        }
        assertEquals(INVALID_TARGET, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, own, LEVEL, OVERWORLD,
                own, NIRN, NIRN, s -> s).result(), "source equal to target");
        assertEquals(INVALID_TARGET, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, target(MOVED, OVERWORLD),
                LEVEL, OVERWORLD, "Nirn#unknown:" + OVERWORLD, "Nirn#unknown", "Nirn#unknown", s -> s).result());
        assertEquals(WRONG_CONFIRMATION, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, target(MOVED, OVERWORLD),
                LEVEL, OVERWORLD, own, NIRN, MOVED, s -> s).result());
        assertEquals(NO_LEGACY, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, "Nirn#0bad:" + OVERWORLD,
                LEVEL, OVERWORLD, own, NIRN, NIRN, s -> s).result());
        Bucket existing = new Bucket(own);
        worlds.put(own, existing);
        assertEquals(TARGET_EXISTS, LegacyRegistryRecoveryPolicy.copyFromSibling(worlds, target(MOVED, OVERWORLD),
                LEVEL, OVERWORLD, own, NIRN, NIRN, s -> { fail("must not copy"); return null; }).result());
        assertSame(existing, worlds.get(own));
        assertEquals(5, worlds.size());
    }
}
