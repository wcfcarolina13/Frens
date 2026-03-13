package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Items;
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
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Estimate how many ticks a delayed-travel sequence should take based on distance.
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
        int chunks = Math.max(1, (int) Math.ceil(distance / 16.0));
        int seconds = chunks;
        if (crossDimension) seconds += 30;
        seconds = Math.max(5, Math.min(300, seconds));
        return seconds * 20;
    }

    // ── Delayed teleport travel system ────────────────────────────────────

    /** Tracks a bot that is currently in transit (removed from world, awaiting respawn). */
    public record PendingTravel(UUID botUuid, String botAlias, BlockPos destination,
                                RegistryKey<World> dimension, long departureTick, long arrivalTick,
                                UUID ownerUuid) {}

    /** Bots currently in transit, keyed by bot UUID. */
    private static final Map<UUID, PendingTravel> PENDING_TRAVELS = new ConcurrentHashMap<>();

    /** Check if a bot is currently in transit. */
    public static boolean isTraveling(UUID botUuid) {
        return botUuid != null && PENDING_TRAVELS.containsKey(botUuid);
    }

    /** Get the pending travel record for a bot, or null if not traveling. */
    public static PendingTravel getPendingTravel(UUID botUuid) {
        return botUuid != null ? PENDING_TRAVELS.get(botUuid) : null;
    }

    /**
     * Begin a delayed travel for a bot. The bot is removed from the world and will be
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
    public static void beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                          String botAlias, BlockPos destination,
                                          RegistryKey<World> dimension, int delayTicks,
                                          UUID ownerUuid) {
        if (server == null || bot == null || botAlias == null || destination == null || dimension == null) {
            LOGGER.warn("beginDelayedTravel called with null arguments; ignoring.");
            return;
        }

        UUID botUuid = bot.getUuid();
        long now = server.getOverworld().getTime();
        long arrival = now + delayTicks;

        PendingTravel travel = new PendingTravel(botUuid, botAlias, destination, dimension,
                now, arrival, ownerUuid);
        PENDING_TRAVELS.put(botUuid, travel);

        // Set mode to TRAVELING so other systems ignore this bot.
        BotCommandStateService.State state = BotCommandStateService.stateFor(botUuid);
        if (state != null) {
            state.mode = BotEventHandler.Mode.TRAVELING;
        }

        // Persist bot state (inventory, position) before removal so it survives the trip.
        // Save explicitly, then disconnect the bot cleanly.
        try {
            BotPersistenceService.onBotDisconnect(bot);
        } catch (Throwable t) {
            LOGGER.warn("Failed to persist bot '{}' before travel: {}", botAlias, t.getMessage());
        }

        // Remove the bot from the world. Use kill(Text) for a clean disconnect
        // that removes the entity from the player list without triggering onDeath logic.
        try {
            if (bot instanceof createFakePlayer fake) {
                fake.kill(Text.literal("Traveling"));
            } else {
                bot.discard();
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to remove bot '{}' from world for travel: {}", botAlias, t.getMessage());
        }

        int delaySeconds = delayTicks / 20;
        LOGGER.info("Bot '{}' departed for {} in {} (ETA: {}s, {} ticks)",
                botAlias, destination.toShortString(),
                dimension.getValue(), delaySeconds, delayTicks);

        // Notify the owner that the bot has departed.
        if (ownerUuid != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
            if (owner != null) {
                owner.sendMessage(
                        Text.literal("\u00A7e" + botAlias + " has departed and will arrive in ~"
                                + delaySeconds + " seconds.\u00A7r"),
                        false);
            }
        }

        flushPendingTravels();
    }

    /**
     * Called every server tick. Checks pending travels and respawns bots whose arrival time
     * has been reached.
     */
    public static void tickPendingTravels(MinecraftServer server) {
        if (PENDING_TRAVELS.isEmpty()) {
            return;
        }

        long now = server.getOverworld().getTime();
        Iterator<Map.Entry<UUID, PendingTravel>> it = PENDING_TRAVELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTravel> entry = it.next();
            PendingTravel travel = entry.getValue();
            if (now >= travel.arrivalTick()) {
                it.remove();
                flushPendingTravels();
                try {
                    respawnBotAtDestination(server, travel);
                } catch (Throwable t) {
                    LOGGER.error("Failed to respawn bot '{}' at destination: {}",
                            travel.botAlias(), t.getMessage(), t);
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
        ServerWorld targetWorld = server.getWorld(travel.dimension());
        if (targetWorld == null) {
            LOGGER.error("Cannot respawn bot '{}': target dimension {} not found.",
                    travel.botAlias(), travel.dimension().getValue());
            return;
        }

        BlockPos dest = travel.destination();
        Vec3d spawnPos = Vec3d.ofBottomCenter(dest);

        LOGGER.info("Respawning bot '{}' at {} in {}",
                travel.botAlias(), dest.toShortString(), travel.dimension().getValue());

        // Create the fake player at the destination.
        // createFake handles GameProfile resolution, skin, connection, and teleport.
        createFakePlayer.createFake(
                travel.botAlias(),
                server,
                spawnPos,
                0.0,   // yaw — will face north; owner can adjust
                0.0,   // pitch
                travel.dimension(),
                GameMode.SURVIVAL,
                false   // not flying
        );

        // Schedule post-spawn setup for next tick to let the player entity fully initialize.
        server.execute(() -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(travel.botAlias());
            if (bot == null || bot.isRemoved()) {
                LOGGER.warn("Bot '{}' not found after travel respawn; it may have failed to connect.",
                        travel.botAlias());
                return;
            }

            // Teleport to exact destination in case onBotJoin restored a stale position.
            bot.teleport(targetWorld, spawnPos.x, spawnPos.y, spawnPos.z,
                    java.util.Set.of(), 0.0F, 0.0F, true);

            // Register with mod systems.
            try {
                BotEventHandler.registerBot(bot);
            } catch (Throwable t) {
                LOGGER.warn("Failed to register bot '{}' after travel: {}", travel.botAlias(), t.getMessage());
            }

            // Start auto-face idle head rotation.
            try {
                AutoFaceEntity.startAutoFace(bot);
            } catch (Throwable t) {
                LOGGER.debug("AutoFaceEntity start failed for '{}': {}", travel.botAlias(), t.getMessage());
            }

            // Set mode back to IDLE now that travel is complete.
            BotCommandStateService.State cmdState = BotCommandStateService.stateFor(bot);
            if (cmdState != null) {
                cmdState.mode = BotEventHandler.Mode.IDLE;
            }

            // Play arrival sound at the destination.
            targetWorld.playSound(
                    null,  // all nearby players hear it
                    dest,
                    SoundEvents.ENTITY_ENDER_PEARL_THROW,
                    SoundCategory.PLAYERS,
                    1.0F,  // volume
                    0.8F   // pitch
            );

            // Notify the owner.
            if (travel.ownerUuid() != null) {
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(travel.ownerUuid());
                if (owner != null) {
                    owner.sendMessage(
                            Text.literal("\u00A7aYour companion " + travel.botAlias()
                                    + " has arrived at the destination.\u00A7r"),
                            false);
                }
            }

            LOGGER.info("Bot '{}' arrived at {} in {} after {} ticks of travel.",
                    travel.botAlias(), dest.toShortString(), travel.dimension().getValue(),
                    travel.arrivalTick() - travel.departureTick());
        });
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
                                now, newArrival, ownerUuid);
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
