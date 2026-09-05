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
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.wcfcarolina13.GameAI.skills.impl.FortifySkillOps.*;

/**
 * Deferred-cleanup processing extracted verbatim from {@link FortifyVillageSkill}:
 * drains the {@link FortifyCleanupHelper} queue (carve repairs, scaffold removal)
 * and owns the mined-block replacement primitives.  Calls back into skill
 * primitives through {@link FortifySkillOps.FortifyNavOps} and
 * {@link FortifySkillOps.FortifySharedContext}.
 */
final class FortifyCleanupProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-cleanup");

    private static final int FORTIFY_MANDATORY_REPLACE_RETRIES = 2;
    private static final long FORTIFY_MANDATORY_REPLACE_RETRY_SLEEP_MS = 60L;
    private static final int FORTIFY_CLEANUP_ACTIVE_RECOVERY_ATTEMPTS = 2;
    private static final int FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST = 12;

    private final FortifySkillOps.FortifyNavOps ops;
    private final FortifyCleanupHelper cleanupHelper;
    private final FortifySkillOps.FortifySharedContext ctx;

    FortifyCleanupProcessor(FortifySkillOps.FortifyNavOps ops,
                            FortifyCleanupHelper cleanupHelper,
                            FortifySkillOps.FortifySharedContext ctx) {
        this.ops = java.util.Objects.requireNonNull(ops);
        this.cleanupHelper = java.util.Objects.requireNonNull(cleanupHelper);
        this.ctx = java.util.Objects.requireNonNull(ctx);
    }

    private boolean tryRecoverTowardDeferredCleanup(ServerPlayerEntity bot, ServerWorld world, DeferredCleanupTask task) {
        if (bot == null || world == null || task == null || task.pos == null) return false;
        if (task.kind != FortifyCleanupKind.SCAFFOLD_REMOVE && task.kind != FortifyCleanupKind.CARVE_REPAIR) return false;
        if (task.attempts < FORTIFY_CLEANUP_ACTIVE_RECOVERY_ATTEMPTS) return false;
        double distSq = bot.getBlockPos().getSquaredDistance(task.pos);
        if (distSq > (double) (FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST * FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST)) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        ops.walkTowardBlock(bot, task.pos, 700L);
        if (!before.equals(bot.getBlockPos())) {
            LOGGER.info("[FortifyCleanup] active-recovery moved toward {} pos={} from={} to={}",
                    task.kind, task.pos.toShortString(), before.toShortString(), bot.getBlockPos().toShortString());
            return true;
        }
        return false;
    }

    void processDeferredFortifyCleanupQueue(ServerPlayerEntity bot, ServerWorld world, String context) {
        if (bot == null || world == null || cleanupHelper.queue.isEmpty()) {
            return;
        }
        if ((SkillManager.shouldAbortSkill(bot) || bot.isRemoved()) && !cleanupHelper.isForcedContext(context)) {
            return;
        }
        boolean forcePass = cleanupHelper.isForcedContext(context);
        if (cleanupHelper.checkAndUpdateThrottle(forcePass)) return;
        long now = System.currentTimeMillis();
        boolean allowActiveRecoveryMovement = cleanupHelper.allowActiveRecovery(context);
        int started = cleanupHelper.queue.size();
        int repaired = 0;
        int removedScaffold = 0;
        int alreadyResolved = 0;
        int skipped = 0;
        int sealRiskSkips = 0;
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        for (Iterator<DeferredCleanupTask> it = cleanupHelper.queue.iterator(); it.hasNext(); ) {
            DeferredCleanupTask task = it.next();
            if (task == null || task.pos == null) {
                it.remove();
                continue;
            }
            if (task.nextEligibleMs > now) {
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "backoffDeferred");
                continue;
            }
            BlockPos pos = task.pos;
            // Proximity skip: don't attempt items that are very far away — just defer them.
            // The bot will pick them up when it's closer on a future pass.
            double distSqToItem = bot.getBlockPos().getSquaredDistance(pos);
            if (distSqToItem > 256.0D) { // > 16 blocks away
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "tooFar");
                continue;
            }
            if (task.kind == FortifyCleanupKind.CARVE_REPAIR) {
                if (task.originalState == null) {
                    it.remove();
                    continue;
                }
                if (!world.getBlockState(pos).isAir()) {
                    cleanupHelper.noteResolved(task);
                    alreadyResolved++;
                    it.remove();
                    continue;
                }
                if (ctx.wouldRepairSealCurrentExit(bot, world, pos)) {
                    cleanupHelper.noteSkip(task, "sealRisk");
                    skipped++;
                    sealRiskSkips++;
                    FortifyCleanupHelper.incrementReason(skipReasons, "sealRisk");
                    continue;
                }
                if (!ops.isWithinReach(bot, pos)) {
                    boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                    if (recovered && ops.isWithinReach(bot, pos)) {
                        now = System.currentTimeMillis();
                    } else {
                        cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                        skipped++;
                        FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                        continue;
                    }
                }
                if (!ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                    boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                    if (!recovered || !ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                        cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                        skipped++;
                        FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                        continue;
                    }
                }
                ReplaceBlockResult replace = tryReplaceMinedBlock(bot, world, pos, task.originalState, task.mandatory,
                        task.context != null ? task.context : context);
                if (!world.getBlockState(pos).isAir()) {
                    cleanupHelper.noteImmediateRetry(task, null);
                    cleanupHelper.noteResolved(task);
                    repaired++;
                    it.remove();
                } else {
                    cleanupHelper.noteSkip(task, "replaceFail");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, "replaceFail");
                }
                continue;
            }

            // Scaffold removal cleanup
            BlockState current = world.getBlockState(pos);
            if (current.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(current.getBlock().asItem())) {
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                alreadyResolved++;
                it.remove();
                continue;
            }
            if (!ops.isWithinMiningReach(bot, pos)) {
                boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                if (recovered && ops.isWithinMiningReach(bot, pos)) {
                    now = System.currentTimeMillis();
                } else {
                    cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                    continue;
                }
            }
            if (!ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                if (!recovered || !ops.hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                    cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                    continue;
                }
            }
            LookController.faceBlock(bot, pos);
            ops.sleepQuiet(30L);
            boolean mined = ops.digBlock(bot, world, pos);
            BlockState after = world.getBlockState(pos);
            if (mined && (after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem()))) {
                cleanupHelper.noteImmediateRetry(task, null);
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                removedScaffold++;
                it.remove();
            } else if (after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem())) {
                cleanupHelper.noteImmediateRetry(task, null);
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                alreadyResolved++;
                it.remove();
            } else {
                cleanupHelper.noteSkip(task, "digFailed");
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "digFailed");
                continue;
            }
        }

        if (started > 0) {
            LOGGER.info("[FortifyCleanup] queue-process ctx={} started={} repaired={} scaffoldRemoved={} alreadyResolved={} skipped={} sealRiskSkips={} remaining={} reasons={}",
                    context, started, repaired, removedScaffold, alreadyResolved, skipped, sealRiskSkips,
                    cleanupHelper.queue.size(), FortifyCleanupHelper.formatReasonSummary(skipReasons));
        }
    }

    static ReplaceFailureKind classifyReplaceFailureKind(String reason) {
        if (reason == null || reason.isBlank()) return ReplaceFailureKind.OTHER;
        if (reason.startsWith("bot-intersects-target")) return ReplaceFailureKind.BOT_OCCUPIES;
        if (reason.startsWith("out-of-reach")) return ReplaceFailureKind.OUT_OF_REACH;
        if (reason.startsWith("no-line-of-sight")) return ReplaceFailureKind.LOS_BLOCKED;
        if (reason.startsWith("no-block-item-available")) return ReplaceFailureKind.NO_MATERIAL;
        return ReplaceFailureKind.OTHER;
    }

    ReplaceBlockResult tryReplaceMinedBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                    BlockState originalState, boolean mandatory, String context) {
        if (bot == null || world == null || pos == null || originalState == null) {
            return new ReplaceBlockResult(false, ReplaceFailureKind.OTHER, "invalid-args");
        }
        if (!world.getBlockState(pos).isAir()) {
            return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
        }

        Item originalItem = originalState.getBlock().asItem();
        List<Item> replacements;
        if (mandatory) {
            Set<Item> seen = new LinkedHashSet<>();
            if (originalItem != Items.AIR) seen.add(originalItem);
            seen.addAll(FortifyLayoutHelper.STONE_BRICK_FALLBACKS);
            seen.addAll(FortifyLayoutHelper.COBBLE_FALLBACKS);
            replacements = new ArrayList<>(seen);
        } else if (originalItem != Items.AIR) {
            replacements = List.of(originalItem, Items.COBBLESTONE, Items.STONE, Items.DIRT);
        } else {
            replacements = List.of(Items.COBBLESTONE, Items.STONE, Items.DIRT);
        }

        int attempts = mandatory ? (1 + FORTIFY_MANDATORY_REPLACE_RETRIES) : 1;
        ReplaceBlockResult last = new ReplaceBlockResult(false, ReplaceFailureKind.OTHER, "not-attempted");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (bot.getBoundingBox().intersects(new net.minecraft.util.math.Box(pos))) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2);
                if (safe != null && !safe.equals(bot.getBlockPos())) {
                    ops.walkToTarget(bot.getCommandSource(), bot, safe, 1000L);
                }
            }

            BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, replacements, false);
            if (place.success()) {
                LOGGER.info("[FortifyNav] Replaced mined block at {}", pos.toShortString());
                return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
            }
            ReplaceFailureKind kind = classifyReplaceFailureKind(place.reason());
            last = new ReplaceBlockResult(false, kind, place.reason());

            boolean finalAttempt = attempt >= attempts;
            if (!finalAttempt && mandatory && last.retryable()) {
                LookController.faceBlock(bot, pos);
                if (kind == ReplaceFailureKind.OUT_OF_REACH
                        && bot.getBlockPos().getSquaredDistance(pos)
                        <= (double) (FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST * FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST)) {
                    ops.walkTowardBlock(bot, pos, 500L);
                } else {
                    BotActions.stop(bot);
                }
                ops.sleepQuiet(FORTIFY_MANDATORY_REPLACE_RETRY_SLEEP_MS);
                if (!world.getBlockState(pos).isAir()) {
                    return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
                }
            }
        }

        // Fallback: bypass vanilla placement with direct setBlockState for mandatory repairs.
        // This handles cases where blockItem.place() rejects placement due to entity collision,
        // line-of-sight issues, or worker-thread read races on block state.
        // Note: forceReplaceBlock has its own server-thread isAir() guard, so no outer check needed.
        if (mandatory) {
            BotActions.PlaceResult forced = BotActions.forceReplaceBlock(bot, pos, replacements);
            if (forced.success() || !world.getBlockState(pos).isAir()) {
                LOGGER.info("[FortifyNav] Force-replaced mined block at {} (bypassed vanilla placement)", pos.toShortString());
                return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
            }
            LOGGER.warn("[FortifyNav] Force-replace also failed pos={} reason={}", pos.toShortString(), forced.reason());
        }

        String ctx = context == null ? "fortify-nav" : context;
        if (mandatory) {
            LOGGER.warn("[FortifyNav] replace-fail ctx={} pos={} mandatory=true reason={}",
                    ctx, pos.toShortString(), last.reason());
        } else {
            LOGGER.debug("[FortifyNav] replace-fail ctx={} pos={} mandatory=false reason={}",
                    ctx, pos.toShortString(), last.reason());
        }
        return last;
    }

    void queueMandatoryCarveRepairIfNeeded(ServerPlayerEntity bot, ServerWorld world,
                                                   BlockPos pos, BlockState originalState,
                                                   boolean mandatory, String context) {
        if (!mandatory || pos == null || originalState == null) return;
        if (world != null && !world.getBlockState(pos).isAir()) return;
        String ctx = context == null ? "fortify-nav" : context;
        cleanupHelper.queue(FortifyCleanupKind.CARVE_REPAIR, pos, originalState, true, ctx);
    }

    void verifyCarveRepairColumn(ServerPlayerEntity bot, ServerWorld world,
                                         BlockPos feetPos, BlockState feetOriginal, boolean feetMandatory,
                                         BlockPos headPos, BlockState headOriginal, boolean headMandatory,
                                         BlockPos overheadPos, BlockState overheadOriginal, boolean overheadMandatory,
                                         String context) {
        queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, context);
        queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, context);
        queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, context);
    }

    boolean replaceMinedBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                      BlockState originalState, boolean mandatory) {
        return tryReplaceMinedBlock(bot, world, pos, originalState, mandatory, "fortify-nav").success();
    }
}
