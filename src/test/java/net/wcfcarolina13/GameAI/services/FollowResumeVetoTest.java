package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowResumeVetoTest {

    @AfterEach
    void reset() {
        FollowResumeVeto.clearAllForTests();
    }

    @Test
    void vetoIsConsumedOnce() {
        UUID bot = UUID.randomUUID();
        FollowResumeVeto.veto(bot);
        assertTrue(FollowResumeVeto.consume(bot));
        assertFalse(FollowResumeVeto.consume(bot), "a veto applies to one resume only");
    }

    @Test
    void aNewTaskClearsAStaleVeto() {
        UUID bot = UUID.randomUUID();
        FollowResumeVeto.veto(bot);
        FollowResumeVeto.clearOnSuspend(bot);
        assertFalse(FollowResumeVeto.consume(bot));
    }

    @Test
    void vetoesArePerBotAndOnlyWhileATaskRuns() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FollowResumeVeto.veto(a);
        assertFalse(FollowResumeVeto.consume(b));
        assertTrue(FollowResumeVeto.consume(a));
        assertTrue(FollowResumeVeto.shouldVeto(true));
        assertFalse(FollowResumeVeto.shouldVeto(false));
        assertFalse(FollowResumeVeto.consume(null));
    }

    @Test
    void wiring() throws IOException {
        String sm = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java"));
        String suspend = sm.substring(sm.indexOf("public static FollowResumeState suspendFollowMode("),
                sm.indexOf("public static void resumeFollowMode("));
        assertTrue(suspend.contains("FollowResumeVeto.clearOnSuspend(botPlayer.getUuid())"));
        String resume = sm.substring(sm.indexOf("public static void resumeFollowMode("));
        int veto = resume.indexOf("FollowResumeVeto.consume(botPlayer.getUuid())");
        int restore = resume.indexOf("BotEventHandler.setComeModeWalk(");
        assertTrue(veto >= 0 && veto < restore, "veto checked before follow is restored");
        String cmd = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
        String stop = cmd.substring(cmd.indexOf("private static int executeFollowStop("),
                cmd.indexOf("static int executeGuard("));
        assertTrue(stop.contains("FollowResumeVeto.veto(bot.getUuid())"));
        String stay = cmd.substring(cmd.indexOf("static int executeStay("));
        assertTrue(stay.substring(0, stay.indexOf("setStayMode")).contains("FollowResumeVeto.veto(bot.getUuid())"));
    }
}
