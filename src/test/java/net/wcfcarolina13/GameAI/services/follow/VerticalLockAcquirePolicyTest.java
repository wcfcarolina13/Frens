package net.wcfcarolina13.GameAI.services.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalLockAcquirePolicyTest {

    @Test
    void ladderBehindBotWithCommanderBelowIsRejected() {
        // 21:26:08 — Jake y66, commander y65, ladder stand 270,66,1289 farther from the commander.
        assertFalse(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 65, 80, 202, 68));
    }

    @Test
    void ladderTowardHigherCommanderIsAccepted() {
        assertTrue(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 72, 80, 40, 72));
    }

    @Test
    void commanderLevelWithBotIsRejected() {
        assertFalse(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 66, 80, 10, 70));
        assertFalse(VerticalLockAcquirePolicy.isCommanderAbove(66, 66));
        assertTrue(VerticalLockAcquirePolicy.isCommanderAbove(66, 67.5));
    }

    @Test
    void standFartherFromCommanderIsRejectedEvenWhenCommanderIsAbove() {
        assertFalse(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 68, 80, 81, 70));
        assertTrue(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 68, 80, 80, 70));
    }

    @Test
    void columnThatCannotReachCommanderHeightIsRejected() {
        assertFalse(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 69, 80, 40, 67));
        assertTrue(VerticalLockAcquirePolicy.acceptSummitCandidate(66, 69, 80, 40, 68));
    }
    @Test
    void usableSummitRequiresAColumnThatRisesAboveTheBot() {
        assertFalse(VerticalLockAcquirePolicy.isUsableSummitCandidate(66, 67, 80, 40, 67));
        assertTrue(VerticalLockAcquirePolicy.isUsableSummitCandidate(66, 67, 80, 40, 68));
        assertFalse(VerticalLockAcquirePolicy.isUsableSummitCandidate(66, 65, 80, 202, 68));
    }

    @Test
    void betterScoredLadderBehindTheBotDoesNotMaskAUsableClimb() {
        // Filter-then-pick, as the climb scan now does: the ladder behind (better score) is rejected,
        // the slightly worse-scored climb toward a commander two blocks up wins.
        record Candidate(double standToTargetHorizSq, int topY, double score) { }
        java.util.List<Candidate> candidates = java.util.List.of(
                new Candidate(202, 70, 1.0),   // behind the bot
                new Candidate(40, 69, 5.0));   // toward the commander
        Candidate best = candidates.stream()
                .filter(c -> VerticalLockAcquirePolicy.isUsableSummitCandidate(66, 68, 80, c.standToTargetHorizSq(), c.topY()))
                .min(java.util.Comparator.comparingDouble(Candidate::score))
                .orElse(null);
        assertTrue(best != null && best.standToTargetHorizSq() == 40);
    }
}
