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
