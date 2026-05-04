package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.skills.impl.HuntSkill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-start hunt tasks when hunger is low (opt-in per bot).
 */
public final class BotAutoHuntService {

    private static final Logger LOGGER = LoggerFactory.getLogger("auto-hunt");
    private static final Random RNG = new Random();
    private static final int AUTO_HUNT_HUNGER_THRESHOLD = 10;
    private static final long COOLDOWN_TICKS = 160L;
    private static final long SURFACE_RECOVERY_RETRY_TICKS = 60L;
    private static final long TELEPORT_ABORT_SNOOZE_TICKS = 20L * 10L;
    private static final Map<UUID, Long> NEXT_DECISION_TICK = new ConcurrentHashMap<>();

    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
    private static volatile ExecutorService EXECUTOR = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "auto-hunt-" + THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Interrupt all in-flight auto-hunt tasks. Called during server shutdown. */
    public static void shutdownExecutors() {
        EXECUTOR.shutdownNow();
    }

    /** Re-create the auto-hunt executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (EXECUTOR.isShutdown()) {
            EXECUTOR = createExecutor();
        }
    }

    private BotAutoHuntService() {}

    public static void requestDecisionNow(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        if (BotFleeService.isSurfaceRecoveryActive(bot.getUuid())) {
            NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks() + SURFACE_RECOVERY_RETRY_TICKS);
            return;
        }
        NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks());
    }

    public static void snoozeAfterExternalTeleport(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks() + TELEPORT_ABORT_SNOOZE_TICKS);
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }
        long nowTick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!BotHomeService.isAutoHuntStarvingEnabled(bot)) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                continue;
            }
            if (bot.isSleeping()) {
                continue;
            }
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
                continue;
            }
            if (BotFleeService.isBreakingFree(bot.getUuid())) {
                continue;
            }
            if (BotFleeService.isSurfaceRecoveryActive(bot.getUuid())) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + SURFACE_RECOVERY_RETRY_TICKS);
                continue;
            }
            if (BotFleeService.isInShelter(bot.getUuid())) {
                // Auto-clear stale shelter if daytime — launch break-free to physically free the bot
                if (world.isDay() && !world.isThundering()) {
                    // fire-and-forget: break-free runs on worker thread; isBreakingFree guards next tick
                    BotFleeService.clearShelterAndBreakFree(bot);
                    continue; // let break-free finish before considering auto-hunt
                } else if (!world.isDay()
                        && bot.getHungerManager().getFoodLevel() <= AUTO_HUNT_HUNGER_THRESHOLD
                        && isZombieHuntGearReady(bot)) {
                    // Hungry and geared at night — exit shelter to hunt zombies
                    BotFleeService.clearShelterAndBreakFree(bot);
                    continue; // let break-free finish; next tick hunger+gear passes
                } else {
                    continue;
                }
            }
            // If the bot already has plenty of safe food, don't hunt — eat what you have.
            // autoEat will consume it next tick; auto-cook (same threshold) will still fire if
            // only raw food is available and furnace/fuel are ready.
            if (HuntSkill.countSafeFoodItems(bot) >= HuntSkill.MIN_BACKUP_FOOD_ITEMS) {
                continue;
            }
            int foodLevel = bot.getHungerManager().getFoodLevel();
            if (foodLevel > AUTO_HUNT_HUNGER_THRESHOLD) {
                continue;
            }
            long next = NEXT_DECISION_TICK.getOrDefault(bot.getUuid(), 0L);
            if (nowTick < next) {
                continue;
            }
            NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + COOLDOWN_TICKS + RNG.nextInt(80));

            LOGGER.info("Auto-hunt (hungry) starting for {} at food={}",
                    bot.getName().getString(),
                    bot.getHungerManager().getFoodLevel());
            startAutoHunt(server, bot);
        }
    }

    private static void startAutoHunt(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null || TaskService.isServerStopping()) {
            return;
        }
        ServerCommandSource botSource = bot.getCommandSource().withSilent();
        boolean isNight = bot.getEntityWorld() instanceof ServerWorld sw && !sw.isDay();
        Map<String, Object> params = new HashMap<>();
        params.put("_origin", "system");
        params.put("open_ended", true);
        // At night, omit until_sunset so the hunt doesn't abort on the first loop iteration
        params.put("options", isNight ? List.of("auto") : List.of("auto", "until_sunset"));

        EXECUTOR.submit(() -> {
            try {
                if (TaskService.isServerStopping()) {
                    return;
                }
                // Reach surface before hunting — underground scans miss surface mobs
                if (bot.getEntityWorld() instanceof ServerWorld sw
                        && !BotFleeService.ensureAtSurface(bot, sw)) {
                    if (BotFleeService.isSurfaceRecoveryActive(bot.getUuid())) {
                        NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks() + SURFACE_RECOVERY_RETRY_TICKS);
                        LOGGER.info("Auto-hunt: {} surface recovery already active, deferring hunt",
                                bot.getName().getString());
                        return;
                    }
                    BotEmergencyRescueService.tryEmergencyRescue(bot, sw, "auto-hunt-surface-failure");
                    // Set cooldown AFTER emergency rescue to prevent requestDecisionNow() from resetting it
                    NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks() + COOLDOWN_TICKS);
                    LOGGER.info("Auto-hunt: {} could not reach surface, aborting (cooldown {}t)",
                            bot.getName().getString(), COOLDOWN_TICKS);
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    LOGGER.info("Auto-hunt: {} surface recovery was interrupted; skipping hunt restart on stale worker",
                            bot.getName().getString());
                    return;
                }
                SkillContext ctx = new SkillContext(botSource, SharedStateService.safeSharedState("auto-hunt"), params, botSource);
                SkillExecutionResult result = SkillManager.runSkill("hunt", ctx);
                LOGGER.info("Auto-hunt finished for {}: success={} msg='{}'",
                        bot.getName().getString(),
                        result != null && result.success(),
                        result != null ? result.message() : "null");
                // Post-hunt: signal idle hobbies to prefer cooking if raw food remains
                if (HuntSkill.hasRawFood(bot)) {
                    BotIdleHobbiesService.setPreferCooking(bot.getUuid());
                }
                BotIdleHobbiesService.requestDecisionNow(bot);
            } catch (Throwable t) {
                LOGGER.warn("Auto-hunt crashed for {}: {}",
                        bot.getName().getString(),
                        t.getClass().getSimpleName(),
                        t);
            }
        });
    }

    /** True if the bot has enough health, armor, and a melee weapon to fight zombies. */
    private static boolean isZombieHuntGearReady(ServerPlayerEntity bot) {
        return bot.getHealth() >= 16.0f
                && (!bot.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
                        || !bot.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
                        || !bot.getEquippedStack(EquipmentSlot.LEGS).isEmpty()
                        || !bot.getEquippedStack(EquipmentSlot.FEET).isEmpty())
                && BotActions.hasMeleeWeapon(bot);
    }
}
