package net.wcfcarolina13.GameAI.souls;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a scene line is spoken TO the owner (vocative) rather than merely ABOUT them.
 *
 * <p>Why (1.1.223 conversation smoothing): ambient bot-to-bot scenes end at the first line that
 * addresses the player, so no bot answers on the player's behalf (2026-08-29 guard). That cut
 * used a plain word match, so any third-person mention ended the scene — "Hey, Jake, what was
 * RotiWokeman looking for the other day?" played alone and Jake never answered. Every one-line
 * two-bot scene in the 2026-09-25 logs named the owner this way.
 *
 * <p>The name matches exactly as {@link SoulGroupResponseValidator#addressesOwner} matches it:
 * the whole normalised name, or a ≥{@value #MIN_PREFIX_CHARS}-char prefix of it ("Roti" for
 * "RotiWokeman"), as a standalone token. An occurrence is vocative when it is not possessive
 * ({@code 's} / {@code ’s}) and it sits in address position:
 * <ul>
 *   <li>opening a sentence and followed by {@code , ! ? .} or the end ("Roti, look at this!");</li>
 *   <li>closing a sentence after a comma ("I'm trying my best, RotiWokeman.");</li>
 *   <li>set off by commas mid-sentence ("Well, Roti, I think so.");</li>
 *   <li>directly after a sentence-opening greeting ("Hey Roti!", "Morning, Roti.").</li>
 * </ul>
 * Pure — no game classes, no state.
 */
final class OwnerAddressPolicy {

    /** Same abbreviation floor as {@code addressesOwner}: "Rot" is not the player, "Roti" is. */
    static final int MIN_PREFIX_CHARS = 4;

    /** Underscore is a legal Minecraft name character, so it stays inside a token. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    /** Sentence-opening words after which a bare name is a greeting to that person. */
    private static final Set<String> GREETINGS = Set.of(
            "hey", "hi", "hello", "heya", "hiya", "howdy", "yo", "oi", "ahoy", "greetings",
            "good", "morning", "afternoon", "evening", "night", "goodnight", "thanks", "thank",
            "you", "welcome", "back", "bye", "goodbye", "cheers", "sorry");

    private OwnerAddressPolicy() {
    }

    /**
     * @param text one scene line body (speaker tag already removed)
     * @param ownerDisplayName the owner's display name, raw or normalised
     * @return true when some occurrence of the owner's name addresses them directly
     */
    static boolean isVocative(String text, String ownerDisplayName) {
        String owner = SoulGroupResponseValidator.normalize(ownerDisplayName);
        if (text == null || text.isEmpty() || owner.isEmpty()) {
            return false;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (!namesOwner(m.group(), owner)) {
                continue;
            }
            int start = m.start();
            int end = m.end();
            if (isPossessive(text, end)) {
                continue; // "Roti's got a short fuse" — about them, never to them
            }
            char before = prevSignificant(text, start);
            char after = nextSignificant(text, end);
            boolean sentenceStart = before == 0 || isSentenceEnd(before);
            boolean closesClause = after == 0 || isSentenceEnd(after);
            if (sentenceStart && (after == ',' || closesClause)) {
                return true;
            }
            if (before == ',' && (after == ',' || closesClause)) {
                return true;
            }
            if (followsGreeting(text, start)) {
                return true;
            }
        }
        return false;
    }

    /** Whole normalised name, or a ≥4-char prefix of it; "Rotisserie" is its own word. */
    private static boolean namesOwner(String token, String normalizedOwner) {
        String w = SoulGroupResponseValidator.normalize(token);
        if (w.isEmpty()) {
            return false;
        }
        return w.equals(normalizedOwner)
                || (w.length() >= MIN_PREFIX_CHARS && normalizedOwner.startsWith(w));
    }

    private static boolean isPossessive(String text, int end) {
        if (end + 1 >= text.length()) {
            return false;
        }
        char q = text.charAt(end);
        if (q != '\'' && q != '’') {
            return false;
        }
        char s = text.charAt(end + 1);
        if (s != 's' && s != 'S') {
            return false;
        }
        return end + 2 >= text.length() || !Character.isLetterOrDigit(text.charAt(end + 2));
    }

    /**
     * True when every word between the sentence start and {@code nameStart} is a greeting
     * ("Hey", "Good morning", "Thank you"), with at least one such word.
     */
    private static boolean followsGreeting(String text, int nameStart) {
        int sentenceStart = 0;
        for (int i = nameStart - 1; i >= 0; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                sentenceStart = i + 1;
                break;
            }
        }
        String prefix = text.substring(sentenceStart, nameStart);
        Matcher m = WORD.matcher(prefix);
        int words = 0;
        while (m.find()) {
            if (!GREETINGS.contains(m.group().toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
            words++;
        }
        if (words == 0) {
            return false;
        }
        // Only commas, spaces and "!" may separate the greeting from the name — "Hey — Jake's
        // here, Roti" is not a greeting to Roti (the comma rule decides that one).
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != ',' && c != '!'
                    && !Character.isWhitespace(c) && !isQuote(c)) {
                return false;
            }
        }
        return true;
    }

    /** Previous non-space, non-quote char before {@code index}, or 0 at the start of the text. */
    private static char prevSignificant(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !isQuote(c) && c != '*') {
                return c;
            }
        }
        return 0;
    }

    /** Next non-space, non-quote char at or after {@code index}, or 0 at the end of the text. */
    private static char nextSignificant(String text, int index) {
        for (int i = index; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !isQuote(c) && c != '*') {
                return c;
            }
        }
        return 0;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '“' || c == '”' || c == '\'' || c == '‘' || c == '’';
    }
}
