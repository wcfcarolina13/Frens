package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.util.math.BlockPos;

final class WoodcutRecoveryHeuristics {

    private WoodcutRecoveryHeuristics() {
    }

    static boolean isPrecisionChurnRisk(int cardinalStandableNeighbors,
                                        int totalStandableNeighbors,
                                        int steepDropNeighbors,
                                        int blockedCardinals) {
        return totalStandableNeighbors <= 2
                || cardinalStandableNeighbors <= 1
                || steepDropNeighbors >= 2
                || blockedCardinals >= 3;
    }

    static double rerouteCandidateScore(BlockPos botPos,
                                        BlockPos candidate,
                                        BlockPos target,
                                        int cardinalStandableNeighbors,
                                        int totalStandableNeighbors,
                                        int steepDropNeighbors,
                                        int blockedCardinals,
                                        boolean precisionChurnRisk) {
        if (botPos == null || candidate == null) {
            return Double.POSITIVE_INFINITY;
        }
        double score = candidate.getSquaredDistance(botPos) * 0.15D;
        if (target != null) {
            score += candidate.getSquaredDistance(target) * 0.08D;
        }
        score += Math.max(0, candidate.getY() - botPos.getY()) * 6.0D;
        score += steepDropNeighbors * 10.0D;
        score += blockedCardinals * 4.0D;
        score -= cardinalStandableNeighbors * 3.0D;
        score -= totalStandableNeighbors * 1.5D;
        if (precisionChurnRisk) {
            score += 20.0D;
        }
        return score;
    }

    static boolean shouldProtectTerrainCarve(int standY,
                                             int candidateY,
                                             boolean terrainLike,
                                             boolean allowSupportCarve) {
        return terrainLike
                && candidateY <= standY
                && !allowSupportCarve;
    }
}
