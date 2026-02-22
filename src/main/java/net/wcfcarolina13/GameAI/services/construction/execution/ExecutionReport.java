package net.wcfcarolina13.GameAI.services.construction.execution;

import java.util.Map;

/**
 * Outcome summary for a construction execution run.
 */
public record ExecutionReport(
        int placedCount,
        int remainingCount,
        Map<FailureReason, Integer> remainingByReason,
        int scaffoldsPlaced,
        int scaffoldsRemoved,
        boolean aborted,
        boolean timedOut
) {}
