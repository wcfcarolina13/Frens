package net.wcfcarolina13.GameAI.skills.support;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Classifies what went wrong while a skill waited on a mining future. A stop, pause or task
 * replacement interrupts the skill thread (TaskService.abortTicket) or cancels the future: that
 * is a cancelled mine, logged at INFO and never counted as a path failure. Anything else is a
 * real mining failure. Pure.
 */
public final class MiningWaitOutcome {

    /** Detail a cancelled mine reports; the woodcut path-failure counters skip it. */
    public static final String CANCELLED_DETAIL = "abort-requested";

    private MiningWaitOutcome() {
    }

    /** True when the wait ended because the mine was interrupted or cancelled, not because it failed. */
    public static boolean isCancellation(Throwable failure) {
        if (failure instanceof InterruptedException || failure instanceof CancellationException) {
            return true;
        }
        if (failure instanceof ExecutionException) {
            Throwable cause = failure.getCause();
            return cause instanceof InterruptedException || cause instanceof CancellationException;
        }
        return false;
    }

    /** True when a failed mine or tree should not count as a path or reach failure. */
    public static boolean isCancelledDetail(String detail) {
        return CANCELLED_DETAIL.equals(detail);
    }
}
