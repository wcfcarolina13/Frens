package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;

/**
 * Shared safety checks for deciding whether a construction repair can be applied
 * without trapping the bot inside the structure being built.
 */
public final class ConstructionRepairSafetyService {

    private ConstructionRepairSafetyService() {}

    public static boolean wouldRepairSealCurrentExit(ServerPlayerEntity bot, ServerWorld world, BlockPos repairPos) {
        if (bot == null || world == null || repairPos == null) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        if (botPos.equals(repairPos) || botPos.up().equals(repairPos)) {
            return true;
        }

        int dx = Math.abs(repairPos.getX() - botPos.getX());
        int dz = Math.abs(repairPos.getZ() - botPos.getZ());
        int dy = Math.abs(repairPos.getY() - botPos.getY());
        if (dx > 1 || dz > 1 || dy > 2) {
            return false;
        }

        int exitsBefore = countOpenExits(world, botPos, null);
        int exitsAfter = countOpenExits(world, botPos, repairPos);
        if (exitsAfter > exitsBefore) {
            return false;
        }
        if (exitsAfter == 0 && exitsAfter < exitsBefore) {
            return true;
        }
        return isTrapLikeCell(world, botPos) && exitsAfter == 0;
    }

    private static boolean isTrapLikeCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        VoxelJunctionService.VoxelStandCell cell = VoxelJunctionService.analyzeStandCell(world, pos);
        return cell.topology() == VoxelJunctionService.CellTopology.POCKET
                || cell.topology() == VoxelJunctionService.CellTopology.DEAD_END
                || cell.openFaces() <= 1;
    }

    private static int countOpenExits(ServerWorld world, BlockPos center, BlockPos forcedSolidPos) {
        int exits = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = center.offset(dir);
            if (canStandAtWithForcedSolid(world, neighbor, forcedSolidPos)) {
                exits++;
            }
        }
        return exits;
    }

    private static boolean canStandAtWithForcedSolid(ServerWorld world, BlockPos pos, BlockPos forcedSolidPos) {
        BlockPos feetPos = pos;
        BlockPos headPos = pos.up();
        BlockPos belowPos = pos.down();

        boolean feetClear = !isForcedSolid(forcedSolidPos, feetPos);
        if (feetClear) {
            BlockState feet = world.getBlockState(feetPos);
            feetClear = feet.isAir() || feet.isReplaceable();
        }

        boolean headClear = !isForcedSolid(forcedSolidPos, headPos);
        if (headClear) {
            BlockState head = world.getBlockState(headPos);
            headClear = head.isAir() || head.isReplaceable();
        }

        boolean hasSupport;
        if (isForcedSolid(forcedSolidPos, belowPos)) {
            hasSupport = true;
        } else {
            BlockState below = world.getBlockState(belowPos);
            hasSupport = !below.isAir() && !below.isReplaceable();
        }

        return feetClear && headClear && hasSupport;
    }

    private static boolean isForcedSolid(BlockPos forcedSolidPos, BlockPos testPos) {
        return forcedSolidPos != null && forcedSolidPos.equals(testPos);
    }
}