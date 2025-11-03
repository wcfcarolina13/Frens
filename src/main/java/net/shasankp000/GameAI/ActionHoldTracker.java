package net.shasankp000.GameAI;

import java.util.EnumSet;
import java.util.Set;

/**
 * Tracks repeated invocations of movement/mining actions so rewards can
 * differentiate between sustained key holds and single taps.
 */
public final class ActionHoldTracker {

    private static final long CONTINUATION_WINDOW_MS = 750L;
    private static final long SUSTAINED_DURATION_MS = 250L;

    private static final Set<StateActions.Action> HOLD_ELIGIBLE =
            EnumSet.of(StateActions.Action.MOVE_FORWARD,
                    StateActions.Action.MOVE_BACKWARD,
                    StateActions.Action.SPRINT,
                    StateActions.Action.BREAK_BLOCK_FORWARD);

    private static StateActions.Action activeAction = null;
    private static long activeStartMs = 0L;
    private static long lastTriggerMs = 0L;
    private static int consecutiveCount = 0;
    private static ActionHoldSnapshot lastSnapshot = ActionHoldSnapshot.EMPTY;

    private ActionHoldTracker() {
    }

    public static synchronized ActionHoldSnapshot recordAction(StateActions.Action action) {
        long now = System.currentTimeMillis();

        if (!HOLD_ELIGIBLE.contains(action)) {
            resetIfExpired(now);
            lastSnapshot = new ActionHoldSnapshot(action, 0L, 0, false);
            return lastSnapshot;
        }

        boolean continuing = action.equals(activeAction) && (now - lastTriggerMs) <= CONTINUATION_WINDOW_MS;
        if (!continuing) {
            activeAction = action;
            activeStartMs = now;
            consecutiveCount = 0;
        }

        consecutiveCount++;
        lastTriggerMs = now;

        long duration = Math.max(0L, now - activeStartMs);
        boolean sustained = consecutiveCount >= 2 && duration >= SUSTAINED_DURATION_MS;

        lastSnapshot = new ActionHoldSnapshot(action, duration, consecutiveCount, sustained);
        return lastSnapshot;
    }

    public static synchronized ActionHoldSnapshot snapshot() {
        resetIfExpired(System.currentTimeMillis());
        return lastSnapshot;
    }

    private static void resetIfExpired(long now) {
        if (activeAction != null && (now - lastTriggerMs) > CONTINUATION_WINDOW_MS) {
            reset();
        }
    }

    private static void reset() {
        activeAction = null;
        activeStartMs = 0L;
        lastTriggerMs = 0L;
        consecutiveCount = 0;
        lastSnapshot = ActionHoldSnapshot.EMPTY;
    }

    public record ActionHoldSnapshot(StateActions.Action action,
                                     long durationMs,
                                     int consecutiveCount,
                                     boolean sustained) {
        public static final ActionHoldSnapshot EMPTY =
                new ActionHoldSnapshot(StateActions.Action.STAY, 0L, 0, false);

        public boolean matches(StateActions.Action target) {
            return action != null && action.equals(target);
        }
    }
}
