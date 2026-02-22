package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.schematic.SchematicData;
import net.wcfcarolina13.GameAI.services.construction.execution.PlacementTarget;

import java.util.*;

/**
 * Generates build order blueprints from schematic data.
 * Handles layer-by-layer ordering, corner prioritization, 
 * door detection, and perimeter-first strategies.
 * 
 * <p>This service abstracts the "what to build in what order" logic
 * that made the hovel builder successful, making it reusable for
 * any schematic-based construction (watchtower, bridge, defensive walls, etc.).</p>
 */
public final class ConstructionBlueprintService {

    private ConstructionBlueprintService() {}

    /**
     * Represents a categorized build target.
     */
    public record BuildTarget(
            BlockPos worldPos,
            BlockState state,
            BuildCategory category,
            int layerY,
            int priority
    ) implements Comparable<BuildTarget> {
        @Override
        public int compareTo(BuildTarget other) {
            // Priority order: lower layer first, then by category, then by priority value
            int layerCmp = Integer.compare(this.layerY, other.layerY);
            if (layerCmp != 0) return layerCmp;
            int catCmp = Integer.compare(this.category.ordinal(), other.category.ordinal());
            if (catCmp != 0) return catCmp;
            return Integer.compare(this.priority, other.priority);
        }
    }

    /**
     * Build categories determine placement order within a layer.
     */
    public enum BuildCategory {
        FOUNDATION,     // Floor/ground level blocks
        CORNER,         // Corner pillars/posts - built first for stability
        WALL,           // Wall blocks (not corners)
        INTERIOR,       // Interior blocks (furniture, torches)
        ROOF,           // Roof blocks
        DOOR,           // Door blocks - placed last
        DECORATION      // Non-structural decorations
    }

    /**
     * Result of analyzing a schematic for construction.
     */
    public record ConstructionPlan(
            List<BuildTarget> orderedTargets,
            BlockPos origin,
            BlockPos center,
            int minY,
            int maxY,
            int width,
            int depth,
            int height,
            Direction suggestedDoorSide,
            List<BlockPos> cornerPositions,
            List<BlockPos> perimeterPositions,
            List<BlockPos> roofPositions
    ) {}

    /**
     * Analyze a schematic and generate a construction plan.
     * 
     * @param schematic The schematic data to analyze
     * @param worldOrigin Where in the world to place the schematic
     * @param facing Direction the structure should face (affects door placement)
     * @return A construction plan with ordered targets
     */
    public static ConstructionPlan planConstruction(SchematicData schematic, BlockPos worldOrigin, Direction facing) {
        List<BuildTarget> targets = new ArrayList<>();
        
        // Track bounds and special positions
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        
        Set<BlockPos> doorPositions = new HashSet<>();
        List<BlockPos> corners = new ArrayList<>();
        List<BlockPos> perimeter = new ArrayList<>();
        List<BlockPos> roof = new ArrayList<>();
        
        // First pass: categorize all blocks
        for (SchematicData.BlockPlacement placement : schematic.blocks()) {
            BlockState state = schematic.getState(placement.paletteIndex());
            if (state == null || state.isAir()) continue;
            
            BlockPos relPos = placement.relativePos();
            BlockPos worldPos = worldOrigin.add(relPos);
            
            minX = Math.min(minX, relPos.getX());
            maxX = Math.max(maxX, relPos.getX());
            minY = Math.min(minY, relPos.getY());
            maxY = Math.max(maxY, relPos.getY());
            minZ = Math.min(minZ, relPos.getZ());
            maxZ = Math.max(maxZ, relPos.getZ());
            
            BuildCategory category = categorizeBlock(state, relPos, 
                    schematic.sizeX(), schematic.sizeY(), schematic.sizeZ());
            
            int priority = calculatePriority(relPos, category, 
                    schematic.sizeX(), schematic.sizeZ());
            
            BuildTarget target = new BuildTarget(worldPos, state, category, relPos.getY(), priority);
            targets.add(target);
            
            // Track special positions
            if (category == BuildCategory.DOOR) {
                doorPositions.add(worldPos);
            } else if (category == BuildCategory.CORNER) {
                corners.add(worldPos);
            } else if (category == BuildCategory.ROOF) {
                roof.add(worldPos);
            }
            
            // Track perimeter (edges of each layer)
            if (isPerimeterBlock(relPos, schematic.sizeX(), schematic.sizeZ())) {
                perimeter.add(worldPos);
            }
        }
        
        // Sort targets by construction order
        Collections.sort(targets);
        
        // Calculate center and dimensions
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int height = maxY - minY + 1;
        BlockPos center = worldOrigin.add(width / 2, 0, depth / 2);
        
        // Detect suggested door side based on schematic analysis
        Direction doorSide = detectDoorSide(doorPositions, worldOrigin, center, facing);
        
        return new ConstructionPlan(
                targets, worldOrigin, center, 
                worldOrigin.getY() + minY, worldOrigin.getY() + maxY,
                width, depth, height,
                doorSide, corners, perimeter, roof
        );
    }

