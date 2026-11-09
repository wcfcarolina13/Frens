package net.wcfcarolina13.GameAI.services.construction.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPolicyTest {

    @Test
    void defaultsAreTheDocumentedGuardrails() {
        ExecutionPolicy p = ExecutionPolicy.defaults();
        assertEquals(6, p.maxPasses());
        assertEquals(3, p.perTargetFailCap());
        assertEquals(2, p.noProgressPasses());
        assertEquals(480_000L, p.timeBudgetMs());
    }

    @Test
    void smallestPositiveValuesAreAccepted() {
        ExecutionPolicy p = assertDoesNotThrow(() -> new ExecutionPolicy(1, 1, 1, 1L));
        assertEquals(new ExecutionPolicy(1, 1, 1, 1L), p);
    }

    @Test
    void largeValuesAreAccepted() {
        assertDoesNotThrow(() -> new ExecutionPolicy(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void zeroMaxPassesIsRejected() {
        assertRejected(() -> new ExecutionPolicy(0, 3, 2, 1_000L), "maxPasses");
        assertRejected(() -> new ExecutionPolicy(-1, 3, 2, 1_000L), "maxPasses");
    }

    @Test
    void zeroPerTargetFailCapIsRejected() {
        assertRejected(() -> new ExecutionPolicy(6, 0, 2, 1_000L), "perTargetFailCap");
        assertRejected(() -> new ExecutionPolicy(6, Integer.MIN_VALUE, 2, 1_000L), "perTargetFailCap");
    }

    @Test
    void zeroNoProgressPassesIsRejected() {
        assertRejected(() -> new ExecutionPolicy(6, 3, 0, 1_000L), "noProgressPasses");
        assertRejected(() -> new ExecutionPolicy(6, 3, -5, 1_000L), "noProgressPasses");
    }

    @Test
    void zeroOrNegativeTimeBudgetIsRejected() {
        // Callers compute budgets from elapsed time (FortifyVillageSkill segment budget), so the
        // exhausted-budget case must fail loudly rather than build a policy that never runs.
        assertRejected(() -> new ExecutionPolicy(6, 3, 2, 0L), "timeBudgetMs");
        assertRejected(() -> new ExecutionPolicy(6, 3, 2, -1L), "timeBudgetMs");
        assertRejected(() -> new ExecutionPolicy(6, 3, 2, Long.MIN_VALUE), "timeBudgetMs");
    }

    @Test
    void firstInvalidFieldIsTheOneReported() {
        assertRejected(() -> new ExecutionPolicy(0, 0, 0, 0L), "maxPasses");
        assertRejected(() -> new ExecutionPolicy(1, 0, 0, 0L), "perTargetFailCap");
        assertRejected(() -> new ExecutionPolicy(1, 1, 0, 0L), "noProgressPasses");
    }

    private static void assertRejected(org.junit.jupiter.api.function.Executable build, String field) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        assertTrue(e.getMessage().startsWith(field + " "), e.getMessage());
    }
}
