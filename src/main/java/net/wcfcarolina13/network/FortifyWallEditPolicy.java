package net.wcfcarolina13.network;

import java.util.List;

/**
 * Minecraft-free decision for a FORTIFY UI action that edits a saved wall (resume, patch,
 * patch_auto, moat, expand; see {@link BotActionPolicy#fortifyEditsSavedWall}).
 *
 * <p>The wall checked is the one {@code FortifyVillageSkill} will use: the named wall, looked up
 * with {@code FortificationPersistenceService.load} (the skill's own lookup), or for a blank label
 * the nearest saved wall to the bot by XZ distance to its centre, strict {@code <}, first wins on
 * a tie (same as {@code FortifyVillageSkill.findNearestWallName}).
 *
 * <p>No wall found is allowed, because the skill then refuses or creates a new wall:
 * blank resume/patch/expand fail with "No saved walls found"; named resume/patch/expand fail with
 * "No saved wall named ..."; moat (blank or named) bootstraps a NEW fort from a nearby village,
 * which edits nobody else's wall, the same as {@code fortify start}.
 */
public final class FortifyWallEditPolicy {

    private FortifyWallEditPolicy() {}

    /** A saved wall reduced to what the decision needs. */
    public record WallRef(String name, String ownerUuid, int centerX, int centerZ) {}

    /**
     * @param wallFound whether the wall the skill will use exists
     * @param ownerUuid that wall's owner ("" for legacy walls with no owner)
     */
    public static boolean mayEdit(boolean wallFound, String ownerUuid, String requesterUuid,
                                  boolean isOp, boolean isHost) {
        if (!wallFound) {
            return true;
        }
        return BaseAccessPolicy.canEdit(BaseAccessPolicy.EntryKind.WALL, requesterUuid, ownerUuid, isOp, isHost);
    }

    /** The wall nearest to (botX, botZ) in XZ, first wins on a tie, or null when there is none. */
    public static WallRef nearest(List<WallRef> walls, int botX, int botZ) {
        if (walls == null) {
            return null;
        }
        WallRef nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (WallRef w : walls) {
            if (w == null) {
                continue;
            }
            double dx = botX - w.centerX();
            double dz = botZ - w.centerZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = w;
            }
        }
        return nearest;
    }
}
