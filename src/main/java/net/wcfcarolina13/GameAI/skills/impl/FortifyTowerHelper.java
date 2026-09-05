package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;
import net.wcfcarolina13.GameAI.services.construction.FortifyExecutionPolicyUtil;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.*;
import net.wcfcarolina13.GameAI.services.construction.execution.ExecutionPolicy;
import net.wcfcarolina13.GameAI.services.construction.execution.PlacementTarget;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Tower construction, scaffold, approach/escape and hard-reset logic extracted
 * verbatim from {@link FortifyVillageSkill}.  Calls back into skill primitives
 * through {@link FortifySkillOps.FortifyNavOps} and
 * {@link FortifySkillOps.FortifyTowerContext}.
 */
final class FortifyTowerHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-tower");

    // ── Constants (duplicated from FortifyVillageSkill; skill still uses its own copies) ──
    private static final double REACH_DISTANCE_SQ = 20.25D;
    private static final int BLOCK_PLACE_DELAY_MS = 50;
    private static final int MIN_APPROACH_OPEN_EXITS = 2;

    // ── Constants (moved from FortifyVillageSkill) ──────────────
    private static final long TOWER_VERTEX_TIME_BUDGET_MS = 45_000L;
    private static final int TOWER_LOCAL_MAX_ATTEMPTS = 6;
    private static final int TOWER_LOCAL_NO_PROGRESS_LIMIT = 3;
    private static final double TOWER_COMPLETION_TARGET_RATIO = 0.95D;
    private static final int TOWER_VERTEX_DEDUP_DISTANCE_SQ = 4; // <= 2 blocks in XZ
    private static final int TOWER_LOCAL_REACHABILITY_RADIUS = 16;
    private static final int TOWER_LOCAL_REACHABILITY_MAX_NODES = 1024;
    private static final long FORTIFY_MEDIUM_REPLAN_BUDGET_MS = 2_500L;

    private final FortifySkillOps.FortifyNavOps ops;
    private final FortifySkillOps.FortifyTowerContext ctx;
    private final FortifyEntombmentHelper entombmentHelper;
    private final FortifyCleanupHelper cleanupHelper;

    /** Tower vertices that yielded zero progress in the current auto-patch session — skip on next pass. */
    private final Set<Long> zeroProgressTowerVertices = new HashSet<>();

    FortifyTowerHelper(FortifySkillOps.FortifyNavOps ops,
                       FortifySkillOps.FortifyTowerContext ctx,
                       FortifyEntombmentHelper entombmentHelper,
                       FortifyCleanupHelper cleanupHelper) {
        this.ops = Objects.requireNonNull(ops);
        this.ctx = Objects.requireNonNull(ctx);
        this.entombmentHelper = Objects.requireNonNull(entombmentHelper);
        this.cleanupHelper = Objects.requireNonNull(cleanupHelper);
    }

    /**
     * Patch tower blocks grouped by vertex. Navigates to each vertex,
     * builds its repair blocks, then tears down scaffolds before moving on.
     */
    int patchTowerBlocks(ServerCommandSource source, ServerPlayerEntity bot,
                                   ServerWorld world, List<ProceduralWallBlock> towerRepairs,
                                   List<WallPoint> hullVertices,
                                   int referenceSurfaceY,
                                   SurfaceProfile surfaceProfile) {
        List<WallPoint> towerVertices = orderAndDedupeTowerVertices(hullVertices, bot.getBlockPos());
        Map<Integer, List<ProceduralWallBlock>> byVertex =
                groupTowerBlocksByNearestVertex(towerRepairs, towerVertices);

        int totalRepaired = 0;
        for (int vi = 0; vi < towerVertices.size(); vi++) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (ctx.countBuildingBlocks(bot) == 0) break;

            WallPoint vertex = towerVertices.get(vi);
            long vertexKey = ((long) vertex.x() << 32) | (vertex.z() & 0xFFFFFFFFL);
            List<ProceduralWallBlock> vertexRepairs = byVertex.getOrDefault(vi, List.of());
            int plannedCount = ctx.countActivePlannedBlocks(vertexRepairs);
            if (plannedCount <= 0) {
                continue;
            }
            int presentBefore = ctx.countPresentBlocks(world, vertexRepairs);
            int missingBefore = Math.max(0, plannedCount - presentBefore);
            if (missingBefore <= 0) {
                continue;
            }

            // Skip towers that had zero progress on a previous pass
            if (zeroProgressTowerVertices.contains(vertexKey)) {
                LOGGER.info("[FortifyTower] Skipping previously-stuck tower ({}, {}) — zero progress in earlier pass",
                        vertex.x(), vertex.z());
                continue;
            }

            ChatUtils.sendSystemMessage(source, String.format("§7  Patching tower at (%d, %d) — %d missing blocks (%d/%d present)",
                    vertex.x(), vertex.z(), missingBefore, presentBefore, plannedCount));

            int placed = executeTowerVertexWithRetries(
                    source,
                    bot,
                    world,
                    vertex,
                    vertexRepairs,
                    "fortify-patch-tower",
                    "patch-tower",
                    vi,
                    towerVertices.size(),
                    referenceSurfaceY,
                    surfaceProfile
            );
            totalRepaired += placed;

            int presentAfter = ctx.countPresentBlocks(world, vertexRepairs);
            if (!isTowerComplete(presentAfter, plannedCount)) {
                ChatUtils.sendSystemMessage(source, String.format(
                        "§7    Tower patch incomplete at (%d, %d): %d/%d present.",
                        vertex.x(), vertex.z(), presentAfter, plannedCount));
            }

            // Track zero-progress vertices so we skip them on the next auto-patch pass
            if (placed == 0) {
                zeroProgressTowerVertices.add(vertexKey);
            } else {
                // Clear the blacklist entry if we made any progress
                zeroProgressTowerVertices.remove(vertexKey);
            }

        }
        return totalRepaired;
    }
    boolean isTowerComplete(int presentCount, int plannedCount) {
        if (plannedCount <= 0) {
            return true;
        }
        int required = (int) Math.ceil(plannedCount * TOWER_COMPLETION_TARGET_RATIO);
        return presentCount >= Math.max(1, required);
    }

    List<WallPoint> orderAndDedupeTowerVertices(List<WallPoint> hullVertices, BlockPos origin) {
        if (hullVertices == null || hullVertices.isEmpty()) {
            return List.of();
        }
        int ox = origin != null ? origin.getX() : 0;
        int oz = origin != null ? origin.getZ() : 0;

        List<WallPoint> ordered = new ArrayList<>(hullVertices);
        ordered.sort(Comparator
                .comparingDouble((WallPoint v) -> {
                    double dx = v.x() - ox;
                    double dz = v.z() - oz;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(WallPoint::x)
                .thenComparingInt(WallPoint::z));

        List<WallPoint> deduped = new ArrayList<>();
        for (WallPoint candidate : ordered) {
            boolean duplicate = false;
            for (WallPoint existing : deduped) {
                int dx = candidate.x() - existing.x();
                int dz = candidate.z() - existing.z();
                if ((dx * dx + dz * dz) <= TOWER_VERTEX_DEDUP_DISTANCE_SQ) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    Map<Integer, List<ProceduralWallBlock>> groupTowerBlocksByNearestVertex(List<ProceduralWallBlock> towerBlocks,
                                                                                     List<WallPoint> towerVertices) {
        Map<Integer, List<ProceduralWallBlock>> byVertex = new LinkedHashMap<>();
        for (int i = 0; i < towerVertices.size(); i++) {
            byVertex.put(i, new ArrayList<>());
        }
        if (towerVertices.isEmpty() || towerBlocks == null || towerBlocks.isEmpty()) {
            return byVertex;
        }

        for (ProceduralWallBlock block : towerBlocks) {
            if (!ctx.isActiveFortifyBlock(block)) {
                continue;
            }
            int nearestVi = 0;
            double nearestDistSq = Double.MAX_VALUE;
            for (int vi = 0; vi < towerVertices.size(); vi++) {
                WallPoint vertex = towerVertices.get(vi);
                double dx = block.worldPos().getX() - vertex.x();
                double dz = block.worldPos().getZ() - vertex.z();
                double distSq = dx * dx + dz * dz;
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestVi = vi;
                }
            }
            byVertex.computeIfAbsent(nearestVi, ignored -> new ArrayList<>()).add(block);
        }
        return byVertex;
    }

    int executeTowerVertexWithRetries(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              List<ProceduralWallBlock> vertexBlocks,
                                              String taskPrefix,
                                              String groupPrefix,
                                              int vertexOrdinal,
                                              int totalVertices,
                                              int referenceSurfaceY,
                                              SurfaceProfile surfaceProfile) {
        int plannedCount = ctx.countActivePlannedBlocks(vertexBlocks);
        if (plannedCount <= 0) {
            return 0;
        }

        int presentCount = ctx.countPresentBlocks(world, vertexBlocks);
        int newlyPlaced = 0;
        int noProgressAttempts = 0;
        BlockPos lastAttemptPos = null;
        int stagnantAttemptStreak = 0;
        int hardResetNoProgressStreak = 0;
        TowerNavAttemptState towerNavState = new TowerNavAttemptState(vertex);

        // Track overall time for the entire tower vertex (approach + local placement + scaffold).
        // Prevents a single stuck tower from monopolizing the session.
        long towerOverallStartMs = System.currentTimeMillis();

        // Long-range navigation: if bot is far from this tower, use MovementService
        // to get to the general area before starting short-range local approach retries.
        double distToTowerSq = bot.squaredDistanceTo(vertex.x() + 0.5, bot.getY(), vertex.z() + 0.5);
        if (distToTowerSq > 400.0D) { // > 20 blocks away
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0, vertexBlocks, towerNavState);
            if (towerApproach != null) {
                // Avoid huge gate detours for near-local tower patch recovery; prefer local reroute/carve.
                if (distToTowerSq > 625.0D) { // > 25 blocks
                    FortifyNavRuntimeScope priorGateScope = ctx.beginFortifyNavScope(
                            "fortify-tower:pre-gate", towerNavState, vertex, towerApproach, true, false);
                    try {
                        ctx.navigateThroughGateIfNeeded(source, bot, world, towerApproach, surfaceProfile);
                    } finally {
                        ctx.endFortifyNavScope(bot, world, priorGateScope);
                    }
                } else {
                    LOGGER.info("[FortifyTower] skipping pre-gate route for local tower target {} dist={}",
                            towerApproach.toShortString(), String.format(Locale.ROOT, "%.1f", Math.sqrt(distToTowerSq)));
                }
                if (SkillManager.shouldAbortSkill(bot)) return 0;
                Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                        bot, towerApproach, MovementService.MovementOptions.skillLoot());
                if (plan.isPresent() && !SkillManager.shouldAbortSkill(bot)) {
                    LOGGER.info("[FortifyTower] long-range nav to tower {}/{} approach={} dist={}",
                            vertexOrdinal + 1, totalVertices, towerApproach.toShortString(),
                            String.format("%.0f", Math.sqrt(distToTowerSq)));
                    // Suppress obstruction mining and door traversal during fortify navigation
                    MovementService.withoutDoorEscape(() ->
                            MovementService.withoutObstructionMining(
                                    () -> MovementService.execute(source, bot, plan.get(), null)));
                }
                // walkToTarget closes any remaining gap with built-in break-through
                double postDistSq = bot.squaredDistanceTo(towerApproach.getX() + 0.5, bot.getY(), towerApproach.getZ() + 0.5);
                if (postDistSq > 9.0D && !SkillManager.shouldAbortSkill(bot)) {
                    ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:long-range-close",
                            towerNavState, vertex, towerApproach,
                            () -> ops.walkToTarget(source, bot, towerApproach, 6_000L, "fortify-tower:long-range-close"));
                }
            }
        }

        long towerStartMs = System.currentTimeMillis();

        for (int attempt = 1; attempt <= TOWER_LOCAL_MAX_ATTEMPTS; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            if (ctx.countBuildingBlocks(bot) == 0) {
                break;
            }
            if (isTowerComplete(presentCount, plannedCount)) {
                break;
            }
            // Overall time cap: includes approach navigation + local placement.
            // Prevents a single tower from monopolizing the session (approach alone can take 15-20s).
            long overallElapsedMs = System.currentTimeMillis() - towerOverallStartMs;
            if (overallElapsedMs > TOWER_VERTEX_TIME_BUDGET_MS) {
                LOGGER.info("[FortifyTower] tower {}/{} hit overall time budget ({}s) placed={}, aborting",
                        vertexOrdinal + 1, totalVertices,
                        overallElapsedMs / 1000, newlyPlaced);
                break;
            }
            // Safety net: bail out after a time budget with zero blocks placed.
            // If the bot is at a known failed sub-surface position, use a shorter budget (8s)
            // to avoid burning 20+ seconds revisiting the same stuck trap.
            boolean atKnownBadPos = towerNavState != null
                    && towerNavState.isKnownSubSurfaceFailure(bot.getBlockPos());
            long zeroBudgetMs = atKnownBadPos ? 8_000L : 20_000L;
            if (newlyPlaced == 0 && System.currentTimeMillis() - towerStartMs > zeroBudgetMs) {
                LOGGER.info("[FortifyTower] tower {}/{} timed out after {}s with 0 blocks placed{}, skipping",
                        vertexOrdinal + 1, totalVertices,
                        (System.currentTimeMillis() - towerStartMs) / 1000,
                        atKnownBadPos ? " (known bad sub-surface pos)" : "");
                break;
            }

            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, attempt - 1);
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attempt - 1, vertexBlocks, towerNavState);
            boolean approached = moveToTowerApproach(
                    source, bot, world, vertex, towerApproach, surfaceProfile,
                    taskPrefix + ":" + vertexOrdinal + ":approach-" + attempt,
                    towerNavState);

            if (!approached) {
                TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                        source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal, towerNavState);
                if (hardReset.moved()) {
                    if (hardReset.meaningful()) {
                        noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                        hardResetNoProgressStreak = 0;
                    } else {
                        hardResetNoProgressStreak++;
                        LOGGER.info("[FortifyTower] hard-reset movement was non-meaningful tower=({}, {}) streak={}/{}",
                                vertex.x(), vertex.z(), hardResetNoProgressStreak, TOWER_HARD_RESET_NO_PROGRESS_LIMIT);
                        if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                            LOGGER.info("[FortifyTower] skipping remaining local retries for tower ({},{}) due to repeated non-meaningful hard resets",
                                    vertex.x(), vertex.z());
                            break;
                        }
                    }
                    continue;
                }
                hardResetNoProgressStreak++;
                noProgressAttempts++;
                if (noProgressAttempts >= TOWER_LOCAL_NO_PROGRESS_LIMIT) {
                    break;
                }
                if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                    LOGGER.info("[FortifyTower] aborting local retries for tower ({},{}) due to hard-reset no-progress streak",
                            vertex.x(), vertex.z());
                    break;
                }
                continue;
            }
            hardResetNoProgressStreak = 0;

            BlockPos botPos = bot.getBlockPos();
            if (Math.abs(botPos.getX() - vertex.x()) <= 1 && Math.abs(botPos.getZ() - vertex.z()) <= 1) {
                ctx.tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, towerApproach,
                        taskPrefix + ":" + vertexOrdinal + ":inside-footprint-" + attempt);
            }

            int before = presentCount;
            int reportedPlaced = ctx.executeLocalPlacementBatch(
                    source,
                    bot,
                    world,
                    vertexBlocks,
                    taskPrefix + ":" + vertexOrdinal + ":attempt-" + attempt,
                    groupPrefix + ":" + vertexOrdinal,
                    referenceSurfaceY,
                    surfaceProfile,
                    new ExecutionPolicy(4, 3, 2, TOWER_VERTEX_TIME_BUDGET_MS),
                    towerApproach,
                    PlacementTarget.TargetKind.FORTIFY_TOWER
            );

            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, attempt);
            presentCount = ctx.countPresentBlocks(world, vertexBlocks);
            int gained = Math.max(0, presentCount - before);
            newlyPlaced += gained;

            if (gained > 0) {
                noProgressAttempts = 0;
                stagnantAttemptStreak = 0;
            } else {
                noProgressAttempts++;
                ctx.tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, towerApproach,
                        taskPrefix + ":" + vertexOrdinal + ":no-progress-" + attempt);
                TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                        source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal, towerNavState);
                if (hardReset.moved() && hardReset.meaningful()) {
                    noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                    hardResetNoProgressStreak = 0;
                } else {
                    hardResetNoProgressStreak++;
                }
            }

            BlockPos attemptPos = bot.getBlockPos();
            if (lastAttemptPos != null && lastAttemptPos.equals(attemptPos) && gained == 0) {
                stagnantAttemptStreak++;
                if (stagnantAttemptStreak >= 2) {
                    TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                            source, bot, world, vertex, surfaceProfile,
                            attempt + vertexOrdinal + stagnantAttemptStreak, towerNavState);
                    if (hardReset.moved() && hardReset.meaningful()) {
                        noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                        hardResetNoProgressStreak = 0;
                    } else {
                        hardResetNoProgressStreak++;
                    }
                }
            } else {
                stagnantAttemptStreak = 0;
            }
            lastAttemptPos = attemptPos.toImmutable();

            LOGGER.info("[FortifyTower] tower={}/{} pos=({}, {}) attempt={} gained={} reportedPlaced={} present={}/{}",
                    vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z(), attempt,
                    gained, reportedPlaced, presentCount, plannedCount);

            if (noProgressAttempts >= TOWER_LOCAL_NO_PROGRESS_LIMIT) {
                break;
            }
            if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                LOGGER.info("[FortifyTower] ending local tower retries early tower=({}, {}) hardResetNoProgressStreak={}",
                        vertex.x(), vertex.z(), hardResetNoProgressStreak);
                break;
            }
        }

        // ── Scaffold phase: reach upper tower layers from elevated position ──
        presentCount = ctx.countPresentBlocks(world, vertexBlocks);
        if (!isTowerComplete(presentCount, plannedCount)
                && !SkillManager.shouldAbortSkill(bot)
                && ctx.countBuildingBlocks(bot) > 0
                && (System.currentTimeMillis() - towerOverallStartMs) < TOWER_VERTEX_TIME_BUDGET_MS) {
            int scaffoldGained = executeTowerScaffoldPhase(
                    source, bot, world, vertex, vertexBlocks, surfaceProfile,
                    vertexOrdinal, totalVertices, plannedCount, referenceSurfaceY, towerNavState);
            newlyPlaced += scaffoldGained;
        }

        if (newlyPlaced == 0 && !isTowerComplete(ctx.countPresentBlocks(world, vertexBlocks), plannedCount)) {
            int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
            int depth = terrainY - bot.getBlockPos().getY();
            LOGGER.info("[FortifyTower] zero-progress tower vertex={}/{} pos=({}, {}) botPos={} trapLike={} depth={} noProgressAttempts={} sameStuckPosCount={}",
                    vertexOrdinal + 1, totalVertices,
                    vertex.x(), vertex.z(),
                    bot.getBlockPos().toShortString(),
                    ctx.isTrapLikeCell(world, bot.getBlockPos()),
                    depth,
                    noProgressAttempts,
                    towerNavState != null ? towerNavState.sameStuckPosCount : 0);
        }

        return newlyPlaced;
    }

    // ── Tower scaffold phase ──────────────────────────────────────

    private static final long TOWER_SCAFFOLD_TIME_BUDGET_MS = 60_000L;
    private static final int TOWER_SCAFFOLD_MAX_SIDES = 6;
    private static final long TOWER_SCAFFOLD_STEP_TIMEOUT_MS = 900L;
    private static final long TOWER_SCAFFOLD_RETURN_TIMEOUT_MS = 900L;
    private static final int TOWER_TOP_VERIFY_MAX_ATTEMPTS = 4;
    private static final long TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS = 320L;
    private static final double TOWER_SCAFFOLD_RECENTER_TOLERANCE_SQ = 0.14D * 0.14D;
    private static final int TOWER_SCAFFOLD_NO_HEADROOM_SAME_POS_LIMIT = 2;
    private static final int TOWER_HARD_RESET_NO_PROGRESS_LIMIT = 3;

    /**
     * Scaffold phase for incomplete towers: pillar up on cardinal sides and place
     * remaining upper blocks from an elevated position, then tear down the scaffold.
     */
    private int executeTowerScaffoldPhase(ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          ServerWorld world,
                                          WallPoint vertex,
                                          List<ProceduralWallBlock> vertexBlocks,
                                          SurfaceProfile surfaceProfile,
                                          int vertexOrdinal,
                                          int totalVertices,
                                          int plannedCount,
                                          int referenceSurfaceY,
                                          TowerNavAttemptState towerNavState) {
        int presentCount = ctx.countPresentBlocks(world, vertexBlocks);
        if (isTowerComplete(presentCount, plannedCount)) {
            return 0;
        }

        // Find the highest target Y among unsatisfied blocks
        int maxTargetY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (!ctx.isActiveFortifyBlock(block)) continue;
            BlockState current = world.getBlockState(block.worldPos());
            if (!ctx.isPlannedBlockSatisfied(block, current)) {
                maxTargetY = Math.max(maxTargetY, block.worldPos().getY());
            }
        }
        if (maxTargetY == Integer.MIN_VALUE) {
            return 0;
        }

        // Optimal scaffold Y: puts bot feet at the highest target block level.
        // This allows stepping onto placed wall blocks at that height, and the
        // bot's eye (Y+1.62) has downward reach to all lower blocks.
        int optimalY = maxTargetY;
        int groundY = ctx.safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        if (optimalY <= groundY) {
            return 0; // no benefit from scaffolding
        }

        LOGGER.info("[FortifyTower] scaffold phase for tower {}/{} pos=({},{}) present={}/{} optimalY={}",
                vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z(),
                presentCount, plannedCount, optimalY);
        ops.showOverhead(bot, "Scaffolding tower " + (vertexOrdinal + 1) + "/" + totalVertices);

        if (towerNavState != null && towerNavState.sameStuckPosCount >= 1 && towerNavState.noRealProgressElapsedMs() >= 2_000L) {
            FortifyNavRuntimeScope prior = ctx.beginFortifyNavScope("fortify-tower:scaffold-pre-escape", towerNavState, vertex, bot.getBlockPos(), true, false);
            try {
                if (towerNavState.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                    towerNavState.activateCarveMode();
                    if (ops.getActiveFortifyNavScope() != null) ops.getActiveFortifyNavScope().navMode = FortifyNavMode.CARVE_CORRIDOR;
                    if (ops.getActiveFortifyNavScope() != null && ops.getActiveFortifyNavScope().carveSession == null) {
                        ops.getActiveFortifyNavScope().carveSession = new FortifyCarveSession(bot.getBlockPos(), "tower-scaffold-pre-escape");
                    }
                    LOGGER.info("[FortifyTower] scaffold pre-escape activating carve mode for tower ({},{})",
                            vertex.x(), vertex.z());
                }
                BlockPos escape = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, 3);
                if (escape != null) {
                    ops.walkToTarget(source, bot, escape, 2_000L, "fortify-tower:scaffold-pre-escape");
                }
            } finally {
                ctx.endFortifyNavScope(bot, world, prior);
            }
            if (towerNavState.sameStuckPosCount >= 1 && bot.getBlockPos().getSquaredDistance(new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z())) > 25.0D) {
                towerNavState.noteRealProgress();
            }
        }

        long phaseStart = System.currentTimeMillis();
        int totalGained = 0;
        Set<Integer> triedSides = new HashSet<>();
        Map<BlockPos, Integer> repeatedNoHeadroomByPos = new HashMap<>();

        for (int sideAttempt = 0; sideAttempt < TOWER_SCAFFOLD_MAX_SIDES; sideAttempt++) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (ctx.countBuildingBlocks(bot) == 0) break;
            if (System.currentTimeMillis() - phaseStart > TOWER_SCAFFOLD_TIME_BUDGET_MS) break;

            presentCount = ctx.countPresentBlocks(world, vertexBlocks);
            if (isTowerComplete(presentCount, plannedCount)) break;

            BlockPos scaffoldBase = chooseTowerScaffoldPos(world, vertex, surfaceProfile, triedSides);
            if (scaffoldBase == null) break;

            // Navigate to scaffold base
            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, sideAttempt);
            FortifyNavRuntimeScope scaffoldScope = ctx.beginFortifyNavScope("fortify-tower:scaffold-base", towerNavState, vertex, scaffoldBase, true, false);
            try {
                ops.walkToTarget(source, bot, scaffoldBase, 3_000L, "fortify-tower:scaffold-base");
            } finally {
                ctx.endFortifyNavScope(bot, world, scaffoldScope);
            }
            double distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            if (distSq > 9.0) {
                ops.walkTowardBlock(bot, scaffoldBase, 2_000L);
                distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            }
            if (distSq <= 25.0D && ctx.isTrapLikeCell(world, bot.getBlockPos())) {
                boolean nudged = ctx.tryPostCarvePocketEscapeToward(bot, world, scaffoldBase);
                if (nudged) {
                    distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
                    LOGGER.info("[FortifyTower] scaffold-base post-carve-escape side={} nudged=true dist={}",
                            sideAttempt, String.format(Locale.ROOT, "%.1f", Math.sqrt(distSq)));
                }
            }

            // If we're too far from the scaffold base, skip this side
            if (distSq > 16.0) {
                LOGGER.info("[FortifyTower] scaffold base unreachable side={} dist={} for tower ({},{})",
                        sideAttempt, String.format("%.1f", Math.sqrt(distSq)), vertex.x(), vertex.z());
                if (towerNavState != null) {
                    towerNavState.noteNoRealProgress();
                }
                if (towerNavState != null && towerNavState.sameStuckPosCount >= 1 && towerNavState.noRealProgressElapsedMs() >= 3_000L) {
                    LOGGER.info("[FortifyTower] skipping remaining scaffold sides for tower ({},{}) due to repeated local trap",
                            vertex.x(), vertex.z());
                    break;
                }
                continue;
            }

            TowerScaffoldSideOutcome sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_HARD;
            boolean recoverableScaffoldFailure = false;

            int localTerrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
            if (bot.getBlockPos().getY() < localTerrainY - 1) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck below-surface pos={} terrainY={} tower=({}, {})",
                        bot.getBlockPos().toShortString(), localTerrainY, vertex.x(), vertex.z());
                final int surfaceYForPrecheck = localTerrainY;
                ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:scaffold-base-surface-precheck",
                        towerNavState, vertex, scaffoldBase,
                        () -> ops.ensureOnSurface(bot, world, surfaceYForPrecheck));
                recoverableScaffoldFailure = true;
            }
            BlockPos launchPrecheckPos = bot.getBlockPos().toImmutable();
            localTerrainY = VillageFortificationLayoutService.terrainY(world, launchPrecheckPos.getX(), launchPrecheckPos.getZ());
            int launchDepth = Math.max(0, localTerrainY - launchPrecheckPos.getY());
            boolean launchBelowSurface = launchPrecheckPos.getY() < localTerrainY - 1;

            if (!ctx.canStandAt(world, launchPrecheckPos)) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck invalid-stand pos={} tower=({}, {})",
                        launchPrecheckPos.toShortString(), vertex.x(), vertex.z());
                entombmentHelper.noteEntombmentScaffoldFailure(world, launchPrecheckPos, "fortify-tower:scaffold-base");
                if (towerNavState != null) towerNavState.noteNoRealProgress();
                recoverableScaffoldFailure = true;
                continue;
            }
            if (!hasTowerPillarLaunchHeadroom(world, launchPrecheckPos)) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck no-headroom pos={} tower=({}, {})",
                        launchPrecheckPos.toShortString(), vertex.x(), vertex.z());
                entombmentHelper.noteEntombmentScaffoldFailure(world, launchPrecheckPos, "fortify-tower:scaffold-base");
                if (towerNavState != null) towerNavState.noteNoRealProgress();
                int repeatCount = repeatedNoHeadroomByPos.merge(launchPrecheckPos, 1, Integer::sum);
                if (launchBelowSurface && launchDepth >= 2 && repeatCount >= TOWER_SCAFFOLD_NO_HEADROOM_SAME_POS_LIMIT) {
                    LOGGER.info("[FortifyTower] scaffold-base launch-precheck aborting scaffold phase tower=({}, {}) pos={} depth={} repeatedNoHeadroom={}",
                            vertex.x(), vertex.z(), launchPrecheckPos.toShortString(), launchDepth, repeatCount);
                    break;
                }
                recoverableScaffoldFailure = true;
                continue;
            }
            repeatedNoHeadroomByPos.remove(launchPrecheckPos);
            if (ctx.isTrapLikeCell(world, launchPrecheckPos)) {
                boolean nudged = ctx.tryPostCarvePocketEscapeToward(bot, world, scaffoldBase);
                if (!nudged && ctx.isTrapLikeCell(world, bot.getBlockPos())) {
                    LOGGER.info("[FortifyTower] scaffold-base launch-precheck trap-like pos={} tower=({}, {})",
                            bot.getBlockPos().toShortString(), vertex.x(), vertex.z());
                    entombmentHelper.noteEntombmentScaffoldFailure(world, bot.getBlockPos(), "fortify-tower:scaffold-base");
                    if (towerNavState != null) towerNavState.noteNoRealProgress();
                    recoverableScaffoldFailure = true;
                    continue;
                }
            }

            // Pillar up
            int startBotY = bot.getBlockPos().getY();
            int stepsNeeded = Math.max(0, optimalY - startBotY);
            LOGGER.info("[FortifyTower] scaffold pillar attempt side={} botY={} optimalY={} steps={} for tower ({},{})",
                    sideAttempt, startBotY, optimalY, stepsNeeded, vertex.x(), vertex.z());
            // Engage sneak BEFORE pillaring — prevents bot from walking off the
            // scaffold column if block placement timing is slightly off.
            SneakLockService.acquire(bot.getUuid());
            BotActions.sneak(bot, true);

            ScaffoldService.ScaffoldSession session = ScaffoldService.beginSession(bot);
            boolean pillared = ScaffoldService.pillarToY(session, optimalY);
            int postPillarY = bot.getBlockPos().getY();
            TowerPillarOutcome pillarOutcome = pillared
                    ? TowerPillarOutcome.OK
                    : (postPillarY > startBotY && !session.trackedPositions().isEmpty()
                    ? TowerPillarOutcome.PARTIAL
                    : TowerPillarOutcome.FAIL);
            // Accept partial success: if the bot gained height and has scaffolds,
            // it can still reach tower blocks from the elevated position.
            // Previously, placing 3/4 blocks (1 short of target) → teardown all 3 → waste.
            boolean usable = pillared || (postPillarY > startBotY && !session.trackedPositions().isEmpty());
            if (!usable) {
                LOGGER.info("[FortifyTower] scaffold pillar failed side={} pillared={} tracked={} botY={} for tower ({},{})",
                        sideAttempt, pillared, session.trackedPositions().size(),
                        postPillarY, vertex.x(), vertex.z());
                SneakLockService.release(bot.getUuid());
                if (!SneakLockService.isLocked(bot.getUuid())) {
                    BotActions.sneak(bot, false);
                }
                teardownScaffoldSurvival(bot, world, session);
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_RECOVERABLE;
                continue;
            }

            // Sneak already acquired before pillar — mark as held for endScaffoldEdgeHold cleanup
            boolean scaffoldSneak = true;

            // Place remaining blocks within reach
            int sidePlaced = 0;
            for (ProceduralWallBlock block : vertexBlocks) {
                if (SkillManager.shouldAbortSkill(bot)) break;
                if (!ctx.isActiveFortifyBlock(block)) continue;
                BlockState current = world.getBlockState(block.worldPos());
                if (ctx.isPlannedBlockSatisfied(block, current)) continue;
                if (!ops.isWithinReach(bot, block.worldPos())) continue;

                LookController.faceBlock(bot, block.worldPos());
                ops.sleepQuiet(BLOCK_PLACE_DELAY_MS);
                BotActions.PlaceResult result = ctx.tryPlaceBlock(bot, world, block.worldPos(), block.state());
                if (result.success()) {
                    sidePlaced++;
                }
            }

            // ── Step-onto-structure: extend reach by sneaking onto intended tower top surface ──
            int stepOnGained = 0;
            List<ProceduralWallBlock> remaining = new ArrayList<>();
            for (ProceduralWallBlock block : vertexBlocks) {
                if (!ctx.isActiveFortifyBlock(block)) continue;
                if (ctx.isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
                remaining.add(block);
            }
            TowerStepAttemptResult stepResult = null;
            TowerReturnAttemptResult returnResult = null;
            BlockPos scaffoldReturn = bot.getBlockPos();
            if (!remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                TowerSummitStepCandidate summitCandidate =
                        chooseTowerSummitStepCandidate(world, vertex, scaffoldReturn, remaining);
                if (summitCandidate != null) {
                    LOGGER.info("[FortifyTower] step-onto-structure dir={} target={} newReachable={} score={}",
                            summitCandidate.dir(), summitCandidate.pos().toShortString(),
                            summitCandidate.newReachable(), summitCandidate.score());
                    stepResult = attemptSneakStepToTowerSurface(bot, world, vertex, scaffoldReturn, summitCandidate);
                    if (stepResult.outcome() == TowerStepOutcome.OK) {
                        stepOnGained += placeReachableTowerBlocksFromCurrentSummit(bot, world, remaining);
                        if (!remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                            TowerSummitRoamResult roamResult = roamTowerSummitForPlacements(
                                    bot, world, vertex, scaffoldReturn, remaining, 4);
                            stepOnGained += roamResult.placed();
                            if (roamResult.recoverableFailure()) {
                                recoverableScaffoldFailure = true;
                            }
                        }
                        returnResult = attemptSneakReturnToScaffold(bot, scaffoldReturn);
                        if (returnResult.outcome() != TowerReturnOutcome.OK) {
                            recoverableScaffoldFailure = true;
                        }
                    } else if (stepResult.outcome() != TowerStepOutcome.NO_MOVE) {
                        recoverableScaffoldFailure = true;
                    }
                }
                sidePlaced += stepOnGained;
            }

            boolean fellDuringSummit = stepResult != null && stepResult.outcome() == TowerStepOutcome.FELL;
            boolean offTargetSummit = stepResult != null && stepResult.outcome() == TowerStepOutcome.OFF_TARGET;
            boolean returnFailed = returnResult != null && returnResult.outcome() == TowerReturnOutcome.FAIL;
            boolean needsFallRecoveryCleanup = fellDuringSummit || offTargetSummit || returnFailed;
            int topVerifyPlaced = 0;
            if (!needsFallRecoveryCleanup) {
                topVerifyPlaced = verifyAndPlaceTowerTopLayer(bot, world, vertexBlocks);
                sidePlaced += topVerifyPlaced;
            }
            if (needsFallRecoveryCleanup) {
                LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery transition tower=({}, {}) step={} return={} current={}",
                        vertex.x(), vertex.z(),
                        stepResult != null ? stepResult.outcome() : TowerStepOutcome.NO_MOVE,
                        returnResult != null ? returnResult.outcome() : TowerReturnOutcome.OK,
                        bot.getBlockPos().toShortString());
            }

            // Keep sneak through pillar/step/place/return and release only once we transition into cleanup.
            ops.endScaffoldEdgeHold(bot, scaffoldSneak);

            ScaffoldTeardownResult teardownResult = needsFallRecoveryCleanup
                    ? recoverAndTeardownScaffoldColumn(source, bot, world, session, "tower-side-" + sideAttempt)
                    : teardownScaffoldSurvival(bot, world, session);

            boolean gainedUsableScaffoldHeight = postPillarY > startBotY && !session.trackedPositions().isEmpty();

            if (sidePlaced > 0) {
                sideOutcome = TowerScaffoldSideOutcome.PROGRESS;
            } else if (recoverableScaffoldFailure
                    || pillarOutcome == TowerPillarOutcome.FAIL
                    || needsFallRecoveryCleanup
                    || teardownResult.queued() > 0
                    || gainedUsableScaffoldHeight) {
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_RECOVERABLE;
            } else {
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_HARD;
            }

            totalGained += sidePlaced;
            LOGGER.info("[FortifyTower] scaffold side={} placed={} topVerify={} outcome={} teardownRemoved={} teardownQueued={} for tower {}/{} ({},{})",
                    sideAttempt, sidePlaced, topVerifyPlaced, sideOutcome,
                    teardownResult.removed(), teardownResult.queued(),
                    vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z());

            if (sidePlaced == 0 && sideOutcome == TowerScaffoldSideOutcome.NO_PROGRESS_HARD) {
                break;
            }
        }

        return totalGained;
    }

    /**
     * Pick a scaffold position near a tower vertex. Tries cardinal directions at
     * distances 1, 2, 3, 4 blocks out, then diagonals. Distance 1 is preferred
     * because it places the scaffold adjacent to the tower structure, enabling
     * step-onto-structure placement for far-side blocks.
     */
    private BlockPos chooseTowerScaffoldPos(ServerWorld world, WallPoint vertex,
                                            SurfaceProfile surfaceProfile,
                                            Set<Integer> triedSides) {
        // 8 directions: 4 cardinal + 4 diagonal
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},  // cardinal (indices 0-3)
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal (indices 4-7)
        };
        // Try increasing distances: 1, 2, 3, 4 blocks out (prefer closest)
        for (int dist = 1; dist <= 4; dist++) {
            for (int i = 0; i < directions.length; i++) {
                // Encode side as (direction_index * 10 + dist) for uniqueness
                int sideKey = i * 10 + dist;
                if (triedSides.contains(sideKey)) continue;
                int x = vertex.x() + directions[i][0] * dist;
                int z = vertex.z() + directions[i][1] * dist;
                // Skip positions inside the 3x3 tower footprint
                if (Math.abs(x - vertex.x()) <= 1 && Math.abs(z - vertex.z()) <= 1) continue;
                int y = ctx.safeSurfaceY(surfaceProfile, world, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (ctx.canStandAt(world, pos)) {
                    triedSides.add(sideKey);
                    LOGGER.debug("[FortifyTower] scaffold pos chosen: dir={} dist={} pos={}",
                            i, dist, pos.toShortString());
                    return pos;
                }
                // Also try one Y above (wall blocks may raise the floor)
                BlockPos posUp = new BlockPos(x, y + 1, z);
                if (ctx.canStandAt(world, posUp)) {
                    triedSides.add(sideKey);
                    LOGGER.debug("[FortifyTower] scaffold pos chosen (Y+1): dir={} dist={} pos={}",
                            i, dist, posUp.toShortString());
                    return posUp;
                }
            }
        }
        LOGGER.info("[FortifyTower] no standable scaffold pos found near tower ({},{})",
                vertex.x(), vertex.z());
        return null;
    }

    private boolean hasTowerPillarLaunchHeadroom(ServerWorld world, BlockPos feetPos) {
        if (world == null || feetPos == null) return false;
        BlockPos head = feetPos.up(2);
        BlockPos head2 = feetPos.up(3);
        BlockState h1 = world.getBlockState(head);
        BlockState h2 = world.getBlockState(head2);
        return h1.getCollisionShape(world, head).isEmpty()
                && h2.getCollisionShape(world, head2).isEmpty();
    }

    private TowerSummitStepCandidate chooseTowerSummitStepCandidate(ServerWorld world,
                                                                    WallPoint vertex,
                                                                    BlockPos scaffoldReturn,
                                                                    List<ProceduralWallBlock> remaining) {
        if (world == null || vertex == null || scaffoldReturn == null || remaining == null || remaining.isEmpty()) {
            return null;
        }
        int maxRemainingY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : remaining) {
            if (block == null) continue;
            maxRemainingY = Math.max(maxRemainingY, block.worldPos().getY());
        }
        TowerSummitStepCandidate best = null;
        Set<BlockPos> seenCandidates = new HashSet<>();
        for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, scaffoldReturn)) {
            if (transition == null || transition.requiresCarve()) continue;
            BlockPos candidatePos = transition.to();
            if (candidatePos == null || !seenCandidates.add(candidatePos.toImmutable())) {
                continue;
            }
            if (Math.abs(candidatePos.getY() - scaffoldReturn.getY()) > 1) {
                continue;
            }
            if (!isInsideTowerFootprint(candidatePos, vertex)) {
                continue;
            }
            if (!ctx.canStandAt(world, candidatePos)) {
                continue;
            }
            BlockPos supportPos = candidatePos.down();
            BlockState supportState = world.getBlockState(supportPos);
            if (supportState.getCollisionShape(world, supportPos).isEmpty()) {
                continue;
            }
            Direction dir = horizontalDirectionToward(scaffoldReturn, candidatePos);
            if (dir == null) {
                continue;
            }
            int newReachable = 0;
            int topReachable = 0;
            int score = 0;
            Vec3d stepEye = Vec3d.ofCenter(candidatePos).add(0, 1.62, 0);
            Vec3d perchEye = Vec3d.ofCenter(scaffoldReturn).add(0, 1.62, 0);
            for (ProceduralWallBlock b : remaining) {
                if (b == null) continue;
                Vec3d targetCenter = Vec3d.ofCenter(b.worldPos());
                boolean fromPerch = perchEye.squaredDistanceTo(targetCenter) <= REACH_DISTANCE_SQ;
                boolean fromStep = stepEye.squaredDistanceTo(targetCenter) <= REACH_DISTANCE_SQ;
                if (fromStep && !fromPerch) {
                    newReachable++;
                    if (b.worldPos().getY() >= maxRemainingY - 1) {
                        topReachable++;
                    }
                }
            }
            if (newReachable == 0) {
                continue;
            }
            score += topReachable * 100;
            score += newReachable * 20;
            score += ctx.countOpenExits(world, candidatePos, null) * 5;
            if (hasOneStepReturnToScaffold(world, candidatePos, scaffoldReturn)) {
                score += 25;
            } else {
                score -= 30;
            }
            score += switch (transition.kind()) {
                case CARDINAL -> 20;
                case STEP_UP -> 15;
                case STEP_DOWN -> 8;
                case NARROW_GAP -> 5;
                case REQUIRES_CARVE -> -50;
            };
            if (best == null || score > best.score()) {
                best = new TowerSummitStepCandidate(candidatePos, dir, newReachable, score);
            }
        }
        return best;
    }

    private int placeReachableTowerBlocksFromCurrentSummit(ServerPlayerEntity bot,
                                                           ServerWorld world,
                                                           List<ProceduralWallBlock> remaining) {
        if (bot == null || world == null || remaining == null || remaining.isEmpty()) {
            return 0;
        }
        int placed = 0;
        Iterator<ProceduralWallBlock> it = remaining.iterator();
        while (it.hasNext()) {
            ProceduralWallBlock b = it.next();
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (b == null || !ops.isWithinReach(bot, b.worldPos())) continue;
            LookController.faceBlock(bot, b.worldPos());
            ops.sleepQuiet(BLOCK_PLACE_DELAY_MS);
            BotActions.PlaceResult placeResult = ctx.tryPlaceBlock(bot, world, b.worldPos(), b.state());
            if (placeResult.success()) {
                placed++;
                it.remove();
            }
        }
        return placed;
    }

    private TowerSummitRoamResult roamTowerSummitForPlacements(ServerPlayerEntity bot,
                                                               ServerWorld world,
                                                               WallPoint vertex,
                                                               BlockPos scaffoldReturn,
                                                               List<ProceduralWallBlock> remaining,
                                                               int maxMoves) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || remaining == null || remaining.isEmpty()) {
            return new TowerSummitRoamResult(0, false);
        }
        int placed = 0;
        boolean recoverableFailure = false;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(bot.getBlockPos().toImmutable());
        for (int move = 0; move < Math.max(0, maxMoves) && !remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot); move++) {
            BlockPos current = bot.getBlockPos();
            BlockPos bestNext = null;
            int bestScore = Integer.MIN_VALUE;
            for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, current)) {
                if (transition == null || transition.requiresCarve()) continue;
                BlockPos candidate = transition.to();
                if (candidate == null) continue;
                if (visited.contains(candidate)) continue;
                if (!isInsideTowerFootprint(candidate, vertex)) continue;
                if (!ctx.canStandAt(world, candidate)) continue;
                if (Math.abs(candidate.getY() - scaffoldReturn.getY()) > 1) continue;
                int score = countSummitReachableBlocks(bot, candidate, remaining) * 20;
                if (score <= 0) continue;
                if (hasOneStepReturnToScaffold(world, candidate, scaffoldReturn)) score += 20;
                score += switch (transition.kind()) {
                    case CARDINAL -> 10;
                    case STEP_UP -> 8;
                    case STEP_DOWN -> 4;
                    case NARROW_GAP -> 2;
                    case REQUIRES_CARVE -> -50;
                };
                if (score > bestScore) {
                    bestScore = score;
                    bestNext = candidate.toImmutable();
                }
            }
            if (bestNext == null) {
                break;
            }
            TowerStepAttemptResult moveResult = attemptSneakMoveOnTowerSurface(bot, world, vertex, scaffoldReturn, bestNext, "tower-step-sweep");
            if (moveResult.outcome() != TowerStepOutcome.OK) {
                if (moveResult.outcome() != TowerStepOutcome.NO_MOVE) {
                    recoverableFailure = true;
                }
                break;
            }
            visited.add(bot.getBlockPos().toImmutable());
            placed += placeReachableTowerBlocksFromCurrentSummit(bot, world, remaining);
        }
        return new TowerSummitRoamResult(placed, recoverableFailure);
    }

    private int countSummitReachableBlocks(ServerPlayerEntity bot, BlockPos standPos, List<ProceduralWallBlock> remaining) {
        if (bot == null || standPos == null || remaining == null || remaining.isEmpty()) {
            return 0;
        }
        Vec3d eye = Vec3d.ofCenter(standPos).add(0, 1.62, 0);
        int count = 0;
        for (ProceduralWallBlock b : remaining) {
            if (b == null) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(b.worldPos())) <= REACH_DISTANCE_SQ) {
                count++;
            }
        }
        return count;
    }

    private Direction horizontalDirectionToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) return null;
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 1 && dz == 0) return Direction.EAST;
        if (dx == -1 && dz == 0) return Direction.WEST;
        if (dx == 0 && dz == 1) return Direction.SOUTH;
        if (dx == 0 && dz == -1) return Direction.NORTH;
        return null;
    }

    private boolean hasOneStepReturnToScaffold(ServerWorld world, BlockPos from, BlockPos scaffoldReturn) {
        if (world == null || from == null || scaffoldReturn == null) {
            return false;
        }
        for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, from)) {
            if (transition == null || transition.requiresCarve()) continue;
            BlockPos to = transition.to();
            if (to == null) continue;
            if (sameBlockColumnWithinOneY(to, scaffoldReturn) || to.equals(scaffoldReturn)) {
                return true;
            }
        }
        return false;
    }

    private TowerStepAttemptResult attemptSneakStepToTowerSurface(ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  WallPoint vertex,
                                                                  BlockPos scaffoldReturn,
                                                                  TowerSummitStepCandidate candidate) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || candidate == null) {
            return new TowerStepAttemptResult(TowerStepOutcome.NO_MOVE, null, bot != null ? bot.getBlockPos() : null);
        }
        BlockPos expected = candidate.pos();
        BlockPos before = bot.getBlockPos();
        Vec3d stepVec = Vec3d.ofCenter(expected);
        settleSneakStepOnBlockCenter(bot, scaffoldReturn, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_STEP_TIMEOUT_MS;
        int stableTicksOnExpected = 0;
        while (System.currentTimeMillis() < deadline) {
            BlockPos current = bot.getBlockPos();
            if (sameBlockColumnWithinOneY(current, expected)) {
                stableTicksOnExpected++;
                if (stableTicksOnExpected >= 2) {
                    break;
                }
                ops.sleepQuiet(35L);
                continue;
            }
            stableTicksOnExpected = 0;
            LookController.faceBlock(bot, expected);
            double horizDist = horizontalDistanceToCenter(bot, expected);
            double impulse = horizDist > 0.55D ? 0.12D : 0.08D;
            BotActions.applyMovementInput(bot, stepVec, impulse);
            ops.sleepQuiet(40L);
        }
        BlockPos after = bot.getBlockPos();
        TowerStepOutcome outcome;
        if (sameBlockColumnWithinOneY(after, expected)) {
            settleSneakStepOnBlockCenter(bot, after, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
            after = bot.getBlockPos();
            outcome = TowerStepOutcome.OK;
        } else if (after.equals(before)) {
            outcome = TowerStepOutcome.NO_MOVE;
        } else if (after.getY() < scaffoldReturn.getY() - 1 || !isInsideTowerFootprint(after, vertex)) {
            outcome = TowerStepOutcome.FELL;
        } else {
            outcome = TowerStepOutcome.OFF_TARGET;
        }
        LOGGER.info("[FortifyTower] tower-step-out result={} expected={} actual={} dir={} newReachable={}",
                outcome,
                expected != null ? expected.toShortString() : "n/a",
                after != null ? after.toShortString() : "n/a",
                candidate.dir(),
                candidate.newReachable());
        return new TowerStepAttemptResult(outcome, expected, after);
    }

    private TowerStepAttemptResult attemptSneakMoveOnTowerSurface(ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  WallPoint vertex,
                                                                  BlockPos scaffoldReturn,
                                                                  BlockPos expected,
                                                                  String logLabel) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || expected == null) {
            return new TowerStepAttemptResult(TowerStepOutcome.NO_MOVE, expected, bot != null ? bot.getBlockPos() : null);
        }
        BlockPos before = bot.getBlockPos();
        Vec3d stepVec = Vec3d.ofCenter(expected);
        settleSneakStepOnBlockCenter(bot, before, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_STEP_TIMEOUT_MS;
        int stableTicksOnExpected = 0;
        while (System.currentTimeMillis() < deadline) {
            BlockPos current = bot.getBlockPos();
            if (sameBlockColumnWithinOneY(current, expected)) {
                stableTicksOnExpected++;
                if (stableTicksOnExpected >= 2) break;
                ops.sleepQuiet(35L);
                continue;
            }
            stableTicksOnExpected = 0;
            LookController.faceBlock(bot, expected);
            double horizDist = horizontalDistanceToCenter(bot, expected);
            double impulse = horizDist > 0.55D ? 0.11D : 0.07D;
            BotActions.applyMovementInput(bot, stepVec, impulse);
            ops.sleepQuiet(40L);
        }
        BlockPos after = bot.getBlockPos();
        TowerStepOutcome outcome;
        if (sameBlockColumnWithinOneY(after, expected)) {
            settleSneakStepOnBlockCenter(bot, after, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
            outcome = TowerStepOutcome.OK;
        } else if (after.equals(before)) {
            outcome = TowerStepOutcome.NO_MOVE;
        } else if (after.getY() < scaffoldReturn.getY() - 1 || !isInsideTowerFootprint(after, vertex)) {
            outcome = TowerStepOutcome.FELL;
        } else {
            outcome = TowerStepOutcome.OFF_TARGET;
        }
        LOGGER.info("[FortifyTower] {} result={} expected={} actual={}",
                logLabel, outcome, expected.toShortString(), after.toShortString());
        return new TowerStepAttemptResult(outcome, expected, after);
    }

    private TowerReturnAttemptResult attemptSneakReturnToScaffold(ServerPlayerEntity bot, BlockPos scaffoldReturn) {
        if (bot == null || scaffoldReturn == null) {
            return new TowerReturnAttemptResult(TowerReturnOutcome.FAIL, scaffoldReturn, bot != null ? bot.getBlockPos() : null);
        }
        settleSneakStepOnBlockCenter(bot, bot.getBlockPos(), Math.min(200L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        Vec3d returnVec = Vec3d.ofCenter(scaffoldReturn);
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_RETURN_TIMEOUT_MS;
        int stableTicksOnReturn = 0;
        while (System.currentTimeMillis() < deadline) {
            if (sameBlockColumnWithinOneY(bot.getBlockPos(), scaffoldReturn)) {
                stableTicksOnReturn++;
                if (stableTicksOnReturn >= 2) {
                    break;
                }
                ops.sleepQuiet(35L);
                continue;
            }
            stableTicksOnReturn = 0;
            LookController.faceBlock(bot, scaffoldReturn);
            double horizDist = horizontalDistanceToCenter(bot, scaffoldReturn);
            double impulse = horizDist > 0.55D ? 0.12D : 0.08D;
            BotActions.applyMovementInput(bot, returnVec, impulse);
            ops.sleepQuiet(40L);
        }
        settleSneakStepOnBlockCenter(bot, scaffoldReturn, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        BlockPos after = bot.getBlockPos();
        TowerReturnOutcome outcome = sameBlockColumnWithinOneY(after, scaffoldReturn) ? TowerReturnOutcome.OK : TowerReturnOutcome.FAIL;
        LOGGER.info("[FortifyTower] tower-step-back result={} expected={} actual={}",
                outcome, scaffoldReturn.toShortString(), after.toShortString());
        return new TowerReturnAttemptResult(outcome, scaffoldReturn, after);
    }

    private boolean sameBlockColumnWithinOneY(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        return a.getX() == b.getX()
                && a.getZ() == b.getZ()
                && Math.abs(a.getY() - b.getY()) <= 1;
    }

    private double horizontalDistanceToCenter(ServerPlayerEntity bot, BlockPos targetBlock) {
        if (bot == null || targetBlock == null) return Double.POSITIVE_INFINITY;
        Vec3d center = Vec3d.ofCenter(targetBlock);
        double dx = center.x - bot.getX();
        double dz = center.z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void settleSneakStepOnBlockCenter(ServerPlayerEntity bot, BlockPos standPos, long timeoutMs) {
        if (bot == null || standPos == null) return;
        if (!sameBlockColumnWithinOneY(bot.getBlockPos(), standPos)) return;
        Vec3d center = Vec3d.ofCenter(standPos);
        long deadline = System.currentTimeMillis() + Math.max(80L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return;
            }
            if (!sameBlockColumnWithinOneY(bot.getBlockPos(), standPos)) {
                return;
            }
            double dx = center.x - bot.getX();
            double dz = center.z - bot.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq <= TOWER_SCAFFOLD_RECENTER_TOLERANCE_SQ) {
                BotActions.stop(bot);
                return;
            }
            BotActions.applyMovementInput(bot, new Vec3d(center.x, bot.getY(), center.z), 0.07D);
            ops.sleepQuiet(30L);
        }
        BotActions.stop(bot);
    }

    private int verifyAndPlaceTowerTopLayer(ServerPlayerEntity bot,
                                            ServerWorld world,
                                            List<ProceduralWallBlock> vertexBlocks) {
        if (bot == null || world == null || vertexBlocks == null || vertexBlocks.isEmpty()) {
            return 0;
        }
        List<ProceduralWallBlock> remaining = new ArrayList<>();
        int maxY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (block == null || !ctx.isActiveFortifyBlock(block)) continue;
            if (ctx.isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
            remaining.add(block);
            maxY = Math.max(maxY, block.worldPos().getY());
        }
        if (remaining.isEmpty()) {
            return 0;
        }
        int topY = maxY;
        remaining.sort(Comparator
                .comparingInt((ProceduralWallBlock b) -> b.worldPos().getY() >= topY - 1 ? 0 : 1)
                .thenComparingInt((ProceduralWallBlock b) -> -b.worldPos().getY())
                .thenComparingDouble(b -> bot.getBlockPos().getSquaredDistance(b.worldPos())));
        int placed = 0;
        int attempted = 0;
        for (ProceduralWallBlock block : remaining) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (!ops.isWithinReach(bot, block.worldPos())) continue;
            if (attempted >= TOWER_TOP_VERIFY_MAX_ATTEMPTS) break;
            attempted++;
            LookController.faceBlock(bot, block.worldPos());
            ops.sleepQuiet(BLOCK_PLACE_DELAY_MS);
            BotActions.PlaceResult placeResult = ctx.tryPlaceBlock(bot, world, block.worldPos(), block.state());
            if (placeResult.success()) {
                placed++;
            }
        }
        if (attempted > 0) {
            LOGGER.info("[FortifyTower] tower-top-verify remainingTopLayerCount={} placed={} attempts={}",
                    remaining.stream().filter(b -> b.worldPos().getY() >= topY - 1).count(),
                    placed, attempted);
        }
        return placed;
    }

    private ScaffoldTeardownResult recoverAndTeardownScaffoldColumn(ServerCommandSource source,
                                                                    ServerPlayerEntity bot,
                                                                    ServerWorld world,
                                                                    ScaffoldService.ScaffoldSession session,
                                                                    String context) {
        if (bot == null || world == null) {
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }
        LinkedHashSet<BlockPos> tracked = new LinkedHashSet<>();
        if (session != null) {
            tracked.addAll(session.trackedPositions());
        }
        tracked.addAll(ScaffoldService.getScaffoldMemory(bot));
        BlockPos focus = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos pos : tracked) {
            if (pos == null) continue;
            double dist = bot.getBlockPos().getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                focus = pos;
            }
        }
        if (focus != null && (!ops.isWithinMiningReach(bot, focus) || !ops.hasLineOfSight(world, bot, bot.getEyePos(), focus))) {
            BlockPos bestStand = null;
            double bestStandScore = Double.POSITIVE_INFINITY;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = focus.add(dx, dy, dz);
                        if (!ctx.canStandAt(world, candidate)) continue;
                        double score = candidate.getSquaredDistance(focus) + bot.getBlockPos().getSquaredDistance(candidate) * 0.25D;
                        if (score < bestStandScore) {
                            bestStandScore = score;
                            bestStand = candidate;
                        }
                    }
                }
            }
            if (bestStand != null && !bestStand.equals(bot.getBlockPos())) {
                LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery moving ctx={} from={} to={} focus={}",
                        context,
                        bot.getBlockPos().toShortString(),
                        bestStand.toShortString(),
                        focus.toShortString());
                ops.walkToTarget(source, bot, bestStand, 1_500L, "fortify-tower:scaffold-recover");
                if (!bot.getBlockPos().equals(bestStand)) {
                    ops.walkTowardBlock(bot, bestStand, 900L);
                }
            }
        }
        ScaffoldTeardownResult result = teardownScaffoldSurvival(bot, world, session);
        LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery result ctx={} removed={} queued={} failedMine={} outOfReach={}",
                context, result.removed(), result.queued(), result.failedMine(), result.outOfReach());
        return result;
    }

    /**
     * Mine tracked scaffold blocks top-down. Each block mined drops the bot 1 block
     * (no fall damage from scaffold height). Uses ops.digBlock() for survival-mode mining.
     */
    private ScaffoldLedger buildScaffoldLedger(ServerPlayerEntity bot, ServerWorld world,
                                               ScaffoldService.ScaffoldSession session) {
        ScaffoldLedger ledger = new ScaffoldLedger();
        if (bot == null || world == null) {
            return ledger;
        }
        if (session != null) {
            for (BlockPos pos : session.trackedPositions()) {
                if (pos != null) ledger.sessionTracked.add(pos.toImmutable());
            }
        }

        BlockPos botPos = bot.getBlockPos();
        for (BlockPos pos : new ArrayList<>(ScaffoldService.getScaffoldMemory(bot))) {
            if (pos == null) continue;
            if (Math.abs(pos.getX() - botPos.getX()) <= 6
                    && Math.abs(pos.getZ() - botPos.getZ()) <= 6
                    && Math.abs(pos.getY() - botPos.getY()) <= 16) {
                ledger.memoryTracked.add(pos.toImmutable());
            }
        }

        // Do not infer scaffold candidates from nearby material scans. Scaffold blocks must be
        // explicitly tagged (session/memory tracked) or we risk tearing up terrain/walls made
        // from common scaffold materials (dirt/cobble/stone), especially below the bot.
        return ledger;
    }

    private ScaffoldTeardownResult teardownScaffoldSurvival(ServerPlayerEntity bot, ServerWorld world,
                                                            ScaffoldService.ScaffoldSession session) {
        if (bot == null || world == null) {
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }

        ScaffoldLedger ledger = buildScaffoldLedger(bot, world, session);
        List<BlockPos> toRemove = ledger.allCandidatesTopDown();
        if (toRemove.isEmpty()) {
            LOGGER.info("[FortifyScaffold] teardown tracked=0 removed=0 queued=0 (no candidates)");
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }

        Set<BlockPos> memory = ScaffoldService.getScaffoldMemory(bot);
        int removed = 0;
        int alreadyAir = 0;
        int failedMine = 0;
        int outOfReach = 0;
        int queued = 0;

        for (BlockPos pos : toRemove) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                alreadyAir++;
                memory.remove(pos);
                continue;
            }
            if (!ScaffoldService.SCAFFOLD_BLOCKS.contains(state.getBlock().asItem())) {
                memory.remove(pos);
                continue;
            }
            if (!ops.isWithinMiningReach(bot, pos)) {
                outOfReach++;
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
                continue;
            }
            if (!ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
                continue;
            }
            LookController.faceBlock(bot, pos);
            ops.sleepQuiet(50L);
            boolean mined = ops.digBlock(bot, world, pos);
            BlockState after = world.getBlockState(pos);
            boolean cleared = after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem());
            if (mined && cleared) {
                removed++;
                memory.remove(pos);
            } else if (cleared) {
                alreadyAir++;
                memory.remove(pos);
            } else {
                failedMine++;
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
            }
        }

        ScaffoldTeardownResult result = new ScaffoldTeardownResult(
                ledger.sessionTracked.size(),
                ledger.memoryTracked.size(),
                ledger.reconciledNearby.size(),
                removed, alreadyAir, failedMine, outOfReach, queued);
        LOGGER.info("[FortifyScaffold] teardown trackedSession={} trackedMemory={} reconciledNearby={} removed={} alreadyAir={} failedMine={} outOfReach={} queued={}",
                result.trackedSession(), result.trackedMemory(), result.reconciledNearby(),
                result.removed(), result.alreadyAir(), result.failedMine(), result.outOfReach(), result.queued());
        if ((result.trackedSession() + result.trackedMemory()) > 0
                && result.removed() == 0 && result.queued() == 0 && result.alreadyAir() == 0) {
            LOGGER.warn("[FortifyScaffold] teardown produced no removals despite tracked scaffolds (session={} memory={})",
                    result.trackedSession(), result.trackedMemory());
        }
        ctx.processDeferredFortifyCleanupQueue(bot, world, "scaffold-teardown");
        return result;
    }

    private void forceExitTowerFootprint(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         WallPoint vertex,
                                         SurfaceProfile surfaceProfile,
                                         int attemptOffset) {
        BlockPos botPos = bot.getBlockPos();
        if (!isInsideTowerFootprint(botPos, vertex)) {
            return;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return;
        }

        BlockPos escape = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, attemptOffset);
        if (escape == null || escape.equals(botPos)) {
            return;
        }

        LOGGER.info("[FortifyTower] forced-footprint-exit from={} to={} tower=({}, {})",
                botPos.toShortString(), escape.toShortString(), vertex.x(), vertex.z());
        ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:forced-footprint-exit",
                null, vertex, escape,
                () -> ops.walkToTarget(source, bot, escape, 1_500L, "fortify-tower:forced-footprint-exit"));
        if (isInsideTowerFootprint(bot.getBlockPos(), vertex)) {
            ops.walkTowardBlock(bot, escape, 1_000L);
            if (isInsideTowerFootprint(bot.getBlockPos(), vertex)) {
                tryTowerHardResetPosition(source, bot, world, vertex, surfaceProfile, attemptOffset + 7);
            }
        }
    }

    private boolean isInsideTowerFootprint(BlockPos botPos, WallPoint vertex) {
        return Math.abs(botPos.getX() - vertex.x()) <= 1 && Math.abs(botPos.getZ() - vertex.z()) <= 1;
    }

    private BlockPos chooseTowerEscapePos(ServerPlayerEntity bot,
                                          ServerWorld world,
                                          WallPoint vertex,
                                          SurfaceProfile surfaceProfile,
                                          int attemptOffset) {
        // Tower footprint is vertex ±1 (3×3). Min offset 3 = 1 block clearance from edge.
        int[][] candidates = {
                {3, 0}, {-3, 0}, {0, 3}, {0, -3},
                {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
                {1, 3}, {-1, 3}, {1, -3}, {-1, -3},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}
        };
        int startIndex = Math.floorMod(attemptOffset, candidates.length);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos botPos = bot.getBlockPos();

        for (int i = 0; i < candidates.length; i++) {
            int[] offset = candidates[(startIndex + i) % candidates.length];
            int x = vertex.x() + offset[0];
            int z = vertex.z() + offset[1];
            int y = ctx.safeSurfaceY(surfaceProfile, world, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!ctx.canStandAt(world, candidate)) {
                continue;
            }
            int exits = ctx.countOpenExits(world, candidate, null);
            if (exits < 2) {
                continue;
            }
            double distFromTowerSq = Math.pow(candidate.getX() - vertex.x(), 2) + Math.pow(candidate.getZ() - vertex.z(), 2);
            double distFromBotSq = botPos.getSquaredDistance(candidate);
            double score = distFromTowerSq * 30.0 + exits * 80.0 - distFromBotSq * 4.0 + i;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            return best;
        }
        return SafePositionService.findSafeNear(world, botPos, 3);
    }

    private boolean tryTowerHardResetPosition(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              SurfaceProfile surfaceProfile,
                                              int attemptOffset) {
        return tryTowerHardResetPositionDetailed(source, bot, world, vertex, surfaceProfile, attemptOffset, null).moved();
    }

    private boolean tryTowerHardResetPosition(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              SurfaceProfile surfaceProfile,
                                              int attemptOffset,
                                              TowerNavAttemptState navState) {
        return tryTowerHardResetPositionDetailed(source, bot, world, vertex, surfaceProfile, attemptOffset, navState).moved();
    }

    private TowerHardResetResult tryTowerHardResetPositionDetailed(ServerCommandSource source,
                                                                   ServerPlayerEntity bot,
                                                                   ServerWorld world,
                                                                   WallPoint vertex,
                                                                   SurfaceProfile surfaceProfile,
                                                                   int attemptOffset,
                                                                   TowerNavAttemptState navState) {
        BlockPos start = bot.getBlockPos();
        TowerNavCandidate bestCandidate = selectReachableTowerHardResetCandidate(
                bot, world, vertex, surfaceProfile, attemptOffset, navState);
        BlockPos best = bestCandidate != null ? bestCandidate.pos() : null;
        if (best == null) {
            best = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, attemptOffset + 9);
            if (best != null) {
                LOGGER.info("[FortifyTower] hard-reset-select tower=({}, {}) fallback={} reason=escape-pos",
                        vertex.x(), vertex.z(), best.toShortString());
            }
        }
        if (best == null || best.equals(start)) {
            if (navState != null && best != null) {
                navState.recordHardResetFailure(best);
            }
            return new TowerHardResetResult(false, false);
        }

        if (bestCandidate != null) {
            LOGGER.info("[FortifyTower] hard-reset-select tower=({}, {}) pos={} score={} exits={} locallyReachable={} prevFailed={}",
                    vertex.x(), vertex.z(), best.toShortString(),
                    String.format(Locale.ROOT, "%.1f", bestCandidate.score()),
                    bestCandidate.exits(), bestCandidate.locallyReachable(), bestCandidate.previouslyFailed());
        }
        LOGGER.info("[FortifyTower] hard-reset tower=({}, {}) from={} to={}",
                vertex.x(), vertex.z(), start.toShortString(), best.toShortString());
        double beforeTargetDistSq = bot.getBlockPos().getSquaredDistance(best);
        long hardResetStartMs = System.currentTimeMillis();
        FortifyNavRuntimeScope priorScope = ctx.beginFortifyNavScope(
                "fortify-tower:hard-reset", navState, vertex, best, true, false);
        try {
            ops.walkToTarget(source, bot, best, 2_300L, "fortify-tower:hard-reset");
        } finally {
            ctx.endFortifyNavScope(bot, world, priorScope);
        }
        if (start.equals(bot.getBlockPos())) {
            ops.walkTowardBlock(bot, best, 1_000L);
        }
        FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                start, bot.getBlockPos(), beforeTargetDistSq, bot.getBlockPos().getSquaredDistance(best),
                System.currentTimeMillis() - hardResetStartMs);
        boolean moved = !start.equals(bot.getBlockPos());
        boolean meaningful = progress.meaningful();
        if (!moved && navState != null) {
            navState.recordHardResetFailure(best);
            navState.noteNoRealProgress();
        } else if (navState != null) {
            navState.noteMovement(start, bot.getBlockPos(), meaningful);
            if (meaningful) navState.noteRealProgress(); else navState.noteNoRealProgress();
            if (!meaningful) {
                navState.recordHardResetFailure(best);
            }
        }
        LOGGER.info("[FortifyTower] hard-reset-result tower=({}, {}) target={} moved={} meaningful={} distDelta={} netDisp={}",
                vertex.x(), vertex.z(), best.toShortString(), moved, meaningful,
                String.format(Locale.ROOT, "%.1f", progress.targetDeltaBlocks()),
                String.format(Locale.ROOT, "%.1f", progress.netDisplacementBlocks()));
        return new TowerHardResetResult(moved, meaningful);
    }

    private boolean moveToTowerApproach(ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        ServerWorld world,
                                        WallPoint vertex,
                                        BlockPos towerApproach,
                                        SurfaceProfile surfaceProfile,
                                        String context,
                                        TowerNavAttemptState navState) {
        if (towerApproach == null) {
            return false;
        }
        Vec3d approachVec = Vec3d.ofCenter(towerApproach);
        if (bot.squaredDistanceTo(approachVec) <= 9.0) {
            return true;
        }

        // Avoid blocking A* pathfinding here; a long tower-approach solve can make fortify look "stuck"
        // before it has placed its first block. Use bounded local movement + unwedge attempts instead.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            BlockPos cycleStart = bot.getBlockPos();
            double beforeTargetDistSq = bot.squaredDistanceTo(approachVec);
            long cycleStartMs = System.currentTimeMillis();
            FortifyNavRuntimeScope priorScope = ctx.beginFortifyNavScope(
                    "fortify-tower:approach", navState, vertex, towerApproach, true, false);
            long walkBudgetMs = bot.squaredDistanceTo(approachVec) > 196.0D ? 2_200L : 1_200L;
            try {
                ops.walkToTarget(source, bot, towerApproach, walkBudgetMs, "fortify-tower:approach");
            } finally {
                ctx.endFortifyNavScope(bot, world, priorScope);
            }
            if (bot.squaredDistanceTo(approachVec) <= 9.0D) {
                FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                        cycleStart, bot.getBlockPos(), beforeTargetDistSq, bot.squaredDistanceTo(approachVec),
                        System.currentTimeMillis() - cycleStartMs);
                if (navState != null) {
                    navState.noteMovement(cycleStart, bot.getBlockPos(), progress.meaningful());
                    if (progress.meaningful()) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                return true;
            }

            boolean unwedged = ctx.tryUnwedgeFromTightSpace(
                    source,
                    bot,
                    world,
                    surfaceProfile,
                    towerApproach,
                    context + ":unwedge-" + (attempt + 1)
            );
            if (!unwedged) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2 + attempt);
                if (safe != null && !safe.equals(bot.getBlockPos())) {
                    ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:approach-safe-nudge",
                            navState, vertex, safe,
                            () -> ops.walkToTarget(source, bot, safe, 900L, "fortify-tower:approach-safe-nudge"));
                }
            }

            if (bot.squaredDistanceTo(approachVec) > 9.0D) {
                ops.walkTowardBlock(bot, towerApproach, 900L + (attempt * 300L));
            }
            BlockPos cycleEnd = bot.getBlockPos();
            FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                    cycleStart, cycleEnd, beforeTargetDistSq, bot.squaredDistanceTo(approachVec),
                    System.currentTimeMillis() - cycleStartMs);
            boolean movedThisCycle = !cycleStart.equals(cycleEnd);
            boolean meaningfulProgress = progress.meaningful();
            int samePosCount = 0;
            if (navState != null) {
                if (meaningfulProgress) {
                    navState.noteMovement(cycleStart, cycleEnd, true);
                    navState.noteRealProgress();
                } else {
                    navState.noteNoRealProgress();
                    samePosCount = navState.noteStuckPosition(cycleEnd);
                    LOGGER.info("[FortifyTower] approach-stuck tower=({}, {}) pos={} target={} samePosCount={} elapsedMs={} attempt={}",
                            vertex.x(), vertex.z(), cycleEnd.toShortString(), towerApproach.toShortString(),
                            samePosCount, navState.stuckElapsedMs(), attempt + 1);
                }
            }

            if (bot.squaredDistanceTo(approachVec) > 9.0D
                    && (!meaningfulProgress || samePosCount >= 2)) {
                BlockPos replanTarget = towerApproach;
                if (!isLocallyReachableStandPos(world, bot.getBlockPos(), towerApproach,
                        new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z()),
                        TOWER_LOCAL_REACHABILITY_RADIUS)) {
                    BlockPos staging = chooseReachableTowerPatchStagingPos(bot, world, vertex, towerApproach, surfaceProfile, navState);
                    if (staging != null) {
                        replanTarget = staging;
                    }
                }
                boolean replanned = attemptTowerMediumRangeReplan(source, bot, world, replanTarget,
                        "tower=(" + vertex.x() + "," + vertex.z() + ") " + context + " attempt=" + (attempt + 1),
                        vertex, navState);
                if (replanned && navState != null) {
                    double postReplanDistSq = bot.squaredDistanceTo(approachVec);
                    FortifyNavProgressWindow postReplanProgress = new FortifyNavProgressWindow(
                            cycleStart, bot.getBlockPos(), beforeTargetDistSq, postReplanDistSq,
                            System.currentTimeMillis() - cycleStartMs);
                    navState.noteMovement(cycleStart, bot.getBlockPos(), postReplanProgress.meaningful());
                    if (postReplanProgress.meaningful()) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                if (bot.squaredDistanceTo(approachVec) <= 16.0D) {
                    return true;
                }
            }

            LOGGER.info("[FortifyTower] approach-cycle tower=({}, {}) attempt={} target={} moved={} meaningful={} dist={} distDelta={} netDisp={}",
                    vertex.x(), vertex.z(), attempt + 1, towerApproach.toShortString(), movedThisCycle, meaningfulProgress,
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(bot.squaredDistanceTo(approachVec))),
                    String.format(Locale.ROOT, "%.1f", progress.targetDeltaBlocks()),
                    String.format(Locale.ROOT, "%.1f", progress.netDisplacementBlocks()));
            if (bot.squaredDistanceTo(approachVec) <= 16.0D) {
                if (navState != null) {
                    navState.noteMovement(cycleStart, bot.getBlockPos(), meaningfulProgress);
                    if (meaningfulProgress) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                return true;
            }
        }

        if (navState != null) {
            navState.recordApproachFailure(towerApproach);
        }
        return false;
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0, null, null);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attemptOffset, null, null);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset,
                                            List<ProceduralWallBlock> vertexBlocks,
                                            TowerNavAttemptState navState) {
        return selectReachableTowerApproach(bot, world, vertex, surfaceProfile, attemptOffset, vertexBlocks, navState);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset,
                                            List<ProceduralWallBlock> vertexBlocks) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attemptOffset, vertexBlocks, null);
    }

    private BlockPos selectReachableTowerApproach(ServerPlayerEntity bot, ServerWorld world,
                                                  WallPoint vertex, SurfaceProfile surfaceProfile,
                                                  int attemptOffset,
                                                  List<ProceduralWallBlock> vertexBlocks,
                                                  TowerNavAttemptState navState) {
        // Tower footprint is vertex ±1 (3×3). Min offset 3 = 1 block clearance from edge.
        int[][] candidates = {
                {3, 0}, {-3, 0}, {0, 3}, {0, -3},
                {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
                {1, 3}, {-1, 3}, {1, -3}, {-1, -3},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}
        };

        Set<BlockPos> locallyReachable = computeLocalReachableStandPositions(
                world, bot.getBlockPos(), new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS, bot.getBlockPos().getY() - 1, bot.getBlockPos().getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);

        List<TowerNavCandidate> preferredCandidates = new ArrayList<>();
        List<TowerNavCandidate> shallowRetryCandidates = new ArrayList<>();
        List<TowerNavCandidate> shallowFallbackCandidates = new ArrayList<>();
        List<TowerNavCandidate> deepFallbackCandidates = new ArrayList<>();
        int badHeadroomOrStand = 0;
        int noFloor = 0;
        int insideFootprint = 0;
        int notLocallyReachable = 0;
        int losZero = 0;
        int lowExits = 0;
        int tooDeep = 0;
        // Track best clearable-headroom candidate for fallback when all normal candidates fail.
        // These are positions with floor support where we could mine the feet/head blocks.
        BlockPos bestClearableHeadroomPos = null;
        double bestClearableHeadroomScore = Double.NEGATIVE_INFINITY;
        int startIndex = Math.floorMod(attemptOffset, candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            int[] c = candidates[(startIndex + i) % candidates.length];
            int x = vertex.x() + c[0];
            int z = vertex.z() + c[1];
            int safeY = ctx.safeSurfaceY(surfaceProfile, world, x, z);
            LinkedHashSet<Integer> yCandidates = new LinkedHashSet<>();
            for (int dy = -2; dy <= 2; dy++) yCandidates.add(safeY + dy);
            for (int dy = -1; dy <= 1; dy++) yCandidates.add(bot.getBlockPos().getY() + dy);
            BlockPos safeCol = SafePositionService.findSafeColumn(world, new BlockPos(x, safeY, z), -3, 3);
            if (safeCol != null) yCandidates.add(safeCol.getY());

            for (int y : yCandidates) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState feet = world.getBlockState(pos);
                BlockState head = world.getBlockState(pos.up());
                BlockState below = world.getBlockState(pos.down());
                boolean feetClear = feet.isAir() || feet.isReplaceable();
                boolean headClear = head.isAir() || head.isReplaceable();
                boolean support = !below.isAir() && !below.isReplaceable();
                if (!(feetClear && headClear && support)) {
                    if (!support) {
                        noFloor++;
                    } else {
                        badHeadroomOrStand++;
                        // Track clearable headroom candidates: has floor, but feet/head need mining.
                        // Only consider if blocks are mineable (hardness >= 0, not bedrock etc.).
                        boolean feetMineable = feetClear || (feet.getHardness(world, pos) >= 0);
                        boolean headMineable = headClear || (head.getHardness(world, pos.up()) >= 0);
                        if (feetMineable && headMineable && !isInsideTowerFootprint(pos, vertex)) {
                            int safeYH = ctx.safeSurfaceY(surfaceProfile, world, pos.getX(), pos.getZ());
                            int depthH = Math.max(0, safeYH - y);
                            if (depthH <= 1) {
                                double distSqH = bot.squaredDistanceTo(pos.getX() + 0.5, y, pos.getZ() + 0.5);
                                double scoreH = -distSqH - depthH * 220.0;
                                if (scoreH > bestClearableHeadroomScore) {
                                    bestClearableHeadroomScore = scoreH;
                                    bestClearableHeadroomPos = pos;
                                }
                            }
                        }
                    }
                    continue;
                }
                if (isInsideTowerFootprint(pos, vertex)) {
                    insideFootprint++;
                    continue;
                }
                int exits = ctx.countOpenExits(world, pos, null);
                if (exits < MIN_APPROACH_OPEN_EXITS) {
                    lowExits++;
                    continue;
                }
                boolean locallyReachableCandidate = isLocallyReachableStandPos(locallyReachable, pos);
                if (!locallyReachableCandidate) {
                    notLocallyReachable++;
                }
                int depthBelowSurface = Math.max(0, safeY - y);
                if (depthBelowSurface > 1) {
                    tooDeep++;
                }
                double distSq = bot.squaredDistanceTo(x + 0.5, y, z + 0.5);
                int losReachable = 0;
                if (vertexBlocks != null && !vertexBlocks.isEmpty()) {
                    losReachable = countReachableWithLOS(world, bot, pos, vertexBlocks);
                    if (losReachable == 0) {
                        losZero++;
                        continue;
                    }
                }
                double score = exits * 120.0 + losReachable * 200.0 - distSq;
                score -= depthBelowSurface * 220.0;
                if (depthBelowSurface > 1) {
                    score -= 1_800.0 + (depthBelowSurface - 1) * 500.0;
                }
                if (!locallyReachableCandidate) {
                    score -= 900.0;
                }
                if (attemptOffset > 0) {
                    score += i * 6.0;
                }
                boolean previouslyFailed = navState != null && navState.failedApproachCandidates.contains(pos);
                if (previouslyFailed) {
                    score -= 200.0;
                }
                TowerNavCandidate candidate = new TowerNavCandidate(pos, score, exits, locallyReachableCandidate, previouslyFailed);
                boolean shallowEnough = depthBelowSurface <= 1;
                if (shallowEnough && locallyReachableCandidate && !previouslyFailed) {
                    preferredCandidates.add(candidate);
                } else if (shallowEnough && locallyReachableCandidate) {
                    shallowRetryCandidates.add(candidate);
                } else if (shallowEnough) {
                    shallowFallbackCandidates.add(candidate);
                } else {
                    deepFallbackCandidates.add(candidate);
                }
            }
        }

        TowerNavCandidate best = chooseBestTowerNavCandidate(preferredCandidates);
        if (best == null) {
            best = chooseBestTowerNavCandidate(shallowRetryCandidates);
        }
        if (best == null) {
            best = chooseBestTowerNavCandidate(shallowFallbackCandidates);
        }
        if (best == null) {
            best = chooseBestTowerNavCandidate(deepFallbackCandidates);
        }

        if (best != null) {
            int bestSurfaceY = ctx.safeSurfaceY(surfaceProfile, world, best.pos().getX(), best.pos().getZ());
            int bestDepth = Math.max(0, bestSurfaceY - best.pos().getY());
            LOGGER.info("[FortifyTower] approach-select tower=({}, {}) pos={} score={} exits={} locallyReachable={} prevFailed={} depth={}",
                    vertex.x(), vertex.z(), best.pos().toShortString(),
                    String.format(Locale.ROOT, "%.1f", best.score()),
                    best.exits(), best.locallyReachable(), best.previouslyFailed(), bestDepth);
            return best.pos();
        }
        // Headroom-clearing fallback: if all candidates were rejected due to bad headroom,
        // try to mine the obstructing blocks at the best clearable position.
        if (bestClearableHeadroomPos != null) {
            BlockPos clearPos = bestClearableHeadroomPos;
            BlockState clFeet = world.getBlockState(clearPos);
            BlockState clHead = world.getBlockState(clearPos.up());
            boolean cleared = false;
            if (!clFeet.isAir() && !clFeet.isReplaceable() && clFeet.getHardness(world, clearPos) >= 0) {
                ops.digBlock(bot, world, clearPos);
                ops.sleepQuiet(80);
                cleared = true;
            }
            if (!clHead.isAir() && !clHead.isReplaceable() && clHead.getHardness(world, clearPos.up()) >= 0) {
                ops.digBlock(bot, world, clearPos.up());
                ops.sleepQuiet(80);
                cleared = true;
            }
            if (cleared) {
                LOGGER.info("[FortifyTower] approach-select tower=({}, {}) cleared-headroom at {} bad_headroom={} no_floor={}",
                        vertex.x(), vertex.z(), clearPos.toShortString(), badHeadroomOrStand, noFloor);
                return clearPos;
            }
        }
        int y = ctx.safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        BlockPos fallback = new BlockPos(vertex.x(), y, vertex.z());
        LOGGER.info("[FortifyTower] approach-select tower=({}, {}) fallback={} reason=no-standable-candidate bad_headroom={} no_floor={} inside_footprint={} not_locally_reachable={} los_zero={} low_exits={} too_deep={}",
                vertex.x(), vertex.z(), fallback.toShortString(),
                badHeadroomOrStand, noFloor, insideFootprint, notLocallyReachable, losZero, lowExits, tooDeep);
        return fallback;
    }

    private TowerNavCandidate chooseBestTowerNavCandidate(List<TowerNavCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        TowerNavCandidate best = null;
        for (TowerNavCandidate c : candidates) {
            if (c == null) continue;
            if (best == null || c.score() > best.score()) {
                best = c;
            }
        }
        return best;
    }

    private Set<BlockPos> computeLocalReachableStandPositions(ServerWorld world,
                                                              BlockPos start,
                                                              BlockPos areaCenter,
                                                              int horizRadius,
                                                              int minY,
                                                              int maxY,
                                                              int maxNodes) {
        return VoxelJunctionService.computeReachableStandCells(
                world, start, areaCenter, horizRadius, minY, maxY, maxNodes,
                seed -> SafePositionService.findSafeNear(world, seed, 2));
    }

    private boolean isLocallyReachableStandPos(Set<BlockPos> reachable, BlockPos candidate) {
        if (candidate == null || reachable == null || reachable.isEmpty()) {
            return false;
        }
        if (reachable.contains(candidate)) {
            return true;
        }
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos probe = candidate.add(0, dy, 0);
            if (reachable.contains(probe)) return true;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (reachable.contains(probe.offset(dir))) return true;
            }
        }
        return false;
    }

    private boolean isLocallyReachableStandPos(ServerWorld world, BlockPos start, BlockPos candidate,
                                               BlockPos areaCenter, int horizRadius) {
        if (world == null || start == null || candidate == null || areaCenter == null) {
            return false;
        }
        Set<BlockPos> reachable = computeLocalReachableStandPositions(
                world, start, areaCenter, horizRadius, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);
        return isLocallyReachableStandPos(reachable, candidate);
    }

    private TowerNavCandidate selectReachableTowerHardResetCandidate(ServerPlayerEntity bot,
                                                                     ServerWorld world,
                                                                     WallPoint vertex,
                                                                     SurfaceProfile surfaceProfile,
                                                                     int attemptOffset,
                                                                     TowerNavAttemptState navState) {
        BlockPos start = bot.getBlockPos();
        int ringStart = 3 + Math.floorMod(attemptOffset, 2);
        Set<BlockPos> locallyReachable = computeLocalReachableStandPositions(
                world, start, new BlockPos(vertex.x(), start.getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS + 2, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);

        for (int r = ringStart; r <= 7; r++) {
            List<TowerNavCandidate> reachableRing = new ArrayList<>();
            List<TowerNavCandidate> fallbackRing = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = vertex.x() + dx;
                    int z = vertex.z() + dz;
                    int baseY = ctx.safeSurfaceY(surfaceProfile, world, x, z);
                    int[] yCandidates = {baseY, baseY + 1, start.getY(), start.getY() + 1};
                    for (int y : yCandidates) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!ctx.canStandAt(world, candidate)) continue;
                        if (isInsideTowerFootprint(candidate, vertex)) continue;
                        int exits = ctx.countOpenExits(world, candidate, null);
                        if (exits < 3) continue;
                        double distFromTowerSq = Math.pow(candidate.getX() - vertex.x(), 2)
                                + Math.pow(candidate.getZ() - vertex.z(), 2);
                        if (distFromTowerSq < 9.0D) continue;
                        boolean locallyReachableCandidate = isLocallyReachableStandPos(locallyReachable, candidate);
                        boolean prevFailed = navState != null && navState.failedHardResetCandidates.contains(candidate);
                        double score = exits * 140.0 + distFromTowerSq * 16.0 - start.getSquaredDistance(candidate) * 4.5;
                        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) score += 20.0;
                        if (prevFailed) score -= 220.0;
                        TowerNavCandidate t = new TowerNavCandidate(candidate, score, exits, locallyReachableCandidate, prevFailed);
                        if (locallyReachableCandidate && !prevFailed) {
                            reachableRing.add(t);
                        } else {
                            fallbackRing.add(t);
                        }
                    }
                }
            }
            TowerNavCandidate bestReachable = chooseBestTowerNavCandidate(reachableRing);
            if (bestReachable != null) {
                return bestReachable;
            }
            TowerNavCandidate bestFallback = chooseBestTowerNavCandidate(fallbackRing);
            if (bestFallback != null) {
                return bestFallback;
            }
        }
        return null;
    }

    private BlockPos chooseReachableTowerPatchStagingPos(ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         WallPoint vertex,
                                                         BlockPos towerApproach,
                                                         SurfaceProfile surfaceProfile,
                                                         TowerNavAttemptState navState) {
        if (bot == null || world == null || towerApproach == null || vertex == null) {
            return null;
        }
        BlockPos start = bot.getBlockPos();
        Set<BlockPos> reachable = computeLocalReachableStandPositions(
                world, start, new BlockPos(vertex.x(), start.getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS + 2, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int r = 2; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = towerApproach.getX() + dx;
                    int z = towerApproach.getZ() + dz;
                    int baseY = ctx.safeSurfaceY(surfaceProfile, world, x, z);
                    int[] yCandidates = {baseY, start.getY(), baseY + 1};
                    for (int y : yCandidates) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!ctx.canStandAt(world, candidate)) continue;
                        if (isInsideTowerFootprint(candidate, vertex)) continue;
                        if (!isLocallyReachableStandPos(reachable, candidate)) continue;
                        if (navState != null && navState.failedHardResetCandidates.contains(candidate)) continue;
                        int exits = ctx.countOpenExits(world, candidate, null);
                        if (exits < 2) continue;
                        double score = exits * 100.0;
                        score -= candidate.getSquaredDistance(towerApproach) * 8.0;
                        score -= start.getSquaredDistance(candidate) * 2.5;
                        if (candidate.getSquaredDistance(towerApproach) < start.getSquaredDistance(towerApproach)) {
                            score += 60.0;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) break;
        }
        if (best != null) {
            LOGGER.info("[FortifyTower] staging-select tower=({}, {}) target={} staging={}",
                    vertex.x(), vertex.z(), towerApproach.toShortString(), best.toShortString());
        }
        return best;
    }

    private boolean attemptTowerMediumRangeReplan(ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos target,
                                                  String reason,
                                                  WallPoint towerVertex,
                                                  TowerNavAttemptState towerState) {
        if (source == null || bot == null || world == null || target == null) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        double distSq = bot.squaredDistanceTo(target.getX() + 0.5, bot.getY(), target.getZ() + 0.5);
        if (distSq < 16.0D || distSq > 400.0D) { // fortify patch local only (<=20 blocks)
            return false;
        }
        if (ctx.isFortifyReplanActive()) {
            LOGGER.info("[FortifyTower] medium-replan skipped target={} reason={} nested-replan-active",
                    target.toShortString(), reason);
            return false;
        }

        // Prefer local stepping for very close targets; avoids long blocking MovementService calls.
        if (distSq <= 144.0D) { // <= 12 blocks
            LOGGER.info("[FortifyTower] medium-replan using local-step target={} reason={}",
                    target.toShortString(), reason);
            BlockPos beforeLocal = bot.getBlockPos();
            ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:local-step-replan",
                    towerState, towerVertex, target,
                    () -> ops.walkToTarget(source, bot, target, 1_500L, "fortify-tower:local-step-replan"));
            FortifyNavProgressWindow localProgress = new FortifyNavProgressWindow(
                    beforeLocal, bot.getBlockPos(), beforeLocal.getSquaredDistance(target),
                    bot.getBlockPos().getSquaredDistance(target), 1_500L);
            return localProgress.meaningful();
        }

        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) {
            LOGGER.info("[FortifyTower] medium-replan skipped target={} reason={} no-plan",
                    target.toShortString(), reason);
            return false;
        }
        LOGGER.info("[FortifyTower] medium-replan target={} reason={} dist={}",
                target.toShortString(), reason, String.format(Locale.ROOT, "%.1f", Math.sqrt(distSq)));
        long epoch = ctx.bumpFortifyMovementEpoch();
        long startMs = System.currentTimeMillis();
        ctx.setFortifyReplanActive(true);
        try {
            MovementService.withoutDoorEscape(() ->
                    MovementService.withoutObstructionMining(
                            () -> MovementService.execute(source, bot, plan.get(), null)));
        } finally {
            ctx.setFortifyReplanActive(false);
        }
        long elapsed = System.currentTimeMillis() - startMs;
        if (epoch != ctx.getFortifyMovementEpoch()) {
            LOGGER.info("[FortifyTower] medium-replan ignored target={} reason={} stale-epoch", target.toShortString(), reason);
            return false;
        }
        boolean moved = !before.equals(bot.getBlockPos());
        FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                before, bot.getBlockPos(), before.getSquaredDistance(target),
                bot.getBlockPos().getSquaredDistance(target), elapsed);
        if (moved) {
            ctx.runWithFortifyTowerNavScope(bot, world, "fortify-tower:post-replan",
                    towerState, towerVertex, target,
                    () -> ops.walkToTarget(source, bot, target, 1_200L, "fortify-tower:post-replan"));
        }
        if (elapsed > FORTIFY_MEDIUM_REPLAN_BUDGET_MS && !progress.meaningful()) {
            LOGGER.info("[FortifyTower] medium-replan over-budget target={} elapsedMs={} meaningful=false",
                    target.toShortString(), elapsed);
            return false;
        }
        return progress.meaningful();
    }

    int countReachableWithLOS(ServerWorld world, ServerPlayerEntity bot,
                                       BlockPos standPos, List<ProceduralWallBlock> vertexBlocks) {
        Vec3d eye = Vec3d.ofCenter(standPos).add(0, 1.12, 0); // 0.5 + 1.12 = 1.62 eye height
        int count = 0;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (!ctx.isActiveFortifyBlock(block)) continue;
            if (ctx.isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
            Vec3d blockCenter = Vec3d.ofCenter(block.worldPos());
            if (eye.squaredDistanceTo(blockCenter) > REACH_DISTANCE_SQ) continue;
            if (ops.hasLineOfSight(world, bot, eye, block.worldPos())) count++;
        }
        return count;
    }
}
