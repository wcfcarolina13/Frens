package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: extracted RL persistence throttling out of {@code BotEventHandler}.
 *
 * <p>This avoids frequent disk writes while still ensuring RL state is periodically saved.</p>
 */
public final class BotRLPersistenceThrottleService {

    private static final Map<UUID, Long> LAST_RL_PERSIST_MS = new ConcurrentHashMap<>();
    private static final long RL_PERSIST_MIN_INTERVAL_MS = 15_000L;

    private BotRLPersistenceThrottleService() {}

    public static void resetAll() {
        LAST_RL_PERSIST_MS.clear();
    }

    public static boolean shouldPersistNow(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_RL_PERSIST_MS.getOrDefault(id, -1L);
        if (last >= 0 && (now - last) < RL_PERSIST_MIN_INTERVAL_MS) {
            return false;
        }
        LAST_RL_PERSIST_MS.put(id, now);
        return true;
    }
}

