package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.wcfcarolina13.GameAI.services.LegacyRegistryRecoveryPolicy.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRegistryRecoveryPolicyTest {
    private static final String LEGACY = "New World/minecraft:overworld";
    private static final String FIRST = "New World#aaaa";
    private static final String SECOND = "New World#bbbb";
    private static final String DIMENSION = "minecraft:overworld";
    private static final Gson GSON = new Gson();

    static final class Anchor {
        int x;
        Anchor(int x) { this.x = x; }
    }
    static final class Registry {
        Map<String, List<Anchor>> records = new HashMap<>();
    }

    private static Registry legacy() {
        var registry = new Registry();
        registry.records.put("owner", new ArrayList<>(List.of(new Anchor(12))));
        return registry;
    }

    private static Registry copy(Registry registry) {
        return GSON.fromJson(GSON.toJson(registry), Registry.class);
    }

    private static String key(String fingerprint) {
        return WorldStoreKeyPolicy.fingerprintKey(fingerprint, DIMENSION);
    }

    @Test
    void confirmedImportCreatesIndependentNestedStateAndRetainsLegacy() {
        Registry original = legacy();
        var worlds = new HashMap<>(Map.of(LEGACY, original));
        var result = LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                LegacyRegistryRecoveryPolicyTest::copy);
        assertEquals(IMPORTED, result.result());
        result.imported().records.get("owner").getFirst().x = 99;
        result.imported().records.get("owner").add(new Anchor(33));
        assertSame(original, worlds.get(LEGACY));
        assertEquals(12, original.records.get("owner").getFirst().x);
        assertEquals(1, original.records.get("owner").size());
        assertSame(result.imported(), worlds.get(key(FIRST)));
    }

    @Test
    void sameLevelDifferentFingerprintsRemainSeparateAcrossReload() {
        var worlds = new HashMap<>(Map.of(LEGACY, legacy()));
        assertNotEquals(key(FIRST), key(SECOND));
        assertEquals(IMPORTED, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                LegacyRegistryRecoveryPolicyTest::copy).result());
        var reloaded = new HashMap<>(worlds);
        assertFalse(reloaded.containsKey(key(SECOND)));
        assertTrue(reloaded.containsKey(LEGACY));
        assertEquals(IMPORTED, LegacyRegistryRecoveryPolicy.copyLegacy(reloaded, LEGACY, key(SECOND), SECOND, SECOND,
                LegacyRegistryRecoveryPolicyTest::copy).result());
        assertNotSame(reloaded.get(key(FIRST)), reloaded.get(key(SECOND)));
    }

    @Test
    void missingConfirmationOrOtherSaveConfirmationRefusesWithoutCopying() {
        var worlds = new HashMap<>(Map.of(LEGACY, legacy()));
        for (String confirmation : new String[]{null, SECOND, FIRST + " "}) {
            assertEquals(WRONG_CONFIRMATION, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST,
                    confirmation, entry -> { fail("must not copy"); return null; }).result());
        }
        assertEquals(1, worlds.size());
    }

    @Test
    void existingCurrentRegistryIncludingEmptyOneAlwaysWins() {
        var own = new Registry();
        var worlds = new HashMap<>(Map.of(LEGACY, legacy(), key(FIRST), own));
        assertEquals(TARGET_EXISTS, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                entry -> { fail("must not copy"); return null; }).result());
        assertSame(own, worlds.get(key(FIRST)));
        assertEquals(2, worlds.size());
    }

    @Test
    void invalidOrUnknownTargetAndAbsentLegacyRefuse() {
        var worlds = new HashMap<>(Map.of(LEGACY, legacy()));
        for (String fingerprint : new String[]{"New World", "New World#unknown", "New World#"}) {
            assertEquals(INVALID_TARGET, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(fingerprint),
                    fingerprint, fingerprint, LegacyRegistryRecoveryPolicyTest::copy).result());
        }
        assertEquals(NO_LEGACY, LegacyRegistryRecoveryPolicy.copyLegacy(new HashMap<>(), LEGACY, key(FIRST), FIRST,
                FIRST, LegacyRegistryRecoveryPolicyTest::copy).result());
        assertEquals(1, worlds.size());
    }

    @Test
    void rollbackRemovesOnlyTheImportedValueAndKeepsNewerReplacement() {
        var worlds = new HashMap<>(Map.of(LEGACY, legacy()));
        var imported = LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                LegacyRegistryRecoveryPolicyTest::copy).imported();
        var newer = new Registry();
        worlds.put(key(FIRST), newer);
        assertFalse(LegacyRegistryRecoveryPolicy.rollback(worlds, key(FIRST), imported));
        assertSame(newer, worlds.get(key(FIRST)));
        assertTrue(worlds.containsKey(LEGACY));
        worlds.put(key(FIRST), imported);
        assertTrue(LegacyRegistryRecoveryPolicy.rollback(worlds, key(FIRST), imported));
        assertFalse(worlds.containsKey(key(FIRST)));
        assertTrue(worlds.containsKey(LEGACY));
    }

    @Test
    void failedOrAliasedCopyCannotInstallLegacyByReference() {
        var worlds = new HashMap<>(Map.of(LEGACY, legacy()));
        assertEquals(SAVE_FAILED, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                entry -> entry).result());
        assertEquals(SAVE_FAILED, LegacyRegistryRecoveryPolicy.copyLegacy(worlds, LEGACY, key(FIRST), FIRST, FIRST,
                entry -> null).result());
        assertEquals(1, worlds.size());
    }
    @Test
    void historyColonLegacyKeyImportsIntoActualFingerprintDimensionKeyWithoutMerging() {
        String legacyHistoryKey = "New World:" + DIMENSION;
        String target = WorldStoreKeyPolicy.fingerprintKey(FIRST, DIMENSION);
        assertEquals("New World#aaaa:minecraft:overworld", target);
        var originals = new ArrayList<>(List.of("minecraft:bread", "minecraft:beef"));
        var histories = new HashMap<String, List<String>>();
        histories.put(legacyHistoryKey, originals);
        var result = LegacyRegistryRecoveryPolicy.copyLegacy(histories, legacyHistoryKey, target,
                FIRST, FIRST, ArrayList::new);
        assertEquals(IMPORTED, result.result());
        result.imported().add("minecraft:carrot");
        assertEquals(List.of("minecraft:bread", "minecraft:beef"), originals);
        assertSame(originals, histories.get(legacyHistoryKey));
        assertEquals(TARGET_EXISTS, LegacyRegistryRecoveryPolicy.copyLegacy(histories, legacyHistoryKey,
                target, FIRST, FIRST, ArrayList::new).result());
        String otherDimension = WorldStoreKeyPolicy.fingerprintKey(FIRST, "minecraft:the_nether");
        assertFalse(histories.containsKey(otherDimension));
        assertFalse(histories.containsKey(key(SECOND)));
    }

    @Test
    void historyRegistrySlashLegacyKeysCannotBeAccidentallyInterchanged() {
        String colonLegacy = "New World:" + DIMENSION;
        var histories = new HashMap<>(Map.of(colonLegacy, new ArrayList<>(List.of("minecraft:bread"))));
        assertEquals(NO_LEGACY, LegacyRegistryRecoveryPolicy.copyLegacy(histories, LEGACY, key(FIRST),
                FIRST, FIRST, ArrayList::new).result());
        assertEquals(1, histories.size());
        assertTrue(histories.containsKey(colonLegacy));
    }

    @Test
    void completeStructureSnapshotSurvivesRecoveryWithOnlyCopiedWorldIdentityChanged() {
        var original = com.google.gson.JsonParser.parseString("""
                {
                  "fortifications": {
                    "wall_a": {
                      "name":"Wall A", "worldKey":"New World:minecraft:overworld",
                      "center":{"x":10,"y":64,"z":-20},
                      "hullVertices":[[1,2],[3,4],[5,6]], "searchRadius":45, "createdAt":123,
                      "completedEdges":[0,2], "lastEdgeIndex":2, "totalBlocksPlaced":91,
                      "complete":false, "moatComplete":true,
                      "edgePlannedCounts":{"0":50,"2":60}, "edgeActualCounts":{"0":45,"2":46},
                      "surfaceProfile":{"10,-20":64},
                      "schemaVersion":2,"schemaRevision":7,"schemaUpdatedAt":456,
                      "schemaOrigin":"expand","schemaParentName":"Older Wall",
                      "poiStructureSignature":"poi","hullSignature":"hull",
                      "ownerUuid":"12345678-1234-1234-1234-123456789abc", "ownerName":"Owner",
                      "ownershipPolicy":"allowlist", "allowedOwnerUuids":["11111111-1111-1111-1111-111111111111"]
                    }
                  }
                }
                """).getAsJsonObject();
        var before = original.deepCopy();
        String legacyKey = "New World:" + DIMENSION;
        String targetKey = key(FIRST);
        var worlds = new HashMap<>(Map.of(legacyKey, original));
        var outcome = LegacyRegistryRecoveryPolicy.copyLegacy(worlds, legacyKey, targetKey, FIRST, FIRST, source -> {
            var copied = source.deepCopy();
            copied.getAsJsonObject("fortifications").entrySet().forEach(entry ->
                    entry.getValue().getAsJsonObject().addProperty("worldKey", targetKey));
            return copied;
        });
        assertEquals(IMPORTED, outcome.result());
        assertEquals(before, original);
        var expected = before.deepCopy();
        expected.getAsJsonObject("fortifications").getAsJsonObject("wall_a").addProperty("worldKey", targetKey);
        assertEquals(expected, outcome.imported());
        outcome.imported().getAsJsonObject("fortifications").getAsJsonObject("wall_a")
                .getAsJsonArray("completedEdges").add(3);
        assertEquals(before, original);
    }

    @Test
    void mappedVillageCopyRekeysOnlyCopyAndRetainsHullNameCenterAndTimestamp() {
        var original = com.google.gson.JsonParser.parseString("""
                {"villages":{"village_a":{"name":"Village A","worldKey":"New World:minecraft:overworld",
                  "dimension":"minecraft:overworld","center":{"x":5,"y":70,"z":9},
                  "hullVertices":[[1,2],[3,4],[5,6]],"createdAt":789}}}
                """).getAsJsonObject();
        var before = original.deepCopy();
        String legacyKey = "New World:" + DIMENSION;
        String dimension = "minecraft:the_nether";
        String targetKey = WorldStoreKeyPolicy.fingerprintKey(FIRST, dimension);
        var worlds = new HashMap<>(Map.of(legacyKey, original));
        var outcome = LegacyRegistryRecoveryPolicy.copyLegacy(worlds, legacyKey, targetKey, FIRST, FIRST, source -> {
            var copied = source.deepCopy();
            var village = copied.getAsJsonObject("villages").getAsJsonObject("village_a");
            village.addProperty("worldKey", targetKey);
            village.addProperty("dimension", dimension);
            return copied;
        });
        assertEquals(IMPORTED, outcome.result());
        assertEquals(before, original);
        var expected = before.deepCopy();
        var village = expected.getAsJsonObject("villages").getAsJsonObject("village_a");
        village.addProperty("worldKey", targetKey);
        village.addProperty("dimension", dimension);
        assertEquals(expected, outcome.imported());
        assertSame(original, worlds.get(legacyKey));
    }

}
