package net.wcfcarolina13.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Who sees which companions in the multiplayer roster (Bradley's ruling, 2026-11-08): an operator
 * or the host sees every companion on the server and can restore or create; any other player sees
 * only companions they own and can do neither. Pure so it is unit tested.
 */
public final class RosterVisibilityPolicy {

    /** Most names sent in one list; a server with more logs it and the rest stay off the screen. */
    public static final int MAX_LISTED = 512;
    /** Longest alias sent; real aliases are at most 24 characters (the roster's Create limit). */
    public static final int MAX_ALIAS_LEN = 64;
    /** One roster request per player per this window; the roster asks once each time it opens. */
    public static final long MIN_REQUEST_INTERVAL_MS = 500L;

    private RosterVisibilityPolicy() {
    }

    /**
     * The aliases {@code viewerUuid} may see, in input order. {@code ownerUuidOf} returns an
     * alias's owner UUID string or null. A companion with no recorded owner is shown to admins only.
     */
    public static List<String> visible(List<String> aliases, Function<String, String> ownerUuidOf,
                                       String viewerUuid, boolean admin) {
        List<String> out = new ArrayList<>();
        if (aliases == null) {
            return out;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            if (admin || ownedBy(ownerUuidOf == null ? null : ownerUuidOf.apply(alias), viewerUuid)) {
                out.add(alias);
            }
        }
        return out;
    }

    static boolean ownedBy(String ownerUuid, String viewerUuid) {
        return ownerUuid != null && !ownerUuid.isBlank() && viewerUuid != null && !viewerUuid.isBlank()
                && ownerUuid.trim().equalsIgnoreCase(viewerUuid.trim());
    }

    /** Non-admins get no other-worlds list: they cannot bring a companion in. */
    public static List<String> otherWorldsFor(List<String> otherWorlds, boolean admin) {
        return admin && otherWorlds != null ? otherWorlds : List.of();
    }

    /** At most {@link #MAX_LISTED} names, each no longer than {@link #MAX_ALIAS_LEN}. */
    public static List<String> capForPayload(List<String> aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null) {
            return out;
        }
        for (String alias : aliases) {
            if (out.size() >= MAX_LISTED) {
                break;
            }
            if (alias != null && !alias.isBlank() && alias.length() <= MAX_ALIAS_LEN) {
                out.add(alias);
            }
        }
        return out;
    }

    /** Whether a request at {@code nowMs} is allowed after one at {@code lastMs} (0 = none yet). */
    public static boolean requestAllowed(long lastMs, long nowMs) {
        return lastMs <= 0L || nowMs - lastMs >= MIN_REQUEST_INTERVAL_MS || nowMs < lastMs;
    }
}
