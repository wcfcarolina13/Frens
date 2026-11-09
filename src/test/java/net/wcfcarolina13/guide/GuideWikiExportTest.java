package net.wcfcarolina13.guide;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GuideWikiExportTest {

    private static final Path WIKI = Path.of("docs/wiki");

    private static String normalise(String s) {
        return s.replace("\r\n", "\n").replaceAll("[ \t]+\n", "\n").strip();
    }

    /** The committed wiki pages must be exactly what the Guide data produces. */
    @Test
    void committedWikiMatchesTheGuide() throws IOException {
        Map<String, String> expected = GuideWikiExporter.render(GuideCatalog.loadBundled());
        for (Map.Entry<String, String> page : expected.entrySet()) {
            Path file = WIKI.resolve(page.getKey());
            assertTrue(Files.exists(file), "docs/wiki/" + page.getKey()
                    + " is missing. Run ./gradlew wikiExport and commit docs/wiki.");
            assertEquals(normalise(page.getValue()), normalise(Files.readString(file, StandardCharsets.UTF_8)),
                    "docs/wiki/" + page.getKey() + " is out of date. Run ./gradlew wikiExport and commit docs/wiki.");
        }
        try (DirectoryStream<Path> pages = Files.newDirectoryStream(WIKI, GuideWikiExporter.PAGE_PREFIX + "*.md")) {
            for (Path p : pages) {
                assertTrue(expected.containsKey(p.getFileName().toString()),
                        "docs/wiki/" + p.getFileName() + " has no Guide category. Run ./gradlew wikiExport.");
            }
        }
    }

    @Test
    void stripsFormattingCodesFillsPlaceholdersAndNestsBullets() {
        GuideCatalog.Topic t = new GuideCatalog.Topic("x", "Core Actions", "§eGo§r", "Use {guideKey}",
                List.of("Top", "  • §fsub§r <count>"), "bot skill fish <count> {bot}",
                "UI-only", "", true, false);
        String page = GuideWikiExporter.render(List.of(t)).get("Guide-Core-Actions.md");
        assertNotNull(page);
        assertTrue(page.contains("## Go\n"), page);
        assertTrue(page.contains("Use ]"), page);
        assertTrue(page.contains("- Top\n  - sub &lt;count&gt;\n"), page);
        assertTrue(page.contains("`/bot skill fish <count> <bot>`"), page);
        assertTrue(page.contains("*Operators and the host only.*"), page);
        assertFalse(page.contains("§"), page);
    }

    @Test
    void anchorsFollowGithubRulesAndNumberRepeats() {
        Map<String, Integer> seen = new HashMap<>();
        assertEquals("bot-roster-spawn-bots", GuideWikiExporter.uniqueAnchor("Bot Roster (Spawn Bots…)", seen));
        assertEquals("stop", GuideWikiExporter.uniqueAnchor("Stop", seen));
        assertEquals("stop-1", GuideWikiExporter.uniqueAnchor("Stop", seen));
        assertEquals("Guide-Core-Actions.md", GuideWikiExporter.pageFileName("Core Actions"));
    }
}
