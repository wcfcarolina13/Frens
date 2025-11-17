package net.shasankp000.GameAI.skills.support;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Handles torch placement for bots during mining operations.
 * Places torches on walls when light level drops below threshold.
 */
public final class TorchPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("torch-placer");
    private static final int LIGHT_THRESHOLD = 7;
    private static final int TORCH_SPACING = 8; // Place torches every 8 blocks

    private TorchPlacer() {
    }

    /**
     * Checks if a torch should be placed at the current position.
     * @param bot The bot
     * @return true if light level is below threshold and torch placement would help
     */
    public static boolean shouldPlaceTorch(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos pos = bot.getBlockPos();
        int lightLevel = world.getLightLevel(LightType.BLOCK, pos);
        return lightLevel < LIGHT_THRESHOLD;
    }

    /**
     * Attempts to find and place a torch on a nearby wall.
     * @param bot The bot
     * @param preferredDirection The direction the bot is facing (torch placed on perpendicular walls)
     * @return PlacementResult indicating success, no torches, or failure
     */
    public static PlacementResult placeTorch(ServerPlayerEntity bot, Direction preferredDirection) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return PlacementResult.FAILED;
        }

        // Find torch in inventory
        PlayerInventory inv = bot.getInventory();
        int torchSlot = findTorchSlot(inv);
        if (torchSlot == -1) {
            LOGGER.info("Bot {} has no torches in inventory", bot.getName().getString());
            return PlacementResult.NO_TORCHES;
        }

        // Find suitable wall position
        Optional<BlockPos> wallPos = findWallPosition(world, bot.getBlockPos(), preferredDirection);
        if (wallPos.isEmpty()) {
            LOGGER.debug("No suitable wall found for torch placement");
            return PlacementResult.FAILED;
        }

        BlockPos targetPos = wallPos.get();
        
        // If torch is in main inventory (slot 9-35), swap it to hotbar first
        int hotbarSlot = -1;
        if (torchSlot >= 9) {
            // Find an empty hotbar slot or use current slot
            hotbarSlot = inv.getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isEmpty()) {
                    hotbarSlot = i;
                    break;
                }
            }
            
            // Swap torch from main inventory to hotbar slot
            ItemStack torchStack = inv.getStack(torchSlot);
            ItemStack hotbarStack = inv.getStack(hotbarSlot);
            inv.setStack(torchSlot, hotbarStack);
            inv.setStack(hotbarSlot, torchStack);
            
            LOGGER.debug("Swapped torch from slot {} to hotbar slot {}", torchSlot, hotbarSlot);
        } else {
            // Torch is already in hotbar
            hotbarSlot = torchSlot;
        }
        
        // Now select the hotbar slot with the torch
        int previousSlot = inv.getSelectedSlot();
        boolean needSwap = previousSlot != hotbarSlot;
        if (needSwap) {
            inv.setSelectedSlot(hotbarSlot);
        }

        try {
            // Place the torch
            boolean placed = placeTorchAt(bot, world, targetPos);
            if (placed) {
                LOGGER.info("Placed torch at {} for bot {}", targetPos, bot.getName().getString());
                return PlacementResult.SUCCESS;
            }
            return PlacementResult.FAILED;
        } finally {
            // Restore previous hotbar slot
            if (needSwap) {
                inv.setSelectedSlot(previousSlot);
            }
        }
    }

    private static int findTorchSlot(PlayerInventory inv) {
        // Search entire inventory (hotbar + main)
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.TORCH)) {
                return i;
            }
        }
        return -1;
    }

    private static Optional<BlockPos> findWallPosition(ServerWorld world, BlockPos center, Direction facing) {
        // Try perpendicular walls (left and right of facing direction)
        Direction[] walls = getPerpendicularDirections(facing);
        
        for (Direction wallDir : walls) {
            // Check if there's a solid wall directly adjacent to the bot
            BlockPos wallBlock = center.offset(wallDir);
            BlockState wallState = world.getBlockState(wallBlock);
            
            // If this is a solid wall, place torch at center (bot's position) attached to the wall
            if (!wallState.isAir() && wallState.isSolidBlock(world, wallBlock)) {
                LOGGER.debug("Found wall at {} for torch placement at {}", wallBlock, center);
                return Optional.of(center); // Place torch at bot's position, attached to wall
            }
        }
        
        // If no adjacent wall, try one block UP (for 3-tall tunnels, place on upper wall)
        BlockPos up = center.up();
        for (Direction wallDir : walls) {
            BlockPos wallBlock = up.offset(wallDir);
            BlockState wallState = world.getBlockState(wallBlock);
            
            if (!wallState.isAir() && wallState.isSolidBlock(world, wallBlock)) {
                // Check if position 'up' is air (where we'd place torch)
                if (world.getBlockState(up).isAir()) {
                    LOGGER.debug("Found upper wall at {} for torch placement at {}", wallBlock, up);
                    return Optional.of(up);
                }
            }
        }
        
        return Optional.empty();
    }

    private static Direction[] getPerpendicularDirections(Direction facing) {
        return switch (facing) {
            case NORTH -> new Direction[]{Direction.EAST, Direction.WEST};
            case SOUTH -> new Direction[]{Direction.WEST, Direction.EAST};
            case EAST -> new Direction[]{Direction.NORTH, Direction.SOUTH};
            case WEST -> new Direction[]{Direction.SOUTH, Direction.NORTH};
            default -> new Direction[]{Direction.NORTH, Direction.SOUTH};
        };
    }

    private static boolean isSuitableForTorch(ServerWorld world, BlockPos pos) {
        // This method is no longer used - keeping for compatibility
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.isSolidBlock(world, pos)) {
            return false;
        }
        BlockPos airSpace = pos.offset(Direction.UP);
        return world.getBlockState(airSpace).isAir();
    }

    private static boolean placeTorchAt(ServerPlayerEntity bot, ServerWorld world, BlockPos torchPos) {
        // torchPos is the AIR position where we want to place the torch
        // Find which adjacent block is a solid wall to attach to
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP || dir == Direction.DOWN) continue; // Only attach to horizontal walls
            
            BlockPos wallBlock = torchPos.offset(dir);
            BlockState wallState = world.getBlockState(wallBlock);
            
            // Check if this is a solid wall we can attach to
            if (!wallState.isAir() && wallState.isSolidBlock(world, wallBlock)) {
                ItemStack torchStack = bot.getStackInHand(Hand.MAIN_HAND);
                if (!torchStack.isOf(Items.TORCH)) {
                    return false;
                }
                
                // Place the torch at torchPos, attached to the wall
                try {
                    // For wall torches, we need to set the facing direction
                    // The torch faces AWAY from the wall (opposite of wall direction)
                    world.setBlockState(torchPos, Blocks.WALL_TORCH.getDefaultState()
                            .with(net.minecraft.block.WallTorchBlock.FACING, dir.getOpposite()));
                    torchStack.decrement(1);
                    bot.swingHand(Hand.MAIN_HAND, true);
                    LOGGER.debug("Placed wall torch at {} facing {}", torchPos, dir.getOpposite());
                    return true;
                } catch (Exception e) {
                    LOGGER.warn("Failed to place torch at {}", torchPos, e);
                    return false;
                }
            }
        }
        
        // If no horizontal wall found, try placing a standing torch
        BlockPos below = torchPos.down();
        if (!world.getBlockState(below).isAir() && world.getBlockState(below).isSolidBlock(world, below)) {
            ItemStack torchStack = bot.getStackInHand(Hand.MAIN_HAND);
            if (!torchStack.isOf(Items.TORCH)) {
                return false;
            }
            
            try {
                world.setBlockState(torchPos, Blocks.TORCH.getDefaultState());
                torchStack.decrement(1);
                bot.swingHand(Hand.MAIN_HAND, true);
                LOGGER.debug("Placed standing torch at {}", torchPos);
                return true;
            } catch (Exception e) {
                LOGGER.warn("Failed to place standing torch at {}", torchPos, e);
                return false;
            }
        }
        
        return false;
    }

    public enum PlacementResult {
        SUCCESS,
        NO_TORCHES,
        FAILED
    }
}
