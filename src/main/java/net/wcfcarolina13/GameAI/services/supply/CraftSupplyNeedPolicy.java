package net.wcfcarolina13.GameAI.services.supply;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Pure-logic policy: everything one craft needs from one chest, worked out before the bot asks,
 * so the owner sees ONE combined ask (Bradley's ruling of 2026-10-23: "I need 3 planks and 2
 * sticks from your chest, OK?" — one approval covers the set).
 *
 * <p>No Minecraft types. Items are opaque strings chosen by the adapter (an item id such as
 * {@code "minecraft:stick"}, or a pooled group such as {@code "#minecraft:planks"}); a recipe is
 * one already-chosen variant (variant choice stays in {@code CraftingHelper}); stock is what the
 * supply policy could grant from the chest ({@code SupplyWithdrawals.grantableEstimate}, so the
 * reserve is already out).
 *
 * <p><b>How a need is resolved</b>, per ingredient, depth first in recipe order:
 * <ol>
 *   <li>what the bot carries (including leftovers of batches made earlier in this plan);</li>
 *   <li>crafted from what it carries alone, if that covers it completely without the chest — so
 *       a bot holding logs makes its own planks rather than asking for the chest's;</li>
 *   <li>taken as-is from the chest (the asked line names the ingredient itself: "3 planks", not
 *       "1 log");</li>
 *   <li>crafted, with its own ingredients resolved the same way, while depth allows;</li>
 *   <li>otherwise missing.</li>
 * </ol>
 * The target itself is always crafted (asking for the finished item is tool retrieval, a
 * different flow). A cycle in the recipe graph, a recipe deeper than {@link Limits#maxDepth()},
 * or a recipe with no output resolves as missing. More than {@link Limits#maxLines()} distinct
 * chest lines: the plan keeps the first lines and moves the rest to missing, flagged
 * {@link Plan#truncated()}.
 *
 * <p><b>Partial availability</b> (ruling made here, to confirm): when the chest covers some lines
 * but not all, the plan still asks for what the chest has and names what is still missing; see
 * {@link SupplyBundlePromptPolicy#decide}.
 */
public final class CraftSupplyNeedPolicy {

    /** Default recipe depth: target → sticks → planks → logs. */
    public static final int DEFAULT_MAX_DEPTH = 3;
    /** Default cap on distinct items in one ask; a longer chat prompt stops being readable. */
    public static final int DEFAULT_MAX_LINES = 6;

    /**
     * One chosen recipe variant.
     *
     * @param outputCount how many one craft makes (must be positive to be usable)
     * @param inputs      ingredient → count per craft, in the order the prompt should list them
     */
    public record Recipe(int outputCount, Map<String, Integer> inputs) {
        public Recipe {
            inputs = inputs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }
    }

    public record Limits(int maxDepth, int maxLines) {
        public Limits {
            maxDepth = Math.max(0, maxDepth);
            maxLines = Math.max(1, maxLines);
        }

        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_DEPTH, DEFAULT_MAX_LINES);
        }
    }

    /** {@code count} of {@code item}. */
    public record Line(String item, int count) {
    }

    /**
     * @param fromChest what to ask the chest for, merged by item, in first-needed order
     * @param missing   what neither the bot nor the chest covers, merged by item (leaf items)
     * @param truncated whether chest lines beyond {@link Limits#maxLines()} were moved to missing
     */
    public record Plan(List<Line> fromChest, List<Line> missing, boolean truncated) {
        public Plan {
            fromChest = List.copyOf(fromChest);
            missing = List.copyOf(missing);
        }

        /** The chest covers everything still lacking: one ask finishes the craft. */
        public boolean complete() {
            return missing.isEmpty();
        }

        /** The bot already has everything: nothing to ask. */
        public boolean nothingNeeded() {
            return fromChest.isEmpty() && missing.isEmpty();
        }
    }

    private CraftSupplyNeedPolicy() {
    }

    /**
     * Plans {@code count} of {@code target}.
     *
     * @param recipes item → its chosen recipe, or {@code null} for a raw item
     * @param have    what the bot carries (absent = 0)
     * @param chest   what the chest could grant (absent = 0)
     */
    public static Plan plan(String target, int count, Function<String, Recipe> recipes,
                            Map<String, Integer> have, Map<String, Integer> chest, Limits limits) {
        Objects.requireNonNull(recipes, "recipes");
        Limits lim = limits == null ? Limits.defaults() : limits;
        State state = new State(have, chest);
        if (target != null && count > 0) {
            Deque<String> path = new ArrayDeque<>();
            boolean madeTarget = craft(target, count, recipes, state, 0, lim, path, true);
            if (!madeTarget) {
                state.addMissing(target, count);
            }
        }
        return state.toPlan(lim.maxLines());
    }

    /**
     * Plans an already-flattened set of needs (e.g. a multi-item batch): each resolved in order.
     * Each need is treated as an ingredient of some craft, so it starts at depth 1, one level below
     * {@link #plan}'s target: a need of sticks still reaches planks and logs within the default
     * depth, while a whole tool passed here as a need gets one fewer recipe level than through
     * {@code plan}. Pass tools through {@code plan}.
     */
    public static Plan planNeeds(Map<String, Integer> needs, Function<String, Recipe> recipes,
                                 Map<String, Integer> have, Map<String, Integer> chest, Limits limits) {
        Objects.requireNonNull(recipes, "recipes");
        Limits lim = limits == null ? Limits.defaults() : limits;
        State state = new State(have, chest);
        if (needs != null) {
            for (Map.Entry<String, Integer> e : needs.entrySet()) {
                int qty = e.getValue() == null ? 0 : e.getValue();
                if (e.getKey() != null && qty > 0) {
                    resolve(e.getKey(), qty, recipes, state, 1, lim, new ArrayDeque<>(), true);
                }
            }
        }
        return state.toPlan(lim.maxLines());
    }

    /** Resolves {@code qty} of {@code item} at {@code depth}; anything unresolved becomes missing. */
    private static void resolve(String item, int qty, Function<String, Recipe> recipes, State state,
                                int depth, Limits lim, Deque<String> path, boolean chestAllowed) {
        int left = qty - state.takeHave(item, qty);
        if (left <= 0) {
            return;
        }
        // 2. Self-sufficient craft: try it on a copy with the chest off; keep it only if complete.
        State trial = state.copy();
        if (craft(item, left, recipes, trial, depth, lim, path, false) && trial.missedNothingBeyond(state)) {
            state.adopt(trial);
            return;
        }
        if (!chestAllowed) {
            state.addMissing(item, left);
            return;
        }
        left -= state.takeChest(item, left);
        if (left <= 0) {
            return;
        }
        if (!craft(item, left, recipes, state, depth, lim, path, true)) {
            state.addMissing(item, left);
        }
    }

    /**
     * Crafts {@code qty} of {@code item} by resolving its ingredients into {@code state}. Returns
     * false (state untouched) when it cannot be crafted at all: no recipe, too deep, or a cycle.
     */
    private static boolean craft(String item, int qty, Function<String, Recipe> recipes, State state,
                                 int depth, Limits lim, Deque<String> path, boolean chestAllowed) {
        if (depth > lim.maxDepth() || path.contains(item)) {
            return false;
        }
        Recipe recipe = recipes.apply(item);
        if (recipe == null || recipe.outputCount() <= 0 || recipe.inputs().isEmpty()) {
            return false;
        }
        int batches = (qty + recipe.outputCount() - 1) / recipe.outputCount();
        path.push(item);
        try {
            for (Map.Entry<String, Integer> in : recipe.inputs().entrySet()) {
                int per = in.getValue() == null ? 0 : in.getValue();
                if (in.getKey() == null || per <= 0) {
                    continue;
                }
                resolve(in.getKey(), per * batches, recipes, state, depth + 1, lim, path, chestAllowed);
            }
        } finally {
            path.pop();
        }
        int surplus = batches * recipe.outputCount() - qty;
        if (surplus > 0) {
            state.giveHave(item, surplus);
        }
        return true;
    }

    /** Mutable pools for one plan. */
    private static final class State {
        final Map<String, Integer> have;
        final Map<String, Integer> chest;
        final LinkedHashMap<String, Integer> fromChest;
        final LinkedHashMap<String, Integer> missing;

        State(Map<String, Integer> have, Map<String, Integer> chest) {
            this.have = positive(have);
            this.chest = positive(chest);
            this.fromChest = new LinkedHashMap<>();
            this.missing = new LinkedHashMap<>();
        }

        private State(State o) {
            this.have = new HashMap<>(o.have);
            this.chest = new HashMap<>(o.chest);
            this.fromChest = new LinkedHashMap<>(o.fromChest);
            this.missing = new LinkedHashMap<>(o.missing);
        }

        State copy() {
            return new State(this);
        }

        /** Takes over a trial's pools; only called when the trial asked and missed nothing new. */
        void adopt(State trial) {
            have.clear();
            have.putAll(trial.have);
            chest.clear();
            chest.putAll(trial.chest);
            fromChest.clear();
            fromChest.putAll(trial.fromChest);
            missing.clear();
            missing.putAll(trial.missing);
        }

        /** Whether this trial added no missing item to what {@code before} already lacked. */
        boolean missedNothingBeyond(State before) {
            return missing.equals(before.missing);
        }

        int takeHave(String item, int qty) {
            return take(have, item, qty);
        }

        int takeChest(String item, int qty) {
            int got = take(chest, item, qty);
            if (got > 0) {
                fromChest.merge(item, got, Integer::sum);
            }
            return got;
        }

        void giveHave(String item, int qty) {
            have.merge(item, qty, Integer::sum);
        }

        void addMissing(String item, int qty) {
            missing.merge(item, qty, Integer::sum);
        }

        Plan toPlan(int maxLines) {
            List<Line> asked = new ArrayList<>();
            LinkedHashMap<String, Integer> miss = new LinkedHashMap<>(missing);
            boolean truncated = false;
            for (Map.Entry<String, Integer> e : fromChest.entrySet()) {
                if (asked.size() < maxLines) {
                    asked.add(new Line(e.getKey(), e.getValue()));
                } else {
                    truncated = true;
                    miss.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
            List<Line> missingLines = new ArrayList<>();
            for (Map.Entry<String, Integer> e : miss.entrySet()) {
                missingLines.add(new Line(e.getKey(), e.getValue()));
            }
            return new Plan(asked, missingLines, truncated);
        }

        private static int take(Map<String, Integer> pool, String item, int qty) {
            int avail = pool.getOrDefault(item, 0);
            int got = Math.min(avail, qty);
            if (got > 0) {
                pool.put(item, avail - got);
            }
            return Math.max(0, got);
        }

        private static Map<String, Integer> positive(Map<String, Integer> src) {
            Map<String, Integer> out = new HashMap<>();
            if (src != null) {
                for (Map.Entry<String, Integer> e : src.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
            }
            return out;
        }
    }
}
