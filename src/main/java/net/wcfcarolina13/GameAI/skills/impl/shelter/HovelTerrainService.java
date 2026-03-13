package net.wcfcarolina13.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terrain queries specific to hovel building.
 */
final class HovelTerrainService {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");

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
                LOGGER.info("Hovel terrain: floor scan center={} startY={} resolvedY={} state={}",
                        center.toShortString(),
                        start,
                        y,
                        s.getBlock().getName().getString());
                return y;
            }
        }
        LOGGER.info("Hovel terrain: floor scan center={} startY={} fell back to {}", center.toShortString(), start, start - 1);
        return start - 1;
    }
}
