package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived "selected hotbar slot" lock used to prevent other automation loops
 * (combat loadout, healing, etc.) from immediately overriding the intended slot.
 *
 * This is intentionally small-scope: it only provides state + helpers; call-sites
 * decide whether to respect the lock.
 */
public final class HotbarLockService {
    private static final Logger LOGGER = Frens.LOGGER;

    private static final Map<UUID, Integer> LOCKED_SLOT = new HashMap<>();
    private static final Map<UUID, Long> LOCK_UNTIL_TICK = new HashMap<>();
    private static final Map<UUID, String> LOCK_REASON = new HashMap<>();
    private static final Map<UUID, Long> LAST_LOCK_LOG_TICK = new HashMap<>();
    private static final long LOG_COOLDOWN_TICKS = 40L;

    private HotbarLockService() {}

    public static void lock(ServerPlayerEntity bot, MinecraftServer server, int slot, long ttlTicks, String reason) {
        if (bot == null || server == null) {
            return;
        }
        UUID id = bot.getUuid();
        LOCKED_SLOT.put(id, Math.max(0, Math.min(8, slot)));
        LOCK_UNTIL_TICK.put(id, (long) server.getTicks() + Math.max(1L, ttlTicks));
        LOCK_REASON.put(id, reason != null ? reason : "unknown");
    }

    public static void clear(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        clear(bot.getUuid());
    }

    public static void clear(UUID botId) {
        if (botId == null) {
            return;
        }
        LOCKED_SLOT.remove(botId);
        LOCK_UNTIL_TICK.remove(botId);
        LOCK_REASON.remove(botId);
        LAST_LOCK_LOG_TICK.remove(botId);
    }

    public static boolean isLocked(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return false;
        }
        Long until = LOCK_UNTIL_TICK.get(bot.getUuid());
        if (until == null) {
            return false;
        }
        long now = server.getTicks();
        if (now > until) {
            clear(bot.getUuid());
            return false;
        }
        return true;
    }

    public static Integer getLockedSlot(ServerPlayerEntity bot) {
        if (!isLocked(bot)) {
            return null;
        }
        return LOCKED_SLOT.get(bot.getUuid());
    }

    public static String getReason(ServerPlayerEntity bot) {
        if (!isLocked(bot)) {
            return null;
        }
        return LOCK_REASON.get(bot.getUuid());
    }

    public static void maybeLogBlocked(ServerPlayerEntity bot, String subsystem) {
        if (bot == null || !isLocked(bot)) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        Long last = LAST_LOCK_LOG_TICK.get(bot.getUuid());
        if (last != null && now - last < LOG_COOLDOWN_TICKS) {
            return;
        }
        LAST_LOCK_LOG_TICK.put(bot.getUuid(), now);
        Integer slot = LOCKED_SLOT.get(bot.getUuid());
        String reason = LOCK_REASON.get(bot.getUuid());
        LOGGER.info("HotbarLock: blocked={} bot={} slot={} reason={}",
                subsystem,
                bot.getName().getString(),
                slot != null ? slot : -1,
                reason != null ? reason : "unknown");
    }
}
