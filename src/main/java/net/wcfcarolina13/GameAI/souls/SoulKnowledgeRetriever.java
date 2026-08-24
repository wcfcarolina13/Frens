package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.Knowledge.GameKnowledgeGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic pre-retrieval for soul prompts: matches the player's message against the
 * knowledge graph's name index and renders at most {@link #MAX_TOPICS} grounded fact lines
 * (craftability with have/missing diffs, carried counts, tag kinds). Pure — no Minecraft
 * classes, no LLM round-trip; an unmatched message costs zero prompt tokens.
 */
final class SoulKnowledgeRetriever {

    private SoulKnowledgeRetriever() {
    }

    static final int MAX_TOPICS = 2;
    static final int MAX_KNOWLEDGE_CHARS = 380;
    private static final Map<String, String> STATION_NAMES = Map.of(
            "crafting_table", "crafting table",
            "furnace", "furnace",
            "smoker", "smoker",
            "blast_furnace", "blast furnace",
            "campfire", "campfire");

    /** Everything the retriever may consult for one turn, as plain data. */
    record RetrievalContext(Map<String, Integer> itemCounts, List<String> facilityIds,
                            SoulTypes.KnowledgeMemory memory, String dimension,
                            int botX, int botY, int botZ) {
        RetrievalContext {
            itemCounts = itemCounts == null ? Map.of() : Map.copyOf(itemCounts);
            facilityIds = facilityIds == null ? List.of() : List.copyOf(facilityIds);
            memory = memory == null ? SoulTypes.KnowledgeMemory.empty() : memory;
            dimension = dimension == null ? "" : dimension;
        }
    }

    /** Memory-less entry point (kept for callers/tests that predate the memory layers). */
    static List<String> retrieve(String message, Map<String, Integer> itemCounts,
                                 List<String> facilityIds, GameKnowledgeGraph.GraphData graph) {
        return retrieve(message, new RetrievalContext(itemCounts, facilityIds,
                SoulTypes.KnowledgeMemory.empty(), "", 0, 0, 0), graph);
    }

