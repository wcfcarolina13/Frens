package net.wcfcarolina13.PathFinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Baritone-inspired pathfinder with four cherry-picked optimizations:
 * 1. Time-budgeted segmented search (500ms primary, 800ms replan)
 * 2. Multi-coefficient best-so-far tracking for partial paths
 * 3. Chebyshev + weighted-Y heuristic (tighter than Euclidean for MC grid)
 * 4. Packed-long positions + chunk cache to avoid BlockPos allocation in hot path
 *
 * Key perf note: campfire avoidance uses a cheap floor-block-only check in the
 * hot path instead of the 75-block area scan that the original PathFinder uses.
 * This alone is a ~300x reduction in block lookups per neighbor.
 */
public class BaritoneStylePathFinder {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");

    // --- Config ---
    static final long PRIMARY_TIMEOUT_MS = 500;
    static final long REPLAN_TIMEOUT_MS = 800;
    static final double[] COEFFICIENTS = {1.5, 2.5, 4.0, 7.0, 10.0};
    static final double MIN_PROGRESS_DIST_SQ = 25.0; // 5 blocks squared
    static final double TAIL_CUTOFF_FACTOR = 0.9;

    // --- Packed-long position encoding (Cherry-Pick 4) ---
    // Layout: x[28 bits] | z[28 bits] | y[8 bits] = 64 bits
    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0xFFFFFFF) << 36) | ((long) (z & 0xFFFFFFF) << 8) | (y & 0xFF);
    }

    // --- Node (Cherry-Pick 4: raw ints, no BlockPos) ---
    private static class Node implements Comparable<Node> {
        int x, y, z;
        long packed;
        Node parent;
        double gScore;
        double hScore;
        double fScore;

        Node(int x, int y, int z, Node parent, double gScore, double hScore) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.packed = packPos(x, y, z);
            this.parent = parent;
            this.gScore = gScore;
            this.hScore = hScore;
            this.fScore = gScore + hScore;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.fScore, other.fScore);
        }
    }

    // --- Chunk cache for world lookups (Cherry-Pick 4) ---
    // Caches the last 4 chunks (covers most movement patterns)
    private static class ChunkCache {
        private static final int CACHE_SIZE = 4;
        private final int[] chunkX = new int[CACHE_SIZE];
        private final int[] chunkZ = new int[CACHE_SIZE];
        private final WorldChunk[] chunks = new WorldChunk[CACHE_SIZE];
        private int nextSlot = 0;
        private final ServerWorld world;
        // Reusable mutable BlockPos for getBlockState calls
        private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        // Path origin Y — used to avoid routing through 3+ block deep pits
        int startY;

        ChunkCache(ServerWorld world) {
            this.world = world;
            Arrays.fill(chunkX, Integer.MIN_VALUE);
        }

        BlockState getBlockState(int x, int y, int z) {
            int cx = x >> 4;
            int cz = z >> 4;
            for (int i = 0; i < CACHE_SIZE; i++) {
                if (chunkX[i] == cx && chunkZ[i] == cz) {
                    return chunks[i].getBlockState(mutablePos.set(x, y, z));
                }
            }
            // Cache miss — load and store in round-robin slot
            WorldChunk chunk = world.getChunk(cx, cz);
            chunkX[nextSlot] = cx;
            chunkZ[nextSlot] = cz;
            chunks[nextSlot] = chunk;
            nextSlot = (nextSlot + 1) % CACHE_SIZE;
            return chunk.getBlockState(mutablePos.set(x, y, z));
        }
    }

    // --- Heuristic (Cherry-Pick 3): Chebyshev XZ + weighted Y ---
    private static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = Math.abs(x1 - x2);
        int dy = Math.abs(y1 - y2);
        int dz = Math.abs(z1 - z2);
        return Math.max(dx, dz) + dy * 2.5;
    }

    // --- Main entry point ---
    public static List<PathFinder.PathNode> calculatePath(
            BlockPos start, BlockPos target, ServerWorld world) {
        return calculatePath(start, target, world, PRIMARY_TIMEOUT_MS);
    }

    public static List<PathFinder.PathNode> calculatePath(
            BlockPos start, BlockPos target, ServerWorld world, long timeoutMs) {

        int sx = start.getX(), sy = start.getY(), sz = start.getZ();
        int tx = target.getX(), ty = target.getY(), tz = target.getZ();

        LOGGER.info("[BaritonePathFinder] Start=({},{},{}) Target=({},{},{}) timeout={}ms",
                sx, sy, sz, tx, ty, tz, timeoutMs);

        ChunkCache cache = new ChunkCache(world);
        cache.startY = sy;

        // Open set (priority queue) and closed set (packed-long map)
        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<Long, Node> openMap = new HashMap<>(1024);
        Map<Long, Node> closedSet = new HashMap<>(1024);

        Node startNode = new Node(sx, sy, sz, null, 0, heuristic(sx, sy, sz, tx, ty, tz));
        openSet.add(startNode);
        openMap.put(startNode.packed, startNode);

        // Cherry-Pick 2: Multi-coefficient best-so-far tracking
        Node[] bestSoFar = new Node[COEFFICIENTS.length];
        double[] bestScores = new double[COEFFICIENTS.length];
        Arrays.fill(bestScores, Double.MAX_VALUE);

        long deadline = System.currentTimeMillis() + timeoutMs;
        int iterations = 0;
        int nodesExpanded = 0;

        while (!openSet.isEmpty()) {
            // Time-limit check every 1024 iterations (amortize syscall cost)
            if ((++iterations & 0x3FF) == 0 && System.currentTimeMillis() > deadline) {
                LOGGER.info("[BaritonePathFinder] Timeout after {}ms, {} iterations, {} nodes expanded",
                        timeoutMs, iterations, nodesExpanded);
                return extractBestPartialPath(bestSoFar, sx, sy, sz, cache);
            }

            Node current = openSet.poll();
            openMap.remove(current.packed);

            // Goal reached?
            if (current.x == tx && current.y == ty && current.z == tz) {
                LOGGER.info("[BaritonePathFinder] Goal reached! {} iterations, {} nodes expanded",
                        iterations, nodesExpanded);
                List<BlockPos> rawPath = reconstructPath(current);
                return tagBlocks(rawPath, cache);
            }

            closedSet.put(current.packed, current);
            nodesExpanded++;

            // Expand neighbors using raw int arithmetic
            expandNeighbors(current, tx, ty, tz, cache, openSet, openMap, closedSet,
                    bestSoFar, bestScores);
        }

        // Open set exhausted — no path exists
        LOGGER.warn("[BaritonePathFinder] Open set exhausted, {} nodes expanded", nodesExpanded);
        List<PathFinder.PathNode> partial = extractBestPartialPath(bestSoFar, sx, sy, sz, cache);
        if (!partial.isEmpty()) {
            return partial;
        }
        return new ArrayList<>();
    }

    // --- Neighbor expansion ---
    private static void expandNeighbors(
            Node current, int tx, int ty, int tz,
            ChunkCache cache,
            PriorityQueue<Node> openSet, Map<Long, Node> openMap, Map<Long, Node> closedSet,
            Node[] bestSoFar, double[] bestScores) {

        int cx = current.x, cy = current.y, cz = current.z;

        // Every move below lands on a standing cell and passes PathMovePolicy (1.1.240):
        // flat, diagonal with corner clearance, step-up of exactly one block with headroom
        // above the origin, step-down of one block. There is no raw vertical "Up" move — it
        // planned pillars (a 2-high rise onto sand) that no executor can walk.

        // Cardinal flat moves
        tryAddNeighbor(cx + 1, cy, cz, current, 1.0, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddNeighbor(cx - 1, cy, cz, current, 1.0, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddNeighbor(cx, cy, cz + 1, current, 1.0, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddNeighbor(cx, cy, cz - 1, current, 1.0, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddDiagonalNeighbor(cx + 1, cy, cz + 1, current, 1.4, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddDiagonalNeighbor(cx + 1, cy, cz - 1, current, 1.4, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddDiagonalNeighbor(cx - 1, cy, cz + 1, current, 1.4, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryAddDiagonalNeighbor(cx - 1, cy, cz - 1, current, 1.4, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);

        // Straight down onto a floor. Only ever passes from a floorless origin (the bot's
        // own cell read mid-jump); from a standing cell the body cell is the floor block.
        tryAddNeighbor(cx, cy - 1, cz, current, 1.0, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);

        // Smart step-up moves: step onto solid block in front (4 cardinals)
        tryStepUp(cx + 1, cy, cz, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepUp(cx - 1, cy, cz, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepUp(cx, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepUp(cx, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepUp(cx + 1, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepUp(cx + 1, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepUp(cx - 1, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepUp(cx - 1, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);

        // Smart step-down moves: drop one block for staircase descents (4 cardinals)
        tryStepDown(cx + 1, cy, cz, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepDown(cx - 1, cy, cz, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepDown(cx, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryStepDown(cx, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepDown(cx + 1, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepDown(cx + 1, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepDown(cx - 1, cy, cz + 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
        tryDiagonalStepDown(cx - 1, cy, cz - 1, current, tx, ty, tz, cache, openSet, openMap, closedSet, bestSoFar, bestScores);
    }

    private static void tryAddDiagonalNeighbor(
            int nx, int ny, int nz,
            Node parent, double moveCost,
            int tx, int ty, int tz,
            ChunkCache cache,
            PriorityQueue<Node> openSet, Map<Long, Node> openMap, Map<Long, Node> closedSet,
            Node[] bestSoFar, double[] bestScores) {
        int dx = Integer.compare(nx, parent.x);
        int dz = Integer.compare(nz, parent.z);
        if (!hasDiagonalClearance(cache, parent.x, parent.y, parent.z, dx, dz)) {
            return;
        }
        tryAddNeighbor(nx, ny, nz, parent, moveCost, tx, ty, tz, cache,
                openSet, openMap, closedSet, bestSoFar, bestScores);
    }

    private static void tryStepUp(int nx, int cy, int nz,
                                    Node parent, int tx, int ty, int tz,
                                    ChunkCache cache,
                                    PriorityQueue<Node> openSet, Map<Long, Node> openMap,
                                    Map<Long, Node> closedSet,
                                    Node[] bestSoFar, double[] bestScores) {
        // Cheap gate: only a solid block in front can be stepped onto. The landing body/head
        // and the headroom above the origin are checked by PathMovePolicy in tryAddNeighbor.
        if (isSolidBlock(cache, nx, cy, nz)) {
            tryAddNeighbor(nx, cy + 1, nz, parent, 1.5, tx, ty, tz, cache,
                    openSet, openMap, closedSet, bestSoFar, bestScores);
        }
    }

    private static void tryDiagonalStepUp(int nx, int cy, int nz,
                                          Node parent, int tx, int ty, int tz,
                                          ChunkCache cache,
                                          PriorityQueue<Node> openSet, Map<Long, Node> openMap,
                                          Map<Long, Node> closedSet,
                                          Node[] bestSoFar, double[] bestScores) {
        int dx = Integer.compare(nx, parent.x);
        int dz = Integer.compare(nz, parent.z);
        if (!hasDiagonalClearance(cache, parent.x, parent.y, parent.z, dx, dz)) {
            return;
        }
        if (isSolidBlock(cache, nx, cy, nz)) {
            tryAddNeighbor(nx, cy + 1, nz, parent, 1.8, tx, ty, tz, cache,
                    openSet, openMap, closedSet, bestSoFar, bestScores);
        }
    }

    /**
     * Step-down move: when the flat position (nx, cy, nz) lacks a floor (staircase),
     * drop to (nx, cy-1, nz). PathMovePolicy requires a floor under the landing, a clear
     * landing body/head and a clear cell at landing.y + 2 (the head passes through it).
     */
    private static void tryStepDown(int nx, int cy, int nz,
                                    Node parent, int tx, int ty, int tz,
                                    ChunkCache cache,
                                    PriorityQueue<Node> openSet, Map<Long, Node> openMap,
                                    Map<Long, Node> closedSet,
                                    Node[] bestSoFar, double[] bestScores) {
        tryAddNeighbor(nx, cy - 1, nz, parent, 1.2, tx, ty, tz, cache,
                openSet, openMap, closedSet, bestSoFar, bestScores);
    }

    private static void tryDiagonalStepDown(int nx, int cy, int nz,
                                            Node parent, int tx, int ty, int tz,
                                            ChunkCache cache,
                                            PriorityQueue<Node> openSet, Map<Long, Node> openMap,
                                            Map<Long, Node> closedSet,
                                            Node[] bestSoFar, double[] bestScores) {
        int dx = Integer.compare(nx, parent.x);
        int dz = Integer.compare(nz, parent.z);
        if (!hasDiagonalClearance(cache, parent.x, parent.y, parent.z, dx, dz)) {
            return;
        }
        tryAddNeighbor(nx, cy - 1, nz, parent, 1.5, tx, ty, tz, cache,
                openSet, openMap, closedSet, bestSoFar, bestScores);
    }

    private static void tryAddNeighbor(
            int nx, int ny, int nz,
            Node parent, double moveCost,
            int tx, int ty, int tz,
            ChunkCache cache,
            PriorityQueue<Node> openSet, Map<Long, Node> openMap, Map<Long, Node> closedSet,
            Node[] bestSoFar, double[] bestScores) {

        long packed = packPos(nx, ny, nz);
        if (closedSet.containsKey(packed)) return;

        // Reject nodes 3+ blocks below the lower of start or target Y — avoids
        // routing through surface pits/overhangs that the bot can't easily escape.
        int depthFloor = Math.min(cache.startY, ty) - 2;
        if (ny < depthFloor) return;

        // Executable edge (1.1.240): standing landing cell, at most one block up or down,
        // headroom above the origin for a rise, a clear head lane for a drop.
        int dy = ny - parent.y;
        if (dy > PathMovePolicy.MAX_RISE || dy < -PathMovePolicy.MAX_DROP) return;
        if (!PathMovePolicy.isExecutable(dy,
                hasSupportedFloor(cache, nx, ny, nz),
                isPassable(cache, nx, ny, nz),
                isPassable(cache, nx, ny + 1, nz),
                dy > 0 && isPassable(cache, parent.x, parent.y + 2, parent.z),
                dy < 0 && isPassable(cache, nx, ny + 2, nz))) {
            return;
        }

        // Cheap hazard checks: magma floor and direct campfire contact only.
        // The expensive 75-block-area campfire scan is NOT done here — it was the
        // primary cause of 1ms/node throughput. Instead we just reject standing
        // directly on campfire/soul_campfire or magma.
        BlockState floorState = cache.getBlockState(nx, ny - 1, nz);
        if (floorState.isOf(Blocks.MAGMA_BLOCK)) return;
        if (floorState.getBlock() instanceof CampfireBlock) {
            // Lit campfire below = damage
            if (floorState.get(CampfireBlock.LIT)) return;
        }
        // Also reject if the body block itself is a lit campfire
        BlockState bodyState = cache.getBlockState(nx, ny, nz);
        if (bodyState.getBlock() instanceof CampfireBlock && bodyState.get(CampfireBlock.LIT)) return;

        double tentativeG = parent.gScore + moveCost
                + net.wcfcarolina13.GameAI.services.navigation.NavHazardCache.penaltyFor(cache.world, nx, ny, nz)
                - net.wcfcarolina13.GameAI.services.navigation.PassageAnchorService.bonusFor(cache.world, nx, ny, nz);
        double h = heuristic(nx, ny, nz, tx, ty, tz);

        Node existing = openMap.get(packed);
        if (existing != null) {
            if (tentativeG < existing.gScore) {
                existing.gScore = tentativeG;
                existing.hScore = h;
                existing.fScore = tentativeG + h;
                existing.parent = parent;
                // Re-heapify
                openSet.remove(existing);
                openSet.add(existing);
                updateBestSoFar(existing, bestSoFar, bestScores);
            }
        } else {
            Node neighbor = new Node(nx, ny, nz, parent, tentativeG, h);
            openSet.add(neighbor);
            openMap.put(packed, neighbor);
            updateBestSoFar(neighbor, bestSoFar, bestScores);
        }
    }

    // --- Cherry-Pick 2: Best-so-far tracking ---
    private static void updateBestSoFar(Node node, Node[] bestSoFar, double[] bestScores) {
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            // Lower is better: heuristic relaxed by coefficient on gScore
            double score = node.hScore + node.gScore / COEFFICIENTS[i];
            if (score < bestScores[i]) {
                bestScores[i] = score;
                bestSoFar[i] = node;
            }
        }
    }

    private static List<PathFinder.PathNode> extractBestPartialPath(
            Node[] bestSoFar, int sx, int sy, int sz, ChunkCache cache) {

        // Iterate from best quality (lowest coefficient) to worst
        for (int i = 0; i < bestSoFar.length; i++) {
            Node best = bestSoFar[i];
            if (best == null) continue;

            double distSq = (best.x - sx) * (best.x - sx)
                    + (best.y - sy) * (best.y - sy)
                    + (best.z - sz) * (best.z - sz);

            if (distSq >= MIN_PROGRESS_DIST_SQ) {
                LOGGER.info("[BaritonePathFinder] Using best-so-far[coeff={}] dist={} at ({},{},{})",
                        COEFFICIENTS[i], String.format("%.1f", Math.sqrt(distSq)), best.x, best.y, best.z);

                List<BlockPos> rawPath = reconstructPath(best);

                // Cherry-Pick 1: Tail cutoff — trim 10% off partial results
                int cutLen = (int) (rawPath.size() * TAIL_CUTOFF_FACTOR);
                if (cutLen > 1 && cutLen < rawPath.size()) {
                    rawPath = new ArrayList<>(rawPath.subList(0, cutLen));
                }

                return tagBlocks(rawPath, cache);
            }
        }

        LOGGER.warn("[BaritonePathFinder] No best-so-far candidate made enough progress");
        return new ArrayList<>();
    }

    // --- Passability checks using chunk cache (no BlockPos allocation) ---
    private static boolean isPassable(ChunkCache cache, int x, int y, int z) {
        BlockState state = cache.getBlockState(x, y, z);

        // Fast-path: air is always passable (most common case)
        if (state.isAir()) return true;

        // Deadly blocks — fire, soul fire, lava, magma, campfires, cactus, sweet
        // berry bush, wither rose, powder snow, pointed dripstone. Some have empty
        // collision shapes (fire) and would otherwise slip through the walkable-
        // partial fallback below.
        if (net.wcfcarolina13.GameAI.services.BotHazardService.isDeadlyBlock(state)) return false;

        if (state.getBlock() instanceof DoorBlock) {
            // Iron doors: only passable if already open
            if (state.isOf(Blocks.IRON_DOOR)) {
                return state.get(DoorBlock.OPEN);
            }
            if (cache.world instanceof net.minecraft.server.world.ServerWorld sw
                    && net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(sw, cache.mutablePos.set(x, y, z))) {
                return false;
            }
            return true; // wooden doors can be opened by bot
        }

        // FenceGateBlock intentionally NOT treated as passable here — the pathfinder
        // would shortcut through animal pens. Fence gates are handled reactively by
        // FollowPathService (bounded planner) and BotEventHandler (door-escape system).

        if (state.getBlock() instanceof TrapdoorBlock) {
            if (state.isOf(Blocks.IRON_TRAPDOOR)) {
                return state.getCollisionShape(cache.world, cache.mutablePos.set(x, y, z)).isEmpty();
            }
            if (cache.world instanceof net.minecraft.server.world.ServerWorld sw
                    && net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(sw, cache.mutablePos.set(x, y, z))) {
                return false;
            }
            return true;
        }

        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) return false;

        // For remaining blocks, accept empty collision OR thin walkable partials
        // (carpets, pressure plates, rails, tripwire, lily pad, floor candles, etc.).
        // Before this check was `getCollisionShape().isEmpty()` alone, which rejected
        // every pressure plate and carpet as "impassable" — the pathfinder then
        // routed around every doorway rug and tried to step-up over plates, producing
        // visible avoidance jitter in villages. See 2026-04-10 walkable-partial autopsy.
        return net.wcfcarolina13.GameAI.services.WalkablePartialBlocks.isPathable(
                state, cache.world, cache.mutablePos.set(x, y, z));
    }

    private static boolean isSolidBlock(ChunkCache cache, int x, int y, int z) {
        BlockState state = cache.getBlockState(x, y, z);
        return !state.isAir() && state.isOpaque();
    }

    static boolean diagonalMovementAllowed(boolean xLaneBlocked, boolean zLaneBlocked) {
        return !(xLaneBlocked && zLaneBlocked);
    }

    private static boolean hasDiagonalClearance(ChunkCache cache, int x, int y, int z, int dx, int dz) {
        if (dx == 0 || dz == 0) {
            return true;
        }
        boolean xLaneBlocked = isPassageBlocked(cache, x + dx, y, z);
        boolean zLaneBlocked = isPassageBlocked(cache, x, y, z + dz);
        return diagonalMovementAllowed(xLaneBlocked, zLaneBlocked);
    }

    private static boolean isPassageBlocked(ChunkCache cache, int x, int y, int z) {
        return !isPassable(cache, x, y, z) || !isPassable(cache, x, y + 1, z);
    }


    /**
     * Returns true if position (nx, ny, nz) has a supported floor: solid ground
     * below. Generic ground pathing must not treat water as normal support.
     */
    private static boolean hasSupportedFloor(ChunkCache cache, int nx, int ny, int nz) {
        if (isSolidBlock(cache, nx, ny - 1, nz)) return true;
        return false;
    }

    // --- Path reconstruction ---
    private static List<BlockPos> reconstructPath(Node node) {
        // Build path using a linked list traversal, then reverse (avoids O(n²) of add(0,...))
        List<BlockPos> path = new ArrayList<>();
        while (node != null) {
            path.add(new BlockPos(node.x, node.y, node.z));
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }

    // --- Block tagging ---
    /**
     * Tags each search node with walkable/jump flags. Uses the same passability and floor
     * predicates as the search, so search and tag cannot disagree.
     *
     * <p>1.1.240: nodes are never lifted or dropped. Index 0 is the bot's own cell and is
     * emitted as-is (it may be mid-jump or inside a slab). Every later node is a standing
     * cell by construction; if one is not, the path is refused (one WARN) rather than
     * quietly reshaped into a hop no executor can walk.
     */
    private static List<PathFinder.PathNode> tagBlocks(List<BlockPos> rawPath, ChunkCache cache) {
        List<PathFinder.PathNode> taggedPath = new ArrayList<>(rawPath.size());

        for (int i = 0; i < rawPath.size(); i++) {
            BlockPos pos = rawPath.get(i);
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();

            boolean solidBelow = isSolidBlock(cache, x, y - 1, z);
            boolean bodyIsSlab = isSlab(cache, x, y, z);
            boolean canStand = solidBelow
                    && isPassable(cache, x, y, z)
                    && isPassable(cache, x, y + 1, z);

            if (!canStand && i > 0) {
                LOGGER.warn("[BaritonePathFinder] Refusing path: node {} of {} at {} is not a standing cell",
                        i, rawPath.size(), pos.toShortString());
                return new ArrayList<>();
            }

            boolean jumpRequired = false;
            if (i > 0) {
                BlockPos prev = rawPath.get(i - 1);
                int dy = y - prev.getY();
                if (dy > 0) {
                    jumpRequired = !bodyIsSlab;
                } else {
                    // Same level or a drop: the cell in front at the origin's height.
                    int fy = prev.getY();
                    jumpRequired = !isSlab(cache, x, fy, z) && isSolidBlock(cache, x, fy, z);
                }
            }

            String feetBlockType = cache.getBlockState(x, y - 1, z).getBlock().getName().getString().toLowerCase();
            taggedPath.add(new PathFinder.PathNode(pos, feetBlockType, canStand, jumpRequired));
        }

        return taggedPath;
    }

    private static boolean isSlab(ChunkCache cache, int x, int y, int z) {
        return cache.getBlockState(x, y, z).isIn(BlockTags.SLABS);
    }
}
