package net.wcfcarolina13.GameAI.services;

import java.util.List;

/**
 * Chooses which greeting pool a bot draws from when a player comes near.
 *
 * <p>A bot spawned for the first time ever this session (no persisted vanilla state) must
 * not say "welcome back" or "good to see you again" to someone it has never met. Its first
 * sighting of each player draws from the neutral lines only. Every other greeting draws from
 * the full reunion pool.
 *
 * <p>The strings are byte-identical to the original pool: {@code DialogueTextMapper} maps
 * them to voice clips by exact text (after its dash normalization).
 */
public final class GreetingPolicy {

    public enum Pool { FIRST_MEETING, REUNION, NONE }

    private GreetingPolicy() {
    }

    /**
     * @param freshBot              the bot had no persisted state when it joined this session
     * @param firstSeenThisSession  this player has not been near this bot yet this session
     * @param longTimeNoSee         this player was last near this bot more than the reunion gap ago
     */
    public static Pool pool(boolean freshBot, boolean firstSeenThisSession, boolean longTimeNoSee) {
        if (!firstSeenThisSession && !longTimeNoSee) {
            return Pool.NONE;
        }
        if (freshBot && firstSeenThisSession) {
            return Pool.FIRST_MEETING;
        }
        return Pool.REUNION;
    }

    public static List<String> lines(Pool pool, String playerName) {
        String name = playerName != null && !playerName.isBlank() ? playerName : "there";
        if (pool == null) {
            return List.of();
        }
        return switch (pool) {
            case FIRST_MEETING -> List.of(
                    "Hey, " + name + ".",
                    "Oh—" + name + ", there you are."
            );
            case REUNION -> List.of(
                    "Hey, " + name + ".",
                    "Oh—" + name + ", there you are.",
                    "Good to see you again, " + name + ".",
                    "Welcome back, " + name + "."
            );
            case NONE -> List.of();
        };
    }
}
