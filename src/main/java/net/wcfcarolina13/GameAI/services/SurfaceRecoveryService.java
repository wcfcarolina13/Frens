package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.impl.CollectDirtSkill;
import net.wcfcarolina13.GameAI.skills.impl.SurfaceRecoverySkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated system-owned surface recovery so break-free/ascent does not masquerade as collect_dirt.
 */
public final class SurfaceRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("surface-recovery");
    private static final int MAX_NO_PROGRESS_STEPS = 6;
    /** A repeated failed exit line logs at INFO once a minute per bot, or when its text changes (finding 48). */
    private static final long EXIT_LOG_INTERVAL_MS = 60_000L;
    private static final Map<UUID, String> EXIT_LOG_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> EXIT_LOG_TIME = new ConcurrentHashMap<>();

    private SurfaceRecoveryService() {
    }

    /** Forget one bot's exit-line throttle. */
    public static void clearLogThrottle(UUID botId) {
        if (botId == null) {
            return;
        }
        EXIT_LOG_STATE.remove(botId);
        EXIT_LOG_TIME.remove(botId);
    }

    /** Server shutdown: forget every bot's exit-line throttle. */
    public static void clearAllLogThrottles() {
        EXIT_LOG_STATE.clear();
        EXIT_LOG_TIME.clear();
    }

    public static SkillExecutionResult runSurfaceRecovery(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return SkillExecutionResult.failure("No bot available for surface recovery.");
        }
        ServerCommandSource source = bot.getCommandSource();
        if (source == null) {
            return SkillExecutionResult.failure("No command source available for surface recovery.");
        }
        UUID botUuid = bot.getUuid();
        var ticketOpt = TaskService.beginSystemTask("surface_recovery", source, botUuid);
        if (ticketOpt.isEmpty()) {
            return SkillExecutionResult.failure("Another task is already running.");
        }
        TaskService.TaskTicket ticket = ticketOpt.get();
        ticket.setOpenEnded(false);
        TaskService.attachExecutingThread(ticket, Thread.currentThread());

        boolean hadIdleSweepState = BackgroundSweepPolicy.clearPendingIdleSweepState(botUuid);
        boolean hadInFlightSweep = DropSweepService.isInProgressFor(bot);
        DropSweepService.requestCancel(bot, "surface-recovery-start");
        if (hadIdleSweepState || hadInFlightSweep) {
            LOGGER.info("Background drop sweep canceled for {} because surface recovery started", bot.getName().getString());
        }

        AutoFaceEntity.setBotExecutingTask(true);

        SkillExecutionResult result = SkillExecutionResult.failure("Surface recovery ended unexpectedly.");
        try {
            LOGGER.info("Surface recovery starting for bot {} on thread {}", bot.getName().getString(), Thread.currentThread().getName());
            Map<String, Object> params = new HashMap<>();
            params.put("_origin", "system");
            params.put("ascentToSurface", true);
            // System recovery escaping a flooded pocket: water is no reason to pause this ascent.
            params.put(CollectDirtSkill.FLOOD_ESCAPE_PARAM, true);
            params.put("skipTorches", true);
            params.put("allowChestStore", false);
            params.put("maxNoProgressSteps", MAX_NO_PROGRESS_STEPS);
            result = new SurfaceRecoverySkill().execute(new SkillContext(source, new HashMap<>(), params));
            if (TaskService.isAbortRequested(botUuid)) {
                String reason = TaskService.getCancelReason(botUuid)
                        .orElse("Surface recovery interrupted.");
                result = SkillExecutionResult.failure(reason);
            }
            return result;
        } catch (Throwable t) {
            LOGGER.error("Surface recovery crashed for {}: {}", bot.getName().getString(), t.getMessage(), t);
            result = SkillExecutionResult.failure("Surface recovery crashed: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            return result;
        } finally {
            boolean abortRequested = TaskService.isAbortRequested(botUuid);
            boolean succeeded = result != null && result.success();
            String exitMessage = result != null ? String.valueOf(result.message()) : "null";
            boolean logInfo = true;
            if (succeeded) {
                clearLogThrottle(botUuid);
            } else {
                boolean changed = LogThrottle.changed(EXIT_LOG_STATE, botUuid, abortRequested + "|" + exitMessage);
                boolean due = LogThrottle.due(EXIT_LOG_TIME, botUuid, System.currentTimeMillis(), EXIT_LOG_INTERVAL_MS);
                logInfo = changed || due;
            }
            if (logInfo) {
                LOGGER.info("Surface recovery exit: bot={} success={} abortRequested={} message='{}'",
                        bot.getName().getString(), succeeded, abortRequested, exitMessage);
            } else {
                LOGGER.debug("Surface recovery exit: bot={} success={} abortRequested={} message='{}'",
                        bot.getName().getString(), succeeded, abortRequested, exitMessage);
            }
            AutoFaceEntity.setBotExecutingTask(false);
            boolean success = succeeded && !abortRequested;
            TaskService.complete(ticket, success);
        }
    }
}
