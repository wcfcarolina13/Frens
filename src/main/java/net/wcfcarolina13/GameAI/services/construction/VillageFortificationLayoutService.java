package net.wcfcarolina13.GameAI.services.construction;

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
    private static final int STRUCTURE_SCAN_RANGE = 32;
    /** Default search radius for villagers and POI blocks. */
    private static final int DEFAULT_SEARCH_RADIUS = 64;
    /** Wall height (foundation + 3 wall rows + merlon row). */
    private static final int WALL_HEIGHT = 5;
    /** Tower height (5 wall rows + cap). */
    private static final int TOWER_HEIGHT = 6;
    /** Moat width (blocks outward from inner face). */
    private static final int MOAT_WIDTH = 1;
    /** Moat depth (blocks below terrain). */
    private static final int MOAT_DEPTH = 3;
    /** Exterior clearance distance (blocks beyond moat). */
    private static final int EXTERIOR_CLEAR_DIST = 1;

    /** Blocks commonly found in village structures. */
    private static final Set<Block> VILLAGE_STRUCTURE_BLOCKS;
    static {
        Set<Block> b = new HashSet<>();
        // Wood planks
        b.add(Blocks.OAK_PLANKS); b.add(Blocks.SPRUCE_PLANKS); b.add(Blocks.BIRCH_PLANKS);
        b.add(Blocks.JUNGLE_PLANKS); b.add(Blocks.ACACIA_PLANKS); b.add(Blocks.DARK_OAK_PLANKS);
        b.add(Blocks.MANGROVE_PLANKS);
        // Logs & stripped logs
        b.add(Blocks.OAK_LOG); b.add(Blocks.SPRUCE_LOG); b.add(Blocks.BIRCH_LOG);
        b.add(Blocks.JUNGLE_LOG); b.add(Blocks.ACACIA_LOG); b.add(Blocks.DARK_OAK_LOG);
        b.add(Blocks.MANGROVE_LOG);
        b.add(Blocks.STRIPPED_OAK_LOG); b.add(Blocks.STRIPPED_SPRUCE_LOG);
        b.add(Blocks.STRIPPED_BIRCH_LOG); b.add(Blocks.STRIPPED_JUNGLE_LOG);
        b.add(Blocks.STRIPPED_ACACIA_LOG); b.add(Blocks.STRIPPED_DARK_OAK_LOG);
        b.add(Blocks.STRIPPED_MANGROVE_LOG);
        // Doors & fences
        b.add(Blocks.OAK_DOOR); b.add(Blocks.SPRUCE_DOOR); b.add(Blocks.BIRCH_DOOR);
        b.add(Blocks.JUNGLE_DOOR); b.add(Blocks.ACACIA_DOOR); b.add(Blocks.DARK_OAK_DOOR);
        b.add(Blocks.OAK_FENCE); b.add(Blocks.SPRUCE_FENCE); b.add(Blocks.BIRCH_FENCE);
        b.add(Blocks.JUNGLE_FENCE); b.add(Blocks.ACACIA_FENCE); b.add(Blocks.DARK_OAK_FENCE);
        b.add(Blocks.OAK_TRAPDOOR);
        // Stairs & slabs (wood)
        b.add(Blocks.OAK_STAIRS); b.add(Blocks.SPRUCE_STAIRS); b.add(Blocks.BIRCH_STAIRS);
        b.add(Blocks.JUNGLE_STAIRS); b.add(Blocks.ACACIA_STAIRS); b.add(Blocks.DARK_OAK_STAIRS);
        b.add(Blocks.OAK_SLAB); b.add(Blocks.SPRUCE_SLAB);
        // Cobblestone family
        b.add(Blocks.COBBLESTONE); b.add(Blocks.COBBLESTONE_STAIRS); b.add(Blocks.COBBLESTONE_WALL);
        b.add(Blocks.COBBLESTONE_SLAB); b.add(Blocks.MOSSY_COBBLESTONE);
        b.add(Blocks.MOSSY_COBBLESTONE_STAIRS); b.add(Blocks.MOSSY_COBBLESTONE_WALL);
        // Stone bricks
        b.add(Blocks.STONE_BRICKS); b.add(Blocks.STONE_BRICK_STAIRS); b.add(Blocks.STONE_BRICK_SLAB);
        b.add(Blocks.MOSSY_STONE_BRICKS); b.add(Blocks.MOSSY_STONE_BRICK_STAIRS);
        b.add(Blocks.MOSSY_STONE_BRICK_SLAB);
        // Bricks
        b.add(Blocks.BRICKS); b.add(Blocks.BRICK_STAIRS); b.add(Blocks.BRICK_SLAB);
        // Stone & smooth stone
        b.add(Blocks.SMOOTH_STONE); b.add(Blocks.SMOOTH_STONE_SLAB);
        // Terracotta (desert/savanna villages)
        b.add(Blocks.TERRACOTTA); b.add(Blocks.WHITE_TERRACOTTA); b.add(Blocks.ORANGE_TERRACOTTA);
        b.add(Blocks.YELLOW_TERRACOTTA); b.add(Blocks.BROWN_TERRACOTTA);
        b.add(Blocks.RED_TERRACOTTA); b.add(Blocks.LIGHT_GRAY_TERRACOTTA);
        // Wool
        b.add(Blocks.WHITE_WOOL); b.add(Blocks.BROWN_WOOL); b.add(Blocks.LIGHT_GRAY_WOOL);
        // Misc village blocks
        b.add(Blocks.HAY_BLOCK); b.add(Blocks.BOOKSHELF); b.add(Blocks.CRAFTING_TABLE);
        b.add(Blocks.IRON_BARS); b.add(Blocks.LADDER);
        b.add(Blocks.GLASS_PANE); b.add(Blocks.FARMLAND);
        b.add(Blocks.TORCH); b.add(Blocks.WALL_TORCH); b.add(Blocks.LANTERN);
        VILLAGE_STRUCTURE_BLOCKS = Collections.unmodifiableSet(b);
    }

    /** Returns true if the given block type is commonly found in village structures. */
    public static boolean isVillageStructureBlock(Block block) {
        return VILLAGE_STRUCTURE_BLOCKS.contains(block);
    }

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
        FOUNDATION, WALL, MERLON, WALL_TOP_SLAB,
        TOWER_BASE, TOWER_WALL, TOWER_CAP,
        GATEHOUSE_PILLAR, GATEHOUSE_LINTEL, GATEHOUSE_CAP,
        MOAT_DIG, MOAT_FLOOR, MOAT_INNER_FACE, MOAT_OVERHANG, EXTERIOR_CLEAR
    }

    /** A single block to place in the world as part of the fortification. */
    public record ProceduralWallBlock(
            BlockPos worldPos, BlockState state, WallBlockType type, int edgeIndex
    ) {}

    /**
     * Cached terrain-Y lookup that ensures layout stability across regenerations.
     * On initial build the profile computes Y from the heightmap and caches it.
     * On resume/patch the profile is pre-populated from saved data, so existing
     * wall blocks don't shift the layout.
     */
    public static final class SurfaceProfile {
        private final Map<Long, Integer> cache;

        /** Empty profile — will compute and cache on demand. */
        public SurfaceProfile() { this.cache = new HashMap<>(); }

        /** Pre-populated profile from saved data. */
        public SurfaceProfile(Map<Long, Integer> saved) {
            this.cache = saved != null ? new HashMap<>(saved) : new HashMap<>();
        }

        private static long packXZ(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        // Wall materials to scan through when detecting existing wall on heightmap
        private static final Set<Block> WALL_MATERIALS = Set.of(
                Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB,
                Blocks.CHISELED_STONE_BRICKS
        );

        /** Get terrain Y — from cache if available, else compute from heightmap. */
        public int getY(ServerWorld world, int x, int z) {
            return cache.computeIfAbsent(packXZ(x, z), k -> computeGroundY(world, x, z));
        }

        /**
         * Compute the real ground Y, scanning down through any existing wall blocks.
         * When the heightmap returns the top of a wall (stone bricks / slab),
         * we scan down to find where the wall ends and natural terrain begins.
         */
        private static int computeGroundY(ServerWorld world, int x, int z) {
            int rawY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockState topState = world.getBlockState(new BlockPos(x, rawY, z));
            if (!WALL_MATERIALS.contains(topState.getBlock())) {
                return rawY; // Not a wall block — trust the heightmap
            }
            // Scan down through wall materials to find the original ground
            int scanY = rawY;
            while (scanY > world.getBottomY()) {
                BlockState below = world.getBlockState(new BlockPos(x, scanY - 1, z));
                if (!WALL_MATERIALS.contains(below.getBlock())) break;
                scanY--;
            }
            // scanY is now the lowest wall material (the foundation)
            // The original ground level was at the foundation Y
            return scanY;
        }

        /** Export the cached profile for persistence. Keys are "x,z" strings. */
        public Map<String, Integer> export() {
            Map<String, Integer> result = new HashMap<>();
            for (var entry : cache.entrySet()) {
                long packed = entry.getKey();
                int x = (int) (packed >> 32);
                int z = (int) packed;
                result.put(x + "," + z, entry.getValue());
            }
            return result;
        }

        /** True if this profile has any pre-populated data (from a previous build). */
        public boolean hasSavedData() { return !cache.isEmpty(); }

        /** Import from persistence format ("x,z" string keys). */
        public static SurfaceProfile fromSaved(Map<String, Integer> saved) {
            if (saved == null || saved.isEmpty()) return new SurfaceProfile();
            Map<Long, Integer> map = new HashMap<>();
            for (var entry : saved.entrySet()) {
                String[] parts = entry.getKey().split(",");
                if (parts.length == 2) {
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int z = Integer.parseInt(parts[1]);
                        map.put(packXZ(x, z), entry.getValue());
                    } catch (NumberFormatException ignored) {}
                }
            }
            return new SurfaceProfile(map);
        }
    }

    /** Complete fortification layout produced by generateLayout(). */
    public record FortificationLayout(
            BlockPos center,
            List<WallEdge> edges,
            List<ProceduralWallBlock> allBlocks,
            List<WallPoint> hullVertices,
            int gatehouseEdgeIndex,
            int totalBlocks,
            SurfaceProfile surfaceProfile
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
        int yHigh = searchCenter.getY() + 10;
        for (int x = searchCenter.getX() - sr; x <= searchCenter.getX() + sr; x += 1) {
            for (int z = searchCenter.getZ() - sr; z <= searchCenter.getZ() + sr; z += 1) {
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
        int scanYLow = searchCenter.getY() - 5;
        int scanYHigh = searchCenter.getY() + 15;
        int expandedMinX = minX - STRUCTURE_SCAN_RANGE;
        int expandedMaxX = maxX + STRUCTURE_SCAN_RANGE;
        int expandedMinZ = minZ - STRUCTURE_SCAN_RANGE;
        int expandedMaxZ = maxZ + STRUCTURE_SCAN_RANGE;

        for (int x = expandedMinX; x <= expandedMaxX; x++) {
            for (int z = expandedMinZ; z <= expandedMaxZ; z++) {
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

    // ── Structure footprint ────────────────────────────────────

    /** Exclusion buffer around detected structure blocks (in blocks). */
    private static final int FOOTPRINT_BUFFER = 2;

    /** Pack an XZ coordinate pair into a single long for set lookup. */
    public static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Check if an XZ position is inside the structure footprint. */
    public static boolean isInFootprint(Set<Long> footprint, int x, int z) {
        return footprint.contains(packXZ(x, z));
    }

    /**
     * Scan the village bounding box for structure blocks and POI blocks.
     * Returns a set of packed XZ coords padded by FOOTPRINT_BUFFER.
     */
    public static Set<Long> computeStructureFootprint(ServerWorld world, List<WallPoint> structurePositions) {
        if (structurePositions.isEmpty()) return Set.of();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (WallPoint p : structurePositions) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }

        Set<Block> poiBlocks = Set.of(
                Blocks.BELL, Blocks.COMPOSTER, Blocks.BARREL, Blocks.SMOKER,
                Blocks.BLAST_FURNACE, Blocks.BREWING_STAND, Blocks.CARTOGRAPHY_TABLE,
                Blocks.FLETCHING_TABLE, Blocks.GRINDSTONE, Blocks.LECTERN,
                Blocks.LOOM, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.CAULDRON
        );

        // Scan at step-1 across the detected village area
        int cy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                (minX + maxX) / 2, (minZ + maxZ) / 2);
        int yLow = cy - 6;
        int yHigh = cy + 16;

        Set<Long> rawPositions = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = yLow; y <= yHigh; y++) {
                    Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (VILLAGE_STRUCTURE_BLOCKS.contains(block)
                            || block instanceof BedBlock
                            || poiBlocks.contains(block)) {
                        rawPositions.add(packXZ(x, z));
                        break; // found structure at this XZ, no need to scan more Y
                    }
                }
            }
        }

        // Expand by FOOTPRINT_BUFFER
        Set<Long> footprint = new HashSet<>();
        for (long packed : rawPositions) {
            int bx = (int) (packed >> 32);
            int bz = (int) packed;
            for (int dx = -FOOTPRINT_BUFFER; dx <= FOOTPRINT_BUFFER; dx++) {
                for (int dz = -FOOTPRINT_BUFFER; dz <= FOOTPRINT_BUFFER; dz++) {
                    footprint.add(packXZ(bx + dx, bz + dz));
                }
            }
        }

        LOGGER.info("Structure footprint: {} raw positions, {} with buffer", rawPositions.size(), footprint.size());
        return footprint;
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
            return new FortificationLayout(center, List.of(), List.of(), List.of(), -1, 0, new SurfaceProfile());
        }

        // 2. Compute structure footprint for collision avoidance
        Set<Long> structureFootprint = computeStructureFootprint(world, structurePositions);

        // 3. Compute convex hull
        List<WallPoint> hull = computeConvexHull(structurePositions);

        // Fallback: if < 3 hull points, create a rectangular fallback
        if (hull.size() < 3) {
            LOGGER.warn("Hull has {} points, falling back to rectangular layout", hull.size());
            hull = createRectangularFallback(center);
        }

        // 4. Expand hull outward
        List<WallPoint> expandedHull = expandHull(hull, MARGIN);

        // 5. Generate wall blocks with footprint exclusion
        return generateWallBlocks(expandedHull, world, center, structureFootprint);
    }

    /**
     * Generate layout from pre-computed hull vertices (for resume from saved data).
     * We still apply a fresh structure footprint filter so resumed runs do not
     * place walls directly through newly detected village buildings.
     */
    public static FortificationLayout generateLayoutFromHull(List<WallPoint> hullVertices, ServerWorld world, BlockPos center) {
        return generateLayoutFromHull(hullVertices, world, center, null);
    }

    /**
     * Generate layout from pre-computed hull vertices with an optional saved surface profile.
     * When a saved profile is provided, terrain Y lookups use the saved values instead of
     * the current heightmap, ensuring layout stability even when the wall already exists.
     */
    public static FortificationLayout generateLayoutFromHull(List<WallPoint> hullVertices, ServerWorld world,
                                                              BlockPos center, SurfaceProfile savedProfile) {
        if (hullVertices.size() < 3) {
            LOGGER.warn("Saved hull has {} points, cannot regenerate", hullVertices.size());
            return new FortificationLayout(center, List.of(), List.of(), hullVertices, -1, 0, new SurfaceProfile());
        }
        SurfaceProfile profile = savedProfile != null ? savedProfile : new SurfaceProfile();
        // When a saved profile exists (resume/patch), skip structure footprint to prevent
        // the bot's own wall blocks (stone bricks) from being detected as village structures
        // and shrinking the layout. The hull already accounts for the village boundary.
        Set<Long> structureFootprint;
        if (savedProfile != null && savedProfile.hasSavedData()) {
            structureFootprint = Set.of(); // empty — trust the established layout
        } else {
            List<WallPoint> structurePositions = detectVillagePositions(world, center, DEFAULT_SEARCH_RADIUS);
            structureFootprint = computeStructureFootprint(world, structurePositions);
        }
        return generateWallBlocks(hullVertices, world, center, structureFootprint, profile);
    }

    /**
     * Generate all wall blocks from the expanded hull.
     *
     * @param structureFootprint packed XZ positions of village structures; wall blocks at
     *                           these positions are skipped to avoid building through houses
     */
    private static FortificationLayout generateWallBlocks(List<WallPoint> expandedHull, ServerWorld world,
                                                           BlockPos center, Set<Long> structureFootprint) {
        return generateWallBlocks(expandedHull, world, center, structureFootprint, new SurfaceProfile());
    }

    private static FortificationLayout generateWallBlocks(List<WallPoint> expandedHull, ServerWorld world,
                                                           BlockPos center, Set<Long> structureFootprint,
                                                           SurfaceProfile surfaceProfile) {
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
            generateTower(allBlocks, vertex, world, -1, structureFootprint, surfaceProfile);
        }

        // Generate wall blocks for each edge
        int gatehouseEdgeIndex = longestEdgeIdx;
        for (int i = 0; i < edges.size(); i++) {
            WallEdge edge = edges.get(i);
            List<WallPoint> traced = traceEdge(edge.start(), edge.end());

            // Compute per-edge outward normal (for moat/clearance offset direction)
            double edgeDx = edge.end().x() - edge.start().x();
            double edgeDz = edge.end().z() - edge.start().z();
            double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
            int normalX = 0, normalZ = 0;
            if (edgeLen > 0.001) {
                // Outward normal for CCW hull: rotate edge direction 90° CW = (dz, -dx)
                normalX = (int) Math.round(edgeDz / edgeLen);
                normalZ = (int) Math.round(-edgeDx / edgeLen);
            }

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
                    generateWallColumn(allBlocks, traced.get(j), world, i, normalX, normalZ, structureFootprint, surfaceProfile);
                }

                // Gatehouse pillars + lintel
                if (gateStart >= startIdx && gateEnd < endIdx) {
                    generateGatehouse(allBlocks, traced.get(gateStart), traced.get(gateEnd),
                            world, i, normalX, normalZ, structureFootprint, surfaceProfile);
                }

                // Gatehouse bridge: fill moat under the gate gap with walkable floor
                for (int j = gateStart + 1; j < gateEnd; j++) {
                    if (j >= 0 && j < traced.size()) {
                        WallPoint gp = traced.get(j);
                        // Generate moat floor at terrain level across gate gap (bridge)
                        // Covers inner face (+1) through end of moat ditch (+2+MOAT_WIDTH)
                        for (int moatOff = 1; moatOff <= 2 + MOAT_WIDTH; moatOff++) {
                            int mx = gp.x() + normalX * moatOff;
                            int mz = gp.z() + normalZ * moatOff;
                            if (!isInFootprint(structureFootprint, mx, mz)) {
                                int baseY = surfaceProfile.getY(world, mx, mz);
                                allBlocks.add(new ProceduralWallBlock(
                                        new BlockPos(mx, baseY - MOAT_DEPTH, mz),
                                        Blocks.COBBLESTONE.getDefaultState(),
                                        WallBlockType.MOAT_FLOOR, i));
                            }
                        }
                    }
                }

                // Wall blocks after gatehouse
                for (int j = gateEnd + 1; j < endIdx; j++) {
                    generateWallColumn(allBlocks, traced.get(j), world, i, normalX, normalZ, structureFootprint, surfaceProfile);
                }
            } else {
                // Normal edge: all wall columns
                for (int j = startIdx; j < endIdx; j++) {
                    generateWallColumn(allBlocks, traced.get(j), world, i, normalX, normalZ, structureFootprint, surfaceProfile);
                }
            }
        }

        LOGGER.info("Generated fortification: {} hull vertices, {} edges, {} blocks, gatehouse on edge {}",
                expandedHull.size(), edges.size(), allBlocks.size(), gatehouseEdgeIndex);

        return new FortificationLayout(center, edges, allBlocks, expandedHull,
                gatehouseEdgeIndex, allBlocks.size(), surfaceProfile);
    }

    /**
     * Generate a 3x3 tower at a hull vertex.
     * Tower: 3x3 stone brick ring Y+0..Y+4, center oak log post Y+0..Y+5, slab at Y+5.
     */
    private static void generateTower(List<ProceduralWallBlock> blocks, WallPoint vertex,
                                       ServerWorld world, int edgeIndex, Set<Long> structureFootprint,
                                       SurfaceProfile surfaceProfile) {
        int baseY = surfaceProfile.getY(world, vertex.x(), vertex.z());
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState slab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        BlockState oakLog = Blocks.OAK_LOG.getDefaultState();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int wx = vertex.x() + dx;
                int wz = vertex.z() + dz;
                if (isInFootprint(structureFootprint, wx, wz)) continue;

                boolean isCenter = dx == 0 && dz == 0;

                if (!isCenter) {
                    // Ring walls Y+0..Y+4
                    for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                        WallBlockType type = dy == 0 ? WallBlockType.TOWER_BASE : WallBlockType.TOWER_WALL;
                        blocks.add(new ProceduralWallBlock(
                                new BlockPos(wx, baseY + dy, wz), stoneBricks, type, edgeIndex));
                    }
                    // Slab at Y+5
                    blocks.add(new ProceduralWallBlock(
                            new BlockPos(wx, baseY + TOWER_HEIGHT - 1, wz), slab,
                            WallBlockType.WALL_TOP_SLAB, edgeIndex));
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
     * Generate a full wall cross-section at a point, extending outward along the normal.
     *
     * Cross-section (exterior → interior, offsets along outward normal):
     *   Offset 0 (wall): foundation Y+0, wall Y+1..Y+3, slab Y+4
     *   Offset +1 (inner face): stone bricks Y-3..Y+0 (cliff above moat)
     *   Offset +2 (overhang): stone brick at Y+0 (stops spider climbing)
     *   Offset +2..+4 (moat ditch): air Y+0..Y-2, cobblestone floor Y-3
     *   Offset +5..+6 (exterior clear): break blocks Y-1..Y+4
     */
    private static void generateWallColumn(List<ProceduralWallBlock> blocks, WallPoint point,
                                            ServerWorld world, int edgeIndex,
                                            int normalX, int normalZ, Set<Long> structureFootprint,
                                            SurfaceProfile surfaceProfile) {
        int baseY = surfaceProfile.getY(world, point.x(), point.z());
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState slab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        BlockState cobble = Blocks.COBBLESTONE.getDefaultState();

        // === Wall column (offset 0) ===
        if (!isInFootprint(structureFootprint, point.x(), point.z())) {
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
            // Stone brick slab Y+4
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(point.x(), baseY + 4, point.z()), slab,
                    WallBlockType.WALL_TOP_SLAB, edgeIndex));
        }

        // === Inner face (offset +1 along normal): 4 blocks Y-3..Y+0 ===
        int faceX = point.x() + normalX;
        int faceZ = point.z() + normalZ;
        if (!isInFootprint(structureFootprint, faceX, faceZ)) {
            for (int dy = 0; dy >= -MOAT_DEPTH; dy--) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(faceX, baseY + dy, faceZ), stoneBricks,
                        WallBlockType.MOAT_INNER_FACE, edgeIndex));
            }
        }

        // === Overhang (offset +2 along normal, Y+0): stops spiders ===
        int ovX = point.x() + normalX * 2;
        int ovZ = point.z() + normalZ * 2;
        if (!isInFootprint(structureFootprint, ovX, ovZ)) {
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(ovX, baseY, ovZ), stoneBricks,
                    WallBlockType.MOAT_OVERHANG, edgeIndex));
        }

        // === Moat ditch (offsets +3..+(2+MOAT_WIDTH) along normal): dig air + cobble floor ===
        // Starts at offset 3 (beyond overhang at +2) to avoid digging out the spider guard.
        for (int moatOff = 3; moatOff <= 2 + MOAT_WIDTH; moatOff++) {
            int mx = point.x() + normalX * moatOff;
            int mz = point.z() + normalZ * moatOff;
            if (isInFootprint(structureFootprint, mx, mz)) continue;

            // Dig from surface down to just above the floor (MOAT_DIG).
            for (int dy = 0; dy >= -(MOAT_DEPTH - 1); dy--) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(mx, baseY + dy, mz), Blocks.AIR.getDefaultState(),
                        WallBlockType.MOAT_DIG, edgeIndex));
            }
            // Cobblestone floor Y-3
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(mx, baseY - MOAT_DEPTH, mz), cobble,
                    WallBlockType.MOAT_FLOOR, edgeIndex));
        }

        // === Exterior clearance (beyond moat ditch): break blocks Y-1..Y+4 ===
        int clearStart = 3 + MOAT_WIDTH;
        for (int clearOff = clearStart; clearOff < clearStart + EXTERIOR_CLEAR_DIST; clearOff++) {
            int cx = point.x() + normalX * clearOff;
            int cz = point.z() + normalZ * clearOff;
            if (isInFootprint(structureFootprint, cx, cz)) continue;

            for (int dy = -1; dy <= 4; dy++) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(cx, baseY + dy, cz), Blocks.AIR.getDefaultState(),
                        WallBlockType.EXTERIOR_CLEAR, edgeIndex));
            }
        }
    }

    /**
     * Generate a gatehouse: 2 pillars flanking a 3-block gap with a lintel.
     * Left pillar: stone bricks Y+0..Y+4, chiseled cap Y+5
     * Right pillar: stone bricks Y+0..Y+4, chiseled cap Y+5
     * Lintel: stone bricks at Y+3 across the gap
     * Each pillar also gets inner face and overhang on the outward side.
     */
    private static void generateGatehouse(List<ProceduralWallBlock> blocks,
                                           WallPoint leftPillar, WallPoint rightPillar,
                                           ServerWorld world, int edgeIndex,
                                           int normalX, int normalZ, Set<Long> structureFootprint,
                                           SurfaceProfile surfaceProfile) {
        BlockState stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.getDefaultState();

        // Left pillar
        int leftY = surfaceProfile.getY(world, leftPillar.x(), leftPillar.z());
        if (!isInFootprint(structureFootprint, leftPillar.x(), leftPillar.z())) {
            for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(leftPillar.x(), leftY + dy, leftPillar.z()), stoneBricks,
                        WallBlockType.GATEHOUSE_PILLAR, edgeIndex));
            }
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(leftPillar.x(), leftY + WALL_HEIGHT, leftPillar.z()), chiseled,
                    WallBlockType.GATEHOUSE_CAP, edgeIndex));
        }

        // Right pillar
        int rightY = surfaceProfile.getY(world, rightPillar.x(), rightPillar.z());
        if (!isInFootprint(structureFootprint, rightPillar.x(), rightPillar.z())) {
            for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(rightPillar.x(), rightY + dy, rightPillar.z()), stoneBricks,
                        WallBlockType.GATEHOUSE_PILLAR, edgeIndex));
            }
            blocks.add(new ProceduralWallBlock(
                    new BlockPos(rightPillar.x(), rightY + WALL_HEIGHT, rightPillar.z()), chiseled,
                    WallBlockType.GATEHOUSE_CAP, edgeIndex));
        }

        // Lintel: trace between pillars at Y+3
        List<WallPoint> lintelPoints = traceEdge(leftPillar, rightPillar);
        int lintelY = Math.max(leftY, rightY) + 3;
        // Skip pillar positions (first and last)
        for (int i = 1; i < lintelPoints.size() - 1; i++) {
            WallPoint lp = lintelPoints.get(i);
            if (!isInFootprint(structureFootprint, lp.x(), lp.z())) {
                blocks.add(new ProceduralWallBlock(
                        new BlockPos(lp.x(), lintelY, lp.z()), stoneBricks,
                        WallBlockType.GATEHOUSE_LINTEL, edgeIndex));
            }
        }

        // Inner face + moat for pillar positions (but no overhang/moat-dig at gate gap)
        for (WallPoint pillar : List.of(leftPillar, rightPillar)) {
            int py = surfaceProfile.getY(world, pillar.x(), pillar.z());
            // Inner face
            int faceX = pillar.x() + normalX;
            int faceZ = pillar.z() + normalZ;
            if (!isInFootprint(structureFootprint, faceX, faceZ)) {
                for (int dy = 0; dy >= -MOAT_DEPTH; dy--) {
                    blocks.add(new ProceduralWallBlock(
                            new BlockPos(faceX, py + dy, faceZ), stoneBricks,
                            WallBlockType.MOAT_INNER_FACE, edgeIndex));
                }
            }
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
        int towers = 0, walls = 0, gateBlocks = 0, moatBlocks = 0, clearBlocks = 0;
        for (ProceduralWallBlock block : layout.allBlocks()) {
            switch (block.type()) {
                case TOWER_BASE, TOWER_WALL, TOWER_CAP -> towers++;
                case FOUNDATION, WALL, MERLON, WALL_TOP_SLAB -> walls++;
                case GATEHOUSE_PILLAR, GATEHOUSE_LINTEL, GATEHOUSE_CAP -> gateBlocks++;
                case MOAT_DIG, MOAT_FLOOR, MOAT_INNER_FACE, MOAT_OVERHANG -> moatBlocks++;
                case EXTERIOR_CLEAR -> clearBlocks++;
            }
        }
        return String.format(
                "Fortification plan: %d hull vertices, %d edges, %d blocks " +
                "(%d tower, %d wall, %d gatehouse, %d moat, %d clearance), gatehouse on edge #%d, center (%d, %d, %d)",
                layout.hullVertices().size(), layout.edges().size(), layout.totalBlocks(),
                towers, walls, gateBlocks, moatBlocks, clearBlocks,
                layout.gatehouseEdgeIndex(),
                layout.center().getX(), layout.center().getY(), layout.center().getZ()
        );
    }

    // ── Overlap detection ───────────────────────────────────────

    /**
     * Test whether a point lies inside a convex hull (CCW winding).
     * Uses the cross-product point-in-polygon test.
     */
    public static boolean pointInConvexHull(List<WallPoint> hull, int px, int pz) {
        if (hull.size() < 3) return false;
        int n = hull.size();
        for (int i = 0; i < n; i++) {
            WallPoint a = hull.get(i);
            WallPoint b = hull.get((i + 1) % n);
            // For CCW hull, all cross products should be >= 0 for interior points
            long cross = (long)(b.x() - a.x()) * (pz - a.z()) - (long)(b.z() - a.z()) * (px - a.x());
            if (cross < 0) return false;
        }
        return true;
    }

    /**
     * Test whether two convex hulls overlap using the Separating Axis Theorem (SAT).
     * If a separating axis exists, the hulls do NOT overlap.
     */
    public static boolean hullsOverlap(List<WallPoint> hullA, List<WallPoint> hullB) {
        if (hullA.size() < 3 || hullB.size() < 3) return false;
        // Check axes from hull A edges
        if (hasSeparatingAxis(hullA, hullB)) return false;
        // Check axes from hull B edges
        if (hasSeparatingAxis(hullB, hullA)) return false;
        return true;
    }

    private static boolean hasSeparatingAxis(List<WallPoint> reference, List<WallPoint> other) {
        int n = reference.size();
        for (int i = 0; i < n; i++) {
            WallPoint a = reference.get(i);
            WallPoint b = reference.get((i + 1) % n);
            // Edge normal (perpendicular): for edge (dx, dz), normal is (dz, -dx)
            int nx = b.z() - a.z();
            int nz = -(b.x() - a.x());

            // Project reference hull
            long refMin = Long.MAX_VALUE, refMax = Long.MIN_VALUE;
            for (WallPoint p : reference) {
                long proj = (long) nx * p.x() + (long) nz * p.z();
                refMin = Math.min(refMin, proj);
                refMax = Math.max(refMax, proj);
            }

            // Project other hull
            long otherMin = Long.MAX_VALUE, otherMax = Long.MIN_VALUE;
            for (WallPoint p : other) {
                long proj = (long) nx * p.x() + (long) nz * p.z();
                otherMin = Math.min(otherMin, proj);
                otherMax = Math.max(otherMax, proj);
            }

            // Separating axis found if projections don't overlap
            if (refMax < otherMin || otherMax < refMin) return true;
        }
        return false;
    }

    /**
     * Estimate the overlap percentage between two hulls using grid sampling.
     * Samples at 2-block intervals within the bounding box of both hulls.
     *
     * @return overlap ratio [0.0, 1.0] relative to the smaller hull's area
     */
    public static double overlapPercentage(List<WallPoint> hullA, List<WallPoint> hullB) {
        if (!hullsOverlap(hullA, hullB)) return 0.0;

        // Compute bounding box of both hulls
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (WallPoint p : hullA) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        for (WallPoint p : hullB) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }

        int inA = 0, inB = 0, inBoth = 0;
        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) {
                boolean a = pointInConvexHull(hullA, x, z);
                boolean b = pointInConvexHull(hullB, x, z);
                if (a) inA++;
                if (b) inB++;
                if (a && b) inBoth++;
            }
        }

        int smallerArea = Math.min(inA, inB);
        return smallerArea > 0 ? (double) inBoth / smallerArea : 0.0;
    }

    /**
     * Merge two hulls into a unified convex hull.
     * Combines all vertices and recomputes the convex hull.
     */
    public static List<WallPoint> mergeHulls(List<WallPoint> hullA, List<WallPoint> hullB) {
        List<WallPoint> combined = new ArrayList<>(hullA.size() + hullB.size());
        combined.addAll(hullA);
        combined.addAll(hullB);
        return computeConvexHull(combined);
    }
}
