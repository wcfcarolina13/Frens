package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * Extracted escape/precipice-defense logic from {@link FortifyVillageSkill}.
 *
 * <p>Handles: precipice detection, footing-bridge patching, escape-shaft clearing,
 * pillar-escape, and hole-escape strategies. All world mutations go through the
 * {@link FortifySkillOps.FortifyBlockOps} callback interface so this helper has
 * no direct dependency on the 9000-line skill class.
 *
 * <p>Navigation primitives (walkTowardBlock) are passed as a separate
 * {@link FortifySkillOps.FortifyNavOps} reference only for methods that need movement.
 */
final class FortifyEscapeHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-escape");

    // ── Constants (moved from FortifyVillageSkill) ──────────────

    private static final int MAX_SCAFFOLD_HEIGHT = 8;
    private static final long FORTIFY_FOOTING_PATCH_COOLDOWN_MS = 400L;
    private static final int FORTIFY_PRECIPICE_DEFENSE_MIN_DROP = 2;
    private static final int FORTIFY_FOOTING_PATCH_SCAN_DEPTH = 5;
    private static final List<Item> FORTIFY_FOOTING_PATCH_MATS = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT
    );

    // ── Dependencies ────────────────────────────────────────────

    private final FortifySkillOps.FortifyBlockOps ops;
    private final FortifyEntombmentHelper entombmentHelper;
    private final Supplier<Set<BlockPos>> protectedPositionsSupplier;

    // ── Mutable state (footing-patch cooldown) ──────────────────

    private long fortifyFootingPatchLastMs = 0L;
    private BlockPos fortifyFootingPatchLastOrigin = null;

    FortifyEscapeHelper(FortifySkillOps.FortifyBlockOps ops,
                        FortifyEntombmentHelper entombmentHelper,
                        Supplier<Set<BlockPos>> protectedPositionsSupplier) {
        this.ops = Objects.requireNonNull(ops);
        this.entombmentHelper = Objects.requireNonNull(entombmentHelper);
        this.protectedPositionsSupplier = Objects.requireNonNull(protectedPositionsSupplier);
    }

    /**
     * Update the protected-positions reference.  Called by the skill when the
     * layout changes (resume, merge, etc.).
     */
    void updateProtectedPositions(Set<BlockPos> newPositions) {
        // No-op: supplier reads the skill's field dynamically.
    }

    // ── Pure utilities ──────────────────────────────────────────

    static Direction dominantHorizontalDirection(BlockPos from, BlockPos to) {
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

    // ── Precipice detection & footing ───────────────────────────

    boolean isFortifyPrecipiceDefenseContext(String navContext) {
        if (navContext == null) return false;
        return navContext.startsWith("fortify-edge:")
                || navContext.startsWith("fortify-tower:");
    }

    int airDropDepth(ServerWorld world, BlockPos pos, int maxDepth) {
        if (world == null || pos == null || maxDepth <= 0) return 0;
        int depth = 0;
        BlockPos cursor = pos;
        for (int i = 0; i < maxDepth; i++) {
            BlockState st = world.getBlockState(cursor);
            if (!(st.isAir() || st.isReplaceable())) {
                break;
            }
            depth++;
            cursor = cursor.down();
        }
        return depth;
    }

    boolean hasDangerousFortifyPrecipiceAhead(ServerPlayerEntity bot, ServerWorld world,
                                              BlockPos target, String navContext) {
        if (bot == null || world == null || target == null) return false;
        if (!isFortifyPrecipiceDefenseContext(navContext)) return false;
        BlockPos botPos = bot.getBlockPos();
        Direction forward = dominantHorizontalDirection(botPos, target);
        BlockPos front = botPos.offset(forward);
        int frontDrop = airDropDepth(world, front.down(), FORTIFY_FOOTING_PATCH_SCAN_DEPTH);
        if (frontDrop >= FORTIFY_PRECIPICE_DEFENSE_MIN_DROP) {
            return true;
        }
        Direction left = forward.rotateYCounterclockwise();
        Direction right = forward.rotateYClockwise();
        int leftDrop = airDropDepth(world, botPos.offset(left).down(), FORTIFY_FOOTING_PATCH_SCAN_DEPTH);
        int rightDrop = airDropDepth(world, botPos.offset(right).down(), FORTIFY_FOOTING_PATCH_SCAN_DEPTH);
        return leftDrop >= (FORTIFY_PRECIPICE_DEFENSE_MIN_DROP + 1)
                || rightDrop >= (FORTIFY_PRECIPICE_DEFENSE_MIN_DROP + 1);
    }

    boolean tryPatchFortifyFootingNearWorksite(ServerPlayerEntity bot, ServerWorld world,
                                               BlockPos target, String navContext, String reason) {
        if (bot == null || world == null || target == null) return false;
        if (!isFortifyPrecipiceDefenseContext(navContext)) return false;

        long now = System.currentTimeMillis();
        BlockPos origin = bot.getBlockPos();
        if (fortifyFootingPatchLastOrigin != null
                && fortifyFootingPatchLastOrigin.getSquaredDistance(origin) <= 4.0D
                && (now - fortifyFootingPatchLastMs) < FORTIFY_FOOTING_PATCH_COOLDOWN_MS) {
            return false;
        }

        Direction forward = dominantHorizontalDirection(origin, target);
        Direction left = forward.rotateYCounterclockwise();
        Direction right = forward.rotateYClockwise();
        LinkedHashSet<BlockPos> supportCandidates = new LinkedHashSet<>();
        supportCandidates.add(origin.down());
        supportCandidates.add(origin.offset(forward).down());
        supportCandidates.add(origin.offset(forward, 2).down());
        supportCandidates.add(origin.offset(left).down());
        supportCandidates.add(origin.offset(right).down());
        supportCandidates.add(origin.offset(forward).offset(left).down());
        supportCandidates.add(origin.offset(forward).offset(right).down());

        int placed = 0;
        for (BlockPos supportPos : supportCandidates) {
            if (supportPos == null) continue;
            if (protectedPositionsSupplier.get().contains(supportPos)) continue;
            BlockState state = world.getBlockState(supportPos);
            if (!(state.isAir() || state.isReplaceable())) continue;

            int dropDepth = airDropDepth(world, supportPos, FORTIFY_FOOTING_PATCH_SCAN_DEPTH);
            if (dropDepth < FORTIFY_PRECIPICE_DEFENSE_MIN_DROP && !supportPos.equals(origin.down())) {
                continue;
            }

            BlockPos standFeet = supportPos.up();
            BlockPos standHead = standFeet.up();
            BlockState feetState = world.getBlockState(standFeet);
            BlockState headState = world.getBlockState(standHead);
            boolean feetClear = feetState.isAir() || feetState.isReplaceable() || standFeet.equals(origin);
            boolean headClear = headState.isAir() || headState.isReplaceable();
            if (!feetClear || !headClear) continue;

            LookController.faceBlock(bot, supportPos);
            BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(bot, supportPos, Direction.UP, FORTIFY_FOOTING_PATCH_MATS);
            if (place.success()) {
                placed++;
                LOGGER.info("[FortifyNav] footing-bridge placed pos={} reason={} ctx={} dropDepth={} origin={} target={}",
                        supportPos.toShortString(),
                        reason != null ? reason : "none",
                        navContext,
                        dropDepth,
                        origin.toShortString(),
                        target.toShortString());
                if (placed >= 2) break;
            }
        }

        if (placed > 0) {
            fortifyFootingPatchLastMs = now;
            fortifyFootingPatchLastOrigin = origin.toImmutable();
            return true;
        }
        return false;
    }

    // ── Escape context detection ────────────────────────────────

    boolean isFortifyEscapeContext(String contextTag) {
        FortifyNavRuntimeScope scope = ops.getActiveFortifyNavScope();
        if (scope != null && ops.isFortifyCarveContext(scope)) {
            return true;
        }
        if (contextTag == null) {
            return false;
        }
        return contextTag.startsWith("fortify-edge:") || contextTag.startsWith("fortify-tower:");
    }

    // ── Escape shaft helpers ────────────────────────────────────

    int countEscapeShaftBlockers(ServerPlayerEntity bot, ServerWorld world, int stepsToClimb) {
        if (bot == null || world == null) return 0;
        int planned = Math.max(1, Math.min(MAX_SCAFFOLD_HEIGHT, stepsToClimb));
        int maxDy = Math.max(3, planned + 3);
        int blockers = 0;
        BlockPos botPos = bot.getBlockPos();
        for (int dy = 2; dy <= maxDy; dy++) {
            BlockPos pos = botPos.up(dy);
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isReplaceable()) {
                continue;
            }
            blockers++;
        }
        return blockers;
    }

    int clearEscapeShaftHeadroom(ServerPlayerEntity bot, ServerWorld world, int stepsToClimb, String contextTag) {
        if (bot == null || world == null) return 0;
        boolean fortifyCtx = isFortifyEscapeContext(contextTag);
        int planned = Math.max(1, Math.min(MAX_SCAFFOLD_HEIGHT, stepsToClimb));
        int maxDy = Math.max(3, planned + 3);
        int cleared = 0;
        BlockPos botPos = bot.getBlockPos();
        for (int dy = 2; dy <= maxDy; dy++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            BlockPos pos = botPos.up(dy);
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isReplaceable()) continue;
            if (state.getHardness(world, pos) < 0) continue;

            boolean dug = false;
            if (fortifyCtx && ops.isWithinMiningReach(bot, pos)) {
                NavBreakCandidateEval eval = ops.evaluateBreakForNavigation(world, pos, true);
                if (eval.allowed()) {
                    dug = ops.digBlockForNavigation(bot, world, pos);
                }
            }
            if (!dug) {
                dug = ops.digBlock(bot, world, pos);
            }
            if (dug) {
                cleared++;
                ops.sleepQuiet(60);
            }
        }
        return cleared;
    }

    int clearImmediateOverheadForEscape(ServerPlayerEntity bot, ServerWorld world) {
        return clearEscapeShaftHeadroom(bot, world, 1, null);
    }

    // ── Pillar escape ───────────────────────────────────────────

    /**
     * Attempt to pillar-escape upward to the reference surface Y.
     * Falls back to horizontal shift + retry if vertical shaft is blocked.
     * Requires a {@link FortifySkillOps.FortifyNavOps} for walkTowardBlock.
     */
    boolean tryPillarEscapeFirst(FortifySkillOps.FortifyNavOps navOps,
                                 ServerPlayerEntity bot, ServerWorld world,
                                 int referenceSurfaceY, String contextTag) {
        if (bot == null || world == null) return false;
        int remaining = referenceSurfaceY - bot.getBlockPos().getY();
        if (remaining <= 0 || remaining > MAX_SCAFFOLD_HEIGHT) {
            return false;
        }

        int shaftBlockersBefore = countEscapeShaftBlockers(bot, world, remaining + 1);
        int cleared = clearEscapeShaftHeadroom(bot, world, remaining + 1, contextTag);
        boolean pillared = ScaffoldService.pillarUp(bot, remaining + 1, true);
        if (!pillared) {
            int extraCleared = clearEscapeShaftHeadroom(bot, world, remaining + 1, contextTag);
            if (extraCleared > 0) {
                cleared += extraCleared;
                pillared = ScaffoldService.pillarUp(bot, remaining + 1, true);
            }
        }

        int shaftBlockersAfter = countEscapeShaftBlockers(bot, world, Math.max(1, referenceSurfaceY - bot.getBlockPos().getY() + 1));
        boolean progress = bot.getBlockPos().getY() > (referenceSurfaceY - remaining);
        LOGGER.info("[FortifyNav] pillar-first-escape ctx={} refY={} pos={} remaining={} clearedOverhead={} shaftBlockersBefore={} shaftBlockersAfter={} pillared={} progress={}",
                contextTag != null ? contextTag : "none",
                referenceSurfaceY,
                bot.getBlockPos().toShortString(),
                remaining,
                cleared,
                shaftBlockersBefore,
                shaftBlockersAfter,
                pillared,
                progress);

        // Horizontal escape fallback: if vertical pillar failed and shaft still has blockers,
        // try mining sideways 1-2 blocks into a cardinal direction that has fewer overhead blockers,
        // then retry pillar from the new position.
        if (!pillared && !progress && shaftBlockersAfter > 0) {
            BlockPos startPos = bot.getBlockPos();
            int bestBlockers = shaftBlockersAfter;
            Direction bestDir = null;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos adjacent = startPos.offset(dir);
                BlockState adjFeet = world.getBlockState(adjacent);
                BlockState adjHead = world.getBlockState(adjacent.up());
                BlockState adjBelow = world.getBlockState(adjacent.down());
                boolean canStand = (adjFeet.isAir() || adjFeet.isReplaceable())
                        && (adjHead.isAir() || adjHead.isReplaceable())
                        && !adjBelow.isAir();
                if (canStand) {
                    int adjRemaining = Math.max(1, referenceSurfaceY - adjacent.getY() + 1);
                    int adjBlockers = 0;
                    for (int dy = 2; dy <= Math.max(3, adjRemaining + 3); dy++) {
                        BlockState st = world.getBlockState(adjacent.up(dy));
                        if (!st.isAir() && !st.isReplaceable()) adjBlockers++;
                    }
                    if (adjBlockers < bestBlockers) {
                        bestBlockers = adjBlockers;
                        bestDir = dir;
                    }
                } else {
                    boolean feetMineable = adjFeet.getHardness(world, adjacent) >= 0 && !adjFeet.isAir();
                    boolean headMineable = adjHead.getHardness(world, adjacent.up()) >= 0 && !adjHead.isAir();
                    if ((adjFeet.isAir() || feetMineable) && (adjHead.isAir() || headMineable) && !adjBelow.isAir()) {
                        int adjRemaining = Math.max(1, referenceSurfaceY - adjacent.getY() + 1);
                        int adjBlockers = 0;
                        for (int dy = 2; dy <= Math.max(3, adjRemaining + 3); dy++) {
                            BlockState st = world.getBlockState(adjacent.up(dy));
                            if (!st.isAir() && !st.isReplaceable()) adjBlockers++;
                        }
                        if (adjBlockers < bestBlockers) {
                            bestBlockers = adjBlockers;
                            bestDir = dir;
                        }
                    }
                }
            }
            if (bestDir != null) {
                BlockPos target = startPos.offset(bestDir);
                LOGGER.info("[FortifyNav] pillar-escape-horizontal dir={} from={} to={} shaftBlockers={}->{}",
                        bestDir, startPos.toShortString(), target.toShortString(), shaftBlockersAfter, bestBlockers);
                BlockState tFeet = world.getBlockState(target);
                if (!tFeet.isAir() && tFeet.getHardness(world, target) >= 0) {
                    ops.digBlock(bot, world, target);
                    ops.sleepQuiet(80);
                }
                BlockState tHead = world.getBlockState(target.up());
                if (!tHead.isAir() && tHead.getHardness(world, target.up()) >= 0) {
                    ops.digBlock(bot, world, target.up());
                    ops.sleepQuiet(80);
                }
                navOps.walkTowardBlock(bot, target, 800L);
                ops.sleepQuiet(100);
                int newRemaining = Math.max(1, referenceSurfaceY - bot.getBlockPos().getY());
                if (newRemaining > 0 && newRemaining <= MAX_SCAFFOLD_HEIGHT) {
                    clearEscapeShaftHeadroom(bot, world, newRemaining + 1, contextTag);
                    boolean retryPillared = ScaffoldService.pillarUp(bot, newRemaining + 1, true);
                    boolean retryProgress = bot.getBlockPos().getY() > startPos.getY();
                    LOGGER.info("[FortifyNav] pillar-escape-horizontal-retry pos={} pillared={} progress={}",
                            bot.getBlockPos().toShortString(), retryPillared, retryProgress);
                    if (retryPillared || retryProgress) return true;
                }
            }
        }

        return pillared || progress;
    }

    // ── Hole escape ─────────────────────────────────────────────

    /**
     * Escape from a hole/moat using heightmap terrain Y.
     */
    void escapeIfInHole(FortifySkillOps.FortifyNavOps navOps,
                        ServerPlayerEntity bot, ServerWorld world) {
        int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        escapeIfInHole(navOps, bot, world, terrainY);
    }

    /**
     * Escape from a hole/moat below the given reference surface Y.
     * Strategy: pillar-up, with shallow-jump shortcut for depth==1.
     */
    void escapeIfInHole(FortifySkillOps.FortifyNavOps navOps,
                        ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        long phaseStartMs = System.currentTimeMillis();
        if (ops.abortFortifyPhase(bot, "escapeIfInHole:entry", phaseStartMs)) {
            return;
        }
        BlockPos botPos = bot.getBlockPos();
        BlockPos startPos = botPos.toImmutable();
        int depth = referenceSurfaceY - botPos.getY();
        if (depth <= 0) return;

        FortifyNavRuntimeScope scope = ops.getActiveFortifyNavScope();

        if (entombmentHelper.shouldSkipRepeatedSurfaceEscape(botPos, referenceSurfaceY)) {
            entombmentHelper.noteEntombmentSurfaceEscapeFailure(world, botPos, scope != null ? scope.context : null);
            LOGGER.info("[FortifyNav] surface escape skipped repeated-fail pos={} surfaceY={} depth={} ctx={}",
                    botPos.toShortString(), referenceSurfaceY, depth,
                    scope != null ? scope.context : "none");
            return;
        }

        if (entombmentHelper.isSealedFortifyEntombmentSurfaceEscapeCell(world, botPos, referenceSurfaceY)) {
            entombmentHelper.noteEntombmentSurfaceEscapeFailure(world, botPos, null);
            LOGGER.info("[FortifyNav] surface escape rejected sealed-entombment pos={} depth={} ctx={}",
                    botPos.toShortString(),
                    depth,
                    scope != null ? scope.context : "none");
            return;
        }

        LOGGER.info("Bot below surface by {} blocks at {} (surfaceY={}), building pillar to escape",
                depth, botPos.toShortString(), referenceSurfaceY);

        // Strategy 1: Shallow (1 block) — just jump
        if (depth == 1) {
            BlockPos headBlock = botPos.up(2);
            BlockState headState = world.getBlockState(headBlock);
            if (!headState.isAir() && headState.getHardness(world, headBlock) >= 0) {
                ops.digBlock(bot, world, headBlock);
                ops.sleepQuiet(100);
            }
            BotActions.jump(bot);
            ops.sleepQuiet(400);
            if (bot.getBlockPos().getY() >= referenceSurfaceY) return;
        }

        // Strategy 2: Pillar up
        String escapeCtx = scope != null ? scope.context : "surface-escape";
        if (tryPillarEscapeFirst(navOps, bot, world, referenceSurfaceY, escapeCtx)) {
            BlockPos afterPillar = bot.getBlockPos();
            if (afterPillar.getY() >= referenceSurfaceY - 1) {
                entombmentHelper.clearSurfaceEscapeRetryState(startPos, referenceSurfaceY);
                entombmentHelper.clearSurfaceEscapeRetryState(afterPillar, referenceSurfaceY);
                entombmentHelper.noteEntombmentRecoverySuccess(world, startPos, afterPillar, escapeCtx);
                return;
            }
        }

        // If pillar failed, note failure
        botPos = bot.getBlockPos();
        if (botPos.getY() < referenceSurfaceY) {
            int sameColumnFailures = entombmentHelper.noteSurfaceEscapeRetryFailure(startPos, referenceSurfaceY);
            if (!startPos.equals(botPos)
                    && !Objects.equals(entombmentHelper.surfaceEscapeRetryKey(startPos, referenceSurfaceY),
                    entombmentHelper.surfaceEscapeRetryKey(botPos, referenceSurfaceY))) {
                entombmentHelper.noteSurfaceEscapeRetryFailure(botPos, referenceSurfaceY);
            }
            LOGGER.warn("Pillar escape incomplete, bot at {} vs surfaceY={} pocketFailures={}",
                    botPos.toShortString(), referenceSurfaceY, sameColumnFailures);
            entombmentHelper.noteEntombmentSurfaceEscapeFailure(world, botPos, scope != null ? scope.context : null);
            if (scope != null && scope.towerState != null) {
                scope.towerState.recordSubSurfaceFailure(startPos);
                scope.towerState.recordSubSurfaceFailure(botPos);
            }
        }
    }

    // ── Surface assurance ───────────────────────────────────────

    void ensureOnSurface(FortifySkillOps.FortifyNavOps navOps,
                         ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        BlockPos botPos = bot.getBlockPos();
        if (botPos.getY() >= referenceSurfaceY) return;

        LOGGER.info("Bot is below surface at {}, Y={} vs surfaceY={}, building ramp to escape",
                botPos.toShortString(), botPos.getY(), referenceSurfaceY);

        escapeIfInHole(navOps, bot, world, referenceSurfaceY);
    }

    void ensureOnSurface(FortifySkillOps.FortifyNavOps navOps,
                         ServerPlayerEntity bot, ServerWorld world) {
        int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        ensureOnSurface(navOps, bot, world, terrainY);
    }
}
