package net.wcfcarolina13.network;

import net.wcfcarolina13.GameAI.services.StaleAutoZonePolicy;

/** Minecraft-free permission decisions shared by base and zone receivers. */
public final class BaseAccessPolicy {
    public enum EntryKind { BASE, WALL, VILLAGE, ZONE, MISSING }
    public enum ZoneEditDecision { ALLOW, NOT_FOUND, BASE_AUTO_ZONE, NOT_OWNER }

    private BaseAccessPolicy() {}

    public static boolean canMapVillage(boolean ownsBot, boolean isOp, boolean isHost) {
        return ownsBot || isOp || isHost;
    }

    public static boolean canClaimUnownedWall(boolean ownsBuilderBot, boolean isOp, boolean isHost) {
        return ownsBuilderBot || isOp || isHost;
    }

    public static boolean canEdit(EntryKind kind, String requester, String owner,
                                  boolean isOp, boolean isHost) {
        if (kind == EntryKind.MISSING || kind == null) return false;
        if (isOp || isHost) return true;
        // Mapped villages have no owner metadata; only an operator or host may edit them.
        if (kind == EntryKind.VILLAGE) return false;
        return requester != null && owner != null && !owner.isBlank()
                && !owner.equals("SERVER") && requester.equals(owner);
    }

    public static ZoneEditDecision zoneEditDecision(String label, boolean visible,
                                                     String requester, String owner,
                                                     boolean isOp, boolean isHost) {
        if (!visible) return ZoneEditDecision.NOT_FOUND;
        if (StaleAutoZonePolicy.isAutoZoneLabel(label) && !isOp && !isHost) {
            return ZoneEditDecision.BASE_AUTO_ZONE;
        }
        return canEdit(EntryKind.ZONE, requester, owner, isOp, isHost)
                ? ZoneEditDecision.ALLOW : ZoneEditDecision.NOT_OWNER;
    }

    public static boolean baseVisible(boolean isOp, boolean isHost, String requester,
                                      String owner, boolean serverOwned, boolean allied) {
        if (isOp || isHost) return true;
        if (serverOwned) return true;
        return owner != null && !owner.isBlank()
                && (owner.equals(requester) || allied);
    }

    public static boolean canSubscribe(int currentCount, boolean alreadySubscribed, int limit) {
        return alreadySubscribed || currentCount < limit;
    }
}
