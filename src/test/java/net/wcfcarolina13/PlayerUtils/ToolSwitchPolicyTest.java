package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSwitchPolicyTest {

    @Test
    void timeoutIsRetriedOnce() {
        assertTrue(ToolSwitchPolicy.retry(ToolSwitchPolicy.Outcome.TIMED_OUT, 1, false));
        assertFalse(ToolSwitchPolicy.retry(ToolSwitchPolicy.Outcome.TIMED_OUT, 2, false));
        assertEquals(2, ToolSwitchPolicy.MAX_ATTEMPTS);
    }

    @Test
    void aHopThatRanIsNeverRetried() {
        assertFalse(ToolSwitchPolicy.retry(ToolSwitchPolicy.Outcome.SWITCHED, 1, false));
        assertFalse(ToolSwitchPolicy.retry(ToolSwitchPolicy.Outcome.NOT_SWITCHED, 1, false));
    }

    @Test
    void cancelledWorkerDoesNotWaitAgain() {
        assertFalse(ToolSwitchPolicy.retry(ToolSwitchPolicy.Outcome.TIMED_OUT, 1, true));
    }

    @Test
    void nullOutcomeCountsAsAHopThatDidNotRun() {
        assertTrue(ToolSwitchPolicy.retry(null, 1, false));
    }

    @Test
    void bothSwitchHopsGoThroughTheRetry() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java"));
        assertTrue(src.contains("hopWithRetry(bot, \"slot \" + slot,"));
        assertTrue(src.contains("hopWithRetry(bot, \"bundle reach\","));
        assertTrue(src.contains("ToolSwitchPolicy.Outcome.TIMED_OUT);"), "a hop that never ran maps to TIMED_OUT");
    }
}
