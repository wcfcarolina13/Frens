package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SleepFollowPolicyTest {

    @Test
    void sleepingBotSkipsTheFollowTickWhateverTheTask() {
        assertTrue(SleepFollowPolicy.suppressFollowTick(true, null));
        assertTrue(SleepFollowPolicy.suppressFollowTick(true, "skill:woodcut"));
        assertTrue(SleepFollowPolicy.suppressFollowTick(true, null, true));
    }

    @Test
    void botWalkingToABedForTheSleepTaskSkipsTheFollowTick() {
        assertTrue(SleepFollowPolicy.suppressFollowTick(false, "skill:sleep"));
        assertTrue(SleepFollowPolicy.suppressFollowTick(false, "SKILL:SLEEP"));
        assertTrue(SleepFollowPolicy.suppressFollowTick(false, "sleep"));
        assertTrue(SleepFollowPolicy.suppressFollowTick(false, " skill:sleep "));
    }

    @Test
    void hostilesLetFollowDefendAnAwakeBotOnItsWayToBed() {
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, "skill:sleep", true));
        assertTrue(SleepFollowPolicy.suppressFollowTick(false, "skill:sleep", false));
    }

    @Test
    void awakeBotWithoutTheSleepTaskFollows() {
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, null));
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, ""));
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, "skill:woodcut"));
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, "skill:sleepwalk"));
        assertFalse(SleepFollowPolicy.suppressFollowTick(false, "ambient:sleep"));
    }

    @Test
    void toggleOnAFollowingBotInBedOrWalkingToBedCallsItBack() {
        assertTrue(SleepFollowPolicy.toggleCallsBack(true, true, null));
        assertTrue(SleepFollowPolicy.toggleCallsBack(true, false, "skill:sleep"));
        assertFalse(SleepFollowPolicy.toggleCallsBack(true, false, null));
        assertFalse(SleepFollowPolicy.toggleCallsBack(true, false, "skill:fish"));
        // Not following this player: the toggle already turns follow on (and wakes it).
        assertFalse(SleepFollowPolicy.toggleCallsBack(false, true, "skill:sleep"));
    }

    // ── wiring ─────────────────────────────────────────────────────────────

    @Test
    void followGuardSitsBeforeLingerIdleSweepAndHandleFollow() throws IOException {
        String body = updateBehavior();
        int guard = body.indexOf("SleepFollowPolicy.suppressFollowTick(");
        assertTrue(guard >= 0, "updateBehavior must call the sleep guard");
        assertTrue(body.substring(guard - 200, guard).contains("mode == Mode.FOLLOW"));
        assertTrue(body.indexOf("bot.isSleeping()", guard) > guard);
        assertTrue(body.indexOf("TaskService.getActiveTaskName(botId)", guard) > guard);
        assertTrue(guard < body.indexOf("isInPostCombatLingerWindow(bot)"));
        assertTrue(guard < body.indexOf("tickOpportunisticIdleSweep(bot, state, server, mode)"));
        assertTrue(guard < body.indexOf("return handleFollow(bot, state, server, augmentedHostiles)"));
    }

    @Test
    void sleepingBotReturnsBeforeCreeperBackoffAndPostCombatSweepInEveryMode() throws IOException {
        String body = updateBehavior();
        int hold = body.indexOf("if (bot.isSleeping()) {\n            return true;\n        }");
        assertTrue(hold >= 0, "updateBehavior must return early for a sleeping bot");
        int teleport = body.indexOf("External teleport detected.");
        assertTrue(teleport >= 0 && teleport < hold, "the hold sits after teleport detection");
        // Mode-agnostic: no mode test on the lines leading into it.
        assertFalse(body.substring(Math.max(0, hold - 120), hold).contains("mode =="));
        assertTrue(hold < body.indexOf("BotCreeperDefenseService.tickBackoff(bot, server)"));
        assertTrue(hold < body.indexOf("tickPostCombatSweep(bot, server, mode)"));
        assertTrue(hold < body.indexOf("tickOpportunisticIdleSweep(bot, state, server, mode)"));
        assertTrue(hold < body.indexOf("SleepFollowPolicy.suppressFollowTick("));
    }

    @Test
    void followStopAndToggleWakeTheBotBeforeChangingFollow() throws IOException {
        String src = commands();
        String follow = between(src, "private static int executeFollow(CommandContext", "private static int executeFollowDistance(");
        assertBefore(follow, "wakeIfSleeping(bot)", "BotEventHandler.setFollowMode(bot, target, false, true)");

        String stop = between(src, "private static int executeStop(CommandContext", "static int executeStopTargets(");
        assertBefore(stop, "wakeIfSleeping(bot)", "BotEventHandler.stopFollowing(bot, false)");

        String toggle = between(src, "static int executeFollowToggleTargets(", "private static int executeInlineBotCommand(");
        assertBefore(toggle, "SleepFollowPolicy.toggleCallsBack(", "if (isFollowingTarget && !callsBack)");
        assertBefore(toggle, "wakeIfSleeping(bot)", "BotEventHandler.setFollowMode(bot, followTarget, false, true)");

        String helper = between(src, "private static void wakeIfSleeping(", "private static int executeStop(");
        assertTrue(helper.contains("bot.isSleeping()"));
        assertTrue(helper.contains("bot.wakeUp(false, true)"));

        // Inline "stop" reaches executeStop through executeStopTargets, so it wakes too.
        String inline = between(src, "private static int handleInlineStop(", "private static boolean shouldTreatAsAlias(");
        assertTrue(inline.contains("executeStopTargets(context, bots, isAll)"));
    }

    private static void assertBefore(String body, String first, String second) {
        int a = body.indexOf(first);
        int b = body.indexOf(second);
        assertTrue(a >= 0, "missing: " + first);
        assertTrue(b >= 0, "missing: " + second);
        assertTrue(a < b, first + " must come before " + second);
    }

    private static String between(String src, String start, String end) {
        int s = src.indexOf(start);
        assertTrue(s >= 0, "missing start: " + start);
        int e = src.indexOf(end, s + start.length());
        assertTrue(e > s, "missing end: " + end);
        return src.substring(s, e);
    }

    private static String updateBehavior() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        return between(src, "public static boolean updateBehavior(", "private static boolean handleFollow(");
    }

    private static String commands() throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
    }
}
