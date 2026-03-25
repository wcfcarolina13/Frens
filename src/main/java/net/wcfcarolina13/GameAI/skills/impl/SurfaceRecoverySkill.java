package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Blocks;
import net.minecraft.item.Items;

public final class SurfaceRecoverySkill extends CollectDirtSkill {

    public SurfaceRecoverySkill() {
        super(
                "surface_recovery",
                "surface",
                itemSet(
                        Items.DIRT,
                        Items.COARSE_DIRT,
                        Items.ROOTED_DIRT,
                        Items.GRASS_BLOCK,
                        Items.GRAVEL,
                        Items.SAND,
                        Items.RED_SAND,
                        Items.MUD
                ),
                blockIds(
                        Blocks.DIRT,
                        Blocks.COARSE_DIRT,
                        Blocks.GRASS_BLOCK,
                        Blocks.ROOTED_DIRT,
                        Blocks.PODZOL,
                        Blocks.MUD,
                        Blocks.MYCELIUM,
                        Blocks.GRAVEL,
                        Blocks.SAND,
                        Blocks.RED_SAND
                ),
                "shovel",
                0
        );
    }
}
