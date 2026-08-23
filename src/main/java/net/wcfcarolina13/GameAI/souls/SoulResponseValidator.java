package net.wcfcarolina13.GameAI.souls;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and sanitizes raw provider text before it can be spoken by a bot.
 *
 * <p>This is the last checkpoint between an LLM provider's raw output and anything that reaches
 * a player or the mod's dialogue systems. It is deliberately narrow: it strips presentation-only
 * noise (hidden reasoning blocks, a redundant speaker-name prefix, Minecraft legacy formatting
 * codes, stray control characters) and rejects output that looks unsafe or malformed. It never
 * inspects the cleaned text for commands or actions — {@link ValidationResult#text()} is plain
 * dialogue only, and any rejection reason is inert data, never something that gets executed.
 */
public final class SoulResponseValidator {

    /** Cleaned output longer than this many characters is rejected rather than truncated. */
    static final int MAX_TEXT_LENGTH = 1200;

    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>.*?</think>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANALYSIS_BLOCK =
            Pattern.compile("<analysis>.*?</analysis>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Matches an opening <think>/<analysis> tag that survived block removal above -- i.e. one
    // with no matching close tag. A provider truncated mid-reasoning (e.g. hitting num_predict)
    // leaves exactly this shape, and everything from the open tag onward is unreviewed reasoning
    // that must never reach dialogue.
    private static final Pattern UNCLOSED_REASONING_TAG =
            Pattern.compile("<think>|<analysis>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_SIGN_CODE = Pattern.compile("§.");
    // A lone trailing section sign with nothing after it to pair with (e.g. truncated output).
    private static final Pattern TRAILING_LONE_SECTION_SIGN = Pattern.compile("§$");
    // Only a fence that opens at the start of a line reads as a tool/JSON payload; a triple
    // backtick mid-sentence is ordinary prose and must not be rejected.
    private static final Pattern FENCE_AT_LINE_START = Pattern.compile("(?m)^\\s*```");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{4,}");

    public SoulResponseValidator() {
        // No collaborators -- validation is a pure function of its arguments.
    }

    /**
     * Cleans and validates raw provider text for a single soul turn.
     *
     * @param raw the unmodified text returned by the provider
     * @param botDisplayName the speaking bot's display name, used only to strip a redundant
     *     leading {@code "Name:"} label the provider may have echoed back
     * @return an accepted result carrying sanitized dialogue text, or a rejected result carrying
     *     {@link SoulTypes.FailureCode#MALFORMED} and a human-readable reason
     */
    public ValidationResult validate(String raw, String botDisplayName) {
        if (raw == null || raw.isEmpty()) {
            return reject("blank output");
        }
        if (raw.indexOf('\u0000') >= 0) {
            return reject("contains a NUL character");
        }

        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = THINK_BLOCK.matcher(text).replaceAll("");
        text = ANALYSIS_BLOCK.matcher(text).replaceAll("");
        text = truncateAtUnclosedReasoningTag(text);

        if (FENCE_AT_LINE_START.matcher(text).find()) {
            return reject("contains a fenced tool/JSON payload");
        }

        text = text.strip();
        text = stripSpeakerPrefix(text, botDisplayName);
        text = SECTION_SIGN_CODE.matcher(text).replaceAll("");
        text = TRAILING_LONE_SECTION_SIGN.matcher(text).replaceAll("");
        text = stripControlCharacters(text);
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n\n");
        text = text.strip();

        if (text.isEmpty()) {
            return reject("blank output");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return reject("cleaned output exceeds " + MAX_TEXT_LENGTH + " characters");
        }

        return accept(text);
    }

    private static String truncateAtUnclosedReasoningTag(String text) {
        Matcher unclosed = UNCLOSED_REASONING_TAG.matcher(text);
        return unclosed.find() ? text.substring(0, unclosed.start()) : text;
    }

    private static String stripSpeakerPrefix(String text, String botDisplayName) {
        if (botDisplayName == null || botDisplayName.isBlank()) {
            return text;
        }
        String prefix = botDisplayName + ":";
        if (text.startsWith(prefix)) {
            return text.substring(prefix.length()).stripLeading();
        }
        return text;
    }

    private static String stripControlCharacters(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                cleaned.append(c);
            } else if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static ValidationResult accept(String text) {
        return new ValidationResult(true, text, null, "");
    }

    private static ValidationResult reject(String reason) {
        return new ValidationResult(false, "", SoulTypes.FailureCode.MALFORMED, reason);
    }

    /**
     * Outcome of {@link #validate(String, String)}. {@code text()} is sanitized dialogue only —
     * consumers must never treat it, or {@code reason()}, as a command to parse or execute.
     */
    public record ValidationResult(boolean accepted, String text,
                                    SoulTypes.FailureCode failureCode, String reason) {
        public ValidationResult {
            text = text == null ? "" : text;
            reason = reason == null ? "" : reason;
        }
    }
}
