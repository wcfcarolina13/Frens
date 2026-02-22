package net.wcfcarolina13.GameAI.services.construction.execution;

/**
 * Runtime guardrails for placement execution loops.
 */
public record ExecutionPolicy(
        int maxPasses,
        int perTargetFailCap,
        int noProgressPasses,
        long timeBudgetMs
) {
    public static ExecutionPolicy defaults() {
        return new ExecutionPolicy(6, 3, 2, 480_000L);
    }

    public ExecutionPolicy {
        if (maxPasses <= 0) throw new IllegalArgumentException("maxPasses must be > 0");
        if (perTargetFailCap <= 0) throw new IllegalArgumentException("perTargetFailCap must be > 0");
        if (noProgressPasses <= 0) throw new IllegalArgumentException("noProgressPasses must be > 0");
        if (timeBudgetMs <= 0) throw new IllegalArgumentException("timeBudgetMs must be > 0");
    }
}
