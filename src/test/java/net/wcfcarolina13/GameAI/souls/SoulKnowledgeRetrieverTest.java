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

    // === Memory layers: known places (seen before) and told facts ===

    private static SoulTypes.KnowledgeMemory memoryWith(SoulTypes.KnownPlace... places) {
        return new SoulTypes.KnowledgeMemory(List.of(places), Map.of());
    }

    @Test
    void rememberedPlaceRendersDistanceAndDirectionWhenNotCurrentlySeen() {
        SoulTypes.KnowledgeMemory memory = memoryWith(new SoulTypes.KnownPlace(
                "enchanting_table", "minecraft:overworld", 30, 64, 0, 1000L));
        GameKnowledgeGraph.GraphData graph = new GameKnowledgeGraph.GraphData(
                Map.of(), Map.of(), Map.of("enchanting table", "enchanting_table"),
                Map.of("enchanting_table", "Enchanting Table"));

        List<String> lines = SoulKnowledgeRetriever.retrieve("where is the enchanting table?",
                new SoulKnowledgeRetriever.RetrievalContext(
                        Map.of(), List.of(), memory, "minecraft:overworld", 0, 64, 0),
                graph);

        assertTrue(lines.contains("You remember an Enchanting Table about 30 blocks east."),
                String.valueOf(lines));
    }

    @Test
    void rememberedPlaceIsSuppressedWhenCurrentlyVisibleOrOtherDimension() {
        SoulTypes.KnowledgeMemory memory = memoryWith(
                new SoulTypes.KnownPlace("enchanting_table", "minecraft:overworld", 30, 64, 0, 1000L),
                new SoulTypes.KnownPlace("lodestone", "minecraft:the_nether", 5, 64, 0, 1000L));
        GameKnowledgeGraph.GraphData graph = new GameKnowledgeGraph.GraphData(
                Map.of(), Map.of(),
                Map.of("enchanting table", "enchanting_table", "lodestone", "lodestone"),
                Map.of("enchanting_table", "Enchanting Table", "lodestone", "Lodestone"));

        List<String> visibleCase = SoulKnowledgeRetriever.retrieve("the enchanting table?",
                new SoulKnowledgeRetriever.RetrievalContext(
                        Map.of(), List.of("enchanting_table"), memory, "minecraft:overworld", 0, 64, 0),
                graph);
        List<String> wrongDimension = SoulKnowledgeRetriever.retrieve("the lodestone?",
                new SoulKnowledgeRetriever.RetrievalContext(
                        Map.of(), List.of(), memory, "minecraft:overworld", 0, 64, 0),
                graph);

        // Currently visible: the Facilities prompt line already covers it — no duplicate lines.
        assertEquals(List.of(), visibleCase);
        // Other dimension: never a "you remember" line (phrase-only knowledge is still allowed).
        assertTrue(wrongDimension.stream().noneMatch(l -> l.contains("remember")),
                String.valueOf(wrongDimension));
    }

    @Test
    void toldFactRendersTellerAndQuote() {
        SoulTypes.KnowledgeMemory memory = new SoulTypes.KnowledgeMemory(List.of(),
                Map.of("barrel", List.of(
                        new SoulTypes.ToldFact("Roti", "the spare picks are in the barrel by the gate", 1000L))));
        GameKnowledgeGraph.GraphData graph = new GameKnowledgeGraph.GraphData(
                Map.of(), Map.of(), Map.of("barrel", "barrel"), Map.of("barrel", "Barrel"));

        List<String> lines = SoulKnowledgeRetriever.retrieve("check the barrel",
                new SoulKnowledgeRetriever.RetrievalContext(
                        Map.of(), List.of(), memory, "minecraft:overworld", 0, 64, 0),
                graph);

        assertTrue(lines.stream().anyMatch(l ->
                l.equals("Roti told you: \"the spare picks are in the barrel by the gate\"")),
                String.valueOf(lines));
    }

    @Test
    void legacyEntryPointStillWorksWithoutMemory() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "can you make a torch?", Map.of("stick", 4), List.of("crafting_table"), torchGraph());
        assertEquals(1, lines.size());
    }

    @Test
    void unknownWordsInsideLongerWordsDoNotMatch() {
        // "stick" must not match inside "sticking"; "torch" not inside "torchlight-style prose".
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "the mud is sticking to my boots", Map.of(), List.of(), torchGraph());
        assertEquals(List.of(), lines);
    }

    // === v2: loot/drop edges ===

    private static GameKnowledgeGraph.GraphData dropsGraph() {
        return new GameKnowledgeGraph.GraphData(
                Map.of(), Map.of(),
                Map.of("diamond ore", "diamond_ore", "zombie", "zombie", "oak log", "oak_log"),
                Map.of("diamond_ore", "Diamond Ore", "zombie", "Zombie", "oak_log", "Oak Log",
                        "diamond", "Diamond", "rotten_flesh", "Rotten Flesh", "iron_ingot", "Iron Ingot"),
                Map.of("diamond_ore", List.of("diamond_ore", "diamond"),
                       "zombie", List.of("rotten_flesh", "iron_ingot"),
                       "oak_log", List.of("oak_log")));
    }

    @Test
    void dropsLineRendersForMatchedTopic() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "what does diamond ore drop?", Map.of(), List.of(), dropsGraph());
        assertTrue(lines.contains("Diamond Ore drops: Diamond"), String.valueOf(lines));
    }

    @Test
    void mobDropsRender() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "what do zombies drop?", Map.of(), List.of(), dropsGraph());
        assertTrue(lines.contains("Zombie drops: Rotten Flesh, Iron Ingot"), String.valueOf(lines));
    }

    @Test
    void selfDropsAreSuppressedAsNoise() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "oak log?", Map.of(), List.of(), dropsGraph());
        assertTrue(lines.stream().noneMatch(l -> l.contains("drops:")), String.valueOf(lines));
    }

    @Test
    void silkTouchSelfDropIsFilteredWhenOtherDropsExist() {
        List<String> lines = SoulKnowledgeRetriever.retrieve(
                "what does diamond ore drop?", Map.of(), List.of(), dropsGraph());
        assertTrue(lines.contains("Diamond Ore drops: Diamond"), String.valueOf(lines));
        assertTrue(lines.stream().noneMatch(l -> l.contains("Diamond Ore drops: Diamond Ore")),
                String.valueOf(lines));
    }
}
