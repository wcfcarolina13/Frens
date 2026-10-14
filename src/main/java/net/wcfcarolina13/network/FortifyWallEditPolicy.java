package net.wcfcarolina13.network;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Minecraft-free decision for a FORTIFY UI action that touches a saved wall: the hull edit sub
 * (expand), the work subs (resume, patch, patch_auto, moat) and the read subs (status, drift).
 * {@link #useForSub} maps a sub to its {@link Use}.
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
        /**
         * Changes the wall's saved hull ({@code expand}): owner of the wall, operator or host.
         * Grantees are refused, so a grantee cannot grow the owner's protected area.
         */
        EDIT,
        /**
         * Builds or repairs the wall's existing plan ({@code resume}, {@code patch},
         * {@code patch_auto}, {@code moat}): owner, operator, host, or a player the owner granted
         * access to (Grant Wall Access). Matches the in-world rule that already lets a grantee's
         * bot work in the hull.
         */
        WORK,
        /** Reports on the wall (coordinates, progress): anyone the Base screen shows it to. */
        VIEW
    }

    /** Why a wall check refused. */
    public enum Refusal {
        /** Allowed. */
        NONE,
        /** The wall has no owner (a legacy wall) and the requester is not an operator or host. */
        LEGACY_OWNERLESS,
        /** The wall has an owner and the requester is not allowed. */
        NOT_PERMITTED
    }

    /** Subs a wall grantee may run: they work on the existing plan without changing the hull. */
    public static final Set<String> WORK_SUBS = Set.of("resume", "patch", "patch_auto", "moat");
    /** Subs that change the saved hull: owner, operator or host only. */
    public static final Set<String> EDIT_SUBS = Set.of("expand");
    /** Subs that only report on a saved wall. */
    public static final Set<String> VIEW_SUBS = Set.of("status", "drift");

    /**
     * The {@link Use} a FORTIFY sub needs, or null when the sub does not touch a saved wall
     * (start, list, unknown). Callers pick the check with this so expand and the work subs get
     * different rules.
     */
    public static Use useForSub(String sub) {
        if (sub == null) return null;
        if (EDIT_SUBS.contains(sub)) return Use.EDIT;
        if (WORK_SUBS.contains(sub)) return Use.WORK;
        if (VIEW_SUBS.contains(sub)) return Use.VIEW;
        return null;
    }

    /** Player-facing text for a refusal (no em dashes, no semicolons). */
    public static String refusalMessage(Refusal refusal) {
        if (refusal == Refusal.LEGACY_OWNERLESS) {
            return "That wall has no owner. Claim it in the Base screen first.";
        }
        return "You cannot work on that wall.";
    }

    /** Whether {@code ownerUuid} names a real player (not blank, not the SERVER marker). */
    static boolean hasPlayerOwner(String ownerUuid) {
        return ownerUuid != null && !ownerUuid.isBlank() && !ownerUuid.equals("SERVER");
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
     * @param wallFound          whether that wall exists; none is allowed for every use (the
     *                           skill then refuses, reports there is none, or builds a new wall)
     * @param ownerUuid          its owner ("" or null for legacy walls with no owner)
     * @param visibleToRequester the Base screen's visibility rule for the requester (owner, ally
     *                           or explicitly granted)
     * @param granted            whether the requester is in the wall's granted list; counts only
     *                           for {@link Use#WORK} and only on a wall with a player owner (an
     *                           unclaim keeps the granted list, and a legacy wall stays admin-only)
     */
    public static boolean mayUse(Use use, boolean wallFound, String ownerUuid, boolean visibleToRequester,
                                 boolean granted, String requesterUuid, boolean isOp, boolean isHost) {
        return refusal(use, wallFound, ownerUuid, visibleToRequester, granted, requesterUuid, isOp, isHost)
                == Refusal.NONE;
    }

    /** Like {@link #mayUse}, with the reason for a refusal. */
    public static Refusal refusal(Use use, boolean wallFound, String ownerUuid, boolean visibleToRequester,
                                  boolean granted, String requesterUuid, boolean isOp, boolean isHost) {
        if (!wallFound) {
            return Refusal.NONE;
        }
        boolean allowed;
        if (use == Use.VIEW) {
            allowed = isOp || isHost || visibleToRequester;
        } else {
            allowed = mayEdit(true, ownerUuid, requesterUuid, isOp, isHost)
                    || (use == Use.WORK && granted && hasPlayerOwner(ownerUuid));
        }
        if (allowed) {
            return Refusal.NONE;
        }
        return ownerUuid == null || ownerUuid.isBlank() ? Refusal.LEGACY_OWNERLESS : Refusal.NOT_PERMITTED;
    }

    /**
     * Owner, operator or host (no grantees). Used directly for {@link Use#EDIT}.
     *
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
