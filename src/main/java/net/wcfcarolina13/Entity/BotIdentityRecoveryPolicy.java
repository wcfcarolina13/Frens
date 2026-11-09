package net.wcfcarolina13.Entity;

import java.util.List;

/**
 * The UUID a bot spawns with when the settings file has none for it (it was reset). Each bot's UUID
 * lived only there, so a reset gave every bot a fresh random UUID: a new player to the world, with
 * the inventory coming back only through the by-name snapshot fallback. The bot's inventory snapshot
 * files are named {@code <alias>_<uuid>.nbt} and keep that UUID, so the newest one is reused; a UUID
 * whose user-cache name is the bot's exact spelling wins (a lower-case bot the old bug created is
 * cached too). No Minecraft imports.
 */
public final class BotIdentityRecoveryPolicy {

    /** A snapshot's UUID and the name the user cache holds for it (null when not cached). */
    public record Candidate(String uuid, String cachedName) {}

    private BotIdentityRecoveryPolicy() {
    }

    /** {@code newestFirst}: the snapshots' UUIDs, newest file first. Null when there is none to reuse. */
    public static String pick(String username, List<Candidate> newestFirst) {
        Candidate c = choose(username, newestFirst, null);
        return c == null ? null : c.uuid();
    }

    /**
     * The identity to reuse: a snapshot candidate as {@link #pick} chooses it, else the user-cache
     * entry for the name ({@code cacheByName}, used when the bot has no snapshot yet), accepted only
     * when its cached name is the exact spelling, since the cache looks names up ignoring case.
     */
    public static Candidate choose(String username, List<Candidate> newestFirst, Candidate cacheByName) {
        if (username == null) {
            return null;
        }
        if (newestFirst != null) {
            for (Candidate c : newestFirst) {
                if (c != null && valid(c.uuid()) && username.equals(c.cachedName())) {
                    return c;
                }
            }
            for (Candidate c : newestFirst) {
                if (c != null && valid(c.uuid()) && (c.cachedName() == null || c.cachedName().equalsIgnoreCase(username))) {
                    return c;
                }
            }
        }
        if (cacheByName != null && valid(cacheByName.uuid()) && username.equals(cacheByName.cachedName())) {
            return cacheByName;
        }
        return null;
    }

    /** The profile name to spawn with: the matched UUID's cached spelling, else the requested name. */
    public static String profileName(String username, Candidate matched) {
        return matched != null && matched.cachedName() != null && !matched.cachedName().isBlank()
                ? matched.cachedName() : username;
    }

    private static boolean valid(String uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            java.util.UUID.fromString(uuid.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
