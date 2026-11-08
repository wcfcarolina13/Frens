package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorationStepPolicyTest {

    @Test
    void movedNeedsMoreThanTwoBlocksHorizontally() {
        assertFalse(ExplorationStepPolicy.moved(0, 0));
        assertFalse(ExplorationStepPolicy.moved(1, 0));
        assertFalse(ExplorationStepPolicy.moved(2, 0));
        assertTrue(ExplorationStepPolicy.moved(2, 1));
        assertTrue(ExplorationStepPolicy.moved(0, -3));
    }

    @Test
    void candidatesCloserThanFourBlocksAreSkipped() {
        assertFalse(ExplorationStepPolicy.farEnough(1, 0));
        assertFalse(ExplorationStepPolicy.farEnough(3, 0));
        assertFalse(ExplorationStepPolicy.farEnough(2, 2));
        assertTrue(ExplorationStepPolicy.farEnough(4, 0));
        assertTrue(ExplorationStepPolicy.farEnough(3, 3));
    }

    @Test
    void farEnoughCandidateArrivedWithinThreeStillCountsAsMoved() {
        // Arriving 3 blocks short of a 4-block-away stand in the same direction leaves a 1-block move,
        // which is why navigation must check the move itself and not only arrival.
        assertTrue(ExplorationStepPolicy.farEnough(4, 0));
        assertFalse(ExplorationStepPolicy.moved(1, 0));
    }

    @Test
    void ordersFacingThenFarthestThenScore() {
        // Facing +X.
        List<ExplorationStepPolicy.Candidate> list = new ArrayList<>(List.of(
                new ExplorationStepPolicy.Candidate(4, 0, 0.9),
                new ExplorationStepPolicy.Candidate(-6, 0, 0.9),
                new ExplorationStepPolicy.Candidate(6, 0, 0.2),
                new ExplorationStepPolicy.Candidate(0, 7, 0.9),
                new ExplorationStepPolicy.Candidate(6, 0, 0.7)));
        list.sort(ExplorationStepPolicy.order(1, 0));
        assertEquals(new ExplorationStepPolicy.Candidate(6, 0, 0.7), list.get(0));
        assertEquals(new ExplorationStepPolicy.Candidate(6, 0, 0.2), list.get(1));
        assertEquals(new ExplorationStepPolicy.Candidate(4, 0, 0.9), list.get(2));
        assertEquals(new ExplorationStepPolicy.Candidate(0, 7, 0.9), list.get(3));
        assertEquals(new ExplorationStepPolicy.Candidate(-6, 0, 0.9), list.get(4));
    }

    @Test
    void collectDirtUsesThePolicy() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java"));
        assertTrue(src.contains("ExplorationStepPolicy.farEnough("), "candidates filtered by distance");
        assertTrue(src.contains("ExplorationStepPolicy.order("), "candidates ordered by the policy");
        assertTrue(src.contains("ExplorationStepPolicy.MAX_TRIES_PER_STEP"), "tries capped");
        assertTrue(src.contains("boolean relocated = net.wcfcarolina13.GameAI.skills.ExplorationStepPolicy.moved("), "navigation success requires a move");
        assertFalse(src.contains("failuresInRow++; // Increment instead of reset"), "no double failure count");
        int stand = src.indexOf("private boolean isNavigableStand(");
        assertTrue(stand >= 0);
        String standBody = src.substring(stand, src.indexOf("private boolean isStandable(", stand));
        assertTrue(standBody.contains("getFluidState(head).isEmpty()")
                        && standBody.contains("getFluidState(head.up()).isEmpty()"),
                "exploration never picks a stand inside water or lava");
    }
}
