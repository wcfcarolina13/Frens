package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(0, true));
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(1, true));
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(2, true));
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(5, true));
        // Review I2: empty merges of truncated scans that never covered the region do not stop the run.
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(2, false));
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(9, false));
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
                    for (boolean covered : new boolean[]{false, true}) {
                        String line = WoodcutCleanupDiagnostics.playerLine(success, c[0], c[1], blockedOnly, covered);
                        assertFalse(line.contains("\u2014"), line);
                        assertFalse(line.contains("\u2013"), line);
                        assertFalse(line.contains(";"), line);
                        assertFalse(line.contains("="), line);
                        assertFalse(line.startsWith("Cleanup "), line);
                    }
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
    @Test
    void boundedScanDeadlineUsesBudgetWhenShorterThanSkillDeadline() {
        assertEquals(15_000L, WoodcutCleanupDiagnostics.boundedScanDeadline(10_000L, 130_000L, 5_000L));
    }

    @Test
    void boundedScanDeadlineNeverPassesSkillDeadline() {
        assertEquals(12_000L, WoodcutCleanupDiagnostics.boundedScanDeadline(10_000L, 12_000L, 5_000L));
    }

    @Test
    void boundedScanDeadlineKeepsAPastSkillDeadline() {
        assertEquals(9_000L, WoodcutCleanupDiagnostics.boundedScanDeadline(10_000L, 9_000L, 5_000L));
    }

    @Test
    void boundedScanDeadlineSurvivesOverflowAndNonPositiveBudget() {
        assertEquals(Long.MAX_VALUE - 1,
                WoodcutCleanupDiagnostics.boundedScanDeadline(Long.MAX_VALUE - 10, Long.MAX_VALUE - 1, 1_000L));
        assertEquals(10_000L, WoodcutCleanupDiagnostics.boundedScanDeadline(10_000L, 130_000L, 0L));
    }

    // ── review I2: resumable scans and coverage ─────────────────────────────────────────────

    @Test
    void scanIndicesMapOneToOneOntoTheBoxAndWrap() {
        int sx = 2, sy = 3, sz = 4;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (long i = 0; i < sx * sy * sz; i++) {
            int[] o = WoodcutCleanupDiagnostics.offsetAt(i, sx, sy, sz);
            assertTrue(o[0] >= 0 && o[0] < sx && o[1] >= 0 && o[1] < sy && o[2] >= 0 && o[2] < sz);
            assertTrue(seen.add(o[0] + "," + o[1] + "," + o[2]), "index " + i + " repeats a block");
        }
        assertEquals(24, seen.size());
        assertArrayEquals(new int[]{0, 0, 0}, WoodcutCleanupDiagnostics.offsetAt(0, sx, sy, sz));
        assertArrayEquals(new int[]{1, 0, 0}, WoodcutCleanupDiagnostics.offsetAt(1, sx, sy, sz));
        assertArrayEquals(new int[]{0, 1, 0}, WoodcutCleanupDiagnostics.offsetAt(2, sx, sy, sz));
        assertArrayEquals(new int[]{0, 0, 1}, WoodcutCleanupDiagnostics.offsetAt(6, sx, sy, sz));
        assertArrayEquals(WoodcutCleanupDiagnostics.offsetAt(5, sx, sy, sz), WoodcutCleanupDiagnostics.offsetAt(29, sx, sy, sz));
    }

    @Test
    void regionVolumeTakesCornersInAnyOrder() {
        assertEquals(37L * 23 * 37, WoodcutCleanupDiagnostics.regionVolume(-18, -2, -18, 18, 20, 18));
        assertEquals(37L * 23 * 37, WoodcutCleanupDiagnostics.regionVolume(18, 20, 18, -18, -2, -18));
        assertEquals(1L, WoodcutCleanupDiagnostics.regionVolume(5, 5, 5, 5, 5, 5));
    }

    @Test
    void truncatedScansResumeAtTheCursorAndCoverTheRegionInTurn() {
        // Field case: each 5 s scan covers a third of the region. Before, every scan restarted at
        // the corner, so two empty merges covered the same third and the run said nothing was left.
        WoodcutCleanupDiagnostics.ScanCoverage c = WoodcutCleanupDiagnostics.ScanCoverage.start(300);
        c = c.afterScan(100, false);
        assertEquals(100, c.cursor());
        assertFalse(c.fullyCovered());
        assertFalse(WoodcutCleanupDiagnostics.shouldStopEmpty(2, c.coveredSinceLastFind()));
        c = c.afterScan(100, false);
        assertEquals(200, c.cursor());
        assertFalse(c.coveredSinceLastFind());
        assertEquals(2.0 / 3.0, c.fraction(), 1e-9);
        c = c.afterScan(100, false);
        assertEquals(0, c.cursor(), "the cursor wraps at the end of the region");
        assertTrue(c.fullyCovered());
        assertTrue(c.coveredSinceLastFind());
        assertEquals(1.0, c.fraction(), 1e-9);
        c = c.afterScan(250, false);
        assertEquals(250, c.cursor());
        assertEquals(1.0, c.fraction(), 1e-9, "the fraction is clamped at the whole region");
    }

    @Test
    void aFindRestartsTheEmptyCoverageButNotTheCursor() {
        WoodcutCleanupDiagnostics.ScanCoverage c = WoodcutCleanupDiagnostics.ScanCoverage.start(300)
                .afterScan(200, false)
                .afterScan(50, true);
        assertEquals(250, c.cursor());
        assertEquals(0, c.scannedSinceLastFind());
        c = c.afterScan(200, false).afterFind();
        assertEquals(150, c.cursor());
        assertEquals(0, c.scannedSinceLastFind(), "working a target means the region must be seen again");
        assertTrue(c.fullyCovered(), "total coverage keeps what was seen");
        c = c.afterScan(300, false);
        assertTrue(c.coveredSinceLastFind());
    }

    @Test
    void aRegionOneScanCoversStillStopsAfterTwoEmptyScans() {
        WoodcutCleanupDiagnostics.ScanCoverage c = WoodcutCleanupDiagnostics.ScanCoverage.start(500);
        c = c.afterScan(500, false);
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(1 + 1, c.coveredSinceLastFind()));
    }

    @Test
    void aFinishedScanIsMergedBeforeAnotherLaunchesOrTheEmptyPassCounts() throws java.io.IOException {
        String cleanup = source("WoodcutCleanupSkill.java");
        assertTrue(cleanup.contains("if (asyncScan == null) {\n                        final Map<Long, Long> cooldownSnapshot"),
                "a new scan launches only once the last one merged, from its cursor");
        assertTrue(cleanup.contains("never into the empty-pass / bounded-stop lines below.\n                    if (asyncScan != null) {"),
                "a done but unmerged scan goes back to the merge, not to the bounded stop");
    }

    @Test
    void anUncoveredEmptyRunDoesNotSayNothingIsLeft() {
        assertTrue(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(true, 0, 0, false));
        assertFalse(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(true, 0, 0, true));
        assertFalse(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(true, 2, 0, false), "it mined something");
        assertFalse(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(true, 0, 3, false), "it removed scaffold");
        assertFalse(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(false, 0, 0, false), "a failure keeps its own line");
        assertEquals(WoodcutCleanupDiagnostics.PARTIAL_SCAN_LINE,
                WoodcutCleanupDiagnostics.playerLine(true, 0, 0, false, false));
        assertEquals("Nothing left to clean up here.", WoodcutCleanupDiagnostics.playerLine(true, 0, 0, false, true));
        assertEquals("Cleaned up 1 log.", WoodcutCleanupDiagnostics.playerLine(true, 1, 0, false, false));
        assertEquals("I couldn't reach the blocks left here.", WoodcutCleanupDiagnostics.playerLine(false, 0, 0, false, false));
        String line = WoodcutCleanupDiagnostics.PARTIAL_SCAN_LINE;
        assertFalse(line.contains("\u2014") || line.contains("\u2013") || line.contains(";"), line);
    }

    @Test
    void emptyRunStopsWellUnderTwentySecondsOnBudgets() {
        // Holds for a region one bounded scan covers; a bigger region needs enough scans to cover it
        // once (ScanCoverage) before the empty stop counts.
        long emptyStop = WoodcutCleanupDiagnostics.EMPTY_MERGE_STOP_STREAK
                * WoodcutCleanupDiagnostics.ASYNC_SCAN_BUDGET_MS;
        long worstTail = WoodcutCleanupDiagnostics.FALLBACK_SWEEP_BUDGET_MS
                + WoodcutCleanupDiagnostics.FINAL_SCAN_BUDGET_MS;
        assertTrue(emptyStop + worstTail < 20_000L);
    }
}
