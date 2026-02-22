package net.wcfcarolina13.GameAI.services.construction.execution;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;

import java.util.List;
import java.util.Set;

/**
 * Immutable execution specification consumed by ConstructionExecutionService.
 */
public record ConstructionTaskSpec(
        String taskId,
        ServerWorld world,
        ServerPlayerEntity bot,
        ServerCommandSource source,
        List<PlacementTarget> orderedTargets,
        ExecutionPolicy executionPolicy,
        SupportPolicy supportPolicy,
        ReachHandler reachHandler,
        PlacementHandler placementHandler,
        ProgressHandler progressHandler,
        NoProgressHandler noProgressHandler,
        ScaffoldService.ScaffoldSession scaffoldSession,
        boolean teardownScaffoldsAtEnd,
        Set<BlockPos> keepScaffoldBlocks
) {
    public ConstructionTaskSpec {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (world == null) throw new IllegalArgumentException("world is required");
        if (bot == null) throw new IllegalArgumentException("bot is required");
        if (orderedTargets == null) throw new IllegalArgumentException("orderedTargets is required");
        if (executionPolicy == null) throw new IllegalArgumentException("executionPolicy is required");
        if (supportPolicy == null) supportPolicy = SupportPolicy.defaults();
        if (keepScaffoldBlocks == null) keepScaffoldBlocks = Set.of();
    }

    public record SupportPolicy(
            boolean allowTemporarySupport,
            boolean allowScaffolding,
            int maxScaffoldHeight
    ) {
        public static SupportPolicy defaults() {
            return new SupportPolicy(true, true, 8);
        }
    }

    @FunctionalInterface
    public interface ReachHandler {
        ConstructionRecoveryService.RecoveryResult ensureReach(PlacementTarget target, int passNumber);
    }

    @FunctionalInterface
    public interface PlacementHandler {
        PlacementOutcome place(PlacementTarget target, int passNumber);
    }

    @FunctionalInterface
    public interface ProgressHandler {
        void onPassComplete(PassProgress progress);
    }

    @FunctionalInterface
    public interface NoProgressHandler {
        void onNoProgress(PassProgress progress, int noProgressStreak);
    }

    public record PlacementOutcome(
            boolean placed,
            FailureReason failureReason,
            boolean progressMade
    ) {
        public static PlacementOutcome ok() {
            return new PlacementOutcome(true, null, true);
        }

        public static PlacementOutcome fail(FailureReason reason) {
            return new PlacementOutcome(false, reason, false);
        }

        public static PlacementOutcome fail(FailureReason reason, boolean progressMade) {
            return new PlacementOutcome(false, reason, progressMade);
        }
    }

    public record PassProgress(
            String taskId,
            int passNumber,
            int placedThisPass,
            int remaining,
            boolean madeProgress
    ) {}
}
