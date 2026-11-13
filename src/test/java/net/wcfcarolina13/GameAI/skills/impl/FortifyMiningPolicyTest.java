package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FortifyMiningPolicyTest {
    @Test void satisfiedLayoutIsProtectedDuringScaffoldAndPlacementClearing() {
        assertFalse(FortifyMiningPolicy.mayMine(true, false, FortifyMiningPolicy.Context.SCAFFOLD_CLEANUP));
        assertFalse(FortifyMiningPolicy.mayMine(true, false, FortifyMiningPolicy.Context.PLACEMENT_CLEARING));
    }
    @Test void explicitRecoveryContextsCanOpenSatisfiedLayoutAndNaturalTerrain() {
        for (var context : new FortifyMiningPolicy.Context[] {
                FortifyMiningPolicy.Context.CARVE_RECOVERY, FortifyMiningPolicy.Context.ESCAPE_HEADROOM,
                FortifyMiningPolicy.Context.GATE_EXIT, FortifyMiningPolicy.Context.TOWER_RING_BREAKOUT }) {
            assertTrue(FortifyMiningPolicy.mayMine(true, false, context), context.name());
        }
    }
    @Test void digPlanWinsOverSatisfiedLiningInEveryContext() {
        for (var context : FortifyMiningPolicy.Context.values()) {
            assertTrue(FortifyMiningPolicy.mayMine(true, true, context), context.name());
        }
    }
    @Test void terrainAndUnsatisfiedLayoutRemainMineable() {
        for (var context : FortifyMiningPolicy.Context.values()) {
            assertTrue(FortifyMiningPolicy.mayMine(false, false, context), context.name());
        }
    }
    @Test void cachedKeepViewTracksSatisfactionWithoutProtectingDigOverlaps() {
        var satisfied = new java.util.HashSet<>(java.util.Set.of("tower", "lining-overlap"));
        var keep = FortifyMiningPolicy.satisfiedPositions(
                java.util.Set.of("tower", "lining-overlap", "unfinished"),
                java.util.Set.of("lining-overlap"), satisfied::contains);
        assertEquals(java.util.Set.of("tower"), keep);
        assertFalse(keep.contains("lining-overlap"));
        assertFalse(keep.contains("unfinished"));
        satisfied.remove("tower");
        satisfied.add("unfinished");
        assertFalse(keep.contains("tower"));
        assertTrue(keep.contains("unfinished"));
        assertEquals(java.util.Set.of("unfinished"), keep);
        assertEquals(1, keep.size());
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
