package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import java.util.concurrent.ConcurrentHashMap;

import java.util.UUID;

/**
 * Stage-2 refactor: follow movement primitives extracted out of {@code BotEventHandler}.
 *
 * <p>Intended to be behavior-neutral. Higher-level follow/come decision logic remains in BotEventHandler.</p>
 */
public final class FollowMovementService {

    private static final double MIN_FOLLOW_DISTANCE = 1.0D;
    private static final double MIN_FOLLOW_DISTANCE_SQ = MIN_FOLLOW_DISTANCE * MIN_FOLLOW_DISTANCE;
    private static final double CLOSE_RANGE_CLEAR_DISTANCE_SQ = 2.25D;

    // Simple local obstacle avoidance for FOLLOW when pathing/door logic doesn't kick in quickly.
    private static final long LOCAL_NUDGE_COOLDOWN_MS = 650L;
    private static final ConcurrentHashMap<UUID, Long> LAST_LOCAL_NUDGE_MS = new ConcurrentHashMap<>();

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
        if (distanceSq <= MIN_FOLLOW_DISTANCE_SQ) {
            stepBack(bot, targetPos);
            return;
        }
        double stopDistance = Math.max(followPersonalSpace, MIN_FOLLOW_DISTANCE);
        if (distanceSq <= stopDistance * stopDistance) {
            if (allowCloseStop) {
                BotActions.stop(bot);
            } else {
                stepBack(bot, targetPos);
            }
            return;
        }
        LookController.faceBlock(bot, BlockPos.ofFloored(targetPos));

        // If we are pressed against a too-tall obstacle, do a small sidestep to try a new lane.
        // This is intentionally lightweight and non-destructive.
        if (tryLocalObstacleNudge(bot, targetPos)) {
            return;
        }

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

    private static boolean tryLocalObstacleNudge(ServerPlayerEntity bot, Vec3d targetPos) {
        if (bot == null || targetPos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!bot.isOnGround() || bot.isUsingItem() || bot.hasVehicle()) {
            return false;
        }

        // Only nudge when we appear "pinned" (not moving much).
        if (bot.getVelocity().horizontalLengthSquared() > 0.0025D) {
            return false;
        }

        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_LOCAL_NUDGE_MS.getOrDefault(id, 0L);
        if (now - last < LOCAL_NUDGE_COOLDOWN_MS) {
            return false;
        }

        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d toTarget = new Vec3d(targetPos.x - pos.x, 0.0D, targetPos.z - pos.z);
        if (toTarget.lengthSquared() < 1.0E-4) {
            return false;
        }
        Vec3d dir = toTarget.normalize();

        // Probe the block we are trying to move into.
        BlockPos front = BlockPos.ofFloored(pos.x + dir.x * 0.65D, pos.y, pos.z + dir.z * 0.65D);
        if (hasTwoHighClearance(world, front)) {
            return false;
        }

        // If stepping up would work, let jump/autojump handle it.
        if (hasTwoHighClearance(world, front.up())) {
            return false;
        }

        // Two-high wall / unjumpable obstruction: sidestep.
        Vec3d left = new Vec3d(-dir.z, 0.0D, dir.x);
        Vec3d right = new Vec3d(dir.z, 0.0D, -dir.x);
        Vec3d leftPos = pos.add(left.multiply(0.95D));
        Vec3d rightPos = pos.add(right.multiply(0.95D));

        BlockPos leftBlock = BlockPos.ofFloored(leftPos.x, leftPos.y, leftPos.z);
        BlockPos rightBlock = BlockPos.ofFloored(rightPos.x, rightPos.y, rightPos.z);
        boolean leftClear = hasTwoHighClearance(world, leftBlock) && world.getFluidState(leftBlock).isEmpty();
        boolean rightClear = hasTwoHighClearance(world, rightBlock) && world.getFluidState(rightBlock).isEmpty();

        if (!leftClear && !rightClear) {
            return false;
        }

        Vec3d nudgeTarget;
        if (leftClear && rightClear) {
            // Stable choice to reduce oscillation.
            nudgeTarget = (id.getLeastSignificantBits() & 1L) == 0L ? leftPos : rightPos;
        } else {
            nudgeTarget = leftClear ? leftPos : rightPos;
        }

        LAST_LOCAL_NUDGE_MS.put(id, now);
        BotActions.sprint(bot, false);
        BotActions.applyMovementInput(bot, nudgeTarget, 0.18D);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasTwoHighClearance(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        return (feet.isAir() || !feet.blocksMovement()) && (head.isAir() || !head.blocksMovement());
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
