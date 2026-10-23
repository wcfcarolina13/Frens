package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WakeUpDelayWiringTest {
    @Test
    void greetingWaitsThroughTheExistingLifecycleSafeDelay() throws IOException {
        String wake = wake();
        assertTrue(wake.contains("TaskService.runOnServerAfterTicks(server, DISPLAY_DELAY_TICKS, () ->"));
        assertFalse(wake.contains("server.send("));
    }

    @Test
    void busyBotRetryWaitsInsteadOfReusingTaskCreationTimeAsADueTime() throws IOException {
        String retry = retry();
        assertTrue(retry.contains("TaskService.runOnServerAfterTicks(server, CO_SLEEP_RETRY_DELAY_TICKS, () ->"));
        assertFalse(retry.contains("server.send("));
    }

    @Test
    void delayedGreetingOnlyUsesTheSameRegisteredAwakeBot() throws IOException {
        String wake = wake();
        int play = wake.indexOf("CompanionContextReactionService.playWakeUpForced(bot)");
        assertBefore(wake, "server.getPlayerManager().getPlayer(botId) != bot", play);
        assertBefore(wake, "!BotEventHandler.isRegisteredBot(bot)", play);
        assertBefore(wake, "bot.isSleeping()", play);
    }

    @Test
    void retryRechecksCurrentRegistrationAndControllerBeforeConsumingCooldown() throws IOException {
        String retry = retry();
        int stamp = retry.indexOf("CO_SLEEP_QUEUED_TICK.put(botId, retryTick)");
        assertBefore(retry, "!BotEventHandler.isRegisteredBot(bot)", stamp);
        assertBefore(retry, "CompanionCommunicationPolicy.resolveController(server, bot)", stamp);
        assertBefore(retry, "!commander.getUuid().equals(retryPlayer.getUuid())", stamp);
        assertTrue(stamp < retry.indexOf("scheduleCoSleep(server, bot)"));
    }

    @Test
    void retryPreservesTheZzzAndLiveSleepEligibilityChecks() throws IOException {
        String retry = retry();
        int stamp = retry.indexOf("CO_SLEEP_QUEUED_TICK.put(botId, retryTick)");
        for (String check : new String[]{"!retryPlayer.isSleeping()", "bot.isSleeping()",
                "BotZzzSleepService.isInSleepCycle(botId)", "BotZzzSleepService.isLoggedOff(bot.getName().getString())",
                "TaskService.hasActiveTask(botId)", "TickIntervals.elapsed(retryTick, lastQueued, CO_SLEEP_COOLDOWN_TICKS)",
                "BotSleepProximityPolicy.isWithinCommanderSleepRadius(distSq)", "sw.isDay() && !sw.isThundering()"}) {
            assertBefore(retry, check, stamp);
        }
    }

    private static void assertBefore(String body, String check, int action) {
        int at = body.indexOf(check);
        assertTrue(at >= 0 && action > at, "Missing check before action: " + check);
    }

    private static String wake() throws IOException {
        String source = source();
        return source.substring(source.indexOf("// Schedule the voiced line"), source.indexOf("LOGGER.info(\"Scheduled voiced wake-up line"));
    }

    private static String retry() throws IOException {
        String source = source();
        return source.substring(source.indexOf("// Schedule a delayed retry"), source.indexOf("private static void scheduleCoSleep"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotWakeUpDialogueService.java"));
    }
}
