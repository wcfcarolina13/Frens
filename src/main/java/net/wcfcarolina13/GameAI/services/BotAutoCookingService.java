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
    // At most one "why no cook" line per bot per minute while it is hungry, so a field run can see
    // which gate held auto-cook back.
    private static final long DIAGNOSTIC_LOG_TICKS = 20L * 60L;
    private static final Map<UUID, Long> NEXT_DIAGNOSTIC_TICK = new ConcurrentHashMap<>();
    /**
     * Cached answer of {@link #canCookRawMeatSoon}: {tick, 1 or 0, fuel run in flight 1 or 0}. The
     * check scans for furnaces. An entry is used only while the bot's fuel-run state still matches,
     * so a false cached just before or after a run is not served across it.
     */
    private static final long COOK_SOON_CACHE_TICKS = 100L;
    private static final Map<UUID, long[]> COOK_SOON_CACHE = new ConcurrentHashMap<>();

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
        NEXT_DIAGNOSTIC_TICK.clear();
        COOK_SOON_CACHE.clear();
    }


    private BotAutoCookingService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }
        long nowTick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            String ineligible = ineligibleReason(bot);
            if (ineligible != null) {
                if (bot != null && bot.isAlive() && bot.getHungerManager().getFoodLevel() <= COOK_HUNGER_THRESHOLD) {
                    logDiagnostic(bot, nowTick, "not eligible: " + ineligible);
                }
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
                if (hasCookableFood && !hasFuel) {
                    UUID id = bot.getUuid();
                    logDiagnostic(bot, nowTick, "raw food but no fuel, no leaf-litter run (hobby="
                            + BotHomeService.isHobbyEnabled(bot, "leaf_litter")
                            + " inFlight=" + FUEL_GATHER_IN_FLIGHT.contains(id)
                            + " cooldownTicks=" + Math.max(0L, NEXT_FUEL_GATHER_TICK.getOrDefault(id, 0L) - nowTick)
                            + " litterNearby=" + BotIdleHobbiesService.hasLeafLitterForFuelRun(bot) + ")");
                } else if (!hasCookableFood) {
                    logDiagnostic(bot, nowTick, "nothing cookable on the bot (food=" + hunger + ")");
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
            int foodNow = live.getHungerManager().getFoodLevel();
            boolean cookableNow = SmeltingService.hasCookableFoodInventoryItems(live, liveWorld);
            if (CookFuelFallbackPolicy.shouldCookAfterGather(mayContinue, hasFuelNow)
                    && isEligible(live)
                    && shouldStartEmergencyAutoCook(foodNow, cookableNow, hasFuelNow)
                    && SmeltingService.startBatchCookFoodOnly(live, live.getCommandSource().withSilent(), "auto")) {
                NEXT_DECISION_TICK.put(botUuid, now + START_COOLDOWN_TICKS + RNG.nextInt(60));
                LOGGER.info("Auto-cook: started batch cook for {} after gathering leaf litter", live.getName().getString());
                return;
            }
            LOGGER.info("Auto-cook: no cook for {} after the leaf-litter run: {} (fuel={}, continued={}, food={})",
                    live.getName().getString(),
                    CookSoonEligibilityPolicy.noCookAfterRunReason(mayContinue, hasFuelNow, ineligibleReason(live),
                            foodNow, COOK_HUNGER_THRESHOLD, cookableNow),
                    hasFuelNow, mayContinue, foodNow);
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

    /**
     * Whether auto-cook could cook this bot's raw food soon: it may run for the bot now, the bot
     * has something cookable, it has fuel (or a leaf-litter fuel run is available), and a furnace
     * or smoker is nearby. {@link HealingService#autoEat} holds raw meat only while this is true.
     * Cached for {@link #COOK_SOON_CACHE_TICKS}; off the server thread only the cache is used
     * (no world scan there), and without a fresh answer the bot eats as before.
     */
    public static boolean canCookRawMeatSoon(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        long nowTick = server.getTicks();
        long inFlight = FUEL_GATHER_IN_FLIGHT.contains(bot.getUuid()) ? 1L : 0L;
        long[] cached = COOK_SOON_CACHE.get(bot.getUuid());
        if (cached != null && cached.length > 2 && cached[2] == inFlight
                && nowTick - cached[0] >= 0 && nowTick - cached[0] < COOK_SOON_CACHE_TICKS) {
            return cached[1] == 1L;
        }
        if (!server.isOnThread()) {
            return false;
        }
        boolean result = computeCanCookRawMeatSoon(bot, nowTick);
        COOK_SOON_CACHE.put(bot.getUuid(), new long[] {nowTick, result ? 1L : 0L, inFlight});
        return result;
    }

    private static boolean computeCanCookRawMeatSoon(ServerPlayerEntity bot, long nowTick) {
        UUID id = bot.getUuid();
        // Auto-cook's own leaf-litter run makes the bot look busy ("task running"); the hold must
        // last through it, or the bot eats the meat it is fetching fuel to cook.
        if (!CookSoonEligibilityPolicy.allows(ineligibleReason(bot), FUEL_GATHER_IN_FLIGHT.contains(id))
                || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!SmeltingService.hasCookableFoodInventoryItems(bot, world)) {
            return false;
        }
        boolean fuel = SmeltingService.hasFuelForAutoCook(bot, world)
                || (BotHomeService.isHobbyEnabled(bot, "leaf_litter")
                        && (FUEL_GATHER_IN_FLIGHT.contains(id)
                                || (nowTick >= NEXT_FUEL_GATHER_TICK.getOrDefault(id, 0L)
                                        && BotIdleHobbiesService.hasLeafLitterForFuelRun(bot))));
        return fuel && SmeltingService.hasFoodStationNear(bot, world);
    }

    static boolean shouldStartEmergencyAutoCook(int hungerLevel, boolean hasCookableFood, boolean hasFuel) {
        return hungerLevel <= COOK_HUNGER_THRESHOLD && hasCookableFood && hasFuel;
    }

    static boolean shouldCollectReadyFood(int hungerLevel, boolean hasReadyFoodOutput) {
        return hungerLevel <= COOK_HUNGER_THRESHOLD && hasReadyFoodOutput;
    }

    private static boolean isEligible(ServerPlayerEntity bot) {
        return ineligibleReason(bot) == null;
    }

    /** Why auto-cook skips this bot now, or null when it may decide. */
    private static String ineligibleReason(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return "gone";
        }
        if (bot.isSleeping()) {
            return "sleeping";
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return "no server world";
        }
        if (world.getRegistryKey() != World.OVERWORLD) {
            return "not in the Overworld";
        }
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return CookSoonEligibilityPolicy.TASK_RUNNING;
        }
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (mode != BotEventHandler.Mode.IDLE) {
            return CookSoonEligibilityPolicy.MODE_PREFIX + mode;
        }
        if (BotFleeService.isBreakingFree(bot.getUuid()) || BotFleeService.isInShelter(bot.getUuid())) {
            return "fleeing or sheltering";
        }
        return null;
    }

    private static void logDiagnostic(ServerPlayerEntity bot, long nowTick, String why) {
        UUID id = bot.getUuid();
        if (nowTick < NEXT_DIAGNOSTIC_TICK.getOrDefault(id, 0L)) {
            return;
        }
        NEXT_DIAGNOSTIC_TICK.put(id, nowTick + DIAGNOSTIC_LOG_TICKS);
        LOGGER.info("Auto-cook: no cook for {} at food {}: {}",
                bot.getName().getString(), bot.getHungerManager().getFoodLevel(), why);
    }
}
