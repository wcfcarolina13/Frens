package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.MovementType;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotHomeService.BaseEntry;
import net.shasankp000.GameAI.services.BotCommandStateService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.services.MountedLeafClearingService;
import net.shasankp000.GameAI.services.MountPersistenceService;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.AIPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RideSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger("ride-sync");
    private static final double SEARCH_RADIUS = 24.0D;
    private static final double SEARCH_RADIUS_SQ = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final long RESYNC_COOLDOWN_TICKS = 40L;
    private static final Map<UUID, UUID> SYNC_COMMANDER = new HashMap<>();
    private static final Map<UUID, UUID> SYNC_VEHICLE = new HashMap<>();
    private static final Map<UUID, Long> LAST_SYNC_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_MOUNT_NOTICE_TICK = new HashMap<>();
    private static final long MOUNT_NOTICE_COOLDOWN_TICKS = 120L;
    private static final Map<UUID, Long> LAST_LEASH_NOTICE_TICK = new HashMap<>();
    private static final long LEASH_NOTICE_COOLDOWN_TICKS = 120L;
    private static final Map<UUID, UUID> LEASH_TARGET = new HashMap<>();
    private static final Map<UUID, Double> LAST_LEASH_DIST = new HashMap<>();
    private static final Map<UUID, Long> LAST_LEASH_ATTEMPT_TICK = new HashMap<>();
    private static final long LEASH_ATTEMPT_COOLDOWN_TICKS = 20L;
    private static final double MOUNT_REACH_SQ = 4.5D * 4.5D;
    private static final double LEASH_KEEP_RANGE_SQ = 10.0D * 10.0D;
    private static final Map<UUID, Long> LEASH_PICKUP_START_TICK = new HashMap<>();
    private static final Map<UUID, UUID> LEASH_PICKUP_TARGET = new HashMap<>();
    private static final long LEASH_PICKUP_DELAY_TICKS = 6L;
    private static final long LEASH_PICKUP_TIMEOUT_TICKS = 40L;
    private static final Map<UUID, Long> LAST_DEBUG_TICK = new HashMap<>();
    private static final long DEBUG_INTERVAL_TICKS = 40L;
    private static final Map<UUID, Long> LAST_ACTION_DEBUG_TICK = new HashMap<>();
    private static final long ACTION_DEBUG_INTERVAL_TICKS = 10L;

    // Mounted-follow tight-space recovery (e.g., uphill nooks / corner wedging)
    private static final Map<UUID, Vec3d> LAST_RIDE_VEHICLE_POS = new HashMap<>();
    private static final Map<UUID, Integer> RIDE_STAGNANT_TICKS = new HashMap<>();
    private static final Map<UUID, Long> LAST_RIDE_ESCAPE_LOG_TICK = new HashMap<>();

    private enum RideCategory {
        HORSE_LIKE,
        BOAT,
        MINECART,
        PIG,
        STRIDER
    }

    private static final Set<EntityType<?>> HORSE_TYPES = Set.of(
            EntityType.HORSE,
            EntityType.DONKEY,
            EntityType.MULE,
            EntityType.SKELETON_HORSE,
            EntityType.ZOMBIE_HORSE,
            EntityType.LLAMA,
            EntityType.TRADER_LLAMA,
            EntityType.CAMEL
    );
    private static final Set<EntityType<?>> MINECART_TYPES = Set.of(
            EntityType.MINECART,
            EntityType.CHEST_MINECART,
            EntityType.FURNACE_MINECART,
            EntityType.TNT_MINECART,
            EntityType.HOPPER_MINECART,
            EntityType.COMMAND_BLOCK_MINECART
    );
    private static final List<Item> FENCE_ITEMS = List.of(
            Items.OAK_FENCE,
            Items.SPRUCE_FENCE,
            Items.BIRCH_FENCE,
            Items.JUNGLE_FENCE,
            Items.ACACIA_FENCE,
            Items.DARK_OAK_FENCE,
            Items.MANGROVE_FENCE,
            Items.CHERRY_FENCE,
            Items.BAMBOO_FENCE,
            Items.CRIMSON_FENCE,
            Items.WARPED_FENCE
    );

    private RideSyncService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return;
        }
        Map<ServerPlayerEntity, List<ServerPlayerEntity>> commanderMap = new HashMap<>();
        for (ServerPlayerEntity bot : bots) {
            ServerPlayerEntity commander = resolveCommander(server, bot);
            if (commander == null || commander.isRemoved()) {
                continue;
            }
            commanderMap.computeIfAbsent(commander, ignored -> new ArrayList<>()).add(bot);
        }
        for (Map.Entry<ServerPlayerEntity, List<ServerPlayerEntity>> entry : commanderMap.entrySet()) {
            ServerPlayerEntity commander = entry.getKey();
            List<ServerPlayerEntity> nearbyBots = entry.getValue();
            if (nearbyBots.isEmpty()) {
                continue;
            }
            nearbyBots.sort(Comparator.comparingDouble(b -> b.squaredDistanceTo(commander)));
            handleCommanderGroup(server, commander, nearbyBots);
        }
    }

    private static void handleCommanderGroup(MinecraftServer server,
                                             ServerPlayerEntity commander,
                                             List<ServerPlayerEntity> bots) {
        Entity commanderVehicle = commander.getVehicle();
        RideCategory commanderCategory = commanderVehicle != null ? categorize(commanderVehicle) : null;

        for (ServerPlayerEntity bot : bots) {
            if (!shouldSyncBot(bot, commander)) {
                if (bot.hasVehicle()) {
                    maybeDismount(bot, commander);
                }
                continue;
            }
            if (commanderVehicle == null || commanderCategory == null) {
                maybeDismount(bot, commander);
            }
            // Do not automatically leash/anchor mounts or animals here.
            // Tying leads to fences is an explicit skill (/bot leash or keybind), not an always-on behavior.
        }

        if (commanderVehicle == null || commanderCategory == null) {
            return;
        }

        if (!(commander.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        List<Entity> candidates = findCandidateVehicles(world, commander, bots, commanderCategory);
        if (candidates.isEmpty()) {
            for (ServerPlayerEntity bot : bots) {
                if (shouldSyncBot(bot, commander)) {
                    maybeLogRideStatus(server, bot, commander, commanderVehicle, bot.hasVehicle(), "no-candidates");
                }
            }
            return;
        }

        Set<Integer> claimed = new HashSet<>();
        for (ServerPlayerEntity bot : bots) {
            if (!shouldSyncBot(bot, commander)) {
                continue;
            }
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "scan");
            if (bot.hasVehicle()) {
                maybeSyncMovement(bot, commander);
                continue;
            }
            if (!cooldownReady(server, bot)) {
                continue;
            }
            Entity preferred = resolvePreferredMount(bot, commander, commanderVehicle, world, commanderCategory, claimed);
            if (preferred != null) {
                if (bot.squaredDistanceTo(preferred) > MOUNT_REACH_SQ) {
                    maybeApproachMount(bot, preferred, commander);
                    continue;
                }
                boolean mounted = bot.startRiding(preferred, false, true);
                if (mounted) {
                    SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                    SYNC_VEHICLE.put(bot.getUuid(), preferred.getUuid());
                    LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                    claimed.add(preferred.getId());
                    MountPersistenceService.recordMount(bot, preferred);
                    BotEventHandler.collectNearbyDrops(bot, 4.0D);
                    maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "preferred-mounted");
                }
                continue;
            }
            Entity best = selectNearestVehicle(bot, commander, commanderVehicle, candidates, claimed);
            if (best == null) {
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "no-vehicle");
                continue;
            }
            if (!prepareVehicle(bot, commander, best)) {
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "prepare-failed");
                continue;
            }
            if (bot.squaredDistanceTo(best) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, best, commander);
                continue;
            }
            boolean mounted = bot.startRiding(best, false, true);
            if (mounted) {
                SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                SYNC_VEHICLE.put(bot.getUuid(), best.getUuid());
                LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                claimed.add(best.getId());
                MountPersistenceService.recordMount(bot, best);
                BotEventHandler.collectNearbyDrops(bot, 4.0D);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "mounted");
            }
        }
    }

    private static Entity resolvePreferredMount(ServerPlayerEntity bot,
                                                ServerPlayerEntity commander,
                                                Entity commanderVehicle,
                                                ServerWorld world,
                                                RideCategory commanderCategory,
                                                Set<Integer> claimed) {
        MountPersistenceService.MountState state = MountPersistenceService.getRecordedState(bot);
        if (state == null || world == null) {
            return null;
        }
        String worldId = world.getRegistryKey().getValue().toString();
        if (!worldId.equals(state.worldId())) {
            return null;
        }
        double distSq = bot.squaredDistanceTo(state.x(), state.y(), state.z());
        if (distSq > SEARCH_RADIUS_SQ * 4.0D) {
            return null;
        }
        Entity preferred = MountPersistenceService.findRecordedMount(world, state);
        if (preferred == null || preferred.isRemoved()) {
            return null;
        }
        if (preferred == commanderVehicle) {
            return null;
        }
        if (claimed.contains(preferred.getId())) {
            return null;
        }
        if (preferred.hasPassengers()) {
            return null;
        }
        if (categorize(preferred) != commanderCategory) {
            return null;
        }
        if (!matchesVehicleRules(preferred)) {
            return null;
        }
        if (!isWithinCombinedRadius(bot, commander, preferred)) {
            return null;
        }
        if (!prepareVehicle(bot, commander, preferred)) {
            return null;
        }
        return preferred;
    }

    private static void maybeApproachMount(ServerPlayerEntity bot, Entity mount, ServerPlayerEntity commander) {
        if (bot == null || mount == null) {
            return;
        }
        if (bot.getCommandSource() == null) {
            return;
        }
        Vec3d target = new Vec3d(mount.getX(), mount.getY(), mount.getZ());
        Vec3d delta = new Vec3d(target.x - bot.getX(), 0.0D, target.z - bot.getZ());
        if (delta.lengthSquared() < 1.0E-4) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        boolean sprint = bot.squaredDistanceTo(mount) > 16.0D;
        BotActions.sprint(bot, sprint);
        BotActions.applyMovementInput(bot, target, sprint ? 0.16D : 0.12D);
        maybeLogRideStatus(bot.getCommandSource().getServer(), bot, commander, commander != null ? commander.getVehicle() : null, false, "approach-mount");
    }

    private static boolean shouldSyncBot(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) {
            return false;
        }
        if (bot.isRemoved() || commander.isRemoved()) {
            return false;
        }
        if (bot.getEntityWorld() != commander.getEntityWorld()) {
            return false;
        }
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return false;
        }
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (mode != BotEventHandler.Mode.FOLLOW) {
            return false;
        }
        if (!isFollowingCommander(bot, commander)) {
            return false;
        }
        double distSq = bot.squaredDistanceTo(commander);
        return distSq <= (SEARCH_RADIUS * SEARCH_RADIUS * 4.0D);
    }

    private static void maybeDismount(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (!bot.hasVehicle()) {
            SYNC_COMMANDER.remove(bot.getUuid());
            SYNC_VEHICLE.remove(bot.getUuid());
            clearRideStuck(bot);
            return;
        }
        Entity vehicle = bot.getVehicle();
        if (!isFollowingCommander(bot, commander)) {
            bot.stopRiding();
            if (vehicle != null) {
                handleDismountCare(bot, vehicle);
                MountPersistenceService.recordMount(bot, vehicle);
            }
            SYNC_COMMANDER.remove(bot.getUuid());
            SYNC_VEHICLE.remove(bot.getUuid());
            clearRideStuck(bot);
            return;
        }
        UUID syncCommander = SYNC_COMMANDER.get(bot.getUuid());
        if (syncCommander == null || !syncCommander.equals(commander.getUuid())) {
            return;
        }
        bot.stopRiding();
        if (vehicle != null) {
            handleDismountCare(bot, vehicle);
            MountPersistenceService.recordMount(bot, vehicle);
        }
        SYNC_COMMANDER.remove(bot.getUuid());
        SYNC_VEHICLE.remove(bot.getUuid());
        clearRideStuck(bot);
    }

    private static void clearRideStuck(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID id = bot.getUuid();
        LAST_RIDE_VEHICLE_POS.remove(id);
        RIDE_STAGNANT_TICKS.remove(id);
        LAST_RIDE_ESCAPE_LOG_TICK.remove(id);
    }

    private static int updateRideStagnation(ServerPlayerEntity bot, Entity vehicle, boolean tryingToMove) {
        if (bot == null || vehicle == null) {
            return 0;
        }
        UUID id = bot.getUuid();
        if (!tryingToMove) {
            RIDE_STAGNANT_TICKS.remove(id);
            LAST_RIDE_VEHICLE_POS.put(id, new Vec3d(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
            return 0;
        }

        Vec3d cur = new Vec3d(vehicle.getX(), vehicle.getY(), vehicle.getZ());
        Vec3d prev = LAST_RIDE_VEHICLE_POS.get(id);
        LAST_RIDE_VEHICLE_POS.put(id, cur);

        if (prev == null) {
            RIDE_STAGNANT_TICKS.put(id, 0);
            return 0;
        }

        double dx = cur.x - prev.x;
        double dz = cur.z - prev.z;
        double movedSq = (dx * dx) + (dz * dz);

        int stagnant = RIDE_STAGNANT_TICKS.getOrDefault(id, 0);
        // If the mount isn't making horizontal progress, assume we're wedged on a corner/slope.
        if (movedSq < 0.0009D) { // ~0.03 blocks/tick
            stagnant++;
        } else {
            stagnant = 0;
        }
        RIDE_STAGNANT_TICKS.put(id, stagnant);
        return stagnant;
    }

    private static Vec3d computeEscapeTarget(Entity botVehicle,
                                             Entity commanderVehicle,
                                             double desiredSpace,
                                             int stagnantTicks) {
        if (botVehicle == null || commanderVehicle == null) {
            return null;
        }
        Vec3d commanderPos = new Vec3d(commanderVehicle.getX(), commanderVehicle.getY(), commanderVehicle.getZ());
        Vec3d botPos = new Vec3d(botVehicle.getX(), botVehicle.getY(), botVehicle.getZ());
        Vec3d toCommander = new Vec3d(commanderPos.x - botPos.x, 0.0D, commanderPos.z - botPos.z);
        if (toCommander.lengthSquared() < 1.0E-4) {
            return commanderPos;
        }
        Vec3d dir = toCommander.normalize();
        Vec3d side = new Vec3d(-dir.z, 0.0D, dir.x);

        // Cycle: side-left, side-right, mild-side (recenter), back-out.
        int phase = Math.floorMod(stagnantTicks / 10, 4);
        double sign = (phase == 1) ? -1.0D : 1.0D;

        Vec3d target;
        if (phase == 3) {
            // Back out a bit to free from corners, then the next phases will try side approaches again.
            target = botPos.subtract(dir.multiply(2.5D)).add(side.multiply(sign * 0.9D));
        } else {
            double sideMag = (phase == 2) ? 0.6D : 1.4D;
            // Aim for a point slightly offset behind the commander, which tends to slide along walls rather than headbutting them.
            target = commanderPos.add(dir.multiply(-Math.max(2.5D, desiredSpace))).add(side.multiply(sign * sideMag));
        }

        // Keep Y aligned to commander's mount so uphill steps can still trigger jump logic.
        return new Vec3d(target.x, commanderPos.y, target.z);
    }

    private static boolean cooldownReady(MinecraftServer server, ServerPlayerEntity bot) {
        long now = server.getTicks();
        long last = LAST_SYNC_TICK.getOrDefault(bot.getUuid(), 0L);
        return now - last >= RESYNC_COOLDOWN_TICKS;
    }

    private static Entity selectNearestVehicle(ServerPlayerEntity bot,
                                               ServerPlayerEntity commander,
                                               Entity commanderVehicle,
                                               List<Entity> candidates,
                                               Set<Integer> claimed) {
        double best = Double.MAX_VALUE;
        Entity bestEntity = null;
        for (Entity entity : candidates) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (entity == commanderVehicle) {
                continue;
            }
            if (claimed.contains(entity.getId())) {
                continue;
            }
            if (entity.hasPassengers()) {
                continue;
            }
            if (!isWithinCombinedRadius(bot, commander, entity)) {
                continue;
            }
            double distSq = bot.squaredDistanceTo(entity);
            if (distSq < best) {
                best = distSq;
                bestEntity = entity;
            }
        }
        return bestEntity;
    }

    private static boolean prepareVehicle(ServerPlayerEntity bot, ServerPlayerEntity commander, Entity target) {
        if (target == null) {
            return false;
        }
        if (bot != null && shouldWaitForLeadPickup(bot, target)) {
            return false;
        }
        if (target instanceof MobEntity mob && mob.isLeashed()) {
            // Check if the bot is allowed to take fence-tethered mounts.
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            boolean allowUnleash = state != null && state.unleashTetheredMounts;
            Entity holder = mob.getLeashHolder();

            // If the animal is tied to a fence (LeashKnotEntity), respect the toggle.
            if (holder instanceof LeashKnotEntity knot) {
                if (!allowUnleash) {
                    return false;
                }
                // Allowed to unleash: interact with the knot to take the lead.
                BotActions.interactEntity(bot, knot, Hand.MAIN_HAND);
                startLeadPickup(bot, target);
                return false;
            }
            // If someone else (not a fence, not this bot) is holding the leash, don't take it.
            if (holder != null && holder != bot) {
                return false;
            }
        }
        if (target instanceof MobEntity mob && !mob.isPersistent()) {
            mob.setPersistent();
        }
        if (target instanceof LivingEntity living) {
            maybeAnnounceLowHealth(bot, living);
            if (AnimalFeedingService.isLowHealth(living) && AnimalFeedingService.hasFoodFor(bot, living)) {
                AnimalFeedingService.feedIfNeeded(bot, living);
            }
        }
        if (target instanceof MobEntity mob && !mob.hasSaddleEquipped()) {
            if (!ToolProvisionService.ensureSaddle(bot, bot.getCommandSource(), commander, 1)) {
                return false;
            }
            if (!BotActions.ensureHotbarItem(bot, Items.SADDLE)) {
                return false;
            }
            if (!BotActions.interactEntity(bot, target, Hand.MAIN_HAND)) {
                return false;
            }
        }
        if (target.getType() == EntityType.PIG) {
            ToolProvisionService.ensureCarrotOnStick(bot, bot.getCommandSource(), commander);
            BotActions.ensureHotbarItem(bot, Items.CARROT_ON_A_STICK);
        }
        if (target.getType() == EntityType.STRIDER) {
            ToolProvisionService.ensureWarpedFungusOnStick(bot, bot.getCommandSource(), commander);
            BotActions.ensureHotbarItem(bot, Items.WARPED_FUNGUS_ON_A_STICK);
        }
        return true;
    }

    private static void startLeadPickup(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null || bot.getCommandSource() == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        LEASH_PICKUP_START_TICK.put(bot.getUuid(), (long) server.getTicks());
        LEASH_PICKUP_TARGET.put(bot.getUuid(), target.getUuid());
    }

    private static boolean shouldWaitForLeadPickup(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        UUID pendingTarget = LEASH_PICKUP_TARGET.get(bot.getUuid());
        Long startTick = LEASH_PICKUP_START_TICK.get(bot.getUuid());
        if (pendingTarget == null || startTick == null) {
            return false;
        }
        if (!pendingTarget.equals(target.getUuid())) {
            clearLeadPickup(bot);
            return false;
        }
        long now = server.getTicks();
        long elapsed = now - startTick;
        if (elapsed > LEASH_PICKUP_TIMEOUT_TICKS) {
            clearLeadPickup(bot);
            return false;
        }
        if (elapsed < LEASH_PICKUP_DELAY_TICKS) {
            return true;
        }
        if (approachNearbyLead(bot, 6.0D)) {
            return true;
        }
        clearLeadPickup(bot);
        return false;
    }

    private static boolean approachNearbyLead(ServerPlayerEntity bot, double radius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        Box searchBox = bot.getBoundingBox().expand(radius, radius, radius);
        Entity leadDrop = world.getEntitiesByClass(
                Entity.class,
                searchBox,
                drop -> drop != null
                        && drop.isAlive()
                        && !drop.isRemoved()
                        && drop.getType() == EntityType.ITEM
                        && drop instanceof net.minecraft.entity.ItemEntity item
                        && item.getStack().isOf(Items.LEAD)
        ).stream().min(Comparator.comparingDouble(bot::squaredDistanceTo)).orElse(null);
        if (leadDrop == null) {
            return false;
        }
        Vec3d target = new Vec3d(leadDrop.getX(), leadDrop.getY(), leadDrop.getZ());
        if (bot.squaredDistanceTo(target) <= 1.5D) {
            BotActions.stop(bot);
            return true;
        }
        BotActions.applyMovementInput(bot, target, 0.12D);
        return true;
    }

    private static void clearLeadPickup(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        LEASH_PICKUP_START_TICK.remove(bot.getUuid());
        LEASH_PICKUP_TARGET.remove(bot.getUuid());
    }

    private static void maybeSyncMovement(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (!bot.hasVehicle() || !commander.hasVehicle()) {
            return;
        }
        Entity botVehicle = bot.getVehicle();
        Entity commanderVehicle = commander.getVehicle();
        if (botVehicle instanceof LivingEntity living) {
            maybeAnnounceLowHealth(bot, living);
        }
        if (botVehicle == null || commanderVehicle == null) {
            return;
        }
        MountedLeafClearingService.maybeClear(bot, commander);
        // Use commander's Y so we follow their elevation on slopes
        Vec3d targetPos = new Vec3d(commanderVehicle.getX(), commanderVehicle.getY(), commanderVehicle.getZ());
        double desiredSpace = getDesiredFollowSpace(bot, commander);
        double dx = botVehicle.getX() - commanderVehicle.getX();
        double dz = botVehicle.getZ() - commanderVehicle.getZ();
        double distSq = (dx * dx) + (dz * dz);
        double desiredSq = desiredSpace * desiredSpace;

        // Detect tight-space wedging (common in small uphill nooks) and vary the target slightly to escape.
        boolean following = isFollowingCommander(bot, commander);
        boolean tryingToMove = following && (distSq > desiredSq || distSq < desiredSq * 0.75D);
        int stagnant = updateRideStagnation(bot, botVehicle, tryingToMove);
        if (stagnant >= 10 && following && distSq > desiredSq) {
            Vec3d escape = computeEscapeTarget(botVehicle, commanderVehicle, desiredSpace, stagnant);
            if (escape != null) {
                targetPos = escape;
                long now = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
                long last = LAST_RIDE_ESCAPE_LOG_TICK.getOrDefault(bot.getUuid(), 0L);
                if (now - last >= 20L) {
                    LAST_RIDE_ESCAPE_LOG_TICK.put(bot.getUuid(), now);
                    LOGGER.info("RideSyncEscape: bot={} stagnantTicks={} dist={} desired={} escapeTarget={},{}",
                            bot.getName().getString(),
                            stagnant,
                            String.format(Locale.ROOT, "%.2f", Math.sqrt(distSq)),
                            String.format(Locale.ROOT, "%.2f", desiredSpace),
                            String.format(Locale.ROOT, "%.2f", targetPos.x),
                            String.format(Locale.ROOT, "%.2f", targetPos.z));
                }
            }
        }
        if (isFollowingCommander(bot, commander) && distSq < desiredSq * 0.95D) {
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "backoff", desiredSpace, distSq);
            driveAway(bot, new Vec3d(commanderVehicle.getX(), commanderVehicle.getY(), commanderVehicle.getZ()), commander.isSprinting());
            forceMoveMount(botVehicle, new Vec3d(botVehicle.getX() + dx, botVehicle.getY(), botVehicle.getZ() + dz), commander.isSprinting());
            return;
        }
        if (isFollowingCommander(bot, commander) && distSq > desiredSq) {
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "steer", desiredSpace, distSq);
            driveToward(bot, targetPos, commander.isSprinting());
            forceMoveMount(botVehicle, targetPos, commander.isSprinting());
            return;
        }
        Vec3d commanderVelocity = commanderVehicle.getVelocity();
        double horizontal = commanderVelocity.horizontalLength();
        if (horizontal > 0.05D) {
            alignToCommander(bot, commander);
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "match-vel", desiredSpace, distSq);
            driveToward(bot, targetPos, commander.isSprinting());
            forceMoveMount(botVehicle, targetPos, commander.isSprinting());
        } else if (isFollowingCommander(bot, commander)) {
            dampenVehicle(botVehicle);
            setMountInput(bot, false, false, false);
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "dampen", desiredSpace, distSq);
        } else {
            setMountInput(bot, false, false, false);
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "idle", desiredSpace, distSq);
        }
    }

    private static boolean isFollowingCommander(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) {
            return false;
        }
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        return followTarget != null && followTarget.equals(commander.getUuid());
    }

    private static double getDesiredFollowSpace(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        double desired = bot != null && bot.hasVehicle() ? 4.0D : 3.0D;
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state != null && state.followStandoffRange > 0.0D) {
            desired = Math.max(desired, state.followStandoffRange);
        }
        return desired;
    }

    private static void alignToCommander(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        float yaw = commander.getYaw();
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
    }

    private static void driveToward(ServerPlayerEntity bot, Vec3d targetPos, boolean sprint) {
        if (bot == null || targetPos == null) {
            return;
        }
        Entity mount = bot.getVehicle();
        Vec3d pos = mount != null
                ? new Vec3d(mount.getX(), mount.getY(), mount.getZ())
                : new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d delta = new Vec3d(targetPos.x - pos.x, targetPos.y - pos.y, targetPos.z - pos.z);
        double lenSq = delta.lengthSquared();
        if (lenSq < 1.0E-4) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        boolean jump = delta.y > 0.6D;
        setMountInput(bot, true, sprint, jump);
    }

    private static void driveAway(ServerPlayerEntity bot, Vec3d targetPos, boolean sprint) {
        if (bot == null || targetPos == null) {
            return;
        }
        Entity mount = bot.getVehicle();
        Vec3d pos = mount != null
                ? new Vec3d(mount.getX(), mount.getY(), mount.getZ())
                : new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d delta = new Vec3d(pos.x - targetPos.x, 0.0D, pos.z - targetPos.z);
        if (delta.lengthSquared() < 1.0E-4) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        setMountInput(bot, true, sprint, false);
    }

    private static void forceMoveMount(Entity vehicle, Vec3d targetPos, boolean sprint) {
        if (vehicle == null || targetPos == null) {
            return;
        }
        Vec3d pos = new Vec3d(vehicle.getX(), vehicle.getY(), vehicle.getZ());
        Vec3d delta = new Vec3d(targetPos.x - pos.x, 0.0D, targetPos.z - pos.z);
        double lenSq = delta.lengthSquared();
        if (lenSq < 1.0E-4) {
            // Even if no horizontal movement needed, apply gravity if floating
            applyGravityIfNeeded(vehicle);
            return;
        }
        double speed = sprint ? 0.35D : 0.25D;
        Vec3d step = delta.normalize().multiply(speed);

        // Important: do NOT force positive Y steps here.
        // Let vanilla stepping/jumping resolve uphill movement; only add a small downward component
        // when we are clearly above the ground (downhill ledges / floating correction).
        if (vehicle.getEntityWorld() instanceof ServerWorld world) {
            BlockPos nextPos = new BlockPos(
                    (int) Math.floor(vehicle.getX() + step.x),
                    (int) Math.floor(vehicle.getY()),
                    (int) Math.floor(vehicle.getZ() + step.z)
            );
            BlockPos groundPos = findGroundAt(world, nextPos, (int) vehicle.getY());
            if (groundPos != null) {
                double groundY = groundPos.getY() + 1.0D;
                double currentY = vehicle.getY();
                double yDiff = groundY - currentY;
                if (yDiff < -0.35D) {
                    // We are significantly above ground at the destination XZ; nudge downward so
                    // the mount doesn't "hover" over downhill terrain.
                    step = new Vec3d(step.x, Math.max(-1.0D, yDiff), step.z);
                } else if (yDiff > 0.6D && yDiff <= 1.25D && vehicle.isOnGround()) {
                    // Uphill assist: allow a small upward component so we can clear a 1-block step
                    // without relying on client jump-charge mechanics.
                    step = new Vec3d(step.x, Math.min(0.55D, yDiff), step.z);
                }
            }
        }

        vehicle.move(MovementType.SELF, step);
        
        // Apply downward velocity if not on ground
        applyGravityIfNeeded(vehicle);
    }
    
    /**
     * Apply gravity velocity to a vehicle if it's floating in air.
     */
    private static void applyGravityIfNeeded(Entity vehicle) {
        if (vehicle == null) return;
        
        if (!vehicle.isOnGround()) {
            Vec3d vel = vehicle.getVelocity();
            double yVel = vel.y;
            // Only correct obvious "hovering" (near-zero vertical motion). Do NOT kill upward motion.
            if (yVel > 0.05D) {
                return;
            }
            if (yVel <= -0.08D) {
                return;
            }
            double corrected = Math.min(yVel - 0.08D, -0.08D);
            vehicle.setVelocity(vel.x, corrected, vel.z);
            vehicle.velocityDirty = true;
        }
    }

    /**
     * Find the topmost solid ground block at the given XZ position.
     * @param world The server world
     * @param pos The position to check (XZ matters, Y is starting point)
     * @param referenceY The Y level to search around (usually entity's current Y)
     * @return The topmost solid block position, or null if not found
     */
    private static BlockPos findGroundAt(ServerWorld world, BlockPos pos, int referenceY) {
        if (world == null || pos == null) {
            return null;
        }
        // Search down from well above current position to well below
        // This handles both steep uphills and steep downhills
        int searchTop = referenceY + 5;
        int searchBottom = referenceY - 20; // Search far down for steep drops
        
        for (int y = searchTop; y >= searchBottom; y--) {
            BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
            BlockPos above = checkPos.up();
            BlockState state = world.getBlockState(checkPos);
            BlockState aboveState = world.getBlockState(above);
            
            // Check if this is solid ground with air above (walkable)
            if (!state.isAir() && 
                !state.getCollisionShape(world, checkPos).isEmpty() &&
                (aboveState.isAir() || aboveState.getCollisionShape(world, above).isEmpty())) {
                return checkPos;
            }
        }
        return null;
    }

    private static void secureMountIfPossible(ServerPlayerEntity bot, Entity vehicle) {
        if (bot == null || vehicle == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (!(vehicle instanceof MobEntity mob) || !mob.canBeLeashed()) {
            return;
        }
        if (!ToolProvisionService.ensureLead(bot, bot.getCommandSource(), resolveCommander(world.getServer(), bot), 1)) {
            maybeAnnounceLeashIssue(bot, "I don't have a lead to secure this horse.");
            return;
        }
        if (!BotActions.ensureHotbarItem(bot, Items.LEAD)) {
            maybeAnnounceLeashIssue(bot, "I can't grab a lead to secure this horse.");
            return;
        }
        if (!mob.isLeashed()) {
            BotActions.interactEntity(bot, vehicle, Hand.MAIN_HAND);
        }
        if (!mob.isLeashed() || mob.getLeashHolder() != bot) {
            maybeAnnounceLeashIssue(bot, "I couldn't secure the lead on the horse.");
            return;
        }
        BlockPos fencePos = findNearbyFence(world, vehicle.getBlockPos(), 5);
        if (fencePos == null) {
            fencePos = tryPlaceFenceNear(bot, vehicle.getBlockPos());
        }
        if (fencePos == null) {
            maybeAnnounceLeashIssue(bot, "I don't have a fence to tie this horse to yet. I'll keep it on a lead.");
            if (vehicle instanceof MobEntity leashed && leashed.isLeashed() && leashed.getLeashHolder() == bot) {
                LEASH_TARGET.put(bot.getUuid(), leashed.getUuid());
            }
            return;
        }
        interactFence(bot, fencePos);
        LEASH_TARGET.remove(bot.getUuid());
    }

    private static BlockPos findNearbyFence(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.getBlockState(pos).isIn(BlockTags.FENCES)) {
                continue;
            }
            double dist = origin.getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static BlockPos tryPlaceFenceNear(ServerPlayerEntity bot, BlockPos origin) {
        if (bot == null || origin == null) {
            return null;
        }
        ToolProvisionService.ensureFence(bot, bot.getCommandSource(), resolveCommander(bot.getCommandSource().getServer(), bot), 1);
        for (Item fence : FENCE_ITEMS) {
            if (BotActions.ensureHotbarItem(bot, fence)) {
                break;
            }
        }
        if (bot.getMainHandStack() == null || !isFenceItem(bot.getMainHandStack())) {
            return null;
        }
        List<BlockPos> candidates = List.of(
                origin.add(1, 0, 0),
                origin.add(-1, 0, 0),
                origin.add(0, 0, 1),
                origin.add(0, 0, -1),
                origin.add(1, 0, 1),
                origin.add(-1, 0, -1),
                origin.add(1, 0, -1),
                origin.add(-1, 0, 1)
        );
        for (BlockPos candidate : candidates) {
            if (BotActions.placeBlockAt(bot, candidate, List.of(bot.getMainHandStack().getItem()))) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static boolean isFenceItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        for (Item fence : FENCE_ITEMS) {
            if (item == fence) {
                return true;
            }
        }
        return false;
    }

    private static void interactFence(ServerPlayerEntity bot, BlockPos fencePos) {
        if (bot == null || fencePos == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Vec3d hitVec = Vec3d.ofCenter(fencePos);
        net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
                hitVec, Direction.UP, fencePos, false);
        bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
    }

    private static void setMountInput(ServerPlayerEntity bot, boolean forward, boolean sprint, boolean jump) {
        if (bot == null) {
            return;
        }
        PlayerInput input = forward || sprint || jump
                ? new PlayerInput(forward, false, false, false, jump, false, sprint)
                : PlayerInput.DEFAULT;
        bot.setPlayerInput(input);
    }

    private static void dampenVehicle(Entity vehicle) {
        if (vehicle == null) {
            return;
        }
        Vec3d current = vehicle.getVelocity();
        vehicle.setVelocity(current.x * 0.25D, current.y, current.z * 0.25D);
    }

    private static void maybeLogRideStatus(MinecraftServer server,
                                           ServerPlayerEntity bot,
                                           ServerPlayerEntity commander,
                                           Entity commanderVehicle,
                                           boolean hasVehicle,
                                           String phase) {
        if (server == null || bot == null || commander == null) {
            return;
        }
        long now = server.getTicks();
        long last = LAST_DEBUG_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - last < DEBUG_INTERVAL_TICKS && !"mounted".equals(phase)) {
            return;
        }
        LAST_DEBUG_TICK.put(bot.getUuid(), now);
        Entity botVehicle = bot.getVehicle();
        Vec3d botVel = botVehicle != null ? botVehicle.getVelocity() : bot.getVelocity();
        Vec3d commanderVel = commanderVehicle != null ? commanderVehicle.getVelocity() : commander.getVelocity();
        double dist = commanderVehicle != null && botVehicle != null
                ? Math.sqrt(botVehicle.squaredDistanceTo(commanderVehicle))
                : bot.squaredDistanceTo(commander);
        LOGGER.info("RideSync: phase={} bot={} mode={} followTarget={} hasVehicle={} botVehicle={} commanderVehicle={} dist={} botVel={} commanderVel={}",
                phase,
                bot.getName().getString(),
                BotEventHandler.getCurrentMode(bot),
                BotEventHandler.getFollowTargetUuid(bot),
                hasVehicle || botVehicle != null,
                botVehicle != null ? botVehicle.getType().toString() : "none",
                commanderVehicle != null ? commanderVehicle.getType().toString() : "none",
                String.format(Locale.ROOT, "%.2f", dist),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", botVel.x, botVel.y, botVel.z),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", commanderVel.x, commanderVel.y, commanderVel.z));
    }

    private static void maybeLogRideMovement(MinecraftServer server,
                                             ServerPlayerEntity bot,
                                             ServerPlayerEntity commander,
                                             Entity commanderVehicle,
                                             String phase,
                                             double desiredSpace,
                                             double distSq) {
        if (server == null || bot == null || commander == null) {
            return;
        }
        long now = server.getTicks();
        long last = LAST_ACTION_DEBUG_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - last < ACTION_DEBUG_INTERVAL_TICKS) {
            return;
        }
        LAST_ACTION_DEBUG_TICK.put(bot.getUuid(), now);
        Entity botVehicle = bot.getVehicle();
        Vec3d botVel = botVehicle != null ? botVehicle.getVelocity() : bot.getVelocity();
        Vec3d commanderVel = commanderVehicle != null ? commanderVehicle.getVelocity() : commander.getVelocity();
        LOGGER.info("RideSyncMove: phase={} bot={} followTarget={} desired={} dist={} botVel={} commanderVel={}",
                phase,
                bot.getName().getString(),
                BotEventHandler.getFollowTargetUuid(bot),
                String.format(Locale.ROOT, "%.2f", desiredSpace),
                String.format(Locale.ROOT, "%.2f", Math.sqrt(distSq)),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", botVel.x, botVel.y, botVel.z),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", commanderVel.x, commanderVel.y, commanderVel.z));
    }

    private static void handleDismountCare(ServerPlayerEntity bot, Entity vehicle) {
        if (bot == null || vehicle == null) {
            return;
        }
        if (vehicle instanceof LivingEntity living) {
            maybeAnnounceLowHealth(bot, living);
            if (AnimalFeedingService.isLowHealth(living) && AnimalFeedingService.hasFoodFor(bot, living)) {
                AnimalFeedingService.feedIfNeeded(bot, living);
            }
        }
        // Only secure the mount if the leash-on-dismount toggle is enabled for this bot.
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state != null && state.leashMountsOnDismount) {
            secureMountIfPossible(bot, vehicle);
        }
    }

    public static void secureMountAfterRejoin(ServerPlayerEntity bot, Entity vehicle) {
        if (bot == null || vehicle == null) {
            return;
        }
        handleDismountCare(bot, vehicle);
    }

    public static void secureLeashedMountOnDisconnect(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MobEntity mount = findLeashedMount(bot, world, 12.0D);
        if (mount == null) {
            return;
        }
        MountPersistenceService.recordMount(bot, mount, false);
        boolean anchored = anchorLeashedMount(bot, mount);
        LOGGER.info("Leashed mount disconnect secure: bot={} mount={} anchored={}",
                bot.getName().getString(),
                mount.getUuid(),
                anchored);
        if (!anchored) {
            LOGGER.info("Leashed mount secure details: leashed={} holder=bot hasLead={}",
                    mount.isLeashed(),
                    mount.getLeashHolder() == bot,
                    hasItem(bot, Items.LEAD));
        }
    }

    private static void maybeAnnounceLowHealth(ServerPlayerEntity bot, LivingEntity mount) {
        if (bot == null || mount == null || !isBadlyHurt(mount)) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        long last = LAST_MOUNT_NOTICE_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - last < MOUNT_NOTICE_COOLDOWN_TICKS) {
            return;
        }
        LAST_MOUNT_NOTICE_TICK.put(bot.getUuid(), now);
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(AIPlayer.OPERATOR_PERMISSIONS),
                "This horse looks hurt.", true);
        if (!hasItem(bot, Items.APPLE)) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(AIPlayer.OPERATOR_PERMISSIONS),
                    "I don't have any apples to heal it.", true);
        }
        if (!AnimalFeedingService.hasFoodFor(bot, mount)) {
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(AIPlayer.OPERATOR_PERMISSIONS),
                    "I don't have any suitable food to heal it.", true);
        }
    }

    private static boolean isBadlyHurt(LivingEntity mount) {
        if (mount == null) {
            return false;
        }
        float max = mount.getMaxHealth();
        return max > 0.0f && mount.getHealth() <= max * 0.5f;
    }

    private static void maybeAnnounceLeashIssue(ServerPlayerEntity bot, String message) {
        if (bot == null || message == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        long last = LAST_LEASH_NOTICE_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - last < LEASH_NOTICE_COOLDOWN_TICKS) {
            return;
        }
        LAST_LEASH_NOTICE_TICK.put(bot.getUuid(), now);
        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(AIPlayer.OPERATOR_PERMISSIONS),
                message, true);
    }

    private static boolean hasItem(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static MobEntity findLeashedMount(ServerPlayerEntity bot, ServerWorld world, double radius) {
        if (bot == null || world == null) {
            return null;
        }
        Box box = new Box(bot.getBlockPos()).expand(radius);
        List<MobEntity> leashed = world.getEntitiesByClass(MobEntity.class, box,
                mob -> mob != null && mob.isLeashed() && mob.getLeashHolder() == bot);
        if (leashed.isEmpty()) {
            return null;
        }
        leashed.sort(Comparator.comparingDouble(bot::squaredDistanceTo));
        return leashed.get(0);
    }

    private static boolean anchorLeashedMount(ServerPlayerEntity bot, MobEntity mount) {
        if (bot == null || mount == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!mount.isLeashed() || mount.getLeashHolder() != bot) {
            return false;
        }
        BlockPos fencePos = findNearbyFence(world, mount.getBlockPos(), 5);
        if (fencePos == null) {
            fencePos = tryPlaceFenceNear(bot, mount.getBlockPos());
        }
        if (fencePos == null) {
            return false;
        }
        // Select a lead (or safe hand) before interacting with the fence.
        // Interacting with the fence while holding a leashed animal transfers the lead to the fence.
        // Don't interact with the mount itself - that would re-attach/detach the lead.
        selectLeadOrSafeHand(bot);
        interactFence(bot, fencePos);
        // Verify attachment succeeded.
        return !mount.isLeashed() || mount.getLeashHolder() != bot;
    }

    /**
     * Select a lead in the hotbar, or fall back to an empty slot or non-placeable item.
     * This matches LeashToFenceSkill's behavior.
     */
    private static void selectLeadOrSafeHand(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        // Prefer holding a lead.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.LEAD)) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        // Fall back to empty slot.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        // Fall back to non-block item.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.getItem() instanceof net.minecraft.item.BlockItem)) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
    }

    private static void maybeMaintainLeash(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID mountId = LEASH_TARGET.get(bot.getUuid());
        if (mountId == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Entity entity = world.getEntity(mountId);
        if (!(entity instanceof MobEntity mob)) {
            LEASH_TARGET.remove(bot.getUuid());
            maybeAnnounceLeashIssue(bot, "I lost track of the horse I was holding.");
            return;
        }
        if (!mob.isAlive()) {
            LEASH_TARGET.remove(bot.getUuid());
            maybeAnnounceLeashIssue(bot, "The horse I was holding is gone.");
            return;
        }
        double distSq = bot.squaredDistanceTo(mob);
        double lastDist = LAST_LEASH_DIST.getOrDefault(bot.getUuid(), distSq);
        LAST_LEASH_DIST.put(bot.getUuid(), distSq);
        if (Math.abs(distSq - lastDist) > 64.0D) {
            LEASH_TARGET.remove(bot.getUuid());
            maybeAnnounceLeashIssue(bot, "The lead snapped after a sudden drop.");
            return;
        }
        if (mob.isLeashed() && mob.getLeashHolder() == bot) {
            return;
        }
        if (distSq > LEASH_KEEP_RANGE_SQ) {
            return;
        }
        long now = server.getTicks();
        long lastAttempt = LAST_LEASH_ATTEMPT_TICK.getOrDefault(bot.getUuid(), 0L);
        if (now - lastAttempt < LEASH_ATTEMPT_COOLDOWN_TICKS) {
            return;
        }
        LAST_LEASH_ATTEMPT_TICK.put(bot.getUuid(), now);
        if (!ToolProvisionService.ensureLead(bot, bot.getCommandSource(), resolveCommander(server, bot), 1)) {
            maybeAnnounceLeashIssue(bot, "I don't have a lead to reattach.");
            return;
        }
        if (!BotActions.ensureHotbarItem(bot, Items.LEAD)) {
            return;
        }
        BotActions.interactEntity(bot, mob, Hand.MAIN_HAND);
    }

    private static void maybeAnchorLeashedMount(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        MobEntity mount = findLeashedMount(bot, world, 8.0D);
        if (mount == null) {
            return;
        }
        anchorLeashedMount(bot, mount);
    }


    private static List<Entity> findCandidateVehicles(ServerWorld world,
                                                      ServerPlayerEntity commander,
                                                      List<ServerPlayerEntity> bots,
                                                      RideCategory category) {
        if (world == null || commander == null) {
            return List.of();
        }
        BlockPos center = commander.getBlockPos();
        Box box = new Box(center).expand(SEARCH_RADIUS + 4.0D);
        List<Entity> entities = world.getEntitiesByClass(Entity.class, box, entity -> entity != null && entity.isAlive());
        List<Entity> matches = new ArrayList<>();
        for (Entity entity : entities) {
            if (categorize(entity) != category) {
                continue;
            }
            if (!matchesVehicleRules(entity)) {
                continue;
            }
            matches.add(entity);
        }
        matches.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(commander)));
        return matches;
    }

    private static boolean matchesVehicleRules(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        if (entity instanceof LivingEntity living && living.isDead()) {
            return false;
        }
        return true;
    }

    private static boolean isWithinCombinedRadius(ServerPlayerEntity bot, ServerPlayerEntity commander, Entity entity) {
        if (bot == null || commander == null || entity == null) {
            return false;
        }
        double distCommander = commander.squaredDistanceTo(entity);
        double distBot = bot.squaredDistanceTo(entity);
        if (distCommander <= SEARCH_RADIUS_SQ && distBot <= SEARCH_RADIUS_SQ) {
            return true;
        }
        return isWithinAnchorRadius(bot, commander, entity);
    }

    private static boolean isWithinAnchorRadius(ServerPlayerEntity bot, ServerPlayerEntity commander, Entity entity) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> anchors = new ArrayList<>();
        BotHomeService.getLastSleep(bot).ifPresent(bedPos -> {
            if (withinRadius(bot, commander, bedPos)) {
                anchors.add(bedPos);
            }
        });
        if (world.getServer() != null) {
            List<BaseEntry> bases = BotHomeService.listBases(world.getServer(), world);
            for (BaseEntry base : bases) {
                BlockPos pos = base.pos();
                if (pos != null && withinRadius(bot, commander, pos)) {
                    anchors.add(pos);
                }
            }
        }
        for (BlockPos anchor : anchors) {
            if (anchor != null && entity.squaredDistanceTo(Vec3d.ofCenter(anchor)) <= SEARCH_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinRadius(ServerPlayerEntity bot, ServerPlayerEntity commander, BlockPos pos) {
        if (bot == null || commander == null || pos == null) {
            return false;
        }
        double botSq = bot.getBlockPos().getSquaredDistance(pos);
        double commanderSq = commander.getBlockPos().getSquaredDistance(pos);
        return botSq <= SEARCH_RADIUS_SQ && commanderSq <= SEARCH_RADIUS_SQ;
    }

    private static RideCategory categorize(Entity entity) {
        if (entity == null) {
            return null;
        }
        EntityType<?> type = entity.getType();
        if (HORSE_TYPES.contains(type)) {
            return RideCategory.HORSE_LIKE;
        }
        if (type.isIn(EntityTypeTags.BOAT)) {
            return RideCategory.BOAT;
        }
        if (MINECART_TYPES.contains(type)) {
            return RideCategory.MINECART;
        }
        if (type == EntityType.PIG) {
            return RideCategory.PIG;
        }
        if (type == EntityType.STRIDER) {
            return RideCategory.STRIDER;
        }
        return null;
    }

    private static ServerPlayerEntity resolveCommander(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return null;
        }
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget != null) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(followTarget);
            if (target != null && !target.isSpectator()) {
                return target;
            }
        }
        return server.getPlayerManager().getPlayerList().stream()
                .filter(player -> !player.getUuid().equals(bot.getUuid()))
                .filter(player -> !player.isSpectator())
                .min(Comparator.comparingDouble(player -> player.squaredDistanceTo(bot)))
                .orElse(null);
    }
}
