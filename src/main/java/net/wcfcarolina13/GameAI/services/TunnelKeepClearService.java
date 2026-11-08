package net.wcfcarolina13.GameAI.services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot tunnel heading, set by a skill while it digs a straight tunnel (stripmine) and read by
 * utility placement so a crafting table never goes into the tunnel ahead
 * ({@link TunnelKeepClearPolicy}). The skill clears it when it ends.
 */
public final class TunnelKeepClearService {

    private static final Map<UUID, int[]> HEADINGS = new ConcurrentHashMap<>();

    private TunnelKeepClearService() {
    }

    public static void set(UUID botId, int dirX, int dirZ) {
        if (botId != null) {
            HEADINGS.put(botId, new int[]{dirX, dirZ});
        }
    }

    public static void clear(UUID botId) {
        if (botId != null) {
            HEADINGS.remove(botId);
        }
    }

    /** Whether the cell is in the tunnel ahead of a bot standing at the given block. */
    public static boolean blocks(UUID botId, int botX, int botY, int botZ, int cellX, int cellY, int cellZ) {
        if (botId == null) {
            return false;
        }
        int[] heading = HEADINGS.get(botId);
        return heading != null && TunnelKeepClearPolicy.blocksTunnel(botX, botY, botZ,
                heading[0], heading[1], cellX, cellY, cellZ, TunnelKeepClearPolicy.DEFAULT_DEPTH);
    }
}
