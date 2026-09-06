package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules of the per-bot mind: stance ladder, open threads, day consolidation, seen-registry. */
class SoulMindOpsTest {

    private static final AtomicLong TICK = new AtomicLong(100);

    private static SoulTypes.SoulEvent event(SoulTypes.EventType type, SoulTypes.Salience salience,
                                              Map<String, String> facts, List<UUID> participants) {
        long tick = TICK.getAndAdd(100);
        return new SoulTypes.SoulEvent(UUID.randomUUID(), type, UUID.randomUUID(), participants,
                "overworld", "plains", facts, SoulTypes.Witness.SELF, tick,
                Instant.ofEpochMilli(tick), salience);
    }

    @Test
    void stanceClampsAndBaseline() {
        assertEquals(new SoulTypes.Stance(6, 0, 0), new SoulTypes.Stance(9, -2, 0));
        assertEquals("", SoulMindOps.stanceClause(SoulTypes.Stance.BASELINE, "Roti"));
        assertEquals("wary of Roti", SoulMindOps.stanceClause(new SoulTypes.Stance(1, 0, 3), "Roti"));
        assertEquals("would follow Roti anywhere, fed up with being ignored",
                SoulMindOps.stanceClause(new SoulTypes.Stance(5, 2, 3), "Roti"));
    }

