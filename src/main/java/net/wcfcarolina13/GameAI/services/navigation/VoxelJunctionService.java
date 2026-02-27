package net.wcfcarolina13.GameAI.services.navigation;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared voxel-local geometry utilities for navigation/placement stance selection.
 *
 * <p>This is intentionally lightweight for the first rollout: it provides standability,
 * local topology classification, and bounded stand-cell reachability. Fortify consumes it
 * first; other construction systems can adopt it incrementally.</p>
 */
public final class VoxelJunctionService {

    public enum CellTopology {
        CORRIDOR,
        CORNER_CONVEX,
        CORNER_CONCAVE,
        POCKET,
        OPENING,
        DEAD_END
    }

    public enum TransitionKind {
        CARDINAL,
        STEP_UP,
        STEP_DOWN,
        NARROW_GAP,
        REQUIRES_CARVE
    }

    public record VoxelStandCell(BlockPos feet,
                                 boolean feetClear,
                                 boolean headClear,
                                 boolean supportSolid,
                                 int openFaces,
                                 CellTopology topology) {}

    public record VoxelTransition(BlockPos from,
                                  BlockPos to,
                                  TransitionKind kind,
                                  boolean requiresCarve) {}

    private VoxelJunctionService() {}

    public static boolean isStandable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        boolean feetClear = feet.isAir() || feet.isReplaceable();
        boolean headClear = head.isAir() || head.isReplaceable();
        boolean supportSolid = !below.isAir() && !below.isReplaceable();
        return feetClear && headClear && supportSolid;
    }

    public static VoxelStandCell analyzeStandCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return new VoxelStandCell(pos, false, false, false, 0, CellTopology.POCKET);
        }
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        boolean feetClear = feet.isAir() || feet.isReplaceable();
        boolean headClear = head.isAir() || head.isReplaceable();
        boolean supportSolid = !below.isAir() && !below.isReplaceable();
        int openFaces = 0;
        if (feetClear && headClear && supportSolid) {
            openFaces = countOpenFaces(world, pos);
        }
        return new VoxelStandCell(pos.toImmutable(), feetClear, headClear, supportSolid, openFaces,
                classifyTopology(openFaces));
    }

    public static int countOpenFaces(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return 0;
        int exits = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isStandable(world, pos.offset(dir))) {
                exits++;
            }
        }
        return exits;
    }

    public static List<VoxelTransition> transitionsFrom(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return List.of();
        List<VoxelTransition> out = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos lane = pos.offset(dir);
            if (isStandable(world, lane)) {
                out.add(new VoxelTransition(pos.toImmutable(), lane.toImmutable(), TransitionKind.CARDINAL, false));
                continue;
            }
            BlockPos up = lane.up();
            if (isStandable(world, up)) {
                out.add(new VoxelTransition(pos.toImmutable(), up.toImmutable(), TransitionKind.STEP_UP, false));
                continue;
            }
            BlockPos down = lane.down();
            if (isStandable(world, down)) {
                out.add(new VoxelTransition(pos.toImmutable(), down.toImmutable(), TransitionKind.STEP_DOWN, false));
                continue;
            }

            // Future rollout can distinguish narrow-gap vs carve-required blockers more precisely.
            if (hasAnyBlockingCollision(world, lane) || hasAnyBlockingCollision(world, lane.up())) {
                out.add(new VoxelTransition(pos.toImmutable(), lane.toImmutable(), TransitionKind.REQUIRES_CARVE, true));
            }
        }
        return out;
    }

    public static Set<BlockPos> computeReachableStandCells(ServerWorld world,
                                                           BlockPos start,
                                                           BlockPos areaCenter,
                                                           int horizRadius,
                                                           int minY,
                                                           int maxY,
                                                           int maxNodes,
                                                           Function<BlockPos, BlockPos> seedFallback) {
        if (world == null || start == null || areaCenter == null) {
            return Set.of();
        }
        BlockPos seed = isStandable(world, start) ? start : (seedFallback == null ? null : seedFallback.apply(start));
        if (seed == null || !isStandable(world, seed)) {
            return Set.of();
        }

        int loY = Math.min(minY, maxY);
        int hiY = Math.max(minY, maxY);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos seedImm = seed.toImmutable();
        queue.add(seedImm);
        visited.add(seedImm);

        while (!queue.isEmpty() && visited.size() < Math.max(32, maxNodes)) {
            BlockPos cur = queue.poll();
            for (VoxelTransition transition : transitionsFrom(world, cur)) {
                if (transition == null || transition.requiresCarve()) continue;
                BlockPos next = transition.to();
                if (next == null) continue;
                if (Math.abs(next.getX() - areaCenter.getX()) > horizRadius
                        || Math.abs(next.getZ() - areaCenter.getZ()) > horizRadius) {
                    continue;
                }
                if (next.getY() < loY || next.getY() > hiY) {
                    continue;
                }
                BlockPos immutable = next.toImmutable();
                if (visited.add(immutable)) {
                    queue.add(immutable);
                }
            }
        }
        return visited;
    }

    private static CellTopology classifyTopology(int openFaces) {
        return switch (openFaces) {
            case 0 -> CellTopology.POCKET;
            case 1 -> CellTopology.DEAD_END;
            case 2 -> CellTopology.CORRIDOR;
            case 3 -> CellTopology.CORNER_CONCAVE;
            default -> CellTopology.OPENING;
        };
    }

    private static boolean hasAnyBlockingCollision(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }
}
