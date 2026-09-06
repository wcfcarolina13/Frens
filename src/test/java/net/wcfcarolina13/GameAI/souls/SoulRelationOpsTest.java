package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules for typed relation facts (ontology Phase 3b). No game classes anywhere. */
class SoulRelationOpsTest {

    private static final Set<String> SCENE = Set.of("Bob", "Roti");

    private static SoulTypes.RelationFact fact(String subject, SoulTypes.Relation relation, String object,
                                               double confidence, int day, int salience) {
        return new SoulTypes.RelationFact(subject, relation, object, confidence,
                SoulTypes.RelationSource.SEEN, day, salience);
    }

    private static SoulTypes.SoulMind mindWith(List<SoulTypes.RelationFact> relations) {
        return SoulMindOps.withRelations(SoulTypes.SoulMind.empty(), relations);
    }

    private static SoulTypes.SoulEvent completed(String category) {
        return new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.TASK_COMPLETED,
                UUID.randomUUID(), List.of(), "overworld", "forest",
                Map.of("task", category + ":thing", "category", category),
                SoulTypes.Witness.SELF, 0L, Instant.EPOCH, SoulTypes.Salience.LOW);
    }

    // === normalise ===

    @Test
    void unknownRelationIsRejectedAndKnownOnesParseCaseInsensitively() {
        assertTrue(SoulRelationOps.normalise("Bob", "hates", "the nether", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).isEmpty());
        Optional<SoulTypes.RelationFact> ok = SoulRelationOps.normalise("Bob", "good_At", "mining", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE);
        assertTrue(ok.isPresent());
        assertEquals(SoulTypes.Relation.GOOD_AT, ok.get().relation());
    }

    @Test
    void subjectMustBeInTheSceneOrTheWorld() {
        assertTrue(SoulRelationOps.normalise("Stranger", "likes", "cake", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).isEmpty());
        assertTrue(SoulRelationOps.normalise("bob", "likes", "cake", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).isPresent(), "case-insensitive match");
        assertTrue(SoulRelationOps.normalise("the world", "fears", "the dark", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).isPresent());
        assertTrue(SoulRelationOps.normalise("Bob", "likes", "   ", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).isEmpty(), "blank object");
    }

    @Test
    void confidenceIsClampedAndQuantisedAndSalienceClamped() {
        assertEquals(0.4d, SoulRelationOps.normalise("Bob", "likes", "cake", 0.44d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).orElseThrow().confidence(), 1e-9);
        assertEquals(1.0d, SoulRelationOps.normalise("Bob", "likes", "cake", 1.7d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).orElseThrow().confidence(), 1e-9);
        assertEquals(0.0d, SoulRelationOps.normalise("Bob", "likes", "cake", -1d,
                SoulTypes.RelationSource.SAID, 3, 5, SCENE).orElseThrow().confidence(), 1e-9);
        assertEquals(10, SoulRelationOps.normalise("Bob", "likes", "cake", 0.5d,
                SoulTypes.RelationSource.SAID, 3, 99, SCENE).orElseThrow().salience());
        assertEquals(SoulRelationOps.MAX_NAME_CHARS, SoulRelationOps.normalise("Bob", "likes",
                "x".repeat(200), 0.5d, SoulTypes.RelationSource.SAID, 3, 5, SCENE)
                .orElseThrow().object().length());
    }

    // === merge ===

    @Test
    void identicalTripleStrengthensInPlace() {
        List<SoulTypes.RelationFact> held = List.of(fact("Bob", SoulTypes.Relation.LIKES, "Cake", 0.4d, 2, 5));
        List<SoulTypes.RelationFact> merged = SoulRelationOps.merge(held,
                fact("bob", SoulTypes.Relation.LIKES, "cake", 0.9d, 4, 3));
        assertEquals(1, merged.size());
        assertEquals(0.9d, merged.get(0).confidence(), 1e-9);
        assertEquals(5, merged.get(0).salience());
        assertEquals(4, merged.get(0).day());
    }

    @Test
    void singleValuedRelationReplacesOnlyWhenAtLeastAsConfident() {
        List<SoulTypes.RelationFact> held = List.of(fact("Bob", SoulTypes.Relation.GOOD_AT, "mining", 0.6d, 2, 5));
        List<SoulTypes.RelationFact> weaker = SoulRelationOps.merge(held,
                fact("Bob", SoulTypes.Relation.GOOD_AT, "fishing", 0.3d, 4, 6));
        assertEquals(held, weaker, "less confident contradiction is dropped");
        List<SoulTypes.RelationFact> stronger = SoulRelationOps.merge(held,
                fact("Bob", SoulTypes.Relation.GOOD_AT, "fishing", 0.6d, 4, 6));
        assertEquals(1, stronger.size());
        assertEquals("fishing", stronger.get(0).object());
    }

    @Test
    void likesAndDislikesAreOpposites() {
        List<SoulTypes.RelationFact> held = List.of(fact("Bob", SoulTypes.Relation.LIKES, "rain", 0.5d, 2, 5));
        List<SoulTypes.RelationFact> merged = SoulRelationOps.merge(held,
                fact("Bob", SoulTypes.Relation.DISLIKES, "rain", 0.5d, 3, 5));
        assertEquals(1, merged.size());
        assertEquals(SoulTypes.Relation.DISLIKES, merged.get(0).relation());
    }

    @Test
    void capEvictsLowestSalienceThenOldestDay() {
        List<SoulTypes.RelationFact> held = new ArrayList<>();
        for (int i = 0; i < SoulRelationOps.MAX_RELATIONS; i++) {
            // Two equally weak facts; the older one must be the eviction victim.
            int salience = i <= 1 ? 2 : 9;
            held.add(fact("Bob", SoulTypes.Relation.WANTS, "thing" + i, 0.5d, i == 0 ? 1 : 5, salience));
        }
        List<SoulTypes.RelationFact> merged = SoulRelationOps.merge(held,
                fact("Bob", SoulTypes.Relation.WANTS, "new", 0.5d, 9, 8));
        assertEquals(SoulRelationOps.MAX_RELATIONS, merged.size());
        assertFalse(merged.stream().anyMatch(f -> f.object().equals("thing0")), "oldest of the weakest");
        assertTrue(merged.stream().anyMatch(f -> f.object().equals("thing1")));
        assertTrue(merged.stream().anyMatch(f -> f.object().equals("new")));
    }

    // === lifecycle ===

    @Test
    void decayLosesAPointAndDropsAtZero() {
        List<SoulTypes.RelationFact> decayed = SoulRelationOps.decay(List.of(
                fact("Bob", SoulTypes.Relation.LIKES, "cake", 0.5d, 1, 1),
                fact("Bob", SoulTypes.Relation.LIKES, "rain", 0.5d, 1, 4)));
        assertEquals(1, decayed.size());
        assertEquals("rain", decayed.get(0).object());
        assertEquals(3, decayed.get(0).salience());
    }

    @Test
    void recallBumpsSalienceToTheCapAndMovesTheDay() {
        SoulTypes.RelationFact which = fact("Bob", SoulTypes.Relation.LIKES, "cake", 0.5d, 1, 9);
        List<SoulTypes.RelationFact> bumped = SoulRelationOps.noteRecalled(List.of(which), which, 7);
        assertEquals(10, bumped.get(0).salience(), "capped at MAX_SALIENCE");
        assertEquals(7, bumped.get(0).day());
        assertEquals(bumped, SoulRelationOps.noteRecalled(bumped, which, 7), "once per day");
    }

    // === SEEN producer ===

    @Test
    void fromJournalNeedsThreeCompletionsPerCategory() {
        assertTrue(SoulRelationOps.fromJournal(List.of(completed("skill"), completed("skill")), "Bob", 4).isEmpty());
        List<SoulTypes.RelationFact> one = SoulRelationOps.fromJournal(
                List.of(completed("skill"), completed("skill"), completed("skill")), "Bob", 4);
        assertEquals(1, one.size());
        assertEquals("Bob", one.get(0).subject());
        assertEquals(SoulTypes.Relation.GOOD_AT, one.get(0).relation());
        assertEquals("skill", one.get(0).object());
        assertEquals(0.4d, one.get(0).confidence(), 1e-9);
        assertEquals(SoulTypes.RelationSource.SEEN, one.get(0).source());
        assertEquals(4, one.get(0).day());
        assertEquals(6, one.get(0).salience());

        List<SoulTypes.SoulEvent> two = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            two.add(completed("skill"));
            two.add(completed("Hobby"));
        }
        two.add(completed("chore"));
        List<SoulTypes.RelationFact> both = SoulRelationOps.fromJournal(two, "Bob", 4);
        assertEquals(List.of("skill", "hobby"), both.stream().map(SoulTypes.RelationFact::object).toList());
    }

    // === rendering ===

    @Test
    void everyRelationRenders() {
        assertEquals("Bob likes cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.LIKES, "cake", 0.5d, 1, 5)));
        assertEquals("Bob dislikes cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.DISLIKES, "cake", 0.5d, 1, 5)));
        assertEquals("Bob fears cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.FEARS, "cake", 0.5d, 1, 5)));
        assertEquals("Bob is good at cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.GOOD_AT, "cake", 0.5d, 1, 5)));
        assertEquals("Bob is bad at cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.BAD_AT, "cake", 0.5d, 1, 5)));
        assertEquals("Bob promised cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.PROMISED, "cake", 0.5d, 1, 5)));
        assertEquals("Bob wants cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.WANTS, "cake", 0.5d, 1, 5)));
        assertEquals("Bob calls cake", SoulRelationOps.render(fact("Bob", SoulTypes.Relation.CALLS, "cake", 0.5d, 1, 5)));
    }

    @Test
    void beliefLinesDropWhatTheAboutBlockAlreadySays() {
        SoulTypes.SoulMind mind = mindWith(List.of(
                fact("Roti", SoulTypes.Relation.DISLIKES, "the nether", 0.9d, 3, 9),
                fact("Bob", SoulTypes.Relation.GOOD_AT, "mining", 0.4d, 3, 6)));
        List<String> lines = SoulRelationOps.beliefLines(mind, List.of("Roti dislikes the nether"));
        assertEquals(List.of("- Bob is good at mining"), lines);
    }

    @Test
    void beliefLinesAreBoundedByCountAndCharsWithNoPartialLine() {
        List<SoulTypes.RelationFact> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(fact("Bob", SoulTypes.Relation.WANTS, "thing" + i, 0.5d, 3, 9));
        }
        List<String> lines = SoulRelationOps.beliefLines(mindWith(many), List.of());
        assertEquals(SoulRelationOps.MAX_BELIEF_LINES, lines.size());

        List<SoulTypes.RelationFact> longOnes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            longOnes.add(fact("Bob", SoulTypes.Relation.WANTS, ("word" + i + " ").repeat(20).trim(), 0.5d, 3, 9));
        }
        List<String> bounded = SoulRelationOps.beliefLines(mindWith(longOnes), List.of());
        int total = 0;
        for (String line : bounded) {
            assertTrue(line.startsWith("- "), line);
            total += line.length() + 1;
        }
        assertTrue(total - 1 <= SoulRelationOps.MAX_BELIEFS_CHARS, "total was " + total);
        assertTrue(bounded.size() < 4, "the long lines did not all fit");
    }

    @Test
    void beliefLinesRankBySalienceThenConfidence() {
        List<String> lines = SoulRelationOps.beliefLines(mindWith(List.of(
                fact("Bob", SoulTypes.Relation.WANTS, "aa", 0.2d, 3, 4),
                fact("Bob", SoulTypes.Relation.WANTS, "bb", 0.9d, 3, 8),
                fact("Bob", SoulTypes.Relation.WANTS, "cc", 0.1d, 3, 8))), List.of());
        assertEquals(List.of("- Bob wants bb", "- Bob wants cc", "- Bob wants aa"), lines);
    }

    // === anchors ===

    @Test
    void anchorsRespectCooldownWeightCapAndTopicPrefix() {
        SoulTypes.SoulMind fresh = mindWith(List.of(fact("Bob", SoulTypes.Relation.GOOD_AT, "mining", 0.4d, 5, 6)));
        assertTrue(SoulRelationOps.anchors(fresh, "Bob", 6, new Random(1)).isEmpty(),
                "day 5 + 3 cooldown is not eligible on day 6");

        SoulTypes.SoulMind mind = mindWith(List.of(
                fact("Bob", SoulTypes.Relation.GOOD_AT, "mining", 0.4d, 1, 9),
                fact("Roti", SoulTypes.Relation.FEARS, "the dark", 0.6d, 1, 4),
                fact("Roti", SoulTypes.Relation.WANTS, "iron", 0.6d, 1, 3)));
        List<SoulBanterSeed.Anchor> anchors = SoulRelationOps.anchors(mind, "Bob", 9, new Random(1));
        assertEquals(SoulRelationOps.MAX_RELATION_ANCHORS, anchors.size());
        assertEquals(SoulRelationOps.RELATION_TOPIC_PREFIX + "GOOD_AT", anchors.get(0).topic());
        assertEquals(SoulRelationOps.RELATION_ANCHOR_WEIGHT, anchors.get(0).weight());
        assertEquals("I am good at mining", anchors.get(0).phrase(), "the bot speaks of itself first person");
        assertTrue(anchors.get(1).phrase().startsWith("Roti "), anchors.get(1).phrase());
    }

    // === consolidate integration ===

    @Test
    void consolidateFoldsSeenFactsOnlyWhenEnabledAndAlwaysDecays() {
        List<SoulTypes.SoulEvent> events = List.of(completed("skill"), completed("skill"), completed("skill"));
        SoulTypes.SoulMind off = SoulMindOps.consolidate(SoulTypes.SoulMind.empty(), events, 4, "forest",
                id -> "?", 1L, "Bob", false);
        assertTrue(off.relations().isEmpty());

        SoulTypes.SoulMind on = SoulMindOps.consolidate(SoulTypes.SoulMind.empty(), events, 4, "forest",
                id -> "?", 1L, "Bob", true);
        assertEquals(1, on.relations().size());
        assertEquals("skill", on.relations().get(0).object());
        assertEquals(SoulRelationOps.SEEN_SALIENCE - 1, on.relations().get(0).salience(),
                "the same consolidation that writes the fact also runs the day's decay");

        SoulTypes.SoulMind aging = SoulMindOps.withRelations(SoulTypes.SoulMind.empty(),
                List.of(fact("Bob", SoulTypes.Relation.LIKES, "cake", 0.5d, 1, 1)));
        assertTrue(SoulMindOps.consolidate(aging, List.of(), 4, "forest", id -> "?", 1L, "Bob", false)
                .relations().isEmpty(), "decay runs even with the toggle off");
    }

    // === toggle ===

    @Test
    void relationsToggleIsOffByDefault() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertFalse(constructor.newInstance().isSoulRelationsEnabled());
    }
}
