package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick Store / Quick Fetch walk the bot to a chest on an async worker with a task ticket, outside
 * SkillManager.runSkill. Nothing in the follow tick checks for a task, so follow must be suspended
 * for the walk and resumed only after the ticket completes (and not after an abort).
 */
class QuickStoreFollowWiringTest {

    @Test
    void quickStoreWorkerSuspendsFollowAndResumesAfterCompleting() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/network/ChestRegistryNetworkManager.java"));
        int start = src.indexOf("CompletableFuture.runAsync(() -> {", src.indexOf("\"quick-store\""));
        assertTrue(start >= 0, "quick store worker not found");
        String body = src.substring(start);
        int attach = body.indexOf("TaskService.attachExecutingThread(ticket, Thread.currentThread())");
        int suspend = body.indexOf("SkillManager.suspendFollowMode(bot)");
        int work = body.indexOf("ChestStoreService.depositMatching(");
        int abortRead = body.indexOf("boolean abortRequested = net.wcfcarolina13.GameAI.services.TaskService.isAbortRequested(bot.getUuid())");
        int complete = body.indexOf("TaskService.complete(ticket, true)");
        int resume = body.indexOf("SkillManager.resumeFollowMode(bot, server, followResume)");
        assertTrue(attach >= 0 && suspend > attach && work > suspend, "attach and suspend before the walk");
        assertTrue(abortRead > work && complete > abortRead && resume > complete, "abort read, complete, then resume");
        assertTrue(body.substring(complete, resume).contains("if (!abortRequested)"), "no resume after an abort");
    }
}
