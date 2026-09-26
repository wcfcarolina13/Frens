package net.wcfcarolina13.GameAI.llm;

/** Whether a legacy conversational generation attempt may announce its status. */
public final class LegacyChatStatusPolicy {
    private LegacyChatStatusPolicy() {
    }

    public static boolean shouldAnnounceProcessing(boolean worldEnabled, boolean botEnabled,
                                                   boolean libraryAvailable, boolean clientAvailable,
                                                   boolean parsedOk) {
        return worldEnabled && botEnabled && libraryAvailable && clientAvailable && parsedOk;
    }
}
