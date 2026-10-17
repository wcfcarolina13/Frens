package net.wcfcarolina13.GameAI.services;

import java.util.Set;

/**
 * Pure rules for tactical shelters across a reload: which shelter types are saved, and whether a
 * saved shelter is restored on join.
 *
 * <p>The record is restored only when it is for this world (the store is keyed per world), this
 * dimension and the exact cell the bot stands in, and the shelter still validates. A wall-off cell
 * with a hostile outside still counts while its 8 side cells hold ({@link WallOffPolicy#keepOnRevalidate}).
 * Daytime does not matter: a restored shelter at day takes its normal type-correct dawn exit.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class ShelterRestorePolicy {

    private ShelterRestorePolicy() {}

    /**
     * Types whose shelter is saved. JOIN_ENCLOSED is not: it places nothing, and the join check
     * re-derives it from the world on every join anyway.
     */
    public static final Set<String> PERSISTED_TYPES = Set.of("WALL_OFF", "DIG_DOWN", "CLIFF", "VILLAGE_HOUSE");

    public static boolean persisted(String typeName) {
        return typeName != null && PERSISTED_TYPES.contains(typeName);
    }

    public enum Verdict {
        RESTORE,
        NO_RECORD,
        UNKNOWN_TYPE,
        WRONG_DIMENSION,
        MOVED,
        INVALID
    }

    /**
     * Checks that need no world read. Null means "assess the shelter now".
     *
     * @param savedInterior the saved interior cell, {x, y, z}; null or short is MOVED
     */
    public static Verdict precheck(boolean hasRecord,
                                   String savedType,
                                   String savedDimension,
                                   String dimension,
                                   int[] savedInterior,
                                   int x, int y, int z) {
        if (!hasRecord) {
            return Verdict.NO_RECORD;
        }
        if (!persisted(savedType)) {
            return Verdict.UNKNOWN_TYPE;
        }
        if (savedDimension == null || !savedDimension.equals(dimension)) {
            return Verdict.WRONG_DIMENSION;
        }
        if (savedInterior == null || savedInterior.length < 3
                || savedInterior[0] != x || savedInterior[1] != y || savedInterior[2] != z) {
            return Verdict.MOVED;
        }
        return null;
    }

    /**
     * @param valid           the shelter assessment for the saved type passed
     * @param wallOff         the saved type is WALL_OFF
     * @param reason          the assessment's invalid reason, lower case
     * @param wallOffSecured  all 8 side cells of the wall-off cell block movement
     */
    public static Verdict afterAssess(boolean valid, boolean wallOff, String reason, boolean wallOffSecured) {
        if (valid) {
            return Verdict.RESTORE;
        }
        if (wallOff && WallOffPolicy.keepOnRevalidate(reason, wallOffSecured)) {
            return Verdict.RESTORE;
        }
        return Verdict.INVALID;
    }
}
