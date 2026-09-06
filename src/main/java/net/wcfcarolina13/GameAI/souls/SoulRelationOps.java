package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Pure, deterministic rules for typed relation facts (conversation ontology Phase 3b): parsing a
 * proposed fact against the closed {@link SoulTypes.Relation} vocabulary, merging it into a mind's
 * list under the single-valued and opposition rules, day decay, recall bumps, the deterministic
 * SEEN producer that reads the event journal, the prompt {@code BELIEFS} block, and banter anchors.
 *
 * <p>No LLM, no I/O, no game classes — {@code java.util} only, so every rule here is unit-testable
 * as plain data. Everything a bot holds is a CLAIM, never world truth; the prompt header says so.
 */
final class SoulRelationOps {

    /** Hard cap on relations held per mind; the weakest go first. */
    static final int MAX_RELATIONS = 20;
    /** At most this many relation anchors per seed. */
    static final int MAX_RELATION_ANCHORS = 2;
    /** Same tier as {@link SoulMindOps#MEMORY_ANCHOR_WEIGHT}: grounding 1-3 < memory 4 < change 5. */
    static final int RELATION_ANCHOR_WEIGHT = 4;
    /**
     * Seed topics of relation anchors are {@code relation:<RELATION>|<subject>|<object>} — the
     * topic carries the whole triple so the director can put exactly the spoken fact on its recall
     * cooldown, the way a {@code memory:} topic round-trips a fact key.
     */
    static final String RELATION_TOPIC_PREFIX = "relation:";
    static final int MAX_BELIEF_LINES = 4;
    static final int MAX_BELIEFS_CHARS = 240;
    /** Completions of one task category before the bot concludes it is good at that category. */
    static final int SEEN_MIN_COMPLETIONS = 3;
    /** A watched-it-happen conclusion is held loosely. */
    static final double SEEN_CONFIDENCE = 0.4d;
    static final int SEEN_SALIENCE = 6;
    static final int MAX_NAME_CHARS = 60;
    /** The one subject that is not a scene participant: a claim about the world at large. */
    static final String WORLD_SUBJECT = "the world";

    /** Relations of which a bot may hold exactly one object at a time. */
    private static final Set<SoulTypes.Relation> SINGLE_VALUED =
            Set.of(SoulTypes.Relation.GOOD_AT, SoulTypes.Relation.BAD_AT, SoulTypes.Relation.CALLS);

    private SoulRelationOps() {
    }

    // === parsing ===

