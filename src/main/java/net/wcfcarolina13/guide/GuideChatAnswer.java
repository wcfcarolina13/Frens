package net.wcfcarolina13.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The words a fren says when it answers a how-to question from the Guide, and the Guide text a
 * soul prompt is grounded in. Pure: the chat components and the click link are built by
 * {@code GuideChatAnswerService}.
 */
public final class GuideChatAnswer {

    /** What a server can say in place of the client's Guide hotkey, which it can't see. */
    public static final String GUIDE_KEY_TEXT = "the Guide key";
    /** The players-only command behind the [Open in Guide] link. */
    public static final String OPEN_COMMAND_PREFIX = "/frens guide ";
    /** Prompt budget for the Guide block in a soul prompt (about 300 tokens). */
    public static final int MAX_PROMPT_CHARS = 1100;

    private static final Pattern FORMAT_CODE = Pattern.compile("§.");
    private static final Pattern BOT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private GuideChatAnswer() {
    }

    /**
     * One chat answer.
     *
     * @param spoken      what the fren says
     * @param commandHint "/bot ..." or another way in, or "" when the card has no command
     * @param alsoTopicId a close runner-up to offer as a second link, or ""
     */
    public record Answer(String spoken, String commandHint, String topicId, String topicTitle,
                         String alsoTopicId, String alsoTopicTitle) {}

    /** The answer for the best match, or empty when there is none. */
    public static Optional<Answer> build(List<GuideQuestionMatcher.Match> matches, String botName) {
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        GuideQuestionMatcher.Match best = matches.get(0);
        GuideCatalog.Topic topic = best.topic().resolve(safeBot(botName), GUIDE_KEY_TEXT);
        String alsoId = "";
        String alsoTitle = "";
        if (matches.size() > 1 && GuideQuestionMatcher.runnerUpWorthMentioning(best, matches.get(1))) {
            alsoId = matches.get(1).topic().id();
            alsoTitle = clean(matches.get(1).topic().resolve(safeBot(botName), GUIDE_KEY_TEXT).title());
        }
        return Optional.of(new Answer(spokenText(topic), commandHint(topic.command()), topic.id(),
                clean(topic.title()), alsoId, alsoTitle));
    }

    /**
     * What the fren says for {@code topic} (placeholders already filled): the card's spoken
     * {@code answer}, or its title and summary when the card has none.
     */
    static String spokenText(GuideCatalog.Topic topic) {
        String answer = clean(topic.answer());
        if (!answer.isEmpty()) {
            return answer;
        }
        return clean(topic.title()) + ": " + clean(topic.summary());
    }

    /**
     * A way in for the chat line: a {@code bot ...} command gets its slash, a slash command or a
     * chat phrase is kept, and "UI-only" style entries are dropped (the Guide link covers them).
     */
    static String commandHint(String command) {
        String c = clean(command);
        if (c.isEmpty()) {
            return "";
        }
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ui")) {
            return "";
        }
        if (lower.startsWith("bot ")) {
            return "/" + c;
        }
        return c;
    }

    /** The command an [Open in Guide] link runs; the bot name fills {bot} on the card. */
    public static String openCommand(String topicId, String botName) {
        String bot = botName == null ? "" : botName.trim();
        return OPEN_COMMAND_PREFIX + topicId + (BOT_NAME.matcher(bot).matches() ? " " + bot : "");
    }

    /**
     * The Guide block of a soul prompt: the best topic in full (spoken answer, summary, command,
     * details, where to find it) as far as {@code maxChars} allows, then one line per other match. Whole lines
     * only, so a cut never leaves half a sentence. Empty for no matches.
     */
    public static List<String> promptLines(List<GuideQuestionMatcher.Match> matches, String botName,
                                           int maxChars) {
        if (matches == null || matches.isEmpty() || maxChars <= 0) {
            return List.of();
        }
        String bot = safeBot(botName);
        List<String> lines = new ArrayList<>();
        int[] used = {0};
        GuideCatalog.Topic first = matches.get(0).topic().resolve(bot, GUIDE_KEY_TEXT);
        if (!add(lines, used, maxChars, "Topic: " + clean(first.title()))) {
            return List.copyOf(lines);
        }
        String answer = clean(first.answer());
        if (!answer.isEmpty() && !add(lines, used, maxChars, "Short answer (in your voice): " + answer)) {
            return List.copyOf(lines);
        }
        if (!add(lines, used, maxChars, clean(first.summary()))) {
            return List.copyOf(lines);
        }
        String hint = commandHint(first.command());
        if (!hint.isEmpty() && !add(lines, used, maxChars, "Command: " + hint)) {
            return List.copyOf(lines);
        }
        for (String detail : first.details()) {
            String d = clean(detail);
            if (!d.isEmpty() && !add(lines, used, maxChars, "- " + d)) {
                return List.copyOf(lines);
            }
        }
        String shortcuts = clean(first.shortcuts());
        if (!shortcuts.isEmpty() && !add(lines, used, maxChars, "Where: " + shortcuts)) {
            return List.copyOf(lines);
        }
        for (int i = 1; i < matches.size(); i++) {
            GuideCatalog.Topic other = matches.get(i).topic().resolve(bot, GUIDE_KEY_TEXT);
            if (!add(lines, used, maxChars, "Related topic: " + clean(other.title()) + ": "
                    + clean(other.summary()))) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    private static boolean add(List<String> lines, int[] used, int maxChars, String line) {
        int cost = line.length() + 1;
        if (used[0] + cost > maxChars) {
            return false;
        }
        lines.add(line);
        used[0] += cost;
        return true;
    }

    /** Strips Minecraft format codes and collapses whitespace. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return SPACES.matcher(FORMAT_CODE.matcher(text).replaceAll("")).replaceAll(" ").trim();
    }

    private static String safeBot(String botName) {
        return botName == null || botName.isBlank() ? "<bot>" : botName.trim();
    }
}
