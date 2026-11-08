package net.wcfcarolina13.network;

import java.util.Optional;

/**
 * Pure checks for {@code CompanionQuestTopicPayload}, the client's "ask the bot about a quest
 * topic" packet. Free of Minecraft imports so it can be unit-tested.
 *
 * <p>Multiplayer finding (2026-11-05): the receiver resolved the alias to any online player (a
 * real player included), played a dialogue sound at that entity for anyone who asked, and echoed
 * the unbounded alias back. The alias now has to be a valid bot alias
 * ({@link BotActionPolicy#isValidAlias}: at most {@value BotActionPolicy#MAX_ALIAS_LEN} chars of
 * a safe charset), the topic key is bounded by {@link #MAX_TOPIC_KEY_LEN}, and the receiver then
 * requires a registered bot the sender may act on ({@code BotAccessGate.permits}).
 */
public final class CompanionQuestTopicPolicy {

    /** The longest topic key the client sends is 28 chars ({@code topic_nether_fortress_memory}). */
    public static final int MAX_TOPIC_KEY_LEN = 64;

    private CompanionQuestTopicPolicy() {}

    /**
     * Why the request's strings are refused before any bot lookup, or empty when they are fine.
     * Codes: {@code BAD_ALIAS} (null, blank, too long or an unsafe char), {@code BAD_TOPIC}
     * (null, blank or longer than {@value #MAX_TOPIC_KEY_LEN}).
     */
    public static Optional<String> refusal(String botAlias, String topicKey) {
        if (!BotActionPolicy.isValidAlias(botAlias)) return Optional.of("BAD_ALIAS");
        if (topicKey == null || topicKey.isBlank() || topicKey.length() > MAX_TOPIC_KEY_LEN) {
            return Optional.of("BAD_TOPIC");
        }
        return Optional.empty();
    }
}
