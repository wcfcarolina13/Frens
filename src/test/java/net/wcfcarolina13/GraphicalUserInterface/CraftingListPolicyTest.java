package net.wcfcarolina13.GraphicalUserInterface;

import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.Entry;
import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.Row;
import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.SortMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingListPolicyTest {

    // The list from the field screenshot, in learned order, with vanilla creative-tab categories.
    private static final List<Entry> HISTORY = List.of(
            new Entry("minecraft:oak_planks", "Oak Planks", "Building Blocks", 0, 0),
            new Entry("minecraft:oak_sign", "Oak Sign", "Functional Blocks", 3, 1),
            new Entry("minecraft:torch", "Torch", "Functional Blocks", 3, 2),
            new Entry("minecraft:stick", "Stick", "Ingredients", 8, 3),
            new Entry("minecraft:raw_iron", "Raw Iron", "Natural Blocks", 2, 4),
            new Entry("minecraft:diamond_chestplate", "Diamond Chestplate", "Combat", 6, 5),
            new Entry("minecraft:diamond_helmet", "Diamond Helmet", "Combat", 6, 6),
            new Entry("minecraft:armor_stand", "Armor Stand", "Functional Blocks", 3, 7),
            new Entry("minecraft:jukebox", "Jukebox", "Functional Blocks", 3, 8),
            new Entry("minecraft:barrel", "Barrel", "Functional Blocks", 3, 9),
            new Entry("minecraft:birch_shelf", "Birch Shelf", "Functional Blocks", 3, 10),
            new Entry("minecraft:piston", "Piston", "Redstone Blocks", 4, 11));

    private static List<String> labels(List<Row> rows) {
        return rows.stream().map(r -> r.isHeader() ? "# " + r.header() : r.entry().label()).toList();
    }

    @Test
    void baseTypeReadsTheMaterialFamilyFromTheId() {
        assertEquals("Wood", CraftingListPolicy.baseTypeOf("minecraft:oak_planks"));
        assertEquals("Wood", CraftingListPolicy.baseTypeOf("minecraft:birch_shelf"));
        assertEquals("Wood", CraftingListPolicy.baseTypeOf("minecraft:dark_oak_door"));
        assertEquals("Wood", CraftingListPolicy.baseTypeOf("minecraft:stick"));
        assertEquals("Iron", CraftingListPolicy.baseTypeOf("minecraft:raw_iron"));
        assertEquals("Diamond", CraftingListPolicy.baseTypeOf("minecraft:diamond_chestplate"));
        assertEquals("Stone", CraftingListPolicy.baseTypeOf("minecraft:stone_bricks"));
        assertEquals("Stone", CraftingListPolicy.baseTypeOf("minecraft:polished_andesite"));
        assertEquals("Stone", CraftingListPolicy.baseTypeOf("cobblestone_slab"));
        assertEquals("Wool", CraftingListPolicy.baseTypeOf("minecraft:light_blue_wool"));
        assertEquals("Gold", CraftingListPolicy.baseTypeOf("minecraft:golden_apple"));
        assertEquals("Netherite", CraftingListPolicy.baseTypeOf("minecraft:netherite_sword"));
        assertEquals("Redstone", CraftingListPolicy.baseTypeOf("minecraft:piston"));
        assertEquals(CraftingListPolicy.OTHER, CraftingListPolicy.baseTypeOf("minecraft:armor_stand"));
        assertEquals(CraftingListPolicy.OTHER, CraftingListPolicy.baseTypeOf(""));
    }

    @Test
    void nameSortsAreCaseInsensitiveAndReversible() {
        List<String> asc = labels(CraftingListPolicy.arrange(HISTORY, "", SortMode.NAME_ASC));
        assertEquals("Armor Stand", asc.get(0));
        assertEquals("Torch", asc.get(asc.size() - 1));

        List<String> desc = labels(CraftingListPolicy.arrange(HISTORY, "", SortMode.NAME_DESC));
        assertEquals("Torch", desc.get(0));
        assertEquals("Armor Stand", desc.get(desc.size() - 1));
    }

    @Test
    void learnedKeepsTheServerOrder() {
        List<String> learned = labels(CraftingListPolicy.arrange(HISTORY, null, SortMode.LEARNED));
        assertEquals(HISTORY.stream().map(Entry::label).toList(), learned);
    }

    @Test
    void categoryGroupsFollowTabOrderWithHeadersAndNamesWithin() {
        List<String> rows = labels(CraftingListPolicy.arrange(HISTORY, "", SortMode.CATEGORY));
        assertEquals(List.of(
                "# Building Blocks", "Oak Planks",
                "# Natural Blocks", "Raw Iron",
                "# Functional Blocks", "Armor Stand", "Barrel", "Birch Shelf", "Jukebox", "Oak Sign", "Torch",
                "# Redstone Blocks", "Piston",
                "# Combat", "Diamond Chestplate", "Diamond Helmet",
                "# Ingredients", "Stick"), rows);
    }

    @Test
    void uncategorisedItemsSortLastUnderOther() {
        List<Entry> entries = List.of(
                new Entry("frens:mystery", "Mystery", "", Integer.MAX_VALUE, 0),
                new Entry("minecraft:stick", "Stick", "Ingredients", 8, 1));
        List<String> rows = labels(CraftingListPolicy.arrange(entries, "", SortMode.CATEGORY));
        assertEquals(List.of("# Ingredients", "Stick", "# Other", "Mystery"), rows);
    }

    @Test
    void baseTypeGroupsItemsByMaterial() {
        List<String> rows = labels(CraftingListPolicy.arrange(HISTORY, "", SortMode.BASE_TYPE));
        assertEquals(List.of(
                "# Wood", "Barrel", "Birch Shelf", "Oak Planks", "Oak Sign", "Stick",
                "# Coal", "Torch",
                "# Iron", "Raw Iron",
                "# Redstone", "Piston",
                "# Diamond", "Diamond Chestplate", "Diamond Helmet",
                "# Other", "Armor Stand", "Jukebox"), rows);
    }

    @Test
    void searchMatchesNameIdCategoryAndBaseType() {
        assertEquals(List.of("Diamond Chestplate", "Diamond Helmet"),
                labels(CraftingListPolicy.arrange(HISTORY, "  DIAMOND ", SortMode.NAME_ASC)));
        assertEquals(List.of("Raw Iron"),
                labels(CraftingListPolicy.arrange(HISTORY, "raw_", SortMode.NAME_ASC)));
        assertEquals(List.of("Diamond Chestplate", "Diamond Helmet"),
                labels(CraftingListPolicy.arrange(HISTORY, "combat", SortMode.NAME_ASC)));
        assertEquals(List.of("Barrel", "Birch Shelf", "Oak Planks", "Oak Sign", "Stick"),
                labels(CraftingListPolicy.arrange(HISTORY, "wood", SortMode.NAME_ASC)));
        assertTrue(CraftingListPolicy.arrange(HISTORY, "zzz", SortMode.CATEGORY).isEmpty());
    }

    @Test
    void sortModeCyclesThroughEveryModeAndWraps() {
        SortMode mode = SortMode.NAME_ASC;
        for (int i = 0; i < SortMode.values().length; i++) {
            mode = mode.next();
        }
        assertEquals(SortMode.NAME_ASC, mode);
        assertEquals(SortMode.NAME_DESC, SortMode.NAME_ASC.next());
    }
}
