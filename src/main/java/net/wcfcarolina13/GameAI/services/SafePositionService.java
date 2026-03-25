package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for choosing a "safe" standing position for a player-sized entity.
 *
 * A position is considered safe if:
 * - feet + head collision shapes are empty (2-block headroom)
 * - no fluids at feet/head
 * - there is a non-empty collision shape under feet (solid-ish floor)
 */
public final class SafePositionService {

    public record SurfaceCandidateAssessment(
            boolean openSky,
            boolean standable,
            boolean nearSurface,
            int cardinalStandableNeighbors,
            int totalStandableNeighbors,
            int steepDropNeighbors,
            int blockedCardinals,
            int localHeightVariance,
            double horizontalDistanceSq) {
    }

    public record SurfaceStagingCandidate(
            BlockPos pos,
            SurfaceCandidateAssessment assessment,
            int score) {
    }

    private SafePositionService() {
    }

    public static BlockPos findForwardSafeSpot(ServerWorld world, ServerPlayerEntity player) {
        if (world == null || player == null) {
            return null;
        }

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        if (look.lengthSquared() < 1.0E-4) {
            Direction facing = player.getHorizontalFacing();
            look = new Vec3d(facing.getOffsetX(), 0, facing.getOffsetZ());
        }

        Direction facing = player.getHorizontalFacing();
        Direction left = facing.rotateYCounterclockwise();
        List<BlockPos> samples = new ArrayList<>();

        for (int dist = 2; dist <= 8; dist++) {
            Vec3d baseVec = eye.add(look.multiply(dist));
            BlockPos base = BlockPos.ofFloored(baseVec.x, player.getBlockY(), baseVec.z);
            samples.add(base);
            samples.add(base.offset(left));
            samples.add(base.offset(left.getOpposite()));
        }
        samples.add(player.getBlockPos());

        for (BlockPos candidate : samples) {
            BlockPos safe = findSafeColumn(world, candidate);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    public static BlockPos findSafeNear(ServerWorld world, BlockPos base, int horizRadius) {
        if (world == null || base == null) {
            return null;
        }
        if (horizRadius <= 0) {
            return findSafeColumn(world, base);
        }

        // Spiral-ish search: expand outward so we prefer closer spots.
        for (int r = 0; r <= horizRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos safe = findSafeColumn(world, base.add(dx, 0, dz));
                    if (safe != null) {
                        return safe;
                    }
                }
            }
        }
        return null;
    }

    public static SurfaceCandidateAssessment analyzeSurfaceCandidate(ServerWorld world, BlockPos feet) {
        return analyzeSurfaceCandidate(world, feet, feet);
    }

