package net.wcfcarolina13.GameAI.souls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates one heard-to-spoken soul-communication turn end to end.
 *
 * <p>The promise chain is fixed: {@link SoulStore#beginHeardTurn} records the HEARD turn first
 * (unconditionally — a turn is always remembered as heard, whatever happens next); then bounded
 * prior history ({@link SoulStore#recentBefore}) and recent witnessed events
 * ({@link SoulStore#recentEvents}) are fetched concurrently; then {@link SoulPromptAssembler}
 * assembles a bounded prompt; then exactly one generation call is scheduled through
 * {@link SoulGenerationScheduler}; then the raw result is validated
 * ({@link SoulResponseValidator}); then, only for an accepted result, {@link Delivery#deliverReply}
 * performs the private send; and only after that send actually succeeds is the reply committed to
 * persisted memory as SPOKEN plus a content-free {@code DIRECT_CONVERSATION} witnessed event.
 *
 * <p>A reply is never recorded as SPOKEN unless {@link Delivery#deliverReply} completed the
 * private send. Every failure after the turn was accepted appends a {@code FAILURE} record
 * <em>when the turn's token is still current</em> — a token invalidated by a mid-flight reset
 * (scheduler {@code CANCELLED}/{@code STALE_EPOCH}) is skipped rather than attempted, since
 * {@link SoulStore} would itself reject that append as stale. Every failure also sends exactly one
 * of a small set of deterministic, provider-detail-free status lines to the player — raw provider
 * exception text is never surfaced to chat.
 */
public final class SoulConversationService {

    // A dedicated logger (never Frens.LOGGER) -- this package is deliberately free of any
    // Minecraft/Fabric/mod-class reference so it stays unit-testable off-thread; touching the
    // Frens class at all triggers its static initializer, which fails outside a running game.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    /** Outcome of {@link #submit(SoulTypes.AcceptedTurn)}. */
    public enum Submission { DELIVERED, FAILED }

    /**
     * Server-thread private-delivery boundary. Implementations must never block the calling
     * (worker) thread; {@link SoulMessageDelivery} schedules its work via
     * {@code MinecraftServer.execute}.
     */
    public interface Delivery {
        CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                                                 String text);

        void deliverStatus(UUID playerId, String text);
    }

    /** Voice subscription point: fired once per turn, only after text is committed as spoken. */
    public interface SpokenListener {
        void onSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, String text);
    }

    private static final SpokenListener NO_OP_SPOKEN_LISTENER = (turn, token, text) -> {
    };

    private final SoulStore store;
    private final SoulPromptAssembler prompts;
    private final SoulGenerationScheduler scheduler;
    private final SoulModelProvider provider;
    private final SoulResponseValidator validator;
    private final Delivery delivery;
    private final SoulSettings settings;
    private final SpokenListener spokenListener;

    public SoulConversationService(SoulStore store,
                                    SoulPromptAssembler prompts,
                                    SoulGenerationScheduler scheduler,
                                    SoulModelProvider provider,
                                    SoulResponseValidator validator,
                                    Delivery delivery,
                                    SoulSettings settings) {
        this(store, prompts, scheduler, provider, validator, delivery, settings, NO_OP_SPOKEN_LISTENER);
    }

    public SoulConversationService(SoulStore store,
                                    SoulPromptAssembler prompts,
                                    SoulGenerationScheduler scheduler,
                                    SoulModelProvider provider,
                                    SoulResponseValidator validator,
                                    Delivery delivery,
                                    SoulSettings settings,
                                    SpokenListener spokenListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.spokenListener = Objects.requireNonNull(spokenListener, "spokenListener");
    }

    /**
     * Cancels the active generation call (if any) and completes any still-queued call with
     * {@code STALE_EPOCH} for {@code key}'s work scheduled before {@code newEpoch}. Pure
     * passthrough to {@link SoulGenerationScheduler#invalidate}; callers own deciding when a
     * conversation reset (e.g. {@link SoulStore#archiveAndReset}) has happened.
     */
    public void invalidate(SoulTypes.ConversationKey key, long newEpoch) {
        scheduler.invalidate(key, newEpoch);
    }

    public CompletableFuture<Submission> submit(SoulTypes.AcceptedTurn turn) {
        Objects.requireNonNull(turn, "turn");
        // Adopt the routing surface's id rather than minting a second one, so every [souls] log
        // line for this turn — routing, turn, knowledge, generation, delivery — joins on one id.
        UUID correlationId = turn.routingId();
        CompletableFuture<Submission> outcome = new CompletableFuture<>();
        Stages stages = new Stages();

        long heardStartNanos = System.nanoTime();
        store.beginHeardTurn(turn.key(), correlationId, turn.playerMessage(), turn.acceptedAt())
                .whenComplete((token, tokenError) -> {
                    stages.heardAppendMs = elapsedMs(heardStartNanos);
                    if (tokenError != null) {
                        // No token exists: there is nothing a FAILURE record could attach to.
                        LOGGER.info(
                                "[souls] turn correlationId={} bot={} player={} reachability={} "
                                        + "outcome=no-token heardAppendMs={} error={}",
                                correlationId, turn.key().botId(), turn.key().playerId(),
                                turn.grounding().reachability(), stages.heardAppendMs,
                                tokenError.getClass().getSimpleName());
                        delivery.deliverStatus(turn.key().playerId(),
                                statusFor(SoulTypes.FailureCode.INTERNAL, turn.botDisplayName()));
                        outcome.complete(Submission.FAILED);
                        return;
                    }
                    continueAfterHeard(turn, token, correlationId, stages, outcome);
                });

        return outcome;
    }

    private void continueAfterHeard(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                                     UUID correlationId, Stages stages,
                                     CompletableFuture<Submission> outcome) {
        CompletableFuture<List<SoulTypes.ConversationRecord>> historyFuture = store.recentBefore(
                token, SoulPromptAssembler.MAX_HISTORY_TURNS, SoulPromptAssembler.MAX_HISTORY_CHARS);
        CompletableFuture<List<SoulTypes.SoulEvent>> eventsFuture =
                store.recentEvents(turn.key().botId(), SoulPromptAssembler.EVENT_FETCH_WINDOW);

        recordKnowledgeObservations(turn);

        CompletableFuture<SoulTypes.KnowledgeMemory> memoryFuture =
                store.knowledgeMemory(turn.key().botId())
                        .exceptionally(ex -> SoulTypes.KnowledgeMemory.empty());

        historyFuture.thenCombine(eventsFuture, HistoryAndEvents::new)
                .thenCombine(memoryFuture, (he, memory) -> dispatchProvider(
                        turn, token, correlationId, he.history(), he.events(), memory, stages))
                .thenCompose(f -> f)
                .whenComplete((result, providerError) ->
                        handleProviderResult(turn, token, correlationId, result, providerError, stages, outcome));
    }

    /**
     * Persists this turn's line-of-sight facility sightings and, when the player's message reads
     * as a statement naming a known topic, the message itself as a told fact. Fire-and-forget:
     * memory writes are never load-bearing for the turn.
     */
    private void recordKnowledgeObservations(SoulTypes.AcceptedTurn turn) {
        try {
            long now = System.currentTimeMillis();
            String dimension = turn.grounding().bot().dimension();
            List<SoulTypes.KnownPlace> sightings = turn.grounding().situation().facilitySightings()
                    .stream()
                    .map(f -> new SoulTypes.KnownPlace(f.idPath(), dimension, f.x(), f.y(), f.z(), now))
                    .toList();
            if (!sightings.isEmpty()) {
                store.recordSightings(turn.key().botId(), sightings)
                        .exceptionally(ex -> null);
            }

            if (SoulKnowledgeMemoryOps.isStatement(turn.playerMessage())) {
                String teller = turn.grounding().player()
                        .map(SoulTypes.PlayerSnapshot::name).orElse("The player");
                for (String topic : SoulKnowledgeRetriever.matchTopics(
                        turn.playerMessage().toLowerCase(java.util.Locale.ROOT),
                        net.wcfcarolina13.GameAI.Knowledge.GameKnowledgeGraph.current())) {
                    store.recordToldFact(turn.key().botId(), topic,
                                    new SoulTypes.ToldFact(teller, turn.playerMessage(), now))
                            .exceptionally(ex -> null);
                }
            }
        } catch (Throwable ignored) {
            // Observation recording must never fail a turn.
        }
    }

    /** Assembles the bounded prompt and schedules exactly one provider generation call. */
    private CompletableFuture<SoulTypes.ProviderResult> dispatchProvider(
            SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, UUID correlationId,
            List<SoulTypes.ConversationRecord> history, List<SoulTypes.SoulEvent> events,
            SoulTypes.KnowledgeMemory memory, Stages stages) {
        SoulTypes.SoulProfile profile = SoulProfileRegistry.require(turn.profileId());
        List<String> relevantKnowledge = List.of();
        try {
            SoulTypes.BotSnapshot bot = turn.grounding().bot();
            // Deictic grounding: fold the player's look-target into topic matching so "what is
            // this / what are they good for" retrieves knowledge about the looked-at block.
            String lookTarget = turn.grounding().player()
                    .map(SoulTypes.PlayerSnapshot::lookingAt).orElse("");
            String matchText = lookTarget.isEmpty()
                    ? turn.playerMessage()
                    : turn.playerMessage() + " " + lookTarget;
            relevantKnowledge = SoulKnowledgeRetriever.retrieve(matchText,
                    new SoulKnowledgeRetriever.RetrievalContext(
                            bot.itemCounts(),
                            turn.grounding().situation().facilityIds(),
                            memory, bot.dimension(),
                            bot.coarseX(), bot.coarseY(), bot.coarseZ()),
                    net.wcfcarolina13.GameAI.Knowledge.GameKnowledgeGraph.current());
        } catch (Throwable ignored) {
            // Retrieval is additive grounding, never load-bearing for a turn.
        }
        if (!relevantKnowledge.isEmpty()) {
            // Prompt-side ground truth for field debugging: what retrieval actually injected.
            org.slf4j.LoggerFactory.getLogger("frens-souls").info(
                    "[souls] knowledge correlationId={} lines={}", correlationId, relevantKnowledge);
        }
        SoulTypes.ProviderRequest request = prompts.assemble(correlationId, settings.model(), profile,
                turn.grounding(), history, events, relevantKnowledge,
                turn.playerMessage(), settings.timeout());

        stages.queueDepthAtSubmit = scheduler.queueDepth();
        long scheduleStartNanos = System.nanoTime();
        return scheduler.submit(turn.key(), token.epoch(), () -> {
            // This supplier only runs once the job leaves the queue and actually dispatches, so
            // the gap between scheduling and here is genuine queue-wait time, never provider time.
            stages.queueWaitMs = elapsedMs(scheduleStartNanos);
            long providerStartNanos = System.nanoTime();
            SoulModelProvider.Call call = provider.generate(request);
            call.result().whenComplete((r, t) -> stages.providerMs = elapsedMs(providerStartNanos));
            return call;
        });
    }

    private void handleProviderResult(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                                       UUID correlationId, SoulTypes.ProviderResult result,
                                       Throwable providerError, Stages stages,
                                       CompletableFuture<Submission> outcome) {
        if (providerError != null) {
            // Covers history/events fetch failures, an unknown profile id, and any unexpected
            // scheduler/provider exception -- never surfaced verbatim to chat.
            failTurn(turn, token, correlationId, SoulTypes.FailureCode.INTERNAL, "", "", null, stages, outcome);
            return;
        }
        if (!result.success()) {
            failTurn(turn, token, correlationId, result.failureCode(), result.provider(), result.model(),
                    result.elapsedMillis(), stages, outcome);
            return;
        }

        long validationStartNanos = System.nanoTime();
        SoulResponseValidator.ValidationResult validation =
                validator.validate(result.text(), turn.botDisplayName());
        stages.validationMs = elapsedMs(validationStartNanos);

        if (!validation.accepted()) {
            failTurn(turn, token, correlationId, validation.failureCode(), result.provider(), result.model(),
                    result.elapsedMillis(), stages, outcome);
            return;
        }

        long deliveryStartNanos = System.nanoTime();
        delivery.deliverReply(turn, token, validation.text()).whenComplete((delivered, deliverError) -> {
            stages.deliveryMs = elapsedMs(deliveryStartNanos);
            if (deliverError != null || !Boolean.TRUE.equals(delivered)) {
                // The Delivery boundary intentionally reports only a boolean -- SoulMessageDelivery
                // logs the richer reason (guard outcome) itself; this service reports it generically.
                failTurn(turn, token, correlationId, SoulTypes.FailureCode.INTERNAL, result.provider(),
                        result.model(), result.elapsedMillis(), stages, outcome);
                return;
            }
            commitSpoken(turn, token, correlationId, validation.text(), result, stages, outcome);
        });
    }

    private void commitSpoken(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, UUID correlationId,
                               String text, SoulTypes.ProviderResult result, Stages stages,
                               CompletableFuture<Submission> outcome) {
        try {
            spokenListener.onSpoken(turn, token, text);
        } catch (RuntimeException ex) {
            LOGGER.warn("[souls] spoken listener threw; voice skipped: {}", ex.toString());
        }

        long commitStartNanos = System.nanoTime();
        store.appendSpoken(token, text, result)
                .thenCompose(v -> store.appendEvent(turn.key().botId(), directConversationEvent(turn)))
                .whenComplete((v, commitError) -> {
                    stages.spokenCommitMs = elapsedMs(commitStartNanos);
                    logTurn(correlationId, turn, token, stages, result.provider(), result.model(),
                            "delivered", commitError == null ? "" : commitError.getClass().getSimpleName());
                    // The private send already happened; a late persistence race here must not
                    // retroactively tell the player their message was never spoken.
                    outcome.complete(Submission.DELIVERED);
                });
    }

    private void failTurn(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token, UUID correlationId,
                           SoulTypes.FailureCode code, String providerId, String model, Long elapsedMillis,
                           Stages stages, CompletableFuture<Submission> outcome) {
        String status = statusFor(code, turn.botDisplayName());
        boolean tokenAlreadyStale =
                code == SoulTypes.FailureCode.CANCELLED || code == SoulTypes.FailureCode.STALE_EPOCH;
        if (tokenAlreadyStale) {
            // The conversation was reset mid-flight: this token's epoch no longer matches the
            // store's cursor, so SoulStore would itself refuse this append as stale. Skip it
            // rather than attempt-and-fail.
            delivery.deliverStatus(turn.key().playerId(), status);
            logTurn(correlationId, turn, token, stages, providerId, model, "failed:" + code, "");
            outcome.complete(Submission.FAILED);
            return;
        }
        store.appendFailure(token, code, providerId, model, elapsedMillis).whenComplete((v, appendError) -> {
            delivery.deliverStatus(turn.key().playerId(), status);
            logTurn(correlationId, turn, token, stages, providerId, model, "failed:" + code,
                    appendError == null ? "" : appendError.getClass().getSimpleName());
            outcome.complete(Submission.FAILED);
        });
    }

    private SoulTypes.SoulEvent directConversationEvent(SoulTypes.AcceptedTurn turn) {
        // Content-free by design: this witnesses that a direct conversation happened, never what
        // was said. worldTick is unavailable at this layer (SoulConversationService touches no
        // Minecraft/Fabric state) and is left at 0; occurredAt carries the real timestamp.
        return new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.DIRECT_CONVERSATION,
                turn.key().botId(), List.of(turn.key().playerId()), turn.grounding().bot().dimension(),
                turn.grounding().bot().biome(), Map.of(), SoulTypes.Witness.SELF, 0L, Instant.now(),
                SoulTypes.Salience.NORMAL);
    }

    // === Deterministic, provider-detail-free status text (never a raw provider message) ===

    private static String statusFor(SoulTypes.FailureCode code, String botDisplayName) {
        String name = (botDisplayName == null || botDisplayName.isBlank()) ? "The bot" : botDisplayName;
        return switch (code) {
            case OVERLOADED -> name + " is tied up answering something else. Try again in a moment.";
            case TIMEOUT -> name + " didn't answer in time.";
            case UNAVAILABLE -> name + "'s local conversation model is unavailable.";
            case MALFORMED -> name + " couldn't form a usable reply.";
            case CANCELLED, STALE_EPOCH -> "The conversation changed before " + name + " could answer.";
            default -> name + " couldn't answer because Frens hit an internal error.";
        };
    }

    // === Logging: INFO only, correlation/identity/timing metadata -- never prompt/dialogue text ===

    private void logTurn(UUID correlationId, SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                          Stages stages, String providerId, String model, String outcome, String note) {
        LOGGER.info(
                "[souls] turn correlationId={} bot={} player={} reachability={} epoch={} sequence={} "
                        + "queueDepth={} provider={} model={} outcome={} note={} heardAppendMs={} "
                        + "queueWaitMs={} providerMs={} validationMs={} deliveryMs={} spokenCommitMs={}",
                correlationId, turn.key().botId(), turn.key().playerId(), turn.grounding().reachability(),
                token.epoch(), token.sequence(), stages.queueDepthAtSubmit, providerId, model, outcome, note,
                stages.heardAppendMs, stages.queueWaitMs, stages.providerMs, stages.validationMs,
                stages.deliveryMs, stages.spokenCommitMs);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private record HistoryAndEvents(List<SoulTypes.ConversationRecord> history, List<SoulTypes.SoulEvent> events) {
    }

    /** Mutable per-submit timing/metadata accumulator for the single combined INFO log line. */
    private static final class Stages {
        volatile int queueDepthAtSubmit;
        volatile long heardAppendMs;
        volatile long queueWaitMs;
        volatile long providerMs;
        volatile long validationMs;
        volatile long deliveryMs;
        volatile long spokenCommitMs;
    }
}
