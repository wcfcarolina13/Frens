package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;

import java.util.UUID;

/**
 * Stage-2 refactor: follow movement primitives extracted out of {@code BotEventHandler}.
 *
 * <p>Intended to be behavior-neutral. Higher-level follow/come decision logic remains in BotEventHandler.</p>
 */
public final class FollowMovementService {

    private static final double CLOSE_RANGE_CLEAR_DISTANCE_SQ = 2.25D;

    private FollowMovementService() {}

    public static void moveToward(ServerPlayerEntity bot,
                                 Vec3d target,
                                 double stopDistance,
                                 boolean sprint,
                                 Runnable lowerShield) {
        if (bot == null || target == null) {
            return;
        }
        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= stopDistance * stopDistance) {
            BotActions.stop(bot);
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);

        if (lowerShield != null) {
            lowerShield.run();
        }
        BotActions.sprint(bot, sprint);
        if (target.y - pos.y > 0.6D) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, target, sprint ? 0.18 : 0.14);
    }

    public static void followInputStep(ServerPlayerEntity bot,
                                       Vec3d targetPos,
                                       double distanceSq,
                                       boolean allowCloseStop,
                                       double followPersonalSpace,
                                       double followSprintDistanceSq) {
        simplePursuitStep(bot, targetPos, allowCloseStop, followPersonalSpace, followSprintDistanceSq);
        if (bot != null && distanceSq <= CLOSE_RANGE_CLEAR_DISTANCE_SQ) {
            FollowStateService.clearTransientCloseRange(bot.getUuid());
        }
    }

    public static void handleFollowPersonalSpace(ServerPlayerEntity bot,
                                                 ServerPlayerEntity target,
                                                 double distanceSq,
                                                 Vec3d targetPos,
                                                 double followBackupDistance,
                                                 long followBackupTriggerMs) {
        if (bot == null || target == null || targetPos == null) {
            return;
        }
        UUID id = bot.getUuid();
        double closeSq = followBackupDistance * followBackupDistance;
        if (distanceSq <= closeSq) {
            long now = System.currentTimeMillis();
            Long since = FollowStateService.FOLLOW_TOO_CLOSE_SINCE.get(id);
            if (since == null) {
                FollowStateService.FOLLOW_TOO_CLOSE_SINCE.put(id, now);
            } else if (now - since >= followBackupTriggerMs) {
                stepBack(bot, targetPos);
            }
        } else {
            FollowStateService.FOLLOW_TOO_CLOSE_SINCE.remove(id);
        }
    }

    private static void simplePursuitStep(ServerPlayerEntity bot,
                                         Vec3d targetPos,
                                         boolean allowCloseStop,
                                         double followPersonalSpace,
                                         double followSprintDistanceSq) {
        if (bot == null || targetPos == null) {
            return;
        }
        double distanceSq = horizontalDistanceSq(bot, targetPos);
        if (allowCloseStop && distanceSq <= followPersonalSpace * followPersonalSpace) {
            BotActions.stop(bot);
            return;
        }
        LookController.faceBlock(bot, BlockPos.ofFloored(targetPos));
        boolean sprint = distanceSq > followSprintDistanceSq;
        BotActions.sprint(bot, sprint);
        double dy = targetPos.y - bot.getY();
        if (dy > 0.6D) {
            BotActions.jump(bot);
        } else if (distanceSq > 2.25D) {
            BotActions.autoJumpIfNeeded(bot);
        }
        double impulse = sprint ? 0.22 : 0.16;
        BotActions.applyMovementInput(bot, targetPos, impulse);
    }

    private static void stepBack(ServerPlayerEntity bot, Vec3d targetPos) {
        if (bot == null || targetPos == null) {
            return;
        }
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d away = new Vec3d(botPos.x - targetPos.x, 0, botPos.z - targetPos.z);
        if (away.lengthSquared() < 1.0E-4) {
            float yaw = bot.getYaw();
            double dx = -Math.sin(Math.toRadians(yaw));
            double dz = Math.cos(Math.toRadians(yaw));
            away = new Vec3d(dx, 0, dz);
        }
        Vec3d target = botPos.add(away.normalize().multiply(1.8));
        LookController.faceBlock(bot, BlockPos.ofFloored(target));
        BotActions.sprint(bot, false);
        BotActions.applyMovementInput(bot, target, 0.14);
    }

    private static double horizontalDistanceSq(ServerPlayerEntity bot, Vec3d targetPos) {
        double dx = targetPos.x - bot.getX();
        double dz = targetPos.z - bot.getZ();
        return dx * dx + dz * dz;
    }
}
