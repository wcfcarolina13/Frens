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
            assertTrue(seed.text().startsWith(SoulBanterSeed.PRIMARY_PREFIX + "talk about "), seed.text());
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
            assertTrue(seed.text().startsWith(SoulBanterSeed.PRIMARY_PREFIX
                    + seed.act().directive(true, "Bradley") + " "), seed.text());
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

    // === Conversation ontology Phase 2: mind anchors join the pool ===

    @Test
    void mindAnchorsJoinThePoolAndMemoryTopicsCountAsEvents() {
        assertTrue(SoulBanterSeed.isEventTopic("memory:fighting"));
        assertTrue(SoulBanterSeed.isEventTopic("unanswered question"));
        assertFalse(SoulBanterSeed.isEventTopic("memory"));
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(grounding()), List.of(List.of()), "Roti", "",
                new Random(3), Set.of(), List.of(), List.of(),
                List.of(new SoulBanterSeed.Anchor("unanswered question", "Bob never got an answer about \"iron?\"", 5)));
        assertTrue(seed.text().contains("never got an answer"), seed.text());
    }

    @Test
    void memoryAnchorsMakeRecallEligibleAndLeadOftenOverGrounding() {
        List<SoulBanterSeed.Anchor> memories = List.of(
                new SoulBanterSeed.Anchor("memory:fighting", "remember when a zombie got the drop on Jake on day 2", 4));
        int memoryLed = 0;
        int recalls = 0;
        for (int i = 0; i < 100; i++) {
            SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                    "Bradley", "", new Random(i), Set.of(), List.of(), List.of(), memories);
            if (seed.topic().equals("memory:fighting")) {
                memoryLed++;
            }
            if (seed.act() == SoulSpeechAct.RECALL) {
                recalls++;
                // richGrounding carries a "hobbies" anchor, itself RECALL material — the guarantee
                // is that RECALL lands on event-or-memory material, not on the memory specifically.
                assertTrue(SoulBanterSeed.isEventTopic(seed.topic()), seed.text());
            }
        }
        assertTrue(memoryLed >= 15, "a memory should lead fairly often: " + memoryLed + "/100");
        assertTrue(recalls > 0, "RECALL must be eligible with only a memory anchor");
    }

    @Test
    void eightArgOverloadIgnoresTheMind() {
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(grounding()), List.of(List.of()), "Roti", "",
                new Random(3), Set.of(), List.of(), List.of());
        assertFalse(seed.text().contains("never got an answer"), seed.text());
    }

    // === 1.1.223 conversation smoothing: one subject, framed background, rotating support ===

    @Test
    void theSeedNamesOneSubjectAndFramesEverythingElseAsBackground() {
        List<SoulBanterSeed.Anchor> changes = List.of(
                new SoulBanterSeed.Anchor("the weather", "the rain just stopped", 10_000));
        Set<String> recent = new java.util.LinkedHashSet<>(List.of("food", "animals"));
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                "Bradley", "", new Random(1), recent, changes, null);
        String text = seed.text();
        assertEquals("the weather", seed.topic());
        assertTrue(text.startsWith("Stay on one subject: talk about the rain just stopped. "), text);
        assertEquals(2, seed.supportTopics().size(), text);
        assertTrue(text.contains(SoulBanterSeed.BACKGROUND_PREFIX), text);
        assertTrue(text.contains("(setting: it is morning, clear, in taiga)"), text);
        assertTrue(text.contains("(do not bring up food or animals again)"), text);
        // Clause order: subject, background, setting, avoid — and no trailing period (the
        // assembler appends ". A few short lines…").
        assertTrue(text.indexOf(SoulBanterSeed.BACKGROUND_PREFIX) < text.indexOf("(setting: "), text);
        assertTrue(text.indexOf("(setting: ") < text.indexOf("(do not bring up"), text);
        assertTrue(text.endsWith(")"), text);
        assertFalse(text.contains("; it is"), "the old flat list is gone: " + text);
    }

    @Test
    void aSeedWithOnlyASubjectHasNoTrailingPeriod() {
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(), List.of(List.of()),
                "Bradley", "", new Random(1), Set.of(),
                List.of(new SoulBanterSeed.Anchor("the weather", "the rain just stopped", 9)), null);
        assertEquals("Stay on one subject: talk about the rain just stopped", seed.text());
        assertTrue(seed.supportTopics().isEmpty());
    }

    @Test
    void supportTopicsRotateOutOfTheNextSeed() {
        // 2026-09-25 field: "the last hobby (woodcut)" rode every seed because only the PRIMARY
        // was remembered. The director feeds each seed's support topics back as recentSupport.
        java.util.ArrayDeque<String> supportRing = new java.util.ArrayDeque<>();
        List<SoulBanterSeed.Anchor> changes = List.of(
                new SoulBanterSeed.Anchor("the weather", "the rain just stopped", 10_000));
        SoulBanterSeed.Seed first = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                "Bradley", "", new Random(2), Set.of(), changes, null, List.of(), Set.copyOf(supportRing));
        assertEquals(2, first.supportTopics().size(), first.text());
        SoulBanterSeed.rememberRecent(supportRing, first.supportTopics(), SoulBanterSeed.RECENT_SUPPORT_MEMORY);

        SoulBanterSeed.Seed second = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                "Bradley", "", new Random(2), Set.of(), changes, null, List.of(), Set.copyOf(supportRing));
        assertFalse(second.supportTopics().isEmpty(), "other background exists: " + second.text());
        for (String topic : first.supportTopics()) {
            assertFalse(second.supportTopics().contains(topic),
                    "support topic " + topic + " repeated in consecutive seeds: " + second.text());
        }
    }

    @Test
    void highSalienceSupportIsNeverRotatedOut() {
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.DEATH, SoulTypes.Salience.HIGH, 100, Map.of()));
        List<SoulBanterSeed.Anchor> changes = List.of(
                new SoulBanterSeed.Anchor("the weather", "the rain just stopped", 10_000));
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(events),
                "Bradley", "", new Random(2), Set.of(), changes, null, List.of(), Set.of("dying"));
        assertTrue(seed.supportTopics().contains("dying"), seed.text());
    }

    @Test
    void recentSupportNeverChangesThePrimaryPickOrTheAvoidList() {
        for (int i = 0; i < 50; i++) {
            SoulBanterSeed.Seed plain = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                    "Bradley", "", new Random(i), Set.of("food"), List.of(), List.of(), List.of(), Set.of());
            SoulBanterSeed.Seed rotated = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                    "Bradley", "", new Random(i), Set.of("food"), List.of(), List.of(), List.of(),
                    Set.of("hobbies", "loot", "gear", "animals"));
            assertEquals(plain.topic(), rotated.topic());
            assertEquals(plain.act(), rotated.act());
        }
    }

    @Test
    void machineTopicKeysNeverReachTheAvoidList() {
        Set<String> recent = new java.util.LinkedHashSet<>(
                List.of("memory:fighting", "relation:LIKES|Bob|Jake", "food"));
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                "Bradley", "", new Random(5), recent);
        assertFalse(seed.text().contains("memory:"), seed.text());
        assertFalse(seed.text().contains("relation:"), seed.text());
        if (!seed.topic().equals("food")) {
            assertTrue(seed.text().contains("(do not bring up food again)"), seed.text());
        }
        assertTrue(SoulBanterSeed.isReadableTopic("unanswered question"));
        assertFalse(SoulBanterSeed.isReadableTopic("memory:said:wall"));
    }

    @Test
    void anOverBudgetSeedDropsWholeClausesAndNeverEndsInsideOne() {
        String longPhrase = "x".repeat(SoulBanterSeed.MAX_PHRASE_CHARS);
        List<SoulBanterSeed.Anchor> anchors = List.of(
                new SoulBanterSeed.Anchor("a", longPhrase, 10_000),
                new SoulBanterSeed.Anchor("b", "b" + longPhrase, 50),
                new SoulBanterSeed.Anchor("c", "c" + longPhrase, 50));
        Set<String> recent = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            recent.add("recent topic number " + i);
        }
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(richGrounding()), List.of(List.of()),
                "Bradley", "", new Random(1), recent, anchors, null);
        String text = seed.text();
        assertTrue(text.length() <= SoulBanterSeed.MAX_SEED_CHARS, "len=" + text.length());
        assertTrue(text.startsWith(SoulBanterSeed.PRIMARY_PREFIX), text);
        int opens = text.length() - text.replace("(", "").length();
        int closes = text.length() - text.replace(")", "").length();
        assertEquals(opens, closes, "unbalanced clause: " + text);
        // Support clauses go first; a support cut for budget is not reported as used, so it is
        // not rotated out of the next seed for nothing.
        assertEquals("a", seed.topic());
        assertTrue(seed.supportTopics().isEmpty(), seed.supportTopics().toString());
        assertFalse(text.contains(SoulBanterSeed.BACKGROUND_PREFIX), text);
    }

    @Test
    void rememberRecentKeepsDistinctNewestTopicsWithinTheCap() {
        java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
        SoulBanterSeed.rememberRecent(ring, List.of("hobbies", "loot"), 4);
        SoulBanterSeed.rememberRecent(ring, List.of("gear", "hobbies"), 4);
        assertEquals(List.of("loot", "gear", "hobbies"), List.copyOf(ring));
        SoulBanterSeed.rememberRecent(ring, List.of("animals", "mood", ""), 4);
        assertEquals(List.of("gear", "hobbies", "animals", "mood"), List.copyOf(ring));
        SoulBanterSeed.rememberRecent(ring, null, 4);
        assertEquals(4, ring.size());
    }
}
