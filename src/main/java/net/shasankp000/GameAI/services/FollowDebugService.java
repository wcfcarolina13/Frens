package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: follow-related debug logging and throttling extracted from BotEventHandler.
 *
 * <p>This class owns only log throttling state; it must not influence follow behavior.</p>
 */
public final class FollowDebugService {

    private static final Map<UUID, Long> LAST_PATH_SKIP_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DECISION_LOG_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STATUS_LOG_MS = new ConcurrentHashMap<>();

    private static final long STATUS_LOG_INTERVAL_MS = 1_800L;

    private FollowDebugService() {}

    public static void clear(UUID botId) {
        if (botId == null) {
            return;
        }
        LAST_PATH_SKIP_LOG_MS.remove(botId);
        LAST_DECISION_LOG_MS.remove(botId);
        LAST_STATUS_LOG_MS.remove(botId);
    }

    public static void reset() {
        LAST_PATH_SKIP_LOG_MS.clear();
        LAST_DECISION_LOG_MS.clear();
        LAST_STATUS_LOG_MS.clear();
    }

    public static void maybeLogPlanSkip(Logger logger, UUID botId, String message) {
        if (logger == null || botId == null || message == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = LAST_PATH_SKIP_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 5_000L) {
            return;
        }
        LAST_PATH_SKIP_LOG_MS.put(botId, now);
        logger.info("Follow path planning {}", message);
    }

    public static void maybeLogDecision(Logger logger, ServerPlayerEntity bot, String message) {
        if (logger == null || bot == null || message == null) {
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_DECISION_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < 2_500L) {
            return;
        }
        LAST_DECISION_LOG_MS.put(botId, now);
        logger.info("Follow decision: bot={} botPos={} msg={}",
                bot.getName().getString(),
                bot.getBlockPos().toShortString(),
                message);
    }

    public static void maybeLogStatus(Logger logger,
                                      ServerPlayerEntity bot,
                                      ServerPlayerEntity target,
                                      double targetDistSq,
                                      double horizDistSq,
                                      boolean canSee,
                                      boolean directBlocked,
                                      boolean usingWaypoints,
                                      int waypointCount,
                                      boolean botSealed,
                                      boolean commanderSealed,
                                      String navGoalStr,
                                      String doorPlanStr,
                                      String lastDoorStr,
                                      String avoidStr) {
        if (logger == null || bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_STATUS_LOG_MS.getOrDefault(botId, -1L);
        if (last >= 0 && (now - last) < STATUS_LOG_INTERVAL_MS) {
            return;
        }

        boolean shouldLog = targetDistSq >= 900.0D || directBlocked || usingWaypoints || (doorPlanStr != null && !doorPlanStr.isBlank());
        if (!shouldLog) {
            return;
        }
        LAST_STATUS_LOG_MS.put(botId, now);

        logger.info("Follow status: bot={} botPos={} target={} targetPos={} dist={} horiz={} canSee={} directBlocked={} usingWaypoints={} wp={} navGoal={} sealed={}/{}{}{}{}",
                bot.getName().getString(),
                bot.getBlockPos().toShortString(),
                target.getName().getString(),
                target.getBlockPos().toShortString(),
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)),
                String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(horizDistSq)),
                canSee,
                directBlocked,
                usingWaypoints,
                waypointCount,
                navGoalStr != null ? navGoalStr : "",
                botSealed,
                commanderSealed,
                doorPlanStr != null ? doorPlanStr : "",
                lastDoorStr != null ? lastDoorStr : "",
                avoidStr != null ? avoidStr : "");
    }
}

