package net.wcfcarolina13.GameAI.services;

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

import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.CRAFTING_TABLE;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.CRAFT_SET;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.LOGS;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.PLANKS;
import static net.wcfcarolina13.GameAI.services.CraftBundleAskPolicy.STICK;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions behind CraftingHelper's one combined chest ask before a tool craft. */
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

    @Test
    void theSandboxPromptReadsAsOneCombinedAsk() {
        // What the owner sees for the sandbox check, once the adapter has named the stacks.
        String prompt = SupplyBundlePromptPolicy.promptText("Jake",
                List.of(new Shown(1, "Oak Log"), new Shown(2, "Stick")), "a wooden pickaxe", 10, 64, 10, List.of());
        assertEquals("Jake asks to take 1 Oak Log and 2 Stick from the chest at 10, 64, 10 to make a wooden pickaxe.",
                prompt);
    }
}
