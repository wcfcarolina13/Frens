package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idle-time automation for tactical furnace usage.
 */
public final class BotAutoCookingService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-auto-cooking");
    private static final Random RNG = new Random();

    private static final int STARVING_THRESHOLD = 5;
    private static final long START_COOLDOWN_TICKS = 20L * 14L;
    private static final long COLLECT_COOLDOWN_TICKS = 20L * 8L;
    private static final Map<UUID, Long> NEXT_DECISION_TICK = new ConcurrentHashMap<>();

    private BotAutoCookingService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }
        long nowTick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (!isEligible(bot)) {
                continue;
            }
            long next = NEXT_DECISION_TICK.getOrDefault(bot.getUuid(), 0L);
            if (nowTick < next) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            ServerCommandSource source = bot.getCommandSource().withSilent();
            if (SmeltingService.hasReadyFoodOutput(bot, world)) {
                if (SmeltingService.startCollectFinishedFood(bot, source)) {
                    NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + COLLECT_COOLDOWN_TICKS + RNG.nextInt(40));
                    LOGGER.info("Auto-cook: collecting finished output for {}", bot.getName().getString());
                    continue;
                }
            }

            if (bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 80L);
                continue;
            }
            if (!SmeltingService.hasCookableInventoryItems(bot, world) || !SmeltingService.hasFuelForAutoCook(bot, world)) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 120L + RNG.nextInt(80));
                continue;
            }
            if (SmeltingService.startBatchCook(bot, source, "", "auto")) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + START_COOLDOWN_TICKS + RNG.nextInt(60));
                LOGGER.info("Auto-cook: started batch cook for {}", bot.getName().getString());
            } else {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 120L);
            }
        }
    }

    private static boolean isEligible(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive() || bot.isSleeping()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getRegistryKey() != World.OVERWORLD) {
            return false;
        }
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return false;
        }
        if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
            return false;
        }
        if (BotFleeService.isBreakingFree(bot.getUuid()) || BotFleeService.isInShelter(bot.getUuid())) {
            return false;
        }
        return true;
    }
}
