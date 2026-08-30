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

    /** Tag words that are model scaffolding, never speaker names — a solo roster's wrong-tag
     *  repair must not turn "Note: …" narration into spoken dialogue (2026-08-29 review #4). */
    private static final java.util.Set<String> META_TAGS = java.util.Set.of(
            "note", "notes", "scene", "narrator", "narration", "system", "user", "assistant",
            "output", "response", "context", "instruction", "instructions", "player", "owner");
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
        return parse(raw, rosterDisplayNames, SoulGroupTypes.MAX_SCENE_LINES);
    }

    /** @param maxSceneLines total-line cap for this scene kind (player 6, banter 4). */
    public SceneParse parse(String raw, List<String> rosterDisplayNames, int maxSceneLines) {
        return parse(raw, rosterDisplayNames, maxSceneLines, "");
    }

    /**
     * True when a speaker tag names the scene owner: exact, or a ≥3-char abbreviation the model
     * is prone to ("Roti" for "RotiWokeman"). Such a line was written FOR the player.
     */
    static boolean isOwnerTag(String normalizedSpeaker, String normalizedOwner) {
        if (normalizedOwner == null || normalizedOwner.isEmpty()
                || normalizedSpeaker == null || normalizedSpeaker.isEmpty()) {
            return false;
        }
        return normalizedSpeaker.equals(normalizedOwner)
                || (normalizedSpeaker.length() >= 3 && normalizedOwner.startsWith(normalizedSpeaker));
    }

    /**
     * @param ownerDisplayName the scene owner's (player's) display name. A line tagged with it —
     *     or an abbreviation of it — is one the model wrote for the player, which the contract
     *     forbids; it is dropped under every roster size. 2026-08-29 field fix: the solo
     *     wrong-tag repair used to strip that tag and hand the player's answer to the lone bot,
     *     so Jake visibly asked a question and then answered himself.
     */
    public SceneParse parse(String raw, List<String> rosterDisplayNames, int maxSceneLines,
                            String ownerDisplayName) {
        return parse(raw, rosterDisplayNames, maxSceneLines, ownerDisplayName, false);
    }

    /**
     * @param endAtOwnerAddress for ambient scenes (banter/work/local): the first line that
     *     addresses the owner by name ends the scene — everything after it is dropped. Field
     *     2026-08-29: "Bob: Morning, Roti. How's the sleep skill going?" was followed by Jake
     *     answering on Roti's behalf. PLAYER scenes (replies to the owner) pass {@code false}.
     */
    public SceneParse parse(String raw, List<String> rosterDisplayNames, int maxSceneLines,
                            String ownerDisplayName, boolean endAtOwnerAddress) {
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
        String normOwner = normalize(ownerDisplayName == null ? "" : ownerDisplayName);

        // Solo-roster leniency (2026-08-29 field fix): with exactly ONE possible speaker the
        // "Name: line" grammar protects nothing — the 3B model answered a solo banter directive
        // with untagged prose and every line was dropped (outcome=failed:MALFORMED). For a
        // roster of one, untagged lines attribute to that speaker, and a line tagged with a
        // WRONG name (typically the shared profile's name rather than the bot's display name)
        // has its tag stripped instead of being rejected. Multi-speaker grammar is unchanged:
        // an untagged line is genuinely ambiguous there and still drops.
        boolean soloRoster = rosterDisplayNames.size() == 1;

        int[] perBot = new int[rosterDisplayNames.size()];
        List<SoulGroupTypes.SceneLine> lines = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            if (lines.size() >= maxSceneLines) {
                break;
            }
            String line = cleanLine(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            int idx;
            String body;
            int colon = line.indexOf(':');
            boolean tagLike = colon > 0 && colon <= MAX_SPEAKER_TAG_CHARS
                    && !normalize(line.substring(0, colon)).isEmpty();
            boolean tagged = tagLike && !line.substring(colon + 1).strip().isEmpty();
            if (tagged) {
                String speaker = normalize(line.substring(0, colon));
                body = line.substring(colon + 1).strip();
                idx = normRoster.indexOf(speaker);
                if (idx < 0) {
                    if (soloRoster && !META_TAGS.contains(speaker) && !isOwnerTag(speaker, normOwner)) {
                        idx = 0; // wrong NAME tag, one possible speaker — strip it, keep the line
                    } else {
                        continue; // unknown speaker or scaffolding tag — dropped, never repaired
                    }
                }
            } else if (soloRoster) {
                if (tagLike || line.endsWith(":")) {
                    continue; // "Here is the scene:" — a tag with no body is scaffolding, not prose
                }
                if (line.replaceAll("[\\p{Punct}\\s]", "").isEmpty()) {
                    continue; // punctuation-only noise
                }
                idx = 0;
                body = line;
            } else {
                continue; // narration or untagged prose is ambiguous with 2+ speakers
            }
            if (body.isEmpty()) {
                continue;
            }
            // Multi-speaker scenes: a run of lines from one speaker is one turn. The 3B model
            // likes "A, A, B, B", which played as the same voice twice with a pause between
            // (1.1.196 field bug). Merging keeps every word and counts once against the per-bot
            // cap. Solo scenes keep their lines — there the breaks are deliberate pacing.
            int last = lines.size() - 1;
            if (!soloRoster && last >= 0 && lines.get(last).participantIndex() == idx) {
                lines.set(last, new SoulGroupTypes.SceneLine(idx,
                        truncateLine(lines.get(last).text() + " " + body)));
                continue;
            }
            if (perBot[idx] >= SoulGroupTypes.MAX_LINES_PER_BOT) {
                continue;
            }
            perBot[idx]++;
            lines.add(new SoulGroupTypes.SceneLine(idx, truncateLine(body)));
        }

        if (endAtOwnerAddress && !normOwner.isEmpty()) {
            for (int i = 0; i < lines.size() - 1; i++) {
                if (addressesOwner(lines.get(i).text(), normOwner)) {
                    lines = new ArrayList<>(lines.subList(0, i + 1));
                    break;
                }
            }
        }
        if (lines.isEmpty()) {
            return reject("no roster-tagged dialogue lines");
        }
        return new SceneParse(true, lines, null, "");
    }

    /** True when a word of the line is the owner's name or a ≥4-char abbreviation of it. */
    static boolean addressesOwner(String text, String normalizedOwner) {
        if (text == null || normalizedOwner == null || normalizedOwner.isEmpty()) {
            return false;
        }
        for (String word : text.split("[^A-Za-z0-9]+")) {
            String w = normalize(word);
            if (w.isEmpty()) {
                continue;
            }
            if (w.equals(normalizedOwner) || (w.length() >= 4 && normalizedOwner.startsWith(w))) {
                return true;
            }
        }
        return false;
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
