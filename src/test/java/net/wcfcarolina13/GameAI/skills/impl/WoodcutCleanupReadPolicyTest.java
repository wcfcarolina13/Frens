package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoodcutCleanupReadPolicyTest {
    @Test
    void unknownCellsPreserveKnownCoverageAndAdvanceTheScanCursor() {
        var coverage = WoodcutCleanupDiagnostics.ScanCoverage.start(100L)
                .afterScan(60L, false)
                .afterScan(30L, false, 10L);
        org.junit.jupiter.api.Assertions.assertEquals(90L, coverage.cursor());
        org.junit.jupiter.api.Assertions.assertEquals(80L, coverage.scannedTotal());
        org.junit.jupiter.api.Assertions.assertEquals(10L, coverage.unknownCells());
        assertFalse(coverage.fullyCovered());
        assertFalse(coverage.coveredSinceLastFind());
        coverage = coverage.afterScan(10L, false, 2L);
        assertFalse(coverage.fullyCovered());
        assertTrue(coverage.coveredSinceLastFind());
        assertTrue(WoodcutCleanupDiagnostics.shouldStopEmpty(2, coverage.coveredSinceLastFind()));
        assertTrue(WoodcutCleanupDiagnostics.isUncoveredNothingToDo(true, 0, 0, coverage.fullyCovered()));
        org.junit.jupiter.api.Assertions.assertEquals(WoodcutCleanupDiagnostics.PARTIAL_SCAN_LINE,
                WoodcutCleanupDiagnostics.playerLine(true, 0, 0, false, coverage.fullyCovered()));
    }

    @Test
    void aFindResetsKnownAndUnknownEmptyCoverageTogether() {
        var coverage = WoodcutCleanupDiagnostics.ScanCoverage.start(100L)
                .afterScan(100L, false, 40L).afterFind();
        assertFalse(coverage.fullyCovered());
        assertFalse(coverage.coveredSinceLastFind());
        coverage = coverage.afterScan(60L, false, 20L);
        assertFalse(coverage.coveredSinceLastFind());
        assertTrue(coverage.afterScan(40L, false, 10L).coveredSinceLastFind());
    }

    @Test
    void unknownCountIsBoundedByVisitedCells() {
        var coverage = WoodcutCleanupDiagnostics.ScanCoverage.start(100L)
                .afterScan(30L, false, 90L);
        org.junit.jupiter.api.Assertions.assertEquals(0L, coverage.scannedTotal());
        org.junit.jupiter.api.Assertions.assertEquals(30L, coverage.unknownCells());
        assertFalse(coverage.fullyCovered());
    }

    @Test
    void earlierUnknownCellsDoNotInvalidateACompleteCandidateRead() {
        assertTrue(WoodcutCleanupReadPolicy.readsKnown(5L, 5L));
    }

    @Test
    void missingNeighborPreventsUsingOrCachingTheClassification() {
        assertFalse(WoodcutCleanupReadPolicy.readsKnown(5L, 6L));
    }

    @Test
    void repeatedCachedChunkMissStillInvalidatesTheNextCandidate() {
        assertFalse(WoodcutCleanupReadPolicy.readsKnown(6L, 7L));
    }

    @Test
    void unloadedRememberedCellIsPreserved() {
        assertFalse(WoodcutCleanupReadPolicy.shouldForgetRemembered(false, false));
    }

    @Test
    void knownReplacementRemovesStaleMemoryButExistingLogStays() {
        assertTrue(WoodcutCleanupReadPolicy.shouldForgetRemembered(true, false));
        assertFalse(WoodcutCleanupReadPolicy.shouldForgetRemembered(true, true));
    }
}
