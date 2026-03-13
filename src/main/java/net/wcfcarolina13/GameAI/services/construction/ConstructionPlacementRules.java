package net.wcfcarolina13.GameAI.services.construction;

/**
 * Shared tuning constants for construction reach, stance, and scaffold behavior.
 *
 * <p>These values deliberately live outside individual skills so generic
 * schematic building, shelter builders, and shared recovery services can stay
 * aligned as construction parity work proceeds.</p>
 */
public final class ConstructionPlacementRules {

    /** Vanilla survival interaction reach: ~4.5 blocks. */
    public static final double REACH_DISTANCE_SQ = 20.25D;

    /** Accept approximate scaffold stance arrival within a 3-block horizontal radius. */
    public static final double APPROXIMATE_STANCE_HORIZONTAL_DISTANCE_SQ = 9.0D;

    /** Prefer current position as a scaffold stance when already within 2 blocks XZ. */
    public static final double CLOSE_STANCE_HORIZONTAL_DISTANCE_SQ = 4.0D;

    /** Generic construction scaffold cap. Fortify may still choose stricter limits. */
    public static final int DEFAULT_MAX_SCAFFOLD_HEIGHT = 12;

    private ConstructionPlacementRules() {
    }
}