package net.shasankp000.GameAI;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.block.BlockState;
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
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.DropSweepService;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.Entity.LookController;
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
        LOGGER.info("Drop sweep initiated with radius={}, vRange={}, maxTargets={}, duration={}ms",
                horizontalRadius, verticalRange, maxTargets, maxDurationMillis);
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            LOGGER.info("Bot is holding: {}", player.getMainHandStack().getItem().getName().getString());
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

        Set<ItemEntity> excludedDrops = new HashSet<>();

        while (attempts < maxTargets && System.currentTimeMillis() <= deadline) {
            if (isInventoryFull(player)) {
                boolean stored = attemptChestStore(source, player);
                if (stored && !isInventoryFull(player)) {
                    continue;
                }
                LOGGER.info("Drop sweep ending: inventory full and no chest storage available.");
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

            LOGGER.info("Found {} drops, closest is {}.", excludedDrops.size() + 1, describeDrop(targetDrop));

            BlockPos dropBlock = targetDrop.getBlockPos().toImmutable();
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
            MovementService.clearRecentWalkAttempt(player.getUuid());
            MovementService.MovementResult movement = MovementService.execute(source, player, plan, false, false, true, false);
            LOGGER.info("Drop sweep movement ({}) -> {}", plan.mode(), movement.detail());
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
                    LOGGER.info("Drop sweep movement ended {} blocks from {}", String.format("%.2f", Math.sqrt(distanceToDestinationSq)), checkPos);
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
                        LOGGER.warn("Drop sweep manual nudge near {} completed but item still present.", dropPos);
                    }
                } else {
                    LOGGER.warn("Drop sweep manual nudge failed near {}", dropPos);
                }
            } else {
                LOGGER.warn("Drop sweep failed to approach {}: {}", dropPos, movement.detail());
            }
        }

        Vec3d finalPos = currentPosition(player);
        double movedDistance = finalPos.distanceTo(origin);
        LOGGER.debug("Drop sweep completed after {} attempt(s), total displacement {}m.", attempts, String.format("%.2f", movedDistance));
    }

    private static ItemEntity findClosestDrop(ServerPlayerEntity player, ServerWorld world, double radius, double verticalRange, Set<ItemEntity> excludedDrops) {
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        Box.of(currentPosition(player), radius * 2, verticalRange * 2, radius * 2),
                        drop -> !drop.isRemoved() && drop.isAlive() && !excludedDrops.contains(drop) && drop.squaredDistanceTo(player) > PICKUP_DISTANCE_SQUARED)
                .stream()
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
        PlayerInventory inventory = player.getInventory();
        int emptyCount = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptyCount++;
                if (emptyCount >= 3) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean attemptChestStore(ServerCommandSource source, ServerPlayerEntity player) {
        if (source == null || player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos origin = player.getBlockPos();
        for (BlockPos chestPos : findNearbyChests(world, origin, 12, 6)) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, player, chestPos, DropSweeper::isChestOffloadCandidate);
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