    /**
     * Validates one proposed fact. The relation must name a {@link SoulTypes.Relation} constant
     * (case-insensitively); the subject must be one of {@code allowedSubjects} (case-insensitively)
     * or the literal {@code "the world"}; subject and object are trimmed, must be non-blank, and
     * are truncated to {@link #MAX_NAME_CHARS}. Confidence is clamped to 0..1 and quantised to one
     * decimal; salience is clamped to 0..{@link SoulMemoryDigestOps#MAX_SALIENCE}.
     */
    static Optional<SoulTypes.RelationFact> normalise(String subject, String relationName, String object,
                                                      double confidence, SoulTypes.RelationSource src,
                                                      int day, int salience, Set<String> allowedSubjects) {
        if (relationName == null) {
            return Optional.empty();
        }
        SoulTypes.Relation relation;
        try {
            relation = SoulTypes.Relation.valueOf(relationName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        String s = subject == null ? "" : subject.trim();
        String o = object == null ? "" : object.trim();
        if (s.isEmpty() || o.isEmpty()) {
            return Optional.empty();
        }
        if (!WORLD_SUBJECT.equalsIgnoreCase(s) && !containsIgnoreCase(allowedSubjects, s)) {
            return Optional.empty();
        }
        // '|' is the anchorTopic delimiter; a subject/object containing it would make the topic
        // unparseable, so that fact could never be put on recall cooldown.
        if (s.contains("|") || o.contains("|")) {
            return Optional.empty();
        }
        s = truncate(s);
        o = truncate(o);
        double c = Math.max(0d, Math.min(1d, confidence));
        c = Math.round(c * 10d) / 10.0d;
        int sal = Math.max(0, Math.min(SoulMemoryDigestOps.MAX_SALIENCE, salience));
        return Optional.of(new SoulTypes.RelationFact(s, relation, o,
                c, src == null ? SoulTypes.RelationSource.INFERRED : src, day, sal));
    }

    private static boolean containsIgnoreCase(Set<String> names, String candidate) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_NAME_CHARS ? value : value.substring(0, MAX_NAME_CHARS).trim();
    }

    // === merge ===

    /**
     * Folds {@code incoming} into {@code existing}, insertion order preserved:
     * <ul>
     *   <li>an identical (subject, relation, object) triple is restated: confidence and salience
     *       take the max, the day the newer;</li>
     *   <li>a single-valued relation (GOOD_AT / BAD_AT / CALLS) with a different object replaces
     *       the held one when the incoming confidence is at least as high, else is dropped;</li>
     *   <li>LIKES and DISLIKES are opposites: writing one removes the other for that
     *       (subject, object);</li>
     *   <li>everything else accumulates, capped at {@link #MAX_RELATIONS} by evicting the lowest
     *       salience, ties broken by the oldest day.</li>
     * </ul>
     */
    static List<SoulTypes.RelationFact> merge(List<SoulTypes.RelationFact> existing,
                                              SoulTypes.RelationFact incoming) {
        List<SoulTypes.RelationFact> out = new ArrayList<>(
                existing == null ? List.<SoulTypes.RelationFact>of() : existing);
        if (incoming == null) {
            return List.copyOf(out);
        }
        // 1. identical triple → strengthen in place.
        for (int i = 0; i < out.size(); i++) {
            SoulTypes.RelationFact held = out.get(i);
            if (sameTriple(held, incoming)) {
                out.set(i, new SoulTypes.RelationFact(held.subject(), held.relation(), held.object(),
                        Math.max(held.confidence(), incoming.confidence()), incoming.source(),
                        Math.max(held.day(), incoming.day()),
                        Math.max(held.salience(), incoming.salience())));
                return List.copyOf(out);
            }
        }
        // 2. single-valued contradiction → replace or drop.
        if (SINGLE_VALUED.contains(incoming.relation())) {
            for (int i = 0; i < out.size(); i++) {
                SoulTypes.RelationFact held = out.get(i);
                if (held.relation() == incoming.relation()
                        && held.subject().equalsIgnoreCase(incoming.subject())) {
                    if (incoming.confidence() >= held.confidence()) {
                        out.set(i, incoming);
                        return List.copyOf(out);
                    }
                    return List.copyOf(out);
                }
            }
        }
        // 3. LIKES/DISLIKES opposition → the other one goes.
        SoulTypes.Relation opposite = opposite(incoming.relation());
        if (opposite != null) {
            out.removeIf(held -> held.relation() == opposite
                    && held.subject().equalsIgnoreCase(incoming.subject())
                    && held.object().equalsIgnoreCase(incoming.object()));
        }
        out.add(incoming);
        while (out.size() > MAX_RELATIONS) {
            int weakest = 0;
            for (int i = 1; i < out.size(); i++) {
                SoulTypes.RelationFact a = out.get(i);
                SoulTypes.RelationFact b = out.get(weakest);
                if (a.salience() < b.salience() || (a.salience() == b.salience() && a.day() < b.day())) {
                    weakest = i;
                }
            }
            out.remove(weakest);
        }
        return List.copyOf(out);
    }

    private static boolean sameTriple(SoulTypes.RelationFact a, SoulTypes.RelationFact b) {
        return a.relation() == b.relation()
                && a.subject().equalsIgnoreCase(b.subject())
                && a.object().equalsIgnoreCase(b.object());
    }

    private static SoulTypes.Relation opposite(SoulTypes.Relation relation) {
        return switch (relation) {
            case LIKES -> SoulTypes.Relation.DISLIKES;
            case DISLIKES -> SoulTypes.Relation.LIKES;
            default -> null;
        };
    }

    // === salience lifecycle ===

    /** One consolidation day of forgetting: every relation loses a point, and zero is gone. */
    static List<SoulTypes.RelationFact> decay(List<SoulTypes.RelationFact> relations) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        List<SoulTypes.RelationFact> kept = new ArrayList<>(relations.size());
        for (SoulTypes.RelationFact fact : relations) {
            int salience = fact.salience() - 1;
            if (salience > 0) {
                kept.add(new SoulTypes.RelationFact(fact.subject(), fact.relation(), fact.object(),
                        fact.confidence(), fact.source(), fact.day(), salience));
            }
        }
        return List.copyOf(kept);
    }

    /**
     * A delivered scene recalled this relation: {@link SoulMemoryDigestOps#RECALL_BUMP} salience
     * capped at {@link SoulMemoryDigestOps#MAX_SALIENCE}, and {@code day} moves to the recall day
     * — which is also what {@link #anchors} reads for the {@link SoulMindOps#RECALL_COOLDOWN_DAYS}
     * cooldown, so a fact just spoken is not offered again for three days.
     */
    static List<SoulTypes.RelationFact> noteRecalled(List<SoulTypes.RelationFact> relations,
                                                     SoulTypes.RelationFact which, int day) {
        if (relations == null || relations.isEmpty() || which == null) {
            return relations == null ? List.of() : List.copyOf(relations);
        }
        List<SoulTypes.RelationFact> out = new ArrayList<>(relations.size());
        for (SoulTypes.RelationFact fact : relations) {
            if (sameTriple(fact, which) && fact.day() != day) {
                out.add(new SoulTypes.RelationFact(fact.subject(), fact.relation(), fact.object(),
                        fact.confidence(), fact.source(),
                        day, Math.min(SoulMemoryDigestOps.MAX_SALIENCE,
                                fact.salience() + SoulMemoryDigestOps.RECALL_BUMP)));
            } else {
                out.add(fact);
            }
        }
        return List.copyOf(out);
    }

