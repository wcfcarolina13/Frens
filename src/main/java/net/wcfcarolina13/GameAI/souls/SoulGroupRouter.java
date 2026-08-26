package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Group-scene routing gate for broadcast ("bots ...") and multi-name ("Jake and Sara, ...") chat
 * addresses: decides whether the address becomes a soul PARTY scene, downgrades to an ordinary
 * DM (exactly one eligible bot), or stays with legacy routing — and, for a scene, builds the
 * fresh per-turn roster (spec D2: the speaker's own soul-bound bots, LOCAL only) with per-bot
 * grounding snapshots and submits the {@link SoulGroupTypes.GroupSceneTurn}.
 *
 * <p>{@link #eligibleRoster} and {@link #decide} are the pure, unit-tested projections;
 * {@link #tryRoute} is the live surface invoked from {@code Frens}' chat callback. The party
 * kill switch ({@code ManualConfig.isSoulPartyEnabled}) is injected by the caller — this package
 * never references {@code Frens} (static-init rule, see {@link SoulChatRouter}).
 */
public final class SoulGroupRouter {

    // Dedicated logger, never Frens.LOGGER — same package rule as SoulChatRouter.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    public enum RouteOutcome { NOT_SOUL, CONSUMED, DOWNGRADE_TO_DM }

    /** One candidate bot's routing-relevant facts, resolved live by {@link #tryRoute}. */
    public record Candidate(UUID botId, boolean profileActive, boolean authorized,
                             SoulTypes.Reachability reachability) {
        public Candidate {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(reachability, "reachability");
        }
    }

    private SoulGroupRouter() {
    }

    /**
     * Pure roster projection: soul-profile-bound ∧ owner/operator-authorized ∧ LOCAL, capped at
     * {@link SoulGroupTypes#MAX_SCENE_BOTS}, preserving candidate order (callers pass
     * nearest-first). REMOTE deliberately does not qualify — scenes are in-earshot exchanges.
     */
    public static List<UUID> eligibleRoster(List<Candidate> candidates) {
        List<UUID> roster = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.profileActive() && candidate.authorized()
                    && candidate.reachability() == SoulTypes.Reachability.LOCAL) {
                roster.add(candidate.botId());
                if (roster.size() >= SoulGroupTypes.MAX_SCENE_BOTS) {
                    break;
                }
            }
        }
        return List.copyOf(roster);
    }

    /**
     * Pure coarse decision, mirroring {@link SoulChatRouter#decide}'s shape: souls master or the
     * party kill switch off → legacy untouched; index still loading or nobody eligible → consumed
     * with a deterministic notice; exactly one eligible → the ordinary DM path handles it;
     * otherwise the scene is exclusively consumed by souls.
     */
    public static RouteOutcome decide(boolean masterEnabled, boolean indexReady,
                                       boolean partyEnabled, int eligibleCount) {
        if (!masterEnabled || !partyEnabled) {
            return RouteOutcome.NOT_SOUL;
        }
        if (!indexReady) {
            return RouteOutcome.CONSUMED;
        }
        if (eligibleCount == 0) {
            return RouteOutcome.CONSUMED;
        }
        if (eligibleCount == 1) {
            return RouteOutcome.DOWNGRADE_TO_DM;
        }
        return RouteOutcome.CONSUMED;
    }

    /**
     * Attempts to route one broadcast/multi-name address as a group scene.
     *
     * @param candidateBots the resolved target bots (all registered bots for a broadcast)
     * @param partyEnabled the live {@code souls.party} kill-switch value, read at the call site
     * @param downgraded out-param of length ≥ 1: on {@link RouteOutcome#DOWNGRADE_TO_DM},
     *     {@code downgraded[0]} is set to the single eligible bot for the caller to hand to
     *     {@link SoulChatRouter#tryRoute}
     */
    public static RouteOutcome tryRoute(List<ServerPlayerEntity> candidateBots,
                                         ServerPlayerEntity sender, String prompt,
                                         boolean partyEnabled, ServerPlayerEntity[] downgraded) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(downgraded, "downgraded");
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

        // Nearest-first, so the roster cap keeps the bots actually standing with the player.
        List<ServerPlayerEntity> sorted = new ArrayList<>();
        for (ServerPlayerEntity bot : candidateBots) {
            if (bot != null && !bot.isRemoved()) {
                sorted.add(bot);
            }
        }
        sorted.sort(Comparator.comparingDouble(bot -> bot.squaredDistanceTo(sender)));

        List<Candidate> candidates = new ArrayList<>(sorted.size());
        for (ServerPlayerEntity bot : sorted) {
            candidates.add(new Candidate(bot.getUuid(),
                    runtime.hasActiveProfile(bot.getUuid()),
                    CompanionCommunicationPolicy.isPrivateSoulAuthorized(sender, bot),
                    CompanionCommunicationPolicy.classifySoulReachability(bot, sender)));
        }
        List<UUID> rosterIds = eligibleRoster(candidates);

        RouteOutcome coarse = decide(masterEnabled, indexReady, partyEnabled, rosterIds.size());
        if (coarse == RouteOutcome.NOT_SOUL) {
            return RouteOutcome.NOT_SOUL;
        }
        if (!indexReady) {
            logScene(routingId, sender, "loading", candidates.size(), rosterIds.size(), routeStartNanos);
            sendNotice(sender, "Your companions' conversation memory is still loading. Try again in a moment.");
            return RouteOutcome.CONSUMED;
        }
        if (!runtime.pipelineAvailable()) {
            logScene(routingId, sender, "invalid-pipeline", candidates.size(), rosterIds.size(), routeStartNanos);
            sendNotice(sender, "The local conversation model is not ready: " + runtime.safeValidationError());
            return RouteOutcome.CONSUMED;
        }
        if (coarse == RouteOutcome.DOWNGRADE_TO_DM) {
            for (ServerPlayerEntity bot : sorted) {
                if (bot.getUuid().equals(rosterIds.get(0))) {
                    downgraded[0] = bot;
                    break;
                }
            }
            logScene(routingId, sender, "downgrade-to-dm", candidates.size(), rosterIds.size(), routeStartNanos);
            return RouteOutcome.DOWNGRADE_TO_DM;
        }
        if (rosterIds.isEmpty()) {
            logScene(routingId, sender, "none-eligible", candidates.size(), 0, routeStartNanos);
            sendNotice(sender, "None of your companions are close enough to chat.");
            return RouteOutcome.CONSUMED;
        }

        MinecraftServer server = sender.getEntityWorld().getServer();
        if (server == null) {
            logScene(routingId, sender, "no-server", candidates.size(), rosterIds.size(), routeStartNanos);
            sendNotice(sender, "None of your companions are close enough to chat.");
            return RouteOutcome.CONSUMED;
        }

        // Fresh authoritative roster per turn (parent-spec ruling): capture per-bot grounding
        // now, on the server thread. A capture that throws drops that bot from the scene.
        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>(rosterIds.size());
        for (ServerPlayerEntity bot : sorted) {
            if (!rosterIds.contains(bot.getUuid())) {
                continue;
            }
            try {
                SoulTypes.GroundingSnapshot grounding = SoulSnapshotBuilder.capture(
                        server, bot, sender, SoulTypes.Reachability.LOCAL);
                String profileId = runtime.cachedState(bot.getUuid())
                        .map(SoulTypes.SoulState::profileId).orElse("");
                roster.add(new SoulGroupTypes.SceneParticipant(bot.getUuid(), profileId,
                        bot.getName().getString(), grounding));
            } catch (RuntimeException captureFailure) {
                LOGGER.warn("[souls] scene-routing routingId={} capture failed for bot {}: {}",
                        routingId, bot.getUuid(), captureFailure.toString());
            }
        }
        if (roster.size() < 2) {
            // Captures failed us below scene size. One survivor still gets a DM; zero gets a notice.
            if (roster.size() == 1) {
                for (ServerPlayerEntity bot : sorted) {
                    if (bot.getUuid().equals(roster.get(0).botId())) {
                        downgraded[0] = bot;
                        break;
                    }
                }
                logScene(routingId, sender, "downgrade-after-capture", candidates.size(), 1, routeStartNanos);
                return RouteOutcome.DOWNGRADE_TO_DM;
            }
            logScene(routingId, sender, "none-eligible", candidates.size(), 0, routeStartNanos);
            sendNotice(sender, "None of your companions are close enough to chat.");
            return RouteOutcome.CONSUMED;
        }

        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(sender.getUuid(),
                sender.getName().getString(), roster, safePrompt, Instant.now(), routingId);
        logScene(routingId, sender, "submitted", candidates.size(), roster.size(), routeStartNanos);
        runtime.submitGroupTurn(turn);
        return RouteOutcome.CONSUMED;
    }

    private static void sendNotice(ServerPlayerEntity sender, String text) {
        sender.sendMessage(Text.literal(text), false);
    }

    private static void logScene(UUID routingId, ServerPlayerEntity sender, String outcome,
                                  int candidateCount, int eligibleCount, long routeStartNanos) {
        LOGGER.info("[souls] scene-routing routingId={} player={} outcome={} candidates={} eligible={} routingMs={}",
                routingId, sender.getUuid(), outcome, candidateCount, eligibleCount,
                Math.max(0L, (System.nanoTime() - routeStartNanos) / 1_000_000L));
    }
}
