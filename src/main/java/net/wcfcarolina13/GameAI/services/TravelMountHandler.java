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
        /** Mount tethered at source (cross-dim or same-dim with too-tight destination);
         *  bot proceeds alone, mount waits to be retrieved. */
        TETHERED_AT_SOURCE
    }

    public record MountTravelResult(
        MountTravelDecision decision,
        String message,
        Entity mountEntity,
        BlockPos tetherPos,
        String tetherLocation
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

        static MountTravelResult tethered(Entity animal, BlockPos fencePos, String location, String msg) {
            return new MountTravelResult(MountTravelDecision.TETHERED_AT_SOURCE, msg, animal, fencePos, location);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Main evaluation entry point
    // ════════════════════════════════════════════════════════════════════

    /**
     * Evaluate what should happen with the bot's current mount before travel.
     *
     * <p><b>Dismount invariant — load-bearing across all decisions:</b> any decision
     * that results in the bot teleporting <em>without</em> bringing the mount must
     * dismount the bot first ({@code bot.stopRiding()}) so there's no dangling
     * rider/vehicle state pointing at a mount that won't follow. Code paths today:</p>
     *
     * <ul>
     *   <li>{@code PROCEED_WITH_ANIMAL} — caller dismounts before discarding the mount
     *       entity (see {@code NavigationArtifactService.completePostSpawnSetup} which
     *       recreates the mount at destination).</li>
     *   <li>{@code TETHERED_AT_SOURCE} — {@code tryTetherForCrossDim} and
     *       {@code tryTetherAtSourceForSameDim} both call {@code bot.stopRiding()}
     *       unconditionally before the secure attempt.</li>
     *   <li>{@code PROCEED_VEHICLE_COLLECTED} — non-living vehicle is converted to an
     *       inventory item; rider relationship is implicitly cleared.</li>
     *   <li>Refusals ({@code REFUSE_*}) — bot doesn't teleport, stays mounted.</li>
     * </ul>
     *
     * <p>If you add a new decision that proceeds without the mount, dismount first.</p>
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
            // Destination too tight for the mount. Rather than refusing outright,
            // try to secure the mount at source first: tether to a nearby fence (or
            // place one) so the bot can travel alone with the mount safely behind.
            // Only refuse if even that fails (no lead, no nearby leashable mob, or
            // no place to put a fence).
            return tryTetherAtSourceForSameDim(bot, vehicle);
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
     * Find a safe position near {@code destination} where the animal's actual
     * bounding box fits without colliding with any block. Searches a 3-block
     * horizontal radius and ±2 vertical range. Prefers the destination cell and
     * same-Y candidates first so we don't drift the animal up/down when a flush
     * placement is available.
     *
     * <p>Uses {@link ServerWorld#isSpaceEmpty(Entity, net.minecraft.util.math.Box)}
     * with the animal's real bbox instead of per-cell collision-shape testing —
     * the per-cell approach was missing animal-bbox-width collisions (e.g. a
     * horse is 1.4 blocks wide and clips the corner of an adjacent tree trunk
     * even when its own cell is clear).</p>
     *
     * @return a safe BlockPos, or null if none found
     */
    static BlockPos findSafeAnimalSpot(ServerWorld world, BlockPos destination, Entity animal) {
        if (world == null || destination == null || animal == null) {
            return null;
        }
        // Try destination first, then spiral outward by manhattan distance so we
        // pick the closest safe cell rather than the lexicographically first one.
        for (int r = 0; r <= 3; r++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int sign : new int[] { 1, -1 }) {
                    int y = dy * sign;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            BlockPos candidate = destination.add(dx, y, dz);
                            if (isSafeAnimalSpot(world, candidate, animal)) {
                                return candidate;
                            }
                        }
                    }
                    if (dy == 0) break; // (+0) and (-0) are the same
                }
            }
        }
        return null;
    }

    /**
     * Returns the number of clear blocks above ground needed for this entity.
     * Tall mounts (horses, camels, donkeys, mules, llamas): 3 blocks.
     * Short mounts (pigs, striders): 2 blocks. Used only for user-facing
     * messages — actual placement check uses the entity's real bbox via
     * {@link #isSafeAnimalSpot}.
     */
    private static int requiredHeadroom(Entity animal) {
        if (animal instanceof AbstractHorseEntity || animal instanceof CamelEntity) {
            return 3;
        }
        return 2;
    }

    /**
     * True if the animal can be placed at {@code feet} without clipping into
     * any block and with solid footing beneath. Uses the animal's actual bbox
     * via {@link ServerWorld#isSpaceEmpty}.
     */
    private static boolean isSafeAnimalSpot(ServerWorld world, BlockPos feet, Entity animal) {
        // Need solid floor below feet.
        BlockPos below = feet.down();
        if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        // Build the bbox the animal would occupy standing at (feet.x+0.5, feet.y, feet.z+0.5).
        double w = animal.getWidth();
        double h = animal.getHeight();
        double cx = feet.getX() + 0.5;
        double cy = feet.getY();
        double cz = feet.getZ() + 0.5;
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                cx - w / 2.0, cy, cz - w / 2.0,
                cx + w / 2.0, cy + h, cz + w / 2.0);
        // Exclude the animal itself so its current position doesn't count as a collision.
        if (!world.isSpaceEmpty(animal, box)) {
            return false;
        }
        // Reject fluid cells (no swimming the horse into source-block water/lava).
        int hCeil = (int) Math.ceil(h);
        for (int y = 0; y < hCeil; y++) {
            if (!world.getFluidState(feet.up(y)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Cross-dimension tethering
    // ════════════════════════════════════════════════════════════════════

    /**
     * Same-dim variant of {@link #tryTetherForCrossDim}. Called when the destination
     * doesn't have room for the mount and we'd otherwise have to refuse the travel
     * outright. Tries to secure the mount at the current location (tether to nearby
     * fence, or place one); if successful, the bot proceeds alone and the mount waits
     * to be retrieved. If we can't tether (no lead, no fence, can't place one), falls
     * back to the original refuse.
     *
     * <p><b>Dismount invariant:</b> {@code bot.stopRiding()} runs unconditionally at
     * the top of this helper. Every code path that teleports the bot without bringing
     * the mount must dismount first — there must be no dangling rider/vehicle state
     * pointing at a mount that won't follow.</p>
     */
    private static MountTravelResult tryTetherAtSourceForSameDim(ServerPlayerEntity bot, Entity vehicle) {
        bot.stopRiding();
        boolean tethered = RideSyncService.secureMountForTravel(bot, vehicle);
        if (tethered) {
            BlockPos pos = vehicle.getBlockPos();
            ensureMountPersistence(vehicle);
            MountPersistenceService.recordMount(bot, vehicle, false);
            LOGGER.info("{}'s mount tethered at {} (destination too tight for co-teleport)",
                    bot.getName().getString(), pos.toShortString());
            return MountTravelResult.tethered(vehicle, pos, "current location",
                    "Your mount has been tethered at " + formatPos(pos) + ". " +
                    "Pick them up when you return — the destination was too tight for them to come along.");
        }
        // Couldn't tether — refuse the travel. Bot is already dismounted at this point
        // (stopRiding above), so it's safe for the user to either manually remount or
        // pick a clearer destination.
        int needed = requiredHeadroom(vehicle);
        ensureMountPersistence(vehicle);
        MountPersistenceService.recordMount(bot, vehicle, false);
        return MountTravelResult.refuseNoRoom(
                "Not enough room at the destination for your mount (" + needed + " blocks of headroom needed) " +
                "and I couldn't tether it (no fence within reach, no lead, or no spot to place a fence). " +
                "Build a fence nearby, equip a lead, or pick a more open destination.");
    }

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

        // Find a safe position near the destination for the animal. If none
        // exists in the search radius, skip the animal teleport rather than
        // placing it in a wall — better to leave the animal at source than
        // suffocate it at destination.
        BlockPos animalPos = findSafeAnimalSpot(destWorld, destination, animal);
        if (animalPos == null) {
            LOGGER.warn("teleportAnimalWithBot: no safe spot for {} near {} — skipping animal teleport",
                    animal.getName().getString(), destination.toShortString());
            return;
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
     *   if (TravelMountHandler.coTeleportSavedMount(bot, destWorld, destination)) {
     *       bot.teleport(destWorld, destX, destY, destZ, ...);
     *   }
     * </pre>
     *
     * <p><b>Return value:</b> {@code true} if the bot's teleport may proceed
     * (no mount to bring, stowaway-gated, or mount placed successfully).
     * {@code false} if the mount needed placement but no safe spot was found
     * within the search radius — the caller MUST skip its own
     * {@code bot.teleport(...)} so the bot doesn't strand at a destination
     * unsuitable for its mount. Without this gate, the user-visible failure
     * mode was the bot teleporting alone while the horse stayed at source,
     * or (pre-1.1.117) the horse being placed in a wall and suffocating.</p>
     *
     * <p><b>Stowaway gate:</b> only co-teleports if the bot is actually
     * connected to the mount — either riding it ({@code wasMounted}) or
     * holding its lead ({@code heldByBot}). Without this gate, every
     * teleport callsite silently dragged the recorded mount along even after
     * the bot had dismounted and stopped tracking it. The recorded state
     * intentionally outlives dismount so the rejoin path can put the horse
     * back under the bot — but rejoin and teleport-with-mount are different
     * operations and this gate keeps them separate.</p>
     */
    public static boolean coTeleportSavedMount(ServerPlayerEntity bot, ServerWorld destWorld, BlockPos destination) {
        if (bot == null || destWorld == null || destination == null) {
            return true;
        }
        MountPersistenceService.MountState state = MountPersistenceService.getRecordedState(bot);
        if (state == null) {
            return true;
        }
        // Stowaway gate — see Javadoc above.
        if (!state.wasMounted() && !state.heldByBot()) {
            LOGGER.debug("coTeleportSavedMount skip: bot={} mount={} reason=not-actively-connected (wasMounted=false heldByBot=false)",
                    bot.getName().getString(), state.mountUuid());
            return true;
        }
        // Resolve the mount's CURRENT world from the saved state, not from the
        // bot. The bot may already be in the destination world (fast-travel
        // post-spawn) or in a different dimension (cross-dim follow handoff).
        // Vanilla-friendly: the saved state's worldId is authoritative for
        // where the mount entity lives right now.
        net.minecraft.server.MinecraftServer server = bot.getCommandSource() != null
                ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return true;
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
            return true;
        }
        Entity mount = MountPersistenceService.findRecordedMount(sourceWorld, state);
        if (mount == null || mount.isRemoved()) {
            // Mount already despawned/gone — nothing to co-teleport, bot may proceed.
            return true;
        }
        BlockPos animalPos = findSafeAnimalSpot(destWorld, destination, mount);
        if (animalPos == null) {
            // CRITICAL: no safe placement for the mount at the destination. Refuse
            // the whole teleport — placing the mount in a wall kills it, leaving
            // it at source orphans it. The caller MUST skip bot.teleport on false
            // so the bot stays with the mount until a clearer destination is
            // available. Follow-catch-up re-fires next tick; one-shot callers
            // (/bot come, fast travel, emergency rescue) surface a message.
            LOGGER.info("coTeleportSavedMount: no safe spot for {} near {} — aborting bot teleport so the pair stays together",
                    mount.getName().getString(), destination.toShortString());
            return false;
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
        return true;
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
