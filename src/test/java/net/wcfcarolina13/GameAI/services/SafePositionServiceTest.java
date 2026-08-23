package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafePositionServiceTest {

    @Test
    void operationalSurfaceAssessmentRejectsShaftLikeFootprints() {
        SafePositionService.SurfaceCandidateAssessment shaft = new SafePositionService.SurfaceCandidateAssessment(
                true,
                true,
                true,
                1,
                2,
                0,
                3,
                0,
                0.0D
        );

        assertFalse(SafePositionService.isOperationalSurfaceAssessment(shaft));
    }

    @Test
    void operationalSurfaceAssessmentAcceptsOpenUsableTerrain() {
        SafePositionService.SurfaceCandidateAssessment openTerrain = new SafePositionService.SurfaceCandidateAssessment(
                true,
                true,
                true,
                3,
                6,
                0,
                1,
                1,
                4.0D
        );

        assertTrue(SafePositionService.isOperationalSurfaceAssessment(openTerrain));
    }

    @Test
    void scoringPrefersSaferFlatterSurfaceCandidates() {
        SafePositionService.SurfaceCandidateAssessment safeFlat = new SafePositionService.SurfaceCandidateAssessment(
                true,
                true,
                true,
                3,
                7,
                0,
                1,
                1,
                9.0D
        );
        SafePositionService.SurfaceCandidateAssessment riskyRavineEdge = new SafePositionService.SurfaceCandidateAssessment(
                true,
                true,
                true,
                2,
                4,
                2,
                2,
                5,
                1.0D
        );

        assertTrue(SafePositionService.scoreSurfaceAssessment(safeFlat)
                > SafePositionService.scoreSurfaceAssessment(riskyRavineEdge));
    }

    @Test
    void scoringCanBiasTowardKnownTargetWithoutOverridingSafety() {
        SafePositionService.SurfaceCandidateAssessment safe = new SafePositionService.SurfaceCandidateAssessment(
                true,
                true,
                true,
                3,
                7,
                0,
                1,
                1,
                9.0D
        );

        int towardTarget = SafePositionService.scoreSurfaceAssessment(
                safe,
                new BlockPos(6, 86, 0),
                BlockPos.ORIGIN,
                new BlockPos(10, 86, 0)
        );
        int awayFromTarget = SafePositionService.scoreSurfaceAssessment(
                safe,
                new BlockPos(-6, 86, 0),
                BlockPos.ORIGIN,
                new BlockPos(10, 86, 0)
        );

        assertTrue(towardTarget > awayFromTarget);
    }
}
