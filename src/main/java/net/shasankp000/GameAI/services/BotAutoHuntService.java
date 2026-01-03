package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
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
 * Auto-start hunt tasks when starving (opt-in per bot).
 */
public final class BotAutoHuntService {

    private static final Logger LOGGER = LoggerFactory.getLogger("auto-hunt");
    private static final Random RNG = new Random();
    private static final int STARVING_THRESHOLD = 5;
    private static final long COOLDOWN_TICKS = 160L;
    private static final Map<UUID, Long> NEXT_DECISION_TICK = new ConcurrentHashMap<>();

    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "auto-hunt-" + THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private BotAutoHuntService() {}

    public static void requestDecisionNow(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        NEXT_DECISION_TICK.put(bot.getUuid(), (long) server.getTicks());
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
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
            if (bot.getHungerManager().getFoodLevel() > STARVING_THRESHOLD) {
                continue;
            }
            if (!HuntHistoryService.hasAnyFoodKill(world)) {
                continue;
            }

            long next = NEXT_DECISION_TICK.getOrDefault(bot.getUuid(), 0L);
            if (nowTick < next) {
                continue;
            }
            NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + COOLDOWN_TICKS + RNG.nextInt(80));

            LOGGER.info("Auto-hunt (starving) starting for {}", bot.getName().getString());
            startAutoHunt(server, bot);
        }
    }

    private static void startAutoHunt(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return;
        }
        ServerCommandSource botSource = bot.getCommandSource().withSilent();
        Map<String, Object> params = new HashMap<>();
        params.put("_origin", "system");
        params.put("open_ended", true);
        params.put("options", List.of("auto", "until_sunset"));

        EXECUTOR.submit(() -> {
            try {
                SkillContext ctx = new SkillContext(botSource, net.shasankp000.FunctionCaller.FunctionCallerV2.getSharedState(), params, botSource);
                SkillExecutionResult result = SkillManager.runSkill("hunt", ctx);
                LOGGER.info("Auto-hunt finished for {}: success={} msg='{}'",
                        bot.getName().getString(),
                        result != null && result.success(),
                        result != null ? result.message() : "null");
            } catch (Exception e) {
                LOGGER.warn("Auto-hunt crashed for {}: {}", bot.getName().getString(), e.getMessage(), e);
            }
        });
    }
}
