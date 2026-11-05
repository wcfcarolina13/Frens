package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.KnownCraft;
import net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.Next;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Line;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Limits;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Plan;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Recipe;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy.Shown;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.BundleWhy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.BUCKET;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.CHEST;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.CRAFTING_TABLE;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.CRAFT_SET;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.FURNACE;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.IRON_INGOT;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.LADDER;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.LOGS;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.PLANKS;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.SHEARS;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.SHIELD;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.STICK;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.STONE_CRAFTING;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.TORCH;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.TORCH_FUEL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions behind CraftingHelper's one combined chest ask before a craft: tools first, then the other crafts. */
class CraftBundleAskPolicyTest {

    private static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    private static final String DIAMOND_PICKAXE = "minecraft:diamond_pickaxe";
    private static final String DIAMOND = "minecraft:diamond";

    private static Plan plan(Function<String, Recipe> recipes, Map<String, Integer> have, Map<String, Integer> chest) {
        return CraftSupplyNeedPolicy.plan(CRAFT_SET, 1, recipes, have, chest, Limits.defaults());
    }

    private static int count(Plan plan, String item) {
        for (Line line : plan.fromChest()) {
            if (line.item().equals(item)) {
                return line.count();
            }
        }
        return 0;
    }

    // ── recipes ──────────────────────────────────────────────────────────────────────────────

