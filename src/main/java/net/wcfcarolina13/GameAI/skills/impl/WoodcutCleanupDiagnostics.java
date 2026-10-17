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
     * Whether the target loop should stop: {@code consecutiveEmptyMerges} async full scans in a row
     * came back with nothing new while the cached list was empty. Without this the loop relaunched
     * a scan every second until its deadline.
     */
    static boolean shouldStopEmpty(int consecutiveEmptyMerges) {
        return consecutiveEmptyMerges >= EMPTY_MERGE_STOP_STREAK;
    }

    /**
     * Time one background region scan may take before it returns what it has. Two empty merges
     * ({@link #EMPTY_MERGE_STOP_STREAK}) then end an empty run in about 2 x 5 s instead of the whole
     * skill duration: a scan given the skill deadline outlived the loop and never merged.
     */
    static final long ASYNC_SCAN_BUDGET_MS = 5_000L;

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
