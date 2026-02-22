package net.wcfcarolina13.GameAI.services.construction.execution;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared reach/recovery helpers for construction execution.
 */
public final class ConstructionRecoveryService {

    private ConstructionRecoveryService() {}

    public record RecoveryResult(
            boolean success,
            FailureReason failureReason,
            boolean progressMade
    ) {
        public static RecoveryResult success(boolean progressMade) {
            return new RecoveryResult(true, null, progressMade);
        }

        public static RecoveryResult failure(FailureReason reason, boolean progressMade) {
            return new RecoveryResult(false, reason, progressMade);
        }
    }

    public static RecoveryResult ensureReachWithScaffold(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            ServerWorld world,
            BlockPos target,
            int passNumber,
            double reachDistanceSq,
            int maxScaffoldHeight,
            ScaffoldService.ScaffoldSession scaffoldSession
    ) {
        boolean progress = false;

        if (isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(false);
        }

        RecoveryResult movement = ensureReachByMovement(source, bot, target, passNumber, reachDistanceSq);
        progress |= movement.progressMade();
        if (movement.success() && isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(progress);
        }

        // Do not scaffold on first reach pass; favor local movement/jump escapes first.
        if (passNumber >= 2 && scaffoldSession != null && maxScaffoldHeight > 0) {
            int verticalDiff = target.getY() - bot.getBlockPos().getY();
            if (verticalDiff > 2) {
                int targetY = target.getY() - 2;
                int climb = targetY - bot.getBlockPos().getY();
                if (climb > 0 && climb <= maxScaffoldHeight) {
                    boolean pillared = ScaffoldService.pillarToY(scaffoldSession, targetY);
                    progress |= pillared;
                    if (pillared && isWithinReach(bot, target, reachDistanceSq)) {
                        return RecoveryResult.success(progress);
                    }
                }
            }
        }

        if (isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(progress);
        }

        return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
    }

