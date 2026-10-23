package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCooldownPolicyTest {
    @Test void firstDiagnosticIsAllowedIncludingAtTickZero() {
        assertTrue(DiagnosticCooldownPolicy.canLog(null, 0L, 60L));
        assertTrue(DiagnosticCooldownPolicy.canLog(null, 100L, 60L));
    }
    @Test void repeatedDiagnosticWaitsUntilTheExactCooldownBoundary() {
        assertFalse(DiagnosticCooldownPolicy.canLog(100L, 100L, 60L));
        assertFalse(DiagnosticCooldownPolicy.canLog(100L, 159L, 60L));
        assertTrue(DiagnosticCooldownPolicy.canLog(100L, 160L, 60L));
    }
    @Test void restartedTickCounterDoesNotSuppressTheNewSession() {
        assertTrue(DiagnosticCooldownPolicy.canLog(900L, 2L, 60L));
        assertFalse(DiagnosticCooldownPolicy.canLog(0L, 2L, 60L));
    }
    @Test void zeroCooldownAllowsEveryDiagnostic() {
        assertTrue(DiagnosticCooldownPolicy.canLog(10L, 10L, 0L));
        assertTrue(DiagnosticCooldownPolicy.canLog(10L, 10L, -1L));
    }
    @Test void suffocationWarningUsesMissingTimestampInsteadOfOverflowingSentinel() throws Exception {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        int start = source.indexOf("long lastWarning");
        if (start < 0) start = source.indexOf("Long lastWarning");
        assertTrue(start >= 0);
        String gate = source.substring(start, source.indexOf("LAST_IN_WALL_WARNING_TICK.put", start));
        assertTrue(gate.contains("LAST_IN_WALL_WARNING_TICK.get(botUuid)"));
        assertTrue(gate.contains("DiagnosticCooldownPolicy.canLog(lastWarning, now, IN_WALL_WARNING_COOLDOWN_TICKS)"));
    }
    @Test void followDiagnosticUsesTheSameMissingTimestampRule() throws Exception {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        int start = source.indexOf("private static boolean shouldLogBackoff(");
        String gate = source.substring(start, source.indexOf("private static boolean isStuckDrivenPlanReason", start));
        assertTrue(gate.contains("FOLLOW_BACKOFF_LOG_TICK.get(botId)"));
        assertTrue(gate.contains("DiagnosticCooldownPolicy.canLog(last, nowTick, FOLLOW_BACKOFF_LOG_COOLDOWN_TICKS)"));
    }
}
