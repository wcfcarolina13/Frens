package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small utility for transitioning bots back into IDLE after a short timeout,
 * when idle hobbies are enabled.
 */
public final class BotIdleResumeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("idle-resume");

    private static final int MAX_SLEEP_RETRIES = 20;      // ~100 seconds at 5s intervals
    private static final long SLEEP_RETRY_DELAY_TICKS = 100L;

    private BotIdleResumeService() {
    }

    public static void scheduleResumeIfEnabled(MinecraftServer server,
                                              ServerPlayerEntity bot,
                                              long delayTicks,
                                              String reason) {
        scheduleResumeIfEnabled(server, bot, delayTicks, reason, 0);
    }

    private static void scheduleResumeIfEnabled(MinecraftServer server,
                                               ServerPlayerEntity bot,
                                               long delayTicks,
                                               String reason,
                                               int sleepAttempt) {
        if (server == null || bot == null || bot.isRemoved()) {
            return;
        }
        long now = server.getTicks();
        long due = now + Math.max(1L, delayTicks);

        server.send(new ServerTask((int) due, () -> {
            if (bot.isRemoved() || !bot.isAlive()) {
                return;
            }
            if (!BotHomeService.isIdleHobbiesEnabled(bot)) {
                return;
            }
            if (TaskService.hasActiveTask(bot.getUuid())) {
                return;
            }

            // If we're still sleeping, wait a bit and try again.
            if (bot.isSleeping()) {
                if (sleepAttempt < MAX_SLEEP_RETRIES) {
                    scheduleResumeIfEnabled(server, bot, SLEEP_RETRY_DELAY_TICKS, reason, sleepAttempt + 1);
                }
                return;
            }

            // Only override "holding position". Don't stomp on guard/patrol/follow.
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.STAY) {
                return;
            }

            boolean changed = BotEventHandler.setIdleMode(bot, true);
            if (changed) {
                BotIdleHobbiesService.requestDecisionNow(bot);
                LOGGER.info("Auto-resumed idle for {} (reason={})", bot.getName().getString(), reason);
            }
        }));
    }
}
