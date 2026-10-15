package net.wcfcarolina13.GameAI.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.wcfcarolina13.PathFinding.unified.MoveKind;
import net.wcfcarolina13.PathFinding.unified.NoPathReason;
import net.wcfcarolina13.PathFinding.unified.PlanStatus;
import net.wcfcarolina13.PathFinding.unified.PlanUnits;
import org.junit.jupiter.api.Test;

class UnifiedWalkPolicyTest {

    @Test
    void boxedInTerrainMoveOnlyForNonMutatingRequestOnce() {
        assertEquals(UnifiedWalkPolicy.NoPathStep.TERRAIN_MOVE,
                UnifiedWalkPolicy.onNoPath(NoPathReason.BOXED_IN, false, false));
        assertEquals(UnifiedWalkPolicy.NoPathStep.FAIL, UnifiedWalkPolicy.onNoPath(NoPathReason.BOXED_IN, false, true));
        assertEquals(UnifiedWalkPolicy.NoPathStep.FAIL, UnifiedWalkPolicy.onNoPath(NoPathReason.BOXED_IN, true, false));
    }

    @Test
    void otherNoPathReasonsAreFinal() {
        for (NoPathReason reason : NoPathReason.values()) {
            if (reason != NoPathReason.BOXED_IN) {
                assertEquals(UnifiedWalkPolicy.NoPathStep.FAIL, UnifiedWalkPolicy.onNoPath(reason, false, false), reason.name());
                assertEquals(UnifiedWalkPolicy.NoPathStep.FAIL, UnifiedWalkPolicy.onNoPath(reason, true, false), reason.name());
            }
        }
    }

    @Test
    void oneFailureReplanLikeTheLegacyTwoAttempts() {
        assertTrue(UnifiedWalkPolicy.replanAfterFailure(0));
        assertFalse(UnifiedWalkPolicy.replanAfterFailure(1));
        assertFalse(UnifiedWalkPolicy.replanAfterFailure(2));
    }

    @Test
    void preclearOnlyWithoutPlannedBreaks() {
        assertTrue(UnifiedWalkPolicy.preclearStepUps(false));
        assertFalse(UnifiedWalkPolicy.preclearStepUps(true));
    }

    @Test
    void afterPlanArrivalStopAndLegs() {
        assertEquals(UnifiedWalkPolicy.AfterPlan.ARRIVED,
                UnifiedWalkPolicy.afterPlan(PlanStatus.FULL, true, 1, 0, false, false));
        assertEquals(UnifiedWalkPolicy.AfterPlan.ARRIVED,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, true, 1, 0, false, true));
        assertEquals(UnifiedWalkPolicy.AfterPlan.STOP,
                UnifiedWalkPolicy.afterPlan(PlanStatus.FULL, false, 1, 20, false, false));
        assertEquals(UnifiedWalkPolicy.AfterPlan.NEXT_LEG,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, false, 1, NavLegPolicy.MIN_PROGRESS_BLOCKS, false, false));
        assertEquals(UnifiedWalkPolicy.AfterPlan.STOP,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, false, 1, 2.5, false, false));
        assertEquals(UnifiedWalkPolicy.AfterPlan.STOP,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, false, 1, 20, false, true));
        assertEquals(UnifiedWalkPolicy.AfterPlan.STOP,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, false, 1, 20, true, false));
        assertEquals(UnifiedWalkPolicy.AfterPlan.STOP,
                UnifiedWalkPolicy.afterPlan(PlanStatus.PARTIAL, false, NavLegPolicy.MAX_LEGS, 20, false, false));
    }

    @Test
    void failedHopOriginFindsTheBotsNodeButNotTheLast() {
        List<PlanUnits.PlanNode> nodes = List.of(
                new PlanUnits.PlanNode(0, 64, 0, false, null),
                new PlanUnits.PlanNode(1, 64, 0, false, MoveKind.TRAVERSE),
                new PlanUnits.PlanNode(2, 65, 0, true, MoveKind.ASCEND));
        assertEquals(0, UnifiedWalkPolicy.failedHopOrigin(nodes, 0, 64, 0));
        assertEquals(1, UnifiedWalkPolicy.failedHopOrigin(nodes, 1, 64, 0));
        assertEquals(-1, UnifiedWalkPolicy.failedHopOrigin(nodes, 2, 65, 0));
        assertEquals(-1, UnifiedWalkPolicy.failedHopOrigin(nodes, 5, 64, 0));
        assertEquals(-1, UnifiedWalkPolicy.failedHopOrigin(null, 0, 64, 0));
    }

    @Test
    void skipForwardToALaterUnitOrigin() {
        List<Long> origins = List.of(10L, 20L, 30L, 40L);
        assertEquals(1, UnifiedWalkPolicy.skipForwardIndex(origins, 1, 20L));   // on the current origin
        assertEquals(3, UnifiedWalkPolicy.skipForwardIndex(origins, 1, 40L));   // already on a later origin
        assertEquals(1, UnifiedWalkPolicy.skipForwardIndex(origins, 1, 10L));   // never backwards
        assertEquals(1, UnifiedWalkPolicy.skipForwardIndex(origins, 1, 99L));   // off plan: the unit decides
        assertEquals(0, UnifiedWalkPolicy.skipForwardIndex(null, 0, 10L));
    }
}
