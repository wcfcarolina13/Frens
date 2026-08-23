package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulPromptAssemblerTest {

    private final SoulPromptAssembler assembler = new SoulPromptAssembler();

    private final SoulTypes.SoulProfile profile = new SoulTypes.SoulProfile(
            "frens:jake", "Jake",
            List.of("Pragmatic field engineer."),
            List.of("Preparation and honesty."),
            List.of("Never invent actions."),
            List.of(new SoulTypes.Message(SoulTypes.Role.ASSISTANT, "Check the supplies first.")));
    private final SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
            "minecraft:overworld", "plains", 0, 64, 0, true,
            "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
            8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
            "Workshop", "Player", true, 2, false, java.util.Optional.empty());
    private final SoulTypes.PlayerSnapshot localPlayer = new SoulTypes.PlayerSnapshot(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Player",
            6, "north", 20.0F, 20.0F, 20, "playerBiomeSecret", false);
    private final SoulTypes.GroundingSnapshot grounding = new SoulTypes.GroundingSnapshot(
            SoulTypes.Reachability.LOCAL, bot, java.util.Optional.of(localPlayer), Instant.EPOCH);
    private final SoulTypes.GroundingSnapshot remoteGrounding = new SoulTypes.GroundingSnapshot(
            SoulTypes.Reachability.REMOTE, bot, java.util.Optional.empty(), Instant.EPOCH);
    private final List<SoulTypes.ConversationRecord> priorHistory = List.of(
            new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
                    SoulTypes.TurnKind.HEARD, "Are we stocked?", Instant.EPOCH,
                    "", "", null, null),
            new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
                    SoulTypes.TurnKind.SPOKEN, "Timber's fine. Food isn't.", Instant.EPOCH,
                    "ollama", "local-model", 25L, null));
    private final List<SoulTypes.SoulEvent> recentEvents = List.of(
            new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.TASK_COMPLETED,
                    bot.botId(), List.of(localPlayer.playerId()), "minecraft:overworld", "plains",
                    Map.of("task", "woodcut"), SoulTypes.Witness.SELF,
                    100L, Instant.EPOCH, SoulTypes.Salience.NORMAL));

    // === Mandated tests (verbatim from brief) ===

    @Test
    void presentMomentImmediatelyPrecedesCurrentPlayerMessage() {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents, "Can you see this village?", Duration.ofSeconds(60));

        List<SoulTypes.Message> messages = request.messages();
        assertEquals(SoulTypes.Role.SYSTEM, messages.get(messages.size() - 2).role());
        assertTrue(messages.get(messages.size() - 2).content().startsWith("PRESENT MOMENT\n"));
        assertEquals(new SoulTypes.Message(SoulTypes.Role.USER, "Can you see this village?"),
                messages.get(messages.size() - 1));
    }

    @Test
    void remotePromptDoesNotContainPlayerSurroundings() {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, remoteGrounding,
                List.of(), List.of(), "What's around me?", Duration.ofSeconds(60));
        String joined = request.messages().stream().map(SoulTypes.Message::content)
                .collect(Collectors.joining("\n"));
        assertTrue(joined.contains("remote communication"));
        assertFalse(joined.contains("playerBiomeSecret"));
    }

    // === Bound assertions ===

    @Test
    void currentMessageAppearsExactlyOnce() {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents, "Can you see this village?", Duration.ofSeconds(60));
        long occurrences = request.messages().stream()
                .filter(m -> m.content().equals("Can you see this village?"))
                .count();
        assertEquals(1, occurrences);
    }

    @Test
    void historyBoundedByTurnCount() {
        List<SoulTypes.ConversationRecord> longHistory = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            SoulTypes.TurnKind kind = (i % 2 == 0) ? SoulTypes.TurnKind.HEARD : SoulTypes.TurnKind.SPOKEN;
            longHistory.add(new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, i,
                    kind, "HIST_TURN_" + i, Instant.EPOCH, "ollama", "local-model", 10L, null));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                longHistory, List.of(), "Status check.", Duration.ofSeconds(60));
        long historyMessages = request.messages().stream()
                .filter(m -> m.content().startsWith("HIST_TURN_"))
                .count();
        assertTrue(historyMessages <= 20, "history turns should be capped at 20, was " + historyMessages);
    }

    @Test
    void historyBoundedByCharacterBudget() {
        List<SoulTypes.ConversationRecord> longHistory = new ArrayList<>();
        String longLine = "HIST_CHARS_" + "x".repeat(2000);
        for (int i = 0; i < 10; i++) {
            SoulTypes.TurnKind kind = (i % 2 == 0) ? SoulTypes.TurnKind.HEARD : SoulTypes.TurnKind.SPOKEN;
            longHistory.add(new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, i,
                    kind, longLine + i, Instant.EPOCH, "ollama", "local-model", 10L, null));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                longHistory, List.of(), "Status check.", Duration.ofSeconds(60));
        long historyChars = request.messages().stream()
                .filter(m -> m.content().startsWith("HIST_CHARS_"))
                .mapToLong(m -> m.content().length())
                .sum();
        assertTrue(historyChars <= 12_000, "history chars should be capped at 12000, was " + historyChars);
    }

    @Test
    void eventsBoundedByCount() {
        List<SoulTypes.SoulEvent> manyEvents = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manyEvents.add(new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.TASK_COMPLETED,
                    bot.botId(), List.of(), "minecraft:overworld", "plains",
                    Map.of("marker", "EVT_MARK_" + i), SoulTypes.Witness.SELF,
                    100L + i, Instant.EPOCH, SoulTypes.Salience.NORMAL));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                List.of(), manyEvents, "Status check.", Duration.ofSeconds(60));
        long eventMessages = request.messages().stream()
                .filter(m -> m.content().contains("EVT_MARK_"))
                .count();
        assertTrue(eventMessages <= 12, "events should be capped at 12, was " + eventMessages);
    }

    @Test
    void eventsBoundedByCharacterBudget() {
        List<SoulTypes.SoulEvent> longEvents = new ArrayList<>();
        String longValue = "y".repeat(800);
        for (int i = 0; i < 6; i++) {
            longEvents.add(new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.TASK_COMPLETED,
                    bot.botId(), List.of(), "minecraft:overworld", "plains",
                    Map.of("marker", "EVT_LONG_" + i + longValue), SoulTypes.Witness.SELF,
                    100L + i, Instant.EPOCH, SoulTypes.Salience.NORMAL));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                List.of(), longEvents, "Status check.", Duration.ofSeconds(60));
        long eventChars = request.messages().stream()
                .filter(m -> m.content().contains("EVT_LONG_"))
                .mapToLong(m -> m.content().length())
                .sum();
        assertTrue(eventChars <= 4_000, "event chars should be capped at 4000, was " + eventChars);
        long eventMessages = request.messages().stream()
                .filter(m -> m.content().contains("EVT_LONG_"))
                .count();
        assertTrue(eventMessages < 6, "char budget should have trimmed some of the 6 events, kept " + eventMessages);
    }

    // === Registry ===

    @Test
    void loadBuiltInsExposesJakeProfile() {
        SoulProfileRegistry.loadBuiltIns();
        SoulTypes.SoulProfile jake = SoulProfileRegistry.require("frens:jake");
        assertEquals("frens:jake", jake.id());
        assertEquals("Jake", jake.displayName());
        assertFalse(jake.identity().isEmpty());
    }

    @Test
    void requireUnknownProfileThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SoulProfileRegistry.require("frens:does-not-exist"));
    }

    @Test
    void registerRejectsBlankId() {
        SoulTypes.SoulProfile blank = new SoulTypes.SoulProfile(
                "   ", "Blank", List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SoulProfileRegistry.register(blank));
    }

    @Test
    void registerRejectsDuplicateId() {
        SoulTypes.SoulProfile first = new SoulTypes.SoulProfile(
                "frens:dup-test", "First", List.of(), List.of(), List.of(), List.of());
        SoulProfileRegistry.register(first);
        SoulTypes.SoulProfile second = new SoulTypes.SoulProfile(
                "frens:dup-test", "Second", List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> SoulProfileRegistry.register(second));
    }
}
