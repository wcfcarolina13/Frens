package net.wcfcarolina13.GameAI.Knowledge;

import java.util.List;
import java.util.Map;

/**
 * In-memory lookup graph projected from the game's own runtime data — recipes and item tags —
 * so souls (and later other systems) can answer "what is X / can we make X" from authoritative
 * data instead of model recall. Nothing here is authored: the game rebuilds the source data
 * every launch, so the graph carries no persistence and no version drift; modded content is
 * included automatically.
 *
 * <p>This class holds the plain-data model ({@link GraphData} and friends) with no Minecraft
 * types in any signature, so every consumer and test stays free of game classes. The
 * Minecraft-facing projection lives in {@link GameKnowledgeGraphBuilder}, which populates the
 * holder on server start and after datapack reloads.
 */
public final class GameKnowledgeGraph {

    private GameKnowledgeGraph() {
    }

    /** One ingredient requirement: {@code count} items drawn from any of {@code alternatives}. */
    public record IngredientReq(int count, List<String> alternatives) {
        public IngredientReq {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    /** One way to produce an item: at {@code station}, yielding {@code outputCount}. */
    public record CraftEdge(String station, int outputCount, List<IngredientReq> requirements) {
        public CraftEdge {
            station = station == null ? "" : station;
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }
    }

    /**
     * The full projected graph. {@code craftEdges}: output item id path → ways to make it.
     * {@code tags}: item id path → tag paths (capped at build time). {@code nameIndex}:
     * lowercase display name → id path, for matching player messages. {@code displayNames}:
     * id path → display name, for rendering.
     */
    public record GraphData(Map<String, List<CraftEdge>> craftEdges,
                            Map<String, List<String>> tags,
                            Map<String, String> nameIndex,
                            Map<String, String> displayNames,
                            Map<String, List<String>> drops) {
        /** Pre-drops shape (v1 graph); defaults {@code drops} to empty. */
        public GraphData(Map<String, List<CraftEdge>> craftEdges,
                         Map<String, List<String>> tags,
                         Map<String, String> nameIndex,
                         Map<String, String> displayNames) {
            this(craftEdges, tags, nameIndex, displayNames, Map.of());
        }

        public GraphData {
            craftEdges = craftEdges == null ? Map.of() : Map.copyOf(craftEdges);
            tags = tags == null ? Map.of() : Map.copyOf(tags);
            nameIndex = nameIndex == null ? Map.of() : Map.copyOf(nameIndex);
            displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
            drops = drops == null ? Map.of() : Map.copyOf(drops);
        }

        public static GraphData empty() {
            return new GraphData(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    private static volatile GraphData current = GraphData.empty();

    /** The current graph; {@link GraphData#empty()} until the builder has run. */
    public static GraphData current() {
        return current;
    }

    /** Installs a freshly built graph. Called by the builder on server start / datapack reload. */
    public static void install(GraphData data) {
        current = data == null ? GraphData.empty() : data;
    }
}
