package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles perimeter and corner operations for construction.
 * Provides movement patterns around structures for efficient block placement.
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Corner priority - build corner pillars first for stability</li>
 *   <li>Perimeter walking - efficient route around structure edges</li>
 *   <li>Station-based building - move to stable positions, place blocks, move on</li>
 *   <li>Interior escape - getting unstuck from inside a structure</li>
 * </ul>
 */
public final class PerimeterService {

    private static final Logger LOGGER = LoggerFactory.getLogger("perimeter-service");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks

    private PerimeterService() {}

    /**
     * Represents a build station - a position from which to place blocks.
     */
    public record BuildStation(
            BlockPos position,
            StationType type,
            Set<BlockPos> reachableTargets
    ) {}

    /**
     * Types of build stations.
     */
    public enum StationType {
        CORNER,         // Corner position (priority for structural stability)
        EDGE_CENTER,    // Center of an edge
        INTERIOR,       // Inside the structure
        EXTERIOR        // Outside the structure perimeter
    }

    /**
     * Generate corner positions for a rectangular structure.
     *
     * @param center Center of the structure
     * @param radiusX Half-width in X direction
     * @param radiusZ Half-width in Z direction
     * @param standY Y level to stand at
     * @return List of corner positions (NW, NE, SW, SE)
     */
    public static List<BlockPos> getCornerPositions(BlockPos center, int radiusX, int radiusZ, int standY) {
        int cx = center.getX();
        int cz = center.getZ();
        
        return List.of(
                new BlockPos(cx - radiusX, standY, cz - radiusZ), // NW
                new BlockPos(cx + radiusX, standY, cz - radiusZ), // NE
                new BlockPos(cx - radiusX, standY, cz + radiusZ), // SW
                new BlockPos(cx + radiusX, standY, cz + radiusZ)  // SE
        );
    }

    /**
     * Generate corner positions for a square structure.
     */
    public static List<BlockPos> getCornerPositions(BlockPos center, int radius, int standY) {
        return getCornerPositions(center, radius, radius, standY);
    }

    /**
     * Generate perimeter positions (all edge blocks) for a rectangular structure.
     * Returns positions in a clockwise walk order.
     */
    public static List<BlockPos> getPerimeterPositions(BlockPos center, int radiusX, int radiusZ, int y) {
        List<BlockPos> perimeter = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();

        // North edge (west to east)
        for (int x = cx - radiusX; x <= cx + radiusX; x++) {
            perimeter.add(new BlockPos(x, y, cz - radiusZ));
        }
        // East edge (north+1 to south)
        for (int z = cz - radiusZ + 1; z <= cz + radiusZ; z++) {
            perimeter.add(new BlockPos(cx + radiusX, y, z));
        }
        // South edge (east-1 to west)
        for (int x = cx + radiusX - 1; x >= cx - radiusX; x--) {
            perimeter.add(new BlockPos(x, y, cz + radiusZ));
        }
        // West edge (south-1 to north+1)
        for (int z = cz + radiusZ - 1; z > cz - radiusZ; z--) {
            perimeter.add(new BlockPos(cx - radiusX, y, z));
        }

        return perimeter;
    }

    /**
     * Generate perimeter positions for a square structure.
     */
    public static List<BlockPos> getPerimeterPositions(BlockPos center, int radius, int y) {
        return getPerimeterPositions(center, radius, radius, y);
    }

    /**
     * Generate exterior perimeter positions (just outside the structure).
     * Useful for building from the outside.
     */
    public static List<BlockPos> getExteriorPerimeter(BlockPos center, int radius, int y) {
        return getPerimeterPositions(center, radius + 1, y);
    }

    /**
     * Generate interior positions (non-perimeter blocks).
     */
    public static List<BlockPos> getInteriorPositions(BlockPos center, int radiusX, int radiusZ, int y) {
        List<BlockPos> interior = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();

        for (int x = cx - radiusX + 1; x < cx + radiusX; x++) {
            for (int z = cz - radiusZ + 1; z < cz + radiusZ; z++) {
                interior.add(new BlockPos(x, y, z));
            }
        }

        return interior;
    }

    /**
     * Generate build stations around a structure.
     * Returns corner stations first (priority), then edge stations.
     *
     * @param center Structure center
     * @param radius Structure radius
     * @param standY Y level for standing
     * @param stationSpacing How many blocks between non-corner stations
     * @return Ordered list of build stations
     */
    public static List<BuildStation> generateBuildStations(BlockPos center, int radius, 
                                                           int standY, int stationSpacing) {
        List<BuildStation> stations = new ArrayList<>();
        
        // Corner stations (highest priority)
        for (BlockPos corner : getCornerPositions(center, radius + 1, standY)) {
            stations.add(new BuildStation(corner, StationType.CORNER, new HashSet<>()));
        }

        // Edge center stations (for larger structures)
        if (radius >= 3) {
            int cx = center.getX();
            int cz = center.getZ();
            int offset = radius + 1;
            
            stations.add(new BuildStation(new BlockPos(cx, standY, cz - offset), StationType.EDGE_CENTER, new HashSet<>())); // North
            stations.add(new BuildStation(new BlockPos(cx + offset, standY, cz), StationType.EDGE_CENTER, new HashSet<>())); // East
            stations.add(new BuildStation(new BlockPos(cx, standY, cz + offset), StationType.EDGE_CENTER, new HashSet<>())); // South
            stations.add(new BuildStation(new BlockPos(cx - offset, standY, cz), StationType.EDGE_CENTER, new HashSet<>())); // West
        }

        // Interior center station
        stations.add(new BuildStation(center.withY(standY), StationType.INTERIOR, new HashSet<>()));

        return stations;
    }

