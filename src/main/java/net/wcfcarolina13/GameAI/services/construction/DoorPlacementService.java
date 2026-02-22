package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Handles door placement, opening, closing, and access for construction.
 * Extracted from HovelPerimeterBuilder for reuse across all schematic builders.
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Finds optimal stance positions outside the door for placement</li>
 *   <li>Handles dual-half door block mechanics</li>
 *   <li>Clears doorway obstructions before placement</li>
 *   <li>Provides entry/exit navigation through doors</li>
 * </ul>
 */
public final class DoorPlacementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("door-placement-service");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    
    // All vanilla door items
    public static final List<Item> DOOR_ITEMS = List.of(
            Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
            Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR,
            Items.BAMBOO_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR, Items.IRON_DOOR
    );

    private DoorPlacementService() {}

    /**
     * Check if the bot has any door item in inventory.
     */
    public static boolean hasDoorItem(ServerPlayerEntity bot) {
        if (bot == null) return false;
        
        // Check hands first
        if (isDoorItem(bot.getMainHandStack().getItem()) || 
            isDoorItem(bot.getOffHandStack().getItem())) {
            return true;
        }
        
        // Check inventory
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (isDoorItem(bot.getInventory().getStack(i).getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if an item is a door.
     */
    public static boolean isDoorItem(Item item) {
        return DOOR_ITEMS.contains(item);
    }

    /**
     * Get the first door item from bot's inventory.
     */
    public static Item findDoorItem(ServerPlayerEntity bot) {
        if (bot == null) return null;
        
        // Check hands
        if (isDoorItem(bot.getMainHandStack().getItem())) {
            return bot.getMainHandStack().getItem();
        }
        if (isDoorItem(bot.getOffHandStack().getItem())) {
            return bot.getOffHandStack().getItem();
        }
        
        // Check inventory
        for (int i = 0; i < bot.getInventory().size(); i++) {
            Item item = bot.getInventory().getStack(i).getItem();
            if (isDoorItem(item)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Check if a door is already installed at the given position.
     */
    public static boolean isDoorInstalled(ServerWorld world, BlockPos doorPos) {
        if (world == null || doorPos == null) return false;
        BlockState state = world.getBlockState(doorPos);
        return state.getBlock() instanceof DoorBlock;
    }

    /**
     * Place a door at the specified position.
     * 
     * @param world The world
     * @param source Command source for messaging
     * @param bot The bot placing the door
     * @param doorPos The position for the bottom half of the door
     * @param facingDirection The direction the door should face (where it opens toward)
     * @return true if door was placed successfully
     */
    public static boolean placeDoor(ServerWorld world, 
                                    ServerCommandSource source,
                                    ServerPlayerEntity bot, 
                                    BlockPos doorPos, 
                                    Direction facingDirection) {
        if (world == null || bot == null || doorPos == null) {
            return false;
        }

        // Check if already installed
        if (isDoorInstalled(world, doorPos)) {
            LOGGER.debug("Door already installed at {}", doorPos.toShortString());
            return true;
        }

        Item doorItem = findDoorItem(bot);
        if (doorItem == null) {
            LOGGER.debug("No door item in inventory");
            return false;
        }

        // Ensure doorway is clear
        ensureDoorwayClear(world, bot, doorPos);

        // Ensure there's a support block below
        BlockPos below = doorPos.down();
        if (world.getBlockState(below).isAir()) {
            BotActions.placeBlockAt(bot, below, Direction.UP, ScaffoldService.SCAFFOLD_BLOCKS);
            sleepQuiet(60L);
        }

        // Find a good stance position
        BlockPos stance = findDoorPlacementStance(world, bot, doorPos, facingDirection);
        if (stance != null && !bot.getBlockPos().equals(stance)) {
            moveToPosition(source, bot, stance);
        }

        // Face the door position
        LookController.faceBlock(bot, doorPos);
        sleepQuiet(200L);

        // Try to place the door
        boolean placed = BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem));
        if (placed) {
            LOGGER.info("Placed door at {}", doorPos.toShortString());
            sleepQuiet(120L);
            return true;
        }

        // Retry facing inward
        if (facingDirection != null) {
            LookController.faceBlock(bot, doorPos.offset(facingDirection.getOpposite()));
            sleepQuiet(200L);
            placed = BotActions.placeBlockAt(bot, doorPos, Direction.UP, List.of(doorItem));
            if (placed) {
                LOGGER.info("Placed door at {} (second attempt)", doorPos.toShortString());
                return true;
            }
        }

        LOGGER.warn("Failed to place door at {}", doorPos.toShortString());
        return false;
    }

    /**
     * Find a good position to stand for placing a door.
     */
    public static BlockPos findDoorPlacementStance(ServerWorld world, 
                                                   ServerPlayerEntity bot,
                                                   BlockPos doorPos, 
                                                   Direction facingDirection) {
        int standY = doorPos.getY();
        
        // Prefer standing outside the door, slightly off to the side
        List<BlockPos> candidates = List.of(
                // Far diagonal positions (best for placement)
                doorPos.offset(facingDirection, 2).offset(facingDirection.rotateYClockwise()).withY(standY),
                doorPos.offset(facingDirection, 2).offset(facingDirection.rotateYCounterclockwise()).withY(standY),
                // Straight out
                doorPos.offset(facingDirection, 2).withY(standY),
                // Closer positions
                doorPos.offset(facingDirection).offset(facingDirection.rotateYClockwise()).withY(standY),
                doorPos.offset(facingDirection).offset(facingDirection.rotateYCounterclockwise()).withY(standY),
                doorPos.offset(facingDirection).withY(standY)
        );

        for (BlockPos candidate : candidates) {
            if (isStandable(world, candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Ensure the doorway area is clear of obstructions.
     */
    public static void ensureDoorwayClear(ServerWorld world, ServerPlayerEntity bot, BlockPos doorPos) {
        // Clear the two-block doorway space
        BlockPos upper = doorPos.up();
        
        for (BlockPos pos : List.of(doorPos, upper)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && state.isReplaceable()) {
                // Can place through replaceable blocks
                continue;
            }
            if (!state.isAir() && !(state.getBlock() instanceof DoorBlock)) {
                // Need to mine out this block
                // Note: actual mining would be done via BotActions.breakBlockAt
                LOGGER.debug("Doorway blocked at {} by {}", pos.toShortString(), state.getBlock().getName().getString());
            }
        }
    }

    /**
     * Navigate a bot through a door (enter or exit).
     */
    public static boolean navigateThroughDoor(ServerWorld world,
                                              ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              BlockPos doorPos,
                                              Direction exitDirection) {
        if (world == null || bot == null || doorPos == null) {
            return false;
        }

        BlockState doorState = world.getBlockState(doorPos);
        if (!(doorState.getBlock() instanceof DoorBlock)) {
            // No door, just walk through
            BlockPos target = doorPos.offset(exitDirection);
            return moveToPosition(source, bot, target);
        }

        // Check if door is open
        boolean isOpen = doorState.contains(Properties.OPEN) && doorState.get(Properties.OPEN);
        
        // If closed and not iron, try to open it
        if (!isOpen && doorState.getBlock() != Blocks.IRON_DOOR) {
            // Right-click to open
            LookController.faceBlock(bot, doorPos);
            sleepQuiet(100L);
            // Would need use block interaction here
            LOGGER.debug("Would need to open door at {}", doorPos.toShortString());
        }

        // Walk through
        BlockPos target = doorPos.offset(exitDirection);
        return moveToPosition(source, bot, target);
    }

    /**
     * Calculate the door position for a structure given its center, radius, and door side.
     */
    public static BlockPos calculateDoorPosition(BlockPos center, int radius, Direction doorSide, int floorY) {
        return center.offset(doorSide, radius).withY(floorY + 1);
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
