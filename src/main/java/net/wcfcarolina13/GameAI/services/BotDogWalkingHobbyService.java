package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking-dogs hobby (added 2026-05-05).
 *
 * <p>When the bot is genuinely idle and walks past a sitting unnamed tamed wolf,
 * the bot toggles it to standing so the wolf will tag along while the bot does
 * other things. After a random 3–10 minute session, if the bot is back near a
 * registered base or its last-slept bed, there's a 50% chance the bot will
 * toggle the wolf back to sitting.
 *
 * <p>Composes alongside other hobbies — the bot does NOT take a TaskService
 * slot for this. The hobby only fires when the bot's idle wandering brings it
 * within direct interact reach of an eligible wolf, so the dog literally tags
 * along rather than the bot detouring to find one.
 *
 * <p>External cancellation: if anyone else (commander, another bot, another
 * player) re-sits the wolf during an active session, the session ends quietly.
 * The check is just {@code wolf.isSitting()} re-read each tick — no
 * data-tracker subscription needed.
 *
 * <p>Custom-named wolves are excluded entirely — name your wolf to opt out.
 */
public final class BotDogWalkingHobbyService {

    public static final String HOBBY_NAME = "walk_dogs";

    /** Search radius when looking for an eligible sitting wolf. */
    private static final double WOLF_PICKUP_RADIUS = 4.5D;

    /** Vanilla server-side entity-interact reach for players is ~6 blocks (squared 36).
     *  We use a tighter limit so the bot only interacts when genuinely adjacent. */
    private static final double INTERACT_REACH_SQ = 9.0D;

    /** Cooldown between pickup attempts per bot, so a bot that fails an interact
     *  doesn't hammer a wolf every tick. */
    private static final long PICKUP_RETRY_COOLDOWN_TICKS = 600L; // 30 s

    /** Session duration bounds (ticks at 20 TPS). */
    private static final long MIN_SESSION_TICKS = 60L * 20L * 3L;   // 3 min
    private static final long MAX_SESSION_TICKS = 60L * 20L * 10L;  // 10 min

    /** Distance beyond which the wolf is considered separated and the session ends. */
    private static final double SEPARATION_RADIUS_SQ = 24.0D * 24.0D;

    /** "At home" gate radius when deciding whether to sit the wolf at end of session. */
    private static final double AT_HOME_RADIUS = 16.0D;

    /** Probability of trying to sit the wolf when the session ends and the bot is at home. */
    private static final double SIT_AT_HOME_CHANCE = 0.5D;

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_PICKUP_ATTEMPT_TICK = new ConcurrentHashMap<>();

    private static final class Session {
        final UUID wolfId;
        final long endByTick;

        Session(UUID wolfId, long endByTick) {
            this.wolfId = wolfId;
            this.endByTick = endByTick;
        }
    }

    private BotDogWalkingHobbyService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();
        if (tick % 20 != 0) return;

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

            UUID botId = bot.getUuid();

            // Disable mid-session if the user toggles the hobby off — drop the session
            // without trying to sit the wolf (the wolf reverts to vanilla follow / sit
            // AI; no harm done).
            if (!BotHomeService.isHobbyEnabled(bot, HOBBY_NAME)) {
                SESSIONS.remove(botId);
                continue;
            }

