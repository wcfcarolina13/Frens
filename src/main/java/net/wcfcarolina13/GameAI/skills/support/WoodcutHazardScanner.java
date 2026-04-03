package net.wcfcarolina13.GameAI.skills.support;

import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class WoodcutHazardScanner {

    private WoodcutHazardScanner() {}

    public enum TerrainRating { SAFE, SHALLOW_WATER, DEEP_WATER, RAVINE }

    public record TreeHazardProfile(
            Map<Direction, TerrainRating> ratings,
            boolean hasAnySafeApproach,
            boolean fullyEnclosed,
            List<Direction> safeSides
    ) {}

    private static final int PROBE_DEPTH = 6;
    private static final int RAVINE_DROP_THRESHOLD = 3;
    private static final int SHALLOW_WATER_MAX_DEPTH = 2;
    private static final int DEEP_WATER_FLOOR_PROBE = 4;

    public static TreeHazardProfile scan(ServerWorld world, BlockPos treeBase) {
        Map<Direction, TerrainRating> ratings = new EnumMap<>(Direction.class);
        List<Direction> safeSides = new ArrayList<>();

        for (Direction dir : Direction.Type.HORIZONTAL) {
            TerrainRating worst = TerrainRating.SAFE;

            for (int step = 1; step <= PROBE_DEPTH; step++) {
                BlockPos current = treeBase.offset(dir, step);
                TerrainRating rating = assessPosition(world, current, treeBase.getY());
                if (rating.ordinal() > worst.ordinal()) {
                    worst = rating;
                }
                if (worst == TerrainRating.RAVINE || worst == TerrainRating.DEEP_WATER) {
                    break;
                }
            }

            ratings.put(dir, worst);
            if (worst == TerrainRating.SAFE || worst == TerrainRating.SHALLOW_WATER) {
                safeSides.add(dir);
            }
        }

        boolean hasAnySafe = !safeSides.isEmpty();
        boolean fullyEnclosed = safeSides.isEmpty();
        return new TreeHazardProfile(ratings, hasAnySafe, fullyEnclosed, List.copyOf(safeSides));
    }

    private static TerrainRating assessPosition(ServerWorld world, BlockPos pos, int baseY) {
        if (!world.isChunkLoaded(pos)) return TerrainRating.SAFE;

        BlockPos groundCheck = new BlockPos(pos.getX(), baseY, pos.getZ());
        int dropDepth = 0;

        for (int d = 0; d <= RAVINE_DROP_THRESHOLD + DEEP_WATER_FLOOR_PROBE; d++) {
            BlockPos probe = groundCheck.down(d);
            var blockState = world.getBlockState(probe);
            var fluidState = world.getFluidState(probe);

            if (fluidState.isIn(FluidTags.WATER)) {
                return classifyWaterDepth(world, probe);
            }

            if (!blockState.getCollisionShape(world, probe).isEmpty()) {
                return dropDepth >= RAVINE_DROP_THRESHOLD ? TerrainRating.RAVINE : TerrainRating.SAFE;
            }

            dropDepth++;
        }

        return TerrainRating.RAVINE;
    }

    private static TerrainRating classifyWaterDepth(ServerWorld world, BlockPos waterSurface) {
        int depth = 0;
        for (int d = 0; d < DEEP_WATER_FLOOR_PROBE; d++) {
            BlockPos probe = waterSurface.down(d);
            if (world.getFluidState(probe).isIn(FluidTags.WATER)) {
                depth++;
            } else {
                break;
            }
        }
        BlockPos floor = waterSurface.down(depth);
        boolean hasFloor = !world.getBlockState(floor).getCollisionShape(world, floor).isEmpty();

        if (depth <= SHALLOW_WATER_MAX_DEPTH && hasFloor) {
            return TerrainRating.SHALLOW_WATER;
        }
        return TerrainRating.DEEP_WATER;
    }
}
