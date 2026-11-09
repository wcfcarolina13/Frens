package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void giveUpDetailNamesAnInterruptNotATimeout() {
        assertEquals("interrupted (worker cancelled)",
                ToolSwitchPolicy.giveUpDetail(ToolSwitchPolicy.Outcome.TIMED_OUT, 1, true));
        assertEquals("timed out 2 times",
                ToolSwitchPolicy.giveUpDetail(ToolSwitchPolicy.Outcome.TIMED_OUT, 2, false));
        assertEquals("timed out 1 time", ToolSwitchPolicy.giveUpDetail(null, 1, false));
    }

    @Test
    void giveUpDetailIsSilentWhenTheHopAnswered() {
        assertNull(ToolSwitchPolicy.giveUpDetail(ToolSwitchPolicy.Outcome.SWITCHED, 1, true));
        assertNull(ToolSwitchPolicy.giveUpDetail(ToolSwitchPolicy.Outcome.NOT_SWITCHED, 2, false));
    }

    @Test
    void hopWithRetryLogsThroughGiveUpDetail() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java"));
        int hop = src.indexOf("private static ToolSwitchPolicy.Outcome hopWithRetry(");
        assertTrue(hop >= 0);
        String body = src.substring(hop, src.indexOf("private static <T> T callOnServer(", hop));
        int interrupted = body.indexOf("boolean interrupted = Thread.currentThread().isInterrupted();");
        int retry = body.indexOf("ToolSwitchPolicy.retry(outcome, attempt, interrupted)");
        int detail = body.indexOf("ToolSwitchPolicy.giveUpDetail(outcome, attempt, interrupted)");
        assertTrue(interrupted >= 0 && retry > interrupted && detail > retry, "one interrupt read feeds retry and the log");
        assertFalse(body.contains("server hop timed out {} times"), "old wording logged an interrupt as a timeout");
    }

    /**
     * A first hop the server had already started finishes after the 2 s timeout (cancel(false)
     * does not stop a running task). Each step checks the main hand first, so the retry after
     * such a late hop reports SWITCHED instead of failing the slot check.
     */
    @Test
    void bothStepsCheckTheMainHandBeforeTheSlot() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java"));
        int slotStep = src.indexOf("hopWithRetry(bot, \"slot \" + slot,");
        String slotBody = src.substring(slotStep, src.indexOf("});", slotStep));
        int inHand = slotBody.indexOf("ToolSelector.slotStillMatches(expected, bot.getMainHandStack())");
        int slotCheck = slotBody.indexOf("ToolSelector.slotStillMatches(expected, inv.getStack(slot))");
        int swap = slotBody.indexOf("swapStacks(inv, slot, target)");
        assertTrue(inHand >= 0 && slotCheck > inHand && swap > slotCheck, "main-hand check first in the slot step");
        assertTrue(slotBody.substring(inHand, slotCheck).contains("return true;"));

        int bundleStep = src.indexOf("hopWithRetry(bot, \"bundle reach\",");
        String bundleBody = src.substring(bundleStep, src.indexOf("});", bundleStep));
        int bundleInHand = bundleBody.indexOf("ToolSelector.slotStillMatches(wanted, bot.getMainHandStack())");
        int reach = bundleBody.indexOf("BundleService.reachFirst(bot, match)");
        assertTrue(bundleInHand >= 0 && reach > bundleInHand, "main-hand check first in the bundle step");
        assertTrue(bundleBody.substring(bundleInHand, reach).contains("return true;"));
    }
}
