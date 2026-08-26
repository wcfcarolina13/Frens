package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates a group-scene orchestration response: plain speaker-tagged lines
 * ({@code Name: dialogue}), one per line, speakers drawn from the scene's roster.
 *
 * <p>Chosen over structured JSON deliberately — small local models (3B/8B) emit tagged dialogue
 * lines far more reliably than valid JSON. The parse is drop-based, never repair-based: a line
 * with an unknown speaker, no speaker tag, or an empty body is discarded; caps
 * ({@link SoulGroupTypes#MAX_LINES_PER_BOT}, {@link SoulGroupTypes#MAX_SCENE_LINES},
 * {@link SoulGroupTypes#MAX_LINE_CHARS}) trim rather than reject. Only a response with zero
 * surviving lines fails, as {@link SoulTypes.FailureCode#MALFORMED}. Like
 * {@link SoulResponseValidator}, output text is inert dialogue only.
 */
public final class SoulGroupResponseValidator {

    /** A speaker tag longer than this many raw chars is treated as narration, not a tag. */
    private static final int MAX_SPEAKER_TAG_CHARS = 40;
    /** Sentence-boundary truncation only applies past this many chars; earlier ends hard-cut. */
    private static final int MIN_TRUNCATION_SENTENCE_CHARS = 80;

    private static final Pattern SECTION_SIGN_CODE = Pattern.compile("§.");

    /** Outcome of {@link #parse}. {@code lines()} is roster-verified, capped dialogue. */
    public record SceneParse(boolean accepted, List<SoulGroupTypes.SceneLine> lines,
                              SoulTypes.FailureCode failureCode, String reason) {
        public SceneParse {
            lines = lines == null ? List.of() : List.copyOf(lines);
            reason = reason == null ? "" : reason;
        }
    }

    public SoulGroupResponseValidator() {
        // No collaborators — parsing is a pure function of its arguments.
    }

    /**
     * Parses raw provider text into verified scene lines.
     *
     * @param raw the unmodified provider response
     * @param rosterDisplayNames display names index-aligned with the scene turn's roster; a
     *     line's speaker must normalize-match one of these or the line is dropped
     */
    public SceneParse parse(String raw, List<String> rosterDisplayNames) {
        if (raw == null || raw.isBlank() || rosterDisplayNames == null || rosterDisplayNames.isEmpty()) {
            return reject("blank output");
        }
        if (raw.indexOf('\u0000') >= 0) {
            return reject("contains a NUL character");
        }
        String text = SoulResponseValidator.sanitizeBase(raw);

        List<String> normRoster = new ArrayList<>(rosterDisplayNames.size());
        for (String name : rosterDisplayNames) {
            normRoster.add(normalize(name));
        }

        int[] perBot = new int[rosterDisplayNames.size()];
        List<SoulGroupTypes.SceneLine> lines = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            if (lines.size() >= SoulGroupTypes.MAX_SCENE_LINES) {
                break;
            }
            String line = cleanLine(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > MAX_SPEAKER_TAG_CHARS) {
                continue; // narration or untagged prose
            }
            String speaker = normalize(line.substring(0, colon));
            String body = line.substring(colon + 1).strip();
            if (speaker.isEmpty() || body.isEmpty()) {
                continue;
            }
            int idx = normRoster.indexOf(speaker);
            if (idx < 0) {
                continue; // unknown speaker — dropped, never repaired
            }
            if (perBot[idx] >= SoulGroupTypes.MAX_LINES_PER_BOT) {
                continue;
            }
            perBot[idx]++;
            lines.add(new SoulGroupTypes.SceneLine(idx, truncateLine(body)));
        }

        if (lines.isEmpty()) {
            return reject("no roster-tagged dialogue lines");
        }
        return new SceneParse(true, lines, null, "");
    }

    /** Strips Minecraft formatting codes and control characters from one candidate line. */
    private static String cleanLine(String line) {
        String cleaned = SECTION_SIGN_CODE.matcher(line).replaceAll("");
        StringBuilder out = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }

    /** Same normalization contract as {@code ChatAddressing.normalizeToken}, local to keep this
     *  package free of top-level mod classes: strip non-alphanumerics, lowercase. */
    private static String normalize(String token) {
        if (token == null) {
            return "";
        }
        return token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Caps one line at {@link SoulGroupTypes#MAX_LINE_CHARS}, preferring to cut at the last
     * sentence end past {@value #MIN_TRUNCATION_SENTENCE_CHARS} chars so the spoken line still
     * ends naturally; hard cut when no such boundary exists.
     */
    private static String truncateLine(String body) {
        if (body.length() <= SoulGroupTypes.MAX_LINE_CHARS) {
            return body;
        }
        String window = body.substring(0, SoulGroupTypes.MAX_LINE_CHARS);
        int cut = -1;
        for (int i = window.length() - 1; i >= MIN_TRUNCATION_SENTENCE_CHARS; i--) {
            char c = window.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                cut = i + 1;
                break;
            }
        }
        return (cut > 0 ? window.substring(0, cut) : window).strip();
    }

    private static SceneParse reject(String reason) {
        return new SceneParse(false, List.of(), SoulTypes.FailureCode.MALFORMED, reason);
    }
}
