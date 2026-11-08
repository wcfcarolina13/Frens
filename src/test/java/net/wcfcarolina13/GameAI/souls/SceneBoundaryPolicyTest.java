package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBoundaryPolicyTest {

    @Test
    void breakOnlyWhereTheSceneChanges() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(SceneBoundaryPolicy.breakBetween(a, b));
        assertFalse(SceneBoundaryPolicy.breakBetween(a, a));
        assertFalse(SceneBoundaryPolicy.breakBetween(null, b));
        assertFalse(SceneBoundaryPolicy.breakBetween(a, null));
    }

    @Test
    void cueBreakOnlyForNarratorCuedScenesWithHistory() {
        assertTrue(SceneBoundaryPolicy.breakBeforeCue(SoulGroupTypes.SceneKind.BANTER, false));
        assertTrue(SceneBoundaryPolicy.breakBeforeCue(SoulGroupTypes.SceneKind.WORK, false));
        assertFalse(SceneBoundaryPolicy.breakBeforeCue(SoulGroupTypes.SceneKind.PLAYER, false));
        assertFalse(SceneBoundaryPolicy.breakBeforeCue(SoulGroupTypes.SceneKind.LOCAL, false));
        assertFalse(SceneBoundaryPolicy.breakBeforeCue(SoulGroupTypes.SceneKind.BANTER, true));
    }

    private static SoulTypes.GroundingSnapshot grounding(UUID botId, String name) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, name, "overworld", "plains",
                0, 64, 0, true, "dusk", "clear", 17f, 20f, 14, 5, "iron sword", 10, 36,
                List.of(), "cheerful", "FOLLOW", "skill:woodcut", "running", "home", "Bradley",
                true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot, Optional.empty(), Instant.EPOCH);
    }

    private static SoulTypes.ConversationRecord spoken(UUID scene, long seq, String content) {
        return new SoulTypes.ConversationRecord(scene, 0L, seq, SoulTypes.TurnKind.SPOKEN, content,
                Instant.EPOCH, "", "", null, null);
    }

    private static List<SoulTypes.Message> assemble(boolean boundaries, SoulGroupTypes.SceneKind kind) {
        UUID jake = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(kind, UUID.randomUUID(), "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(bob, "frens:bob", "Bob", grounding(bob, "Bob"))),
                "it is dusk", Instant.EPOCH, UUID.randomUUID());
        SoulTypes.SoulProfile profile = new SoulTypes.SoulProfile("frens:jake", "Jake",
                List.of("A companion."), List.of(), List.of(), List.of());
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> history = List.of(
                spoken(s1, 1, "Jake: First scene."), spoken(s1, 2, "Bob: Still first."),
                spoken(s2, 3, "Bob: Second scene."));
        SoulGroupPromptAssembler assembler = new SoulGroupPromptAssembler(null, () -> false, () -> false,
                () -> boundaries);
        return assembler.assemble(UUID.randomUUID(), "m", turn, List.of(profile, profile), history,
                Duration.ofSeconds(5)).messages();
    }

    private static long markers(List<SoulTypes.Message> messages) {
        return messages.stream().filter(m -> m.content().equals(SceneBoundaryPolicy.MARKER)).count();
    }

    @Test
    void offByDefaultAddsNoMarker() {
        assertEquals(0, markers(assemble(false, SoulGroupTypes.SceneKind.BANTER)));
    }

    @Test
    void banterGetsOneBreakBetweenScenesAndOneBeforeTheCue() {
        List<SoulTypes.Message> messages = assemble(true, SoulGroupTypes.SceneKind.BANTER);
        assertEquals(2, markers(messages));
        int second = indexOf(messages, "Bob: Second scene.");
        assertEquals(SceneBoundaryPolicy.MARKER, messages.get(second - 1).content());
        assertEquals(SceneBoundaryPolicy.MARKER, messages.get(second + 1).content());
        assertEquals(SoulTypes.Role.USER, messages.get(messages.size() - 1).role());
    }

    @Test
    void playerSceneKeepsContinuityWithItsLastScene() {
        assertEquals(1, markers(assemble(true, SoulGroupTypes.SceneKind.PLAYER)));
    }

    private static int indexOf(List<SoulTypes.Message> messages, String content) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().equals(content)) {
                return i;
            }
        }
        throw new AssertionError("missing " + content);
    }
}
