package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;

/**
 * Shared village-structure protection helpers for generic navigation and safe mutation checks.
 */
public final class VillageStructureProtectionService {

    private static final int GENERIC_BREAK_ADJACENT_THRESHOLD = 2;

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
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (!neighbor.isAir() && isVillageStructureBlock(neighbor.getBlock())) {
                count++;
                if (count >= required) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isVillageProtectedForGenericBreaking(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        return isVillageStructureBlock(state.getBlock())
                || isAdjacentToVillageStructure(world, pos, GENERIC_BREAK_ADJACENT_THRESHOLD);
    }
}
