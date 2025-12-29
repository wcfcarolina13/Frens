package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for choosing a "safe" standing position for a player-sized entity.
 *
 * A position is considered safe if:
 * - feet + head collision shapes are empty (2-block headroom)
 * - no fluids at feet/head
 * - there is a non-empty collision shape under feet (solid-ish floor)
 */
public final class SafePositionService {

    private SafePositionService() {
    }

    public static BlockPos findForwardSafeSpot(ServerWorld world, ServerPlayerEntity player) {
        if (world == null || player == null) {
            return null;
        }

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        if (look.lengthSquared() < 1.0E-4) {
            Direction facing = player.getHorizontalFacing();
            look = new Vec3d(facing.getOffsetX(), 0, facing.getOffsetZ());
        }

        Direction facing = player.getHorizontalFacing();
        Direction left = facing.rotateYCounterclockwise();
        List<BlockPos> samples = new ArrayList<>();

        for (int dist = 2; dist <= 8; dist++) {
            Vec3d baseVec = eye.add(look.multiply(dist));
            BlockPos base = BlockPos.ofFloored(baseVec.x, player.getBlockY(), baseVec.z);
            samples.add(base);
            samples.add(base.offset(left));
            samples.add(base.offset(left.getOpposite()));
        }
        samples.add(player.getBlockPos());

        for (BlockPos candidate : samples) {
            BlockPos safe = findSafeColumn(world, candidate);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    public static BlockPos findSafeNear(ServerWorld world, BlockPos base, int horizRadius) {
        if (world == null || base == null) {
            return null;
        }
        if (horizRadius <= 0) {
            return findSafeColumn(world, base);
        }

        // Spiral-ish search: expand outward so we prefer closer spots.
        for (int r = 0; r <= horizRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos safe = findSafeColumn(world, base.add(dx, 0, dz));
                    if (safe != null) {
                        return safe;
                    }
                }
            }
        }
        return null;
    }

    public static BlockPos findSafeColumn(ServerWorld world, BlockPos base) {
        return findSafeColumn(world, base, -3, 2);
    }

    public static BlockPos findSafeColumn(ServerWorld world, BlockPos base, int minDy, int maxDy) {
        if (world == null || base == null) {
            return null;
        }
        int min = Math.min(minDy, maxDy);
        int max = Math.max(minDy, maxDy);
        for (int dy = max; dy >= min; dy--) {
            BlockPos candidate = base.up(dy);
            if (isSpawnable(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean isSpawnable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) {
            return false;
        }
        BlockState floor = world.getBlockState(feet.down());
        return !floor.getCollisionShape(world, feet.down()).isEmpty();
    }
}
