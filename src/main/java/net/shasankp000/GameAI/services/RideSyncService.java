package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.ChestBoatEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.item.MinecartItem;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
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
import net.minecraft.util.ActionResult;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.services.BotHomeService.BaseEntry;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.AIPlayer;
import org.slf4j.Logger;
import net.shasankp000.GameAI.services.HotbarLockService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RideSyncService {
    // Use the mod's main logger so these diagnostics reliably appear in Minecraft's log output.
    private static final Logger LOGGER = AIPlayer.LOGGER;
    private static final String RIDESYNC_MARKER = "RideSyncService loaded (marker=2026-01-18-boat-follow-v2)";

    static {
        // This runs as soon as the class is loaded (at mod init due to tick registration).
        // If you do not see this line in latest.log, the running jar does NOT include the latest RideSync changes.
        try {
            LOGGER.info(RIDESYNC_MARKER);
        } catch (Throwable ignored) {
        }
    }
    private static final double SEARCH_RADIUS = 24.0D;
    private static final double SEARCH_RADIUS_SQ = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final long RESYNC_COOLDOWN_TICKS = 40L;
    private static final long BOAT_RESYNC_COOLDOWN_TICKS = 10L;
    private static final long MINECART_RESYNC_COOLDOWN_TICKS = 10L;
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
    private static final Map<UUID, UUID> LAST_COMMANDER_BOAT = new HashMap<>();
    private static final Map<UUID, Long> LAST_COMMANDER_BOAT_LOST_TICK = new HashMap<>();
    private static final long COMMANDER_BOAT_DESTROY_WINDOW_TICKS = 60L;
    private static final Map<UUID, UUID> LAST_BOT_BOAT = new HashMap<>();
    private static final Map<UUID, UUID> PENDING_BOAT_BREAK = new HashMap<>();
    private static final Map<UUID, Long> PENDING_BOAT_BREAK_START = new HashMap<>();
    private static final Map<UUID, Long> LAST_BOAT_BREAK_TICK = new HashMap<>();
    private static final long BOAT_BREAK_TIMEOUT_TICKS = 80L;
    private static final long BOAT_BREAK_COOLDOWN_TICKS = 5L;
    private static final Map<UUID, BlockPos> PENDING_BOAT_PLACE_POS = new HashMap<>();
    private static final Map<UUID, Long> PENDING_BOAT_PLACE_TICK = new HashMap<>();
    private static final long BOAT_PLACE_TIMEOUT_TICKS = 60L;
    private static final Map<UUID, UUID>     LAST_COMMANDER_MINECART           = new HashMap<>();
    private static final Map<UUID, Long>     LAST_COMMANDER_MINECART_LOST_TICK = new HashMap<>();
    private static final Map<UUID, UUID>     LAST_BOT_MINECART                = new HashMap<>();
    private static final Map<UUID, UUID>     PENDING_MINECART_BREAK           = new HashMap<>();
    private static final Map<UUID, Long>     PENDING_MINECART_BREAK_START     = new HashMap<>();
    private static final Map<UUID, Long>     LAST_MINECART_BREAK_TICK         = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_MINECART_PLACE_POS       = new HashMap<>();
    private static final Map<UUID, Long>     PENDING_MINECART_PLACE_TICK      = new HashMap<>();

    // When RideSync decides the bot should approach a mount/boat/water placement, normal FOLLOW movement
    // can overwrite the movement input later in the tick. Persist a short-lived "approach target" that
    // FOLLOW can consult to steer toward the mount first.
    private static final Map<UUID, Vec3d> FOLLOW_OVERRIDE_TARGET = new HashMap<>();
    private static final Map<UUID, Long> FOLLOW_OVERRIDE_UNTIL_TICK = new HashMap<>();
    private static final long FOLLOW_OVERRIDE_TTL_TICKS = 30L;

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

    public static Vec3d getBoatFollowOverrideTarget(ServerPlayerEntity bot,
                                                    ServerPlayerEntity commander,
                                                    MinecraftServer server) {
        if (bot == null || commander == null || server == null) {
            return null;
        }
        UUID id = bot.getUuid();
        if (bot.hasVehicle()) {
            clearFollowOverride(id);
            return null;
        }
        Entity commanderVehicle = commander.getVehicle();
        RideCategory cat = commanderVehicle != null ? categorize(commanderVehicle) : null;
        if (cat != RideCategory.BOAT && cat != RideCategory.MINECART) {
            clearFollowOverride(id);
            return null;
        }
        Long until = FOLLOW_OVERRIDE_UNTIL_TICK.get(id);
        if (until == null || server.getTicks() > until) {
            clearFollowOverride(id);
            return null;
        }
        return FOLLOW_OVERRIDE_TARGET.get(id);
    }

    private static void setFollowOverride(ServerPlayerEntity bot, MinecraftServer server, Vec3d targetPos) {
        if (bot == null || server == null || targetPos == null) {
            return;
        }
        UUID id = bot.getUuid();
        FOLLOW_OVERRIDE_TARGET.put(id, targetPos);
        FOLLOW_OVERRIDE_UNTIL_TICK.put(id, (long) server.getTicks() + FOLLOW_OVERRIDE_TTL_TICKS);
    }

    private static void clearFollowOverride(UUID botId) {
        if (botId == null) {
            return;
        }
        FOLLOW_OVERRIDE_TARGET.remove(botId);
        FOLLOW_OVERRIDE_UNTIL_TICK.remove(botId);
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return;
        }
        maybeProcessBoatBreaks(server, bots);
        maybeProcessBoatPlacements(server, bots);
        maybeProcessMinecartBreaks(server, bots);
        maybeProcessMinecartPlacements(server, bots);

        // Low-frequency heartbeat for field debugging: confirms RideSync is actually ticking in-game.
        // (This has proven essential when reports show "no RideSync logs".)
        int tick = server.getTicks();
        try {
            ServerPlayerEntity sample = bots.getFirst();
            boolean hasTask = sample != null && TaskService.hasActiveTask(sample.getUuid());

            // Extra context: the most common confusion case is "RideSync is ticking" but the commander
            // is not actually in a boat during the captured log window, so no boat logic runs.
            ServerPlayerEntity commander = sample != null ? resolveCommander(server, sample) : null;
            Entity commanderVehicle = commander != null ? commander.getVehicle() : null;
            String commanderName = commander != null ? commander.getName().getString() : "none";
            String commanderVehicleType = commanderVehicle != null ? commanderVehicle.getType().toString() : "none";
            boolean commanderBoating = commanderVehicle != null && categorize(commanderVehicle) == RideCategory.BOAT;
            boolean commanderChestBoat = commanderVehicle != null && isChestBoat(commanderVehicle);
            boolean commanderMinecart = commanderVehicle != null && categorize(commanderVehicle) == RideCategory.MINECART;
            double distToCommander = (sample != null && commander != null) ? Math.sqrt(sample.squaredDistanceTo(commander)) : Double.NaN;

            // Log every 10s normally, but when the commander is boating/carting, log more frequently so short repros
            // still capture useful context.
            boolean emit = (tick % 200) == 0;
            if (!emit && (commanderBoating || commanderMinecart)) {
                emit = (tick % 40) == 0; // ~2s
            }
            if (emit) {
                LOGGER.info("RideSyncTick: bots={} sample={} mode={} followTarget={} hasTask={} botHasVehicle={} commander={} commanderVehicle={} commanderBoating={} commanderChestBoat={} commanderMinecart={} distToCommander={}",
                        bots.size(),
                        sample != null ? sample.getName().getString() : "none",
                        sample != null ? BotEventHandler.getCurrentMode(sample) : "none",
                        sample != null ? BotEventHandler.getFollowTargetUuid(sample) : null,
                        hasTask,
                        sample != null && sample.hasVehicle(),
                        commanderName,
                        commanderVehicleType,
                        commanderBoating,
                        commanderChestBoat,
                        commanderMinecart,
                        Double.isFinite(distToCommander) ? String.format(Locale.ROOT, "%.2f", distToCommander) : "NaN");
            }
        } catch (Throwable ignored) {
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
        updateCommanderBoatTracking(server, commander, commanderVehicle);
        updateCommanderMinecartTracking(server, commander, commanderVehicle);

        for (ServerPlayerEntity bot : bots) {
            if (!shouldSyncBot(bot, commander)) {
                // When the commander is boating and the bot is too far to qualify, emit a low-frequency
                // diagnostic so we can distinguish "RideSync never ran" from "RideSync ran but skipped".
                if (commanderVehicle != null && (commanderCategory == RideCategory.BOAT || commanderCategory == RideCategory.MINECART)) {
                    maybeLogRideStatus(server, bot, commander, commanderVehicle, bot.hasVehicle(), "skip:ineligible");
                }
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

        maybeQueueBoatBreaksFromCommanderLoss(server, commander, bots);
        maybeQueueMinecartBreaksFromCommanderLoss(server, commander, bots);

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
            recordBotBoat(bot);
            recordBotMinecart(bot);
            maybeSyncMovement(bot, commander);
            continue;
        }
            if (!cooldownReady(server, bot, commanderCategory)) {
                continue;
            }

            // Boats can carry multiple passengers. If the commander is already in a boat and there's an empty seat,
            // prefer joining the commander's boat rather than requiring a separate boat.
            if (commanderCategory == RideCategory.BOAT
                    && tryJoinCommanderBoat(server, bot, commander, commanderVehicle, claimed)) {
                continue;
            }

            // If we can't (or shouldn't) join the commander's boat (e.g., chest boat, seat full),
            // prefer mounting ANY nearby empty boat close to the bot. This lets the bot stop
            // swimming-follow and actually catch up even when the commander is far ahead.
            if (commanderCategory == RideCategory.BOAT
                    && tryMountNearbyIndependentBoat(server, bot, commander, commanderVehicle, claimed)) {
                continue;
            }

            // If we did not join the commander's boat (seat reserved/full/chest boat), try to ensure the bot has its
            // own boat by placing one from inventory when there isn't an empty nearby boat available.
            if (commanderCategory == RideCategory.BOAT) {
                if (tryPlaceAndMountOwnBoat(server, bot, commander, commanderVehicle, claimed)) {
                    continue;
                }
            }

            // Minecart parity: find or place a minecart when the commander is in one.
            if (commanderCategory == RideCategory.MINECART
                    && tryMountNearbyIndependentMinecart(server, bot, commander, commanderVehicle, claimed)) {
                continue;
            }
            if (commanderCategory == RideCategory.MINECART
                    && tryPlaceAndMountOwnMinecart(server, bot, commander, commanderVehicle, claimed)) {
                continue;
            }

            Entity preferred = resolvePreferredMount(bot, commander, commanderVehicle, world, commanderCategory, claimed);
            if (preferred != null) {
                if (bot.squaredDistanceTo(preferred) > MOUNT_REACH_SQ) {
                    maybeApproachMount(bot, preferred, commander);
                    continue;
                }
                boolean mounted = tryMount(bot, preferred);
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
            boolean mounted = tryMount(bot, best);
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

    /**
     * For boat-follow: mount a nearby empty boat near the bot (not necessarily near the commander).
     * This is important when the commander is in a single-seat boat (e.g., chest boat) or when the
     * commander has moved far enough that "combined radius" filters would reject local boats.
     */
    private static boolean tryMountNearbyIndependentBoat(MinecraftServer server,
                                                         ServerPlayerEntity bot,
                                                         ServerPlayerEntity commander,
                                                         Entity commanderVehicle,
                                                         Set<Integer> claimed) {
        if (server == null || bot == null || commander == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle()) {
            return false;
        }

        // Search near the bot. Keep this reasonably bounded, but large enough to catch the common
        // "boat is right next to the bot" cases even with slight vertical offsets / drift.
        final double radius = 20.0D;
        Box box = bot.getBoundingBox().expand(radius, 6.0D, radius);
        final int commanderVehicleId = commanderVehicle != null ? commanderVehicle.getId() : -1;
        
        // DEBUG: Log what we're excluding
        LOGGER.info("tryMountNearbyIndependentBoat: excludingId={} commanderVehicle={}", 
            commanderVehicleId, 
            commanderVehicle != null ? commanderVehicle.getType().getName().getString() : "null");
        
        List<Entity> boats = world.getEntitiesByClass(
                Entity.class,
                box,
                e -> e != null
                        && e.isAlive()
                        && !e.isRemoved()
                        && e.getId() != commanderVehicleId  // Use ID comparison, not reference
                        && categorize(e) == RideCategory.BOAT
                        && isBoatSeatAvailable(e)
        );
        
        // DEBUG: Log what boats were found
        for (Entity boat : boats) {
            LOGGER.info("tryMountNearbyIndependentBoat: foundBoat id={} type={} hasPassengers={}", 
                boat.getId(), boat.getType().getName().getString(), boat.hasPassengers());
        }

        if (boats.isEmpty()) {
            return false;
        }

        List<Entity> emptyBoats = new ArrayList<>();
        List<Entity> occupiedBoats = new ArrayList<>();
        for (Entity boat : boats) {
            if (boat == null) {
                continue;
            }
            if (boat.hasPassengers()) {
                occupiedBoats.add(boat);
            } else {
                emptyBoats.add(boat);
            }
        }
        List<Entity> candidates = emptyBoats.isEmpty() ? occupiedBoats : emptyBoats;

        // Prefer non-chest boats first, but allow chest boats if that's what's available.
        candidates.sort(Comparator
                .comparingInt((Entity e) -> isChestBoat(e) ? 1 : 0)
                .thenComparingDouble(bot::squaredDistanceTo));

        for (Entity candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (claimed != null && claimed.contains(candidate.getId())) {
                continue;
            }
            if (bot.squaredDistanceTo(candidate) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, candidate, commander);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false,
                        "nearby-boat:approach count=" + boats.size() + " best=" + candidate.getType());
                return true;
            }
            boolean mounted = tryMount(bot, candidate);
            if (!mounted) {
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "nearby-boat:mount-failed");
                if (claimed != null) {
                    claimed.add(candidate.getId());
                }
                continue;
            }

            SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
            SYNC_VEHICLE.put(bot.getUuid(), candidate.getUuid());
            LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
            if (claimed != null) {
                claimed.add(candidate.getId());
            }
            MountPersistenceService.recordMount(bot, candidate);
            BotEventHandler.collectNearbyDrops(bot, 4.0D);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "nearby-boat:mounted");
            return true;
        }
        return false;
    }

    /**
     * Search for a nearby empty rideable minecart (EntityType.MINECART only — not TNT/hopper/furnace/etc.)
     * and mount it. Mirrors tryMountNearbyIndependentBoat but simpler since minecarts are single-passenger.
     */
    private static boolean tryMountNearbyIndependentMinecart(MinecraftServer server,
                                                              ServerPlayerEntity bot,
                                                              ServerPlayerEntity commander,
                                                              Entity commanderVehicle,
                                                              Set<Integer> claimed) {
        if (server == null || bot == null || commander == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle()) {
            return false;
        }

        final double radius = 20.0D;
        Box box = bot.getBoundingBox().expand(radius, 6.0D, radius);
        final int commanderVehicleId = commanderVehicle != null ? commanderVehicle.getId() : -1;

        List<Entity> minecarts = world.getEntitiesByClass(
                Entity.class,
                box,
                e -> e != null
                        && e.isAlive()
                        && !e.isRemoved()
                        && e.getId() != commanderVehicleId
                        && e.getType() == EntityType.MINECART
                        && !e.hasPassengers()
        );

        if (minecarts.isEmpty()) {
            return false;
        }

        minecarts.sort(Comparator.comparingDouble(bot::squaredDistanceTo));

        for (Entity candidate : minecarts) {
            if (candidate == null) {
                continue;
            }
            if (claimed != null && claimed.contains(candidate.getId())) {
                continue;
            }
            if (bot.squaredDistanceTo(candidate) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, candidate, commander);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false,
                        "nearby-minecart:approach count=" + minecarts.size());
                return true;
            }
            boolean mounted = tryMount(bot, candidate);
            if (!mounted) {
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "nearby-minecart:mount-failed");
                if (claimed != null) {
                    claimed.add(candidate.getId());
                }
                continue;
            }

            SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
            SYNC_VEHICLE.put(bot.getUuid(), candidate.getUuid());
            LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
            if (claimed != null) {
                claimed.add(candidate.getId());
            }
            MountPersistenceService.recordMount(bot, candidate);
            BotEventHandler.collectNearbyDrops(bot, 4.0D);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "nearby-minecart:mounted");
            return true;
        }
        return false;
    }

    private static boolean tryJoinCommanderBoat(MinecraftServer server,
                                               ServerPlayerEntity bot,
                                               ServerPlayerEntity commander,
                                               Entity commanderVehicle,
                                               Set<Integer> claimed) {
        if (server != null && hasPendingBoatPlacement(server, bot)) {
            // Bot is already trying to place its own boat; avoid fighting for the commander's seat.
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:skip-pending-place");
            return false;
        }
        if (server == null || bot == null || commander == null || commanderVehicle == null) {
            return false;
        }
        if (claimed != null && claimed.contains(commanderVehicle.getId())) {
            return false;
        }
        if (categorize(commanderVehicle) != RideCategory.BOAT) {
            return false;
        }

        // User intent: if it's a chest boat, the bot should NOT join (player likely wants inventory access).
        if (isChestBoat(commanderVehicle)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:chest-skip");
            return false;
        }

        // If another usable boat is nearby, prefer that instead of joining the commander's boat.
        if (hasNearbyIndependentBoat(bot, commanderVehicle, 20.0D)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:alt-available");
            return false;
        }

        // If anyone besides the commander is already in the boat, don't try to squeeze in.
        if (hasNonCommanderPassenger(commanderVehicle, commander)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:occupied");
            return false;
        }

        // User intent: if the commander is leading a mob on a lead, reserve the extra seat for that mob.
        if (commanderHasNearbyLeashedMob(commander)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:seat-reserved");
            return false;
        }

        // Vanilla boats have 2 seats (and the BOAT tag includes rafts/chest boats).
        int passengers = commanderVehicle.getPassengerList() != null ? commanderVehicle.getPassengerList().size() : 0;
        if (passengers >= 2) {
            if (claimed != null) {
                claimed.add(commanderVehicle.getId());
            }
            // Important: allow fallback to placing an independent boat when the commander boat is full.
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:full");
            return false;
        }

        // If the bot is extremely far from the commander's boat, do NOT try to swim all the way to it.
        // In that situation it's better to mount/place a local independent boat near the bot.
        // (Returning false allows downstream fallbacks to run.)
        final double tooFarSq = 64.0D * 64.0D;
        if (bot.squaredDistanceTo(commanderVehicle) > tooFarSq) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:too-far");
            return false;
        }

        if (bot.squaredDistanceTo(commanderVehicle) > MOUNT_REACH_SQ) {
            maybeApproachMount(bot, commanderVehicle, commander);
            return true;
        }

        boolean mounted = tryMount(bot, commanderVehicle);
        if (!mounted) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "commander-boat:mount-failed");
            // Allow fallback to separate boats if present.
            return false;
        }
        SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
        SYNC_VEHICLE.put(bot.getUuid(), commanderVehicle.getUuid());
        LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
        if (claimed != null) {
            claimed.add(commanderVehicle.getId());
        }
        MountPersistenceService.recordMount(bot, commanderVehicle);
        BotEventHandler.collectNearbyDrops(bot, 4.0D);

        // Force a log line even if we already logged "scan" this tick.
        maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "commander-mounted");
        return true;
    }

    /**
     * Attempts to mount via direct riding first, then falls back to interaction-style mounting.
     * Some entity types (and some server-side fake-player implementations) are more reliable via interactEntity.
     */
    private static boolean tryMount(ServerPlayerEntity bot, Entity vehicle) {
        if (bot == null || vehicle == null) {
            LOGGER.info("tryMount: null bot or vehicle");
            return false;
        }
        if (bot.hasVehicle() && bot.getVehicle() == vehicle) {
            return true;
        }
        
        double distSq = bot.squaredDistanceTo(vehicle);
        boolean hasPassengers = vehicle.hasPassengers();
        int passengerCount = vehicle.getPassengerList() != null ? vehicle.getPassengerList().size() : 0;
        
        // Check boat state
        boolean isSubmerged = vehicle.isSubmergedInWater();
        boolean isTouchingWater = vehicle.isTouchingWater();
        double vehicleY = vehicle.getY();
        double botY = bot.getY();
        
        boolean isBoat = categorize(vehicle) == RideCategory.BOAT;
        if (isBoat && passengerCount >= 2) {
            LOGGER.info("tryMount: boat full bot={} vehicle={} passengerCount={}",
                bot.getName().getString(),
                vehicle.getType().getName().getString(),
                passengerCount);
            return false;
        }

        // For boats with existing passengers, avoid force-mount so we don't bypass seat limits.
        boolean force = !isBoat || passengerCount == 0;
        boolean mounted = bot.startRiding(vehicle, force, true);  // sendPacket=true
        if (mounted || (bot.hasVehicle() && bot.getVehicle() == vehicle)) {
            LOGGER.info("tryMount: SUCCESS via startRiding bot={} vehicle={} distSq={}", 
                bot.getName().getString(), vehicle.getType().getName().getString(), distSq);
            return true;
        }
        
        // Log why startRiding failed with more details
        LOGGER.info("tryMount: startRiding failed bot={} vehicle={} distSq={} hasPassengers={} passengerCount={} isSubmerged={} isTouchingWater={} vehicleY={} botY={}", 
            bot.getName().getString(), 
            vehicle.getType().getName().getString(),
            String.format("%.2f", distSq),
            hasPassengers,
            passengerCount,
            isSubmerged,
            isTouchingWater,
            String.format("%.2f", vehicleY),
            String.format("%.2f", botY));
        
        // Fallback: simulate right-click interaction at the entity.
        // BoatEntity / many rideable entities mount via Entity#interact.
        try {
            vehicle.interact(bot, Hand.MAIN_HAND);
            bot.swingHand(Hand.MAIN_HAND, true);
        } catch (Throwable t) {
            LOGGER.info("tryMount: interact threw {}", t.getMessage());
        }
        
        boolean success = bot.hasVehicle() && bot.getVehicle() == vehicle;
        if (success) {
            LOGGER.info("tryMount: SUCCESS via interact bot={} vehicle={}", 
                bot.getName().getString(), vehicle.getType().getName().getString());
        } else {
            LOGGER.info("tryMount: FAILED both methods bot={} vehicle={} botHasVehicle={} botVehicle={}", 
                bot.getName().getString(), 
                vehicle.getType().getName().getString(),
                bot.hasVehicle(),
                bot.hasVehicle() ? bot.getVehicle().getType().getName().getString() : "none");
        }
        return success;
    }

    private static boolean isChestBoat(Entity entity) {
        return entity instanceof ChestBoatEntity;
    }

    private static boolean hasNonCommanderPassenger(Entity vehicle, ServerPlayerEntity commander) {
        if (vehicle == null) {
            return false;
        }
        List<Entity> passengers = vehicle.getPassengerList();
        if (passengers == null || passengers.isEmpty()) {
            return false;
        }
        for (Entity passenger : passengers) {
            if (passenger == null) {
                continue;
            }
            if (commander != null && passenger.getUuid().equals(commander.getUuid())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isBoatSeatAvailable(Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        if (vehicle instanceof AbstractBoatEntity boat && boat.isSubmergedInWater()) {
            return false;
        }
        List<Entity> passengers = vehicle.getPassengerList();
        int count = passengers != null ? passengers.size() : 0;
        if (count >= 2) {
            return false;
        }
        if (count == 0) {
            return true;
        }
        for (Entity passenger : passengers) {
            if (passenger instanceof ServerPlayerEntity) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNearbyIndependentBoat(ServerPlayerEntity bot,
                                                    Entity commanderVehicle,
                                                    double radius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        Box box = bot.getBoundingBox().expand(radius, 6.0D, radius);
        int commanderVehicleId = commanderVehicle != null ? commanderVehicle.getId() : -1;
        List<Entity> boats = world.getEntitiesByClass(
                Entity.class,
                box,
                e -> e != null
                        && e.isAlive()
                        && !e.isRemoved()
                        && e.getId() != commanderVehicleId
                        && categorize(e) == RideCategory.BOAT
                        && isBoatSeatAvailable(e)
        );
        return !boats.isEmpty();
    }

    private static boolean commanderHasNearbyLeashedMob(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        // Look for any mob currently leashed to the commander. If so, assume player wants that mob in the spare seat.
        Box box = commander.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        List<MobEntity> leashed = world.getEntitiesByClass(
                MobEntity.class,
                box,
                mob -> mob != null && mob.isAlive() && mob.isLeashed() && mob.getLeashHolder() == commander
        );
        return !leashed.isEmpty();
    }

    private static boolean tryPlaceAndMountOwnBoat(MinecraftServer server,
                                                  ServerPlayerEntity bot,
                                                  ServerPlayerEntity commander,
                                                  Entity commanderVehicle,
                                                  Set<Integer> claimed) {
        if (server == null || bot == null || commander == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle()) {
            return false;
        }
        // If the commander is in a chest boat, we always want a separate boat.
        boolean commanderIsChestBoat = commanderVehicle != null && isChestBoat(commanderVehicle);
        boolean reserveSeatForMob = commanderHasNearbyLeashedMob(commander);

        // If the commander's boat is full (e.g., another bot already took the second seat), we need an independent boat.
        boolean commanderBoatFull = false;
        if (commanderVehicle != null && categorize(commanderVehicle) == RideCategory.BOAT) {
            int passengers = commanderVehicle.getPassengerList() != null ? commanderVehicle.getPassengerList().size() : 0;
            commanderBoatFull = passengers >= 2;
        }

        // If seat isn't reserved/isn't a chest boat/isn't full, joining commander boat is handled elsewhere.
        // (But if mount attempts fail, we still allow own-boat placement via the "full" or explicit policies).
        if (!commanderIsChestBoat && !reserveSeatForMob && !commanderBoatFull) {
            return false;
        }

        // If there is already an empty nearby (non-chest) boat, use it immediately.
        // IMPORTANT: do NOT rely on commander-centered candidate selection for this case;
        // the nearest usable boat can be close to the bot but outside the commander's search box.
        Entity nearbyEmptyBoat = findNearestEmptyBoat(world, bot, commander, commanderVehicle);
        if (nearbyEmptyBoat != null) {
            if (bot.squaredDistanceTo(nearbyEmptyBoat) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, nearbyEmptyBoat, commander);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-nearby:approach");
                return true;
            }
            boolean mounted = tryMount(bot, nearbyEmptyBoat);
            if (mounted) {
                SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                SYNC_VEHICLE.put(bot.getUuid(), nearbyEmptyBoat.getUuid());
                LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                if (claimed != null) {
                    claimed.add(nearbyEmptyBoat.getId());
                }
                MountPersistenceService.recordMount(bot, nearbyEmptyBoat);
                BotEventHandler.collectNearbyDrops(bot, 4.0D);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "nearby-mounted");
                return true;
            }
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-nearby:mount-failed");
            // Fall through to placement.
        }

        if (hasPendingBoatPlacement(server, bot)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:pending");
            return true;
        }

        Hand useHand = Hand.MAIN_HAND;
        ItemStack offhand = bot.getOffHandStack();
        if (isBoatItemStack(offhand)) {
            useHand = Hand.OFF_HAND;
        } else {
            // Find a boat item in inventory.
            int boatSlot = findBoatItemSlot(bot);
            if (boatSlot == -1) {
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:no-boat");
                return false;
            }
            int hotbarSlot = ensureHotbarAccess(bot, boatSlot);
            // Combat logic tends to snap to slot 0, so place the boat there.
            int targetSlot = 0;
            if (hotbarSlot != targetSlot) {
                swapInventoryStacks(bot, hotbarSlot, targetSlot);
            }
            bot.getInventory().setSelectedSlot(targetSlot);
            bot.getInventory().markDirty();
            HotbarLockService.lock(bot, server, targetSlot, 120L, "boat-place");
            ItemStack check = bot.getMainHandStack();
            if (!isBoatItemStack(check)) {
                String key = check != null && !check.isEmpty() ? check.getItem().getTranslationKey() : "empty";
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:no-boat-in-hand:" + key + ":slot" + targetSlot);
                HotbarLockService.clear(bot);
                return false;
            }
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false,
                    "boat-place:equip:slot" + targetSlot + ":" + check.getItem().getTranslationKey());
        }
        if (hasPendingBoatPlacement(server, bot)) {
            return true;
        }

        // Find nearby water to place onto. If we can't find water, don't place.
        // Use a wider search radius: the bot is often on shore while the commander is already boating.
        BlockPos water = findNearbyBoatPlacementWater(world, bot.getBlockPos(), 10);
        if (water == null) {
            // Try moving toward the commander (who is presumably on water).
            if (commanderVehicle != null) {
                maybeApproachMount(bot, commanderVehicle, commander);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:approach-water");
                return true;
            }
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:no-water");
            return false;
        }

        // Move close enough to place.
        Vec3d placeAt = Vec3d.ofCenter(water);
        if (bot.squaredDistanceTo(placeAt) > 12.0D) {
            BotActions.sprint(bot, false);
            BotActions.applyMovementInput(bot, placeAt, 0.14D);
            setFollowOverride(bot, server, placeAt);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:approach");
            return true;
        }
        if (!ensureSurfaceForBoatPlacement(bot, world)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:surface");
            return true;
        }

        // Aim slightly offset into the next block (helps avoid placing directly under the bot).
        Vec3d aimPos = placeAt;
        Vec3d commanderPos = commander != null ? new Vec3d(commander.getX(), commander.getY(), commander.getZ()) : null;
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d dir = commanderPos != null ? commanderPos.subtract(botPos) : bot.getRotationVec(1.0F);
        if (dir.lengthSquared() > 0.0625D) { // >0.25 blocks
            Vec3d offset = dir.normalize().multiply(0.9D);
            aimPos = aimPos.add(offset.x, 0.0D, offset.z);
        }
        aimAt(bot, aimPos);
        ItemStack stack = useHand == Hand.OFF_HAND ? bot.getOffHandStack() : bot.getMainHandStack();
        if (!isBoatItemStack(stack)) {
            String key = stack != null && !stack.isEmpty() ? stack.getItem().getTranslationKey() : "empty";
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:no-boat-in-hand:" + key);
            // Fallback: if offhand has a boat, switch to it.
            if (useHand == Hand.MAIN_HAND && isBoatItemStack(bot.getOffHandStack())) {
                useHand = Hand.OFF_HAND;
                stack = bot.getOffHandStack();
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:swap-to-offhand");
            } else {
                HotbarLockService.clear(bot);
                return false;
            }
        }
        if (bot.isBlocking() || bot.isUsingItem()) {
            ItemStack active = bot.getActiveItem();
            String activeKey = active != null && !active.isEmpty() ? active.getItem().getTranslationKey() : "none";
            // clearActiveItem cancels use without triggering "release" handlers (safe for bows/tridents too).
            bot.clearActiveItem();
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:cancel-use:" + activeKey);
        }
        int beforeCount = stack.getCount();

        // BoatItem#use performs its own raycast which can be unreliable for fake players.
        // Treat "accepted" as only a hint; confirm via spawned boat or item consumption.
        ActionResult resultUse = stack.use(world, bot, useHand);
        int afterUse = stack.getCount();
        Entity boatAfterUse = (resultUse != null && resultUse.isAccepted())
                ? findNearbyPlacedBoat(world, bot, placeAt, 6.0D, commanderVehicle, null)
                : null;
        boolean useSucceeded = boatAfterUse != null || afterUse < beforeCount;
        if (useSucceeded) {
            bot.swingHand(useHand, true);
            if (boatAfterUse != null && bot.squaredDistanceTo(boatAfterUse) <= MOUNT_REACH_SQ && tryMount(bot, boatAfterUse)) {
                SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                SYNC_VEHICLE.put(bot.getUuid(), boatAfterUse.getUuid());
                LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                if (claimed != null) {
                    claimed.add(boatAfterUse.getId());
                }
                MountPersistenceService.recordMount(bot, boatAfterUse);
                BotEventHandler.collectNearbyDrops(bot, 4.0D);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "placed-mounted");
                HotbarLockService.clear(bot);
                return true;
            }
            queueBoatPlacement(server, bot, water);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:placed(use)");
            return true;
        }

        // Fallback 1: explicit interactBlock at the water position.
        net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
                aimPos, Direction.UP, water, false);
        ActionResult resultBlock = bot.interactionManager.interactBlock(bot, world, stack, useHand, hit);
        int afterBlock = stack.getCount();
        Entity boatAfterBlock = (resultBlock != null && resultBlock.isAccepted())
                ? findNearbyPlacedBoat(world, bot, placeAt, 6.0D, commanderVehicle, null)
                : null;
        boolean blockSucceeded = boatAfterBlock != null || afterBlock < beforeCount;
        if (blockSucceeded) {
            bot.swingHand(useHand, true);
            if (boatAfterBlock != null && bot.squaredDistanceTo(boatAfterBlock) <= MOUNT_REACH_SQ && tryMount(bot, boatAfterBlock)) {
                SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                SYNC_VEHICLE.put(bot.getUuid(), boatAfterBlock.getUuid());
                LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                if (claimed != null) {
                    claimed.add(boatAfterBlock.getId());
                }
                MountPersistenceService.recordMount(bot, boatAfterBlock);
                BotEventHandler.collectNearbyDrops(bot, 4.0D);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "placed-mounted");
                HotbarLockService.clear(bot);
                return true;
            }
            queueBoatPlacement(server, bot, water);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:placed(block)");
            return true;
        }

        // Fallback 2: interactItem (may still do its own raycast; last resort).
        ActionResult resultItem = bot.interactionManager.interactItem(bot, world, stack, useHand);
        int afterItem = stack.getCount();
        Entity boatAfterItem = (resultItem != null && resultItem.isAccepted())
                ? findNearbyPlacedBoat(world, bot, placeAt, 6.0D, commanderVehicle, null)
                : null;
        boolean itemSucceeded = boatAfterItem != null || afterItem < beforeCount;
        if (itemSucceeded) {
            bot.swingHand(useHand, true);
            queueBoatPlacement(server, bot, water);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:placed(item)");
            return true;
        }

        // Extra diagnostics: show which calls "accepted" without actually placing.
        boolean acceptedWithoutSpawn = (resultUse != null && resultUse.isAccepted())
                || (resultBlock != null && resultBlock.isAccepted())
                || (resultItem != null && resultItem.isAccepted());
        maybeLogRideStatus(server, bot, commander, commanderVehicle, false,
                acceptedWithoutSpawn ? "boat-place:accepted-no-spawn" : "boat-place:failed");
        if (placeAt != null && server != null) {
            BotActions.sprint(bot, false);
            BotActions.applyMovementInput(bot, placeAt, 0.12D);
            setFollowOverride(bot, server, placeAt);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "boat-place:reposition");
            return true;
        }
        HotbarLockService.clear(bot);
        return false;
    }

    private static boolean isBoatItemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.item.BoatItem) {
            return true;
        }
        String key = stack.getItem().getTranslationKey();
        return key != null && (key.contains("_boat") || key.contains("_raft"));
    }

    private static boolean ensureSurfaceForBoatPlacement(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return true;
        }
        BlockPos feet = bot.getBlockPos();
        if (!world.getFluidState(feet).isIn(FluidTags.WATER)) {
            return true;
        }
        boolean headSubmerged = world.getFluidState(feet.up()).isIn(FluidTags.WATER);
        // Always try to breach upward before placing, even in shallow water.
        bot.setSwimming(true);
        if (bot.isSneaking() && !SneakLockService.isLocked(bot.getUuid())) {
            bot.setSneaking(false);
        }
        Vec3d velocity = bot.getVelocity();
        double targetUp = headSubmerged ? 0.26D : 0.18D;
        if (velocity.y < targetUp) {
            bot.addVelocity(0.0D, targetUp - velocity.y, 0.0D);
            bot.velocityDirty = true;
        }
        return !headSubmerged;
    }

    private static Entity findNearestEmptyBoat(ServerWorld world,
                                               ServerPlayerEntity bot,
                                               ServerPlayerEntity commander,
                                               Entity commanderVehicle) {
        if (world == null || bot == null) {
            return null;
        }
        Box box = bot.getBoundingBox().expand(SEARCH_RADIUS, 8.0D, SEARCH_RADIUS);
        final int commanderVehicleId = commanderVehicle != null ? commanderVehicle.getId() : -1;
        List<Entity> boats = world.getEntitiesByClass(
            Entity.class,
            box,
            e -> e != null
                && e.isAlive()
                && !e.isRemoved()
                && categorize(e) == RideCategory.BOAT
                && e.getId() != commanderVehicleId  // Use ID comparison, not reference
                && isBoatSeatAvailable(e)
        );
        if (boats.isEmpty()) {
            return null;
        }
        List<Entity> emptyBoats = new ArrayList<>();
        List<Entity> occupiedBoats = new ArrayList<>();
        for (Entity boat : boats) {
            if (boat == null) {
                continue;
            }
            if (boat.hasPassengers()) {
                occupiedBoats.add(boat);
            } else {
                emptyBoats.add(boat);
            }
        }
        List<Entity> candidates = emptyBoats.isEmpty() ? occupiedBoats : emptyBoats;
        // Prefer non-chest boats, but allow chest boats (especially important when the only free boat nearby
        // is a chest boat and the bot otherwise has no way to catch up).
        candidates.sort(Comparator
            .comparingInt((Entity e) -> isChestBoat(e) ? 1 : 0)
            .thenComparingDouble(bot::squaredDistanceTo));
        return candidates.getFirst();
    }

    private static Entity findNearbyPlacedBoat(ServerWorld world,
                                               ServerPlayerEntity bot,
                                               Vec3d placeAt,
                                               double radius,
                                               Entity exclude1,
                                               Entity exclude2) {
        if (world == null || bot == null || placeAt == null) {
            return null;
        }
        Box box = new Box(
                placeAt.x, placeAt.y, placeAt.z,
                placeAt.x, placeAt.y, placeAt.z
        ).expand(radius, 3.0D, radius);
        List<Entity> boats = world.getEntitiesByClass(
                Entity.class,
                box,
                e -> e != null
                        && e.isAlive()
                        && !e.isRemoved()
                        && categorize(e) == RideCategory.BOAT
                        && (exclude1 == null || e.getId() != exclude1.getId())
                        && (exclude2 == null || e.getId() != exclude2.getId())
        );
        if (boats.isEmpty()) {
            return null;
        }
        return boats.stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    private static int findBoatItemSlot(ServerPlayerEntity bot) {
        if (bot == null) {
            return -1;
        }

        // Prefer non-chest boats for autonomous placement (chest boats are often player-intentful storage).
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey();
            boolean looksLikeBoat = key != null && (key.contains("_boat") || key.contains("_raft"));
            boolean looksLikeChestBoat = key != null && (key.contains("chest_boat") || key.contains("chest_raft"));
            if (looksLikeBoat && !looksLikeChestBoat) {
                return i;
            }
            if (stack.getItem() instanceof net.minecraft.item.BoatItem && !looksLikeChestBoat) {
                return i;
            }
        }

        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            // Covers boats + rafts + chest-boat items.
            if (stack.getItem() instanceof net.minecraft.item.BoatItem) {
                return i;
            }
            String key = stack.getItem().getTranslationKey();
            if (key != null && (key.contains("_boat") || key.contains("_raft"))) {
                return i;
            }
        }
        return -1;
    }

    private static int ensureHotbarAccess(ServerPlayerEntity bot, int slot) {
        if (bot == null) {
            return slot;
        }
        if (slot >= 0 && slot < 9) {
            return slot;
        }
        // Find empty hotbar slot.
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target == -1) {
            target = 0;
        }
        swapInventoryStacks(bot, slot, target);
        return target;
    }

    private static void swapInventoryStacks(ServerPlayerEntity bot, int from, int to) {
        if (bot == null || from == to || from < 0 || to < 0) {
            return;
        }
        ItemStack fromStack = bot.getInventory().getStack(from);
        ItemStack toStack = bot.getInventory().getStack(to);
        bot.getInventory().setStack(from, toStack);
        bot.getInventory().setStack(to, fromStack);
        bot.getInventory().markDirty();
    }

    private static void aimAt(ServerPlayerEntity bot, Vec3d target) {
        if (bot == null || target == null) {
            return;
        }
        Vec3d from = bot.getEyePos();
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        bot.setPitch(pitch);
    }

    private static BlockPos findNearbyBoatPlacementWater(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.getFluidState(pos).isIn(FluidTags.WATER)) {
                continue;
            }
            // Need some headroom above water.
            BlockPos above = pos.up();
            if (world.getFluidState(above).isIn(FluidTags.WATER)) {
                continue;
            }
            if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
                continue;
            }
            // Ensure there is physical space for a boat and no entity overlap (commander boat often blocks placement).
            Vec3d center = Vec3d.ofCenter(pos);
            Box boatBox = new Box(
                    center.x - 0.75D, center.y, center.z - 0.75D,
                    center.x + 0.75D, center.y + 0.6D, center.z + 0.75D
            );
            if (!world.isSpaceEmpty(null, boatBox)) {
                continue;
            }
            boolean blockedByEntity = !world.getOtherEntities(null, boatBox, e -> e != null && e.isAlive() && !e.isRemoved()).isEmpty();
            if (blockedByEntity) {
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
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server != null) {
            setFollowOverride(bot, server, target);
        }
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
        // Don't fight explicit skills/tasks, but do NOT let ambient/stale task tickets block follow-quality features.
        // If an ambient hobby ticket is lingering while the bot is actively FOLLOWing, RideSync should still run.
        Optional<TaskService.ActiveTaskInfo> active = TaskService.getActiveTaskInfo(bot.getUuid());
        if (active.isPresent()) {
            TaskService.ActiveTaskInfo info = active.get();
            if (info != null
                    && info.state() == TaskService.State.RUNNING
                    && info.origin() != TaskService.Origin.AMBIENT) {
                return false;
            }
        }
        BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
        if (mode != BotEventHandler.Mode.FOLLOW) {
            return false;
        }
        if (!isFollowingCommander(bot, commander)) {
            return false;
        }

        // Default sync radius is bounded (performance + avoids snapping to irrelevant mounts).
        // For boats, we extend the range significantly so a bot that falls behind while swimming
        // can still receive RideSync assistance to mount a nearby free boat.
        double maxDist = SEARCH_RADIUS * 2.0D; // ~48 blocks
        Entity commanderVehicle = commander.getVehicle();
        RideCategory cmdCat = commanderVehicle != null ? categorize(commanderVehicle) : null;
        if (cmdCat == RideCategory.BOAT || cmdCat == RideCategory.MINECART) {
            // Boats/minecarts can pull away very quickly while bots are walking/swimming.
            // When the commander is riding and the bot is unmounted, allow RideSync to keep running
            // even at long distances so the bot can mount/place a vehicle near itself.
            if (!bot.hasVehicle()) {
                return true;
            }
            // If already mounted, we can still keep syncing over longer distances.
            maxDist = 4096.0D;
        }
        double distSq = bot.squaredDistanceTo(commander);
        return distSq <= (maxDist * maxDist);
    }

    private static void maybeDismount(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null) {
            return;
        }
        if (commander == null) {
            if (bot.hasVehicle()) {
                Entity vehicle = bot.getVehicle();
                bot.stopRiding();
                if (vehicle != null) {
                    handleDismountCare(bot, vehicle);
                    MountPersistenceService.recordMount(bot, vehicle);
                }
            }
            SYNC_COMMANDER.remove(bot.getUuid());
            SYNC_VEHICLE.remove(bot.getUuid());
            clearRideStuck(bot);
            return;
        }
        if (!bot.hasVehicle()) {
            SYNC_COMMANDER.remove(bot.getUuid());
            SYNC_VEHICLE.remove(bot.getUuid());
            clearRideStuck(bot);
            return;
        }

        // If the commander is no longer mounted, and the bot is in a boat, we usually want to
        // dismount too so the bot can continue normal follow on land.
        // (If the commander is actively swimming, keeping the bot in a boat can be useful, so we
        // only force dismount when the commander is effectively "on land".)
        if (commander.getVehicle() == null) {
            Entity botVehicle = bot.getVehicle();
            if (botVehicle != null && categorize(botVehicle) == RideCategory.BOAT) {
                boolean commanderOnLand = commander.isOnGround() || !commander.isTouchingWater();
                if (commanderOnLand && isFollowingCommander(bot, commander)) {
                    bot.stopRiding();
                    maybeQueueBoatBreak(bot, botVehicle, commander);
                    handleDismountCare(bot, botVehicle);
                    MountPersistenceService.recordMount(bot, botVehicle);
                    SYNC_COMMANDER.remove(bot.getUuid());
                    SYNC_VEHICLE.remove(bot.getUuid());
                    clearRideStuck(bot);
                    return;
                }
            }
            if (botVehicle != null && categorize(botVehicle) == RideCategory.MINECART) {
                boolean commanderOnLand = commander.isOnGround();
                if (commanderOnLand && isFollowingCommander(bot, commander)) {
                    bot.stopRiding();
                    maybeQueueMinecartBreak(bot, botVehicle, commander);
                    handleDismountCare(bot, botVehicle);
                    MountPersistenceService.recordMount(bot, botVehicle);
                    SYNC_COMMANDER.remove(bot.getUuid());
                    SYNC_VEHICLE.remove(bot.getUuid());
                    clearRideStuck(bot);
                    return;
                }
            }
        }

        // If the commander is still mounted and we're still following them, do not auto-dismount
        // just because sync temporarily becomes ineligible (e.g., a short-lived task kicks in).
        // This is especially important for boats: dismounting drops the bot into water and it
        // will immediately attempt to swim-follow instead of staying in formation.
        Entity commanderVehicle = commander.getVehicle();
        Entity botVehicle = bot.getVehicle();
        if (commanderVehicle != null && botVehicle != null) {
            RideCategory commanderCategory = categorize(commanderVehicle);
            RideCategory botCategory = categorize(botVehicle);
            if (commanderCategory != null
                    && commanderCategory == botCategory
                    && isFollowingCommander(bot, commander)
                    && bot.squaredDistanceTo(commander) <= (SEARCH_RADIUS_SQ * 4.0D)) {
                return;
            }
        }

        Entity vehicle = bot.getVehicle();
        if (!isFollowingCommander(bot, commander)) {
            bot.stopRiding();
            if (vehicle != null) {
                maybeQueueBoatBreak(bot, vehicle, commander);
                maybeQueueMinecartBreak(bot, vehicle, commander);
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
            maybeQueueBoatBreak(bot, vehicle, commander);
            maybeQueueMinecartBreak(bot, vehicle, commander);
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

    private static void updateCommanderBoatTracking(MinecraftServer server,
                                                    ServerPlayerEntity commander,
                                                    Entity commanderVehicle) {
        if (server == null || commander == null) {
            return;
        }
        UUID commanderId = commander.getUuid();
        if (commanderVehicle != null && categorize(commanderVehicle) == RideCategory.BOAT) {
            LAST_COMMANDER_BOAT.put(commanderId, commanderVehicle.getUuid());
            LAST_COMMANDER_BOAT_LOST_TICK.remove(commanderId);
            return;
        }
        UUID lastBoatId = LAST_COMMANDER_BOAT.get(commanderId);
        if (lastBoatId == null) {
            return;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Entity lastBoat = world.getEntity(lastBoatId);
        if (lastBoat == null || lastBoat.isRemoved()) {
            if (!LAST_COMMANDER_BOAT_LOST_TICK.containsKey(commanderId)) {
                LAST_COMMANDER_BOAT_LOST_TICK.put(commanderId, (long) server.getTicks());
            }
        }
    }

    private static void maybeQueueBoatBreak(ServerPlayerEntity bot,
                                            Entity vehicle,
                                            ServerPlayerEntity commander) {
        if (bot == null || vehicle == null || commander == null) {
            return;
        }
        if (categorize(vehicle) != RideCategory.BOAT) {
            return;
        }
        LAST_BOT_BOAT.put(bot.getUuid(), vehicle.getUuid());
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID commanderId = commander.getUuid();
        Long lostTick = LAST_COMMANDER_BOAT_LOST_TICK.get(commanderId);
        if (lostTick == null) {
            return;
        }
        long now = world.getServer() != null ? world.getServer().getTicks() : 0L;
        if (now - lostTick > COMMANDER_BOAT_DESTROY_WINDOW_TICKS) {
            return;
        }
        if (vehicle.hasPassengers()) {
            return;
        }
        PENDING_BOAT_BREAK.put(bot.getUuid(), vehicle.getUuid());
        PENDING_BOAT_BREAK_START.put(bot.getUuid(), now);
    }

    private static void maybeQueueBoatBreaksFromCommanderLoss(MinecraftServer server,
                                                              ServerPlayerEntity commander,
                                                              List<ServerPlayerEntity> bots) {
        if (server == null || commander == null || bots == null || bots.isEmpty()) {
            return;
        }
        Long lostTick = LAST_COMMANDER_BOAT_LOST_TICK.get(commander.getUuid());
        if (lostTick == null) {
            return;
        }
        long now = server.getTicks();
        if (now - lostTick > COMMANDER_BOAT_DESTROY_WINDOW_TICKS) {
            return;
        }
        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.hasVehicle()) {
                continue;
            }
            if (PENDING_BOAT_BREAK.containsKey(bot.getUuid())) {
                continue;
            }
            UUID boatId = LAST_BOT_BOAT.get(bot.getUuid());
            if (boatId == null) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Entity boat = world.getEntity(boatId);
            if (boat == null || boat.isRemoved() || boat.hasPassengers()) {
                continue;
            }
            if (bot.squaredDistanceTo(boat) > (SEARCH_RADIUS_SQ * 4.0D)) {
                continue;
            }
            PENDING_BOAT_BREAK.put(bot.getUuid(), boat.getUuid());
            PENDING_BOAT_BREAK_START.put(bot.getUuid(), now);
        }
    }

    private static void recordBotBoat(ServerPlayerEntity bot) {
        if (bot == null || !bot.hasVehicle()) {
            return;
        }
        Entity vehicle = bot.getVehicle();
        if (vehicle != null && categorize(vehicle) == RideCategory.BOAT) {
            LAST_BOT_BOAT.put(bot.getUuid(), vehicle.getUuid());
        }
    }

    private static void recordBotMinecart(ServerPlayerEntity bot) {
        if (bot == null || !bot.hasVehicle()) {
            return;
        }
        Entity vehicle = bot.getVehicle();
        if (vehicle != null && categorize(vehicle) == RideCategory.MINECART) {
            LAST_BOT_MINECART.put(bot.getUuid(), vehicle.getUuid());
        }
    }

    private static void updateCommanderMinecartTracking(MinecraftServer server,
                                                         ServerPlayerEntity commander,
                                                         Entity commanderVehicle) {
        if (server == null || commander == null) {
            return;
        }
        UUID commanderId = commander.getUuid();
        if (commanderVehicle != null && categorize(commanderVehicle) == RideCategory.MINECART) {
            LAST_COMMANDER_MINECART.put(commanderId, commanderVehicle.getUuid());
            LAST_COMMANDER_MINECART_LOST_TICK.remove(commanderId);
            return;
        }
        UUID lastCartId = LAST_COMMANDER_MINECART.get(commanderId);
        if (lastCartId == null) {
            return;
        }
        if (!(commander.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Entity lastCart = world.getEntity(lastCartId);
        if (lastCart == null || lastCart.isRemoved()) {
            if (!LAST_COMMANDER_MINECART_LOST_TICK.containsKey(commanderId)) {
                LAST_COMMANDER_MINECART_LOST_TICK.put(commanderId, (long) server.getTicks());
            }
        }
    }

    private static void maybeQueueMinecartBreak(ServerPlayerEntity bot,
                                                 Entity vehicle,
                                                 ServerPlayerEntity commander) {
        if (bot == null || vehicle == null || commander == null) {
            return;
        }
        if (categorize(vehicle) != RideCategory.MINECART) {
            return;
        }
        LAST_BOT_MINECART.put(bot.getUuid(), vehicle.getUuid());
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID commanderId = commander.getUuid();
        Long lostTick = LAST_COMMANDER_MINECART_LOST_TICK.get(commanderId);
        if (lostTick == null) {
            // Commander didn't lose their minecart — queue break anyway since bot is dismounting.
            PENDING_MINECART_BREAK.put(bot.getUuid(), vehicle.getUuid());
            MinecraftServer server = world.getServer();
            if (server != null) {
                PENDING_MINECART_BREAK_START.put(bot.getUuid(), (long) server.getTicks());
            }
            return;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        if (now - lostTick > COMMANDER_BOAT_DESTROY_WINDOW_TICKS) {
            return;
        }
        PENDING_MINECART_BREAK.put(bot.getUuid(), vehicle.getUuid());
        PENDING_MINECART_BREAK_START.put(bot.getUuid(), now);
    }

    private static void maybeQueueMinecartBreaksFromCommanderLoss(MinecraftServer server,
                                                                   ServerPlayerEntity commander,
                                                                   List<ServerPlayerEntity> bots) {
        if (server == null || commander == null || bots == null || bots.isEmpty()) {
            return;
        }
        Long lostTick = LAST_COMMANDER_MINECART_LOST_TICK.get(commander.getUuid());
        if (lostTick == null) {
            return;
        }
        long now = server.getTicks();
        if (now - lostTick > COMMANDER_BOAT_DESTROY_WINDOW_TICKS) {
            return;
        }
        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.hasVehicle()) {
                continue;
            }
            if (PENDING_MINECART_BREAK.containsKey(bot.getUuid())) {
                continue;
            }
            UUID cartId = LAST_BOT_MINECART.get(bot.getUuid());
            if (cartId == null) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Entity cart = world.getEntity(cartId);
            if (cart == null || cart.isRemoved() || cart.hasPassengers()) {
                continue;
            }
            if (bot.squaredDistanceTo(cart) > (SEARCH_RADIUS_SQ * 4.0D)) {
                continue;
            }
            PENDING_MINECART_BREAK.put(bot.getUuid(), cart.getUuid());
            PENDING_MINECART_BREAK_START.put(bot.getUuid(), now);
        }
    }

    private static void maybeProcessMinecartBreaks(MinecraftServer server, List<ServerPlayerEntity> bots) {
        if (server == null || bots == null || bots.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        for (ServerPlayerEntity bot : bots) {
            UUID cartId = PENDING_MINECART_BREAK.get(bot.getUuid());
            if (cartId == null) {
                continue;
            }
            Long startTick = PENDING_MINECART_BREAK_START.get(bot.getUuid());
            if (startTick == null || now - startTick > BOAT_BREAK_TIMEOUT_TICKS) {
                PENDING_MINECART_BREAK.remove(bot.getUuid());
                PENDING_MINECART_BREAK_START.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Entity cart = world.getEntity(cartId);
            if (cart == null || cart.isRemoved()) {
                PENDING_MINECART_BREAK.remove(bot.getUuid());
                PENDING_MINECART_BREAK_START.remove(bot.getUuid());
                continue;
            }
            if (bot.squaredDistanceTo(cart) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, cart, bot);
                continue;
            }
            long lastSwing = LAST_MINECART_BREAK_TICK.getOrDefault(bot.getUuid(), 0L);
            if (now - lastSwing < BOAT_BREAK_COOLDOWN_TICKS) {
                continue;
            }
            LAST_MINECART_BREAK_TICK.put(bot.getUuid(), now);
            bot.attack(cart);
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    // ---- Minecart placement from inventory ----

    private static boolean hasPendingMinecartPlacement(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return false;
        }
        Long startTick = PENDING_MINECART_PLACE_TICK.get(bot.getUuid());
        if (startTick == null) {
            return false;
        }
        long now = server.getTicks();
        if (now - startTick > BOAT_PLACE_TIMEOUT_TICKS) {
            PENDING_MINECART_PLACE_TICK.remove(bot.getUuid());
            PENDING_MINECART_PLACE_POS.remove(bot.getUuid());
            return false;
        }
        return true;
    }

    private static void queueMinecartPlacement(MinecraftServer server, ServerPlayerEntity bot, BlockPos railPos) {
        if (server == null || bot == null || railPos == null) {
            return;
        }
        PENDING_MINECART_PLACE_POS.put(bot.getUuid(), railPos.toImmutable());
        PENDING_MINECART_PLACE_TICK.put(bot.getUuid(), (long) server.getTicks());
    }

    private static void maybeProcessMinecartPlacements(MinecraftServer server, List<ServerPlayerEntity> bots) {
        if (server == null || bots == null || bots.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        for (ServerPlayerEntity bot : bots) {
            BlockPos placePos = PENDING_MINECART_PLACE_POS.get(bot.getUuid());
            Long startTick = PENDING_MINECART_PLACE_TICK.get(bot.getUuid());
            if (placePos == null || startTick == null) {
                continue;
            }
            if (now - startTick > BOAT_PLACE_TIMEOUT_TICKS || bot.hasVehicle()) {
                PENDING_MINECART_PLACE_TICK.remove(bot.getUuid());
                PENDING_MINECART_PLACE_POS.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Box box = new Box(placePos).expand(3.5D, 3.0D, 3.5D);
            List<Entity> carts = world.getEntitiesByClass(
                    Entity.class,
                    box,
                    e -> e != null && e.isAlive() && !e.isRemoved()
                            && e.getType() == EntityType.MINECART && !e.hasPassengers()
            );
            if (carts.isEmpty()) {
                if (now - startTick > 20L) {
                    PENDING_MINECART_PLACE_TICK.remove(bot.getUuid());
                    PENDING_MINECART_PLACE_POS.remove(bot.getUuid());
                }
                continue;
            }
            Entity nearest = carts.stream()
                    .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (nearest == null) {
                continue;
            }
            if (bot.squaredDistanceTo(nearest) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, nearest, bot);
                continue;
            }
            if (tryMount(bot, nearest)) {
                PENDING_MINECART_PLACE_TICK.remove(bot.getUuid());
                PENDING_MINECART_PLACE_POS.remove(bot.getUuid());
            }
        }
    }

    /**
     * Find a minecart item in the bot's inventory.
     * Prefers Items.MINECART, then falls back to any MinecartItem.
     */
    private static int findMinecartItemSlot(ServerPlayerEntity bot) {
        if (bot == null) {
            return -1;
        }
        // Prefer plain minecart first.
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.MINECART)) {
                return i;
            }
        }
        // Fallback: any MinecartItem (covers modded minecarts).
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof MinecartItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Scan a radius around origin for an AbstractRailBlock with headroom and no entity overlap.
     */
    private static BlockPos findNearbyRailForPlacement(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!(world.getBlockState(pos).getBlock() instanceof AbstractRailBlock)) {
                continue;
            }
            // Need headroom above the rail.
            BlockPos above = pos.up();
            if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
                continue;
            }
            // No entity overlap on the rail.
            Vec3d center = Vec3d.ofCenter(pos);
            Box cartBox = new Box(
                    center.x - 0.5D, center.y, center.z - 0.5D,
                    center.x + 0.5D, center.y + 0.7D, center.z + 0.5D
            );
            boolean blockedByEntity = !world.getOtherEntities(null, cartBox,
                    e -> e != null && e.isAlive() && !e.isRemoved()).isEmpty();
            if (blockedByEntity) {
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

    /**
     * Attempt to place a minecart from inventory onto a nearby rail and mount it.
     * Mirrors tryPlaceAndMountOwnBoat but adapted for rails instead of water.
     */
    private static boolean tryPlaceAndMountOwnMinecart(MinecraftServer server,
                                                        ServerPlayerEntity bot,
                                                        ServerPlayerEntity commander,
                                                        Entity commanderVehicle,
                                                        Set<Integer> claimed) {
        if (server == null || bot == null || commander == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (bot.hasVehicle()) {
            return false;
        }

        if (hasPendingMinecartPlacement(server, bot)) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:pending");
            return true;
        }

        // Find a minecart item in inventory.
        int cartSlot = findMinecartItemSlot(bot);
        if (cartSlot == -1) {
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:no-item");
            return false;
        }

        // Find a nearby rail to place onto.
        BlockPos rail = findNearbyRailForPlacement(world, bot.getBlockPos(), 10);
        if (rail == null) {
            // Move toward the commander (who is presumably on rails).
            if (commanderVehicle != null) {
                maybeApproachMount(bot, commanderVehicle, commander);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:approach-rail");
                return true;
            }
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:no-rail");
            return false;
        }

        // Equip the minecart item to hotbar slot 0.
        int hotbarSlot = ensureHotbarAccess(bot, cartSlot);
        int targetSlot = 0;
        if (hotbarSlot != targetSlot) {
            swapInventoryStacks(bot, hotbarSlot, targetSlot);
        }
        bot.getInventory().setSelectedSlot(targetSlot);
        bot.getInventory().markDirty();
        HotbarLockService.lock(bot, server, targetSlot, 120L, "minecart-place");

        ItemStack check = bot.getMainHandStack();
        if (check == null || check.isEmpty() || !(check.getItem() instanceof MinecartItem) && !check.isOf(Items.MINECART)) {
            String key = check != null && !check.isEmpty() ? check.getItem().getTranslationKey() : "empty";
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:no-item-in-hand:" + key);
            HotbarLockService.clear(bot);
            return false;
        }

        // Move close enough to place.
        Vec3d placeAt = Vec3d.ofCenter(rail);
        if (bot.squaredDistanceTo(placeAt) > 12.0D) {
            BotActions.sprint(bot, false);
            BotActions.applyMovementInput(bot, placeAt, 0.14D);
            setFollowOverride(bot, server, placeAt);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:approach");
            return true;
        }

        // Aim at rail and place.
        aimAt(bot, placeAt);

        if (bot.isBlocking() || bot.isUsingItem()) {
            bot.clearActiveItem();
        }

        Hand useHand = Hand.MAIN_HAND;
        ItemStack stack = bot.getMainHandStack();
        int beforeCount = stack.getCount();

        // MinecartItem places via interactBlock on a rail.
        net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
                placeAt, Direction.UP, rail, false);
        ActionResult result = bot.interactionManager.interactBlock(bot, world, stack, useHand, hit);
        int afterCount = stack.getCount();
        boolean placed = (result != null && result.isAccepted()) || afterCount < beforeCount;

        if (!placed) {
            // Fallback: use item directly.
            ActionResult resultUse = stack.use(world, bot, useHand);
            afterCount = stack.getCount();
            placed = (resultUse != null && resultUse.isAccepted()) || afterCount < beforeCount;
        }

        if (placed) {
            bot.swingHand(useHand, true);
            // Try to find the newly spawned minecart near the rail.
            Box searchBox = new Box(rail).expand(3.0D, 2.0D, 3.0D);
            List<Entity> carts = world.getEntitiesByClass(
                    Entity.class,
                    searchBox,
                    e -> e != null && e.isAlive() && !e.isRemoved()
                            && e.getType() == EntityType.MINECART && !e.hasPassengers()
            );
            Entity nearest = carts.stream()
                    .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (nearest != null && bot.squaredDistanceTo(nearest) <= MOUNT_REACH_SQ && tryMount(bot, nearest)) {
                SYNC_COMMANDER.put(bot.getUuid(), commander.getUuid());
                SYNC_VEHICLE.put(bot.getUuid(), nearest.getUuid());
                LAST_SYNC_TICK.put(bot.getUuid(), (long) server.getTicks());
                if (claimed != null) {
                    claimed.add(nearest.getId());
                }
                MountPersistenceService.recordMount(bot, nearest);
                BotEventHandler.collectNearbyDrops(bot, 4.0D);
                maybeLogRideStatus(server, bot, commander, commanderVehicle, true, "minecart-placed-mounted");
                HotbarLockService.clear(bot);
                return true;
            }
            queueMinecartPlacement(server, bot, rail);
            maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:placed");
            return true;
        }

        maybeLogRideStatus(server, bot, commander, commanderVehicle, false, "minecart-place:failed");
        HotbarLockService.clear(bot);
        return false;
    }

    private static boolean hasPendingBoatPlacement(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return false;
        }
        Long startTick = PENDING_BOAT_PLACE_TICK.get(bot.getUuid());
        if (startTick == null) {
            return false;
        }
        long now = server.getTicks();
        if (now - startTick > BOAT_PLACE_TIMEOUT_TICKS) {
            PENDING_BOAT_PLACE_TICK.remove(bot.getUuid());
            PENDING_BOAT_PLACE_POS.remove(bot.getUuid());
            return false;
        }
        return true;
    }

    private static void queueBoatPlacement(MinecraftServer server, ServerPlayerEntity bot, BlockPos water) {
        if (server == null || bot == null || water == null) {
            return;
        }
        PENDING_BOAT_PLACE_POS.put(bot.getUuid(), water.toImmutable());
        PENDING_BOAT_PLACE_TICK.put(bot.getUuid(), (long) server.getTicks());
    }

    private static void maybeProcessBoatPlacements(MinecraftServer server, List<ServerPlayerEntity> bots) {
        if (server == null || bots == null || bots.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        for (ServerPlayerEntity bot : bots) {
            BlockPos placePos = PENDING_BOAT_PLACE_POS.get(bot.getUuid());
            Long startTick = PENDING_BOAT_PLACE_TICK.get(bot.getUuid());
            if (placePos == null || startTick == null) {
                continue;
            }
            if (now - startTick > BOAT_PLACE_TIMEOUT_TICKS || bot.hasVehicle()) {
                PENDING_BOAT_PLACE_TICK.remove(bot.getUuid());
                PENDING_BOAT_PLACE_POS.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Box box = new Box(placePos).expand(3.5D, 3.0D, 3.5D);
            List<Entity> boats = world.getEntitiesByClass(
                    Entity.class,
                    box,
                    e -> e != null && e.isAlive() && !e.isRemoved() && categorize(e) == RideCategory.BOAT
            );
            if (boats.isEmpty()) {
                if (now - startTick > 20L) {
                    PENDING_BOAT_PLACE_TICK.remove(bot.getUuid());
                    PENDING_BOAT_PLACE_POS.remove(bot.getUuid());
                }
                continue;
            }
            Entity nearest = boats.stream()
                    .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                    .orElse(null);
            if (nearest == null) {
                continue;
            }
            if (bot.squaredDistanceTo(nearest) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, nearest, bot);
                continue;
            }
            if (tryMount(bot, nearest)) {
                PENDING_BOAT_PLACE_TICK.remove(bot.getUuid());
                PENDING_BOAT_PLACE_POS.remove(bot.getUuid());
            }
        }
    }

    private static void maybeProcessBoatBreaks(MinecraftServer server, List<ServerPlayerEntity> bots) {
        if (server == null || bots == null || bots.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        for (ServerPlayerEntity bot : bots) {
            UUID boatId = PENDING_BOAT_BREAK.get(bot.getUuid());
            if (boatId == null) {
                continue;
            }
            Long startTick = PENDING_BOAT_BREAK_START.get(bot.getUuid());
            if (startTick == null || now - startTick > BOAT_BREAK_TIMEOUT_TICKS) {
                PENDING_BOAT_BREAK.remove(bot.getUuid());
                PENDING_BOAT_BREAK_START.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            Entity boat = world.getEntity(boatId);
            if (boat == null || boat.isRemoved()) {
                PENDING_BOAT_BREAK.remove(bot.getUuid());
                PENDING_BOAT_BREAK_START.remove(bot.getUuid());
                continue;
            }
            if (bot.squaredDistanceTo(boat) > MOUNT_REACH_SQ) {
                maybeApproachMount(bot, boat, bot);
                continue;
            }
            long lastSwing = LAST_BOAT_BREAK_TICK.getOrDefault(bot.getUuid(), 0L);
            if (now - lastSwing < BOAT_BREAK_COOLDOWN_TICKS) {
                continue;
            }
            LAST_BOAT_BREAK_TICK.put(bot.getUuid(), now);
            bot.attack(boat);
            bot.swingHand(Hand.MAIN_HAND, true);
        }
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

    private static boolean cooldownReady(MinecraftServer server, ServerPlayerEntity bot, RideCategory commanderCategory) {
        long now = server.getTicks();
        long last = LAST_SYNC_TICK.getOrDefault(bot.getUuid(), 0L);
        long cooldown = (commanderCategory == RideCategory.BOAT || commanderCategory == RideCategory.MINECART)
                ? BOAT_RESYNC_COOLDOWN_TICKS : RESYNC_COOLDOWN_TICKS;
        return now - last >= cooldown;
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
        if (bot == null || commander == null) {
            return false;
        }
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
        var leadDropOpt = world.getEntitiesByClass(
                Entity.class,
                searchBox,
                drop -> drop != null
                        && drop.isAlive()
                        && !drop.isRemoved()
                        && drop.getType() == EntityType.ITEM
                        && drop instanceof net.minecraft.entity.ItemEntity item
                        && item.getStack().isOf(Items.LEAD)
        ).stream().min(Comparator.comparingDouble(bot::squaredDistanceTo));
        if (leadDropOpt.isEmpty()) {
            return false;
        }
        Entity leadDrop = leadDropOpt.get();
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

        // If we ended up in the *same* vehicle as the commander (or we're not the controlling passenger),
        // do NOT attempt to steer or force-move it. Force-moving a shared boat causes the commander to
        // appear to "teleport" because both passengers are carried by the same entity.
        if (botVehicle == commanderVehicle) {
            setMountInput(bot, false, false, false);
            return;
        }
        try {
            Entity controlling = botVehicle.getControllingPassenger();
            if (controlling != null && controlling != bot) {
                setMountInput(bot, false, false, false);
                return;
            }
        } catch (Throwable ignored) {
        }
        // On-rail minecarts are PASSIVE — rail physics drives them. Just align yaw and let rails do the work.
        if (botVehicle instanceof AbstractMinecartEntity && isOnRail(botVehicle)) {
            alignToCommander(bot, commander);
            setMountInput(bot, false, false, false);
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
        Vec3d commanderVelocity = commanderVehicle.getVelocity();
        if (isFollowingCommander(bot, commander) && distSq < desiredSq * 0.95D) {
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "backoff", desiredSpace, distSq);
            driveAway(bot, new Vec3d(commanderVehicle.getX(), commanderVehicle.getY(), commanderVehicle.getZ()), commander.isSprinting());
            forceMoveMount(botVehicle,
                    new Vec3d(botVehicle.getX() + dx, botVehicle.getY(), botVehicle.getZ() + dz),
                    commander.isSprinting(),
                    commanderVelocity.horizontalLength(),
                    Math.sqrt(distSq));
            return;
        }
        if (isFollowingCommander(bot, commander) && distSq > desiredSq) {
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "steer", desiredSpace, distSq);
            driveToward(bot, targetPos, commander.isSprinting());
            forceMoveMount(botVehicle,
                    targetPos,
                    commander.isSprinting(),
                    commanderVelocity.horizontalLength(),
                    Math.sqrt(distSq));
            return;
        }
        double horizontal = commanderVelocity.horizontalLength();
        if (horizontal > 0.05D) {
            alignToCommander(bot, commander);
            maybeLogRideMovement(bot.getCommandSource().getServer(), bot, commander, commanderVehicle, "match-vel", desiredSpace, distSq);
            driveToward(bot, targetPos, commander.isSprinting());
            forceMoveMount(botVehicle,
                    targetPos,
                    commander.isSprinting(),
                    commanderVelocity.horizontalLength(),
                    Math.sqrt(distSq));
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

    private static void forceMoveMount(Entity vehicle,
                                       Vec3d targetPos,
                                       boolean sprint,
                                       double commanderSpeed,
                                       double distance) {
        if (vehicle == null || targetPos == null) {
            return;
        }

        // Boats need server-side input nudges; avoid direct position moves to prevent tether snaps.
        if (vehicle instanceof AbstractBoatEntity) {
            forceMoveBoat(vehicle, targetPos, sprint, commanderSpeed, distance);
            return;
        }
        // On-rail minecarts are driven by rail physics — do NOT force-move or they'll fight the track.
        if (vehicle instanceof AbstractMinecartEntity && isOnRail(vehicle)) {
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
     * IMPORTANT: Skip for boats - they handle their own buoyancy physics!
     */
    private static void applyGravityIfNeeded(Entity vehicle) {
        if (vehicle == null) return;
        
        // Boats handle their own buoyancy - do NOT apply artificial gravity or they will sink!
        if (vehicle instanceof AbstractBoatEntity) {
            return;
        }
        // Minecarts handle their own physics on rails and off.
        if (vehicle instanceof AbstractMinecartEntity) {
            return;
        }
        
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
     * Nudge boat velocity toward the target and keep paddles active for visuals.
     * This avoids direct position moves that look tethered.
     */
    private static void forceMoveBoat(Entity boatEntity,
                                      Vec3d targetPos,
                                      boolean sprint,
                                      double commanderSpeed,
                                      double distance) {
        if (boatEntity == null || targetPos == null) {
            return;
        }
        Vec3d pos = new Vec3d(boatEntity.getX(), boatEntity.getY(), boatEntity.getZ());
        Vec3d delta = new Vec3d(targetPos.x - pos.x, 0.0D, targetPos.z - pos.z);
        double lenSq = delta.lengthSquared();
        if (lenSq < 1.0E-4) {
            if (boatEntity instanceof AbstractBoatEntity boat) {
                boat.setPaddlesMoving(false, false);
                boat.setInputs(false, false, false, false);
            }
            return;
        }

        Vec3d direction = delta.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        boatEntity.setYaw(yaw);
        if (boatEntity instanceof AbstractBoatEntity boat) {
            boat.setPaddlesMoving(true, true);
            boat.setInputs(false, false, true, false);
        }

        Vec3d current = boatEntity.getVelocity();
        Vec3d horiz = new Vec3d(current.x, 0.0D, current.z);
        double horizSpeed = horiz.length();
        double baseSpeed = sprint ? 0.45D : 0.35D;
        double maxSpeed = sprint ? 0.95D : 0.80D;
        double targetSpeed = Math.max(baseSpeed, commanderSpeed);
        if (distance > 6.0D) {
            targetSpeed = Math.max(targetSpeed, commanderSpeed + 0.12D);
        }
        targetSpeed = Math.min(targetSpeed, maxSpeed);
        double dot = horizSpeed > 1.0E-4 ? horiz.normalize().dotProduct(direction) : 0.0D;
        if (horizSpeed < targetSpeed * 0.95D || dot < 0.7D) {
            Vec3d next = new Vec3d(direction.x * targetSpeed, current.y, direction.z * targetSpeed);
            boatEntity.setVelocity(next);
            boatEntity.velocityDirty = true;
        }

        if (horizSpeed < 0.02D && distance > 4.0D) {
            double stepSpeed = distance > 10.0D ? 0.24D : 0.18D;
            Vec3d step = new Vec3d(direction.x * stepSpeed, 0.0D, direction.z * stepSpeed);
            boatEntity.move(MovementType.SELF, step);
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
        // On-rail minecarts are driven by rail physics — don't kill their momentum.
        if (vehicle instanceof AbstractMinecartEntity && isOnRail(vehicle)) {
            return;
        }
        if (vehicle instanceof AbstractBoatEntity boat) {
            boat.setPaddlesMoving(false, false);
            boat.setInputs(false, false, false, false);
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
        boolean important = phase != null && (phase.contains("mounted")
            || phase.startsWith("boat-")
            || phase.startsWith("commander-boat:")
            || phase.startsWith("nearby-boat:")
            || phase.startsWith("approach-mount"));
        if (now - last < DEBUG_INTERVAL_TICKS && !important) {
            return;
        }
        LAST_DEBUG_TICK.put(bot.getUuid(), now);
        Entity botVehicle = bot.getVehicle();
        Vec3d botVel = botVehicle != null ? botVehicle.getVelocity() : bot.getVelocity();
        Vec3d commanderVel = commanderVehicle != null ? commanderVehicle.getVelocity() : commander.getVelocity();
        double dist = commanderVehicle != null && botVehicle != null
            ? Math.sqrt(botVehicle.squaredDistanceTo(commanderVehicle))
            : Math.sqrt(bot.squaredDistanceTo(commander));
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
        // Be robust across vanilla boats, rafts, and chest boats.
        // ChestBoatEntity may not extend BoatEntity in all mappings, so check it explicitly first.
        if (entity instanceof ChestBoatEntity) {
            return RideCategory.BOAT;
        }
        if (entity instanceof net.minecraft.entity.vehicle.BoatEntity) {
            return RideCategory.BOAT;
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

    /**
     * Returns true if the given entity (expected to be a minecart) is sitting on a rail block.
     * Checks the block at the entity's position and one below (minecarts sit slightly above).
     */
    private static boolean isOnRail(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        if (!(entity.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos pos = entity.getBlockPos();
        if (world.getBlockState(pos).getBlock() instanceof AbstractRailBlock) {
            return true;
        }
        BlockPos below = pos.down();
        return world.getBlockState(below).getBlock() instanceof AbstractRailBlock;
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
