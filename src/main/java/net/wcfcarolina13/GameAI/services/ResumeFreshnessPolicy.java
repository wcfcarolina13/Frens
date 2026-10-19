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

    /** What the hostiles-cleared path does with a threat's pending auto-resume. */
    public enum ThreatClearedAction {
        /** Within the window: resume the paused task. */
        RESUME,
        /** Past the window, but a sunset stop holds the task for sunrise: leave everything to that path. */
        LEAVE_FOR_SUNRISE,
        /** Past the window while the player is being asked (death, protected zone): drop the request, keep the record for that answer. */
        DROP_REQUEST_KEEP_RECORD,
        /**
         * Past the window: drop the request and the task record, so a later threat that pauses some
         * other task cannot re-arm this one and revive a long-finished job (finding 23).
         */
        DROP_REQUEST_AND_RECORD
    }

    public static ThreatClearedAction onThreatCleared(long pausedAtMs, long nowMs, long windowMs,
                                                      boolean heldForSunrise, boolean awaitingDecision) {
        if (shouldAutoResume(pausedAtMs, nowMs, windowMs)) {
            return ThreatClearedAction.RESUME;
        }
        if (heldForSunrise) {
            return ThreatClearedAction.LEAVE_FOR_SUNRISE;
        }
        return awaitingDecision ? ThreatClearedAction.DROP_REQUEST_KEEP_RECORD
                : ThreatClearedAction.DROP_REQUEST_AND_RECORD;
    }

    /**
     * Whether a threat pause of the bot's active task may arm the auto-resume of its recorded
     * {@code /bot skill}. Only when the task being paused is that skill (ticket {@code "skill:" + name})
     * and not an idle hobby running under the same skill name: a pause of anything else must not
     * re-arm an old record.
     *
     * @param activeTaskName the active ticket's name, or null when the bot has no task
     * @param activeIsAmbient the active ticket is an idle hobby
     * @param recordSkillName the recorded skill, or null when there is no record
     */
    public static boolean pausedTaskOwnsRecord(String activeTaskName, boolean activeIsAmbient, String recordSkillName) {
        if (activeTaskName == null || recordSkillName == null || recordSkillName.isEmpty() || activeIsAmbient) {
            return false;
        }
        return activeTaskName.equals("skill:" + recordSkillName);
    }
}
