package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Shared predicates for structure-like blocks that recovery code must treat conservatively.
 */
public final class ProtectedStructureBlockHelper {

    private ProtectedStructureBlockHelper() {
    }

    /**
     * Blocks that must NEVER be broken by stuck-escape, mine-escape, or any autonomous
     * recovery code. These are high-value player-placed infrastructure blocks.
     */
    public static boolean isNeverBreakBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        // AbstractSignBlock covers every sign variant (standing, wall, hanging, ceiling
        // hanging, wall hanging) and every wood type via the class hierarchy. Added
        // 2026-04-10 after reports of the bot thinking it was spawning inside signs
        // and breaking them — see stuck-escape mining in BotRescueService + ReturnBaseStuckService.
        if (state.getBlock() instanceof AbstractSignBlock) return true;
        // Hazard blocks — the bot must route AROUND these, never try to mine-escape
        // THROUGH them. Mining a campfire/fire/sweet-berry-bush would damage the bot
        // mid-mine; mining a magma block is fine but stepping off it is the correct
        // escape; powder snow/dripstone drop on break and cause further hazards.
        // See BotHazardService.isDeadlyBlock for the full rationale.
        if (BotHazardService.isDeadlyBlock(state)) return true;
        // Player-facing storage + bookshelves — breaking these via stuck-escape would
        // dump or destroy the contained items. Bots have no legitimate "mine through
        // a chest" workflow; the right behavior is always to route around.
        if (isProtectedContainer(state)) return true;
        return state.isOf(Blocks.LODESTONE)
                || state.isOf(Blocks.ENCHANTING_TABLE)
                || state.isOf(Blocks.BEACON)
                || state.isOf(Blocks.RESPAWN_ANCHOR)
                || state.isOf(Blocks.CONDUIT)
                || state.isOf(Blocks.END_PORTAL_FRAME)
                || isProtectedGlassLike(state);
    }

    /**
     * Player-facing storage + workstation blocks that hold items. Bots must never
     * mine these via stuck-escape — the contents would drop or vanish, and the
     * legitimate workflow is always to walk around them.
     *
     * <p>Covered: bookshelves (regular + chiseled), all chest variants (chest /
     * trapped chest / ender chest / barrel), all 17 shulker box variants
     * (uncolored + 16 dyes via translation-key suffix match), redstone-storage
     * blocks (hopper / dispenser / dropper), the 1.20+ decorated pot, the 1.21+
     * crafter, the 1-slot brewing stand, and active furnaces (furnace / blast
     * furnace / smoker — active workstations with fuel + input + output stacks).
     */
    public static boolean isProtectedContainer(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.isOf(Blocks.BOOKSHELF)
                || state.isOf(Blocks.CHISELED_BOOKSHELF)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.ENDER_CHEST)
                || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.HOPPER)
                || state.isOf(Blocks.DISPENSER)
                || state.isOf(Blocks.DROPPER)
                || state.isOf(Blocks.DECORATED_POT)
                || state.isOf(Blocks.CRAFTER)
                || state.isOf(Blocks.BREWING_STAND)
                || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)) {
            return true;
        }
        // All shulker box variants share a translation-key suffix; covers the
        // uncolored shulker_box and all 16 dyed variants in one match.
        String key = state.getBlock().getTranslationKey();
        return key != null && key.endsWith("shulker_box");
    }

    public static boolean isProtectedGlassLike(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }

        return isProtectedGlassLikeTranslationKey(state.getBlock().getTranslationKey());
    }

    static boolean isProtectedGlassLikeTranslationKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.equals("block.minecraft.glass")
                || key.equals("block.minecraft.glass_pane")
                || key.equals("block.minecraft.tinted_glass")
                || key.equals("block.minecraft.iron_bars")
                || key.equals("block.minecraft.chain")
                || key.equals("block.minecraft.lantern")
                || key.equals("block.minecraft.soul_lantern")
                || key.endsWith("_stained_glass")
                || key.endsWith("_stained_glass_pane")
                || key.endsWith("_pressure_plate");
    }
}
