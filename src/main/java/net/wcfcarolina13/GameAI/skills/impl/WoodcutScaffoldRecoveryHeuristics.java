package net.wcfcarolina13.GameAI.skills.impl;

final class WoodcutScaffoldRecoveryHeuristics {
    private WoodcutScaffoldRecoveryHeuristics() {
    }

    static boolean shouldRecoverSurface(int botBlockY, int workingY, int surfaceY, boolean skyVisible) {
        boolean belowSurface = botBlockY + 1 < surfaceY;
        boolean belowWorkingArea = botBlockY + 1 < workingY - 1;
        boolean trappedUnderCover = !skyVisible && belowWorkingArea && botBlockY + 2 < surfaceY;
        return belowSurface || trappedUnderCover;
    }

    static int desiredPillarRecoverySteps(int botBlockY, int surfaceY, int availableBlocks) {
        int needed = Math.max(0, surfaceY - botBlockY);
        return Math.max(0, Math.min(needed, Math.max(0, availableBlocks)));
    }

    static boolean shouldRepositionForCleanup(int botBlockY, int cleanupBaseY, int surfaceY, boolean skyVisible) {
        return shouldRecoverSurface(botBlockY, cleanupBaseY + 1, surfaceY, skyVisible)
                || botBlockY + 1 < cleanupBaseY;
    }
}
