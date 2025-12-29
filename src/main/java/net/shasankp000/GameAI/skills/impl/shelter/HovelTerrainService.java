package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Terrain queries specific to hovel building.
 */
final class HovelTerrainService {

    private HovelTerrainService() {
    }

    /**
     * Try to detect the actual floor block Y under the build center.
     * This helps patch holes even if the chosen Y represents the player's standing cell.
     */
    static int detectFloorBlockY(ServerWorld world, BlockPos center) {
        if (world == null || center == null) return 0;
        int start = center.getY();
        for (int y = start; y >= start - 6; y--) {
            BlockPos p = new BlockPos(center.getX(), y, center.getZ());
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            BlockState s = world.getBlockState(p);
            if (!world.getFluidState(p).isEmpty()) continue;
            if (!s.getCollisionShape(world, p).isEmpty()) {
                return y;
            }
        }
        return start - 1;
    }
}