    @Test
    void woodenPickaxeFromAnEmptyPackNextToLogsAndSticksAsksForLogsAndSticksOnce() {
        // The sandbox check: an empty bot, a chest with logs and sticks, a table already near.
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(), Map.of(LOGS, 20, STICK, 20));
        assertTrue(plan.complete(), plan.toString());
        assertEquals(2, count(plan, STICK));
        assertEquals(1, count(plan, LOGS), "3 planks come from 1 log");
        assertEquals(List.of(LOGS, STICK), plan.fromChest().stream().map(Line::item).toList(),
                "the head comes first in the recipe, so its logs are asked first");
    }

    @Test
    void aTableThatMustBeMadeAddsItsFourPlanksToTheSameAsk() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, true);
        Plan plan = plan(recipes, Map.of(), Map.of(LOGS, 20, STICK, 20));
        assertTrue(plan.complete());
        assertEquals(2, count(plan, LOGS), "3 + 4 planks need 2 logs");
        assertEquals(2, count(plan, STICK));
    }

    @Test
    void aChestWithPlanksIsAskedForPlanksNotLogs() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(), Map.of(PLANKS, 10, STICK, 10, LOGS, 10));
        assertTrue(plan.complete());
        assertEquals(3, count(plan, PLANKS));
        assertEquals(2, count(plan, STICK));
        assertEquals(0, count(plan, LOGS));
    }

    @Test
    void aChestWithPlanksOnlyHasTheSticksMadeFromItsPlanks() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(), Map.of(PLANKS, 10));
        assertTrue(plan.complete());
        assertEquals(5, count(plan, PLANKS), "3 for the head, 2 for one stick craft");
        assertEquals(0, count(plan, STICK));
    }

    @Test
    void aBotHoldingLogsMakesItsOwnPlanksAndAsksOnlyForSticks() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(LOGS, 1), Map.of(PLANKS, 10, STICK, 10));
        assertTrue(plan.complete());
        assertEquals(0, count(plan, PLANKS));
        assertEquals(2, count(plan, STICK));
    }

    @Test
    void aBotWithEverythingNeedsNothingFromTheChest() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(PLANKS, 3, STICK, 2), Map.of(PLANKS, 10, STICK, 10));
        assertTrue(plan.nothingNeeded());
        assertEquals(-1, CraftBundleAskPolicy.chooseChest(List.of(plan)));
    }

    @Test
    void aNonWoodHeadIsItsOwnLineAndTwoToolsDoubleEverything() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(DIAMOND_PICKAXE, DIAMOND, 3, 2, 2, false);
        Plan plan = plan(recipes, Map.of(), Map.of(DIAMOND, 20, STICK, 20));
        assertTrue(plan.complete());
        assertEquals(6, count(plan, DIAMOND));
        assertEquals(4, count(plan, STICK));
    }

    @Test
    void recipesOnlyKnowTheCraftTheyWereBuiltFor() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        assertNull(recipes.apply("minecraft:iron_ingot"));
        assertNull(recipes.apply(LOGS), "logs are raw");
        assertEquals(4, recipes.apply(STICK).outputCount());
        assertEquals(4, recipes.apply(PLANKS).outputCount());
        assertEquals(Map.of(PLANKS, 4), recipes.apply(CRAFTING_TABLE).inputs());
    }

    @Test
    void poolKeysGroupPlanksAndLogsAndLeaveTheRestAlone() {
        assertEquals(PLANKS, CraftBundleAskPolicy.poolKey("minecraft:birch_planks", true, false));
        assertEquals(LOGS, CraftBundleAskPolicy.poolKey("minecraft:oak_log", false, true));
        assertEquals(STICK, CraftBundleAskPolicy.poolKey(STICK, false, false));
    }

    // ── choosing the chest and skipping a material ───────────────────────────────────────────

    @Test
    void theFirstCompleteChestWinsOverANearerPartialOne() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan near = plan(recipes, Map.of(), Map.of(STICK, 10));
        Plan far = plan(recipes, Map.of(), Map.of(STICK, 10, LOGS, 10));
        assertFalse(near.complete());
        assertTrue(far.complete());
        assertEquals(1, CraftBundleAskPolicy.chooseChest(List.of(near, far)));
    }

    @Test
    void withNoCompleteChestTheNearestWithAnythingToGiveIsAsked() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan empty = plan(recipes, Map.of(), Map.of());
        Plan partial = plan(recipes, Map.of(), Map.of(STICK, 10));
        assertEquals(1, CraftBundleAskPolicy.chooseChest(List.of(empty, partial)));
        assertEquals(-1, CraftBundleAskPolicy.chooseChest(List.of(empty)));
        assertEquals(-1, CraftBundleAskPolicy.chooseChest(List.of()));
        assertEquals(-1, CraftBundleAskPolicy.chooseChest(null));
    }

    @Test
    void aMaterialWhoseHeadNoChestHasIsSkippedWithoutAskingForItsSticks() {
        // A bare "/bot craft pickaxe" tries diamond first: a chest with sticks must not be asked
        // for sticks "to make a diamond pickaxe".
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(DIAMOND_PICKAXE, DIAMOND, 3, 2, 1, false);
        Plan sticksOnly = plan(recipes, Map.of(), Map.of(STICK, 10, LOGS, 10));
        assertEquals(SupplyBundlePromptPolicy.Decision.ASK_PARTIAL, SupplyBundlePromptPolicy.decide(sticksOnly));
        assertTrue(CraftBundleAskPolicy.headHopeless(List.of(sticksOnly), CraftBundleAskPolicy.headKeys(DIAMOND)));
        Plan withDiamonds = plan(recipes, Map.of(), Map.of(STICK, 10, DIAMOND, 10));
        assertFalse(CraftBundleAskPolicy.headHopeless(List.of(sticksOnly, withDiamonds),
                CraftBundleAskPolicy.headKeys(DIAMOND)), "another chest has the head");
    }

    @Test
    void woodIsHopelessWhenNoChestHasPlanksOrLogs() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, false);
        Plan sticksOnly = plan(recipes, Map.of(), Map.of(STICK, 10));
        assertEquals(List.of(PLANKS, LOGS), CraftBundleAskPolicy.headKeys(PLANKS));
        assertTrue(CraftBundleAskPolicy.headHopeless(List.of(sticksOnly), CraftBundleAskPolicy.headKeys(PLANKS)),
                "planks short of logs show up as missing logs");
        assertFalse(CraftBundleAskPolicy.headHopeless(List.of(), CraftBundleAskPolicy.headKeys(PLANKS)),
                "no chest at all is today's path, not a skip");
    }

    @Test
    void aBotThatHoldsTheHeadIsNeverHopeless() {
        Function<String, Recipe> recipes = CraftBundleAskPolicy.toolRecipes(DIAMOND_PICKAXE, DIAMOND, 3, 2, 1, false);
        Plan plan = plan(recipes, Map.of(DIAMOND, 3), Map.of(STICK, 10));
        assertTrue(plan.complete());
        assertFalse(CraftBundleAskPolicy.headHopeless(List.of(plan), CraftBundleAskPolicy.headKeys(DIAMOND)));
    }

    // ── lines to stacks ──────────────────────────────────────────────────────────────────────

    @Test
    void aPooledLineIsSplitAcrossTheChestsStacksInOrder() {
        assertArrayEquals(new int[]{3, 0}, CraftBundleAskPolicy.splitLine(3, new int[]{5, 2}));
        assertArrayEquals(new int[]{2, 1}, CraftBundleAskPolicy.splitLine(3, new int[]{2, 2}));
        assertArrayEquals(new int[]{0, 3}, CraftBundleAskPolicy.splitLine(3, new int[]{0, 4}));
        assertArrayEquals(new int[]{2, 2}, CraftBundleAskPolicy.splitLine(7, new int[]{2, 2}), "falls short, never over");
        assertArrayEquals(new int[]{0}, CraftBundleAskPolicy.splitLine(0, new int[]{5}));
        assertArrayEquals(new int[]{0}, CraftBundleAskPolicy.splitLine(2, new int[]{-1}), "a bad count gives nothing");
        assertArrayEquals(new int[0], CraftBundleAskPolicy.splitLine(2, null));
    }

    @Test
    void poolNamesReadAsWordsAndOtherItemsAreLeftToTheAdapter() {
        assertEquals("planks", CraftBundleAskPolicy.poolDisplayName(PLANKS, 3));
        assertEquals("plank", CraftBundleAskPolicy.poolDisplayName(PLANKS, 1));
        assertEquals("logs", CraftBundleAskPolicy.poolDisplayName(LOGS, 2));
        assertEquals("log", CraftBundleAskPolicy.poolDisplayName(LOGS, 1));
        assertNull(CraftBundleAskPolicy.poolDisplayName(STICK, 2));
    }

    // ── after the ask ────────────────────────────────────────────────────────────────────────

    @Test
    void afterTheBundleTheCraftGoesOnStopsQuietlyOrStopsWithALine() {
        assertEquals(Next.CONTINUE, CraftBundleAskPolicy.afterBundle(Kind.MOVED, BundleWhy.NONE));
        assertEquals(Next.CONTINUE, CraftBundleAskPolicy.afterBundle(Kind.READY, BundleWhy.NONE));
        assertEquals(Next.STOP_SILENT, CraftBundleAskPolicy.afterBundle(Kind.WAITING, BundleWhy.NONE),
                "a prompt is open on the server thread: the prompt is the message");
        assertEquals(Next.STOP_WITH_LINE, CraftBundleAskPolicy.afterBundle(Kind.REFUSED, BundleWhy.OWNER_DECLINED));
        assertEquals(Next.STOP_WITH_LINE, CraftBundleAskPolicy.afterBundle(Kind.REFUSED, BundleWhy.NO_ANSWER));
        assertEquals(Next.CONTINUE, CraftBundleAskPolicy.afterBundle(Kind.REFUSED, BundleWhy.OTHER),
                "busy, no owner, not running: today's per-item path says what it always said");
        assertEquals(Next.CONTINUE, CraftBundleAskPolicy.afterBundle(Kind.REFUSED, BundleWhy.NONE));
    }

    @Test
    void thePurposeNamesTheToolAndTheTable() {
        assertEquals("a wooden pickaxe", CraftBundleAskPolicy.purposeText("wooden pickaxe", 1, false));
        assertEquals("a wooden pickaxe and a crafting table", CraftBundleAskPolicy.purposeText("wooden pickaxe", 1, true));
        assertEquals("2 stone axes", CraftBundleAskPolicy.purposeText("stone axe", 2, false));
        assertEquals("that", CraftBundleAskPolicy.purposeText(" ", 1, false));
    }

    @Test
    void theStopLineSaysWhatWasAskedAndWhyTheCraftEnds() {
        List<Shown> asked = List.of(new Shown(2, "Stick"), new Shown(1, "Oak Log"));
        assertEquals("Okay, I won't take 2 Stick and 1 Oak Log from the chest, so I can't make a wooden pickaxe right now.",
                CraftBundleAskPolicy.stopLine("a wooden pickaxe", asked, BundleWhy.OWNER_DECLINED));
        assertEquals("No answer about 2 Stick and 1 Oak Log from the chest, so I'll leave a wooden pickaxe for now.",
                CraftBundleAskPolicy.stopLine("a wooden pickaxe", asked, BundleWhy.NO_ANSWER));
        assertEquals("Okay, I won't take the materials from the chest, so I can't make that right now.",
                CraftBundleAskPolicy.stopLine(null, List.of(), BundleWhy.OWNER_DECLINED));
    }

    // ── the other crafts (vanilla 1.21.11 recipe data) ───────────────────────────────────────

    private static Plan planKnown(String id, int amount, boolean withTable, Map<String, Integer> have, Map<String, Integer> chest) {
        KnownCraft craft = CraftBundleAskPolicy.known(id);
        assertTrue(craft != null, "known craft " + id);
        return plan(CraftBundleAskPolicy.craftRecipes(craft, amount, withTable), have, chest);
    }

    @Test
    void theKnownTableHoldsEveryCraftTheHelperMakesWithTheVanillaCounts() {
        assertEquals(List.of(LADDER, TORCH, SHIELD, BUCKET, SHEARS, FURNACE, CHEST, CRAFTING_TABLE),
                CraftBundleAskPolicy.knownIds());
        // Ladder: 7 sticks -> 3 ladders (data/minecraft/recipe/ladder.json; wiki Ladder).
        assertEquals(new Recipe(3, Map.of(STICK, 7)), CraftBundleAskPolicy.known(LADDER).recipe());
        // Torch: 1 coal or charcoal + 1 stick -> 4 torches (torch.json; wiki Torch), fuel first as in the grid.
        Recipe torch = CraftBundleAskPolicy.known(TORCH).recipe();
        assertEquals(4, torch.outputCount());
        assertEquals(List.of(TORCH_FUEL, STICK), List.copyOf(torch.inputs().keySet()));
        assertEquals(Map.of(TORCH_FUEL, 1, STICK, 1), torch.inputs());
        // Shield: 6 planks (#wooden_tool_materials) + 1 iron ingot (shield.json; wiki Shield).
        assertEquals(Map.of(PLANKS, 6, IRON_INGOT, 1), CraftBundleAskPolicy.known(SHIELD).recipe().inputs());
        assertEquals(1, CraftBundleAskPolicy.known(SHIELD).recipe().outputCount());
        // Bucket: 3 iron ingots; shears: 2 (bucket.json, shears.json; wiki Bucket, Shears).
        assertEquals(new Recipe(1, Map.of(IRON_INGOT, 3)), CraftBundleAskPolicy.known(BUCKET).recipe());
        assertEquals(new Recipe(1, Map.of(IRON_INGOT, 2)), CraftBundleAskPolicy.known(SHEARS).recipe());
        // Furnace: 8 #stone_crafting_materials (furnace.json; wiki Furnace).
        assertEquals(new Recipe(1, Map.of(STONE_CRAFTING, 8)), CraftBundleAskPolicy.known(FURNACE).recipe());
        // Chest: 8 planks; crafting table: 4 planks (chest.json, crafting_table.json; wiki Chest, Crafting Table).
        assertEquals(new Recipe(1, Map.of(PLANKS, 8)), CraftBundleAskPolicy.known(CHEST).recipe());
        assertEquals(new Recipe(1, Map.of(PLANKS, 4)), CraftBundleAskPolicy.known(CRAFTING_TABLE).recipe());
        assertNull(CraftBundleAskPolicy.known("minecraft:door"), "an unknown craft keeps today's path");
        assertNull(CraftBundleAskPolicy.known(null));
    }

    @Test
    void aBedIsThreeMatchingWoolAndThreePlanks() {
        // <colour>_bed.json: "###" wool over "XXX" planks; the wiki's Bed page: matching wool, any planks.
        KnownCraft bed = CraftBundleAskPolicy.bedCraft("minecraft:red_bed", "minecraft:red_wool");
        assertEquals("minecraft:red_bed", bed.itemId());
        assertEquals(List.of("minecraft:red_wool", PLANKS), List.copyOf(bed.recipe().inputs().keySet()));
        assertEquals(Map.of("minecraft:red_wool", 3, PLANKS, 3), bed.recipe().inputs());
        assertEquals("bed", bed.singular());
        assertEquals("beds", bed.plural());
    }

    @Test
    void laddersNextToAChestWithLogsAskForLogsOrPlanksAndSticksOnce() {
        // The sandbox check: an empty bot asked for ladders, a chest with logs, a table near.
        Plan logsOnly = planKnown(LADDER, 3, false, Map.of(), Map.of(LOGS, 20));
        assertTrue(logsOnly.complete(), logsOnly.toString());
        assertEquals(1, count(logsOnly, LOGS), "7 sticks are 2 stick crafts, 4 planks, 1 log");
        Plan logsAndSticks = planKnown(LADDER, 3, false, Map.of(), Map.of(LOGS, 20, STICK, 20));
        assertEquals(List.of(new Line(STICK, 7)), logsAndSticks.fromChest(), "sticks as they are beat making them");
        Plan planksAndSticks = planKnown(LADDER, 6, true, Map.of(STICK, 4), Map.of(PLANKS, 20, STICK, 20));
        assertTrue(planksAndSticks.complete());
        assertEquals(10, count(planksAndSticks, STICK), "14 sticks for 6 ladders, 4 held");
        assertEquals(4, count(planksAndSticks, PLANKS), "the table's planks join the same ask");
    }

    @Test
    void torchesAskForCoalOrCharcoalAndSticksWithoutATable() {
        Plan plan = planKnown(TORCH, 8, false, Map.of(), Map.of(TORCH_FUEL, 10, STICK, 10));
        assertTrue(plan.complete());
        assertEquals(List.of(new Line(TORCH_FUEL, 2), new Line(STICK, 2)), plan.fromChest(), "8 torches are 2 crafts");
        Plan fromPlanks = planKnown(TORCH, 4, false, Map.of(TORCH_FUEL, 1), Map.of(PLANKS, 10));
        assertEquals(List.of(new Line(PLANKS, 2)), fromPlanks.fromChest(), "one stick craft covers the stick");
        Plan noFuel = planKnown(TORCH, 4, false, Map.of(), Map.of(STICK, 10));
        assertEquals(List.of(new Line(TORCH_FUEL, 1)), noFuel.missing());
        assertEquals(SupplyBundlePromptPolicy.Decision.ASK_PARTIAL, SupplyBundlePromptPolicy.decide(noFuel));
    }

    @Test
    void aShieldAsksForItsPlanksAndIronAndTheTableWhenNoneIsNear() {
        Plan plan = planKnown(SHIELD, 1, true, Map.of(), Map.of(LOGS, 10, IRON_INGOT, 5));
        assertTrue(plan.complete());
        assertEquals(List.of(new Line(LOGS, 3), new Line(IRON_INGOT, 1)), plan.fromChest(), "6 + 4 planks are 3 logs");
        Plan two = planKnown(SHIELD, 2, false, Map.of(PLANKS, 12), Map.of(IRON_INGOT, 5));
        assertEquals(List.of(new Line(IRON_INGOT, 2)), two.fromChest());
    }

    @Test
    void bucketsAndShearsAskForIronOnly() {
        Plan bucket = planKnown(BUCKET, 1, false, Map.of(), Map.of(IRON_INGOT, 5));
        assertEquals(List.of(new Line(IRON_INGOT, 3)), bucket.fromChest());
        Plan bucketNoTable = planKnown(BUCKET, 1, true, Map.of(), Map.of(IRON_INGOT, 5, PLANKS, 10));
        assertEquals(List.of(new Line(IRON_INGOT, 3), new Line(PLANKS, 4)), bucketNoTable.fromChest());
        Plan shears = planKnown(SHEARS, 2, false, Map.of(IRON_INGOT, 1), Map.of(IRON_INGOT, 5));
        assertEquals(List.of(new Line(IRON_INGOT, 3)), shears.fromChest());
    }

    @Test
    void aFurnaceAsksForEightStoneBlocksOfAnyKind() {
        Plan plan = planKnown(FURNACE, 1, false, Map.of(STONE_CRAFTING, 3), Map.of(STONE_CRAFTING, 20));
        assertEquals(List.of(new Line(STONE_CRAFTING, 5)), plan.fromChest());
        assertEquals(STONE_CRAFTING, CraftBundleAskPolicy.poolKey("minecraft:blackstone", false, false, false, true));
        assertEquals(TORCH_FUEL, CraftBundleAskPolicy.poolKey("minecraft:charcoal", false, false, true, false));
        assertEquals(IRON_INGOT, CraftBundleAskPolicy.poolKey(IRON_INGOT, false, false, false, false));
        assertEquals("cobblestone or similar", CraftBundleAskPolicy.poolDisplayName(STONE_CRAFTING, 8));
        assertEquals("coal or charcoal", CraftBundleAskPolicy.poolDisplayName(TORCH_FUEL, 1));
    }

    @Test
    void aChestAsksForEightPlanksAndACraftingTableForFourAndNeverForItself() {
        Plan chest = planKnown(CHEST, 1, true, Map.of(), Map.of(LOGS, 10));
        assertEquals(List.of(new Line(LOGS, 3)), chest.fromChest(), "8 + 4 planks are 3 logs");
        Plan table = planKnown(CRAFTING_TABLE, 1, true, Map.of(), Map.of(PLANKS, 10));
        assertEquals(List.of(new Line(PLANKS, 4)), table.fromChest(), "withTable never adds a table to a table craft");
        Function<String, Recipe> recipes = CraftBundleAskPolicy.craftRecipes(CraftBundleAskPolicy.known(CRAFTING_TABLE), 1, true);
        assertEquals(Map.of(CRAFTING_TABLE, 1), recipes.apply(CRAFT_SET).inputs());
    }

    @Test
    void theToolRecipesAreTheGeneralOnesWithAToolTarget() {
        Function<String, Recipe> viaTool = CraftBundleAskPolicy.toolRecipes(WOODEN_PICKAXE, PLANKS, 3, 2, 1, true);
        Function<String, Recipe> viaGeneral = CraftBundleAskPolicy.craftRecipes(WOODEN_PICKAXE,
                CraftBundleAskPolicy.toolRecipe(PLANKS, 3, 2), 1, true);
        for (String key : List.of(CRAFT_SET, WOODEN_PICKAXE, STICK, PLANKS, CRAFTING_TABLE)) {
            assertEquals(viaGeneral.apply(key), viaTool.apply(key), key);
        }
        assertEquals(Map.of(PLANKS, 3, STICK, 2), CraftBundleAskPolicy.toolRecipe(PLANKS, 3, 2).inputs());
        assertEquals(Map.of(STICK, 2), CraftBundleAskPolicy.toolRecipe(PLANKS, 0, 2).inputs());
    }

    @Test
    void theBedWoolIsTheFirstColourTheBotOrOneChestCompletes() {
        List<String> wools = List.of("minecraft:white_wool", "minecraft:black_wool", "minecraft:red_wool");
        assertEquals("minecraft:black_wool", CraftBundleAskPolicy.chooseBedWool(wools,
                Map.of("minecraft:black_wool", 3), List.of(Map.of("minecraft:white_wool", 10)), 3),
                "held wool beats the chest's");
        assertEquals("minecraft:red_wool", CraftBundleAskPolicy.chooseBedWool(wools,
                Map.of("minecraft:red_wool", 1), List.of(Map.of("minecraft:white_wool", 1), Map.of("minecraft:red_wool", 2)), 3),
                "the bot plus one chest");
        assertNull(CraftBundleAskPolicy.chooseBedWool(wools, Map.of(),
                List.of(Map.of("minecraft:white_wool", 2), Map.of("minecraft:white_wool", 2)), 3),
                "matching wool split across two chests is no bed");
        assertNull(CraftBundleAskPolicy.chooseBedWool(wools, Map.of(), List.of(), 3));
        assertNull(CraftBundleAskPolicy.chooseBedWool(List.of(), Map.of("minecraft:red_wool", 9), null, 3));
        assertEquals("minecraft:white_wool", CraftBundleAskPolicy.chooseBedWool(wools,
                Map.of(), List.of(Map.of("minecraft:white_wool", 6), Map.of("minecraft:red_wool", 6)), 6),
                "two beds need 6");
    }

    @Test
    void aBedPlanAsksForTheWoolAndThePlanksTogether() {
        KnownCraft bed = CraftBundleAskPolicy.bedCraft("minecraft:white_bed", "minecraft:white_wool");
        Plan plan = plan(CraftBundleAskPolicy.craftRecipes(bed, 1, true), Map.of(),
                Map.of("minecraft:white_wool", 10, LOGS, 10));
        assertTrue(plan.complete());
        assertEquals(List.of(new Line("minecraft:white_wool", 3), new Line(LOGS, 2)), plan.fromChest(),
                "3 + 4 planks are 2 logs");
    }

    @Test
    void thePlanksWantedAfterAMoveCoverTheTargetTheTableAndTheSticksStillShort() {
        Recipe pickaxe = CraftBundleAskPolicy.toolRecipe(PLANKS, 3, 2);
        assertEquals(3 + 4 + 2, CraftBundleAskPolicy.planksWanted(pickaxe, 1, 0, true), "the tool craft's old sum");
        assertEquals(3, CraftBundleAskPolicy.planksWanted(pickaxe, 1, 2, false), "sticks held: none to make");
        assertEquals(4, CraftBundleAskPolicy.planksWanted(CraftBundleAskPolicy.known(LADDER).recipe(), 3, 0, false),
                "7 sticks are 2 crafts");
        assertEquals(8, CraftBundleAskPolicy.planksWanted(CraftBundleAskPolicy.known(LADDER).recipe(), 6, 0, false),
                "14 sticks are 4 crafts");
        assertEquals(6, CraftBundleAskPolicy.planksWanted(CraftBundleAskPolicy.known(SHIELD).recipe(), 1, 0, false));
        assertEquals(0, CraftBundleAskPolicy.planksWanted(CraftBundleAskPolicy.known(BUCKET).recipe(), 1, 0, false));
        assertEquals(4, CraftBundleAskPolicy.planksWanted(null, 1, 0, true));
    }

    @Test
    void thePurposeKnowsEachCraftsPlural() {
        assertEquals("a torch", CraftBundleAskPolicy.purposeText("torch", "torches", 1, false));
        assertEquals("8 torches", CraftBundleAskPolicy.purposeText("torch", "torches", 8, false));
        assertEquals("a pair of shears", CraftBundleAskPolicy.purposeText("pair of shears", "pairs of shears", 1, false));
        assertEquals("2 pairs of shears", CraftBundleAskPolicy.purposeText("pair of shears", "pairs of shears", 2, false));
        assertEquals("a chest and a crafting table", CraftBundleAskPolicy.purposeText("chest", "chests", 1, true));
        assertEquals("3 ladders", CraftBundleAskPolicy.purposeText("ladder", null, 3, false), "a blank plural adds an s");
        assertEquals("that", CraftBundleAskPolicy.purposeText(null, null, 2, false));
        String prompt = SupplyBundlePromptPolicy.promptText("Jake",
                List.of(new Shown(1, "Oak Log")), "3 ladders", 10, 64, 10, List.of());
        assertEquals("Jake asks to take 1 Oak Log from the chest at 10, 64, 10 to make 3 ladders.", prompt);
    }

    @Test
    void theSandboxPromptReadsAsOneCombinedAsk() {
        // What the owner sees for the sandbox check, once the adapter has named the stacks.
        String prompt = SupplyBundlePromptPolicy.promptText("Jake",
                List.of(new Shown(1, "Oak Log"), new Shown(2, "Stick")), "a wooden pickaxe", 10, 64, 10, List.of());
        assertEquals("Jake asks to take 1 Oak Log and 2 Stick from the chest at 10, 64, 10 to make a wooden pickaxe.",
                prompt);
    }
}
