package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 47: every /bot follow and /bot come path calls forceAbort right before starting the
 * walk, and the abort latch then killed the follow's own leaf clear on its first tick. A
 * player-issued follow/come now clears the latch (like guard/patrol since 1.1.272); internal
 * resumes and in-skill callers must not. Works from source text because BotEventHandler needs
 * the Minecraft runtime.
 */
class FollowCommandAbortLatchSourceTest {

    private static final String HANDLER = "src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java";
    private static final String COMMANDS = "src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java";
    private static final String[] INTERNAL_CALLERS = {
            "src/main/java/net/wcfcarolina13/GameAI/skills/impl/ShelterSkill.java",
            "src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java",
            "src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java",
            "src/main/java/net/wcfcarolina13/GameAI/services/FollowPlannerService.java",
    };
    private static final Pattern CALL = Pattern.compile(
            "BotEventHandler\\.(setFollowModeDistance|setFollowMode|setComeModeWalk)\\(([^;]*?)\\);", Pattern.DOTALL);

    private static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    /** Body of the overload whose parameter list ends with {@code boolean supersedesStop}. */
    private static String supersedingBody(String src, String method) {
        Matcher m = Pattern.compile("public static String " + method + "\\([^)]*boolean supersedesStop\\)\\s*\\{")
                .matcher(src);
        assertTrue(m.find(), method + "(..., boolean supersedesStop) not found");
        int depth = 1;
        int i = m.end();
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(m.end(), i);
    }

    /** Each call as "method|args" with whitespace collapsed. */
    private static List<String> calls(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = CALL.matcher(src);
        while (m.find()) {
            out.add(m.group(1) + "|" + m.group(2).replaceAll("\\s+", " ").trim());
        }
        return out;
    }

    /** Top-level argument count (commas inside nested parentheses are ignored). */
    private static int argCount(String args) {
        int depth = 0;
        int count = args.isEmpty() ? 0 : 1;
        for (char c : args.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    @Test
    void supersedingOverloadsClearTheLatchOnlyWhenAsked() throws IOException {
        String src = read(HANDLER);
        for (String method : new String[] {"setFollowMode", "setFollowModeDistance", "setComeModeWalk"}) {
            String body = supersedingBody(src, method);
            Matcher gate = Pattern.compile("if \\(supersedesStop[^)]*\\)\\s*\\{\\s*TaskService\\.clearAbortLatch\\(")
                    .matcher(body);
            assertTrue(gate.find(), method + " must clear the abort latch behind the supersedesStop flag");
            assertEquals(1, body.split("clearAbortLatch\\(", -1).length - 1,
                    method + " must clear the latch only once, behind the flag");
        }
    }

    @Test
    void everyCommandFollowOrComeSupersedesTheStop() throws IOException {
        List<String> found = calls(read(COMMANDS));
        assertFalse(found.isEmpty(), "no follow/come calls found in modCommandRegistry");
        for (String call : found) {
            assertTrue(call.endsWith(", true"), "command call must pass supersedesStop=true: " + call);
        }
    }

    @Test
    void internalCallersLeaveTheLatchAlone() throws IOException {
        for (String path : INTERNAL_CALLERS) {
            List<String> found = calls(read(path));
            assertFalse(found.isEmpty(), "no follow/come call found in " + path);
            for (String call : found) {
                String method = call.substring(0, call.indexOf('|'));
                int n = argCount(call.substring(call.indexOf('|') + 1));
                // The supersedesStop overloads take 4 (follow, follow-distance) and 6 (come) arguments.
                int superseding = "setComeModeWalk".equals(method) ? 6 : 4;
                assertTrue(n < superseding, path + " must use a non-superseding overload: " + call);
            }
        }
    }

    @Test
    void followDistanceCommandSupersedesTheStop() throws IOException {
        List<String> found = calls(read(COMMANDS));
        assertTrue(found.stream().anyMatch(c -> c.startsWith("setFollowModeDistance|")),
                "/bot follow <bot> <n> must call setFollowModeDistance");
        for (String call : found) {
            if (call.startsWith("setFollowModeDistance|")) {
                assertEquals(4, argCount(call.substring(call.indexOf('|') + 1)), call);
            }
        }
    }

    @Test
    void argCountIgnoresNestedCommas() {
        assertEquals(5, argCount("liveBot, liveTarget, liveTarget.getBlockPos().offset(1, 2), 3.2D, true"));
        assertEquals(3, argCount("bot, target, false"));
    }
}
