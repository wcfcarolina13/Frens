package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the group-scene prompt: fixed message order (scene contract first, owner message
 * last exactly once), cast/state blocks for every roster member, verbatim speaker-tagged party
 * history under the group budgets, and request metadata.
 */
class SoulGroupPromptAssemblerTest {

    private final SoulGroupPromptAssembler assembler = new SoulGroupPromptAssembler();

    private static SoulTypes.GroundingSnapshot grounding(UUID botId, String name) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, name, "overworld", "plains",
                0, 64, 0, true, "dusk", "clear", 17f, 20f, 14, 5, "iron sword", 10, 36,
                List.of(), "cheerful", "FOLLOW", "skill:woodcut", "running", "home", "Bradley",
                true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), Instant.EPOCH);
    }

    private static SoulGroupTypes.GroupSceneTurn turn(String message) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                message, Instant.EPOCH, UUID.randomUUID());
    }

    private static SoulTypes.SoulProfile profile(String id, String displayName) {
        return new SoulTypes.SoulProfile(id, displayName,
                List.of("A loyal companion who loves mining.", "Wry sense of humor."),
                List.of("honesty"), List.of("never spoils quests"), List.of());
    }

    private static SoulTypes.ConversationRecord record(long seq, SoulTypes.TurnKind kind, String content) {
        return new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, seq, kind, content,
                Instant.EPOCH, "", "", null, null);
    }

    @Test
    void messageOrderContractFirstOwnerMessageLastExactlyOnce() {
        SoulGroupTypes.GroupSceneTurn turn = turn("what should we do tonight");
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "llama3.1:8b",
                turn, List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));

        List<SoulTypes.Message> messages = request.messages();
        assertEquals(SoulTypes.Role.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().startsWith("SCENE CONTRACT"));
        SoulTypes.Message last = messages.get(messages.size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertEquals("Bradley: what should we do tonight", last.content());
        long ownerMessages = messages.stream()
                .filter(m -> m.content().equals("Bradley: what should we do tonight")).count();
        assertEquals(1, ownerMessages);
    }

    @Test
    void castAndStateBlocksNameEveryRosterMember() {
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("hi"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));
        String allSystem = request.messages().stream()
                .filter(m -> m.role() == SoulTypes.Role.SYSTEM)
                .map(SoulTypes.Message::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allSystem.contains("CAST"));
        assertTrue(allSystem.contains("Jake"));
        assertTrue(allSystem.contains("Sara"));
        assertTrue(allSystem.contains("CURRENT STATE"));
        assertTrue(allSystem.contains("iron sword"));
    }

    @Test
    void partyHistoryReplaysVerbatimWithRolesAndSkipsFailures() {
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.HEARD, "Bradley: evening plans?"),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: mining, obviously."),
                record(2, SoulTypes.TurnKind.FAILURE, ""),
                record(3, SoulTypes.TurnKind.SPOKEN, "Sara: fishing is safer."));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        List<SoulTypes.Message> messages = request.messages();
        int n = messages.size();
        // ... history..., final USER message
        assertEquals("Sara: fishing is safer.", messages.get(n - 2).content());
        assertEquals(SoulTypes.Role.ASSISTANT, messages.get(n - 2).role());
        assertEquals("Bradley: evening plans?", messages.get(n - 4).content());
        assertEquals(SoulTypes.Role.USER, messages.get(n - 4).role());
        assertTrue(messages.stream().noneMatch(m -> m.content().isEmpty() && m.role() == SoulTypes.Role.ASSISTANT));
    }

    @Test
    void historyTurnBudgetKeepsOnlyTheNewestTwelve() {
        List<SoulTypes.ConversationRecord> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(record(i, SoulTypes.TurnKind.SPOKEN, "Jake: line " + i));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        long historyMessages = request.messages().stream()
                .filter(m -> m.content().startsWith("Jake: line ")).count();
        assertEquals(SoulGroupPromptAssembler.MAX_HISTORY_TURNS, historyMessages);
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Jake: line 14")));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().equals("Jake: line 2")));
    }

    @Test
    void historyCharBudgetTrimsOldestFirst() {
        String big = "x".repeat(3_900);
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.SPOKEN, "Jake: OLDEST " + big),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: newer " + big.substring(0, 100)));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().startsWith("Jake: OLDEST")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().startsWith("Jake: newer")));
    }

    @Test
    void identityBlocksAreTruncatedPerBot() {
        List<String> identity = List.of("y".repeat(2_000));
        SoulTypes.SoulProfile huge = new SoulTypes.SoulProfile("frens:jake", "Jake",
                identity, List.of(), List.of(), List.of());
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(huge, huge), List.of(), Duration.ofSeconds(60));
        String cast = request.messages().stream()
                .filter(m -> m.content().startsWith("CAST"))
                .findFirst().orElseThrow().content();
        // Two members, each identity bounded — the whole cast block stays under 2 * (cap + names/labels).
        assertTrue(cast.length() < 2 * (SoulGroupPromptAssembler.MAX_IDENTITY_CHARS_PER_BOT + 200),
                "cast block length " + cast.length());
    }

    private static SoulGroupTypes.GroupSceneTurn banterTurn(String seed) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                seed, Instant.EPOCH, UUID.randomUUID());
    }

    @Test
    void banterFinalMessageIsANarratorDirectiveNotAPlayerLine() {
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                banterTurn("it is dusk, rain; Jake slew a mob"),
                List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));
        SoulTypes.Message last = request.messages().get(request.messages().size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertTrue(last.content().startsWith("[") && last.content().endsWith("]"), last.content());
        assertTrue(last.content().contains("it is dusk, rain; Jake slew a mob"), last.content());
        assertTrue(request.messages().stream().noneMatch(m -> m.content().startsWith("Bradley: ")));
    }

    @Test
    void banterHeardRecordsAreSkippedInHistoryReplayButSpokenReplay() {
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.HEARD, SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "old seed"),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: banter line."),
                record(2, SoulTypes.TurnKind.HEARD, "Bradley: real question"));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().contains("old seed")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Jake: banter line.")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Bradley: real question")));
    }

    @Test
    void requestCarriesModelTimeoutTokensAndCorrelation() {
        UUID correlationId = UUID.randomUUID();
        SoulTypes.ProviderRequest request = assembler.assemble(correlationId, "llama3.2:3b",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(45));
        assertEquals(correlationId, request.correlationId());
        assertEquals("llama3.2:3b", request.model());
        assertEquals(Duration.ofSeconds(45), request.timeout());
        assertEquals(SoulGroupPromptAssembler.MAX_OUTPUT_TOKENS, request.maxOutputTokens());
    }
}
