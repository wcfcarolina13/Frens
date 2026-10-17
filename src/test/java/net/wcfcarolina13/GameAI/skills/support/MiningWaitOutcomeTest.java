package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for telling a cancelled mine from a failed one. No net.minecraft types. */
class MiningWaitOutcomeTest {

    @Test
    void interruptAndCancelAreCancellations() {
        assertTrue(MiningWaitOutcome.isCancellation(new InterruptedException()));
        assertTrue(MiningWaitOutcome.isCancellation(new CancellationException()));
        assertTrue(MiningWaitOutcome.isCancellation(new ExecutionException(new InterruptedException())));
        assertTrue(MiningWaitOutcome.isCancellation(new ExecutionException(new CancellationException())));
    }

    @Test
    void realFailuresAreNotCancellations() {
        assertFalse(MiningWaitOutcome.isCancellation(null));
        assertFalse(MiningWaitOutcome.isCancellation(new TimeoutException()));
        assertFalse(MiningWaitOutcome.isCancellation(new ExecutionException(new IllegalStateException("boom"))));
        assertFalse(MiningWaitOutcome.isCancellation(new ExecutionException(null)));
        assertFalse(MiningWaitOutcome.isCancellation(new RuntimeException()));
    }

    @Test
    void cancelledDetailIsTheExistingAbortDetail() {
        assertEquals("abort-requested", MiningWaitOutcome.CANCELLED_DETAIL);
        assertTrue(MiningWaitOutcome.isCancelledDetail("abort-requested"));
        assertFalse(MiningWaitOutcome.isCancelledDetail("exception"));
        assertFalse(MiningWaitOutcome.isCancelledDetail("approach-failed"));
        assertFalse(MiningWaitOutcome.isCancelledDetail(null));
    }
}
