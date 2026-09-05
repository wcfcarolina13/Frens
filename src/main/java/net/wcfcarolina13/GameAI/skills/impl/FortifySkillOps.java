package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.ProceduralWallBlock;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.WallPoint;
import net.wcfcarolina13.GameAI.services.construction.execution.ExecutionPolicy;
import net.wcfcarolina13.GameAI.services.construction.execution.PlacementTarget;

import java.util.List;
import java.util.Set;

/**
 * Tiered callback interfaces that let extracted helper classes call back into
 * {@link FortifyVillageSkill} primitives without taking a direct dependency
 * on the 9000-line skill class.
 *
 * <p>Tier 1 ({@link FortifyBlockOps}) covers block queries, digging, sleeping,
 * and abort/overhead — enough for helpers that only need local world mutation.
 *
 * <p>Tier 2 ({@link FortifyNavOps}) adds navigation (walk, scaffold, escape,
 * reach-with-effort) — needed by helpers that move the bot around the world.
 *
 * <p>{@link FortifySharedContext} exposes skill-level queries and cleanup entry
 * points shared by several extracted processors; {@link FortifyTowerContext}
 * extends it with the nav-scope / placement / replan state the tower code needs.
 */
final class FortifySkillOps {
    private FortifySkillOps() {} // non-instantiable container

    // ── Constants shared by the skill and its extracted helpers ──
    static final double REACH_DISTANCE_SQ = 20.25D;
    static final int BLOCK_PLACE_DELAY_MS = 50;
    static final int MIN_APPROACH_OPEN_EXITS = 2;
    static final int FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST = 10;

    // ── Tier 1: Block-level primitives ─────────────────────────

    interface FortifyBlockOps {

        /** Thread.sleep wrapper that swallows InterruptedException. */
        void sleepQuiet(long ms);

        /** Mine a single block; returns true if removed or already air. */
        boolean digBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos);

