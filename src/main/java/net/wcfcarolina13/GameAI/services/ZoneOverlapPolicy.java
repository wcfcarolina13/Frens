package net.wcfcarolina13.GameAI.services;

import java.util.List;

/**
 * Which of the protected zones containing a position refuses a bot block mutation. Pure: no
 * Minecraft types.
 *
 * <p>Rule: any refusing zone refuses, except that a refusing SERVER-owned base zone (the Spawn
 * base) yields when another containing zone that is not server-owned explicitly grants the bot's
 * owner (owner match or allowlist). So a host's own base drawn over the Spawn base works for the
 * host's bots, while the Spawn base still refuses wherever no player zone grants. A user-drawn
 * protected area or another player's base never yields, and alliances grant nothing on zones.
 */
public final class ZoneOverlapPolicy {

    /**
     * One containing zone.
     *
     * @param refuses     this zone alone would refuse the mutation (after the Spawn woodcut toggle)
     * @param serverOwned the zone is the auto-zone of a base owned literally by SERVER
     * @param grantsOwner the zone explicitly grants the bot's owner (zone owner or allowlist)
     */
    public record ZoneVerdict(boolean refuses, boolean serverOwned, boolean grantsOwner) {
    }

    private ZoneOverlapPolicy() {
    }

    /** Index of the first zone that refuses, or -1 when the mutation is allowed. */
    public static int firstRefusingIndex(List<ZoneVerdict> zones) {
        if (zones == null || zones.isEmpty()) {
            return -1;
        }
        boolean playerZoneGrants = false;
        for (ZoneVerdict zone : zones) {
            if (zone != null && !zone.serverOwned() && zone.grantsOwner()) {
                playerZoneGrants = true;
                break;
            }
        }
        for (int i = 0; i < zones.size(); i++) {
            ZoneVerdict zone = zones.get(i);
            if (zone == null || !zone.refuses()) {
                continue;
            }
            if (zone.serverOwned() && playerZoneGrants) {
                continue;
            }
            return i;
        }
        return -1;
    }
}
