package net.wcfcarolina13.GameAI.souls;

/**
 * What a bot's death does to its soul profile (finding 1, 1.1.272).
 *
 * <p>A death always cancels the bot's pending generations so no line survives into the
 * respawn. The profile itself is deactivated only when Auto Respawn is off: that bot is
 * shelved until the player brings it back. With Auto Respawn on, the bot comes straight back
 * and keeps its soul. A respawn never reactivates a profile, so a soul the player disabled
 * before the death stays disabled.
 */
public final class SoulDeathPolicy {

    private SoulDeathPolicy() {
    }

    /** True when the death should deactivate the bot's soul profile. */
    public static boolean deactivateOnDeath(boolean autoRespawn) {
        return !autoRespawn;
    }
}
