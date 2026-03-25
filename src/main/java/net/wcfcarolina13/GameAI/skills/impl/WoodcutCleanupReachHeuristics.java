package net.wcfcarolina13.GameAI.skills.impl;

final class WoodcutCleanupReachHeuristics {
    private WoodcutCleanupReachHeuristics() {
    }

    static int requiredPillarSteps(int botBlockY, int targetY) {
        return Math.max(0, targetY - botBlockY - 1);
    }

    static boolean shouldAttemptElevatedRecovery(int botBlockY, int targetY) {
        return requiredPillarSteps(botBlockY, targetY) > 0;
    }

    static String elevatedReachFailureReason(int botBlockY, int targetY, boolean pillarAttempted) {
        if (!shouldAttemptElevatedRecovery(botBlockY, targetY)) {
            return "no-reachable-stand";
        }
        return pillarAttempted ? "post-pillar-unreachable" : "pillar-failed";
    }
}
