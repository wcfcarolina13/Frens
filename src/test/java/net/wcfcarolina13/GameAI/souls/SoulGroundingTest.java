package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
