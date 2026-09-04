package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
