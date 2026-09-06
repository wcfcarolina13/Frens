package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@code ##FRENS} side-channel rules (ontology Phase 3c): drop-based per-element
 * validation, the ≤3 fact cap, the 0.6 confidence ceiling, owner/off-roster rejection, and the
 * once-per-scene / once-per-day / range-clamped peer stance bump.
 */
class SoulSideChannelOpsTest {

    private static final Set<String> ROSTER = new LinkedHashSet<>(List.of("Jake", "Sara"));
    private static final String OWNER = "Roti";

    private static SoulSideChannelOps.SideEffects parse(String raw) {
        return SoulSideChannelOps.parse(Optional.of(raw), ROSTER, OWNER);
    }

    @Test
    void absentSentinelYieldsEmptyEffects() {
        SoulSideChannelOps.SideEffects fx = SoulSideChannelOps.parse(Optional.empty(), ROSTER, OWNER);
        assertTrue(fx.isEmpty());
        assertFalse(fx.unparsed(), "no sentinel is not a parse failure");
        assertTrue(SoulSideChannelOps.parse(Optional.of("   "), ROSTER, OWNER).isEmpty());
    }

    @Test
    void unparseableJsonIsEmptyAndFlagged() {
        SoulSideChannelOps.SideEffects fx = parse("{\"stance\": {oops");
        assertTrue(fx.isEmpty());
        assertTrue(fx.unparsed());
        assertTrue(parse("[1,2,3]").unparsed(), "a non-object tail is unparsed too");
    }

    @Test
    void unknownKeysAreIgnoredAndKnownOnesApplied() {
        SoulSideChannelOps.SideEffects fx = parse(
                "{\"mood\":\"grim\",\"stance\":{\"Jake\":{\"warmth\":1,\"vibes\":3}},"
                        + "\"threads\":{\"closed\":[\"Did you find the iron?\"]}}");
        assertFalse(fx.unparsed());
        assertEquals(1, fx.stanceDeltas().size());
        assertEquals("Jake", fx.stanceDeltas().get(0).peerName());
        assertEquals(SoulSideChannelOps.Axis.WARMTH, fx.stanceDeltas().get(0).axis());
        assertEquals(0, fx.droppedStance(), "an unknown axis key is ignored, not counted as dropped");
        assertTrue(fx.facts().isEmpty(), "threads.closed is parsed and deliberately ignored");
    }

    @Test
    void stanceMagnitudeAboveOneIsDropped() {
        SoulSideChannelOps.SideEffects fx = parse(
                "{\"stance\":{\"Jake\":{\"warmth\":3,\"friction\":-1},\"Sara\":{\"curiosity\":0.5}}}");
        assertEquals(1, fx.stanceDeltas().size());
        assertEquals(SoulSideChannelOps.Axis.FRICTION, fx.stanceDeltas().get(0).axis());
        assertEquals(2, fx.droppedStance(), "magnitude 3 and the non-integer both drop");
    }

    @Test
    void zeroIsANoOpNotADrop() {
        SoulSideChannelOps.SideEffects fx = parse("{\"stance\":{\"Jake\":{\"warmth\":0}}}");
        assertTrue(fx.stanceDeltas().isEmpty());
        assertEquals(0, fx.droppedStance());
    }

    @Test
    void ownerAndOffRosterStanceTargetsAreDropped() {
        SoulSideChannelOps.SideEffects fx = parse(
                "{\"stance\":{\"Roti\":{\"warmth\":1},\"Villager\":{\"friction\":1},\"sara\":{\"warmth\":1}}}");
        assertEquals(1, fx.stanceDeltas().size(), "only the roster peer survives (case-insensitive)");
        assertEquals("Sara", fx.stanceDeltas().get(0).peerName(), "canonical roster spelling is returned");
        assertEquals(2, fx.droppedStance());
    }

