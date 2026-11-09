package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickTransferPolicyTest {

    @Test
    void stopOrInterruptEndsBeforeTheWalk() {
        assertTrue(QuickTransferPolicy.stopBeforeWalk(true, false));
        assertTrue(QuickTransferPolicy.stopBeforeWalk(false, true));
        assertFalse(QuickTransferPolicy.stopBeforeWalk(false, false));
    }

    @Test
    void stoppedTransferThatMovedNothingSaysNothing() {
        assertNull(QuickTransferPolicy.resultMessage("Ann", 0, true, false, true));
        assertNull(QuickTransferPolicy.resultMessage("Ann", 0, false, false, true));
    }

    @Test
    void unstoppedEmptyTransferKeepsTheOldHint() {
        assertEquals("§eAnn could not deposit any items. Chest may be full or bot inventory empty.§r",
                QuickTransferPolicy.resultMessage("Ann", 0, true, true, false));
        assertEquals("§eAnn could not fetch any items. Chest may be empty or bot inventory full.§r",
                QuickTransferPolicy.resultMessage("Ann", 0, false, false, false));
    }

    @Test
    void itemsMovedAreReportedEvenAfterAStop() {
        assertEquals("§aAnn deposited 5 items (kept its gear).§r",
                QuickTransferPolicy.resultMessage("Ann", 5, true, true, true));
        assertEquals("§aAnn deposited 5 items.§r",
                QuickTransferPolicy.resultMessage("Ann", 5, true, false, false));
        assertEquals("§aAnn fetched 3 items.§r",
                QuickTransferPolicy.resultMessage("Ann", 3, false, true, false));
    }

    @Test
    void workerChecksForAStopAfterTheCombatWaitAndBeforeTheWalk() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/network/ChestRegistryNetworkManager.java"));
        int start = src.indexOf("QUICK_TRANSFER_EXECUTOR.execute(() -> {", src.indexOf("\"quick-store\""));
        assertTrue(start >= 0, "quick store worker not found");
        String body = src.substring(start);
        int pollSleep = body.indexOf("Thread.sleep(500);");
        int firstStop = body.indexOf("QuickTransferPolicy.stopBeforeWalk(");
        int stillInCombat = body.indexOf("is still in combat.");
        int settle = body.indexOf("Thread.sleep(1000);");
        int secondStop = body.indexOf("QuickTransferPolicy.stopBeforeWalk(", settle);
        int walk = body.indexOf("ChestStoreService.depositMatching(");
        int message = body.indexOf("QuickTransferPolicy.resultMessage(");
        int complete = body.indexOf("TaskService.complete(ticket, true)");
        assertTrue(pollSleep >= 0 && firstStop > pollSleep && stillInCombat > firstStop, "stop check right after the poll");
        assertTrue(settle > stillInCombat && secondStop > settle && walk > secondStop, "stop check after the settle, before the walk");
        assertTrue(message > walk && complete > message, "result message read before the ticket completes");
        String waits = body.substring(pollSleep, secondStop);
        assertFalse(waits.contains("catch (InterruptedException ignored)"), "the interrupt is not swallowed");
        assertEquals(2, waits.split("Thread.currentThread\\(\\).interrupt\\(\\);", -1).length - 1,
                "both sleeps restore the interrupt flag");
    }

    @Test
    void aFightIsRefusedWithItsOwnMessageNotTheBusyOne() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/network/ChestRegistryNetworkManager.java"));
        int handler = src.indexOf("private static void handleStoreTarget(");
        int busy = src.indexOf("is busy. Stop the current task first.", handler);
        int fight = src.indexOf("is in a fight. Try again once it's safe.", handler);
        int ticket = src.indexOf("TaskService.beginSkill(", handler);
        assertTrue(handler >= 0 && busy > handler && fight > busy && ticket > fight, "busy, then fight, then the ticket");
        String busyCheck = src.substring(src.lastIndexOf("if (", busy), busy);
        assertFalse(busyCheck.contains("isInCombat"), "combat no longer shares the busy message");
    }
}