        /** Mine a single block for navigation break-through. */
        boolean digBlockForNavigation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos);

        /** Eye-based reach check (4.5-block squared distance). */
        boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos);

        /** Feet-based mining reach check (matches MiningTool gate). */
        boolean isWithinMiningReach(ServerPlayerEntity bot, BlockPos pos);

        /** Raycast line-of-sight from eye to target block centre. */
        boolean hasLineOfSight(ServerWorld world, ServerPlayerEntity bot, Vec3d eye, BlockPos target);

        /** Check skill abort flag; stops the bot if aborting. Returns true → caller should bail. */
        boolean abortFortifyPhase(ServerPlayerEntity bot, String phase, long phaseStartMs);

        /** Show a transient overhead hologram for fortify status. */
        void showOverhead(ServerPlayerEntity bot, String text);

        /** Read the current ephemeral fortify-nav scope (may be null). */
        FortifyNavRuntimeScope getActiveFortifyNavScope();

        /** Check whether the given scope represents a carve context. */
        boolean isFortifyCarveContext(FortifyNavRuntimeScope scope);

        /** Evaluate whether a block may be broken for navigation purposes. */
        NavBreakCandidateEval evaluateBreakForNavigation(ServerWorld world, BlockPos pos, boolean allowLayout);
    }

    // ── Tier 2: Navigation primitives (extends Tier 1) ─────────

    interface FortifyNavOps extends FortifyBlockOps {

        /** Tick-based walk toward a block (no A*, no door handling). */
        void walkTowardBlock(ServerPlayerEntity bot, BlockPos target, long timeoutMs);

        /** Walk to a target with optional nav-context tag. */
        void walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target, long timeoutMs);

        /** Walk to a target with explicit nav-context tag. */
        void walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target,
                          long timeoutMs, String navContext);

        /** Acquire sneak-lock if standing on scaffold near focus. Returns true if held. */
        boolean beginScaffoldEdgeHold(ServerPlayerEntity bot, ServerWorld world, BlockPos focusPos);

        /** Release sneak-lock acquired by beginScaffoldEdgeHold. */
        void endScaffoldEdgeHold(ServerPlayerEntity bot, boolean held);

        /** Escape from a hole below the given reference surface Y. */
        void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY);

        /** Escape from a hole using heightmap terrain Y. */
        void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world);

        /** Ensure the bot is at or above reference surface Y. */
        void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY);

        /** Ensure the bot is at or above heightmap terrain Y. */
        void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world);

        /** Multi-strategy reach helper (walk, side-approach, scaffold). */
        boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, BlockPos target,
                                              int heightAboveGround, int passNumber);

        /** Multi-strategy reach helper with explicit surface Y. */
        boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, BlockPos target,
                                              int heightAboveGround, int passNumber,
                                              int referenceSurfaceY);

        /** Multi-strategy reach helper with explicit surface Y and scaffold-failed set. */
        boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, BlockPos target,
                                              int heightAboveGround, int passNumber,
                                              int referenceSurfaceY,
                                              Set<BlockPos> scaffoldFailedPositions);
    }

    // ── Shared skill context (used by extracted processors) ────

    interface FortifySharedContext {

        /** Would repairing this block seal the bot's only current exit? */
        boolean wouldRepairSealCurrentExit(ServerPlayerEntity bot, ServerWorld world, BlockPos repairPos);

        /** Count horizontal exits from a cell, treating forcedSolidPos as solid. */
        int countOpenExits(ServerWorld world, BlockPos center, BlockPos forcedSolidPos);

        /** Is this stand cell a pocket/dead-end style trap? */
        boolean isTrapLikeCell(ServerWorld world, BlockPos pos);

        /** Can the bot stand at this position? */
        boolean canStandAt(ServerWorld world, BlockPos pos);

        /** Safe surface Y at (x,z) given the surface profile. */
        int safeSurfaceY(SurfaceProfile profile, ServerWorld world, int x, int z);

        /** Whether the planned block is active in the current layout/stage. */
        boolean isActiveFortifyBlock(ProceduralWallBlock block);

        /** Whether the current world state satisfies the planned block. */
        boolean isPlannedBlockSatisfied(ProceduralWallBlock planned, BlockState current);

        /** Count planned blocks already present in the world. */
        int countPresentBlocks(ServerWorld world, List<ProceduralWallBlock> allBlocks);

        /** Count planned blocks that are active. */
        int countActivePlannedBlocks(List<ProceduralWallBlock> blocks);

        /** Count unsatisfied active planned blocks within reach and line-of-sight of an eye at standPos. */
        int countReachableWithLOS(ServerWorld world, ServerPlayerEntity bot,
                                  BlockPos standPos, List<ProceduralWallBlock> vertexBlocks);

        /** Count building-material blocks in the bot's inventory. */
        int countBuildingBlocks(ServerPlayerEntity bot);

        /** Attempt to place a block matching targetState at pos. */
        BotActions.PlaceResult tryPlaceBlock(ServerPlayerEntity bot, ServerWorld world,
                                             BlockPos pos, BlockState targetState);

        /** Process the deferred cleanup queue (carve repairs, scaffold removal). */
        void processDeferredFortifyCleanupQueue(ServerPlayerEntity bot, ServerWorld world, String context);

        /** Try to unwedge the bot from a tight space near the anchor. */
        boolean tryUnwedgeFromTightSpace(ServerCommandSource source, ServerPlayerEntity bot,
                                         ServerWorld world, SurfaceProfile surfaceProfile,
                                         BlockPos anchorPos, String context);
    }

    // ── Tower context (extends shared context) ─────────────────

    interface FortifyTowerContext extends FortifySharedContext {

        /** Push a fortify nav scope; returns the prior scope for endFortifyNavScope. */
        FortifyNavRuntimeScope beginFortifyNavScope(String context,
                                                    TowerNavAttemptState towerState,
                                                    WallPoint towerVertex,
                                                    BlockPos target,
                                                    boolean towerPatchContext,
                                                    boolean gateContext);

        /** Pop a fortify nav scope, finalising any carve session. */
        void endFortifyNavScope(ServerPlayerEntity bot, ServerWorld world, FortifyNavRuntimeScope prior);

        /** Run an action inside a tower nav scope. */
        void runWithFortifyTowerNavScope(ServerPlayerEntity bot, ServerWorld world,
                                         String context,
                                         TowerNavAttemptState towerState,
                                         WallPoint towerVertex,
                                         BlockPos target,
                                         Runnable action);

        /** Route through the gatehouse opening if the bot is inside the hull and the target outside. */
        boolean navigateThroughGateIfNeeded(ServerCommandSource source, ServerPlayerEntity bot,
                                            ServerWorld world, BlockPos target,
                                            SurfaceProfile surfaceProfile);

        /** Execute a local placement batch; returns the number of blocks placed. */
        int executeLocalPlacementBatch(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       ServerWorld world,
                                       List<ProceduralWallBlock> blocks,
                                       String taskId,
                                       String groupId,
                                       int referenceSurfaceY,
                                       SurfaceProfile surfaceProfile,
                                       ExecutionPolicy executionPolicy,
                                       BlockPos anchorPos,
                                       PlacementTarget.TargetKind targetKind);

        /** Reposition the bot near the anchor (attempt-indexed strategy). */
        void repositionNearAnchor(ServerCommandSource source, ServerPlayerEntity bot,
                                  ServerWorld world, BlockPos anchorPos, SurfaceProfile surfaceProfile,
                                  int attempt);

        /** Try to escape a post-carve pocket toward the target. */
        boolean tryPostCarvePocketEscapeToward(ServerPlayerEntity bot, ServerWorld world, BlockPos target);

        /** Replan re-entrancy flag. */
        boolean isFortifyReplanActive();

        void setFortifyReplanActive(boolean active);

        /** Increment and return the movement epoch. */
        long bumpFortifyMovementEpoch();

        long getFortifyMovementEpoch();
    }
}
