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
     * soul-bound bot.
     *
     * <p>Cheapest-first: bails immediately while the feature is off (no scan, no recording — see
     * {@link SoulLocalMemory}'s own invariant), then checks the gates that need no per-bot
     * snapshot ({@code pipeline}/{@code cooldown}/{@code busy}/{@code muted}/
     * {@code player-not-at-ease}) with placeholder-eligible roster/salience facts before ever
     * computing earshot or calling {@link SoulSnapshotBuilder#capture}, which does raycasts and
     * entity/POI scans. Only once those pass does it compute earshot once — shared by the
     * recording half ({@link SoulLocalMemory#note}) and the reaction half — run the capture loop,
     * and re-check with the real roster size and salience.
     *
     * <p>The reply window's bot identity gates the continuation bypass: a window only waives
     * cooldown/salience for the specific bot that opened it, never for whichever bot happens to
     * win the scoring race.
     */
    public void noteUnaddressedChat(ServerPlayerEntity player, String line) {
        if (player == null || !localChatEnabled.getAsBoolean()) {
            return; // disabled: no scan, nothing recorded, nothing touched.
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

        ReplyWindow window = replyWindows.get(playerId);
        boolean windowOpen = window != null && !window.used() && now < window.expiresAtMs();

        // Cheap gates only — no earshot scan, no capture — with permissive placeholders for the
        // two facts that require the (expensive) roster/capture pass below.
        long nextAt = nextEligibleAtMs.computeIfAbsent(playerId, id -> now + initialDelayMs(random));
        boolean pipelineAvailable = runtime.pipelineAvailable();
        boolean budgetFree = runtime.isSceneBudgetFree(playerId);
        boolean surfaceOpen = ambientTextOpen.getAsBoolean() || ambientVoiceOpen.getAsBoolean();
        boolean atEase = playerAtEase(player);
        String earlyVeto = firstVeto(true, pipelineAvailable, now >= nextAt || windowOpen,
                budgetFree, surfaceOpen, atEase, 1, true);
        if (earlyVeto != null) {
            recordVerdict(playerId, "vetoed:" + earlyVeto);
            lastScore.put(playerId, 0);
            return;
        }

        // Earshot computed once and shared by both the recording half and the reaction half.
        List<ServerPlayerEntity> near = botsInEarshot(player, bots);

        Set<UUID> witnessIds = new HashSet<>();
        for (ServerPlayerEntity bot : near) {
            if (runtime.hasActiveProfile(bot.getUuid())) {
                witnessIds.add(bot.getUuid());
            }
        }
        SoulLocalMemory.note(playerId, line, witnessIds, now);

        List<ServerPlayerEntity> rosterBots = eligibleRosterBots(player, near);

        UUID windowBotId = windowOpen ? window.botId() : null;
        int topScore = -1;
        int captureSuccessCount = 0;
        int bestScore = -1;
        ServerPlayerEntity bestBot = null;
        SoulTypes.GroundingSnapshot bestSnapshot = null;
        boolean bestIsContinuation = false;
        for (ServerPlayerEntity bot : rosterBots) {
            SoulTypes.GroundingSnapshot snapshot;
            try {
                snapshot = SoulSnapshotBuilder.capture(server, bot, player, SoulTypes.Reachability.LOCAL);
            } catch (RuntimeException captureFailure) {
                LOGGER.warn("[souls] local capture failed for bot {}: {}", bot.getUuid(),
                        captureFailure.toString());
                continue;
            }
            captureSuccessCount++;
            String activeTask = snapshot.bot().activeTask();
            int score = SoulLocalSalience.score(line, bot.getName().getString(), activeTask, "");
            topScore = Math.max(topScore, score);
            // The continuation bypass belongs to the bot that opened the window — never to
            // whichever bot merely wins the scoring race.
            boolean isContinuation = windowBotId != null && windowBotId.equals(bot.getUuid());
            boolean eligible = score >= SoulLocalSalience.THRESHOLD || isContinuation;
            if (eligible && score > bestScore) {
                bestScore = score;
                bestBot = bot;
                bestSnapshot = snapshot;
                bestIsContinuation = isContinuation;
            }
        }

        if (!rosterBots.isEmpty() && captureSuccessCount == 0) {
            // Every capture in a non-empty roster failed — nothing left to ground on. Distinct
            // from a normal salience miss, so report it as its own reason.
            recordVerdict(playerId, "vetoed:roster-lost");
            lastScore.put(playerId, 0);
            return;
        }

        String veto = firstVeto(true, pipelineAvailable, now >= nextAt || bestIsContinuation,
                budgetFree, surfaceOpen, atEase, rosterBots.size(), bestBot != null);
        if (veto != null) {
            recordVerdict(playerId, "vetoed:" + veto);
            lastScore.put(playerId, Math.max(topScore, 0));
            return;
        }

        if (groundingDangerous(bestSnapshot.situation())) {
            recordVerdict(playerId, "vetoed:danger");
            lastScore.put(playerId, bestScore);
            // Push the cooldown out, but never shorten one already armed further out (a prior
            // fire's full cooldown must survive a subsequent danger veto).
            nextEligibleAtMs.merge(playerId, now + RETRY_AFTER_VETO_MS, Math::max);
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
        if (bestIsContinuation) {
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

    /**
     * Server-thread only; cheap no-op except for closing reply windows — on expiry, or when the
     * player has left earshot of the window's bot (gone or in a different world) or moved beyond
     * {@link #EARSHOT_BLOCKS}, using the same same-world + radius test the witness pass uses.
     */
    public void tick() {
        if (replyWindows.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        replyWindows.entrySet().removeIf(entry -> {
            ReplyWindow window = entry.getValue();
            if (now >= window.expiresAtMs()) {
                return true;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(window.botId());
            return player == null || bot == null || bot.isRemoved() || !bot.isAlive()
                    || bot.getEntityWorld() != player.getEntityWorld()
                    || bot.squaredDistanceTo(player) > EARSHOT_BLOCKS * EARSHOT_BLOCKS;
        });
    }

    /**
     * Evicts {@code playerId}'s entries from every per-player map — called on disconnect so
     * {@code lastLineByPlayer} (which retains chat text) and the rest don't linger for a player
     * who is gone.
     */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        lastLineByPlayer.remove(playerId);
        nextEligibleAtMs.remove(playerId);
        lastVerdict.remove(playerId);
        lastScore.remove(playerId);
        replyWindows.remove(playerId);
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
