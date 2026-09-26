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
}
