package net.shasankp000.GameAI.services.construction;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plans a defensive wall layout around a detected village using a 2D convex hull
 * of village structure positions. Produces procedural wall blocks (foundations,
 * walls, merlons, towers, gatehouses) that FortifyVillageSkill builds sequentially.
 */
public class VillageFortificationLayoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillageFortificationLayout");

    /** Margin added outside the convex hull of village structures. */
    private static final int MARGIN = 12;
    /** Minimum half-width/half-depth fallback when hull has < 3 points. */
    private static final int MIN_RADIUS = 12;
    /** Maximum hull expansion distance. */
    private static final int MAX_RADIUS = 60;
    /** How far beyond the POI bounding box to scan for village structure blocks. */
    private static final int STRUCTURE_SCAN_RANGE = 15;
    /** Default search radius for villagers and POI blocks. */
    private static final int DEFAULT_SEARCH_RADIUS = 64;
    /** Wall height (foundation + 3 wall rows + merlon row). */
    private static final int WALL_HEIGHT = 5;
    /** Tower height (5 wall rows + cap). */
    private static final int TOWER_HEIGHT = 6;

    /** Blocks commonly found in village structures. */
    private static final Set<Block> VILLAGE_STRUCTURE_BLOCKS = Set.of(
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
            Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_WALL,
            Blocks.COBBLESTONE_SLAB, Blocks.SMOOTH_STONE,
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
            Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE,
            Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS,
            Blocks.GLASS_PANE,
            Blocks.FARMLAND,
            Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR,
            Blocks.OAK_TRAPDOOR,
            Blocks.TORCH, Blocks.WALL_TORCH,
            Blocks.LANTERN
    );

    // ── Data types ──────────────────────────────────────────────

    /** A 2D point in the XZ plane used for convex hull computation. */
    public record WallPoint(int x, int z) implements Comparable<WallPoint> {
        @Override
        public int compareTo(WallPoint o) {
            int cmp = Integer.compare(this.x, o.x);
            return cmp != 0 ? cmp : Integer.compare(this.z, o.z);
        }
    }

    /** An edge of the convex hull between two vertices. */
    public record WallEdge(WallPoint start, WallPoint end, int index) {
        public double length() {
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** Classification of each procedural wall block for material selection. */
    public enum WallBlockType {
        FOUNDATION, WALL, MERLON,
        TOWER_BASE, TOWER_WALL, TOWER_CAP,
        GATEHOUSE_PILLAR, GATEHOUSE_LINTEL, GATEHOUSE_CAP
    }

    /** A single block to place in the world as part of the fortification. */
    public record ProceduralWallBlock(
            BlockPos worldPos, BlockState state, WallBlockType type, int edgeIndex
    ) {}

    /** Complete fortification layout produced by generateLayout(). */
    public record FortificationLayout(
            BlockPos center,
            List<WallEdge> edges,
            List<ProceduralWallBlock> allBlocks,
            List<WallPoint> hullVertices,
            int gatehouseEdgeIndex,
            int totalBlocks
    ) {
        public List<ProceduralWallBlock> blocksForEdge(int idx) {
            List<ProceduralWallBlock> result = new ArrayList<>();
            for (ProceduralWallBlock b : allBlocks) {
                if (b.edgeIndex() == idx) result.add(b);
            }
            return result;
        }
    }

    /** Bounding box detection result (still used for initial village detection). */
    public record VillageBounds(
            BlockPos center,
            int radiusX,
            int radiusZ,
            int foundPOIs
    ) {}

    // ── Village detection ───────────────────────────────────────

    /**
     * Scans for villagers and POI blocks to compute a bounding rectangle around the village.
     * Kept for compatibility with initial detection (foundPOIs check).
     */
    public static VillageBounds detectVillageBounds(ServerWorld world, BlockPos searchCenter, int searchRadius) {
        List<WallPoint> positions = detectVillagePositions(world, searchCenter, searchRadius);
        if (positions.isEmpty()) {
            LOGGER.warn("No village POIs found within {} blocks of {}", searchRadius, searchCenter.toShortString());
            return new VillageBounds(searchCenter, MIN_RADIUS, MIN_RADIUS, 0);
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (WallPoint p : positions) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }

        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;
        int halfX = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (maxX - minX) / 2 + MARGIN));
        int halfZ = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (maxZ - minZ) / 2 + MARGIN));
        int cy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);

        LOGGER.info("Village bounds: center=({},{},{}), radius={}x{}, POIs={}",
                cx, cy, cz, halfX, halfZ, positions.size());
        return new VillageBounds(new BlockPos(cx, cy, cz), halfX, halfZ, positions.size());
    }

    /**
     * Returns individual XZ positions of all detected village structures.
     * Scans villagers, POI blocks, and village structure blocks.
     */
    public static List<WallPoint> detectVillagePositions(ServerWorld world, BlockPos searchCenter, int searchRadius) {
        int sr = searchRadius > 0 ? searchRadius : DEFAULT_SEARCH_RADIUS;
        Set<WallPoint> points = new HashSet<>();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        // Scan villagers on the server thread
        final int searchR = sr;
        final BlockPos sc = searchCenter;
        CompletableFuture<List<BlockPos>> villagerFuture = new CompletableFuture<>();
        world.getServer().execute(() -> {
            List<VillagerEntity> villagers = world.getEntitiesByClass(
                    VillagerEntity.class,
                    new Box(sc).expand(searchR),
                    v -> true
            );
            List<BlockPos> positions = new ArrayList<>();
            for (VillagerEntity v : villagers) {
                positions.add(v.getBlockPos().toImmutable());
            }
            villagerFuture.complete(positions);
        });

        List<BlockPos> villagerPositions;
        try {
            villagerPositions = villagerFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Failed to query villagers: {}", e.getMessage());
            villagerPositions = List.of();
        }

        for (BlockPos vp : villagerPositions) {
            points.add(new WallPoint(vp.getX(), vp.getZ()));
            minX = Math.min(minX, vp.getX());
            maxX = Math.max(maxX, vp.getX());
            minZ = Math.min(minZ, vp.getZ());
            maxZ = Math.max(maxZ, vp.getZ());
        }

        // Scan POI blocks
        Set<Block> poiBlocks = Set.of(
                Blocks.BELL,
                Blocks.COMPOSTER, Blocks.BARREL, Blocks.SMOKER,
                Blocks.BLAST_FURNACE, Blocks.BREWING_STAND,
                Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE,
                Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.LOOM,
                Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
                Blocks.CAULDRON
        );

        int yLow = searchCenter.getY() - 5;
        int yHigh = searchCenter.getY() + 5;
        for (int x = searchCenter.getX() - sr; x <= searchCenter.getX() + sr; x += 2) {
            for (int z = searchCenter.getZ() - sr; z <= searchCenter.getZ() + sr; z += 2) {
                for (int y = yLow; y <= yHigh; y++) {
                    Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (poiBlocks.contains(block) || block instanceof BedBlock) {
                        points.add(new WallPoint(x, z));
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }

        if (points.isEmpty()) {
            return List.of();
        }

        // Structure scan: expand the POI bounding box to include actual building footprints
        int scanYLow = searchCenter.getY() - 3;
        int scanYHigh = searchCenter.getY() + 10;
        int expandedMinX = minX - STRUCTURE_SCAN_RANGE;
        int expandedMaxX = maxX + STRUCTURE_SCAN_RANGE;
        int expandedMinZ = minZ - STRUCTURE_SCAN_RANGE;
        int expandedMaxZ = maxZ + STRUCTURE_SCAN_RANGE;

        for (int x = expandedMinX; x <= expandedMaxX; x += 3) {
            for (int z = expandedMinZ; z <= expandedMaxZ; z += 3) {
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
                for (int y = scanYLow; y <= scanYHigh; y++) {
                    Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (VILLAGE_STRUCTURE_BLOCKS.contains(block)) {
                        points.add(new WallPoint(x, z));
                        break;
                    }
                }
            }
        }

        return new ArrayList<>(points);
    }

    // ── Convex hull ─────────────────────────────────────────────

    /**
     * Compute the 2D convex hull using Andrew's monotone chain algorithm.
     * Returns vertices in counter-clockwise order.
     */
    public static List<WallPoint> computeConvexHull(List<WallPoint> points) {
        if (points.size() < 3) return new ArrayList<>(points);

        List<WallPoint> sorted = new ArrayList<>(points);
        Collections.sort(sorted);

        // Remove duplicates
        List<WallPoint> unique = new ArrayList<>();
        for (WallPoint p : sorted) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(p)) {
                unique.add(p);
            }
        }
        if (unique.size() < 3) return unique;

        int n = unique.size();
        WallPoint[] hull = new WallPoint[2 * n];
        int k = 0;

        // Build lower hull (left to right)
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], unique.get(i)) <= 0) k--;
            hull[k++] = unique.get(i);
        }

        // Build upper hull (right to left)
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            while (k >= lower && cross(hull[k - 2], hull[k - 1], unique.get(i)) <= 0) k--;
            hull[k++] = unique.get(i);
        }

        // Remove duplicate endpoint
        List<WallPoint> result = new ArrayList<>(k - 1);
        for (int i = 0; i < k - 1; i++) {
            result.add(hull[i]);
        }
        return result;
    }

    /** Cross product of vectors OA and OB. Positive = CCW turn. */
    private static long cross(WallPoint o, WallPoint a, WallPoint b) {
        return (long)(a.x() - o.x()) * (b.z() - o.z()) - (long)(a.z() - o.z()) * (b.x() - o.x());
    }

    /**
     * Expand the hull outward by the given margin.
     * For each edge, compute the outward normal (90° CW rotation of the edge direction
     * for a CCW hull) and offset both endpoints. Recompute the hull from expanded points
     * to handle corner intersections cleanly.
     */
    public static List<WallPoint> expandHull(List<WallPoint> hull, int margin) {
        if (hull.size() < 3 || margin <= 0) return new ArrayList<>(hull);

        List<WallPoint> expanded = new ArrayList<>();
        int n = hull.size();

        for (int i = 0; i < n; i++) {
            WallPoint a = hull.get(i);
            WallPoint b = hull.get((i + 1) % n);

            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) continue;

            // Outward normal for CCW hull: rotate edge direction 90° clockwise
            // Edge dir = (dx, dz), CW rotation = (dz, -dx)
            double nx = dz / len;
            double nz = -dx / len;

            expanded.add(new WallPoint(
                    (int) Math.round(a.x() + nx * margin),
                    (int) Math.round(a.z() + nz * margin)
            ));
            expanded.add(new WallPoint(
                    (int) Math.round(b.x() + nx * margin),
                    (int) Math.round(b.z() + nz * margin)
            ));
        }

        return computeConvexHull(expanded);
    }

    // ── Edge tracing ────────────────────────────────────────────

    /**
     * Trace a line from point A to point B using Bresenham's algorithm.
     * Returns the list of XZ block positions along the line.
     */
    public static List<WallPoint> traceEdge(WallPoint a, WallPoint b) {
        List<WallPoint> result = new ArrayList<>();

        int x0 = a.x(), z0 = a.z();
        int x1 = b.x(), z1 = b.z();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            result.add(new WallPoint(x0, z0));
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }

        return result;
    }

    // ── Procedural wall generation ──────────────────────────────

    /**
     * Main orchestrator: detect village, compute hull, generate all wall blocks.
     *
     * @param world        the server world
     * @param center       search center for village detection
     * @param searchRadius how far to scan for village structures
     * @return complete layout with all block positions, or a minimal layout if no village found
     */
    public static FortificationLayout generateLayout(ServerWorld world, BlockPos center, int searchRadius) {
        // 1. Detect village structure positions
        List<WallPoint> structurePositions = detectVillagePositions(world, center, searchRadius);

        if (structurePositions.isEmpty()) {
            LOGGER.warn("No village structures found, cannot generate layout");
            return new FortificationLayout(center, List.of(), List.of(), List.of(), -1, 0);
        }

        // 2. Compute convex hull
        List<WallPoint> hull = computeConvexHull(structurePositions);

        // Fallback: if < 3 hull points, create a rectangular fallback
        if (hull.size() < 3) {
            LOGGER.warn("Hull has {} points, falling back to rectangular layout", hull.size());
            hull = createRectangularFallback(center);
        }

        // 3. Expand hull outward
        List<WallPoint> expandedHull = expandHull(hull, MARGIN);

        // 4. Generate wall blocks
        return generateWallBlocks(expandedHull, world, center);
    }

    /**
     * Generate layout from pre-computed hull vertices (for resume from saved data).
     * Deterministically regenerates the same blocks.
     */
    public static FortificationLayout generateLayoutFromHull(List<WallPoint> hullVertices, ServerWorld world, BlockPos center) {
        if (hullVertices.size() < 3) {
            LOGGER.warn("Saved hull has {} points, cannot regenerate", hullVertices.size());
            return new FortificationLayout(center, List.of(), List.of(), hullVertices, -1, 0);
        }
        return generateWallBlocks(hullVertices, world, center);
    }

    /**
     * Generate all wall blocks from the expanded hull.
     */
    private static FortificationLayout generateWallBlocks(List<WallPoint> expandedHull, ServerWorld world, BlockPos center) {
        List<WallEdge> edges = new ArrayList<>();
        List<ProceduralWallBlock> allBlocks = new ArrayList<>();
        int n = expandedHull.size();

        // Build edges
        int longestEdgeIdx = 0;
        double longestLen = 0;
        for (int i = 0; i < n; i++) {
            WallPoint start = expandedHull.get(i);
            WallPoint end = expandedHull.get((i + 1) % n);
            WallEdge edge = new WallEdge(start, end, i);
            edges.add(edge);
            if (edge.length() > longestLen) {
                longestLen = edge.length();
                longestEdgeIdx = i;
            }
        }

        // Generate tower blocks at each hull vertex (edgeIndex = -1 for towers)
        for (int i = 0; i < n; i++) {
            WallPoint vertex = expandedHull.get(i);
            generateTower(allBlocks, vertex, world, -1);
        }

        // Generate wall blocks for each edge
        int gatehouseEdgeIndex = longestEdgeIdx;
        for (int i = 0; i < edges.size(); i++) {
            WallEdge edge = edges.get(i);
            List<WallPoint> traced = traceEdge(edge.start(), edge.end());

            // Skip first and last 2 points to avoid tower overlap
            int startIdx = Math.min(2, traced.size());
            int endIdx = Math.max(startIdx, traced.size() - 2);

            if (i == gatehouseEdgeIndex && traced.size() >= 9) {
                // Insert gatehouse in the middle of this edge
                int mid = traced.size() / 2;
                int gateStart = mid - 1;
                int gateEnd = mid + 2; // 3-block gap

                // Wall blocks before gatehouse
                for (int j = startIdx; j < gateStart && j < endIdx; j++) {
                    generateWallColumn(allBlocks, traced.get(j), world, i, j % 2 == 0);
                }

                // Gatehouse pillars + lintel
                if (gateStart >= startIdx && gateEnd < endIdx) {
                    generateGatehouse(allBlocks, traced.get(gateStart), traced.get(gateEnd), world, i);
                }

                // Wall blocks after gatehouse
                for (int j = gateEnd + 1; j < endIdx; j++) {
                    generateWallColumn(allBlocks, traced.get(j), world, i, j % 2 == 0);
                }
            } else {
                // Normal edge: all wall columns
                for (int j = startIdx; j < endIdx; j++) {
                    generateWallColumn(allBlocks, traced.get(j), world, i, j % 2 == 0);
                }
            }
        }

        LOGGER.info("Generated fortification: {} hull vertices, {} edges, {} blocks, gatehouse on edge {}",
                expandedHull.size(), edges.size(), allBlocks.size(), gatehouseEdgeIndex);

        return new FortificationLayout(center, edges, allBlocks, expandedHull,
                gatehouseEdgeIndex, allBlocks.size());
    }

    /**
     * Generate a 3x3 tower at a hull vertex.
     * Tower: 3x3 stone brick ring Y+0..Y+4, center oak log post Y+0..Y+5, cap at Y+5.
     */
    private static void generateTower(List<ProceduralWallBlock> blocks, WallPoint vertex,
                                       ServerWorld world, int edgeIndex) {
        int baseY = terrainY(world, vertex.x(), vertex.z());
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState oakLog = Blocks.OAK_LOG.getDefaultState();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int wx = vertex.x() + dx;
                int wz = vertex.z() + dz;
                boolean isCenter = dx == 0 && dz == 0;
                boolean isEdge = !isCenter;

                if (isEdge) {
                    // Ring walls Y+0..Y+4
                    for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                        WallBlockType type = dy == 0 ? WallBlockType.TOWER_BASE : WallBlockType.TOWER_WALL;
                        blocks.add(new ProceduralWallBlock(
                                new BlockPos(wx, baseY + dy, wz), stoneBricks, type, edgeIndex));
                    }
                    // Cap at Y+5
                    blocks.add(new ProceduralWallBlock(
                            new BlockPos(wx, baseY + TOWER_HEIGHT - 1, wz), stoneBricks,
                            WallBlockType.TOWER_CAP, edgeIndex));
                } else {
                    // Center oak log post Y+0..Y+5
                    for (int dy = 0; dy <= TOWER_HEIGHT - 1; dy++) {
                        WallBlockType type = dy == 0 ? WallBlockType.TOWER_BASE : WallBlockType.TOWER_WALL;
                        blocks.add(new ProceduralWallBlock(
                                new BlockPos(wx, baseY + dy, wz), oakLog, type, edgeIndex));
                    }
                }
            }
        }
    }

    /**
     * Generate a single-block-wide wall column at a point.
     * Y+0: foundation (stone bricks)
     * Y+1..Y+3: wall (stone bricks)
     * Y+4: merlon (stone bricks, alternating positions only)
     */
    private static void generateWallColumn(List<ProceduralWallBlock> blocks, WallPoint point,
                                            ServerWorld world, int edgeIndex, boolean hasMerlon) {
        int baseY = terrainY(world, point.x(), point.z());
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();

        // Foundation Y+0
        blocks.add(new ProceduralWallBlock(
                new BlockPos(point.x(), baseY, point.z()), stoneBricks,
                WallBlockType.FOUNDATION, edgeIndex));

        // Wall Y+1..Y+3
        for (int dy = 1; dy <= 3; dy++) {
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(point.x(), baseY + dy, point.z()), stoneBricks,
                    WallBlockType.WALL, edgeIndex));
        }

        // Merlon Y+4 (alternating)
        if (hasMerlon) {
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(point.x(), baseY + 4, point.z()), stoneBricks,
                    WallBlockType.MERLON, edgeIndex));
        }
    }

    /**
     * Generate a gatehouse: 2 pillars flanking a 3-block gap with a lintel.
     * Left pillar: stone bricks Y+0..Y+4, chiseled cap Y+5
     * Right pillar: stone bricks Y+0..Y+4, chiseled cap Y+5
     * Lintel: stone bricks at Y+3 across the gap
     */
    private static void generateGatehouse(List<ProceduralWallBlock> blocks,
                                           WallPoint leftPillar, WallPoint rightPillar,
                                           ServerWorld world, int edgeIndex) {
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.getDefaultState();

        // Left pillar
        int leftY = terrainY(world, leftPillar.x(), leftPillar.z());
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(leftPillar.x(), leftY + dy, leftPillar.z()), stoneBricks,
                    WallBlockType.GATEHOUSE_PILLAR, edgeIndex));
        }
        blocks.add(new ProceduralWallBlock(
                new BlockPos(leftPillar.x(), leftY + WALL_HEIGHT, leftPillar.z()), chiseled,
                WallBlockType.GATEHOUSE_CAP, edgeIndex));

        // Right pillar
        int rightY = terrainY(world, rightPillar.x(), rightPillar.z());
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(rightPillar.x(), rightY + dy, rightPillar.z()), stoneBricks,
                    WallBlockType.GATEHOUSE_PILLAR, edgeIndex));
        }
        blocks.add(new ProceduralWallBlock(
                new BlockPos(rightPillar.x(), rightY + WALL_HEIGHT, rightPillar.z()), chiseled,
                WallBlockType.GATEHOUSE_CAP, edgeIndex));

        // Lintel: trace between pillars at Y+3
        List<WallPoint> lintelPoints = traceEdge(leftPillar, rightPillar);
        int lintelY = Math.max(leftY, rightY) + 3;
        // Skip pillar positions (first and last)
        for (int i = 1; i < lintelPoints.size() - 1; i++) {
            WallPoint lp = lintelPoints.get(i);
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(lp.x(), lintelY, lp.z()), stoneBricks,
                    WallBlockType.GATEHOUSE_LINTEL, edgeIndex));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    /** Create a rectangular fallback hull when there are fewer than 3 detected points. */
    private static List<WallPoint> createRectangularFallback(BlockPos center) {
        int r = MIN_RADIUS;
        return List.of(
                new WallPoint(center.getX() - r, center.getZ() - r),
                new WallPoint(center.getX() + r, center.getZ() - r),
                new WallPoint(center.getX() + r, center.getZ() + r),
                new WallPoint(center.getX() - r, center.getZ() + r)
        );
    }

    /** Sample terrain Y at the given XZ from the heightmap. */
    public static int terrainY(ServerWorld world, int x, int z) {
        return world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** Describe the layout in a human-readable summary for chat output. */
    public static String describePlan(FortificationLayout layout) {
        int towers = 0, walls = 0, gateBlocks = 0;
        for (ProceduralWallBlock block : layout.allBlocks()) {
            switch (block.type()) {
                case TOWER_BASE, TOWER_WALL, TOWER_CAP -> towers++;
                case FOUNDATION, WALL, MERLON -> walls++;
                case GATEHOUSE_PILLAR, GATEHOUSE_LINTEL, GATEHOUSE_CAP -> gateBlocks++;
            }
        }
        return String.format(
                "Fortification plan: %d hull vertices, %d edges, %d blocks " +
                "(%d tower, %d wall, %d gatehouse), gatehouse on edge #%d, center (%d, %d, %d)",
                layout.hullVertices().size(), layout.edges().size(), layout.totalBlocks(),
                towers, walls, gateBlocks,
                layout.gatehouseEdgeIndex(),
                layout.center().getX(), layout.center().getY(), layout.center().getZ()
        );
    }
}
