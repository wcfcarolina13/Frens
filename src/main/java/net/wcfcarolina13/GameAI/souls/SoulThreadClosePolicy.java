package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which open thread a {@code ##FRENS} {@code "threads":{"closed":[...]}} entry names. No Minecraft types.
 *
 * <p>The model sees each open question in the {@code OPEN THREADS} block and is asked to copy the one
 * the owner answered. A claim closes a thread only when, after lower-casing, dropping punctuation and
 * collapsing spaces, the two texts are equal, or one contains the other and the shorter is at least
 * {@link #MIN_OVERLAP_RATIO} of the longer and {@link #MIN_MATCH_CHARS} long. A paraphrase or a vague
 * claim ("the question") matches nothing and the thread stays open: a thread left open expires by
 * itself, while a wrong close loses a question the bot still wanted answered.
 *
 * <p>Closing this way never moves the player stance. That move ({@link SoulMindOps#markAnswered}) is
 * driven by the owner actually replying, and the side channel never moves owner stance (the model
 * cannot flatter itself into the owner's good graces).
 */
final class SoulThreadClosePolicy {

    /** At most this many closes are taken from one scene. */
    static final int MAX_CLOSES = 3;
    static final int MIN_MATCH_CHARS = 12;
    static final double MIN_OVERLAP_RATIO = 0.6d;

    private SoulThreadClosePolicy() {
    }

    /** Lower case, letters/digits/spaces only, single spaces, trimmed. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                if (space && sb.length() > 0) {
                    sb.append(' ');
                }
                space = false;
                sb.append(c);
            } else {
                space = true;
            }
        }
        return sb.toString();
    }

    static boolean matches(String question, String claim) {
        String q = normalise(question);
        String c = normalise(claim);
        if (q.isEmpty() || c.isEmpty()) {
            return false;
        }
        if (q.equals(c)) {
            return true;
        }
        String shorter = q.length() <= c.length() ? q : c;
        String longer = shorter == q ? c : q;
        return shorter.length() >= MIN_MATCH_CHARS
                && shorter.length() >= MIN_OVERLAP_RATIO * longer.length()
                && longer.contains(shorter);
    }

    /**
     * The threads left after the claims: each claim closes the first still-open (non-expired) thread
     * it matches, at most one per claim. Expired threads are left for {@code dropExpired} (the seed
     * still recalls them). Returns the same list when nothing matched.
     */
    static List<SoulTypes.OpenThread> remaining(List<SoulTypes.OpenThread> threads, List<String> claims) {
        if (threads == null || threads.isEmpty() || claims == null || claims.isEmpty()) {
            return threads;
        }
        List<SoulTypes.OpenThread> left = new ArrayList<>(threads);
        boolean changed = false;
        for (String claim : claims) {
            for (int i = 0; i < left.size(); i++) {
                SoulTypes.OpenThread t = left.get(i);
                if (!t.expired() && matches(t.question(), claim)) {
                    left.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return changed ? left : threads;
    }
}
