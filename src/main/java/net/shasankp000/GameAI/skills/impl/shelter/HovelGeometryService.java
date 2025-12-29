package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.util.math.BlockPos;

/**
 * Small geometry helpers used across hovel building submodules.
 */
final class HovelGeometryService {

    private HovelGeometryService() {
    }

    static double distSqXZ(BlockPos a, BlockPos b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        long dx = (long) a.getX() - (long) b.getX();
        long dz = (long) a.getZ() - (long) b.getZ();
        return (double) (dx * dx + dz * dz);
    }

    static boolean isInsideFootprint(BlockPos pos, BlockPos center, int radius) {
        if (pos == null || center == null || radius <= 0) return false;
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx < radius && dz < radius;
    }

    static boolean isOutsideFootprint(BlockPos pos, BlockPos center, int radius) {
        if (pos == null || center == null || radius <= 0) return false;
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx > radius || dz > radius;
    }
}
