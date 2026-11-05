package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Line;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Plan;
import net.wcfcarolina13.GameAI.services.supply.CraftSupplyNeedPolicy.Recipe;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy;
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy.Shown;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.BundleWhy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Pure decisions behind {@code CraftingHelper}'s one combined chest ask before a tool craft
 * (Bradley's ruling of 2026-10-23: the bot works out everything the craft needs and asks the chest
 * once). No Minecraft types: items are the pool keys below or plain item ids, and the chest is a
 * list of what each exact stack could grant.
 *
 * <p>The recipe lookup is the one {@code CraftingHelper} already hard-codes for tools: a tool is
 * its head items plus sticks, a stick craft turns 2 planks into 4, a plank craft turns 1 log into
 * 4, a crafting table takes 4 planks. Planks and logs are pools ({@link #PLANKS}, {@link #LOGS}):
 * any plank makes a stick or a tool, and the ask names the pool's actual stacks
 * ({@link #splitLine}).
 *
 * <p><b>Rulings made here (to confirm):</b> a material the chest cannot complete (its head is
 * still missing after the chest) is skipped without asking for its sticks, so a bare
 * {@code /bot craft pickaxe} does not ask for sticks "to make a diamond pickaxe"; with several
 * chests near, the first chest whose plan is complete is asked, else the nearest chest that has
 * anything to give (the partial ruling of {@link SupplyBundlePromptPolicy}).
 */
public final class CraftBundleAskPolicy {

    /** Pool key for any plank. */
    public static final String PLANKS = "#minecraft:planks";
    /** Pool key for any log. */
    public static final String LOGS = "#minecraft:logs";
    public static final String STICK = "minecraft:stick";
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    /** The synthetic target of one craft: the tools asked for, plus a table when one must be made. */
    public static final String CRAFT_SET = "frens:craft_set";

    private CraftBundleAskPolicy() {
    }

    // ── Recipes ──────────────────────────────────────────────────────────────────────────────

    /**
     * The recipe function for {@code amount} tools of one material.
     *
     * @param toolId     the tool's item id (never crafted from the chest: it is not in any pool)
     * @param headKey    the head item: {@link #PLANKS} for wood, else the exact item id
     * @param headCount  head items per tool
     * @param stickCount sticks per tool
     * @param withTable  whether a crafting table must be made first (4 planks)
     */
    public static Function<String, Recipe> toolRecipes(String toolId, String headKey, int headCount,
                                                       int stickCount, int amount, boolean withTable) {
        Map<String, Recipe> recipes = new LinkedHashMap<>();
        Map<String, Integer> set = new LinkedHashMap<>();
        set.put(toolId, Math.max(1, amount));
        if (withTable) {
            set.put(CRAFTING_TABLE, 1);
        }
        recipes.put(CRAFT_SET, new Recipe(1, set));
        Map<String, Integer> tool = new LinkedHashMap<>();
        if (headCount > 0) {
            tool.put(headKey, headCount);
        }
        if (stickCount > 0) {
            tool.merge(STICK, stickCount, Integer::sum);
        }
        recipes.put(toolId, new Recipe(1, tool));
        recipes.put(STICK, new Recipe(4, Map.of(PLANKS, 2)));
        recipes.put(PLANKS, new Recipe(4, Map.of(LOGS, 1)));
        recipes.put(CRAFTING_TABLE, new Recipe(1, Map.of(PLANKS, 4)));
        return recipes::get;
    }

    /** The pool an exact item counts toward: planks and logs pool, anything else is itself. */
    public static String poolKey(String itemId, boolean isPlank, boolean isLog) {
        if (isPlank) {
            return PLANKS;
        }
        if (isLog) {
            return LOGS;
        }
        return itemId;
    }

    // ── Choosing the chest ───────────────────────────────────────────────────────────────────

    /**
     * Which chest to ask, from each chest's plan in nearest-first order: the first complete plan,
     * else the first that has anything to ask; {@code -1} when nothing is needed or no chest has
     * anything to give (today's per-item path then runs).
     */
    public static int chooseChest(List<Plan> plans) {
        if (plans == null) {
            return -1;
        }
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            if (plan != null && !plan.fromChest().isEmpty() && plan.complete()) {
                return i;
            }
        }
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            if (plan != null && !plan.fromChest().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether this material cannot be made from the bot and any of these chests: one of its head
     * keys is still missing in every plan ({@link #PLANKS} and {@link #LOGS} for wood, since planks
     * short of logs show up as missing logs). Such a material is skipped without asking for its
     * sticks. No chest at all is not hopeless: today's path then runs.
     */
    public static boolean headHopeless(List<Plan> plans, List<String> headKeys) {
        if (plans == null || plans.isEmpty() || headKeys == null || headKeys.isEmpty()) {
            return false;
        }
        for (Plan plan : plans) {
            if (plan == null || !missingIncludesAny(plan, headKeys)) {
                return false;
            }
        }
        return true;
    }

    private static boolean missingIncludesAny(Plan plan, List<String> keys) {
        for (Line line : plan.missing()) {
            if (keys.contains(line.item())) {
                return true;
            }
        }
        return false;
    }

    /** The head keys a plan may report as missing for a head: planks also miss as logs. */
    public static List<String> headKeys(String headKey) {
        return PLANKS.equals(headKey) ? List.of(PLANKS, LOGS) : List.of(headKey);
    }

    // ── Lines to stacks ──────────────────────────────────────────────────────────────────────

    /**
     * Splits a pooled line ("3 planks") across the exact stacks of that pool, in listing order,
     * taking as much as each can grant until the line is covered: {@code takes[i]} is what stack
     * {@code i} (with {@code grantable[i]} to give) contributes. Stacks with nothing to grant give
     * 0; the takes fall short when the pool cannot cover the line.
     */
    public static int[] splitLine(int qty, int[] grantable) {
        if (grantable == null) {
            return new int[0];
        }
        int[] takes = new int[grantable.length];
        int left = Math.max(0, qty);
        for (int i = 0; i < grantable.length && left > 0; i++) {
            int take = Math.min(left, Math.max(0, grantable[i]));
            takes[i] = take;
            left -= take;
        }
        return takes;
    }

    /** "planks"/"plank", "logs"/"log" for the pools; anything else is left to the adapter. */
    public static String poolDisplayName(String key, int count) {
        if (PLANKS.equals(key)) {
            return count == 1 ? "plank" : "planks";
        }
        if (LOGS.equals(key)) {
            return count == 1 ? "log" : "logs";
        }
        return null;
    }

    // ── After the ask ────────────────────────────────────────────────────────────────────────

    /** What the craft does after the bundle (or instead of it). */
    public enum Next {
        /** Go on: the existing per-item path counts what the bot now has and crafts or says why. */
        CONTINUE,
        /**
         * A prompt is still open after the wait (the settle step had to ask again, or this call
         * could not wait): stop quietly; a later attempt finds the ticket. Server-thread crafts
         * never ask, so they never reach this.
         */
        STOP_SILENT,
        /** The owner declined or never answered: stop this craft with one line. */
        STOP_WITH_LINE,
        /** No chest here can complete this material ({@link #headHopeless}): ask nothing, try the next. */
        SKIP_MATERIAL
    }

    public static Next afterBundle(Kind kind, BundleWhy why) {
        if (kind == Kind.WAITING) {
            return Next.STOP_SILENT;
        }
        if (kind == Kind.REFUSED && (why == BundleWhy.OWNER_DECLINED || why == BundleWhy.NO_ANSWER)) {
            return Next.STOP_WITH_LINE;
        }
        return Next.CONTINUE;
    }

    /** "a wooden pickaxe", "2 wooden pickaxes", "a wooden pickaxe and a crafting table". */
    public static String purposeText(String toolLabel, int amount, boolean withTable) {
        boolean blank = toolLabel == null || toolLabel.isBlank();
        String what = blank ? "that" : amount > 1 ? amount + " " + toolLabel.strip() + "s" : "a " + toolLabel.strip();
        return withTable ? what + " and a crafting table" : what;
    }

    /** The one line a craft ends with when the owner declined or never answered. */
    public static String stopLine(String purpose, List<Shown> asked, BundleWhy why) {
        String thing = purpose == null || purpose.isBlank() ? "that" : purpose.strip();
        String what = SupplyBundlePromptPolicy.join(asked);
        String items = what.isEmpty() ? "the materials" : what;
        if (why == BundleWhy.NO_ANSWER) {
            return "No answer about " + items + " from the chest, so I'll leave " + thing + " for now.";
        }
        return "Okay, I won't take " + items + " from the chest, so I can't make " + thing + " right now.";
    }
}
