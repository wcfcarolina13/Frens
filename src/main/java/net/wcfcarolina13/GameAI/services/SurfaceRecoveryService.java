package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.impl.SurfaceRecoverySkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated system-owned surface recovery so break-free/ascent does not masquerade as collect_dirt.
 */
public final class SurfaceRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger("surface-recovery");
    private static final int MAX_NO_PROGRESS_STEPS = 6;

    private SurfaceRecoveryService() {
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

        BotEventHandler.setExternalOverrideActive(true);
        AutoFaceEntity.setBotExecutingTask(true);

        SkillExecutionResult result = SkillExecutionResult.failure("Surface recovery ended unexpectedly.");
        try {
            LOGGER.info("Surface recovery starting for bot {} on thread {}", bot.getName().getString(), Thread.currentThread().getName());
            Map<String, Object> params = new HashMap<>();
            params.put("_origin", "system");
            params.put("ascentToSurface", true);
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
            LOGGER.info("Surface recovery exit: success={} abortRequested={} message='{}'",
                    result != null && result.success(),
                    abortRequested,
                    result != null ? result.message() : "null");
            AutoFaceEntity.setBotExecutingTask(false);
            BotEventHandler.setExternalOverrideActive(false);
            boolean success = result != null && result.success() && !abortRequested;
            TaskService.complete(ticket, success);
        }
    }
}