    static List<String> retrieve(String message, RetrievalContext context,
                                 GameKnowledgeGraph.GraphData graph) {
        if (message == null || message.isBlank() || graph.nameIndex().isEmpty()) {
            return List.of();
        }
        List<String> topics = matchTopics(message.toLowerCase(java.util.Locale.ROOT), graph);
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (String topic : topics) {
            List<String> topicLines = new ArrayList<>();
            describeTopic(topic, context.itemCounts(), context.facilityIds(), graph)
                    .ifPresent(topicLines::add);
            rememberedPlaceLine(topic, context, graph).ifPresent(topicLines::add);
            toldFactLine(topic, context).ifPresent(topicLines::add);
            for (String line : topicLines) {
                if (total + line.length() > MAX_KNOWLEDGE_CHARS) {
                    return lines;
                }
                total += line.length();
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * "You remember a <X> about N blocks <direction>." for the nearest same-dimension sighting —
     * suppressed while the facility is currently in sight (the Facilities line covers it).
     */
    private static Optional<String> rememberedPlaceLine(String topic, RetrievalContext context,
                                                        GameKnowledgeGraph.GraphData graph) {
        if (context.facilityIds().contains(topic)) {
            return Optional.empty();
        }
        return context.memory().places().stream()
                .filter(place -> place.idPath().equals(topic))
                .filter(place -> place.dimension().equals(context.dimension()))
                .min(Comparator.comparingLong(place -> squaredDistance(place, context)))
                .map(place -> {
                    long distance = Math.round(Math.sqrt(squaredDistance(place, context)));
                    String display = graph.displayNames().getOrDefault(topic, topic);
                    String article = "aeiou".indexOf(Character.toLowerCase(display.charAt(0))) >= 0
                            ? "an " : "a ";
                    return "You remember " + article + display + " about " + distance + " blocks "
                            + SoulSnapshotBuilder.cardinalDirection(
                                    place.x() - context.botX(), place.z() - context.botZ())
                            + ".";
                });
    }

    private static Optional<String> toldFactLine(String topic, RetrievalContext context) {
        List<SoulTypes.ToldFact> facts = context.memory().toldFacts().getOrDefault(topic, List.of());
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        SoulTypes.ToldFact newest = facts.get(facts.size() - 1);
        return Optional.of(newest.teller() + " told you: \"" + newest.message() + "\"");
    }

    private static long squaredDistance(SoulTypes.KnownPlace place, RetrievalContext context) {
        long dx = place.x() - context.botX();
        long dy = place.y() - context.botY();
        long dz = place.z() - context.botZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Longest-name-first, word-boundary matching with plural tolerance; overlapping spans lose. */
    static List<String> matchTopics(String lowerMessage, GameKnowledgeGraph.GraphData graph) {
        List<String> names = new ArrayList<>(graph.nameIndex().keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        List<int[]> claimed = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        for (String name : names) {
            if (topics.size() >= MAX_TOPICS) {
                break;
            }
            Pattern p = Pattern.compile(
                    "(?<![a-z0-9])" + Pattern.quote(name) + "(?:e?s)?(?![a-z0-9])");
            Matcher m = p.matcher(lowerMessage);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                boolean overlaps = claimed.stream().anyMatch(span -> start < span[1] && end > span[0]);
                if (!overlaps) {
                    claimed.add(new int[] {start, end});
                    topics.add(graph.nameIndex().get(name));
                    break;
                }
            }
        }
        return topics;
    }

    private static Optional<String> describeTopic(String topic, Map<String, Integer> itemCounts,
                                                  List<String> facilityIds,
                                                  GameKnowledgeGraph.GraphData graph) {
        String display = graph.displayNames().getOrDefault(topic, topic);
        List<GameKnowledgeGraph.CraftEdge> edges = graph.craftEdges().getOrDefault(topic, List.of());
        if (!edges.isEmpty()) {
            GameKnowledgeGraph.CraftEdge edge = edges.stream()
                    .filter(e -> facilityIds.contains(e.station()))
                    .findFirst()
                    .orElse(edges.get(0));
            StringBuilder sb = new StringBuilder(display).append(": craft ").append(edge.outputCount())
                    .append(" at ").append(STATION_NAMES.getOrDefault(edge.station(), edge.station()))
                    .append(" from ");
            List<String> reqs = new ArrayList<>();
            for (GameKnowledgeGraph.IngredientReq req : edge.requirements()) {
                String alternatives = req.alternatives().stream()
                        .map(id -> graph.displayNames().getOrDefault(id, id))
                        .reduce((a, b) -> a + " or " + b).orElse("?");
                int have = req.alternatives().stream()
                        .mapToInt(id -> itemCounts.getOrDefault(id, 0)).sum();
                String status = have == 0 ? "MISSING"
                        : have < req.count() ? "have " + have + ", need " + req.count()
                        : "have " + have;
                reqs.add(req.count() + " " + alternatives + " (" + status + ")");
            }
            sb.append(String.join(" + ", reqs));
            if (!facilityIds.contains(edge.station())) {
                sb.append("; no ").append(STATION_NAMES.getOrDefault(edge.station(), edge.station()))
                  .append(" nearby");
            }
            return Optional.of(sb.toString());
        }

        if (facilityIds.contains(topic)) {
            // Currently in sight: the Facilities prompt line already names it with its phrase.
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        int carried = itemCounts.getOrDefault(topic, 0);
        if (carried > 0) {
            parts.add("you carry " + carried);
        }
        SoulBlockKnowledge.phraseFor(topic).ifPresent(parts::add);
        List<String> tags = graph.tags().getOrDefault(topic, List.of());
        if (!tags.isEmpty()) {
            parts.add("a kind of: " + String.join(", ", tags));
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(display + ": " + String.join("; ", parts));
    }
}
