package net.wcfcarolina13.GameAI.skills.impl;

/** Shared refusal for every Fortify mining entry point. Only the explicit ring exit opts out. */
final class FortifyMiningPolicy {
    private FortifyMiningPolicy() {}

    static boolean mayMine(boolean satisfiedLayout, boolean explicitRingBreakout) {
        return !satisfiedLayout || explicitRingBreakout;
    }

    static boolean outsideTower(int x, int z, int towerX, int towerZ) {
        return Math.abs(x - towerX) > 1 || Math.abs(z - towerZ) > 1;
    }
}
