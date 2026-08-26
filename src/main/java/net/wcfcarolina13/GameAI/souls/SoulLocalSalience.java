package net.wcfcarolina13.GameAI.souls;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic "is this overheard line worth answering" rule (local-chat spec §5.1). Pure — no
 * Minecraft types, no state — and the whole reason the ambient surface is affordable: it runs
 * before any provider call, on every unaddressed line, and rejects nearly all of them.
 *
 * <p>The model never decides whether to speak; this class does. Weights and the threshold are
 * constants precisely so field-test logs can retune them without touching logic.
 */
final class SoulLocalSalience {

    /** Minimum score for a line to earn a reaction. */
    static final int THRESHOLD = 4;

    static final int WEIGHT_BOT_MENTION = 3;
    static final int WEIGHT_STATED_INTENT = 2;
    static final int WEIGHT_TOPIC_OVERLAP = 2;
    static final int WEIGHT_QUESTION = 2;
    static final int WEIGHT_SUBSTANTIAL = 1;
    static final int PENALTY_NUMERIC = -2;

    private static final int MIN_WORDS = 3;
    private static final int MIN_CHARS = 12;
    private static final int SUBSTANTIAL_WORDS = 6;

    private static final Set<String> INTENT_MARKERS = Set.of(
            "i'm going", "im going", "going to", "let's", "lets ", "we should",
            "i need to", "i'm gonna", "im gonna", "heading to", "planning to");

    private SoulLocalSalience() {
    }

    /**
     * Cheapest possible reject, needing no bot context: blank, too short, or a repeat of the
     * player's previous line. Runs before the veto chain and before any scoring.
     */
    static boolean hardReject(String line, String previousLine) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String trimmed = line.trim();
        if (trimmed.length() < MIN_CHARS) {
            return true;
        }
        if (trimmed.split("\\s+").length < MIN_WORDS) {
            return true;
        }
        return previousLine != null && trimmed.equalsIgnoreCase(previousLine.trim());
    }

    /**
     * Additive salience of {@code line} with respect to one candidate bot. Never negative.
     *
     * @param botName the candidate bot's display name
     * @param activeTask that bot's current task ("skill:fishing", or "" when idle)
     * @param recentEventSubject a one-word subject from its newest journal event ("creeper"), or
     *     "" — <b>always</b> "" today: the director passes it empty because its only source is an
     *     asynchronous journal read, which does not belong on the chat hot path (spec §5.1). Kept
     *     as the seam a future implementation would fill.
     */
    static int score(String line, String botName, String activeTask, String recentEventSubject) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        String lower = line.trim().toLowerCase(Locale.ROOT);
        int score = 0;

        if (mentionsBotNotLeading(lower, botName)) {
            score += WEIGHT_BOT_MENTION;
        }
        for (String marker : INTENT_MARKERS) {
            if (lower.contains(marker)) {
                score += WEIGHT_STATED_INTENT;
                break;
            }
        }
        if (overlaps(lower, activeTask) || overlaps(lower, recentEventSubject)) {
            score += WEIGHT_TOPIC_OVERLAP;
        }
        if (lower.endsWith("?")) {
            score += WEIGHT_QUESTION;
        }
        String[] words = lower.split("\\s+");
        if (words.length >= SUBSTANTIAL_WORDS) {
            score += WEIGHT_SUBSTANTIAL;
        }
        if (mostlyNumeric(words)) {
            score += PENALTY_NUMERIC;
        }
        return Math.max(0, score);
    }

    /**
     * True when the bot's name appears as a whole word somewhere other than the start of the
     * line. A leading name is an address ("Jake, come here") which the DM router already
     * consumed, so it must score zero here — otherwise every address would double as an
     * overheard mention.
     *
     * <p>Word-boundary matched, not substring matched: a short name ("Al", "Sam") otherwise
     * matches inside ordinary words ("also", "same") and adds +3, which together with the
     * six-word +1 reaches the threshold of 4 on a line that mentions nobody.
     */
    private static boolean mentionsBotNotLeading(String lowerLine, String botName) {
        if (botName == null || botName.isBlank()) {
            return false;
        }
        String needle = botName.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(needle) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(lowerLine);
        // Only the FIRST occurrence decides, matching the previous indexOf semantics.
        return matcher.find() && matcher.start() > 0;
    }

    /** Overlap on the meaningful tail of a task id ("skill:fishing" -> "fishing"). */
    private static boolean overlaps(String lowerLine, String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < normalized.length()) {
            normalized = normalized.substring(colon + 1);
        }
        return normalized.length() >= 3 && lowerLine.contains(normalized);
    }

    private static boolean mostlyNumeric(String[] words) {
        if (words.length == 0) {
            return false;
        }
        int numeric = 0;
        for (String word : words) {
            if (word.matches("-?\\d+")) {
                numeric++;
            }
        }
        return numeric * 2 >= words.length;
    }
}
