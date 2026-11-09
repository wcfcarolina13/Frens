package net.wcfcarolina13.GameAI;

/**
 * Whether BotActions.moveRelative (the combat backpedal and the step-forward) may put the bot on a
 * cell. It only checked headroom, so a guarding bot kiting a zombie stepped off its stair edge into
 * the open pit beside it (sandbox 2026-11-08). The first solid (or liquid) cell below the landing
 * must be at most {@link #MAX_STEP_DOWN} blocks down; deeper, the step is refused. No Minecraft imports.
 */
public final class StepFloorPolicy {

    /**
     * floorDepth 1 = level ground, 2 = a 1-block step down; 3 or more (two open cells under the
     * landing) is refused, the same depth the guard walk path refuses (GUARD_MIN_DEPTH open cells).
     * Applies to guard and patrol only.
     */
    public static final int MAX_STEP_DOWN = 2;

    private StepFloorPolicy() {
    }

    /** {@code floorDepth}: 1 = solid right below the landing cell, MAX_STEP_DOWN + 1 = none found. */
    public static boolean allows(int floorDepth) {
        return floorDepth >= 1 && floorDepth <= MAX_STEP_DOWN;
    }
}
