package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SkillResumeReplyAccessTest {
    @Test
    void currentBotPermissionIsCheckedBeforeEitherAnswerChangesTheJob() throws IOException {
        String body = chat();
        int pending = body.indexOf("PENDING_BY_RESPONDER.get(key)");
        int gate = body.indexOf("BotAccessGate.permits(sender, bot, \"skill_resume_reply\")");
        int answer = body.indexOf("if (normalized.equals(\"yes\"))");
        assertTrue(pending >= 0 && pending < gate && gate < answer);
        assertTrue(gate < body.indexOf("resume(pending, false, sender.getCommandSource())"));
        assertTrue(gate < body.indexOf("clear(pending.botUuid())"));
    }

    @Test
    void authorizationResolvesTheLiveBotByTheSavedUuid() throws IOException {
        String body = chat();
        int server = body.indexOf("MinecraftServer server = sender.getCommandSource().getServer()");
        int target = body.indexOf("server.getPlayerManager().getPlayer(pending.botUuid())");
        int gate = body.indexOf("BotAccessGate.permits(sender, bot,");
        assertTrue(server >= 0 && server < target && target < gate);
        assertTrue(body.substring(server, gate).contains("server == null ? null"));
    }

    @Test
    void deniedAnswerRetiresOnlyItsStaleRouteAndKeepsTheJob() throws IOException {
        String body = chat();
        String denial = block(body, "if (!net.wcfcarolina13.network.BotAccessGate.permits(");
        assertTrue(denial.contains("ChatUtils.sendSystemMessage(sender.getCommandSource()"));
        assertTrue(denial.contains("The saved job was kept."));
        assertTrue(denial.contains("return true;"));
        assertFalse(denial.contains("clear("));
        assertFalse(denial.contains("notifyDecision("));
        assertFalse(denial.contains("resume("));
        assertTrue(denial.contains("PENDING_BY_RESPONDER.remove(key, pending)"));
        assertFalse(denial.contains("LAST_SKILL_BY_BOT"));
        assertFalse(denial.contains("AWAITING_DECISION"));
        assertFalse(denial.contains("DEFERRED_RESUMES"));
    }

    @Test
    void authorizedAnswersRetainTheirExistingResumeAndStandDownPaths() throws IOException {
        String body = chat();
        String yes = block(body, "if (normalized.equals(\"yes\"))");
        assertTrue(yes.contains("resume(pending, false, sender.getCommandSource())"));
        int no = body.indexOf("} else {", body.indexOf("if (normalized.equals(\"yes\"))"));
        String standDown = block(body.substring(no), "else");
        assertTrue(standDown.contains("notifyDecision(pending, false)"));
        assertTrue(standDown.contains("clear(pending.botUuid())"));
    }

    private static String chat() throws IOException {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java"));
        return block(source, "public static boolean handleChat(");
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
