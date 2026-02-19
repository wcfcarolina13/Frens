package net.shasankp000.GameAI.services.construction;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plans a rectangular defensive wall layout around a detected village boundary.
 * Produces an ordered list of wall segments (corners, sections, gatehouses)
 * that FortifyVillageSkill builds sequentially.
 */
public class VillageFortificationLayoutService {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillageFortificationLayout");

    /** Margin added outside the detected village bounding box. */
    private static final int MARGIN = 8;
    /** Minimum half-width/half-depth of the fortification rectangle. */
    private static final int MIN_RADIUS = 12;
    /** Maximum half-width/half-depth — prevents absurdly large walls on flat worlds. */
    private static final int MAX_RADIUS = 35;
    /** Default search radius for villagers and POI blocks. */
    private static final int DEFAULT_SEARCH_RADIUS = 64;

    // Schematic footprint sizes (from SimpleSchematicBuilder)
    public static final int WALL_SECTION_LENGTH = 5;  // sizeX of defensive_wall_section
    public static final int WALL_SECTION_DEPTH  = 2;  // sizeZ
    public static final int CORNER_SIZE         = 3;  // sizeX = sizeZ of defensive_wall_corner
    public static final int GATEHOUSE_WIDTH     = 3;  // sizeX of defensive_gatehouse
    public static final int GATEHOUSE_DEPTH     = 2;  // sizeZ

    // ── Data types ──────────────────────────────────────────────

    public enum SegmentType { CORNER, WALL_SECTION, GATEHOUSE }

    public enum CardinalSide { NORTH, EAST, SOUTH, WEST }

    public record WallSegment(
            SegmentType type,
            BlockPos origin,
            int quarterTurns,
            CardinalSide side,
            int segmentIndex
    ) {}

    public record FortificationLayout(
            BlockPos center,
            int radiusX,
            int radiusZ,
            List<WallSegment> segments,
            int totalBlockEstimate
    ) {}

    public record VillageBounds(
            BlockPos center,
            int radiusX,
            int radiusZ,
            int foundPOIs
    ) {}

    // ── Village detection ───────────────────────────────────────

    /**
     * Scans for villagers and POI blocks (beds, bells, workstations) to compute
     * a bounding rectangle around the village, with margin.
     */
    public static VillageBounds detectVillageBounds(ServerWorld world, BlockPos searchCenter, int searchRadius) {
        int sr = searchRadius > 0 ? searchRadius : DEFAULT_SEARCH_RADIUS;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int poiCount = 0;

        // Scan villagers on the server thread to avoid ConcurrentModificationException
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
            minX = Math.min(minX, vp.getX());
            maxX = Math.max(maxX, vp.getX());
            minZ = Math.min(minZ, vp.getZ());
            maxZ = Math.max(maxZ, vp.getZ());
            poiCount++;
        }

        // Scan POI blocks: beds, bells, and common workstations
        Set<Block> poiBlocks = Set.of(
                Blocks.BELL,
                Blocks.COMPOSTER, Blocks.BARREL, Blocks.SMOKER,
                Blocks.BLAST_FURNACE, Blocks.BREWING_STAND,
                Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE,
                Blocks.GRINDSTONE, Blocks.LECTERN, Blocks.LOOM,
                Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
                Blocks.CAULDRON
        );

