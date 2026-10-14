package net.wcfcarolina13.network;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Minecraft-free decision for a FORTIFY UI action that touches a saved wall: the edit subs
 * (resume, patch, patch_auto, moat, expand; see {@link BotActionPolicy#fortifyEditsSavedWall})
 * and the read subs (status, drift; see {@link BotActionPolicy#fortifyReadsSavedWall}).
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

    /** What the FORTIFY sub does with the wall it resolves. */
    public enum Use {
        /** Changes the wall: owner of the wall, operator or host. */
        EDIT,
        /** Reports on the wall (coordinates, progress): anyone the Base screen shows it to. */
        VIEW
    }

    /**
     * Words the {@code /bot skill} argument parser or {@code handlePatch} consume before the skill
     * reads the wall name: {@code auto} (patch's repeat flag, stripped from the end), and the
     * ascent/descent words of {@code modCommandRegistry.executeSkill}.
     */
    public static final Set<String> REWRITTEN_WORDS = Set.of(
            "auto", "ascent", "ascend", "descent", "descend", "ascent-y", "descent-y");

    /**
     * Whether {@code executeSkill} or {@code handlePatch} would drop or reinterpret this label
     * token, so the wall the skill uses would differ from the one checked: a
     * {@link #REWRITTEN_WORDS} word (any case), any {@code key=value} shape (any {@code =}), or an
     * int ({@code Integer.parseInt}, the parser's own count test). Block ids are checked
     * separately because they need the block registry.
     */
    public static boolean isRewrittenToken(String token) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        if (REWRITTEN_WORDS.contains(token.toLowerCase(Locale.ROOT)) || token.indexOf('=') >= 0) {
            return true;
        }
        try {
            Integer.parseInt(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Whether every space-separated token of {@code label} reaches the skill unchanged: none is
     * {@link #isRewrittenToken rewritten} and none is a block id ({@code isBlockId} mirrors the
     * parser's registry test, which moves block ids into {@code targetBlocks}).
     */
    public static boolean labelReachesSkillIntact(String label, Predicate<String> isBlockId) {
        if (label == null || label.isBlank()) {
            return false;
        }
        for (String token : label.trim().split("\\s+")) {
            if (isRewrittenToken(token) || (isBlockId != null && isBlockId.test(token))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a FORTIFY sub may use the wall the skill will pick.
     *
     * @param wallFound          whether that wall exists; none is allowed for both uses (the
     *                           skill then refuses, reports there is none, or builds a new wall)
     * @param ownerUuid          its owner ("" for legacy walls with no owner)
     * @param visibleToRequester the Base screen's visibility rule for the requester (owner, ally
     *                           or explicitly granted)
     */
    public static boolean mayUse(Use use, boolean wallFound, String ownerUuid, boolean visibleToRequester,
                                 String requesterUuid, boolean isOp, boolean isHost) {
        if (!wallFound) {
            return true;
        }
        if (use == Use.VIEW) {
            return isOp || isHost || visibleToRequester;
        }
        return mayEdit(true, ownerUuid, requesterUuid, isOp, isHost);
    }

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
