package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.ChestBoatEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mount evaluation for all travel paths (fast travel, Chorus Recall, etc.).
 * Determines how to handle a mounted bot before teleportation: collect vehicles as items,
 * co-teleport animals when safe, tether for cross-dimension, or refuse when unsafe.
 */
public final class TravelMountHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("travel-mount-handler");

    private TravelMountHandler() {}

    // ════════════════════════════════════════════════════════════════════
    //  Decision types
    // ════════════════════════════════════════════════════════════════════

    public enum MountTravelDecision {
        PROCEED_NO_MOUNT,
        PROCEED_VEHICLE_COLLECTED,
        PROCEED_WITH_ANIMAL,
        REFUSE_FULL_INVENTORY,
        REFUSE_NO_ROOM_AT_DEST,
        REFUSE_CROSS_DIM_ANIMAL,
        TETHERED_CROSS_DIM
    }

    public record MountTravelResult(
        MountTravelDecision decision,
        String message,
        Entity mountEntity,
        BlockPos tetherPos,
        String tetherDimName
    ) {
        static MountTravelResult proceed() {
            return new MountTravelResult(MountTravelDecision.PROCEED_NO_MOUNT, null, null, null, null);
        }

        static MountTravelResult vehicleCollected(String msg) {
            return new MountTravelResult(MountTravelDecision.PROCEED_VEHICLE_COLLECTED, msg, null, null, null);
        }

        static MountTravelResult withAnimal(Entity animal, String msg) {
            return new MountTravelResult(MountTravelDecision.PROCEED_WITH_ANIMAL, msg, animal, null, null);
        }

        static MountTravelResult refuseInventory(String msg) {
            return new MountTravelResult(MountTravelDecision.REFUSE_FULL_INVENTORY, msg, null, null, null);
        }

        static MountTravelResult refuseNoRoom(String msg) {
            return new MountTravelResult(MountTravelDecision.REFUSE_NO_ROOM_AT_DEST, msg, null, null, null);
        }

        static MountTravelResult refuseCrossDim(String msg) {
            return new MountTravelResult(MountTravelDecision.REFUSE_CROSS_DIM_ANIMAL, msg, null, null, null);
        }

        static MountTravelResult tethered(Entity animal, BlockPos fencePos, String dimName, String msg) {
            return new MountTravelResult(MountTravelDecision.TETHERED_CROSS_DIM, msg, animal, fencePos, dimName);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Main evaluation entry point
    // ════════════════════════════════════════════════════════════════════

    /**
     * Evaluate what should happen with the bot's current mount before travel.
     *
     * @param bot           the traveling bot
     * @param destination   target block position
     * @param destDimension target dimension registry key
     * @param destWorld     resolved destination ServerWorld (may be null if dimension unloaded)
     * @return result describing the decision and any relevant context
     */
    public static MountTravelResult evaluateTravel(ServerPlayerEntity bot, BlockPos destination,
                                                    RegistryKey<World> destDimension, ServerWorld destWorld) {
        if (bot == null || !bot.hasVehicle()) {
            return MountTravelResult.proceed();
        }

        Entity vehicle = bot.getVehicle();
        if (vehicle == null) {
            return MountTravelResult.proceed();
        }

        // Mark mount as persistent immediately to prevent despawn
        ensureMountPersistence(vehicle);

        boolean crossDim = !bot.getEntityWorld().getRegistryKey().equals(destDimension);

        // Non-living vehicles (boats, minecarts): collect as items
        if (vehicle instanceof AbstractBoatEntity || vehicle instanceof AbstractMinecartEntity) {
            return tryCollectVehicle(bot, vehicle);
        }

        // Living mounts (horses, donkeys, camels, pigs, striders, etc.)
        if (vehicle instanceof LivingEntity) {
            if (crossDim) {
                return tryTetherForCrossDim(bot, vehicle);
            }
            // Same-dimension: check if destination has room for the animal
            if (destWorld != null && hasAnimalRoom(destWorld, destination, vehicle)) {
                String name = vehicle.getName().getString();
                LOGGER.info("Animal '{}' will co-teleport with {} to {}", name, bot.getName().getString(), destination.toShortString());
                return MountTravelResult.withAnimal(vehicle,
                        "Your mount " + name + " will travel with you.");
            }
            int needed = requiredHeadroom(vehicle);
            return MountTravelResult.refuseNoRoom(
                    "Not enough room at the destination for your mount (" + needed + " blocks of headroom needed). " +
                    "Dismount first or choose a more open destination.");
        }

        // Unknown vehicle type — proceed without it
        bot.stopRiding();
        return MountTravelResult.proceed();
    }

    // ════════════════════════════════════════════════════════════════════
    //  Vehicle collection (boats, minecarts → items)
    // ════════════════════════════════════════════════════════════════════

    private static MountTravelResult tryCollectVehicle(ServerPlayerEntity bot, Entity vehicle) {
        Item item = vehicleToItem(vehicle);
        if (item == null) {
            // Unrecognized vehicle — just dismount and proceed
            bot.stopRiding();
            vehicle.discard();
            return MountTravelResult.vehicleCollected("Dismounted from vehicle.");
        }

        // Check if there's room for the vehicle item BEFORE dismounting
        ItemStack vehicleStack = new ItemStack(item);
        if (!bot.getInventory().insertStack(vehicleStack)) {
            // Inventory full — leave everything in place
            return MountTravelResult.refuseInventory(
                    "Your inventory is full — couldn't store the " + item.getName().getString() + ". " +
                    "Free up inventory space and try again.");
        }

        // Item inserted successfully — now dismount and clean up
        bot.stopRiding();

        // Transfer container contents (chest boats, chest/hopper minecarts) to bot inventory
        transferVehicleContents(bot, vehicle);

        vehicle.discard();
        LOGGER.info("{} collected vehicle as item: {}", bot.getName().getString(), item);
        return MountTravelResult.vehicleCollected(
                "Collected your " + item.getName().getString() + " for the journey.");
    }

    private static void transferVehicleContents(ServerPlayerEntity bot, Entity vehicle) {
        Inventory inv = null;
        if (vehicle instanceof ChestBoatEntity chestBoat) {
            inv = chestBoat;
        } else if (vehicle instanceof Inventory containerVehicle) {
            inv = containerVehicle;
        }
        if (inv == null || inv.isEmpty()) {
            return;
        }
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            if (!bot.getInventory().insertStack(copy)) {
                // Couldn't fit — drop on ground
                bot.dropItem(copy, false);
            }
            inv.setStack(i, ItemStack.EMPTY);
        }
        LOGGER.info("Transferred vehicle container contents for {}", bot.getName().getString());
    }

    // ════════════════════════════════════════════════════════════════════
    //  Vehicle → Item mapping
    // ════════════════════════════════════════════════════════════════════

    private static Item vehicleToItem(Entity vehicle) {
        // Boats — use instanceof since entity types are per-variant in this MC version.
        // Chest boats must be checked first (ChestBoatEntity extends BoatEntity).
        if (vehicle instanceof ChestBoatEntity) return Items.OAK_CHEST_BOAT;
        if (vehicle instanceof AbstractBoatEntity) return Items.OAK_BOAT;

        // Minecarts
        EntityType<?> type = vehicle.getType();
        if (type == EntityType.MINECART) return Items.MINECART;
        if (type == EntityType.CHEST_MINECART) return Items.CHEST_MINECART;
        if (type == EntityType.HOPPER_MINECART) return Items.HOPPER_MINECART;
        if (type == EntityType.FURNACE_MINECART) return Items.FURNACE_MINECART;
        if (type == EntityType.TNT_MINECART) return Items.TNT_MINECART;
        if (type == EntityType.COMMAND_BLOCK_MINECART) return Items.COMMAND_BLOCK_MINECART;

        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Animal room check at destination
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if the destination area has enough headroom for the given animal.
     * Searches the destination block and a 3-block radius spiral for a valid spot.
     */
    static boolean hasAnimalRoom(ServerWorld world, BlockPos destination, Entity animal) {
        return findSafeAnimalSpot(world, destination, animal) != null;
    }

    /**
     * Find a safe position near {@code destination} with enough headroom for the animal.
     * Searches a 3-block horizontal radius and ±2 vertical range.
     *
     * @return a safe BlockPos, or null if none found
     */
    static BlockPos findSafeAnimalSpot(ServerWorld world, BlockPos destination, Entity animal) {
        if (world == null || destination == null || animal == null) {
            return null;
        }
        int needed = requiredHeadroom(animal);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = destination.add(dx, dy, dz);
                    if (hasHeadroom(world, candidate, needed)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns the number of clear blocks above ground needed for this entity.
     * Tall mounts (horses, camels, donkeys, mules, llamas): 3 blocks.
     * Short mounts (pigs, striders): 2 blocks.
     */
    private static int requiredHeadroom(Entity animal) {
        // AbstractHorseEntity covers horse, donkey, mule, skeleton horse, zombie horse, llama, trader llama
        if (animal instanceof AbstractHorseEntity || animal instanceof CamelEntity) {
            return 3;
        }
        return 2;
    }

    /**
     * Check if a position has solid floor and {@code height} clear blocks above it.
     */
    private static boolean hasHeadroom(ServerWorld world, BlockPos feet, int height) {
        // Need solid floor below feet
        if (world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) {
            return false;
        }
        // Need 'height' clear blocks starting at feet
        for (int y = 0; y < height; y++) {
            BlockPos check = feet.up(y);
            if (!world.getBlockState(check).getCollisionShape(world, check).isEmpty()) {
                return false;
            }
            if (!world.getFluidState(check).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Cross-dimension tethering
    // ════════════════════════════════════════════════════════════════════

    private static MountTravelResult tryTetherForCrossDim(ServerPlayerEntity bot, Entity vehicle) {
        bot.stopRiding();
        boolean tethered = RideSyncService.secureMountForTravel(bot, vehicle);
        if (tethered) {
            BlockPos pos = vehicle.getBlockPos();
            String dimName = vehicle.getEntityWorld().getRegistryKey().getValue().getPath();
            ensureMountPersistence(vehicle);
            MountPersistenceService.recordMount(bot, vehicle, false);
            LOGGER.info("{}'s mount tethered at {} in {} for cross-dim travel",
                    bot.getName().getString(), pos.toShortString(), dimName);
            return MountTravelResult.tethered(vehicle, pos, dimName,
                    "Your mount has been tethered to a fence at " + formatPos(pos) + " in the " + dimName + ". " +
                    "It will be waiting for you when you return.");
        }

        // Couldn't tether — refuse travel
        ensureMountPersistence(vehicle);
        MountPersistenceService.recordMount(bot, vehicle, false);
        return MountTravelResult.refuseCrossDim(
                "Cannot travel to another dimension while mounted — no fence available to tether your mount. " +
                "Place a fence nearby or dismount first.");
    }

    // ════════════════════════════════════════════════════════════════════
    //  Animal co-teleport
    // ════════════════════════════════════════════════════════════════════

    /**
     * Teleport an animal alongside the bot to the destination.
     * Finds a safe spot near the destination for the animal and places it there.
     */
    public static void teleportAnimalWithBot(ServerPlayerEntity bot, Entity animal,
                                              ServerWorld destWorld, BlockPos destination) {
        if (bot == null || animal == null || destWorld == null || destination == null) {
            return;
        }

        // Find a safe position near the destination for the animal
        BlockPos animalPos = findSafeAnimalSpot(destWorld, destination, animal);
        if (animalPos == null) {
            // Fallback: place at destination anyway (room was checked in evaluateTravel)
            animalPos = destination;
        }

        // Dismount if still riding
        if (bot.getVehicle() == animal) {
            bot.stopRiding();
        }

        // Teleport the animal
        animal.teleport(destWorld, animalPos.getX() + 0.5, animalPos.getY(), animalPos.getZ() + 0.5,
                java.util.Set.of(), animal.getYaw(), animal.getPitch(), true);

        ensureMountPersistence(animal);
        MountPersistenceService.recordMount(bot, animal, true);

        LOGGER.info("Teleported animal '{}' with {} to {}",
                animal.getName().getString(), bot.getName().getString(), animalPos.toShortString());
    }

    // ════════════════════════════════════════════════════════════════════
    //  Despawn prevention
    // ════════════════════════════════════════════════════════════════════

    /**
     * Co-teleport the bot's recorded mount to a new destination. Called from
     * far-distance teleport sites (fast travel, follow-teleport catch-up,
     * emergency rescue, /bot come) so the bot's horse/mount comes along
     * instead of being orphaned at the source. Without this, the saved
     * mount state drifts >400 blocks from the bot, RideSyncService rejects
     * with "state-too-far" forever, and the user reports "the horse
     * disappeared."
     *
     * <p>Looks up the mount in the bot's <em>current</em> world (i.e. before
     * the bot's own teleport completes), so the typical call order is:
     * <pre>
     *   TravelMountHandler.coTeleportSavedMount(bot, destWorld, destination);
     *   bot.teleport(destWorld, destX, destY, destZ, ...);
     * </pre>
     * No-op when the bot has no recorded mount or the mount entity isn't
     * currently loaded in the source world.
     */
    public static void coTeleportSavedMount(ServerPlayerEntity bot, ServerWorld destWorld, BlockPos destination) {
        if (bot == null || destWorld == null || destination == null) {
            return;
        }
        MountPersistenceService.MountState state = MountPersistenceService.getRecordedState(bot);
        if (state == null) {
            return;
        }
        // Resolve the mount's CURRENT world from the saved state, not from the
        // bot. The bot may already be in the destination world (fast-travel
        // post-spawn) or in a different dimension (cross-dim follow handoff).
        // Vanilla-friendly: the saved state's worldId is authoritative for
        // where the mount entity lives right now.
        net.minecraft.server.MinecraftServer server = bot.getCommandSource() != null
                ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        ServerWorld sourceWorld;
        try {
            RegistryKey<World> sourceKey = RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,
                    net.minecraft.util.Identifier.of(state.worldId()));
            sourceWorld = server.getWorld(sourceKey);
        } catch (Exception ignored) {
            sourceWorld = null;
        }
        if (sourceWorld == null) {
            // Fallback to bot's current world — handles legacy state with a
            // stale or unparseable worldId.
            sourceWorld = bot.getEntityWorld() instanceof ServerWorld bw ? bw : null;
        }
        if (sourceWorld == null) {
            return;
        }
        Entity mount = MountPersistenceService.findRecordedMount(sourceWorld, state);
        if (mount == null || mount.isRemoved()) {
            return;
        }
        BlockPos animalPos = findSafeAnimalSpot(destWorld, destination, mount);
        if (animalPos == null) {
            animalPos = destination;
        }
        // Dismount cleanly first so vanilla doesn't desync rider/vehicle.
        if (bot.getVehicle() == mount) {
            bot.stopRiding();
        }
        mount.teleport(destWorld,
                animalPos.getX() + 0.5,
                animalPos.getY(),
                animalPos.getZ() + 0.5,
                java.util.Set.of(),
                mount.getYaw(),
                mount.getPitch(),
                true);
        ensureMountPersistence(mount);
        // Refresh saved state — wasMounted=false because we just dismounted the
        // bot to keep entity sync clean across the teleport.
        MountPersistenceService.recordMount(bot, mount, false);
        LOGGER.info("Co-teleported mount {} to {} alongside bot {}",
                mount.getName().getString(), animalPos.toShortString(), bot.getName().getString());
    }

    /**
     * Mark a mount entity as persistent so it won't despawn.
     * Should be called at every entry point that touches mounts.
     */
    public static void ensureMountPersistence(Entity entity) {
        if (entity instanceof MobEntity mob && !mob.isPersistent()) {
            mob.setPersistent();
            LOGGER.debug("Marked mount {} as persistent", entity.getName().getString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
