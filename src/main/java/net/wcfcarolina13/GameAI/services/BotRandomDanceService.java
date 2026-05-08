package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.BotEventHandler.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atmospheric "rare meme idle" service that occasionally has bots play one of
 * the four pure-meme Emotecraft dances ({@code backflip}, {@code twerk},
 * {@code club_penguin_dance}, {@code roblox_potion_dance}) during truly calm
 * idle moments.
 *
 * <p>Conditions (all must hold):
 * <ul>
 *   <li>Mode is {@link Mode#IDLE} (not following, not on a task).</li>
 *   <li>No active {@link TaskService} ticket.</li>
 *   <li>{@code !isUsingItem() && !hasVehicle() && !isSleeping()}.</li>
 *   <li>No hostile within 16 blocks LOS, no hostile within 8 blocks regardless
 *       of LOS (same combat-suppression model as
 *       {@link BotTorchHoldService}).</li>
 * </ul>
 *
 * <p>Cadence: per-bot evaluation every 200 ticks (10 s). When eligible, a 2%
 * roll per evaluation fires a dance. Hard cooldown of 6000 ticks (5 minutes)
 * between dances per bot prevents back-to-back triggers. Combined avg interval
 * is ~10 minutes of eligible idle time before a dance.
 *
 * <p>Soft-dependency on Emotecraft via {@link EmotecraftBridge} — when
 * Emotecraft is missing, every {@code playEmote} call is a no-op so this
 * service silently does nothing.
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
                // Start check is throttled to EVAL_INTERVAL_TICKS.
                tickStartCheck(bot);
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
        boolean expired = nowTick - since >= MAX_DANCE_DURATION_TICKS;
        boolean stateBroken = bot.isSleeping()
                || bot.hasVehicle()
                || bot.isUsingItem()
                || BotEventHandler.getModePublic(bot) != Mode.IDLE
                || TaskService.hasActiveTask(id)
                || hasNearbyHostile(bot);

        if (expired || stateBroken) {
            EmotecraftBridge.stopEmote(bot);
            ACTIVE_DANCE_SINCE.remove(id);
            LOGGER.debug("Dance stopped bot={} reason={}",
                    bot.getName().getString(), expired ? "duration-cap" : "state-broken");
        }
    }

    /** Throttled to EVAL_INTERVAL_TICKS: roll for a new dance when conditions allow. */
    private static void tickStartCheck(ServerPlayerEntity bot) {
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
    }
}
