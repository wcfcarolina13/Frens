package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.services.TaskService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.BOT_DAMAGE;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.COMBAT_ENDED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.COMBAT_STARTED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.DIMENSION_CHANGED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.HOBBY_SESSION;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.HUNT_PROGRESS;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.MOB_KILLED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.OWNER_DAMAGE;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.QUEST_STAGE_CHANGED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.SELF_RESCUE;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.SLEEP;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.TASK_CANCELLED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.TASK_COMPLETED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.TASK_FAILED;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.EventType.WAKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SoulEventObserver}'s data-only instance seam ({@code observe},
 * {@code noteBotDamage}, {@code noteOwnerDamage}, {@code tickCombat}) and the package-private
 * static {@code taskOutcome} mapping -- none of which ever touch a Minecraft type, so this runs
 * without a Minecraft server the same way every other soul-communication unit test does.
 */
class SoulEventObserverTest {

    @Test
    void damageStartsCombatOnceAndCooldownEndsItOnce() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteBotDamage(bot, "minecraft:overworld", "plains", 3.0F, "zombie", 10L);
        observer.noteBotDamage(bot, "minecraft:overworld", "plains", 2.0F, "zombie", 20L);
        observer.tickCombat(bot, "minecraft:overworld", "plains", 109L);
        observer.tickCombat(bot, "minecraft:overworld", "plains", 120L);

