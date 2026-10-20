package net.wcfcarolina13.GameAI.services;

/**
 * Pure cell rules for the stuck-escape paths in {@link BotRescueService} and
 * {@link ReturnBaseStuckService}. Callers read the world (collision shape,
 * {@code WalkablePartialBlocks.isPathable}, {@code BotHazardService.isDeadlyBlock},
 * fluid state) and pass plain flags here, so the decisions are unit-testable.
 *
 * <p>Two different questions live here and they deliberately use different
 * thresholds:</p>
 * <ul>
 *   <li><b>"Can the bot be inside this cell?"</b> ({@link #sideEnterable},
 *       {@link #isWallCell}, {@link #dropOneAllowed}) uses "pathable": empty
 *       collision or a thin partial with collision max Y at most 0.125 (short grass,
 *       leaf litter, carpet, snow layers 1 and 2). A snow layer of 3 or more or a
 *       bottom slab in the FEET cell is not enterable here: the bot would stand on
 *       top of it, which is a step, not a side move into the cell.</li>
 *   <li><b>"Does this cell stop the bot crossing it?"</b> ({@link #blocksHorizontal})
 *       uses the step height at feet (max Y above 0.6 blocks, so a bottom slab or a
 *       low snow layer is stepped over while fences and walls, max Y 1.5, block) and
 *       the pathable rule at head height (anything thicker than 0.125 hits the head).</li>
 * </ul>
 */
public final class StuckCellPolicy {

    /** Vanilla player step height: feet-level collision up to this is stepped over. */
    public static final double STEP_HEIGHT = 0.6D;
    /** Thin-partial ceiling shared with {@code WalkablePartialBlocks.isPathable}. */
    public static final double THIN_MAX_Y = 0.125D;

    private StuckCellPolicy() {
    }

    /**
     * Side cell the rescue can push the bot into: both rows pathable (or climbable,
     * folded in by the caller), no deadly block in either row, no non-water fluid.
     */
    public static boolean sideEnterable(boolean feetPathable, boolean headPathable,
                                        boolean feetHazard, boolean headHazard,
                                        boolean nonWaterFluid) {
        return feetPathable && headPathable && !feetHazard && !headHazard && !nonWaterFluid;
    }

    /** One cell clips the bot when it has collision and is not a thin pathable partial. */
    public static boolean isWallCell(boolean collisionEmpty, boolean pathable) {
        return !collisionEmpty && !pathable;
    }

    /**
     * A wall beside the bot if either the feet or the head row is a wall cell. Vanilla
     * {@code isInsideWall} tests the eye-level box, so a head-level overhang counts.
     */
    public static boolean wallAt(boolean feetCollisionEmpty, boolean headCollisionEmpty,
                                 boolean feetPathable, boolean headPathable) {
        return isWallCell(feetCollisionEmpty, feetPathable) || isWallCell(headCollisionEmpty, headPathable);
    }

    /** True when the wall beside the bot is only in the head row (for the log suffix). */
    public static boolean wallHeadOnly(boolean feetCollisionEmpty, boolean headCollisionEmpty,
                                       boolean feetPathable, boolean headPathable) {
        return !isWallCell(feetCollisionEmpty, feetPathable) && isWallCell(headCollisionEmpty, headPathable);
    }

    /**
     * Does a cell stop horizontal movement through it? Feet row: collision taller than
     * the step height. Head row: any collision thicker than a thin partial.
     */
    public static boolean blocksHorizontal(boolean collisionEmpty, double collisionMaxY, boolean isHead) {
        if (collisionEmpty) {
            return false;
        }
        return isHead ? collisionMaxY > THIN_MAX_Y : collisionMaxY > STEP_HEIGHT;
    }

    /**
     * {@link #blocksHorizontal} with two exceptions. A door or trapdoor the bot can pass
     * (wooden, or iron and already open) never blocks, even though its panel reports a
     * full-height collision box. In the FEET row a standable cell (stairs, slabs, snow
     * layers, farmland, dirt path, mud, soul sand, honey: {@code WalkablePartialBlocks
     * .isStandable}) never blocks, because the bot stands IN that cell. {@code standable}
     * is ignored for the head row.
     */
    public static boolean blocksHorizontalCell(boolean passableDoorOrTrapdoor, boolean standable,
                                               boolean collisionEmpty, double collisionMaxY, boolean isHead) {
        if (passableDoorOrTrapdoor || (standable && !isHead)) {
            return false;
        }
        return blocksHorizontal(collisionEmpty, collisionMaxY, isHead);
    }

    /**
     * Quick-nudge drop of exactly one block: landing feet and head pathable, the bot's
     * transit row above the landing (the cell it crosses at its current height) pathable,
     * a collision floor under the landing, and no hazard or fluid where it lands.
     */
    public static boolean dropOneAllowed(boolean landingFeetPathable, boolean landingHeadPathable,
                                         boolean transitAbovePathable, boolean floorCollisionNonEmpty,
                                         boolean floorHazard, boolean landingHazard, boolean landingFluid) {
        return landingFeetPathable && landingHeadPathable && transitAbovePathable
                && floorCollisionNonEmpty && !floorHazard && !landingHazard && !landingFluid;
    }

    /**
     * A jump-sprint escape worked only when the bot ended in a different X or Z cell. A Y-only
     * change (the bot read mid-jump, or landed back on the same column) is not an escape. The
     * caller waits (bounded) for the landing after its stop, which zeroes velocity near the apex:
     * a sideways change counts once the bot is on the ground, or when it is still airborne after
     * the whole wait ({@code landWaitTimedOut}, e.g. falling down a slope), but not on an early
     * airborne read.
     */
    public static boolean jumpSprintEscaped(int startX, int startZ, int endX, int endZ,
                                            boolean onGround, boolean landWaitTimedOut) {
        return (onGround || landWaitTimedOut) && (startX != endX || startZ != endZ);
    }
}
