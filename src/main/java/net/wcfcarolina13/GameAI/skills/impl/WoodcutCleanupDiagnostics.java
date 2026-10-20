package net.wcfcarolina13.GameAI.skills.impl;

import java.util.Locale;

final class WoodcutCleanupDiagnostics {

    private WoodcutCleanupDiagnostics() {
    }

    static record CleanupScanStats(int actionableTargets,
                                   int blockedProtected,
                                   int blockedHuman,
                                   int rejectedFullTree,
                                   int rejectedGrounded,
                                   String protectedReasonSummary) {
        /**
         * Floaters cleanup wanted but was not allowed to touch (protected zone, next to a player
         * build). Rejections (a live full tree, a grounded log) are not cleanup targets at all.
         */
        boolean hasBlockedFloaters() {
            return blockedProtected > 0 || blockedHuman > 0;
        }

        String formatCounts() {
            String base = String.format(Locale.ROOT,
                    "actionable=%d blockedProtected=%d blockedHuman=%d rejectedFullTree=%d rejectedGrounded=%d",
                    actionableTargets, blockedProtected, blockedHuman, rejectedFullTree, rejectedGrounded);
            if (blockedProtected > 0
                    && protectedReasonSummary != null
                    && !protectedReasonSummary.isBlank()
                    && !"none".equalsIgnoreCase(protectedReasonSummary)) {
                return base + " protectedReasons=" + protectedReasonSummary;
            }
            return base;
        }
    }

    static String buildCompletionSummary(boolean success,
                                         int logsMined,
                                         int scaffoldMined,
                                         int attemptedTargets,
                                         int radius,
                                         int verticalRange,
                                         int remainingRemembered,
                                         int remainingOverhead,
                                         int remainingLogs,
                                         CleanupScanStats stats) {
        String prefix = success ? "Cleanup done" : "Cleanup incomplete";
        if (stats == null) {
            stats = new CleanupScanStats(0, 0, 0, 0, 0, "none");
        }
        return String.format(Locale.ROOT,
                "%s: minedLogs=%d removedScaffold=%d attemptedTargets=%d remainingRemembered=%d remainingOverhead=%d remainingLogs=%d %s (radius=%d vertical=%d)",
                prefix,
                logsMined,
                scaffoldMined,
                attemptedTargets,
                remainingRemembered,
                remainingOverhead,
                remainingLogs,
                stats.formatCounts(),
                radius,
                verticalRange);
    }

    /** Consecutive async scans that merged nothing into an empty list before the sweep stops. */
    static final int EMPTY_MERGE_STOP_STREAK = 2;

    /**
     * Whether the target loop should stop: {@code consecutiveEmptyMerges} async scans in a row came
     * back with nothing new while the cached list was empty, and the scans since the last find have
     * covered the whole region at least once ({@link ScanCoverage#coveredSinceLastFind}). A scan cut
     * short by its budget sees only part of the region, so empty merges alone are not "nothing left".
     */
    static boolean shouldStopEmpty(int consecutiveEmptyMerges, boolean coveredSinceLastFind) {
        return coveredSinceLastFind && consecutiveEmptyMerges >= EMPTY_MERGE_STOP_STREAK;
    }

    /**
     * Time one background region scan may take before it returns what it has. Each scan resumes
     * where the last one stopped ({@link ScanCoverage}), so a region one scan covers ends an empty
     * run in about 2 x 5 s, and a bigger one after enough scans to cover it, instead of the whole
     * skill duration: a scan given the skill deadline outlived the loop and never merged.
     */
    static final long ASYNC_SCAN_BUDGET_MS = 5_000L;

    /** Blocks in the inclusive box between two corners, in any corner order. */
    static long regionVolume(int ax, int ay, int az, int bx, int by, int bz) {
        return (long) (Math.abs(bx - ax) + 1) * (Math.abs(by - ay) + 1) * (Math.abs(bz - az) + 1);
    }

    /**
     * Offset {dx, dy, dz} of scan index {@code index} in a box of the given size, x fastest, then y,
     * then z. Indices 0 .. volume-1 map one to one onto the box; larger ones wrap.
     */
    static int[] offsetAt(long index, int sizeX, int sizeY, int sizeZ) {
        long volume = (long) sizeX * sizeY * sizeZ;
        long i = Math.floorMod(index, volume);
        int dx = (int) (i % sizeX);
        int dy = (int) ((i / sizeX) % sizeY);
        int dz = (int) (i / ((long) sizeX * sizeY));
        return new int[]{dx, dy, dz};
    }

    /**
     * How far the bounded region scans of one run have got: the index the next scan starts at,
     * blocks scanned in total, and blocks scanned since the last time a target turned up. Successive
     * scans resume at {@code cursor} and wrap at the end, so they cover new ground instead of the
     * same prefix from the same corner.
     */
    record ScanCoverage(long volume, long cursor, long scannedTotal, long scannedSinceLastFind) {

        static ScanCoverage start(long volume) {
            return new ScanCoverage(Math.max(0L, volume), 0L, 0L, 0L);
        }

