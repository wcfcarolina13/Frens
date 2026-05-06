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
        LOGGER.debug("Drop sweep initiated with radius={}, vRange={}, maxTargets={}, duration={}ms",
                horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            LOGGER.debug("Drop sweep bot is holding: {}", player.getMainHandStack().getItem().getName().getString());
        }
        if (player == null) {
            LOGGER.debug("Drop sweep skipped: no bot player available.");
            return;
        }
        ServerWorld world = source.getWorld();
        if (world == null) {
            LOGGER.debug("Drop sweep skipped: no server world on source.");
            return;
        }

        // Store original teleport preference and set drop sweep preference
        boolean originalTeleport = SkillPreferences.teleportDuringSkills(player);
        boolean dropSweepTeleport = SkillPreferences.teleportDuringDropSweep(player);
        SkillPreferences.setTeleportDuringSkills(player.getUuid(), dropSweepTeleport);
        
        try {
            performSweep(source, player, world, horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
        } finally {
            // Restore original teleport preference
            SkillPreferences.setTeleportDuringSkills(player.getUuid(), originalTeleport);
        }
    }

    private static void performSweep(ServerCommandSource source,
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
        }

        Vec3d finalPos = currentPosition(player);
        double movedDistance = finalPos.distanceTo(origin);
        LOGGER.debug("Drop sweep completed after {} attempt(s), total displacement {}m.", attempts, String.format("%.2f", movedDistance));
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

    private static boolean attemptChestStore(ServerCommandSource source, ServerPlayerEntity player) {
        if (source == null || player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
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
        BlockPos placed = ChestStoreService.placeChestNearBot(source, player, false);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, placed, DropSweeper::isChestOffloadCandidate);
            return moved > 0;
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
            CompletableFuture<Void> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    LookController.faceBlock(player, dropPos);
                    BotActions.sneak(player, false);
                    BlockPos current = player.getBlockPos();
                    int dy = dropPos.getY() - current.getY();
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
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            };

            server.execute(task);

            try {
                future.get(250, TimeUnit.MILLISECONDS);
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
