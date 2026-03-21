package net.wcfcarolina13.GameAI.skills.support;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.VillageStructureProtectionService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Light-weight tree identification helpers shared by woodcutting logic.
 */
public final class TreeDetector {

    private static final int MAX_TREE_HEIGHT = 18;
    private static final int HUMAN_SCAN_RADIUS = 2;
    private static final int LEAF_SCAN_RADIUS = 3;
    private static final int VILLAGE_SCAN_RADIUS = 6;
    private static final int VILLAGE_SCAN_Y = 4;
    private static final int VILLAGER_SCAN_RADIUS_XZ = 12;
    private static final int VILLAGER_SCAN_RADIUS_Y = 5;

    private static final Set<Block> SOIL_BLOCKS = Set.of(
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.ROOTED_DIRT,
            Blocks.COARSE_DIRT,
            Blocks.MUD
    );

    private static final Set<Block> HUMAN_BLOCKS = Set.of(
            Blocks.CRAFTING_TABLE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.TORCH,
            Blocks.SOUL_TORCH,
            Blocks.REDSTONE_TORCH,
            Blocks.CAMPFIRE,
            Blocks.BELL,
            Blocks.WHITE_BED,
            Blocks.RED_BED,
            Blocks.OAK_DOOR,
            Blocks.BIRCH_DOOR,
            Blocks.SPRUCE_DOOR,
            Blocks.DARK_OAK_DOOR,
            Blocks.JUNGLE_DOOR,
            Blocks.ACACIA_DOOR,
            Blocks.MANGROVE_DOOR,
            Blocks.CHERRY_DOOR,
            Blocks.BAMBOO_DOOR
    );

    private TreeDetector() {
    }

    public record TreeTarget(BlockPos base, BlockPos top, int height) {
    }

    public static Optional<TreeTarget> findNearestTree(ServerPlayerEntity bot,
                                                       int horizontalRadius,
                                                       int verticalRange,
                                                       Set<BlockPos> visitedBases) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        BlockPos origin = bot.getBlockPos();
        double bestDistSq = Double.MAX_VALUE;
        TreeTarget best = null;

