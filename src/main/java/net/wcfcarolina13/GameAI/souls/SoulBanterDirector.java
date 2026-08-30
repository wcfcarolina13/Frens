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
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
    /** Active lane: work continues under chat, so only a short hush is required. */
    static final long ACTIVE_QUIET_WINDOW_MS = 30_000L;

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

    // Active lane (2026-08-29 pacing spec §3.4): companions chat WHILE working. Own enable,
    // own cadence, own cooldown + verdict per player; pendingAttempts is shared so the two
    // lanes never race one player into two scenes.
    private final BooleanSupplier activeEnabled;
    private final Predicate<ServerPlayerEntity> workingProbe;
    private final IntSupplier idleRate;
    private final IntSupplier activeRate;
    private final Map<UUID, Long> nextActiveAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastActiveVerdict = new ConcurrentHashMap<>();
    /**
     * Per-audience conversational memory (both lanes): the grounding of the last scene (for
     * {@link SoulSceneDiff}), the seen-registry, and the last few topics and speech acts — fed
     * back to the seed so consecutive scenes differ in subject AND shape (ontology Phase 1).
     */
    static final class AudienceMemory {
        SoulTypes.GroundingSnapshot lastGrounding;
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final java.util.ArrayDeque<String> topics = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<SoulSpeechAct> acts = new java.util.ArrayDeque<>();
    }

    private final Map<UUID, AudienceMemory> memories = new ConcurrentHashMap<>();
    static final int RECENT_TOPIC_MEMORY = 6;

    /** Which banter lane a scene belongs to; each has its own cooldown map and verdict. */
    enum Lane { IDLE, ACTIVE }

    public SoulBanterDirector(SoulRuntime runtime, MinecraftServer server,
                               BooleanSupplier banterEnabled, BooleanSupplier activeEnabled,
                               BooleanSupplier ambientTextOpen, BooleanSupplier ambientVoiceOpen,
                               Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
                               Predicate<ServerPlayerEntity> workingProbe,
                               IntSupplier idleRate, IntSupplier activeRate,
                               LongSupplier clock, RandomGenerator random) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.banterEnabled = Objects.requireNonNull(banterEnabled, "banterEnabled");
        this.activeEnabled = Objects.requireNonNull(activeEnabled, "activeEnabled");
        this.ambientTextOpen = Objects.requireNonNull(ambientTextOpen, "ambientTextOpen");
        this.ambientVoiceOpen = Objects.requireNonNull(ambientVoiceOpen, "ambientVoiceOpen");
        this.botsProvider = Objects.requireNonNull(botsProvider, "botsProvider");
        this.workingProbe = Objects.requireNonNull(workingProbe, "workingProbe");
        this.idleRate = Objects.requireNonNull(idleRate, "idleRate");
        this.activeRate = Objects.requireNonNull(activeRate, "activeRate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Server-thread only; cheap no-op between evaluation windows and while banter is off. */
    public void tick() {
        if (++tickCounter % EVAL_INTERVAL_TICKS != 0) {
            return;
        }
        if (!banterEnabled.getAsBoolean() && !activeEnabled.getAsBoolean()) {
            return; // fully dark when both lanes are off: no verdict churn, no logs
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
            if (banterEnabled.getAsBoolean()) {
                evaluate(player, bots, now);
            }
            if (activeEnabled.getAsBoolean() && !pendingAttempts.contains(player.getUuid())) {
                evaluateActive(player, bots, now);
            }
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
            recordVerdict(playerId, Lane.IDLE, "vetoed:" + veto);
            return;
        }
        beginScene(playerId, rosterBots, Lane.IDLE);
    }

    /** Active lane, Phase A: same roster rules, its own gate chain, needs someone working. */
    private void evaluateActive(ServerPlayerEntity player, List<ServerPlayerEntity> bots, long now) {
        UUID playerId = player.getUuid();
        long nextAt = nextActiveAtMs.computeIfAbsent(playerId, id -> now + initialDelayMs(random));
        List<ServerPlayerEntity> rosterBots = eligibleRosterBots(player, bots);
        int working = 0;
        for (ServerPlayerEntity bot : rosterBots) {
            if (workingProbe.test(bot)) {
                working++;
            }
        }
        String veto = firstActiveVeto(
                activeEnabled.getAsBoolean(),
                runtime.pipelineAvailable(),
                now >= nextAt,
                runtime.isSceneBudgetFree(playerId),
                ambientTextOpen.getAsBoolean() || ambientVoiceOpen.getAsBoolean(),
                playerReady(player),
                now - SoulPlayerActivity.lastChatAt(playerId) >= ACTIVE_QUIET_WINDOW_MS,
                rosterBots.size(),
                working,
                botsCloseTogether(rosterBots));
        if (veto != null) {
            recordVerdict(playerId, Lane.ACTIVE, "vetoed:" + veto);
            return;
        }
        beginScene(playerId, rosterBots, Lane.ACTIVE);
    }

    /** Phase A tail shared by both lanes: fetch recent events off-thread, hop back, fire. */
    private void beginScene(UUID playerId, List<ServerPlayerEntity> rosterBots, Lane lane) {
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
                        fireScene(playerId, rosterIds, eventsPerBot, lane);
                    } finally {
                        pendingAttempts.remove(playerId);
                    }
                }));
    }

    /** Phase B, server thread: re-check, capture, danger-veto, seed, submit. */
    private void fireScene(UUID playerId, List<UUID> rosterIds,
                            List<List<SoulTypes.SoulEvent>> eventsPerBot, Lane lane) {
        long now = clock.getAsLong();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        boolean laneEnabled = lane == Lane.IDLE ? banterEnabled.getAsBoolean() : activeEnabled.getAsBoolean();
        long quietWindow = lane == Lane.IDLE ? QUIET_WINDOW_MS : ACTIVE_QUIET_WINDOW_MS;
        boolean ready = player != null && (lane == Lane.IDLE ? playerAtEase(player) : playerReady(player));
        if (player == null || !laneEnabled || !runtime.pipelineAvailable()
                || !runtime.isSceneBudgetFree(playerId) || !ready
                || now - SoulPlayerActivity.lastChatAt(playerId) < quietWindow) {
            recordVerdict(playerId, lane, "vetoed:changed-before-capture");
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
                    recordVerdict(playerId, lane, "vetoed:danger");
                    cooldowns(lane).put(playerId, now + RETRY_AFTER_VETO_MS);
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
            recordVerdict(playerId, lane, "vetoed:roster-lost");
            cooldowns(lane).put(playerId, now + RETRY_AFTER_VETO_MS);
            return;
        }

        String playerActivity = SoulPlayerActivity.recentAction(playerId, now).orElse("");
        AudienceMemory memory = memories.computeIfAbsent(playerId, id -> new AudienceMemory());
        SoulBanterSeed.Seed seeded;
        synchronized (memory) {
            List<SoulBanterSeed.Anchor> changes = SoulSceneDiff.diff(memory.lastGrounding,
                    groundings.get(0), memory.seen, player.getName().getString());
            memory.lastGrounding = groundings.get(0);
            seeded = SoulBanterSeed.buildSeed(groundings, eventsPerBot,
                    player.getName().getString(), playerActivity, random,
                    new java.util.HashSet<>(memory.topics), changes, new ArrayList<>(memory.acts));
            if (!seeded.topic().isEmpty()) {
                memory.topics.addLast(seeded.topic());
                while (memory.topics.size() > RECENT_TOPIC_MEMORY) {
                    memory.topics.removeFirst();
                }
            }
            if (seeded.act() != null) {
                memory.acts.addLast(seeded.act());
                while (memory.acts.size() > SoulSpeechAct.RECENT_ACT_MEMORY) {
                    memory.acts.removeFirst();
                }
            }
        }
        String seed = seeded.text();
        UUID routingId = UUID.randomUUID();
        boolean addressPlayer = decideAddressPlayer(roster.size(), random);
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                kind(lane), playerId, player.getName().getString(),
                roster, seed, Instant.now(), routingId, addressPlayer);
        long armedUntilMs = now + nextDelay(lane);
        cooldowns(lane).put(playerId, armedUntilMs);
        recordVerdict(playerId, lane, "fired");
        LOGGER.info("[souls] banter lane={} player={} outcome=fired routingId={} roster={} seedChars={} act={} topic=\"{}\" addressPlayer={}",
                lane, playerId, routingId, roster.size(), seed.length(), seeded.act(), seeded.topic(), addressPlayer);
        runtime.submitGroupTurn(turn).thenAccept(submission -> {
            // 2026-08-29 field fix (+ review round): a fired scene arms the full 8–15 min
            // cooldown up front, so a generation that then FAILS (observed: 3B output rejected
            // as MALFORMED) used to cost the player the whole window for zero delivered lines.
            // Refund with a CONDITIONAL replace of exactly the value this fire wrote — a plain
            // min-merge would also shorten a cooldown deliberately re-armed by notePlayerScene
            // when a real player conversation started while this generation was in flight.
            if (submission == SoulGroupConversationService.Submission.FAILED) {
                if (cooldowns(lane).replace(playerId, armedUntilMs,
                        clock.getAsLong() + RETRY_AFTER_VETO_MS)) {
                    recordVerdict(playerId, lane, "fired-but-failed");
                }
            }
        });
    }

    /** Field-test lever ({@code /bot soul banter now}): clears the actor's cooldown so the
     *  next 5 s evaluation may fire immediately if every other gate passes. */
    public void primeNow(UUID playerId) {
        if (playerId != null) {
            nextEligibleAtMs.put(playerId, 0L);
            nextActiveAtMs.put(playerId, 0L);
        }
    }

    /** A player-initiated scene re-arms the full cooldown — banter yields to real conversation. */
    public void notePlayerScene(UUID playerId) {
        nextEligibleAtMs.put(playerId, clock.getAsLong() + nextDelay(Lane.IDLE));
        nextActiveAtMs.put(playerId, clock.getAsLong() + nextDelay(Lane.ACTIVE));
    }

    /** For {@code /bot soul banter status}: last verdict + time to next eligibility. */
    public String statusFor(UUID playerId) {
        long now = clock.getAsLong();
        return "idle — " + laneStatus(playerId, now, lastVerdict, nextEligibleAtMs)
                + "; active — " + laneStatus(playerId, now, lastActiveVerdict, nextActiveAtMs);
    }

    private static String laneStatus(UUID playerId, long now, Map<UUID, String> verdicts,
                                     Map<UUID, Long> cooldowns) {
        long nextAt = cooldowns.getOrDefault(playerId, 0L);
        String verdict = verdicts.getOrDefault(playerId, "no evaluation yet");
        long remainingS = Math.max(0L, (nextAt - now) / 1000L);
        return "last verdict: " + verdict + "; cooldown: "
                + (remainingS == 0 ? "elapsed" : remainingS + "s remaining");
    }

    private void recordVerdict(UUID playerId, Lane lane, String verdict) {
        Map<UUID, String> verdicts = lane == Lane.IDLE ? lastVerdict : lastActiveVerdict;
        String previous = verdicts.put(playerId, verdict);
        if (!verdict.equals(previous)) {
            LOGGER.info("[souls] banter lane={} player={} outcome={}", lane, playerId, verdict);
        }
    }

    private Map<UUID, Long> cooldowns(Lane lane) {
        return lane == Lane.IDLE ? nextEligibleAtMs : nextActiveAtMs;
    }

    private static SoulGroupTypes.SceneKind kind(Lane lane) {
        return lane == Lane.IDLE ? SoulGroupTypes.SceneKind.BANTER : SoulGroupTypes.SceneKind.WORK;
    }

    private long nextDelay(Lane lane) {
        return lane == Lane.IDLE
                ? nextDelayMs(random, multiplier(idleRate.getAsInt()))
                : nextActiveDelayMs(random, multiplier(activeRate.getAsInt()));
    }

    /** Active lane is lenient: work goes on under light danger; only dead/asleep blocks it. */
    private static boolean playerReady(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSleeping();
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
        return nextDelayMs(random, 1.0);
    }

    /** 8–15 min × the Idle rate multiplier (DialoguePacing math, mirrored to stay Frens-free). */
    static long nextDelayMs(RandomGenerator random, double multiplier) {
        return Math.round((8 * 60_000L + random.nextDouble() * 7 * 60_000L) * multiplier);
    }

    /** 4–8 min × the Active rate multiplier — working chatter is denser than idle chatter. */
    static long nextActiveDelayMs(RandomGenerator random, double multiplier) {
        return Math.round((4 * 60_000L + random.nextDouble() * 4 * 60_000L) * multiplier);
    }

    /** Same curve as DialoguePacing.multiplier: rate 0 → ×8, 50 → ×1, 100 → ×0.125. */
    static double multiplier(int rate) {
        int r = Math.max(0, Math.min(100, rate));
        return Math.pow(8.0, (50 - r) / 50.0);
    }

    /** Active lane gate chain: "player-not-ready" replaces at-ease, "nobody-working" is new. */
    static String firstActiveVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
                                   boolean budgetFree, boolean surfaceOpen, boolean playerReady,
                                   boolean quiet, int eligibleRosterSize, int workingCount,
                                   boolean botsCloseTogether) {
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
        if (!playerReady) {
            return "player-not-ready";
        }
        if (!quiet) {
            return "not-quiet";
        }
        if (eligibleRosterSize < 1) {
            return "roster";
        }
        if (workingCount < 1) {
            return "nobody-working";
        }
        if (!botsCloseTogether) {
            return "bots-apart";
        }
        return null;
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
