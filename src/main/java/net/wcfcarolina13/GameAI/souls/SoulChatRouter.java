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
 * outcome, and per-stage durations -- never the player's message content. The submit-stage
 * generation/delivery turn mints its own, separate correlation id inside
 * {@link SoulConversationService#submit}; that id is logged by {@link SoulConversationService}
 * itself, since {@link SoulRuntime#submitTurn}'s {@code Submission} return value does not expose
 * it back to this router.
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
     * Attempts to route one already-resolved single-target DM through the soul-communication
     * pilot. Returns {@link RouteOutcome#NOT_SOUL} the instant the coarse {@link #decide} check
     * says legacy routing should handle it (no notice sent, nothing logged beyond that check being
     * cheap synchronous/cached reads); returns {@link RouteOutcome#CONSUMED} in every other case,
     * having already sent exactly one deterministic notice or submitted exactly one turn.
     */
    public static RouteOutcome tryRoute(ServerPlayerEntity bot, ServerPlayerEntity sender, String prompt) {
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

        boolean masterEnabled = runtime.isMasterEnabled();
        boolean indexReady = runtime.isReady();
        boolean profileActive = runtime.hasActiveProfile(bot.getUuid());

        // Coarse gate first: cheap synchronous/cached reads only, no Minecraft-world scanning.
        // pipelineAvailable/authorized/reachability don't affect this outcome (see decide's
        // Javadoc) -- LOCAL/false/false are safe placeholders here, never consulted by decide.
        RouteOutcome coarse = decide(masterEnabled, indexReady, profileActive, false, false,
                SoulTypes.Reachability.LOCAL);
        if (coarse == RouteOutcome.NOT_SOUL) {
            return RouteOutcome.NOT_SOUL;
        }

        if (!indexReady) {
            logRouting(routingId, bot, sender, "loading", null, routeStartNanos, 0L, 0L, 0L);
            sendNotice(sender, "Jake's conversation memory is still loading. Try again in a moment.");
            return RouteOutcome.CONSUMED;
        }

        boolean pipelineAvailable = runtime.pipelineAvailable();
        if (!pipelineAvailable) {
            logRouting(routingId, bot, sender, "invalid-pipeline", null, routeStartNanos, 0L, 0L, 0L);
            sendNotice(sender, "Jake's local conversation model is not ready: " + runtime.safeValidationError());
            return RouteOutcome.CONSUMED;
        }

        long authStartNanos = System.nanoTime();
        boolean authorized = CompanionCommunicationPolicy.isPrivateSoulAuthorized(sender, bot);
        long authorizationMs = elapsedMs(authStartNanos);
        if (!authorized) {
            logRouting(routingId, bot, sender, "unauthorized", null, routeStartNanos, authorizationMs, 0L, 0L);
            sendNotice(sender, "Jake's private conversation is available only to his owner or an operator.");
            return RouteOutcome.CONSUMED;
        }

        long reachStartNanos = System.nanoTime();
        SoulTypes.Reachability reachability = CompanionCommunicationPolicy.classifySoulReachability(bot, sender);
        long reachabilityMs = elapsedMs(reachStartNanos);
        if (reachability == SoulTypes.Reachability.UNREACHABLE) {
            logRouting(routingId, bot, sender, "unreachable", reachability, routeStartNanos, authorizationMs,
                    reachabilityMs, 0L);
            sendNotice(sender, "You cannot reach Jake from here.");
            return RouteOutcome.CONSUMED;
        }

        MinecraftServer server = bot.getEntityWorld().getServer();
        if (server == null) {
            // Defensive only: the chat callback this is invoked from always runs on a live server
            // thread with a registered bot. No deterministic-notice text is specified for this
            // case in the brief, so fail closed the same way an unreachable turn does rather than
            // fabricate a new message.
            logRouting(routingId, bot, sender, "no-server", reachability, routeStartNanos, authorizationMs,
                    reachabilityMs, 0L);
            sendNotice(sender, "You cannot reach Jake from here.");
            return RouteOutcome.CONSUMED;
        }

        long snapshotStartNanos = System.nanoTime();
        SoulTypes.GroundingSnapshot grounding = SoulSnapshotBuilder.capture(server, bot, sender, reachability);
        long snapshotMs = elapsedMs(snapshotStartNanos);

        String profileId = runtime.cachedState(bot.getUuid()).map(SoulTypes.SoulState::profileId).orElse("");
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(bot.getUuid(), sender.getUuid(), SoulTypes.Channel.DIRECT);
        SoulTypes.AcceptedTurn turn = new SoulTypes.AcceptedTurn(key, bot.getName().getString(),
                sender.getName().getString(), safePrompt, profileId, grounding, Instant.now());

        logRouting(routingId, bot, sender, "submitted", reachability, routeStartNanos, authorizationMs,
                reachabilityMs, snapshotMs);
        runtime.submitTurn(turn);
        return RouteOutcome.CONSUMED;
    }

    private static void sendNotice(ServerPlayerEntity sender, String text) {
        sender.sendMessage(Text.literal(text), false);
    }

    private static void logRouting(UUID routingId, ServerPlayerEntity bot, ServerPlayerEntity sender,
                                    String outcome, SoulTypes.Reachability reachability, long routeStartNanos,
                                    long authorizationMs, long reachabilityMs, long snapshotMs) {
        LOGGER.info(
                "[souls] routing routingId={} bot={} player={} reachability={} outcome={} routingMs={} "
                        + "authorizationMs={} reachabilityMs={} snapshotMs={}",
                routingId, bot.getUuid(), sender.getUuid(), reachability, outcome, elapsedMs(routeStartNanos),
                authorizationMs, reachabilityMs, snapshotMs);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
