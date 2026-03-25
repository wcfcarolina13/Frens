package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityPosition;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NavigationArtifactService {

    private static final Logger LOGGER = LoggerFactory.getLogger("nav-artifact");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final String TRAVEL_FILE = "pending_travels.json";

    private NavigationArtifactService() {}

    /** Gson-serializable DTO mirroring {@link PendingTravel} with primitive/String fields. */
    public static class SavedTravel {
        public String botUuid;
        public String botAlias;
        public int destX, destY, destZ;
        public String dimension;
        public long departureTick;
        public long arrivalTick;
        public String ownerUuid;
        public String mountEntityTypeId;
    }

    private static Path travelFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(TRAVEL_FILE);
    }

    // ── Navigation tiers ──────────────────────────────────────────────────

    public enum NavTier { NONE, BASIC, ENHANCED }

    /**
     * Determine navigation tier from bot + player inventories.
     * ENHANCED: either holds Eye of Ender.
     * BASIC: bot holds Compass, Recovery Compass, Map, or Filled Map.
     */
    public static NavTier getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player) {
        if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
            return NavTier.ENHANCED;
        }
        if (hasItemInInventory(bot, Items.COMPASS)
                || hasItemInInventory(bot, Items.RECOVERY_COMPASS)
                || hasItemInInventory(bot, Items.FILLED_MAP)
                || hasItemInInventory(bot, Items.MAP)) {
            return NavTier.BASIC;
        }
        return NavTier.NONE;
    }

    /** Check if both player and bot each hold at least one ender pearl. */
    public static boolean bothHaveEnderPearl(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.ENDER_PEARL);
    }

    /** Check if both hold an ender pearl AND a chorus fruit. */
    public static boolean bothHaveChorusRecallItems(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(player, Items.CHORUS_FRUIT)
                && hasItemInInventory(bot, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Check bot has Eye of Ender (kept) + Chorus Fruit (to consume) for Soul of Ender spell. */
    public static boolean botHasSoulOfEnderItems(ServerPlayerEntity bot) {
        return hasItemInInventory(bot, Items.ENDER_EYE)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Consume one item of a given type from the player's inventory. Returns true if consumed. */
    public static boolean consumeItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) {
                stack.decrement(1);
                if (stack.isEmpty()) inv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /**
     * Estimate how many ticks a fast travel sequence should take based on distance.
     * <ul>
     *   <li>1 real second per chunk (distance / 16)</li>
     *   <li>Cross-dimension adds 30 seconds</li>
     *   <li>Min 5 seconds, max 5 minutes</li>
     * </ul>
     *
     * @param distance      Euclidean distance in blocks between origin and destination.
     * @param crossDimension true if the travel crosses dimensions (e.g. Overworld to Nether).
     * @return delay in game ticks (20 ticks = 1 second).
     */
    public static int calculateDelayTicks(double distance, boolean crossDimension) {
        return calculateDelayTicks(distance, crossDimension, 1.0);
    }

    public static int calculateDelayTicks(double distance, boolean crossDimension, double multiplier) {
        int chunks = Math.max(1, (int) Math.ceil(distance / 16.0));
        int seconds = chunks;
        if (crossDimension) seconds += 30;
        seconds = Math.max(5, Math.min(300, (int) (seconds * multiplier)));
        return seconds * 20;
    }

    /** Determine delay multiplier based on bot/player artifact tier. 2x for Tier 1, 1x for Tier 2+. */
    public static double artifactDelayMultiplier(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        // Tier 2+: Eye of Ender, Wizard's Tome, Enchanting Table, or both hold Ender Pearls.
        if (hasArtifact(bot, net.minecraft.item.Items.ENDER_EYE)
                || hasArtifact(owner, net.minecraft.item.Items.ENDER_EYE)
                || CompanionCommunicationPolicy.hasWizardTome(bot)
                || CompanionCommunicationPolicy.hasWizardTome(owner)
                || isNearBlock(bot, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || isNearBlock(owner, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)
                || (hasArtifact(bot, net.minecraft.item.Items.ENDER_PEARL)
                    && hasArtifact(owner, net.minecraft.item.Items.ENDER_PEARL))) {
            return 1.0;
        }
        // Tier 1: Map or Map+Compass on bot → 2x slower.
        if (hasArtifact(bot, net.minecraft.item.Items.FILLED_MAP)
                || hasArtifact(bot, net.minecraft.item.Items.COMPASS)) {
            return 2.0;
        }
        // No artifacts: still allow but at 3x delay.
        return 3.0;
    }

    private static boolean hasArtifact(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(item)) return true;
        }
        return false;
    }

    private static boolean isNearBlock(ServerPlayerEntity player, net.minecraft.block.Block block, int radius) {
        if (player == null) return false;
        BlockPos center = player.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (player.getEntityWorld().getBlockState(center.add(dx, dy, dz)).isOf(block)) return true;
                }
            }
        }
        return false;
    }

    // ── Bot-to-bot artifact teleport (summon home) ─────────────

    /** Distance threshold for bot-to-bot artifact teleport (same as sunset fast travel). */
    private static final double BOT_SUMMON_RANGE_SQ = 96.0D * 96.0D;
    /** Receiver must be within this range of the destination to qualify as "at base". */
    private static final double RECEIVER_AT_DEST_RANGE_SQ = 32.0D * 32.0D;

    /**
     * Attempt to teleport a traveler bot to a destination by having a "receiver" bot
     * already near that destination summon it via tier-2 artifact access.
     *
     * <p>Requirements:
     * <ul>
     *   <li>Traveler is &gt;96 blocks from destination</li>
     *   <li>A same-owner bot ("receiver") exists within 32 blocks of destination</li>
     *   <li>Receiver has tier-2 artifact access (Eye of Ender, Wizard's Tome,
     *       Enchanting Table nearby, or both bots hold Ender Pearls)</li>
     *   <li>Traveler is not in combat</li>
     * </ul>
     *
     * @param server      the Minecraft server
     * @param traveler    the bot that wants to get home
     * @param destination the target position (home/base)
     * @return true if the teleport was initiated
     */
    public static boolean tryBotToBotArtifactTeleport(MinecraftServer server,
                                                       ServerPlayerEntity traveler,
                                                       BlockPos destination) {
        if (server == null || traveler == null || destination == null) {
            return false;
        }
        if (isTraveling(traveler.getUuid())) {
            return false;
        }
        if (BotCombatCalloutService.isInCombat(traveler.getUuid())) {
            return false;
        }

        // Distance gate: traveler must be far enough from destination.
        double distSq = traveler.getBlockPos().getSquaredDistance(destination);
        if (distSq <= BOT_SUMMON_RANGE_SQ) {
            return false;
        }

        UUID travelerOwner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(traveler);
        if (travelerOwner == null) {
            return false;
        }

        // Find a receiver bot near the destination with tier-2 artifact access.
        ServerPlayerEntity receiver = null;
        for (ServerPlayerEntity candidate : BotEventHandler.getRegisteredBots(server)) {
            if (candidate == null || candidate == traveler || candidate.isRemoved()) {
                continue;
            }
            if (isTraveling(candidate.getUuid())) {
                continue;
            }
            // Same owner check.
            UUID candidateOwner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(candidate);
            if (!travelerOwner.equals(candidateOwner)) {
                continue;
            }
            // Receiver must be near the destination.
            if (candidate.getBlockPos().getSquaredDistance(destination) > RECEIVER_AT_DEST_RANGE_SQ) {
                continue;
            }
            // Receiver must have tier-2 artifact access.
            if (!hasReceiverTier2Access(candidate, traveler)) {
                continue;
            }
            receiver = candidate;
            break;
        }

        if (receiver == null) {
            return false;
        }

        // Calculate delay using receiver's artifact multiplier (1.0x for tier-2).
        double distance = Math.sqrt(distSq);
        ServerWorld travelerWorld = (ServerWorld) traveler.getEntityWorld();
        boolean crossDim = !travelerWorld.getRegistryKey().equals(
                ((ServerWorld) receiver.getEntityWorld()).getRegistryKey());
        int delayTicks = calculateDelayTicks(distance, crossDim, 1.0);

        UUID ownerUuid = travelerOwner;
        String travelerAlias = traveler.getName().getString();
        String receiverAlias = receiver.getName().getString();

        boolean started = beginDelayedTravel(server, traveler, travelerAlias, destination,
                ((ServerWorld) receiver.getEntityWorld()).getRegistryKey(), delayTicks, ownerUuid);

        if (started) {
            LOGGER.info("Bot-to-bot summon: {} is summoning {} home via artifact (dist={}, delay={}t)",
                    receiverAlias, travelerAlias, (int) distance, delayTicks);
            notifyOwner(server, ownerUuid,
                    "\u00A7d" + receiverAlias + " is summoning " + travelerAlias
                    + " home via artifact (ETA ~" + Math.max(1, delayTicks / 20) + "s).\u00A7r");
        }
        return started;
    }

    /**
     * Check whether a receiver bot has tier-2 artifact access for summoning.
     * Matches the tier-2 checks in {@link #artifactDelayMultiplier}.
     */
    private static boolean hasReceiverTier2Access(ServerPlayerEntity receiver,
                                                   ServerPlayerEntity traveler) {
        if (hasArtifact(receiver, net.minecraft.item.Items.ENDER_EYE)) return true;
        if (CompanionCommunicationPolicy.hasWizardTome(receiver)) return true;
        if (isNearBlock(receiver, net.minecraft.block.Blocks.ENCHANTING_TABLE, 6)) return true;
        // Dual ender pearls: both receiver and traveler must hold one.
        if (hasArtifact(receiver, net.minecraft.item.Items.ENDER_PEARL)
                && hasArtifact(traveler, net.minecraft.item.Items.ENDER_PEARL)) {
            return true;
        }
        return false;
    }

    // ── Fast travel system ────────────────────────────────────

    /** Tracks a bot that is currently in transit (removed from world, awaiting respawn). */
    public record PendingTravel(UUID botUuid, String botAlias, BlockPos destination,
                                RegistryKey<World> dimension, long departureTick, long arrivalTick,
                                UUID ownerUuid, String mountEntityTypeId) {}

    /** Bots currently in transit, keyed by bot UUID. */
    private static final Map<UUID, PendingTravel> PENDING_TRAVELS = new ConcurrentHashMap<>();

    /** Retry counts for failed respawn attempts, keyed by bot UUID. Max 3 retries. */
    private static final Map<UUID, Integer> RESPAWN_RETRY_COUNTS = new ConcurrentHashMap<>();
    private static final int MAX_RESPAWN_RETRIES = 3;

    /** Messages queued for owners who were offline when the notification was sent. */
    private static final Map<UUID, List<String>> QUEUED_NOTIFICATIONS = new ConcurrentHashMap<>();

    /** Actions to perform after a bot arrives at its fast travel destination (e.g., withdraw from chest). */
    public record PostArrivalAction(String type, BlockPos target, UUID ownerUuid, Vec3d returnPos) {}
    private static final Map<String, PostArrivalAction> PENDING_POST_ARRIVAL = new ConcurrentHashMap<>();

    /** Schedule a post-arrival action for a bot (keyed by lowercase alias). */
    public static void schedulePostArrival(String botAlias, PostArrivalAction action) {
        if (botAlias != null && action != null) {
            PENDING_POST_ARRIVAL.put(botAlias.toLowerCase(java.util.Locale.ROOT), action);
        }
    }

    /** Post-spawn setup waiting for the bot entity to appear in the player manager. */
    private record PostSpawnSetup(String botAlias, ServerWorld world, Vec3d spawnPos, BlockPos dest,
                                  RegistryKey<World> dim, PendingTravel travel,
                                  boolean dimensionFallback, int ticksWaited) {}
    private static final Map<String, PostSpawnSetup> PENDING_POST_SPAWN = new ConcurrentHashMap<>();

    /** Check if a bot is currently in transit. */
    public static boolean isTraveling(UUID botUuid) {
        return botUuid != null && PENDING_TRAVELS.containsKey(botUuid);
    }

    /** Get the pending travel record for a bot, or null if not traveling. */
    public static PendingTravel getPendingTravel(UUID botUuid) {
        return botUuid != null ? PENDING_TRAVELS.get(botUuid) : null;
    }

    /**
     * Begin fast travel for a bot. The bot is removed from the world and will be
     * respawned at the destination after {@code delayTicks} have elapsed.
     *
     * @param server      the Minecraft server
     * @param bot         the fake player bot to send traveling
     * @param botAlias    the bot's display name / alias
     * @param destination the target block position
     * @param dimension   the target dimension
     * @param delayTicks  how many ticks until arrival
     * @param ownerUuid   UUID of the player who owns this bot (for notifications)
     */
    public static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                             String botAlias, BlockPos destination,
                                             RegistryKey<World> dimension, int delayTicks,
                                             UUID ownerUuid) {
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, false, false);
    }

    /**
     * Emergency travel path used by autonomous rescue logic.
     * Reuses the same delayed-travel pipeline but skips the normal underground gating.
     */
    public static boolean beginEmergencyTravel(MinecraftServer server, ServerPlayerEntity bot,
                                               String botAlias, BlockPos destination,
                                               RegistryKey<World> dimension, int delayTicks,
                                               UUID ownerUuid) {
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, true, true);
    }

    /**
     * Coordinated emergency travel for two bots. Used by autonomous escort rescue so both
     * bots enter traveling on the same tick and the rescue service can own HUD messaging.
     */
    public static boolean beginCoordinatedEmergencyTravel(MinecraftServer server,
                                                          ServerPlayerEntity primaryBot,
                                                          String primaryAlias,
                                                          BlockPos primaryDestination,
                                                          RegistryKey<World> primaryDimension,
                                                          ServerPlayerEntity secondaryBot,
                                                          String secondaryAlias,
                                                          BlockPos secondaryDestination,
                                                          RegistryKey<World> secondaryDimension,
                                                          int delayTicks,
                                                          UUID ownerUuid) {
        if (server == null || primaryBot == null || secondaryBot == null
                || primaryDestination == null || secondaryDestination == null
                || primaryDimension == null || secondaryDimension == null) {
            return false;
        }
        if (primaryBot.hasVehicle() || secondaryBot.hasVehicle()) {
            return false;
        }
        if (!canBeginDelayedTravel(server, primaryBot, primaryDestination, primaryDimension, ownerUuid, true)
                || !canBeginDelayedTravel(server, secondaryBot, secondaryDestination, secondaryDimension, ownerUuid, true)) {
            return false;
        }
        boolean primaryStarted = beginDelayedTravel(server, primaryBot, primaryAlias, primaryDestination, primaryDimension,
                delayTicks, ownerUuid, true, true);
        if (!primaryStarted) {
            return false;
        }
        boolean secondaryStarted = beginDelayedTravel(server, secondaryBot, secondaryAlias, secondaryDestination, secondaryDimension,
                delayTicks, ownerUuid, true, true);
        return secondaryStarted;
    }

    private static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                              String botAlias, BlockPos destination,
                                              RegistryKey<World> dimension, int delayTicks,
                                              UUID ownerUuid, boolean skipGates, boolean suppressOwnerNotify) {
        if (server == null || bot == null || botAlias == null || destination == null || dimension == null) {
            LOGGER.warn("beginDelayedTravel called with null arguments; ignoring.");
            return false;
        }

        if (!skipGates) {
            // ── Combat gate ──────────────────────────────────────────────
            if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                notifyOwner(server, ownerUuid,
                        "\u00A7c" + botAlias + " cannot fast-travel while in combat.\u00A7r");
                return false;
            }

            // ── Underground gate (requires Map+Compass or Tier 2+) ──────
            ServerWorld currentWorld = (ServerWorld) bot.getEntityWorld();
            if (!currentWorld.isSkyVisible(bot.getBlockPos().up())) {
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                double mult = artifactDelayMultiplier(bot, owner);
                if (mult <= 1.0) {
                    // Tier 2+ artifacts — proceed normally, no penalty
                } else if (hasArtifact(bot, net.minecraft.item.Items.FILLED_MAP)
                        && hasArtifact(bot, net.minecraft.item.Items.COMPASS)) {
                    // Map + Compass on bot — allow but with 50% extra delay
                    delayTicks = (int) (delayTicks * 1.5);
                } else {
                    notifyOwner(server, ownerUuid,
                            "\u00A7c" + botAlias + " cannot fast-travel underground without a Map and Compass.\u00A7r");
                    return false;
                }
            }
        }

        // ── Mount evaluation ──────────────────────────────────────────────
        ServerWorld destWorld = server.getWorld(dimension);
        TravelMountHandler.MountTravelResult mountResult =
                TravelMountHandler.evaluateTravel(bot, destination, dimension, destWorld);

        String mountEntityTypeId = null;
        switch (mountResult.decision()) {
            case REFUSE_FULL_INVENTORY, REFUSE_NO_ROOM_AT_DEST, REFUSE_CROSS_DIM_ANIMAL -> {
                notifyOwner(server, ownerUuid, "\u00A7c" + mountResult.message() + "\u00A7r");
                return false; // Abort travel
            }
            case TETHERED_CROSS_DIM -> {
                notifyOwner(server, ownerUuid, "\u00A7e" + mountResult.message() + "\u00A7r");
                // Fall through — travel proceeds without animal
            }
            case PROCEED_WITH_ANIMAL -> {
                // Record the entity type so we can recreate it at the destination
                Entity mount = mountResult.mountEntity();
                if (mount != null) {
                    bot.stopRiding();
                    Identifier typeId = EntityType.getId(mount.getType());
                    mountEntityTypeId = typeId != null ? typeId.toString() : null;
                    TravelMountHandler.ensureMountPersistence(mount);
                    mount.discard();
                    LOGGER.info("Mount '{}' ({}) will be recreated at destination for bot '{}'",
                            mount.getName().getString(), mountEntityTypeId, botAlias);
                }
            }
            default -> {} // PROCEED_NO_MOUNT, PROCEED_VEHICLE_COLLECTED — nothing extra
        }

        UUID botUuid = bot.getUuid();
        long now = server.getOverworld().getTime();
        long arrival = now + delayTicks;

        PendingTravel travel = new PendingTravel(botUuid, botAlias, destination, dimension,
                now, arrival, ownerUuid, mountEntityTypeId);
        PENDING_TRAVELS.put(botUuid, travel);

        // Set mode to TRAVELING so other systems ignore this bot.
        BotCommandStateService.State state = BotCommandStateService.stateFor(botUuid);
        if (state != null) {
            state.mode = BotEventHandler.Mode.TRAVELING;
        }

        // Pre-write destination as the bot's saved world-state position. This is a safety
        // net: if onBotJoin's isTraveling guard doesn't fire, the bot restores to the
        // destination rather than the departure point.
        BotWorldStateService.saveStateManual(server, botAlias,
                destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, 0, 0);

        // Persist bot state (inventory, position) before removal so it survives the trip.
        // Save explicitly, then disconnect the bot cleanly.
        try {
            BotPersistenceService.onBotDisconnect(bot);
        } catch (Throwable t) {
            LOGGER.warn("Failed to persist bot '{}' before travel: {}", botAlias, t.getMessage());
        }

        // Remove from player manager FIRST (sends PlayerRemoveS2CPacket — clears tab list),
        // THEN discard the entity (sets removal reason to DISCARDED, which triggers ServerWorld
        // to remove the entity from chunk tracking and broadcast EntitiesDestroyS2CPacket to all
        // clients, making the bot visually disappear).
        // Note: kill() on createFakePlayer is a no-op (FakeClientConnection.handleDisconnection
        // does nothing), so we must use discard() to actually remove the entity from the world.
        try {
            BotPersistenceService.removeFromPlayerManager(server, bot);
        } catch (Throwable t) {
            LOGGER.warn("Failed to remove bot '{}' from player manager: {}", botAlias, t.getMessage());
        }
        try {
            bot.discard();
        } catch (Throwable t) {
            LOGGER.warn("Failed to discard bot '{}' for travel: {}", botAlias, t.getMessage());
        }

        int delaySeconds = delayTicks / 20;
        LOGGER.info("Bot '{}' departed for {} in {} (ETA: {}s, {} ticks)",
                botAlias, destination.toShortString(),
                dimension.getValue(), delaySeconds, delayTicks);

        // Notify the owner that the bot has departed (queues if offline).
        if (!suppressOwnerNotify) {
            notifyOwner(server, ownerUuid,
                    "\u00A7e" + botAlias + " has departed and will arrive in ~"
                    + delaySeconds + " seconds.\u00A7r");
        }

        flushPendingTravels();
        return true;
    }

    private static boolean canBeginDelayedTravel(MinecraftServer server,
                                                 ServerPlayerEntity bot,
                                                 BlockPos destination,
                                                 RegistryKey<World> dimension,
                                                 UUID ownerUuid,
                                                 boolean skipGates) {
        if (server == null || bot == null || destination == null || dimension == null) {
            return false;
        }
        if (!skipGates) {
            if (BotCombatCalloutService.isInCombat(bot.getUuid())) {
                return false;
            }
            ServerWorld currentWorld = (ServerWorld) bot.getEntityWorld();
            if (!currentWorld.isSkyVisible(bot.getBlockPos().up())) {
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                double mult = artifactDelayMultiplier(bot, owner);
                if (mult > 1.0
                        && !(hasArtifact(bot, net.minecraft.item.Items.FILLED_MAP)
                        && hasArtifact(bot, net.minecraft.item.Items.COMPASS))) {
                    return false;
                }
            }
        }
        ServerWorld destWorld = server.getWorld(dimension);
        TravelMountHandler.MountTravelResult mountResult =
                TravelMountHandler.evaluateTravel(bot, destination, dimension, destWorld);
        return switch (mountResult.decision()) {
            case REFUSE_FULL_INVENTORY, REFUSE_NO_ROOM_AT_DEST, REFUSE_CROSS_DIM_ANIMAL -> false;
            default -> true;
        };
    }

    /**
     * Called every server tick. Checks pending travels and respawns bots whose arrival time
     * has been reached.
     */
    public static void tickPendingTravels(MinecraftServer server) {
        // Process post-spawn setups (waiting for bot entity to appear after createFake).
        if (!PENDING_POST_SPAWN.isEmpty()) {
            Iterator<Map.Entry<String, PostSpawnSetup>> psIt = PENDING_POST_SPAWN.entrySet().iterator();
            while (psIt.hasNext()) {
                Map.Entry<String, PostSpawnSetup> psEntry = psIt.next();
                PostSpawnSetup ps = psEntry.getValue();
                // Find the bot by name — must be alive (not the old killed entity).
                ServerPlayerEntity bot = null;
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && !p.isRemoved() && ps.botAlias().equalsIgnoreCase(p.getName().getString())) {
                        bot = p;
                        break;
                    }
                }
                if (bot != null) {
                    LOGGER.info("Bot '{}' appeared after {} real ticks — completing travel setup.",
                            ps.botAlias(), ps.ticksWaited());
                    completePostSpawnSetup(server, bot, ps);
                    psIt.remove();
                } else if (ps.ticksWaited() >= 60) {
                    // Log all entities with matching name for diagnostics.
                    ServerPlayerEntity stale = server.getPlayerManager().getPlayer(ps.botAlias());
                    LOGGER.error("Bot '{}' failed to appear after 60 real ticks. " +
                            "getPlayer(name) returned: {} (removed={})",
                            ps.botAlias(), stale, stale != null ? stale.isRemoved() : "n/a");
                    psIt.remove();
                } else {
                    int nextTick = ps.ticksWaited() + 1;
                    if (nextTick % 10 == 0) {
                        // Count matching players in full list for diagnostics.
                        long matching = server.getPlayerManager().getPlayerList().stream()
                                .filter(p -> ps.botAlias().equalsIgnoreCase(p.getName().getString()))
                                .count();
                        long alive = server.getPlayerManager().getPlayerList().stream()
                                .filter(p -> ps.botAlias().equalsIgnoreCase(p.getName().getString()) && !p.isRemoved())
                                .count();
                        LOGGER.info("Post-spawn poll tick {}: bot='{}' matching={} alive={}",
                                nextTick, ps.botAlias(), matching, alive);
                    }
                    psEntry.setValue(new PostSpawnSetup(ps.botAlias(), ps.world(), ps.spawnPos(),
                            ps.dest(), ps.dim(), ps.travel(), ps.dimensionFallback(),
                            nextTick));
                }
            }
        }

        if (PENDING_TRAVELS.isEmpty()) {
            return;
        }

        long now = server.getOverworld().getTime();
        Iterator<Map.Entry<UUID, PendingTravel>> it = PENDING_TRAVELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTravel> entry = it.next();
            PendingTravel travel = entry.getValue();
            if (now >= travel.arrivalTick()) {
                try {
                    respawnBotAtDestination(server, travel);
                    // Success — remove from map and clean up retry state.
                    it.remove();
                    RESPAWN_RETRY_COUNTS.remove(travel.botUuid());
                    flushPendingTravels();
                } catch (Throwable t) {
                    int retries = RESPAWN_RETRY_COUNTS.merge(travel.botUuid(), 1, Integer::sum);
                    if (retries > MAX_RESPAWN_RETRIES) {
                        LOGGER.error("Permanently failed to respawn bot '{}' after {} retries: {}",
                                travel.botAlias(), retries, t.getMessage(), t);
                        it.remove();
                        RESPAWN_RETRY_COUNTS.remove(travel.botUuid());
                        flushPendingTravels();
                        // Notify owner of permanent failure.
                        notifyOwner(server, travel.ownerUuid(),
                                "\u00A7cTravel failed: your companion " + travel.botAlias()
                                + " could not be respawned at the destination.\u00A7r");
                    } else {
                        LOGGER.warn("Respawn attempt {}/{} failed for bot '{}': {} (will retry next tick)",
                                retries, MAX_RESPAWN_RETRIES, travel.botAlias(), t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Respawn a bot at its travel destination. Creates a new fake player entity at the
     * destination coordinates, restores persisted state (inventory, etc.), registers
     * the bot with mod systems, and notifies the owner.
     */
    private static void respawnBotAtDestination(MinecraftServer server, PendingTravel travel) {
        final boolean dimensionFallback;
        final ServerWorld finalWorld;
        final BlockPos finalDest;
        final RegistryKey<World> finalDim;

        ServerWorld targetWorld = server.getWorld(travel.dimension());
        if (targetWorld == null) {
            LOGGER.warn("Target dimension {} unloaded for bot '{}'; falling back to Overworld spawn.",
                    travel.dimension().getValue(), travel.botAlias());
            targetWorld = server.getOverworld();
            finalWorld = targetWorld;
            net.minecraft.world.WorldProperties.SpawnPoint sp = targetWorld.getSpawnPoint();
            finalDest = (sp != null && sp.getPos() != null) ? sp.getPos() : BlockPos.ORIGIN;
            finalDim = World.OVERWORLD;
            dimensionFallback = true;
        } else {
            finalWorld = targetWorld;
            finalDest = travel.destination();
            finalDim = travel.dimension();
            dimensionFallback = false;
        }
        final Vec3d spawnPos = Vec3d.ofBottomCenter(finalDest);

        LOGGER.info("Respawning bot '{}' at {} in {}",
                travel.botAlias(), finalDest.toShortString(), finalDim.getValue());

        // Create the fake player at the destination.
        // createFake handles GameProfile resolution, skin, connection, and teleport.
        createFakePlayer.createFake(
                travel.botAlias(),
                server,
                spawnPos,
                0.0,   // yaw — will face north; owner can adjust
                0.0,   // pitch
                finalDim,
                GameMode.SURVIVAL,
                false   // not flying
        );

        // Enqueue post-spawn setup — processed in tickPendingTravels() which runs
        // once per real server tick, giving createFake time to fully register the entity.
        PENDING_POST_SPAWN.put(travel.botAlias().toLowerCase(java.util.Locale.ROOT),
                new PostSpawnSetup(travel.botAlias(), finalWorld, spawnPos, finalDest,
                        finalDim, travel, dimensionFallback, 0));
    }

    private static void completePostSpawnSetup(MinecraftServer server, ServerPlayerEntity bot, PostSpawnSetup ps) {
        // Teleport to exact destination (onBotJoin skips position restore for traveling bots).
        double dx = ps.spawnPos().x, dy = ps.spawnPos().y, dz = ps.spawnPos().z;
        bot.teleport(ps.world(), dx, dy, dz,
                java.util.Set.of(), 0.0F, 0.0F, true);
        // Force position + send position packet to ALL clients in the dimension.
        bot.setPosition(dx, dy, dz);
        bot.setVelocity(Vec3d.ZERO);
        // Broadcast entity position to all players in the dimension (same approach as createFake).
        EntityPosition ep = EntityPosition.fromEntity(bot).withRotation(0.0F, 0.0F);
        server.getPlayerManager().sendToDimension(
                new net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket(
                        bot.getId(), ep, java.util.Set.of(), bot.isOnGround()),
                ps.dim());

        try { BotEventHandler.registerBot(bot); }
        catch (Throwable t) { LOGGER.warn("Failed to register bot '{}' after travel: {}", ps.botAlias(), t.getMessage()); }

        try { AutoFaceEntity.startAutoFace(bot); }
        catch (Throwable t) { LOGGER.debug("AutoFaceEntity start failed for '{}': {}", ps.botAlias(), t.getMessage()); }

        BotCommandStateService.State cmdState = BotCommandStateService.stateFor(bot);
        if (cmdState != null) { cmdState.mode = BotEventHandler.Mode.IDLE; }

        // Recreate co-traveling mount if one was stored.
        PendingTravel travel = ps.travel();
        if (travel.mountEntityTypeId() != null) {
            try {
                Identifier typeId = Identifier.of(travel.mountEntityTypeId());
                if (Registries.ENTITY_TYPE.containsId(typeId)) {
                    EntityType<?> mountType = Registries.ENTITY_TYPE.get(typeId);
                    Entity mount = mountType.create(ps.world(), SpawnReason.COMMAND);
                    if (mount != null) {
                        BlockPos safeSpot = TravelMountHandler.findSafeAnimalSpot(ps.world(), ps.dest(), mount);
                        BlockPos mountPos = safeSpot != null ? safeSpot : ps.dest();
                        mount.refreshPositionAndAngles(mountPos.getX() + 0.5, mountPos.getY(), mountPos.getZ() + 0.5, 0, 0);
                        ps.world().spawnEntity(mount);
                        TravelMountHandler.ensureMountPersistence(mount);
                        MountPersistenceService.recordMount(bot, mount, true);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to recreate mount for '{}': {}", ps.botAlias(), e.getMessage());
            }
        }

        ps.world().playSound(null, ps.dest(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 1.0F, 0.8F);

        String msg = ps.dimensionFallback()
                ? "\u00A7eYour companion " + ps.botAlias() + " arrived at Overworld spawn (target dimension was unavailable).\u00A7r"
                : "\u00A7aYour companion " + ps.botAlias() + " has arrived at the destination.\u00A7r";
        notifyOwner(server, travel.ownerUuid(), msg);

        LOGGER.info("Bot '{}' arrived at {} in {}.", ps.botAlias(), ps.dest().toShortString(), ps.dim().getValue());

        // Process post-arrival action (e.g., withdraw from chest, then return).
        String key = ps.botAlias().toLowerCase(java.util.Locale.ROOT);
        PostArrivalAction action = PENDING_POST_ARRIVAL.remove(key);
        if (action != null && action.type() != null && action.type().startsWith("withdraw")) {
            // Parse return mode: "withdraw:stay", "withdraw:player", "withdraw:home"
            String returnMode = "stay";
            if (action.type().contains(":")) {
                returnMode = action.type().substring(action.type().indexOf(':') + 1);
            }
            final String finalReturnMode = returnMode;

            // Schedule withdrawal on next tick (bot needs to be fully in world first).
            server.execute(() -> {
                ServerPlayerEntity arrBot = server.getPlayerManager().getPlayer(ps.botAlias());
                if (arrBot == null || arrBot.isRemoved()) return;
                BlockPos chestPos = action.target();
                ServerWorld w = (ServerWorld) arrBot.getEntityWorld();
                var be = w.getBlockEntity(chestPos);
                if (be instanceof net.minecraft.inventory.Inventory storage) {
                    int moved = 0;
                    for (int i = 0; i < storage.size() && moved < 256; i++) {
                        net.minecraft.item.ItemStack stack = storage.getStack(i);
                        if (stack == null || stack.isEmpty()) continue;
                        net.minecraft.item.ItemStack copy = stack.copy();
                        if (arrBot.getInventory().insertStack(copy)) {
                            int taken = stack.getCount() - copy.getCount();
                            stack.decrement(taken);
                            moved += taken;
                        }
                    }
                    storage.markDirty();
                    BotChestRegistryService.updateContentsSnapshot(arrBot, chestPos, w, storage);
                    LOGGER.info("Post-arrival: {} withdrew {} items from chest at {} (return={})",
                            ps.botAlias(), moved, chestPos.toShortString(), finalReturnMode);

                    notifyOwner(server, action.ownerUuid(),
                            "\u00A7a" + ps.botAlias() + " collected " + moved + " items from the chest.\u00A7r");

                    // Handle return trip.
                    handlePostCollectReturn(server, arrBot, ps.botAlias(), finalReturnMode, action);
                } else {
                    notifyOwner(server, action.ownerUuid(),
                            "\u00A7e" + ps.botAlias() + " arrived but no chest found at " + chestPos.toShortString() + ".\u00A7r");
                }
            });
        }
    }

    /** After collecting, fast-travel back to the requested destination. */
    private static void handlePostCollectReturn(MinecraftServer server, ServerPlayerEntity bot,
                                                 String botAlias, String returnMode, PostArrivalAction action) {
        if ("stay".equals(returnMode) || returnMode == null) return;

        BlockPos returnDest = null;
        String returnLabel = null;

        if ("player".equals(returnMode)) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(action.ownerUuid());
            if (owner != null && !owner.isRemoved()) {
                returnDest = owner.getBlockPos();
                returnLabel = "player";
            }
        } else if ("home".equals(returnMode)) {
            // Use the "home" base label.
            ServerWorld w = (ServerWorld) bot.getEntityWorld();
            var homeOpt = BotHomeService.getBaseByLabel(server, w, "home");
            if (homeOpt.isPresent()) {
                returnDest = homeOpt.get();
                returnLabel = "home base";
            }
        }

        if (returnDest == null) {
            // Fall back to origin position stored in the action.
            if (action.returnPos() != null) {
                returnDest = BlockPos.ofFloored(action.returnPos());
                returnLabel = "origin";
            }
        }

        if (returnDest != null) {
            double dist = bot.getBlockPos().getManhattanDistance(returnDest);
            int delayTicks = calculateDelayTicks(dist, false);
            LOGGER.info("Post-collect return: {} -> {} ({}, dist={}, ETA={}s)",
                    botAlias, returnDest.toShortString(), returnLabel, (int) dist, delayTicks / 20);
            beginDelayedTravel(server, bot, botAlias, returnDest,
                    ((ServerWorld) bot.getEntityWorld()).getRegistryKey(), delayTicks, action.ownerUuid(),
                    true /* skipGates: return trip after collection */, false);
            notifyOwner(server, action.ownerUuid(),
                    "\u00A7e" + botAlias + " is returning to " + returnLabel + " (ETA ~" + Math.max(1, delayTicks / 20) + "s).\u00A7r");
        }
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    /**
     * Flush pending travels to disk, then clear all in-memory state.
     * Called from {@code Frens.java} SERVER_STOPPED to prevent stale state
     * leaking across integrated-server world reloads.
     */
    public static void resetSession() {
        flushPendingTravels();
        PENDING_TRAVELS.clear();
        RESPAWN_RETRY_COUNTS.clear();
        QUEUED_NOTIFICATIONS.clear();
        PENDING_POST_SPAWN.clear();
        PENDING_POST_ARRIVAL.clear();
    }

    // ── Persistence ────────────────────────────────────────────────────────

    /**
     * Write the current {@link #PENDING_TRAVELS} map to JSON so in-flight travels
     * survive a server restart.
     */
    public static void flushPendingTravels() {
        synchronized (LOCK) {
            try {
                Path file = travelFile();
                Files.createDirectories(file.getParent());

                List<SavedTravel> list = new ArrayList<>();
                for (PendingTravel t : PENDING_TRAVELS.values()) {
                    SavedTravel s = new SavedTravel();
                    s.botUuid = t.botUuid().toString();
                    s.botAlias = t.botAlias();
                    s.destX = t.destination().getX();
                    s.destY = t.destination().getY();
                    s.destZ = t.destination().getZ();
                    s.dimension = t.dimension().getValue().toString();
                    s.departureTick = t.departureTick();
                    s.arrivalTick = t.arrivalTick();
                    s.ownerUuid = t.ownerUuid() != null ? t.ownerUuid().toString() : null;
                    s.mountEntityTypeId = t.mountEntityTypeId();
                    list.add(s);
                }

                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(list, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save pending travels: {}", e.getMessage());
            }
        }
    }

    /**
     * Reload pending travels from JSON after a server restart. Rebases arrival ticks
     * to current server time, preserving the remaining travel duration.
     *
     * @param server the freshly started server
     */
    public static void loadPendingTravels(MinecraftServer server) {
        synchronized (LOCK) {
            Path file = travelFile();
            if (!Files.exists(file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                Type listType = new TypeToken<List<SavedTravel>>() {}.getType();
                List<SavedTravel> list = GSON.fromJson(reader, listType);
                if (list == null || list.isEmpty()) {
                    return;
                }

                long now = server.getOverworld().getTime();

                for (SavedTravel s : list) {
                    try {
                        UUID botUuid = UUID.fromString(s.botUuid);
                        UUID ownerUuid = s.ownerUuid != null ? UUID.fromString(s.ownerUuid) : null;
                        BlockPos dest = new BlockPos(s.destX, s.destY, s.destZ);
                        RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD,
                                Identifier.of(s.dimension));

                        // Rebase: preserve the remaining travel duration from the
                        // original schedule. On restart we don't know wall-clock elapsed
                        // time, so we use the full planned duration as remaining ticks.
                        long remainingTicks = Math.max(0, s.arrivalTick - s.departureTick);
                        long newArrival = now + remainingTicks;

                        PendingTravel travel = new PendingTravel(botUuid, s.botAlias, dest, dim,
                                now, newArrival, ownerUuid, s.mountEntityTypeId);
                        PENDING_TRAVELS.put(botUuid, travel);

                        LOGGER.info("Restored pending travel for '{}': destination {} in {}, ETA {} ticks",
                                s.botAlias, dest.toShortString(), s.dimension, remainingTicks);
                    } catch (Exception e) {
                        LOGGER.warn("Skipping malformed saved travel entry for '{}': {}",
                                s.botAlias, e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load pending travels: {}", e.getMessage());
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Send a message to a player if online, otherwise queue it for delivery on next login. */
    private static void notifyOwner(MinecraftServer server, UUID ownerUuid, String message) {
        if (server == null || ownerUuid == null) return;
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
        if (owner != null) {
            owner.sendMessage(Text.literal(message), false);
        } else {
            QUEUED_NOTIFICATIONS.computeIfAbsent(ownerUuid, k -> new ArrayList<>()).add(message);
        }
    }

    /**
     * Send all queued travel notifications to a player who just joined.
     * Call from {@code Frens.java} ServerPlayConnectionEvents.JOIN handler.
     */
    public static void drainQueuedNotifications(ServerPlayerEntity player) {
        if (player == null) return;
        List<String> messages = QUEUED_NOTIFICATIONS.remove(player.getUuid());
        if (messages == null || messages.isEmpty()) return;
        for (String msg : messages) {
            player.sendMessage(Text.literal(msg), false);
        }
    }

    private static boolean hasItemInInventory(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }
}
