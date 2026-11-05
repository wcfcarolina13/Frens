package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A singleplayer quit keeps static state while the next world's server restarts its tick counter
 * at 0 and the bots keep their UUIDs: tick- and day-based per-bot gates must be cleared at
 * SERVER_STOPPED, and executors shut at SERVER_STOPPING must be restarted at SERVER_STARTED.
 */
class SessionResetWiringTest {
    private static final String SERVICES = "src/main/java/net/wcfcarolina13/GameAI/services/";

    @Test
    void serverStoppedResetsEveryTickGatedService() throws IOException {
        String frens = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        String stopped = frens.substring(frens.indexOf("ServerLifecycleEvents.SERVER_STOPPED.register(server -> {"));
        stopped = stopped.substring(0, stopped.indexOf("\n        });"));
        for (String service : new String[]{"BotAutoReturnSunsetService", "BotEmergencyRescueService",
                "BotMutualAidService", "BotAutoHuntService", "BotAutoCookingService", "BotFallSafetyService",
                "SkillResumeService", "BotCombatCalloutService", "BotFleeService"}) {
            assertTrue(stopped.contains("services." + service + ".resetSession();"), service + " is reset at SERVER_STOPPED");
            String src = Files.readString(Path.of(SERVICES + service + ".java"));
            String reset = src.substring(src.indexOf("public static void resetSession() {"));
            reset = reset.substring(0, reset.indexOf("\n    }"));
            assertTrue(reset.contains(".clear();"), service + " clears its maps");
        }
    }

    @Test
    void theSunsetDayGatesAreAmongThem() throws IOException {
        String src = Files.readString(Path.of(SERVICES + "BotAutoReturnSunsetService.java"));
        String reset = src.substring(src.indexOf("public static void resetSession() {"));
        assertTrue(reset.contains("LAST_TRIGGERED_DAY.clear();") && reset.contains("LAST_RESUMED_DAY.clear();"));
    }

    @Test
    void durabilityFallbackExecutorIsRestarted() throws IOException {
        String src = Files.readString(Path.of(SERVICES + "DurabilityFallbackService.java"));
        assertFalse(src.contains("private static final ExecutorService fallbackExecutor"), "not final: it is replaced on restart");
        assertTrue(src.contains("public static synchronized void restartExecutors()"));
        String frens = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        String started = frens.substring(frens.indexOf("ServerLifecycleEvents.SERVER_STARTED.register(server -> {"));
        assertTrue(started.indexOf("DurabilityFallbackService.restartExecutors();") < started.indexOf("serverInstance = server;"));
    }
}
