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

    // --- Pure situation-capture seam: SoulSnapshotBuilder.buildSituation(SituationInputs) ---

    private static SoulSnapshotBuilder.SituationInputs baseSituationInputs() {
        return new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void buildSituationDigestsRawFacilitiesIntoDescribedLines() {
        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(),
                List.of(new SoulTypes.RawFacility("chest", "Chest"),
                        new SoulTypes.RawFacility("chest", "Chest"),
                        new SoulTypes.RawFacility("furnace", "Furnace"),
                        new SoulTypes.RawFacility("oak_sign", "Oak Sign")),
                false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(List.of("2x Chest (stores items)", "Furnace (smelts ore, cooks food)"),
                situation.facilities());
    }

    @Test
    void hostilesAreSortedNearestFirstAndCappedAtFive() {
        List<SoulSnapshotBuilder.RawEntity> entities = new ArrayList<>();
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie1", true, 10.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie2", true, 3.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie3", true, 7.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie4", true, 1.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie5", true, 20.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie6", true, 15.0D, 0.0D, 0.0D));
        entities.add(new SoulSnapshotBuilder.RawEntity("Zombie7", true, 5.0D, 0.0D, 0.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

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
                new SoulSnapshotBuilder.RawEntity("Cow", false, 2.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("Zombie", true, 4.0D, 0.0D, 0.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(1, situation.hostiles().size());
        assertEquals("Zombie", situation.hostiles().get(0).name());
    }

    @Test
    void hostileDirectionIsWorldCompassBearingFromDxDz() {
        // dx=5, dz=-5 is the compass northeast quadrant (dz negative is north, dx positive is
        // east) — must match cardinalDirection(dx, dz)'s own convention, not a bot-relative
        // front/left/behind/right bearing.
        List<SoulSnapshotBuilder.RawEntity> entities = List.of(
                new SoulSnapshotBuilder.RawEntity("Zombie", true, 5.0D, 0.0D, -5.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(1, situation.hostiles().size());
        assertEquals("northeast", situation.hostiles().get(0).direction());
        assertEquals(SoulSnapshotBuilder.cardinalDirection(5.0D, -5.0D), situation.hostiles().get(0).direction());
    }

    @Test
    void nearbyAnimalsAggregateNonHostileEntitiesExcludingOwnerAndSelf() {
        List<SoulSnapshotBuilder.RawEntity> entities = List.of(
                new SoulSnapshotBuilder.RawEntity("horse", false, 2.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("horse", false, 3.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("horse", false, 4.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("wolf", false, 5.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("villager", false, 6.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("Player", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("Jake", false, 0.5D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("Zombie", true, 8.0D, 0.0D, 0.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "Player", "Jake", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        // 3 horses win the count, then insertion order breaks the tie between wolf and villager;
        // "Player" (the owner) and "Jake" (the bot itself) are excluded entirely; the hostile
        // "Zombie" is excluded from nearbyAnimals (it belongs in hostiles instead).
        assertEquals(List.of("horse x3", "wolf", "villager"), situation.nearbyAnimals());
        assertEquals(1, situation.hostiles().size());
        assertEquals("Zombie", situation.hostiles().get(0).name());
    }

    @Test
    void nearbyAnimalsCappedAtFourMostNumerousEntries() {
        List<SoulSnapshotBuilder.RawEntity> entities = List.of(
                new SoulSnapshotBuilder.RawEntity("cow", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("cow", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("pig", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("sheep", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("chicken", false, 1.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("cat", false, 1.0D, 0.0D, 0.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(4, situation.nearbyAnimals().size());
        assertEquals("cow x2", situation.nearbyAnimals().get(0));
    }

    @Test
    void dangerDistancePassesThroughWithNegativeOneDefault() {
        SoulSnapshotBuilder.SituationInputs zero = new SoulSnapshotBuilder.SituationInputs(
                0.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(-1, SoulSnapshotBuilder.buildSituation(zero).dangerDistance());

        SoulSnapshotBuilder.SituationInputs negative = new SoulSnapshotBuilder.SituationInputs(
                -7.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(-1, SoulSnapshotBuilder.buildSituation(negative).dangerDistance());

        SoulSnapshotBuilder.SituationInputs positive = new SoulSnapshotBuilder.SituationInputs(
                12.4D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(12, SoulSnapshotBuilder.buildSituation(positive).dangerDistance());
    }

    @Test
    void companionDaysComputedFromRecruitedAtEpochMsAsWholeDayFloor() {
        long recruitedAt = 1_000L;
        long twoDaysAndChangeLater = recruitedAt + 2 * 86_400_000L + 12_345L;

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                recruitedAt, -1, twoDaysAndChangeLater,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(2, SoulSnapshotBuilder.buildSituation(inputs).companionDays());
    }

    @Test
    void companionDaysIsUnknownWhenRecruitedAtEpochMsIsZero() {
        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 999_999L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(-1, SoulSnapshotBuilder.buildSituation(inputs).companionDays());
    }

    @Test
    void absentOptionalInputsYieldEmptySituationSnapshot() {
        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(baseSituationInputs());

        assertEquals(SoulTypes.SituationSnapshot.empty(), situation);
    }

    // --- Fix A: following passes through untouched (server-thread-captured, not derived here) ---

    @Test
    void followingPassesThroughFromInputs() {
        SoulSnapshotBuilder.SituationInputs following = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "FOLLOW", true, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(true, SoulSnapshotBuilder.buildSituation(following).following());

        SoulSnapshotBuilder.SituationInputs notFollowing = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "FOLLOW", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(false, SoulSnapshotBuilder.buildSituation(notFollowing).following());
    }

    // --- Fix C: standingOn passes through untouched; nearbyBlocks is deduped/counted/capped ---

    @Test
    void standingOnPassesThroughFromInputs() {
        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "Grass Block", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("Grass Block", SoulSnapshotBuilder.buildSituation(inputs).standingOn());
    }

    @Test
    void nearbyBlocksDedupesCountsAndCapsAtFourMostNumerous() {
        // Raw scan output has heavy duplication (one entry per matching block position); the pure
        // seam must dedupe by type name, rank by count, and cap at 4 -- mirroring nearbyAnimals'
        // own aggregation shape but without an "x2" count suffix on the rendered name.
        List<String> rawBlocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rawBlocks.add("Stone");
        }
        for (int i = 0; i < 3; i++) {
            rawBlocks.add("Dirt");
        }
        rawBlocks.add("Oak Log");
        rawBlocks.add("Water");
        rawBlocks.add("Coal Ore");

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", rawBlocks, List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertEquals(4, situation.nearbyBlocks().size());
        assertEquals("Stone", situation.nearbyBlocks().get(0));
        assertEquals("Dirt", situation.nearbyBlocks().get(1));
        assertTrue(situation.nearbyBlocks().containsAll(List.of("Oak Log", "Water")));
        assertFalse(situation.nearbyBlocks().contains("Coal Ore"),
                "the 5th distinct block type should be dropped by the top-4 cap");
    }

    @Test
    void nearbyBlocksDropsBlankEntries() {
        // Note: List.copyOf (used by SituationInputs' canonical constructor to defensively copy
        // nearbyBlocks) rejects null elements outright, so a null block name can never reach this
        // seam -- only blank/whitespace names, which BlockDistanceLimitedSearch never produces but
        // this filter guards against defensively anyway.
        List<String> rawBlocks = new ArrayList<>();
        rawBlocks.add("Stone");
        rawBlocks.add("");
        rawBlocks.add("   ");

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", rawBlocks, List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(List.of("Stone"), SoulSnapshotBuilder.buildSituation(inputs).nearbyBlocks());
    }

    // --- Round-4 Fix 1: species-first naming with custom-name annotation ---

    @Test
    void formatEntityNameUsesSpeciesAloneWithoutACustomName() {
        assertEquals("wolf", SoulSnapshotBuilder.formatEntityName("wolf", null));
        assertEquals("parrot", SoulSnapshotBuilder.formatEntityName("parrot", ""));
        assertEquals("horse", SoulSnapshotBuilder.formatEntityName("horse", "   "));
    }

    @Test
    void formatEntityNameAnnotatesSpeciesWithCustomNameWhenPresent() {
        assertEquals("wolf (Rex)", SoulSnapshotBuilder.formatEntityName("wolf", "Rex"));
        assertEquals("parrot (Polly)", SoulSnapshotBuilder.formatEntityName("parrot", "Polly"));
    }

    @Test
    void formatEntityNameTreatsNullTypePathAsEmptyBase() {
        assertEquals("", SoulSnapshotBuilder.formatEntityName(null, null));
        assertEquals(" (Rex)", SoulSnapshotBuilder.formatEntityName(null, "Rex"));
    }

    // --- Round-4 Fix 2: shoulder-perched pets ---

    @Test
    void shoulderEntryFormatsSpeciesWithOwnerLabel() {
        assertEquals("parrot (on your shoulder)", SoulSnapshotBuilder.shoulderEntry("parrot", "your shoulder"));
        assertEquals("parrot (on Bradley's shoulder)",
                SoulSnapshotBuilder.shoulderEntry("parrot", "Bradley's shoulder"));
    }

    @Test
    void shoulderPetsFlowThroughTheSameNearbyAnimalsAggregationAsGroundSightings() {
        // Shoulder pets are folded into the RawEntity list by captureSituation itself (not a
        // separate SituationInputs field) so this seam's existing name-grouping/owner-exclusion/
        // cap logic applies uniformly -- exercised here with pre-formatted shoulder-entry names.
        List<SoulSnapshotBuilder.RawEntity> entities = List.of(
                new SoulSnapshotBuilder.RawEntity(
                        SoulSnapshotBuilder.shoulderEntry("parrot", "your shoulder"), false, 0.0D, 0.0D, 0.0D),
                new SoulSnapshotBuilder.RawEntity("cow", false, 5.0D, 0.0D, 0.0D));

        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, entities, "", "Jake", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        SoulTypes.SituationSnapshot situation = SoulSnapshotBuilder.buildSituation(inputs);

        assertTrue(situation.nearbyAnimals().contains("parrot (on your shoulder)"));
        assertTrue(situation.nearbyAnimals().contains("cow"));
    }

    // --- Fix D: atBase passes through untouched (already-resolved by captureSituation) ---

    @Test
    void atBasePassesThroughFromInputs() {
        SoulSnapshotBuilder.SituationInputs inputs = new SoulSnapshotBuilder.SituationInputs(
                -1.0D, List.of(), "", "", "", List.of(), List.of(), false, false, false,
                "IDLE", false, false, false, 0,
                false, false, false, false,
                0L, -1, 0L,
                Optional.empty(), 1, Optional.empty(), Optional.of("Workshop"), Optional.empty(),
                Optional.empty());
        assertEquals(Optional.of("Workshop"), SoulSnapshotBuilder.buildSituation(inputs).atBase());
    }
}
