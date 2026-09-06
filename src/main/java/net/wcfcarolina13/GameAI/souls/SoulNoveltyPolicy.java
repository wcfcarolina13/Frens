package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure novelty rules for group-scene lines (Phase 3d). A bot that keeps saying the same thing is
 * the loudest failure mode of a small local model, so every candidate line is checked against a
 * short in-memory ring of what that bot recently said, plus the lines already kept inside the
 * same scene.
 *
 * <p>Length-gated by design (spec §(d) approach 3): a laconic persona legitimately repeats short
 * interjections with slight variation ("Aye." / "Aye, right."), so fuzzy matching is only applied
 * to lines with at least {@link #MIN_CONTENT_WORDS} content words. Shorter lines are rejected
 * only on an exact normalised match.
 *
 * <p>No game classes, no I/O, no state beyond the caller-owned {@link Ring}s — everything here is
 * deterministic and unit-testable.
 */
final class SoulNoveltyPolicy {

    /** At or above this many content words a line is long enough for fuzzy (trigram) matching. */
    static final int MIN_CONTENT_WORDS = 8;
    /** Trigram overlap at or above which two long lines are "the same line again". */
    static final double TRIGRAM_THRESHOLD = 0.6d;
    /** Per-bot ring capacity: the last N normalised lines that bot actually delivered. */
    static final int RING_SIZE = 12;

    /** Rejection reason for an exact normalised match (any length). */
    static final String REASON_EXACT = "exact";
    /** Rejection reason for a fuzzy trigram match (long lines only). */
    static final String REASON_TRIGRAM = "trigram";

    private SoulNoveltyPolicy() {
    }

    /** Lowercase, punctuation-stripped, whitespace-collapsed — the digest's normaliser. */
    static String normalise(String text) {
        return SoulMemoryDigestOps.normalize(text);
    }

    /**
     * Content words of {@code text} in their original order: normalised, then stop-words dropped.
     * Order matters (trigrams are positional), so this cannot reuse the digest's {@code tokens},
     * which returns an unordered set.
     */
    static List<String> contentWords(String text) {
        String normalised = normalise(text);
        if (normalised.isEmpty()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String token : normalised.split(" ")) {
            if (!token.isEmpty() && !SoulMemoryDigestOps.STOP_WORDS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    /** Ordered word trigrams of {@code words}; empty when there are fewer than three words. */
    static Set<String> trigrams(List<String> words) {
        if (words == null || words.size() < 3) {
            return Set.of();
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 < words.size(); i++) {
            grams.add(words.get(i) + " " + words.get(i + 1) + " " + words.get(i + 2));
        }
        return grams;
    }

    /**
     * Containment overlap: {@code |a ∩ b| / min(|a|, |b|)}. Deliberately not Jaccard — a model
     * that restates a remembered line with a trailing clause tacked on should still count as a
     * repeat, and containment scores that at 1.0 where Jaccard would dilute it by the extra
     * length. Zero when either side has no trigrams (lines under three content words), which
     * leaves those to the exact path.
     */
    static double trigramOverlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int shared = 0;
        for (String gram : a) {
            if (b.contains(gram)) {
                shared++;
            }
        }
        return (double) shared / Math.min(a.size(), b.size());
    }

    /**
     * Why {@code candidate} should be dropped against {@code rememberedNormalised} (already
     * normalised strings), or empty when it is novel enough to say.
     *
     * <p>Exact normalised match always rejects. A candidate with at least
     * {@link #MIN_CONTENT_WORDS} content words additionally rejects when its trigram overlap with
     * any remembered line reaches {@link #TRIGRAM_THRESHOLD}.
     */
    static Optional<String> rejectReason(String candidate, Collection<String> rememberedNormalised) {
        if (rememberedNormalised == null || rememberedNormalised.isEmpty()) {
            return Optional.empty();
        }
        String normalised = normalise(candidate);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        if (rememberedNormalised.contains(normalised)) {
            return Optional.of(REASON_EXACT);
        }
        List<String> words = contentWords(candidate);
        if (words.size() < MIN_CONTENT_WORDS) {
            return Optional.empty();
        }
        Set<String> candidateGrams = trigrams(words);
        if (candidateGrams.isEmpty()) {
            return Optional.empty();
        }
        for (String remembered : rememberedNormalised) {
            if (remembered == null || remembered.isEmpty()) {
                continue;
            }
            Set<String> rememberedGrams = trigrams(contentWords(remembered));
            if (trigramOverlap(candidateGrams, rememberedGrams) >= TRIGRAM_THRESHOLD) {
                return Optional.of(REASON_TRIGRAM);
            }
        }
        return Optional.empty();
    }

    /** One verdict per candidate line, in candidate order. */
    record Verdict(int index, boolean kept, String reason, String normalised) {
    }

    /**
     * Verdicts for one scene's candidate line texts, in order. {@code historyByLine} supplies the
     * speaker's ring snapshot for each candidate (same length as {@code candidates}); a null or
     * short entry is treated as an empty history.
     *
     * <p>Kept lines are also checked against each other within the scene: the same normalised text
     * twice in one scene drops the second occurrence with reason {@link #REASON_EXACT}, no matter
     * which bot said it, so two bots cannot echo each other inside a single exchange.
     */
    static List<Verdict> filter(List<String> candidates, List<? extends Collection<String>> historyByLine) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> saidThisScene = new HashSet<>();
        List<Verdict> verdicts = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i);
            String normalised = normalise(text);
            Collection<String> history = historyByLine != null && i < historyByLine.size()
                    ? historyByLine.get(i) : null;
            String reason = null;
            if (!normalised.isEmpty() && saidThisScene.contains(normalised)) {
                reason = REASON_EXACT;
            } else {
                reason = rejectReason(text, history).orElse(null);
            }
            if (reason == null) {
                if (!normalised.isEmpty()) {
                    saidThisScene.add(normalised);
                }
                verdicts.add(new Verdict(i, true, null, normalised));
            } else {
                verdicts.add(new Verdict(i, false, reason, normalised));
            }
        }
        return verdicts;
    }

    /**
     * A bot's ring of the last {@link #RING_SIZE} normalised delivered lines, oldest evicted
     * first. Held per bot in a concurrent map by the caller; scenes for different owners complete
     * on different worker threads, so every access here is synchronized. Never persisted — a
     * restart legitimately resets what "recently" means.
     */
    static final class Ring {
        private final ArrayDeque<String> recent = new ArrayDeque<>(RING_SIZE);

        synchronized void remember(String normalised) {
            if (normalised == null || normalised.isEmpty()) {
                return;
            }
            while (recent.size() >= RING_SIZE) {
                recent.removeFirst();
            }
            recent.addLast(normalised);
        }

        synchronized List<String> snapshot() {
            return List.copyOf(recent);
        }

        synchronized int size() {
            return recent.size();
        }
    }
}
