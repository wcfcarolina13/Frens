package net.wcfcarolina13.GameAI.services.construction.execution;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared pass-based execution engine for construction targets.
 */
public final class ConstructionExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-execution");

    private ConstructionExecutionService() {}

    public static ExecutionReport execute(ConstructionTaskSpec spec) {
        Map<BlockPos, PlacementTarget> remaining = new LinkedHashMap<>();
        for (PlacementTarget target : spec.orderedTargets()) {
            if (target == null || target.pos() == null || target.desiredState() == null) {
                continue;
            }
            remaining.put(target.pos(), target);
        }

        Map<BlockPos, Integer> failCounts = new HashMap<>();
        Map<BlockPos, FailureReason> lastFailures = new HashMap<>();
        int totalPlaced = 0;
        int noProgressStreak = 0;
        boolean aborted = false;
        boolean timedOut = false;
        long startMs = System.currentTimeMillis();

        ExecutionPolicy policy = spec.executionPolicy();

        for (int pass = 1; pass <= policy.maxPasses() && !remaining.isEmpty(); pass++) {
            if (SkillManager.shouldAbortSkill(spec.bot())) {
                aborted = true;
                break;
            }
            if ((System.currentTimeMillis() - startMs) > policy.timeBudgetMs()) {
                timedOut = true;
                break;
            }

            int passPlaced = 0;
            boolean passProgress = false;
            List<PlacementTarget> passTargets = new ArrayList<>();
            for (PlacementTarget target : spec.orderedTargets()) {
                if (target != null && remaining.containsKey(target.pos())) {
                    passTargets.add(target);
                }
            }

            for (PlacementTarget target : passTargets) {
                if (SkillManager.shouldAbortSkill(spec.bot())) {
                    aborted = true;
                    break;
                }
                if ((System.currentTimeMillis() - startMs) > policy.timeBudgetMs()) {
                    timedOut = true;
                    break;
                }

                BlockPos pos = target.pos();
                BlockState desired = target.desiredState();
                BlockState current = spec.world().getBlockState(pos);

                if (current.equals(desired)) {
                    remaining.remove(pos);
                    lastFailures.remove(pos);
                    failCounts.remove(pos);
                    passPlaced++;
                    passProgress = true;
                    continue;
                }

                if (!current.isAir() && !current.isReplaceable()) {
                    lastFailures.put(pos, FailureReason.BLOCKED_BY_SOLID);
                    failCounts.merge(pos, 1, Integer::sum);
                    continue;
                }

                int priorFails = failCounts.getOrDefault(pos, 0);
                if (priorFails >= policy.perTargetFailCap()) {
                    continue;
                }

                if (spec.reachHandler() != null) {
                    ConstructionRecoveryService.RecoveryResult reach = spec.reachHandler().ensureReach(target, pass);
                    if (reach.progressMade()) {
                        passProgress = true;
                    }
                    if (!reach.success()) {
                        FailureReason reason = reach.failureReason() != null ? reach.failureReason() : FailureReason.MOVEMENT_FAILED;
                        lastFailures.put(pos, reason);
                        failCounts.merge(pos, 1, Integer::sum);
                        continue;
                    }
                }

                if (spec.placementHandler() == null) {
                    lastFailures.put(pos, FailureReason.UNKNOWN);
                    failCounts.merge(pos, 1, Integer::sum);
                    continue;
                }

                ConstructionTaskSpec.PlacementOutcome outcome = spec.placementHandler().place(target, pass);
                if (outcome.placed()) {
                    remaining.remove(pos);
                    lastFailures.remove(pos);
                    failCounts.remove(pos);
                    passPlaced++;
                    passProgress = true;
                } else {
                    failCounts.merge(pos, 1, Integer::sum);
                    lastFailures.put(pos, outcome.failureReason() != null ? outcome.failureReason() : FailureReason.UNKNOWN);
                    if (outcome.progressMade()) {
                        passProgress = true;
                    }
                }
            }

            totalPlaced += passPlaced;
            ConstructionTaskSpec.PassProgress progress = new ConstructionTaskSpec.PassProgress(
                    spec.taskId(), pass, passPlaced, remaining.size(), passProgress
            );

            if (spec.progressHandler() != null) {
                spec.progressHandler().onPassComplete(progress);
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("task={} pass={} placed={} remaining={} passProgress={} noProgressStreak={}",
                        spec.taskId(), pass, passPlaced, remaining.size(), passProgress, noProgressStreak);
            }

            if (aborted || timedOut) {
                break;
            }

            if (passProgress) {
                noProgressStreak = 0;
            } else {
                noProgressStreak++;
                if (spec.noProgressHandler() != null) {
                    spec.noProgressHandler().onNoProgress(progress, noProgressStreak);
                }
                if (noProgressStreak >= policy.noProgressPasses()) {
                    break;
                }
            }
        }

        for (BlockPos pos : remaining.keySet()) {
            if (!lastFailures.containsKey(pos)) {
                if (aborted) {
                    lastFailures.put(pos, FailureReason.ABORTED);
                } else if (timedOut) {
                    lastFailures.put(pos, FailureReason.TIME_BUDGET);
                } else {
                    lastFailures.put(pos, FailureReason.UNKNOWN);
                }
            }
        }

        Map<FailureReason, Integer> remainingByReason = new EnumMap<>(FailureReason.class);
        for (FailureReason reason : lastFailures.values()) {
            remainingByReason.merge(reason, 1, Integer::sum);
        }

        int scaffoldsPlaced = 0;
        int scaffoldsRemoved = 0;
        ScaffoldService.ScaffoldSession session = spec.scaffoldSession();
        if (session != null) {
            scaffoldsPlaced = session.trackedPositions().size();
            if (spec.teardownScaffoldsAtEnd()) {
                Set<BlockPos> keep = spec.keepScaffoldBlocks() == null ? Set.of() : spec.keepScaffoldBlocks();
                scaffoldsRemoved = ScaffoldService.teardown(session, keep);
            }
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("task={} complete placed={} remaining={} aborted={} timedOut={} failures={}",
                    spec.taskId(), totalPlaced, remaining.size(), aborted, timedOut, remainingByReason);
        }

        return new ExecutionReport(
                totalPlaced,
                remaining.size(),
                Map.copyOf(remainingByReason),
                scaffoldsPlaced,
                scaffoldsRemoved,
                aborted,
                timedOut
        );
    }
}
