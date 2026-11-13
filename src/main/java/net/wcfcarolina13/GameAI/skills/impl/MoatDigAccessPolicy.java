package net.wcfcarolina13.GameAI.skills.impl;

/** Dig-list and stance decisions, independent of world and entity types. */
final class MoatDigAccessPolicy {
    private MoatDigAccessPolicy() {}

    static boolean dropCoveredClear(boolean exteriorClear, boolean solidAbove, boolean abovePlannedForDig) {
        return exteriorClear && solidAbove && !abovePlannedForDig;
    }

    static int compareHeight(int leftY, int rightY) { return Integer.compare(rightY, leftY); }

    static double visibilityScore(boolean reach, boolean visible, boolean orthogonal) {
        return (reach ? 1_000 : 0) + (reach && visible ? 10_000 : 0) + (orthogonal ? 200 : 0);
    }
}
