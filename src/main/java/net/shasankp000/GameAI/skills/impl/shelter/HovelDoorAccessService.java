package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Door-based entry/exit helpers used by perimeter routing and interior-only steps.
 *
 * <p>Extracted from {@code HovelPerimeterBuilder} to keep the builder class focused on the build pipeline.</p>
 */
final class HovelDoorAccessService {

    @FunctionalInterface
    interface FindStandable {
        BlockPos find(ServerWorld world, BlockPos seed, int radius);
    }

    @FunctionalInterface
    interface Move {
        boolean move(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest);
    }

    @FunctionalInterface
    interface EnsureRingStandable {
        void ensure(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos);
    }

    @FunctionalInterface
    interface Mine {
        void mine(ServerPlayerEntity bot, BlockPos pos);
    }

    private HovelDoorAccessService() {
    }

    static Direction resolveDoorSideForExit(Direction activeDoorSide,
                                           ServerPlayerEntity bot,
                                           BlockPos activeBuildCenter,
                                           int activeRadius) {
        if (activeDoorSide != null) return activeDoorSide;
        if (bot == null) return Direction.NORTH;
        if (activeBuildCenter == null || activeRadius <= 0) {
            return bot.getHorizontalFacing();
        }
        BlockPos pos = bot.getBlockPos();
        Direction best = bot.getHorizontalFacing();
        double bestSq = Double.MAX_VALUE;
        for (Direction dir : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos outside = activeBuildCenter.offset(dir, activeRadius + 1).withY(pos.getY());
            double d = HovelGeometryService.distSqXZ(pos, outside);
            if (d < bestSq) {
                bestSq = d;
                best = dir;
            }
        }
        return best;
    }

    static boolean enterInteriorViaDoor(ServerWorld world,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       BlockPos center,
                                       int radius,
                                       Direction doorSide,
                                       double reachDistanceSq,
                                       FindStandable findNearbyStandable,
                                       Move moveToRingPosFast,
                                       Move directMove,
                                       Move pathMove,
                                       Mine mineSoft) {
        if (world == null || source == null || bot == null || center == null || doorSide == null) {
            return false;
        }
        int standY = center.getY() + 1;
        BlockPos outsideFront = center.offset(doorSide, radius + 1).withY(standY);
        BlockPos insideFront = center.offset(doorSide, Math.max(1, radius - 1)).withY(standY);

        BlockPos outside = findNearbyStandable.find(world, outsideFront, 6);
        if (outside == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(outside) > 1.0D) {
            if (!moveToRingPosFast.move(source, bot, outside)) {
                return false;
            }
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, mineSoft);

        BlockPos inside = findNearbyStandable.find(world, insideFront, 6);
        if (inside == null) {
            return false;
        }
        if (!directMove.move(source, bot, inside) && !pathMove.move(source, bot, inside)) {
            return false;
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, mineSoft);
        return true;
    }

    static boolean exitInteriorViaDoor(ServerWorld world,
                                      ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      BlockPos center,
                                      int radius,
                                      Direction doorSide,
                                      double reachDistanceSq,
                                      FindStandable findNearbyStandable,
                                      Move moveToBuildSiteAllowPathing,
                                      Move directMove,
                                      Move pathMove,
                                      EnsureRingStandable ensureRingStandable,
                                      Mine mineSoft) {
        if (world == null || source == null || bot == null || center == null || doorSide == null) {
            return false;
        }

        int standY = center.getY() + 1;
        BlockPos insideFront = center.offset(doorSide, Math.max(1, radius - 1)).withY(standY);
        BlockPos outsideFront = center.offset(doorSide, radius + 1).withY(standY);

        BlockPos insideStand = findNearbyStandable.find(world, insideFront, 5);
        if (insideStand == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(insideStand) > 1.0D) {
            if (!moveToBuildSiteAllowPathing.move(source, bot, insideStand)) {
                return false;
            }
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, mineSoft);

        BlockPos outsideStand = findNearbyStandable.find(world, outsideFront, 6);
        if (outsideStand == null) {
            return false;
        }
        ensureRingStandable.ensure(world, bot, outsideStand);
        if (!directMove.move(source, bot, outsideStand) && !pathMove.move(source, bot, outsideStand)) {
            return false;
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, mineSoft);
        return true;
    }

    static void clearDoorwayNearby(ServerWorld world,
                                  ServerPlayerEntity bot,
                                  BlockPos center,
                                  int radius,
                                  Direction doorSide,
                                  double reachDistanceSq,
                                  Mine mineSoft) {
        if (world == null || bot == null || center == null || doorSide == null) return;
        int standY = center.getY() + 1;
        BlockPos doorBase = center.offset(doorSide, radius).withY(standY);
        BlockPos doorUpper = doorBase.up();
        BlockPos insideFront = doorBase.offset(doorSide.getOpposite());
        BlockPos outsideFront = doorBase.offset(doorSide);

        for (BlockPos p : List.of(doorBase, doorUpper, insideFront, insideFront.up(), outsideFront, outsideFront.up())) {
            if (p == null) continue;
            if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) > reachDistanceSq) continue;
            BlockState s = world.getBlockState(p);
            if (s.isAir()) continue;
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock) continue;
            mineSoft.mine(bot, p);
        }
    }
}
