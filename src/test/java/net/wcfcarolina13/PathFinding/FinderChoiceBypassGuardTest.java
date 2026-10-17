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
 * reference to {@code BaritoneStylePathFinder} (comments stripped) inside the PathFinding package.
 * It fails loudly when the source tree cannot be found, so it cannot pass by reading nothing.
 */
class FinderChoiceBypassGuardTest {

    private static final String PACKAGE_DIR = "net/wcfcarolina13/PathFinding/";
    private static final Pattern REFERENCE = Pattern.compile("\\bBaritoneStylePathFinder\\b");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|[^:\"])//.*$");

    @Test
    void noProductionFileOutsidePathFindingNamesBaritoneStylePathFinder() throws IOException {
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

    @Test
    void stripCommentsDropsCommentsOnly() {
        assertFalse(REFERENCE.matcher(stripComments("/** see {@link BaritoneStylePathFinder} */ a();")).find());
        assertFalse(REFERENCE.matcher(stripComments("a(); // BaritoneStylePathFinder.calculatePath")).find());
        assertTrue(REFERENCE.matcher(stripComments("x = BaritoneStylePathFinder.calculatePath(a, b, w);")).find());
        assertFalse(REFERENCE.matcher("BaritoneStylePathFinderTest").find());
    }

    private static String stripComments(String source) {
        String noBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlocks).replaceAll("$1");
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
