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

    private static final int COOK_HUNGER_THRESHOLD = 10;
    private static final long START_COOLDOWN_TICKS = 20L * 14L;
    private static final long COLLECT_COOLDOWN_TICKS = 20L * 8L;
    private static final Map<UUID, Long> NEXT_DECISION_TICK = new ConcurrentHashMap<>();
    // Cook fuel fallback (CookFuelFallbackPolicy): when the next leaf-litter run may start, and
    // which bots have one in flight.
    private static final Map<UUID, Long> NEXT_FUEL_GATHER_TICK = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> FUEL_GATHER_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Auto-cook decision gate.
     * Called at SERVER_STOPPED: a singleplayer quit keeps static state, the next world's server
     * restarts its tick counter at 0 and the same bots keep their UUIDs, so tick-based gates from
     * the old world would hold back behaviour for as long as that world had run.
     */
    public static void resetSession() {
        NEXT_DECISION_TICK.clear();
        NEXT_FUEL_GATHER_TICK.clear();
        FUEL_GATHER_IN_FLIGHT.clear();
    }


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

            int hunger = bot.getHungerManager().getFoodLevel();
            ServerCommandSource source = bot.getCommandSource().withSilent();
            if (shouldCollectReadyFood(hunger, SmeltingService.hasReadyFoodOutput(bot, world))) {
                if (SmeltingService.startCollectFinishedFood(bot, source)) {
                    NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + COLLECT_COOLDOWN_TICKS + RNG.nextInt(40));
                    LOGGER.info("Auto-cook: collecting finished output for {}", bot.getName().getString());
                    continue;
                }
            }

            if (hunger > COOK_HUNGER_THRESHOLD) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 80L);
                continue;
            }
            boolean hasCookableFood = SmeltingService.hasCookableFoodInventoryItems(bot, world);
            boolean hasFuel = SmeltingService.hasFuelForAutoCook(bot, world);
            if (!shouldStartEmergencyAutoCook(hunger, hasCookableFood, hasFuel)) {
                // No fuel only: one bounded leaf-litter run, then one more fuel check (backlog 1238).
                if (CookFuelFallbackPolicy.shouldGatherForEmergencyCook(
                                hunger <= COOK_HUNGER_THRESHOLD,
                                hasCookableFood,
                                hasFuel,
                                BotHomeService.isHobbyEnabled(bot, "leaf_litter"),
                                FUEL_GATHER_IN_FLIGHT.contains(bot.getUuid()),
                                nowTick,
                                NEXT_FUEL_GATHER_TICK.getOrDefault(bot.getUuid(), 0L),
                                () -> BotIdleHobbiesService.hasLeafLitterForFuelRun(bot))
                        && startFuelGatherThenCook(server, bot, nowTick)) {
                    continue;
                }
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 120L + RNG.nextInt(80));
                continue;
            }
            if (SmeltingService.startBatchCookFoodOnly(bot, source, "auto")) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + START_COOLDOWN_TICKS + RNG.nextInt(60));
                LOGGER.info("Auto-cook: started batch cook for {}", bot.getName().getString());
            } else {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 120L);
            }
        }
    }

    /**
     * Server thread. Starts the leaf-litter fuel run; when it ends, checks fuel once more and either
     * starts the cook or gives up exactly as the no-fuel branch does.
     */
    private static boolean startFuelGatherThenCook(MinecraftServer server, ServerPlayerEntity bot, long nowTick) {
        UUID botUuid = bot.getUuid();
        FUEL_GATHER_IN_FLIGHT.add(botUuid);
        NEXT_FUEL_GATHER_TICK.put(botUuid, CookFuelFallbackPolicy.nextGatherTick(nowTick));
        // Held off while the run takes its task slot; the completion below sets the real next tick.
        NEXT_DECISION_TICK.put(botUuid, nowTick + 200L);
        boolean submitted = BotIdleHobbiesService.startLeafLitterFuelRun(bot, mayContinue -> {
            FUEL_GATHER_IN_FLIGHT.remove(botUuid);
            long now = server.getTicks();
            ServerPlayerEntity live = server.getPlayerManager().getPlayer(botUuid);
            if (live == null || !(live.getEntityWorld() instanceof ServerWorld liveWorld)) {
                return;
            }
            boolean hasFuelNow = SmeltingService.hasFuelForAutoCook(live, liveWorld);
            if (CookFuelFallbackPolicy.shouldCookAfterGather(mayContinue, hasFuelNow)
                    && isEligible(live)
                    && shouldStartEmergencyAutoCook(live.getHungerManager().getFoodLevel(),
                            SmeltingService.hasCookableFoodInventoryItems(live, liveWorld), hasFuelNow)
                    && SmeltingService.startBatchCookFoodOnly(live, live.getCommandSource().withSilent(), "auto")) {
                NEXT_DECISION_TICK.put(botUuid, now + START_COOLDOWN_TICKS + RNG.nextInt(60));
                LOGGER.info("Auto-cook: started batch cook for {} after gathering leaf litter", live.getName().getString());
                return;
            }
            LOGGER.info("Auto-cook: {} still has no fuel to cook with after the leaf-litter run (fuel={}, continued={})",
                    live.getName().getString(), hasFuelNow, mayContinue);
            NEXT_DECISION_TICK.put(botUuid, now + 120L + RNG.nextInt(80));
        });
        if (!submitted) {
            FUEL_GATHER_IN_FLIGHT.remove(botUuid);
        }
        LOGGER.info("Auto-cook: {} has raw food but no fuel, gathering leaf litter first (submitted={})",
                bot.getName().getString(), submitted);
        return submitted;
    }

    public static void snoozeFor(ServerPlayerEntity bot, long delayTicks) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        long nowTick = server.getTicks();
        NEXT_DECISION_TICK.merge(bot.getUuid(), nowTick + Math.max(0L, delayTicks), Math::max);
    }

    static boolean shouldStartEmergencyAutoCook(int hungerLevel, boolean hasCookableFood, boolean hasFuel) {
        return hungerLevel <= COOK_HUNGER_THRESHOLD && hasCookableFood && hasFuel;
    }

    static boolean shouldCollectReadyFood(int hungerLevel, boolean hasReadyFoodOutput) {
        return hungerLevel <= COOK_HUNGER_THRESHOLD && hasReadyFoodOutput;
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