        // Scan POI blocks using stride-2 sampling to reduce iterations (~4x faster).
        // POI blocks are full-size blocks, so stride-2 still catches them reliably
        // since villages cluster POIs. Y range ±5 covers village terrain variation.
        int yLow = searchCenter.getY() - 5;
        int yHigh = searchCenter.getY() + 5;
        for (int x = searchCenter.getX() - sr; x <= searchCenter.getX() + sr; x += 2) {
            for (int z = searchCenter.getZ() - sr; z <= searchCenter.getZ() + sr; z += 2) {
                for (int y = yLow; y <= yHigh; y++) {
                    Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (poiBlocks.contains(block) || block instanceof BedBlock) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                        poiCount++;
                    }
                }
            }
        }

        if (poiCount == 0) {
            LOGGER.warn("No village POIs found within {} blocks of {}", sr, searchCenter.toShortString());
            return new VillageBounds(searchCenter, MIN_RADIUS, MIN_RADIUS, 0);
        }

        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;
        int halfX = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (maxX - minX) / 2 + MARGIN));
        int halfZ = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, (maxZ - minZ) / 2 + MARGIN));

        // Sample terrain Y at center
        int cy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);

        LOGGER.info("Village bounds: center=({},{},{}), radius={}x{}, POIs={}",
                cx, cy, cz, halfX, halfZ, poiCount);
        return new VillageBounds(new BlockPos(cx, cy, cz), halfX, halfZ, poiCount);
    }

    // ── Layout generation ───────────────────────────────────────

    /**
     * Generates a clockwise-ordered list of wall segments for a rectangular fortification.
     *
     * @param world          the server world (for terrain Y sampling)
     * @param center         center of the fortification rectangle
     * @param radiusX        half-width (X axis)
     * @param radiusZ        half-depth (Z axis)
     * @param gatehouseSides which sides get a gatehouse (replaces the middle wall section)
     * @return the ordered layout
     */
    public static FortificationLayout generateLayout(
            ServerWorld world,
            BlockPos center,
            int radiusX,
            int radiusZ,
            EnumSet<CardinalSide> gatehouseSides
    ) {
        // Snap radii up so wall length between corners is a multiple of WALL_SECTION_LENGTH.
        // Wall length per side = 2*radius - 2*CORNER_SIZE. Need this divisible by WALL_SECTION_LENGTH.
        radiusX = snapRadius(radiusX);
        radiusZ = snapRadius(radiusZ);

        List<WallSegment> segments = new ArrayList<>();
        int segIdx = 0;
        int totalBlocks = 0;

        // Rectangle corners (outer edge of the wall):
        //   NW ---- North wall ---- NE
        //   |                        |
        //   West                   East
        //   |                        |
        //   SW ---- South wall ---- SE
        //
        // Corner schematics: tower post at (0,0,0), arms extend along +X and +Z.
        // After CW rotation, the tower moves within the bounding box:
        //   0 turns: tower at (0,0)   — NW vertex, arms go +X (east)  and +Z (south)
        //   1 turn:  tower at (2,0)   — NE vertex, arms go +Z (south) and -X (west)
        //   2 turns: tower at (2,2)   — SE vertex, arms go -X (west)  and -Z (north)
        //   3 turns: tower at (0,2)   — SW vertex, arms go -Z (north) and +X (east)

        int nx = center.getX() - radiusX;  // north-west X
        int px = center.getX() + radiusX;  // north-east X (outer)
        int nz = center.getZ() - radiusZ;  // north Z (outer edge)
        int pz = center.getZ() + radiusZ;  // south Z (outer edge)

        // Corner positions (origin of each corner schematic)
        BlockPos nwCorner = new BlockPos(nx, terrainY(world, nx, nz), nz);
        BlockPos neCorner = new BlockPos(px - (CORNER_SIZE - 1), terrainY(world, px, nz), nz);
        BlockPos seCorner = new BlockPos(px - (CORNER_SIZE - 1), terrainY(world, px, pz), pz - (CORNER_SIZE - 1));
        BlockPos swCorner = new BlockPos(nx, terrainY(world, nx, pz), pz - (CORNER_SIZE - 1));

        // NW corner
        segments.add(new WallSegment(SegmentType.CORNER, nwCorner, 0, CardinalSide.NORTH, segIdx++));
        totalBlocks += 25; // approximate block count for corner

        // North wall: from NW corner's east arm end to NE corner's west arm start
        int northWallStartX = nx + CORNER_SIZE;
        int northWallEndX = px - CORNER_SIZE;
        int northWallZ = nz; // wall exterior face at north Z
        totalBlocks += fillWallSide(world, segments, CardinalSide.NORTH,
                northWallStartX, northWallEndX, northWallZ,
                0, // turns=0: section extends along +X, face at Z=0
                true, // extend along X
                gatehouseSides.contains(CardinalSide.NORTH),
                segIdx);
        segIdx = segments.size();

        // NE corner (1 turn CW: tower at relative (2,0), arms go south & west)
        segments.add(new WallSegment(SegmentType.CORNER, neCorner, 1, CardinalSide.EAST, segIdx++));
        totalBlocks += 25;

        // East wall: from NE corner's south arm end to SE corner's north arm start
        int eastWallStartZ = nz + CORNER_SIZE;
        int eastWallEndZ = pz - CORNER_SIZE;
        int eastWallX = px - (WALL_SECTION_DEPTH - 1); // wall face at east edge
        totalBlocks += fillWallSide(world, segments, CardinalSide.EAST,
                eastWallStartZ, eastWallEndZ, eastWallX,
                1, // turns=1: section extends along +Z
                false, // extend along Z
                gatehouseSides.contains(CardinalSide.EAST),
                segIdx);
        segIdx = segments.size();

        // SE corner
        segments.add(new WallSegment(SegmentType.CORNER, seCorner, 2, CardinalSide.SOUTH, segIdx++));
        totalBlocks += 25;

        // South wall: from SE corner's west arm end to SW corner's east arm start
        // South wall goes in -X direction (right to left), but we place segments left to right
        int southWallStartX = nx + CORNER_SIZE;
        int southWallEndX = px - CORNER_SIZE;
        int southWallZ = pz - (WALL_SECTION_DEPTH - 1); // wall face at south edge
        totalBlocks += fillWallSide(world, segments, CardinalSide.SOUTH,
                southWallStartX, southWallEndX, southWallZ,
                2, // turns=2: section extends along -X (mirrored)
                true, // extend along X
                gatehouseSides.contains(CardinalSide.SOUTH),
                segIdx);
        segIdx = segments.size();

        // SW corner (3 turns CW: tower at relative (0,2), arms go north & east)
        segments.add(new WallSegment(SegmentType.CORNER, swCorner, 3, CardinalSide.WEST, segIdx++));
        totalBlocks += 25;

        // West wall: from SW corner's north arm end to NW corner's south arm start
        int westWallStartZ = nz + CORNER_SIZE;
        int westWallEndZ = pz - CORNER_SIZE;
        int westWallX = nx; // wall face at west edge
        totalBlocks += fillWallSide(world, segments, CardinalSide.WEST,
                westWallStartZ, westWallEndZ, westWallX,
                3, // turns=3
                false, // extend along Z
                gatehouseSides.contains(CardinalSide.WEST),
                segIdx);

        LOGGER.info("Generated fortification layout: {} segments, ~{} blocks, radius {}x{}",
                segments.size(), totalBlocks, radiusX, radiusZ);

        return new FortificationLayout(center, radiusX, radiusZ, segments, totalBlocks);
    }

    // ── Internal helpers ────────────────────────────────────────

    /**
     * Fills one side of the wall with sections and optionally a gatehouse.
     * Returns estimated block count for this side.
     */
    private static int fillWallSide(
            ServerWorld world,
            List<WallSegment> segments,
            CardinalSide side,
            int startCoord,
            int endCoord,
            int fixedCoord,
            int quarterTurns,
            boolean alongX,
            boolean hasGatehouse,
            int segIdxStart
    ) {
        int length = endCoord - startCoord;
        if (length <= 0) return 0;

        int sectionCount = length / WALL_SECTION_LENGTH;
        if (sectionCount <= 0) sectionCount = 1;

        int gatehouseIdx = hasGatehouse ? sectionCount / 2 : -1;
        int blockEstimate = 0;
        int segIdx = segIdxStart;
        int coord = startCoord;

        for (int i = 0; i < sectionCount && coord + WALL_SECTION_LENGTH <= endCoord; i++) {
            BlockPos origin;
            if (alongX) {
                int y = terrainY(world, coord + WALL_SECTION_LENGTH / 2, fixedCoord);
                origin = new BlockPos(coord, y, fixedCoord);
            } else {
                int y = terrainY(world, fixedCoord, coord + WALL_SECTION_LENGTH / 2);
                origin = new BlockPos(fixedCoord, y, coord);
            }

            if (i == gatehouseIdx) {
                // For gatehouse, center it in the section span.
                // Gatehouse is narrower (3 wide) vs wall section (5 wide), center it
                int offset = (WALL_SECTION_LENGTH - GATEHOUSE_WIDTH) / 2;
                BlockPos ghOrigin;
                if (alongX) {
                    ghOrigin = new BlockPos(coord + offset, origin.getY(), fixedCoord);
                } else {
                    ghOrigin = new BlockPos(fixedCoord, origin.getY(), coord + offset);
                }
                segments.add(new WallSegment(SegmentType.GATEHOUSE, ghOrigin, quarterTurns, side, segIdx++));
                blockEstimate += 30;
            } else {
                segments.add(new WallSegment(SegmentType.WALL_SECTION, origin, quarterTurns, side, segIdx++));
                blockEstimate += 20;
            }

            coord += WALL_SECTION_LENGTH;
        }

        return blockEstimate;
    }

    /**
     * Snap a radius up so that the wall length between corners is a positive
     * multiple of WALL_SECTION_LENGTH, eliminating gaps.
     * wallLength = 2*(radius - CORNER_SIZE), so we need (radius - CORNER_SIZE)
     * to be a multiple of WALL_SECTION_LENGTH.
     */
    private static int snapRadius(int radius) {
        int k = radius - CORNER_SIZE;
        if (k < WALL_SECTION_LENGTH) {
            k = WALL_SECTION_LENGTH; // minimum 1 section per side
        }
        int remainder = k % WALL_SECTION_LENGTH;
        if (remainder != 0) {
            k += WALL_SECTION_LENGTH - remainder;
        }
        return k + CORNER_SIZE;
    }

    /**
     * Sample terrain Y at the given XZ from the heightmap.
     */
    private static int terrainY(ServerWorld world, int x, int z) {
        return world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /**
     * Describe the layout in a human-readable summary for chat output.
     */
    public static String describePlan(FortificationLayout layout) {
        int corners = 0, walls = 0, gatehouses = 0;
        for (WallSegment seg : layout.segments()) {
            switch (seg.type()) {
                case CORNER -> corners++;
                case WALL_SECTION -> walls++;
                case GATEHOUSE -> gatehouses++;
            }
        }
        return String.format(
                "Fortification plan: %d segments (%d corners, %d walls, %d gatehouses), " +
                "~%d blocks, radius %dx%d around (%d, %d, %d)",
                layout.segments().size(), corners, walls, gatehouses,
                layout.totalBlockEstimate(),
                layout.radiusX(), layout.radiusZ(),
                layout.center().getX(), layout.center().getY(), layout.center().getZ()
        );
    }
}
