package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

import static net.wcfcarolina13.GameAI.skills.impl.FortifySkillOps.*;

/**
 * Carve / break-through navigation logic extracted verbatim from
 * {@link FortifyVillageSkill}: last-resort obstacle mining, the carve-mode
 * eligibility rules, deferred carve repairs, and the carve-transaction
 * finalizer.  Calls back into skill primitives through
 * {@link FortifySkillOps.FortifyNavOps} and
 * {@link FortifySkillOps.FortifyCarveContext}.
 */
final class FortifyCarveHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-carve");

    // ── Constants (moved from FortifyVillageSkill) ─────────────
    private static final int FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST = 6;
    private static final int FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST = 8;
    private static final int FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST = 20;
    private static final int FORTIFY_TRAP_CARVE_DEPTH_LIMIT = 6;

    private final FortifySkillOps.FortifyNavOps ops;
    private final FortifySkillOps.FortifyCarveContext ctx;
    private final FortifyEntombmentHelper entombmentHelper;
    private final FortifyCleanupHelper cleanupHelper;
    private final FortifyCleanupProcessor cleanupProcessor;
    private final Supplier<Set<BlockPos>> protectedPositions;

    FortifyCarveHelper(FortifySkillOps.FortifyNavOps ops,
                       FortifySkillOps.FortifyCarveContext ctx,
                       FortifyEntombmentHelper entombmentHelper,
                       FortifyCleanupHelper cleanupHelper,
                       FortifyCleanupProcessor cleanupProcessor,
                       Supplier<Set<BlockPos>> protectedPositions) {
        this.ops = Objects.requireNonNull(ops);
        this.ctx = Objects.requireNonNull(ctx);
        this.entombmentHelper = Objects.requireNonNull(entombmentHelper);
        this.cleanupHelper = Objects.requireNonNull(cleanupHelper);
        this.cleanupProcessor = Objects.requireNonNull(cleanupProcessor);
        this.protectedPositions = Objects.requireNonNull(protectedPositions);
    }

    private void incrementNavBreakReject(Map<NavBreakRejectReason, Integer> counts, NavBreakCandidateEval eval) {
        if (counts == null || eval == null || eval.allowed() || eval.rejectReason() == null) {
            return;
        }
        counts.merge(eval.rejectReason(), 1, Integer::sum);
    }

    private String formatNavBreakRejectSummary(Map<NavBreakRejectReason, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<NavBreakRejectReason, Integer> e : counts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Last-resort navigation: mine one blocking block between the bot and its target,
     * walk through the gap, then replace the mined block.
     * Returns true if the bot moved to a new position.
     *
     * <p>Two-tier safety: prefers non-layout blocks first, then allows breaking
     * fortification layout blocks (with mandatory replacement).
     * Max one break-through per call to prevent tunnel-mining.
     */
    boolean tryBreakThroughObstacle(ServerPlayerEntity bot, ServerWorld world, BlockPos target, String causeContext) {
        BlockPos botPos = bot.getBlockPos();
        FortifyNavRuntimeScope scope = ops.getActiveFortifyNavScope();

        // Guardrail: if the bot is already below the local terrain surface, bail to surface
        // recovery instead of tunneling horizontally underground.
        int terrainY = VillageFortificationLayoutService.terrainY(world, botPos.getX(), botPos.getZ());
        if (botPos.getY() < terrainY - 1) {
            int depth = terrainY - botPos.getY();
            String effectiveCauseContext = causeContext != null ? causeContext : (scope != null ? scope.context : null);
            boolean scopeFortifyContext = scope != null && ops.isFortifyCarveContext(scope);
            boolean inferredFortifyContext = scope == null
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-edge:") || effectiveCauseContext.startsWith("fortify-tower:"))
                    && ctx.hasCurrentFortificationLayout();
            boolean fortifyContext = scopeFortifyContext || inferredFortifyContext;
            boolean trapLike = ctx.isTrapLikeCell(world, botPos);
            boolean shallowDepth = depth <= 3;
            boolean trapPocketDepth = depth <= FORTIFY_TRAP_CARVE_DEPTH_LIMIT;
            boolean insideOrAdjacentHull = entombmentHelper.isAdjacentToCurrentFortificationHull(botPos);
            int surfaceEscapePocketFailures = entombmentHelper.getSurfaceEscapeRetryFailureCount(botPos, terrainY);
            boolean repeatedSurfaceEscapePocket = entombmentHelper.shouldSkipRepeatedSurfaceEscape(botPos, terrainY);
            boolean noProgressPocketBurst = entombmentHelper.hasFortifyPocketNoProgressBurst(world, botPos, effectiveCauseContext, 2);
            boolean towerTrapPocketContext = fortifyContext
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-tower:approach")
                    || effectiveCauseContext.startsWith("fortify-tower:local-step-replan")
                    || effectiveCauseContext.startsWith("fortify-tower:hard-reset")
                    || effectiveCauseContext.startsWith("fortify-tower:scaffold-base")
                    || effectiveCauseContext.startsWith("fortify-tower:unwedge"));
            boolean edgeTrapPocketContext = fortifyContext
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-edge:approach")
                    || effectiveCauseContext.startsWith("fortify-edge:approach-close")
                    || effectiveCauseContext.startsWith("fortify-edge:approach-retry")
                    || effectiveCauseContext.startsWith("fortify-edge:unwedge")
                    || effectiveCauseContext.startsWith("fortify-edge:patch-start-preflight"));
            boolean towerTrapPocketImmediateOverride = towerTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (depth >= 4 || surfaceEscapePocketFailures >= 1);
            boolean edgeTrapPocketImmediateOverride = edgeTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && surfaceEscapePocketFailures >= 1;
            boolean towerLocalTrapPocketOverride = fortifyContext
                    && (towerTrapPocketImmediateOverride
                    || (towerTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (repeatedSurfaceEscapePocket || noProgressPocketBurst)));
            boolean edgeLocalTrapPocketOverride = fortifyContext
                    && (edgeTrapPocketImmediateOverride
                    || (edgeTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (repeatedSurfaceEscapePocket || noProgressPocketBurst)));
            boolean fortifyTrapEntombment = fortifyContext
                    && trapLike
                    && ((shallowDepth && insideOrAdjacentHull) || towerLocalTrapPocketOverride || edgeLocalTrapPocketOverride);
            if (fortifyTrapEntombment) {
                LOGGER.info("[FortifyNav] Bot below surface at {} vs terrainY={} but trap-like fortify entombment detected (depth={} hullAdj={} surfaceEscapeFailures={} repeatedSurfaceEscape={} noProgressBurst={} towerOverride={} edgeOverride={}), allowing emergency carve",
                        botPos.toShortString(), terrainY, depth, insideOrAdjacentHull,
                        surfaceEscapePocketFailures,
                        repeatedSurfaceEscapePocket, noProgressPocketBurst,
                        towerLocalTrapPocketOverride, edgeLocalTrapPocketOverride);
            } else {
                List<String> rejectReasons = new ArrayList<>();
                if (!fortifyContext) {
                    rejectReasons.add(scope == null ? "scopeMissing" : "notFortifyContext");
                }
                if (!trapLike) rejectReasons.add("notTrapLike");
                if (!shallowDepth && !towerLocalTrapPocketOverride && !edgeLocalTrapPocketOverride) rejectReasons.add("depthTooHigh");
                if (!insideOrAdjacentHull && !towerLocalTrapPocketOverride && !edgeLocalTrapPocketOverride) rejectReasons.add("outsideHull");
                if (surfaceEscapePocketFailures <= 0) rejectReasons.add("noSurfaceEscapeFailures");
                if (!repeatedSurfaceEscapePocket) rejectReasons.add("noRepeatedSurfaceEscape");
                if (!noProgressPocketBurst) rejectReasons.add("noProgressBurst");
                if (rejectReasons.isEmpty()) {
                    rejectReasons.add("fallbackSurfaceEscape");
                }
                BlockPos beforeSurfaceEscape = bot.getBlockPos();
                ops.ensureOnSurface(bot, world, terrainY);
                BlockPos afterSurfaceEscape = bot.getBlockPos();
                boolean escaped = afterSurfaceEscape.getY() >= terrainY - 1 || !beforeSurfaceEscape.equals(afterSurfaceEscape);
                if (!escaped) {
                    entombmentHelper.noteEntombmentSurfaceEscapeFailure(world, beforeSurfaceEscape, causeContext);
                } else {
                    entombmentHelper.noteEntombmentRecoverySuccess(world, beforeSurfaceEscape, afterSurfaceEscape, causeContext);
                }
                LOGGER.info("[FortifyNav] Bot below surface at {} vs terrainY={}, invoking surface escape instead of carving rejects={} escaped={} moved={}",
                        botPos.toShortString(), terrainY, String.join("|", rejectReasons), escaped, !beforeSurfaceEscape.equals(afterSurfaceEscape));
                return escaped;
            }
        }
        boolean carveMode = scope != null
                && scope.navMode == FortifyNavMode.CARVE_CORRIDOR
                && isCarveEligibleForBreakAttempt(scope, world, botPos, target);
        FortifyCarveSession carveSession = (scope != null) ? scope.carveSession : null;
        boolean emergencyTrapSearch = carveMode && isEmergencyTrapEscapeEligible(scope, world, botPos);
        List<BlockPos> candidateOffsets = buildBreakThroughCandidateOffsets(botPos, target, emergencyTrapSearch);
        boolean towerExteriorGuard = scope != null && scope.towerPatchContext;

        // Diagnostic counters for the "no viable candidates" case
        int diagAllAir = 0, diagCanBreak = 0, diagReach = 0, diagNoFloor = 0, diagRecentCooldown = 0;
        Map<NavBreakRejectReason, Integer> rejectReasonCounts = new EnumMap<>(NavBreakRejectReason.class);

        boolean allowGateLayoutOverride = scope != null && scope.gateContext && ctx.isInsideCurrentFortificationHull(botPos);
        String replaceContext = scope != null && scope.context != null
                ? scope.context
                : (causeContext != null ? causeContext : "fortify-nav");

        // Two passes: first try non-layout blocks, then allow layout blocks
        for (int pass = 0; pass < 2; pass++) {
            boolean allowLayout = (pass == 1);
            if (pass == 1) {
                diagAllAir = 0;
                diagCanBreak = 0;
                diagReach = 0;
                diagNoFloor = 0;
                diagRecentCooldown = 0;
                rejectReasonCounts.clear();
            }

            for (BlockPos offset : candidateOffsets) {
                BlockPos feetPos = botPos.add(offset);
                BlockPos headPos = feetPos.up();
                BlockPos overheadPos = headPos.up(); // Y+2: clear overhead for tall walls

                if (entombmentHelper.isRecentCarveColumnOnCooldown(feetPos)) {
                    diagRecentCooldown++;
                    continue;
                }

                boolean feetBlocking = !world.getBlockState(feetPos).getCollisionShape(world, feetPos).isEmpty();
                boolean headBlocking = !world.getBlockState(headPos).getCollisionShape(world, headPos).isEmpty();
                if (!feetBlocking && !headBlocking) {
                    diagAllAir++;
                    if (!emergencyTrapSearch) {
                        continue;
                    }
                    if (offset.getY() == 0) {
                        // Guardrail: in trap carve mode, never count same-level air candidates as break-through success.
                        // They caused A<->B oscillation and masked the actual tunnel opportunity.
                        if (!ctx.canStandAt(world, feetPos)) {
                            diagNoFloor++;
                        }
                        continue;
                    }
                    if (!ctx.canStandAt(world, feetPos)) {
                        diagNoFloor++;
                        continue;
                    }

                    BlockPos walkTarget = buildBreakThroughWalkTarget(botPos, offset);
                    BlockPos before = bot.getBlockPos();
                    ops.walkTowardBlock(bot, walkTarget, 1_200L);
                    ops.sleepQuiet(100);

                    boolean moved = !before.equals(bot.getBlockPos());
                    if (moved && !isMeaningfulTrapEscapeProgress(world, before, bot.getBlockPos(), target)) {
                        LOGGER.info("[FortifyNav] trap-step false-progress ctx={} before={} after={} target={} offset={}",
                                scope != null ? scope.context : "n/a",
                                before.toShortString(),
                                bot.getBlockPos().toShortString(),
                                target != null ? target.toShortString() : "n/a",
                                offset.toShortString());
                        moved = false;
                    }
                    if (moved) {
                        LOGGER.info("[FortifyNav] Trap step escape success, moved from {} to {}",
                                before.toShortString(), bot.getBlockPos().toShortString());
                        if (scope != null && carveMode && scope.carveSession != null) {
                            scope.carveSession.completed = true;
                            scope.carveSession.crossed = true;
                            scope.carveSession.touch();
                        }
                        return true;
                    }
                    continue;
                }

                if (emergencyTrapSearch && offset.getY() < 0) {
                    // Guardrail: "tunneling" means opening through the obstacle in front (wall opening),
                    // not excavating downward. Step-down is allowed only when already open+standable
                    // (handled in the all-air branch above), never by mining below the current floor.
                    continue;
                }

                // Check safety: tier 1 (non-layout) or tier 2 (layout with mandatory replace)
                if (feetBlocking) {
                    NavBreakCandidateEval feetEval = ops.evaluateBreakForNavigation(world, feetPos, allowLayout);
                    if (!feetEval.allowed() && allowGateLayoutOverride && protectedPositions.get().contains(feetPos)) {
                        feetEval = new NavBreakCandidateEval(true, null);
                    }
                    if (!feetEval.allowed() && canOverrideVillageAdjacentForCarve(scope, world, botPos, feetPos, target, carveSession, feetEval)) {
                        feetEval = new NavBreakCandidateEval(true, null);
                    }
                    if (towerExteriorGuard && protectedPositions.get().contains(feetPos)
                            && !ctx.isLayoutExteriorReachable(world, feetPos)) {
                        feetEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                    }
                    if (!feetEval.allowed()) {
                        diagCanBreak++;
                        incrementNavBreakReject(rejectReasonCounts, feetEval);
                        continue;
                    }
                }
                if (headBlocking) {
                NavBreakCandidateEval headEval = ops.evaluateBreakForNavigation(world, headPos, allowLayout);
                if (!headEval.allowed() && allowGateLayoutOverride && protectedPositions.get().contains(headPos)) {
                    headEval = new NavBreakCandidateEval(true, null);
                }
                if (!headEval.allowed() && canOverrideVillageAdjacentForCarve(scope, world, botPos, headPos, target, carveSession, headEval)) {
                    headEval = new NavBreakCandidateEval(true, null);
                    }
                    if (towerExteriorGuard && protectedPositions.get().contains(headPos)
                            && !ctx.isLayoutExteriorReachable(world, headPos)) {
                        headEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                    }
                    if (!headEval.allowed()) {
                        diagCanBreak++;
                        incrementNavBreakReject(rejectReasonCounts, headEval);
                        continue;
                    }
                }

                if (feetBlocking && !ops.isWithinMiningReach(bot, feetPos)) { diagReach++; continue; }
                if (headBlocking && !ops.isWithinMiningReach(bot, headPos)) { diagReach++; continue; }

                // Overhead is best-effort: mine if blocking AND reachable, soft-skip otherwise
                boolean overheadBlocking = !world.getBlockState(overheadPos).getCollisionShape(world, overheadPos).isEmpty();
                NavBreakCandidateEval overheadEval = overheadBlocking
                        ? ops.evaluateBreakForNavigation(world, overheadPos, allowLayout)
                        : new NavBreakCandidateEval(false, NavBreakRejectReason.AIR_OR_REPLACEABLE);
                if (overheadBlocking && !overheadEval.allowed() && allowGateLayoutOverride && protectedPositions.get().contains(overheadPos)) {
                    overheadEval = new NavBreakCandidateEval(true, null);
                }
                if (overheadBlocking && !overheadEval.allowed()
                        && canOverrideVillageAdjacentForCarve(scope, world, botPos, overheadPos, target, carveSession, overheadEval)) {
                    overheadEval = new NavBreakCandidateEval(true, null);
                }
                if (overheadBlocking && towerExteriorGuard && protectedPositions.get().contains(overheadPos)
                        && !ctx.isLayoutExteriorReachable(world, overheadPos)) {
                    overheadEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                }
                boolean mineOverhead = overheadBlocking
                        && overheadEval.allowed()
                        && ops.isWithinMiningReach(bot, overheadPos);

                // Must be able to stand on the block below
                BlockState belowState = world.getBlockState(feetPos.down());
                if (belowState.getCollisionShape(world, feetPos.down()).isEmpty()) { diagNoFloor++; continue; }

                boolean anyLayoutBreak = (feetBlocking && protectedPositions.get().contains(feetPos))
                        || (headBlocking && protectedPositions.get().contains(headPos))
                        || (mineOverhead && protectedPositions.get().contains(overheadPos));

                LOGGER.info("[FortifyNav] Breaking through {} at {} (head={} overhead={})",
                        anyLayoutBreak ? "WALL" : "obstruction",
                        feetPos.toShortString(),
                        headBlocking ? headPos.toShortString() : "clear",
                        mineOverhead ? overheadPos.toShortString() : "skip");

                BlockState feetOriginal = feetBlocking ? world.getBlockState(feetPos) : null;
                BlockState headOriginal = headBlocking ? world.getBlockState(headPos) : null;
                BlockState overheadOriginal = mineOverhead ? world.getBlockState(overheadPos) : null;
                boolean feetMandatory = feetOriginal != null && protectedPositions.get().contains(feetPos);
                boolean headMandatory = headOriginal != null && protectedPositions.get().contains(headPos);
                boolean overheadMandatory = overheadOriginal != null && protectedPositions.get().contains(overheadPos);
                boolean preferHeadFirstEscapeOrder = emergencyTrapSearch
                        || (scope != null && scope.towerPatchContext && ctx.isTrapLikeCell(world, botPos));

                // Emergency entombment escape (learned demo pattern) prefers head-first opening,
                // then overhead, then feet. This reduces self-trapping while creating a passable slot.
                boolean overheadMined = false;
                boolean headMined = true;
                if (preferHeadFirstEscapeOrder && headBlocking) {
                    headMined = ops.digBlockForNavigation(bot, world, headPos);
                } else if (!preferHeadFirstEscapeOrder && mineOverhead) {
                    if (!ops.digBlockForNavigation(bot, world, overheadPos)) {
                        // Soft-skip: overhead failure doesn't reject this candidate
                        mineOverhead = false;
                        overheadOriginal = null;
                    } else {
                        overheadMined = true;
                    }
                }
                if (!headMined && overheadOriginal != null && overheadMined) {
                    ReplaceBlockResult rollback = cleanupProcessor.tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                    if (!rollback.success()) {
                        cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                    }
                }
                if (!headMined) {
                    cleanupProcessor.verifyCarveRepairColumn(bot, world,
                            feetPos, feetOriginal, feetMandatory,
                            headPos, headOriginal, headMandatory,
                            overheadPos, overheadOriginal, overheadMandatory,
                            replaceContext);
                    continue;
                }
                if (preferHeadFirstEscapeOrder && mineOverhead) {
                    if (!ops.digBlockForNavigation(bot, world, overheadPos)) {
                        mineOverhead = false;
                        overheadOriginal = null;
                    } else {
                        overheadMined = true;
                    }
                }
                if (!preferHeadFirstEscapeOrder && headBlocking) {
                    headMined = ops.digBlockForNavigation(bot, world, headPos);
                    if (!headMined && overheadOriginal != null && overheadMined) {
                        ReplaceBlockResult rollback = cleanupProcessor.tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        if (!rollback.success()) {
                            cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        }
                    }
                    if (!headMined) {
                        cleanupProcessor.verifyCarveRepairColumn(bot, world,
                                feetPos, feetOriginal, feetMandatory,
                                headPos, headOriginal, headMandatory,
                                overheadPos, overheadOriginal, overheadMandatory,
                                replaceContext);
                        continue;
                    }
                }

                boolean feetMined = true;
                if (feetBlocking) {
                    feetMined = ops.digBlockForNavigation(bot, world, feetPos);
                    if (!feetMined && headOriginal != null && headMined) {
                        ReplaceBlockResult rollback = cleanupProcessor.tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                        if (!rollback.success()) {
                            cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                        }
                    }
                    if (!feetMined && overheadOriginal != null && overheadMined) {
                        ReplaceBlockResult rollback = cleanupProcessor.tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        if (!rollback.success()) {
                            cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        }
                    }
                }
                if (!feetMined) {
                    cleanupProcessor.verifyCarveRepairColumn(bot, world,
                            feetPos, feetOriginal, feetMandatory,
                            headPos, headOriginal, headMandatory,
                            overheadPos, overheadOriginal, overheadMandatory,
                            replaceContext);
                    continue;
                }

                // Walk through the gap toward a point 4 blocks past the bot in the break direction.
                // walkTowardBlock bails at distSq < 6.0, so feetPos (1 block) is too close.
                // 4 blocks gives distSq >= 16 which reliably runs the walk loop.
                BlockPos walkTarget = buildBreakThroughWalkTarget(botPos, offset);
                BlockPos before = bot.getBlockPos();
                ops.walkTowardBlock(bot, walkTarget, 900L);
                ops.sleepQuiet(60);

                boolean moved = !before.equals(bot.getBlockPos());
                if (moved && emergencyTrapSearch && !isMeaningfulTrapEscapeProgress(world, before, bot.getBlockPos(), target)) {
                    LOGGER.info("[FortifyNav] trap-carve false-progress ctx={} before={} after={} target={} offset={}",
                            scope != null ? scope.context : "n/a",
                            before.toShortString(),
                            bot.getBlockPos().toShortString(),
                            target != null ? target.toShortString() : "n/a",
                            offset.toShortString());
                    moved = false;
                }

                boolean deferRepair = carveMode && scope != null;
                if (deferRepair) {
                    deferCarveRepair(scope, feetPos, feetOriginal, feetMandatory);
                    deferCarveRepair(scope, headPos, headOriginal, headMandatory);
                    deferCarveRepair(scope, overheadPos, overheadOriginal, overheadMandatory);
                } else {
                    // Replace mined blocks — but only mandatory (fortification) blocks.
                    // Non-mandatory blocks are natural terrain broken during navigation;
                    // backfilling them blocks the bot's own escape/travel path.
                    boolean replaceTopDown = emergencyTrapSearch || (moved && preferHeadFirstEscapeOrder);
                    if (replaceTopDown) {
                        if (overheadOriginal != null && overheadMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            }
                        }
                        if (headOriginal != null && headMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            }
                        }
                        if (feetOriginal != null && feetMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            }
                        }
                    } else {
                        if (feetOriginal != null && feetMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            }
                        }
                        if (headOriginal != null && headMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            }
                        }
                        if (overheadOriginal != null && overheadMandatory) {
                            ReplaceBlockResult result = cleanupProcessor.tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            if (!result.success()) {
                                cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            }
                        }
                    }
                }

                if (moved) {
                    LOGGER.info("[FortifyNav] Break-through success, moved from {} to {}",
                            before.toShortString(), bot.getBlockPos().toShortString());
                    if (scope != null && carveMode && scope.carveSession != null) {
                        scope.carveSession.completed = true;
                        scope.carveSession.crossed = true;
                        scope.carveSession.touch();
                    }
                }

                // Ensure all layout blocks mined during this attempt are either replaced immediately
                // or queued for deferred repair so no hole remains.
                if (deferRepair && scope != null && scope.carveSession != null && !scope.carveSession.deferredRepairs.isEmpty()) {
                    cleanupHelper.queueCarveRepairs(scope, scope.carveSession, new ArrayList<>(scope.carveSession.deferredRepairs));
                }
                cleanupProcessor.verifyCarveRepairColumn(bot, world,
                        feetPos, feetOriginal, feetMandatory,
                        headPos, headOriginal, headMandatory,
                        overheadPos, overheadOriginal, overheadMandatory,
                        replaceContext);

                // Cooldown this carve column to avoid back-and-forth re-carving.
                if (feetPos != null) {
                    entombmentHelper.noteRecentCarveColumn(feetPos);
                }
                return moved;
            }
        }

        boolean villageDominated = rejectReasonCounts.getOrDefault(NavBreakRejectReason.VILLAGE_ADJACENT, 0) > 0;
        boolean trapLike = ctx.isTrapLikeCell(world, botPos);
        if (scope != null && scope.towerState != null) {
            scope.towerState.noteBreakRejects(rejectReasonCounts);
            boolean emergencyEligible = isEmergencyTrapEscapeEligible(scope, world, botPos);
            boolean gateVillageDeadlock = scope.gateContext
                    && villageDominated
                    && isCarveEligibleScope(scope, botPos, target);
            boolean trapEmergencyReady = emergencyEligible && villageDominated
                    && (scope.towerState.shouldActivateCarveMode()
                    || scope.towerState.sameStuckPosCount >= 1
                    || scope.towerState.villageAdjacentRejectBursts >= 1);
            if (gateVillageDeadlock && scope.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] gate-carve-activated ctx={} target={} sameStuck={} noRealProgressMs={} villageAdjBursts={}",
                        scope.context, target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.noRealProgressElapsedMs(),
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (trapEmergencyReady && scope.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] trap-detected ctx={} pos={} topology={} target={} emergencyEscape=activating sameStuck={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (scope.towerState.shouldActivateCarveMode() && isCarveEligibleScope(scope, botPos, target)) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] Carve mode activated ctx={} target={} sameStuck={} noRealProgressMs={} villageAdjBursts={}",
                        scope.context, target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.noRealProgressElapsedMs(),
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (scope.towerState.shouldActivateCarveMode() && !isCarveEligibleScope(scope, botPos, target) && !emergencyEligible) {
                LOGGER.info("[FortifyNav] Carve mode suppressed ctx={} target={} dist={} (local-only)",
                        scope.context, target.toShortString(),
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(botPos.getSquaredDistance(target))));
            }
        } else if (scope != null && ops.isFortifyCarveContext(scope)) {
            if (villageDominated) {
                if (scope.lastTrapRejectPos != null && scope.lastTrapRejectPos.equals(botPos)) {
                    scope.sameTrapRejectPosCount++;
                } else {
                    scope.lastTrapRejectPos = botPos.toImmutable();
                    scope.sameTrapRejectPosCount = 1;
                }
                if (trapLike) {
                    scope.localTrapRejectBursts++;
                } else {
                    scope.localTrapRejectBursts = Math.max(0, scope.localTrapRejectBursts - 1);
                }
            } else {
                scope.localTrapRejectBursts = Math.max(0, scope.localTrapRejectBursts - 1);
            }

            boolean emergencyEligible = isEmergencyTrapEscapeEligible(scope, world, botPos);
            boolean gateVillageDeadlock = scope.gateContext
                    && villageDominated
                    && isCarveEligibleScope(scope, botPos, target);
            if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR && gateVillageDeadlock) {
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] gate-carve-activated ctx={} pos={} topology={} target={} samePos={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.sameTrapRejectPosCount,
                        scope.localTrapRejectBursts);
            } else if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR && emergencyEligible
                    && villageDominated && (scope.sameTrapRejectPosCount >= 1 || scope.localTrapRejectBursts >= 1)) {
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] trap-detected ctx={} pos={} topology={} target={} emergencyEscape=activating samePos={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.sameTrapRejectPosCount,
                        scope.localTrapRejectBursts);
            }
        }

        LOGGER.info("[FortifyNav] Break-through found no viable candidates at {} toward {} " +
                        "(allAir={} canBreak={} reach={} noFloor={} recentCooldown={} offsets={} rejectReasons={})",
                botPos.toShortString(), target.toShortString(),
                diagAllAir, diagCanBreak, diagReach, diagNoFloor, diagRecentCooldown, candidateOffsets.size(),
                formatNavBreakRejectSummary(rejectReasonCounts));
        return false;
    }

    private BlockPos buildBreakThroughWalkTarget(BlockPos botPos, BlockPos offset) {
        if (botPos == null || offset == null) {
            return botPos;
        }
        int maxAbs = Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ()));
        int scale = maxAbs >= 2 ? 2 : 4;
        return new BlockPos(
                botPos.getX() + offset.getX() * scale,
                botPos.getY() + offset.getY(),
                botPos.getZ() + offset.getZ() * scale);
    }

    private boolean isMeaningfulTrapEscapeProgress(ServerWorld world, BlockPos before, BlockPos after, BlockPos target) {
        if (world == null || before == null || after == null || before.equals(after)) {
            return false;
        }
        VoxelJunctionService.VoxelStandCell beforeCell = VoxelJunctionService.analyzeStandCell(world, before);
        VoxelJunctionService.VoxelStandCell afterCell = VoxelJunctionService.analyzeStandCell(world, after);

        double netDisp = Math.sqrt(before.getSquaredDistance(after));
        double distDelta = 0.0D;
        if (target != null) {
            distDelta = Math.sqrt(before.getSquaredDistance(target)) - Math.sqrt(after.getSquaredDistance(target));
        }

        boolean topologyImproved = afterCell.openFaces() > beforeCell.openFaces()
                || (!ctx.isTrapLikeCell(world, after) && ctx.isTrapLikeCell(world, before))
                || (afterCell.topology() != beforeCell.topology()
                && afterCell.topology() != VoxelJunctionService.CellTopology.POCKET
                && afterCell.topology() != VoxelJunctionService.CellTopology.DEAD_END);

        if (topologyImproved && netDisp >= 1.0D) {
            return true;
        }
        return distDelta >= 1.5D || netDisp >= 2.0D;
    }

    private boolean canOverrideVillageAdjacentForCarve(FortifyNavRuntimeScope scope,
                                                       ServerWorld world,
                                                       BlockPos botPos,
                                                       BlockPos blockPos,
                                                       BlockPos target,
                                                       FortifyCarveSession carveSession,
                                                       NavBreakCandidateEval eval) {
        if (scope == null || blockPos == null || botPos == null || target == null) return false;
        if (!ops.isFortifyCarveContext(scope)) return false;
        if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR) return false;
        boolean emergencyTrap = isEmergencyTrapEscapeEligible(scope, world, botPos);
        if (!emergencyTrap && !isCarveEligibleScope(scope, botPos, target)) return false;
        if (carveSession == null || !carveSession.canMineMore()) return false;
        if (eval == null || eval.allowed() || eval.rejectReason() != NavBreakRejectReason.VILLAGE_ADJACENT) return false;
        if (botPos.getSquaredDistance(blockPos) > (double) (FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST * FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST)) {
            return false;
        }
        if (!emergencyTrap
                && target.getSquaredDistance(blockPos) > (double) (FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST * FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST)) {
            return false;
        }
        return true;
    }

    private void deferCarveRepair(FortifyNavRuntimeScope scope, BlockPos pos, BlockState originalState, boolean mandatory) {
        if (scope == null || pos == null || originalState == null) return;
        if (scope.carveSession == null) {
            scope.carveSession = new FortifyCarveSession(scope.primaryTarget, scope.context);
        }
        FortifyCarveSession session = scope.carveSession;
        if (session.entryPos == null) {
            session.entryPos = pos.toImmutable();
        }
        session.touch();
        for (DeferredRepair existing : session.deferredRepairs) {
            if (existing.pos().equals(pos)) {
                return;
            }
        }
        if (!session.canMineMore()) {
            return;
        }
        session.deferredRepairs.add(new DeferredRepair(pos.toImmutable(), originalState, mandatory));
        session.blocksMined++;
    }

    private boolean isCarveEligibleScope(FortifyNavRuntimeScope scope, BlockPos botPos, BlockPos target) {
        if (scope == null) return false;
        if (!ops.isFortifyCarveContext(scope)) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (ctx.contains("long-range")) return false;
        BlockPos effectiveTarget = target != null ? target : scope.primaryTarget;
        if (botPos == null || effectiveTarget == null) return false;
        return botPos.getSquaredDistance(effectiveTarget)
                <= (double) (FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST * FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST);
    }


    private boolean isEmergencyTrapEscapeEligible(FortifyNavRuntimeScope scope, ServerWorld world, BlockPos botPos) {
        if (scope == null || world == null || botPos == null) return false;
        if (!ops.isFortifyCarveContext(scope)) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (ctx.contains("long-range")) return false;
        return this.ctx.isTrapLikeCell(world, botPos);
    }

    boolean isCarveEligibleForBreakAttempt(FortifyNavRuntimeScope scope, ServerWorld world, BlockPos botPos, BlockPos target) {
        return isCarveEligibleScope(scope, botPos, target) || isEmergencyTrapEscapeEligible(scope, world, botPos);
    }

    private List<BlockPos> buildBreakThroughCandidateOffsets(BlockPos botPos, BlockPos target, boolean emergencyTrapSearch) {
        LinkedHashSet<BlockPos> offsets = new LinkedHashSet<>();
        if (botPos != null && target != null) {
            int dx = target.getX() - botPos.getX();
            int dz = target.getZ() - botPos.getZ();
            int sx = Integer.signum(dx);
            int sz = Integer.signum(dz);
            
            if (sx != 0 && sz != 0) {
                offsets.add(new BlockPos(sx, 0, sz));
            }
            if (sx != 0) {
                offsets.add(new BlockPos(sx, 0, 0));
            }
            if (sz != 0) {
                offsets.add(new BlockPos(0, 0, sz));
            }
            
            // Add sideways offsets that still make progress along the major axis
            if (Math.abs(dx) >= Math.abs(dz) && sx != 0) {
                offsets.add(new BlockPos(sx, 0, 1));
                offsets.add(new BlockPos(sx, 0, -1));
            } else if (sz != 0) {
                offsets.add(new BlockPos(1, 0, sz));
                offsets.add(new BlockPos(-1, 0, sz));
            }
        }
        if (emergencyTrapSearch) {
            int[][] emergency = {
                    {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                    {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                    {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                    {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                    {1, 2}, {-1, 2}, {1, -2}, {-1, -2}
            };
            for (int[] e : emergency) {
                offsets.add(new BlockPos(e[0], 0, e[1]));
            }

            // Guardrail: allow step-up / step-down trap exits, but only for immediate neighbors.
            // This fixes dead-end pockets where same-Y candidates have no floor without reintroducing
            // the broken remote support-column behavior.
            List<BlockPos> verticalVariants = new ArrayList<>();
            for (BlockPos base : new ArrayList<>(offsets)) {
                if (base.getY() != 0) continue;
                if (Math.max(Math.abs(base.getX()), Math.abs(base.getZ())) > 1) continue;
                verticalVariants.add(new BlockPos(base.getX(), -1, base.getZ()));
                verticalVariants.add(new BlockPos(base.getX(), 1, base.getZ()));
            }
            for (BlockPos v : verticalVariants) {
                offsets.add(v);
            }
        }
        return new ArrayList<>(offsets);
    }



    private boolean shouldDeferCarveFinalize(FortifyNavRuntimeScope scope, ServerPlayerEntity bot, ServerWorld world) {
        if (scope == null || scope.carveSession == null || bot == null || world == null) return false;
        if (scope.carveSession.deferredRepairs.isEmpty()) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (!ctx.startsWith("fortify-tower:scaffold-base")) {
            return false;
        }
        if (!scope.carveSession.crossed && !scope.carveSession.completed) {
            return false;
        }
        if (scope.primaryTarget != null && bot.getBlockPos().getSquaredDistance(scope.primaryTarget) > 25.0D) {
            return true;
        }
        if (this.ctx.isTrapLikeCell(world, bot.getBlockPos())) {
            return true;
        }
        for (DeferredRepair repair : scope.carveSession.deferredRepairs) {
            if (repair == null || repair.pos() == null) continue;
            if (world.getBlockState(repair.pos()).isAir() && this.ctx.wouldRepairSealCurrentExit(bot, world, repair.pos())) {
                return true;
            }
        }
        return false;
    }

    void attemptFinalizeCarveTransaction(ServerPlayerEntity bot, ServerWorld world, FortifyNavRuntimeScope scope) {
        if (scope == null || scope.carveSession == null) return;
        FortifyCarveSession session = scope.carveSession;
        if (session.deferredRepairs.isEmpty()) return;

        List<DeferredRepair> pending = new ArrayList<>();
        for (DeferredRepair repair : session.deferredRepairs) {
            if (repair == null || repair.pos() == null || repair.state() == null) continue;
            // Already restored or filled by something else.
            if (!world.getBlockState(repair.pos()).isAir()) continue;
            pending.add(repair);
        }
        if (pending.isEmpty()) {
            session.deferredRepairs.clear();
            session.cleanupState = CleanupState.DONE;
            LOGGER.info("[FortifyNav] Carve repair ctx={} target={} mined={} state={} queued=0 (nothing pending)",
                    scope.context,
                    session.target != null ? session.target.toShortString() : "n/a",
                    session.blocksMined,
                    session.cleanupState);
            return;
        }

        if (shouldDeferCarveFinalize(scope, bot, world)) {
            cleanupHelper.queueCarveRepairs(scope, session, pending);
            session.deferredRepairs.clear();
            session.cleanupState = CleanupState.FAILED_QUEUED;
            LOGGER.info("[FortifyNav] Carve finalize deferred ctx={} target={} pending={} reason=still-trapped queued={} topology={} dist={}",
                    scope.context,
                    session.target != null ? session.target.toShortString() : "n/a",
                    pending.size(),
                    pending.size(),
                    VoxelJunctionService.analyzeStandCell(world, bot.getBlockPos()).topology(),
                    scope.primaryTarget != null
                            ? String.format(Locale.ROOT, "%.1f", Math.sqrt(bot.getBlockPos().getSquaredDistance(scope.primaryTarget)))
                            : "n/a");
            return;
        }

        BlockPos botPos = bot.getBlockPos();
        int reachableCount = 0;
        double minDist = Double.POSITIVE_INFINITY;
        BlockPos nearest = null;
        Vec3d eye = bot.getEyePos();
        for (DeferredRepair repair : pending) {
            BlockPos pos = repair.pos();
            double dist = Math.sqrt(botPos.getSquaredDistance(pos));
            if (dist < minDist) {
                minDist = dist;
                nearest = pos;
            }
            if (ops.isWithinReach(bot, pos) && ops.hasLineOfSight(world, bot, eye, pos)
                    && !botPos.equals(pos) && !botPos.up().equals(pos)) {
                reachableCount++;
            }
        }

        if (reachableCount < pending.size() && nearest != null && minDist <= FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST) {
            // Try a short reposition before giving up and queueing cleanup.
            BlockPos beforeStage = bot.getBlockPos();
            ops.walkTowardBlock(bot, nearest, 900L);
            if (!beforeStage.equals(bot.getBlockPos())) {
                botPos = bot.getBlockPos();
                eye = bot.getEyePos();
                reachableCount = 0;
                minDist = Double.POSITIVE_INFINITY;
                for (DeferredRepair repair : pending) {
                    BlockPos pos = repair.pos();
                    double dist = Math.sqrt(botPos.getSquaredDistance(pos));
                    if (dist < minDist) minDist = dist;
                    if (ops.isWithinReach(bot, pos) && ops.hasLineOfSight(world, bot, eye, pos)
                            && !botPos.equals(pos) && !botPos.up().equals(pos)) {
                        reachableCount++;
                    }
                }
            }
        }

        LOGGER.info("[FortifyNav] Carve repair posture ctx={} target={} pending={} reachable={} minDist={} crossed={} completed={}",
                scope.context,
                session.target != null ? session.target.toShortString() : "n/a",
                pending.size(),
                reachableCount,
                minDist == Double.POSITIVE_INFINITY ? "n/a" : String.format(Locale.ROOT, "%.1f", minDist),
                session.crossed, session.completed);

        int repaired = 0;
        List<DeferredRepair> unresolved = new ArrayList<>();
        pending.sort((a, b) -> {
            boolean aSeal = ctx.wouldRepairSealCurrentExit(bot, world, a != null ? a.pos() : null);
            boolean bSeal = ctx.wouldRepairSealCurrentExit(bot, world, b != null ? b.pos() : null);
            if (aSeal != bSeal) return aSeal ? 1 : -1; // seal-risk repairs last
            BlockPos botPosSort = bot.getBlockPos();
            double aDist = a == null || a.pos() == null ? Double.NEGATIVE_INFINITY : botPosSort.getSquaredDistance(a.pos());
            double bDist = b == null || b.pos() == null ? Double.NEGATIVE_INFINITY : botPosSort.getSquaredDistance(b.pos());
            return Double.compare(bDist, aDist); // farther repairs first
        });
        for (DeferredRepair repair : pending) {
            BlockPos pos = repair.pos();
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            if (ctx.wouldRepairSealCurrentExit(bot, world, pos)) {
                unresolved.add(repair);
                LOGGER.info("[FortifyNav] Carve repair skip pos={} reason=seal-risk exitsAfter=0", pos.toShortString());
                continue;
            }
            if (bot.getBlockPos().equals(pos) || bot.getBlockPos().up().equals(pos)) {
                unresolved.add(repair);
                continue;
            }
            if (!ops.isWithinReach(bot, pos) || !ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                unresolved.add(repair);
                continue;
            }
            ReplaceBlockResult replaceResult = cleanupProcessor.tryReplaceMinedBlock(bot, world, pos, repair.state(), repair.mandatory(), scope.context);
            if (!world.getBlockState(pos).isAir()) {
                repaired++;
            } else {
                if (!replaceResult.success() && repair.mandatory()) {
                    cleanupProcessor.queueMandatoryCarveRepairIfNeeded(bot, world, pos, repair.state(), true, scope.context);
                }
                unresolved.add(repair);
            }
        }

        int queued = 0;
        if (!unresolved.isEmpty()) {
            cleanupHelper.queueCarveRepairs(scope, session, unresolved);
            queued = unresolved.size();
        }
        session.deferredRepairs.clear();
        if (queued == 0) {
            session.cleanupState = CleanupState.DONE;
        } else if (repaired > 0) {
            session.cleanupState = CleanupState.PARTIAL;
        } else {
            session.cleanupState = CleanupState.FAILED_QUEUED;
        }
        LOGGER.info("[FortifyNav] Carve repair ctx={} target={} mined={} repaired={} queued={} state={} completed={}",
                scope.context,
                session.target != null ? session.target.toShortString() : "n/a",
                session.blocksMined, repaired, queued, session.cleanupState, session.completed);
    }

}
