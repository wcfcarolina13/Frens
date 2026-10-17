package net.wcfcarolina13.GameAI.services;

import java.util.Set;

/**
 * Pure rules for saving a fast-travel post-arrival action with its pending travel
 * ({@code config/frens/pending_travels.json}) so a world reload mid-trip still runs it on arrival.
 * No Minecraft types, so it is unit tested.
 *
 * <p>Saved: {@code retrieve:<mode>} (death retrieval) and {@code withdraw:stay|player|home}
 * (chest collect). Not saved: {@code skill_resume:} (ruling: a resumed skill after a reload is
 * not worth the risk of replaying a stale command).
 */
public final class PostArrivalPersistPolicy {

    private static final String WITHDRAW_PREFIX = "withdraw:";
    private static final Set<String> WITHDRAW_MODES = Set.of("stay", "player", "home");

    /** What loading does with one saved entry. */
    public enum Restore {
        /** Restore the action with its travel. */
        KEEP,
        /** The travel belongs to another save world: skip it here, keep it in the file. */
        DROP_WORLD,
        /** The action has no travel to ride on (the travel entry was malformed). */
        DROP_ORPHAN,
        /** The action's target is not this trip's destination, so it would never fire. */
        DROP_WRONG_TRIP
    }

    private PostArrivalPersistPolicy() {}

    /** True when an action of this type is saved with its travel. */
    public static boolean persistable(String type) {
        if (type == null) return false;
        if (DeathRetrievalPolicy.parseAction(type).isPresent()) return true;
        return type.startsWith(WITHDRAW_PREFIX) && WITHDRAW_MODES.contains(type.substring(WITHDRAW_PREFIX.length()));
    }

    /**
     * True when a saved travel belongs to the current save world. An entry saved before world keys
     * existed (null) or a server whose key is unknown (null) keeps the old restore-everywhere rule.
     */
    public static boolean sameWorld(String savedWorld, String currentWorld) {
        return savedWorld == null || currentWorld == null || savedWorld.equals(currentWorld);
    }

    /** The fate of a saved action on load. World mismatch wins over every other reason. */
    public static Restore restore(String savedWorld, String currentWorld, boolean hasTravel, boolean targetEqualsDest) {
        if (!sameWorld(savedWorld, currentWorld)) return Restore.DROP_WORLD;
        if (!hasTravel) return Restore.DROP_ORPHAN;
        if (!targetEqualsDest) return Restore.DROP_WRONG_TRIP;
        return Restore.KEEP;
    }

    /** {@code {x, y, z}} when all three are present, else null. */
    public static int[] intTriple(Integer x, Integer y, Integer z) {
        if (x == null || y == null || z == null) return null;
        return new int[] {x, y, z};
    }

    /** {@code {x, y, z}} when all three are present and finite, else null. */
    public static double[] doubleTriple(Double x, Double y, Double z) {
        if (x == null || y == null || z == null) return null;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        return new double[] {x, y, z};
    }

    /** True when the saved target equals the saved travel destination. */
    public static boolean targetEqualsDest(int[] target, int destX, int destY, int destZ) {
        return target != null && target.length == 3
                && target[0] == destX && target[1] == destY && target[2] == destZ;
    }
}