    @Test
    void threadsOpenAnswerExpireAndCap() {
        SoulTypes.SoulMind m = SoulTypes.SoulMind.empty();
        UUID bob = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            m = SoulMindOps.openThread(m, new SoulTypes.OpenThread(bob, "q" + i + "?", 1_000L * i, false));
        }
        assertEquals(3, m.threads().size());
        assertEquals("q1?", m.threads().get(0).question(), "oldest evicted");
        SoulTypes.SoulMind answered = SoulMindOps.markAnswered(m);
        assertTrue(answered.threads().isEmpty());
        assertEquals(4, answered.playerStance().trust());
        assertEquals(2, answered.playerStance().curiosity());
        SoulTypes.SoulMind expired = SoulMindOps.expireThreads(m, 3_000L + SoulMindOps.THREAD_TTL_MS + 1);
        assertTrue(expired.threads().stream().allMatch(SoulTypes.OpenThread::expired));
        assertEquals(3, expired.playerStance().exasperation());
        assertTrue(SoulMindOps.dropExpired(expired).threads().isEmpty());
        assertTrue(SoulMindOps.markAnswered(expired).threads().isEmpty());
        assertEquals(3, SoulMindOps.markAnswered(expired).playerStance().trust(), "expired threads earn no trust");
    }

    @Test
    void questionExtraction() {
        assertEquals(Optional.of("Did you find the iron, Roti?"),
                SoulMindOps.extractQuestion("Did you find the iron, Roti?", "RotiWokeman", false));
        assertEquals(Optional.of("Where next?"), SoulMindOps.extractQuestion("Where next?", "RotiWokeman", true));
        assertTrue(SoulMindOps.extractQuestion("Where next?", "RotiWokeman", false).isEmpty(), "not addressed");
        assertTrue(SoulMindOps.extractQuestion("Fine by me.", "RotiWokeman", true).isEmpty(), "not a question");
        assertEquals(SoulMindOps.MAX_QUESTION_CHARS,
                SoulMindOps.extractQuestion("a".repeat(200) + "?", "R", true).orElseThrow().length());
    }

    @Test
    void consolidationKeepsTopTopicsDecaysAndCaps() {
        UUID roti = UUID.randomUUID();
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, Map.of("amount", "5", "source", "skeleton"), List.of(roti)),
                event(SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, Map.of("amount", "3", "source", "skeleton"), List.of()),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, Map.of("mob", "Zombie"), List.of()),
                event(SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.LOW, Map.of("task", "skill:woodcut", "category", "skill"), List.of()),
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.LOW, Map.of(), List.of()),
                event(SoulTypes.EventType.TASK_STARTED, SoulTypes.Salience.LOW, Map.of("task", "skill:sleep", "category", "skill"), List.of()),
                event(SoulTypes.EventType.SELF_RESCUE, SoulTypes.Salience.HIGH, Map.of(), List.of()));
        SoulTypes.SoulMind before = new SoulTypes.SoulMind(1, new SoulTypes.Stance(5, 3, 1),
                List.of(), List.of(new SoulTypes.DayMemory(2, "the work", "finished fishing", "river", List.of(), 1, -1)),
                Set.of(), 0L, 2, -1);
        SoulTypes.SoulMind after = SoulMindOps.consolidate(before, events, 3, "forest",
                id -> id.equals(roti) ? "Roti" : "?", 999L);
        List<String> topics = after.memories().stream().map(SoulTypes.DayMemory::topic).toList();
        assertEquals(List.of("getting stuck", "getting hurt", "fighting"), topics,
                "top 3 by score; sleep excluded; old memory decayed to 0 and evicted");
        SoulTypes.DayMemory hurt = after.memories().get(1);
        assertEquals("took a beating from a skeleton", hurt.phrase());
        assertEquals(List.of("Roti"), hurt.participants());
        assertEquals("forest", hurt.place());
        assertEquals(new SoulTypes.Stance(4, 2, 2), after.playerStance(), "one step toward baseline");
        assertEquals(3, after.lastDay());
        assertEquals(999L, after.lastConsolidatedAtMs());
    }

    @Test
    void anchorsFromMemoriesAndExpiredThreads() {
        UUID bob = UUID.randomUUID();
        SoulTypes.SoulMind m = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE,
                List.of(new SoulTypes.OpenThread(bob, "Did you find iron?", 0L, true),
                        new SoulTypes.OpenThread(bob, "Open one?", 0L, false)),
                List.of(new SoulTypes.DayMemory(1, "fighting", "slew a zombie", "forest", List.of(), 4, -1),
                        new SoulTypes.DayMemory(3, "getting hurt", "took a beating", "cave", List.of(), 6, 3)),
                Set.of(), 0L, 3, -1);
        List<SoulBanterSeed.Anchor> anchors = SoulMindOps.anchors(m, "Bob", 4, new Random(1));
        assertEquals(2, anchors.size());
        assertEquals("unanswered question", anchors.get(0).topic());
        assertEquals("Bob never got an answer about \"Did you find iron?\"", anchors.get(0).phrase());
        assertEquals(5, anchors.get(0).weight());
        assertEquals("memory:fighting", anchors.get(1).topic());
        assertEquals("remember when slew a zombie on day 1", anchors.get(1).phrase());
        assertEquals(4, anchors.get(1).weight());
        // "getting hurt" was recalled on day 3 -> skipped until day 6
    }

    @Test
    void seenRegistryCaps() {
        SoulTypes.SoulMind m = SoulTypes.SoulMind.empty();
        for (int i = 0; i < SoulMindOps.MAX_SEEN + 5; i++) {
            m = SoulMindOps.withSeen(m, Set.of("k" + i));
        }
        assertEquals(SoulMindOps.MAX_SEEN, m.seen().size());
        assertFalse(m.seen().contains("k0"));
    }

    @Test
    void consolidateDecaysPlayerMemoriesAndNoteRecalledDispatchesSaidKeys() {
        UUID p = UUID.randomUUID();
        SoulTypes.SoulMind m = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(p, 1, "Roti wants a farm", 2, -1, List.of())));
        SoulTypes.SoulMind c = SoulMindOps.consolidate(m, List.of(), 5, "plains", id -> "Roti", 1_000L);
        assertEquals(1, c.playerMemories().get(0).salience());
        String key = SoulMemoryDigestOps.factKey("Roti wants a farm");
        SoulTypes.SoulMind r = SoulMindOps.noteRecalled(c, key, 5);
        assertEquals(4, r.playerMemories().get(0).salience());
        assertEquals(5, r.playerMemories().get(0).lastRecalledDay());
    }

    // === peer stance (conversation ontology Phase 3a) ===

    private static final UUID ALFA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRAVO = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static SoulGroupTypes.SceneLine line(int index, String text) {
        return new SoulGroupTypes.SceneLine(index, text);
    }

    private static SoulTypes.SoulMind mindWithPeers(Map<UUID, SoulTypes.PeerStance> peers) {
        return SoulMindOps.withPeerStances(SoulTypes.SoulMind.empty(), peers);
    }

    @Test
    void peerSceneAppliesCuriosityExasperationAndTrust() {
        // Bravo asks Alfa a question and Alfa never answers: Alfa is curious, Bravo exasperated.
        List<String> names = List.of("Alfa", "Bravo");
        List<UUID> ids = List.of(ALFA, BRAVO);
        List<SoulGroupTypes.SceneLine> delivered = List.of(
                line(0, "Nice weather."),
                line(1, "Alfa, did you bring the pickaxe?"));

        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, names, ids, delivered, 4);
        SoulTypes.PeerStance alfaOnBravo = alfa.peerStances().get(BRAVO);
        assertEquals(new SoulTypes.Stance(4, 0, 4), alfaOnBravo.stance(), "curiosity +1 (asked), trust +1 (both spoke)");
        assertEquals(4, alfaOnBravo.lastTrustDay());

        SoulTypes.SoulMind bravo = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 1, names, ids, delivered, 4);
        assertEquals(new SoulTypes.Stance(4, 1, 3), bravo.peerStances().get(ALFA).stance(),
                "exasperation +1 (unanswered), trust +1 (both spoke)");
    }

    @Test
    void peerTrustBumpsOnlyOncePerDayPerPair() {
        List<String> names = List.of("Alfa", "Bravo");
        List<UUID> ids = List.of(ALFA, BRAVO);
        List<SoulGroupTypes.SceneLine> delivered = List.of(line(0, "Hi."), line(1, "Hello."));
        SoulTypes.SoulMind once = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, names, ids, delivered, 4);
        assertEquals(4, once.peerStances().get(BRAVO).stance().trust());
        SoulTypes.SoulMind twice = SoulMindOps.notePeerScene(once, 0, names, ids, delivered, 4);
        assertSame(once, twice, "same day, nothing left to change");
        SoulTypes.SoulMind nextDay = SoulMindOps.notePeerScene(once, 0, names, ids, delivered, 5);
        assertEquals(5, nextDay.peerStances().get(BRAVO).stance().trust());
        assertEquals(5, nextDay.peerStances().get(BRAVO).lastTrustDay());
    }

    @Test
    void peerRulesApplyAtMostOncePerScene() {
        List<String> names = List.of("Alfa", "Bravo");
        List<UUID> ids = List.of(ALFA, BRAVO);
        List<SoulGroupTypes.SceneLine> delivered = List.of(
                line(1, "alfa, where were you?"),
                line(0, "Busy."),
                line(1, "Alfa, really?"));
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, names, ids, delivered, 1);
        assertEquals(new SoulTypes.Stance(4, 0, 4), alfa.peerStances().get(BRAVO).stance(),
                "two questions in one scene still only +1 curiosity");
    }

    @Test
    void peerExasperationNeedsNoLaterPeerLine() {
        List<String> names = List.of("Alfa", "Bravo");
        List<UUID> ids = List.of(ALFA, BRAVO);
        List<SoulGroupTypes.SceneLine> answered = List.of(
                line(0, "Bravo, did you eat?"),
                line(1, "I did."));
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, names, ids, answered, 1);
        assertEquals(0, alfa.peerStances().get(BRAVO).stance().exasperation(), "peer answered afterwards");
    }

    @Test
    void peerCapEvictsFlattestAndNeverTheUpdatedPeer() {
        Map<UUID, SoulTypes.PeerStance> peers = new LinkedHashMap<>();
        // Six existing peers; the first two are exactly baseline (flattest, tie on distance 0).
        UUID flatLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID flatHigh = UUID.fromString("00000000-0000-0000-0000-000000000002");
        peers.put(flatHigh, new SoulTypes.PeerStance(SoulTypes.Stance.BASELINE, -1));
        peers.put(flatLow, new SoulTypes.PeerStance(SoulTypes.Stance.BASELINE, -1));
        for (int i = 3; i <= 6; i++) {
            peers.put(UUID.fromString("00000000-0000-0000-0000-00000000000" + i),
                    new SoulTypes.PeerStance(new SoulTypes.Stance(6, 4, 6), -1));
        }
        List<String> names = List.of("Alfa", "Bravo");
        List<UUID> ids = List.of(ALFA, BRAVO);
        List<SoulGroupTypes.SceneLine> delivered = List.of(line(0, "Hi."), line(1, "Hello."));
        SoulTypes.SoulMind after =
                SoulMindOps.notePeerScene(mindWithPeers(peers), 0, names, ids, delivered, 1);
        assertEquals(SoulMindOps.MAX_PEER_STANCES, after.peerStances().size());
        assertTrue(after.peerStances().containsKey(BRAVO), "the peer just updated is never evicted");
        assertFalse(after.peerStances().containsKey(flatLow), "lowest UUID wins the distance tie");
        assertTrue(after.peerStances().containsKey(flatHigh));
    }

    @Test
    void peerStancesDecayAndDropAtBaseline() {
        Map<UUID, SoulTypes.PeerStance> peers = new LinkedHashMap<>();
        peers.put(ALFA, new SoulTypes.PeerStance(new SoulTypes.Stance(4, 1, 3), 2));
        peers.put(BRAVO, new SoulTypes.PeerStance(new SoulTypes.Stance(6, 0, 3), 2));
        SoulTypes.SoulMind after = SoulMindOps.consolidate(mindWithPeers(peers), List.of(), 3, "forest",
                id -> "?", 500L);
        assertFalse(after.peerStances().containsKey(ALFA), "reached baseline -> forgotten");
        assertEquals(new SoulTypes.Stance(5, 0, 3), after.peerStances().get(BRAVO).stance());
        assertEquals(2, after.peerStances().get(BRAVO).lastTrustDay());
    }

    @Test
    void peerStanceClauseLadder() {
        assertEquals("", SoulMindOps.peerStanceClause(SoulTypes.Stance.BASELINE, "Bravo"));
        assertEquals("wary of Bravo", SoulMindOps.peerStanceClause(new SoulTypes.Stance(1, 0, 3), "Bravo"));
        assertEquals("thick as thieves with Bravo",
                SoulMindOps.peerStanceClause(new SoulTypes.Stance(5, 0, 3), "Bravo"));
        assertEquals("a little tired of Bravo", SoulMindOps.peerStanceClause(new SoulTypes.Stance(3, 2, 3), "Bravo"));
        assertEquals("short with Bravo", SoulMindOps.peerStanceClause(new SoulTypes.Stance(3, 4, 3), "Bravo"));
        assertEquals("curious about Bravo", SoulMindOps.peerStanceClause(new SoulTypes.Stance(3, 0, 5), "Bravo"));
        assertEquals("wary of Bravo, short with Bravo, curious about Bravo",
                SoulMindOps.peerStanceClause(new SoulTypes.Stance(0, 6, 6), "Bravo"));
    }
}
