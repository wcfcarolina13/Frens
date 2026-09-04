package net.wcfcarolina13.GameAI.souls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Consolidates each bot's recent transcripts into durable {@code playerMemories} (spec
 * 2026-09-04 §6).
 *
 * <p>One {@link SoulGenerationScheduler} submission per (bot, player) pair, keyed
 * {@code (botId, playerId, SYSTEM)} so it queues FIFO behind live scenes and can never collide
 * with an active DM turn. Every gathering, validation and merge rule lives in the pure
 * {@link SoulMemoryDigestOps}; this class only sequences I/O, the provider call and the single
 * {@code mind.json} write per pair.
 *
 * <p>Digests for one bot are chained strictly one after another, so two {@code updateMind} writes
 * can never interleave on the same mind. The cursor advances on <em>every</em> outcome except
 * {@code too-few} (nothing was consumed) and {@code disabled} (nothing ran): re-digesting the same
 * lines tomorrow would not change the answer, and a persistent provider outage must not accumulate
 * an ever-growing backlog.
 */
public final class SoulMemoryDigestService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-souls");

    /** Bounded so a runaway model cannot spend a whole generation slot on the clerk prompt. */
    static final int MAX_OUTPUT_TOKENS = 200;

    private final SoulStore store;
    private final SoulStore partyStore;
    private final SoulGenerationScheduler scheduler;
    private final SoulModelProvider provider;
    private final String model;
    private final Duration timeout;
    private final BooleanSupplier enabled;

    public SoulMemoryDigestService(SoulStore store, SoulStore partyStore,
                                   SoulGenerationScheduler scheduler, SoulModelProvider provider,
                                   String model, Duration timeout, BooleanSupplier enabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.partyStore = Objects.requireNonNull(partyStore, "partyStore");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = model == null ? "" : model;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /**
     * Digests every DM and party transcript {@code botId} has with a currently-named player.
     *
     * <p>Runs entirely off the server thread; the returned future completes once every
     * (bot, player) digest has finished, successfully or not. Players absent from
     * {@code playerNames} are skipped whole — the clerk prompt is written around the player's
     * name, so an unnamed (offline) player is left for a later day with its cursor untouched.
     */
    public CompletableFuture<Void> digest(UUID botId, String botName, int day,
                                          Map<UUID, String> playerNames) {
        Objects.requireNonNull(botId, "botId");
        if (!enabled.getAsBoolean()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<UUID, String> names = playerNames == null ? Map.of() : Map.copyOf(playerNames);
        String bot = botName == null ? "" : botName;

        return store.conversationPlayers(botId)
                .thenCompose(players -> chain(players, playerId -> {
                    String name = names.get(playerId);
                    if (name == null || name.isBlank()) {
                        return null;
                    }
                    return digestOne(store, botId, bot, day,
                            new SoulTypes.ConversationKey(botId, playerId, SoulTypes.Channel.DIRECT),
                            playerId, name, false);
                }))
                .thenCompose(ignored -> partyStore.botDirectories())
                .thenCompose(owners -> chain(owners, ownerId -> {
                    // With a shared store instance (the runtime's test seam) this listing also
                    // contains DM bot directories; only owners with a known name are party owners
                    // worth digesting, which filters those out.
                    String name = names.get(ownerId);
                    if (name == null || name.isBlank()) {
                        return null;
                    }
                    return digestOne(partyStore, botId, bot, day,
                            SoulGroupTypes.partyKey(ownerId), ownerId, name, true);
                }))
                .handle((ignored, error) -> {
                    if (error != null) {
                        LOGGER.warn("[souls] memory digest sweep failed bot={} : {}", botId,
                                describe(error));
                    }
                    return null;
                });
    }

    /** Unwraps a {@link java.util.concurrent.CompletionException} to the class + message that caused it. */
    private static String describe(Throwable error) {
        Throwable cause = error.getCause() != null
                && error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    /** Runs {@code step} over {@code ids} strictly one after another; a null step is skipped. */
    private static CompletableFuture<Void> chain(
            List<UUID> ids, java.util.function.Function<UUID, CompletableFuture<Void>> step) {
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        for (UUID id : ids) {
            tail = tail.thenCompose(ignored -> {
                CompletableFuture<Void> next = step.apply(id);
                return next == null ? CompletableFuture.completedFuture(null) : next;
            });
        }
        return tail;
    }

    /**
     * One (bot, player) digest: read the cursor, gather, generate, then write memories and the
     * new cursor in a single {@code updateMind}. Never completes exceptionally — a failure here
     * must not abort the rest of the bot's chain.
     */
    private CompletableFuture<Void> digestOne(SoulStore records, UUID botId, String botName, int day,
                                              SoulTypes.ConversationKey key, UUID playerId,
                                              String playerName, boolean party) {
        String cursorKey = SoulStore.cursorKey(key);
        // Cursors live in the bot's own mind.json even for party material, which is read from the
        // separate party store.
        return store.mind(botId)
                .thenCompose(mind -> {
                    SoulTypes.ConversationCursor from = SoulMemoryDigestOps.cursorFor(mind, cursorKey);
                    return records.recordsSince(key, from).thenCompose(since -> {
                        SoulMemoryDigestOps.Material material = SoulMemoryDigestOps.gather(
                                since, from, botId, botName, playerName, party);
                        if (material.playerLines() < SoulMemoryDigestOps.MIN_PLAYER_LINES) {
                            log(botId, playerId, key, "too-few", 0, material.playerLines());
                            return CompletableFuture.completedFuture(null);
                        }
                        return generate(botId, botName, day, key, playerId, playerName, cursorKey,
                                material);
                    });
                })
                .handle((ignored, error) -> {
                    if (error != null) {
                        log(botId, playerId, key, "error:INTERNAL", 0, 0);
                    }
                    return null;
                });
    }

    private CompletableFuture<Void> generate(UUID botId, String botName, int day,
                                             SoulTypes.ConversationKey key, UUID playerId,
                                             String playerName, String cursorKey,
                                             SoulMemoryDigestOps.Material material) {
        UUID correlationId = UUID.randomUUID();
        SoulTypes.ProviderRequest request = buildRequest(correlationId, model, botName, playerName,
                material.text(), timeout);
        SoulTypes.ConversationKey schedulerKey =
                new SoulTypes.ConversationKey(botId, playerId, SoulTypes.Channel.SYSTEM);
        List<UUID> sources = sources(material);

        return scheduler.submit(schedulerKey, 0L, () -> provider.generate(request))
                .handle((result, error) -> Outcome.of(result, error, playerName))
                .thenCompose(outcome -> {
                    if (outcome.skipWrite()) {
                        log(botId, playerId, key, outcome.label(), 0, material.playerLines());
                        return CompletableFuture.completedFuture((Void) null);
                    }
                    return store.updateMind(botId, mind -> SoulMemoryDigestOps.withCursor(
                                    SoulMindOps.withPlayerMemories(mind, SoulMemoryDigestOps.merge(
                                            mind.playerMemories(), playerId, outcome.facts(), day, sources)),
                                    cursorKey, material.next()))
                            .thenAccept(ignored -> log(botId, playerId, key, outcome.label(),
                                    outcome.facts().size(), material.playerLines()));
                });
    }

    /** Distinct correlation ids of the player lines this material was built from. */
    private static List<UUID> sources(SoulMemoryDigestOps.Material material) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (SoulTypes.ConversationRecord record : material.records()) {
            if (record.kind() == SoulTypes.TurnKind.HEARD) {
                ids.add(record.correlationId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The provider's answer reduced to a log label plus the validated facts. A {@code success=false}
     * result is a typed provider failure; an exceptional future is an unexpected internal one.
     * Both advance the cursor with zero facts, except {@code CANCELLED} / {@code STALE_EPOCH}: the
     * scheduler never actually ran the job (or ran a superseded one), so — like {@code too-few} —
     * nothing was consumed and the cursor is left untouched for a later attempt.
     */
    private record Outcome(List<String> facts, String label, boolean skipWrite) {

        static Outcome of(SoulTypes.ProviderResult result, Throwable error, String playerName) {
            if (error != null || result == null) {
                return new Outcome(List.of(), "provider-failed:INTERNAL", false);
            }
            if (!result.success()) {
                SoulTypes.FailureCode code = result.failureCode();
                if (code == SoulTypes.FailureCode.CANCELLED) {
                    return new Outcome(List.of(), "cancelled", true);
                }
                if (code == SoulTypes.FailureCode.STALE_EPOCH) {
                    return new Outcome(List.of(), "stale-epoch", true);
                }
                return new Outcome(List.of(), "provider-failed:"
                        + (code == null ? "INTERNAL" : code.name()), false);
            }
            List<String> facts = SoulMemoryDigestOps.validate(result.text(), playerName);
            return new Outcome(facts, facts.isEmpty() ? "none" : "ok", false);
        }
    }

    /**
     * The clerk contract (spec §6, verbatim) plus the rendered transcript. Deliberately not the
     * bot's persona: this call summarizes, it never speaks in character.
     */
    static SoulTypes.ProviderRequest buildRequest(UUID correlationId, String model, String botName,
                                                  String playerName, String material,
                                                  Duration timeout) {
        String system = "You are a memory clerk for " + botName + ", a Minecraft companion. Read the"
                + " transcript and list the things about " + playerName + " that " + botName
                + " should remember later: plans, preferences, promises, names " + playerName
                + " uses for places or things, and how " + playerName + " feels."
                + " Rules: at most " + SoulMemoryDigestOps.MAX_FACTS + " lines; each line starts"
                + " with \"- \" and is under " + SoulMemoryDigestOps.MAX_FACT_CHARS + " characters;"
                + " write in third person using the name " + playerName + "; only what " + playerName
                + " actually said or clearly implied; no world facts, no advice, no quotes."
                + " If nothing is worth remembering, reply with exactly \"- none\".";
        List<SoulTypes.Message> messages = new ArrayList<>(2);
        messages.add(new SoulTypes.Message(SoulTypes.Role.SYSTEM, system));
        messages.add(new SoulTypes.Message(SoulTypes.Role.USER, material));
        return new SoulTypes.ProviderRequest(correlationId, model, messages, timeout, MAX_OUTPUT_TOKENS);
    }

    /** Counts and outcomes only -- never transcript text or model output. */
    private static void log(UUID botId, UUID playerId, SoulTypes.ConversationKey key,
                            String outcome, int kept, int lines) {
        LOGGER.info("[souls] memory digest bot={} player={} channel={} outcome={} kept={} lines={}",
                botId, playerId, key.channel(), outcome, kept, lines);
    }
}