            Session session = SESSIONS.get(botId);
            if (session == null) {
                considerStartingSession(server, world, bot, tick);
            } else {
                advanceSession(server, world, bot, tick, session);
            }
        }

        // Garbage-collect state for bots that are no longer in the registry.
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot != null) live.add(bot.getUuid());
        }
        SESSIONS.keySet().retainAll(live);
        LAST_PICKUP_ATTEMPT_TICK.keySet().retainAll(live);
    }

    private static void considerStartingSession(MinecraftServer server, ServerWorld world,
                                                ServerPlayerEntity bot, long tick) {
        UUID botId = bot.getUuid();

        // Only fire when the bot is genuinely idle — no active skill/task — and not in a vehicle.
        if (TaskService.hasActiveTask(botId)) return;
        if (bot.hasVehicle()) return;

        // Don't pick up wolves while inside a registered base — bot is parked at home.
        if (BotHomeService.findBaseNearPosition(server, world, bot.getBlockPos()).isPresent()) return;

        Long lastAttempt = LAST_PICKUP_ATTEMPT_TICK.get(botId);
        if (lastAttempt != null && tick - lastAttempt < PICKUP_RETRY_COOLDOWN_TICKS) return;

        WolfEntity wolf = findEligibleSittingWolf(world, bot);
        if (wolf == null) return;

        // Too far for a clean interact — the spec says no detour, so just wait until the
        // bot's idle wandering brings it closer.
        if (wolf.squaredDistanceTo(bot) > INTERACT_REACH_SQ) return;

        LAST_PICKUP_ATTEMPT_TICK.put(botId, tick);

        // Physical interaction: face the wolf, then right-click. This routes through
        // the same bot.interact() path used by other entity interactions in the mod —
        // no direct setSitting() mutation.
        LookController.faceEntity(bot, wolf);
        boolean accepted = BotActions.interactEntity(bot, wolf, Hand.MAIN_HAND);
        if (!accepted) return;

        // Confirm the toggle actually flipped — bot might have had wolf food in main
        // hand and fed the wolf instead of toggling.
        if (wolf.isSitting()) return;

        long duration = MIN_SESSION_TICKS
                + (long) (RNG.nextDouble() * (MAX_SESSION_TICKS - MIN_SESSION_TICKS));
        SESSIONS.put(botId, new Session(wolf.getUuid(), tick + duration));
    }

    private static void advanceSession(MinecraftServer server, ServerWorld world,
                                       ServerPlayerEntity bot, long tick, Session session) {
        UUID botId = bot.getUuid();
        Entity entity = world.getEntity(session.wolfId);

        // Wolf gone or dead — drop the session, nothing to clean up.
        if (!(entity instanceof WolfEntity wolf) || !wolf.isAlive()) {
            SESSIONS.remove(botId);
            return;
        }

        // External cancellation — anyone else (commander / another bot / another player)
        // sat the wolf back down. End quietly per spec.
        if (wolf.isSitting()) {
            SESSIONS.remove(botId);
            return;
        }

        // Wolf got separated (out of range for a long time, e.g. a chunk away).
        // End the session — wolf reverts to vanilla owner-follow AI.
        if (wolf.squaredDistanceTo(bot) > SEPARATION_RADIUS_SQ) {
            SESSIONS.remove(botId);
            return;
        }

        // Session timer expired — try to sit the wolf if at home, otherwise just end.
        if (tick >= session.endByTick) {
            if (isAtHome(server, world, bot) && wolf.squaredDistanceTo(bot) <= INTERACT_REACH_SQ) {
                if (RNG.nextDouble() < SIT_AT_HOME_CHANCE) {
                    LookController.faceEntity(bot, wolf);
                    BotActions.interactEntity(bot, wolf, Hand.MAIN_HAND);
                }
            }
            SESSIONS.remove(botId);
        }
    }

    /** Find a tamed, unnamed, sitting wolf within {@link #WOLF_PICKUP_RADIUS} blocks.
     *  Returns the closest match or null. */
    private static WolfEntity findEligibleSittingWolf(ServerWorld world, ServerPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(WOLF_PICKUP_RADIUS, 4.0D, WOLF_PICKUP_RADIUS);
        List<WolfEntity> wolves = world.getEntitiesByClass(
                WolfEntity.class,
                box,
                w -> w != null
                        && w.isAlive()
                        && w.isTamed()
                        && w.isSitting()
                        && !w.hasCustomName()
        );
        WolfEntity closest = null;
        double bestSq = Double.MAX_VALUE;
        for (WolfEntity w : wolves) {
            double sq = w.squaredDistanceTo(bot);
            if (sq < bestSq) {
                bestSq = sq;
                closest = w;
            }
        }
        return closest;
    }

    /** True if the bot is within {@link #AT_HOME_RADIUS} of either a registered base or
     *  its last-slept bed. */
    private static boolean isAtHome(MinecraftServer server, ServerWorld world, ServerPlayerEntity bot) {
        BlockPos botPos = bot.getBlockPos();
        if (BotHomeService.findBaseNearPosition(server, world, botPos).isPresent()) {
            return true;
        }
        Optional<BlockPos> lastBed = BotHomeService.getLastSleep(bot);
        if (lastBed.isPresent()) {
            return botPos.isWithinDistance(lastBed.get(), AT_HOME_RADIUS);
        }
        return false;
    }
}
