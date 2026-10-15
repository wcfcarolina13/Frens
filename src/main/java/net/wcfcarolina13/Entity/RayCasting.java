package net.wcfcarolina13.Entity;


import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class RayCasting {

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
