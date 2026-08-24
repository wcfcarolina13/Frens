package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoulSituationTypesTest {

    private SoulTypes.BotSnapshot botFixture() {
        return new SoulTypes.BotSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
                "minecraft:overworld", "plains", 0, 64, 0, true,
                "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
                8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
                "Workshop", "Player", true, 2, false, Optional.empty());
    }

    // === Mandated tests (verbatim from brief) ===

    @Test
    void groundingSnapshotFourArgConstructorDefaultsToEmptySituation() {
        SoulTypes.GroundingSnapshot g = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, botFixture(), Optional.empty(), Instant.EPOCH);
        assertEquals(SoulTypes.SituationSnapshot.empty(), g.situation());
    }

    @Test
    void situationSnapshotDefensivelyCopiesAndNormalizes() {
        List<SoulTypes.HostileSighting> hostiles = new ArrayList<>();
        hostiles.add(new SoulTypes.HostileSighting("zombie", "north", 6));
        SoulTypes.SituationSnapshot s = new SoulTypes.SituationSnapshot(
                8, hostiles, List.of(), true, false, true, "GUARD",
                true, false, 2, false, false, false, false,
                14, 1, Optional.empty(), 3, null, null, Optional.of("fishing"));
        hostiles.clear();
        assertEquals(1, s.hostiles().size());
        assertThrows(UnsupportedOperationException.class,
                () -> s.hostiles().add(new SoulTypes.HostileSighting("x", "s", 1)));
        assertEquals(Optional.empty(), s.lastSleepLabel());
        assertEquals(Optional.empty(), s.hunt());
    }

    // === Additional normalization coverage ===

    @Test
    void hostileSightingNormalizesNullStrings() {
        SoulTypes.HostileSighting sighting = new SoulTypes.HostileSighting(null, null, 4);
        assertEquals("", sighting.name());
        assertEquals("", sighting.direction());
    }

    @Test
    void mountSummaryNormalizesNullType() {
        SoulTypes.MountSummary mount = new SoulTypes.MountSummary(null, 10.0F, 20.0F, true);
        assertEquals("", mount.type());
    }

    @Test
    void huntSummaryNormalizesNullTarget() {
        SoulTypes.HuntSummary hunt = new SoulTypes.HuntSummary(null, 1, 5);
        assertEquals("", hunt.target());
    }

    @Test
    void situationSnapshotNullBehaviorModeAndMountNormalize() {
        SoulTypes.SituationSnapshot s = new SoulTypes.SituationSnapshot(
                -1, null, null, false, false, false, null,
                false, false, 0, false, false, false, false,
                -1, -1, null, 0, null, null, null);
        assertEquals("", s.behaviorMode());
        assertEquals(List.of(), s.hostiles());
        assertEquals(List.of(), s.nearbyAnimals());
        assertEquals(Optional.empty(), s.mount());
        assertEquals(Optional.empty(), s.lastSleepLabel());
        assertEquals(Optional.empty(), s.hunt());
        assertEquals(Optional.empty(), s.lastHobby());
    }

    @Test
    void situationSnapshotEmptyIsStable() {
        assertEquals(SoulTypes.SituationSnapshot.empty(), SoulTypes.SituationSnapshot.empty());
    }

    @Test
    void groundingSnapshotFiveArgConstructorAcceptsNullSituation() {
        SoulTypes.GroundingSnapshot g = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, botFixture(), Optional.empty(), null, Instant.EPOCH);
        assertEquals(SoulTypes.SituationSnapshot.empty(), g.situation());
    }
}
