package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.BotEventHandler.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the four meme Emotecraft dances ({@code backflip}, {@code twerk},
 * {@code club_penguin_dance}, {@code roblox_potion_dance}) on bots. Two trigger
 * paths share the same eligibility gates and the same active-dance bookkeeping:
 *
 * <ol>
 *   <li><b>Random idle:</b> rare atmospheric trigger during truly calm idle
 *       moments. Per-bot eval every 200 ticks (10 s), 2% roll, 5-minute
 *       cooldown between rolls, capped at {@value #MAX_DANCE_DURATION_TICKS}
 *       ticks (~20 s) per dance.</li>
 *   <li><b>Jukebox-driven:</b> a {@link JukeboxBlock} with
 *       {@link Properties#HAS_RECORD HAS_RECORD=true} within
 *       {@value #JUKEBOX_HEAR_RADIUS} blocks fires a dance immediately,
 *       no cooldown or roll. The duration cap is suppressed for as long as
 *       the jukebox is still detected so the bot keeps dancing through the
 *       whole song.</li>
 * </ol>
 *
 * <p>Eligibility (both paths): mode is {@link Mode#IDLE}, no active
 * {@link TaskService} ticket, {@code !isUsingItem() && !hasVehicle() &&
 * !isSleeping()}, no hostile within 16 blocks LOS, no hostile within 8 blocks
 * regardless of LOS. Same combat-suppression model as
 * {@link BotTorchHoldService}.
 *
 * <p>Soft-dependency on Emotecraft via {@link EmotecraftBridge} — when
 * Emotecraft is missing, every {@code playEmote}/{@code stopEmote} call is a
 * no-op so this service silently does nothing.
 */
public final class BotRandomDanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger("random-dance");

    private static final long EVAL_INTERVAL_TICKS = 200L;
    private static final long MIN_INTERVAL_TICKS = 6000L;
    // Hard cap on how long a single dance is allowed to play. The Emotecraft dance
    // emotes loop indefinitely once started, so without a cap the bot would dance
    // forever (observed: bot dancing through sleep, follow, combat). 400 ticks ≈ 20 s
    // — long enough to look intentional, short enough that mistimed triggers don't
    // dominate behaviour.
    private static final long MAX_DANCE_DURATION_TICKS = 400L;
    private static final double DANCE_PROBABILITY = 0.02D;
    private static final double VISIBLE_HOSTILE_RADIUS = 16.0D;
    private static final double AUDIBLE_HOSTILE_RADIUS = 8.0D;
    /** Block radius within which a playing jukebox triggers an immediate dance. */
    private static final int JUKEBOX_HEAR_RADIUS = 12;
    /** Vertical search depth around the bot for jukebox scans (jukeboxes are floor blocks). */
    private static final int JUKEBOX_VERTICAL_RANGE = 3;
    /** Cache TTL for the jukebox-nearby probe so we don't scan O(r³) cells every tick. */
    private static final long JUKEBOX_SCAN_INTERVAL_TICKS = 10L;

    private static final EmotecraftBridge.EmoteId[] DANCES = new EmotecraftBridge.EmoteId[]{
            EmotecraftBridge.EmoteId.BACKFLIP,
            EmotecraftBridge.EmoteId.TWERK,
            EmotecraftBridge.EmoteId.CLUB_PENGUIN_DANCE,
            EmotecraftBridge.EmoteId.ROBLOX_POTION_DANCE
    };

    private static final ConcurrentHashMap<UUID, Long> LAST_DANCE_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_EVAL_TICK = new ConcurrentHashMap<>();
    // Bots with an active dance the bridge has fired but not yet stopped. Used by
    // the per-tick stop check to know which bots need state-validity scrutiny.
    private static final ConcurrentHashMap<UUID, Long> ACTIVE_DANCE_SINCE = new ConcurrentHashMap<>();
    // Whether the active dance was kicked off by a jukebox (suppresses duration cap).
    private static final java.util.Set<UUID> JUKEBOX_DRIVEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Per-bot cache for the jukebox-nearby probe. Refreshed on JUKEBOX_SCAN_INTERVAL_TICKS.
    private static final ConcurrentHashMap<UUID, Long> JUKEBOX_SCAN_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> JUKEBOX_SCAN_RESULT = new ConcurrentHashMap<>();
    private static final Random RNG = new Random();

    private BotRandomDanceService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (!EmotecraftBridge.isAvailable()) return;
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            try {
                // Stop check runs every tick — Emotecraft dance emotes loop, so we
                // need to react fast when the bot enters bed / mounts / aggros.
                tickStopCheck(bot);
                // Jukebox-driven start runs every tick (no throttle): the bot should
                // visibly react when a song starts. Random idle start is throttled.
                tickJukeboxStart(bot);
                tickRandomStart(bot);
            } catch (Exception e) {
                LOGGER.debug("random-dance tick failed for {}: {}",
                        bot.getName().getString(), e.getMessage());
            }
        }
    }

    /** Per-tick: cancel an active dance when the bot's state goes invalid or the cap expires. */
    private static void tickStopCheck(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        UUID id = bot.getUuid();
        Long since = ACTIVE_DANCE_SINCE.get(id);
        if (since == null) return;

        long nowTick = world.getTime();
        boolean stateBroken = bot.isSleeping()
                || bot.hasVehicle()
                || bot.isUsingItem()
                || BotEventHandler.getModePublic(bot) != Mode.IDLE
                || TaskService.hasActiveTask(id)
                || hasNearbyHostile(bot);

        // Jukebox-driven dances suppress the duration cap for as long as a jukebox
        // is still playing nearby — bot dances through the whole song. If the music
        // stops (HAS_RECORD turns false / record extracted), drop back to standard
        // cap behaviour and stop on the next eligible check.
        boolean jukeboxDriven = JUKEBOX_DRIVEN.contains(id);
        boolean musicStillNearby = jukeboxDriven && isJukeboxPlayingNear(bot, world, nowTick);
        boolean expired = !musicStillNearby && nowTick - since >= MAX_DANCE_DURATION_TICKS;

        if (expired || stateBroken) {
            EmotecraftBridge.stopEmote(bot);
            ACTIVE_DANCE_SINCE.remove(id);
            JUKEBOX_DRIVEN.remove(id);
            LOGGER.debug("Dance stopped bot={} reason={}",
                    bot.getName().getString(),
                    stateBroken ? "state-broken" : (jukeboxDriven ? "music-ended" : "duration-cap"));
        }
    }

    /** Per-tick: if a jukebox is playing within range, fire a dance immediately. */
    private static void tickJukeboxStart(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        UUID id = bot.getUuid();
        long nowTick = world.getTime();

        // Already dancing — nothing to do (existing dance carries through, including
        // the "jukebox suppresses cap" behaviour handled by tickStopCheck).
        if (ACTIVE_DANCE_SINCE.containsKey(id)) return;

        if (!isJukeboxPlayingNear(bot, world, nowTick)) return;

        if (BotEventHandler.getModePublic(bot) != Mode.IDLE) return;
        if (TaskService.hasActiveTask(id)) return;
        if (bot.isUsingItem() || bot.hasVehicle() || bot.isSleeping()) return;
        if (hasNearbyHostile(bot)) return;

        EmotecraftBridge.EmoteId emote = DANCES[RNG.nextInt(DANCES.length)];
        if (EmotecraftBridge.playEmote(bot, emote, true)) {
            LAST_DANCE_TICK.put(id, nowTick);
            ACTIVE_DANCE_SINCE.put(id, nowTick);
            JUKEBOX_DRIVEN.add(id);
            LOGGER.debug("Jukebox dance fired bot={} emote={}",
                    bot.getName().getString(), emote.slug);
        }
    }

    /** Throttled to EVAL_INTERVAL_TICKS: roll for a new random dance when conditions allow. */
    private static void tickRandomStart(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        UUID id = bot.getUuid();
        long nowTick = world.getTime();

        Long lastEval = LAST_EVAL_TICK.get(id);
        if (lastEval != null && nowTick - lastEval < EVAL_INTERVAL_TICKS) return;
        LAST_EVAL_TICK.put(id, nowTick);

        // Don't start a new dance while one is still active (stop check will clear it).
        if (ACTIVE_DANCE_SINCE.containsKey(id)) return;

        Long lastDance = LAST_DANCE_TICK.get(id);
        if (lastDance != null && nowTick - lastDance < MIN_INTERVAL_TICKS) return;

        if (BotEventHandler.getModePublic(bot) != Mode.IDLE) return;
        if (TaskService.hasActiveTask(id)) return;
        if (bot.isUsingItem() || bot.hasVehicle() || bot.isSleeping()) return;
        if (hasNearbyHostile(bot)) return;

        if (RNG.nextDouble() > DANCE_PROBABILITY) return;

        EmotecraftBridge.EmoteId emote = DANCES[RNG.nextInt(DANCES.length)];
        // force=true bypasses the bridge's per-emote 30 s cooldown (we have our own
        // longer 5 min per-bot cooldown for the dance bucket).
        if (EmotecraftBridge.playEmote(bot, emote, true)) {
            LAST_DANCE_TICK.put(id, nowTick);
            ACTIVE_DANCE_SINCE.put(id, nowTick);
            LOGGER.debug("Random dance fired bot={} emote={}",
                    bot.getName().getString(), emote.slug);
        }
    }

    /**
     * Cached probe for "is a jukebox actually playing music within hearing range?"
     * Refreshed on {@link #JUKEBOX_SCAN_INTERVAL_TICKS}; iterates a
     * {@code (2r+1) × (2v+1) × (2r+1)} block box around the bot with early exit
     * on first match.
     *
     * <p>Uses {@link JukeboxBlockEntity#getManager()}{@code .isPlaying()} as the
     * play-state authority — vanilla 1.21's actual playing/idle flag, distinct
     * from the {@code HAS_RECORD} block-state property (which stays true while
     * a finished disc sits in the jukebox). Previously this method used
     * {@code HAS_RECORD}, which kept the bot dancing forever after the song
     * ended until the player popped the disc.</p>
     */
    private static boolean isJukeboxPlayingNear(ServerPlayerEntity bot, ServerWorld world, long nowTick) {
        UUID id = bot.getUuid();
        Long lastScan = JUKEBOX_SCAN_TICK.get(id);
        Boolean cached = JUKEBOX_SCAN_RESULT.get(id);
        if (cached != null && lastScan != null && nowTick - lastScan < JUKEBOX_SCAN_INTERVAL_TICKS) {
            return cached;
        }
        JUKEBOX_SCAN_TICK.put(id, nowTick);

        BlockPos botPos = bot.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dy = -JUKEBOX_VERTICAL_RANGE; dy <= JUKEBOX_VERTICAL_RANGE; dy++) {
            for (int dx = -JUKEBOX_HEAR_RADIUS; dx <= JUKEBOX_HEAR_RADIUS; dx++) {
                for (int dz = -JUKEBOX_HEAR_RADIUS; dz <= JUKEBOX_HEAR_RADIUS; dz++) {
                    cursor.set(botPos.getX() + dx, botPos.getY() + dy, botPos.getZ() + dz);
                    BlockState state = world.getBlockState(cursor);
                    if (!(state.getBlock() instanceof JukeboxBlock)) continue;
                    if (!(world.getBlockEntity(cursor) instanceof JukeboxBlockEntity jbe)) continue;
                    if (jbe.getManager() != null && jbe.getManager().isPlaying()) {
                        JUKEBOX_SCAN_RESULT.put(id, true);
                        return true;
                    }
                }
            }
        }
        JUKEBOX_SCAN_RESULT.put(id, false);
        return false;
    }

    /** Combat suppression — same model as BotTorchHoldService. */
    private static boolean hasNearbyHostile(ServerPlayerEntity bot) {
        List<Entity> hostiles = BotThreatService.findHostilesAround(bot, VISIBLE_HOSTILE_RADIUS);
        for (Entity h : hostiles) {
            double distSq = h.squaredDistanceTo(bot);
            if (distSq <= AUDIBLE_HOSTILE_RADIUS * AUDIBLE_HOSTILE_RADIUS) return true;
            if (EntityVisibilityUtil.canSee(bot, h)) return true;
        }
        return false;
    }

    public static void reset() {
        LAST_DANCE_TICK.clear();
        LAST_EVAL_TICK.clear();
        ACTIVE_DANCE_SINCE.clear();
        JUKEBOX_DRIVEN.clear();
        JUKEBOX_SCAN_TICK.clear();
        JUKEBOX_SCAN_RESULT.clear();
    }
}