        /** After a scan that looked at {@code scanned} blocks from the cursor; {@code found} resets the empty run. */
        ScanCoverage afterScan(long scanned, boolean found) {
            long n = Math.max(0L, scanned);
            long next = volume <= 0L ? 0L : Math.floorMod(cursor + n, volume);
            return new ScanCoverage(volume, next, scannedTotal + n, found ? 0L : scannedSinceLastFind + n);
        }

        /** Targets are being worked: the region must be covered again with nothing new before an empty stop. */
        ScanCoverage afterFind() {
            return scannedSinceLastFind == 0L ? this : new ScanCoverage(volume, cursor, scannedTotal, 0L);
        }

        /** Every block of the region was looked at at least once this run. */
        boolean fullyCovered() {
            return scannedTotal >= volume;
        }

        /** Every block was looked at at least once since the last target turned up. */
        boolean coveredSinceLastFind() {
            return scannedSinceLastFind >= volume;
        }

        /** Share of the region scanned this run, 0..1. */
        double fraction() {
            if (volume <= 0L) {
                return 1.0D;
            }
            return Math.min(1.0D, scannedTotal / (double) volume);
        }
    }

    /**
     * How long a run that has found nothing at all keeps scanning before it ends with the
     * partial-scan line. A quiet forest region of ~30k blocks takes a couple of minutes of bounded
     * scans to cover; a run with no find by now ends instead of filling the skill deadline.
     */
    static final long EMPTY_SCAN_PHASE_CAP_MS = 30_000L;

    /**
     * Whether a run should end its empty scan phase: nothing mined, nothing attempted, nothing
     * cached to work on, and at least {@code capMs} since the run started. A run that found or
     * mined anything never ends here.
     */
    static boolean shouldEndEmptyScanPhase(long startMs,
                                           long nowMs,
                                           int logsMined,
                                           int attemptedTargets,
                                           int cachedTargets,
                                           long capMs) {
        if (logsMined != 0 || attemptedTargets != 0 || cachedTargets != 0) {
            return false;
        }
        return nowMs - startMs >= capMs;
    }

    /**
     * Whether the fallback sweep may mine a pillar-material block. Sun-exposed dirt is still
     * judged by shape (the caller's grass and sky checks), but stone-type material is mined only
     * when this bot placed it: remembered scaffold or registered owned scaffold. 1.1.272 sandbox:
     * the sweep mined the natural stone the bot stood on because stone is a pillar material.
     * {@code treeSoil} marks the soil a felled trunk stood on: mined only when this bot placed it
     * (finding 38, 1.1.274: the sweep dug out the dirt under a felled trunk and a bot fell in).
     * {@code anyTreeSoil} marks soil of any tree, any bot's: the shared recent-soil ledger, a live
     * claim's ring, or a log or sapling right above. It vetoes the dirt-like shape rule but not this
     * bot's own remembered or owned scaffold (finding 52: one bot's sweep dug out the dirt under
     * another bot's tree). {@code remembered} must be this bot's own memory.
     */
    static boolean fallbackMayMinePillarBlock(boolean dirtLike, boolean remembered, boolean ownedScaffold,
                                              boolean treeSoil, boolean anyTreeSoil) {
        if (remembered || ownedScaffold) {
            return true;
        }
        return dirtLike && !treeSoil && !anyTreeSoil;
    }

    /**
     * The {@code protectSoil} cleanup parameter: packed positions of the soil under felled trunks.
     * Anything other than a {@code long[]} (absent, or a stray type from a chat call) protects
     * nothing.
     */
    static java.util.Set<Long> parseProtectedSoil(Object raw) {
        if (!(raw instanceof long[] packed) || packed.length == 0) {
            return java.util.Set.of();
        }
        java.util.Set<Long> out = new java.util.HashSet<>(packed.length * 2);
        for (long p : packed) {
            out.add(p);
        }
        return out;
    }

    /** How far above a column block a standing player still counts as on that pillar. */
    static final int STANDS_ON_COLUMN_MAX_ABOVE = 8;

    /**
     * Whether a player whose feet are in block (px, py, pz) stands on the column through block
     * (x, y, z): same block X and Z, and up to {@code maxAbove} blocks above it (finding 52: the
     * heuristic scan and the fallback sweep tore down another bot's pillar under its feet).
     */
    static boolean standsOnColumn(int px, int py, int pz, int x, int y, int z, int maxAbove) {
        return px == x && pz == z && py > y && py - y <= maxAbove;
    }

    /**
     * Whether a player on the column stops cleanup from mining it: any player but the cleaning
     * bot itself. Its own pillar may have dropped out of its memory (restart, memory trim) and
     * still has to come down under it, as descending cleanup already does.
     */
    static boolean blocksColumnCleanup(boolean cleaningBot, int px, int py, int pz,
                                       int x, int y, int z, int maxAbove) {
        return !cleaningBot && standsOnColumn(px, py, pz, x, y, z, maxAbove);
    }

