package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Deterministic local-chat scheduler (local-chat spec §5): decides whether an unaddressed line a
 * player types near a bot earns a spoken one-bot reaction; the model only ever writes the line
 * inside a scene this class already accepted.
 *
 * <p>Unlike {@link SoulBanterDirector}, which polls every {@value SoulBanterDirector#EVAL_INTERVAL_TICKS}
 * ticks looking for a quiet moment, this director is edge-triggered: {@link #noteUnaddressedChat}
 * is called once, from the chat callback, at the moment a line is typed, and that single call is
 * where earshot is computed, the line is recorded into {@link SoulLocalMemory}, and the reaction
 * decision runs. {@link #tick()} only expires reply windows and must stay a cheap no-op the rest
 * of the time.
 *
 * <p>Two related but different bot sets come out of the same earshot computation: the
 * <em>witnesses</em> (bots in range with an active soul profile — no authorization check, because
 * recording is cheap and authorization is a reaction-time concern) and the <em>eligible roster</em>
 * (witnesses further filtered through {@link SoulGroupRouter#eligibleRoster} exactly like
 * {@link SoulBanterDirector#eligibleRosterBots}, which is what may actually react).
 *
 * <p>No {@code Frens} references — enablement and the ambient-surface gates arrive as injected
 * suppliers, and the registered-bot list arrives through {@code botsProvider}, the same
 * lazy-injection pattern {@link SoulBanterDirector} uses.
 */
public final class SoulLocalDirector {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    static final double EARSHOT_BLOCKS = 16.0;
    static final long REPLY_WINDOW_MS = 30_000L;
    static final long RETRY_AFTER_VETO_MS = 120_000L;

    private final SoulRuntime runtime;
    private final MinecraftServer server;
    private final BooleanSupplier localChatEnabled;
    private final BooleanSupplier ambientTextOpen;
    private final BooleanSupplier ambientVoiceOpen;
    private final Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider;
    private final LongSupplier clock;
    private final RandomGenerator random;

    private final Map<UUID, Long> nextEligibleAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastVerdict = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastScore = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastLineByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, ReplyWindow> replyWindows = new ConcurrentHashMap<>();

    /** One open continuation window for a player; single-use, closed by expiry or consumption. */
    private record ReplyWindow(UUID botId, long expiresAtMs, boolean used) {
    }

    public SoulLocalDirector(SoulRuntime runtime, MinecraftServer server,
                              BooleanSupplier localChatEnabled, BooleanSupplier ambientTextOpen,
                              BooleanSupplier ambientVoiceOpen,
                              Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
                              LongSupplier clock, RandomGenerator random) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.localChatEnabled = Objects.requireNonNull(localChatEnabled, "localChatEnabled");
        this.ambientTextOpen = Objects.requireNonNull(ambientTextOpen, "ambientTextOpen");
        this.ambientVoiceOpen = Objects.requireNonNull(ambientVoiceOpen, "ambientVoiceOpen");
        this.botsProvider = Objects.requireNonNull(botsProvider, "botsProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Server-thread only, called from the chat callback for every unaddressed line spoken near a
     * soul-bound bot. Records the line into {@link SoulLocalMemory} for every witness, then — on
     * the same earshot computation — decides whether one bot should react.
     */
    public void noteUnaddressedChat(ServerPlayerEntity player, String line) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUuid();
        List<ServerPlayerEntity> bots = botsProvider.apply(server);
        if (isBot(playerId, bots)) {
            return;
        }
        String previousLine = lastLineByPlayer.put(playerId, line);
        if (SoulLocalSalience.hardReject(line, previousLine)) {
            return;
        }
        long now = clock.getAsLong();

        // Earshot computed once and shared by both the recording half and the reaction half.
        List<ServerPlayerEntity> near = botsInEarshot(player, bots);

        Set<UUID> witnessIds = new HashSet<>();
        for (ServerPlayerEntity bot : near) {
            if (runtime.hasActiveProfile(bot.getUuid())) {
                witnessIds.add(bot.getUuid());
            }
        }
        SoulLocalMemory.note(playerId, line, witnessIds, now);

        ReplyWindow window = replyWindows.get(playerId);
        boolean windowOpen = window != null && !window.used() && now < window.expiresAtMs();

        List<ServerPlayerEntity> rosterBots = eligibleRosterBots(player, near);

        int bestScore = -1;
        ServerPlayerEntity bestBot = null;
        SoulTypes.GroundingSnapshot bestSnapshot = null;
        for (ServerPlayerEntity bot : rosterBots) {
            SoulTypes.GroundingSnapshot snapshot;
            try {
                snapshot = SoulSnapshotBuilder.capture(server, bot, player, SoulTypes.Reachability.LOCAL);
            } catch (RuntimeException captureFailure) {
                LOGGER.warn("[souls] local capture failed for bot {}: {}", bot.getUuid(),
                        captureFailure.toString());
                continue;
            }
            String activeTask = snapshot.bot().activeTask();
            int score = SoulLocalSalience.score(line, bot.getName().getString(), activeTask, "");
            if (score > bestScore) {
                bestScore = score;
                bestBot = bot;
                bestSnapshot = snapshot;
            }
        }

        long nextAt = nextEligibleAtMs.computeIfAbsent(playerId, id -> now + initialDelayMs(random));
        String veto = firstVeto(
                localChatEnabled.getAsBoolean(),
                runtime.pipelineAvailable(),
                now >= nextAt || windowOpen,
                runtime.isSceneBudgetFree(playerId),
                ambientTextOpen.getAsBoolean() || ambientVoiceOpen.getAsBoolean(),
                playerAtEase(player),
                rosterBots.size(),
                bestScore >= SoulLocalSalience.THRESHOLD || windowOpen);
        if (veto != null) {
            recordVerdict(playerId, "vetoed:" + veto);
            lastScore.put(playerId, Math.max(bestScore, 0));
            return;
        }
        if (bestBot == null || bestSnapshot == null) {
            // Roster was non-empty but every capture in it failed — nothing left to ground on.
            recordVerdict(playerId, "vetoed:roster-lost");
            lastScore.put(playerId, Math.max(bestScore, 0));
            return;
        }

        if (groundingDangerous(bestSnapshot.situation())) {
            recordVerdict(playerId, "vetoed:danger");
            lastScore.put(playerId, bestScore);
            nextEligibleAtMs.put(playerId, now + RETRY_AFTER_VETO_MS);
            return;
        }

        String profileId = runtime.cachedState(bestBot.getUuid())
                .map(SoulTypes.SoulState::profileId).orElse("");
        SoulGroupTypes.SceneParticipant participant = new SoulGroupTypes.SceneParticipant(
                bestBot.getUuid(), profileId, bestBot.getName().getString(), bestSnapshot);
        UUID routingId = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.LOCAL, playerId, player.getName().getString(),
                List.of(participant), line, Instant.now(), routingId);
        nextEligibleAtMs.put(playerId, now + nextDelayMs(random));
        if (windowOpen) {
            markWindowUsed(playerId);
        } else {
            openReplyWindow(playerId, bestBot.getUuid(), now);
        }
        recordVerdict(playerId, "fired");
        lastScore.put(playerId, bestScore);
        LOGGER.info("[souls] local player={} bot={} outcome=fired routingId={} score={}",
                playerId, bestBot.getUuid(), routingId, bestScore);
        runtime.submitGroupTurn(turn);
    }

    /** Closes any open reply window — the player addressed a bot directly, so ambient yields. */
    public void noteAddressedChat(UUID playerId) {
        if (playerId != null) {
            replyWindows.remove(playerId);
        }
    }

    /** A player-initiated scene re-arms the full cooldown — local chat yields to real conversation. */
    public void notePlayerScene(UUID playerId) {
        if (playerId == null) {
            return;
        }
        nextEligibleAtMs.put(playerId, clock.getAsLong() + nextDelayMs(random));
        replyWindows.remove(playerId);
    }

    /** Server-thread only; cheap no-op except for expiring reply windows. */
    public void tick() {
        if (replyWindows.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        replyWindows.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtMs());
    }

    /** For {@code /bot soul local status}-style surfaces: last verdict, score, cooldown, window. */
    public String statusFor(UUID playerId) {
        long now = clock.getAsLong();
        long nextAt = nextEligibleAtMs.getOrDefault(playerId, 0L);
        String verdict = lastVerdict.getOrDefault(playerId, "no evaluation yet");
        int score = lastScore.getOrDefault(playerId, 0);
        long remainingS = Math.max(0L, (nextAt - now) / 1000L);
        ReplyWindow window = replyWindows.get(playerId);
        boolean windowOpen = window != null && !window.used() && now < window.expiresAtMs();
        return "last verdict: " + verdict + "; last score: " + score + "; cooldown: "
                + (remainingS == 0 ? "elapsed" : remainingS + "s remaining")
                + "; reply window: " + (windowOpen ? "open" : "closed");
    }

    private void recordVerdict(UUID playerId, String verdict) {
        String previous = lastVerdict.put(playerId, verdict);
        if (!verdict.equals(previous)) {
            LOGGER.info("[souls] local player={} outcome={}", playerId, verdict);
        }
    }

    private void openReplyWindow(UUID playerId, UUID botId, long now) {
        replyWindows.put(playerId, new ReplyWindow(botId, now + REPLY_WINDOW_MS, false));
    }

    private void markWindowUsed(UUID playerId) {
        ReplyWindow window = replyWindows.get(playerId);
        if (window != null) {
            replyWindows.put(playerId, new ReplyWindow(window.botId(), window.expiresAtMs(), true));
        }
    }

    private static boolean isBot(UUID playerId, List<ServerPlayerEntity> bots) {
        for (ServerPlayerEntity bot : bots) {
            if (bot != null && bot.getUuid().equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    /** Bots in the same world within {@link #EARSHOT_BLOCKS}, nearest first. */
    private List<ServerPlayerEntity> botsInEarshot(ServerPlayerEntity player, List<ServerPlayerEntity> bots) {
        List<ServerPlayerEntity> near = new ArrayList<>();
        for (ServerPlayerEntity bot : bots) {
            if (bot != null && !bot.isRemoved() && bot.isAlive()
                    && bot.getEntityWorld() == player.getEntityWorld()
                    && bot.squaredDistanceTo(player) <= EARSHOT_BLOCKS * EARSHOT_BLOCKS) {
                near.add(bot);
            }
        }
        near.sort(Comparator.comparingDouble(bot -> bot.squaredDistanceTo(player)));
        return near;
    }

    /** {@code near}, further filtered through {@link SoulGroupRouter#eligibleRoster}. */
    private List<ServerPlayerEntity> eligibleRosterBots(ServerPlayerEntity player,
                                                          List<ServerPlayerEntity> near) {
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

    // === Pure rules (unit-tested) ===

    static long initialDelayMs(RandomGenerator random) {
        return (long) (random.nextDouble() * 2 * 60_000L);
    }

    static long nextDelayMs(RandomGenerator random) {
        return 6 * 60_000L + (long) (random.nextDouble() * 6 * 60_000L);
    }

    /** First failed gate's name in spec §5.2 order, or {@code null} when eligible. */
    static String firstVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
                             boolean budgetFree, boolean surfaceOpen, boolean playerAtEase,
                             int eligibleRosterSize, boolean salient) {
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
        if (eligibleRosterSize < 1) {
            return "roster";
        }
        if (!salient) {
            return "salience";
        }
        return null;
    }

    /** Post-capture danger veto — identical rule to banter's. */
    static boolean groundingDangerous(SoulTypes.SituationSnapshot situation) {
        return !situation.hostiles().isEmpty() || situation.inCombat()
                || situation.breakingFree() || situation.surfaceRecoveryActive();
    }
}