    @Test
    void anAxisMovesAtMostOncePerScenePerPeer() {
        SoulSideChannelOps.SideEffects fx = parse(
                "{\"stance\":{\"Jake\":{\"warmth\":1}},\"facts\":[]}");
        assertEquals(1, fx.stanceDeltas().size());
        // Duplicate peer keys inside one object: Jackson keeps the last, so assert the
        // scene-level dedupe through two differently-cased names for the same peer instead.
        SoulSideChannelOps.SideEffects twice = SoulSideChannelOps.parse(
                Optional.of("{\"stance\":{\"Jake\":{\"warmth\":1},\"JAKE\":{\"warmth\":-1}}}"),
                ROSTER, OWNER);
        assertEquals(1, twice.stanceDeltas().size());
        assertEquals(1, twice.stanceDeltas().get(0).delta(), "first writer wins");
        assertEquals(1, twice.droppedStance());
    }

    @Test
    void factsAreTruncatedToThreeAndTheRestCounted() {
        SoulSideChannelOps.SideEffects fx = parse("{\"facts\":["
                + "[\"Jake\",\"LIKES\",\"caves\",0.5],"
                + "[\"Sara\",\"FEARS\",\"the dark\",0.5],"
                + "[\"Roti\",\"PROMISED\",\"a bridge\",0.5],"
                + "[\"the world\",\"WANTS\",\"rain\",0.5]]}");
        assertEquals(3, fx.facts().size());
        assertEquals(1, fx.droppedFacts());
        assertEquals("Roti", fx.facts().get(2).subject(), "the owner is a legal fact subject");
    }

    @Test
    void confidenceIsClampedToPointSix() {
        SoulSideChannelOps.SideEffects fx = parse("{\"facts\":[[\"Jake\",\"LIKES\",\"caves\",0.99]]}");
        assertEquals(1, fx.facts().size());
        assertEquals(0.6d, fx.facts().get(0).confidence(), 1e-9);
        assertEquals(0.4d, parse("{\"facts\":[[\"Jake\",\"LIKES\",\"caves\",0.4]]}")
                .facts().get(0).confidence(), 1e-9, "a modest claim is left alone");
    }

    @Test
    void relationOutsideTheEnumIsDropped() {
        SoulSideChannelOps.SideEffects fx = parse("{\"facts\":["
                + "[\"Jake\",\"ADORES\",\"caves\",0.5],[\"Jake\",\"likes\",\"caves\",0.5]]}");
        assertEquals(1, fx.facts().size(), "the enum is closed; casing is not");
        assertEquals("LIKES", fx.facts().get(0).relation());
        assertEquals(1, fx.droppedFacts());
    }

    @Test
    void subjectNotInTheSceneIsDropped() {
        SoulSideChannelOps.SideEffects fx = parse("{\"facts\":["
                + "[\"Villager\",\"LIKES\",\"emeralds\",0.5],"
                + "[\"the world\",\"BAD_AT\",\"keeping quiet\",0.5]]}");
        assertEquals(1, fx.facts().size());
        assertEquals(SoulSideChannelOps.WORLD_SUBJECT, fx.facts().get(0).subject());
        assertEquals(1, fx.droppedFacts());
    }

    @Test
    void malformedFactTuplesAreDroppedIndividually() {
        SoulSideChannelOps.SideEffects fx = parse("{\"facts\":["
                + "[\"Jake\",\"LIKES\"],\"nope\",[\"Jake\",\"LIKES\",\"\",0.5],"
                + "[\"Jake\",\"LIKES\",\"caves\",\"lots\"],[\"Jake\",\"FEARS\",\"lava\",0.3]]}");
        assertEquals(1, fx.facts().size());
        assertEquals(4, fx.droppedFacts());
    }

    // === bumpPeerStance ===

    private static final UUID PEER = UUID.nameUUIDFromBytes("peer".getBytes());

    private static SoulTypes.SoulMind mind() {
        return SoulTypes.SoulMind.empty();
    }

