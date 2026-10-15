package net.wcfcarolina13.GameAI.llm;

/**
 * The one-time notice a player gets when they address a single bot by name while AI chat is
 * off and nothing else answered. It is a plain system notice to the sender: no bot prefix, no
 * voice, and it belongs to neither the scripted lane nor the soul lane, so neither lane's
 * toggles gate it.
 */
public final class AiChatOffHintPolicy {

    public static final String NOTICE =
            "AI chat is off. To let companions talk back, open the Guide (]) → AI setup.";

    private AiChatOffHintPolicy() {
    }

    /**
     * @param singleExplicitAddress exactly one bot named, not a broadcast keyword
     * @param promptNonEmpty        the line had text after the bot's name
     * @param soulMasterEnabled     the soul chat master switch (absent runtime counts as off)
     * @param senderAuthorized      the sender may drive this bot (owner, operator or host)
     * @param alreadyHinted         this player already saw the notice this session
     */
    public static boolean shouldHint(boolean singleExplicitAddress, boolean promptNonEmpty,
                                     boolean soulMasterEnabled, boolean senderAuthorized,
                                     boolean alreadyHinted) {
        return singleExplicitAddress
                && promptNonEmpty
                && !soulMasterEnabled
                && senderAuthorized
                && !alreadyHinted;
    }
}
