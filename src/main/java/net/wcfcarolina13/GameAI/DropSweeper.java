package net.wcfcarolina13.GameAI;

import net.minecraft.entity.ItemEntity;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.DropSweepService;
import net.wcfcarolina13.GameAI.services.DropReachPolicy;
import net.wcfcarolina13.GameAI.services.DropStandHelper;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.BundleService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility that walks the bot over to nearby item entities so drops from recent
 * tasks are gathered before the bot resumes other duties.
 */
public final class DropSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger("drop-sweeper");
    private static final double PICKUP_DISTANCE_SQUARED = 1.0 * 1.0;

    private DropSweeper() {
    }

    public static void sweep(ServerCommandSource source,
                             double horizontalRadius,
                             double verticalRange,
                             int maxTargets,
                             long maxDurationMillis) {
        sweepCounted(source, horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
    }

    /**
     * Same as {@link #sweep} but returns how many items (stack counts, not entities) the bot
     * actually picked up from the drops it targeted. Standing within pickup distance of a drop
     * does not count; only a drop that shrank or vanished does.
     */
    public static int sweepCounted(ServerCommandSource source,
                                   double horizontalRadius,
                                   double verticalRange,
                                   int maxTargets,
                                   long maxDurationMillis) {
        LOGGER.debug("Drop sweep initiated with radius={}, vRange={}, maxTargets={}, duration={}ms",
                horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            LOGGER.debug("Drop sweep bot is holding: {}", player.getMainHandStack().getItem().getName().getString());
        }
        if (player == null) {
            LOGGER.debug("Drop sweep skipped: no bot player available.");
            return 0;
        }
        ServerWorld world = source.getWorld();
        if (world == null) {
            LOGGER.debug("Drop sweep skipped: no server world on source.");
            return 0;
        }

        // Store original teleport preference and set drop sweep preference
        boolean originalTeleport = SkillPreferences.teleportDuringSkills(player);
        boolean dropSweepTeleport = SkillPreferences.teleportDuringDropSweep(player);
        SkillPreferences.setTeleportDuringSkills(player.getUuid(), dropSweepTeleport);
        
        try {
            return performSweep(source, player, world, horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
        } finally {
            // Restore original teleport preference
            SkillPreferences.setTeleportDuringSkills(player.getUuid(), originalTeleport);
        }
    }

    private static int performSweep(ServerCommandSource source,
                                     ServerPlayerEntity player,
                                     ServerWorld world,
                                     double horizontalRadius,
                                     double verticalRange,
                                     int maxTargets,
                                     long maxDurationMillis) {

        long deadline = maxDurationMillis > 0 ? System.currentTimeMillis() + maxDurationMillis : Long.MAX_VALUE;
        int attempts = 0;
        Vec3d origin = currentPosition(player);
        ServerPlayerEntity commander = resolveCommanderForBundle(player, source);

        Set<ItemEntity> excludedDrops = new HashSet<>();
        int pickedUp = 0;

        while (attempts < maxTargets && System.currentTimeMillis() <= deadline) {
            if (isInventoryFull(player)) {
                if (ensureSpaceForDropSweep(source, player, commander)) {
                    continue;
                }
                LOGGER.info("Drop sweep ending: inventory full and no storage/drop options available.");
                break;
            }
            if (DropSweepService.shouldAbort(player)) {
                LOGGER.info("Drop sweep aborted after {} attempt(s).", attempts);
                break;
            }
            ItemEntity targetDrop = findClosestDrop(player, world, horizontalRadius, verticalRange, excludedDrops);
            if (targetDrop == null) {
                LOGGER.debug("Drop sweep finished: no drops within radius {}.", horizontalRadius);
                break;
            }

            BlockPos dropBlock = targetDrop.getBlockPos().toImmutable();
            LOGGER.debug("Drop sweep target {} selected near {}", describeDrop(targetDrop), dropBlock.toShortString());
            excludedDrops.add(targetDrop);

            double distanceSq = player.squaredDistanceTo(targetDrop);
            if (distanceSq <= PICKUP_DISTANCE_SQUARED) {
                LOGGER.debug("Drop sweep skipping {}m item already within reach: {}",
                        String.format("%.2f", Math.sqrt(distanceSq)), describeDrop(targetDrop));
                attempts++;
                continue;
            }

            BlockPos dropPos = dropBlock;
            int countBefore = targetDrop.getStack().getCount();
            MovementService.MovementOptions options = MovementService.MovementOptions.lootCollection();
            Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(player, dropPos, options);
            if (planOpt.isEmpty()) {
                LOGGER.debug("Drop sweep skipping {}: no viable approach plan", describeDrop(targetDrop));
                attempts++;
                continue;
            }

            MovementService.MovementPlan plan = planOpt.get();
            // Avoid teleport/snap during cleanup so we don't "yank" the bot around while staying in survival mechanics.
            // Use fastReplan=true to keep movement attempts short (5s budget) — prevents the bot from
            // getting stuck in nudge retry loops for minutes on unreachable drops.
            MovementService.clearRecentWalkAttempt(player.getUuid());
            MovementService.MovementResult movement = MovementService.execute(source, player, plan, false, true, true, false);
            if (movement.success()) {
                LOGGER.debug("Drop sweep movement ({}) -> {}", plan.mode(), movement.detail());
            } else {
                LOGGER.info("Drop sweep approach failed at {} ({})", dropPos.toShortString(), movement.detail());
            }
            BotActions.stop(player);
            attempts++;

            boolean success = movement.success();
            BlockPos checkPos = movement.arrivedAt() != null ? movement.arrivedAt() : dropPos;
            if (success) {
                double distanceToDestinationSq = player.squaredDistanceTo(
                        checkPos.getX() + 0.5,
                        checkPos.getY() + 0.5,
                        checkPos.getZ() + 0.5
                );
                if (distanceToDestinationSq > 9.0) {
                    success = false;
                    LOGGER.debug("Drop sweep movement ended {} blocks from {}", String.format("%.2f", Math.sqrt(distanceToDestinationSq)), checkPos);
                }
            }

            boolean itemCollected = targetDrop.isRemoved()
                    || player.squaredDistanceTo(dropPos.getX() + 0.5, dropPos.getY(), dropPos.getZ() + 0.5) <= PICKUP_DISTANCE_SQUARED;

            if (success && itemCollected) {
                LOGGER.info("Drop sweep collected {} ({})", describeDrop(targetDrop), movement.detail());
            } else if (success) {
                LOGGER.info("Drop sweep reached {} but item still present ({}). Nudging for pickup.", checkPos, movement.detail());
                boolean nudged = attemptManualNudge(player, targetDrop, dropPos);
                if (nudged) {
                    if (targetDrop.isRemoved() || player.squaredDistanceTo(targetDrop) <= PICKUP_DISTANCE_SQUARED) {
                        LOGGER.info("Drop sweep manual nudge collected {} near {}", describeDrop(targetDrop), dropPos);
                    } else {
                        LOGGER.info("Drop sweep manual nudge near {} completed but item still present.", dropPos);
                    }
                } else {
                    LOGGER.info("Drop sweep manual nudge failed near {}", dropPos);
                }
                // If item is trapped inside a leaf block within reach, break the leaf to free it.
                if (!targetDrop.isRemoved() && player.getEntityWorld() instanceof ServerWorld sweepWorld) {
                    BlockPos itemBlock = targetDrop.getBlockPos();
                    if (sweepWorld.getBlockState(itemBlock).isIn(BlockTags.LEAVES)
                            && itemBlock.getY() - player.getBlockY() <= 2) {
                        LOGGER.info("Drop sweep: breaking leaf at {} to free trapped item", itemBlock.toShortString());
                        net.wcfcarolina13.PlayerUtils.MiningTool.mineBlock(player, itemBlock);
                    }
                }
            } else {
                LOGGER.info("Drop sweep failed to approach {}: {}", dropPos, movement.detail());
            }
            pickedUp += pickedUpFrom(targetDrop, countBefore);
        }

        Vec3d finalPos = currentPosition(player);
        double movedDistance = finalPos.distanceTo(origin);
        LOGGER.debug("Drop sweep completed after {} attempt(s), total displacement {}m, picked up {} item(s).",
                attempts, String.format("%.2f", movedDistance), pickedUp);
        return pickedUp;
    }

    /**
     * Items taken from {@code drop} since its count was {@code countBefore}: the whole stack if the
     * entity is gone, otherwise how much it shrank (a partial pickup into a nearly full inventory).
     * A drop that merged into a neighbouring stack also reads as gone.
     */
    public static int pickedUpFrom(ItemEntity drop, int countBefore) {
        if (drop == null || countBefore <= 0) {
            return 0;
        }
        int after = drop.isRemoved() ? 0 : drop.getStack().getCount();
        return Math.max(0, countBefore - after);
    }

    private static ItemEntity findClosestDrop(ServerPlayerEntity player, ServerWorld world, double radius, double verticalRange, Set<ItemEntity> excludedDrops) {
        Vec3d eyePos = player.getEyePos();
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        Box.of(currentPosition(player), radius * 2, verticalRange * 2, radius * 2),
                        drop -> !drop.isRemoved() && drop.isAlive() && !excludedDrops.contains(drop)
                                && drop.squaredDistanceTo(player) > PICKUP_DISTANCE_SQUARED
                                && drop.getBlockY() - player.getBlockY() <= 4) // skip unreachable elevated drops
                .stream()
                // Back off from drops near a real player who just broke a block.
                .filter(drop -> !net.wcfcarolina13.GameAI.services.CommanderActivityService
                        .isDropNearActiveMiner(world, drop))
                // Skip items the bot itself dropped via dropCheapStackForSpace within
                // the last 5 minutes — fixes the inter-sweep cobblestone loop where the
                // bot would drop junk to make room then immediately re-acquire it on the
                // next sweep tick. The TTL is per-bot, per-item-entity, self-evicting.
                .filter(drop -> !net.wcfcarolina13.GameAI.services.CraftingHelper
                        .isRecentlySelfDropped(player, drop))
                // Skip items in a 1-high slot with no 2-high stand at or beside them:
                // the bot cannot fit there and nudging in suffocates it (FRESH_INSTALL #10).
                // Leaf-trapped items pass: the sweep breaks the leaf to free them.
                .filter(drop -> world.getBlockState(drop.getBlockPos()).isIn(BlockTags.LEAVES)
                        || DropStandHelper.hasTwoHighStandAtOrBeside(world, drop.getBlockPos()))
                .filter(drop -> {
                    // Skip items behind solid blocks — raycast from bot eye to item.
                    Vec3d dropPos = new Vec3d(drop.getX(), drop.getY(), drop.getZ());
                    net.minecraft.world.RaycastContext ctx = new net.minecraft.world.RaycastContext(
                            eyePos, dropPos,
                            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                            net.minecraft.world.RaycastContext.FluidHandling.NONE,
                            player);
                    net.minecraft.util.hit.BlockHitResult hit = world.raycast(ctx);
                    if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                        double hitDistSq = hit.getPos().squaredDistanceTo(dropPos);
                        return hitDistSq <= 4.0; // within 2 blocks of item = probably reachable
                    }
                    return true;
                })
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    /**
     * Nudge safety: the cell the bot steps into (facing direction; one up for a jump onto a
     * step) must fit it 2-high, and for a drop below, the landing cell at the item must too.
     * Called on the server thread, where the nudge itself runs.
     */
    private static boolean isNudgeSafe(ServerWorld world, ServerPlayerEntity player, BlockPos current, BlockPos dropPos, int dy) {
        BlockPos next = current.offset(player.getHorizontalFacing());
        if (dy > 0) {
            next = next.up();
        }
        if (!DropReachPolicy.isNudgeCellSafe(DropStandHelper.isCellPassable(world, next), DropStandHelper.isCellPassable(world, next.up()))) {
            return false;
        }
        if (dy < 0) {
            return DropReachPolicy.isNudgeCellSafe(DropStandHelper.isCellPassable(world, dropPos), DropStandHelper.isCellPassable(world, dropPos.up()));
        }
        return true;
    }

    private static String describeDrop(ItemEntity entity) {
        Text name = entity.getStack().getName();
        return name == null ? entity.getStack().toString() : name.getString();
    }

    private static Vec3d currentPosition(ServerPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static boolean isInventoryFull(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return player.getInventory().getEmptySlot() == -1;
    }

    /** Bots whose current sweep must not store into, or place, chests or drop items (death retrieval). */
    private static final java.util.Set<java.util.UUID> NO_OFFLOAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** While set, a full inventory only packs bundles: no chest store, no crafting table, no dropping. */
    public static void setNoOffload(java.util.UUID botUuid, boolean noOffload) {
        if (botUuid == null) return;
        if (noOffload) NO_OFFLOAD.add(botUuid); else NO_OFFLOAD.remove(botUuid);
    }

    public static boolean ensureSpaceForDropSweep(ServerCommandSource source, ServerPlayerEntity player) {
        return ensureSpaceForDropSweep(source, player, resolveCommanderForBundle(player, source));
    }

    private static boolean ensureSpaceForDropSweep(ServerCommandSource source,
                                                   ServerPlayerEntity player,
                                                   ServerPlayerEntity commander) {
        if (player == null) {
            return false;
        }
        if (!isInventoryFull(player)) {
            return true;
        }
        if (BundleService.packInventory(source, player, commander) && !isInventoryFull(player)) {
            return true;
        }
        if (NO_OFFLOAD.contains(player.getUuid())) {
            LOGGER.info("Drop sweep: {} is full and offload is off for this run; stopping.", player.getName().getString());
            return false;
        }
        boolean chestStoreSucceeded = false;
        if (source != null) {
            boolean stored = attemptChestStore(source, player);
            if (stored && !isInventoryFull(player)) {
                return true;
            }
            chestStoreSucceeded = stored;
        }
        maybePlaceCraftingTableForBundle(player, source);
        if (!isInventoryFull(player)) {
            return true;
        }
        // Only drop items if chest storage partially worked (some items offloaded).
        // If no viable offload target exists, dropping is futile — DropSweeper will
        // immediately re-acquire the dropped items, causing an infinite cycle.
        if (!chestStoreSucceeded) {
            return false;
        }
        java.util.Set<net.minecraft.item.Item> reserved = java.util.Set.of(
                Items.LEATHER,
                Items.STRING,
                Items.RABBIT_HIDE
        );
        boolean dropped = CraftingHelper.dropCheapStackForSpace(player, source, reserved);
        if (!dropped) {
            return false;
        }
        BundleService.packInventory(source, player, commander);
        return !isInventoryFull(player);
    }

    private static void maybePlaceCraftingTableForBundle(ServerPlayerEntity player, ServerCommandSource source) {
        if (player == null || source == null) {
            return;
        }
        if (player.getInventory().getEmptySlot() != -1) {
            return;
        }
        if (!hasItem(player, Items.CRAFTING_TABLE)) {
            return;
        }
        CraftingHelper.ensureCraftingStation(player, source);
    }

    /**
     * Offloads into an <em>already existing</em> nearby chest only; never places a new chest.
     * <p>
     * Extracted from {@link #attemptChestStore} so tick-driven callers (e.g. travel-wait) can reuse
     * the existing-chest half without inheriting the place-a-chest fall-through.
     *
     * @return true if anything was actually stored.
     */
    public static boolean attemptChestStoreExistingOnly(ServerCommandSource source, ServerPlayerEntity player) {
        if (source == null || player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld)) {
            return false;
        }
        for (ChestStoreService.StorageChestCandidate candidate : ChestStoreService.listDepositChestCandidates(
                source,
                player,
                null,
                12,
                6,
                140.0D * 140.0D)) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, candidate.pos(), DropSweeper::isChestOffloadCandidate);
            if (moved > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean attemptChestStore(ServerCommandSource source, ServerPlayerEntity player) {
        if (source == null || player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (attemptChestStoreExistingOnly(source, player)) {
            return true;
        }
        BlockPos placed = ChestStoreService.placeChestNearBot(source, player, false);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, placed, DropSweeper::isChestOffloadCandidate);
            if (moved > 0) {
                return true;
            }
        }
        // No chest found and none could be placed (or the placed one took nothing): give cheap
        // fuel items to a nearby furnace instead of dropping them.
        if (net.wcfcarolina13.GameAI.services.FurnaceOffloadService.depositFuel(source, player, 12)) {
            var srv = source.getServer();
            if (srv != null) {
                srv.execute(() -> net.wcfcarolina13.ChatUtils.ChatUtils.sendSystemMessage(source,
                        "No chest nearby - dropped fuel into a furnace instead."));
            }
            return true;
        }
        return false;
    }

    private static boolean isChestOffloadCandidate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isOf(Items.CHEST) || stack.isOf(Items.CRAFTING_TABLE)) {
            return false;
        }
        return stack.getMaxCount() > 1;
    }

    private static java.util.List<BlockPos> findNearbyChests(ServerWorld world, BlockPos origin, int radius, int vertical) {
        if (world == null || origin == null) {
            return java.util.List.of();
        }
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -vertical, -radius), origin.add(radius, vertical, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                found.add(pos.toImmutable());
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return found;
    }

    private static ServerPlayerEntity resolveCommanderForBundle(ServerPlayerEntity bot, ServerCommandSource source) {
        if (bot == null) {
            return null;
        }
        if (source != null && source.getPlayer() != null && source.getPlayer() != bot) {
            return source.getPlayer();
        }
        MinecraftServer server = source != null ? source.getServer() : bot.getCommandSource().getServer();
        if (server == null) {
            return null;
        }
        java.util.UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget != null) {
            ServerPlayerEntity commander = server.getPlayerManager().getPlayer(followTarget);
            if (commander != null && !commander.isRemoved()) {
                return commander;
            }
        }
        return null;
    }

    private static boolean hasItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
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

    public static boolean attemptManualNudge(ServerPlayerEntity player, ItemEntity targetDrop, BlockPos dropPos) {
        if (player == null || targetDrop == null || dropPos == null) {
            return false;
        }

        ServerWorld world = player.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            return false;
        }

        // Never block the server thread (sleeping / waiting) just to pick up loot.
        if (server.isOnThread()) {
            try {
                LookController.faceBlock(player, dropPos);
                BotActions.sneak(player, false);
                BlockPos current = player.getBlockPos();
                int dy = dropPos.getY() - current.getY();
                if (!isNudgeSafe(world, player, current, dropPos, dy)) {
                    LOGGER.info("Drop sweep nudge skipped near {}: no 2-high clearance", dropPos.toShortString());
                    return false;
                }
                if (dy > 0) {
                    BotActions.jumpForward(player);
                } else {
                    BotActions.moveForward(player);
                    if (dy <= 0) {
                        BotActions.jumpForward(player);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                BotActions.stop(player);
            }
            return targetDrop.isRemoved() || player.squaredDistanceTo(targetDrop) <= PICKUP_DISTANCE_SQUARED;
        }

        boolean collected = false;
        for (int attempt = 0; attempt < 3 && !collected; attempt++) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    LookController.faceBlock(player, dropPos);
                    BotActions.sneak(player, false);
                    BlockPos current = player.getBlockPos();
                    int dy = dropPos.getY() - current.getY();
                    // Clearance is read here, on the server thread, right before the step.
                    if (!isNudgeSafe(world, player, current, dropPos, dy)) {
                        future.complete(false);
                        return;
                    }
                    if (dy > 0) {
                        BotActions.jumpForward(player);
                    } else {
                        BotActions.moveForward(player);
                        if (dy < 0) {
                            BotActions.moveForward(player);
                        } else {
                            BotActions.jumpForward(player);
                        }
                    }
                    future.complete(true);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            };

            server.execute(task);

            try {
                if (!future.get(250, TimeUnit.MILLISECONDS)) {
                    // Item left where it is; the calling sweep moves on to the next target.
                    LOGGER.info("Drop sweep nudge skipped near {}: no 2-high clearance", dropPos.toShortString());
                    break;
                }
            } catch (Exception e) {
                LOGGER.warn("Drop sweep nudge attempt {} near {} errored: {}", attempt + 1, dropPos, e.getMessage());
                break;
            }

            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            BotActions.stop(player);

            collected = targetDrop.isRemoved() || player.squaredDistanceTo(targetDrop) <= PICKUP_DISTANCE_SQUARED;
            if (!collected) {
                double distance = Math.sqrt(player.squaredDistanceTo(targetDrop));
                LOGGER.debug("Drop sweep nudge attempt {} near {} still {} blocks away.",
                        attempt + 1,
                        dropPos,
                        String.format("%.2f", distance));
            }
        }

        return collected;
    }
}