        assertEquals(List.of(BOT_DAMAGE, COMBAT_STARTED, BOT_DAMAGE, COMBAT_ENDED),
                sink.events().stream().map(SoulTypes.SoulEvent::type).toList());
    }

    @Test
    void firstObservationSeedsWithoutEmittingTransitions() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.observe(new SoulEventObserver.Observation(bot, "minecraft:overworld", "plains",
                false, "", 0L, 0, Instant.now()));

        assertTrue(sink.events().isEmpty());
    }

    @Test
    void dayRolloverNotifiesTheSinkOncePerDay() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        // First observation seeds; same-day repeats are silent; only the 4 -> 5 edge fires.
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 96_000L, 4));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 96_100L, 4));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 120_000L, 5));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 120_100L, 5));

        assertEquals(List.of(5), sink.newDays);
        assertTrue(sink.events().isEmpty(), "a day rollover is a sink callback, not a journal event");
    }

    @Test
    void dayRolloverNeverFiresForARejectedBot() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 0L, 4));
        sink.setAccepts(false);
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 24_000L, 5));

        assertTrue(sink.newDays.isEmpty());
    }

    @Test
    void sleepWakeEdgesEmitOnce() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 0L));
        // Same sleeping state repeated -- must not re-emit.
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 20L));
        observer.observe(observation(bot, "minecraft:overworld", "plains", true, "", 40L));
        // Still asleep -- must not re-emit SLEEP again.
        observer.observe(observation(bot, "minecraft:overworld", "plains", true, "", 60L));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 80L));

        assertEquals(List.of(SLEEP, WAKE),
                sink.events().stream().map(SoulTypes.SoulEvent::type).toList());
    }

    @Test
    void dimensionChangeEmitsOnTransition() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 0L));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "", 20L));
        observer.observe(observation(bot, "minecraft:the_nether", "nether_wastes", false, "", 40L));

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(DIMENSION_CHANGED),
                events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals("minecraft:overworld", events.get(0).facts().get("from"));
        assertEquals("minecraft:the_nether", events.get(0).facts().get("to"));
    }

    @Test
    void questStageChangeEmitsOnTransition() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "quest_a:0", 0L));
        observer.observe(observation(bot, "minecraft:overworld", "plains", false, "quest_a:1", 20L));

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(QUEST_STAGE_CHANGED),
                events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals("quest_a:0", events.get(0).facts().get("from"));
        assertEquals("quest_a:1", events.get(0).facts().get("to"));
    }

    @Test
    void ownerDamageOnlyEmitsWhenWitnessed() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        observer.noteOwnerDamage(bot, owner, false, "minecraft:overworld", "plains", 4.0F, "spider", 10L);
        assertTrue(sink.events().isEmpty());

        observer.noteOwnerDamage(bot, owner, true, "minecraft:overworld", "plains", 4.0F, "spider", 10L);
        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(OWNER_DAMAGE), events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals(List.of(owner), events.get(0).participants());
        assertEquals(SoulTypes.Witness.LOCAL, events.get(0).witness());
    }

    @Test
    void disabledOrUnboundSinkIsANoOp() {
        CapturingSink sink = new CapturingSink();
        sink.setAccepts(false);
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteBotDamage(bot, "minecraft:overworld", "plains", 3.0F, "zombie", 10L);
        observer.tickCombat(bot, "minecraft:overworld", "plains", 500L);
        observer.noteOwnerDamage(bot, UUID.randomUUID(), true, "minecraft:overworld", "plains", 1.0F, "spider", 10L);
        observer.observe(observation(bot, "minecraft:overworld", "plains", true, "quest:1", 0L));
        observer.observe(observation(bot, "minecraft:the_end", "the_end", false, "quest:2", 20L));

        assertTrue(sink.events().isEmpty());
    }

    /**
     * Regression test for the production {@link SoulEventObserver.EventSink} journaling events
     * after the master soul switch is turned off: {@code acceptsEvent} must require BOTH the
     * master switch and an active profile, not just the profile.
     */
    @Test
    void acceptsEventRequiresBothTheMasterSwitchAndAnActiveProfile() {
        assertTrue(SoulEventObserver.acceptsEvent(true, true));
        assertFalse(SoulEventObserver.acceptsEvent(false, true));
        assertFalse(SoulEventObserver.acceptsEvent(true, false));
        assertFalse(SoulEventObserver.acceptsEvent(false, false));
    }

    @Test
    void taskOutcomeMapsSuccessCancelAndFailure() {
        assertEquals(TASK_COMPLETED, SoulEventObserver.taskOutcome(TaskService.State.COMPLETED, false));
        assertEquals(TASK_COMPLETED, SoulEventObserver.taskOutcome(TaskService.State.COMPLETED, true));
        assertEquals(TASK_CANCELLED, SoulEventObserver.taskOutcome(TaskService.State.ABORTED, true));
        assertEquals(TASK_FAILED, SoulEventObserver.taskOutcome(TaskService.State.ABORTED, false));
    }

    @Test
    void mobKilledEmitsNormalSalienceWithMobFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteMobKilled(bot, "minecraft:overworld", "plains", "zombie", 10L);

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(MOB_KILLED), events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals(SoulTypes.Salience.NORMAL, events.get(0).salience());
        assertEquals("zombie", events.get(0).facts().get("mob"));
    }

    @Test
    void mobKilledNormalizesNullMobTypeToEmptyStringNeverANullFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteMobKilled(bot, "minecraft:overworld", "plains", null, 10L);

        Map<String, String> facts = sink.events().get(0).facts();
        assertEquals("", facts.get("mob"));
        assertFalse(facts.values().stream().anyMatch(Objects::isNull));
    }

    @Test
    void selfRescueEmitsHighSalienceWithKindFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteSelfRescue(bot, "minecraft:overworld", "plains", "powder_snow", 10L);

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(SELF_RESCUE), events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals(SoulTypes.Salience.HIGH, events.get(0).salience());
        assertEquals("powder_snow", events.get(0).facts().get("kind"));
    }

    @Test
    void selfRescueNormalizesNullKindToEmptyStringNeverANullFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteSelfRescue(bot, "minecraft:overworld", "plains", null, 10L);

        Map<String, String> facts = sink.events().get(0).facts();
        assertEquals("", facts.get("kind"));
        assertFalse(facts.values().stream().anyMatch(Objects::isNull));
    }

    @Test
    void hobbySessionEmitsLowSalienceWithHobbyFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteHobbySession(bot, "fishing");

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(HOBBY_SESSION), events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals(SoulTypes.Salience.LOW, events.get(0).salience());
        assertEquals("fishing", events.get(0).facts().get("hobby"));
    }

    @Test
    void hobbySessionNormalizesNullHobbyNameToEmptyStringNeverANullFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteHobbySession(bot, null);

        Map<String, String> facts = sink.events().get(0).facts();
        assertEquals("", facts.get("hobby"));
        assertFalse(facts.values().stream().anyMatch(Objects::isNull));
    }

    @Test
    void huntProgressEmitsNormalSalienceWithTargetKillsGoalFacts() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteHuntProgress(bot, "zombie", 3, 10);

        List<SoulTypes.SoulEvent> events = sink.events();
        assertEquals(List.of(HUNT_PROGRESS), events.stream().map(SoulTypes.SoulEvent::type).toList());
        assertEquals(SoulTypes.Salience.NORMAL, events.get(0).salience());
        Map<String, String> facts = events.get(0).facts();
        assertEquals("zombie", facts.get("target"));
        assertEquals("3", facts.get("kills"));
        assertEquals("10", facts.get("goal"));
    }

    @Test
    void huntProgressNormalizesNullTargetToEmptyStringNeverANullFact() {
        CapturingSink sink = new CapturingSink();
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteHuntProgress(bot, null, 0, 5);

        Map<String, String> facts = sink.events().get(0).facts();
        assertEquals("", facts.get("target"));
        assertFalse(facts.values().stream().anyMatch(Objects::isNull));
    }

    @Test
    void newSituationalEventTypesAreNoOpWhenSinkRejects() {
        CapturingSink sink = new CapturingSink();
        sink.setAccepts(false);
        SoulEventObserver observer = new SoulEventObserver(sink, 100L);
        UUID bot = UUID.randomUUID();

        observer.noteMobKilled(bot, "minecraft:overworld", "plains", "zombie", 10L);
        observer.noteSelfRescue(bot, "minecraft:overworld", "plains", "powder_snow", 10L);
        observer.noteHobbySession(bot, "fishing");
        observer.noteHuntProgress(bot, "zombie", 3, 10);

        assertTrue(sink.events().isEmpty());
    }

    private static SoulEventObserver.Observation observation(UUID bot, String dimension, String biome,
            boolean sleeping, String questSignature, long worldTick) {
        return observation(bot, dimension, biome, sleeping, questSignature, worldTick, 0);
    }

    private static SoulEventObserver.Observation observation(UUID bot, String dimension, String biome,
            boolean sleeping, String questSignature, long worldTick, int day) {
        return new SoulEventObserver.Observation(bot, dimension, biome, sleeping, questSignature,
                worldTick, day, Instant.now());
    }

    private static final class CapturingSink implements SoulEventObserver.EventSink {
        private final List<SoulTypes.SoulEvent> events = new ArrayList<>();
        private final List<Integer> newDays = new ArrayList<>();
        private boolean accepts = true;

        void setAccepts(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public boolean accepts(UUID botId) {
            return accepts;
        }

        @Override
        public void append(UUID botId, SoulTypes.SoulEvent event) {
            events.add(event);
        }

        @Override
        public void onNewDay(UUID botId, int day, String biome) {
            newDays.add(day);
        }

        List<SoulTypes.SoulEvent> events() {
            return List.copyOf(events);
        }
    }
}
