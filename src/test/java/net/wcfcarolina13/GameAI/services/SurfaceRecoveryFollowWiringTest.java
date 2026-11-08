package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface recovery (run from flee and the dig-down climb-out) executes its skill directly, outside
 * SkillManager.runSkill, so follow kept steering the bot while the ascent walked it. It must
 * suspend follow before the skill, and resume only after the ticket completes and never after an
 * abort — the same contract as /bot craft and Quick Store.
 */
class SurfaceRecoveryFollowWiringTest {

    @Test
    void recoverySuspendsFollowAndResumesAfterCompleting() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/SurfaceRecoveryService.java"));
        int start = src.indexOf("public static SkillExecutionResult runSurfaceRecovery(");
        assertTrue(start >= 0);
        String body = src.substring(start);
        int busy = body.indexOf("return SkillExecutionResult.failure(\"Another task is already running.\")");
        int suspend = body.indexOf("SkillManager.suspendFollowMode(bot)");
        int work = body.indexOf("new SurfaceRecoverySkill().execute(");
        int abortRead = body.indexOf("boolean abortRequested = TaskService.isAbortRequested(botUuid)");
        int complete = body.indexOf("TaskService.complete(ticket, success)");
        int resume = body.indexOf("SkillManager.resumeFollowMode(bot, source.getServer(), followResume)");
        assertTrue(busy >= 0 && busy < suspend, "a refused start never touches follow");
        assertTrue(suspend < work, "follow suspended before the ascent");
        assertTrue(body.lastIndexOf("try {", suspend) > body.indexOf("followResume = null"),
                "suspend sits inside the try, so the finally always runs after it");
        assertTrue(abortRead > work && abortRead < complete, "abort read before complete drops the ticket");
        assertTrue(complete < resume, "ticket completed before follow resumes");
        assertTrue(body.substring(complete, resume).contains("if (!abortRequested)"), "no resume after an abort");
    }
}
