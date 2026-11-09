package net.wcfcarolina13.guide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the Guide topics as Markdown wiki pages: {@code Guide.md} (index) and one
 * {@code Guide-<Category>.md} page per category. Run with {@code ./gradlew wikiExport};
 * {@code GuideWikiExportTest} fails when the committed pages don't match.
 */
public final class GuideWikiExporter {

    public static final String INDEX_FILE = "Guide.md";
    public static final String PAGE_PREFIX = "Guide-";

    static final String HEADER = "<!-- Generated from src/main/resources/" + GuideCatalog.RESOURCE_PATH
            + " by ./gradlew wikiExport. Edit that file, not this page. -->\n\n";

    private static final Pattern FORMATTING_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private GuideWikiExporter() {}

    /** File name to page content, in a stable order. Pure: no file access. */
    public static Map<String, String> render(List<GuideCatalog.Topic> topics) {
        LinkedHashMap<String, List<GuideCatalog.Topic>> byCategory = new LinkedHashMap<>();
        for (GuideCatalog.Topic topic : topics) {
            byCategory.computeIfAbsent(topic.category(), k -> new ArrayList<>()).add(topic);
        }

        LinkedHashMap<String, String> pages = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder(HEADER);
        index.append("# In-game Guide\n\n");
        index.append("Every card from the Guide screen in game (press ] by default). ")
                .append("`<bot>` stands for your fren's name.\n");

        for (Map.Entry<String, List<GuideCatalog.Topic>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            String file = pageFileName(category);
            Map<String, Integer> anchorsSeen = new HashMap<>();

            StringBuilder page = new StringBuilder(HEADER);
            page.append("# ").append(text(category)).append("\n\n");
            page.append("[All Guide topics](").append(INDEX_FILE).append(")\n");

            index.append("\n## [").append(text(category)).append("](").append(file).append(")\n\n");
            for (GuideCatalog.Topic topic : entry.getValue()) {
                String title = plain(topic.title());
                String anchor = uniqueAnchor(title, anchorsSeen);
                index.append("- [").append(escape(title)).append("](").append(file).append('#').append(anchor)
                        .append("): ").append(text(topic.summary())).append('\n');
                appendTopic(page, topic);
            }
            pages.put(file, page.toString());
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put(INDEX_FILE, index.toString());
        out.putAll(pages);
        return out;
    }

    private static void appendTopic(StringBuilder page, GuideCatalog.Topic topic) {
        page.append("\n## ").append(text(topic.title())).append("\n\n");
        page.append(text(topic.summary())).append("\n\n");
        if (topic.adminOnly()) {
            page.append("*Operators and the host only.*\n\n");
        }
        if (topic.questingOnly()) {
            page.append("*Shown only while Questing world mode is on.*\n\n");
        }
        for (String line : topic.details()) {
            String stripped = line.stripLeading();
            boolean nested = line.length() > stripped.length();
            if (stripped.startsWith("•")) {
                stripped = stripped.substring(1).stripLeading();
                nested = true;
            }
            page.append(nested ? "  - " : "- ").append(text(stripped)).append('\n');
        }
        String command = plain(topic.command()).strip();
        if (!command.isEmpty()) {
            page.append("\n**Command:** ").append(commandText(command)).append('\n');
        }
        String shortcuts = plain(topic.shortcuts()).strip();
        if (!shortcuts.isEmpty()) {
            page.append("\n**Where:** ").append(escape(shortcuts)).append('\n');
        }
    }

    private static String commandText(String command) {
        if (command.startsWith("bot ")) {
            return "`/" + command + "`";
        }
        if (command.startsWith("/bot ")) {
            return "`" + command + "`";
        }
        return escape(command);
    }

    /** Category page file name, e.g. "Core Actions" becomes Guide-Core-Actions.md. */
    static String pageFileName(String category) {
        String slug = plain(category).strip().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        return PAGE_PREFIX + slug + ".md";
    }

    /** GitHub's heading anchor: lower case, punctuation dropped, spaces to hyphens, -1/-2 for repeats. */
    static String uniqueAnchor(String heading, Map<String, Integer> seen) {
        String base = heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} _-]", "")
                .replace(' ', '-');
        int count = seen.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "-" + (count - 1);
    }

    /** Drops Minecraft formatting codes and fills placeholders with wiki wording. */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String filled = GuideCatalog.fill(text, "<bot>", "]");
        return FORMATTING_CODE.matcher(filled).replaceAll("");
    }

    private static String text(String raw) {
        return escape(plain(raw));
    }

    /** Escapes what GitHub Markdown would otherwise read as HTML. */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Writes the pages into {@code dir} and removes Guide pages for categories that no longer exist. */
    public static void write(Path dir, Map<String, String> pages) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(dir, PAGE_PREFIX + "*.md")) {
            for (Path old : existing) {
                if (!pages.containsKey(old.getFileName().toString())) {
                    Files.delete(old);
                }
            }
        }
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Files.writeString(dir.resolve(page.getKey()), page.getValue(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "docs/wiki");
        Map<String, String> pages = render(GuideCatalog.loadBundled());
        write(dir, pages);
        System.out.println("Wrote " + pages.size() + " wiki pages to " + dir.toAbsolutePath());
    }
}
