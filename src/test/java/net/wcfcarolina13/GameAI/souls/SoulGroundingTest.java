package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SoulGroundingTest {

    @Test
    void privateSoulRequiresExactOwnerOrOperator() {
        UUID owner = UUID.randomUUID();
        assertTrue(CompanionCommunicationPolicy.isPrivateSoulAuthorized(true, UUID.randomUUID(), owner));
        assertTrue(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, owner, owner));
        assertFalse(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, UUID.randomUUID(), owner));
        assertFalse(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, owner, null));
    }

    @Test
    void remoteSnapshotOmitsPlayerState() {
        SoulTypes.BotSnapshot bot = mock(SoulTypes.BotSnapshot.class);
        SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
                UUID.randomUUID(), "Player", 48, "east",
                20.0F, 20.0F, 20, "secret_map", false);
        SoulTypes.GroundingSnapshot snapshot = SoulSnapshotBuilder.assemble(
                bot, player, SoulTypes.Reachability.REMOTE, Instant.EPOCH);
        assertTrue(snapshot.player().isEmpty());
        assertEquals(SoulTypes.Reachability.REMOTE, snapshot.reachability());
    }

    @Test
    void localSnapshotIncludesPlayerState() {
        SoulTypes.BotSnapshot bot = mock(SoulTypes.BotSnapshot.class);
        SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
                UUID.randomUUID(), "Player", 10, "north",
                20.0F, 20.0F, 20, "sword", false);
        SoulTypes.GroundingSnapshot snapshot = SoulSnapshotBuilder.assemble(
                bot, player, SoulTypes.Reachability.LOCAL, Instant.EPOCH);
        assertTrue(snapshot.player().isPresent());
        assertEquals(player, snapshot.player().get());
        assertEquals(SoulTypes.Reachability.LOCAL, snapshot.reachability());
    }

    // --- Reachability classification: LOCAL/REMOTE/UNREACHABLE at the 32-block boundary ---

    @Test
    void reachabilityIsLocalAtExactly32BlocksBoundary() {
        double atBoundary = CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS
                * CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS;
        assertEquals(SoulTypes.Reachability.LOCAL,
                CompanionCommunicationPolicy.classifySoulReachability(true, atBoundary, false));
    }

    @Test
    void reachabilityIsRemoteJustBeyond32BlocksWhenChatGateAllows() {
        double justBeyond = CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS
                * CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS + 1.0D;
        assertEquals(SoulTypes.Reachability.REMOTE,
                CompanionCommunicationPolicy.classifySoulReachability(true, justBeyond, true));
    }

    @Test
    void reachabilityIsUnreachableBeyond32BlocksWithoutChatGate() {
        double justBeyond = CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS
                * CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS + 1.0D;
        assertEquals(SoulTypes.Reachability.UNREACHABLE,
                CompanionCommunicationPolicy.classifySoulReachability(true, justBeyond, false));
    }

    @Test
    void reachabilityIsUnreachableAcrossDimensionsWithoutChatGate() {
        assertEquals(SoulTypes.Reachability.UNREACHABLE,
                CompanionCommunicationPolicy.classifySoulReachability(false, 0.0D, false));
    }

    // --- Coordinate rounding to 8-block increments ---

    @Test
    void coordinatesRoundToNearestEightBlockIncrement() {
        assertEquals(0, SoulSnapshotBuilder.roundToEight(3));
        assertEquals(8, SoulSnapshotBuilder.roundToEight(4));
        assertEquals(8, SoulSnapshotBuilder.roundToEight(5));
        assertEquals(-8, SoulSnapshotBuilder.roundToEight(-5));
        assertEquals(0, SoulSnapshotBuilder.roundToEight(-3));
    }

    // --- Cardinal direction ---

    @Test
    void cardinalDirectionResolvesEightPoints() {
        assertEquals("north", SoulSnapshotBuilder.cardinalDirection(0.0D, -10.0D));
        assertEquals("south", SoulSnapshotBuilder.cardinalDirection(0.0D, 10.0D));
        assertEquals("east", SoulSnapshotBuilder.cardinalDirection(10.0D, 0.0D));
        assertEquals("west", SoulSnapshotBuilder.cardinalDirection(-10.0D, 0.0D));
        assertEquals("northeast", SoulSnapshotBuilder.cardinalDirection(10.0D, -10.0D));
        assertEquals("southwest", SoulSnapshotBuilder.cardinalDirection(-10.0D, 10.0D));
    }

    // --- Time phase ---

    @Test
    void timePhaseBucketsVanillaDayCycle() {
        assertEquals("day", SoulSnapshotBuilder.timePhase(0L));
        assertEquals("day", SoulSnapshotBuilder.timePhase(11_999L));
        assertEquals("dusk", SoulSnapshotBuilder.timePhase(12_000L));
        assertEquals("dusk", SoulSnapshotBuilder.timePhase(12_999L));
        assertEquals("night", SoulSnapshotBuilder.timePhase(13_000L));
        assertEquals("night", SoulSnapshotBuilder.timePhase(22_999L));
        assertEquals("dawn", SoulSnapshotBuilder.timePhase(23_000L));
        assertEquals("dawn", SoulSnapshotBuilder.timePhase(23_999L));
    }

    // --- Resource summary cap of six entries ---

    @Test
    void resourceSummaryCapsAtSixEntries() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("oak_log", 64);
        counts.put("iron_ingot", 12);
        counts.put("cobblestone", 200);
        counts.put("bread", 5);
        counts.put("stick", 30);
        counts.put("coal", 8);
        counts.put("string", 3);

        List<String> summary = SoulSnapshotBuilder.topResourceSummary(counts);

        assertEquals(6, summary.size());
        assertTrue(summary.get(0).contains("cobblestone"));
    }

    // --- Pure situation-capture seam: SoulSnapshotBuilder.buildSituation(SituationInputs) ---

    private static SoulSnapshotBuilder.SituationInputs baseSituationInputs() {
        return new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), false, false, false,
                "", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void hostilesAreSortedNearestFirstAndCappedAtFive() {
        List<SoulSnapshotBuilder.RawEntity> entities = new ArrayList<>();
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie1", true, 10.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie2", true, 3.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie3", true, 7.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie4", true, 1.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie5", true, 20.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie6", true, 15.0D, 0.0D, 0.0D, "front"));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie7", true, 5.0D, 0.0D, 0.0D, "front"));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(5, situation.hostiles().size());
        List<String> orderedNames = situation.hostiles().stream()
                .map(SoulTypes.HostileSighting::name)
                .toList();
        assertEquals(List.of("Zombie4", "Zombie2", "Zombie7", "Zombie3", "Zombie1"), orderedNames);
        List<Integer> orderedDistances = situation.hostiles().stream()
                .map(SoulTypes.HostileSighting::distanceBlocks)
                .toList();
        assertEquals(List.of(1, 3, 5, 7, 10), orderedDistances);
    }

    @Test
    void nonHostileEntitiesAreExcludedFromHostileSightings() {
        List<SoulSnapshotBuilder.RawEntity> entities = List.of(
                new SoulSnapshotBuilder.RawEntity("Cow", false, 2.0D, 0.0D, 0.0D, "front"),
                new SoulSnapshotBuilder.RawEntity("Zombie", true, 4.0D, 0.0D, 0.0D, "left"));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(1, situation.hostiles().size());
        assertEquals("Zombie", situation.hostiles().get(0).name());
    }

    @Test
    void dangerDistancePassesThroughWithNegativeOneDefault() {
        SoulSnapshotBuilder.SituationInputs zero = new SoulSnapshotBuilder.SituationInputs(
                0.0D, List.of(), false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(-1, SoulSnapshotBuilder.buildSituation(zero).dangerDistance());

        SoulSnapshotBuilder.SituationInputs negative = new SoulSnapshotBuilder.SituationInputs(
                -7.0D, List.of(), false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(-1, SoulSnapshotBuilder.buildSituation(negative).dangerDistance());

        SoulSnapshotBuilder.SituationInputs positive = new SoulSnapshotBuilder.SituationInputs(
                12.4D, List.of(), false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(12, SoulSnapshotBuilder.buildSituation(positive).dangerDistance());
    }

    @Test
    void companionDaysComputedFromRecruitedAtEpochMsAsWholeDayFloor() {
        long recruitedAt = 1_000L;
        long twoDaysAndChangeLater = recruitedAt + 2 * 86_400_000L + 12_345L;

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                recruitedAt, -1, twoDaysAndChangeLater,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(2, SoulSnapshotBuilder.buildSituation(inputs).companionDays());
    }

    @Test
    void companionDaysIsUnknownWhenRecruitedAtEpochMsIsZero() {
        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), false, false, false,
                "IDLE", false, false, 0,
                false, false, false, false,
                0L, -1, 999_999L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(-1, SoulSnapshotBuilder.buildSituation(inputs).companionDays());
    }

    @Test
    void absentOptionalInputsYieldEmptySituationSnapshot() {
        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(baseSituationInputs());

        assertEquals(SoulTypes.SituationSnapshot.empty(), situation);
    }
}
