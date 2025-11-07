package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Collects stone-like blocks using pickaxes, mirroring the collect_dirt workflow but
 * targeting harder materials such as stone, deepslate, andesite, etc.
 */
public final class MiningSkill extends CollectDirtSkill {

    private static final Set<Item> TARGET_ITEMS = itemSet(
            Items.COBBLESTONE,
            Items.STONE,
            Items.COBBLED_DEEPSLATE,
            Items.DEEPSLATE,
            Items.ANDESITE,
            Items.DIORITE,
            Items.GRANITE,
            Items.TUFF
    );

    private static final Set<Identifier> TARGET_BLOCKS = blockIds(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE,
            Blocks.TUFF
    );

    private static final int DEFAULT_MAX_FAILS = 5; // New default for mining

    public MiningSkill() {
        super(
                "mining",
                "stone",
                TARGET_ITEMS,
                TARGET_BLOCKS,
                "pickaxe",
                DEFAULT_MAX_FAILS // Pass default maxFails
        );
    }

    public MiningSkill(Set<Identifier> targetBlockIds, int maxFails) {
        super(
                "mining",
                "stone",
                TARGET_ITEMS,
                targetBlockIds,
                "pickaxe",
                maxFails
        );
    }
}
