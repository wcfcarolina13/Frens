package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

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
        if (isWorkstation(state)) return true;
        if (isRedstoneComponent(state)) return true;
        if (isCauldron(state)) return true;
        return state.isOf(Blocks.LODESTONE)
                || state.isOf(Blocks.ENCHANTING_TABLE)
                || state.isOf(Blocks.BEACON)
                || state.isOf(Blocks.RESPAWN_ANCHOR)
                || state.isOf(Blocks.CONDUIT)
                || state.isOf(Blocks.END_PORTAL_FRAME)
                || isProtectedGlassLike(state);
    }

    /**
     * Context-aware overload: state-check plus neighbor-check plus entity-scan. Refuses
     * to break a block if mining it would damage adjacent infrastructure even when the
     * block itself is safe.
     *
     * <p>Adjacency rules (all 6 cardinal neighbors):
     * <ul>
     *   <li>Any neighbor is a redstone component → refuse (the component may be supported
     *       by the target).</li>
     *   <li>Any neighbor is a chain or lantern variant → refuse (chains/lanterns hang from
     *       or sit on the target).</li>
     * </ul>
     *
     * <p>Entity scan (3-block painting box around the target): if any painting entity is
     * attached such that the target is the painting's support face, refuse.
     */
    public static boolean isNeverBreakAt(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        if (isNeverBreakBlock(state)) return true;
        for (Direction dir : Direction.values()) {
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (neighbor == null || neighbor.isAir()) continue;
            if (isRedstoneComponent(neighbor)) return true;
            if (isChainOrLantern(neighbor)) return true;
        }
        if (hasPaintingAttachedTo(world, pos)) return true;
        return false;
    }

    private static boolean hasPaintingAttachedTo(World world, BlockPos pos) {
        // Paintings are 1×1 to 4×4 in the air space adjacent to their support wall. Scan a
        // 5-block cube to catch the largest variant whose attachment edge touches `pos`.
        Box scanBox = new Box(pos).expand(2.5);
        var paintings = world.getEntitiesByClass(PaintingEntity.class, scanBox, p -> true);
        for (PaintingEntity painting : paintings) {
            // The painting's attachment block is one step opposite its facing from the
            // painting position. We over-approximate: if the target block is along the
            // painting's facing axis behind the painting bounding box, treat as attached.
            Direction facing = painting.getHorizontalFacing();
            if (facing == null) continue;
            Box paintBox = painting.getBoundingBox();
            // Walk one block behind the painting (opposite of facing) and check overlap.
            Box behind = paintBox.offset(-facing.getOffsetX(), -facing.getOffsetY(), -facing.getOffsetZ());
            if (behind.intersects(new Box(pos).expand(0.01))) return true;
        }
        return false;
    }

    private static boolean isChainOrLantern(BlockState state) {
        if (state == null || state.isAir()) return false;
        String key = state.getBlock().getTranslationKey();
        if (key == null) return false;
        return key.endsWith("_chain") || key.equals("block.minecraft.chain")
                || key.endsWith("_lantern") || key.equals("block.minecraft.lantern");
    }

    /**
     * Crafting workstations and decorative-but-functional player infrastructure. Anything
     * that holds inventory is in {@link #isProtectedContainer}; this list is the rest.
     */
    public static boolean isWorkstation(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.isOf(Blocks.CRAFTING_TABLE)
                || state.isOf(Blocks.SMITHING_TABLE)
                || state.isOf(Blocks.LOOM)
                || state.isOf(Blocks.ANVIL)
                || state.isOf(Blocks.CHIPPED_ANVIL)
                || state.isOf(Blocks.DAMAGED_ANVIL)
                || state.isOf(Blocks.GRINDSTONE)
                || state.isOf(Blocks.FLETCHING_TABLE)
                || state.isOf(Blocks.CARTOGRAPHY_TABLE)
                || state.isOf(Blocks.STONECUTTER)
                || state.isOf(Blocks.LECTERN)
                || state.isOf(Blocks.COMPOSTER)
                || state.isOf(Blocks.JUKEBOX)
                || state.isOf(Blocks.NOTE_BLOCK);
    }

    /**
     * Redstone components — wiring, signal sources, signal modifiers, and motion blocks.
     * Mining any of these in a player base destroys signal paths, so the bot refuses
     * blanket. Includes hopper/dispenser/dropper for completeness even though those are
     * also matched by {@link #isProtectedContainer}.
     */
    public static boolean isRedstoneComponent(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.isIn(BlockTags.BUTTONS)) return true;
        if (state.isIn(BlockTags.PRESSURE_PLATES)) return true;
        return state.isOf(Blocks.REDSTONE_WIRE)
                || state.isOf(Blocks.REDSTONE_TORCH)
                || state.isOf(Blocks.REDSTONE_WALL_TORCH)
                || state.isOf(Blocks.REDSTONE_BLOCK)
                || state.isOf(Blocks.REDSTONE_LAMP)
                || state.isOf(Blocks.REPEATER)
                || state.isOf(Blocks.COMPARATOR)
                || state.isOf(Blocks.OBSERVER)
                || state.isOf(Blocks.PISTON)
                || state.isOf(Blocks.STICKY_PISTON)
                || state.isOf(Blocks.PISTON_HEAD)
                || state.isOf(Blocks.MOVING_PISTON)
                || state.isOf(Blocks.LEVER)
                || state.isOf(Blocks.DAYLIGHT_DETECTOR)
                || state.isOf(Blocks.TARGET)
                || state.isOf(Blocks.TRIPWIRE)
                || state.isOf(Blocks.TRIPWIRE_HOOK)
                || state.isOf(Blocks.DISPENSER)
                || state.isOf(Blocks.DROPPER)
                || state.isOf(Blocks.HOPPER)
                || state.isOf(Blocks.CRAFTER);
    }

    /** Cauldron variants — empty, water, lava, powder-snow. */
    public static boolean isCauldron(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.isOf(Blocks.CAULDRON)
                || state.isOf(Blocks.WATER_CAULDRON)
                || state.isOf(Blocks.LAVA_CAULDRON)
                || state.isOf(Blocks.POWDER_SNOW_CAULDRON);
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
                || key.endsWith("_chain") || key.equals("block.minecraft.chain")
                || key.endsWith("_lantern") || key.equals("block.minecraft.lantern")
                || key.endsWith("_stained_glass")
                || key.endsWith("_stained_glass_pane")
                || key.endsWith("_pressure_plate");
    }
}
