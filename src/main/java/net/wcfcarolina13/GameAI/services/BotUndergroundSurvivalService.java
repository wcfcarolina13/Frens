package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodic safety net for idle bots stuck underground.
 *
 * <p>When idle hobbies and auto-hunt are both disabled (their defaults), no service
 * evaluates surface recovery for an idle underground bot.  This service fills that
 * gap by calling {@link BotFleeService#ensureAtSurface} on a worker thread every
 * 10 seconds for each qualifying bot.  The full linger decision tree inside
 * {@code shouldSuppressSurfaceRecovery()} still controls whether the bot actually
 * surfaces.</p>
 */
public final class BotUndergroundSurvivalService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-underground-survival");

    /** How often to evaluate each bot (200 ticks = 10 seconds). */
    private static final long CHECK_INTERVAL_TICKS = 200L;

    /** Per-bot throttle: next tick at which a check is allowed. */
    private static final Map<UUID, Long> NEXT_CHECK_TICK = new ConcurrentHashMap<>();

    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
    private static volatile ExecutorService EXECUTOR = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "underground-survival-" + THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private BotUndergroundSurvivalService() {}

    public static void shutdownExecutors() {
        EXECUTOR.shutdownNow();
    }

    /** Re-create the underground survival executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (EXECUTOR.isShutdown()) {
            EXECUTOR = createExecutor();
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) return;
        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            UUID botId = bot.getUuid();

            // Throttle: one check per bot per interval
            if (nowTick < NEXT_CHECK_TICK.getOrDefault(botId, 0L)) continue;
            NEXT_CHECK_TICK.put(botId, nowTick + CHECK_INTERVAL_TICKS);

            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;
            if (world.getRegistryKey() != World.OVERWORLD) continue;

            // Only idle bots — don't interrupt tasks or active movement
            if (TaskService.hasActiveTask(botId)) continue;
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) continue;

            // Already at surface — nothing to do
            if (BotFleeService.isAtSurface(bot, world)) continue;

            // Another service already handling recovery — don't dogpile
            if (BotFleeService.isSurfaceRecoveryActive(botId)) continue;
            if (BotFleeService.isBreakingFree(botId)) continue;
            if (BotFleeService.isInShelter(botId)) continue;

            // Dispatch to worker thread (ensureAtSurface is blocking)
            EXECUTOR.submit(() -> {
                try {
                    if (TaskService.isServerStopping()) return;
                    if (bot.isRemoved() || !bot.isAlive()) return;
                    BotFleeService.ensureAtSurface(bot, world);
                } catch (Throwable t) {
                    LOGGER.warn("Underground survival check failed for {}: {}",
                            bot.getName().getString(), t.getMessage());
                }
            });
        }
    }
}
