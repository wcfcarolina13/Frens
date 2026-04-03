package net.wcfcarolina13.GameAI.services;

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
        return state.isOf(Blocks.LODESTONE)
                || state.isOf(Blocks.ENCHANTING_TABLE)
                || state.isOf(Blocks.BEACON)
                || state.isOf(Blocks.RESPAWN_ANCHOR)
                || state.isOf(Blocks.CONDUIT)
                || state.isOf(Blocks.END_PORTAL_FRAME)
                || isProtectedGlassLike(state);
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
