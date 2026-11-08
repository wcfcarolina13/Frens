package net.wcfcarolina13.GameAI.services;

/**
 * Decides whether a bot is saved right before it is removed from the world.
 *
 * <p>{@code /bot despawn} used to remove the bot without saving, so anything picked up since
 * the last autosave was lost. Only a live, still-present fake player is saved: the death
 * path removes a dead bot after the death wipe, and saving it there would write the wiped (or a
 * stale) inventory over the death record; a stale instance that is already removed has nothing
 * current to save. Callers that delete the bot's data right after removal ({@code /bot forget})
 * do not ask for a save.
 */
public final class DespawnSavePolicy {
    private DespawnSavePolicy() {
    }

    public static boolean shouldSave(boolean requested, boolean fakePlayer, boolean alive, boolean removed) {
        return requested && fakePlayer && alive && !removed;
    }
}
