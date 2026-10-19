package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions about the "resume the task a threat paused" record in {@link SkillResumeService}.
 * No Minecraft types, so it is unit-tested directly.
 */
public final class ResumeFreshnessPolicy {

    /** A threat-paused task is resumed only if the pause happened within this window. */
    public static final long AUTO_RESUME_WINDOW_MS = 10L * 60L * 1000L;

    private ResumeFreshnessPolicy() {}

    /**
     * Whether a task paused at {@code pausedAtMs} may still be auto-resumed at {@code nowMs}.
     * A missing pause time ({@code <= 0}) is never fresh. A clock that reads earlier than the
     * pause (wall-clock adjustment) counts as fresh: the pause cannot be older than the window.
     */
    public static boolean shouldAutoResume(long pausedAtMs, long nowMs, long windowMs) {
        if (pausedAtMs <= 0L || windowMs <= 0L) {
            return false;
        }
        long age = nowMs - pausedAtMs;
        return age <= windowMs;
    }

    /**
     * Whether a finished {@code /bot skill} run should drop the bot's resume record.
     *
     * @param sameRun          the record still belongs to the run that just finished (a newer
     *                         {@code /bot skill}, or a skill re-recording itself at sunset, owns it otherwise)
     * @param autoResumePending a threat paused this run and asked for an auto-resume
     * @param awaitingDecision the player is being asked whether to continue (death, protected zone)
     * @param heldForSunrise   a sunset stop saved this task to resume at sunrise
     */
    public static boolean shouldClearOnCompletion(boolean sameRun, boolean autoResumePending,
                                                  boolean awaitingDecision, boolean heldForSunrise) {
        return sameRun && !autoResumePending && !awaitingDecision && !heldForSunrise;
    }
}
