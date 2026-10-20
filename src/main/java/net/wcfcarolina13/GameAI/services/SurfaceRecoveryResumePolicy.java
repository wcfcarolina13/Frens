package net.wcfcarolina13.GameAI.services;

public final class SurfaceRecoveryResumePolicy {
    public static final long MAX_WAIT_MS = 30_000L;

    private SurfaceRecoveryResumePolicy() {}

    public enum Action { CANCEL, WAIT, RESUME, RESUME_AFTER_TIMEOUT }

    public static Action action(boolean currentRequest, boolean botAvailable,
                                boolean recoveryActive, long elapsedMs) {
        if (!currentRequest || !botAvailable) {
            return Action.CANCEL;
        }
        if (!recoveryActive) {
            return Action.RESUME;
        }
        return elapsedMs < MAX_WAIT_MS ? Action.WAIT : Action.RESUME_AFTER_TIMEOUT;
    }
}
