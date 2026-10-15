package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutProtectionNudgePolicyTest {

    @Test
    void dominantReasonPicksHighestCount() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("mapped-village", 3);
        counts.put("base-radius", 9);
        counts.put("beehive", 1);
        assertEquals(Optional.of("base-radius"), WoodcutProtectionNudgePolicy.dominantReason(counts));
    }

    @Test
    void tiesGoToPriorityOrder() {
        assertEquals(Optional.of("admin-zone"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("fort-buffer", 4, "admin-zone", 4)));
        assertEquals(Optional.of("base-radius"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("beehive", 2, "base-radius", 2)));
    }

    @Test
    void proximityAndUnknownReasonsAreIgnored() {
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.dominantReason(Map.of("human", 20, "none", 5)));
        assertEquals(Optional.of("beehive"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("human", 20, "beehive", 1, "mystery", 9)));
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.dominantReason(Map.of()));
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.dominantReason(null));
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.dominantReason(Map.of("base-radius", 0)));
    }

    @Test
    void eachReasonMapsToItsLine() {
        assertEquals(WoodcutProtectionNudgePolicy.BASE_RADIUS_LINE, WoodcutProtectionNudgePolicy.lineFor("base-radius"));
        assertEquals(WoodcutProtectionNudgePolicy.ADMIN_ZONE_LINE, WoodcutProtectionNudgePolicy.lineFor("admin-zone"));
        assertEquals(WoodcutProtectionNudgePolicy.VILLAGE_LINE, WoodcutProtectionNudgePolicy.lineFor("mapped-village"));
        assertEquals(WoodcutProtectionNudgePolicy.VILLAGE_LINE, WoodcutProtectionNudgePolicy.lineFor("fort-buffer"));
        assertEquals(WoodcutProtectionNudgePolicy.BEEHIVE_LINE, WoodcutProtectionNudgePolicy.lineFor("beehive"));
        assertNull(WoodcutProtectionNudgePolicy.lineFor("human"));
        assertNull(WoodcutProtectionNudgePolicy.lineFor(null));
    }

    @Test
    void linesUsePlainPunctuation() {
        for (String line : new String[]{
                WoodcutProtectionNudgePolicy.BASE_RADIUS_LINE,
                WoodcutProtectionNudgePolicy.ADMIN_ZONE_LINE,
                WoodcutProtectionNudgePolicy.VILLAGE_LINE,
                WoodcutProtectionNudgePolicy.BEEHIVE_LINE}) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains("–"), line);
            assertFalse(line.contains(";"), line);
        }
    }

    @Test
    void claimSaysEachReasonOnlyOnce() {
        Set<String> said = new HashSet<>();
        Map<String, Integer> base = Map.of("base-radius", 6);
        assertEquals(Optional.of(WoodcutProtectionNudgePolicy.BASE_RADIUS_LINE),
                WoodcutProtectionNudgePolicy.claimNudge(base, said));
        assertTrue(said.contains("base-radius"));
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.claimNudge(base, said));
        // A different reason still gets its own one-time line.
        assertEquals(Optional.of(WoodcutProtectionNudgePolicy.BEEHIVE_LINE),
                WoodcutProtectionNudgePolicy.claimNudge(Map.of("beehive", 2), said));
    }

    @Test
    void sharedSetMeansOnlyTheFirstBotSpeaks() {
        Set<String> shared = ConcurrentHashMap.newKeySet();
        Map<String, Integer> village = Map.of("mapped-village", 5);
        assertTrue(WoodcutProtectionNudgePolicy.claimNudge(village, shared).isPresent());
        assertTrue(WoodcutProtectionNudgePolicy.claimNudge(village, shared).isEmpty());
    }

    @Test
    void noLineWhenNothingWasProtected() {
        Set<String> said = new HashSet<>();
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.claimNudge(Map.of("human", 8), said));
        assertTrue(said.isEmpty());
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.claimNudge(Map.of("base-radius", 3), null));
    }

    @Test
    void villageReasonsShareOneSaidKey() {
        assertEquals("village", WoodcutProtectionNudgePolicy.saidKeyFor("mapped-village"));
        assertEquals("village", WoodcutProtectionNudgePolicy.saidKeyFor("fort-buffer"));
        assertEquals("base-radius", WoodcutProtectionNudgePolicy.saidKeyFor("base-radius"));
        assertNull(WoodcutProtectionNudgePolicy.saidKeyFor(null));

        Set<String> said = new HashSet<>();
        assertEquals(Optional.of(WoodcutProtectionNudgePolicy.VILLAGE_LINE),
                WoodcutProtectionNudgePolicy.claimNudge(Map.of("mapped-village", 4), said));
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.claimNudge(Map.of("fort-buffer", 4), said));
        assertEquals(Set.of("village"), said);
    }

    @Test
    void clearingTheSetLetsTheNudgeRepeat() {
        Set<String> said = ConcurrentHashMap.newKeySet();
        Map<String, Integer> base = Map.of("base-radius", 3);
        assertTrue(WoodcutProtectionNudgePolicy.claimNudge(base, said).isPresent());
        assertTrue(WoodcutProtectionNudgePolicy.claimNudge(base, said).isEmpty());
        said.clear(); // what WoodcutSkill.clearProtectionNudges() does on server stop
        assertTrue(WoodcutProtectionNudgePolicy.claimNudge(base, said).isPresent());
    }

    @Test
    void spawnBaseDominantSaysSpawnLine() {
        Set<String> said = new HashSet<>();
        Optional<String> line = WoodcutProtectionNudgePolicy.claimNudge(Map.of("spawn-base", 12, "human", 3), said);
        assertEquals(Optional.of(WoodcutProtectionNudgePolicy.SPAWN_BASE_LINE), line);
        assertEquals(Optional.empty(), WoodcutProtectionNudgePolicy.claimNudge(Map.of("spawn-base", 12), said));
    }

    @Test
    void spawnBaseLosesTiesToPlayerClaims() {
        assertEquals(Optional.of("admin-zone"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("spawn-base", 4, "admin-zone", 4)));
        assertEquals(Optional.of("base-radius"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("spawn-base", 4, "base-radius", 4)));
        assertEquals(Optional.of("spawn-base"),
                WoodcutProtectionNudgePolicy.dominantReason(Map.of("spawn-base", 4, "mapped-village", 4)));
    }

    @Test
    void spawnLineIsPlainPublicText() {
        String line = WoodcutProtectionNudgePolicy.SPAWN_BASE_LINE;
        assertEquals("The trees around spawn are protected, so I'm heading out to find others.", line);
        assertFalse(line.contains("\u2014"));
        assertFalse(line.contains("\u2013"));
        assertFalse(line.contains(";"));
    }
}
