package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * runSkill takes a task ticket, then runs start-up steps (attach, drop-sweep cancel, follow
 * suspend) before the try whose finally completes the ticket. A throw in those steps used to leak
 * the ticket and the bot's task slot. Reads the source: SkillManager touches server types.
 */
class SkillManagerTicketReleaseWiringTest {

    private static String runSkill() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java"));
        int start = src.indexOf("public static SkillExecutionResult runSkill(");
        int end = src.indexOf("} finally {", start);
        assertTrue(start >= 0 && end > start, "runSkill and its finally not found");
        return src.substring(start, end);
    }

    @Test
    void everyStartUpStepAfterTheTicketIsGuarded() throws IOException {
        String body = runSkill();
        int ticket = body.indexOf("TaskTicket ticket = ticketOpt.get();");
        int guardTry = body.indexOf("try {", body.indexOf("FollowResumeState followResume;"));
        int attach = body.indexOf("TaskService.attachExecutingThread(ticket");
        int suspend = body.indexOf("followResume = suspendFollowMode(botPlayer);");
        int release = body.indexOf("TaskService.complete(ticket, false);");
        int mainTry = body.indexOf("result = skill.execute(context);");
        assertTrue(ticket >= 0 && guardTry > ticket, "start-up guard after the ticket");
        assertTrue(attach > guardTry && suspend > attach, "attach and follow suspend inside the guard");
        assertTrue(release > suspend && release < mainTry, "the guard releases the ticket before the main try");
    }
}
