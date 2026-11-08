package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Limits;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Line;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Plan;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Recipe;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftSupplyNeedPolicyTest {

    private static final String PICK = "wooden_pickaxe";
    private static final String PLANKS = "#planks";
    private static final String STICK = "stick";
    private static final String LOG = "#logs";
    private static final String IRON = "iron_ingot";
    private static final String TORCH = "torch";
    private static final String COAL = "coal";

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return out;
    }

    private static final Map<String, Recipe> BOOK = new HashMap<>();

    static {
        BOOK.put(PICK, new Recipe(1, m(PLANKS, 3, STICK, 2)));
        BOOK.put(STICK, new Recipe(4, m(PLANKS, 2)));
        BOOK.put(PLANKS, new Recipe(4, m(LOG, 1)));
        BOOK.put("iron_pickaxe", new Recipe(1, m(IRON, 3, STICK, 2)));
        BOOK.put(TORCH, new Recipe(4, m(COAL, 1, STICK, 1)));
    }

    private static final Function<String, Recipe> RECIPES = BOOK::get;

    private static Plan plan(String target, int count, Map<String, Integer> have, Map<String, Integer> chest) {
        return CraftSupplyNeedPolicy.plan(target, count, RECIPES, have, chest, Limits.defaults());
    }

    @Test
    void bradleysExampleAsksForPlanksAndSticksInOneSet() {
        Plan p = plan(PICK, 1, Map.of(), m(PLANKS, 10, STICK, 10));
        assertEquals(List.of(new Line(PLANKS, 3), new Line(STICK, 2)), p.fromChest());
        assertTrue(p.complete());
        assertFalse(p.truncated());
    }

    @Test
    void whatTheBotCarriesIsSubtractedFirst() {
        Plan p = plan(PICK, 1, m(PLANKS, 2, STICK, 2), m(PLANKS, 10, STICK, 10));
        assertEquals(List.of(new Line(PLANKS, 1)), p.fromChest());
        assertTrue(p.complete());
    }

    @Test
    void botWithEverythingNeedsNothing() {
        Plan p = plan(PICK, 1, m(PLANKS, 3, STICK, 2), m(PLANKS, 10));
        assertTrue(p.nothingNeeded());
    }

    @Test
    void ownLogsAreCraftedBeforeAskingTheChest() {
        // 2 logs -> 8 planks: 3 for the head, 2 for sticks (4 sticks, 2 left). No ask.
        Plan p = plan(PICK, 1, m(LOG, 2), m(PLANKS, 64, STICK, 64));
        assertTrue(p.nothingNeeded(), p.toString());
    }

    @Test
    void oneOwnLogIsNotEnoughSoTheChestCoversTheRest() {
        // 1 log -> 4 planks: 3 head; sticks need 2 planks, only 1 left -> stick line from the chest.
        Plan p = plan(PICK, 1, m(LOG, 1), m(STICK, 10));
        assertEquals(List.of(new Line(STICK, 2)), p.fromChest());
        assertTrue(p.complete(), p.toString());
    }

    @Test
    void sticksAreCraftedFromChestPlanksWhenTheChestHasNoSticks() {
        Plan p = plan(PICK, 1, Map.of(), m(PLANKS, 10));
        // 3 planks for the head, 2 for one batch of sticks: one line of 5 planks.
        assertEquals(List.of(new Line(PLANKS, 5)), p.fromChest());
        assertTrue(p.complete());
    }

    @Test
    void chestPlanksAreAskedAsPlanksNotFlattenedToLogs() {
        Plan p = plan(PICK, 1, Map.of(), m(PLANKS, 10, LOG, 10));
        assertEquals(List.of(new Line(PLANKS, 5)), p.fromChest());
    }

    @Test
    void logsAreAskedWhenTheChestHasNoPlanks() {
        // 5 planks -> 2 logs (one batch for 3, then 1 surplus + 1 more batch for the sticks' 2).
        Plan p = plan(PICK, 1, Map.of(), m(LOG, 10));
        int logs = p.fromChest().stream().filter(l -> l.item().equals(LOG)).mapToInt(Line::count).sum();
        assertEquals(2, logs, p.toString());
        assertTrue(p.complete());
    }

    @Test
    void partialChestAsksForWhatItHasAndNamesTheRest() {
        Plan p = plan("iron_pickaxe", 1, Map.of(), m(STICK, 10));
        assertEquals(List.of(new Line(STICK, 2)), p.fromChest());
        assertEquals(List.of(new Line(IRON, 3)), p.missing());
        assertFalse(p.complete());
    }

    @Test
    void emptyChestReportsLeafMissingItems() {
        Plan p = plan(PICK, 1, Map.of(), Map.of());
        assertTrue(p.fromChest().isEmpty());
        assertEquals(List.of(new Line(LOG, 2)), p.missing(), p.toString());
    }

    @Test
    void batchesRoundUpAndSurplusIsReused() {
        // 8 torches = 2 batches: 2 coal, 2 sticks (one stick batch = 4 from 2 planks).
        Plan p = plan(TORCH, 8, Map.of(), m(COAL, 10, PLANKS, 10));
        assertEquals(List.of(new Line(COAL, 2), new Line(PLANKS, 2)), p.fromChest());
    }

    @Test
    void depthCapStopsExpansion() {
        // Depth 0: only the target is crafted; its ingredients can't be made from the chest's logs.
        Plan p = CraftSupplyNeedPolicy.plan(PICK, 1, RECIPES, Map.of(), m(LOG, 10), new Limits(0, 6));
        assertTrue(p.fromChest().isEmpty(), p.toString());
        assertEquals(List.of(new Line(PLANKS, 3), new Line(STICK, 2)), p.missing());
        // Depth 1: the head's planks come from a log, but the sticks' planks (depth 2) can't be made.
        Plan one = CraftSupplyNeedPolicy.plan(PICK, 1, RECIPES, Map.of(), m(LOG, 10), new Limits(1, 6));
        assertEquals(List.of(new Line(LOG, 1)), one.fromChest(), one.toString());
        assertEquals(List.of(new Line(PLANKS, 1)), one.missing());
        // Depth 2: everything resolves to logs.
        Plan two = CraftSupplyNeedPolicy.plan(PICK, 1, RECIPES, Map.of(), m(LOG, 10), new Limits(2, 6));
        assertTrue(two.complete(), two.toString());
    }

    @Test
    void cycleResolvesAsMissingInsteadOfLooping() {
        Map<String, Recipe> cyc = Map.of("a", new Recipe(1, m("b", 1)), "b", new Recipe(1, m("a", 1)));
        Plan p = CraftSupplyNeedPolicy.plan("a", 1, cyc::get, Map.of(), Map.of(), new Limits(10, 6));
        // b is crafted from a, and a is already on the path: a is the missing leaf.
        assertEquals(List.of(new Line("a", 1)), p.missing());
    }

    @Test
    void lineCapMovesOverflowToMissingAndFlagsIt() {
        Map<String, Recipe> wide = Map.of("x", new Recipe(1, m("a", 1, "b", 1, "c", 1)));
        Plan p = CraftSupplyNeedPolicy.plan("x", 1, wide::get, Map.of(), m("a", 5, "b", 5, "c", 5),
                new Limits(3, 2));
        assertEquals(List.of(new Line("a", 1), new Line("b", 1)), p.fromChest());
        assertEquals(List.of(new Line("c", 1)), p.missing());
        assertTrue(p.truncated());
    }

    @Test
    void uncraftableTargetIsMissing() {
        Plan p = plan("diamond", 2, Map.of(), Map.of("diamond", 5));
        assertTrue(p.fromChest().isEmpty());
        assertEquals(List.of(new Line("diamond", 2)), p.missing());
    }

    @Test
    void zeroOrNullTargetPlansNothing() {
        assertTrue(plan(PICK, 0, Map.of(), Map.of()).nothingNeeded());
        assertTrue(plan(null, 1, Map.of(), Map.of()).nothingNeeded());
    }

    @Test
    void chestStockIsNotDoubleSpentAcrossBranches() {
        // Head takes 3 planks, sticks need 2 more planks; chest has only 4 -> 1 plank short -> log missing.
        Plan p = plan(PICK, 1, Map.of(), m(PLANKS, 4));
        assertEquals(List.of(new Line(PLANKS, 4)), p.fromChest());
        assertEquals(List.of(new Line(LOG, 1)), p.missing(), p.toString());
    }

    @Test
    void planNeedsMergesSharedIngredients() {
        Plan p = CraftSupplyNeedPolicy.planNeeds(m(PLANKS, 3, STICK, 2), RECIPES, Map.of(),
                m(PLANKS, 20), Limits.defaults());
        assertEquals(List.of(new Line(PLANKS, 5)), p.fromChest());
    }

    @Test
    void nonPositiveAndNullEntriesAreIgnored() {
        Map<String, Integer> have = new HashMap<>();
        have.put(PLANKS, -4);
        have.put(null, 9);
        Plan p = plan(PICK, 1, have, m(PLANKS, 10, STICK, 10));
        assertEquals(List.of(new Line(PLANKS, 3), new Line(STICK, 2)), p.fromChest());
    }
}
