package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic banter seed: salience-first event pick with type dedupe,
 * DIRECT_CONVERSATION excluded, bounded length, situation + player-activity lines.
 */
class SoulBanterSeedTest {

    private static SoulTypes.SoulEvent event(SoulTypes.EventType type, SoulTypes.Salience salience,
                                              long tick, Map<String, String> facts) {
        return new SoulTypes.SoulEvent(UUID.randomUUID(), type, UUID.randomUUID(), List.of(),
                "overworld", "plains", facts, SoulTypes.Witness.SELF, tick,
                Instant.ofEpochMilli(tick), salience);
    }

    private static SoulTypes.GroundingSnapshot grounding() {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(UUID.randomUUID(), "Jake",
                "overworld", "plains", 0, 64, 0, true, "dusk", "rain", 20f, 20f, 18, 4, "", 4, 36,
                List.of(), "content", "FOLLOW", "", "", "", "Bradley", true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), Instant.EPOCH);
    }

    private static SoulTypes.GroundingSnapshot groundingWithOverheard(List<String> overheard) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(UUID.randomUUID(), "Jake",
                "overworld", "plains", 0, 64, 0, true, "dusk", "rain", 20f, 20f, 18, 4, "", 4, 36,
                List.of(), "content", "FOLLOW", "", "", "", "Bradley", true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), SoulTypes.SituationSnapshot.empty(), Instant.EPOCH, overheard);
    }

    @Test
    void highSalienceEventsWinOverNewerNormalOnes() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.DEATH, SoulTypes.Salience.HIGH, 100, Map.of()),
                event(SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.NORMAL, 900, Map.of()),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 800, Map.of()),
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.NORMAL, 700, Map.of()));
        String seed = SoulBanterSeed.build(List.of(grounding()), List.of(events),
                "Bradley", "", new Random(42));
        assertTrue(seed.contains("died"), "HIGH-salience death should be picked: " + seed);
    }

    @Test
    void directConversationEventsNeverSeed() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.DIRECT_CONVERSATION, SoulTypes.Salience.HIGH, 900, Map.of()));
        String seed = SoulBanterSeed.build(List.of(grounding()), List.of(events),
                "Bradley", "", new Random(42));
        assertFalse(seed.toLowerCase().contains("conversation"), seed);
    }

    @Test
    void duplicateEventTypesCollapse() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 900, Map.of()),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 800, Map.of()),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 700, Map.of()));
        String seed = SoulBanterSeed.build(List.of(grounding()), List.of(events),
                "Bradley", "", new Random(42));
        int first = seed.indexOf("slew");
        assertTrue(first >= 0, seed);
        assertEquals(-1, seed.indexOf("slew", first + 1), "mob-kill phrase should appear once: " + seed);
    }

    @Test
    void deterministicGivenAFixedRandom() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 900, Map.of()),
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.NORMAL, 800, Map.of()),
                event(SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.NORMAL, 700, Map.of()),
                event(SoulTypes.EventType.RESPAWN, SoulTypes.Salience.NORMAL, 600, Map.of()),
                event(SoulTypes.EventType.DIMENSION_CHANGED, SoulTypes.Salience.NORMAL, 500, Map.of()));
        String a = SoulBanterSeed.build(List.of(grounding()), List.of(events), "Bradley", "", new Random(7));
        String b = SoulBanterSeed.build(List.of(grounding()), List.of(events), "Bradley", "", new Random(7));
        assertEquals(a, b);
    }

    @Test
    void emptyEventsStillYieldASituationSeed() {
        String seed = SoulBanterSeed.build(List.of(grounding()), List.of(List.of()),
                "Bradley", "", new Random(1));
        assertTrue(seed.contains("dusk"), seed);
        assertTrue(seed.contains("rain"), seed);
        assertTrue(seed.contains("plains"), seed);
    }

    @Test
    void playerActivityLineOnlyWhenKnown() {
        String with = SoulBanterSeed.build(List.of(grounding()), List.of(List.of()),
                "Bradley", "mining, broke stone 4s ago", new Random(1));
        assertTrue(with.contains("Bradley is mining"), with);
        String without = SoulBanterSeed.build(List.of(grounding()), List.of(List.of()),
                "Bradley", "", new Random(1));
        assertFalse(without.contains("Bradley is"), without);
    }

    @Test
    void seedIsBoundedEvenWithOversizedFacts() {
        String huge = "x".repeat(500);
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.HIGH, 900, Map.of("task", huge)),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.HIGH, 800, Map.of("mob", huge)),
                event(SoulTypes.EventType.DEATH, SoulTypes.Salience.HIGH, 700, Map.of("cause", huge)));
        String seed = SoulBanterSeed.build(List.of(grounding()), List.of(events),
                "Bradley", "", new Random(3));
        assertTrue(seed.length() <= SoulBanterSeed.MAX_SEED_CHARS, "len=" + seed.length());
    }

    @Test
    void seedPicksUpAnOverheardFragmentFromGrounding() {
        String seed = SoulBanterSeed.build(
                List.of(groundingWithOverheard(List.of("we should check the ravine"))),
                List.of(List.of()), "Bradley", "", new Random(1));

        assertTrue(seed.contains("ravine"), "seed was: " + seed);
    }

    @Test
    void seedIsByteIdenticalWithAndWithoutAnEmptyOverheardList() {
        // Stronger than asserting the word "overheard" is absent: an empty list must produce
        // exactly the seed the pre-feature builder produced, character for character.
        String withEmpty = SoulBanterSeed.build(
                List.of(groundingWithOverheard(List.of())),
                List.of(List.of()), "Bradley", "mining", new Random(4));
        String plain = SoulBanterSeed.build(
                List.of(grounding()),
                List.of(List.of()), "Bradley", "mining", new Random(4));
        assertEquals(plain, withEmpty);
    }

    @Test
    void seedWithoutOverheardLinesIsUnchanged() {
        String seed = SoulBanterSeed.build(
                List.of(groundingWithOverheard(List.of())),
                List.of(List.of()), "Bradley", "", new Random(1));

        assertFalse(seed.contains("overheard"));
    }
    // === Topic rotation (2026-08-29): the bots kept talking about naps next to a bed ===

    private static SoulTypes.GroundingSnapshot richGrounding() {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(UUID.randomUUID(), "Jake",
                "overworld", "taiga", 0, 64, 0, true, "morning", "clear", 11f, 20f, 8, 4, "iron axe", 4, 36,
                List.of("oak logs x40"), "cheerful", "IDLE", "", "", "", "Bradley", true, 0, true, Optional.empty());
        SoulTypes.SituationSnapshot base = SoulTypes.SituationSnapshot.empty();
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(base.dangerDistance(), base.hostiles(),
                List.of("wolves", "sheep"), "grass block", List.of("spruce logs", "moss"), List.of("Bed nearby: sleep"),
                base.facilitySightings(), base.armorStands(), base.blockLight(), base.skyLight(), base.enclosed(),
                base.hasHeadroom(), base.hasEscapeRoute(), base.behaviorMode(), base.following(), base.inCombat(),
                base.postCombatLinger(), base.recentKillCount(), base.inShelter(), base.surfaceRecoveryActive(),
                base.breakingFree(), base.nightTravelActive(), 3, 1, base.mount(), base.knownBaseCount(),
                base.lastSleepLabel(), base.atBase(), base.hunt(), Optional.of("fishing"));
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), situation, Instant.EPOCH, List.of());
    }

    @Test
    void sleepAndWakeCollapseToOneTopicAndNeverOutrankTheWorld() {
        assertEquals(SoulBanterSeed.topicOf(SoulTypes.EventType.SLEEP), SoulBanterSeed.topicOf(SoulTypes.EventType.WAKE));
        List<SoulTypes.SoulEvent> naps = List.of(
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.LOW, 900, Map.of()),
                event(SoulTypes.EventType.WAKE, SoulTypes.Salience.LOW, 950, Map.of()));
        int sleepPrimary = 0;
        for (int i = 0; i < 200; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(naps),
                    "Bradley", "", new Random(i), Set.of());
            if (seed.topic().equals("sleep")) {
                sleepPrimary++;
            }
        }
        assertTrue(sleepPrimary < 40, "sleep was primary " + sleepPrimary + "/200 — it should be a rare pick");
    }

    @Test
    void recentTopicsAreSkippedWhileAnythingElseExists() {
        List<SoulTypes.SoulEvent> naps = List.of(
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.LOW, 900, Map.of()));
        Set<String> recent = new java.util.HashSet<>(Set.of("sleep", "the weather", "the land"));
        for (int i = 0; i < 100; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(naps),
                    "Bradley", "", new Random(i), recent);
            assertFalse(recent.contains(seed.topic()), "picked a recent topic: " + seed.topic());
            assertTrue(seed.text().startsWith("talk about "), seed.text());
            assertTrue(seed.text().contains("do not bring up"), seed.text());
        }
    }

    @Test
    void groundingOffersManyAnchorsBeyondEvents() {
        List<SoulBanterSeed.Anchor> anchors = SoulBanterSeed.groundingAnchors(richGrounding());
        Set<String> topics = new java.util.HashSet<>();
        for (SoulBanterSeed.Anchor a : anchors) {
            topics.add(a.topic());
        }
        assertTrue(topics.containsAll(Set.of("the weather", "the land", "gear", "food", "health", "mood",
                "animals", "terrain", "facilities", "loot", "hobbies", "the journey", "deaths")), topics.toString());
    }

    @Test
    void highSalienceEventsStillSurfaceAsSupportWhenNotPrimary() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.DEATH, SoulTypes.Salience.HIGH, 100, Map.of()));
        for (int i = 0; i < 50; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(events),
                    "Bradley", "", new Random(i), Set.of());
            assertTrue(seed.text().contains("died"), seed.text());
        }
    }
    @Test
    void changeAnchorsLeadAndTheActOpensTheCue() {
        List<SoulBanterSeed.Anchor> changes = List.of(
                new SoulBanterSeed.Anchor("the weather", "the rain just stopped", SoulSceneDiff.CHANGE_WEIGHT));
        int changeLed = 0;
        for (int i = 0; i < 100; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                    "Bradley", "", new Random(i), Set.of(), changes, List.of(SoulSpeechAct.OBSERVE));
            assertTrue(seed.act() != null && seed.act() != SoulSpeechAct.OBSERVE, "act rotates: " + seed.act());
            assertTrue(seed.text().startsWith(seed.act().directive(true, "Bradley") + " "), seed.text());
            if (seed.topic().equals("the weather")) {
                changeLed++;
            }
        }
        assertTrue(changeLed >= 20, "a change should lead often: " + changeLed + "/100");
    }

    @Test
    void recallActRepicksAnEventAnchorAsPrimary() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 900, Map.of("mob", "zombie")));
        List<SoulSpeechAct> recentAllButRecall = List.of(SoulSpeechAct.OBSERVE, SoulSpeechAct.ASK,
                SoulSpeechAct.TEASE, SoulSpeechAct.PLAN);
        int recalls = 0;
        for (int i = 0; i < 100; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(events),
                    "Bradley", "", new Random(i), Set.of(), List.of(), recentAllButRecall);
            if (seed.act() == SoulSpeechAct.RECALL) {
                recalls++;
                // "fighting" (the zombie) or "hobbies" (the grounding's last hobby) — both recallable.
                assertTrue(SoulBanterSeed.isEventTopic(seed.topic()), seed.text());
            }
        }
        assertTrue(recalls > 0, "RECALL must be reachable when an event exists");
    }

    @Test
    void eventPhrasesUseHumanTaskNamesNotRawFactValues() {
        // 1.1.196 field bug: "(skill:sleep, skill)" leaked into the seed and the model invented a
        // game mechanic called "the sleep skill" that both bots then talked about for an hour.
        assertEquals("started woodcutting", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.TASK_STARTED, SoulTypes.Salience.NORMAL, 1,
                Map.of("task", "skill:woodcut", "category", "skill"))));
        assertEquals("finished sleeping", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.LOW, 1,
                Map.of("task", "skill:sleep", "category", "skill", "state", "TASK_COMPLETED", "reason", ""))));
        assertEquals("botched mining (no tool)", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.TASK_FAILED, SoulTypes.Salience.NORMAL, 1,
                Map.of("task", "skill:mine", "category", "skill", "reason", "NO_TOOL"))));
        assertEquals("slew a zombie", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, 1, Map.of("mob", "Zombie"))));
        assertEquals("took a beating from a skeleton", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, 1,
                Map.of("amount", "4.0", "source", "skeleton"))));
        assertEquals("took a beating", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, 1, Map.of("amount", "4.0", "source", ""))));
        assertEquals("started a task", SoulBanterSeed.phraseFor(event(
                SoulTypes.EventType.TASK_STARTED, SoulTypes.Salience.NORMAL, 1, Map.of())));
    }

    @Test
    void sleepTasksRotateUnderTheSleepTopicNotTheWork() {
        assertEquals("sleep", SoulBanterSeed.topicOf(event(SoulTypes.EventType.TASK_STARTED,
                SoulTypes.Salience.LOW, 1, Map.of("task", "skill:sleep", "category", "skill"))));
        assertEquals("the work", SoulBanterSeed.topicOf(event(SoulTypes.EventType.TASK_STARTED,
                SoulTypes.Salience.LOW, 1, Map.of("task", "skill:woodcut", "category", "skill"))));
        assertEquals(1, SoulBanterSeed.weightOf(event(SoulTypes.EventType.TASK_COMPLETED,
                SoulTypes.Salience.NORMAL, 1, Map.of("task", "skill:sleep", "category", "skill"))));
    }
}
