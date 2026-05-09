package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot "stand down" state: bot stops following and is forbidden from drop-sweeping
 * for a fixed duration, then auto-resumes follow against its prior target.
 *
 * <p>Drop-sweep suppression is delegated to {@link DropSweepService#suppressFor}; this
 * service owns the follow-target snapshot and the timed resume.
 */
public final class BotStandDownService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-stand-down");

    private static final Map<UUID, StandDown> ACTIVE = new ConcurrentHashMap<>();

    private BotStandDownService() {}

    private record StandDown(UUID followTargetUuid, long expiresAtMs) {}

    /**
     * Begin a stand-down. Snapshots the bot's current follow target (if any), stops
     * following, and registers drop-sweep suppression for the same window. After the
     * duration expires, {@link #onServerTick} re-issues follow against the snapshot.
     */
    public static void beginStandDown(ServerPlayerEntity bot, long durationMs) {
        if (bot == null || durationMs <= 0L) {
            return;
        }
        UUID id = bot.getUuid();
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        long expires = System.currentTimeMillis() + durationMs;
        ACTIVE.put(id, new StandDown(followTarget, expires));
        DropSweepService.suppressFor(id, durationMs);
        DropSweepService.requestCancel(bot, "stand-down");
        BotEventHandler.stopFollowing(bot, false);
        LOGGER.info("Stand-down begun: bot={} duration={}ms followTarget={}",
                bot.getName().getString(), durationMs, followTarget);
    }

    /** Cancel an active stand-down without resuming follow (e.g. user issued a new command). */
    public static void cancel(UUID botId) {
        if (botId == null) {
            return;
        }
        if (ACTIVE.remove(botId) != null) {
            LOGGER.debug("Stand-down canceled for {}", botId);
        }
    }

    public static boolean isStandingDown(UUID botId) {
        if (botId == null) {
            return false;
        }
        StandDown active = ACTIVE.get(botId);
        if (active == null) {
            return false;
        }
        if (System.currentTimeMillis() >= active.expiresAtMs) {
            ACTIVE.remove(botId);
            return false;
        }
        return true;
    }

    public static long getRemainingMs(UUID botId) {
        if (botId == null) {
            return 0L;
        }
        StandDown active = ACTIVE.get(botId);
        if (active == null) {
            return 0L;
        }
        return Math.max(0L, active.expiresAtMs - System.currentTimeMillis());
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, StandDown>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, StandDown> entry = it.next();
            StandDown s = entry.getValue();
            if (now < s.expiresAtMs) {
                continue;
            }
            it.remove();
            UUID botId = entry.getKey();
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null) {
                continue;
            }
            if (s.followTargetUuid == null) {
                LOGGER.info("Stand-down expired for {}; no prior follow target, leaving idle",
                        bot.getName().getString());
                continue;
            }
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(s.followTargetUuid);
            if (target == null) {
                LOGGER.info("Stand-down expired for {}; prior follow target offline, leaving idle",
                        bot.getName().getString());
                continue;
            }
            BotEventHandler.setFollowMode(bot, target, false);
            ChatUtils.sendChatMessages(
                    bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                    "Back in formation.");
            LOGGER.info("Stand-down expired for {}; resumed follow on {}",
                    bot.getName().getString(), target.getName().getString());
        }
    }
}
