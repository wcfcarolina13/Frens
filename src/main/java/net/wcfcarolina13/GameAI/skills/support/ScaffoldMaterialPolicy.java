package net.wcfcarolina13.GameAI.skills.support;

import java.util.Comparator;

/**
 * Which world blocks an early-game bot may dig by hand for scaffold: soft, non-falling members of
 * the dirt family (dirt, grass block, coarse and rooted dirt, podzol, mycelium, mud, moss).
 *
 * <ul>
 *   <li>Gravity blocks (sand, red sand, gravel, concrete powder) never: a pillar of them collapses
 *       and buries whoever stands next to it.</li>
 *   <li>Clay and snow block are not in the dirt tag and drop nothing placeable by hand.</li>
 *   <li>Anything needing a tool (stone, netherrack) drops nothing by hand.</li>
 *   <li>Muddy mangrove roots (0.7) are over the hardness cap: slow by hand.</li>
 * </ul>
 *
 * <p>No Minecraft types: WoodcutSkill reads the block state (BlockTags.DIRT, FallingBlock,
 * isToolRequired, getHardness) and passes plain values.
 */
public final class ScaffoldMaterialPolicy {

    /** Grass block and mycelium; the softest blocks a bare hand breaks in under a second. */
    public static final float MAX_HAND_HARDNESS = 0.6F;

    /**
     * Horizontal search radius of the woodcut hand gather around the bot. 3 until 1.1.240: in a new
     * world's Spawn base every dirt cell that close was protected, so the bot gathered nothing
     * (protection stays strict, ruling 2026-10-14; the search widens instead). Cells beyond reach
     * cost a short bounded walk, so {@link #gatherOrder()} puts unprotected and near cells first.
     * Only WoodcutSkill uses it.
     */
    public static final int HAND_GATHER_RADIUS = 6;

    /** A gather cell as an offset from the bot's feet block, and whether territory protects it. */
    public record GatherCandidate(int dx, int dy, int dz, boolean protectedCell) {
        public int distanceSq() {
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private ScaffoldMaterialPolicy() {
    }

    /**
     * Whether the axe-bootstrap hand gather may dig the block at this offset from the bot's feet
     * block: feet level ({@code dy} 0) or head level (1), within {@link #HAND_GATHER_RADIUS}, and
     * never the bot's own column. The row it stands on ({@code dy} -1) and below are off limits:
     * digging there left holes in the lawn next to the bot and could take its own footing.
     */
    public static boolean mayHandGather(int dx, int dy, int dz) {
        if (dy < 0 || dy > 1) {
            return false;
        }
        if (dx == 0 && dz == 0) {
            return false;
        }
        return Math.abs(dx) <= HAND_GATHER_RADIUS && Math.abs(dz) <= HAND_GATHER_RADIUS;
    }

    /**
     * Gather order: unprotected cells first (a protected cell only ever yields a refusal), then
     * nearest (least walking), then feet level before head level.
     */
    public static Comparator<GatherCandidate> gatherOrder() {
        return Comparator.comparing(GatherCandidate::protectedCell)
                .thenComparingInt(GatherCandidate::distanceSq)
                .thenComparingInt(GatherCandidate::dy);
    }

    public static boolean isHandScaffold(float hardness, boolean falls, boolean inDirtTag, boolean requiresTool) {
        return inDirtTag
                && !falls
                && !requiresTool
                && hardness >= 0.0F
                && hardness <= MAX_HAND_HARDNESS;
    }
}
