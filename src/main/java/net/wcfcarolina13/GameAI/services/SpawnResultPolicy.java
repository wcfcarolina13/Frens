package net.wcfcarolina13.GameAI.services;

/**
 * Classifies what {@code /bot spawn} found right after asking for a fake player.
 *
 * <p>Offline and integrated servers connect the bot synchronously, so a missing bot means the
 * spawn failed. An online-mode dedicated server first looks the name up on Mojang's servers
 * off-thread, so the bot is legitimately absent until that lookup finishes. Reporting that as a
 * failure was wrong: the bot appears a moment later. Pure so it can be tested without Minecraft.
 */
public final class SpawnResultPolicy {

    public enum Result {
        /** The bot is in the world now. Initialise it immediately. */
        READY,
        /** The spawn is still in flight. Say so and initialise the bot when it arrives. */
        PENDING,
        /** The spawn finished but no bot appeared. */
        FAILED
    }

    private SpawnResultPolicy() {
    }

    public static Result classify(boolean botFound, boolean spawnStillInFlight) {
        if (botFound) {
            return Result.READY;
        }
        return spawnStillInFlight ? Result.PENDING : Result.FAILED;
    }

    /** Neutral chat line for a spawn that has not finished yet. */
    public static String pendingMessage(String botName) {
        return "Spawning " + botName + "…";
    }

    /** One-line note when the bot spawned but its language model client could not start. */
    public static String aiOffMessage(String botName, String provider) {
        return "AI is off for " + botName + ". The " + provider
                + " client could not start, so check its API key. " + botName + " still follows and works as usual.";
    }
}
