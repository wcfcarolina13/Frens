package net.shasankp000.Entity;


import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.shasankp000.ChatUtils.ChatUtils;

public class RayCasting {

    private static String checkOutput = "";

    public static String detect(ServerPlayerEntity bot) {
        detectBlocks(bot);
        return checkOutput;
    }

    private static void detectBlocks(ServerPlayerEntity bot) {

        Vec3d botPosition = bot.getEntityPos();
        Direction getDirection = bot.getHorizontalFacing();
        Vec3d botDirection = Vec3d.of(getDirection.getVector());
        double rayLength = 15.0;
        Vec3d rayEnd = botPosition.add(botDirection.multiply(rayLength));

        RaycastContext raycastContext = new RaycastContext(
                botPosition,
                rayEnd,
                RaycastContext.ShapeType.COLLIDER, // Use COLLIDER for block and entity detection
                RaycastContext.FluidHandling.ANY, // Consider all fluids
                bot
        );

        BlockHitResult hitResult = bot.getEntityWorld().raycast(raycastContext);


        if (hitResult.getType() == HitResult.Type.BLOCK) {
            System.out.println("Block detected at: " + hitResult.getBlockPos());
            checkOutput = "Block detected in front at " + hitResult.getBlockPos().getX() + ", " + hitResult.getBlockPos().getY() + ", " + hitResult.getBlockPos().getZ();

            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), "Block detected in front at " + hitResult.getBlockPos().getX() + ", " + hitResult.getBlockPos().getY() + ", " + hitResult.getBlockPos().getZ());
            
        } else if (hitResult.getType() == HitResult.Type.MISS) {
            System.out.println("Nothing detected in front by raycast");

            checkOutput = "No block detected in front";

            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), "No block detected in front");
        }

    }

    public static boolean hasLineOfSight(ServerPlayerEntity source, Entity target) {
        Vec3d sourceEyePos = source.getEyePos();
        Vec3d targetEyePos = target.getEyePos();

        RaycastContext raycastContext = new RaycastContext(
                sourceEyePos,
                targetEyePos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                source
        );

        BlockHitResult hitResult = source.getEntityWorld().raycast(raycastContext);

        // If the raycast misses or hits a block that is further than the target, then there is line of sight
        return hitResult.getType() == HitResult.Type.MISS || hitResult.getPos().squaredDistanceTo(targetEyePos) < 1.0; // Small tolerance
    }

}
