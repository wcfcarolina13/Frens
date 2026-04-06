package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.FluidTags;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage-2 refactor: follow movement primitives extracted out of {@code BotEventHandler}.
 *
 * <p>Intended to be behavior-neutral. Higher-level follow/come decision logic remains in BotEventHandler.</p>
 */
public final class FollowMovementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");

    private static final double MIN_FOLLOW_DISTANCE = 1.0D;
    private static final double MIN_FOLLOW_DISTANCE_SQ = MIN_FOLLOW_DISTANCE * MIN_FOLLOW_DISTANCE;
    private static final double CLOSE_RANGE_CLEAR_DISTANCE_SQ = 2.25D;

    // Simple local obstacle avoidance for FOLLOW when pathing/door logic doesn't kick in quickly.
    private static final long LOCAL_NUDGE_COOLDOWN_MS = 650L;
    private static final ConcurrentHashMap<UUID, Long> LAST_LOCAL_NUDGE_MS = new ConcurrentHashMap<>();
    private static final int[] REPOSITION_Y_OFFSETS = new int[] { 0, 1, -1 };
    private static final int DROP_GUARD_MIN_DEPTH = 3;
    private static final int DROP_GUARD_MAX_PROBE_DEPTH = 12;
    private static final double DROP_GUARD_FORWARD_PROBE = 0.8D;
    private static final long DROP_WARNING_COOLDOWN_MS = 8_000L;
    private static final ConcurrentHashMap<UUID, Long> LAST_DROP_WARNING_MS = new ConcurrentHashMap<>();
    private static final String DROP_WARNING_LINE = "woah, I almost fell into that hole";
    private static final long ELYTRA_DESCENT_COOLDOWN_MS = 30_000L;
    private static final ConcurrentHashMap<UUID, Long> LAST_ELYTRA_DESCENT_ATTEMPT_MS = new ConcurrentHashMap<>();

    public enum WaypointRepositionOutcome {
        NO_CANDIDATE,
        INPUT_APPLIED,
        DISPLACED
    }

    public record WaypointRepositionResult(WaypointRepositionOutcome outcome,
                                           BlockPos origin,
                                           BlockPos target,
                                           String detail) {
    }

    public enum FollowWaterEscapeOutcome {
        NO_CONTEXT,
        NO_CANDIDATE,
        INPUT_APPLIED,
        DISPLACED
    }

    public record FollowWaterEscapeResult(FollowWaterEscapeOutcome outcome,
                                          BlockPos origin,
                                          BlockPos candidate,
                                          String detail) {
    }

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
        yaw = deflectFromPassiveEndermen(bot, yaw);
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);

        // Guard against blindly stepping off tall ledges during waypoint/goal movement.
        if (tryDropoffGuard(bot, target)) {
            return;
        }

        // Preventive lava/magma look-ahead: avoid walking into dangerous ground.
        if (bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos ahead = bot.getBlockPos().offset(
                approximateToward(bot.getBlockPos(), BlockPos.ofFloored(target)));
            if (isDangerousGround(sw, ahead)) {
                BlockPos safe = selectSafeLateralBypass(sw, bot.getBlockPos(), target);
                if (safe != null) {
                    LookController.faceBlock(bot, safe);
                    BotActions.sprint(bot, false);
                    BotActions.autoJumpIfNeeded(bot);
                    BotActions.applyMovementInput(bot, Vec3d.ofCenter(safe), 0.16D);
                    return;
                }
                BotActions.stop(bot);
                return;
            }
        }

        if (lowerShield != null) {
            lowerShield.run();
        }
        applyHumanLikeForwardInput(bot, target, sprint, sprint ? 0.24D : 0.16D, 0.12D);
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

    public static void followWaypointStep(ServerPlayerEntity bot,
                                          BlockPos waypoint,
                                          double distanceSq,
                                          double followSprintDistanceSq,
                                          Runnable lowerShield) {
        if (bot == null || waypoint == null) {
            return;
        }
        Vec3d waypointCenter = Vec3d.ofCenter(waypoint);
        if (distanceSq <= CLOSE_RANGE_CLEAR_DISTANCE_SQ) {
            BotActions.stop(bot);
            return;
        }
        LookController.faceBlock(bot, waypoint);
        // Narrow passage alignment: when stagnant near a 1-wide gap, steer toward the gap center.
        if (bot.getVelocity().horizontalLengthSquared() < 0.0025D
                && bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos gap = findNarrowPassageAlignmentTarget(sw, bot.getBlockPos(), waypoint);
            if (gap != null) {
                FollowDebugService.maybeLogDecision(LOGGER, bot,
                        "narrow-passage-align: gap=" + gap.toShortString() + " waypoint=" + waypoint.toShortString());
                Vec3d gapCenter = Vec3d.ofCenter(gap);
                LookController.faceBlock(bot, gap);
                BotActions.sprint(bot, false);
                BotActions.autoJumpIfNeeded(bot);
                BotActions.applyMovementInput(bot, gapCenter, 0.18D);
                return;
            }
        }
        if (tryLocalObstacleNudge(bot, waypointCenter)) {
            return;
        }
        if (tryDropoffGuard(bot, waypointCenter)) {
            return;
        }
        // Preventive lava/magma look-ahead: avoid walking into dangerous ground.
        if (bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos ahead = bot.getBlockPos().offset(
                approximateToward(bot.getBlockPos(), waypoint));
            if (isDangerousGround(sw, ahead)) {
                BlockPos safe = selectSafeLateralBypass(sw, bot.getBlockPos(), waypointCenter);
                if (safe != null) {
                    LookController.faceBlock(bot, safe);
                    BotActions.sprint(bot, false);
                    BotActions.autoJumpIfNeeded(bot);
                    BotActions.applyMovementInput(bot, Vec3d.ofCenter(safe), 0.16D);
                    return;
                }
                BotActions.stop(bot);
                return;
            }
        }

        if (lowerShield != null) {
            lowerShield.run();
        }
        boolean sprint = distanceSq > followSprintDistanceSq;
        applyHumanLikeForwardInput(bot, waypointCenter, sprint, sprint ? 0.24D : 0.17D, 0.12D);
    }

    public static WaypointRepositionResult tryWaypointLocalRepositionDetailed(ServerPlayerEntity bot, BlockPos waypoint) {
        if (bot == null || waypoint == null) {
            return new WaypointRepositionResult(WaypointRepositionOutcome.NO_CANDIDATE, null, null, "invalid-input");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new WaypointRepositionResult(WaypointRepositionOutcome.NO_CANDIDATE, null, null, "non-server-world");
        }
        if (bot.hasVehicle() || bot.isUsingItem()) {
            return new WaypointRepositionResult(WaypointRepositionOutcome.NO_CANDIDATE, bot.getBlockPos().toImmutable(), null, "busy");
        }

        BlockPos origin = bot.getBlockPos().toImmutable();
        BlockPos best = selectBestLocalRepositionCandidate(world, origin, waypoint);
        if (best == null) {
            return new WaypointRepositionResult(WaypointRepositionOutcome.NO_CANDIDATE, origin, null, "no-candidate");
        }

        Vec3d target = Vec3d.ofCenter(best);
        LookController.faceBlock(bot, best);
        BotActions.sprint(bot, false);
        BotActions.autoJumpIfNeeded(bot);
        BotActions.applyMovementInput(bot, target, 0.18D);
        BlockPos after = bot.getBlockPos();
        WaypointRepositionOutcome outcome = after != null && !after.equals(origin)
                ? WaypointRepositionOutcome.DISPLACED
                : WaypointRepositionOutcome.INPUT_APPLIED;
        return new WaypointRepositionResult(outcome, origin, best.toImmutable(), outcome == WaypointRepositionOutcome.DISPLACED ? "immediate-displacement" : "input-applied");
    }

    public static boolean tryWaypointLocalReposition(ServerPlayerEntity bot, BlockPos waypoint) {
        WaypointRepositionResult result = tryWaypointLocalRepositionDetailed(bot, waypoint);
        return result != null && result.outcome() != WaypointRepositionOutcome.NO_CANDIDATE;
    }

    public static FollowWaterEscapeResult tryFollowWaterLedgeEscapeDetailed(ServerPlayerEntity bot, BlockPos goalBlock) {
        if (bot == null || goalBlock == null) {
            return new FollowWaterEscapeResult(FollowWaterEscapeOutcome.NO_CONTEXT, null, null, "invalid-input");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new FollowWaterEscapeResult(FollowWaterEscapeOutcome.NO_CONTEXT, null, null, "non-server-world");
        }
        if (bot.hasVehicle() || bot.isUsingItem()) {
            return new FollowWaterEscapeResult(FollowWaterEscapeOutcome.NO_CONTEXT, bot.getBlockPos().toImmutable(), null, "busy");
        }
        if (!isWaterEscapeContext(bot, world)) {
            return new FollowWaterEscapeResult(FollowWaterEscapeOutcome.NO_CONTEXT, bot.getBlockPos().toImmutable(), null, "not-in-water");
        }

        BlockPos origin = bot.getBlockPos().toImmutable();
        BlockPos best = selectBestWaterEscapeCandidate(world, origin, goalBlock);
        if (best == null) {
            return new FollowWaterEscapeResult(FollowWaterEscapeOutcome.NO_CANDIDATE, origin, null, "no-candidate");
        }

        LookController.faceBlock(bot, best);
        BotActions.sprint(bot, false);
        if (isWaterEscapeContext(bot, world)) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, Vec3d.ofCenter(best), 0.18D);

        BlockPos after = bot.getBlockPos();
        FollowWaterEscapeOutcome outcome = after != null && !after.equals(origin)
                ? FollowWaterEscapeOutcome.DISPLACED
                : FollowWaterEscapeOutcome.INPUT_APPLIED;
        return new FollowWaterEscapeResult(outcome, origin, best.toImmutable(), outcome == FollowWaterEscapeOutcome.DISPLACED ? "immediate-displacement" : "input-applied");
    }

    public static boolean tryFollowWaterLedgeEscape(ServerPlayerEntity bot, BlockPos goalBlock) {
        FollowWaterEscapeResult result = tryFollowWaterLedgeEscapeDetailed(bot, goalBlock);
        if (result == null) {
            return false;
        }
        return result.outcome() == FollowWaterEscapeOutcome.INPUT_APPLIED
                || result.outcome() == FollowWaterEscapeOutcome.DISPLACED;
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

        // Narrow passage alignment: when stagnant near a 1-wide gap, steer toward the gap center.
        if (bot.getVelocity().horizontalLengthSquared() < 0.0025D
                && bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos targetBlock = BlockPos.ofFloored(targetPos);
            BlockPos gap = findNarrowPassageAlignmentTarget(sw, bot.getBlockPos(), targetBlock);
            if (gap != null) {
                FollowDebugService.maybeLogDecision(LOGGER, bot,
                        "narrow-passage-align: gap=" + gap.toShortString() + " target=" + targetBlock.toShortString());
                Vec3d gapCenter = Vec3d.ofCenter(gap);
                LookController.faceBlock(bot, gap);
                BotActions.sprint(bot, false);
                BotActions.autoJumpIfNeeded(bot);
                BotActions.applyMovementInput(bot, gapCenter, 0.18D);
                return;
            }
        }

        // If we are pressed against a too-tall obstacle, do a small sidestep to try a new lane.
        // This is intentionally lightweight and non-destructive.
        if (tryLocalObstacleNudge(bot, targetPos)) {
            return;
        }
        if (tryDropoffGuard(bot, targetPos)) {
            return;
        }

        // Preventive lava/magma look-ahead: avoid walking into dangerous ground.
        if (bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos ahead = bot.getBlockPos().offset(approximateToward(bot.getBlockPos(),
                    BlockPos.ofFloored(targetPos)));
            if (isDangerousGround(sw, ahead)) {
                BlockPos safe = selectSafeLateralBypass(sw, bot.getBlockPos(), targetPos);
                if (safe != null) {
                    LookController.faceBlock(bot, safe);
                    BotActions.sprint(bot, false);
                    BotActions.autoJumpIfNeeded(bot);
                    BotActions.applyMovementInput(bot, Vec3d.ofCenter(safe), 0.16D);
                    return;
                }
                BotActions.stop(bot);
                return;
            }
        }

        boolean sprint = distanceSq > followSprintDistanceSq;
        double impulse = sprint ? 0.26D : 0.18D;
        applyHumanLikeForwardInput(bot, targetPos, sprint, impulse, 0.12D);
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

        BlockPos goal = BlockPos.ofFloored(targetPos);
        BlockPos current = bot.getBlockPos();
        BlockPos candidate = selectBestLocalRepositionCandidate(world, current, goal);
        if (candidate == null) {
            return false;
        }

        LAST_LOCAL_NUDGE_MS.put(id, now);
        BotActions.sprint(bot, false);
        BotActions.applyMovementInput(bot, Vec3d.ofCenter(candidate), 0.18D);
        return true;
    }

    private static boolean tryDropoffGuard(ServerPlayerEntity bot, Vec3d targetPos) {
        if (bot == null || targetPos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!bot.isOnGround() || bot.hasVehicle() || bot.isUsingItem() || bot.isTouchingWater()) {
            return false;
        }

        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d toward = new Vec3d(targetPos.x - pos.x, 0.0D, targetPos.z - pos.z);
        if (toward.lengthSquared() < 0.10D) {
            return false;
        }
        Vec3d dir = toward.normalize();
        BlockPos front = BlockPos.ofFloored(pos.x + dir.x * DROP_GUARD_FORWARD_PROBE, bot.getY(), pos.z + dir.z * DROP_GUARD_FORWARD_PROBE);
        if (front.equals(bot.getBlockPos())) {
            Direction fallbackDir = approximateToward(bot.getBlockPos(), BlockPos.ofFloored(targetPos));
            front = bot.getBlockPos().offset(fallbackDir);
        }
        if (!isDangerousDropCell(world, front)) {
            return false;
        }

        // If the target is a valid, grounded standing position and the step-down
        // is within safe fall distance, this is a normal descent (e.g. cave
        // staircase) — not a cliff walk-off.  The drop guard probes at the bot's
        // current Y, so a deep cave below a perfectly walkable ledge triggers a
        // false positive.  Checking the target directly avoids this.
        BlockPos targetBlock = BlockPos.ofFloored(targetPos);
        double stepDown = bot.getY() - targetPos.y;
        if (stepDown >= -0.5D && stepDown <= 4.5D
                && isGroundedTwoHighClearance(world, targetBlock)) {
            return false;
        }

        BlockPos safeBypass = selectDropoffBypassCandidate(world, bot.getBlockPos(), front, BlockPos.ofFloored(targetPos));
        if (safeBypass != null) {
            LookController.faceBlock(bot, safeBypass);
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, Vec3d.ofCenter(safeBypass), 0.18D);
        } else {
            // No safe bypass — try elytra descent if available, with cooldown
            // to prevent rapid retry loops when the bot can't actually launch
            // (e.g. cave opening below, not open air).
            UUID elytraId = bot.getUuid();
            long elytraNow = System.currentTimeMillis();
            long lastElytraAttempt = LAST_ELYTRA_DESCENT_ATTEMPT_MS.getOrDefault(elytraId, 0L);
            boolean elytraCooledDown = (elytraNow - lastElytraAttempt) >= ELYTRA_DESCENT_COOLDOWN_MS;

            if (elytraCooledDown
                    && !ElytraFlightService.isInFlight(elytraId)
                    && ElytraFlightService.tryAutonomousDescent(bot, world.getServer())) {
                LAST_ELYTRA_DESCENT_ATTEMPT_MS.put(elytraId, elytraNow);
            } else {
                BotActions.stop(bot);
            }
        }
        maybeShowDropWarning(bot);
        return true;
    }

    private record RepositionCandidate(BlockPos pos, double lanePenalty) {
    }

    private record WaterEscapeCandidate(BlockPos pos, double laneBonus) {
    }

    private record DropBypassCandidate(BlockPos pos, double lanePenalty) {
    }

    private static BlockPos selectBestLocalRepositionCandidate(ServerWorld world, BlockPos current, BlockPos waypoint) {
        if (world == null || current == null || waypoint == null) {
            return null;
        }
        Direction toward = approximateToward(current, waypoint);
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        ArrayList<RepositionCandidate> candidates = new ArrayList<>(18);
        candidates.add(new RepositionCandidate(current.offset(left), 0.0D));
        candidates.add(new RepositionCandidate(current.offset(right), 0.0D));
        candidates.add(new RepositionCandidate(current.offset(toward).offset(left), 0.15D));
        candidates.add(new RepositionCandidate(current.offset(toward).offset(right), 0.15D));
        candidates.add(new RepositionCandidate(current.offset(toward.getOpposite()), 0.45D));
        candidates.add(new RepositionCandidate(current.offset(toward.getOpposite()).offset(left), 0.35D));
        candidates.add(new RepositionCandidate(current.offset(toward.getOpposite()).offset(right), 0.35D));

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (RepositionCandidate base : candidates) {
            if (base == null || base.pos() == null) {
                continue;
            }
            for (int dY : REPOSITION_Y_OFFSETS) {
                BlockPos cand = base.pos().add(0, dY, 0);
                if (!isGroundedTwoHighClearance(world, cand)) {
                    continue;
                }
                double dx = cand.getX() - waypoint.getX();
                double dz = cand.getZ() - waypoint.getZ();
                double horizDistSq = dx * dx + dz * dz;
                double yDelta = Math.abs(cand.getY() - waypoint.getY());
                double score = horizDistSq + (yDelta * 1.35D) + base.lanePenalty();
                if (score < bestScore) {
                    bestScore = score;
                    best = cand.toImmutable();
                }
            }
        }
        return best;
    }

    private static BlockPos selectBestWaterEscapeCandidate(ServerWorld world, BlockPos origin, BlockPos goalBlock) {
        if (world == null || origin == null || goalBlock == null) {
            return null;
        }
        Direction toward = approximateToward(origin, goalBlock);
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();
        BlockPos sideL = origin.offset(left);
        BlockPos sideR = origin.offset(right);
        BlockPos back = origin.offset(toward.getOpposite());
        double originGoalHorizSq = horizontalSq(origin, goalBlock);

        ArrayList<WaterEscapeCandidate> bases = new ArrayList<>(24);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos p = origin.add(dx, 0, dz);
                double laneBonus = 0.0D;
                if (p.getX() == sideL.getX() && p.getZ() == sideL.getZ()) {
                    laneBonus = -0.28D;
                } else if (p.getX() == sideR.getX() && p.getZ() == sideR.getZ()) {
                    laneBonus = -0.28D;
                } else if (p.getX() == back.getX() && p.getZ() == back.getZ()) {
                    laneBonus = 0.30D;
                }
                bases.add(new WaterEscapeCandidate(p, laneBonus));
            }
        }

        int[] yOffsets = new int[] { -1, 0, 1, 2 };
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (WaterEscapeCandidate base : bases) {
            if (base == null || base.pos() == null) {
                continue;
            }
            for (int yOff : yOffsets) {
                BlockPos cand = base.pos().add(0, yOff, 0);
                if (!isGroundedTwoHighClearance(world, cand)) {
                    continue;
                }
                if (!world.getFluidState(cand).isEmpty() || !world.getFluidState(cand.up()).isEmpty()) {
                    continue;
                }
                double goalHorizSq = horizontalSq(cand, goalBlock);
                if (goalHorizSq > (originGoalHorizSq + 0.05D)) {
                    continue;
                }
                double yDelta = Math.abs(cand.getY() - goalBlock.getY());
                double score = Math.sqrt(goalHorizSq) + (1.2D * yDelta) + base.laneBonus();
                if (score < bestScore) {
                    bestScore = score;
                    best = cand.toImmutable();
                }
            }
        }
        return best;
    }

    private static BlockPos selectDropoffBypassCandidate(ServerWorld world, BlockPos current, BlockPos front, BlockPos goal) {
        if (world == null || current == null || front == null || goal == null) {
            return null;
        }
        Direction toward = approximateToward(current, goal);
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        ArrayList<DropBypassCandidate> candidates = new ArrayList<>(8);
        candidates.add(new DropBypassCandidate(current.offset(left), 0.0D));
        candidates.add(new DropBypassCandidate(current.offset(right), 0.0D));
        candidates.add(new DropBypassCandidate(current.offset(left).offset(toward), 0.10D));
        candidates.add(new DropBypassCandidate(current.offset(right).offset(toward), 0.10D));
        candidates.add(new DropBypassCandidate(current.offset(toward.getOpposite()), 0.40D));

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (DropBypassCandidate cand : candidates) {
            if (cand == null || cand.pos() == null) {
                continue;
            }
            for (int dY : REPOSITION_Y_OFFSETS) {
                BlockPos pos = cand.pos().add(0, dY, 0);
                if (!isGroundedTwoHighClearance(world, pos)) {
                    continue;
                }
                if (isDangerousDropCell(world, pos)) {
                    continue;
                }
                double distScore = horizontalSq(pos, goal);
                double yPenalty = Math.abs(pos.getY() - goal.getY()) * 1.4D;
                double score = distScore + yPenalty + cand.lanePenalty();
                if (score < bestScore) {
                    bestScore = score;
                    best = pos.toImmutable();
                }
            }
        }
        return best;
    }

    private static boolean isWaterEscapeContext(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        if (bot.isTouchingWater()) {
            return true;
        }
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();
        return world.getFluidState(feet).isOf(Fluids.WATER)
                || world.getFluidState(head).isOf(Fluids.WATER);
    }

    public static boolean isDangerousDropCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!hasTwoHighClearance(world, pos)) {
            return false;
        }
        if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.up()).isEmpty()) {
            return false;
        }
        int fallDepth = 0;
        for (int d = 1; d <= DROP_GUARD_MAX_PROBE_DEPTH; d++) {
            BlockPos probe = pos.down(d);
            BlockState state = world.getBlockState(probe);
            boolean passable = state.getCollisionShape(world, probe).isEmpty() && world.getFluidState(probe).isEmpty();
            if (!passable) {
                break;
            }
            fallDepth++;
        }
        return fallDepth >= DROP_GUARD_MIN_DEPTH;
    }

    private static double horizontalSq(BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
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

    private static boolean isGroundedTwoHighClearance(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        if (isDangerousGround(world, pos)) {
            return false;
        }
        return hasTwoHighClearance(world, pos) && world.getFluidState(pos).isEmpty() && world.getFluidState(pos.up()).isEmpty();
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

    private static Direction approximateToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Scans the line between the bot and its waypoint for a "chokepoint" — a 1-wide gap
     * in a wall. Returns the gap center as a movement target, or null if no chokepoint found.
     *
     * Only activates within 3 blocks of a gap.
     * Cardinal-only scan for now; diagonal gaps may need handling later.
     */
    static BlockPos findNarrowPassageAlignmentTarget(ServerWorld world, BlockPos botPos, BlockPos waypoint) {
        if (world == null || botPos == null || waypoint == null) {
            return null;
        }
        int dx = waypoint.getX() - botPos.getX();
        int dz = waypoint.getZ() - botPos.getZ();
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist < 1 || dist > 4) {
            return null;
        }

        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        int steps = Math.max(Math.abs(dx), Math.abs(dz));

        for (int i = 1; i <= Math.min(steps, 3); i++) {
            int probeX, probeZ;
            if (Math.abs(dx) >= Math.abs(dz)) {
                probeX = botPos.getX() + stepX * i;
                probeZ = botPos.getZ() + (dz != 0 ? (int) Math.round((double) dz * i / Math.abs(dx)) : 0);
            } else {
                probeZ = botPos.getZ() + stepZ * i;
                probeX = botPos.getX() + (dx != 0 ? (int) Math.round((double) dx * i / Math.abs(dz)) : 0);
            }
            BlockPos probe = new BlockPos(probeX, botPos.getY(), probeZ);
            if (!hasTwoHighClearance(world, probe)) {
                continue;
            }
            if (isChokepoint(world, probe)) {
                return probe;
            }
        }
        return null;
    }

    /**
     * Returns true if the given position is a 1-wide gap: has 2-high clearance but both
     * perpendicular wall pairs are solid.
     *
     * Checks both axes. A chokepoint on the X-axis means walls to the north and south;
     * on the Z-axis, walls to the east and west.
     */
    private static boolean isChokepoint(ServerWorld world, BlockPos pos) {
        boolean wallNorth = !hasTwoHighClearance(world, pos.north());
        boolean wallSouth = !hasTwoHighClearance(world, pos.south());
        if (wallNorth && wallSouth) {
            return true;
        }
        boolean wallEast = !hasTwoHighClearance(world, pos.east());
        boolean wallWest = !hasTwoHighClearance(world, pos.west());
        return wallEast && wallWest;
    }

    private static void maybeShowDropWarning(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_DROP_WARNING_MS.getOrDefault(id, 0L);
        if (now - last < DROP_WARNING_COOLDOWN_MS) {
            return;
        }
        LAST_DROP_WARNING_MS.put(id, now);
        CompanionOverheadDialogueService.showOverheadLine(bot, DROP_WARNING_LINE, 2_800, 32.0D, "follow-drop-guard", "dropoff");
    }

    private static void applyHumanLikeForwardInput(ServerPlayerEntity bot,
                                                   Vec3d targetPos,
                                                   boolean sprint,
                                                   double groundImpulse,
                                                   double waterImpulse) {
        if (bot == null || targetPos == null) {
            return;
        }
        boolean inWater = isWaterLikeContext(bot);
        if (inWater) {
            // Avoid "ice-skating" across water during follow/return-home; keep movement buoyant and moderate.
            BotActions.sprint(bot, false);
            if (targetPos.y >= bot.getY() - 0.15D) {
                BotActions.jump(bot);
            }
            BotActions.applyMovementInput(bot, targetPos, Math.max(0.08D, waterImpulse));
            return;
        }

        BotActions.sprint(bot, sprint);
        double dy = targetPos.y - bot.getY();
        if (dy > 0.6D) {
            BotActions.jump(bot);
        } else {
            BotActions.autoJumpIfNeeded(bot);
        }
        BotActions.applyMovementInput(bot, targetPos, Math.max(0.10D, groundImpulse));
    }

    private static boolean isWaterLikeContext(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        if (bot.isTouchingWater() || bot.isSwimming()) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos feet = bot.getBlockPos();
        return world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    // ---- Dangerous ground (lava / magma / fire) avoidance ----

    /**
     * Scan left, right, and diagonal-forward for a safe position that stays close to the goal.
     */
    private static BlockPos selectSafeLateralBypass(ServerWorld world, BlockPos origin, Vec3d goal) {
        if (world == null || origin == null || goal == null) return null;
        Direction toward = approximateToward(origin, BlockPos.ofFloored(goal));
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        BlockPos[] candidates = {
            origin.offset(left),
            origin.offset(right),
            origin.offset(left).offset(toward),
            origin.offset(right).offset(toward),
        };

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos goalBlock = BlockPos.ofFloored(goal);
        for (BlockPos cand : candidates) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos test = cand.add(0, dy, 0);
                if (isDangerousGround(world, test)) continue;
                if (!isGroundedTwoHighClearance(world, test)) continue;
                double dist = test.getSquaredDistance(goalBlock);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = test;
                }
            }
        }
        return best;
    }

    /**
     * Returns true if the given position has lava, magma, or fire that would harm the bot.
     */
    private static boolean isDangerousGround(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState floor = world.getBlockState(pos.down());
        if (floor.isOf(Blocks.MAGMA_BLOCK)) return true;
        BlockState atFeet = world.getBlockState(pos);
        if (atFeet.isOf(Blocks.FIRE) || atFeet.isOf(Blocks.SOUL_FIRE)) return true;
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isEmpty() && !fluid.isOf(Fluids.WATER)) return true;
        return false;
    }

    /**
     * If the bot is standing on dangerous ground (lava, magma, fire), scan for the nearest
     * safe position and apply movement input toward it. Returns true if escape input was applied.
     */
    public static boolean tryDangerousGroundEscape(ServerPlayerEntity bot, BlockPos goalBlock) {
        if (bot == null || goalBlock == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        BlockPos origin = bot.getBlockPos();
        if (!isDangerousGround(world, origin)) return false;

        // Scan a small area for the best safe, walkable position (prefer closer to goal).
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int scanRadius = 5;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos cand = origin.add(dx, dy, dz);
                    if (isDangerousGround(world, cand)) continue;
                    if (!isGroundedTwoHighClearance(world, cand)) continue;
                    double goalDistSq = horizontalSq(cand, goalBlock);
                    double originDistSq = cand.getSquaredDistance(origin);
                    double score = goalDistSq * 0.6 + originDistSq * 0.4;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }
        }
        if (best == null) return false;

        LookController.faceBlock(bot, best);
        BotActions.sprint(bot, true);
        BotActions.autoJumpIfNeeded(bot);
        BotActions.applyMovementInput(bot, Vec3d.ofCenter(best), 0.22D);
        return true;
    }

    /**
     * If the computed movement yaw would cause the bot to stare at a passive enderman
     * (using Minecraft's own stare threshold: dot > 1 - 0.025/dist), deflect the yaw
     * by 30 degrees to avoid triggering aggro.
     */
    private static float deflectFromPassiveEndermen(ServerPlayerEntity bot, float yaw) {
        Box searchBox = bot.getBoundingBox().expand(12, 8, 12);
        List<EndermanEntity> endermen = bot.getEntityWorld().getEntitiesByClass(
                EndermanEntity.class, searchBox,
                e -> e.isAlive() && !e.isRemoved() && EndermanSafetyService.shouldAvoidLookingAt(e));
        if (endermen.isEmpty()) {
            return yaw;
        }
        float adjusted = yaw;
        for (EndermanEntity enderman : endermen) {
            double lookX = -Math.sin(Math.toRadians(adjusted));
            double lookZ =  Math.cos(Math.toRadians(adjusted));
            double toEx = enderman.getX() - bot.getX();
            double toEz = enderman.getZ() - bot.getZ();
            double distSq = toEx * toEx + toEz * toEz;
            if (distSq < 0.01) continue;
            double dist = Math.sqrt(distSq);
            double dot = (lookX * toEx + lookZ * toEz) / dist;
            if (dot > 1.0 - 0.025 / dist) {
                adjusted += 30.0f;
            }
        }
        return adjusted;
    }
}
