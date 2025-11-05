package net.shasankp000.GameAI.services;

import net.minecraft.fluid.FluidState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.registry.tag.FluidTags;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.Entity.LookController;
import net.shasankp000.PathFinding.GoTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class MovementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("movement-service");
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final double CLOSE_ENOUGH_DISTANCE_SQ = 2.25D; // ~1.5 blocks

    private MovementService() {
    }

    public enum Mode {
        DIRECT,
        WADE,
        BRIDGE
    }

    public record MovementOptions(boolean allowWade,
                                  int maxWadeDepth,
                                  boolean allowBridge,
                                  int maxBridgeDepth) {
        public static MovementOptions lootCollection() {
            return new MovementOptions(true, 1, true, 1);
        }

        public static MovementOptions skillLoot() {
            return new MovementOptions(true, 1, true, 2);
        }
    }

    public record MovementPlan(Mode mode,
                               BlockPos finalDestination,
                               BlockPos approachDestination,
                               BlockPos wadeTarget,
                               BlockPos bridgeTarget,
                               Direction direction) {
        public MovementPlan {
            if (mode == Mode.DIRECT) {
                if (finalDestination == null) {
                    throw new IllegalArgumentException("finalDestination required for DIRECT moves");
                }
                if (approachDestination == null) {
                    approachDestination = finalDestination;
                }
            }
        }
    }

    public record MovementResult(boolean success,
                                 Mode mode,
                                 BlockPos arrivedAt,
                                 String detail) {
    }

    public static Optional<MovementPlan> planLootApproach(ServerPlayerEntity player,
                                                          BlockPos target,
                                                          MovementOptions options) {
        ServerWorld world = getWorld(player);
        if (world == null || target == null) {
            return Optional.empty();
        }

        // Prefer existing standable tiles around the target.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos foot = target.offset(direction);
            BlockPos head = foot.up();
            if (isSolidStandable(world, foot, head)) {
                LOGGER.debug("Movement plan: direct access to {} via {}", target, head);
                return Optional.of(new MovementPlan(Mode.DIRECT, head, head, null, null, direction));
            }
        }

        // Consider water-adjacent approaches.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            MovementPlan plan = evaluateWaterApproach(world, target, direction, options);
            if (plan != null) {
                LOGGER.debug("Movement plan: {} approach to {} via {}", plan.mode(), target, plan);
                return Optional.of(plan);
            }
        }

        BlockPos fallback = findNearbyStandable(world, target, 6, 6);
        if (fallback != null) {
            LOGGER.debug("Movement plan: fallback standable {} for {}", fallback, target);
            return Optional.of(new MovementPlan(Mode.DIRECT, fallback, fallback, null, null, Direction.UP));
        }

        // Fallback: stand directly on target if possible.
        BlockPos foot = target.down();
        if (isSolidStandable(world, foot, target)) {
            LOGGER.debug("Movement plan: direct stand on target {}", target);
            return Optional.of(new MovementPlan(Mode.DIRECT, target, target, null, null, Direction.UP));
        }

        LOGGER.debug("No viable movement plan for {}", target);
        return Optional.empty();
    }

    public static MovementResult execute(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         MovementPlan plan) {
        if (source == null || player == null || plan == null) {
            return new MovementResult(false, Mode.DIRECT, player != null ? player.getBlockPos() : null, "Invalid movement request.");
        }

        return switch (plan.mode()) {
            case DIRECT -> moveDirect(source, player, plan.finalDestination());
            case WADE -> moveWithWade(source, player, plan);
            case BRIDGE -> moveWithBridge(source, player, plan);
        };
    }

    private static MovementResult moveDirect(ServerCommandSource source,
                                             ServerPlayerEntity player,
                                             BlockPos destination) {
        MovementResult goTo = moveTo(source, player, destination, Mode.DIRECT, "direct");
        if (!goTo.success()) {
            return goTo;
        }
        return new MovementResult(true, Mode.DIRECT, destination, goTo.detail());
    }

    private static MovementResult moveWithWade(ServerCommandSource source,
                                               ServerPlayerEntity player,
                                               MovementPlan plan) {
        MovementResult approach = moveTo(source, player, plan.approachDestination(), Mode.WADE, "wade-approach");
        if (!approach.success()) {
            return approach;
        }

        boolean waded = performManualStep(player, plan.wadeTarget());
        String detail = waded ? "Manual wade succeeded toward " + plan.wadeTarget()
                : "Manual wade failed for " + plan.wadeTarget();
        return new MovementResult(waded, Mode.WADE, plan.wadeTarget(), detail);
    }

    private static MovementResult moveWithBridge(ServerCommandSource source,
                                                 ServerPlayerEntity player,
                                                 MovementPlan plan) {
        MovementResult approach = moveTo(source, player, plan.approachDestination(), Mode.BRIDGE, "bridge-approach");
        if (!approach.success()) {
            return approach;
        }

        boolean bridged = placeBridgeBlock(player, plan.bridgeTarget());
        if (!bridged) {
            return new MovementResult(false, Mode.BRIDGE, plan.bridgeTarget(), "Unable to place bridge block at " + plan.bridgeTarget());
        }

        MovementResult finalMove = moveTo(source, player, plan.finalDestination(), Mode.BRIDGE, "bridge-final");
        if (!finalMove.success()) {
            return finalMove;
        }
        return new MovementResult(true, Mode.BRIDGE, plan.finalDestination(), "Bridge placed and destination reached.");
    }

    private static MovementPlan evaluateWaterApproach(ServerWorld world,
                                                      BlockPos target,
                                                      Direction direction,
                                                      MovementOptions options) {
        BlockPos walkwayFoot = target.offset(direction);
        BlockPos walkwayHead = walkwayFoot.up();
        BlockPos approachFoot = walkwayFoot.offset(direction);
        BlockPos approachHead = approachFoot.up();

        boolean approachStandable = isSolidStandable(world, approachFoot, approachHead);
        boolean headClear = hasClearance(world, walkwayHead);

        if (options.allowBridge()
                && approachStandable
                && headClear
                && isBridgeCandidate(world, walkwayFoot, options.maxBridgeDepth())) {
            return new MovementPlan(Mode.BRIDGE, walkwayHead, approachHead, null, walkwayFoot, direction);
        }

        if (options.allowWade()
                && approachStandable
                && headClear
                && isShallowWater(world, walkwayFoot, options.maxWadeDepth())) {
            return new MovementPlan(Mode.WADE, target, approachHead, walkwayFoot, null, direction);
        }

        return null;
    }

    private static MovementResult moveTo(ServerCommandSource source,
                                         ServerPlayerEntity player,
                                         BlockPos destination,
                                         Mode mode,
                                         String label) {
        if (destination == null) {
            return new MovementResult(false, mode, player.getBlockPos(), "No destination specified for " + label);
        }
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d destinationCenter = new Vec3d(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
        if (playerPos.squaredDistanceTo(destinationCenter) <= CLOSE_ENOUGH_DISTANCE_SQ) {
            return new MovementResult(true, mode, destination, label + ": already at destination");
        }
        String rawResult = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
        boolean success = isGoToSuccess(rawResult);
        if (success) {
            BlockPos post = player.getBlockPos();
            double distanceSq = post.getSquaredDistance(destination);
            success = distanceSq <= ARRIVAL_DISTANCE_SQ;
            if (!success) {
                rawResult = rawResult + " (ended " + Math.sqrt(distanceSq) + " blocks away)";
            }
        }
        return new MovementResult(success, mode, destination, label + ": " + rawResult);
    }

    private static BlockPos findNearbyStandable(ServerWorld world, BlockPos target, int horizontalRange, int verticalRange) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(target);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            int dx = Math.abs(current.getX() - target.getX());
            int dz = Math.abs(current.getZ() - target.getZ());
            int dy = Math.abs(current.getY() - target.getY());
            if (dx > horizontalRange || dz > horizontalRange || dy > verticalRange) {
                continue;
            }

            BlockPos foot = current.down();
            if (isSolidStandable(world, foot, current)) {
                double distance = current.getSquaredDistance(target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = current;
                }
            }

            for (Direction direction : Direction.values()) {
                queue.add(current.offset(direction));
            }
        }
        return best;
    }

    private static boolean performManualStep(ServerPlayerEntity player, BlockPos target) {
        if (player == null || target == null) {
            return false;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        ServerWorld world = getWorld(player);
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            try {
                LookController.faceBlock(player, target);
                BotActions.moveForward(player);
                BotActions.jumpForward(player);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            future.get(1, TimeUnit.SECONDS);
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            return false;
        }
        Vec3d targetCenter = Vec3d.ofCenter(target);
        return player.squaredDistanceTo(targetCenter) <= ARRIVAL_DISTANCE_SQ;
    }

    private static boolean placeBridgeBlock(ServerPlayerEntity player, BlockPos target) {
        if (player == null || target == null) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ServerWorld world = getWorld(player);
        MinecraftServer server = world != null ? world.getServer() : null;
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            try {
                LookController.faceBlock(player, target);
                future.complete(BotActions.placeBlockAt(player, target));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBridgeCandidate(ServerWorld world, BlockPos pos, int maxDepth) {
        return isShallowWater(world, pos, maxDepth);
    }

    private static boolean isShallowWater(ServerWorld world, BlockPos pos, int maxDepth) {
        if (world == null || pos == null) {
            return false;
        }
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isIn(FluidTags.WATER)) {
            return false;
        }
        int depth = 0;
        BlockPos cursor = pos;
        while (depth < maxDepth) {
            FluidState state = world.getFluidState(cursor);
            if (!state.isIn(FluidTags.WATER)) {
                break;
            }
            depth++;
            cursor = cursor.down();
        }
        if (depth == 0) {
            return false;
        }
        if (world.getFluidState(cursor).isIn(FluidTags.WATER)) {
            return false;
        }
        return isFootSolid(world, cursor);
    }

    private static boolean isSolidStandable(ServerWorld world, BlockPos foot, BlockPos head) {
        return isFootSolid(world, foot) && hasClearance(world, head);
    }

    private static boolean isFootSolid(World world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        return !world.getBlockState(foot).getCollisionShape(world, foot).isEmpty();
    }

    private static boolean hasClearance(World world, BlockPos head) {
        if (world == null || head == null) {
            return false;
        }
        return world.getBlockState(head).getCollisionShape(world, head).isEmpty()
                && world.getBlockState(head.up()).getCollisionShape(world, head.up()).isEmpty();
    }

    private static boolean isGoToSuccess(String result) {
        if (result == null) {
            return false;
        }
        String lowered = result.toLowerCase();
        for (String token : new String[]{"failed", "error", "not found"}) {
            if (lowered.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static ServerWorld getWorld(ServerPlayerEntity player) {
        return player != null && player.getEntityWorld() instanceof ServerWorld world ? world : null;
    }
}
