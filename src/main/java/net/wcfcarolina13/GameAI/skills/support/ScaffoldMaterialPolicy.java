package net.wcfcarolina13.GameAI.skills.support;

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

    private ScaffoldMaterialPolicy() {
    }

    public static boolean isHandScaffold(float hardness, boolean falls, boolean inDirtTag, boolean requiresTool) {
        return inDirtTag
                && !falls
                && !requiresTool
                && hardness >= 0.0F
                && hardness <= MAX_HAND_HARDNESS;
    }
}
