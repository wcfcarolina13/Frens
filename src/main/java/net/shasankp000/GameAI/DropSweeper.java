package net.shasankp000.GameAI;

import net.minecraft.entity.ItemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.PathFinding.GoTo;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

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
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            LOGGER.debug("Drop sweep skipped: no bot player available.");
            return;
        }
        ServerWorld world = source.getWorld();
        if (world == null) {
            LOGGER.debug("Drop sweep skipped: no server world on source.");
            return;
        }

        long deadline = maxDurationMillis > 0 ? System.currentTimeMillis() + maxDurationMillis : Long.MAX_VALUE;
        int attempts = 0;
        Vec3d origin = currentPosition(player);

        Map<BlockPos, Integer> dropAttempts = new HashMap<>();

        while (attempts < maxTargets && System.currentTimeMillis() <= deadline) {
            ItemEntity targetDrop = findClosestDrop(player, world, horizontalRadius, verticalRange);
            if (targetDrop == null) {
                LOGGER.debug("Drop sweep finished: no drops within radius {}.", horizontalRadius);
                break;
            }

            BlockPos dropBlock = targetDrop.getBlockPos().toImmutable();
            int tries = dropAttempts.getOrDefault(dropBlock, 0);
            if (tries >= 3) {
                LOGGER.info("Drop sweep skipping {} at {} after {} attempts.", describeDrop(targetDrop), dropBlock, tries);
                attempts++;
                continue;
            }

            dropAttempts.put(dropBlock, tries + 1);

            double distanceSq = player.squaredDistanceTo(targetDrop);
            if (distanceSq <= PICKUP_DISTANCE_SQUARED) {
                LOGGER.debug("Drop sweep skipping {}m item already within reach: {}",
                        String.format("%.2f", Math.sqrt(distanceSq)), describeDrop(targetDrop));
                attempts++;
                continue;
            }

            BlockPos dropPos = dropBlock;
            BlockPos destination = findPickupDestination(player, dropPos);
            if (destination == null) {
                LOGGER.debug("Drop sweep skipping {}: no standable block nearby", describeDrop(targetDrop));
                attempts++;
                continue;
            }

            LOGGER.info("Drop sweep heading to {} for {}", destination, describeDrop(targetDrop));
            String result = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
            attempts++;

            boolean success = isGoToSuccess(result);
            if (success) {
                double distanceToDestinationSq = player.squaredDistanceTo(destination.getX() + 0.5, destination.getY() + 0.5, destination.getZ() + 0.5);
                if (distanceToDestinationSq > 9.0) { // 3 blocks radius
                    success = false;
                    result = "Too far from destination (distance=" + String.format("%.2f", Math.sqrt(distanceToDestinationSq)) + ")";
                }
            }

            boolean itemCollected = targetDrop.isRemoved()
                    || player.squaredDistanceTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5) <= PICKUP_DISTANCE_SQUARED;

            if (success && itemCollected) {
                LOGGER.info("Drop sweep collected {} ({})", describeDrop(targetDrop), result);
                dropAttempts.remove(dropBlock);
            } else if (success) {
                LOGGER.info("Drop sweep reached {} but item still present ({}). Nudging for pickup.", destination, result);
                boolean nudged = attemptManualNudge(player, targetDrop, dropPos);
                if (nudged) {
                    if (targetDrop.isRemoved() || player.squaredDistanceTo(targetDrop) <= PICKUP_DISTANCE_SQUARED) {
                        LOGGER.info("Drop sweep manual nudge collected {} near {}", describeDrop(targetDrop), dropPos);
                        dropAttempts.remove(dropBlock);
                    } else {
                        LOGGER.warn("Drop sweep manual nudge near {} completed but item still present.", dropPos);
                    }
                } else {
                    LOGGER.warn("Drop sweep manual nudge failed near {}", dropPos);
                }
            } else {
                LOGGER.warn("Drop sweep failed to reach {}: {}", destination, result);
            }
        }

        Vec3d finalPos = currentPosition(player);
        double movedDistance = finalPos.distanceTo(origin);
        LOGGER.debug("Drop sweep completed after {} attempt(s), total displacement {}m.", attempts, String.format("%.2f", movedDistance));
    }

    private static ItemEntity findClosestDrop(ServerPlayerEntity player, ServerWorld world, double radius, double verticalRange) {
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        Box.of(currentPosition(player), radius * 2, verticalRange * 2, radius * 2),
                        drop -> !drop.isRemoved() && drop.isAlive())
                .stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private static ItemEntity findClosestDropExcluding(ServerPlayerEntity player, ServerWorld world, double radius, double verticalRange, ItemEntity excluded) {
        return world.getEntitiesByClass(
                        ItemEntity.class,
                        Box.of(currentPosition(player), radius * 2, verticalRange * 2, radius * 2),
                        drop -> drop != excluded && !drop.isRemoved() && drop.isAlive())
                .stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private static boolean isGoToSuccess(String result) {
        if (result == null) {
            return false;
        }
        String lowered = result.toLowerCase(Locale.ROOT);
        return !(lowered.contains("failed") || lowered.contains("error") || lowered.contains("not found"));
    }

    private static String describeDrop(ItemEntity entity) {
        Text name = entity.getStack().getName();
        return name == null ? entity.getStack().toString() : name.getString();
    }

    private static BlockPos findPickupDestination(ServerPlayerEntity player, BlockPos dropPos) {
        ServerWorld world = (player.getEntityWorld() instanceof ServerWorld serverWorld) ? serverWorld : null;
        if (world == null) {
            return null;
        }

        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos foot = dropPos.offset(direction);
            BlockPos head = foot.up();
            if (!isNavigableStand(world, foot, head)) {
                continue;
            }
            double distance = player.getBlockPos().getSquaredDistance(head);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPos = head;
            }
        }

        if (bestPos == null) {
            BlockPos footBelow = dropPos.down();
            BlockPos head = dropPos;
            if (isNavigableStand(world, footBelow, head)) {
                bestPos = head;
            }
        }

        if (bestPos == null) {
            BlockPos foot = dropPos;
            BlockPos head = dropPos.up();
            if (isNavigableStand(world, foot, head)) {
                bestPos = head;
            }
        }

        return bestPos;
    }

    private static boolean isNavigableStand(ServerWorld world, BlockPos foot, BlockPos head) {
        return isStandable(world, foot, head)
                && world.getBlockState(head.up()).getCollisionShape(world, head.up()).isEmpty();
    }

    private static boolean isStandable(ServerWorld world, BlockPos foot, BlockPos head) {
        return !world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    private static Vec3d currentPosition(ServerPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static boolean attemptManualNudge(ServerPlayerEntity player, ItemEntity targetDrop, BlockPos dropPos) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ServerWorld world = (player.getEntityWorld() instanceof ServerWorld serverWorld) ? serverWorld : null;
        MinecraftServer server = world != null ? world.getServer() : null;

        Runnable task = () -> {
            try {
                LookController.faceBlock(player, dropPos);
                BotActions.moveForward(player);
                BotActions.jumpForward(player);
                boolean collected = targetDrop.isRemoved() || player.squaredDistanceTo(targetDrop) <= PICKUP_DISTANCE_SQUARED;
                future.complete(collected);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (server == null || server.isOnThread()) {
            task.run();
        } else {
            server.execute(task);
        }

        try {
            return future.get();
        } catch (Exception e) {
            LOGGER.warn("Drop sweep nudge errored near {}: {}", dropPos, e.getMessage());
            return false;
        }
    }
}
