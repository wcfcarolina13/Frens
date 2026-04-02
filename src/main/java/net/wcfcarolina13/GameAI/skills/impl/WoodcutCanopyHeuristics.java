package net.wcfcarolina13.GameAI.skills.impl;

final class WoodcutCanopyHeuristics {

    private WoodcutCanopyHeuristics() {
    }

    static boolean isUnsafeCanopyAssessment(int cardinalStandableNeighbors,
                                            int totalStandableNeighbors,
                                            int steepDropNeighbors,
                                            int blockedCardinals,
                                            boolean leafFloor,
                                            boolean dangerousDrop) {
        if (dangerousDrop) {
            return true;
        }
        if (steepDropNeighbors >= 2 && cardinalStandableNeighbors <= 1) {
            return true;
        }
        if (blockedCardinals >= 3 && steepDropNeighbors >= 1) {
            return true;
        }
        return leafFloor
                && (totalStandableNeighbors < 3
                || cardinalStandableNeighbors < 1
                || steepDropNeighbors >= 2);
    }
}
