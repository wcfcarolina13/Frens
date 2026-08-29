package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Deterministic banter scheduler (spec §3): decides WHEN companions strike up an autonomous
 * scene; the model only ever writes lines inside a scene this class already accepted. Ticked
 * every server tick via {@link SoulRuntime#tickScenes}, self-throttled to one evaluation per
 * {@value #EVAL_INTERVAL_TICKS} ticks.
 *
 * <p>Two-phase trigger: Phase A (server tick) runs the cheap gate chain ({@link #firstVeto});
 * on pass it fetches the roster bots' recent events asynchronously, then Phase B (hopped back
 * onto the server thread) re-checks the cheap gates, captures fresh per-bot grounding, applies
 * the post-capture danger veto, builds the seed, and submits a BANTER-kind
 * {@link SoulGroupTypes.GroupSceneTurn}. All banter failures are silent to players; every
 * verdict is recorded per player for {@code /bot soul banter status} and logged only when the
 * reason changes.
 *
 * <p>No {@code Frens} references — enablement and the ambient-surface gates arrive as injected
 * suppliers (the established lazy-lambda pattern), and the registered-bot list arrives through
 * {@code botsProvider} for the same reason.
 */
public final class SoulBanterDirector {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    static final long EVAL_INTERVAL_TICKS = 100;           // 5s
    static final long QUIET_WINDOW_MS = 90_000L;
    static final long RETRY_AFTER_VETO_MS = 120_000L;      // danger / capture / fetch vetoes
    static final double AUDIENCE_RADIUS_BLOCKS = 24.0;
    static final double BOT_PROXIMITY_BLOCKS = 12.0;
    /** Journal tail fetched per roster bot for the seed builder. */
    static final int EVENT_FETCH_WINDOW = 12;

    private final SoulRuntime runtime;
    private final MinecraftServer server;
    private final BooleanSupplier banterEnabled;
    private final BooleanSupplier ambientTextOpen;
    private final BooleanSupplier ambientVoiceOpen;
    private final Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider;
    private final LongSupplier clock;
    private final RandomGenerator random;

    private long tickCounter;
    private final Map<UUID, Long> nextEligibleAtMs = new ConcurrentHashMap<>();
    private final Set<UUID> pendingAttempts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastVerdict = new ConcurrentHashMap<>();

    public SoulBanterDirector(SoulRuntime runtime, MinecraftServer server,
                               BooleanSupplier banterEnabled, BooleanSupplier ambientTextOpen,
                               BooleanSupplier ambientVoiceOpen,
                               Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
                               LongSupplier clock, RandomGenerator random) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.banterEnabled = Objects.requireNonNull(banterEnabled, "banterEnabled");
        this.ambientTextOpen = Objects.requireNonNull(ambientTextOpen, "ambientTextOpen");
        this.ambientVoiceOpen = Objects.requireNonNull(ambientVoiceOpen, "ambientVoiceOpen");
        this.botsProvider = Objects.requireNonNull(botsProvider, "botsProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Server-thread only; cheap no-op between evaluation windows and while banter is off. */
    public void tick() {
        if (++tickCounter % EVAL_INTERVAL_TICKS != 0) {
            return;
        }
        if (!banterEnabled.getAsBoolean()) {
            return; // fully dark when off: no verdict churn, no logs
        }
        long now = clock.getAsLong();
        List<ServerPlayerEntity> bots = botsProvider.apply(server);
        Set<UUID> botIds = ConcurrentHashMap.newKeySet();
        for (ServerPlayerEntity bot : bots) {
            if (bot != null) {
                botIds.add(bot.getUuid());
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || botIds.contains(player.getUuid())
                    || pendingAttempts.contains(player.getUuid())) {
                continue;
            }
            evaluate(player, bots, now);
        }
    }

    private void evaluate(ServerPlayerEntity player, List<ServerPlayerEntity> bots, long now) {
        UUID playerId = player.getUuid();
        long nextAt = nextEligibleAtMs.computeIfAbsent(playerId, id -> now + initialDelayMs(random));

        List<ServerPlayerEntity> rosterBots = eligibleRosterBots(player, bots);
        String veto = firstVeto(
                banterEnabled.getAsBoolean(),
                runtime.pipelineAvailable(),
                now >= nextAt,
                runtime.isSceneBudgetFree(playerId),
                ambientTextOpen.getAsBoolean() || ambientVoiceOpen.getAsBoolean(),
                playerAtEase(player),
                now - SoulPlayerActivity.lastChatAt(playerId) >= QUIET_WINDOW_MS,
                rosterBots.size(),
                botsCloseTogether(rosterBots));
        if (veto != null) {
            recordVerdict(playerId, "vetoed:" + veto);
            return;
        }

        // Phase A passed — fetch the roster's recent events off-thread, then hop back.
        pendingAttempts.add(playerId);
        List<UUID> rosterIds = rosterBots.stream().map(ServerPlayerEntity::getUuid).toList();
        List<CompletableFuture<List<SoulTypes.SoulEvent>>> fetches = new ArrayList<>();
        for (UUID botId : rosterIds) {
            fetches.add(runtime.recentEventsForBanter(botId, EVENT_FETCH_WINDOW)
                    .exceptionally(ex -> List.of()));
        }
        CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new))
                .whenComplete((v, err) -> server.execute(() -> {
                    try {
                        List<List<SoulTypes.SoulEvent>> eventsPerBot = new ArrayList<>();
                        for (CompletableFuture<List<SoulTypes.SoulEvent>> fetch : fetches) {
                            eventsPerBot.add(fetch.getNow(List.of()));
                        }
                        fireScene(playerId, rosterIds, eventsPerBot);
                    } finally {
                        pendingAttempts.remove(playerId);
                    }
                }));
    }

    /** Phase B, server thread: re-check, capture, danger-veto, seed, submit. */
    private void fireScene(UUID playerId, List<UUID> rosterIds,
                            List<List<SoulTypes.SoulEvent>> eventsPerBot) {
        long now = clock.getAsLong();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null || !banterEnabled.getAsBoolean() || !runtime.pipelineAvailable()
                || !runtime.isSceneBudgetFree(playerId) || !playerAtEase(player)
                || now - SoulPlayerActivity.lastChatAt(playerId) < QUIET_WINDOW_MS) {
            recordVerdict(playerId, "vetoed:changed-before-capture");
            return;
        }

        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>();
        List<SoulTypes.GroundingSnapshot> groundings = new ArrayList<>();
        for (UUID botId : rosterIds) {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            try {
                SoulTypes.GroundingSnapshot grounding = SoulSnapshotBuilder.capture(
                        server, bot, player, SoulTypes.Reachability.LOCAL);
                if (groundingDangerous(grounding.situation())) {
                    recordVerdict(playerId, "vetoed:danger");
                    nextEligibleAtMs.put(playerId, now + RETRY_AFTER_VETO_MS);
                    return;
                }
                String profileId = runtime.cachedState(botId)
                        .map(SoulTypes.SoulState::profileId).orElse("");
                roster.add(new SoulGroupTypes.SceneParticipant(botId, profileId,
                        bot.getName().getString(), grounding));
                groundings.add(grounding);
            } catch (RuntimeException captureFailure) {
                LOGGER.warn("[souls] banter capture failed for bot {}: {}", botId,
                        captureFailure.toString());
            }
        }
        if (roster.size() < 1) {
            recordVerdict(playerId, "vetoed:roster-lost");
            nextEligibleAtMs.put(playerId, now + RETRY_AFTER_VETO_MS);
            return;
        }

        String playerActivity = SoulPlayerActivity.recentAction(playerId, now).orElse("");
        String seed = SoulBanterSeed.build(groundings, eventsPerBot,
                player.getName().getString(), playerActivity, random);
        UUID routingId = UUID.randomUUID();
        boolean addressPlayer = decideAddressPlayer(roster.size(), random);
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, playerId, player.getName().getString(),
                roster, seed, Instant.now(), routingId, addressPlayer);
        nextEligibleAtMs.put(playerId, now + nextDelayMs(random));
        recordVerdict(playerId, "fired");
        LOGGER.info("[souls] banter player={} outcome=fired routingId={} roster={} seedChars={} addressPlayer={}",
                playerId, routingId, roster.size(), seed.length(), addressPlayer);
        runtime.submitGroupTurn(turn).thenAccept(submission -> {
            // 2026-08-29 field fix: a fired scene arms the full 8–15 min cooldown up front, so a
            // generation that then FAILS (observed: 3B model output rejected as MALFORMED) used
            // to cost the player the whole window for zero delivered lines. Refund a failure
            // down to the short retry delay — Math::min only ever shortens, and a player scene
            // re-arming the cooldown meanwhile is respected because min keeps the earlier time.
            if (submission == SoulGroupConversationService.Submission.FAILED) {
                nextEligibleAtMs.merge(playerId, clock.getAsLong() + RETRY_AFTER_VETO_MS, Math::min);
                recordVerdict(playerId, "fired-but-failed");
            }
        });
    }

    /** Field-test lever ({@code /bot soul banter now}): clears the actor's cooldown so the
     *  next 5 s evaluation may fire immediately if every other gate passes. */
    public void primeNow(UUID playerId) {
        if (playerId != null) {
            nextEligibleAtMs.put(playerId, 0L);
        }
    }

    /** A player-initiated scene re-arms the full cooldown — banter yields to real conversation. */
    public void notePlayerScene(UUID playerId) {
        nextEligibleAtMs.put(playerId, clock.getAsLong() + nextDelayMs(random));
    }

    /** For {@code /bot soul banter status}: last verdict + time to next eligibility. */
    public String statusFor(UUID playerId) {
        long now = clock.getAsLong();
        long nextAt = nextEligibleAtMs.getOrDefault(playerId, 0L);
        String verdict = lastVerdict.getOrDefault(playerId, "no evaluation yet");
        long remainingS = Math.max(0L, (nextAt - now) / 1000L);
        return "last verdict: " + verdict + "; cooldown: "
                + (remainingS == 0 ? "elapsed" : remainingS + "s remaining");
    }

    private void recordVerdict(UUID playerId, String verdict) {
        String previous = lastVerdict.put(playerId, verdict);
        if (!verdict.equals(previous)) {
            LOGGER.info("[souls] banter player={} outcome={}", playerId, verdict);
        }
    }

    private List<ServerPlayerEntity> eligibleRosterBots(ServerPlayerEntity player,
                                                         List<ServerPlayerEntity> bots) {
        List<ServerPlayerEntity> near = new ArrayList<>();
        for (ServerPlayerEntity bot : bots) {
            if (bot != null && !bot.isRemoved() && bot.isAlive()
                    && bot.getEntityWorld() == player.getEntityWorld()
                    && bot.squaredDistanceTo(player) <= AUDIENCE_RADIUS_BLOCKS * AUDIENCE_RADIUS_BLOCKS) {
                near.add(bot);
            }
        }
        near.sort(Comparator.comparingDouble(bot -> bot.squaredDistanceTo(player)));
        List<SoulGroupRouter.Candidate> candidates = new ArrayList<>(near.size());
        for (ServerPlayerEntity bot : near) {
            candidates.add(new SoulGroupRouter.Candidate(bot.getUuid(),
                    runtime.hasActiveProfile(bot.getUuid()),
                    CompanionCommunicationPolicy.isPrivateSoulAuthorized(player, bot),
                    CompanionCommunicationPolicy.classifySoulReachability(bot, player)));
        }
        List<UUID> rosterIds = SoulGroupRouter.eligibleRoster(candidates);
        List<ServerPlayerEntity> roster = new ArrayList<>();
        for (ServerPlayerEntity bot : near) {
            if (rosterIds.contains(bot.getUuid())) {
                roster.add(bot);
            }
        }
        return roster;
    }

    private static boolean playerAtEase(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSleeping()
                && player.hurtTime == 0 && player.getAttacker() == null;
    }

    /** All roster bots within {@link #BOT_PROXIMITY_BLOCKS} of the nearest one. */
    private static boolean botsCloseTogether(List<ServerPlayerEntity> roster) {
        if (roster.size() <= 1) {
            // A lone bot is trivially "together" — solo remarks (engagement spec §3) need no
            // proximity pair; an empty roster stays false so the roster gate reports first.
            return roster.size() == 1;
        }
        ServerPlayerEntity anchor = roster.get(0);
        for (ServerPlayerEntity bot : roster) {
            if (bot.squaredDistanceTo(anchor) > BOT_PROXIMITY_BLOCKS * BOT_PROXIMITY_BLOCKS) {
                return false;
            }
        }
        return true;
    }

    // === Pure rules (unit-tested) ===

    static long initialDelayMs(RandomGenerator random) {
        // 60–150 s (was 4–8 min): both 2026-08 field sessions ended before the old grace ever
        // elapsed, so the feature was untestable and a fresh session felt mute. Steady-state
        // spacing (nextDelayMs) is unchanged — this only moves the FIRST possible scene.
        return 60_000L + (long) (random.nextDouble() * 90_000L);
    }

    static long nextDelayMs(RandomGenerator random) {
        return 8 * 60_000L + (long) (random.nextDouble() * 7 * 60_000L);
    }

    /**
     * Fire-time engagement coin (engagement spec §3): solo scenes always speak to the player;
     * group scenes get a closing player-addressed line about one time in three. Deterministic
     * Frens logic — the model never decides WHETHER the player is addressed.
     */
    static boolean decideAddressPlayer(int rosterSize, RandomGenerator random) {
        return rosterSize == 1 || random.nextInt(3) == 0;
    }

    /** First failed gate's name in spec §3 order, or {@code null} when eligible. */
    static String firstVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
                             boolean budgetFree, boolean surfaceOpen, boolean playerAtEase,
                             boolean quiet, int eligibleRosterSize, boolean botsCloseTogether) {
        if (!enabled) {
            return "disabled";
        }
        if (!pipelineAvailable) {
            return "pipeline";
        }
        if (!cooldownElapsed) {
            return "cooldown";
        }
        if (!budgetFree) {
            return "busy";
        }
        if (!surfaceOpen) {
            return "muted";
        }
        if (!playerAtEase) {
            return "player-not-at-ease";
        }
        if (!quiet) {
            return "not-quiet";
        }
        if (eligibleRosterSize < 1) {
            return "roster"; // no eligible bot at all — solo rosters are valid since the engagement spec
        }
        if (!botsCloseTogether) {
            return "bots-apart";
        }
        return null;
    }

    /** Post-capture danger veto (spec §3 item 7). */
    static boolean groundingDangerous(SoulTypes.SituationSnapshot situation) {
        return !situation.hostiles().isEmpty() || situation.inCombat()
                || situation.breakingFree() || situation.surfaceRecoveryActive();
    }
}
