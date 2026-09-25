package net.wcfcarolina13;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Pure token-level chat-address resolution: given a raw public chat line, the registered bot
 * names and (optionally) the names of the <em>other</em> online humans, decides whether the line
 * addresses a bot (or the "bots"/"all bots" broadcast keyword), is a soft group address, or is
 * addressed to another human — and what prompt text a bot addressee should receive. Extracted
 * from {@code Frens#resolveChatTargets} so the addressing rules are unit-testable without a
 * running server; {@code Frens} maps the returned name index / flags back onto live
 * {@code ServerPlayerEntity} instances.
 *
 * <p>Matching: tokens are normalized by stripping non-alphanumerics and lowercasing; the first
 * token that matches a broadcast keyword ({@code bots}, {@code allbots}, or the pair
 * {@code all bots}) or a registered bot name wins. Only a line with neither is scanned for a
 * soft group address.
 *
 * <p>Prompt extraction (the leading-name quirk fix): when the matched token is
 * <em>leading</em> — every earlier token normalizes to empty, e.g. {@code "Jake come here"} or
 * {@code "!! Jake come here"} — the prompt is the tail after the match. When the match sits
 * anywhere later, the prompt is the <em>full trimmed message</em> with the name left in place.
 *
 * <p>Addressee rules added 2026-09-25:
 * <ul>
 *   <li><b>Soft broadcast</b> — {@code everyone}, {@code everybody}, {@code yall}/{@code y'all},
 *       {@code guys} (unless a determiner such as "those"/"the" precedes it), {@code you two},
 *       {@code you both}. Applies only when the line names no bot and no broadcast keyword
 *       ("hey guys, Jake come here" is for Jake). Reported with {@code broadcast} and
 *       {@code softBroadcast} both set; the caller decides whether to honour it (see
 *       {@link #shouldDemoteSoftBroadcast}). The explicit keywords stay hard broadcasts.</li>
 *   <li><b>Other addressee</b> — the leading token is another online human's name: the line is
 *       for them, not the bots ({@code otherAddressee}, no bot indices). A bot name always wins
 *       over a human name. Names of two characters or fewer, or on a small common-word list,
 *       only count when the token carries a trailing {@code ,} or {@code :}, so a player called
 *       "Hey" does not swallow every greeting.</li>
 *   <li><b>Trailing vocative</b> — a non-leading first match loses to a different bot named by
 *       the last token when the token before it ends in a comma ("did Jake eat, Wren" → Wren).
 *       Leading names still win.</li>
 *   <li><b>Comma runs</b> — in a leading multi-name run, a name reached with "and" or a comma
 *       joins the run ("Jake and Wren come here", "Jake, Wren come here" and "Jake, Wren" all
 *       address two), unless a comma-joined name is followed directly by a {@link #CLAUSE_CUES
 *       clause cue} ("Jake, Wren said you took it" addresses Jake). A name reached with neither
 *       joins only when its own token ends in punctuation or it is the last meaningful token
 *       ("Jake Wren come here" addresses Jake only).</li>
 * </ul>
 */
public final class ChatAddressing {

    /**
     * A resolved address. {@code matchedNameIndices} holds the indices into the {@code botNames}
     * list passed to {@link #resolve} of the explicitly-named bots, in address order — usually a
     * single entry, two or more for a leading multi-name run ({@code "Jake and Sara, ..."}), and
     * empty for a broadcast match or an other-addressee line. {@code prompt} may be empty (a bare
     * {@code "Jake"} with no content). {@code otherAddressee} marks a line addressed to another
     * online human; {@code softBroadcast} marks a broadcast that came from a soft group address
     * rather than the explicit {@code bots}/{@code all bots} keywords ({@code broadcast} is set
     * too, so callers that ignore the soft flag see an ordinary broadcast).
     */
    public record Resolution(List<Integer> matchedNameIndices, boolean broadcast, String prompt,
                             boolean otherAddressee, boolean softBroadcast) {
        public Resolution {
            matchedNameIndices = matchedNameIndices == null ? List.of() : List.copyOf(matchedNameIndices);
            prompt = prompt == null ? "" : prompt;
        }

        /** Source-compatible constructor: an ordinary bot address (not other-addressee, not soft). */
        public Resolution(List<Integer> matchedNameIndices, boolean broadcast, String prompt) {
            this(matchedNameIndices, broadcast, prompt, false, false);
        }

        /** Back-compat: the first explicitly named bot's index, or {@code -1} for broadcast/none. */
        public int matchedNameIndex() {
            return matchedNameIndices.isEmpty() ? -1 : matchedNameIndices.get(0);
        }
    }

    /** Words that make "guys" a description ("those guys") rather than an address ("morning guys"). */
    static final Set<String> GUYS_DETERMINERS = Set.of(
            "the", "these", "those", "them", "other", "some", "bad", "good", "nice", "big",
            "two", "three", "few");

    /**
     * Common chat words that are also plausible usernames: a human with one of these names is
     * only treated as the addressee when the token carries a trailing {@code ,} or {@code :}.
     * Includes the broadcast keywords and every soft-broadcast word, so a human called "All" or
     * "Everyone" does not swallow "all bots follow me" or "everyone come here". Normalized forms.
     */
    static final Set<String> HUMAN_NAME_STOPWORDS = Set.of(
            "hey", "hi", "hello", "yo", "yes", "yeah", "yep", "nope", "okay", "lol", "lmao", "oh",
            "so", "well", "wait", "what", "why", "how", "who", "where", "when", "the", "and", "but",
            "you", "this", "that", "come", "stop", "help", "nice", "cool", "thanks", "bro", "dude",
            "man", "guys", "hmm", "sure", "sorry", "please", "afk",
            "bots", "allbots", "all", "everyone", "everybody", "yall", "two", "both");

    /**
     * Tokens that, right after a comma-joined second name, make that name the subject of a new
     * clause ("Jake, Wren said you took it") instead of a second addressee. Normalized forms
     * (apostrophes stripped: "isn't" is {@code isnt}).
     */
    static final Set<String> CLAUSE_CUES = Set.of(
            "said", "says", "told", "tells", "asked", "asks", "thinks", "thought", "wants", "wanted",
            "is", "was", "isnt", "wasnt", "has", "had", "did", "does", "didnt", "doesnt", "will",
            "would", "can", "could", "should", "went", "got", "just", "also", "always", "never");

    private ChatAddressing() {
    }

    public static Optional<Resolution> resolve(String raw, List<String> botNames) {
        return resolve(raw, botNames, List.of());
    }

    /**
     * @param humanNames the names of the other online human players — the caller excludes the
     *     sender (and every bot) before passing them; {@code null} is treated as empty
     */
    public static Optional<Resolution> resolve(String raw, List<String> botNames, Collection<String> humanNames) {
        if (raw == null || botNames == null || botNames.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] tokens = trimmed.split("\\s+");

        if (leadsWithOtherHuman(tokens, botNames, humanNames == null ? List.of() : humanNames)) {
            return Optional.of(new Resolution(List.of(), false, "", true, false));
        }

        int matchTokenIndex = -1;
        int consumed = -1;
        int matchedNameIndex = -1;
        boolean broadcast = false;
        boolean soft = false;
        for (int i = 0; i < tokens.length; i++) {
            String current = normalizeToken(tokens[i]);
            if (current.isEmpty()) {
                continue;
            }
            if (current.equals("allbots") || current.equals("bots")) {
                broadcast = true;
                matchTokenIndex = i;
                consumed = i + 1;
                break;
            }
            if (current.equals("all") && i + 1 < tokens.length
                    && normalizeToken(tokens[i + 1]).equals("bots")) {
                broadcast = true;
                matchTokenIndex = i;
                consumed = i + 2;
                break;
            }
            int nameIdx = botIndex(botNames, current);
            if (nameIdx >= 0) {
                matchedNameIndex = nameIdx;
                matchTokenIndex = i;
                consumed = i + 1;
                break;
            }
        }
        // Soft group words only count when the line names no bot and no broadcast keyword at all:
        // "hey guys, Jake come here" is for Jake, decided by the ordinary name rules above.
        if (consumed < 0) {
            for (int i = 0; i < tokens.length; i++) {
                String current = normalizeToken(tokens[i]);
                int softLength = current.isEmpty() ? 0 : softBroadcastLength(tokens, i, current);
                if (softLength > 0) {
                    broadcast = true;
                    soft = true;
                    matchTokenIndex = i;
                    consumed = i + softLength;
                    break;
                }
            }
        }
        if (consumed < 0) {
            return Optional.empty();
        }

        boolean leading = true;
        for (int i = 0; i < matchTokenIndex; i++) {
            if (!normalizeToken(tokens[i]).isEmpty()) {
                leading = false;
                break;
            }
        }
        int lastMeaningful = lastMeaningfulIndex(tokens);

        // Trailing vocative: "did Jake eat, Wren" is addressed to Wren, not Jake.
        if (matchedNameIndex >= 0 && !leading && lastMeaningful > matchTokenIndex) {
            int trailingIdx = botIndex(botNames, normalizeToken(tokens[lastMeaningful]));
            if (trailingIdx >= 0 && trailingIdx != matchedNameIndex
                    && tokens[lastMeaningful - 1].endsWith(",")) {
                matchedNameIndex = trailingIdx;
            }
        }

        List<Integer> indices = new ArrayList<>();
        if (matchedNameIndex >= 0) {
            indices.add(matchedNameIndex);
        }
        // Leading multi-name run: after "Jake", keep consuming ("and" | punctuation-only)* Name
        // pairs — "Jake and Wren, ..." or "Jake, Wren come here" address both bots. A connector
        // that is not followed by a further bot name reverts entirely, so "Jake and I went mining"
        // still routes only to Jake with the tail untouched. A comma-joined name is left out when
        // the very next token opens a new clause with it as the subject ("Jake, Wren said you took
        // it" is for Jake about Wren). A name reached with neither "and" nor a comma joins only
        // when its token ends in punctuation or closes the line, so "Jake Wren come here" stays
        // with Jake. Non-leading matches never extend: the full-message prompt rule already
        // preserves every name for the single addressee.
        if (matchedNameIndex >= 0 && leading) {
            int cursor = consumed;
            while (cursor < tokens.length) {
                int probe = cursor;
                boolean viaAnd = false;
                boolean viaComma = tokens[cursor - 1].endsWith(",");
                String norm = normalizeToken(tokens[probe]);
                while (probe < tokens.length && (norm.equals("and") || norm.isEmpty())) {
                    if (norm.equals("and")) {
                        viaAnd = true;
                    } else if (tokens[probe].contains(",")) {
                        viaComma = true;
                    }
                    probe++;
                    norm = probe < tokens.length ? normalizeToken(tokens[probe]) : "";
                }
                if (probe >= tokens.length) {
                    break;
                }
                int nameIdx = botIndex(botNames, norm);
                if (nameIdx < 0) {
                    break;
                }
                if (!joinsRun(tokens, probe, lastMeaningful, viaAnd, viaComma)) {
                    break;
                }
                if (!indices.contains(nameIdx)) {
                    indices.add(nameIdx);
                }
                cursor = probe + 1;
                consumed = cursor;
            }
        }

        String prompt;
        if (leading) {
            prompt = consumed >= tokens.length
                    ? ""
                    : String.join(" ", Arrays.copyOfRange(tokens, consumed, tokens.length)).trim();
        } else {
            prompt = trimmed;
        }
        return Optional.of(new Resolution(indices, broadcast, prompt, false, soft));
    }

    /**
     * Whether the caller should treat a resolved soft broadcast as an ordinary unaddressed line
     * instead. A soft group address ("hey everyone") is honoured only when the sender is the only
     * human online and the soul party path can actually route it silently; otherwise it is plain
     * chat. Explicit {@code bots}/{@code all bots} broadcasts ({@code softBroadcast == false}) are
     * never demoted.
     */
    public static boolean shouldDemoteSoftBroadcast(boolean softBroadcast, boolean otherHumanOnline,
                                                    boolean partyCanRoute) {
        return softBroadcast && (otherHumanOnline || !partyCanRoute);
    }

    /**
     * Number of tokens a soft group address starting at {@code i} spans (1 or 2), or 0 when the
     * token does not start one.
     */
    private static int softBroadcastLength(String[] tokens, int i, String current) {
        return switch (current) {
            case "everyone", "everybody", "yall" -> 1;
            case "guys" -> GUYS_DETERMINERS.contains(i == 0 ? "" : normalizeToken(tokens[i - 1])) ? 0 : 1;
            case "you" -> i + 1 < tokens.length && isTwoOrBoth(normalizeToken(tokens[i + 1])) ? 2 : 0;
            default -> 0;
        };
    }

    private static boolean isTwoOrBoth(String normalized) {
        return normalized.equals("two") || normalized.equals("both");
    }

    /**
     * True when the first meaningful token names another online human (and no bot). Exact
     * normalized match only; short or common-word names need a trailing {@code ,} or {@code :}.
     */
    private static boolean leadsWithOtherHuman(String[] tokens, List<String> botNames,
                                               Collection<String> humanNames) {
        if (humanNames.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            String first = normalizeToken(token);
            if (first.isEmpty()) {
                continue;
            }
            if (botIndex(botNames, first) >= 0) {
                return false;
            }
            for (String human : humanNames) {
                if (normalizeToken(human).equals(first)) {
                    return !needsVocativeMark(first) || token.endsWith(",") || token.endsWith(":");
                }
            }
            return false;
        }
        return false;
    }

    static boolean needsVocativeMark(String normalizedName) {
        return normalizedName.length() <= 2 || HUMAN_NAME_STOPWORDS.contains(normalizedName);
    }

    private static int botIndex(List<String> botNames, String normalized) {
        if (normalized.isEmpty()) {
            return -1;
        }
        for (int n = 0; n < botNames.size(); n++) {
            if (normalizeToken(botNames.get(n)).equals(normalized)) {
                return n;
            }
        }
        return -1;
    }

    private static int lastMeaningfulIndex(String[] tokens) {
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (!normalizeToken(tokens[i]).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether the bot name at {@code nameToken}, reached from the previous run name, joins a
     * leading multi-name run. "and" always joins; so does a name whose own token ends in
     * punctuation ("Wren,") or that closes the line. A comma-joined name joins unless the token
     * right after it is a {@link #CLAUSE_CUES clause cue} making it the subject of a new clause.
     */
    private static boolean joinsRun(String[] tokens, int nameToken, int lastMeaningful,
                                    boolean viaAnd, boolean viaComma) {
        if (viaAnd || endsInPunctuation(tokens[nameToken]) || nameToken == lastMeaningful) {
            return true;
        }
        return viaComma && (nameToken + 1 >= tokens.length
                || !CLAUSE_CUES.contains(normalizeToken(tokens[nameToken + 1])));
    }

    private static boolean endsInPunctuation(String token) {
        return !token.isEmpty() && !Character.isLetterOrDigit(token.charAt(token.length() - 1));
    }

    static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.replaceAll("[^a-zA-Z0-9]", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
