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
                for (boolean blockedOnly : new boolean[]{false, true}) {
                    String line = WoodcutCleanupDiagnostics.playerLine(success, c[0], c[1], blockedOnly);
                    assertFalse(line.contains("\u2014"), line);
                    assertFalse(line.contains("\u2013"), line);
                    assertFalse(line.contains(";"), line);
                    assertFalse(line.contains("="), line);
                    assertFalse(line.startsWith("Cleanup "), line);
                }
            }
        }
    }

    // ── 1.1.234 fix wave #9: a run held back only by protection names protection ────────────

    @Test
    void protectedOnlyRunSaysTheLogsAreProtectedNotOutOfReach() {
        WoodcutCleanupDiagnostics.CleanupScanStats blocked =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 2, 0, 0, 0, "protected-zone");
        boolean success = WoodcutCleanupDiagnostics.isSuccess(0, 0, 0, 0, 0, blocked);
        assertFalse(success);
        assertTrue(WoodcutCleanupDiagnostics.isBlockedOnlyByProtection(success, 0, 0, 0, blocked));
        String line = WoodcutCleanupDiagnostics.playerLine(success, 0, 0, true);
        assertEquals("Some logs are in a protected area, so I left them.", line);
        assertFalse(line.contains("couldn't reach"));
        assertEquals("Cleaned up 2 scaffold blocks. Some logs are in a protected area, so I left them.",
                WoodcutCleanupDiagnostics.playerLine(false, 0, 2, true));
    }

    @Test
    void leftoverLogsAreNotBlamedOnProtection() {
        WoodcutCleanupDiagnostics.CleanupScanStats blocked =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 2, 0, 0, 0, "protected-zone");
        assertFalse(WoodcutCleanupDiagnostics.isBlockedOnlyByProtection(false, 0, 0, 3, blocked));
        assertFalse(WoodcutCleanupDiagnostics.isBlockedOnlyByProtection(false, 1, 0, 0, blocked));
        assertFalse(WoodcutCleanupDiagnostics.isBlockedOnlyByProtection(true, 0, 0, 0, blocked));
        WoodcutCleanupDiagnostics.CleanupScanStats clean =
                new WoodcutCleanupDiagnostics.CleanupScanStats(0, 0, 0, 0, 0, "none");
        assertFalse(WoodcutCleanupDiagnostics.isBlockedOnlyByProtection(false, 0, 0, 0, clean));
        assertEquals("I couldn't reach the blocks left here.", WoodcutCleanupDiagnostics.playerLine(false, 0, 0, false));
    }

    // ── 1.1.234 fix wave #4: scaffold lists ─────────────────────────────────────────────────

    @Test
    void cleanupHeuristicListHasNoFallingBlocksAndRememberedPassUsesWoodcutsList() throws java.io.IOException {
        String cleanup = source("WoodcutCleanupSkill.java");
        String list = cleanup.substring(cleanup.indexOf("private static final List<Item> PILLAR_BLOCKS"));
        list = list.substring(0, list.indexOf(");"));
        assertFalse(list.contains("Items.SAND"), list);
        assertFalse(list.contains("Items.RED_SAND"), list);
        assertFalse(list.contains("Items.GRAVEL"), list);
        assertFalse(list.contains("_PLANKS"), "no planks in the heuristic pass");
        assertTrue(cleanup.contains("!isRememberedScaffoldBlock(state.getBlock().asItem())"),
                "the remembered pass must accept everything woodcut places");
        assertTrue(cleanup.contains("WoodcutSkill.PILLAR_BLOCKS.contains(item)"));
        String woodcut = source("WoodcutSkill.java");
        String placed = woodcut.substring(woodcut.indexOf("static final List<Item> PILLAR_BLOCKS"));
        placed = placed.substring(0, placed.indexOf(");"));
        assertTrue(placed.contains("Items.MOSS_BLOCK") && placed.contains("Items.PALE_MOSS_BLOCK"), placed);
    }

    private static String source(String file) throws java.io.IOException {
        String relative = "src/main/java/net/wcfcarolina13/GameAI/skills/impl/" + file;
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path at = dir; at != null; at = at.getParent()) {
            java.nio.file.Path candidate = at.resolve(relative);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return java.nio.file.Files.readString(candidate, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException(relative + " not found above " + dir);
    }
}