    public static RecoveryResult ensureReachByMovement(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            BlockPos target,
            int passNumber,
            double reachDistanceSq
    ) {
        boolean progress = false;

        if (isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(false);
        }

        RecoveryResult localTraversal = ensureReachByLocalTraversal(bot, target, passNumber, reachDistanceSq);
        progress |= localTraversal.progressMade();
        if (localTraversal.success() && isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(progress);
        }

        if (moveTo(source, bot, target)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        }

        if (passNumber >= 2 && tryWideArcReposition(bot, target)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        }

        if (passNumber >= 3) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos side = target.offset(dir, 2).withY(bot.getBlockPos().getY());
                if (moveTo(source, bot, side)) {
                    progress = true;
                    if (isWithinReach(bot, target, reachDistanceSq)) {
                        return RecoveryResult.success(true);
                    }
                }
            }
        }

        return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
    }

    public static RecoveryResult tryCreateTemporarySupportUnder(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            ServerWorld world,
            BlockPos target,
            Set<BlockPos> plannedNonAir,
            int maxSupportHeight,
            List<Item> supportBlocks,
            ScaffoldService.ScaffoldSession scaffoldSession,
            double reachDistanceSq
    ) {
        if (target == null || maxSupportHeight <= 0 || supportBlocks == null || supportBlocks.isEmpty()) {
            return RecoveryResult.failure(FailureReason.NO_SUPPORT, false);
        }

        BlockPos cursor = target.down();
        List<BlockPos> toFill = new ArrayList<>();
        for (int i = 0; i < maxSupportHeight; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty()) {
                break;
            }
            if (plannedNonAir != null && plannedNonAir.contains(cursor)) {
                return RecoveryResult.failure(FailureReason.NO_SUPPORT, false);
            }
            toFill.add(cursor.toImmutable());
            cursor = cursor.down();
        }

        if (toFill.isEmpty()) {
            return RecoveryResult.failure(FailureReason.NO_SUPPORT, false);
        }

        BlockState foundation = world.getBlockState(toFill.get(toFill.size() - 1).down());
        if (foundation.isAir() || !foundation.getFluidState().isEmpty()) {
            return RecoveryResult.failure(FailureReason.NO_SUPPORT, false);
        }

        boolean progress = false;
        for (int i = toFill.size() - 1; i >= 0; i--) {
            BlockPos pos = toFill.get(i);
            RecoveryResult reach = ensureReachByMovement(source, bot, pos, 2, reachDistanceSq);
            progress |= reach.progressMade();
            if (!reach.success()) {
                return RecoveryResult.failure(reach.failureReason(), progress);
            }

            BotActions.PlaceResult placed = BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, supportBlocks);
            if (!placed.success()) {
                return RecoveryResult.failure(FailureReason.fromPlaceReason(placed.reason()), progress);
            }
            progress = true;
            if (scaffoldSession != null) {
                scaffoldSession.track(pos);
            }
        }

        return RecoveryResult.success(progress);
    }

    public static boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos, double reachDistanceSq) {
        Vec3d eye = bot.getEyePos();
        return eye.squaredDistanceTo(Vec3d.ofCenter(pos)) <= reachDistanceSq;
    }

    public static boolean isWithinReachXZ(ServerPlayerEntity bot, BlockPos pos, double maxDist) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        return (dx * dx + dz * dz) <= (maxDist * maxDist);
    }

    private static boolean moveTo(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        var plan = MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) {
            return false;
        }
        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true, true, false);
        return result.success();
    }

    private static RecoveryResult ensureReachByLocalTraversal(ServerPlayerEntity bot,
                                                              BlockPos target,
                                                              int passNumber,
                                                              double reachDistanceSq) {
        if (bot == null || target == null) {
            return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
        }
        if (isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(false);
        }

        boolean progress = false;
        int exits = countOpenExits(world, bot.getBlockPos());
        if (exits <= 2 && tryImmediateLateralOrStepEscape(bot, world, target)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        }

        if (passNumber >= 2 && tryWideArcReposition(bot, target)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        }

        return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
    }

    private static boolean tryImmediateLateralOrStepEscape(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        BlockPos start = bot.getBlockPos();
        for (Direction dir : prioritizedDirections(start, target)) {
            BlockPos lateral = start.offset(dir);
            if (canStandAt(world, lateral)) {
                if (nudgeToward(bot, lateral, 700L, false)) {
                    return true;
                }
            }

            BlockPos stepUp = lateral.up();
            if (canStandAt(world, stepUp) && canJumpInto(world, start, dir)) {
                BotActions.jump(bot);
                if (nudgeToward(bot, stepUp, 900L, true)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tryWideArcReposition(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos start = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = start.add(dx, dy, dz);
                        if (!canStandAt(world, candidate)) {
                            continue;
                        }
                        int exits = countOpenExits(world, candidate);
                        if (exits < 2) {
                            continue;
                        }

                        double towardGain = start.getSquaredDistance(target) - candidate.getSquaredDistance(target);
                        double score = exits * 110.0;
                        score += towardGain * 5.5;
                        score -= start.getSquaredDistance(candidate) * 6.5;
                        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) {
                            score += 24.0;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) {
                break;
            }
        }

        if (best == null) {
            return false;
        }
        return nudgeToward(bot, best, 1_400L, false);
    }

    private static Direction[] prioritizedDirections(BlockPos from, BlockPos target) {
        Direction[] dirs = new Direction[] {
                Direction.NORTH,
                Direction.SOUTH,
                Direction.EAST,
                Direction.WEST
        };
        if (from == null || target == null) {
            return dirs;
        }
        java.util.Arrays.sort(dirs, (a, b) -> {
            BlockPos pa = from.offset(a);
            BlockPos pb = from.offset(b);
            return Double.compare(pa.getSquaredDistance(target), pb.getSquaredDistance(target));
        });
        return dirs;
    }

    private static boolean nudgeToward(ServerPlayerEntity bot, BlockPos target, long timeoutMs, boolean allowJump) {
        if (bot == null || target == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();
        Vec3d targetVec = Vec3d.ofCenter(target);
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (bot.squaredDistanceTo(targetVec) <= 4.0D) {
                return !bot.getBlockPos().equals(start);
            }
            if (allowJump && target.getY() > bot.getBlockPos().getY()) {
                BotActions.jump(bot);
            } else {
                BotActions.autoJumpIfNeeded(bot);
            }
            BotActions.applyMovementInput(bot, targetVec, 0.24D);
            sleepQuiet(50L);
        }
        return !bot.getBlockPos().equals(start);
    }

    private static boolean canStandAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        boolean feetClear = feet.isAir() || feet.isReplaceable();
        boolean headClear = head.isAir() || head.isReplaceable();
        boolean hasSupport = !below.isAir() && !below.isReplaceable();
        return feetClear && headClear && hasSupport;
    }

    private static int countOpenExits(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return 0;
        }
        int exits = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (canStandAt(world, center.offset(dir))) {
                exits++;
            }
        }
        return exits;
    }

    private static boolean canJumpInto(ServerWorld world, BlockPos start, Direction dir) {
        if (world == null || start == null || dir == null) {
            return false;
        }
        BlockState headNow = world.getBlockState(start.up(2));
        if (!headNow.isAir() && !headNow.isReplaceable()) {
            return false;
        }
        BlockState headAhead = world.getBlockState(start.offset(dir).up(2));
        return headAhead.isAir() || headAhead.isReplaceable();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