        for (BlockPos candidate : BlockPos.iterate(origin.add(-horizontalRadius, -verticalRange, -horizontalRadius),
                origin.add(horizontalRadius, verticalRange, horizontalRadius))) {
            if (visitedBases != null && visitedBases.contains(candidate)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!isLog(state)) {
                continue;
            }
            Optional<TreeTarget> target = detectTreeAt(world, candidate);
            if (target.isEmpty()) {
                continue;
            }
            TreeTarget tree = target.get();
            double distSq = origin.getSquaredDistance(tree.base());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = tree;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Fallback search for stray logs that still have leaves nearby (likely branches) even if the soil check fails.
     * Still respects human-block avoidance.
     */
    public static Optional<BlockPos> findNearestLooseLog(ServerPlayerEntity bot,
                                                         int horizontalRadius,
                                                         int verticalRange,
                                                         Set<BlockPos> visited) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        BlockPos origin = bot.getBlockPos();
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos candidate : BlockPos.iterate(origin.add(-horizontalRadius, -verticalRange, -horizontalRadius),
                origin.add(horizontalRadius, verticalRange, horizontalRadius))) {
            if (visited != null && visited.contains(candidate)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!isLog(state)) {
                continue;
            }
            if (!hasLeavesNearby(world, candidate, 4, 4)) {
                continue;
            }
            if (isNearHumanBlocks(world, candidate, 4)) {
                continue;
            }
            double distSq = origin.getSquaredDistance(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate.toImmutable();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Very permissive fallback: any log nearby that is not near human blocks or protected zones.
     */
    public static Optional<BlockPos> findNearestAnyLog(ServerPlayerEntity bot,
                                                       int horizontalRadius,
                                                       int verticalRange,
                                                       Set<BlockPos> visited) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        BlockPos origin = bot.getBlockPos();
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos candidate : BlockPos.iterate(origin.add(-horizontalRadius, -verticalRange, -horizontalRadius),
                origin.add(horizontalRadius, verticalRange, horizontalRadius))) {
            if (visited != null && visited.contains(candidate)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!isLog(state)) {
                continue;
            }
            if (isNearHumanBlocks(world, candidate, 3)) {
                continue;
            }
            if (isProtected(world, candidate, 3)) {
                continue;
            }
            double distSq = origin.getSquaredDistance(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate.toImmutable();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
        * Floating log: no adjacent logs and no nearby leaves; intended for cleanup of stray blocks (unlikely player builds).
        */
    public static Optional<BlockPos> findFloatingLog(ServerPlayerEntity bot,
                                                     int horizontalRadius,
                                                     int verticalRange,
                                                     Set<BlockPos> visited) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        BlockPos origin = bot.getBlockPos();
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos candidate : BlockPos.iterate(origin.add(-horizontalRadius, -verticalRange, -horizontalRadius),
                origin.add(horizontalRadius, verticalRange, horizontalRadius))) {
            if (visited != null && visited.contains(candidate)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!isLog(state)) {
                continue;
            }
            if (isNearHumanBlocks(world, candidate, 3) || isProtected(world, candidate, 3)) {
                continue;
            }
            boolean hasLogNeighbor = false;
            for (Direction dir : Direction.values()) {
                if (isLog(world.getBlockState(candidate.offset(dir)))) {
                    hasLogNeighbor = true;
                    break;
                }
            }
            if (hasLogNeighbor) {
                continue;
            }
            if (hasLeavesNearby(world, candidate, 2, 2)) {
                continue;
            }
            double d = origin.getSquaredDistance(candidate);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = candidate.toImmutable();
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<TreeTarget> detectTreeAt(ServerWorld world, BlockPos logPos) {
        if (world == null || logPos == null) {
            return Optional.empty();
        }
        BlockState state = world.getBlockState(logPos);
        if (!isLog(state)) {
            return Optional.empty();
        }

        BlockPos base = logPos.toImmutable();
        while (isLog(world.getBlockState(base.down())) && base.getY() > world.getBottomY()) {
            base = base.down();
        }

        BlockState below = world.getBlockState(base.down());
        if (!isValidSoil(below)) {
            // Allow re-run in branch cleanup: treat as valid if leaves present and no human blocks
            if (!hasLeavesNearby(world, base, 4, 4) || isNearHumanBlocks(world, base, 4)) {
                return Optional.empty();
            }
        }

        int height = 1;
        BlockPos cursor = base.up();
        while (height < MAX_TREE_HEIGHT && isLog(world.getBlockState(cursor))) {
            height++;
            cursor = cursor.up();
        }
        BlockPos top = cursor.down();
        if (height < 2 || height > MAX_TREE_HEIGHT) {
            return Optional.empty();
        }

        if (isProtected(world, base, height)) {
            return Optional.empty();
        }

        if (!hasLeavesNearby(world, base, height)) {
            return Optional.empty();
        }
        if (isNearHumanBlocks(world, base, height)) {
            return Optional.empty();
        }

        return Optional.of(new TreeTarget(base.toImmutable(), top.toImmutable(), height));
    }

    public static List<BlockPos> collectTrunk(ServerWorld world, BlockPos base) {
        List<BlockPos> trunk = new ArrayList<>();
        if (world == null || base == null) {
            return trunk;
        }
        BlockPos cursor = base.toImmutable();
        while (isLog(world.getBlockState(cursor))) {
            trunk.add(cursor);
            cursor = cursor.up();
        }
        return trunk;
    }

    public static List<BlockPos> collectConnectedLogs(ServerWorld world,
                                                      BlockPos start,
                                                      int horizontalLimit,
                                                      int verticalLimit) {
        List<BlockPos> found = new ArrayList<>();
        if (world == null || start == null) {
            return found;
        }
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (!withinLimits(start, neighbor, horizontalLimit, verticalLimit)) {
                    continue;
                }
                if (isLog(world.getBlockState(neighbor))) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return found;
    }

    private static boolean withinLimits(BlockPos origin, BlockPos candidate, int horizontal, int vertical) {
        return Math.abs(candidate.getX() - origin.getX()) <= horizontal
                && Math.abs(candidate.getZ() - origin.getZ()) <= horizontal
                && Math.abs(candidate.getY() - origin.getY()) <= vertical;
    }

    private static boolean hasLeavesNearby(ServerWorld world, BlockPos base, int height) {
        BlockPos min = base.add(-LEAF_SCAN_RADIUS, -1, -LEAF_SCAN_RADIUS);
        BlockPos max = base.add(LEAF_SCAN_RADIUS, height + 2, LEAF_SCAN_RADIUS);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (world.getBlockState(pos).isIn(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasLeavesNearby(ServerWorld world, BlockPos base, int horizontalRadius, int verticalRadius) {
        BlockPos min = base.add(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = base.add(horizontalRadius, verticalRadius + 2, horizontalRadius);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (world.getBlockState(pos).isIn(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNearHumanBlocks(ServerWorld world, BlockPos base, int height) {
        BlockPos min = base.add(-HUMAN_SCAN_RADIUS, -1, -HUMAN_SCAN_RADIUS);
        BlockPos max = base.add(HUMAN_SCAN_RADIUS, height + 2, HUMAN_SCAN_RADIUS);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (state.isIn(BlockTags.PLANKS)
                    || state.isIn(BlockTags.BEDS)
                    || state.isIn(BlockTags.WOODEN_DOORS)
                    || state.isIn(BlockTags.WOODEN_PRESSURE_PLATES)
                    || state.isIn(BlockTags.FENCES)
                    || HUMAN_BLOCKS.contains(block)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProtected(ServerWorld world, BlockPos pos) {
        return isProtected(world, pos, 4);
    }

    public static boolean isProtected(ServerWorld world, BlockPos pos, int height) {
        if (world == null || pos == null) {
            return false;
        }
        return CompanionSafeZoneService.isProtected(world, pos, null)
                || isInsideBaseProtectionRadius(world, pos)
                || VillageStructureProtectionService.isVillageProtectedForGenericBreaking(world, pos)
                || isVillageProtected(world, pos, height);
    }

    private static boolean isLog(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isIn(BlockTags.LOGS)
                || state.isOf(Blocks.MANGROVE_ROOTS)
                || state.isOf(Blocks.MANGROVE_LOG)
                || state.isOf(Blocks.CHERRY_WOOD)
                || state.isOf(Blocks.CHERRY_LOG);
    }

    public static boolean isValidSoil(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isIn(BlockTags.DIRT) || SOIL_BLOCKS.contains(state.getBlock());
    }

    private static boolean isInsideBaseProtectionRadius(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || world.getServer() == null) {
            return false;
        }
        for (BotHomeService.BaseEntry base : BotHomeService.listBases(world.getServer(), world)) {
            if (base == null || base.pos() == null) {
                continue;
            }
            int radius = Math.max(1, base.radius());
            if (VecHelper.withinSquaredDistance(pos, base.pos(), radius * radius)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVillageProtected(ServerWorld world, BlockPos pos, int height) {
        if (world == null || pos == null) {
            return false;
        }
        int scanHeight = Math.max(3, Math.min(MAX_TREE_HEIGHT, height + 2));
        int signalBlocks = 0;
        boolean bellNearby = false;
        boolean bedNearby = false;
        BlockPos min = pos.add(-VILLAGE_SCAN_RADIUS, -1, -VILLAGE_SCAN_RADIUS);
        BlockPos max = pos.add(VILLAGE_SCAN_RADIUS, Math.max(VILLAGE_SCAN_Y, scanHeight), VILLAGE_SCAN_RADIUS);
        for (BlockPos sample : BlockPos.iterate(min, max)) {
            BlockState state = world.getBlockState(sample);
            if (state.isOf(Blocks.BELL)) {
                bellNearby = true;
                continue;
            }
            if (state.isIn(BlockTags.BEDS)) {
                bedNearby = true;
                continue;
            }
            if (isVillageSignalBlock(state)) {
                signalBlocks++;
                if (signalBlocks >= 3) {
                    return true;
                }
            }
        }

        if (!bellNearby && !bedNearby && signalBlocks == 0) {
            return false;
        }

        int villagers = countNearbyVillagers(world, pos);
        if ((bellNearby || bedNearby) && villagers >= 1) {
            return true;
        }
        return signalBlocks >= 2 && (bellNearby || bedNearby || villagers >= 2);
    }

    private static int countNearbyVillagers(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0;
        }
        Box box = new Box(pos).expand(VILLAGER_SCAN_RADIUS_XZ, VILLAGER_SCAN_RADIUS_Y, VILLAGER_SCAN_RADIUS_XZ);
        return world.getEntitiesByClass(VillagerEntity.class, box, villager -> villager != null && villager.isAlive()).size();
    }

    private static boolean isVillageSignalBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.isOf(Blocks.BELL) || state.isIn(BlockTags.BEDS)) {
            return true;
        }
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        return VillageFortificationLayoutService.isVillageStructureBlock(state.getBlock());
    }

    private static final class VecHelper {
        private static boolean withinSquaredDistance(BlockPos a, BlockPos b, double radiusSq) {
            return a != null && b != null && a.getSquaredDistance(b) <= radiusSq;
        }
    }
}
