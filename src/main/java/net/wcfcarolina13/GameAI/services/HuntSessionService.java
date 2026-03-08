package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory hunt session tracking for multi-day hunts.
 * When a hunt is paused at sunset, the session state is saved here so it can be
 * resumed at sunrise via SkillResumeService.
 */
public final class HuntSessionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("hunt-session");

    private static final Map<UUID, HuntSession> SESSIONS = new ConcurrentHashMap<>();

    private HuntSessionService() {}

    public record HuntSession(
            UUID botId,
            BlockPos huntOrigin,
            BlockPos chestPos,
            List<String> targetIds,
            int killsCompleted,
            int killsTarget,
            String zoneName,
            boolean depopulationEnabled,
            String rawArgs
    ) {}

    public static void saveSession(ServerPlayerEntity bot, BlockPos huntOrigin, BlockPos chestPos,
                                    List<String> targetIds, int killsCompleted, int killsTarget,
                                    String zoneName, boolean depopulationEnabled, String rawArgs) {
        if (bot == null) return;
        HuntSession session = new HuntSession(
                bot.getUuid(), huntOrigin, chestPos, targetIds,
                killsCompleted, killsTarget, zoneName, depopulationEnabled, rawArgs);
        SESSIONS.put(bot.getUuid(), session);
        LOGGER.info("Hunt session saved for {}: kills={}/{} origin={}",
                bot.getName().getString(), killsCompleted, killsTarget, huntOrigin);
    }

    public static HuntSession getSession(UUID botId) {
        return botId != null ? SESSIONS.get(botId) : null;
    }

    public static HuntSession consumeSession(UUID botId) {
        return botId != null ? SESSIONS.remove(botId) : null;
    }

    public static boolean hasSession(UUID botId) {
        return botId != null && SESSIONS.containsKey(botId);
    }

    public static void clearSession(UUID botId) {
        if (botId != null) {
            SESSIONS.remove(botId);
        }
    }
}