    // === deterministic SEEN producer ===

    /**
     * The only producer shipped in Phase 3b: the bot watching itself work. Every
     * {@code TASK_COMPLETED} event in {@code events} (already this bot's own journal) is counted by
     * its task bucket (see {@link #bucketOf}), and the single most-completed bucket at or above
     * {@link #SEEN_MIN_COMPLETIONS} becomes {@code <bot> GOOD_AT <bucket>} at
     * {@link #SEEN_CONFIDENCE}. Exactly one fact per fold: GOOD_AT is single-valued and every SEEN
     * fact shares one confidence, so two qualifying buckets in one day would just overwrite each
     * other and make the belief flap. Ties go to the alphabetically first bucket for determinism.
     * BAD_AT is deliberately not produced — failure counting needs a failure event this journal
     * does not carry.
     */
    static List<SoulTypes.RelationFact> fromJournal(List<SoulTypes.SoulEvent> events, String botName, int day) {
        if (events == null || events.isEmpty() || botName == null || botName.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SoulTypes.SoulEvent event : events) {
            if (event == null || event.type() != SoulTypes.EventType.TASK_COMPLETED) {
                continue;
            }
            String bucket = bucketOf(event);
            if (bucket == null || bucket.contains("|")) {
                continue;
            }
            counts.merge(bucket, 1, Integer::sum);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count < SEEN_MIN_COMPLETIONS) {
                continue;
            }
            if (count > bestCount || (count == bestCount && best != null && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        if (best == null) {
            return List.of();
        }
        return List.of(new SoulTypes.RelationFact(botName.trim(), SoulTypes.Relation.GOOD_AT,
                best, SEEN_CONFIDENCE, SoulTypes.RelationSource.SEEN, day, SEEN_SALIENCE));
    }

    /**
     * The bucket one completed task counts toward: the task identifier's suffix after the first
     * {@code ':'} ({@code "skill:woodcut"} → {@code "woodcut"}), the whole task name when it has
     * no prefix, and the {@code category} fact only as a last resort — the category alone is the
     * prefix, which is {@code "skill"} for every skill task and would make one useless belief.
     */
    private static String bucketOf(SoulTypes.SoulEvent event) {
        String task = event.facts().get("task");
        if (task != null && !task.isBlank()) {
            String trimmed = task.trim();
            int colon = trimmed.indexOf(':');
            String candidate = colon >= 0 ? trimmed.substring(colon + 1).trim() : trimmed;
            if (!candidate.isEmpty()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        String category = event.facts().get("category");
        if (category != null && !category.isBlank()) {
            return category.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    // === rendering ===

    /** One fact as an English clause: {@code "Bob is good at mining"}. */
    static String render(SoulTypes.RelationFact fact) {
        return fact.subject() + " " + verb(fact.relation()) + " " + fact.object();
    }

    private static String verb(SoulTypes.Relation relation) {
        return switch (relation) {
            case LIKES -> "likes";
            case DISLIKES -> "dislikes";
            case FEARS -> "fears";
            case GOOD_AT -> "is good at";
            case BAD_AT -> "is bad at";
            case PROMISED -> "promised";
            case WANTS -> "wants";
            case CALLS -> "calls";
        };
    }

    /**
     * The prompt {@code BELIEFS} block for one bot: strongest first, minus anything that already
     * says what an {@code ABOUT} line says (token Jaccard at or above
     * {@link SoulMemoryDigestOps#DUP_JACCARD}), bounded to {@link #MAX_BELIEF_LINES} lines and
     * {@link #MAX_BELIEFS_CHARS} characters — never a partial line.
     */
    static List<String> beliefLines(SoulTypes.SoulMind mind, List<String> aboutFacts) {
        if (mind == null || mind.relations().isEmpty()) {
            return List.of();
        }
        List<SoulTypes.RelationFact> ordered = new ArrayList<>(mind.relations());
        ordered.sort(Comparator.comparingInt(SoulTypes.RelationFact::salience)
                .thenComparingDouble(SoulTypes.RelationFact::confidence)
                .thenComparingInt(SoulTypes.RelationFact::day).reversed());
        List<Set<String>> aboutTokens = new ArrayList<>();
        if (aboutFacts != null) {
            for (String fact : aboutFacts) {
                aboutTokens.add(SoulMemoryDigestOps.tokens(fact));
            }
        }
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (SoulTypes.RelationFact fact : ordered) {
            if (lines.size() == MAX_BELIEF_LINES) {
                break;
            }
            String rendered = render(fact);
            Set<String> tokens = SoulMemoryDigestOps.tokens(rendered);
            boolean duplicate = false;
            for (Set<String> about : aboutTokens) {
                if (SoulMemoryDigestOps.jaccard(tokens, about) >= SoulMemoryDigestOps.DUP_JACCARD) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            String line = "- " + rendered;
            int projected = total + line.length() + (lines.isEmpty() ? 0 : 1);
            if (projected > MAX_BELIEFS_CHARS) {
                break;
            }
            lines.add(line);
            total = projected;
        }
        return List.copyOf(lines);
    }

    // === injection ===

    /**
     * Up to {@link #MAX_RELATION_ANCHORS} banter anchors: the strongest relation off its recall
     * cooldown, plus one more at random, mirroring {@link SoulMindOps#anchors}. A fact about the
     * bot itself is phrased in the first person; anything else keeps {@link #render}'s wording.
     */
    static List<SoulBanterSeed.Anchor> anchors(SoulTypes.SoulMind mind, String botName, int currentDay,
                                               RandomGenerator random) {
        if (mind == null || mind.relations().isEmpty()) {
            return List.of();
        }
        List<SoulTypes.RelationFact> eligible = new ArrayList<>();
        for (SoulTypes.RelationFact fact : mind.relations()) {
            if (fact.day() + SoulMindOps.RECALL_COOLDOWN_DAYS <= currentDay) {
                eligible.add(fact);
            }
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        eligible.sort(Comparator.comparingInt(SoulTypes.RelationFact::salience).reversed());
        List<SoulTypes.RelationFact> pool = new ArrayList<>(eligible);
        List<SoulTypes.RelationFact> picked = new ArrayList<>(MAX_RELATION_ANCHORS);
        while (picked.size() < MAX_RELATION_ANCHORS && !pool.isEmpty()) {
            picked.add(pool.remove(pickWeighted(pool, random)));
        }
        List<SoulBanterSeed.Anchor> out = new ArrayList<>(picked.size());
        for (SoulTypes.RelationFact fact : picked) {
            out.add(new SoulBanterSeed.Anchor(anchorTopic(fact), phrase(fact, botName),
                    RELATION_ANCHOR_WEIGHT));
        }
        return List.copyOf(out);
    }

    /**
     * Salience-weighted index into {@code pool} (already strongest-first). A null {@code random}
     * — or an all-zero-salience pool, which decay makes impossible — falls back to the strongest,
     * keeping the choice deterministic in tests that pass no rng.
     */
    private static int pickWeighted(List<SoulTypes.RelationFact> pool, RandomGenerator random) {
        int total = 0;
        for (SoulTypes.RelationFact fact : pool) {
            total += Math.max(1, fact.salience());
        }
        if (random == null || total <= 0) {
            return 0;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < pool.size(); i++) {
            roll -= Math.max(1, pool.get(i).salience());
            if (roll < 0) {
                return i;
            }
        }
        return pool.size() - 1;
    }

    /** The self-identifying seed topic of one relation anchor. */
    static String anchorTopic(SoulTypes.RelationFact fact) {
        return RELATION_TOPIC_PREFIX + fact.relation().name() + "|" + fact.subject() + "|" + fact.object();
    }

    /**
     * The inverse of {@link #anchorTopic}: a fired seed topic back into the triple it names, so
     * the director can hand it to {@link #noteRecalled}. Empty for any topic that is not one of
     * ours or is malformed.
     */
    static Optional<SoulTypes.RelationFact> parseAnchorTopic(String topic) {
        if (topic == null || !topic.startsWith(RELATION_TOPIC_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = topic.substring(RELATION_TOPIC_PREFIX.length()).split("\\|", -1);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        SoulTypes.Relation relation;
        try {
            relation = SoulTypes.Relation.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return Optional.of(new SoulTypes.RelationFact(parts[1], relation, parts[2],
                0d, SoulTypes.RelationSource.SEEN, -1, 0));
    }

    /** First person when the bot is talking about itself, {@link #render}'s wording otherwise. */
    private static String phrase(SoulTypes.RelationFact fact, String botName) {
        if (botName == null || !botName.equalsIgnoreCase(fact.subject())) {
            return render(fact);
        }
        String verb = switch (fact.relation()) {
            case LIKES -> "like";
            case DISLIKES -> "dislike";
            case FEARS -> "fear";
            case GOOD_AT -> "am good at";
            case BAD_AT -> "am bad at";
            case PROMISED -> "promised";
            case WANTS -> "want";
            case CALLS -> "call";
        };
        return "I " + verb + " " + fact.object();
    }
}
