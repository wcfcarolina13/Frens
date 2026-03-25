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
        boolean hasBlockedOrRejectedFloaters() {
            return blockedProtected > 0 || blockedHuman > 0 || rejectedFullTree > 0 || rejectedGrounded > 0;
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
}