    public static SurfaceCandidateAssessment analyzeSurfaceCandidate(ServerWorld world, BlockPos feet, BlockPos reference) {
        if (world == null || feet == null) {
            return new SurfaceCandidateAssessment(false, false, false, 0, 0, 4, 4, Integer.MAX_VALUE, Double.MAX_VALUE);
        }

        boolean standable = isSpawnable(world, feet);
        boolean openSky = world.isSkyVisible(feet.up(2));
        boolean nearSurface = feet.getY() >= wideSurfaceFeetY(world, feet.getX(), feet.getZ()) - 2;

        int cardinalStandableNeighbors = 0;
        int totalStandableNeighbors = 0;
        int steepDropNeighbors = 0;
        int blockedCardinals = 0;

        int minSurfaceY = columnSurfaceFeetY(world, feet.getX(), feet.getZ());
        int maxSurfaceY = minSurfaceY;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int surfaceFeetY = columnSurfaceFeetY(world, x, z);
                minSurfaceY = Math.min(minSurfaceY, surfaceFeetY);
                maxSurfaceY = Math.max(maxSurfaceY, surfaceFeetY);

                BlockPos neighbor = findSafeColumn(world, new BlockPos(x, feet.getY(), z), -1, 1);
                boolean standableNeighbor = neighbor != null && Math.abs(neighbor.getY() - feet.getY()) <= 1;
                if (standableNeighbor) {
                    totalStandableNeighbors++;
                }

                boolean cardinal = dx == 0 || dz == 0;
                if (cardinal) {
                    if (standableNeighbor) {
                        cardinalStandableNeighbors++;
                    } else {
                        blockedCardinals++;
                    }
                    if (surfaceFeetY < feet.getY() - 2) {
                        steepDropNeighbors++;
                    }
                }
            }
        }

        double horizontalDistanceSq = reference == null
                ? 0.0D
                : horizontalDistanceSq(feet, reference);

        return new SurfaceCandidateAssessment(
                openSky,
                standable,
                nearSurface,
                cardinalStandableNeighbors,
                totalStandableNeighbors,
                steepDropNeighbors,
                blockedCardinals,
                maxSurfaceY - minSurfaceY,
                horizontalDistanceSq
        );
    }

    static boolean isOperationalSurfaceAssessment(SurfaceCandidateAssessment assessment) {
        if (assessment == null) {
            return false;
        }
        return assessment.openSky()
                && assessment.standable()
                && assessment.nearSurface()
                && assessment.cardinalStandableNeighbors() >= 2
                && assessment.totalStandableNeighbors() >= 4
                && assessment.steepDropNeighbors() <= 1
                && assessment.blockedCardinals() <= 2;
    }

    public static boolean isRecoveryReadySurface(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        SurfaceCandidateAssessment assessment = analyzeSurfaceCandidate(world, feet);
        if (isOperationalSurfaceAssessment(assessment)) {
            return true;
        }
        if (!assessment.standable()) {
            // Before giving up, check if ±1 Y is standable — the bot may be in a
            // 1-block depression or standing on an irregular block (slab, half-step).
            if (assessment.openSky() && assessment.nearSurface()) {
                for (int dy = -1; dy <= 1; dy += 2) {
                    BlockPos adjusted = feet.up(dy);
                    if (isSpawnable(world, adjusted)) {
                        return true;
                    }
                }
            }
            return false;
        }
        int columnGap = Math.max(0, columnSurfaceFeetY(world, feet.getX(), feet.getZ()) - feet.getY());
        return hasLowRoofCover(world, feet, 4)
                && columnGap <= 4
                && assessment.cardinalStandableNeighbors() >= 2
                && assessment.totalStandableNeighbors() >= 3
                && assessment.steepDropNeighbors() <= 1
                && assessment.blockedCardinals() <= 2
                && assessment.localHeightVariance() <= 6;
    }

    static int scoreSurfaceAssessment(SurfaceCandidateAssessment assessment) {
        return scoreSurfaceAssessment(assessment, null, null, null);
    }

    static int scoreSurfaceAssessment(SurfaceCandidateAssessment assessment,
                                      BlockPos candidate,
                                      BlockPos origin,
                                      BlockPos focus) {
        if (assessment == null) {
            return Integer.MIN_VALUE / 4;
        }

        int score = 0;
        score += assessment.openSky() ? 140 : -220;
        score += assessment.standable() ? 120 : -240;
        score += assessment.nearSurface() ? 110 : -180;
        score += assessment.cardinalStandableNeighbors() * 28;
        score += assessment.totalStandableNeighbors() * 16;
        score -= assessment.steepDropNeighbors() * 70;
        score -= assessment.blockedCardinals() * 45;
        score -= assessment.localHeightVariance() * 10;
        score -= (int) Math.round(Math.sqrt(Math.max(0.0D, assessment.horizontalDistanceSq())) * 6.0D);
        if (candidate != null && origin != null && focus != null) {
            double originToFocus = Math.sqrt(horizontalDistanceSq(origin, focus));
            double candidateToFocus = Math.sqrt(horizontalDistanceSq(candidate, focus));
            double improvement = originToFocus - candidateToFocus;
            if (improvement > 0.0D) {
                score += (int) Math.round(Math.min(120.0D, improvement * 10.0D));
            } else if (improvement < 0.0D) {
                score += (int) Math.round(Math.max(-60.0D, improvement * 4.0D));
            }
        }
        return score;
    }

    public static boolean isOperationalSurface(ServerWorld world, BlockPos feet) {
        return isOperationalSurfaceAssessment(analyzeSurfaceCandidate(world, feet));
    }

    public static SurfaceStagingCandidate findBestSurfaceStaging(ServerWorld world, BlockPos origin, int searchRadius) {
        return findBestSurfaceStaging(world, origin, searchRadius, true);
    }

    public static SurfaceStagingCandidate findBestSurfaceStaging(ServerWorld world,
                                                                 BlockPos origin,
                                                                 int searchRadius,
                                                                 boolean requireOperational,
                                                                 BlockPos focus) {
        return findBestSurfaceStagingInternal(world, origin, searchRadius, requireOperational, focus);
    }

    public static SurfaceStagingCandidate findBestSurfaceStaging(ServerWorld world,
                                                                 BlockPos origin,
                                                                 int searchRadius,
                                                                 boolean requireOperational) {
        return findBestSurfaceStagingInternal(world, origin, searchRadius, requireOperational, null);
    }

    private static SurfaceStagingCandidate findBestSurfaceStagingInternal(ServerWorld world,
                                                                          BlockPos origin,
                                                                          int searchRadius,
                                                                          boolean requireOperational,
                                                                          BlockPos focus) {
        if (world == null || origin == null) {
            return null;
        }

        SurfaceStagingCandidate best = null;
        int radius = Math.max(0, searchRadius);
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    BlockPos candidatePos = resolveSurfaceCandidate(world, origin, x, z);
                    if (candidatePos == null) {
                        continue;
                    }

                    SurfaceCandidateAssessment assessment = analyzeSurfaceCandidate(world, candidatePos, origin);
                    if (requireOperational && !isOperationalSurfaceAssessment(assessment)) {
                        continue;
                    }
                    int score = scoreSurfaceAssessment(assessment, candidatePos, origin, focus);
                    if (best == null || score > best.score()) {
                        best = new SurfaceStagingCandidate(candidatePos.toImmutable(), assessment, score);
                    }
                }
            }
        }
        return best;
    }

    public static String summarizeSurfaceAssessment(SurfaceCandidateAssessment assessment) {
        if (assessment == null) {
            return "null";
        }
        return "openSky=" + assessment.openSky()
                + " standable=" + assessment.standable()
                + " nearSurface=" + assessment.nearSurface()
                + " cardinal=" + assessment.cardinalStandableNeighbors()
                + " total=" + assessment.totalStandableNeighbors()
                + " steepDrops=" + assessment.steepDropNeighbors()
                + " blocked=" + assessment.blockedCardinals()
                + " variance=" + assessment.localHeightVariance()
                + " dist=" + String.format(java.util.Locale.ROOT, "%.1f",
                Math.sqrt(Math.max(0.0D, assessment.horizontalDistanceSq())));
    }

    public static BlockPos findSafeColumn(ServerWorld world, BlockPos base) {
        return findSafeColumn(world, base, -3, 2);
    }

    public static BlockPos findSafeColumn(ServerWorld world, BlockPos base, int minDy, int maxDy) {
        if (world == null || base == null) {
            return null;
        }
        int min = Math.min(minDy, maxDy);
        int max = Math.max(minDy, maxDy);
        for (int dy = max; dy >= min; dy--) {
            BlockPos candidate = base.up(dy);
            if (isSpawnable(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean isSpawnable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) {
            return false;
        }
        BlockState floor = world.getBlockState(feet.down());
        return !floor.getCollisionShape(world, feet.down()).isEmpty();
    }

    public static void snapTo(ServerPlayerEntity bot, BlockPos targetFeet) {
        if (bot == null || targetFeet == null) {
            return;
        }
        Vec3d center = new Vec3d(targetFeet.getX() + 0.5, targetFeet.getY(), targetFeet.getZ() + 0.5);
        bot.refreshPositionAndAngles(center.x, center.y, center.z, bot.getYaw(), bot.getPitch());
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityDirty = true;
        bot.fallDistance = 0.0F;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Bed / spawn validation
    // ════════════════════════════════════════════════════════════════════

    /**
     * Validate that a bed still exists at the given position and has safe standing room nearby.
     * Mirrors vanilla's "bed missing or obstructed" check using public APIs only.
     *
     * @return a safe standing {@link BlockPos} adjacent to the bed, or {@code null} if the bed
     *         is destroyed or fully obstructed.
     */
    public static BlockPos validateBedSpawn(ServerWorld world, BlockPos bedPos) {
        if (world == null || bedPos == null) {
            return null;
        }
        // Check if the block is actually a bed
        BlockState state = world.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return null; // Bed destroyed
        }
        // Look for safe standing room within 2 blocks (vanilla checks adjacent blocks)
        return findSafeNear(world, bedPos, 2);
    }

    /**
     * Find a safe surface position near the given coordinates, using heightmap when the
     * simple spiral search fails (e.g. void, deep underground, or massive obstruction).
     *
     * @param fallbackRadius radius for the initial spiral search
     * @param heightmapRadius radius for the heightmap-based surface scan
     * @return a safe {@link BlockPos}, or {@code null} if no safe ground exists at all
     */
    public static BlockPos findSafeSurface(ServerWorld world, BlockPos base, int fallbackRadius, int heightmapRadius) {
        if (world == null || base == null) {
            return null;
        }
        // First try the normal near-search
        BlockPos safe = findSafeNear(world, base, fallbackRadius);
        if (safe != null) {
            return safe;
        }
        // Heightmap scan: find the top solid block at expanding offsets
        for (int r = 0; r <= heightmapRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, base.getX() + dx, base.getZ() + dz);
                    if (surfaceY <= world.getBottomY()) {
                        continue; // No surface here (void column)
                    }
                    BlockPos candidate = new BlockPos(base.getX() + dx, surfaceY, base.getZ() + dz);
                    if (isSpawnable(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos resolveSurfaceCandidate(ServerWorld world, BlockPos origin, int x, int z) {
        int surfaceFeetY = columnSurfaceFeetY(world, x, z);
        if (surfaceFeetY <= world.getBottomY()) {
            return null;
        }

        BlockPos surfaceBase = new BlockPos(x, surfaceFeetY, z);
        BlockPos surfaceSafe = findSafeColumn(world, surfaceBase, -2, 2);
        if (surfaceSafe != null) {
            return surfaceSafe;
        }

        return findSafeColumn(world, new BlockPos(x, origin.getY(), z), -4, 4);
    }

    /**
     * Return the Y where feet would stand on actual walkable ground,
     * scanning through tree trunks/leaves that fool the heightmap.
     * Uses MOTION_BLOCKING_NO_LEAVES as a ceiling hint, then scans downward
     * through log and leaf blocks to find the first solid non-vegetation block.
     */
    public static int getWalkableGroundY(ServerWorld world, int x, int z) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (topY <= world.getBottomY()) return topY;
        int y = topY;
        int minY = Math.max(world.getBottomY(), y - 32);
        while (y > minY) {
            BlockPos checkPos = new BlockPos(x, y - 1, z);
            BlockState state = world.getBlockState(checkPos);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                y--;
                continue;
            }
            if (!state.getCollisionShape(world, checkPos).isEmpty()) {
                return y; // feet stand on top of this block
            }
            y--;
        }
        return topY; // fallback to heightmap
    }

    private static int columnSurfaceFeetY(ServerWorld world, int x, int z) {
        return getWalkableGroundY(world, x, z);
    }

    private static int wideSurfaceFeetY(ServerWorld world, int x, int z) {
        // Use the median of surrounding surface samples rather than the max.
        // Max is biased upward by nearby tree trunks (MOTION_BLOCKING_NO_LEAVES
        // still counts log blocks), causing nearSurface=false under canopies
        // even when the bot is clearly at ground level.
        int[][] offsets = {{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {3, 3}, {3, -3}, {-3, 3}, {-3, -3}};
        int[] samples = new int[offsets.length + 1];
        samples[0] = columnSurfaceFeetY(world, x, z);
        for (int i = 0; i < offsets.length; i++) {
            samples[i + 1] = columnSurfaceFeetY(world, x + offsets[i][0], z + offsets[i][1]);
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2]; // median
    }

    private static boolean hasLowRoofCover(ServerWorld world, BlockPos feet, int maxBlocksAboveHead) {
        if (world == null || feet == null) {
            return false;
        }
        int max = Math.max(2, maxBlocksAboveHead);
        for (int dy = 2; dy <= max; dy++) {
            BlockPos pos = feet.up(dy);
            BlockState state = world.getBlockState(pos);
            if (state != null && !state.getCollisionShape(world, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
