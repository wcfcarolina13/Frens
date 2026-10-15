package net.wcfcarolina13.PathFinding.unified;

/**
 * Per-cell properties as bit flags, read once per cell per search (1.1.242 unified finder).
 *
 * <p>A {@link TerrainView} returns an OR of these for every cell. A cell outside the loaded world
 * returns 0, so it has no {@link #LOADED} bit and nothing can stand on it or move through it.
 * Pure: no {@code net.minecraft.*} types.</p>
 */
public final class CellProps {
    /** A bot can stand on this block's top (opaque, or walkable partial top). */
    public static final int SOLID_FLOOR = 1;
    /** A body fits here dry (air, pathable partial, openable wooden door/trapdoor). */
    public static final int PASSABLE_BODY = 1 << 1;
    /** Water with no collision whose cell below has SOLID_FLOOR. */
    public static final int WATER_WADE = 1 << 2;
    /** Water with no collision whose cell below is not a floor. */
    public static final int WATER_DEEP = 1 << 3;
    public static final int LAVA = 1 << 4;
    /** BotHazardService.isDeadlyBlock, magma, lit campfire, campfire berth. */
    public static final int HAZARD = 1 << 5;
    /** ProtectedZoneService.isProtected for the bot owner (strict). */
    public static final int PROTECTED = 1 << 6;
    /**
     * Never broken by a planned move: ProtectedStructureBlockHelper.isNeverBreakBlock, hasBlockEntity, a
     * door, and (on a view that may break) the executor's plannedStepTrimAllowed state refusals (logs,
     * planks, wool, fences, walls, gates, glass-like, dirt path, beds, shulkers, persistent leaves,
     * hardness out of 0..12) and cells near human blocks.
     */
    public static final int NEVER_BREAK = 1 << 7;
    /** FallingBlock (sand, gravel, concrete powder). */
    public static final int GRAVITY = 1 << 8;
    /** Bottom slab or stair top the bot steps onto without a jump. */
    public static final int SLAB_TOP = 1 << 9;
    /** Air or replaceable, not PROTECTED, has a solid face below. */
    public static final int PLACEABLE = 1 << 10;
    /** Chunk loaded. Everything else is 0 when unset. */
    public static final int LOADED = 1 << 11;
    /** LockableBlockService wall: never passable, never breakable. */
    public static final int LOCKED_DOOR = 1 << 12;

    /** Either water flag. */
    public static final int WATER = WATER_WADE | WATER_DEEP;

    private CellProps() {
    }

    public static boolean has(int props, int flag) {
        return (props & flag) != 0;
    }

    public static boolean loaded(int props) {
        return (props & LOADED) != 0;
    }

    /** Dry passable for a body or head: loaded, PASSABLE_BODY, never a locked door. */
    public static boolean passable(int props) {
        return (props & (LOADED | PASSABLE_BODY)) == (LOADED | PASSABLE_BODY) && (props & LOCKED_DOOR) == 0;
    }

    /** A cell a bot can stand on: loaded, SOLID_FLOOR, not a locked door. */
    public static boolean floor(int props) {
        return (props & (LOADED | SOLID_FLOOR)) == (LOADED | SOLID_FLOOR) && (props & LOCKED_DOOR) == 0;
    }

    public static boolean liquid(int props) {
        return (props & (WATER | LAVA)) != 0;
    }

    /** Short readable flag list for test failure messages and logs. */
    public static String describe(int props) {
        if (props == 0) {
            return "UNLOADED";
        }
        StringBuilder sb = new StringBuilder();
        String[] names = {"SOLID_FLOOR", "PASSABLE_BODY", "WATER_WADE", "WATER_DEEP", "LAVA", "HAZARD", "PROTECTED",
                "NEVER_BREAK", "GRAVITY", "SLAB_TOP", "PLACEABLE", "LOADED", "LOCKED_DOOR"};
        for (int i = 0; i < names.length; i++) {
            if ((props & (1 << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }
}
