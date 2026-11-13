package net.wcfcarolina13.PlayerUtils;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Shared by Fortify stance selection, diagnostics and its actual mining precheck. */
public final class MiningFaceVisibility {
    private MiningFaceVisibility() {}

    /** Null means a sampled face is visible, otherwise the first blocking position is returned. */
    public static BlockPos blockingPos(ServerWorld world, ServerPlayerEntity bot, Vec3d eye, BlockPos target) {
        BlockPos blocker = null;
        for (var point : MiningFaceVisibilityPolicy.samples(target.getX(), target.getY(), target.getZ(),
                eye.x, eye.y, eye.z)) {
            BlockHitResult hit = world.raycast(new RaycastContext(eye, new Vec3d(point.x(), point.y(), point.z()),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, bot));
            if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) return null;
            if (blocker == null) blocker = hit.getBlockPos().toImmutable();
        }
        return blocker;
    }
}
