package net.wcfcarolina13.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Guide's topics, loaded from {@code assets/frens/guide/topics.json}.
 *
 * <p>One file feeds the in-game Guide ({@code BotGuideScreen}) and the wiki pages in
 * {@code docs/wiki} ({@link GuideWikiExporter}), so the two can't drift apart. No Minecraft
 * types here, so it is unit-tested directly.
 */
public final class GuideCatalog {

    public static final String RESOURCE_PATH = "assets/frens/guide/topics.json";
    public static final String BOT_PLACEHOLDER = "{bot}";
    public static final String GUIDE_KEY_PLACEHOLDER = "{guideKey}";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<Topic> cached;

    private GuideCatalog() {}

    /**
     * One Guide card.
     *
     * @param adminOnly    hidden from players who are not an operator or the host
     * @param questingOnly hidden while Questing world mode is off
     * @param answer       what a fren says when asked how to use this: one to three plain
     *                     sentences in the fren's own voice, no formatting codes; "" when the
     *                     topic has none
     */
    public record Topic(String id,
                        String category,
                        String title,
                        String summary,
                        List<String> details,
                        String command,
                        String shortcuts,
                        String tags,
                        boolean adminOnly,
                        boolean questingOnly,
                        String answer) {

        public Topic {
            details = List.copyOf(details);
            answer = answer == null ? "" : answer;
        }

        /** A topic without a spoken answer. */
        public Topic(String id, String category, String title, String summary, List<String> details,
                     String command, String shortcuts, String tags, boolean adminOnly, boolean questingOnly) {
            this(id, category, title, summary, details, command, shortcuts, tags, adminOnly, questingOnly, "");
        }

        /** Fills in the placeholders for display in game. */
        public Topic resolve(String bot, String guideKey) {
            List<String> resolvedDetails = new ArrayList<>(details.size());
            for (String line : details) {
                resolvedDetails.add(fill(line, bot, guideKey));
            }
            return new Topic(id, category, fill(title, bot, guideKey), fill(summary, bot, guideKey),
                    resolvedDetails, fill(command, bot, guideKey), fill(shortcuts, bot, guideKey),
                    tags, adminOnly, questingOnly, fill(answer, bot, guideKey));
        }
    }

    /** The bundled topics, in file order (the Guide groups categories in first-seen order). */
    public static List<Topic> topics() {
        List<Topic> local = cached;
        if (local == null) {
            synchronized (GuideCatalog.class) {
                local = cached;
                if (local == null) {
                    local = loadBundled();
                    cached = local;
                }
            }
        }
        return local;
    }

    static List<Topic> loadBundled() {
        try (InputStream in = GuideCatalog.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Missing guide resource " + RESOURCE_PATH);
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE_PATH, e);
        }
    }

    /** Parses and validates a topics file. Throws on a missing field or a duplicate id. */
    public static List<Topic> parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        JsonNode array = root == null ? null : root.get("topics");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("Guide file has no \"topics\" array");
        }
        List<Topic> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : array) {
            String id = requiredText(node, "id", null);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate guide topic id: " + id);
            }
            JsonNode detailsNode = node.get("details");
            if (detailsNode == null || !detailsNode.isArray()) {
                throw new IllegalArgumentException("Guide topic " + id + " has no details array");
            }
            List<String> details = new ArrayList<>();
            for (JsonNode line : detailsNode) {
                details.add(line.asText());
            }
            out.add(new Topic(
                    id,
                    requiredText(node, "category", id),
                    requiredText(node, "title", id),
                    requiredText(node, "summary", id),
                    details,
                    optionalText(node, "command"),
                    optionalText(node, "shortcuts"),
                    optionalText(node, "tags"),
                    node.path("adminOnly").asBoolean(false),
                    node.path("questingOnly").asBoolean(false),
                    optionalText(node, "answer")));
        }
        return List.copyOf(out);
    }

    static String fill(String text, String bot, String guideKey) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace(BOT_PLACEHOLDER, bot).replace(GUIDE_KEY_PLACEHOLDER, guideKey);
    }

    private static String requiredText(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Guide topic " + (id == null ? "?" : id)
                    + " is missing \"" + field + "\"");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
