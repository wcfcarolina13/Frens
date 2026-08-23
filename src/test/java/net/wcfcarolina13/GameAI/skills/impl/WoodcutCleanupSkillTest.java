package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutCleanupSkillTest {

    @Test
    void blockedOrRejectedOnlySummaryIsNotReportedAsDone() {
        String summary = WoodcutCleanupDiagnostics.buildCompletionSummary(
                false,
                0,
                0,
                0,
                18,
                12,
                0,
                0,
                0,
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 2, 1, 0, 3, "protected-woodcut:base-radius=2")
        );

        assertTrue(summary.startsWith("Cleanup incomplete:"));
        assertFalse(summary.startsWith("Cleanup done:"));
        assertTrue(summary.contains("blockedProtected=2"));
        assertTrue(summary.contains("rejectedGrounded=3"));
    }

    @Test
    void zeroDetectedCandidatesCanStillReturnNoopSuccessSummary() {
        String summary = WoodcutCleanupDiagnostics.buildCompletionSummary(
                true,
                0,
                0,
                0,
                18,
                12,
                0,
                0,
                0,
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 0, 0, 0, 0, "none")
        );

        assertTrue(summary.startsWith("Cleanup done:"));
        assertTrue(summary.contains("actionable=0"));
        assertTrue(summary.contains("remainingLogs=0"));
    }

    @Test
    void elevatedCleanupTargetsChoosePillarRecovery() {
        assertTrue(WoodcutCleanupReachHeuristics.shouldAttemptElevatedRecovery(72, 76));
        assertTrue(WoodcutCleanupReachHeuristics.requiredPillarSteps(72, 76) > 0);
    }

    @Test
    void elevatedReachFailuresAreNotReportedAsGenericPathFailures() {
        assertTrue(WoodcutCleanupReachHeuristics.elevatedReachFailureReason(72, 76, false).contains("pillar"));
        assertTrue(WoodcutCleanupReachHeuristics.elevatedReachFailureReason(72, 76, true).contains("post-pillar"));
        assertFalse(WoodcutCleanupReachHeuristics.elevatedReachFailureReason(72, 76, true).equals("path-failed"));
    }
}
