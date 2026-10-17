package net.wcfcarolina13;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Source-reading guard for the project rule: no em dashes and no semicolons in player-facing
 * text (1.1.260 sweep). Scans every string literal in src/main/java, skipping comments and
 * log calls (including their continuation lines), and fails on a literal holding an em dash
 * (literal or written as the unicode escape) or a semicolon unless the file or line is on the
 * allowlist below. The allowlist holds text that is not shown to players: LLM prompt material,
 * separators, hashes, key names, regexes and class descriptors.
 */
class PlayerTextPunctuationGuardTest {

    private static final Path ROOT = Paths.get("src/main/java");

    /** Whole files whose literals are LLM prompt or knowledge text, or mixin descriptors. */
    private static final List<String> ALLOWED_FILES = List.of(
            "GameAI/souls/SoulGroupPromptAssembler.java",
            "GameAI/souls/SoulPromptAssembler.java",
            "GameAI/souls/SoulBlockKnowledge.java",
            "GameAI/souls/SoulMemoryDigestService.java",
            "GameAI/souls/SoulKnowledgeRetriever.java",
            "GameAI/souls/SoulPlayerActivity.java",
            "GameAI/souls/SoulBanterSeed.java",
            "mixin/");

    /** Single lines that are not player text: key names, descriptors, hashes, regexes, separators. */
    private static final List<String> ALLOWED_LINES = List.of(
            "lockKey = \";\"",
            "replace('.', '/') + \";\"",
            "+ \";\").getBytes(",
            "\"^[,:;-]+\"",
            "FP_SEPARATOR = \";\"",
            "\";IN:\"",
            ".replace(\"—\", \"-\")",
            "\"On it [-—] .+\\\\.\"",
            "\"empty plan has no end cell; use the search start\"");

    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern LOG_CALL = Pattern.compile(
            "LOGGER\\.|logger\\.|LOG\\.|\\.(?:info|warn|debug|error|trace)\\(|debugChest\\(|logStageWarn\\(|System\\.(?:out|err)");

    @Test
    void playerTextHasNoEmDashOrSemicolon() throws IOException {
        assumeTrue(Files.isDirectory(ROOT), "run from the project directory");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = ROOT.relativize(file).toString().replace('\\', '/');
                rel = rel.startsWith("net/wcfcarolina13/") ? rel.substring("net/wcfcarolina13/".length()) : rel;
                if (allowedFile(rel)) {
                    continue;
                }
                scan(rel, Files.readAllLines(file, StandardCharsets.UTF_8), hits);
            }
        }
        assertTrue(hits.isEmpty(), "player text with an em dash or semicolon:\n" + String.join("\n", hits));
    }

    private static boolean allowedFile(String rel) {
        for (String allowed : ALLOWED_FILES) {
            if (rel.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static void scan(String rel, List<String> lines, List<String> hits) {
        boolean inBlockComment = false;
        boolean inLogCall = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (inBlockComment) {
                if (t.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (t.startsWith("/*")) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            if (inLogCall) {
                inLogCall = !t.endsWith(";");
                continue;
            }
            if (LOG_CALL.matcher(line).find()) {
                inLogCall = !t.endsWith(";");
                continue;
            }
            Matcher m = LITERAL.matcher(line);
            while (m.find()) {
                String lit = m.group();
                if ((lit.contains("—") || lit.contains("\\u2014") || lit.contains(";")) && !allowedLine(line)) {
                    hits.add(rel + ":" + (i + 1) + ": " + t);
                    break;
                }
            }
        }
    }

    private static boolean allowedLine(String line) {
        for (String allowed : ALLOWED_LINES) {
            if (line.contains(allowed)) {
                return true;
            }
        }
        return false;
    }
}
