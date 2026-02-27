package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
 */
final class FortifySkillOps {
    private FortifySkillOps() {} // non-instantiable container

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
}