    @Test
    void bumpMovesTheMappedAxisAndStampsTheDayGuard() {
        SoulTypes.SoulMind after = SoulMindOps.bumpPeerStance(mind(), PEER,
                SoulSideChannelOps.Axis.WARMTH, 1, 4);
        SoulTypes.PeerStance ps = after.peerStances().get(PEER);
        assertEquals(SoulTypes.Stance.BASELINE.trust() + 1, ps.stance().trust());
        assertEquals(4, ps.lastTrustDay());
        assertEquals(-1, ps.lastAskDay(), "warmth does not consume the ask guard");

        SoulTypes.SoulMind friction = SoulMindOps.bumpPeerStance(mind(), PEER,
                SoulSideChannelOps.Axis.FRICTION, 1, 4);
        assertEquals(1, friction.peerStances().get(PEER).stance().exasperation());
        assertEquals(4, friction.peerStances().get(PEER).lastAskDay());
        assertEquals(-1, friction.peerStances().get(PEER).lastTrustDay());

        SoulTypes.SoulMind curious = SoulMindOps.bumpPeerStance(mind(), PEER,
                SoulSideChannelOps.Axis.CURIOSITY, -1, 4);
        assertEquals(SoulTypes.Stance.BASELINE.curiosity() - 1,
                curious.peerStances().get(PEER).stance().curiosity());
    }

    @Test
    void aSpentDayGuardBlocksASecondBumpAndReturnsTheSameMind() {
        SoulTypes.SoulMind once = SoulMindOps.bumpPeerStance(mind(), PEER,
                SoulSideChannelOps.Axis.WARMTH, 1, 4);
        SoulTypes.SoulMind twice = SoulMindOps.bumpPeerStance(once, PEER,
                SoulSideChannelOps.Axis.WARMTH, 1, 4);
        assertSame(once, twice, "once per Minecraft day per pair per guard");
        SoulTypes.SoulMind nextDay = SoulMindOps.bumpPeerStance(once, PEER,
                SoulSideChannelOps.Axis.WARMTH, 1, 5);
        assertEquals(SoulTypes.Stance.BASELINE.trust() + 2, nextDay.peerStances().get(PEER).stance().trust());
    }

    @Test
    void frictionAndCuriosityShareTheAskGuard() {
        SoulTypes.SoulMind once = SoulMindOps.bumpPeerStance(mind(), PEER,
                SoulSideChannelOps.Axis.FRICTION, 1, 7);
        assertSame(once, SoulMindOps.bumpPeerStance(once, PEER,
                SoulSideChannelOps.Axis.CURIOSITY, 1, 7));
    }

    @Test
    void theRangeClampHoldsAtBothEnds() {
        SoulTypes.SoulMind mind = mind();
        for (int day = 0; day < 12; day++) {
            mind = SoulMindOps.bumpPeerStance(mind, PEER, SoulSideChannelOps.Axis.WARMTH, 1, day);
        }
        assertEquals(6, mind.peerStances().get(PEER).stance().trust());
        SoulTypes.SoulMind down = mind();
        for (int day = 0; day < 12; day++) {
            down = SoulMindOps.bumpPeerStance(down, PEER, SoulSideChannelOps.Axis.WARMTH, -1, day);
        }
        assertEquals(0, down.peerStances().get(PEER).stance().trust());
    }

    @Test
    void nullTargetOrZeroDeltaChangesNothing() {
        SoulTypes.SoulMind base = mind();
        assertSame(base, SoulMindOps.bumpPeerStance(base, null, SoulSideChannelOps.Axis.WARMTH, 1, 1));
        assertSame(base, SoulMindOps.bumpPeerStance(base, PEER, SoulSideChannelOps.Axis.WARMTH, 0, 1));
        assertSame(base, SoulMindOps.bumpPeerStance(base, PEER, null, 1, 1));
        assertSame(base, SoulMindOps.bumpPeerStance(base, PEER, SoulSideChannelOps.Axis.WARMTH, 2, 1),
                "a magnitude the parser would have dropped is refused here too");
    }
}
