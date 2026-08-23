package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WoodcutRecoveryHeuristicsTest {

    @Test
    void reroutePrefersLowerSaferCandidateOverNearbyChurnyPerch() {
        BlockPos bot = new BlockPos(0, 10, 0);
        BlockPos target = new BlockPos(4, 8, 0);
        BlockPos unsafeNearby = new BlockPos(1, 11, 0);
        BlockPos saferLower = new BlockPos(2, 9, 0);

        double unsafeScore = WoodcutRecoveryHeuristics.rerouteCandidateScore(
                bot, unsafeNearby, target, 1, 2, 2, 3, true);
        double safeScore = WoodcutRecoveryHeuristics.rerouteCandidateScore(
                bot, saferLower, target, 3, 5, 0, 1, false);

        assertTrue(safeScore < unsafeScore);
    }

    @Test
    void detectsPrecisionChurnRiskForTightCandidates() {
        assertTrue(WoodcutRecoveryHeuristics.isPrecisionChurnRisk(1, 2, 0, 3));
        assertTrue(WoodcutRecoveryHeuristics.isPrecisionChurnRisk(1, 3, 2, 1));
        assertFalse(WoodcutRecoveryHeuristics.isPrecisionChurnRisk(3, 5, 0, 1));
    }

    @Test
    void protectsSupportTerrainByDefault() {
        assertTrue(WoodcutRecoveryHeuristics.shouldProtectTerrainCarve(98, 98, true, false));
        assertTrue(WoodcutRecoveryHeuristics.shouldProtectTerrainCarve(98, 97, true, false));
        assertFalse(WoodcutRecoveryHeuristics.shouldProtectTerrainCarve(98, 99, true, false));
        assertFalse(WoodcutRecoveryHeuristics.shouldProtectTerrainCarve(98, 98, true, true));
    }
}
