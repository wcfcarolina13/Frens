package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Brigadier nodes are nested by parentheses, and a misplaced paren silently re-parents a whole
 * subtree: {@code /bot debug} once sat under {@code literal("spawn")}, so {@code /bot debug on}
 * never parsed. This reads {@code modCommandRegistry.java} (strings and comments ignored),
 * measures the paren depth of each {@code literal("...")} node in the {@code /bot} registration,
 * and checks that the top-level subcommands are direct children of {@code literal("bot")}.
 */
class BotCommandTreeShapeTest {

    private static final String REGISTRY = "net/wcfcarolina13/Commands/modCommandRegistry.java";

    /** First occurrence (after the /bot registration starts) of each literal name -> {offset, depth}. */
    private static final Map<String, int[]> LITERALS = new LinkedHashMap<>();
    private static String code;

    @BeforeAll
    static void scan() throws IOException {
        String src = Files.readString(findSourceRoot().resolve(REGISTRY), StandardCharsets.UTF_8);
        int start = src.indexOf("dispatcher.register(");
        assertTrue(start >= 0, "dispatcher.register( not found in " + REGISTRY);

        StringBuilder blanked = new StringBuilder(src.length());
        int depth = 0;
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') { blanked.append(' '); i++; }
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? src.length() : end + 2;
                while (i < end) { blanked.append(' '); i++; }
                continue;
            }
            if (c == '"' || c == '\'') {
                if (c == '"' && i >= start && isLiteralCall(src, i)) {
                    int close = src.indexOf('"', i + 1);
                    LITERALS.putIfAbsent(src.substring(i + 1, close), new int[]{i, depth});
                }
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\') j++;
                    j++;
                }
                blanked.append(c);
                for (int k = i + 1; k < j; k++) blanked.append(' ');
                blanked.append(c);
                i = j + 1;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            blanked.append(c);
            i++;
        }
        code = blanked.toString();
    }

    /** True when the quote at {@code quote} opens the argument of a bare {@code literal(}. */
    private static boolean isLiteralCall(String src, int quote) {
        String call = "literal(";
        int at = quote - call.length();
        if (at < 1 || !src.startsWith(call, at)) return false;
        char before = src.charAt(at - 1);
        return before != '.' && !Character.isJavaIdentifierPart(before);
    }

    private static int depthOf(String name) {
        int[] hit = LITERALS.get(name);
        if (hit == null) fail("literal(\"" + name + "\") not found in the /bot registration");
        return hit[1];
    }

    @Test
    void topLevelSubcommandsAreDirectChildrenOfBot() {
        int bot = depthOf("bot");
        int child = bot + 1;
        assertEquals(child, depthOf("spawn"), "spawn must be a child of bot");
        for (String name : new String[]{"debug", "llm", "config", "testgear", "test_chat_message", "recruit", "compass"}) {
            assertEquals(child, depthOf(name), "/bot " + name + " must be a direct child of literal(\"bot\"), a sibling of spawn");
        }
    }

    @Test
    void debugChildrenSitUnderDebug() {
        int debug = depthOf("debug");
        assertEquals(debug + 1, depthOf("status"), "/bot debug status");
        assertEquals(debug + 1, depthOf("nav-hazard"), "/bot debug nav-hazard");
    }

    @Test
    void devHelpersAreGatedOnDebug() {
        for (String name : new String[]{"testgear", "test_chat_message"}) {
            int at = LITERALS.get(name)[0];
            String after = code.substring(at, Math.min(code.length(), at + 200));
            assertTrue(after.contains(".requires(src -> DebugToggleService.verbose())"),
                    "/bot " + name + " must carry .requires(src -> DebugToggleService.verbose())");
        }
    }

    private static Path findSourceRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path candidate = d.resolve("src/main/java");
            if (Files.isRegularFile(candidate.resolve(REGISTRY))) return candidate;
        }
        fail(REGISTRY + " not found from " + dir + "; the command tree scan cannot run");
        return null;
    }
}
