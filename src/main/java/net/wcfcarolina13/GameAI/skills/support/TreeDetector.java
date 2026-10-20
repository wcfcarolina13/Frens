package net.wcfcarolina13.GameAI.skills.support;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import net.wcfcarolina13.GameAI.services.ChunkBlockReader;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Light-weight tree identification helpers shared by woodcutting logic.
 */
public final class TreeDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("tree-detector");
    private static final int MAX_TREE_HEIGHT = 18;
    private static final int HUMAN_SCAN_RADIUS = 2;
    private static final int LEAF_SCAN_RADIUS = 3;
    private static final int TREE_ENVELOPE_SCAN_HORIZONTAL = 7;
    private static final int TREE_ENVELOPE_SCAN_VERTICAL_ABOVE = 6;
    private static final int TREE_ENVELOPE_SCAN_VERTICAL_BELOW = 2;

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
                             Map<BlockPos, Integer> nearbyRootedBases,
                             Set<BlockPos> canopyLeaves) {
        public TreeTarget {
            nearbyRootedBases = nearbyRootedBases == null ? Map.of() : Map.copyOf(nearbyRootedBases);
            canopyLeaves = canopyLeaves == null ? Set.of() : Set.copyOf(canopyLeaves);
        }

        public TreeTarget(BlockPos base, BlockPos top, int height) {
            this(
                    base,
                    top,
                    height,
                    base.add(-1, -1, -1).toImmutable(),
                    top.add(1, 1, 1).toImmutable(),
                    Map.of(),
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

        public boolean rejectedFullTree() {
            return kind == CleanupLogDispositionKind.REJECTED_FULL_TREE;
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

    public static Optional<TreeTarget> detectTreeAt(ServerWorld world, BlockPos logPos) {
        if (world == null) {
            return Optional.empty();
        }
        return detectTreeAtCore(new ChunkBlockReader(world), logPos, TreeDetectionMode.STRICT_STANDING)
                .filter(t -> !isProtected(world, t.base(), t.height()));
    }

    /**
     * Like {@link #detectTreeAt} but uses the woodcut-specific protection check
     * that allows trees within the base protection radius (trees are resources).
     */
    public static Optional<TreeTarget> detectTreeAtForWoodcut(ServerWorld world, BlockPos logPos) {
        return detectTreeAtForWoodcut(world, logPos, TreeDetectionMode.STRICT_STANDING);
    }

    public static Optional<TreeTarget> detectTreeAtForWoodcut(ServerWorld world,
                                                              BlockPos logPos,
                                                              TreeDetectionMode mode) {
        if (world == null) {
            return Optional.empty();
        }
        return detectTreeAtForWoodcut(new ChunkBlockReader(world), logPos, mode);
    }

    /**
     * {@link #detectTreeAtForWoodcut(ServerWorld, BlockPos, TreeDetectionMode)} through a scan's
     * chunk reader: every block read is a palette lookup instead of a server round trip from a
     * worker. A cell in an unloaded chunk reads as no log, no leaf, no soil.
     */
    public static Optional<TreeTarget> detectTreeAtForWoodcut(ChunkBlockReader reader,
                                                              BlockPos logPos,
                                                              TreeDetectionMode mode) {
        if (reader == null) {
            return Optional.empty();
        }
        ServerWorld world = reader.world();
        return detectTreeAtCore(reader, logPos, mode)
                .filter(t -> !getWoodcutProtectionDecision(world, t.base(), Math.max(4, t.height())).blocked());
    }

    /**
     * One woodcut detect scan's tree memo (1.1.268 finding 4), strict and loose kept apart. Every
     * log of one trunk resolves to the same tree, so the scan builds it once per base. Never kept
     * across scans: another bot cuts between them.
     */
    public static final class DetectScanMemo {
        private final BaseVerdictMemo<Optional<TreeTarget>> strict = new BaseVerdictMemo<>();
        private final BaseVerdictMemo<Optional<TreeTarget>> loose = new BaseVerdictMemo<>();

        /** Distinct trunk bases the strict detect resolved (empty verdicts included). */
        public int strictBases() {
            return strict.misses();
        }

        /** Detect calls (strict and loose) answered from the memo. */
        public int hits() {
            return strict.hits() + loose.hits();
        }
    }

    /**
     * {@link #detectTreeAtForWoodcut(ServerWorld, BlockPos, TreeDetectionMode)} with a scan memo
     * ({@code null} for none). The answer is a function of the trunk base alone: the resolver
     * reads {@code logPos} only to check it is a log, then works from {@link #trunkBaseOf}, and the
     * tree build and protection check read only the resolved trunk. So it is cached per base and
     * mode.
     */
    public static Optional<TreeTarget> detectTreeAtForWoodcut(ServerWorld world,
                                                              BlockPos logPos,
                                                              TreeDetectionMode mode,
                                                              DetectScanMemo memo) {
        if (world == null) {
            return Optional.empty();
        }
        return detectTreeAtForWoodcut(new ChunkBlockReader(world), logPos, mode, memo);
    }

    /** {@link #detectTreeAtForWoodcut(ServerWorld, BlockPos, TreeDetectionMode, DetectScanMemo)} through a scan's chunk reader. */
    public static Optional<TreeTarget> detectTreeAtForWoodcut(ChunkBlockReader reader,
                                                              BlockPos logPos,
                                                              TreeDetectionMode mode,
                                                              DetectScanMemo memo) {
        if (memo == null || reader == null || logPos == null || !isLog(reader.state(logPos))) {
            return detectTreeAtForWoodcut(reader, logPos, mode);
        }
        BaseVerdictMemo<Optional<TreeTarget>> cache = mode == TreeDetectionMode.STRICT_STANDING ? memo.strict : memo.loose;
        return cache.computeIfAbsent(trunkBaseOf(reader, logPos).asLong(),
                () -> detectTreeAtForWoodcut(reader, logPos, mode));
    }

    private static Optional<TreeTarget> detectTreeAtCore(ChunkBlockReader reader,
                                                         BlockPos logPos,
                                                         TreeDetectionMode mode) {
        Optional<BasicTreeInfo> basicOpt = resolveStandingTreeBasic(reader, logPos, mode);
        if (basicOpt.isEmpty()) {
            return Optional.empty();
        }
        BasicTreeInfo basic = basicOpt.get();
        return Optional.of(buildTreeTarget(reader, basic));
    }

    private static Optional<BasicTreeInfo> resolveStandingTreeBasic(ChunkBlockReader reader,
                                                                    BlockPos logPos,
                                                                    TreeDetectionMode mode) {
        if (reader == null || logPos == null) {
            return Optional.empty();
        }
        BlockState state = reader.state(logPos);
        if (!isLog(state)) {
            return Optional.empty();
        }

        BlockPos base = trunkBaseOf(reader, logPos);

        // Scan downward up to 8 blocks for valid soil — handles cherry trees and
        // hillside trees whose trunks start above ground with air/stone gaps below.
        // Scans through stone layers (hillside terrain: air → stone → dirt).
        boolean foundSoil = false;
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos probe = base.down(dy);
            if (!reader.isLoaded(probe.getX(), probe.getZ())) break;
            BlockState soilCandidate = reader.state(probe);
            if (isValidSoil(soilCandidate)) {
                foundSoil = true;
                break;
            }
        }
        if (!foundSoil) {
            if (mode == TreeDetectionMode.STRICT_STANDING) {
                return Optional.empty();
            }
            // Allow re-run in branch cleanup: treat as valid if leaves present and no human blocks
            if (!hasLeavesNearby(reader, base, 4, 4) || isNearHumanBlocks(reader, base, 4)) {
                return Optional.empty();
            }
        }

        int height = 1;
        BlockPos cursor = base.up();
        while (height < MAX_TREE_HEIGHT && isLog(reader.state(cursor))) {
            height++;
            cursor = cursor.up();
        }
        BlockPos top = cursor.down();
        if (height < 2 || height > MAX_TREE_HEIGHT) {
            return Optional.empty();
        }

        if (!hasLeavesNearby(reader, base, height)) {
            return Optional.empty();
        }
        if (isNearHumanBlocks(reader, base, height)) {
            return Optional.empty();
        }

        return Optional.of(new BasicTreeInfo(base.toImmutable(), top.toImmutable(), height));
    }

    /** The lowest log of the straight column {@code logPos} stands in (the trunk base the tree checks start from). */
    private static BlockPos trunkBaseOf(ChunkBlockReader reader, BlockPos logPos) {
        BlockPos base = logPos.toImmutable();
        int bottomY = reader.world().getBottomY();
        while (isLog(reader.state(base.down())) && base.getY() > bottomY) {
            base = base.down();
        }
        return base;
    }

    /**
     * Same verdict as {@code detectTreeAtForWoodcut(world, logPos).isPresent()}: a strict standing
     * tree whose base the woodcut protection check allows. {@link #detectTreeAtCore} always wraps a
     * resolved tree in {@code Optional.of(buildTreeTarget(...))} and the protection filter reads only
     * its base and height, which {@code buildTreeTarget} copies from the resolved trunk, so the
     * canopy flood fill and the neighbouring-trunk box it builds never change the answer and are
     * skipped here.
     */
    public static boolean isStandingTreeForWoodcut(ServerWorld world, BlockPos logPos) {
        return world != null && isStandingTreeForWoodcut(new ChunkBlockReader(world), logPos);
    }

    /** {@link #isStandingTreeForWoodcut(ServerWorld, BlockPos)} through a scan's chunk reader. */
    static boolean isStandingTreeForWoodcut(ChunkBlockReader reader, BlockPos logPos) {
        if (reader == null) {
            return false;
        }
        ServerWorld world = reader.world();
        Optional<BasicTreeInfo> basic = resolveStandingTreeBasic(reader, logPos, TreeDetectionMode.STRICT_STANDING);
        return basic.isPresent()
                && !getWoodcutProtectionDecision(world, basic.get().base(), Math.max(4, basic.get().height())).blocked();
    }

    /**
     * {@link #isStandingTreeForWoodcut(ServerWorld, BlockPos)} with a run's memo ({@code null} for
     * none). The verdict is a function of the trunk base alone (the trunk is re-walked up from the
     * base), so it is cached per base and every log of one column reuses it.
     */
    static boolean isStandingTreeForWoodcut(ChunkBlockReader reader, BlockPos logPos, CleanupScanMemo memo) {
        if (memo == null || reader == null || logPos == null || !isLog(reader.state(logPos))) {
            return isStandingTreeForWoodcut(reader, logPos);
        }
        long key = trunkBaseOf(reader, logPos).asLong();
        Boolean cached = memo.standingTrees.get(key);
        if (cached != null) {
            return cached;
        }
        boolean verdict = isStandingTreeForWoodcut(reader, logPos);
        memo.standingTrees.put(key, verdict);
        return verdict;
    }

    private static TreeTarget buildTreeTarget(ChunkBlockReader reader, BasicTreeInfo basic) {
        Map<BlockPos, Integer> nearbyRootedBases = findNearbyRootedBases(reader, basic);
        Set<BlockPos> canopyLeaves = collectLeafEnvelope(reader, basic, nearbyRootedBases);
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

    private static Map<BlockPos, Integer> findNearbyRootedBases(ChunkBlockReader reader, BasicTreeInfo selected) {
        Map<BlockPos, Integer> bases = new HashMap<>();
        if (reader == null || selected == null) {
            return bases;
        }
        BlockPos scanMin = selected.base().add(-TREE_ENVELOPE_SCAN_HORIZONTAL, -1, -TREE_ENVELOPE_SCAN_HORIZONTAL);
        BlockPos scanMax = new BlockPos(
                selected.base().getX() + TREE_ENVELOPE_SCAN_HORIZONTAL,
                selected.top().getY() + TREE_ENVELOPE_SCAN_VERTICAL_ABOVE,
                selected.base().getZ() + TREE_ENVELOPE_SCAN_HORIZONTAL
        );
        for (BlockPos candidate : BlockPos.iterate(scanMin, scanMax)) {
            if (!isLog(reader.state(candidate))) {
                continue;
            }
            Optional<BasicTreeInfo> infoOpt = resolveStandingTreeBasic(reader, candidate, TreeDetectionMode.STRICT_STANDING);
            if (infoOpt.isEmpty()) {
                continue;
            }
            BasicTreeInfo info = infoOpt.get();
            if (!info.base().equals(selected.base())) {
                bases.merge(info.base().toImmutable(), info.top().getY(), Math::max);
            }
        }
        return bases;
    }

    private static Set<BlockPos> collectLeafEnvelope(ChunkBlockReader reader,
                                                     BasicTreeInfo selected,
                                                     Map<BlockPos, Integer> nearbyRootedBases) {
        Set<BlockPos> leaves = new HashSet<>();
        if (reader == null || selected == null) {
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
                        if (!isLeaf(reader.state(seed))) {
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
                        if (!isLeaf(reader.state(neighbor))) {
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
                                                     Map<BlockPos, Integer> nearbyRootedBases) {
        if (leaf == null || selectedBase == null) {
            return false;
        }
        double selectedDistSq = horizontalDistanceSq(leaf, selectedBase);
        for (var entry : nearbyRootedBases.entrySet()) {
            BlockPos otherBase = entry.getKey();
            int otherTopY = entry.getValue();
            if (otherBase == null) {
                continue;
            }
            if (Math.abs(leaf.getX() - otherBase.getX()) <= 1
                    && Math.abs(leaf.getZ() - otherBase.getZ()) <= 1
                    && leaf.getY() >= otherBase.getY() - 1
                    && leaf.getY() <= otherTopY + 1) {
                return false;
            }
            double otherDistSq = horizontalDistanceSq(leaf, otherBase);
            if (otherDistSq + 1.0D < selectedDistSq) {
                return false;
            }
        }
        return true;
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

    private static boolean hasLeavesNearby(ChunkBlockReader reader, BlockPos base, int height) {
        BlockPos min = base.add(-LEAF_SCAN_RADIUS, -1, -LEAF_SCAN_RADIUS);
        BlockPos max = base.add(LEAF_SCAN_RADIUS, height + 2, LEAF_SCAN_RADIUS);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (isLeaf(reader.state(pos))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasLeavesNearby(ServerWorld world, BlockPos base, int horizontalRadius, int verticalRadius) {
        return hasLeavesNearby(new ChunkBlockReader(world), base, horizontalRadius, verticalRadius);
    }

    /** {@link #hasLeavesNearby(ServerWorld, BlockPos, int, int)} through a scan's chunk reader; an unloaded cell holds no leaves. */
    public static boolean hasLeavesNearby(ChunkBlockReader reader, BlockPos base, int horizontalRadius, int verticalRadius) {
        BlockPos min = base.add(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = base.add(horizontalRadius, verticalRadius + 2, horizontalRadius);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (isLeaf(reader.state(pos))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNearHumanBlocks(ServerWorld world, BlockPos base, int height) {
        return isNearHumanBlocks(new ChunkBlockReader(world), base, height);
    }

    /**
     * {@link #isNearHumanBlocks(ServerWorld, BlockPos, int)} through a scan's chunk reader. A cell
     * in an unloaded chunk counts as a human block (conservative, as {@code WorldTerrainView}): the
     * tree waits until its surroundings are loaded. A cell above or below the world height is air.
     */
    public static boolean isNearHumanBlocks(ChunkBlockReader reader, BlockPos base, int height) {
        BlockPos min = base.add(-HUMAN_SCAN_RADIUS, -1, -HUMAN_SCAN_RADIUS);
        BlockPos max = base.add(HUMAN_SCAN_RADIUS, height + 2, HUMAN_SCAN_RADIUS);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state = reader.state(pos);
            if (state == null) {
                if (!reader.isLoaded(pos.getX(), pos.getZ())) {
                    return true;
                }
                continue;
            }
            if (isHumanBlock(state)) {
                return true;
            }
        }
        return false;
    }

    /** The scan window of {@link #isNearHumanBlocks}: x/z +-this around the base. */
    public static int humanScanRadius() {
        return HUMAN_SCAN_RADIUS;
    }

    /** One cell of {@link #isNearHumanBlocks}: a block a player builds with or places. */
    public static boolean isHumanBlock(BlockState state) {
        return state != null
                && (state.isIn(BlockTags.PLANKS)
                || state.isIn(BlockTags.BEDS)
                || state.isIn(BlockTags.WOODEN_DOORS)
                || state.isIn(BlockTags.WOODEN_PRESSURE_PLATES)
                || state.isIn(BlockTags.FENCES)
                || HUMAN_BLOCKS.contains(state.getBlock()));
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
        if (BotBeehiveRegistryService.findProtectingBeehive(world, pos) != null) {
            return new WoodcutProtectionDecision(true, "beehive");
        }
        boolean spawnBaseWoodcutAllowed = BotHomeService.isSpawnBaseWoodcutAllowed(world.getServer());
        WoodcutBaseAccessPolicy.ZoneVerdict zoneVerdict = classifyWoodcutZones(world, pos, spawnBaseWoodcutAllowed);
        boolean spawnBaseBlocked = zoneVerdict == WoodcutBaseAccessPolicy.ZoneVerdict.SPAWN_BASE
                || (!WoodcutBaseAccessPolicy.detectionExempt(true, spawnBaseWoodcutAllowed)
                && CompanionSafeZoneService.isNearServerOwnedBase(world, pos));
        return VillageRuntimeProtectionPolicy.decideWoodcutProtection(
                zoneVerdict == WoodcutBaseAccessPolicy.ZoneVerdict.OTHER,
                CompanionSafeZoneService.isNearPlayerOwnedBase(world, pos),
                spawnBaseBlocked,
                CompanionSafeZoneService.isInsideFortificationZone(world, pos),
                MappedVillageService.isInsideMappedVillage(world, pos)
        );
    }

    /**
     * Explicit-zone check for woodcut: the zone service must refuse the mutation, then the
     * containing zones are classified. A player base auto-zone or a user-drawn zone is OTHER
     * ("admin-zone"); a base auto-zone owned literally by SERVER is SPAWN_BASE unless the admin
     * toggle opens it to woodcut; a legacy ownerless base auto-zone is skipped (its radius still
     * blocks as "base-radius"), as in 1.1.237. Conservative edge: a public-mode user zone overlapping a server base
     * zone still counts as blocking here.
     */
    private static WoodcutBaseAccessPolicy.ZoneVerdict classifyWoodcutZones(ServerWorld world, BlockPos pos,
                                                                             boolean spawnBaseWoodcutAllowed) {
        if (!CompanionSafeZoneService.isInExplicitProtectedZone(world, pos, null)) {
            return WoodcutBaseAccessPolicy.ZoneVerdict.NONE;
        }
        List<ProtectedZoneService.ProtectedZone> containing = new ArrayList<>();
        for (ProtectedZoneService.ProtectedZone zone : ProtectedZoneService.listZones(world)) {
            if (zone != null && zone.contains(pos)) {
                containing.add(zone);
            }
        }
        MinecraftServer server = world.getServer();
        return WoodcutBaseAccessPolicy.classifyZones(containing,
                zone -> BotHomeService.baseZoneKind(server, world, zone),
                spawnBaseWoodcutAllowed);
    }

    /**
     * Memo of log components a cleanup run has already explored, so every log of one rejected
     * component does not redo the component BFS. Only a fully enumerated component (not cut off
     * at the BFS limit) is stored, and only when its profile alone rejects it: its size and its
     * grounded / leaves / unsupported flags are ORs over the same connected set from whichever log
     * the BFS starts, so the profile read from the memo is the one a fresh BFS would compute on an
     * unchanged world. It also holds the standing-tree verdict per trunk base
     * ({@link #isStandingTreeForWoodcut(ServerWorld, BlockPos, CleanupScanMemo)}), so the logs of one
     * live tree do not each redo the trunk walk and protection check. The other per-log signals
     * (human blocks, protection, lone log) are still computed for every log, so the classification
     * is identical on an unchanged world. Cleared with the run's other scan caches.
     * Thread-safe: an async region scan writes it.
     */
    public static final class CleanupScanMemo {
        private final Map<Long, CleanupComponentProfile> profiles = new ConcurrentHashMap<>();
        private final Map<Long, Boolean> standingTrees = new ConcurrentHashMap<>();

        public void clear() {
            profiles.clear();
            standingTrees.clear();
        }

        /** Logs whose rejected component profile is memoised (standing-tree verdicts not counted). */
        public int size() {
            return profiles.size();
        }
    }

    /** A profile for a log whose verdict does not read the component (see {@link TreeCleanupClassifier#needsComponentProfile}). */
    private static final CleanupComponentProfile UNREAD_PROFILE = new CleanupComponentProfile(0, false, false, false, false);

    private static final int CLEANUP_COMPONENT_LIMIT = 64;

    public static CleanupLogDisposition classifyCleanupLog(ServerWorld world, BlockPos pos, boolean rememberedLeftover) {
        return classifyCleanupLog(world, pos, rememberedLeftover, null);
    }

    /**
     * {@link #classifyCleanupLog(ServerWorld, BlockPos, boolean)} with a run's component memo
     * ({@code null} for none). The full-tree check and the component BFS run only when the verdict
     * reads them; the classifier returns before reading a signal it does not need, so skipping it
     * gives the same disposition.
     */
    public static CleanupLogDisposition classifyCleanupLog(ServerWorld world, BlockPos pos, boolean rememberedLeftover, CleanupScanMemo memo) {
        if (world == null) {
            return new CleanupLogDisposition(CleanupLogDispositionKind.REJECTED_NON_LOG, false, "not-log");
        }
        return classifyCleanupLog(new ChunkBlockReader(world), pos, rememberedLeftover, memo);
    }

    /**
     * {@link #classifyCleanupLog(ServerWorld, BlockPos, boolean, CleanupScanMemo)} through a scan's
     * chunk reader. The reader is the caller's (one per scan and thread); the memo never holds it.
     */
    public static CleanupLogDisposition classifyCleanupLog(ChunkBlockReader reader, BlockPos pos, boolean rememberedLeftover, CleanupScanMemo memo) {
        if (reader == null || pos == null || !isTaggedLog(reader.state(pos))) {
            return new CleanupLogDisposition(CleanupLogDispositionKind.REJECTED_NON_LOG, false, "not-log");
        }
        ServerWorld world = reader.world();

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

        boolean humanAdjacent = isNearHumanBlocks(reader, pos, 3);
        WoodcutProtectionDecision protectionDecision = getWoodcutProtectionDecision(world, pos, 4);
        boolean protectedTarget = protectionDecision.blocked();
        boolean fullTree = false;
        if (TreeCleanupClassifier.needsFullTreeCheck(humanAdjacent, protectedTarget)) {
            try {
                fullTree = isStandingTreeForWoodcut(reader, pos, memo);
            } catch (Exception ignored) {
            }
        }

        boolean loneFloating = isCleanupLoneFloatingLog(reader, pos);
        CleanupComponentProfile profile = TreeCleanupClassifier.needsComponentProfile(humanAdjacent, protectedTarget, fullTree, loneFloating)
                ? cleanupComponentProfile(reader, pos, memo)
                : UNREAD_PROFILE;
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

    private static boolean isCleanupLoneFloatingLog(ChunkBlockReader reader, BlockPos candidate) {
        if (reader == null || candidate == null) {
            return false;
        }
        if (hasLeavesNearby(reader, candidate, 2, 2)) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            if (isTaggedLog(reader.state(candidate.offset(dir)))) {
                return false;
            }
        }
        return true;
    }

    /** The component profile for {@code pos}, from the memo when an explored rejected component holds it. */
    private static CleanupComponentProfile cleanupComponentProfile(ChunkBlockReader reader, BlockPos pos, CleanupScanMemo memo) {
        if (memo != null) {
            CleanupComponentProfile cached = memo.profiles.get(pos.asLong());
            if (cached != null) {
                return cached;
            }
        }
        Set<BlockPos> visited = new HashSet<>();
        CleanupComponentProfile profile = inspectCleanupLogComponent(reader, pos, CLEANUP_COMPONENT_LIMIT, visited);
        if (memo != null
                && !profile.tooLarge()
                && TreeCleanupClassifier.profileRejects(profile.size(), profile.grounded(), profile.leavesNearby(),
                        profile.unsupported(), profile.tooLarge())) {
            for (BlockPos node : visited) {
                memo.profiles.put(node.asLong(), profile);
            }
        }
        return profile;
    }

    private static CleanupComponentProfile inspectCleanupLogComponent(ChunkBlockReader reader, BlockPos start, int limit, Set<BlockPos> visited) {
        if (reader == null || start == null || !isTaggedLog(reader.state(start))) {
            return new CleanupComponentProfile(0, false, false, false, false);
        }
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start.toImmutable());

        boolean grounded = false;
        boolean leavesNearby = false;
        boolean unsupported = false;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            leavesNearby = leavesNearby || hasLeavesNearby(reader, pos, 3, 3);

            BlockPos below = pos.down();
            BlockState belowState = reader.state(below);
            // Same column as a log just read, so null means below the world: void air, as
            // world.getBlockState gave (not a log, no collision, unsupported).
            WorldChunk belowView = belowState == null ? null : reader.chunk(below.getX() >> 4, below.getZ() >> 4);
            boolean belowIsLog = belowState != null && belowState.isIn(BlockTags.LOGS);
            boolean belowCollisionEmpty = belowView == null || belowState.getCollisionShape(belowView, below).isEmpty();
            boolean belowIsSolid = !belowCollisionEmpty && !belowState.isIn(BlockTags.LEAVES);
            boolean belowUnsupported = belowState == null
                    || belowState.isAir()
                    || belowState.isReplaceable()
                    || belowState.isIn(BlockTags.LEAVES)
                    || belowCollisionEmpty;

            if (!belowIsLog && belowIsSolid) {
                grounded = true;
            }
            if (!belowIsLog && belowUnsupported) {
                unsupported = true;
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.offset(dir);
                if (visited.contains(neighbor) || !isTaggedLog(reader.state(neighbor))) {
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
        long startedAt = System.nanoTime();
        long deadline = startedAt + Math.max(1L, timeBudgetMs) * 1_000_000L;
        // One reader per sampling pass: each sample reads 9 columns, and off the server thread
        // every world.getBlockState is a server round trip.
        ChunkBlockReader reader = new ChunkBlockReader(world);
        int sampledColumns = 0;
        int lastRadius = 0;
        int startRadius = Math.max(Math.max(1, innerRadius + 1), Math.max(1, radiusStep));
        int innerSq = Math.max(0, innerRadius * innerRadius);
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;
        Set<Long> seenBases = new HashSet<>();

        for (int radius = startRadius; radius <= outerRadius; radius += Math.max(1, radiusStep)) {
            int samples = TreeSamplingPolicy.samplesForRing(radius, minSamplesPerRing);
            lastRadius = radius;
            for (int sample = 0; sample < samples; sample++) {
                if (System.nanoTime() > deadline) {
                    logSamplingPass(origin, outerRadius, lastRadius, sampledColumns, best, startedAt, true);
                    return best;
                }
                double angle = (Math.PI * 2.0D * sample) / samples;
                int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
                sampledColumns++;
                BlockPos tree = findTreeNearColumnForWoodcut(world, reader, origin, x, z, verticalRange, seenBases, excludedBases);
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
        logSamplingPass(origin, outerRadius, lastRadius, sampledColumns, best, startedAt, false);
        return best;
    }

    private static void logSamplingPass(BlockPos origin, int outerRadius, int lastRadius, int sampledColumns,
                                        BlockPos best, long startedAt, boolean budgetExhausted) {
        LOGGER.info("Tree sampling from {}: sampledColumns={} reachedRadius={}/{} found={} budgetExhausted={} after {}ms",
                origin.toShortString(), sampledColumns, lastRadius, outerRadius,
                best == null ? "none" : best.toShortString(), budgetExhausted,
                (System.nanoTime() - startedAt) / 1_000_000L);
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

    /** A leaf block; null (unloaded or outside the world height) is not. */
    private static boolean isLeaf(BlockState state) {
        return state != null && state.isIn(BlockTags.LEAVES);
    }

    /** A {@code BlockTags.LOGS} block; null (unloaded or outside the world height) is not. */
    private static boolean isTaggedLog(BlockState state) {
        return state != null && state.isIn(BlockTags.LOGS);
    }

    public static boolean isValidSoil(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isIn(BlockTags.DIRT) || SOIL_BLOCKS.contains(state.getBlock());
    }

    private static BlockPos findTreeNearColumnForWoodcut(ServerWorld world,
                                                         ChunkBlockReader reader,
                                                         BlockPos origin,
                                                         int x,
                                                         int z,
                                                         int verticalRange,
                                                         Set<Long> seenBases,
                                                         Set<BlockPos> excludedBases) {
        WorldChunk columnChunk = reader.chunk(x >> 4, z >> 4);
        if (columnChunk == null) {
            return null;
        }
        int minY = Math.max(world.getBottomY(), origin.getY() - Math.max(1, verticalRange));
        // The chunk's own heightmap: world.getTopY(type, x, z) reads the same sample, plus a server round trip.
        int maxY = TreeSamplingPolicy.scanMaxY(
                columnChunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15),
                origin.getY(), verticalRange);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                for (int y = maxY; y >= minY; y--) {
                    BlockState state = reader.state(cx, y, cz);
                    if (state == null || !state.isIn(BlockTags.LOGS)) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(cx, y, cz);
                    Optional<TreeTarget> treeOpt = detectTreeAtForWoodcut(reader, candidate, TreeDetectionMode.STRICT_STANDING);
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
