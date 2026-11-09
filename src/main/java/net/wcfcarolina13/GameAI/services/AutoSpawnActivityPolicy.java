package net.wcfcarolina13.GameAI.services;

/**
 * Which bots auto-spawn when the only record of them is the world-state store, as after the settings
 * file was reset (it held each bot's "auto-spawn on load" flag, and /bot despawn's "keep it away").
 * Before, every bot that ever stood in the world came back (sandbox 2026-11-08: 7 of 8 slots filled
 * from 300 old entries). Now a put-away bot stays away, and the others come back only if they were in
 * the world during its last session: saved within {@link #SESSION_WINDOW_MS} of the newest entry.
 * Entries written before these fields existed carry no time; they count only when no entry in the
 * world has one (an old save keeps the old behaviour). No Minecraft imports.
 */
public final class AutoSpawnActivityPolicy {

    /** Online bots refresh their entry every few minutes; the last session's bots fall inside this. */
    public static final long SESSION_WINDOW_MS = 30L * 60L * 1000L;

    private AutoSpawnActivityPolicy() {
    }

    public static boolean bringBack(Boolean shelved, long savedAtMs, long newestSavedAtMsInWorld) {
        if (Boolean.TRUE.equals(shelved)) {
            return false;
        }
        if (newestSavedAtMsInWorld <= 0L) {
            return true;
        }
        if (savedAtMs <= 0L) {
            return false;
        }
        return savedAtMs >= newestSavedAtMsInWorld - SESSION_WINDOW_MS;
    }
}
