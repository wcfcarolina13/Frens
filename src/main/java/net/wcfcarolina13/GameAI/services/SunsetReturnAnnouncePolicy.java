package net.wcfcarolina13.GameAI.services;

/**
 * Pure rule for the "Returning to base." line during one sunset return session.
 *
 * <p>A sunset session can drop the bot out of return mode and back in several times
 * (starving or food detours, the local-reassess cooldown, flee and surface recovery). Each
 * resume re-enters {@code BotEventHandler.setReturnToBaseSegmented}, which announced every
 * time because the bot was no longer "already returning". The line is said once per session.
 */
public final class SunsetReturnAnnouncePolicy {

    private SunsetReturnAnnouncePolicy() {
    }

    /** Announce only if this session has not announced yet. */
    public static boolean shouldAnnounce(boolean announcedThisSession) {
        return !announcedThisSession;
    }

    /**
     * The session's flag after one return start: it becomes true only when an announcing
     * start actually began a return (a refused start, such as a questing-mode compass
     * refusal, said nothing about returning).
     */
    public static boolean announcedAfter(boolean announcedThisSession, boolean announceRequested, boolean returnStarted) {
        return announcedThisSession || (announceRequested && returnStarted);
    }
}
