package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Same-owner food support between bots and shared tactical chests.
 */
public final class BotMutualAidService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-mutual-aid");

    private static final int STARVING_THRESHOLD = 5;
    private static final int HUNGRY_THRESHOLD = 14;
    private static final int DONOR_MIN_FOOD_LEVEL = 10;
    private static final int COMFORTABLE_HUNGER_THRESHOLD = 17;
    private static final double CHEST_RADIUS_SQ = 12.0D * 12.0D;
    private static final int CHEST_SEARCH_RADIUS = 12;
    private static final int CHEST_SEARCH_YSPAN = 4;
    private static final double BASE_STORAGE_RADIUS = 32.0D;
    private static final long RECENT_SLEEP_WINDOW_MS = 1000L * 60L * 60L * 12L;
    private static final double SHARE_RADIUS = 6.0D;
    private static final double SHARE_RADIUS_SQ = SHARE_RADIUS * SHARE_RADIUS;
    private static final double DISTANT_AID_RADIUS = 28.0D;
    private static final double DISTANT_AID_RADIUS_SQ = DISTANT_AID_RADIUS * DISTANT_AID_RADIUS;
    private static final double HANDOFF_KEEPALIVE_RADIUS = 8.0D;
    private static final double HANDOFF_KEEPALIVE_RADIUS_SQ = HANDOFF_KEEPALIVE_RADIUS * HANDOFF_KEEPALIVE_RADIUS;
    private static final double DROPPED_FOOD_RESCUE_RADIUS = 3.5D;
    private static final double DROPPED_FOOD_RESCUE_RADIUS_SQ = DROPPED_FOOD_RESCUE_RADIUS * DROPPED_FOOD_RESCUE_RADIUS;
    private static final double REGROUP_RADIUS = 12.0D;
    private static final double REGROUP_RADIUS_SQ = REGROUP_RADIUS * REGROUP_RADIUS;
    private static final double REGROUP_MAX_RADIUS = 48.0D;
    private static final double REGROUP_MAX_RADIUS_SQ = REGROUP_MAX_RADIUS * REGROUP_MAX_RADIUS;
    private static final double HANDOFF_THREAT_RADIUS = 8.0D;
    private static final double DEFENSE_SCAN_RADIUS = 24.0D;
    private static final double DEFENSE_SCAN_RADIUS_SQ = DEFENSE_SCAN_RADIUS * DEFENSE_SCAN_RADIUS;
    private static final double ALLY_THREAT_RADIUS = 8.0D;
    private static final double DEFENSE_ENGAGE_REACH_SQ = 7.0D * 7.0D;
    private static final long CHEST_COOLDOWN_TICKS = 20L * 8L;
    private static final long SHARE_COOLDOWN_TICKS = 20L * 5L;
    private static final long HANDSHAKE_TIMEOUT_TICKS = 20L * 4L;
    private static final long HANDSHAKE_HOLD_TICKS = 12L;
    private static final long AID_CHECK_COOLDOWN_TICKS = 20L * 30L;
    private static final long SEEK_AID_COOLDOWN_TICKS = 20L * 6L;
    private static final long REGROUP_COOLDOWN_TICKS = 20L * 20L;
    private static final long DEFENSE_COOLDOWN_TICKS = 20L * 4L;
    private static final long FLOWER_COOLDOWN_TICKS = 20L * 90L;
    private static final long STATE_CLEANUP_INTERVAL_TICKS = 20L * 60L;

    private static final Map<UUID, Long> NEXT_CHEST_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_SHARE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_SEEK_AID_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_REGROUP_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_DEFENSE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_FLOWER_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingHandoff> PENDING_HANDOFFS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> DONOR_PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> LAST_INTERACTION_BOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_INTERACTION_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Long>> BOT_AID_CHECK_COOLDOWNS = new ConcurrentHashMap<>();
    private static volatile long NEXT_STATE_CLEANUP_TICK = 0L;

    private BotMutualAidService() {
    }

    private enum AidKind {
        FOOD,
        GEAR,
        FLOWER
    }

    private record DefenseRequest(ServerPlayerEntity ally, Entity hostile, int priority) {
    }

    private record PendingHandoff(UUID donorId,
                                  UUID recipientId,
                                  long createdTick,
                                  long acknowledgedSinceTick,
                                  AidKind kind,
                                  int itemCount) {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }
        long nowTick = server.getTicks();
        if (nowTick >= NEXT_STATE_CLEANUP_TICK) {
            cleanupStaleState(server, nowTick);
            NEXT_STATE_CLEANUP_TICK = nowTick + STATE_CLEANUP_INTERVAL_TICKS;
        }
        processPendingHandoffs(server, nowTick);
        processDefensiveSupport(server, nowTick);
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (!isChestAidEligible(bot)) {
                continue;
            }
            ServerWorld world = (ServerWorld) bot.getEntityWorld();
            if (isPendingParticipant(bot.getUuid())) {
                continue;
            }

            if (bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD
                    && bot.getInventory().getEmptySlot() == -1
                    && tryMakeSpaceForNearbyDroppedFood(bot, world)) {
                continue;
            }

            if (bot.getHungerManager().getFoodLevel() <= HUNGRY_THRESHOLD) {
                long nextChest = NEXT_CHEST_TICK.getOrDefault(bot.getUuid(), 0L);
                if (nowTick >= nextChest && tryTakeFoodFromSharedChest(bot, world)) {
                    NEXT_CHEST_TICK.put(bot.getUuid(), nowTick + CHEST_COOLDOWN_TICKS);
                    HealingService.autoEat(bot);
                    continue;
                }
            }

            if (!isEligible(bot)) {
                continue;
            }

            long nextShare = NEXT_SHARE_TICK.getOrDefault(bot.getUuid(), 0L);
            if (nowTick >= nextShare) {
                boolean shared = tryShareFoodWithNearbyBot(bot, world, nowTick)
                        || tryShareGearWithNearbyBot(bot, world, nowTick);
                if (shared) {
                    NEXT_SHARE_TICK.put(bot.getUuid(), nowTick + SHARE_COOLDOWN_TICKS);
                    continue;
                }
            }

            long nextFlower = NEXT_FLOWER_TICK.getOrDefault(bot.getUuid(), 0L);
            if (nowTick >= nextFlower && tryShareFlowerWithNearbyBot(bot, world, nowTick)) {
                NEXT_FLOWER_TICK.put(bot.getUuid(), nowTick + FLOWER_COOLDOWN_TICKS);
                NEXT_SHARE_TICK.put(bot.getUuid(), nowTick + SHARE_COOLDOWN_TICKS);
            }
        }
    }

    public static boolean trySeekFoodFromNearbyBot(ServerPlayerEntity seeker, ServerWorld world) {
        if (seeker == null || world == null || seeker.getCommandSource() == null) {
            return false;
        }
        MinecraftServer server = seeker.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        long nowTick = server.getTicks();
        long nextAllowed = NEXT_SEEK_AID_TICK.getOrDefault(seeker.getUuid(), 0L);
        if (nowTick < nextAllowed || isPendingParticipant(seeker.getUuid())) {
            return false;
        }

        ServerPlayerEntity donor = world.getPlayers().stream()
                .filter(other -> other != null
                        && other != seeker
                        && BotEventHandler.isRegisteredBot(other)
                        && !other.isRemoved()
                        && other.isAlive()
                        && !other.isSleeping()
                        && seeker.squaredDistanceTo(other) <= DISTANT_AID_RADIUS_SQ
                        && sharesOwner(seeker, other)
                        && seeker.canSee(other)
                        && !isAidCheckCoolingDown(seeker, other, nowTick))
                .min(Comparator.comparingDouble(seeker::squaredDistanceTo))
                .orElse(null);
        if (donor == null) {
            NEXT_SEEK_AID_TICK.put(seeker.getUuid(), nowTick + 40L);
            return false;
        }

        rememberInteraction(seeker, donor, nowTick);
        if (!approachBot(seeker, donor, SHARE_RADIUS_SQ, 3_200L, "mutual-aid-seek-food")) {
            setAidCheckCooldown(seeker, donor, nowTick + AID_CHECK_COOLDOWN_TICKS);
            NEXT_SEEK_AID_TICK.put(seeker.getUuid(), nowTick + SEEK_AID_COOLDOWN_TICKS);
            return false;
        }

        rememberInteraction(seeker, donor, nowTick);
        if (pickAidInventorySlot(donor, seeker, AidKind.FOOD) < 0) {
            setAidCheckCooldown(seeker, donor, nowTick + AID_CHECK_COOLDOWN_TICKS);
            NEXT_SEEK_AID_TICK.put(seeker.getUuid(), nowTick + SEEK_AID_COOLDOWN_TICKS);
            LOGGER.info("Mutual aid: {} checked {} for food, but nothing spare was available",
                    seeker.getName().getString(),
                    donor.getName().getString());
            return false;
        }

        int transferCount = determineFoodHandoffCount(donor, seeker);
        registerPendingHandoff(new PendingHandoff(donor.getUuid(), seeker.getUuid(), nowTick, -1L, AidKind.FOOD, transferCount));
        NEXT_SEEK_AID_TICK.put(seeker.getUuid(), nowTick + SHARE_COOLDOWN_TICKS);
        LOGGER.info("Mutual aid: {} approached {} for food support",
                seeker.getName().getString(),
                donor.getName().getString());
        return true;
    }

    public static boolean tryImmediateChestFoodRecovery(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        long nowTick = server != null ? server.getTicks() : 0L;
        long nextChest = NEXT_CHEST_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextChest) {
            return false;
        }
        if (!tryTakeFoodFromSharedChest(bot, world)) {
            return false;
        }
        NEXT_CHEST_TICK.put(bot.getUuid(), nowTick + CHEST_COOLDOWN_TICKS);
        HealingService.autoEat(bot);
        return true;
    }

    public static boolean tryUrgentFoodRecovery(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        if (HealingService.autoEat(bot)) {
            return true;
        }
        if (tryImmediateChestFoodRecovery(bot, world)) {
            return true;
        }
        return trySeekFoodFromNearbyBot(bot, world);
    }

    public static boolean tryRegroupWithLastInteraction(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        long nowTick = server.getTicks();
        long nextAllowed = NEXT_REGROUP_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextAllowed) {
            return false;
        }

        UUID lastBotId = LAST_INTERACTION_BOT.get(bot.getUuid());
        if (lastBotId == null) {
            return false;
        }
        ServerPlayerEntity other = server.getPlayerManager().getPlayer(lastBotId);
        if (other == null || other == bot || other.isRemoved() || !other.isAlive() || other.getEntityWorld() != world) {
            return false;
        }
        if (!sharesOwner(bot, other)) {
            return false;
        }
        double distSq = bot.squaredDistanceTo(other);
        if (distSq <= REGROUP_RADIUS_SQ || distSq > REGROUP_MAX_RADIUS_SQ) {
            return false;
        }
        boolean moved = approachBot(bot, other, REGROUP_RADIUS_SQ, 2_800L, "mutual-aid-regroup");
        NEXT_REGROUP_TICK.put(bot.getUuid(), nowTick + REGROUP_COOLDOWN_TICKS);
        if (moved) {
            rememberInteraction(bot, other, nowTick);
            LOGGER.info("Mutual aid: {} regrouped toward {} before sunset",
                    bot.getName().getString(),
                    other.getName().getString());
        }
        return moved;
    }

    private static void cleanupStaleState(MinecraftServer server, long nowTick) {
        Set<UUID> knownBots = new HashSet<>();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot != null && !bot.isRemoved()) {
                knownBots.add(bot.getUuid());
            }
        }
        pruneMap(NEXT_CHEST_TICK, knownBots);
        pruneMap(NEXT_SHARE_TICK, knownBots);
        pruneMap(NEXT_SEEK_AID_TICK, knownBots);
        pruneMap(NEXT_REGROUP_TICK, knownBots);
        pruneMap(NEXT_DEFENSE_TICK, knownBots);
        pruneMap(NEXT_FLOWER_TICK, knownBots);
        pruneMap(DONOR_PENDING, knownBots);
        pruneMap(LAST_INTERACTION_BOT, knownBots);
        pruneMap(LAST_INTERACTION_TICK, knownBots);
        PENDING_HANDOFFS.entrySet().removeIf(entry -> {
            PendingHandoff handoff = entry.getValue();
            return handoff == null
                    || !knownBots.contains(handoff.donorId())
                    || !knownBots.contains(handoff.recipientId())
                    || nowTick - handoff.createdTick() > HANDSHAKE_TIMEOUT_TICKS;
        });
        BOT_AID_CHECK_COOLDOWNS.entrySet().removeIf(entry -> !knownBots.contains(entry.getKey()));
        for (Map<UUID, Long> perBot : BOT_AID_CHECK_COOLDOWNS.values()) {
            if (perBot == null) {
                continue;
            }
            perBot.entrySet().removeIf(entry -> !knownBots.contains(entry.getKey()) || nowTick >= entry.getValue());
        }
    }

    private static <T> void pruneMap(Map<UUID, T> map, Set<UUID> knownBots) {
        if (map == null || knownBots == null) {
            return;
        }
        map.keySet().removeIf(uuid -> uuid == null || !knownBots.contains(uuid));
    }

    private static void processDefensiveSupport(MinecraftServer server, long nowTick) {
        for (ServerPlayerEntity defender : BotEventHandler.getRegisteredBots(server)) {
            if (!isDefenseEligible(defender)) {
                continue;
            }
            if (isPendingParticipant(defender.getUuid())) {
                continue;
            }
            long nextAllowed = NEXT_DEFENSE_TICK.getOrDefault(defender.getUuid(), 0L);
            if (nowTick < nextAllowed) {
                continue;
            }
            if (hasNearbyThreats(defender, HANDOFF_THREAT_RADIUS)) {
                NEXT_DEFENSE_TICK.put(defender.getUuid(), nowTick + 20L);
                continue;
            }
            if (!(defender.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            DefenseRequest request = findDefenseRequest(defender, world);
            if (request == null) {
                continue;
            }
            if (!prepareForDefense(defender)) {
                NEXT_DEFENSE_TICK.put(defender.getUuid(), nowTick + 40L);
                continue;
            }
            if (respondToDefenseRequest(defender, request, nowTick)) {
                NEXT_DEFENSE_TICK.put(defender.getUuid(), nowTick + DEFENSE_COOLDOWN_TICKS);
            } else {
                NEXT_DEFENSE_TICK.put(defender.getUuid(), nowTick + 40L);
            }
        }
    }

    private static boolean isEligible(ServerPlayerEntity bot) {
        if (!isChestAidEligible(bot)) {
            return false;
        }
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return false;
        }
        if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
            return false;
        }
        return !BotFleeService.isBreakingFree(bot.getUuid()) && !BotFleeService.isInShelter(bot.getUuid());
    }

    private static boolean isChestAidEligible(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive() || bot.isSleeping()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getRegistryKey() != World.OVERWORLD) {
            return false;
        }
        return !bot.hasVehicle();
    }

    private static boolean isDefenseEligible(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive() || bot.isSleeping()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (world.getRegistryKey() != World.OVERWORLD) {
            return false;
        }
        if (BotFleeService.isBreakingFree(bot.getUuid()) || BotFleeService.isInShelter(bot.getUuid())) {
            return false;
        }
        if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
            return false;
        }
        var activeTask = TaskService.getActiveTaskInfo(bot.getUuid());
        if (activeTask.isEmpty()) {
            return isCombatCapable(bot);
        }
        return activeTask.get().origin() == TaskService.Origin.AMBIENT
                && activeTask.get().openEnded()
                && isCombatCapable(bot);
    }

    private static boolean isCombatCapable(ServerPlayerEntity bot) {
        return bot != null
                && bot.getHungerManager().getFoodLevel() > STARVING_THRESHOLD
                && (hasUsableWeapon(bot) || bot.getArmor() >= 6);
    }

    private static DefenseRequest findDefenseRequest(ServerPlayerEntity defender, ServerWorld world) {
        if (defender == null || world == null) {
            return null;
        }
        DefenseRequest best = null;
        for (ServerPlayerEntity ally : world.getPlayers()) {
            if (ally == null || ally == defender || ally.isRemoved() || !ally.isAlive()) {
                continue;
            }
            if (!BotEventHandler.isRegisteredBot(ally) || !sharesOwner(defender, ally)) {
                continue;
            }
            if (defender.squaredDistanceTo(ally) > DEFENSE_SCAN_RADIUS_SQ) {
                continue;
            }
            if (!needsDefensiveSupport(ally) || !isMeaningfullyStronger(defender, ally)) {
                continue;
            }
            Entity hostile = BotThreatService.findHostilesAround(ally, ALLY_THREAT_RADIUS).stream()
                    .filter(entity -> entity != null && entity.isAlive() && !entity.isRemoved())
                    .min(Comparator.comparingDouble(ally::squaredDistanceTo))
                    .orElse(null);
            if (hostile == null) {
                continue;
            }
            int priority = defensePriority(ally);
            if (best == null || priority > best.priority()
                    || (priority == best.priority()
                    && defender.squaredDistanceTo(ally) < defender.squaredDistanceTo(best.ally()))) {
                best = new DefenseRequest(ally, hostile, priority);
            }
        }
        return best;
    }

    private static boolean needsDefensiveSupport(ServerPlayerEntity ally) {
        if (ally == null) {
            return false;
        }
        if (BotThreatService.findHostilesAround(ally, ALLY_THREAT_RADIUS).isEmpty()) {
            return false;
        }
        return ally.getHungerManager().getFoodLevel() <= HUNGRY_THRESHOLD
                || isHuntTaskActive(ally)
                || ally.getHealth() < ally.getMaxHealth() * 0.75F;
    }

    private static int defensePriority(ServerPlayerEntity ally) {
        int score = 0;
        if (ally == null) {
            return score;
        }
        if (isHuntTaskActive(ally)) {
            score += 4;
        }
        if (ally.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD) {
            score += 4;
        } else if (ally.getHungerManager().getFoodLevel() <= HUNGRY_THRESHOLD) {
            score += 2;
        }
        if (ally.getHealth() < ally.getMaxHealth() * 0.5F) {
            score += 2;
        }
        return score;
    }

    private static boolean isMeaningfullyStronger(ServerPlayerEntity defender, ServerPlayerEntity ally) {
        if (defender == null || ally == null) {
            return false;
        }
        if (hasUsableWeapon(defender) && !hasUsableWeapon(ally)) {
            return true;
        }
        return combatReadinessScore(defender) >= combatReadinessScore(ally) + 4;
    }

    private static int combatReadinessScore(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int bestWeapon = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            bestWeapon = Math.max(bestWeapon, weaponScore(bot.getInventory().getStack(i)));
        }
        return bestWeapon + bot.getArmor() + Math.round(bot.getHealth() / 2.0F) + (bot.getHungerManager().getFoodLevel() / 3);
    }

    private static boolean isHuntTaskActive(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        return TaskService.getActiveTaskName(bot.getUuid())
                .map(name -> name != null && name.toLowerCase(Locale.ROOT).contains("skill:hunt"))
                .orElse(false);
    }

    private static boolean prepareForDefense(ServerPlayerEntity defender) {
        if (defender == null) {
            return false;
        }
        var activeTask = TaskService.getActiveTaskInfo(defender.getUuid());
        if (activeTask.isPresent() && activeTask.get().origin() == TaskService.Origin.AMBIENT) {
            return TaskService.interruptAmbientTask(defender.getUuid(), "§eBreaking off ambient task to defend an ally.");
        }
        return !TaskService.hasActiveTask(defender.getUuid());
    }

    private static boolean respondToDefenseRequest(ServerPlayerEntity defender, DefenseRequest request, long nowTick) {
        if (defender == null || request == null || request.ally() == null) {
            return false;
        }
        Entity hostile = request.hostile();
        ServerPlayerEntity ally = request.ally();
        if (hostile == null || hostile.isRemoved() || !hostile.isAlive()) {
            return approachBot(defender, ally, REGROUP_RADIUS_SQ, 1_600L, "mutual-aid-defend-ally");
        }
        boolean closeEnough = defender.squaredDistanceTo(hostile) <= DEFENSE_ENGAGE_REACH_SQ;
        if (!closeEnough) {
            closeEnough = MovementService.nudgeTowardUntilClose(
                    defender,
                    hostile.getBlockPos(),
                    DEFENSE_ENGAGE_REACH_SQ,
                    2_400L,
                    0.20D,
                    "mutual-aid-defend-threat"
            );
        }
        if (closeEnough) {
            LookController.faceEntity(defender, hostile);
            BotActions.selectBestWeapon(defender);
            BotActions.attackTarget(defender, hostile);
            rememberInteraction(defender, ally, nowTick);
            LOGGER.info("Mutual aid: {} moved to defend {} against {}",
                    defender.getName().getString(),
                    ally.getName().getString(),
                    hostile.getName().getString());
            return true;
        }
        return false;
    }

    private static boolean hasNearbyThreats(ServerPlayerEntity bot, double radius) {
        return bot != null && !BotThreatService.findHostilesAround(bot, radius).isEmpty();
    }

    private static boolean tryTakeFoodFromSharedChest(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        for (BlockPos chestPos : collectCandidateFoodChests(bot, world)) {
            if (!BlockInteractionService.canInteract(bot, chestPos)) {
                continue;
            }
            var be = world.getBlockEntity(chestPos);
            if (!(be instanceof Inventory inv)) {
                continue;
            }
            int slot = pickBestFoodSlot(inv, bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD);
            if (slot < 0) {
                continue;
            }
            ensureInventorySpaceForChestFood(bot);
            ItemStack preview = inv.getStack(slot);
            ItemStack taken = inv.removeStack(slot, determineChestFoodWithdrawCount(preview, bot));
            if (taken.isEmpty()) {
                continue;
            }
            if (!bot.getInventory().insertStack(taken)) {
                inv.setStack(slot, taken);
                inv.markDirty();
                continue;
            }
            inv.markDirty();
            bot.getInventory().markDirty();
            BotChestRegistryService.updateContentsSnapshot(bot, chestPos, world, inv);
            LOGGER.info("Mutual aid: {} took {} from shared chest at {}",
                    bot.getName().getString(),
                    taken.getName().getString(),
                    chestPos.toShortString());
            return true;
        }
        return false;
    }

    private static boolean tryShareFoodWithNearbyBot(ServerPlayerEntity donor, ServerWorld world, long nowTick) {
        if (donor == null || world == null) {
            return false;
        }
        if (donor.getHungerManager().getFoodLevel() < DONOR_MIN_FOOD_LEVEL) {
            return false;
        }
        if (isPendingParticipant(donor.getUuid()) || hasNearbyThreats(donor, HANDOFF_THREAT_RADIUS)) {
            return false;
        }
        if (pickBestInventoryFoodSlot(donor, true) < 0) {
            return false;
        }
        ServerPlayerEntity recipient = world.getPlayers().stream()
                .filter(other -> other != null
                        && other != donor
                        && BotEventHandler.isRegisteredBot(other)
                        && !other.isRemoved()
                        && other.isAlive()
                        && donor.squaredDistanceTo(other) <= SHARE_RADIUS_SQ
                        && sharesOwner(donor, other)
                        && !isPendingParticipant(other.getUuid())
                        && !hasNearbyThreats(other, HANDOFF_THREAT_RADIUS)
                        && other.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD)
                .min(Comparator.comparingDouble(donor::squaredDistanceTo))
                .orElse(null);
        if (recipient == null) {
            return false;
        }
        int transferCount = determineFoodHandoffCount(donor, recipient);
        registerPendingHandoff(new PendingHandoff(donor.getUuid(), recipient.getUuid(), nowTick, -1L, AidKind.FOOD, transferCount));
        LOGGER.info("Mutual aid: {} offered food to {}; awaiting acknowledgment",
                donor.getName().getString(),
                recipient.getName().getString());
        return true;
    }

    private static boolean tryShareGearWithNearbyBot(ServerPlayerEntity donor, ServerWorld world, long nowTick) {
        if (donor == null || world == null || isPendingParticipant(donor.getUuid()) || hasNearbyThreats(donor, HANDOFF_THREAT_RADIUS)) {
            return false;
        }
        List<ServerPlayerEntity> recipients = world.getPlayers().stream()
                .filter(other -> other != null
                        && other != donor
                        && BotEventHandler.isRegisteredBot(other)
                        && !other.isRemoved()
                        && other.isAlive()
                        && donor.squaredDistanceTo(other) <= SHARE_RADIUS_SQ
                        && sharesOwner(donor, other)
                        && !isPendingParticipant(other.getUuid())
                        && !hasNearbyThreats(other, HANDOFF_THREAT_RADIUS)
                        && needsSharedGear(other))
                .sorted(Comparator.comparingDouble(donor::squaredDistanceTo))
                .toList();
        for (ServerPlayerEntity recipient : recipients) {
            if (!ensureInventorySpaceForAidRecipient(recipient, AidKind.GEAR)) {
                continue;
            }
            if (pickAidInventorySlot(donor, recipient, AidKind.GEAR) < 0) {
                continue;
            }
            registerPendingHandoff(new PendingHandoff(donor.getUuid(), recipient.getUuid(), nowTick, -1L, AidKind.GEAR, 1));
            LOGGER.info("Mutual aid: {} offered spare gear to {}; awaiting acknowledgment",
                    donor.getName().getString(),
                    recipient.getName().getString());
            return true;
        }
        return false;
    }

    private static boolean tryShareFlowerWithNearbyBot(ServerPlayerEntity donor, ServerWorld world, long nowTick) {
        if (donor == null || world == null || isPendingParticipant(donor.getUuid())) {
            return false;
        }
        if (donor.getHungerManager().getFoodLevel() < COMFORTABLE_HUNGER_THRESHOLD || hasNearbyThreats(donor, HANDOFF_THREAT_RADIUS)) {
            return false;
        }
        int flowerSlot = pickAidInventorySlot(donor, donor, AidKind.FLOWER);
        if (flowerSlot < 0) {
            return false;
        }
        ServerPlayerEntity recipient = preferredFlowerRecipient(donor, world);
        if (recipient == null) {
            return false;
        }
        if (!ensureInventorySpaceForAidRecipient(recipient, AidKind.FLOWER)) {
            return false;
        }
        registerPendingHandoff(new PendingHandoff(donor.getUuid(), recipient.getUuid(), nowTick, -1L, AidKind.FLOWER, 1));
        LOGGER.info("Mutual aid: {} offered a flower to {}; awaiting acknowledgment",
                donor.getName().getString(),
                recipient.getName().getString());
        return true;
    }

    private static void processPendingHandoffs(MinecraftServer server, long nowTick) {
        for (UUID recipientId : List.copyOf(PENDING_HANDOFFS.keySet())) {
            PendingHandoff handoff = PENDING_HANDOFFS.get(recipientId);
            if (handoff == null) {
                continue;
            }
            processPendingHandoff(server, handoff, nowTick);
        }
    }

    private static void processPendingHandoff(MinecraftServer server, PendingHandoff handoff, long nowTick) {
        if (server == null || handoff == null) {
            return;
        }
        ServerPlayerEntity donor = server.getPlayerManager().getPlayer(handoff.donorId());
        ServerPlayerEntity recipient = server.getPlayerManager().getPlayer(handoff.recipientId());
        if (!isValidHandoff(donor, recipient, handoff, nowTick)) {
            clearPendingHandoff(handoff);
            return;
        }

        BotActions.stop(donor);
        BotActions.stop(recipient);
        LookController.faceEntity(donor, recipient);
        LookController.faceEntity(recipient, donor);

        long acknowledgedSince = handoff.acknowledgedSinceTick();
        if (acknowledgedSince < 0L) {
            PENDING_HANDOFFS.put(handoff.recipientId(), new PendingHandoff(
                    handoff.donorId(),
                    handoff.recipientId(),
                    handoff.createdTick(),
                    nowTick,
                    handoff.kind(),
                    handoff.itemCount()
            ));
            return;
        }
        if (nowTick - acknowledgedSince < HANDSHAKE_HOLD_TICKS) {
            return;
        }

        int slot = pickAidInventorySlot(donor, recipient, handoff.kind());
        if (slot < 0) {
            clearPendingHandoff(handoff);
            return;
        }
        int desiredCount = handoff.kind() == AidKind.FOOD
                ? Math.max(1, determineFoodHandoffCount(donor, recipient, donor.getInventory().getStack(slot)))
                : Math.max(1, handoff.itemCount());
        ItemStack stack = donor.getInventory().removeStack(slot, Math.min(desiredCount, donor.getInventory().getStack(slot).getCount()));
        if (stack.isEmpty()) {
            clearPendingHandoff(handoff);
            return;
        }
        donor.getInventory().markDirty();
        throwTowardBot(donor, recipient, stack);
        clearPendingHandoff(handoff);
        rememberInteraction(donor, recipient, nowTick);
        LOGGER.info("Mutual aid: {} shared {} with {} after acknowledgment ({})",
                donor.getName().getString(),
                stack.getName().getString(),
                recipient.getName().getString(),
                handoff.kind().name().toLowerCase(Locale.ROOT));
    }

    private static boolean approachAndInteract(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, target)) {
            return true;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                null
        );
        MovementService.MovementResult result = MovementService.execute(
                bot.getCommandSource().withSilent(),
                bot,
                plan,
                Boolean.FALSE,
                true,
                true,
                false
        );
        return result != null && (result.success() || BlockInteractionService.canInteract(bot, target));
    }

    private static int pickBestFoodSlot(Inventory inv, boolean allowRotten) {
        int bestSlot = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            double score = foodScore(stack, allowRotten);
            if (score < bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static List<BlockPos> collectCandidateFoodChests(ServerPlayerEntity bot, ServerWorld world) {
        Set<BlockPos> nearby = new LinkedHashSet<>();
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos pos = record.toBlockPos();
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (bot.getBlockPos().getSquaredDistance(pos) <= CHEST_RADIUS_SQ) {
                nearby.add(pos.toImmutable());
            }
        }

        if (shouldUseBaseStorage(bot)) {
            BlockPos origin = bot.getBlockPos();
            for (BlockPos pos : BlockPos.iterate(
                    origin.add(-CHEST_SEARCH_RADIUS, -CHEST_SEARCH_YSPAN, -CHEST_SEARCH_RADIUS),
                    origin.add(CHEST_SEARCH_RADIUS, CHEST_SEARCH_YSPAN, CHEST_SEARCH_RADIUS))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                var state = world.getBlockState(pos);
                if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) {
                    nearby.add(pos.toImmutable());
                }
            }
        }

        return nearby.stream()
                .sorted(Comparator.comparingDouble(bot.getBlockPos()::getSquaredDistance))
                .toList();
    }

    private static boolean shouldUseBaseStorage(ServerPlayerEntity bot) {
        return BotHomeService.isNearAnyBase(bot, BASE_STORAGE_RADIUS)
                || BotHomeService.isNearRecentSleep(bot, BASE_STORAGE_RADIUS, RECENT_SLEEP_WINDOW_MS);
    }

    private static void ensureInventorySpaceForChestFood(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null || bot.getInventory().getEmptySlot() != -1) {
            return;
        }
        Map<Item, Integer> reserveFood = collectReservedFood(bot);
        CraftingHelper.offloadCheapItemsToNearbyChest(bot, bot.getCommandSource().withSilent(), 0, 0, reserveFood);
        if (bot.getInventory().getEmptySlot() == -1) {
            CraftingHelper.dropCheapStackForSpace(bot, bot.getCommandSource().withSilent(), reserveFood.keySet());
        }
    }

    private static int determineChestFoodWithdrawCount(ItemStack stack, ServerPlayerEntity bot) {
        if (stack == null || stack.isEmpty() || bot == null) {
            return 1;
        }
        int hungerDeficit = Math.max(1, 20 - bot.getHungerManager().getFoodLevel());
        int pieces = bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD ? 3 : 2;
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food != null && food.nutrition() > 0) {
            pieces = Math.max(1, Math.min(pieces, (int) Math.ceil(hungerDeficit / (double) food.nutrition())));
        }
        return Math.max(1, Math.min(stack.getCount(), pieces));
    }

    private static int pickBestInventoryFoodSlot(ServerPlayerEntity bot, boolean allowRotten) {
        if (bot == null) {
            return -1;
        }
        int bestSlot = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            double score = foodScore(stack, allowRotten);
            if (score == Double.POSITIVE_INFINITY) {
                continue;
            }
            if (bot.getHungerManager().getFoodLevel() - nutritionValue(stack) < DONOR_MIN_FOOD_LEVEL) {
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int pickAidInventorySlot(ServerPlayerEntity donor, ServerPlayerEntity recipient, AidKind kind) {
        if (kind == AidKind.GEAR) {
            return pickSpareGearSlot(donor, recipient);
        }
        if (kind == AidKind.FLOWER) {
            return pickFlowerSlot(donor);
        }
        return pickBestInventoryFoodSlot(donor, true);
    }

    private static int determineFoodHandoffCount(ServerPlayerEntity donor, ServerPlayerEntity recipient) {
        int slot = pickBestInventoryFoodSlot(donor, true);
        if (slot < 0 || donor == null) {
            return 1;
        }
        return determineFoodHandoffCount(donor, recipient, donor.getInventory().getStack(slot));
    }

    private static int determineFoodHandoffCount(ServerPlayerEntity donor,
                                                 ServerPlayerEntity recipient,
                                                 ItemStack stack) {
        if (donor == null || recipient == null || stack == null || stack.isEmpty()) {
            return 1;
        }
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        int nutrition = food != null ? Math.max(1, food.nutrition()) : 1;
        int hungerNeed = Math.max(1, 20 - recipient.getHungerManager().getFoodLevel());
        int desiredByNeed = Math.max(1, (int) Math.ceil(hungerNeed / (double) nutrition));
        int sparePieces = countShareableFoodPieces(donor, true);
        int desiredBySurplus;
        if (sparePieces >= 10) {
            desiredBySurplus = 4;
        } else if (sparePieces >= 6) {
            desiredBySurplus = 3;
        } else if (sparePieces >= 3) {
            desiredBySurplus = 2;
        } else {
            desiredBySurplus = 1;
        }
        return Math.max(1, Math.min(stack.getCount(), Math.min(desiredByNeed, desiredBySurplus)));
    }

    private static int countShareableFoodPieces(ServerPlayerEntity bot, boolean allowRotten) {
        if (bot == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isShareableFood(stack, allowRotten)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static double foodScore(ItemStack stack, boolean allowRotten) {
        if (stack == null || stack.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food == null) {
            return Double.POSITIVE_INFINITY;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("poisonous_potato") || key.contains("spider_eye") || key.contains("pufferfish") || key.contains("suspicious_stew")) {
            return Double.POSITIVE_INFINITY;
        }
        if (key.contains("rotten_flesh")) {
            return allowRotten ? 999.0D : Double.POSITIVE_INFINITY;
        }
        return food.nutrition() + (food.saturation() * 2.0D);
    }

    private static int nutritionValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        return food != null ? food.nutrition() : 0;
    }

    private static boolean tryMakeSpaceForNearbyDroppedFood(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        ItemEntity nearbyFood = world.getEntitiesByClass(
                        ItemEntity.class,
                        new Box(
                                bot.getX() - DROPPED_FOOD_RESCUE_RADIUS,
                                bot.getY() - 1.5D,
                                bot.getZ() - DROPPED_FOOD_RESCUE_RADIUS,
                                bot.getX() + DROPPED_FOOD_RESCUE_RADIUS,
                                bot.getY() + 1.5D,
                                bot.getZ() + DROPPED_FOOD_RESCUE_RADIUS
                        ),
                        item -> isRescuableDroppedFood(bot, item)
                ).stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
        if (nearbyFood == null) {
            return false;
        }

        Map<Item, Integer> reserveFood = collectReservedFood(bot);
        CraftingHelper.offloadCheapItemsToNearbyChest(bot, bot.getCommandSource().withSilent(), 0, 0, reserveFood);
        if (bot.getInventory().getEmptySlot() == -1) {
            CraftingHelper.dropCheapStackForSpace(bot, bot.getCommandSource().withSilent(), reserveFood.keySet());
        }
        if (bot.getInventory().getEmptySlot() == -1) {
            return false;
        }

        nearbyFood.setPickupDelay(0);
        if (!nearbyFood.isRemoved() && bot.squaredDistanceTo(nearbyFood) <= DROPPED_FOOD_RESCUE_RADIUS_SQ) {
            DropSweeper.attemptManualNudge(bot, nearbyFood, nearbyFood.getBlockPos());
        }
        LOGGER.info("Mutual aid: {} cleared inventory space for nearby dropped food {}",
                bot.getName().getString(),
                nearbyFood.getStack().getName().getString());
        return true;
    }

    private static boolean isRescuableDroppedFood(ServerPlayerEntity bot, ItemEntity item) {
        if (bot == null || item == null || item.isRemoved()) {
            return false;
        }
        if (bot.squaredDistanceTo(item) > DROPPED_FOOD_RESCUE_RADIUS_SQ) {
            return false;
        }
        return isShareableFood(item.getStack(), true);
    }

    private static Map<Item, Integer> collectReservedFood(ServerPlayerEntity bot) {
        Map<Item, Integer> reserve = new ConcurrentHashMap<>();
        if (bot == null) {
            return reserve;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!isShareableFood(stack, true)) {
                continue;
            }
            reserve.merge(stack.getItem(), Math.max(1, stack.getCount()), Integer::sum);
        }
        return reserve;
    }

    private static boolean isShareableFood(ItemStack stack, boolean allowRotten) {
        return foodScore(stack, allowRotten) != Double.POSITIVE_INFINITY;
    }

    private static boolean ensureInventorySpaceForAidRecipient(ServerPlayerEntity recipient, AidKind kind) {
        if (recipient == null || recipient.getCommandSource() == null) {
            return false;
        }
        if (kind == AidKind.FOOD) {
            return true;
        }
        if (recipient.getInventory().getEmptySlot() != -1) {
            return true;
        }
        Map<Item, Integer> reserveItems = collectAidReserveItems(recipient);
        CraftingHelper.offloadCheapItemsToNearbyChest(recipient, recipient.getCommandSource().withSilent(), 0, 0, reserveItems);
        if (recipient.getInventory().getEmptySlot() != -1) {
            return true;
        }
        return CraftingHelper.dropCheapStackForSpace(recipient, recipient.getCommandSource().withSilent(), reserveItems.keySet());
    }

    private static Map<Item, Integer> collectAidReserveItems(ServerPlayerEntity bot) {
        Map<Item, Integer> reserve = new ConcurrentHashMap<>();
        if (bot == null) {
            return reserve;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!isAidReservedItem(stack)) {
                continue;
            }
            reserve.merge(stack.getItem(), Math.max(1, stack.getCount()), Integer::sum);
        }
        return reserve;
    }

    private static boolean isAidReservedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isShareableFood(stack, true) || weaponScore(stack) > 0 || isFlowerItem(stack)) {
            return true;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("shield")) {
            return true;
        }
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return false;
        }
        Set<EquipmentSlot> armorSlots = Set.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        return armorSlots.contains(equippable.slot());
    }

    private static ServerPlayerEntity preferredFlowerRecipient(ServerPlayerEntity donor, ServerWorld world) {
        if (donor == null || world == null) {
            return null;
        }
        UUID lastBotId = LAST_INTERACTION_BOT.get(donor.getUuid());
        if (lastBotId != null) {
            ServerPlayerEntity last = world.getServer().getPlayerManager().getPlayer(lastBotId);
            if (isFlowerRecipientCandidate(donor, last)) {
                return last;
            }
        }
        return world.getPlayers().stream()
                .filter(other -> isFlowerRecipientCandidate(donor, other))
                .min(Comparator.comparingDouble(donor::squaredDistanceTo))
                .orElse(null);
    }

    private static boolean isFlowerRecipientCandidate(ServerPlayerEntity donor, ServerPlayerEntity recipient) {
        if (donor == null || recipient == null || recipient == donor || recipient.isRemoved() || !recipient.isAlive()) {
            return false;
        }
        if (!BotEventHandler.isRegisteredBot(recipient)
                || !sharesOwner(donor, recipient)
                || isPendingParticipant(recipient.getUuid())
                || donor.squaredDistanceTo(recipient) > SHARE_RADIUS_SQ) {
            return false;
        }
        if (recipient.getHungerManager().getFoodLevel() <= HUNGRY_THRESHOLD
                || needsSharedGear(recipient)
                || hasNearbyThreats(recipient, HANDOFF_THREAT_RADIUS)) {
            return false;
        }
        return true;
    }

    private static boolean needsSharedGear(ServerPlayerEntity recipient) {
        return recipient != null && (!hasUsableWeapon(recipient) || firstMissingArmorSlot(recipient) != null);
    }

    private static boolean hasUsableWeapon(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (weaponScore(bot.getInventory().getStack(i)) > 0) {
                return true;
            }
        }
        return weaponScore(bot.getMainHandStack()) > 0;
    }

    /** Only donate wooden or stone tier weapons (unenchanted). Never iron/gold/diamond/netherite. */
    private static final int MAX_DONATABLE_WEAPON_SCORE = 20;

    private static int pickSpareGearSlot(ServerPlayerEntity donor, ServerPlayerEntity recipient) {
        if (donor == null || recipient == null) {
            return -1;
        }

        if (!hasUsableWeapon(recipient)) {
            int totalWeapons = countWeapons(donor);
            if (totalWeapons >= 2) {
                int bestSlot = -1;
                int bestScore = Integer.MAX_VALUE;
                for (int i = 0; i < donor.getInventory().size(); i++) {
                    ItemStack stack = donor.getInventory().getStack(i);
                    int score = weaponScore(stack);
                    if (score <= 0) {
                        continue;
                    }
                    if (score < bestScore && score <= MAX_DONATABLE_WEAPON_SCORE) {
                        bestScore = score;
                        bestSlot = i;
                    }
                }
                if (bestSlot >= 0) {
                    return bestSlot;
                }
            }
        }

        EquipmentSlot missingSlot = firstMissingArmorSlot(recipient);
        if (missingSlot == null) {
            return -1;
        }
        return pickLeatherArmorSlot(donor, missingSlot);
    }

    private static int pickFlowerSlot(ServerPlayerEntity donor) {
        if (donor == null) {
            return -1;
        }
        int bestSlot = -1;
        int bestCount = -1;
        for (int i = 0; i < donor.getInventory().size(); i++) {
            ItemStack stack = donor.getInventory().getStack(i);
            if (!isFlowerItem(stack)) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static boolean isFlowerItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.FLOWERS);
    }

    private static int countWeapons(ServerPlayerEntity bot) {
        int total = 0;
        if (bot == null) {
            return total;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (weaponScore(bot.getInventory().getStack(i)) > 0) {
                total++;
            }
        }
        return total;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        boolean weapon = key.contains("sword") || key.contains("axe") || key.contains("trident")
                || key.contains("mace") || key.contains("dagger");
        if (!weapon) {
            return 0;
        }
        int base;
        if (key.contains("netherite")) base = 50;
        else if (key.contains("diamond")) base = 40;
        else if (key.contains("iron")) base = 30;
        else if (key.contains("stone") || key.contains("cobble")) base = 20;
        else if (key.contains("gold")) base = 15;
        else if (key.contains("wood")) base = 10;
        else base = 5;
        if (stack.hasEnchantments()) base += 100;
        return base;
    }

    private static EquipmentSlot firstMissingArmorSlot(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (bot.getEquippedStack(slot).isEmpty()) {
                return slot;
            }
        }
        return null;
    }

    private static int pickLeatherArmorSlot(ServerPlayerEntity donor, EquipmentSlot missingSlot) {
        if (donor == null || missingSlot == null) {
            return -1;
        }
        for (int i = 0; i < donor.getInventory().size(); i++) {
            ItemStack stack = donor.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (!key.contains("leather_")) {
                continue;
            }
            EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable != null && equippable.slot() == missingSlot) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sharesOwner(ServerPlayerEntity a, ServerPlayerEntity b) {
        UUID ownerA = BotTerritoryAuthorizationService.resolveBotOwnerUuid(a);
        UUID ownerB = BotTerritoryAuthorizationService.resolveBotOwnerUuid(b);
        if (ownerA == null || ownerB == null) {
            return false;
        }
        return Objects.equals(ownerA, ownerB);
    }

    private static void throwTowardBot(ServerPlayerEntity donor, ServerPlayerEntity recipient, ItemStack stack) {
        if (donor == null || recipient == null || stack == null || stack.isEmpty()) {
            return;
        }
        ItemEntity itemEntity = donor.dropItem(stack, false, true);
        if (itemEntity == null) {
            itemEntity = new ItemEntity(donor.getEntityWorld(), donor.getX(), donor.getEyeY() - 0.3D, donor.getZ(), stack);
            donor.getEntityWorld().spawnEntity(itemEntity);
        }
        itemEntity.setOwner(recipient.getUuid());
        itemEntity.setPickupDelay(0);
        Vec3d dir = new Vec3d(recipient.getX(), recipient.getEyeY(), recipient.getZ())
                .subtract(donor.getX(), donor.getEyeY(), donor.getZ())
                .normalize();
        itemEntity.setVelocity(dir.multiply(0.28D));
    }

    private static boolean approachBot(ServerPlayerEntity bot,
                                       ServerPlayerEntity target,
                                       double reachSq,
                                       long timeoutMs,
                                       String label) {
        if (bot == null || target == null) {
            return false;
        }
        if (bot.squaredDistanceTo(target) <= reachSq) {
            return true;
        }
        return MovementService.nudgeTowardUntilClose(
                bot,
                target.getBlockPos(),
                reachSq,
                timeoutMs,
                0.18D,
                label
        );
    }

    private static boolean isValidHandoff(ServerPlayerEntity donor,
                                          ServerPlayerEntity recipient,
                                          PendingHandoff handoff,
                                          long nowTick) {
        if (donor == null || recipient == null) {
            return false;
        }
        if (nowTick - handoff.createdTick() > HANDSHAKE_TIMEOUT_TICKS) {
            return false;
        }
        if (donor.isRemoved() || recipient.isRemoved() || !donor.isAlive() || !recipient.isAlive()) {
            return false;
        }
        if (donor.isSleeping() || recipient.isSleeping()) {
            return false;
        }
        if (donor.getEntityWorld() != recipient.getEntityWorld()) {
            return false;
        }
        if (!sharesOwner(donor, recipient)) {
            return false;
        }
        if (hasNearbyThreats(donor, HANDOFF_THREAT_RADIUS) || hasNearbyThreats(recipient, HANDOFF_THREAT_RADIUS)) {
            return false;
        }
        if (handoff.kind() == AidKind.FOOD) {
            if (recipient.getHungerManager().getFoodLevel() > HUNGRY_THRESHOLD) {
                return false;
            }
            if (donor.getHungerManager().getFoodLevel() < DONOR_MIN_FOOD_LEVEL) {
                return false;
            }
        } else if (handoff.kind() == AidKind.GEAR) {
            if (!needsSharedGear(recipient)) {
                return false;
            }
        } else {
            if (!isFlowerRecipientCandidate(donor, recipient) || donor.getHungerManager().getFoodLevel() < COMFORTABLE_HUNGER_THRESHOLD) {
                return false;
            }
        }
        if (donor.squaredDistanceTo(recipient) > HANDOFF_KEEPALIVE_RADIUS_SQ) {
            return false;
        }
        return pickAidInventorySlot(donor, recipient, handoff.kind()) >= 0;
    }

    private static boolean isPendingParticipant(UUID botId) {
        if (botId == null) {
            return false;
        }
        return PENDING_HANDOFFS.containsKey(botId) || DONOR_PENDING.containsKey(botId);
    }

    private static void registerPendingHandoff(PendingHandoff handoff) {
        if (handoff == null) {
            return;
        }
        PENDING_HANDOFFS.put(handoff.recipientId(), handoff);
        DONOR_PENDING.put(handoff.donorId(), handoff.recipientId());
    }

    private static void clearPendingHandoff(PendingHandoff handoff) {
        if (handoff == null) {
            return;
        }
        PENDING_HANDOFFS.remove(handoff.recipientId(), handoff);
        DONOR_PENDING.remove(handoff.donorId(), handoff.recipientId());
    }

    private static void rememberInteraction(ServerPlayerEntity a, ServerPlayerEntity b, long nowTick) {
        if (a == null || b == null) {
            return;
        }
        LAST_INTERACTION_BOT.put(a.getUuid(), b.getUuid());
        LAST_INTERACTION_BOT.put(b.getUuid(), a.getUuid());
        LAST_INTERACTION_TICK.put(a.getUuid(), nowTick);
        LAST_INTERACTION_TICK.put(b.getUuid(), nowTick);
    }

    private static boolean isAidCheckCoolingDown(ServerPlayerEntity seeker, ServerPlayerEntity candidate, long nowTick) {
        if (seeker == null || candidate == null) {
            return false;
        }
        Map<UUID, Long> perBot = BOT_AID_CHECK_COOLDOWNS.get(seeker.getUuid());
        if (perBot == null) {
            return false;
        }
        return nowTick < perBot.getOrDefault(candidate.getUuid(), 0L);
    }

    private static void setAidCheckCooldown(ServerPlayerEntity seeker, ServerPlayerEntity candidate, long untilTick) {
        if (seeker == null || candidate == null) {
            return;
        }
        BOT_AID_CHECK_COOLDOWNS
                .computeIfAbsent(seeker.getUuid(), ignored -> new ConcurrentHashMap<>())
                .put(candidate.getUuid(), untilTick);
    }
}
