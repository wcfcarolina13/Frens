package net.wcfcarolina13.GameAI.Knowledge;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minecraft-facing projection for {@link GameKnowledgeGraph}: one pass over the server's recipe
 * manager and the item registry, installed on server start and after datapack reloads (see the
 * registration in Frens). Any failure logs once and installs an empty graph, so consumers
 * degrade to exactly the pre-graph behavior.
 */
public final class GameKnowledgeGraphBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-knowledge-graph");
    private static final int MAX_TAGS_PER_ITEM = 8;

    private GameKnowledgeGraphBuilder() {
    }

    /** Builds and installs the graph; never throws. */
    public static void rebuild(MinecraftServer server) {
        try {
            GameKnowledgeGraph.GraphData data = build(server);
            GameKnowledgeGraph.install(data);
            LOGGER.info("[souls] knowledge graph built: {} craftable items, {} tagged items, {} names",
                    data.craftEdges().size(), data.tags().size(), data.nameIndex().size());
        } catch (Throwable t) {
            GameKnowledgeGraph.install(GameKnowledgeGraph.GraphData.empty());
            LOGGER.warn("[souls] knowledge graph build failed; retrieval disabled: {}", t.toString());
        }
    }

    private static GameKnowledgeGraph.GraphData build(MinecraftServer server) {
        Map<String, List<GameKnowledgeGraph.CraftEdge>> craftEdges = new LinkedHashMap<>();
        for (RecipeEntry<?> entry : server.getRecipeManager().values()) {
            try {
                collectRecipe(entry.value(), craftEdges);
            } catch (Throwable ignored) {
                // A single odd recipe (modded, special) must never sink the whole build.
            }
        }

        Map<String, List<String>> tags = new LinkedHashMap<>();
        Map<String, String> nameIndex = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (Item item : Registries.ITEM) {
            String idPath = Registries.ITEM.getId(item).getPath();
            String display = item.getName().getString();
            displayNames.put(idPath, display);
            nameIndex.putIfAbsent(display.toLowerCase(Locale.ROOT), idPath);
            List<String> itemTags = Registries.ITEM.getEntry(item).streamTags()
                    .filter(tag -> "minecraft".equals(tag.id().getNamespace()))
                    .map(tag -> tag.id().getPath())
                    .limit(MAX_TAGS_PER_ITEM)
                    .toList();
            if (!itemTags.isEmpty()) {
                tags.put(idPath, itemTags);
            }
        }
        return new GameKnowledgeGraph.GraphData(craftEdges, tags, nameIndex, displayNames);
    }

    private static void collectRecipe(Recipe<?> recipe,
                                      Map<String, List<GameKnowledgeGraph.CraftEdge>> craftEdges) {
        String station = stationFor(recipe.getType());
        if (station == null) {
            return;
        }
        List<Ingredient> ingredients = recipe.getIngredientPlacement().getIngredients();
        if (ingredients.isEmpty()) {
            return;
        }
        for (RecipeDisplay display : recipe.getDisplays()) {
            ResultInfo result = resultOf(display.result());
            if (result == null) {
                continue;
            }
            // Merge identical ingredient alternative-sets into count-summed requirements.
            Map<List<String>, Integer> merged = new LinkedHashMap<>();
            for (Ingredient ingredient : ingredients) {
                List<String> alternatives = ingredient.getMatchingItems()
                        .map(e -> Registries.ITEM.getId(e.value()).getPath())
                        .toList();
                if (alternatives.isEmpty()) {
                    continue;
                }
                merged.merge(alternatives, 1, Integer::sum);
            }
            if (merged.isEmpty()) {
                return;
            }
            List<GameKnowledgeGraph.IngredientReq> requirements = new ArrayList<>();
            merged.forEach((alternatives, count) ->
                    requirements.add(new GameKnowledgeGraph.IngredientReq(count, alternatives)));
            craftEdges.computeIfAbsent(result.idPath(), k -> new ArrayList<>())
                    .add(new GameKnowledgeGraph.CraftEdge(station, result.count(), requirements));
            return; // first usable display per recipe
        }
    }

    private record ResultInfo(String idPath, int count) {
    }

    private static ResultInfo resultOf(SlotDisplay result) {
        if (result instanceof SlotDisplay.StackSlotDisplay stackDisplay) {
            ItemStack stack = stackDisplay.stack();
            return stack.isEmpty() ? null
                    : new ResultInfo(Registries.ITEM.getId(stack.getItem()).getPath(), stack.getCount());
        }
        if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
            RegistryEntry<Item> item = itemDisplay.item();
            return new ResultInfo(Registries.ITEM.getId(item.value()).getPath(), 1);
        }
        return null;
    }

    private static String stationFor(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return "crafting_table";
        }
        if (type == RecipeType.SMELTING) {
            return "furnace";
        }
        if (type == RecipeType.SMOKING) {
            return "smoker";
        }
        if (type == RecipeType.BLASTING) {
            return "blast_furnace";
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return "campfire";
        }
        return null;
    }
}
