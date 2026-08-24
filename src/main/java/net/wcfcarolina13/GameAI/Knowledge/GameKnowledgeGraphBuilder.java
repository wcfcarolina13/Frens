package net.wcfcarolina13.GameAI.Knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTable;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
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
            LOGGER.info("[souls] knowledge graph built: {} craftable items, {} tagged items, {} names, {} drop tables",
                    data.craftEdges().size(), data.tags().size(), data.nameIndex().size(),
                    data.drops().size());
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

        // v2: entities join the name index (so "zombie" is a topic) and both blocks and mobs
        // get drop edges by walking their loot tables' JSON (via LootTable.CODEC) for item
        // entries — no reflection into private pools, version-tolerant, mod-inclusive.
        Map<String, List<String>> drops = new LinkedHashMap<>();
        RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            try {
                String idPath = Registries.ENTITY_TYPE.getId(type).getPath();
                displayNames.putIfAbsent(idPath, type.getName().getString());
                nameIndex.putIfAbsent(type.getName().getString().toLowerCase(Locale.ROOT), idPath);
                Identifier typeId = Registries.ENTITY_TYPE.getId(type);
                collectDrops(server, ops,
                        RegistryKey.of(RegistryKeys.LOOT_TABLE,
                                Identifier.of(typeId.getNamespace(), "entities/" + typeId.getPath())),
                        idPath, drops);
            } catch (Throwable ignored) {
            }
        }
        for (Block block : Registries.BLOCK) {
            try {
                block.getLootTableKey().ifPresent(key -> collectDrops(server, ops, key,
                        Registries.BLOCK.getId(block).getPath(), drops));
            } catch (Throwable ignored) {
            }
        }
        return new GameKnowledgeGraph.GraphData(craftEdges, tags, nameIndex, displayNames, drops);
    }

    private static final int MAX_DROPS_PER_NODE = 6;

    /** Extracts the distinct item ids named by a loot table's item entries, capped. */
    private static void collectDrops(MinecraftServer server, RegistryOps<JsonElement> ops,
                                     RegistryKey<LootTable> key, String nodeIdPath,
                                     Map<String, List<String>> drops) {
        LootTable table = server.getReloadableRegistries().getLootTable(key);
        if (table == null || table == LootTable.EMPTY) {
            return;
        }
        JsonElement json = LootTable.CODEC.encodeStart(ops, table).result().orElse(null);
        if (json == null) {
            return;
        }
        List<String> found = new ArrayList<>();
        collectItemNames(json, found);
        if (!found.isEmpty()) {
            drops.put(nodeIdPath, found.stream().distinct().limit(MAX_DROPS_PER_NODE).toList());
        }
    }

    /** Recursively collects "name" values of {"type": "...item"} loot entries from table JSON. */
    private static void collectItemNames(JsonElement element, List<String> out) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement type = object.get("type");
            JsonElement name = object.get("name");
            if (type != null && type.isJsonPrimitive() && name != null && name.isJsonPrimitive()
                    && type.getAsString().endsWith("item")) {
                String id = name.getAsString();
                out.add(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id);
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectItemNames(entry.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectItemNames(child, out));
        }
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
