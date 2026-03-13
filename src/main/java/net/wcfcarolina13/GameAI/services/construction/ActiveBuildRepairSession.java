package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.execution.ConstructionRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task-scoped ledger for actively built structure cells.
 *
 * <p>Once a planned non-air structure cell has existed in the world in a valid material
 * state, it becomes protected by repair policy: if it later goes missing or changes to an
 * invalid material during the same task, the position is treated as a repair target rather
 * than disposable terrain.</p>
 */
public final class ActiveBuildRepairSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-repair");

    private static final long BACKOFF_BASE_MS = 250L;
    private static final long BACKOFF_MAX_MS = 1_000L;
    private static final long PROCESS_MIN_INTERVAL_MS = 350L;

    private final String taskId;
    private final Map<BlockPos, RepairTargetState> targets;
    private final Map<BlockPos, DeferredRepairTask> deferredQueue = new LinkedHashMap<>();

    private long lastProcessMs = 0L;

    private ActiveBuildRepairSession(String taskId, Map<BlockPos, RepairTargetState> targets) {
        this.taskId = taskId == null || taskId.isBlank() ? "construction" : taskId;
        this.targets = targets;
    }

    public static ActiveBuildRepairSession begin(String taskId,
                                                 ServerWorld world,
                                                 Map<BlockPos, BlockState> plannedStates) {
        Map<BlockPos, RepairTargetState> targets = new HashMap<>();
        if (plannedStates != null) {
            for (Map.Entry<BlockPos, BlockState> entry : plannedStates.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState desiredState = entry.getValue();
                if (pos == null || !BlockReplacementService.isRepairCandidate(desiredState)) {
                    continue;
                }
                boolean activated = world != null
                        && BlockReplacementService.stateSatisfies(world.getBlockState(pos), desiredState);
                targets.put(pos.toImmutable(), new RepairTargetState(pos.toImmutable(), desiredState, activated));
            }
        }
        return new ActiveBuildRepairSession(taskId, targets);
    }

    public void markPlaced(BlockPos pos) {
        if (pos == null) {
            return;
        }
        RepairTargetState state = targets.get(pos);
        if (state != null) {
            state.activated = true;
        }
    }

    public int remainingDamageCount(ServerWorld world) {
        captureCommittedStructure(world);
        int count = 0;
        for (RepairTargetState target : targets.values()) {
            if (target.activated && !BlockReplacementService.stateSatisfies(world.getBlockState(target.pos), target.desiredState)) {
                count++;
            }
        }
        return count;
    }

    public boolean recordObservedDamage(ServerWorld world, BlockPos pos, String cause) {
        if (world == null || pos == null) {
            return false;
        }
        captureCommittedStructure(world);
        RepairTargetState target = targets.get(pos);
        if (target == null || !target.activated) {
            return false;
        }
        if (BlockReplacementService.stateSatisfies(world.getBlockState(pos), target.desiredState)) {
            return false;
        }
        queueRepair(target, world, cause == null ? "observed-damage" : cause);
        return true;
    }

    public RepairSweepResult sweep(ServerCommandSource source,
                                   ServerPlayerEntity bot,
                                   ServerWorld world,
                                   double reachDistanceSq,
                                   boolean force) {
        if (bot == null || world == null) {
            return RepairSweepResult.empty();
        }

        captureCommittedStructure(world);
        int damagedCount = detectDamagedStructure(world);

        long now = System.currentTimeMillis();
        if (!force && (now - lastProcessMs) < PROCESS_MIN_INTERVAL_MS) {
            return new RepairSweepResult(damagedCount, 0, 0, 0, 0, deferredQueue.size(), false, true);
        }
        lastProcessMs = now;

        int attempted = 0;
        int repaired = 0;
        int queued = 0;
        int skipped = 0;
        int sealRiskSkips = 0;
        boolean progressMade = false;

        List<DeferredRepairTask> ordered = new ArrayList<>(deferredQueue.values());
        ordered.sort(Comparator.comparingDouble(task -> bot.getBlockPos().getSquaredDistance(task.pos)));

        for (DeferredRepairTask task : ordered) {
            DeferredRepairTask live = deferredQueue.get(task.pos);
            if (live == null) {
                continue;
            }
            if (live.nextEligibleMs > now) {
                skipped++;
                continue;
            }

            BlockState current = world.getBlockState(live.pos);
            if (BlockReplacementService.stateSatisfies(current, live.desiredState)) {
                deferredQueue.remove(live.pos);
                continue;
            }

            if (ConstructionRepairSafetyService.wouldRepairSealCurrentExit(bot, world, live.pos)) {
                noteSkip(live, "seal-risk");
                skipped++;
                sealRiskSkips++;
                LOGGER.info("construction repair defer: task={} pos={} reason=seal-risk",
                        taskId, live.pos.toShortString());
                continue;
            }

            if (!ConstructionRecoveryService.isWithinReach(bot, live.pos, reachDistanceSq)) {
                ConstructionRecoveryService.RecoveryResult reach = ConstructionRecoveryService.ensureReachByMovement(
                        source, bot, live.pos, 2, reachDistanceSq);
                progressMade |= reach.progressMade();
                if (!ConstructionRecoveryService.isWithinReach(bot, live.pos, reachDistanceSq)) {
                    noteSkip(live, "blocked-reach");
                    skipped++;
                    continue;
                }
            }

            attempted++;
            BlockReplacementService.ReplaceResult replace = BlockReplacementService.tryReplaceBlock(
                    bot, world, live.pos, live.desiredState, true, taskId + ":repair");
            if (BlockReplacementService.stateSatisfies(world.getBlockState(live.pos), live.desiredState)) {
                deferredQueue.remove(live.pos);
                repaired++;
                progressMade = true;
                continue;
            }

            noteSkip(live, replace.reason() == null ? "replace-failed" : replace.reason());
            skipped++;
        }

        for (DeferredRepairTask task : deferredQueue.values()) {
            if (task.freshlyQueued) {
                queued++;
                task.freshlyQueued = false;
            }
        }

        if (damagedCount > 0 || attempted > 0 || repaired > 0 || queued > 0 || sealRiskSkips > 0) {
            LOGGER.info("construction repair sweep: task={} damaged={} attempted={} repaired={} queued={} skipped={} sealRiskSkips={} remainingQueue={} throttled={}",
                    taskId, damagedCount, attempted, repaired, queued, skipped, sealRiskSkips, deferredQueue.size(), false);
        }

        return new RepairSweepResult(damagedCount, attempted, repaired, queued, sealRiskSkips,
                deferredQueue.size(), progressMade, false);
    }

    private void captureCommittedStructure(ServerWorld world) {
        if (world == null) {
            return;
        }
        for (RepairTargetState target : targets.values()) {
            if (!target.activated && BlockReplacementService.stateSatisfies(world.getBlockState(target.pos), target.desiredState)) {
                target.activated = true;
            }
        }
    }

    private int detectDamagedStructure(ServerWorld world) {
        int damaged = 0;
        for (RepairTargetState target : targets.values()) {
            if (!target.activated) {
                continue;
            }
            if (BlockReplacementService.stateSatisfies(world.getBlockState(target.pos), target.desiredState)) {
                continue;
            }
            damaged++;
            queueRepair(target, world, "pass-detect");
        }
        return damaged;
    }

    private void queueRepair(RepairTargetState target, ServerWorld world, String cause) {
        if (target == null || world == null) {
            return;
        }
        DeferredRepairTask existing = deferredQueue.get(target.pos);
        if (existing != null) {
            return;
        }
        deferredQueue.put(target.pos, new DeferredRepairTask(target.pos, target.desiredState));
        LOGGER.info("construction repair detect: task={} pos={} desired={} actual={} cause={}",
                taskId,
                target.pos.toShortString(),
                target.desiredState.getBlock().getName().getString(),
                world.getBlockState(target.pos).getBlock().getName().getString(),
                cause);
    }

    private void noteSkip(DeferredRepairTask task, String reason) {
        if (task == null) {
            return;
        }
        task.attempts++;
        task.lastAttemptMs = System.currentTimeMillis();
        long multiplier = 1L << Math.min(2, Math.max(0, task.attempts - 1));
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * multiplier);
        task.nextEligibleMs = task.lastAttemptMs + delay;
    }

    public record RepairSweepResult(
            int damagedCount,
            int attemptedCount,
            int repairedCount,
            int queuedCount,
            int sealRiskSkips,
            int remainingQueue,
            boolean progressMade,
            boolean throttled
    ) {
        public static RepairSweepResult empty() {
            return new RepairSweepResult(0, 0, 0, 0, 0, 0, false, false);
        }
    }

    private static final class RepairTargetState {
        private final BlockPos pos;
        private final BlockState desiredState;
        private boolean activated;

        private RepairTargetState(BlockPos pos, BlockState desiredState, boolean activated) {
            this.pos = pos;
            this.desiredState = desiredState;
            this.activated = activated;
        }
    }

    private static final class DeferredRepairTask {
        private final BlockPos pos;
        private final BlockState desiredState;
        private int attempts;
        private long lastAttemptMs;
        private long nextEligibleMs;
        private boolean freshlyQueued = true;

        private DeferredRepairTask(BlockPos pos, BlockState desiredState) {
            this.pos = pos;
            this.desiredState = desiredState;
        }
    }
}