package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;

/**
 * Local helpers for explicit village workflows such as fortification planning.
 * Do not use this as a generic runtime protection source; ambient protection comes
 * from saved zones/bases/fortifications/mapped villages.
 */
public final class VillageStructureProtectionService {

    private VillageStructureProtectionService() {
    }

    public static boolean isVillageStructureBlock(Block block) {
        return VillageFortificationLayoutService.isVillageStructureBlock(block);
    }

    public static boolean isAdjacentToVillageStructure(ServerWorld world, BlockPos pos, int threshold) {
        if (world == null || pos == null) {
            return false;
        }
        int required = Math.max(1, threshold);
        int count = 0;
        for (Direction dir : Direction.values()) {
            var neighbor = world.getBlockState(pos.offset(dir));
            if (!neighbor.isAir() && isVillageStructureBlock(neighbor.getBlock())) {
                count++;
                if (count >= required) {
                    return true;
                }
            }
        }
        return false;
    }
}
