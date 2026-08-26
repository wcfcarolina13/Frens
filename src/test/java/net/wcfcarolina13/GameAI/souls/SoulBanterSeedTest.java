package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
}
