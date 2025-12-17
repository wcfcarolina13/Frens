package net.shasankp000.GameAI.services;

import net.minecraft.block.DoorBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.ArrayList;

/**
 * Utility checks to keep block interactions aligned with survival mechanics:
 * limited reach + unobstructed line-of-sight raycast.
 */
public final class BlockInteractionService {

    // Vanilla survival reach is ~4.5 blocks.
    public static final double SURVIVAL_REACH_SQ = 20.25D;

    private BlockInteractionService() {
    }

    public static boolean canInteract(ServerPlayerEntity player, BlockPos blockPos) {
        return canInteract(player, blockPos, SURVIVAL_REACH_SQ);
    }

    public static boolean canInteract(ServerPlayerEntity player, BlockPos blockPos, double maxDistanceSq) {
        if (player == null || blockPos == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (player.squaredDistanceTo(Vec3d.ofCenter(blockPos)) > maxDistanceSq) {
            return false;
        }

        // Doors are 2-block tall; raycasts commonly hit the upper half. Normalize to base so
        // "doorPos" checks remain stable regardless of which half is intersected.
        BlockPos desiredDoorBase = null;
        if (world.getBlockState(blockPos).getBlock() instanceof DoorBlock) {
            desiredDoorBase = normalizeDoorBase(world, blockPos);
        }

        Vec3d from = player.getEyePos();
        List<Vec3d> targets = desiredDoorBase != null
                ? doorTargetPoints(desiredDoorBase)
                : List.of(
                        Vec3d.ofCenter(blockPos),
                        Vec3d.ofCenter(blockPos).add(0, 0.35, 0),
                        Vec3d.ofCenter(blockPos).add(0, 0.7, 0)
                );

        for (Vec3d to : targets) {
            for (RaycastContext.ShapeType shape : List.of(RaycastContext.ShapeType.OUTLINE, RaycastContext.ShapeType.COLLIDER)) {
                HitResult hit = world.raycast(new RaycastContext(
                        from,
                        to,
                        shape,
                        RaycastContext.FluidHandling.NONE,
                        player
                ));
                if (hit.getType() == HitResult.Type.BLOCK
                        && hit instanceof BlockHitResult bhr) {
                    BlockPos hitPos = bhr.getBlockPos();
                    if (hitPos.equals(blockPos)) {
                        return true;
                    }
                    if (desiredDoorBase != null && world.getBlockState(hitPos).getBlock() instanceof DoorBlock) {
                        BlockPos hitBase = normalizeDoorBase(world, hitPos);
                        if (hitBase != null && hitBase.equals(desiredDoorBase)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<Vec3d> doorTargetPoints(BlockPos doorBase) {
        // A closed door is a thin plane on one edge of the block; raycasting to the block center
        // frequently misses it. Probe points near the block edges on both halves.
        List<Vec3d> points = new ArrayList<>(24);
        List<BlockPos> halves = List.of(doorBase, doorBase.up());
        double[] ys = new double[] { 0.2, 0.5, 0.8 };
        double edge = 0.48;
        for (BlockPos pos : halves) {
            double cx = pos.getX() + 0.5;
            double cz = pos.getZ() + 0.5;
            for (double y : ys) {
                double py = pos.getY() + y;
                points.add(new Vec3d(cx, py, cz));
                points.add(new Vec3d(cx + edge, py, cz));
                points.add(new Vec3d(cx - edge, py, cz));
                points.add(new Vec3d(cx, py, cz + edge));
                points.add(new Vec3d(cx, py, cz - edge));
            }
        }
        return points;
    }

    public static BlockPos findBlockingDoor(ServerPlayerEntity player, BlockPos targetPos, double maxDistanceSq) {
        if (player == null || targetPos == null) {
            return null;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        if (player.squaredDistanceTo(Vec3d.ofCenter(targetPos)) > maxDistanceSq) {
            return null;
        }

        Vec3d from = player.getEyePos();
        List<Vec3d> targets = List.of(
                Vec3d.ofCenter(targetPos),
                Vec3d.ofCenter(targetPos).add(0, 0.35, 0),
                Vec3d.ofCenter(targetPos).add(0, 0.7, 0)
        );

        for (Vec3d to : targets) {
            for (RaycastContext.ShapeType shape : List.of(RaycastContext.ShapeType.OUTLINE, RaycastContext.ShapeType.COLLIDER)) {
                HitResult hit = world.raycast(new RaycastContext(
                        from,
                        to,
                        shape,
                        RaycastContext.FluidHandling.NONE,
                        player
                ));
                if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
                    continue;
                }
                BlockPos hitPos = bhr.getBlockPos();
                if (hitPos.equals(targetPos)) {
                    continue;
                }
                if (world.getBlockState(hitPos).getBlock() instanceof DoorBlock) {
                    return hitPos.toImmutable();
                }
            }
        }
        return null;
    }

    public static BlockPos findDoorAlongLine(ServerPlayerEntity player, Vec3d goal, double maxDistance) {
        if (player == null || goal == null) {
            return null;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        Vec3d from = player.getEyePos();
        Vec3d delta = goal.subtract(from);
        double len = delta.length();
        if (len < 1.0E-4) {
            return null;
        }
        double dist = Math.min(maxDistance, len);
        Vec3d to = from.add(delta.normalize().multiply(dist));

        for (RaycastContext.ShapeType shape : List.of(RaycastContext.ShapeType.OUTLINE, RaycastContext.ShapeType.COLLIDER)) {
            HitResult hit = world.raycast(new RaycastContext(
                    from,
                    to,
                    shape,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
                continue;
            }
            BlockPos hitPos = bhr.getBlockPos();
            if (world.getBlockState(hitPos).getBlock() instanceof DoorBlock) {
                return hitPos.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos normalizeDoorBase(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.contains(DoorBlock.HALF)
                    && state.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
                return pos.down();
            }
            return pos;
        }
        BlockState up = world.getBlockState(pos.up());
        if (up.getBlock() instanceof DoorBlock) {
            return normalizeDoorBase(world, pos.up());
        }
        BlockState down = world.getBlockState(pos.down());
        if (down.getBlock() instanceof DoorBlock) {
            return normalizeDoorBase(world, pos.down());
        }
        return null;
    }
}
