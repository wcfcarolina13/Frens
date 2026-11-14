package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions for taking a fake player out of the vanilla {@code PlayerManager}.
 *
 * <p>Vanilla keeps a player in two places: the {@code players} list and the
 * {@code playerMap} (UUID to entity). {@code PlayerManager.remove} clears both, saves the
 * player data and sends the tab-list removal packet. It is not idempotent (it saves and
 * counts a leave stat each time), so it is called only while this exact entity is still
 * registered in either place.
 *
 * <p>Presence is judged by identity, not by UUID alone: during a travel respawn a newer
 * entity with the same UUID can already own the map slot, and that does not mean the
 * entity being removed is still there.
 */
public final class BotRemovalPolicy {
    private BotRemovalPolicy() {}

    /**
     * @param mapHoldsThisBot  {@code playerMap.get(uuid) == bot} (same instance)
     * @param listHoldsThisBot {@code players.contains(bot)}
     */
    public static boolean isStillRegistered(boolean mapHoldsThisBot, boolean listHoldsThisBot) {
        return mapHoldsThisBot || listHoldsThisBot;
    }

    /** Call vanilla remove only while the entity is still registered somewhere. */
    public static boolean shouldCallVanillaRemove(boolean mapHoldsThisBot, boolean listHoldsThisBot) {
        return isStillRegistered(mapHoldsThisBot, listHoldsThisBot);
    }

    /**
     * Whether a vanilla {@code PlayerManager.remove} of this player should first write the Frens
     * snapshot (fake-player items live only there). Skipped for real players, for Frens' own
     * removals (their callers saved already, or are dropping a stale duplicate whose save would
     * overwrite the good snapshot), and for a dead bot (the death path owns its inventory).
     */
    public static boolean shouldSaveOnVanillaRemove(boolean isFakePlayer, boolean frensRemovalInProgress,
                                                    boolean isDead) {
        return isFakePlayer && !frensRemovalInProgress && !isDead;
    }
}
