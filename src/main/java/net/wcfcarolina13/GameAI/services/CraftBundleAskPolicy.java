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
 * Pure decisions behind {@code CraftingHelper}'s one combined chest ask before a craft
 * (Bradley's ruling of 2026-10-23: the bot works out everything the craft needs and asks the chest
 * once). No Minecraft types: items are the pool keys below or plain item ids, and the chest is a
 * list of what each exact stack could grant.
 *
 * <p>The recipe lookup is the one {@code CraftingHelper} already hard-codes: a tool is its head
 * items plus sticks, a stick craft turns 2 planks into 4, a plank craft turns 1 log into 4, a
 * crafting table takes 4 planks, and the other crafts the helper makes are in {@link #known}
 * (ladder, torch, shield, bucket, shears, furnace, chest, crafting table; a bed through
 * {@link #bedCraft}), each checked against the vanilla 1.21.11 recipe data. Planks and logs are
 * pools ({@link #PLANKS}, {@link #LOGS}), as are a torch's coal-or-charcoal ({@link #TORCH_FUEL})
 * and a furnace's cobblestone-like blocks ({@link #STONE_CRAFTING}): any member makes the craft,
 * and the ask names the pool's actual stacks ({@link #splitLine}).
 *
 * <p><b>Rulings made here (to confirm):</b> a material the chest cannot complete (its head is
 * still missing after the chest) is skipped without asking for its sticks, so a bare
 * {@code /bot craft pickaxe} does not ask for sticks "to make a diamond pickaxe"; with several
 * chests near, the first chest whose plan is complete is asked, else the nearest chest that has
 * anything to give (the partial ruling of {@link SupplyBundlePromptPolicy}); a craft with a line
 * one chest must complete whole (a bed's matching wool, {@link KnownCraft#mustComplete()}) is
 * asked of a complete chest only, never for the planks of a bed whose wool is elsewhere
 * ({@link #chooseChest(List, String)}).
 */
public final class CraftBundleAskPolicy {

    /** Pool key for any plank. */
    public static final String PLANKS = "#minecraft:planks";
    /** Pool key for any log. */
    public static final String LOGS = "#minecraft:logs";
    /** Pool key for a torch's fuel: coal or charcoal. */
    public static final String TORCH_FUEL = "#frens:torch_fuel";
    /** Pool key for a furnace's blocks: the vanilla {@code stone_crafting_materials} tag (cobblestone, cobbled deepslate, blackstone). */
    public static final String STONE_CRAFTING = "#minecraft:stone_crafting_materials";
    public static final String STICK = "minecraft:stick";
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    public static final String IRON_INGOT = "minecraft:iron_ingot";
    public static final String LADDER = "minecraft:ladder";
    public static final String TORCH = "minecraft:torch";
    public static final String SHIELD = "minecraft:shield";
    public static final String BUCKET = "minecraft:bucket";
    public static final String SHEARS = "minecraft:shears";
    public static final String FURNACE = "minecraft:furnace";
    public static final String CHEST = "minecraft:chest";
    public static final String GOLD_INGOT = "minecraft:gold_ingot";
    public static final String DIAMOND = "minecraft:diamond";
    /** The synthetic target of one craft: the tools asked for, plus a table when one must be made. */
    public static final String CRAFT_SET = "frens:craft_set";

    private CraftBundleAskPolicy() {
    }

    // ── Recipes ──────────────────────────────────────────────────────────────────────────────

    /**
     * One craft the helper knows how to make: the item, its recipe (a pool or an exact item per
     * ingredient, in the order the prompt lists them), the words the purpose uses ("a shield",
     * "2 shields") and, for a craft whose one chest must complete a line whole, that line's key
     * ({@code mustComplete}: a bed's matching wool; {@code null} for every other craft, whose
     * partial chest is asked for what it has). See {@link #chooseChest(List, String)}.
     */
    public record KnownCraft(String itemId, Recipe recipe, String singular, String plural, String mustComplete) {
        public KnownCraft(String itemId, Recipe recipe, String singular, String plural) {
            this(itemId, recipe, singular, plural, null);
        }
    }

    /**
     * The crafts {@code CraftingHelper} makes besides tools, with the vanilla 1.21.11 recipe facts
     * (recipe data {@code data/minecraft/recipe/<id>.json}; minecraft.wiki pages Ladder, Torch,
     * Shield, Bucket, Shears, Furnace, Chest, Crafting Table):
     * <ul>
     *   <li>ladder: 7 sticks → 3 ladders (3×3);</li>
     *   <li>torch: 1 coal or charcoal + 1 stick → 4 torches (2×2);</li>
     *   <li>shield: 6 planks (the {@code wooden_tool_materials} tag) + 1 iron ingot (3×3);</li>
     *   <li>bucket: 3 iron ingots (3×3); shears: 2 iron ingots (2×2);</li>
     *   <li>furnace: 8 {@code stone_crafting_materials} (3×3);</li>
     *   <li>chest: 8 planks (3×3); crafting table: 4 planks (2×2);</li>
     *   <li>iron, golden and diamond armour (backlog 1217; {@code <material>_<piece>.json} and the
     *       minecraft.wiki Helmet, Chestplate, Leggings and Boots crafting grids): helmet 5,
     *       chestplate 8, leggings 7, boots 4 of the iron ingot, gold ingot or diamond, all 3×3
     *       (the wiki's Armor page: "A full set ... requires 24 units of a given material").</li>
     * </ul>
     */
    private static final Map<String, KnownCraft> KNOWN = knownCrafts();

    private static Map<String, KnownCraft> knownCrafts() {
        Map<String, KnownCraft> known = new LinkedHashMap<>();
        put(known, LADDER, new Recipe(3, Map.of(STICK, 7)), "ladder", "ladders");
        Map<String, Integer> torch = new LinkedHashMap<>();
        torch.put(TORCH_FUEL, 1);
        torch.put(STICK, 1);
        put(known, TORCH, new Recipe(4, torch), "torch", "torches");
        Map<String, Integer> shield = new LinkedHashMap<>();
        shield.put(PLANKS, 6);
        shield.put(IRON_INGOT, 1);
        put(known, SHIELD, new Recipe(1, shield), "shield", "shields");
        put(known, BUCKET, new Recipe(1, Map.of(IRON_INGOT, 3)), "bucket", "buckets");
        put(known, SHEARS, new Recipe(1, Map.of(IRON_INGOT, 2)), "pair of shears", "pairs of shears");
        put(known, FURNACE, new Recipe(1, Map.of(STONE_CRAFTING, 8)), "furnace", "furnaces");
        put(known, CHEST, new Recipe(1, Map.of(PLANKS, 8)), "chest", "chests");
        put(known, CRAFTING_TABLE, new Recipe(1, Map.of(PLANKS, 4)), "crafting table", "crafting tables");
        putArmor(known, "iron", "iron", IRON_INGOT);
        putArmor(known, "golden", "golden", GOLD_INGOT);
        putArmor(known, "diamond", "diamond", DIAMOND);
        return known;
    }

    /** A metal's four armour pieces: helmet 5, chestplate 8, leggings 7, boots 4 of {@code material}. */
    private static void putArmor(Map<String, KnownCraft> known, String idPrefix, String word, String material) {
        put(known, "minecraft:" + idPrefix + "_helmet", new Recipe(1, Map.of(material, 5)),
                word + " helmet", word + " helmets");
        put(known, "minecraft:" + idPrefix + "_chestplate", new Recipe(1, Map.of(material, 8)),
                word + " chestplate", word + " chestplates");
        put(known, "minecraft:" + idPrefix + "_leggings", new Recipe(1, Map.of(material, 7)),
                "pair of " + word + " leggings", "pairs of " + word + " leggings");
        put(known, "minecraft:" + idPrefix + "_boots", new Recipe(1, Map.of(material, 4)),
                "pair of " + word + " boots", "pairs of " + word + " boots");
    }

    private static void put(Map<String, KnownCraft> known, String id, Recipe recipe, String singular, String plural) {
        known.put(id, new KnownCraft(id, recipe, singular, plural));
    }

    /** The known craft for an item id, or {@code null}: the craft then keeps today's per-item path. */
    public static KnownCraft known(String itemId) {
        return itemId == null ? null : KNOWN.get(itemId);
    }

    /** Every known craft's item id, in table order (for tests and logs). */
    public static List<String> knownIds() {
        return List.copyOf(KNOWN.keySet());
    }

    /**
     * A bed of one colour: 3 wool of that colour + 3 planks → 1 bed (3×3; recipe data
     * {@code <colour>_bed.json}, wiki page Bed: "the wool color must match, the planks can differ").
     * The wool is the line one chest must complete ({@link KnownCraft#mustComplete()}): planks
     * can be made from logs or pulled per item afterwards, matching wool cannot.
     */
    public static KnownCraft bedCraft(String bedId, String woolId) {
        Map<String, Integer> bed = new LinkedHashMap<>();
        bed.put(woolId, 3);
        bed.put(PLANKS, 3);
        return new KnownCraft(bedId, new Recipe(1, bed), "bed", "beds", woolId);
    }

    /**
     * How much coal or charcoal a torch craft still has to pull after the bundle, counting both
     * fuels the bot holds: the bundle grants either (the {@link #TORCH_FUEL} pool), so charcoal in
     * the pack must not open a second prompt for coal. The craft pulls this coal first, then
     * charcoal for what coal could not cover, as it did before the bundle when nothing was held.
     */
    public static int torchFuelShortfall(int craftsNeeded, int coalHeld, int charcoalHeld) {
        return Math.max(0, craftsNeeded - Math.max(0, coalHeld) - Math.max(0, charcoalHeld));
    }

    /**
     * Which wool a bed craft should use, given what the bot holds and what each chest could grant
     * per wool id (nearest chest first): the first colour the bot already has enough of, else the
     * first colour the bot plus one chest cover; {@code null} when no colour can be completed (the
     * craft then keeps today's path, which also tries string). Beds need matching wool, so a
     * colour split across two chests does not count.
     */
    public static String chooseBedWool(List<String> woolIds, Map<String, Integer> have,
                                       List<Map<String, Integer>> chestPools, int needed) {
        if (woolIds == null || woolIds.isEmpty()) {
            return null;
        }
        for (String wool : woolIds) {
            if (count(have, wool) >= needed) {
                return wool;
            }
        }
        if (chestPools != null) {
            for (String wool : woolIds) {
                int held = count(have, wool);
                for (Map<String, Integer> chest : chestPools) {
                    if (held + count(chest, wool) >= needed) {
                        return wool;
                    }
                }
            }
        }
        return null;
    }

    private static int count(Map<String, Integer> pool, String key) {
        if (pool == null || key == null) {
            return 0;
        }
        Integer n = pool.get(key);
        return n == null ? 0 : Math.max(0, n);
    }

    /**
     * The recipe function for {@code amount} of one craft: the synthetic set ({@link #CRAFT_SET}:
     * the target, plus a crafting table when one must be made), the target's own recipe, and the
     * wood sub-crafts (2 planks → 4 sticks, 1 log → 4 planks, 4 planks → table). Anything else is
     * raw and comes from the chest as it is.
     */
    public static Function<String, Recipe> craftRecipes(String targetId, Recipe target, int amount, boolean withTable) {
        Map<String, Recipe> recipes = new LinkedHashMap<>();
        Map<String, Integer> set = new LinkedHashMap<>();
        set.put(targetId, Math.max(1, amount));
        if (withTable && !CRAFTING_TABLE.equals(targetId)) {
            set.put(CRAFTING_TABLE, 1);
        }
        recipes.put(CRAFT_SET, new Recipe(1, set));
        recipes.put(targetId, target);
        recipes.put(STICK, new Recipe(4, Map.of(PLANKS, 2)));
        recipes.put(PLANKS, new Recipe(4, Map.of(LOGS, 1)));
        recipes.putIfAbsent(CRAFTING_TABLE, new Recipe(1, Map.of(PLANKS, 4)));
        return recipes::get;
    }

    /** {@link #craftRecipes} for a known craft. */
    public static Function<String, Recipe> craftRecipes(KnownCraft craft, int amount, boolean withTable) {
        return craftRecipes(craft.itemId(), craft.recipe(), amount, withTable);
    }

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
        return craftRecipes(toolId, toolRecipe(headKey, headCount, stickCount), amount, withTable);
    }

    /** One tool: its head items plus its sticks. */
    public static Recipe toolRecipe(String headKey, int headCount, int stickCount) {
        Map<String, Integer> tool = new LinkedHashMap<>();
        if (headCount > 0) {
            tool.put(headKey, headCount);
        }
        if (stickCount > 0) {
            tool.merge(STICK, stickCount, Integer::sum);
        }
        return new Recipe(1, tool);
    }

    /**
     * How many planks the craft counted on having after a bundle moved, so taken logs are turned
     * into exactly those (as the per-item path would): the target's planks over its batches, the
     * table's 4, and 2 per stick craft still short.
     */
    public static int planksWanted(Recipe target, int amount, int sticksHeld, boolean withTable) {
        if (target == null || target.outputCount() <= 0) {
            return withTable ? 4 : 0;
        }
        int batches = (Math.max(1, amount) + target.outputCount() - 1) / target.outputCount();
        int planks = count(target.inputs(), PLANKS) * batches;
        int sticksShort = Math.max(0, count(target.inputs(), STICK) * batches - Math.max(0, sticksHeld));
        return planks + (withTable ? 4 : 0) + 2 * ((sticksShort + 3) / 4);
    }

    /** The pool an exact item counts toward: planks and logs pool, anything else is itself. */
    public static String poolKey(String itemId, boolean isPlank, boolean isLog) {
        return poolKey(itemId, isPlank, isLog, false, false);
    }

    /**
     * The pool an exact item counts toward: planks, logs, torch fuel (coal or charcoal) and the
     * furnace's stone blocks pool; anything else is itself.
     */
    public static String poolKey(String itemId, boolean isPlank, boolean isLog, boolean isTorchFuel,
                                 boolean isStoneCrafting) {
        if (isPlank) {
            return PLANKS;
        }
        if (isLog) {
            return LOGS;
        }
        if (isTorchFuel) {
            return TORCH_FUEL;
        }
        if (isStoneCrafting) {
            return STONE_CRAFTING;
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
     * {@link #chooseChest(List)} for a craft with a line one chest must complete whole
     * ({@code mustComplete}, a bed's matching wool; {@code null} is the plain rule). A chest whose
     * plan still misses that line is never asked: taking its planks would leave the bot holding
     * half a bed while the wool stays in another chest (the colour was picked by whichever chest
     * completes the wool, the chest by whichever is nearest with anything to give). And since no
     * single chest then completes both the wool and the planks, nothing is asked at all ({@code -1},
     * today's per-item path) rather than a partial set: only a complete plan is taken, the first
     * one in nearest-first order.
     */
    public static int chooseChest(List<Plan> plans, String mustComplete) {
        if (mustComplete == null) {
            return chooseChest(plans);
        }
        if (plans == null) {
            return -1;
        }
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            if (plan == null || plan.fromChest().isEmpty() || missingIncludesAny(plan, List.of(mustComplete))) {
                continue;
            }
            if (plan.complete()) {
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

    /** "planks"/"plank", "logs"/"log", "coal or charcoal", "cobblestone or similar" for the pools; anything else is left to the adapter. */
    public static String poolDisplayName(String key, int count) {
        if (PLANKS.equals(key)) {
            return count == 1 ? "plank" : "planks";
        }
        if (LOGS.equals(key)) {
            return count == 1 ? "log" : "logs";
        }
        if (TORCH_FUEL.equals(key)) {
            return "coal or charcoal";
        }
        if (STONE_CRAFTING.equals(key)) {
            return "cobblestone or similar";
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
        return purposeText(toolLabel, null, amount, withTable);
    }

    /** "a torch", "4 torches", "a pair of shears", "a chest and a crafting table"; a blank plural adds an "s". */
    public static String purposeText(String singular, String plural, int amount, boolean withTable) {
        boolean blank = singular == null || singular.isBlank();
        String many = plural == null || plural.isBlank() ? (blank ? null : singular.strip() + "s") : plural.strip();
        String what = blank ? "that" : amount > 1 ? amount + " " + many : article(singular.strip()) + " " + singular.strip();
        return withTable ? what + " and a crafting table" : what;
    }

    /** "an" before a vowel ("an iron helmet"), else "a". */
    private static String article(String word) {
        return !word.isEmpty() && "aeiouAEIOU".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
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
