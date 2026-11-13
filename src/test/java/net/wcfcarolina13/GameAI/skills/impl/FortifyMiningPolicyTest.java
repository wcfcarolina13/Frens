package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FortifyMiningPolicyTest {
    @Test void satisfiedTowerCannotBeMinedForScaffoldsOrNavigation() {
        assertFalse(FortifyMiningPolicy.mayMine(true, false));
    }
    @Test void onlyExplicitRingBreakoutMayMineSatisfiedLayout() {
        assertTrue(FortifyMiningPolicy.mayMine(true, true));
    }
    @Test void terrainAndUnsatisfiedLayoutRemainMineable() {
        assertTrue(FortifyMiningPolicy.mayMine(false, false));
    }
    @Test void everyColumnOfThreeByThreeFootprintRefusesPillars() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            assertFalse(FortifyMiningPolicy.outsideTower(54415 + dx, 16798 + dz, 54415, 16798));
    }
    @Test void cardinalAndDiagonalColumnsBeyondRingAllowPillars() {
        assertTrue(FortifyMiningPolicy.outsideTower(54417, 16798, 54415, 16798));
        assertTrue(FortifyMiningPolicy.outsideTower(54413, 16796, 54415, 16798));
    }
}
