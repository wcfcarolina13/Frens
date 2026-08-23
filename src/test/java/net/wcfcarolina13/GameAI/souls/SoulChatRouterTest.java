package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.wcfcarolina13.GameAI.souls.SoulChatRouter.RouteOutcome.CONSUMED;
import static net.wcfcarolina13.GameAI.souls.SoulChatRouter.RouteOutcome.NOT_SOUL;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.Reachability.LOCAL;
import static net.wcfcarolina13.GameAI.souls.SoulTypes.Reachability.UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers what is testable about {@link SoulChatRouter} without a running Minecraft server.
 *
 * <p>{@link SoulChatRouter#tryRoute} itself is untestable at the unit level in this harness: it
 * takes {@code net.minecraft.server.network.ServerPlayerEntity} parameters and calls
 * {@code bot.getEntityWorld().getServer()}, {@code CompanionCommunicationPolicy}, and
 * {@code SoulSnapshotBuilder.capture} -- all of which need live Minecraft/Fabric state. No test in
 * this repository mocks or constructs {@code MinecraftServer}/{@code ServerPlayerEntity} (see
 * {@code SoulMessageDeliveryTest}'s class Javadoc for the confirmed Mockito failure), and this one
 * does not either. {@code tryRoute} is exercised in-game instead, once wired up.
 *
 * <p>What IS covered here: {@link SoulChatRouter#decide}, the pure coarse routing-decision table
 * (the brief's exact mandated matrix), and {@link SoulRuntime#submitTurn} -- the seam
 * {@code tryRoute}'s final step delegates to once every gate before it has already passed. Per the
 * task controller's ruling, "an authorized reachable ready turn is consumed and submitted exactly
 * once" is asserted at that seam: a runtime built via the package-private five-argument constructor
 * plus {@link SoulRuntime#installForTest} with a mocked {@link SoulConversationService} lets
 * {@code submitTurn}'s delegation be verified by invocation count without touching
 * {@code SoulRuntimeTest}.
 */
class SoulChatRouterTest {

    @AfterEach
    void tearDown() {
        // The static INSTANCE slot is process-wide; make sure a test that installs a runtime
        // never leaks it into the next test (same discipline as SoulRuntimeTest).
        SoulRuntime.stop();
    }

    private static SoulSettings settings(boolean enabled, boolean valid, String model) {
        return new SoulSettings(enabled, valid, valid ? "" : "invalid", "ollama", model,
                URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
    }

    private static SoulTypes.AcceptedTurn acceptedTurn() {
        UUID botId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(botId, playerId, SoulTypes.Channel.DIRECT);
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, "Jake", "minecraft:overworld", "plains",
                0, 64, 0, true, "day", "clear", 20.0f, 20.0f, 20, 0, "bare hands", 0, 36,
                List.of(), "content", "idle", "", "", "", "", false, 0, false, Optional.empty());
        SoulTypes.GroundingSnapshot grounding =
                new SoulTypes.GroundingSnapshot(LOCAL, bot, Optional.empty(), Instant.now());
        return new SoulTypes.AcceptedTurn(key, "Jake", "Player", "hi jake", "frens:jake", grounding, Instant.now());
    }

    // === Mandated tests (verbatim from the task brief) ===

    @Test
    void enabledActiveSoulConsumesUnauthorizedAndUnreachableTurns() {
        assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, true, false, LOCAL));
        assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, true, true, UNREACHABLE));
    }

    @Test
    void disabledOrUnboundSoulLeavesLegacyRoutingUntouched() {
        assertEquals(NOT_SOUL, SoulChatRouter.decide(false, true, true, true, true, LOCAL));
        assertEquals(NOT_SOUL, SoulChatRouter.decide(true, true, false, true, true, LOCAL));
        assertEquals(CONSUMED, SoulChatRouter.decide(true, false, false, false, true, LOCAL));
        assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, false, true, LOCAL));
    }

    // === Own coverage: decide()'s remaining branches ===

    @Test
    void notReadyIndexConsumesBeforeProfileActivationIsEvenConsidered() {
        // indexReady=false must short-circuit to CONSUMED (a LOADING notice) regardless of
        // profileActive, since activation state is unknown until the index has warmed.
        assertEquals(CONSUMED, SoulChatRouter.decide(true, false, true, true, true, LOCAL));
    }

    @Test
    void readyIndexWithNoActiveProfileIsUnboundNotSoul() {
        assertEquals(NOT_SOUL, SoulChatRouter.decide(true, true, false, false, false, UNREACHABLE));
    }

    // === Own coverage: SoulRuntime#submitTurn -- the seam tryRoute's final step delegates to ===

    @Test
    void authorizedReachableReadyTurnIsSubmittedExactlyOnce() {
        SoulStore store = mock(SoulStore.class);
        SoulConversationService conversationService = mock(SoulConversationService.class);
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), mock(SoulGenerationScheduler.class), conversationService);
        SoulRuntime.installForTest(runtime);

        SoulTypes.AcceptedTurn turn = acceptedTurn();
        when(conversationService.submit(turn))
                .thenReturn(CompletableFuture.completedFuture(SoulConversationService.Submission.DELIVERED));

        CompletableFuture<SoulConversationService.Submission> result = runtime.submitTurn(turn);

        assertEquals(SoulConversationService.Submission.DELIVERED, result.join());
        verify(conversationService, times(1)).submit(turn);
    }

    @Test
    void submitTurnFailsClosedWithoutTouchingTheServiceWhenConversationIsNotEnabled() {
        SoulStore store = mock(SoulStore.class);
        SoulConversationService conversationService = mock(SoulConversationService.class);
        SoulRuntime runtime = new SoulRuntime(settings(true, false, ""), store,
                mock(SoulModelProvider.class), mock(SoulGenerationScheduler.class), conversationService);
        SoulRuntime.installForTest(runtime);

        SoulTypes.AcceptedTurn turn = acceptedTurn();
        CompletableFuture<SoulConversationService.Submission> result = runtime.submitTurn(turn);

        assertEquals(SoulConversationService.Submission.FAILED, result.join());
        verify(conversationService, never()).submit(turn);
    }

    @Test
    void submitTurnFailsClosedAfterStopWithoutTouchingTheService() {
        SoulStore store = mock(SoulStore.class);
        SoulConversationService conversationService = mock(SoulConversationService.class);
        SoulRuntime runtime = new SoulRuntime(settings(true, true, "test-model"), store,
                mock(SoulModelProvider.class), mock(SoulGenerationScheduler.class), conversationService);
        SoulRuntime.installForTest(runtime);
        SoulRuntime.stop();

        SoulTypes.AcceptedTurn turn = acceptedTurn();
        CompletableFuture<SoulConversationService.Submission> result = runtime.submitTurn(turn);

        assertEquals(SoulConversationService.Submission.FAILED, result.join());
        verify(conversationService, never()).submit(turn);
    }
}
