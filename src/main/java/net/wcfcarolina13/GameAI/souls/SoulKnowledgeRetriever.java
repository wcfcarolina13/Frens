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

    static List<String> retrieve(String message, Map<String, Integer> itemCounts,
                                 List<String> facilityIds, GameKnowledgeGraph.GraphData graph) {
        if (message == null || message.isBlank() || graph.nameIndex().isEmpty()) {
            return List.of();
        }
        List<String> topics = matchTopics(message.toLowerCase(java.util.Locale.ROOT), graph);
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (String topic : topics) {
            Optional<String> line = describeTopic(topic, itemCounts, facilityIds, graph);
            if (line.isEmpty()) {
                continue;
            }
            if (total + line.get().length() > MAX_KNOWLEDGE_CHARS) {
                break;
            }
            total += line.get().length();
            lines.add(line.get());
        }
        return lines;
    }

    /** Longest-name-first, word-boundary matching with plural tolerance; overlapping spans lose. */
    private static List<String> matchTopics(String lowerMessage, GameKnowledgeGraph.GraphData graph) {
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
