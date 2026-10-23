package net.wcfcarolina13.GameAI.services;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;

/**
 * Reads whether a bot stands inside a player-built structure (a house with a door), for the
 * join enclosure check and linger check #0. The decision itself is
 * {@link JoinEnclosurePolicy#isInsidePlayerBuilt(int, boolean)}.
 *
 * <p>Reads go through a {@link ChunkBlockReader}, so the same call is cheap on the server
 * thread (join check) and on a worker (idle linger path), with no per-cell server round trip.
 * Cells in unloaded chunks are skipped: both callers run at the bot's own position.
 *
 * <p>Classification is by tags so modded doors, planks, stairs and glass count without an
 * allowlist.
 */
public final class PlayerBuiltStructureScanner {

    /** Player-built blocks are counted within x/z +-this of the bot's feet. */
    static final int COUNT_RADIUS = 3;
    /** A door is looked for within x/z +-this (a bot in the far corner of an 8-wide house). */
    static final int DOOR_RADIUS = 7;
    static final int MIN_DY = -1;
    static final int MAX_DY = 3;

    private PlayerBuiltStructureScanner() {
    }

    public static boolean isInsidePlayerBuilt(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        return isInsidePlayerBuilt(new ChunkBlockReader(world), feet);
    }

    public static boolean isInsidePlayerBuilt(ChunkBlockReader reader, BlockPos feet) {
        int built = 0;
        boolean door = false;
        for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
            for (int dx = -DOOR_RADIUS; dx <= DOOR_RADIUS; dx++) {
                for (int dz = -DOOR_RADIUS; dz <= DOOR_RADIUS; dz++) {
                    BlockState state = reader.state(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    if (!door && state.isIn(BlockTags.DOORS)) {
                        door = true;
                    }
                    if (Math.abs(dx) <= COUNT_RADIUS && Math.abs(dz) <= COUNT_RADIUS
                            && isPlayerBuiltBlock(state)) {
                        built++;
                    }
                }
            }
            if (door && built >= JoinEnclosurePolicy.MIN_BUILT_BLOCKS) {
                return true;
            }
        }
        return JoinEnclosurePolicy.isInsidePlayerBuilt(built, door);
    }

    /** A block a player builds a house with: planks, stairs, slabs, glass, bricks, doors, beds, chests. */
    public static boolean isPlayerBuiltBlock(BlockState state) {
        return state != null
                && (TreeDetector.isHumanBlock(state)
                || state.isIn(BlockTags.DOORS)
                || state.isIn(BlockTags.TRAPDOORS)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.STAIRS)
                || state.isIn(BlockTags.SLABS)
                || state.isIn(BlockTags.STONE_BRICKS)
                || state.isIn(BlockTags.LANTERNS)
                || state.isIn(ConventionalBlockTags.GLASS_BLOCKS)
                || state.isIn(ConventionalBlockTags.GLASS_PANES)
                || state.isIn(ConventionalBlockTags.CHESTS)
                || state.isIn(ConventionalBlockTags.PLAYER_WORKSTATIONS_CRAFTING_TABLES)
                || state.isOf(net.minecraft.block.Blocks.BRICKS));
    }
}