    /** Time the post-loop fallback sweep (scan plus any mining it finds) may take. */
    static final long FALLBACK_SWEEP_BUDGET_MS = 3_000L;

    /** Time the end-of-run leftover count may take. */
    static final long FINAL_SCAN_BUDGET_MS = 2_000L;

    /**
     * Deadline for one bounded scan: {@code budgetMs} from {@code nowMs}, but never past the skill
     * deadline. A skill deadline already in the past is returned unchanged.
     */
    static long boundedScanDeadline(long nowMs, long skillDeadlineMs, long budgetMs) {
        long budgeted = budgetMs <= 0L ? nowMs : nowMs + budgetMs;
        if (budgeted < nowMs) {
            budgeted = Long.MAX_VALUE; // overflow
        }
        return Math.min(skillDeadlineMs, budgeted);
    }

    /**
     * Whether the run left nothing behind: no remembered, overhead or visible leftover logs, and
     * when nothing was even attempted, no floater it was blocked from touching. Live full trees and
     * grounded logs the scan rejected are not leftovers.
     */
    static boolean isSuccess(int remainingRemembered,
                             int remainingOverhead,
                             int remainingLogs,
                             int logsMined,
                             int attemptedTargets,
                             CleanupScanStats stats) {
        if (remainingRemembered != 0 || remainingOverhead != 0 || remainingLogs != 0) {
            return false;
        }
        boolean blocked = stats != null && stats.hasBlockedFloaters();
        return !(logsMined == 0 && attemptedTargets == 0 && blocked);
    }

    /** A successful run that found nothing to mine and removed nothing. */
    static boolean isNothingToDo(boolean success, int logsMined, int scaffoldMined) {
        return success && logsMined == 0 && scaffoldMined == 0;
    }

    /** What a run that found nothing says when its scans did not reach the whole region in time. */
    static final String PARTIAL_SCAN_LINE = "I checked what I could reach in time and found nothing to clean up.";

    /**
     * A run that would say "Nothing left to clean up here." although its scans never covered the
     * whole region: it removed nothing, so it cannot vouch for the part it did not look at.
     */
    static boolean isUncoveredNothingToDo(boolean success, int logsMined, int scaffoldMined, boolean fullyCovered) {
        return !fullyCovered && isNothingToDo(success, logsMined, scaffoldMined);
    }

    /** What a run held back only by protection says (no "couldn't reach", which names the wrong cause). */
    static final String PROTECTED_ONLY_LINE = "Some logs are in a protected area, so I left them.";

    /**
     * Whether a run failed only because the floaters it found were off limits (protected zone,
     * next to a player build): nothing remembered, overhead or visible is left, and the blocked
     * floaters are what made {@link #isSuccess} say no.
     */
    static boolean isBlockedOnlyByProtection(boolean success,
                                             int remainingRemembered,
                                             int remainingOverhead,
                                             int remainingLogs,
                                             CleanupScanStats stats) {
        return !success
                && remainingRemembered == 0
                && remainingOverhead == 0
                && remainingLogs == 0
                && stats != null
                && stats.hasBlockedFloaters();
    }

    /**
     * The one sentence the player sees when cleanup finishes. The stat summary
     * ({@link #buildCompletionSummary}) stays in the log.
     */
    static String playerLine(boolean success, int logsMined, int scaffoldMined) {
        return playerLine(success, logsMined, scaffoldMined, false);
    }

    /** {@link #playerLine(boolean, int, int, boolean)}, with the honest line for an uncovered empty run. */
    static String playerLine(boolean success, int logsMined, int scaffoldMined, boolean blockedOnlyByProtection, boolean fullyCovered) {
        if (isUncoveredNothingToDo(success, logsMined, scaffoldMined, fullyCovered)) {
            return PARTIAL_SCAN_LINE;
        }
        return playerLine(success, logsMined, scaffoldMined, blockedOnlyByProtection);
    }

    static String playerLine(boolean success, int logsMined, int scaffoldMined, boolean blockedOnlyByProtection) {
        if (!success) {
            if (blockedOnlyByProtection) {
                if (scaffoldMined > 0) {
                    return "Cleaned up " + countPhrase(scaffoldMined, "scaffold block", "scaffold blocks") + ". "
                            + PROTECTED_ONLY_LINE;
                }
                return PROTECTED_ONLY_LINE;
            }
            if (logsMined <= 0 && scaffoldMined <= 0) {
                return "I couldn't reach the blocks left here.";
            }
            return "Cleaned up what I could, a few blocks are out of reach.";
        }
        if (isNothingToDo(true, logsMined, scaffoldMined)) {
            return "Nothing left to clean up here.";
        }
        String logs = logsMined > 0 ? countPhrase(logsMined, "log", "logs") : null;
        String scaffold = scaffoldMined > 0 ? countPhrase(scaffoldMined, "scaffold block", "scaffold blocks") : null;
        if (logs != null && scaffold != null) {
            return "Cleaned up " + logs + " and " + scaffold + ".";
        }
        return "Cleaned up " + (logs != null ? logs : scaffold) + ".";
    }

    private static String countPhrase(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
