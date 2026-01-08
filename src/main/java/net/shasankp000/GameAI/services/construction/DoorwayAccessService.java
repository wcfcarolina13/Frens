package net.shasankp000.GameAI.services.construction;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service for ensuring doorways are accessible.
 * Handles clearing paths to doorways, removing obstructions, and preparing
 * approach areas for proper door placement.
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Clear the 2-block high doorway opening</li>
 *   <li>Clear the approach path outside the door (1-2 blocks)</li>
 *   <li>Ensure ground level is suitable for walking through</li>
 *   <li>Detect and report doorway obstructions</li>
 * </ul>
 */
public final class DoorwayAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger("doorway-access-service");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks

    private DoorwayAccessService() {}

    /**
     * Represents a doorway with its position and orientation.
     */
    public record Doorway(
            BlockPos bottomPos,       // Bottom half of door opening
            BlockPos topPos,          // Top half of door opening (bottomPos.up())
            Direction facingOutward,  // Direction the door opens (exterior side)
            BlockPos outsideApproach, // Position outside the door
            BlockPos insidePos        // Position inside the door
    ) {
        public static Doorway create(BlockPos bottomPos, Direction facingOutward) {
            return new Doorway(
                    bottomPos,
                    bottomPos.up(),
                    facingOutward,
                    bottomPos.offset(facingOutward),
                    bottomPos.offset(facingOutward.getOpposite())
            );
        }
    }

    /**
     * Result of doorway access operations.
     */
    public record AccessResult(
            boolean success,
            int blocksCleared,
            String message
    ) {
        public static AccessResult success(int cleared) {
            return new AccessResult(true, cleared, 
                    cleared > 0 ? "Cleared " + cleared + " obstructions" : "Doorway already clear");
        }
        
        public static AccessResult failure(String reason) {
            return new AccessResult(false, 0, reason);
        }
    }

    /**
     * Analyze a doorway position and return its full structure.
     * 
     * @param bottomPos The bottom block of the doorway opening
     * @param facingOutward Direction toward the exterior (where people approach from)
     * @return Doorway record with all positions
     */
    public static Doorway analyzeDoorway(BlockPos bottomPos, Direction facingOutward) {
        return Doorway.create(bottomPos, facingOutward);
    }

    /**
     * Detect doorways in a schematic by looking for 2-block-high air gaps in walls.
     * 
     * @param world The world
     * @param origin Schematic origin
     * @param sizeX Schematic X size
     * @param sizeZ Schematic Z size
     * @param floorY Y level of the floor (unused, door is 1 block above origin)
     * @return List of detected doorways
     */
    public static List<Doorway> detectDoorways(ServerWorld world, BlockPos origin, 
                                                int sizeX, int sizeZ, int floorY) {
        List<Doorway> doorways = new ArrayList<>();
        // Door opening is 1 block above the schematic origin (floor level)
        // Use relative offset, not absolute Y
        int doorYOffset = 1;
        
        // Check north edge (Z = 0)
        for (int x = 1; x < sizeX - 1; x++) {
            BlockPos pos = origin.add(x, doorYOffset, 0);
            if (isDoorwayOpening(world, pos)) {
                doorways.add(Doorway.create(pos, Direction.NORTH));
            }
        }
        
        // Check south edge (Z = sizeZ - 1)
        for (int x = 1; x < sizeX - 1; x++) {
            BlockPos pos = origin.add(x, doorYOffset, sizeZ - 1);
            if (isDoorwayOpening(world, pos)) {
                doorways.add(Doorway.create(pos, Direction.SOUTH));
            }
        }
        
        // Check west edge (X = 0)
        for (int z = 1; z < sizeZ - 1; z++) {
            BlockPos pos = origin.add(0, doorYOffset, z);
            if (isDoorwayOpening(world, pos)) {
                doorways.add(Doorway.create(pos, Direction.WEST));
            }
        }
        
        // Check east edge (X = sizeX - 1)
        for (int z = 1; z < sizeZ - 1; z++) {
            BlockPos pos = origin.add(sizeX - 1, doorYOffset, z);
            if (isDoorwayOpening(world, pos)) {
                doorways.add(Doorway.create(pos, Direction.EAST));
            }
        }
        
        LOGGER.debug("Detected {} doorways at origin {}", doorways.size(), origin.toShortString());
        return doorways;
    }

    /**
     * Check if a position is a doorway opening (2 blocks high air/door).
     */
    private static boolean isDoorwayOpening(ServerWorld world, BlockPos pos) {
        BlockState bottom = world.getBlockState(pos);
        BlockState top = world.getBlockState(pos.up());
        
        boolean bottomClear = bottom.isAir() || bottom.getBlock() instanceof DoorBlock;
        boolean topClear = top.isAir() || top.getBlock() instanceof DoorBlock;
        
        return bottomClear && topClear;
    }

    /**
     * Clear the doorway opening and approach path.
     * 
     * @param world The world
     * @param source Command source for messaging
     * @param bot The bot doing the clearing
     * @param doorway The doorway to clear
     * @return Result of the clearing operation
     */
    public static AccessResult clearDoorwayAccess(ServerWorld world,
                                                   ServerCommandSource source,
                                                   ServerPlayerEntity bot,
                                                   Doorway doorway) {
        if (world == null || bot == null || doorway == null) {
            return AccessResult.failure("Invalid parameters");
        }

        int cleared = 0;
        List<BlockPos> toClear = new ArrayList<>();

        // Positions to clear: doorway itself (2 blocks), approach path (2 blocks out), ground level
        // 1. Doorway opening
        toClear.add(doorway.bottomPos());
        toClear.add(doorway.topPos());

        // 2. Approach path outside (2 blocks outward, 2 high)
        BlockPos approach1 = doorway.outsideApproach();
        BlockPos approach2 = approach1.offset(doorway.facingOutward());
        toClear.add(approach1);
        toClear.add(approach1.up());
        toClear.add(approach2);
        toClear.add(approach2.up());

        // 3. Ground level at approach (make sure it's walkable)
        BlockPos groundApproach1 = approach1.down();
        BlockPos groundApproach2 = approach2.down();
        
        // Clear obstructions
        for (BlockPos pos : toClear) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return AccessResult.failure("Aborted");
            }

            BlockState state = world.getBlockState(pos);
            if (shouldClearBlock(state)) {
                boolean success = clearBlock(world, source, bot, pos);
                if (success) {
                    cleared++;
                }
            }
        }

        // Ensure ground is solid at approach positions
        for (BlockPos groundPos : List.of(groundApproach1, groundApproach2)) {
            BlockState groundState = world.getBlockState(groundPos);
            if (groundState.isAir()) {
                // Need to place a block here for walking
                LOGGER.debug("Ground missing at approach {}", groundPos.toShortString());
                // Could optionally place a block here via BotActions
            }
        }

        LOGGER.info("Cleared {} obstructions from doorway at {}", cleared, doorway.bottomPos().toShortString());
        return AccessResult.success(cleared);
    }

    /**
     * Clear all detected doorways in a structure.
     */
    public static AccessResult clearAllDoorways(ServerWorld world,
                                                 ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 BlockPos origin,
                                                 int sizeX, int sizeZ, int floorY) {
        List<Doorway> doorways = detectDoorways(world, origin, sizeX, sizeZ, floorY);
        
        if (doorways.isEmpty()) {
            return AccessResult.success(0);
        }

        int totalCleared = 0;
        for (Doorway doorway : doorways) {
            AccessResult result = clearDoorwayAccess(world, source, bot, doorway);
            if (result.success()) {
                totalCleared += result.blocksCleared();
            }
        }

        return AccessResult.success(totalCleared);
    }

    /**
     * Determine if a block should be cleared from a doorway/approach path.
     */
    private static boolean shouldClearBlock(BlockState state) {
        if (state.isAir()) return false;
        if (state.getBlock() instanceof DoorBlock) return false; // Don't clear doors
        
        Block block = state.getBlock();
        
        // Always clear these
        if (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || 
            block == Blocks.SAND || block == Blocks.GRAVEL ||
            block == Blocks.SNOW || block == Blocks.SNOW_BLOCK) {
            return true;
        }
        
        // Clear vegetation
        if (state.isReplaceable()) {
            return true;
        }
        
        // Clear common building blocks if they're blocking the path
        if (block == Blocks.COBBLESTONE || block == Blocks.STONE ||
            block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS ||
            block == Blocks.BIRCH_PLANKS || block == Blocks.JUNGLE_PLANKS ||
            block == Blocks.ACACIA_PLANKS || block == Blocks.DARK_OAK_PLANKS) {
            return true;
        }
        
        return false;
    }

    /**
     * Clear/break a single block.
     */
    private static boolean clearBlock(ServerWorld world, 
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot, 
                                       BlockPos pos) {
        // Move closer if needed
        if (!isWithinReach(bot, pos)) {
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, pos, MovementService.MovementOptions.skillLoot());
            if (plan.isPresent()) {
                MovementService.execute(source, bot, plan.get(), false, true);
            }
        }

        // Face and break
        LookController.faceBlock(bot, pos);
        sleepQuiet(100L);

        // Use world.breakBlock for server-side breaking
        BlockState state = world.getBlockState(pos);
        if (!state.isAir()) {
            boolean broke = world.breakBlock(pos, true, bot);
            if (broke) {
                LOGGER.debug("Cleared block at {}", pos.toShortString());
                sleepQuiet(80L);
                return true;
            }
        }
        
        return false;
    }

    /**
     * Check if a doorway has clear exterior access.
     */
    public static boolean hasExteriorAccess(ServerWorld world, Doorway doorway) {
        if (world == null || doorway == null) return false;

        // Check approach positions
        BlockPos approach = doorway.outsideApproach();
        BlockState approachState = world.getBlockState(approach);
        BlockState approachAbove = world.getBlockState(approach.up());
        BlockState groundBelow = world.getBlockState(approach.down());

        // Need: solid ground, clear standing space (2 high)
        return !groundBelow.isAir() && approachState.isAir() && approachAbove.isAir();
    }

    /**
     * Check if a doorway has clear interior access.
     */
    public static boolean hasInteriorAccess(ServerWorld world, Doorway doorway) {
        if (world == null || doorway == null) return false;

        BlockPos inside = doorway.insidePos();
        BlockState insideState = world.getBlockState(inside);
        BlockState insideAbove = world.getBlockState(inside.up());
        BlockState groundBelow = world.getBlockState(inside.down());

        return !groundBelow.isAir() && insideState.isAir() && insideAbove.isAir();
    }

    /**
     * Prepare a doorway for door installation.
     * Ensures the doorway opening is exactly 2 blocks high and has proper support.
     * 
     * @param world The world
     * @param source Command source
     * @param bot The bot
     * @param doorway The doorway to prepare
     * @return True if doorway is ready for door placement
     */
    public static boolean prepareDoorwayForDoor(ServerWorld world,
                                                 ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 Doorway doorway) {
        // First clear any obstructions
        AccessResult clearResult = clearDoorwayAccess(world, source, bot, doorway);
        
        // Check doorway dimensions
        BlockState bottomState = world.getBlockState(doorway.bottomPos());
        BlockState topState = world.getBlockState(doorway.topPos());
        BlockState groundState = world.getBlockState(doorway.bottomPos().down());

        // Bottom and top must be air (or already a door)
        boolean openingClear = (bottomState.isAir() || bottomState.getBlock() instanceof DoorBlock)
                            && (topState.isAir() || topState.getBlock() instanceof DoorBlock);

        // Must have solid ground below door
        boolean hasSupport = !groundState.isAir();

        if (!openingClear) {
            LOGGER.warn("Doorway opening not clear at {}", doorway.bottomPos().toShortString());
            return false;
        }

        if (!hasSupport) {
            LOGGER.warn("No support block below doorway at {}", doorway.bottomPos().toShortString());
            // Could try to place a support block
            return false;
        }

        LOGGER.info("Doorway at {} prepared for door installation", doorway.bottomPos().toShortString());
        return true;
    }

    // ========== Helper Methods ==========

    private static boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        double distSq = bot.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return distSq <= REACH_DISTANCE_SQ;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
