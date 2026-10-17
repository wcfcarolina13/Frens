package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The finder choice (global {@code PathFinder.ACTIVE} plus per-bot {@code PathFinderOverrides}) is
 * honoured only if callers go through {@code PathFinder}. Up to 1.1.261 the sunset auto-return
 * called {@code BaritoneStylePathFinder} directly and so ignored it. This scan keeps every production
 * reference to a concrete finder ({@code BaritoneStylePathFinder}, {@code UnifiedPathFinder};
 * comments and string literals stripped) inside the PathFinding package.
 * It fails loudly when the source tree cannot be found, so it cannot pass by reading nothing.
 */
class FinderChoiceBypassGuardTest {

    private static final String PACKAGE_DIR = "net/wcfcarolina13/PathFinding/";
    private static final Pattern PUBLIC_ENTRY = Pattern.compile(
            "public\\s+static\\s+[\\w<>.]+\\s+(calculatePath|canReach)\\s*\\(");
    private static final Pattern REFERENCE = Pattern.compile("\\b(?:BaritoneStylePathFinder|UnifiedPathFinder)\\b");

    @Test
    void noProductionFileOutsidePathFindingNamesAConcreteFinder() throws IOException {
        Path root = findMainJavaRoot();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        int insidePackage = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                scanned++;
                String relative = root.relativize(file).toString().replace('\\', '/');
                String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!REFERENCE.matcher(source).find()) {
                    continue;
                }
                if (relative.startsWith(PACKAGE_DIR)) {
                    insidePackage++;
                } else {
                    offenders.add(relative);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        assertTrue(scanned > 100, "suspiciously few sources scanned: " + scanned);
        assertTrue(insidePackage > 0, "the scan no longer sees PathFinder's own references; pattern broken?");
        assertEquals(List.of(), offenders,
                "plan through PathFinder (plan / calculatePath / probePlan / canReach) so the bot's finder choice applies");
    }

    /**
     * Up to 1.1.263 {@code /bot go_to} called a no-bot {@code calculatePath} overload and so ignored
     * the bot's override. 1.1.265 removed that overload and the matching no-bot {@code canReach}; every
     * public {@code calculatePath} / {@code canReach} on {@code PathFinder} must take the bot's UUID.
     */
    @Test
    void everyPublicPathFinderEntryTakesTheBotUuid() throws IOException {
        Path file = findMainJavaRoot().resolve(PACKAGE_DIR + "PathFinder.java");
        String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        Matcher m = PUBLIC_ENTRY.matcher(source);
        int entries = 0;
        List<String> noBot = new ArrayList<>();
        while (m.find()) {
            entries++;
            int open = m.end() - 1;
            int close = source.indexOf(')', open);
            String params = source.substring(open + 1, close);
            if (!params.contains("UUID")) {
                noBot.add(m.group(1) + "(" + params.replaceAll("\\s+", " ").trim() + ")");
            }
        }
        assertTrue(entries >= 2, "found " + entries + " public calculatePath/canReach entries; pattern broken?");
        assertEquals(List.of(), noBot, "a no-bot entry lets callers skip the per-bot finder override");
    }

    @Test
    void stripCommentsDropsCommentsOnly() {
        assertFalse(REFERENCE.matcher(stripComments("/** see {@link BaritoneStylePathFinder} */ a();")).find());
        assertFalse(REFERENCE.matcher(stripComments("a(); // BaritoneStylePathFinder.calculatePath")).find());
        assertTrue(REFERENCE.matcher(stripComments("x = BaritoneStylePathFinder.calculatePath(a, b, w);")).find());
        assertFalse(REFERENCE.matcher("BaritoneStylePathFinderTest").find());
        assertTrue(REFERENCE.matcher(stripComments("p = UnifiedPathFinder.search(ctx);")).find());
        // A "/*" inside a string must not open a comment that swallows the real reference after it.
        assertTrue(REFERENCE.matcher(stripComments(
                "String g = \"src/*.java\"; x = BaritoneStylePathFinder.calculatePath(a, b, w); String h = \"*/\";")).find());
        assertTrue(REFERENCE.matcher(stripComments("String u = \"http://x\"; UnifiedPathFinder.search(c);")).find());
        assertTrue(REFERENCE.matcher(stripComments("char q = '\"'; UnifiedPathFinder.search(c); // \"")).find());
        assertTrue(REFERENCE.matcher(stripComments("String t = \"\"\"\n /* \n\"\"\"; UnifiedPathFinder.search(c);")).find());
        assertFalse(REFERENCE.matcher(stripComments("log(\"UnifiedPathFinder failed\");")).find());
    }

    /**
     * Drops comments and the contents of string, text-block and char literals with a small state
     * machine, so a {@code /*} or {@code //} inside a literal never starts a comment.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && source.charAt(end - 1) == '\\') {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Path findMainJavaRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            Path candidate = d.resolve("src/main/java");
            if (Files.isDirectory(candidate.resolve("net/wcfcarolina13"))) {
                return candidate;
            }
        }
        fail("src/main/java/net/wcfcarolina13 not found from " + dir + "; the guard scan cannot run");
        return null;
    }
}
