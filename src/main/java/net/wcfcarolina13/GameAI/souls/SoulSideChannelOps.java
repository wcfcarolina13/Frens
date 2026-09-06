package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure parser for the {@code ##FRENS} structured side channel (conversation ontology Phase 3c).
 *
 * <p>The scene grammar stays plain {@code Name: line} prose — small local models fail JSON far more
 * often than tagged dialogue — so the model may OPTIONALLY append one final line beginning with
 * {@code ##FRENS} carrying a compact JSON object of what it thinks changed. The validator ends the
 * scene at that line and hands the raw tail here; nothing in this class can lose a scene line.
 *
 * <p>Every element is validated and dropped individually: an unparseable tail yields empty effects
 * with {@link SideEffects#unparsed()} set, unknown keys are ignored, and a bad stance magnitude or
 * an off-roster subject drops just that element. Model-asserted confidence is capped at
 * {@link #MAX_MODEL_CONFIDENCE} before it ever reaches {@link SoulRelationOps#normalise}.
 *
 * <p>{@code java.util} + Jackson only — no game classes, so every rule here is unit-testable as
 * plain data.
 */
final class SoulSideChannelOps {

    /** Sentinel token that opens the side-channel line. */
    static final String SENTINEL = "##FRENS";
    /** At most this many facts are taken from one scene; the rest are counted as dropped. */
    static final int MAX_FACTS = 3;
    /** Whatever the model claims, an inferred fact is held no more firmly than this. */
    static final double MAX_MODEL_CONFIDENCE = 0.6d;
    /** Same forgetting budget as the deterministic SEEN producer. */
    static final int FACT_SALIENCE = SoulRelationOps.SEEN_SALIENCE;
    /** The one subject that is not a scene participant. */
    static final String WORLD_SUBJECT = SoulRelationOps.WORLD_SUBJECT;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The three stance axes the schema exposes, mapped onto {@link SoulTypes.Stance}. */
    enum Axis {
        /** → {@code Stance.trust}. */
        WARMTH,
        /** → {@code Stance.exasperation}. */
        FRICTION,
        /** → {@code Stance.curiosity}. */
        CURIOSITY
    }

    /** One accepted stance move: {@code peerName} is a roster display name, delta is -1 or +1. */
    record StanceDelta(String peerName, Axis axis, int delta) {
    }

    /** One accepted fact tuple, still unnormalised (the caller runs {@link SoulRelationOps}). */
    record FactCandidate(String subject, String relation, String object, double confidence) {
    }

    /**
     * Outcome of {@link #parse}. Counts are of ELEMENTS SEEN AND REJECTED, so
     * {@code stanceDeltas.size() + droppedStance} is how many stance entries the model wrote.
     * {@code unparsed} is true only when a sentinel was present but its tail was not a JSON object.
     */
    record SideEffects(List<StanceDelta> stanceDeltas, List<FactCandidate> facts,
                       int droppedStance, int droppedFacts, boolean unparsed) {

        static final SideEffects EMPTY = new SideEffects(List.of(), List.of(), 0, 0, false);
        static final SideEffects UNPARSED = new SideEffects(List.of(), List.of(), 0, 0, true);

        SideEffects {
            stanceDeltas = stanceDeltas == null ? List.of() : List.copyOf(stanceDeltas);
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        /** True when there is nothing at all to apply and nothing at all to report. */
        boolean isEmpty() {
            return stanceDeltas.isEmpty() && facts.isEmpty() && droppedStance == 0 && droppedFacts == 0;
        }
    }

    private SoulSideChannelOps() {
    }

    /**
     * Parses the raw tail the validator captured after {@code ##FRENS}.
     *
     * @param raw the trimmed text after the sentinel; empty/blank yields {@link SideEffects#EMPTY}
     * @param rosterNames display names of the bots in the scene — the only legal stance targets,
     *     and (with {@code ownerName} and {@code "the world"}) the only legal fact subjects
     * @param ownerName the scene owner's (player's) display name: a legal fact subject, never a
     *     stance target — owner stance stays rule-driven so the model cannot flatter itself into
     *     the player's good graces
     */
    static SideEffects parse(Optional<String> raw, Set<String> rosterNames, String ownerName) {
        if (raw == null || raw.isEmpty() || raw.get().isBlank()) {
            return SideEffects.EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw.get());
        } catch (Exception malformed) {
            return SideEffects.UNPARSED;
        }
        if (root == null || !root.isObject()) {
            return SideEffects.UNPARSED;
        }
        Set<String> roster = rosterNames == null ? Set.of() : rosterNames;
        Set<String> subjects = new LinkedHashSet<>(roster);
        if (ownerName != null && !ownerName.isBlank()) {
            subjects.add(ownerName);
        }

        List<StanceDelta> deltas = new ArrayList<>();
        int droppedStance = parseStance(root.get("stance"), roster, ownerName, deltas);
        List<FactCandidate> facts = new ArrayList<>();
        int droppedFacts = parseFacts(root.get("facts"), subjects, facts);
        // "threads" is parsed-and-ignored on purpose: closing a specific question needs a
        // per-question markAnswered overload the mind ops do not have yet (Phase 3c deferral).
        return new SideEffects(deltas, facts, droppedStance, droppedFacts, false);
    }

    /** @return the number of stance entries seen and rejected. */
    private static int parseStance(JsonNode stance, Set<String> roster, String ownerName,
                                   List<StanceDelta> out) {
        if (stance == null || !stance.isObject()) {
            return 0;
        }
        int dropped = 0;
        Set<String> claimed = new LinkedHashSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = stance.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> peerEntry = it.next();
            String peerName = matchName(roster, peerEntry.getKey());
            boolean isOwner = ownerName != null && ownerName.equalsIgnoreCase(
                    peerEntry.getKey() == null ? "" : peerEntry.getKey().trim());
            JsonNode axes = peerEntry.getValue();
            if (axes == null || !axes.isObject()) {
                dropped++;
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> ai = axes.fields(); ai.hasNext(); ) {
                Map.Entry<String, JsonNode> axisEntry = ai.next();
                Axis axis = axisFor(axisEntry.getKey());
                if (axis == null) {
                    continue; // unknown key — ignored, not counted (the schema says so)
                }
                JsonNode value = axisEntry.getValue();
                if (value == null || !value.isIntegralNumber()) {
                    dropped++;
                    continue;
                }
                int delta = value.asInt();
                if (delta < -1 || delta > 1) {
                    dropped++;
                    continue;
                }
                if (peerName == null || isOwner) {
                    dropped++; // off-roster name, or the owner: never a peer stance target
                    continue;
                }
                if (delta == 0) {
                    continue; // an explicit no-op is not a drop
                }
                // One move per (peer, axis) per scene, first writer wins.
                if (!claimed.add(peerName.toLowerCase(Locale.ROOT) + "|" + axis)) {
                    dropped++;
                    continue;
                }
                out.add(new StanceDelta(peerName, axis, delta));
            }
        }
        return dropped;
    }

    /** @return the number of fact tuples seen and rejected (over-cap extras included). */
    private static int parseFacts(JsonNode facts, Set<String> allowedSubjects, List<FactCandidate> out) {
        if (facts == null || !facts.isArray()) {
            return 0;
        }
        int dropped = 0;
        for (JsonNode tuple : facts) {
            if (out.size() >= MAX_FACTS) {
                dropped++; // over the per-scene cap — truncated, and counted
                continue;
            }
            if (tuple == null || !tuple.isArray() || tuple.size() != 4) {
                dropped++;
                continue;
            }
            String subject = text(tuple.get(0));
            String relation = text(tuple.get(1));
            String object = text(tuple.get(2));
            JsonNode confidence = tuple.get(3);
            if (subject.isEmpty() || object.isEmpty() || confidence == null || !confidence.isNumber()) {
                dropped++;
                continue;
            }
            if (!isKnownRelation(relation)) {
                dropped++;
                continue;
            }
            String matched = WORLD_SUBJECT.equalsIgnoreCase(subject)
                    ? WORLD_SUBJECT : matchName(allowedSubjects, subject);
            if (matched == null) {
                dropped++;
                continue;
            }
            double c = Math.max(0d, Math.min(MAX_MODEL_CONFIDENCE, confidence.asDouble()));
            out.add(new FactCandidate(matched, relation.trim().toUpperCase(Locale.ROOT), object, c));
        }
        return dropped;
    }

    private static boolean isKnownRelation(String relation) {
        if (relation.isEmpty()) {
            return false;
        }
        try {
            SoulTypes.Relation.valueOf(relation.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static Axis axisFor(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "warmth" -> Axis.WARMTH;
            case "friction" -> Axis.FRICTION;
            case "curiosity" -> Axis.CURIOSITY;
            default -> null;
        };
    }

    /** The canonical spelling of {@code candidate} from {@code names}, or null when absent. */
    private static String matchName(Set<String> names, String candidate) {
        if (names == null || candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(trimmed)) {
                return name;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText().trim();
    }
}
