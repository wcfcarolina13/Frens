package net.wcfcarolina13.network;

/** Minecraft-free permission decisions shared by base and zone receivers. */
public final class BaseAccessPolicy {
    public enum EntryKind { BASE, WALL, VILLAGE, MISSING }

    private BaseAccessPolicy() {}

    public static boolean canEdit(EntryKind kind, String requester, String owner,
                                  boolean isOp, boolean isHost) {
        if (kind == EntryKind.MISSING || kind == null) return false;
        if (isOp || isHost) return true;
        // Mapped villages have no owner metadata; only an operator or host may edit them.
        if (kind == EntryKind.VILLAGE) return false;
        return requester != null && owner != null && !owner.isBlank()
                && !owner.equals("SERVER") && requester.equals(owner);
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
