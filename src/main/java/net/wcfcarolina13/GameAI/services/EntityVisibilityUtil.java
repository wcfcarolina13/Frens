package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Strict eye-to-eye line-of-sight test for voice-line / "I see X" reaction triggers.
 *
 * <p>Used by {@link CompanionContextReactionService} and similar reaction services
 * where the bot is supposed to be commenting on something it can actually see.
 * Threat-detection code paths (creepers, warden, hostile radius) intentionally do
 * NOT use this — a creeper around a corner is still a real threat.</p>
 *
 * <p>Implementation mirrors vanilla {@code LivingEntity.canSee}: raycast from the
 * bot's eye position to the target's eye position with a collider-shape /
 * fluid-NONE context, ignoring the bot itself. A clear ray (or one that misses
 * any block) counts as visible. Any block intersection counts as occluded.</p>
 *
 * <p>Strict (no tolerance) is the right behavior here: voice lines about visible
 * entities should not fire when the entity is genuinely behind a wall. The
 * 2-block tolerance used for item pickup in {@code findNearestDrop} is for a
 * different purpose (items physically near a surface look "behind" a block to a
 * naive ray) and would defeat the LOS purpose.</p>
 */
public final class EntityVisibilityUtil {

    private EntityVisibilityUtil() {}

    /**
     * Returns true iff the bot has an unobstructed line of sight from its eye
     * to the target's eye position. Returns false on any null input or if the
     * bot isn't in a {@link ServerWorld}.
     */
    public static boolean canSee(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null) return false;
        if (!target.isAlive() || target.isRemoved()) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        if (target.getEntityWorld() != world) return false;

        Vec3d eye = bot.getEyePos();
        Vec3d targetEye = new Vec3d(target.getX(), target.getEyeY(), target.getZ());
        RaycastContext ctx = new RaycastContext(
                eye, targetEye,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        BlockHitResult hit = world.raycast(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }
}
