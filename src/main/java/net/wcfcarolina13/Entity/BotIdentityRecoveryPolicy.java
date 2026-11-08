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
        if (username == null || newestFirst == null || newestFirst.isEmpty()) {
            return null;
        }
        for (Candidate c : newestFirst) {
            if (c != null && valid(c.uuid()) && username.equals(c.cachedName())) {
                return c.uuid();
            }
        }
        for (Candidate c : newestFirst) {
            if (c != null && valid(c.uuid()) && (c.cachedName() == null || c.cachedName().equalsIgnoreCase(username))) {
                return c.uuid();
            }
        }
        return null;
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
