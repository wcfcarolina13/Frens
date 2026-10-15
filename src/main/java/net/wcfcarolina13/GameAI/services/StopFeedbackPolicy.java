package net.wcfcarolina13.GameAI.services;

/**
 * Chat feedback rules for a manual stop ({@code /bot stop}, the stop key, the leash flow).
 *
 * <p>One stop used to produce four chat lines. The only line that survives is the per-command
 * summary ("Jake has stopped."). The task abort is silent and carries {@link #MANUAL_STOP_REASON},
 * and the {@code /bot skill} worker does not echo a result whose message is that reason. The same
 * echo is skipped for {@link CreeperEvasionPolicy#ABORT_REASON}, which the bot already said aloud.
 *
 * <p>The reason text matters beyond chat: {@code SoulEventObserver.sanitizeReasonCategory} buckets
 * abort reasons by substring, checking "respawn", "stopping", "preempt", "threat" and "danger"
 * before "stop". The reason must contain "stop" and none of the earlier words so it stays
 * {@code manual_stop}.
 */
public final class StopFeedbackPolicy {

    public static final String MANUAL_STOP_REASON = "Stopped by the player.";

    private StopFeedbackPolicy() {
    }

    /** True when the reason (legacy {@code §} codes ignored) is the manual-stop reason. */
    public static boolean isManualStop(String reason) {
        if (reason == null) {
            return false;
        }
        return MANUAL_STOP_REASON.equals(reason.replaceAll("§.", "").strip());
    }

    /** Whether the {@code /bot skill} worker should echo a finished skill's message to the issuer. */
    public static boolean shouldEchoSkillResult(String resultMessage) {
        return !isManualStop(resultMessage) && !isCreeperBackoff(resultMessage);
    }

    /**
     * True when the reason is the creeper-backoff abort line. The bot already said it in chat
     * when the task aborted, so the skill result must not repeat it.
     */
    public static boolean isCreeperBackoff(String reason) {
        if (reason == null) {
            return false;
        }
        return CreeperEvasionPolicy.ABORT_REASON.equals(reason.replaceAll("§.", "").strip());
    }
}