    /**
     * Check if a position is a corner of the structure.
     */
    public static boolean isCornerPosition(BlockPos pos, BlockPos center, int radius) {
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx == radius && dz == radius;
    }

    /**
     * Check if a position is on the perimeter (edge) of the structure.
     */
    public static boolean isPerimeterPosition(BlockPos pos, BlockPos center, int radius) {
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return (dx == radius || dz == radius) && dx <= radius && dz <= radius;
    }

    /**
     * Walk the exterior perimeter, executing a callback at each position.
     * Used for placing blocks from the outside.
     */
    public static void walkExteriorPerimeter(ServerWorld world,
                                              ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              BlockPos center,
                                              int radius,
                                              int standY,
                                              PerimeterCallback callback) {
        List<BlockPos> perimeter = getExteriorPerimeter(center, radius, standY);
        
        for (BlockPos pos : perimeter) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            
            if (!moveToPosition(source, bot, pos)) {
                // If we can't reach this position, try the next
                continue;
            }
            
            sleepQuiet(80L);
            callback.onPosition(pos);
        }
    }

    /**
     * Visit each build station and execute callback.
     */
    public static void visitBuildStations(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          List<BuildStation> stations,
                                          StationCallback callback) {
        for (BuildStation station : stations) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            
            if (!moveToPosition(source, bot, station.position())) {
                LOGGER.debug("Could not reach station at {}", station.position().toShortString());
                continue;
            }
            
            BotActions.stop(bot);
            sleepQuiet(100L);
            
            callback.onStation(station);
        }
    }

    /**
     * Escape from inside a structure to the exterior.
     * Finds the nearest door or opening and navigates through it.
     *
     * @param world The world
     * @param source Command source
     * @param bot The bot
     * @param structureCenter Center of the structure
     * @param radius Structure radius
     * @param doorSide Which side has the door (can be null to auto-detect)
     * @return true if escaped successfully
     */
    public static boolean escapeToExterior(ServerWorld world,
                                           ServerCommandSource source,
                                           ServerPlayerEntity bot,
                                           BlockPos structureCenter,
                                           int radius,
                                           Direction doorSide) {
        if (bot == null || world == null) {
            return false;
        }

        BlockPos botPos = bot.getBlockPos();
        int standY = structureCenter.getY() + 1;

        // If already outside, we're done
        if (!isInsideStructure(botPos, structureCenter, radius)) {
            return true;
        }

        // If door side is known, use it
        if (doorSide != null) {
            BlockPos doorExit = structureCenter.offset(doorSide, radius + 2).withY(standY);
            return moveToPosition(source, bot, doorExit);
        }

        // Otherwise, try each direction
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos exit = structureCenter.offset(dir, radius + 2).withY(standY);
            if (moveToPosition(source, bot, exit)) {
                return true;
            }
        }

        LOGGER.warn("Failed to escape from structure at {}", structureCenter.toShortString());
        return false;
    }

    /**
     * Check if a position is inside the structure bounds.
     */
    public static boolean isInsideStructure(BlockPos pos, BlockPos center, int radius) {
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx < radius && dz < radius;
    }

    /**
     * Find the nearest standable position to a target.
     */
    public static BlockPos findNearbyStandable(ServerWorld world, BlockPos target, int searchRadius) {
        if (isStandable(world, target)) {
            return target;
        }

        // Spiral outward
        for (int r = 1; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r || Math.abs(dz) == r) {
                        BlockPos candidate = target.add(dx, 0, dz);
                        if (isStandable(world, candidate)) {
                            return candidate;
                        }
                        // Try one block up/down
                        if (isStandable(world, candidate.up())) {
                            return candidate.up();
                        }
                        if (isStandable(world, candidate.down())) {
                            return candidate.down();
                        }
                    }
                }
            }
        }

        return null;
    }

    // ========== Callback Interfaces ==========

    @FunctionalInterface
    public interface PerimeterCallback {
        void onPosition(BlockPos standPosition);
    }

    @FunctionalInterface
    public interface StationCallback {
        void onStation(BuildStation station);
    }

    // ========== Helper Methods ==========

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockPos below = pos.down();
        BlockPos above = pos.up();
        
        return !world.getBlockState(below).isAir() && 
               world.getBlockState(pos).isAir() && 
               world.getBlockState(above).isAir();
    }

    private static boolean moveToPosition(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) return false;
        
        // Already there?
        if (bot.getBlockPos().equals(target)) {
            return true;
        }
        
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        
        if (plan.isEmpty()) {
            return false;
        }

        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true);
        return result.success();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
