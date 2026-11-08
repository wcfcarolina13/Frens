package net.wcfcarolina13.GameAI.services;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
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
 * allowlist. The door gate takes wooden doors only (iron and copper doors are in strongholds and
 * trial chambers), and the bot must stand within
 * {@link JoinEnclosurePolicy#MAX_DEPTH_BELOW_SURFACE} blocks of the surface heightmap at its
 * column (skipped in dimensions with a ceiling), or one of the wooden doors must open to the sky
 * (a house dug into a hillside).
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
        // Depth gate: strongholds, trial chambers and other deep structures have doors and stone
        // bricks too. A dimension with a ceiling (the Nether) has no useful surface, so skip it.
        // A deeper bot still counts when one of the wooden doors opens to the sky (a house dug
        // into a hillside or under a mountain), checked as each door is found.
        boolean shallow = true;
        if (!reader.world().getDimension().hasCeiling()) {
            int surfaceY = reader.surfaceY(feet.getX(), feet.getZ());
            shallow = surfaceY != Integer.MIN_VALUE
                    && JoinEnclosurePolicy.withinSurfaceDepth(surfaceY - feet.getY());
        }
        int built = 0;
        boolean door = false;
        boolean doorToSky = false;
        int doorsChecked = 0;
        for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
            for (int dx = -DOOR_RADIUS; dx <= DOOR_RADIUS; dx++) {
                for (int dz = -DOOR_RADIUS; dz <= DOOR_RADIUS; dz++) {
                    int x = feet.getX() + dx;
                    int y = feet.getY() + dy;
                    int z = feet.getZ() + dz;
                    BlockState state = reader.state(x, y, z);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    if (state.isIn(BlockTags.WOODEN_DOORS)) {
                        door = true;
                        if (!shallow && !doorToSky && doorsChecked < MAX_DOORS_CHECKED) {
                            doorsChecked++;
                            doorToSky = doorOpensToSky(reader, state, x, y, z);
                        }
                    }
                    if (Math.abs(dx) <= COUNT_RADIUS && Math.abs(dz) <= COUNT_RADIUS
                            && isPlayerBuiltBlock(state)) {
                        built++;
                    }
                }
            }
            if (JoinEnclosurePolicy.isInsidePlayerBuilt(built, door, shallow, doorToSky)) {
                return true;
            }
        }
        return JoinEnclosurePolicy.isInsidePlayerBuilt(built, door, shallow, doorToSky);
    }

    /** Wooden-door blocks (each half counts) tested for open sky per scan, 8 heightmap reads each. */
    static final int MAX_DOORS_CHECKED = 16;

    /**
     * Whether a cell 1 or 2 blocks out from this door block, on either horizontal axis, has open
     * sky at the door's top half. Heightmap reads only, so it is cheap on any thread.
     */
    private static boolean doorOpensToSky(ChunkBlockReader reader, BlockState door, int x, int y, int z) {
        boolean upper = door.contains(Properties.DOUBLE_BLOCK_HALF)
                && door.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        int topY = JoinEnclosurePolicy.doorTopY(y, upper);
        for (int[] off : JoinEnclosurePolicy.doorSkyOffsets()) {
            if (JoinEnclosurePolicy.doorCellOpensToSky(reader.surfaceY(x + off[0], z + off[1]), topY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A wooden door near the bot whose far side opens to the sky: its lower half, its facing axis,
     * and which way along that axis is the far side from the bot (+1 or -1).
     */
    public record SkyDoor(BlockPos lowerHalf, boolean facesAlongX, int farSign) {
    }

    /**
     * The nearest wooden door within {@link ShelterDoorExitPolicy#DOOR_RADIUS} of {@code feet}
     * (feet Y -1..+1) whose lower half is there and whose far side from the bot, 1 or 2 cells out
     * along the door's facing axis, is open to the sky. A door between two rooms has roof on its
     * far side, so it never counts. For break-free, before mining out.
     */
    public static SkyDoor findSkyDoor(ChunkBlockReader reader, BlockPos feet) {
        if (reader == null || feet == null) {
            return null;
        }
        int r = ShelterDoorExitPolicy.DOOR_RADIUS;
        SkyDoor best = null;
        int bestDistSq = -1;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = feet.getX() + dx;
                    int y = feet.getY() + dy;
                    int z = feet.getZ() + dz;
                    BlockState state = reader.state(x, y, z);
                    if (state == null || !state.isIn(BlockTags.WOODEN_DOORS)
                            || !state.contains(Properties.DOUBLE_BLOCK_HALF)
                            || state.get(Properties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
                            || !state.contains(Properties.HORIZONTAL_FACING)) {
                        continue;
                    }
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (!ShelterDoorExitPolicy.nearer(distSq, bestDistSq)) {
                        continue;
                    }
                    boolean alongX = state.get(Properties.HORIZONTAL_FACING).getAxis()
                            == net.minecraft.util.math.Direction.Axis.X;
                    int far = alongX ? ShelterDoorExitPolicy.farSign(x, feet.getX())
                            : ShelterDoorExitPolicy.farSign(z, feet.getZ());
                    if (far == 0 || !farSideOpensToSky(reader, x, y, z, alongX, far)) {
                        continue;
                    }
                    best = new SkyDoor(new BlockPos(x, y, z), alongX, far);
                    bestDistSq = distSq;
                }
            }
        }
        return best;
    }

    /** Sky at the door's top half 1 or 2 cells out on the far side (heightmap reads only). */
    private static boolean farSideOpensToSky(ChunkBlockReader reader, int x, int lowerY, int z,
                                             boolean alongX, int far) {
        int topY = JoinEnclosurePolicy.doorTopY(lowerY, false);
        for (int d = 1; d <= 2; d++) {
            int cx = alongX ? x + far * d : x;
            int cz = alongX ? z : z + far * d;
            if (JoinEnclosurePolicy.doorCellOpensToSky(reader.surfaceY(cx, cz), topY)) {
                return true;
            }
        }
        return false;
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
