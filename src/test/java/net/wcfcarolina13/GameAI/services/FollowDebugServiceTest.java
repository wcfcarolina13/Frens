package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowDebugServiceTest {
    @Test
    void routineLogsUseDebugUnlessVerboseDiagnosticsIsEnabled() {
        assertFalse(FollowDebugService.logAtInfo(false));
        assertTrue(FollowDebugService.logAtInfo(true));
    }
}
