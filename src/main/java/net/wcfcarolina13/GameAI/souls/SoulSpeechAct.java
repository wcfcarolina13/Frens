package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The KIND of thing a scene says (conversation ontology Phase 1, 2026-08-29). Topic rotation
 * stops the same noun recurring; this wheel stops every scene being an observation. Pure —
 * randomness injected; the director remembers the last few acts per audience and passes them
 * back so consecutive scenes differ in shape as well as subject.
 */
enum SoulSpeechAct {
    OBSERVE(3, "one of you points out", "point out to %s"),
    ASK(3, "one of you asks the other something real about", "ask %s something about"),
    TEASE(2, "one of you needles the other about", "gently tease %s about"),
    PLAN(2, "make a small plan together around", "suggest to %s a small plan around"),
    RECALL(2, "recall an earlier moment together about", "remind %s of an earlier moment about"),
    WORRY(2, "one of you voices a worry about", "tell %s a worry about"),
    JOKE(2, "share a joke about", "make %s laugh about");

    /** How many recent acts an audience remembers (skipped while any other eligible act exists). */
    static final int RECENT_ACT_MEMORY = 4;

    private final int weight;
    private final String groupDirective;
    private final String soloDirective;

    SoulSpeechAct(int weight, String groupDirective, String soloDirective) {
        this.weight = weight;
        this.groupDirective = groupDirective;
        this.soloDirective = soloDirective;
    }

    /** The cue's verb phrase; the anchor phrase follows it. */
    String directive(boolean solo, String playerName) {
        return solo ? String.format(soloDirective, playerName) : groupDirective;
    }

    /** RECALL needs something to recall; WORRY needs something worth worrying about. */
    boolean eligible(boolean hasEventAnchor, boolean hasWorryAnchor) {
        return switch (this) {
            case RECALL -> hasEventAnchor;
            case WORRY -> hasWorryAnchor;
            default -> true;
        };
    }

    /**
     * Weighted pick among eligible acts, skipping {@code recent} while any other eligible act
     * exists. Never returns null: with nothing eligible but recent ones, the recent pool is used.
     */
    static SoulSpeechAct pick(boolean hasEventAnchor, boolean hasWorryAnchor,
                              Collection<SoulSpeechAct> recent, RandomGenerator random) {
        List<SoulSpeechAct> eligible = new ArrayList<>();
        for (SoulSpeechAct act : values()) {
            if (act.eligible(hasEventAnchor, hasWorryAnchor)) {
                eligible.add(act);
            }
        }
        List<SoulSpeechAct> fresh = new ArrayList<>();
        for (SoulSpeechAct act : eligible) {
            if (recent == null || !recent.contains(act)) {
                fresh.add(act);
            }
        }
        List<SoulSpeechAct> pool = fresh.isEmpty() ? eligible : fresh;
        int total = 0;
        for (SoulSpeechAct act : pool) {
            total += act.weight;
        }
        int roll = random.nextInt(Math.max(1, total));
        for (SoulSpeechAct act : pool) {
            roll -= act.weight;
            if (roll < 0) {
                return act;
            }
        }
        return pool.get(pool.size() - 1);
    }
}
