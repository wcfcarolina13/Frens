package net.wcfcarolina13.GameAI.services;

/**
 * Pure rules for auto-spawning saved companions when a world loads.
 *
 * <p>Fake players connect through {@code PlayerManager.onPlayerConnect} directly and never pass
 * {@code checkCanJoin}, but they still fill the player list. In 1.21.11 the integrated server's
 * max player count is 8 and the singleplayer host gets no bypass, so bots spawned at
 * SERVER_STARTED could fill the list before the host's own login was checked
 * ({@code multiplayer.disconnect.server_full}).
 */
public final class AutoSpawnPolicy {

    /** Player slots auto-spawn never fills, so a real player can always still join. */
    public static final int RESERVED_SLOTS = 1;

    private AutoSpawnPolicy() {
    }

    /**
     * Integrated (singleplayer / LAN) servers start before the host has logged in, so auto-spawn
     * waits for the first real player to join. Dedicated servers spawn at startup as before.
     */
    public static boolean deferUntilHostJoins(boolean dedicatedServer) {
        return !dedicatedServer;
    }

    /** True when one more bot fits while keeping {@link #RESERVED_SLOTS} slots free. */
    public static boolean hasRoomForAutoSpawn(int currentPlayers, int maxPlayers) {
        if (maxPlayers <= RESERVED_SLOTS) {
            return false;
        }
        int current = Math.max(0, currentPlayers);
        return current + 1 <= maxPlayers - RESERVED_SLOTS;
    }

    /**
     * Player count to judge room against while a batch of auto-spawns is in flight. A spawn can
     * complete asynchronously (dedicated online-mode profile lookup), so the observed count may lag
     * the spawns already dispatched; take the larger of the observed count and the batch baseline
     * plus everything dispatched so far.
     */
    public static int effectivePlayerCount(int observed, int baseline, int dispatched) {
        return Math.max(observed, baseline + Math.max(0, dispatched));
    }
}
