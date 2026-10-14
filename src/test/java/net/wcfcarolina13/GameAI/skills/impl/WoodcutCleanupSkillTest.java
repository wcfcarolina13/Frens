package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ── 1.1.234 #9: empty async scans finish the sweep ──────────────────────────────────────

    @Test
    void sweepKeepsGoingAfterOneEmptyScanAndStopsAfterTwo() {
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(0));
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(1));
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(2));
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(5));
    }

    @Test
    void liveFullTreesAndGroundedLogsAreNothingToDoNotFailure() {
        WoodcutCleanupDiagnostics.CleanupScanStats onlyRejected =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 0, 0, 4, 2, "none");
        boolean success = WoodcutCleanupDiagnostics.isSuccess(0, 0, 0, 0, 0, onlyRejected);
        assertTrue(success);
        assertTrue(WoodcutCleanupDiagnostics.isNothingToDo(success, 0, 0));
        assertEquals("Nothing left to clean up here.", WoodcutCleanupDiagnostics.playerLine(success, 0, 0));
    }

    @Test
    void blockedFloatersWithNothingAttemptedAreStillAFailure() {
        WoodcutCleanupDiagnostics.CleanupScanStats blocked =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 2, 0, 0, 0, "protected-woodcut:base-radius=2");
        assertFalse(WoodcutCleanupDiagnostics.isSuccess(0, 0, 0, 0, 0, blocked));
        assertTrue(WoodcutCleanupDiagnostics.isSuccess(0, 0, 0, 3, 3, blocked));
    }

    @Test
    void leftoverLogsAreAFailure() {
        WoodcutCleanupDiagnostics.CleanupScanStats none =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 0, 0, 0, 0, "none");
        assertFalse(WoodcutCleanupDiagnostics.isSuccess(1, 0, 0, 5, 5, none));
        assertFalse(WoodcutCleanupDiagnostics.isSuccess(0, 1, 0, 5, 5, none));
        assertFalse(WoodcutCleanupDiagnostics.isSuccess(0, 0, 1, 5, 5, none));
        assertTrue(WoodcutCleanupDiagnostics.isSuccess(0, 0, 0, 0, 0, null));
    }

    // ── 1.1.234 #10: one plain sentence for the player ──────────────────────────────────────

    @Test
    void playerLineNamesWhatWasCleanedUp() {
        assertEquals("Cleaned up 3 logs and 5 scaffold blocks.", WoodcutCleanupDiagnostics.playerLine(true, 3, 5));
        assertEquals("Cleaned up 1 log.", WoodcutCleanupDiagnostics.playerLine(true, 1, 0));
        assertEquals("Cleaned up 1 scaffold block.", WoodcutCleanupDiagnostics.playerLine(true, 0, 1));
        assertFalse(WoodcutCleanupDiagnostics.isNothingToDo(true, 0, 1));
    }

    @Test
    void playerLineOnFailureSaysWhatIsLeft() {
        assertEquals("Cleaned up what I could, a few blocks are out of reach.",
                WoodcutCleanupDiagnostics.playerLine(false, 2, 0));
        assertEquals("I couldn't reach the blocks left here.", WoodcutCleanupDiagnostics.playerLine(false, 0, 0));
        assertFalse(WoodcutCleanupDiagnostics.isNothingToDo(false, 0, 0));
    }

    @Test
    void playerLinesCarryNoStatsDashesOrSemicolons() {
        int[][] cases = {{0, 0}, {1, 0}, {0, 1}, {3, 5}, {12, 40}};
        for (boolean success : new boolean[]{true, false}) {
            for (int[] c : cases) {
                String line = WoodcutCleanupDiagnostics.playerLine(success, c[0], c[1]);
                assertFalse(line.contains("\u2014"), line);
                assertFalse(line.contains("\u2013"), line);
                assertFalse(line.contains(";"), line);
                assertFalse(line.contains("="), line);
                assertFalse(line.startsWith("Cleanup"), line);
            }
        }
    }
}
