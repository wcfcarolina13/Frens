package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Exclusive chat-routing gate: decides whether an already-resolved single-target bot DM belongs
 * to the soul-communication pilot instead of the legacy {@code LLMOrchestrator} path, and — when
 * it does — drives the turn end to end through {@link SoulRuntime#submitTurn}.
 *
 * <p>{@link #decide} is the pure, unit-tested decision table: it answers only "does this turn
 * belong exclusively to souls" ({@link RouteOutcome#CONSUMED}) or "leave it to legacy routing"
 * ({@link RouteOutcome#NOT_SOUL}) from the coarse enablement facts (master switch, index
 * readiness, cached profile activation). It deliberately ignores {@code pipelineAvailable},
 * {@code authorized}, and {@code reachability} for that coarse call — once a turn is enabled and
 * bound, it is always exclusively consumed by souls, even when the fine-grained checks below end
 * up sending a deterministic refusal instead of a real reply. Those three parameters exist on the
 * signature because the same fine-grained facts are what {@link #tryRoute} evaluates next, in a
 * fixed order, to choose which deterministic notice (or a real submission) applies.
 *
 * <p>{@link #tryRoute} performs, in order: runtime/master check, index-readiness check, cached
 * profile check, pipeline-availability check, exact authorization
 * ({@link CompanionCommunicationPolicy#isPrivateSoulAuthorized}), reachability
 * ({@link CompanionCommunicationPolicy#classifySoulReachability}), server-thread snapshot capture
 * ({@link SoulSnapshotBuilder#capture}), and finally accepted-turn submission
 * ({@link SoulRuntime#submitTurn}). The four deterministic notices (loading / invalid pipeline /
 * unauthorized / unreachable) are sent directly to the player and return {@link RouteOutcome#CONSUMED}
 * without ever appending conversation history or invoking a provider — only a turn that clears
 * every gate reaches snapshot capture and submission.
 *
 * <p>Routing decisions are logged at INFO via the shared {@code frens.souls} logger with a
 * router-generated correlation id, identity (bot/player uuids), the resolved reachability, the
 * outcome, and per-stage durations -- never the player's message content. The same
 * {@code routingId} travels inside the {@link SoulTypes.AcceptedTurn} and is adopted by
 * {@link SoulConversationService#submit} as the turn's correlation id, so this router's
 * {@code [souls] routing} line and the downstream {@code [souls] turn}/{@code knowledge}/
 * {@code delivery} lines all join on one id.
 */
public final class SoulChatRouter {

    // A dedicated logger (never Frens.LOGGER) -- see the package-wide convention documented on
    // SoulRuntime/SoulConversationService.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    public enum RouteOutcome { NOT_SOUL, CONSUMED }

    private SoulChatRouter() {
    }

    /**
     * Pure coarse decision: does this turn belong exclusively to souls at all? Only
     * {@code masterEnabled}, {@code indexReady}, and {@code profileActive} affect the outcome --
     * see the class Javadoc for why {@code pipelineAvailable}/{@code authorized}/
     * {@code reachability} are still part of this exact signature despite not being consulted
     * here.
     *
     * <ul>
     *   <li>Master switch off &rarr; {@link RouteOutcome#NOT_SOUL} (legacy routing untouched).</li>
     *   <li>Master on but the index hasn't finished its initial load &rarr;
     *       {@link RouteOutcome#CONSUMED} (the caller sends a LOADING notice -- profile activation
     *       is unknown until the index is warm, so this is deliberately checked before
     *       {@code profileActive}).</li>
     *   <li>Master on, index ready, but this bot has no active bound profile &rarr;
     *       {@link RouteOutcome#NOT_SOUL} ("unbound" -- legacy routing untouched).</li>
     *   <li>Master on, index ready, profile active &rarr; {@link RouteOutcome#CONSUMED}
     *       unconditionally -- an unauthorized or unreachable turn is still exclusively consumed
     *       (with its own deterministic refusal), never silently handed to legacy routing.</li>
     * </ul>
     */
    public static RouteOutcome decide(boolean masterEnabled, boolean indexReady, boolean profileActive,
                                       boolean pipelineAvailable, boolean authorized,
                                       SoulTypes.Reachability reachability) {
        if (!masterEnabled) {
            return RouteOutcome.NOT_SOUL;
        }
        if (!indexReady) {
            return RouteOutcome.CONSUMED;
        }
        if (!profileActive) {
            return RouteOutcome.NOT_SOUL;
        }
        return RouteOutcome.CONSUMED;
    }

    /**
     * True only for an explicit single-bot chat address, never a broadcast keyword
     * ("bots"/"all bots") that happens to resolve to exactly one registered bot. The chat-target
     * resolver's list size alone cannot distinguish the two -- a server with one bot registered
     * produces a size-1 target list for both "Jake how are you" and "bots how are you" -- so the
     * caller must pass the resolver's own broadcast flag (computed where the keyword list already
     * lives), never re-derive it from the count. {@code bots}/{@code all bots} must never be
     * routed to souls; only an explicitly-named single bot may be.
     */
    public static boolean isSingleBotAddress(int routedBotCount, boolean broadcastKeyword) {
        return routedBotCount == 1 && !broadcastKeyword;
    }

    /**
     * Pure eligibility gate for the private-whisper surface ({@code /msg} and its aliases
     * {@code /tell} / {@code /w}): should an incoming whisper delivered to a fake player be
     * handed to {@link #tryRoute} at all? All four facts must hold: the message really is a
     * {@code MSG_COMMAND_INCOMING} delivery (never ordinary broadcast chat, which already routes
     * through the public chat callback), the sending player was resolved live (console and
     * command-block whispers have no player to converse with), the sender is not itself a bot
     * (no bot-to-bot soul loops), and the whisper has non-blank content. A whisper that fails
     * this gate — or that {@link #tryRoute} then answers with {@link RouteOutcome#NOT_SOUL} —
     * stays a plain vanilla whisper with no soul side effects.
     */
    public static boolean isWhisperEligible(boolean incomingMsgCommand, boolean senderKnown,
                                             boolean senderIsBot, String content) {
        return incomingMsgCommand && senderKnown && !senderIsBot
                && content != null && !content.isBlank();
    }

    /** The first of {@link #tryRoute}'s fixed-order gates that is closed, or {@link Gate#OPEN}. */
    public enum Gate { NOT_SOUL, LOADING, INVALID_PIPELINE, UNAUTHORIZED, UNREACHABLE, OPEN }

    /**
     * Pure gate order shared by {@link #tryRoute} and {@link #tryRouteSilently}, so the two can
     * never drift: the coarse {@link #decide} (master switch, index readiness, cached profile),
     * then index readiness, pipeline availability, exact authorization, and reachability. The
     * suppliers are consulted lazily, in that order, only once every earlier gate is open —
     * authorization and reachability read live world state and are timed by the caller.
     */
    public static Gate firstClosedGate(boolean masterEnabled, boolean indexReady, boolean profileActive,
                                       BooleanSupplier pipelineAvailable, BooleanSupplier authorized,
                                       Supplier<SoulTypes.Reachability> reachability) {
        // pipelineAvailable/authorized/reachability don't affect decide's outcome (see its
        // Javadoc) -- LOCAL/false/false are safe placeholders there.
        if (decide(masterEnabled, indexReady, profileActive, false, false, SoulTypes.Reachability.LOCAL)
                == RouteOutcome.NOT_SOUL) {
            return Gate.NOT_SOUL;
        }
        if (!indexReady) {
            return Gate.LOADING;
        }
        if (!pipelineAvailable.getAsBoolean()) {
            return Gate.INVALID_PIPELINE;
        }
        if (!authorized.getAsBoolean()) {
            return Gate.UNAUTHORIZED;
        }
        if (reachability.get() == SoulTypes.Reachability.UNREACHABLE) {
            return Gate.UNREACHABLE;
        }
        return Gate.OPEN;
    }

    /**
     * Attempts to route one already-resolved single-target DM through the soul-communication
     * pilot. Returns {@link RouteOutcome#NOT_SOUL} the instant the coarse {@link #decide} check
     * says legacy routing should handle it (no notice sent, nothing logged beyond that check being
     * cheap synchronous/cached reads); returns {@link RouteOutcome#CONSUMED} in every other case,
     * having already sent exactly one deterministic notice or submitted exactly one turn.
     */
    public static RouteOutcome tryRoute(ServerPlayerEntity bot, ServerPlayerEntity sender, String prompt) {
        return route(bot, sender, prompt, false);
    }

    /**
     * Silent variant for a line the player did not explicitly address to a bot (the DM follow-up
     * window, addressee rule R1): submits exactly as {@link #tryRoute} does when every gate is
     * open, and otherwise returns {@link RouteOutcome#NOT_SOUL} with no notice and no routing log
     * line, so the caller's ordinary unaddressed handling runs unchanged. Same gate evaluation as
     * {@link #tryRoute} ({@link #firstClosedGate}).
     */
    public static RouteOutcome tryRouteSilently(ServerPlayerEntity bot, ServerPlayerEntity sender, String prompt) {
        return route(bot, sender, prompt, true);
    }

    /**
     * Routes an unaddressed line through an open DM follow-up window ({@link #tryRouteSilently})
     * and, when it is consumed, logs one content-free INFO line.
     *
     * @param windowAgeMs how long ago the window opened, for the log line only
     */
    public static RouteOutcome routeDmFollowUp(ServerPlayerEntity bot, ServerPlayerEntity sender, String line,
                                               long windowAgeMs) {
        RouteOutcome outcome = tryRouteSilently(bot, sender, line);
        if (outcome == RouteOutcome.CONSUMED) {
            LOGGER.info("[souls] dm follow-up routed player={} bot={} ageMs={}",
                    sender.getName().getString(), bot.getName().getString(), windowAgeMs);
        }
        return outcome;
    }

    private static RouteOutcome route(ServerPlayerEntity bot, ServerPlayerEntity sender, String prompt,
                                      boolean silent) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(sender, "sender");
        String safePrompt = prompt == null ? "" : prompt;

        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            return RouteOutcome.NOT_SOUL;
        }
        SoulRuntime runtime = maybeRuntime.get();

        UUID routingId = UUID.randomUUID();
        long routeStartNanos = System.nanoTime();

        // Gates in fixed order; authorization and reachability are timed as they are evaluated.
        long[] authorizationMs = {0L};
        long[] reachabilityMs = {0L};
        SoulTypes.Reachability[] reachability = {null};
        Gate gate = firstClosedGate(runtime.isMasterEnabled(), runtime.isReady(),
                runtime.hasActiveProfile(bot.getUuid()),
                runtime::pipelineAvailable,
                () -> {
                    long authStartNanos = System.nanoTime();
                    boolean authorized = CompanionCommunicationPolicy.isPrivateSoulAuthorized(sender, bot);
                    authorizationMs[0] = elapsedMs(authStartNanos);
                    return authorized;
                },
                () -> {
                    long reachStartNanos = System.nanoTime();
                    reachability[0] = CompanionCommunicationPolicy.classifySoulReachability(bot, sender);
                    reachabilityMs[0] = elapsedMs(reachStartNanos);
                    return reachability[0];
                });
        if (gate == Gate.NOT_SOUL) {
            return RouteOutcome.NOT_SOUL;
        }
        if (silent && gate != Gate.OPEN) {
            return RouteOutcome.NOT_SOUL;
        }

        switch (gate) {
            case LOADING -> {
                logRouting(routingId, bot, sender, "loading", null, routeStartNanos, 0L, 0L, 0L);
                sendNotice(sender, bot.getName().getString() + "'s conversation memory is still loading. Try again in a moment.");
                return RouteOutcome.CONSUMED;
            }
            case INVALID_PIPELINE -> {
                logRouting(routingId, bot, sender, "invalid-pipeline", null, routeStartNanos, 0L, 0L, 0L);
                sendNotice(sender, bot.getName().getString() + "'s local conversation model is not ready: " + runtime.safeValidationError());
                return RouteOutcome.CONSUMED;
            }
            case UNAUTHORIZED -> {
                logRouting(routingId, bot, sender, "unauthorized", null, routeStartNanos, authorizationMs[0], 0L, 0L);
                sendNotice(sender, bot.getName().getString() + "'s private conversation is available only to their owner or an operator.");
                return RouteOutcome.CONSUMED;
            }
            case UNREACHABLE -> {
                logRouting(routingId, bot, sender, "unreachable", reachability[0], routeStartNanos, authorizationMs[0],
                        reachabilityMs[0], 0L);
                sendNotice(sender, "You cannot reach " + bot.getName().getString() + " from here.");
                return RouteOutcome.CONSUMED;
            }
            default -> {
                // OPEN: fall through to submission.
            }
        }

        MinecraftServer server = bot.getEntityWorld().getServer();
        if (server == null && silent) {
            return RouteOutcome.NOT_SOUL;
        }
        if (server == null) {
            // Defensive only: the chat callback this is invoked from always runs on a live server
            // thread with a registered bot. No deterministic-notice text is specified for this
            // case in the brief, so fail closed the same way an unreachable turn does rather than
            // fabricate a new message.
            logRouting(routingId, bot, sender, "no-server", reachability[0], routeStartNanos, authorizationMs[0],
                    reachabilityMs[0], 0L);
            sendNotice(sender, "You cannot reach " + bot.getName().getString() + " from here.");
            return RouteOutcome.CONSUMED;
        }

        long snapshotStartNanos = System.nanoTime();
        SoulTypes.GroundingSnapshot grounding = SoulSnapshotBuilder.capture(server, bot, sender, reachability[0]);
        long snapshotMs = elapsedMs(snapshotStartNanos);

        String profileId = runtime.cachedState(bot.getUuid()).map(SoulTypes.SoulState::profileId).orElse("");
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(bot.getUuid(), sender.getUuid(), SoulTypes.Channel.DIRECT);
        SoulTypes.AcceptedTurn turn = new SoulTypes.AcceptedTurn(key, bot.getName().getString(),
                sender.getName().getString(), safePrompt, profileId, grounding, Instant.now(), routingId);

        logRouting(routingId, bot, sender, "submitted", reachability[0], routeStartNanos, authorizationMs[0],
                reachabilityMs[0], snapshotMs, grounding);
        // SoulRuntime#submitTurn records this routingId as the player's newest DM and opens the
        // DM follow-up window when this exact turn's reply is delivered.
        runtime.submitTurn(turn);
        return RouteOutcome.CONSUMED;
    }

    private static void sendNotice(ServerPlayerEntity sender, String text) {
        sender.sendMessage(Text.literal(text), false);
    }

    private static void logRouting(UUID routingId, ServerPlayerEntity bot, ServerPlayerEntity sender,
                                    String outcome, SoulTypes.Reachability reachability, long routeStartNanos,
                                    long authorizationMs, long reachabilityMs, long snapshotMs) {
        logRouting(routingId, bot, sender, outcome, reachability, routeStartNanos, authorizationMs,
                reachabilityMs, snapshotMs, null);
    }

    /**
     * Extends the routing line with content-free ground-truth facts pulled straight from the
     * captured snapshot -- {@code mode}, {@code following}, {@code sky}, {@code hostiles}, and
     * {@code animals} -- so a field test can check what the pipeline actually saw (e.g. "was the
     * bot really in FOLLOW mode when it missed the follow cue", or "did capture actually see the
     * parrot on the shoulder") directly in {@code latest.log} without touching player message
     * content. {@code hostiles}/{@code animals} are type-only names (species, "wolf (Rex)",
     * "parrot (on your shoulder)") -- game facts, never private chat text -- and the animals list
     * is capped at 6 rendered entries defensively, though the captured list itself is already
     * capped at 4 (plus up to 2 shoulder pets) upstream. {@code grounding} is {@code null} for
     * every outcome logged before snapshot capture (loading / invalid-pipeline / unauthorized /
     * unreachable / no-server); those facts are unknown yet, so the fields render as their neutral
     * defaults ({@code mode=} empty, {@code false}s, {@code hostiles=0}, {@code animals=[]}).
     */
    private static void logRouting(UUID routingId, ServerPlayerEntity bot, ServerPlayerEntity sender,
                                    String outcome, SoulTypes.Reachability reachability, long routeStartNanos,
                                    long authorizationMs, long reachabilityMs, long snapshotMs,
                                    SoulTypes.GroundingSnapshot grounding) {
        String mode = grounding != null ? grounding.situation().behaviorMode() : "";
        boolean following = grounding != null && grounding.situation().following();
        boolean sky = grounding != null && grounding.bot().skyVisible();
        int hostileCount = grounding != null ? grounding.situation().hostiles().size() : 0;
        String animals = grounding != null
                ? grounding.situation().nearbyAnimals().stream().limit(6).collect(Collectors.joining(", "))
                : "";
        LOGGER.info(
                "[souls] routing routingId={} bot={} player={} reachability={} outcome={} routingMs={} "
                        + "authorizationMs={} reachabilityMs={} snapshotMs={} mode={} following={} sky={} "
                        + "hostiles={} animals=[{}]",
                routingId, bot.getUuid(), sender.getUuid(), reachability, outcome, elapsedMs(routeStartNanos),
                authorizationMs, reachabilityMs, snapshotMs, mode, following, sky, hostileCount, animals);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
