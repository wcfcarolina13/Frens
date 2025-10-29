package net.shasankp000.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * Minimal action executor that replaces the old Carpet "player" commands.
 * Adjusts the server-side player directly so that training steps can take effect
 * even without the Carpet mod.
 */
public final class BotActions {

    private static final double STEP_DISTANCE = 0.75;
    private static final float TURN_DEGREES = 20.0f;

    private BotActions() {}

    public static void moveForward(ServerPlayerEntity bot) {
        moveRelative(bot, STEP_DISTANCE);
    }

    public static void moveBackward(ServerPlayerEntity bot) {
        moveRelative(bot, -STEP_DISTANCE);
    }

    public static void stop(ServerPlayerEntity bot) {
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityDirty = true;
    }

    public static void turnLeft(ServerPlayerEntity bot) {
        rotate(bot, -TURN_DEGREES);
    }

    public static void turnRight(ServerPlayerEntity bot) {
        rotate(bot, TURN_DEGREES);
    }

    public static void jump(ServerPlayerEntity bot) {
        bot.jump();
    }

    public static void sneak(ServerPlayerEntity bot, boolean value) {
        bot.setSneaking(value);
    }

    public static void sprint(ServerPlayerEntity bot, boolean value) {
        bot.setSprinting(value);
    }

    public static void jumpForward(ServerPlayerEntity bot) {
        bot.jump();
        moveRelative(bot, STEP_DISTANCE * 0.6);
    }

    public static void attackNearest(ServerPlayerEntity bot, List<Entity> nearbyEntities) {
        Entity target = nearbyEntities.stream()
                .filter(entity -> entity instanceof HostileEntity)
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);

        if (target != null) {
            bot.attack(target);
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    public static void useSelectedItem(ServerPlayerEntity bot) {
        ItemStack stack = bot.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }

        ActionResult result = stack.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    public static void selectHotbarSlot(ServerPlayerEntity bot, int index) {
        bot.getInventory().setSelectedSlot(MathHelper.clamp(index, 0, 8));
    }

    public static boolean breakBlockAhead(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos targetPos = getRelativeBlockPos(bot, 1, 0);
        if (!world.getBlockState(targetPos).isAir()) {
            boolean success = world.breakBlock(targetPos, true, bot);
            if (success) {
                bot.swingHand(Hand.MAIN_HAND, true);
                return true;
            }
        }

        // Try the block above-front if the direct block was air (stair carving)
        BlockPos upperPos = getRelativeBlockPos(bot, 1, 1);
        if (!world.getBlockState(upperPos).isAir()) {
            boolean success = world.breakBlock(upperPos, true, bot);
            if (success) {
                bot.swingHand(Hand.MAIN_HAND, true);
                return true;
            }
        }

        return false;
    }

    public static boolean placeSupportBlock(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        int slot = findPlaceableHotbarSlot(bot);
        if (slot == -1) {
            return false;
        }

        ItemStack stack = bot.getInventory().getStack(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        selectHotbarSlot(bot, slot);

        BlockPos below = bot.getBlockPos().down();
        BlockPos target = world.getBlockState(below).isAir() ? below : getRelativeBlockPos(bot, 0, -1);
        if (!world.getBlockState(target).isAir()) {
            // Try front-lower spot for stair stepping
            target = getRelativeBlockPos(bot, 1, -1);
        }

        if (!world.getBlockState(target).isAir()) {
            return false;
        }

        BlockState stateToPlace = blockItem.getBlock().getDefaultState();
        if (!stateToPlace.canPlaceAt(world, target)) {
            return false;
        }

        boolean placed = world.setBlockState(target, stateToPlace);
        if (placed) {
            stack.decrement(1);
            if (stack.isEmpty()) {
                bot.getInventory().setStack(slot, ItemStack.EMPTY);
            }
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }

        return false;
    }

    public static void escapeStairs(ServerPlayerEntity bot) {
        boolean placed = placeSupportBlock(bot);
        if (!placed) {
            breakBlockAhead(bot);
        }
        jumpForward(bot);
    }

    private static void moveRelative(ServerPlayerEntity bot, double distance) {
        float yaw = bot.getYaw();
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad) * distance;
        double dz = Math.cos(yawRad) * distance;

        bot.teleport(bot.getX() + dx, bot.getY(), bot.getZ() + dz, true);
    }

    private static BlockPos getRelativeBlockPos(ServerPlayerEntity bot, int forwardOffset, int verticalOffset) {
        Direction facing = getFacingDirection(bot);
        BlockPos basePos = bot.getBlockPos().add(0, verticalOffset, 0);
        return basePos.offset(facing, forwardOffset);
    }

    private static Direction getFacingDirection(ServerPlayerEntity bot) {
        int index = MathHelper.floor((bot.getYaw() * 4.0F / 360.0F) + 0.5D) & 3;
        return switch (index) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static int findPlaceableHotbarSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    private static void rotate(ServerPlayerEntity bot, float deltaYaw) {
        float newYaw = bot.getYaw() + deltaYaw;
        bot.setYaw(newYaw);
        bot.setHeadYaw(newYaw);
        bot.setBodyYaw(newYaw);
    }
}
