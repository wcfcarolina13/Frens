package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.GameAI.Knowledge.GameKnowledgeGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulKnowledgeRetrieverTest {

    private static GameKnowledgeGraph.GraphData torchGraph() {
        return new GameKnowledgeGraph.GraphData(
                Map.of("torch", List.of(new GameKnowledgeGraph.CraftEdge("crafting_table", 4,
                        List.of(new GameKnowledgeGraph.IngredientReq(1, List.of("stick")),
                                new GameKnowledgeGraph.IngredientReq(1, List.of("coal", "charcoal")))))),
                Map.of("oak_log", List.of("logs", "logs_that_burn")),
                Map.of("torch", "torch", "oak log", "oak_log", "stick", "stick"),
                Map.of("torch", "Torch", "oak_log", "Oak Log", "stick", "Stick",
                        "coal", "Coal", "charcoal", "Charcoal"));
    }

    @Test
    void noTopicMatchYieldsNoLines() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "how are you feeling today?", Map.of(), List.of(), torchGraph());
        assertEquals(List.of(), lines);
    }

    @Test
    void craftableTopicRendersRecipeWithHaveAndMissingDiff() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "can you make a torch?", Map.of("stick", 4), List.of("crafting_table"), torchGraph());

        assertEquals(List.of(
                "Torch: craft 4 at crafting table from 1 Stick (have 4) + 1 Coal or Charcoal (MISSING)"),
                lines);
    }

    @Test
    void pluralMentionStillMatches() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "we need torches", Map.of("stick", 4, "coal", 2), List.of("crafting_table"), torchGraph());

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("Torch: craft 4 at crafting table"), lines.get(0));
        assertTrue(lines.get(0).contains("1 Coal or Charcoal (have 2)"), lines.get(0));
    }

    @Test
    void missingStationIsCalledOut() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "can you make a torch?", Map.of("stick", 4, "coal", 2), List.of(), torchGraph());

        assertTrue(lines.get(0).endsWith("; no crafting table nearby"), lines.get(0));
    }

    @Test
    void partialHaveShowsNeed() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "can you make a torch?",
                Map.of("stick", 4),
                List.of("crafting_table"),
                new GameKnowledgeGraph.GraphData(
                        Map.of("torch", List.of(new GameKnowledgeGraph.CraftEdge("crafting_table", 4,
                                List.of(new GameKnowledgeGraph.IngredientReq(6, List.of("stick")))))),
                        Map.of(), Map.of("torch", "torch"), Map.of("torch", "Torch", "stick", "Stick")));

        assertTrue(lines.get(0).contains("6 Stick (have 4, need 6)"), lines.get(0));
    }

    @Test
    void uncraftableCarriedTopicRendersCountAndTags() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "what about this oak log?", Map.of("oak_log", 12), List.of(), torchGraph());

        assertEquals(List.of("Oak Log: you carry 12; a kind of: logs, logs_that_burn"), lines);
    }

    @Test
    void atMostTwoTopicsAndBudgetRespected() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "torch stick oak log", Map.of("oak_log", 1, "stick", 2), List.of(), torchGraph());

        assertTrue(lines.size() <= 2, String.valueOf(lines));
        int total = lines.stream().mapToInt(String::length).sum();
        assertTrue(total <= 380, "budget exceeded: " + total);
    }

    @Test
    void unknownWordsInsideLongerWordsDoNotMatch() {
        // "stick" must not match inside "sticking"; "torch" not inside "torchlight-style prose".
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "the mud is sticking to my boots", Map.of(), List.of(), torchGraph());
        assertEquals(List.of(), lines);
    }
}
