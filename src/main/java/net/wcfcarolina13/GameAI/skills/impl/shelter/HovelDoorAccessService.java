package net.wcfcarolina13.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

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
    interface Mine {
        void mine(ServerPlayerEntity bot, BlockPos pos);
    }

    /** Builder primitives the door egress drives. None of them may route through the builder's access guards. */
    interface EgressOps {
        boolean isStandable(ServerWorld world, BlockPos foot);

        BlockPos findStandableFiltered(ServerWorld world, BlockPos seed, int radius, Predicate<BlockPos> accept);

        boolean directMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest);

        boolean pathMove(ServerCommandSource source, ServerPlayerEntity bot, BlockPos dest);

        boolean nudgeToStand(ServerWorld world, ServerPlayerEntity bot, BlockPos stand, long timeoutMs);

        void ensureRingStandable(ServerWorld world, ServerPlayerEntity bot, BlockPos ringPos);

        void mineSoft(ServerPlayerEntity bot, BlockPos pos);
    }

    /** One egress attempt: {@code failedStep} is null on success; {@code route} lists the resolved waypoints. */
    record EgressOutcome(boolean ok, String failedStep, String route) {
        static EgressOutcome success(String route) {
            return new EgressOutcome(true, null, route);
        }

        static EgressOutcome failure(String step, String route) {
            return new EgressOutcome(false, step, route);
        }

        String describe() {
            return ok ? "ok" : "failed:" + failedStep;
        }
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

    /**
     * Leave the footprint through the doorway along three cells lined up on the door normal:
     * inside approach -> doorway gap -> exit on the perimeter ring (see {@link HovelEgressGeometry}).
     *
     * <p>Each waypoint is first looked up in its own column (so the route stays aligned with the door);
     * only if that column has no standable cell does it fall back to a nearest-standable search, and that
     * search is constrained to the right zone (approach strictly inside, exit strictly in front of the door
     * wall) so the fallback can never pick a cell on the wrong side of a wall.</p>
     *
     * <p>Feet Y for all three legs is the blueprint stand level ({@code center.getY() + 1}): the doorway gap
     * is fixed at floorY+1..+2 ({@link HovelBlueprint#isDoorGap}), so this is the authoritative level, not a
     * heightmap reading. Column lookups still scan a block or two either way for uneven ground outside.</p>
     *
     * <p>Only uses direct/path moves and nudges, none of which re-enter the builder's access guards.</p>
     */
    static EgressOutcome exitInteriorViaDoor(ServerWorld world,
                                             ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             BlockPos center,
                                             int radius,
                                             Direction doorSide,
                                             double reachDistanceSq,
                                             EgressOps ops) {
        if (world == null || source == null || bot == null || center == null || doorSide == null || ops == null
                || radius <= 0 || doorSide.getAxis().isVertical()) {
            return EgressOutcome.failure("args", "-");
        }
        int cx = center.getX();
        int cz = center.getZ();
        int nx = doorSide.getOffsetX();
        int nz = doorSide.getOffsetZ();
        int standY = center.getY() + 1;
        HovelEgressGeometry.Waypoints wp = HovelEgressGeometry.doorWaypoints(cx, cz, radius, nx, nz);
        BlockPos start = bot.getBlockPos();
        HovelEgressGeometry.EgressStep first =
                HovelEgressGeometry.firstStep(start.getX(), start.getZ(), cx, cz, radius, nx, nz);
        StringBuilder route = new StringBuilder("start=").append(first.name().toLowerCase(Locale.ROOT));

        // Leg 1: inside approach, two cells in on the door axis, so the door leg is taken head-on.
        if (first == HovelEgressGeometry.EgressStep.APPROACH) {
            BlockPos approach = resolveAligned(world, ops, wp.approach(), standY, 2);
            route.append(approach != null ? " approach=" : " approach~");
            if (approach == null) {
                approach = ops.findStandableFiltered(world, at(wp.approach(), standY), 3,
                        p -> HovelEgressGeometry.classify(p.getX(), p.getZ(), cx, cz, radius)
                                == HovelEgressGeometry.FootprintZone.INSIDE);
            }
            if (approach == null) {
                return EgressOutcome.failure("approach", route.append("none").toString());
            }
            route.append(approach.toShortString());
            if (!sameColumn(bot.getBlockPos(), approach)) {
                boolean moved = ops.directMove(source, bot, approach) || ops.pathMove(source, bot, approach);
                if (!moved) {
                    return EgressOutcome.failure("approach", route.toString());
                }
                if (!sameColumn(bot.getBlockPos(), approach)) {
                    // MovementService counts ~3 blocks as arrived; line up on the axis before the door leg.
                    ops.nudgeToStand(world, bot, approach, 1200L);
                }
            }
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, ops::mineSoft);

        // Leg 2: the doorway gap. A placed door block is not standable; then the exit leg passes through it
        // on the same straight line (the old two-leg behaviour).
        BlockPos doorCell = at(wp.door(), standY);
        if (first != HovelEgressGeometry.EgressStep.EXIT) {
            BlockPos door = resolveAligned(world, ops, wp.door(), standY, 1);
            if (door == null) {
                route.append(" door=pass");
            } else {
                route.append(" door=").append(door.toShortString());
                if (!ops.directMove(source, bot, door) && !ops.pathMove(source, bot, door)) {
                    route.append("(missed)");
                }
            }
            clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, ops::mineSoft);
        }

        // Leg 3: straight out along the normal, preferring the perimeter ring cell.
        BlockPos exit = null;
        for (HovelEgressGeometry.Cell candidate : wp.exitCandidates()) {
            exit = resolveAligned(world, ops, candidate, standY, 2);
            if (exit != null) break;
        }
        route.append(exit != null ? " exit=" : " exit~");
        if (exit == null) {
            exit = ops.findStandableFiltered(world, at(wp.exitCandidates().get(0), standY), 6,
                    p -> HovelEgressGeometry.isInFrontOfDoor(p.getX(), p.getZ(), cx, cz, radius, nx, nz));
        }
        if (exit == null) {
            return EgressOutcome.failure("exit", route.append("none").toString());
        }
        route.append(exit.toShortString());
        ops.ensureRingStandable(world, bot, exit);
        boolean moved = ops.directMove(source, bot, exit) || ops.pathMove(source, bot, exit);
        if (!isOutside(bot, center, radius) && HovelGeometryService.distSqXZ(bot.getBlockPos(), doorCell) <= 4.0D) {
            // Arrival tolerance can leave the bot standing in the doorway; finish the straight step out.
            ops.nudgeToStand(world, bot, exit, 1500L);
        }
        clearDoorwayNearby(world, bot, center, radius, doorSide, reachDistanceSq, ops::mineSoft);
        if (!isOutside(bot, center, radius)) {
            return EgressOutcome.failure(moved ? "not-outside" : "exit", route.toString());
        }
        return EgressOutcome.success(route.toString());
    }

    /** First standable feet cell in the waypoint's own column, nearest the stand level first. */
    private static BlockPos resolveAligned(ServerWorld world,
                                           EgressOps ops,
                                           HovelEgressGeometry.Cell cell,
                                           int standY,
                                           int maxDy) {
        for (int dy : HovelEgressGeometry.verticalScanOffsets(maxDy)) {
            BlockPos p = new BlockPos(cell.x(), standY + dy, cell.z());
            if (ops.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    private static BlockPos at(HovelEgressGeometry.Cell cell, int y) {
        return new BlockPos(cell.x(), y, cell.z());
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static boolean isOutside(ServerPlayerEntity bot, BlockPos center, int radius) {
        return HovelGeometryService.classifyFootprint(bot.getBlockPos(), center, radius)
                == HovelEgressGeometry.FootprintZone.OUTSIDE;
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
