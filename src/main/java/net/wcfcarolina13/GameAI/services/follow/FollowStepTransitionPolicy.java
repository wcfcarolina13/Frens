package net.wcfcarolina13.GameAI.services.follow;

/**
 * Pure-logic policy: which extra headroom cell a walking (non-swim) follow-plan step between two
 * standable cells must have clear, and whether the step is allowed given that cell.
 *
 * <p>No Minecraft imports.
 *
 * <p>Both standable ends already have feet and head clear. A step that changes Y crosses through a
 * third cell the endpoints do not cover:
 * <ul>
 *   <li>step up (dY = +1): the bot jumps before it moves, so its head reaches {@code from.up(2)}.
 *       Out of the snapshot counts as blocked (the original rule).</li>
 *   <li>step down (dY = -1): the bot walks off at its old height, so mid-crossing its head is at
 *       {@code to.up(2)} in the destination column. Out of the snapshot counts as clear, so the
 *       snapshot's top edge does not start rejecting step-downs it allowed before.</li>
 *   <li>level step (dY = 0): nothing extra.</li>
 * </ul>
 *
 * <p>Finding 47 (1.1.276 sandbox): under an oak canopy edge A* kept choosing a one-block step
 * down whose {@code to.up(2)} was oak leaves; the movement gate rejected every tick
 * ({@code head-not-passable}) and the follow stalled 40+ s.
 */
public final class FollowStepTransitionPolicy {

    private FollowStepTransitionPolicy() {
    }

    /** Which cell the caller has to look up for a step of the given dY. */
    public enum HeadroomCell {
        /** Level step: no extra cell. */
        NONE,
        /** Step up: {@code from.up(2)}. */
        FROM_UP2,
        /** Step down: {@code to.up(2)}. */
        TO_UP2
    }

    public static HeadroomCell headroomCell(int dY) {
        if (dY > 0) {
            return HeadroomCell.FROM_UP2;
        }
        if (dY < 0) {
            return HeadroomCell.TO_UP2;
        }
        return HeadroomCell.NONE;
    }

    /**
     * @param dY             to.y - from.y (callers already rejected |dY| &gt; 1)
     * @param cellInBounds   whether the {@link #headroomCell(int)} cell is inside the snapshot
     * @param cellPassable   whether that cell is passable (ignored when out of bounds)
     */
    public static boolean allows(int dY, boolean cellInBounds, boolean cellPassable) {
        switch (headroomCell(dY)) {
            case FROM_UP2:
                return cellInBounds && cellPassable;
            case TO_UP2:
                return !cellInBounds || cellPassable;
            default:
                return true;
        }
    }
}
