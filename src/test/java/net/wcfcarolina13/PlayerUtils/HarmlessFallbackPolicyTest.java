package net.wcfcarolina13.PlayerUtils;

import net.wcfcarolina13.PlayerUtils.HarmlessFallbackPolicy.Next;
import net.wcfcarolina13.PlayerUtils.HarmlessFallbackPolicy.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HarmlessFallbackPolicyTest {

    @Test
    void equippedProceedsOnAnyAttempt() {
        assertEquals(Next.PROCEED, HarmlessFallbackPolicy.decide(Outcome.EQUIPPED, 1));
        assertEquals(Next.PROCEED, HarmlessFallbackPolicy.decide(Outcome.EQUIPPED, 2));
    }

    @Test
    void noSafeItemAbortsAtOnceWithTheOldMessage() {
        assertEquals(Next.ABORT_NO_SAFE_ITEM, HarmlessFallbackPolicy.decide(Outcome.NO_SAFE_ITEM, 1));
        assertEquals(Next.ABORT_NO_SAFE_ITEM, HarmlessFallbackPolicy.decide(Outcome.NO_SAFE_ITEM, 2));
        assertEquals("⚠️ Cannot mine: could not equip a safe tool or empty hand.",
                HarmlessFallbackPolicy.abortMessage(Next.ABORT_NO_SAFE_ITEM));
    }

    @Test
    void firstTimeoutRetriesSecondAbortsAsServerBusy() {
        assertEquals(Next.RETRY, HarmlessFallbackPolicy.decide(Outcome.TIMED_OUT, 1));
        assertEquals(Next.ABORT_SERVER_BUSY, HarmlessFallbackPolicy.decide(Outcome.TIMED_OUT, 2));
        assertEquals("⚠️ Cannot mine: the server was too busy to switch tools.",
                HarmlessFallbackPolicy.abortMessage(Next.ABORT_SERVER_BUSY));
    }

    @Test
    void timeoutThenEquippedOrNoSafeItemFollowsTheSecondOutcome() {
        assertEquals(Next.RETRY, HarmlessFallbackPolicy.decide(Outcome.TIMED_OUT, 1));
        assertEquals(Next.PROCEED, HarmlessFallbackPolicy.decide(Outcome.EQUIPPED, 2));
        assertEquals(Next.ABORT_NO_SAFE_ITEM, HarmlessFallbackPolicy.decide(Outcome.NO_SAFE_ITEM, 2));
    }

    @Test
    void nullOutcomeIsTreatedAsAHopThatDidNotRun() {
        assertEquals(Next.RETRY, HarmlessFallbackPolicy.decide(null, 1));
        assertEquals(Next.ABORT_SERVER_BUSY, HarmlessFallbackPolicy.decide(null, 2));
    }

    @Test
    void sequenceAlwaysTerminatesWithinMaxAttempts() {
        for (Outcome o : Outcome.values()) {
            assertNotEquals(Next.RETRY, HarmlessFallbackPolicy.decide(o, HarmlessFallbackPolicy.MAX_ATTEMPTS));
        }
    }

    @Test
    void messagesAreDistinctAndOnlyForAborts() {
        assertNotEquals(HarmlessFallbackPolicy.abortMessage(Next.ABORT_NO_SAFE_ITEM),
                HarmlessFallbackPolicy.abortMessage(Next.ABORT_SERVER_BUSY));
        assertNull(HarmlessFallbackPolicy.abortMessage(Next.PROCEED));
        assertNull(HarmlessFallbackPolicy.abortMessage(Next.RETRY));
    }
}