    /**
     * Categorize a block based on its state and position.
     */
    private static BuildCategory categorizeBlock(BlockState state, BlockPos relPos,
                                                  int sizeX, int sizeY, int sizeZ) {
        String blockName = state.getBlock().getTranslationKey().toLowerCase();
        
        // Check for doors
        if (blockName.contains("door")) {
            return BuildCategory.DOOR;
        }
        
        // Check for torches, flowers, decorations
        if (blockName.contains("torch") || blockName.contains("flower") || 
            blockName.contains("lantern") || blockName.contains("carpet")) {
            return BuildCategory.DECORATION;
        }
        
        // Position-based categorization
        int y = relPos.getY();
        int x = relPos.getX();
        int z = relPos.getZ();
        
        // Floor level (y = 0 or lowest)
        if (y == 0) {
            // Check if corner
            if (isCorner(x, z, sizeX, sizeZ)) {
                return BuildCategory.CORNER;
            }
            return BuildCategory.FOUNDATION;
        }
        
        // Top level = roof
        if (y == sizeY - 1) {
            return BuildCategory.ROOF;
        }
        
        // Edge positions = wall or corner
        boolean onEdgeX = (x == 0 || x == sizeX - 1);
        boolean onEdgeZ = (z == 0 || z == sizeZ - 1);
        
        if (onEdgeX && onEdgeZ) {
            return BuildCategory.CORNER;
        }
        
        if (onEdgeX || onEdgeZ) {
            return BuildCategory.WALL;
        }
        
        return BuildCategory.INTERIOR;
    }

    /**
     * Calculate placement priority within a category.
     * Lower numbers = placed first.
     */
    private static int calculatePriority(BlockPos relPos, BuildCategory category, int sizeX, int sizeZ) {
        int x = relPos.getX();
        int z = relPos.getZ();
        
        switch (category) {
            case CORNER:
                // Corners are highest priority - return 0
                return 0;
            case WALL:
                // Walls placed after corners, prioritize by distance from corners
                return Math.min(
                        Math.min(x, sizeX - 1 - x),
                        Math.min(z, sizeZ - 1 - z)
                );
            case FOUNDATION:
                // Foundation: perimeter first, then fill inward
                if (x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1) {
                    return 0;
                }
                return 1;
            case ROOF:
                // Roof: edges first for support, then fill
                if (x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1) {
                    return 0;
                }
                return 1;
            case DOOR:
                // Doors placed last
                return 100;
            default:
                return 50;
        }
    }

    private static boolean isCorner(int x, int z, int sizeX, int sizeZ) {
        boolean xEdge = (x == 0 || x == sizeX - 1);
        boolean zEdge = (z == 0 || z == sizeZ - 1);
        return xEdge && zEdge;
    }

    private static boolean isPerimeterBlock(BlockPos relPos, int sizeX, int sizeZ) {
        int x = relPos.getX();
        int z = relPos.getZ();
        return x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1;
    }

    private static Direction detectDoorSide(Set<BlockPos> doorPositions, BlockPos origin, 
                                            BlockPos center, Direction defaultFacing) {
        if (doorPositions.isEmpty()) {
            return defaultFacing != null ? defaultFacing : Direction.NORTH;
        }
        
        // Find average door position relative to center
        double avgX = 0, avgZ = 0;
        for (BlockPos door : doorPositions) {
            avgX += door.getX() - center.getX();
            avgZ += door.getZ() - center.getZ();
        }
        avgX /= doorPositions.size();
        avgZ /= doorPositions.size();
        
        // Determine which side the door is on
        if (Math.abs(avgX) > Math.abs(avgZ)) {
            return avgX > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return avgZ > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * Generate a list of vantage points from which to build.
     * Similar to the hovel builder's station concept.
     */
    public static List<BlockPos> generateBuildStations(ConstructionPlan plan, int stationSpacing) {
        List<BlockPos> stations = new ArrayList<>();
        int standY = plan.minY; // Ground level
        
        // Add corners as primary stations
        for (BlockPos corner : plan.cornerPositions) {
            // Stand just outside the corner
            BlockPos station = corner.withY(standY);
            stations.add(station);
        }
        
        // Add center as a station for interior work
        stations.add(plan.center.withY(standY));
        
        // Add perimeter stations for large structures
        if (plan.width > 6 || plan.depth > 6) {
            for (int i = 0; i < plan.perimeterPositions.size(); i += stationSpacing) {
                BlockPos perimPos = plan.perimeterPositions.get(i);
                stations.add(perimPos.withY(standY).offset(
                        getOutwardDirection(perimPos, plan.center)));
            }
        }
        
        return stations;
    }

    /**
     * Adapter that converts a construction plan into shared placement targets.
     */
    public static List<PlacementTarget> toPlacementTargets(ConstructionPlan plan) {
        if (plan == null || plan.orderedTargets() == null || plan.orderedTargets().isEmpty()) {
            return List.of();
        }

        List<PlacementTarget> targets = new ArrayList<>(plan.orderedTargets().size());
        for (BuildTarget t : plan.orderedTargets()) {
            targets.add(new PlacementTarget(
                    t.worldPos(),
                    t.state(),
                    toTargetKind(t.category()),
                    t.priority(),
                    "layer:" + t.layerY() + ":" + t.category().name()
            ));
        }
        return targets;
    }

    private static PlacementTarget.TargetKind toTargetKind(BuildCategory category) {
        return switch (category) {
            case FOUNDATION -> PlacementTarget.TargetKind.FOUNDATION;
            case CORNER -> PlacementTarget.TargetKind.CORNER;
            case WALL -> PlacementTarget.TargetKind.WALL;
            case INTERIOR -> PlacementTarget.TargetKind.INTERIOR;
            case ROOF -> PlacementTarget.TargetKind.ROOF;
            case DOOR -> PlacementTarget.TargetKind.DOOR;
            case DECORATION -> PlacementTarget.TargetKind.DECORATION;
        };
    }

    private static Direction getOutwardDirection(BlockPos pos, BlockPos center) {
        int dx = pos.getX() - center.getX();
        int dz = pos.getZ() - center.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
