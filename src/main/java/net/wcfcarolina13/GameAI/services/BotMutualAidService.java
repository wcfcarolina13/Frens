package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
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
import net.wcfcarolina13.GameAI.services.MutualAidChestFoodPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals;
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
    /** How long a worker waits for its chest-food attempt to start on the server thread (the supply facade's hop budget). */
    private static final long CHEST_FOOD_HOP_TIMEOUT_MS = 2_500L;
    private static final String CHEST_FOOD_PURPOSE = "mutual-aid-food";
    private static final long SHARE_COOLDOWN_TICKS = 20L * 5L;
    private static final long HANDSHAKE_TIMEOUT_TICKS = 20L * 4L;
    private static final long HANDSHAKE_HOLD_TICKS = 12L;
    private static final long AID_CHECK_COOLDOWN_TICKS = 20L * 30L;
    private static final long SEEK_AID_COOLDOWN_TICKS = 20L * 6L;
    private static final long REGROUP_COOLDOWN_TICKS = 20L * 20L;
    private static final long DEFENSE_COOLDOWN_TICKS = 20L * 4L;
    private static final long DEFENSE_FAILURE_COOLDOWN_TICKS = 40L;
    private static final long FLOWER_COOLDOWN_TICKS = 20L * 90L;
    private static final long STATE_CLEANUP_INTERVAL_TICKS = 20L * 60L;

    private static final Map<UUID, Long> NEXT_CHEST_TICK = new ConcurrentHashMap<>();
    /** The chest whose owner a bot asked for food and has not heard back from; asked first next time. Server thread. */
    private static final Map<UUID, BlockPos> ASKED_FOOD_CHEST = new ConcurrentHashMap<>();
    /**
     * Until this tick a bot counts on no shared-chest food: its last attempt ended in the owner's
     * decision or the owner away ({@link MutualAidChestFoodPolicy#withholdsChestFood}). Server thread.
     */
    private static final Map<UUID, Long> CHEST_FOOD_WITHHELD_UNTIL = new ConcurrentHashMap<>();
    /** The last {@link #hasGrantableChestFood} answer per bot, reused while {@link MutualAidChestFoodPolicy#isFresh}. Server thread. */
    private static final Map<UUID, ChestFoodMemo> CHEST_FOOD_MEMO = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_SHARE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_SEEK_AID_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_REGROUP_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_DEFENSE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_FLOWER_TICK = new ConcurrentHashMap<>();
    /**
     * Per bot that would drop, per make-room kind: no make-room drop for that kind before this tick
     * (set when a drop found nothing it may drop). Keyed by kind because each kind reserves different
     * items, so a failed gear drop says nothing about a food pickup. Server thread.
     */
    private static final Map<UUID, Map<MutualAidMakeRoomPolicy.Kind, Long>> NEXT_MAKE_ROOM_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingHandoff> PENDING_HANDOFFS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> DONOR_PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> LAST_INTERACTION_BOT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_INTERACTION_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<UUID, Long>> BOT_AID_CHECK_COOLDOWNS = new ConcurrentHashMap<>();
    /**
     * Approaches in progress, one per moving bot, advanced one step per server tick
     * ({@link #advanceEngagements}). Replaces the blocking nudge that froze the server thread. Server thread.
     */
    private static final Map<UUID, Engagement> ENGAGEMENTS = new ConcurrentHashMap<>();
    private static final double DEFENSE_IMPULSE = 0.20D;
    private static final double APPROACH_BOT_IMPULSE = 0.18D;
    private static final double JUMP_RISE = 0.6D;
    private static final double AUTO_JUMP_BEYOND_SQ = 2.25D;
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

    private enum EngagementKind {
        /** A defender closing on the hostile near an ally; attacks on arrival. */
        DEFEND_HOSTILE,
        /** A defender closing on the ally when the hostile was already gone. */
        DEFEND_ALLY,
        /** A hungry bot walking to a donor bot; asks for food on arrival. */
        SEEK_FOOD
    }

    /**
     * @param targetId the entity walked toward: the hostile, the ally, or the donor
     * @param allyId   the defended ally (defense kinds); the donor again for {@link EngagementKind#SEEK_FOOD}
     */
    private record Engagement(EngagementKind kind,
                              UUID targetId,
                              UUID allyId,
                              long deadlineTick,
                              double reachSq,
                              double bestDistSq,
                              int noProgressTicks) {
        Engagement next(MutualAidDefensePolicy.Step step) {
            return new Engagement(kind, targetId, allyId, deadlineTick, reachSq, step.bestDistSq(), step.noProgressTicks());
        }
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
                    && tryMakeSpaceForNearbyDroppedFood(bot, world, nowTick)) {
                continue;
            }

            if (bot.getHungerManager().getFoodLevel() <= HUNGRY_THRESHOLD
                    && probeSharedChestFood(server, bot, world, nowTick)) {
                continue;
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
        // On the server thread the walk to the donor is an engagement advanced one step per tick
        // (a nudge here would freeze the tick and the bot with it); off it, the caller's worker walks.
        boolean onServerThread = server.isOnThread();
        Engagement active = ENGAGEMENTS.get(seeker.getUuid());
        if (active != null) {
            // Still walking to a donor: the seek is under way. Any other engagement: busy.
            return active.kind() == EngagementKind.SEEK_FOOD;
        }
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
        if (onServerThread) {
            if (seeker.squaredDistanceTo(donor) > SHARE_RADIUS_SQ) {
                startEngagement(seeker, EngagementKind.SEEK_FOOD, donor, donor.getUuid(),
                        nowTick + MutualAidDefensePolicy.SEEK_FOOD_DEADLINE_TICKS, SHARE_RADIUS_SQ);
                LOGGER.info("Mutual aid: {} is walking to {} for food support",
                        seeker.getName().getString(),
                        donor.getName().getString());
                return true;
            }
        } else if (!approachBot(seeker, donor, SHARE_RADIUS_SQ, 3_200L, "mutual-aid-seek-food")) {
            seekFoodApproachFailed(seeker, donor, nowTick);
            return false;
        }
        return askDonorForFood(seeker, donor, nowTick);
    }

    private static void seekFoodApproachFailed(ServerPlayerEntity seeker, ServerPlayerEntity donor, long nowTick) {
        setAidCheckCooldown(seeker, donor, nowTick + AID_CHECK_COOLDOWN_TICKS);
        NEXT_SEEK_AID_TICK.put(seeker.getUuid(), nowTick + SEEK_AID_COOLDOWN_TICKS);
    }

    /** The seeker is within {@link #SHARE_RADIUS} of the donor: register a food handoff if the donor has spare food. */
    private static boolean askDonorForFood(ServerPlayerEntity seeker, ServerPlayerEntity donor, long nowTick) {
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
        if (server == null) {
            return false;
        }
        return probeSharedChestFood(server, bot, world, server.getTicks());
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
        pruneMap(ASKED_FOOD_CHEST, knownBots);
        pruneMap(CHEST_FOOD_WITHHELD_UNTIL, knownBots);
        pruneMap(CHEST_FOOD_MEMO, knownBots);
        pruneMap(NEXT_SHARE_TICK, knownBots);
        pruneMap(NEXT_SEEK_AID_TICK, knownBots);
        pruneMap(NEXT_REGROUP_TICK, knownBots);
        pruneMap(NEXT_DEFENSE_TICK, knownBots);
        pruneMap(ENGAGEMENTS, knownBots);
        pruneMap(NEXT_FLOWER_TICK, knownBots);
        pruneMap(NEXT_MAKE_ROOM_TICK, knownBots);
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
        // Approaches already under way take their step before any new one starts.
        advanceEngagements(server, nowTick);
        for (ServerPlayerEntity defender : BotEventHandler.getRegisteredBots(server)) {
            if (defender == null || ENGAGEMENTS.containsKey(defender.getUuid())) {
                continue;
            }
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
                NEXT_DEFENSE_TICK.put(defender.getUuid(), nowTick + DEFENSE_FAILURE_COOLDOWN_TICKS);
                continue;
            }
            respondToDefenseRequest(defender, request, nowTick);
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

    /**
     * Answers a defense request without blocking the tick: a defender already within reach strikes
     * now; otherwise an engagement starts and {@link #advanceEngagements} moves it one step per tick.
     * Server thread.
     */
    private static void respondToDefenseRequest(ServerPlayerEntity defender, DefenseRequest request, long nowTick) {
        if (defender == null) {
            return;
        }
        if (request == null || request.ally() == null) {
            endDefense(defender, false, nowTick);
            return;
        }
        Entity hostile = request.hostile();
        ServerPlayerEntity ally = request.ally();
        if (hostile == null || hostile.isRemoved() || !hostile.isAlive()) {
            if (defender.squaredDistanceTo(ally) <= REGROUP_RADIUS_SQ) {
                endDefense(defender, true, nowTick);
                return;
            }
            startEngagement(defender, EngagementKind.DEFEND_ALLY, ally, ally.getUuid(),
                    nowTick + MutualAidDefensePolicy.ALLY_DEADLINE_TICKS, REGROUP_RADIUS_SQ);
            return;
        }
        if (defender.squaredDistanceTo(hostile) <= DEFENSE_ENGAGE_REACH_SQ) {
            endDefense(defender, strikeHostile(defender, ally, hostile, nowTick), nowTick);
            return;
        }
        startEngagement(defender, EngagementKind.DEFEND_HOSTILE, hostile, ally.getUuid(),
                nowTick + MutualAidDefensePolicy.HOSTILE_DEADLINE_TICKS, DEFENSE_ENGAGE_REACH_SQ);
    }

    /** The defender is within reach of the hostile: attack once. @return whether it attacked */
    private static boolean strikeHostile(ServerPlayerEntity defender, ServerPlayerEntity ally, Entity hostile, long nowTick) {
        if (!BotCombatPolicyService.shouldBotAttack(hostile, defender)) {
            // Target is name-tagged — bot is forbidden from engaging even in mutual aid.
            return false;
        }
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

    /** The defense cooldown a finished response leaves: 4 s after a success, 2 s after a failure. */
    private static void endDefense(ServerPlayerEntity defender, boolean succeeded, long nowTick) {
        NEXT_DEFENSE_TICK.put(defender.getUuid(),
                nowTick + (succeeded ? DEFENSE_COOLDOWN_TICKS : DEFENSE_FAILURE_COOLDOWN_TICKS));
    }

    private static void startEngagement(ServerPlayerEntity bot, EngagementKind kind, Entity target, UUID allyId,
                                        long deadlineTick, double reachSq) {
        ENGAGEMENTS.put(bot.getUuid(),
                new Engagement(kind, target.getUuid(), allyId, deadlineTick, reachSq, bot.squaredDistanceTo(target), 0));
    }

    /**
     * One step for every approach under way, at most one movement input per bot per tick. A bot
     * that left the server is dropped; every other end goes through {@link #endEngagement}, which
     * stops the bot so it is never left walking. Server thread.
     */
    private static void advanceEngagements(MinecraftServer server, long nowTick) {
        if (ENGAGEMENTS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Engagement> entry : ENGAGEMENTS.entrySet()) {
            UUID botId = entry.getKey();
            Engagement engagement = entry.getValue();
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved() || !BotEventHandler.isRegisteredBot(bot)) {
                ENGAGEMENTS.remove(botId);
                continue;
            }
            advanceEngagement(server, bot, engagement, nowTick);
        }
    }

    private static void advanceEngagement(MinecraftServer server, ServerPlayerEntity bot, Engagement engagement,
                                          long nowTick) {
        Entity target = resolveEngagementTarget(server, bot, engagement);
        if (target == null || !canContinueEngagement(bot, engagement)) {
            endEngagement(server, bot, engagement, target, false, nowTick);
            return;
        }
        double distSq = bot.squaredDistanceTo(target);
        MutualAidDefensePolicy.Step step = MutualAidDefensePolicy.step(distSq, engagement.reachSq(),
                engagement.bestDistSq(), engagement.noProgressTicks(), nowTick, engagement.deadlineTick());
        switch (step.decision()) {
            case ARRIVED -> endEngagement(server, bot, engagement, target, true, nowTick);
            case ABORT -> endEngagement(server, bot, engagement, target, false, nowTick);
            case CONTINUE -> {
                ENGAGEMENTS.put(bot.getUuid(), engagement.next(step));
                double baseImpulse = engagement.kind() == EngagementKind.DEFEND_HOSTILE ? DEFENSE_IMPULSE : APPROACH_BOT_IMPULSE;
                stepToward(bot, target, distSq, baseImpulse);
            }
        }
    }

    /** The entity walked toward, if it is still alive in the bot's world. */
    private static Entity resolveEngagementTarget(MinecraftServer server, ServerPlayerEntity bot, Engagement engagement) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        Entity target = engagement.kind() == EngagementKind.DEFEND_HOSTILE
                ? world.getEntity(engagement.targetId())
                : server.getPlayerManager().getPlayer(engagement.targetId());
        if (target == null || target.isRemoved() || !target.isAlive() || target.getEntityWorld() != world) {
            return null;
        }
        return target;
    }

    private static boolean canContinueEngagement(ServerPlayerEntity bot, Engagement engagement) {
        if (bot.isInsideWall()) {
            return false;
        }
        if (engagement.kind() != EngagementKind.SEEK_FOOD) {
            return isDefenseEligible(bot);
        }
        // Not TaskService.isAbortRequested: interrupting an ambient task latches it, and the hobby
        // starvation path interrupts one right before seeking food.
        return bot.isAlive()
                && !bot.isSleeping()
                && BotEventHandler.getCurrentMode(bot) == BotEventHandler.Mode.IDLE
                && !BotFleeService.isBreakingFree(bot.getUuid())
                && !BotFleeService.isInShelter(bot.getUuid());
    }

    /** One tick of the old nudge's input: face, sprint when far, jump up a rise, one push toward the target. */
    private static void stepToward(ServerPlayerEntity bot, Entity target, double distSq, double baseImpulse) {
        LookController.faceEntity(bot, target);
        BotActions.sprint(bot, MutualAidDefensePolicy.shouldSprint(distSq));
        if (target.getY() - bot.getY() > JUMP_RISE) {
            BotActions.jump(bot);
        } else if (distSq > AUTO_JUMP_BEYOND_SQ) {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, new Vec3d(target.getX(), target.getY(), target.getZ()),
                MutualAidDefensePolicy.impulse(distSq, baseImpulse));
    }

    /**
     * Ends an approach: removes it, stops the bot (velocity and sprint cleared, as the old nudge did
     * on every exit), then acts on arrival or backs off. {@code target} may be null when it was lost.
     */
    private static void endEngagement(MinecraftServer server, ServerPlayerEntity bot, Engagement engagement,
                                      Entity target, boolean arrived, long nowTick) {
        ENGAGEMENTS.remove(bot.getUuid());
        BotActions.stop(bot);
        switch (engagement.kind()) {
            case DEFEND_HOSTILE -> {
                ServerPlayerEntity ally = server.getPlayerManager().getPlayer(engagement.allyId());
                boolean struck = arrived && ally != null && strikeHostile(bot, ally, target, nowTick);
                endDefense(bot, struck, nowTick);
            }
            case DEFEND_ALLY -> endDefense(bot, arrived, nowTick);
            case SEEK_FOOD -> {
                ServerPlayerEntity donor = target instanceof ServerPlayerEntity player ? player : null;
                if (arrived && donor != null) {
                    askDonorForFood(bot, donor, nowTick);
                } else {
                    if (donor == null) {
                        donor = server.getPlayerManager().getPlayer(engagement.targetId());
                    }
                    seekFoodApproachFailed(bot, donor, nowTick);
                    LOGGER.info("Mutual aid: {} could not reach {} for food support",
                            bot.getName().getString(),
                            donor != null ? donor.getName().getString() : "a donor");
                }
            }
        }
    }

    private static boolean hasNearbyThreats(ServerPlayerEntity bot, double radius) {
        return bot != null && !BotThreatService.findHostilesAround(bot, radius).isEmpty();
    }

    /**
     * At most one shared-chest food attempt per bot every {@link MutualAidChestFoodPolicy#THROTTLE_TICKS},
     * whatever it came to: a refusal, an unanswered prompt or an empty scan must not ask again next
     * tick. An attempt the owner's answer or absence ended holds this bot off chests longer
     * ({@link MutualAidChestFoodPolicy#probeDelayTicks}); the attempt itself records that. Any thread;
     * the attempt runs on the server thread through {@link SupplyServerHop}.
     *
     * @return whether food moved into the bot (which has then eaten, if it needed to)
     */
    private static boolean probeSharedChestFood(MinecraftServer server, ServerPlayerEntity bot, ServerWorld world,
                                                long nowTick) {
        if (server == null || bot == null || world == null) {
            return false;
        }
        UUID botId = bot.getUuid();
        if (nowTick < NEXT_CHEST_TICK.getOrDefault(botId, 0L)) {
            return false;
        }
        // Set before the hop, so a worker whose hop ran out cannot start a second take behind the first.
        NEXT_CHEST_TICK.put(botId, nowTick + MutualAidChestFoodPolicy.THROTTLE_TICKS);
        return Boolean.TRUE.equals(SupplyServerHop.call(server, () -> tryTakeFoodFromSharedChest(bot, world),
                CHEST_FOOD_HOP_TIMEOUT_MS, Boolean.FALSE));
    }

    /**
     * Takes food from the first in-reach shared chest whose owner allows it, through the supply
     * facade (never walking, never waiting), then eats if the bot needs to. Chests only; a double
     * chest is read and asked as one. A food refused for itself is not asked again in any chest
     * this attempt; a chest whose owner was found away is skipped (an "always" chest further on
     * may still serve); the owner's decision, a busy answer, or a full inventory room could not be
     * made in ends the attempt. How long the bot
     * then leaves chests alone is {@link MutualAidChestFoodPolicy#probeDelayTicks}; an attempt that
     * found the owner away and got nothing else defers a flat minute
     * ({@link MutualAidChestFoodPolicy#endOfAttempt}). Server thread.
     */
    private static boolean tryTakeFoodFromSharedChest(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.isRemoved()) {
            return false;
        }
        UUID botId = bot.getUuid();
        BlockPos asked = ASKED_FOOD_CHEST.get(botId);
        boolean starving = bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD;
        Set<BlockPos> tried = new HashSet<>();
        List<ItemStack> refusedFoods = new ArrayList<>();
        boolean ownerAwaySeen = false;
        for (BlockPos chestPos : MutualAidChestFoodPolicy.askedFirst(collectCandidateFoodChests(bot, world), asked)) {
            if (tried.contains(chestPos) || !BlockInteractionService.canInteract(bot, chestPos)) {
                continue;
            }
            BlockState state = world.getBlockState(chestPos);
            Inventory chest = mergedChestAt(world, chestPos, state);
            if (chest == null) {
                continue;
            }
            tried.add(chestPos);
            BlockPos otherHalf = otherHalfOf(state, chestPos);
            if (otherHalf != null) {
                tried.add(otherHalf);
            }
            Next next = takeFoodFromChest(bot, world, chestPos, chest, starving, refusedFoods);
            if (next == Next.WAIT) {
                ASKED_FOOD_CHEST.put(botId, chestPos);
                return false;
            }
            if (next == Next.TAKEN || chestPos.equals(asked) || (otherHalf != null && otherHalf.equals(asked))) {
                ASKED_FOOD_CHEST.remove(botId);
            }
            if (next == Next.TAKEN) {
                // A take proves the chests serve this bot again; the rescue's next look recounts.
                CHEST_FOOD_WITHHELD_UNTIL.remove(botId);
                CHEST_FOOD_MEMO.remove(botId);
                HealingService.autoEat(bot);
                return true;
            }
            if (next == Next.OWNER_AWAY) {
                // Both halves are already in tried; a chest further on may be one the owner allows always.
                ownerAwaySeen = true;
                continue;
            }
            if (next != Next.NEXT_CHEST) {
                holdOffChestFood(bot, world, next);
                return false;
            }
        }
        holdOffChestFood(bot, world, MutualAidChestFoodPolicy.endOfAttempt(ownerAwaySeen));
        return false;
    }

    /** One {@link #hasGrantableChestFood} answer: when, where (packed block), for which starving flag. */
    private record ChestFoodMemo(long tick, long posKey, boolean starving, boolean result) {
    }

    /**
     * Whether a shared chest in reach holds food the supply rules would grant this bot: the same
     * chests, reach and merged double chests the take in {@link #tryTakeFoodFromSharedChest} uses,
     * each food counted by {@link SupplyWithdrawals#grantablePreview} (allowlist, food reserve, an
     * owner with a standing permission for the chest or in prompt range). False while the bot's
     * last attempt left chest food withheld (the owner said no, or was away), off the server thread
     * (chest contents are not readable there), and for a bot with no owner, whom nobody may approve
     * a take for. Advisory: no owner is asked and nothing moves, so the owner can still refuse.
     *
     * <p>An answer is reused for {@link MutualAidChestFoodPolicy#CHEST_FOOD_MEMO_TICKS} at the same
     * block and starving flag, so a starving bot's per-tick rescue check does not rescan chests every
     * tick. A hold is read before the memo and clears it, so a refusal counts at once.
     */
    public static boolean hasGrantableChestFood(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.isRemoved()) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null || !server.isOnThread()) {
            return false;
        }
        if (CompanionCommunicationPolicy.resolveOwnerUuid(bot) == null) {
            return false;
        }
        UUID botId = bot.getUuid();
        long nowTick = server.getTicks();
        if (nowTick < CHEST_FOOD_WITHHELD_UNTIL.getOrDefault(botId, Long.MIN_VALUE)) {
            return false;
        }
        boolean starving = bot.getHungerManager().getFoodLevel() <= STARVING_THRESHOLD;
        long posKey = bot.getBlockPos().asLong();
        ChestFoodMemo memo = CHEST_FOOD_MEMO.get(botId);
        if (memo != null && MutualAidChestFoodPolicy.isFresh(memo.tick(), nowTick, memo.posKey(), posKey,
                memo.starving(), starving)) {
            return memo.result();
        }
        boolean result = scanForGrantableChestFood(bot, world, starving);
        CHEST_FOOD_MEMO.put(botId, new ChestFoodMemo(nowTick, posKey, starving, result));
        return result;
    }

    private static boolean scanForGrantableChestFood(ServerPlayerEntity bot, ServerWorld world, boolean starving) {
        for (BlockPos chestPos : collectCandidateFoodChests(bot, world)) {
            if (!BlockInteractionService.canInteract(bot, chestPos)) {
                continue;
            }
            Inventory chest = mergedChestAt(world, chestPos, world.getBlockState(chestPos));
            if (chest == null) {
                continue;
            }
            for (ChestFood food : listChestFood(chest, starving)) {
                if (SupplyWithdrawals.grantablePreview(bot, chestPos, food.sample(), food.count()) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The chest at {@code pos} with both halves, as the supply policy counts them; null when the
     * block is not a {@link ChestBlock} or its lid is blocked (which the policy would refuse).
     * Server thread.
     */
    private static Inventory mergedChestAt(ServerWorld world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getInventory(chestBlock, state, world, pos, false);
    }

    /**
     * After an attempt ended in {@code next}, no chest is asked for food before
     * {@link MutualAidChestFoodPolicy#probeDelayTicks} from now. Never shortens a hold already set.
     * Written here, on the server thread, so a worker whose hop gave up still leaves the hold behind.
     */
    private static void holdOffChestFood(ServerPlayerEntity bot, ServerWorld world, Next next) {
        long delay = MutualAidChestFoodPolicy.probeDelayTicks(next);
        long until = world.getServer().getTicks() + delay;
        NEXT_CHEST_TICK.merge(bot.getUuid(), until, Math::max);
        if (MutualAidChestFoodPolicy.withholdsChestFood(next)) {
            // The rescue stops counting chest food now, not when its memo runs out.
            CHEST_FOOD_WITHHELD_UNTIL.merge(bot.getUuid(), until, Math::max);
            CHEST_FOOD_MEMO.remove(bot.getUuid());
        }
        if (delay > MutualAidChestFoodPolicy.THROTTLE_TICKS) {
            LOGGER.debug("Mutual aid: {} leaves shared chests alone for {} s ({})",
                    bot.getName().getString(), delay / 20L, next);
        }
    }

    /**
     * Asks the chest for its cheapest food the policy could grant, then the next, until one is
     * taken, asked about, or refused for the whole chest (or the whole bot). A food refused for
     * itself goes into {@code refusedFoods} and is skipped in every chest this attempt; a food this
     * chest cannot grant is skipped here only. Room is made in the bot only once the facade says an
     * otherwise permitted take has none (it keeps the ticket for that), and then the same food is
     * asked for once more.
     */
    private static Next takeFoodFromChest(ServerPlayerEntity bot, ServerWorld world, BlockPos chestPos,
                                          Inventory chest, boolean starving, List<ItemStack> refusedFoods) {
        int foodLevel = bot.getHungerManager().getFoodLevel();
        for (ChestFood food : listChestFood(chest, starving)) {
            if (isSameFoodListed(refusedFoods, food.sample())) {
                continue;
            }
            // 0: not allowlisted, or none above the reserve. Asking would only be refused, loudly.
            int grantable = SupplyWithdrawals.grantableEstimate(world, food.sample(), food.count());
            if (grantable <= 0) {
                continue;
            }
            int pieces = MutualAidChestFoodPolicy.withdrawCount(foodLevel, starving,
                    nutritionValue(food.sample()), grantable);
            SupplyWithdrawals.Result result = askForFood(bot, chestPos, food.sample(), pieces);
            Next next = MutualAidChestFoodPolicy.afterWithdraw(result.kind(), result.scope(),
                    hasRoomForOne(bot, food.sample()), false);
            if (next == Next.MAKE_ROOM_AND_RETRY) {
                ensureInventorySpaceForChestFood(bot);
                result = askForFood(bot, chestPos, food.sample(), pieces);
                next = MutualAidChestFoodPolicy.afterWithdraw(result.kind(), result.scope(),
                        hasRoomForOne(bot, food.sample()), true);
            }
            if (next == Next.TAKEN) {
                LOGGER.info("Mutual aid: {} took {}x {} from shared chest at {}",
                        bot.getName().getString(),
                        result.moved(),
                        food.sample().getName().getString(),
                        chestPos.toShortString());
            }
            if (next == Next.NEXT_ITEM) {
                refusedFoods.add(food.sample());
                continue;
            }
            if (next != Next.NEXT_TARGET) {
                return next;
            }
        }
        return Next.NEXT_CHEST;
    }

    private static boolean isSameFoodListed(List<ItemStack> foods, ItemStack sample) {
        for (ItemStack food : foods) {
            if (ItemStack.areItemsAndComponentsEqual(food, sample)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one more piece of {@code sample} fits the bot's main inventory: an empty slot, or a
     * stack of the exact item with room. The supply adapter counts room the same way before a take.
     */
    private static boolean hasRoomForOne(ServerPlayerEntity bot, ItemStack sample) {
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.areItemsAndComponentsEqual(stack, sample)
                    && stack.getCount() < bot.getInventory().getMaxCount(stack)) {
                return true;
            }
        }
        return false;
    }

    private static SupplyWithdrawals.Result askForFood(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample,
                                                       int pieces) {
        return SupplyWithdrawals.withdraw(bot, chestPos, sample, pieces, pieces, CHEST_FOOD_PURPOSE,
                SupplyWithdrawals.WaitMode.NONE, null);
    }

    /** One food in a chest: a copy of its first stack (exact item and components), how many the chest holds, its score. */
    private record ChestFood(ItemStack sample, int count, double score) {
    }

    /**
     * The chest's foods, cheapest first by {@link #foodScore} (ties keep slot order), each exact
     * item once with its total count. What {@link #foodScore} never eats is left out.
     */
    private static List<ChestFood> listChestFood(Inventory chest, boolean allowRotten) {
        List<ChestFood> foods = new ArrayList<>();
        for (int slot = 0; slot < chest.size(); slot++) {
            ItemStack stack = chest.getStack(slot);
            double score = foodScore(stack, allowRotten);
            if (score == Double.POSITIVE_INFINITY) {
                continue;
            }
            int known = indexOfSameFood(foods, stack);
            if (known < 0) {
                foods.add(new ChestFood(stack.copy(), stack.getCount(), score));
            } else {
                ChestFood food = foods.get(known);
                foods.set(known, new ChestFood(food.sample(), food.count() + stack.getCount(), food.score()));
            }
        }
        foods.sort(Comparator.comparingDouble(ChestFood::score));
        return foods;
    }

    private static int indexOfSameFood(List<ChestFood> foods, ItemStack stack) {
        for (int i = 0; i < foods.size(); i++) {
            if (ItemStack.areItemsAndComponentsEqual(foods.get(i).sample(), stack)) {
                return i;
            }
        }
        return -1;
    }

    /** The other half of a double chest, from its block state; {@code null} for a single chest. */
    private static BlockPos otherHalfOf(BlockState state, BlockPos pos) {
        if (!state.contains(ChestBlock.CHEST_TYPE) || !state.contains(ChestBlock.FACING)
                || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.offset(ChestBlock.getFacing(state));
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
            // The donor must have gear for this recipient before the recipient drops a stack for it.
            if (pickAidInventorySlot(donor, recipient, AidKind.GEAR) < 0) {
                continue;
            }
            if (!ensureInventorySpaceForAidRecipient(recipient, AidKind.GEAR, nowTick)) {
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
        if (!ensureInventorySpaceForAidRecipient(recipient, AidKind.FLOWER, nowTick)) {
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

    /**
     * Chests only (anything whose block is a {@link ChestBlock}): a barrel, shulker or other
     * container is never asked, since the supply policy refuses anything but a chest.
     */
    private static List<BlockPos> collectCandidateFoodChests(ServerPlayerEntity bot, ServerWorld world) {
        Set<BlockPos> nearby = new LinkedHashSet<>();
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos pos = record.toBlockPos();
            if (!world.isChunkLoaded(pos) || !(world.getBlockState(pos).getBlock() instanceof ChestBlock)) {
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
                if (world.getBlockState(pos).getBlock() instanceof ChestBlock) {
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

    /**
     * Frees one slot for chest food by dropping one stack, a cheap one first (never food the bot
     * carries, never a protected or damageable item; the drop is registered so the bot does not
     * sweep it back up).
     * Server thread, inside the take: it must never walk, place a chest or deposit into one, so no
     * offload to a nearby chest here.
     */
    private static void ensureInventorySpaceForChestFood(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null || bot.getInventory().getEmptySlot() != -1) {
            return;
        }
        Map<Item, Integer> reserveFood = collectReservedFood(bot);
        CraftingHelper.dropCheapStackForSpace(bot, bot.getCommandSource().withSilent(), reserveFood.keySet());
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

    /**
     * A starving bot with a full inventory next to dropped food frees a slot by dropping one stack
     * (a cheap one first; never food it carries, never a protected or damageable item) and lets the
     * food be picked up at once. Server tick: it only drops, never walks to, places or fills a chest.
     * A drop that finds nothing it may drop holds this bot's food-pickup make-room off for
     * {@link MutualAidMakeRoomPolicy#RETRY_TICKS}; until then this returns false without dropping.
     *
     * @return whether room was made for the food (the tick then skips this bot)
     */
    private static boolean tryMakeSpaceForNearbyDroppedFood(ServerPlayerEntity bot, ServerWorld world, long nowTick) {
        if (bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        MutualAidMakeRoomPolicy.Decision room = MutualAidMakeRoomPolicy.decide(
                MutualAidMakeRoomPolicy.Kind.FOOD_PICKUP, bot.getInventory().getEmptySlot() != -1);
        if (room == MutualAidMakeRoomPolicy.Decision.DECLINE
                || (room == MutualAidMakeRoomPolicy.Decision.DROP
                        && !mayMakeRoom(bot, MutualAidMakeRoomPolicy.Kind.FOOD_PICKUP, nowTick))) {
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

        if (room == MutualAidMakeRoomPolicy.Decision.DROP) {
            Map<Item, Integer> reserveFood = collectReservedFood(bot);
            if (!CraftingHelper.dropCheapStackForSpace(bot, bot.getCommandSource().withSilent(), reserveFood.keySet())) {
                holdOffMakeRoom(bot, MutualAidMakeRoomPolicy.Kind.FOOD_PICKUP, nowTick);
                return false;
            }
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

    /**
     * Whether {@code recipient} has room for a {@code kind} handoff, made if it must be. Food needs
     * none; an empty slot is room. Otherwise gear makes room by dropping one stack (a cheap one
     * first; never food, weapons, armor, shields or flowers it carries, never a protected or
     * damageable item), and a flower is declined without dropping anything. Server tick: it only
     * drops, never walks to, places or fills a chest. A drop that finds nothing it may drop holds
     * this recipient's make-room for that kind off for {@link MutualAidMakeRoomPolicy#RETRY_TICKS};
     * until then this returns false without dropping.
     */
    private static boolean ensureInventorySpaceForAidRecipient(ServerPlayerEntity recipient, AidKind kind, long nowTick) {
        if (recipient == null || recipient.getCommandSource() == null) {
            return false;
        }
        MutualAidMakeRoomPolicy.Kind roomKind = switch (kind) {
            case FOOD -> MutualAidMakeRoomPolicy.Kind.FOOD_SHARE;
            case GEAR -> MutualAidMakeRoomPolicy.Kind.GEAR;
            case FLOWER -> MutualAidMakeRoomPolicy.Kind.FLOWER;
        };
        MutualAidMakeRoomPolicy.Decision room = MutualAidMakeRoomPolicy.decide(
                roomKind, recipient.getInventory().getEmptySlot() != -1);
        if (room != MutualAidMakeRoomPolicy.Decision.DROP) {
            return room == MutualAidMakeRoomPolicy.Decision.NOT_NEEDED;
        }
        if (!mayMakeRoom(recipient, roomKind, nowTick)) {
            return false;
        }
        Map<Item, Integer> reserveItems = collectAidReserveItems(recipient);
        if (CraftingHelper.dropCheapStackForSpace(recipient, recipient.getCommandSource().withSilent(), reserveItems.keySet())) {
            return true;
        }
        holdOffMakeRoom(recipient, roomKind, nowTick);
        return false;
    }

    /** Whether {@code bot} may drop a stack for {@code kind} now, i.e. no failed {@code kind} make-room drop in the last {@link MutualAidMakeRoomPolicy#RETRY_TICKS}. */
    private static boolean mayMakeRoom(ServerPlayerEntity bot, MutualAidMakeRoomPolicy.Kind kind, long nowTick) {
        Map<MutualAidMakeRoomPolicy.Kind, Long> perKind = NEXT_MAKE_ROOM_TICK.get(bot.getUuid());
        return MutualAidMakeRoomPolicy.mayAttempt(nowTick, perKind == null ? 0L : perKind.getOrDefault(kind, 0L));
    }

    /** After a {@code kind} make-room drop found nothing {@code bot} may drop: no {@code kind} make-room drop for it for {@link MutualAidMakeRoomPolicy#RETRY_TICKS}. */
    private static void holdOffMakeRoom(ServerPlayerEntity bot, MutualAidMakeRoomPolicy.Kind kind, long nowTick) {
        NEXT_MAKE_ROOM_TICK.computeIfAbsent(bot.getUuid(), id -> new ConcurrentHashMap<>())
                .put(kind, MutualAidMakeRoomPolicy.nextAllowedAfterFailure(nowTick));
        LOGGER.info("mutual-aid make-room: {} has nothing it may drop, retry in 10 s (kind={})",
                bot.getName().getString(), kind);
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
        return PENDING_HANDOFFS.containsKey(botId) || DONOR_PENDING.containsKey(botId) || ENGAGEMENTS.containsKey(botId);
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
