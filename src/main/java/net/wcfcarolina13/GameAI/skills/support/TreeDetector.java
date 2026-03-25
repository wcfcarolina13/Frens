package net.wcfcarolina13.GameAI.skills.support;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int TREE_ENVELOPE_SCAN_HORIZONTAL = 7;
    private static final int TREE_ENVELOPE_SCAN_VERTICAL_ABOVE = 6;
    private static final int TREE_ENVELOPE_SCAN_VERTICAL_BELOW = 2;
    private static final int TREE_ENVELOPE_LEAF_NEIGHBOR_RADIUS = 2;

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

    public record TreeTarget(BlockPos base,
                             BlockPos top,
                             int height,
                             BlockPos envelopeMin,
                             BlockPos envelopeMax,
                             Set<BlockPos> nearbyRootedBases,
                             Set<BlockPos> canopyLeaves) {
        public TreeTarget {
            nearbyRootedBases = nearbyRootedBases == null ? Set.of() : Set.copyOf(nearbyRootedBases);
            canopyLeaves = canopyLeaves == null ? Set.of() : Set.copyOf(canopyLeaves);
        }

        public TreeTarget(BlockPos base, BlockPos top, int height) {
            this(
                    base,
                    top,
                    height,
                    base.add(-1, -1, -1).toImmutable(),
                    top.add(1, 1, 1).toImmutable(),
                    Set.of(),
                    Set.of()
            );
        }
    }

    public enum TreeDetectionMode {
        STRICT_STANDING,
        LOOSE_FRAGMENT
    }

    public record WoodcutProtectionDecision(boolean blocked, String reason) {
    }

    public enum CleanupLogDispositionKind {
        REMEMBERED_LEFTOVER,
        LONE_FLOATING,
        SUSPENDED_FRAGMENT,
        ORPHAN_CLUSTER,
        BLOCKED_PROTECTED,
        BLOCKED_HUMAN_ADJACENT,
        REJECTED_FULL_TREE,
        REJECTED_GROUNDED_OR_NON_FLOATER,
        REJECTED_NON_LOG
    }

    public record CleanupLogDisposition(CleanupLogDispositionKind kind, boolean actionable, String reason) {
        public boolean blockedProtected() {
            return kind == CleanupLogDispositionKind.BLOCKED_PROTECTED;
        }

        public boolean blockedHumanAdjacent() {
            return kind == CleanupLogDispositionKind.BLOCKED_HUMAN_ADJACENT;
        }

        public boolean rejectedFullTree() {
            return kind == CleanupLogDispositionKind.REJECTED_FULL_TREE;
        }

        public boolean rejectedGroundedOrNonFloater() {
            return kind == CleanupLogDispositionKind.REJECTED_GROUNDED_OR_NON_FLOATER;
        }

        public boolean floaterLike() {
            return kind != CleanupLogDispositionKind.REJECTED_NON_LOG;
        }
    }

    private record BasicTreeInfo(BlockPos base, BlockPos top, int height) {
    }

    private record CleanupComponentProfile(int size,
                                           boolean grounded,
                                           boolean leavesNearby,
                                           boolean unsupported,
                                           boolean tooLarge) {
    }

    private static final WoodcutProtectionDecision WOODCUT_ALLOWED =
            new WoodcutProtectionDecision(false, "none");

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
        return detectTreeAtCore(world, logPos, TreeDetectionMode.STRICT_STANDING)
                .filter(t -> !isProtected(world, t.base(), t.height()));
    }

    /**
     * Like {@link #detectTreeAt} but uses the woodcut-specific protection check
     * that allows trees within the base protection radius (trees are resources).
     */
    public static Optional<TreeTarget> detectTreeAtForWoodcut(ServerWorld world, BlockPos logPos) {
        return detectTreeAtForWoodcut(world, logPos, TreeDetectionMode.STRICT_STANDING)
                .filter(t -> !getWoodcutProtectionDecision(world, t.base(), Math.max(4, t.height())).blocked());
    }

    public static Optional<TreeTarget> detectTreeAtForWoodcut(ServerWorld world,
                                                              BlockPos logPos,
                                                              TreeDetectionMode mode) {
        return detectTreeAtCore(world, logPos, mode)
                .filter(t -> !getWoodcutProtectionDecision(world, t.base(), Math.max(4, t.height())).blocked());
    }

    private static Optional<TreeTarget> detectTreeAtCore(ServerWorld world,
                                                         BlockPos logPos,
                                                         TreeDetectionMode mode) {
        Optional<BasicTreeInfo> basicOpt = resolveStandingTreeBasic(world, logPos, mode);
        if (basicOpt.isEmpty()) {
            return Optional.empty();
        }
        BasicTreeInfo basic = basicOpt.get();
        return Optional.of(buildTreeTarget(world, basic));
    }

    private static Optional<BasicTreeInfo> resolveStandingTreeBasic(ServerWorld world,
                                                                    BlockPos logPos,
                                                                    TreeDetectionMode mode) {
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
            if (mode == TreeDetectionMode.STRICT_STANDING) {
                return Optional.empty();
            }
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

        if (!hasLeavesNearby(world, base, height)) {
            return Optional.empty();
        }
        if (isNearHumanBlocks(world, base, height)) {
            return Optional.empty();
        }

        return Optional.of(new BasicTreeInfo(base.toImmutable(), top.toImmutable(), height));
    }

    private static TreeTarget buildTreeTarget(ServerWorld world, BasicTreeInfo basic) {
        Set<BlockPos> nearbyRootedBases = findNearbyRootedBases(world, basic);
        Set<BlockPos> canopyLeaves = collectLeafEnvelope(world, basic, nearbyRootedBases);
        BlockPos envelopeMin = basic.base().add(-1, -1, -1);
        BlockPos envelopeMax = basic.top().add(1, 1, 1);
        if (!canopyLeaves.isEmpty()) {
            int minX = envelopeMin.getX();
            int minY = envelopeMin.getY();
            int minZ = envelopeMin.getZ();
            int maxX = envelopeMax.getX();
            int maxY = envelopeMax.getY();
            int maxZ = envelopeMax.getZ();
            for (BlockPos leaf : canopyLeaves) {
                minX = Math.min(minX, leaf.getX() - 1);
                minY = Math.min(minY, leaf.getY() - 1);
                minZ = Math.min(minZ, leaf.getZ() - 1);
                maxX = Math.max(maxX, leaf.getX() + 1);
                maxY = Math.max(maxY, leaf.getY() + 1);
                maxZ = Math.max(maxZ, leaf.getZ() + 1);
            }
            envelopeMin = new BlockPos(minX, minY, minZ);
            envelopeMax = new BlockPos(maxX, maxY, maxZ);
        }
        return new TreeTarget(
                basic.base(),
                basic.top(),
                basic.height(),
                envelopeMin.toImmutable(),
                envelopeMax.toImmutable(),
                nearbyRootedBases,
                canopyLeaves
        );
    }

    private static Set<BlockPos> findNearbyRootedBases(ServerWorld world, BasicTreeInfo selected) {
        Set<BlockPos> bases = new HashSet<>();
        if (world == null || selected == null) {
            return bases;
        }
        BlockPos scanMin = selected.base().add(-TREE_ENVELOPE_SCAN_HORIZONTAL, -1, -TREE_ENVELOPE_SCAN_HORIZONTAL);
        BlockPos scanMax = new BlockPos(
                selected.base().getX() + TREE_ENVELOPE_SCAN_HORIZONTAL,
                selected.top().getY() + TREE_ENVELOPE_SCAN_VERTICAL_ABOVE,
                selected.base().getZ() + TREE_ENVELOPE_SCAN_HORIZONTAL
        );
        for (BlockPos candidate : BlockPos.iterate(scanMin, scanMax)) {
            if (!isLog(world.getBlockState(candidate))) {
                continue;
            }
            Optional<BasicTreeInfo> infoOpt = resolveStandingTreeBasic(world, candidate, TreeDetectionMode.STRICT_STANDING);
            if (infoOpt.isEmpty()) {
                continue;
            }
            BasicTreeInfo info = infoOpt.get();
            if (!info.base().equals(selected.base())) {
                bases.add(info.base().toImmutable());
            }
        }
        return bases;
    }

    private static Set<BlockPos> collectLeafEnvelope(ServerWorld world,
                                                     BasicTreeInfo selected,
                                                     Set<BlockPos> nearbyRootedBases) {
        Set<BlockPos> leaves = new HashSet<>();
        if (world == null || selected == null) {
            return leaves;
        }
        BlockPos scanMin = selected.base().add(-TREE_ENVELOPE_SCAN_HORIZONTAL, -TREE_ENVELOPE_SCAN_VERTICAL_BELOW, -TREE_ENVELOPE_SCAN_HORIZONTAL);
        BlockPos scanMax = new BlockPos(
                selected.base().getX() + TREE_ENVELOPE_SCAN_HORIZONTAL,
                selected.top().getY() + TREE_ENVELOPE_SCAN_VERTICAL_ABOVE,
                selected.base().getZ() + TREE_ENVELOPE_SCAN_HORIZONTAL
        );
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        for (int y = selected.base().getY(); y <= selected.top().getY(); y++) {
            BlockPos trunk = new BlockPos(selected.base().getX(), y, selected.base().getZ());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos seed = trunk.add(dx, dy, dz);
                        if (!isWithinBounds(seed, scanMin, scanMax)) {
                            continue;
                        }
                        if (!world.getBlockState(seed).isIn(BlockTags.LEAVES)) {
                            continue;
                        }
                        if (!leafBelongsToSelectedTree(seed, selected.base(), nearbyRootedBases)) {
                            continue;
                        }
                        if (visited.add(seed.toImmutable())) {
                            queue.add(seed.toImmutable());
                            leaves.add(seed.toImmutable());
                        }
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = pos.add(dx, dy, dz);
                        if (!isWithinBounds(neighbor, scanMin, scanMax)) {
                            continue;
                        }
                        if (!world.getBlockState(neighbor).isIn(BlockTags.LEAVES)) {
                            continue;
                        }
                        if (!leafBelongsToSelectedTree(neighbor, selected.base(), nearbyRootedBases)) {
                            continue;
                        }
                        if (visited.add(neighbor.toImmutable())) {
                            queue.add(neighbor.toImmutable());
                            leaves.add(neighbor.toImmutable());
                        }
                    }
                }
            }
        }

        return leaves;
    }

    private static boolean leafBelongsToSelectedTree(BlockPos leaf,
                                                     BlockPos selectedBase,
                                                     Set<BlockPos> nearbyRootedBases) {
        if (leaf == null || selectedBase == null) {
            return false;
        }
        double selectedDistSq = horizontalDistanceSq(leaf, selectedBase);
        for (BlockPos otherBase : nearbyRootedBases) {
            if (otherBase == null) {
                continue;
            }
            if (Math.abs(leaf.getX() - otherBase.getX()) <= 1
                    && Math.abs(leaf.getZ() - otherBase.getZ()) <= 1
                    && leaf.getY() >= otherBase.getY() - 1) {
                return false;
            }
            double otherDistSq = horizontalDistanceSq(leaf, otherBase);
            if (otherDistSq + 1.0D < selectedDistSq) {
                return false;
            }
        }
        return true;
    }

    public static List<BlockPos> collectOwnedTreeLogs(ServerWorld world, TreeTarget target) {
        List<BlockPos> owned = new ArrayList<>();
        if (world == null || target == null) {
            return owned;
        }
        BlockPos min = target.envelopeMin().add(-1, -1, -1);
        BlockPos max = target.envelopeMax().add(1, 1, 1);
        for (BlockPos candidate : BlockPos.iterate(min, max)) {
            if (isOwnedTreeLog(world, target, candidate)) {
                owned.add(candidate.toImmutable());
            }
        }
        return owned;
    }

    public static boolean isOwnedTreeLog(ServerWorld world, TreeTarget target, BlockPos candidate) {
        if (world == null || target == null || candidate == null) {
            return false;
        }
        if (!isLog(world.getBlockState(candidate))) {
            return false;
        }
        if (!isWithinBounds(candidate, target.envelopeMin().add(-1, -1, -1), target.envelopeMax().add(1, 1, 1))) {
            return false;
        }
        Optional<BasicTreeInfo> strictOwner = resolveStandingTreeBasic(world, candidate, TreeDetectionMode.STRICT_STANDING);
        if (strictOwner.isPresent() && !strictOwner.get().base().equals(target.base())) {
            return false;
        }
        for (BlockPos otherBase : target.nearbyRootedBases()) {
            if (Math.abs(candidate.getX() - otherBase.getX()) <= 1
                    && Math.abs(candidate.getZ() - otherBase.getZ()) <= 1
                    && candidate.getY() >= otherBase.getY() - 1) {
                return false;
            }
        }
        if (Math.abs(candidate.getX() - target.base().getX()) <= 1
                && Math.abs(candidate.getZ() - target.base().getZ()) <= 1
                && candidate.getY() >= target.base().getY()
                && candidate.getY() <= target.top().getY() + 1) {
            return true;
        }
        if (target.canopyLeaves().isEmpty()) {
            return Math.abs(candidate.getX() - target.base().getX()) <= 2
                    && Math.abs(candidate.getZ() - target.base().getZ()) <= 2
                    && candidate.getY() >= target.base().getY() - 1
                    && candidate.getY() <= target.top().getY() + TREE_ENVELOPE_SCAN_VERTICAL_ABOVE;
        }
        for (BlockPos leaf : target.canopyLeaves()) {
            if (Math.abs(candidate.getX() - leaf.getX()) <= TREE_ENVELOPE_LEAF_NEIGHBOR_RADIUS
                    && Math.abs(candidate.getY() - leaf.getY()) <= TREE_ENVELOPE_LEAF_NEIGHBOR_RADIUS
                    && Math.abs(candidate.getZ() - leaf.getZ()) <= TREE_ENVELOPE_LEAF_NEIGHBOR_RADIUS) {
                return true;
            }
        }
        return false;
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

    private static boolean isWithinBounds(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= Math.min(min.getX(), max.getX())
                && pos.getX() <= Math.max(min.getX(), max.getX())
                && pos.getY() >= Math.min(min.getY(), max.getY())
                && pos.getY() <= Math.max(min.getY(), max.getY())
                && pos.getZ() >= Math.min(min.getZ(), max.getZ())
                && pos.getZ() <= Math.max(min.getZ(), max.getZ());
    }

    private static double horizontalDistanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
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
        return CompanionSafeZoneService.isProtected(world, pos, null);
    }

    /**
     * Like {@link #isProtected} but skips the base protection radius check.
     * Base protection is meant to prevent structure destruction, not resource gathering.
     * Trees are resources — bots should be able to cut them near their base.
     */
    public static boolean isProtectedForWoodcut(ServerWorld world, BlockPos pos, int height) {
        return getWoodcutProtectionDecision(world, pos, height).blocked();
    }

    public static WoodcutProtectionDecision getWoodcutProtectionDecision(ServerWorld world, BlockPos pos, int height) {
        if (world == null || pos == null) {
            return WOODCUT_ALLOWED;
        }
        return VillageRuntimeProtectionPolicy.decideWoodcutProtection(
                CompanionSafeZoneService.isInExplicitProtectedZone(world, pos, null),
                CompanionSafeZoneService.isNearSavedBase(world, pos),
                CompanionSafeZoneService.isInsideFortificationZone(world, pos),
                MappedVillageService.isInsideMappedVillage(world, pos)
        );
    }

    public static CleanupLogDisposition classifyCleanupLog(ServerWorld world, BlockPos pos, boolean rememberedLeftover) {
        if (world == null || pos == null || !world.getBlockState(pos).isIn(BlockTags.LOGS)) {
            return new CleanupLogDisposition(CleanupLogDispositionKind.REJECTED_NON_LOG, false, "not-log");
        }

        if (rememberedLeftover) {
            WoodcutProtectionDecision decision = getWoodcutProtectionDecision(world, pos, 4);
            return classifyCleanupLogFromSignals(
                    true,
                    decision.blocked(),
                    "protected-woodcut:" + decision.reason(),
                    false,
                    false,
                    false,
                    0,
                    false,
                    false,
                    false,
                    false
            );
        }

        boolean humanAdjacent = isNearHumanBlocks(world, pos, 3);
        WoodcutProtectionDecision protectionDecision = getWoodcutProtectionDecision(world, pos, 4);
        boolean protectedTarget = protectionDecision.blocked();
        boolean fullTree = false;
        try {
            fullTree = detectTreeAtForWoodcut(world, pos, TreeDetectionMode.STRICT_STANDING).isPresent();
        } catch (Exception ignored) {
        }

        boolean loneFloating = isCleanupLoneFloatingLog(world, pos);
        CleanupComponentProfile profile = inspectCleanupLogComponent(world, pos, 64);
        return classifyCleanupLogFromSignals(
                false,
                protectedTarget,
                "protected-woodcut:" + protectionDecision.reason(),
                humanAdjacent,
                fullTree,
                loneFloating,
                profile.size(),
                profile.grounded(),
                profile.leavesNearby(),
                profile.unsupported(),
                profile.tooLarge()
        );
    }

    static CleanupLogDisposition classifyCleanupLogFromSignals(boolean rememberedLeftover,
                                                               boolean blockedProtected,
                                                               String protectedReason,
                                                               boolean humanAdjacent,
                                                               boolean fullTree,
                                                               boolean loneFloating,
                                                               int componentSize,
                                                               boolean componentGrounded,
                                                               boolean componentLeavesNearby,
                                                               boolean componentUnsupported,
                                                               boolean componentTooLarge) {
        TreeCleanupClassifier.CleanupLogDisposition disposition = TreeCleanupClassifier.classifyFromSignals(
                rememberedLeftover,
                blockedProtected,
                protectedReason,
                humanAdjacent,
                fullTree,
                loneFloating,
                componentSize,
                componentGrounded,
                componentLeavesNearby,
                componentUnsupported,
                componentTooLarge
        );
        return new CleanupLogDisposition(
                CleanupLogDispositionKind.valueOf(disposition.kind().name()),
                disposition.actionable(),
                disposition.reason()
        );
    }

    private static boolean isCleanupLoneFloatingLog(ServerWorld world, BlockPos candidate) {
        if (world == null || candidate == null) {
            return false;
        }
        if (hasLeavesNearby(world, candidate, 2, 2)) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            if (world.getBlockState(candidate.offset(dir)).isIn(BlockTags.LOGS)) {
                return false;
            }
        }
        return true;
    }

    private static CleanupComponentProfile inspectCleanupLogComponent(ServerWorld world, BlockPos start, int limit) {
        if (world == null || start == null || !world.getBlockState(start).isIn(BlockTags.LOGS)) {
            return new CleanupComponentProfile(0, false, false, false, false);
        }
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start.toImmutable());

        boolean grounded = false;
        boolean leavesNearby = false;
        boolean unsupported = false;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            leavesNearby = leavesNearby || hasLeavesNearby(world, pos, 3, 3);

            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);
            boolean belowIsLog = belowState.isIn(BlockTags.LOGS);
            boolean belowIsSolid = !belowState.getCollisionShape(world, below).isEmpty() && !belowState.isIn(BlockTags.LEAVES);
            boolean belowUnsupported = belowState.isAir()
                    || belowState.isReplaceable()
                    || belowState.isIn(BlockTags.LEAVES)
                    || belowState.getCollisionShape(world, below).isEmpty();

            if (!belowIsLog && belowIsSolid) {
                grounded = true;
            }
            if (!belowIsLog && belowUnsupported) {
                unsupported = true;
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor) || !world.getBlockState(neighbor).isIn(BlockTags.LOGS)) {
                    continue;
                }
                BlockPos immutable = neighbor.toImmutable();
                visited.add(immutable);
                if (visited.size() > limit) {
                    return new CleanupComponentProfile(visited.size(), grounded, leavesNearby, unsupported, true);
                }
                queue.add(immutable);
            }
        }

        return new CleanupComponentProfile(visited.size(), grounded, leavesNearby, unsupported, false);
    }

    /**
     * Searches an annular ring for the nearest unprotected tree suitable for woodcutting.
     * When {@code facingX == 0 && facingZ == 0} the full 360 degrees are searched;
     * otherwise the search is restricted to a +/-60 degree forward cone.
     *
     * @return the base position of the nearest unprotected tree, or null
     */
    public static BlockPos findNearestUnprotectedTreeInRing(
            ServerWorld world, BlockPos origin,
            int innerRadius, int outerRadius, int verticalRange,
            double facingX, double facingZ) {
        if (world == null || origin == null) {
            return null;
        }
        boolean useDirectionalCone = (facingX != 0.0 || facingZ != 0.0);
        int innerSq = innerRadius * innerRadius;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;

        for (BlockPos candidate : BlockPos.iterate(
                origin.add(-outerRadius, -verticalRange, -outerRadius),
                origin.add(outerRadius, verticalRange, outerRadius))) {
            double distSq = origin.getSquaredDistance(candidate);
            if (distSq <= innerSq || distSq >= bestDistSq) continue;
            if (!world.isChunkLoaded(candidate)) continue;

            if (useDirectionalCone) {
                double dx = candidate.getX() - origin.getX();
                double dz = candidate.getZ() - origin.getZ();
                double dist = Math.sqrt(distSq);
                double dot = (dx * facingX + dz * facingZ) / dist;
                if (dot < 0.5) continue; // outside +/-60 degree forward cone
            }

            BlockState state = world.getBlockState(candidate);
            if (!state.isIn(BlockTags.LOGS)) continue;

            var treeOpt = detectTreeAtForWoodcut(world, candidate);
            if (treeOpt.isEmpty()) continue;

            bestDistSq = distSq;
            best = treeOpt.get().base();
        }
        return best;
    }

    public static BlockPos findNearestUnprotectedTreeNearby(ServerWorld world,
                                                            BlockPos origin,
                                                            int horizontalRadius,
                                                            int verticalRange) {
        return findNearestUnprotectedTreeNearby(world, origin, horizontalRadius, verticalRange, null);
    }

    public static BlockPos findNearestUnprotectedTreeNearby(ServerWorld world,
                                                            BlockPos origin,
                                                            int horizontalRadius,
                                                            int verticalRange,
                                                            Set<BlockPos> excludedBases) {
        if (world == null || origin == null) {
            return null;
        }
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        for (BlockPos candidate : BlockPos.iterate(
                origin.add(-horizontalRadius, -verticalRange, -horizontalRadius),
                origin.add(horizontalRadius, verticalRange, horizontalRadius))) {
            if (!world.isChunkLoaded(candidate)) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            var treeOpt = detectTreeAtForWoodcut(world, candidate);
            if (treeOpt.isEmpty()) {
                continue;
            }
            TreeTarget tree = treeOpt.get();
            if (excludedBases != null && excludedBases.contains(tree.base())) {
                continue;
            }
            double distSq = origin.getSquaredDistance(tree.base());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = tree.base();
            }
        }
        return best;
    }

    public static BlockPos findNearestUnprotectedTreeBySampling(ServerWorld world,
                                                                BlockPos origin,
                                                                int innerRadius,
                                                                int outerRadius,
                                                                int verticalRange,
                                                                long timeBudgetMs,
                                                                int radiusStep,
                                                                int minSamplesPerRing) {
        return findNearestUnprotectedTreeBySampling(
                world, origin, innerRadius, outerRadius, verticalRange, timeBudgetMs, radiusStep, minSamplesPerRing, null);
    }

    public static BlockPos findNearestUnprotectedTreeBySampling(ServerWorld world,
                                                                BlockPos origin,
                                                                int innerRadius,
                                                                int outerRadius,
                                                                int verticalRange,
                                                                long timeBudgetMs,
                                                                int radiusStep,
                                                                int minSamplesPerRing,
                                                                Set<BlockPos> excludedBases) {
        if (world == null || origin == null) {
            return null;
        }
        long deadline = System.nanoTime() + Math.max(1L, timeBudgetMs) * 1_000_000L;
        int startRadius = Math.max(Math.max(1, innerRadius + 1), Math.max(1, radiusStep));
        int innerSq = Math.max(0, innerRadius * innerRadius);
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        Set<Long> seenBases = new HashSet<>();

        for (int radius = startRadius; radius <= outerRadius; radius += Math.max(1, radiusStep)) {
            int samples = Math.max(minSamplesPerRing, Math.min(48, radius * 2));
            for (int sample = 0; sample < samples; sample++) {
                if (System.nanoTime() > deadline) {
                    return best;
                }
                double angle = (Math.PI * 2.0D * sample) / samples;
                int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos tree = findTreeNearColumnForWoodcut(world, origin, x, z, verticalRange, seenBases, excludedBases);
                if (tree == null) {
                    continue;
                }
                double distSq = origin.getSquaredDistance(tree);
                if (distSq <= innerSq || distSq >= bestDistSq) {
                    continue;
                }
                bestDistSq = distSq;
                best = tree.toImmutable();
            }
        }
        return best;
    }

    public static String summarizeProtectionReasons(Map<String, Integer> reasonCounts) {
        if (reasonCounts == null || reasonCounts.isEmpty()) {
            return "none";
        }
        return reasonCounts.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.getValue(), a.getValue());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return a.getKey().compareToIgnoreCase(b.getKey());
                })
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
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

    private static BlockPos findTreeNearColumnForWoodcut(ServerWorld world,
                                                         BlockPos origin,
                                                         int x,
                                                         int z,
                                                         int verticalRange,
                                                         Set<Long> seenBases,
                                                         Set<BlockPos> excludedBases) {
        BlockPos column = new BlockPos(x, origin.getY(), z);
        if (!world.isChunkLoaded(column)) {
            return null;
        }
        int minY = Math.max(world.getBottomY(), origin.getY() - Math.max(1, verticalRange));
        int maxY = Math.min(world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 2,
                origin.getY() + Math.max(1, verticalRange));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                for (int y = maxY; y >= minY; y--) {
                    BlockPos candidate = new BlockPos(cx, y, cz);
                    if (!world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    Optional<TreeTarget> treeOpt = detectTreeAtForWoodcut(world, candidate);
                    if (treeOpt.isEmpty()) {
                        continue;
                    }
                    BlockPos base = treeOpt.get().base().toImmutable();
                    if (excludedBases != null && excludedBases.contains(base)) {
                        continue;
                    }
                    if (seenBases != null && !seenBases.add(base.asLong())) {
                        continue;
                    }
                    return base;
                }
            }
        }
        return null;
    }

}
