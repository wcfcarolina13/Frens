package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure order in which a shelter seal (wall-off cells, the cliff-dig entrance, the dig-down cap)
 * picks its block. The bot places the first id it holds, and the same bot mines the block again at
 * dawn, so without a pickaxe the order follows the bare-hand break time.
 *
 * <p>Bare-hand times (ticks = ceil(1 / delta)): dirt and sand 0.75 s, gravel 0.9 s (both drop),
 * planks 3 s (1.5 s with a wooden axe, drops), then the blocks that only drop with a pickaxe:
 * netherrack 2 s, calcite 3.75 s, sandstone and basalt 6.25 s, stone family and tuff 7.5 s,
 * cobblestone 10 s, deepslate 15 s, cobbled deepslate 17.5 s.</p>
 *
 * <p>{@code hasAxe} does not change the order with today's list: planks with an axe (1.5 s) are
 * still slower than gravel by hand (0.9 s) and faster than every pickaxe-only block. It stays in
 * the signature so the rule is written down where a future seal block would change it.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class SealOrderPolicy {

    private SealOrderPolicy() {}

    /** Bare-hand tiers, lower first. Ids not listed sort after every listed one. */
    private static final Map<String, Integer> NO_PICKAXE_RANK = Map.ofEntries(
            // Tier 1: hand-mineable, drops itself.
            Map.entry("minecraft:dirt", 10),
            Map.entry("minecraft:sand", 11),
            Map.entry("minecraft:gravel", 12),
            // Tier 2: planks.
            Map.entry("minecraft:oak_planks", 20),
            Map.entry("minecraft:spruce_planks", 20),
            Map.entry("minecraft:birch_planks", 20),
            Map.entry("minecraft:jungle_planks", 20),
            Map.entry("minecraft:acacia_planks", 20),
            Map.entry("minecraft:dark_oak_planks", 20),
            Map.entry("minecraft:mangrove_planks", 20),
            Map.entry("minecraft:cherry_planks", 20),
            Map.entry("minecraft:bamboo_planks", 20),
            // Tier 3: needs a pickaxe to drop, fastest bare-hand break first.
            Map.entry("minecraft:netherrack", 30),
            Map.entry("minecraft:calcite", 31),
            Map.entry("minecraft:sandstone", 32),
            Map.entry("minecraft:red_sandstone", 32),
            Map.entry("minecraft:basalt", 32),
            Map.entry("minecraft:stone", 33),
            Map.entry("minecraft:granite", 33),
            Map.entry("minecraft:diorite", 33),
            Map.entry("minecraft:andesite", 33),
            Map.entry("minecraft:tuff", 33),
            Map.entry("minecraft:cobblestone", 34),
            Map.entry("minecraft:deepslate", 35),
            Map.entry("minecraft:cobbled_deepslate", 36));

    private static final int UNRANKED = 1_000;

    /**
     * @param sealIds   the seal allowlist in today's order (registry ids)
     * @param hasPickaxe whether the bot holds any pickaxe
     * @param hasAxe    whether the bot holds any axe (no effect today, see the class note)
     * @return a new list with the same ids: today's order with a pickaxe, else the bare-hand order.
     *         Unranked ids keep their relative order at the end. A bot with no pickaxe and only
     *         cobblestone still gets cobblestone.
     */
    public static List<String> order(List<String> sealIds, boolean hasPickaxe, boolean hasAxe) {
        if (sealIds == null || sealIds.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(sealIds);
        if (hasPickaxe) {
            return List.copyOf(out);
        }
        // List.sort is stable, so equal ranks (the plank family, the stone family) keep input order.
        out.sort(Comparator.comparingInt(id -> NO_PICKAXE_RANK.getOrDefault(id, UNRANKED)));
        return List.copyOf(out);
    }

    /** Whether a two-block entrance seal places its head block. */
    public enum HeadStep { PLACE, SKIP_FEET_OPEN }

    /**
     * The head block goes on only over a feet block that is really there: placed over an open
     * feet cell it hangs in the air and the entrance stays open below it.
     *
     * @param feetBlocksMovement the feet cell blocks movement after the feet attempt
     */
    public static HeadStep headStep(boolean feetBlocksMovement) {
        return feetBlocksMovement ? HeadStep.PLACE : HeadStep.SKIP_FEET_OPEN;
    }

    /**
     * {@code ordered} without the falling blocks (sand, gravel), order kept. For a block placed with
     * air under it, such as the dig-down cap over the bot's head.
     */
    public static List<String> withoutFalling(List<String> ordered, java.util.Set<String> fallingIds) {
        if (ordered == null || ordered.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            if (fallingIds == null || !fallingIds.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
