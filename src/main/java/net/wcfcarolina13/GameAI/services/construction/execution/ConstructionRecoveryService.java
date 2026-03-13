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
import net.wcfcarolina13.GameAI.services.construction.ConstructionPlacementRules;
import net.wcfcarolina13.GameAI.services.construction.ConstructionProtectionService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shared reach/recovery helpers for construction execution.
 */
public final class ConstructionRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-recovery");
    private static final int MAX_REACH_STANCE_RADIUS = 4;
    private static final int MAX_REACH_STANCE_DROP = 5;

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
            ScaffoldService.ScaffoldSession scaffoldSession,
            boolean preferScaffoldForOneUp
    ) {
        boolean progress = false;
        int verticalDiff = target.getY() - bot.getBlockPos().getY();

        // Only skip recovery if within reach AND at/above the target level.
        // Even verticalDiff=1 causes no-line-of-sight-to-support failures when
        // the bot's body blocks the support face from below.
        if (isWithinReach(bot, target, reachDistanceSq) && verticalDiff <= 0) {
            return RecoveryResult.success(false);
        }

        // For blocks 2+ above the bot, ALWAYS scaffold first (FortifyVillageSkill pattern).
        // Being "within reach" from below doesn't help — placement fails because the
        // bot can't see the support face when looking up through its own body or
        // adjacent wall blocks.
        if (scaffoldSession != null && maxScaffoldHeight > 0 && (verticalDiff >= 2 || (preferScaffoldForOneUp && verticalDiff >= 1))) {
            if (LOGGER.isInfoEnabled()) {
            LOGGER.info("task-recovery: scaffold entry target={} botPos={} verticalDiff={} maxScaffold={} pass={} preferOneUp={}",
                        target.toShortString(), bot.getBlockPos().toShortString(),
                verticalDiff, maxScaffoldHeight, passNumber, preferScaffoldForOneUp);
            }
            RecoveryResult scaffoldResult = ensureReachByScaffolding(
                    source, bot, world, target, reachDistanceSq,
                maxScaffoldHeight, scaffoldSession,
                verticalDiff >= 2 ? "elevated" : "aggressive-1up"
            );
            progress |= scaffoldResult.progressMade();
            if (scaffoldResult.success() && isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(progress);
            }
        }

        // For verticalDiff = 1: try movement to get to the same level as the target
        if (verticalDiff == 1) {
            RecoveryResult movement = ensureReachByMovement(source, bot, target, passNumber, reachDistanceSq);
            progress |= movement.progressMade();
            if (movement.success() && isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(progress);
            }
            // If movement didn't help, try scaffold for 1-block elevation too
            if (scaffoldSession != null && maxScaffoldHeight > 0) {
                RecoveryResult scaffoldFallback = ensureReachByScaffolding(
                        source, bot, world, target, reachDistanceSq,
                        maxScaffoldHeight, scaffoldSession, "fallback-1up"
                );
                progress |= scaffoldFallback.progressMade();
                if (scaffoldFallback.success() && isWithinReach(bot, target, reachDistanceSq)) {
                    return RecoveryResult.success(progress);
                }
            }
        }

        // For same-level or below targets, use movement
        if (verticalDiff <= 0) {
            RecoveryResult movement = ensureReachByMovement(source, bot, target, passNumber, reachDistanceSq);
            progress |= movement.progressMade();
            if (movement.success() && isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(progress);
            }
        }

        if (isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(progress);
        }

        return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
    }

    private static RecoveryResult ensureReachByScaffolding(ServerCommandSource source,
                                                           ServerPlayerEntity bot,
                                                           ServerWorld world,
                                                           BlockPos target,
                                                           double reachDistanceSq,
                                                           int maxScaffoldHeight,
                                                           ScaffoldService.ScaffoldSession scaffoldSession,
                                                           String mode) {
        if (bot == null || world == null || target == null || scaffoldSession == null || maxScaffoldHeight <= 0) {
            return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
        }

        BlockPos stance = chooseScaffoldStance(bot, world, target);
        if (stance == null) {
            LOGGER.info("task-recovery: scaffold {} no-stance target={} botPos={}",
                    mode, target.toShortString(), bot.getBlockPos().toShortString());
            return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
        }

        boolean progress = false;
        if (!stance.equals(bot.getBlockPos())) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("task-recovery: scaffold {} stance target={} stance={} botPos={}",
                        mode,
                        target.toShortString(),
                        stance.toShortString(),
                        bot.getBlockPos().toShortString());
            }
            // Try pathfinder first; fall back to direct nudge if path goes through protected blocks
            if (!moveTo(source, bot, stance)) {
                nudgeToward(bot, stance, 2000L, true);
            }
            // Accept if we're close enough horizontally — pillar doesn't need exact position
                if (squaredHorizontalDistance(bot.getBlockPos(), stance)
                    > ConstructionPlacementRules.APPROXIMATE_STANCE_HORIZONTAL_DISTANCE_SQ) {
                LOGGER.info("task-recovery: scaffold {} stance-unreachable target={} stance={} botPos={}",
                        mode, target.toShortString(), stance.toShortString(),
                        bot.getBlockPos().toShortString());
                return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
            }
            progress = true;
        }

        int targetStandY = target.getY() - 1;
        int climb = targetStandY - bot.getBlockPos().getY();
        if (climb <= 0) {
            return isWithinReach(bot, target, reachDistanceSq)
                    ? RecoveryResult.success(progress)
                    : RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
        }
        if (climb > maxScaffoldHeight) {
            LOGGER.info("task-recovery: scaffold {} climb-too-high target={} climb={} max={}",
                    mode, target.toShortString(), climb, maxScaffoldHeight);
            return RecoveryResult.failure(FailureReason.OUT_OF_REACH, progress);
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("task-recovery: scaffold {} pillar target={} stanceY={} targetStandY={} climb={}",
                    mode,
                    target.toShortString(),
                    bot.getBlockPos().getY(),
                    targetStandY,
                    climb);
        }

        boolean pillared = ScaffoldService.pillarToY(scaffoldSession, targetStandY);
        progress |= pillared;
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("task-recovery: scaffold {} result target={} pillared={} botPos={} inReach={}",
                    mode,
                    target.toShortString(),
                    pillared,
                    bot.getBlockPos().toShortString(),
                    isWithinReach(bot, target, reachDistanceSq));
        }

        if (pillared && isWithinReach(bot, target, reachDistanceSq)) {
            return RecoveryResult.success(progress);
        }
        return RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
    }

    /**
     * Choose a scaffold stance near the target for pillaring.
     * After pillaring to target.Y - 1, the bot places at eye level.
     * Matches FortifyVillageSkill: no protection check (scaffold stances are
     * temporary), prefers positions the bot can physically stand at.
     */
    private static BlockPos chooseScaffoldStance(ServerPlayerEntity bot,
                                                 ServerWorld world,
                                                 BlockPos target) {
        if (bot == null || world == null || target == null) {
            return null;
        }
        BlockPos current = bot.getBlockPos();
        int targetStandY = target.getY() - 1;

        // If already close enough in XZ (within 2 blocks), use current position
        double currentHorizSq = squaredHorizontalDistance(current, target);
        if (currentHorizSq <= ConstructionPlacementRules.CLOSE_STANCE_HORIZONTAL_DISTANCE_SQ
            && canStandAt(world, current)
            && hasScaffoldHeadroom(world, current, targetStandY)) {
            return current;
        }

        // Priority 1: cardinal adjacent (offset 1 from target XZ)
        // Score by travel distance and open exits — no protection check
        BlockPos bestAdjacent = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = new BlockPos(
                    target.getX() + dir.getOffsetX(), current.getY(), target.getZ() + dir.getOffsetZ());
            if (!canStandAt(world, candidate) || !hasScaffoldHeadroom(world, candidate, targetStandY)) {
                continue;
            }
            double travelSq = current.getSquaredDistance(candidate);
            double score = 220.0 - travelSq + countOpenExits(world, candidate) * 15.0;
            if (score > bestScore) {
                bestScore = score;
                bestAdjacent = candidate;
            }
        }
        if (bestAdjacent != null) {
            return bestAdjacent;
        }

        // Priority 2: diagonal adjacent (offset 1,1)
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                BlockPos candidate = new BlockPos(target.getX() + dx, current.getY(), target.getZ() + dz);
                if (canStandAt(world, candidate) && hasScaffoldHeadroom(world, candidate, targetStandY)) {
                    return candidate;
                }
            }
        }

        // Priority 3: directly below target (same XZ column — FortifyVillageSkill pattern)
        BlockPos below = new BlockPos(target.getX(), current.getY(), target.getZ());
        if (canStandAt(world, below) && hasScaffoldHeadroom(world, below, targetStandY)) {
            return below;
        }

        // Priority 4: radius 2
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    continue;
                }
                BlockPos candidate = new BlockPos(target.getX() + dx, current.getY(), target.getZ() + dz);
                if (canStandAt(world, candidate) && hasScaffoldHeadroom(world, candidate, targetStandY)) {
                    return candidate;
                }
            }
        }

        // Last resort: use current position even if not ideal, if we can stand here
        // and are within pillar+reach distance (FortifyVillageSkill walks toward without
        // strict stance — the pillar will bring us to the right height)
        if (canStandAt(world, current)
                && hasScaffoldHeadroom(world, current, targetStandY)
                && currentHorizSq <= 16.0D) {
            return current;
        }

        return null;
    }

    private static boolean hasScaffoldHeadroom(ServerWorld world, BlockPos stance, int targetStandY) {
        if (world == null || stance == null) {
            return false;
        }
        int topY = Math.max(stance.getY() + 2, targetStandY + 1);
        for (int y = stance.getY() + 2; y <= topY; y++) {
            BlockPos check = new BlockPos(stance.getX(), y, stance.getZ());
            BlockState state = world.getBlockState(check);
            if (!state.isAir() && !state.isReplaceable()) {
                return false;
            }
        }
        return true;
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

        if (tryMoveToReachStance(source, bot, target, passNumber, reachDistanceSq)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        }

        boolean targetProtected = isProtectedMoveTarget(bot, target);
        if (!targetProtected && moveTo(source, bot, target)) {
            progress = true;
            if (isWithinReach(bot, target, reachDistanceSq)) {
                return RecoveryResult.success(true);
            }
        } else if (targetProtected && LOGGER.isInfoEnabled()) {
            LOGGER.info("task-recovery: skipping direct move into protected target={} botPos={}",
                    target.toShortString(),
                    bot.getBlockPos().toShortString());
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
                if (isProtectedMoveTarget(bot, side)) {
                    continue;
                }
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
        // Use Entity.squaredDistanceTo to match BotActions.tryPlaceBlockAt reach gate.
        // Eye-based checks caused false positives for blocks above/below the bot.
        return bot.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reachDistanceSq;
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

    private static boolean tryMoveToReachStance(ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                BlockPos target,
                                                int passNumber,
                                                double reachDistanceSq) {
        if (source == null || bot == null || target == null || passNumber < 1) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        BlockPos stance = findBestReachStance(bot, world, target, reachDistanceSq);
        if (stance == null || stance.equals(bot.getBlockPos())) {
            return false;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("task-recovery: stance approach target={} stance={} botPos={}",
                    target.toShortString(),
                    stance.toShortString(),
                    bot.getBlockPos().toShortString());
        }

        return moveTo(source, bot, stance);
    }

    private static BlockPos findBestReachStance(ServerPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos target,
                                                double reachDistanceSq) {
        if (bot == null || world == null || target == null) {
            return null;
        }

        UUID botId = bot.getUuid();
        BlockPos botPos = bot.getBlockPos();
        double currentTargetDistSq = bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target));
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int radius = 1; radius <= MAX_REACH_STANCE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    if (dx == 0 && dz == 0) {
                        continue;
                    }

                    BlockPos candidate = findStandableStanceAtColumn(botId, world, target, botPos, dx, dz);
                    if (candidate == null) {
                        continue;
                    }

                    int exits = countOpenExits(world, candidate);
                    if (exits < 1) {
                        continue;
                    }

                    double candidateDistSq = squaredEyeDistance(candidate, target);
                    double towardGain = currentTargetDistSq - candidateDistSq;
                    double score = exits * 70.0;
                    score += towardGain * 5.0;
                    score -= botPos.getSquaredDistance(candidate) * 5.5;
                    if (candidateDistSq <= reachDistanceSq) {
                        score += 280.0;
                    }
                    if (candidate.getY() <= target.getY() - 1) {
                        score += 18.0;
                    }
                    if (radius >= 2) {
                        score += 8.0;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }

        return best;
    }

    private static BlockPos findStandableStanceAtColumn(UUID botId,
                                                        ServerWorld world,
                                                        BlockPos target,
                                                        BlockPos botPos,
                                                        int dx,
                                                        int dz) {
        if (world == null || target == null || botPos == null) {
            return null;
        }

        int x = target.getX() + dx;
        int z = target.getZ() + dz;
        int startY = Math.max(botPos.getY() + 2, target.getY());
        int minY = Math.max(world.getBottomY() + 1, Math.min(botPos.getY(), target.getY()) - MAX_REACH_STANCE_DROP);
        for (int y = startY; y >= minY; y--) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (!canStandAt(world, candidate)) {
                continue;
            }
            if (isProtectedStance(botId, candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static boolean isProtectedStance(UUID botId, BlockPos candidate) {
        if (botId == null || candidate == null) {
            return false;
        }
        return ConstructionProtectionService.isProtected(botId, candidate)
                || ConstructionProtectionService.isProtected(botId, candidate.down())
                || ConstructionProtectionService.isProtected(botId, candidate.up());
    }

    private static boolean isProtectedMoveTarget(ServerPlayerEntity bot, BlockPos candidate) {
        if (bot == null || candidate == null) {
            return false;
        }
        return isProtectedStance(bot.getUuid(), candidate);
    }

    private static double squaredEyeDistance(BlockPos fromFeet, BlockPos target) {
        if (fromFeet == null || target == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3d fromEye = new Vec3d(fromFeet.getX() + 0.5D, fromFeet.getY() + 1.62D, fromFeet.getZ() + 0.5D);
        return fromEye.squaredDistanceTo(Vec3d.ofCenter(target));
    }

    private static double squaredHorizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
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
