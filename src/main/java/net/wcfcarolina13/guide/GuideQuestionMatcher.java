package net.wcfcarolina13.guide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches a chat question ("how do I fast travel?") to Guide topics, so a fren can answer from
 * the Guide with no LLM, and so a soul prompt can be grounded in the same topics.
 *
 * <p>Pure: no Minecraft types, no I/O. Two gates, both required for an answer:
 * <ol>
 *   <li>{@link #isHowToQuestion}: the line is shaped like a question or a request for help.
 *       A line that is not ("Jake, nice house") never gets a Guide answer.</li>
 *   <li>{@link #match}: keyword overlap with a topic's title (3 points per word), tags (2) or
 *       summary (1), plus 2 for each pair of adjacent question words that also sit next to each
 *       other in the title or tags ("fast travel"). A topic needs {@link #MIN_SCORE}.</li>
 * </ol>
 * Words are compared after a light stem (plural and -ing folding, a trailing "e" dropped) applied
 * the same way to both sides, so "fishing" meets "Fishing" and "mining" meets "mine".
 */
public final class GuideQuestionMatcher {

    /** Lowest score that counts as a match: one title word, or two tag words. */
    public static final int MIN_SCORE = 3;
    /**
     * Lowest score for a line that is a question only by its trailing "?" ("want to go
     * fishing?", "can you come here?"): two words, or a word and a phrase. Those are mostly
     * ordinary talk, so one stray title word is not enough.
     */
    public static final int MIN_SCORE_BARE_QUESTION = 5;
    static final int TITLE_POINTS = 3;
    static final int TAG_POINTS = 2;
    static final int SUMMARY_POINTS = 1;
    static final int PHRASE_POINTS = 2;

    /** What the asker may see: admin-only topics need an operator or the host. */
    public record Visibility(boolean admin, boolean questingMode) {
        /** A non-admin player on a world with Questing mode off. */
        public static final Visibility PUBLIC = new Visibility(false, false);

        boolean allows(GuideCatalog.Topic topic) {
            if (topic.adminOnly() && !admin) {
                return false;
            }
            return !topic.questingOnly() || questingMode;
        }
    }

    public record Match(GuideCatalog.Topic topic, int score) {}

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /**
     * "How does X work?": the closing "work" belongs to the question, not the topic, and would
     * otherwise pull such questions toward Daily Work Loops. Group 1 keeps the rest.
     */
    private static final Pattern HOW_DOES_IT_WORK = Pattern.compile(
            "(?i)^(\\W*how\\b.*?)\\s+work(?:s|ing)?\\W*$");
    private static final Pattern FORMAT_CODE = Pattern.compile("§.");

    /** Openers that make a line a how-to question even without a question mark. */
    private static final Pattern QUESTION_OPENER = Pattern.compile(
            "^(?:(?:hey|so|ok|okay|um|uh|please|quick question)\\W+)*"
                    + "(?:how|what|whats|what's|where|which|why|when"
                    + "|explain|teach me|tell me (?:about|how)|show me how|help"
                    + "|is there a way|do you know how|any tips"
                    + "|i (?:need|want) (?:help|to know|to learn)|i don't know how|i dont know how"
                    + "|(?:can|could|would) you (?:explain|tell me|show me|teach me|help))\\b");

    /**
     * Words that never decide a topic: question words, pronouns, filler, and words that sit in
     * many titles without saying which one ("bot", "set", "up").
     */
    private static final Set<String> STOPWORDS = stemAll(Set.of(
            "a", "an", "the", "i", "im", "me", "my", "you", "your", "youre", "we", "us",
            "our", "it", "its", "they", "them", "their", "he", "she", "him", "her",
            "to", "do", "does", "did", "done", "doing", "how", "what", "whats", "where", "which",
            "why", "when", "who", "is", "are", "was", "were", "be", "been", "am",
            "can", "could", "would", "should", "will", "shall", "may", "might", "must",
            "please", "hey", "so", "ok", "okay", "um", "uh", "of", "in", "on", "at", "for",
            "with", "from", "into", "onto", "and", "or", "but", "if", "then", "than", "about",
            "explain", "tell", "teach", "show", "help", "know", "way", "there", "here", "this",
            "that", "these", "those", "get", "got", "make", "use", "using", "set", "up",
            "bot", "bots", "fren", "frens", "companion", "companions", "guide", "minecraft",
            "thing", "things", "like", "just", "really", "want", "need", "go", "let", "lets",
            "some", "any", "all", "one", "each", "every", "it's", "i'm",
            "not", "no", "yes", "yeah", "tip", "tips", "question", "quick", "learn", "able",
            "anything", "something", "stuff", "again", "now", "still", "also", "too", "very",
            "build", "back"));

    /**
     * A few everyday words mapped onto the word the Guide uses, applied to both sides after
     * stemming. Kept short on purpose: the Guide's own tags are the place for synonyms.
     */
    private static final java.util.Map<String, String> SYNONYMS = java.util.Map.ofEntries(
            java.util.Map.entry("dying", "death"), java.util.Map.entry("die", "death"),
            java.util.Map.entry("died", "death"), java.util.Map.entry("dead", "death"),
            java.util.Map.entry("wood", "woodcut"), java.util.Map.entry("chop", "woodcut"),
            java.util.Map.entry("quieter", "quiet"), java.util.Map.entry("shut", "quiet"));

    private GuideQuestionMatcher() {
    }

    /**
     * Whether {@code prompt} asks for help or how something works: a question mark at the end,
     * or an opener such as "how", "what is", "where", "explain" or "can you help".
     */
    public static boolean isHowToQuestion(String prompt) {
        if (prompt == null) {
            return false;
        }
        String trimmed = prompt.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.endsWith("?") || hasQuestionOpener(trimmed);
    }

    /** Whether the line opens like a request for help ("how", "what is", "explain", "can you help"). */
    public static boolean hasQuestionOpener(String prompt) {
        if (prompt == null) {
            return false;
        }
        String lower = prompt.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
        return QUESTION_OPENER.matcher(lower).find();
    }

    /**
     * The best topics for {@code prompt}, highest score first, at most {@code max}. Ties keep
     * Guide file order. Only topics at or above {@link #MIN_SCORE} that {@code visibility}
     * allows are returned. Does not check {@link #isHowToQuestion}.
     */
    public static List<Match> match(String prompt, List<GuideCatalog.Topic> topics,
                                    Visibility visibility, int max) {
        if (prompt == null || prompt.isBlank() || topics == null || max <= 0) {
            return List.of();
        }
        List<String> query = contentStems(HOW_DOES_IT_WORK.matcher(prompt.trim()).replaceFirst("$1"));
        if (query.isEmpty()) {
            return List.of();
        }
        Visibility seen = visibility == null ? Visibility.PUBLIC : visibility;
        List<Match> scored = new ArrayList<>();
        for (GuideCatalog.Topic topic : topics) {
            if (!seen.allows(topic)) {
                continue;
            }
            int score = score(query, topic);
            if (score >= MIN_SCORE) {
                scored.add(new Match(topic, score));
            }
        }
        // List.sort is stable, so equal scores keep file order.
        scored.sort(Comparator.comparingInt(Match::score).reversed());
        return scored.size() <= max ? List.copyOf(scored) : List.copyOf(scored.subList(0, max));
    }

    /**
     * {@link #match} behind the {@link #isHowToQuestion} gate: empty unless both pass. A line
     * with no question opener needs {@link #MIN_SCORE_BARE_QUESTION} for its best topic.
     */
    public static List<Match> answer(String prompt, List<GuideCatalog.Topic> topics,
                                     Visibility visibility, int max) {
        if (!isHowToQuestion(prompt)) {
            return List.of();
        }
        List<Match> matches = match(prompt, topics, visibility, max);
        if (matches.isEmpty() || hasQuestionOpener(prompt)) {
            return matches;
        }
        return matches.get(0).score() >= MIN_SCORE_BARE_QUESTION ? matches : List.of();
    }

    /**
     * Whether a runner-up is close enough to mention next to the best match: at least half the
     * best score. A distant second is usually a stray tag hit.
     */
    public static boolean runnerUpWorthMentioning(Match best, Match runnerUp) {
        if (best == null || runnerUp == null) {
            return false;
        }
        return runnerUp.score() * 2 >= best.score();
    }

    static int score(List<String> query, GuideCatalog.Topic topic) {
        List<String> title = stems(topic.title());
        List<String> tags = stems(topic.tags());
        Set<String> summary = new LinkedHashSet<>(stems(topic.summary()));
        int score = 0;
        for (String word : new LinkedHashSet<>(query)) {
            if (title.contains(word)) {
                score += TITLE_POINTS;
            } else if (tags.contains(word)) {
                score += TAG_POINTS;
            } else if (summary.contains(word)) {
                score += SUMMARY_POINTS;
            }
        }
        for (int i = 0; i + 1 < query.size(); i++) {
            String first = query.get(i);
            String second = query.get(i + 1);
            if (first.equals(second)) {
                continue;
            }
            if (adjacent(title, first, second) || adjacent(tags, first, second)) {
                score += PHRASE_POINTS;
            }
        }
        return score;
    }

    private static boolean adjacent(List<String> words, String first, String second) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals(first) && words.get(i + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    /** The question's words that can decide a topic, stemmed, in order. */
    static List<String> contentStems(String text) {
        List<String> out = new ArrayList<>();
        for (String stem : stems(text)) {
            if (!STOPWORDS.contains(stem)) {
                out.add(stem);
            }
        }
        return out;
    }

    static List<String> stems(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String plain = FORMAT_CODE.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace("'s ", " ").replace("'", "");
        List<String> out = new ArrayList<>();
        for (String raw : NON_WORD.split(plain)) {
            if (!raw.isEmpty()) {
                String stemmed = stem(raw);
                out.add(SYNONYMS == null ? stemmed : SYNONYMS.getOrDefault(stemmed, stemmed));
            }
        }
        return out;
    }

    /**
     * Light stem, applied identically to question and topic words: plural folding, "-ing" with a
     * doubled consonant undone ("chopping" to "chop"), then a trailing "e" dropped so "mine",
     * "mining" and "mines" meet.
     */
    static String stem(String word) {
        String w = word;
        if (w.length() > 4 && w.endsWith("ies")) {
            w = w.substring(0, w.length() - 3) + "y";
        } else if (w.length() > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")
                || w.endsWith("sses"))) {
            w = w.substring(0, w.length() - 2);
        } else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.length() >= 6 && w.endsWith("ing")) {
            w = w.substring(0, w.length() - 3);
            int n = w.length();
            if (n >= 3 && w.charAt(n - 1) == w.charAt(n - 2) && !isVowel(w.charAt(n - 1))
                    && w.charAt(n - 1) != 's') {
                w = w.substring(0, n - 1);
            }
        }
        if (w.length() > 3 && w.endsWith("e") && !w.endsWith("ee")) {
            w = w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private static Set<String> stemAll(Set<String> words) {
        Set<String> out = new java.util.HashSet<>();
        for (String word : words) {
            for (String stem : stems(word)) {
                out.add(stem);
            }
        }
        return Set.copyOf(out);
    }
}
