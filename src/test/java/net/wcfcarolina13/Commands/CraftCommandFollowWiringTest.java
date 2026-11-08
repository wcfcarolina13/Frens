package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /bot craft runs CraftingHelper on a worker with a task ticket but outside SkillManager.runSkill,
 * and nothing in the follow tick checks for an active task, so follow kept steering the bot while
 * the crafting walk moved it. The worker must suspend follow first and resume it only after the
 * ticket is completed (resumeFollowMode skips while a task is active), and not after an abort.
 */
class CraftCommandFollowWiringTest {

    @Test
    void craftWorkerSuspendsFollowAndResumesAfterCompleting() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
        int start = src.indexOf("private static int executeCraftGeneric(");
        int end = src.indexOf("private static int executePlaceGeneric(", start);
        assertTrue(start >= 0 && end > start);
        String body = src.substring(start, end);
        int suspend = body.indexOf("SkillManager.suspendFollowMode(bot)");
        int work = body.indexOf("CraftingHelper.craftGenericForPlayer(");
        int abortRead = body.indexOf("boolean abortRequested = TaskService.isAbortRequested(botUuid)");
        int complete = body.indexOf("TaskService.complete(ticket, success)");
        int resume = body.indexOf("SkillManager.resumeFollowMode(bot, source.getServer(), followResume)");
        assertTrue(suspend >= 0 && suspend < work, "follow suspended before the crafting walk");
        assertTrue(abortRead > work && abortRead < complete, "abort read before complete drops the ticket");
        assertTrue(complete < resume, "ticket completed before follow resumes");
        assertTrue(body.substring(complete, resume).contains("if (!abortRequested)"), "no resume after an abort");
    }
}
