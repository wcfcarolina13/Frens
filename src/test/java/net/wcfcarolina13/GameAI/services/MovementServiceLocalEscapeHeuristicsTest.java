package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.PathFinding.PathFinder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementServiceLocalEscapeHeuristicsTest {

    @Test
    void localEscapeScoringPrefersStepUpThatLeavesTrap() {
        double trappedStepUp = MovementService.scoreLocalEscapeCandidate(0.05D, 1, 3, true, true, false);
        double flatNoEscape = MovementService.scoreLocalEscapeCandidate(0.20D, 1, 1, false, false, false);

        assertTrue(trappedStepUp > flatNoEscape);
    }

    @Test
    void nearbyDiagonalLeadInUsesRawPathSegments() {
        List<PathFinder.PathNode> diagonalLeadIn = List.of(
                new PathFinder.PathNode(new BlockPos(0, 64, 0), "air", true, false),
                new PathFinder.PathNode(new BlockPos(1, 65, 1), "air", true, true),
                new PathFinder.PathNode(new BlockPos(2, 65, 2), "air", true, false)
        );
        List<PathFinder.PathNode> flatPath = List.of(
                new PathFinder.PathNode(new BlockPos(0, 64, 0), "air", true, false),
                new PathFinder.PathNode(new BlockPos(1, 64, 0), "air", true, false),
                new PathFinder.PathNode(new BlockPos(2, 64, 0), "air", true, false)
        );

        assertTrue(MovementService.shouldUseRawPathSegmentsForLocalEscape(
                diagonalLeadIn,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 65, 4)
        ));
        assertFalse(MovementService.shouldUseRawPathSegmentsForLocalEscape(
                flatPath,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0)
        ));
    }

    @Test
    void routeScoringPrefersTrueExitOverGreedyWallHop() {
        double wallHop = MovementService.scoreLocalEscapeRoute(
                1.00D,
                1,
                1,
                3,
                3,
                1,
                false,
                false,
                1
        );
        double twoHopExit = MovementService.scoreLocalEscapeRoute(
                0.25D,
                1,
                3,
                3,
                8,
                1,
                true,
                false,
                2
        );

        assertTrue(twoHopExit > wallHop);
    }

    @Test
    void compellingEndpointRequiresMoreThanTinyWallShelf() {
        assertFalse(MovementService.isCompellingLocalEscapeEndpoint(1, 3, 1, 3, 1, 1));
        assertTrue(MovementService.isCompellingLocalEscapeEndpoint(1, 3, 3, 7, 1, 2));
    }
}
