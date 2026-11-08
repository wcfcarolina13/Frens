package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-thread paths that used to sleep or wait on themselves. */
class ServerThreadNoBlockTest {
    private static final String MAIN = "src/main/java/net/wcfcarolina13/";

    @Test
    void comeWalksWithinThirtyBlocksEvenWithTeleportOn() {
        assertTrue(ComeTeleportPolicy.walkInsteadOfTeleport(0));
        assertTrue(ComeTeleportPolicy.walkInsteadOfTeleport(900));
        assertFalse(ComeTeleportPolicy.walkInsteadOfTeleport(901));
    }

    @Test
    void comeUsesThePolicyBeforeTheBlockingMovementCall() throws IOException {
        String src = Files.readString(Path.of(MAIN + "Commands/modCommandRegistry.java"));
        int gate = src.indexOf("if (!teleportAllowed || ComeTeleportPolicy.walkInsteadOfTeleport(bot.squaredDistanceTo(commander))) {");
        assertTrue(gate >= 0);
        assertTrue(src.indexOf("BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, allowRecoverySkills, true);", gate) > gate);
    }

    @Test
    void villageScanRunsInlineOnTheServerThread() throws IOException {
        String src = Files.readString(Path.of(MAIN + "GameAI/services/construction/VillageFortificationLayoutService.java"));
        assertTrue(src.contains("ServerThreadCallPolicy.call(server.isOnThread(), server::execute, () ->"));
        assertFalse(src.contains("villagerFuture.get("), "no wait on a task the waiting thread must run");
    }

    @Test
    void mountFeedingNeverSleepsOnTheServerTick() throws IOException {
        String src = Files.readString(Path.of(MAIN + "GameAI/services/AnimalFeedingService.java"));
        int onThread = src.indexOf("if (server != null && server.isOnThread()) {");
        assertTrue(onThread >= 0 && onThread < src.indexOf("Thread.sleep(160L)"), "the tick path returns before the sleep loop");
        String helper = src.substring(src.indexOf("private static boolean feedOnServerTick("));
        helper = helper.substring(0, helper.indexOf("\n    }\n"));
        assertFalse(helper.contains("sleep"), "paced by scheduled ticks");
        assertTrue(helper.contains("TaskService.runOnServerAfterTicks(server, 3L,"));
    }
}
