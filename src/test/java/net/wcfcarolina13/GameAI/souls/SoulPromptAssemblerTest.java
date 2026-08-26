package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final SoulTypes.BotSnapshot undergroundBot = new SoulTypes.BotSnapshot(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
            "minecraft:overworld", "plains", 0, 12, 0, false,
            "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
            8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
            "Workshop", "Player", true, 2, false, java.util.Optional.empty());
    private final SoulTypes.BotSnapshot followBot = new SoulTypes.BotSnapshot(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
            "minecraft:overworld", "plains", 0, 64, 0, true,
            "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
            8, 36, List.of("oak_log x32"), "content", "FOLLOW", "", "IDLE",
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
    void presentMomentIncludesDynamicRightNowLineReflectingGrounding() {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents, "Can you see this village?", Duration.ofSeconds(60));
        String presentMomentContent = request.messages().get(request.messages().size() - 2).content();
        assertTrue(presentMomentContent.startsWith("PRESENT MOMENT\n"));
        assertTrue(presentMomentContent.contains("Right now: plains, (0,64,0), open sky, mode idle."),
                presentMomentContent);
    }

    @Test
    void presentMomentReflectsUndergroundAndFollowMode() {
        SoulTypes.SituationSnapshot followingSituation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "", List.of(), false, false, false, "FOLLOW", true,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        SoulTypes.GroundingSnapshot followGrounding = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, followBot, Optional.of(localPlayer), followingSituation,
                Instant.EPOCH);
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, followGrounding,
                List.of(), List.of(), "Where are we going?", Duration.ofSeconds(60));
        String presentMomentContent = request.messages().get(request.messages().size() - 2).content();
        assertTrue(presentMomentContent.contains("mode FOLLOW, following your owner."), presentMomentContent);

        SoulTypes.GroundingSnapshot undergroundGrounding = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, undergroundBot, Optional.of(localPlayer), Instant.EPOCH);
        SoulTypes.ProviderRequest undergroundRequest = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, undergroundGrounding,
                List.of(), List.of(), "Where are we going?", Duration.ofSeconds(60));
        String undergroundPresentMoment =
                undergroundRequest.messages().get(undergroundRequest.messages().size() - 2).content();
        assertTrue(undergroundPresentMoment.contains("Right now: plains, (0,12,0), underground, mode idle."),
                undergroundPresentMoment);
    }

    @Test
    void presentMomentDoesNotClaimFollowingFromModeNameAlone() {
        // followBot's behaviorMode is the string "FOLLOW", but its grounding carries no situation
        // (defaults to SituationSnapshot.empty(), following=false) -- return-to-base also renders
        // Mode.FOLLOW, so the "following your owner" clause must come from the situation's own
        // following flag, never from string-matching bot.behaviorMode() == "FOLLOW".
        SoulTypes.GroundingSnapshot followGroundingWithoutSituation = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, followBot, Optional.of(localPlayer), Instant.EPOCH);
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, followGroundingWithoutSituation,
                List.of(), List.of(), "Where are we going?", Duration.ofSeconds(60));
        String presentMomentContent = request.messages().get(request.messages().size() - 2).content();
        assertTrue(presentMomentContent.contains("mode FOLLOW."), presentMomentContent);
        assertFalse(presentMomentContent.contains("following your owner"), presentMomentContent);
    }

    @Test
    void presentMomentAppendsAtBaseWhenPresent() {
        SoulTypes.SituationSnapshot atBaseSituation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "", List.of(), false, false, false, "IDLE", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 1, Optional.empty(), Optional.of("Workshop"), Optional.empty(),
                Optional.empty());
        SoulTypes.GroundingSnapshot atBaseGrounding = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(localPlayer), atBaseSituation, Instant.EPOCH);
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, atBaseGrounding,
                List.of(), List.of(), "Where are we?", Duration.ofSeconds(60));
        String presentMomentContent = request.messages().get(request.messages().size() - 2).content();
        // PRESENT MOMENT's mode name comes from BotSnapshot.behaviorMode() ("idle", the bot
        // fixture's lowercase value), not the situation's own behaviorMode ("IDLE") -- only the
        // atBase clause is sourced from the situation snapshot here.
        assertTrue(presentMomentContent.contains("mode idle, at base Workshop."), presentMomentContent);
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

    @Test
    void remotePromptRendersSituationWithoutPlayerSurroundings() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                6, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)), List.of(), "", List.of(),
                false, true, true, "GUARD", false, false, true, 2,
                false, false, false, false, 14, 1,
                Optional.of(new SoulTypes.MountSummary("horse", 11.0F, 22.0F, true)),
                3, Optional.of("Workshop"), Optional.empty(), Optional.empty(), Optional.of("fishing"));
        SoulTypes.GroundingSnapshot remoteGroundingWithSituation = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.REMOTE, bot, Optional.empty(), situation, Instant.EPOCH);
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, remoteGroundingWithSituation,
                List.of(), List.of(), "What's around me?", Duration.ofSeconds(60));
        String joined = request.messages().stream().map(SoulTypes.Message::content)
                .collect(Collectors.joining("\n"));
        assertTrue(joined.contains("remote communication"));
        assertTrue(joined.contains("SITUATION"));
        assertTrue(joined.contains("zombie"));
        assertFalse(joined.contains("playerBiomeSecret"));
    }

    // === SITUATION rendering ===

    private String authoritativeStateMessage(SoulTypes.ProviderRequest request) {
        return request.messages().stream()
                .filter(m -> m.role() == SoulTypes.Role.SYSTEM && m.content().startsWith("AUTHORITATIVE STATE"))
                .map(SoulTypes.Message::content)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void situationBlockRendersInsideAuthoritativeStateInPriorityOrder() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                6, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)), List.of(), "", List.of(),
                false, true, true, "GUARD", false, false, true, 2,
                false, false, false, false, 14, 1,
                Optional.of(new SoulTypes.MountSummary("horse", 11.0F, 22.0F, true)),
                3, Optional.of("Workshop"), Optional.empty(), Optional.empty(), Optional.of("fishing"));
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "How are we doing?", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request); // helper: find the system msg containing "SITUATION"
        assertTrue(state.contains("SITUATION"));
        assertTrue(state.indexOf("zombie") < state.indexOf("GUARD"));           // hazards before mode
        assertTrue(state.indexOf("GUARD") < state.indexOf("companion for"));    // mode before relationship
        assertTrue(state.contains("northeast"));
    }

    @Test
    void situationBlockIsCappedAt800CharsDroppingLowestPriorityFirst() {
        String longSleepLabel = "S".repeat(400);
        String longHobby = "H".repeat(400);
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                5, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)), List.of(), "", List.of(),
                false, false, false, "GUARD", false, false, false, 0,
                false, false, false, false, 14, 1,
                Optional.empty(), 0, Optional.of(longSleepLabel), Optional.empty(), Optional.empty(),
                Optional.of(longHobby));
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "Status check.", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        int situationStart = state.indexOf("SITUATION");
        assertTrue(situationStart >= 0, "expected a SITUATION block");
        String situationBlock = state.substring(situationStart);
        assertTrue(situationBlock.length() <= 800,
                "situation block should be capped at 800 chars, was " + situationBlock.length());
        assertTrue(situationBlock.contains("zombie"), "hostiles line should survive the cap");
        assertTrue(situationBlock.contains("You are in plains at (0,64,0) in minecraft:overworld."),
                "location line is top-priority and must survive the cap");
        assertFalse(situationBlock.contains(longHobby), "hobby text should be dropped by the cap");
    }

    @Test
    void situationBlockAlwaysOpensWithLocationLineWhenNonEmpty() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                6, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)), List.of(), "", List.of(),
                false, false, false, "", false, false, false, 0,
                false, false, false, false, -1, -1,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "Status check.", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        int situationStart = state.indexOf("SITUATION");
        assertTrue(situationStart >= 0, "expected a SITUATION block");
        String situationBlock = state.substring(situationStart);
        assertTrue(situationBlock.startsWith("SITUATION\nYou are in plains at (0,64,0) in minecraft:overworld.\n"),
                "location line should immediately follow the SITUATION header: " + situationBlock);
        assertFalse(situationBlock.contains("underground"),
                "the fixture's bot has sky visible; no underground line expected");
    }

    @Test
    void undergroundLineAppearsWhenSkyNotVisible() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                6, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)), List.of(), "", List.of(),
                false, false, false, "", false, false, false, 0,
                false, false, false, false, -1, -1,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, undergroundBot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "Status check.", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        assertTrue(state.contains("You are underground -- no sky overhead. There are no trees or "
                + "open terrain down here; surface features are out of sight."));
    }

    @Test
    void nearbyAnimalsRenderInSituationBlockWithLogistics() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of("horse x2", "wolf"), "", List.of(),
                false, false, false, "IDLE", false, false, false, 0,
                false, false, false, false, -1, -1,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "What's around?", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        assertTrue(state.contains("Animals nearby: horse x2, wolf."));
        assertTrue(state.indexOf("Mode: IDLE") < state.indexOf("Animals nearby"),
                "nearby-animals line renders with logistics, after mode");
    }

    @Test
    void standingOnAndNearbyBlocksRenderInSituationBlockWithLogistics() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "Grass Block", List.of("Oak Log", "Stone", "Dirt", "Water"),
                false, false, false, "IDLE", false, false, false, 0,
                false, false, false, false, -1, -1,
                Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "What's around?", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        assertTrue(state.contains("Standing on Grass Block; nearby blocks: Oak Log, Stone, Dirt, Water."),
                state);
        assertTrue(state.indexOf("Mode: IDLE") < state.indexOf("Standing on"),
                "standing-on/nearby-blocks line renders with logistics, after mode");
    }

    @Test
    void atBaseRendersInSituationBlockWhenPresent() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "", List.of(),
                false, false, false, "IDLE", false, false, false, 0,
                false, false, false, false, -1, -1,
                Optional.empty(), 1, Optional.empty(), Optional.of("Workshop"), Optional.empty(), Optional.empty());
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(localPlayer), situation, Instant.EPOCH),
                List.of(), List.of(), "Are we home?", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        assertTrue(state.contains("You are at your base \"Workshop\"."), state);
    }

    @Test
    void emptySituationRendersNoSituationBlock() {
        SoulTypes.GroundingSnapshot groundingWithoutSituation = new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(localPlayer), Instant.EPOCH);
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, groundingWithoutSituation,
                List.of(), List.of(), "Status check.", Duration.ofSeconds(60));
        String state = authoritativeStateMessage(request);
        assertFalse(state.contains("SITUATION"));
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

    private String systemContent(SoulTypes.GroundingSnapshot g) {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, g,
                priorHistory, recentEvents, "What are you carrying?", Duration.ofSeconds(60));
        return request.messages().stream()
                .filter(m -> m.role() == SoulTypes.Role.SYSTEM)
                .map(SoulTypes.Message::content)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void stateMessageDescribesWornGearAndNotableItems() {
        SoulTypes.BotSnapshot gearedBot = new SoulTypes.BotSnapshot(
                bot.botId(), "Jake", "minecraft:overworld", "plains", 0, 64, 0, true,
                "day", "clear", 20.0F, 20.0F, 18, 4, "Iron Pickaxe (Efficiency III)",
                8, 36, List.of("32x Oak Log"),
                List.of("Iron Helmet (Protection II)", "Shield (offhand)"),
                List.of("Red Bed", "Red Bundle holding 32x Torch"),
                "content", "idle", "", "IDLE",
                "Workshop", "Player", true, 2, false, Optional.empty());
        String content = systemContent(new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, gearedBot, Optional.of(localPlayer), Instant.EPOCH));

        assertTrue(content.contains("Wearing: Iron Helmet (Protection II), Shield (offhand)"), content);
        assertTrue(content.contains("Carrying: Red Bed, Red Bundle holding 32x Torch"), content);
        assertTrue(content.contains("held item: Iron Pickaxe (Efficiency III)"), content);
    }

    @Test
    void stateMessageSaysWearingNothingAndOmitsCarryingWhenBare() {
        String content = systemContent(grounding);

        assertTrue(content.contains("Wearing: nothing"), content);
        assertFalse(content.contains("Carrying:"), content);
    }

    @Test
    void situationRendersFacilitiesLineWithUtilityPhrases() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "", List.of(),
                List.of("2x Chest (stores items)", "Furnace (smelts ore, cooks food)"),
                false, false, false, "IDLE", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        String content = systemContent(new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(localPlayer), situation, Instant.EPOCH));

        assertTrue(content.contains(
                "Facilities nearby: 2x Chest (stores items), Furnace (smelts ore, cooks food)."), content);
    }

    @Test
    void situationOmitsFacilitiesLineWhenNoneSeen() {
        assertFalse(systemContent(grounding).contains("Facilities nearby"), "no facilities expected");
    }

    @Test
    void situationRendersLightLevelsAndArmorStands() {
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
                -1, List.of(), List.of(), "", List.of(), List.of(),
                List.of("Armor stand displaying: Iron Helmet (Protection I), Iron Chestplate"),
                4, 0,
                false, false, false, "IDLE", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        String content = systemContent(new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(localPlayer), situation, Instant.EPOCH));

        assertTrue(content.contains(
                "Light here: block light 4, sky light 0. Hostile mobs can only spawn where block light is 0."),
                content);
        assertTrue(content.contains(
                "Armor stand displaying: Iron Helmet (Protection I), Iron Chestplate"), content);
    }

    @Test
    void situationOmitsLightLineWhenUnknown() {
        assertFalse(systemContent(grounding).contains("Light here"), "no light data expected");
    }

    @Test
    void playerLineIncludesLookTarget() {
        SoulTypes.PlayerSnapshot looking = new SoulTypes.PlayerSnapshot(
                localPlayer.playerId(), "Player", 6, "north", 20.0F, 20.0F, 20,
                "playerBiomeSecret", false, "Weathered Copper Trapdoor");
        String content = systemContent(new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(looking), Instant.EPOCH));

        assertTrue(content.contains("looking at: Weathered Copper Trapdoor"), content);
    }

    @Test
    void playerLineOmitsLookTargetWhenUnknown() {
        assertFalse(systemContent(grounding).contains("looking at:"), "no look target expected");
    }

    @Test
    void playerLineIncludesCurrentActivity() {
        SoulTypes.PlayerSnapshot busy = new SoulTypes.PlayerSnapshot(
                localPlayer.playerId(), "Player", 6, "north", 20.0F, 20.0F, 20,
                "playerBiomeSecret", false, "", "sneaking; broke Stone 4s ago");
        String content = systemContent(new SoulTypes.GroundingSnapshot(
                SoulTypes.Reachability.LOCAL, bot, Optional.of(busy), Instant.EPOCH));

        assertTrue(content.contains("currently: sneaking; broke Stone 4s ago"), content);
    }

    @Test
    void playerLineOmitsActivityWhenIdle() {
        assertFalse(systemContent(grounding).contains("currently:"), "no activity expected");
    }

    // === RELEVANT KNOWLEDGE block (deterministic retrieval, between events and PRESENT MOMENT) ===

    @Test
    void relevantKnowledgeRendersBetweenEventsAndPresentMoment() {
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents,
                List.of("Torch: craft 4 at crafting table from 1 Stick (have 4)"),
                "can you make a torch?", Duration.ofSeconds(60));

        List<SoulTypes.Message> messages = request.messages();
        int knowledgeIdx = -1;
        int presentIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().startsWith("RELEVANT KNOWLEDGE\n")) {
                knowledgeIdx = i;
            }
            if (messages.get(i).content().startsWith("PRESENT MOMENT\n")) {
                presentIdx = i;
            }
        }
        assertTrue(knowledgeIdx >= 0, "knowledge block missing");
        assertEquals(presentIdx - 1, knowledgeIdx, "knowledge must immediately precede PRESENT MOMENT");
        assertEquals(SoulTypes.Role.SYSTEM, messages.get(knowledgeIdx).role());
        assertTrue(messages.get(knowledgeIdx).content().contains("Torch: craft 4"),
                messages.get(knowledgeIdx).content());
    }

    @Test
    void emptyRelevantKnowledgeAddsNoMessage() {
        SoulTypes.ProviderRequest withEmpty = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents, List.of(), "hello", Duration.ofSeconds(60));
        SoulTypes.ProviderRequest legacy = assembler.assemble(
                UUID.randomUUID(), "local-model", profile, grounding,
                priorHistory, recentEvents, "hello", Duration.ofSeconds(60));

        assertEquals(legacy.messages().size(), withEmpty.messages().size());
        assertTrue(withEmpty.messages().stream()
                .noneMatch(m -> m.content().startsWith("RELEVANT KNOWLEDGE")));
    }

    // === Salience-weighted event selection (pure seam: SoulPromptAssembler.selectEvents) ===

    private SoulTypes.SoulEvent event(SoulTypes.EventType type, long tick, SoulTypes.Salience salience) {
        return new SoulTypes.SoulEvent(UUID.randomUUID(), type, bot.botId(), List.of(),
                "minecraft:overworld", "plains", Map.of(), SoulTypes.Witness.SELF,
                tick, Instant.EPOCH, salience);
    }

    private List<SoulTypes.SoulEvent> lowEvents(int count, long startTick) {
        List<SoulTypes.SoulEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(event(SoulTypes.EventType.HOBBY_SESSION, startTick + i, SoulTypes.Salience.LOW));
        }
        return events;
    }

    @Test
    void twelveOrFewerEventsAreKeptVerbatim() {
        List<SoulTypes.SoulEvent> events = lowEvents(12, 0L);
        assertEquals(events, SoulPromptAssembler.selectEvents(events));
    }

    @Test
    void highSalienceEventSurvivesDeepInTheWindow() {
        List<SoulTypes.SoulEvent> events = new ArrayList<>();
        events.add(event(SoulTypes.EventType.DEATH, 0L, SoulTypes.Salience.HIGH));
        events.addAll(lowEvents(40, 1L));

        List<SoulTypes.SoulEvent> selected = SoulPromptAssembler.selectEvents(events);

        assertEquals(12, selected.size());
        assertEquals(SoulTypes.EventType.DEATH, selected.get(0).type());
    }

    @Test
    void sixNewestEventsAreAlwaysKeptInOrder() {
        List<SoulTypes.SoulEvent> events = new ArrayList<>();
        events.add(event(SoulTypes.EventType.DEATH, 0L, SoulTypes.Salience.HIGH));
        events.addAll(lowEvents(40, 1L));

        List<SoulTypes.SoulEvent> selected = SoulPromptAssembler.selectEvents(events);
        List<SoulTypes.SoulEvent> lastSix = selected.subList(selected.size() - 6, selected.size());

        assertEquals(events.subList(events.size() - 6, events.size()), lastSix);
    }

    @Test
    void salienceFillPrefersNormalOverLowAndNewerWithinTier() {
        List<SoulTypes.SoulEvent> events = new ArrayList<>();
        SoulTypes.SoulEvent olderNormal = event(SoulTypes.EventType.TASK_COMPLETED, 0L, SoulTypes.Salience.NORMAL);
        SoulTypes.SoulEvent newerNormal = event(SoulTypes.EventType.MOB_KILLED, 1L, SoulTypes.Salience.NORMAL);
        events.add(olderNormal);
        events.add(newerNormal);
        events.addAll(lowEvents(46, 2L));

        List<SoulTypes.SoulEvent> selected = SoulPromptAssembler.selectEvents(events);

        assertTrue(selected.contains(olderNormal), "older NORMAL should beat LOW filler");
        assertTrue(selected.contains(newerNormal), "newer NORMAL should beat LOW filler");
        assertEquals(12, selected.size());
    }

    @Test
    void selectionIsPresentedInOriginalChronologicalOrder() {
        List<SoulTypes.SoulEvent> events = new ArrayList<>(lowEvents(20, 0L));
        events.add(event(SoulTypes.EventType.SELF_RESCUE, 20L, SoulTypes.Salience.HIGH));
        events.addAll(lowEvents(20, 21L));

        List<SoulTypes.SoulEvent> selected = SoulPromptAssembler.selectEvents(events);

        List<SoulTypes.SoulEvent> reordered = new ArrayList<>(selected);
        reordered.sort((a, b) -> Long.compare(a.worldTick(), b.worldTick()));
        assertEquals(reordered, selected, "selected events must stay in journal order");
        assertTrue(selected.stream().anyMatch(e -> e.type() == SoulTypes.EventType.SELF_RESCUE));
    }

    @Test
    void presentMomentCarriesPlayerLookTargetForRecency() {
        SoulTypes.PlayerSnapshot looking = new SoulTypes.PlayerSnapshot(
                localPlayer.playerId(), "Player", 6, "north", 20.0F, 20.0F, 20,
                "playerBiomeSecret", false, "Brown Carpet", "");
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "local-model", profile,
                new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                        Optional.of(looking), Instant.EPOCH),
                priorHistory, recentEvents, "what's this?", Duration.ofSeconds(60));
        String presentMoment = request.messages().get(request.messages().size() - 2).content();

        assertTrue(presentMoment.contains("the player is looking at Brown Carpet"), presentMoment);
    }

    private static SoulTypes.GroundingSnapshot groundingWithOverheard(List<String> overheard) {
        SoulTypes.BotSnapshot localBot = new SoulTypes.BotSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
                "minecraft:overworld", "plains", 0, 64, 0, true,
                "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
                8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
                "Workshop", "Player", true, 2, false, Optional.empty());
        SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "Player",
                6, "north", 20.0F, 20.0F, 20, "playerBiomeSecret", false);
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, localBot,
                Optional.of(player), SoulTypes.SituationSnapshot.empty(), Instant.EPOCH, overheard);
    }

    @Test
    void recentlyOverheardBlockRendersOnlyWhenPresent() {
        SoulTypes.GroundingSnapshot without = groundingWithOverheard(List.of());
        SoulTypes.ProviderRequest bare = assembler.assemble(
                UUID.randomUUID(), "model", profile, without, List.of(), List.of(), List.of(),
                "hello", Duration.ofSeconds(30));
        assertTrue(bare.messages().stream().noneMatch(m -> m.content().contains("RECENTLY OVERHEARD")),
                "an empty list must add no block at all — DM prompts stay byte-identical");

        SoulTypes.GroundingSnapshot with = groundingWithOverheard(
                List.of("heading to the ravine", "bring a bucket"));
        SoulTypes.ProviderRequest grounded = assembler.assemble(
                UUID.randomUUID(), "model", profile, with, List.of(), List.of(), List.of(),
                "hello", Duration.ofSeconds(30));
        String block = grounded.messages().stream()
                .map(SoulTypes.Message::content)
                .filter(c -> c.contains("RECENTLY OVERHEARD"))
                .findFirst().orElse("");
        assertTrue(block.contains("heading to the ravine"));
        assertTrue(block.contains("bring a bucket"));
    }

    @Test
    void overheardBlockIsBounded() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("a fairly long overheard line number " + i + " about mining and caves");
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "model", profile, groundingWithOverheard(many), List.of(),
                List.of(), List.of(), "hello", Duration.ofSeconds(30));
        String block = request.messages().stream()
                .map(SoulTypes.Message::content)
                .filter(c -> c.contains("RECENTLY OVERHEARD"))
                .findFirst().orElse("");
        assertTrue(block.length() <= SoulPromptAssembler.MAX_OVERHEARD_CHARS + 40,
                "block was " + block.length() + " chars");
    }
}
