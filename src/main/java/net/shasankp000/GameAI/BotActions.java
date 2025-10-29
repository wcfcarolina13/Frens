package net.shasankp000.GameAI;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;
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

    private static void moveRelative(ServerPlayerEntity bot, double distance) {
        float yaw = bot.getYaw();
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad) * distance;
        double dz = Math.cos(yawRad) * distance;

        bot.teleport(bot.getX() + dx, bot.getY(), bot.getZ() + dz, true);
    }

    private static void rotate(ServerPlayerEntity bot, float deltaYaw) {
        float newYaw = bot.getYaw() + deltaYaw;
        bot.setYaw(newYaw);
        bot.setHeadYaw(newYaw);
        bot.setBodyYaw(newYaw);
    }
}
