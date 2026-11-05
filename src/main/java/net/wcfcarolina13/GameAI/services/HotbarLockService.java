package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;

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

    private static final HotbarLockTable LOCKS = new HotbarLockTable();
    private static final long LOG_COOLDOWN_TICKS = 40L;

    private HotbarLockService() {}

    public static void lock(ServerPlayerEntity bot, MinecraftServer server, int slot, long ttlTicks, String reason) {
        if (bot == null || server == null) {
            return;
        }
        LOCKS.lock(bot.getUuid(), slot, server.getTicks(), ttlTicks, reason);
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
        LOCKS.clear(botId);
    }

    public static boolean isLocked(ServerPlayerEntity bot) {
        return activeLock(bot) != null;
    }

    public static Integer getLockedSlot(ServerPlayerEntity bot) {
        HotbarLockTable.Lock lock = activeLock(bot);
        return lock != null ? lock.slot() : null;
    }

    private static HotbarLockTable.Lock activeLock(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return null;
        }
        return LOCKS.active(bot.getUuid(), server.getTicks());
    }

    public static void maybeLogBlocked(ServerPlayerEntity bot, String subsystem) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        HotbarLockTable.Lock lock = LOCKS.active(bot.getUuid(), now);
        if (lock == null || !LOCKS.logDue(bot.getUuid(), now, LOG_COOLDOWN_TICKS)) {
            return;
        }
        LOGGER.info("HotbarLock: blocked={} bot={} slot={} reason={}",
                subsystem,
                bot.getName().getString(),
                lock.slot(),
                lock.reason());
    }
}
