package net.wcfcarolina13.GameAI.services;

/**
 * What underground survival does with the abort latch ({@code TaskService.ABORT_LATCH}).
 *
 * <p>The latch is set by a manual stop, but also by deaths ("aborted due to respawn"), threat
 * aborts ("Terminating all current tasks due to threat detections") and other force aborts. Only
 * the manual stop means "hold still until the next command". Any other reason left behind with no
 * task running is stale: it would make every staging walk abort instantly, so it is cleared and
 * escape runs.
 */
public final class SurvivalLatchPolicy {

    public enum Decision {
        /** No latch, or a non-stop latch that belongs to a running task: survival's other gates decide. */
        PROCEED,
        /** Manual stop: hold still until the next command clears the latch. */
        HOLD,
        /** Stale non-stop latch with no task: clear it, then run the normal dispatch. */
        CLEAR_AND_PROCEED
    }

    private SurvivalLatchPolicy() {
    }

    public static Decision decide(boolean latched, String reason, boolean hasActiveTask) {
        if (!latched) {
            return Decision.PROCEED;
        }
        if (StopFeedbackPolicy.isManualStop(reason)) {
            return Decision.HOLD;
        }
        return hasActiveTask ? Decision.PROCEED : Decision.CLEAR_AND_PROCEED;
    }
}
