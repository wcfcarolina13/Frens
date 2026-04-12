package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.createFakePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent block-break activity by real players. Used by the drop-sweep path so bots
 * back off from items popping out of blocks the player is actively mining — otherwise the
 * opportunistic sweeper shoves the player in tight tunnels.
 *
 * <p>Fake-player bots are intentionally excluded from tracking; their drop-handling runs
 * through dedicated skill paths and we don't want bots to stop picking up their own loot.
 */
public final class CommanderActivityService {

    /** Drops within this distance of a recently-mining player are off-limits to idle sweeps. */
    public static final double MINING_DROP_EXCLUSION_RADIUS = 6.0D;
    /** A bot within this range of a recently-mining player has ALL sweeps suppressed. */
    public static final double BOT_MINING_PROXIMITY_RADIUS = 8.0D;
    /** How long after a block break a player is considered "actively mining". */
    public static final long MINING_WINDOW_MS = 4000L;

    private static final ConcurrentHashMap<UUID, Long> lastBreakMs = new ConcurrentHashMap<>();

    private CommanderActivityService() {}

    /** Call from a block-break event. No-op for fake-player bots. */
    public static void markBlockBreak(ServerPlayerEntity player) {
        if (player == null || player instanceof createFakePlayer) return;
        lastBreakMs.put(player.getUuid(), System.currentTimeMillis());
    }

    public static boolean isPlayerRecentlyMining(UUID playerId) {
        if (playerId == null) return false;
        Long ts = lastBreakMs.get(playerId);
        return ts != null && (System.currentTimeMillis() - ts) < MINING_WINDOW_MS;
    }

    /**
     * True if any real player in {@code world} has broken a block within {@link #MINING_WINDOW_MS}
     * and is currently within {@link #MINING_DROP_EXCLUSION_RADIUS} of the drop position.
     */
    public static boolean isDropNearActiveMiner(ServerWorld world, Vec3d dropPos) {
        if (world == null || dropPos == null) return false;
        long now = System.currentTimeMillis();
        double r = MINING_DROP_EXCLUSION_RADIUS;
        double rSq = r * r;
        Box box = new Box(
                dropPos.x - r, dropPos.y - r, dropPos.z - r,
                dropPos.x + r, dropPos.y + r, dropPos.z + r);
        List<ServerPlayerEntity> nearby = world.getEntitiesByClass(
                ServerPlayerEntity.class,
                box,
                p -> p != null && p.isAlive() && !(p instanceof createFakePlayer));
        for (ServerPlayerEntity p : nearby) {
            Long ts = lastBreakMs.get(p.getUuid());
            if (ts == null) continue;
            if (now - ts >= MINING_WINDOW_MS) continue;
            if (p.squaredDistanceTo(dropPos.x, dropPos.y, dropPos.z) <= rSq) {
                return true;
            }
        }
        return false;
    }

    /** Convenience wrapper that pulls the drop's position from an {@link ItemEntity}. */
    public static boolean isDropNearActiveMiner(ServerWorld world, ItemEntity drop) {
        if (drop == null) return false;
        return isDropNearActiveMiner(world, new Vec3d(drop.getX(), drop.getY(), drop.getZ()));
    }

    /**
     * True if the bot is within {@code radius} of any real player who has broken a block within
     * {@link #MINING_WINDOW_MS}. Used as a global "don't sweep drops right now" gate, stronger than
     * per-drop filtering: while the commander is working, the bot stays clear of the tunnel entirely.
     */
    public static boolean isBotNearActiveMiner(ServerPlayerEntity bot, double radius) {
        if (bot == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        long now = System.currentTimeMillis();
        double rSq = radius * radius;
        Box box = bot.getBoundingBox().expand(radius, radius, radius);
        List<ServerPlayerEntity> nearby = world.getEntitiesByClass(
                ServerPlayerEntity.class,
                box,
                p -> p != null && p.isAlive() && !(p instanceof createFakePlayer));
        for (ServerPlayerEntity p : nearby) {
            Long ts = lastBreakMs.get(p.getUuid());
            if (ts == null) continue;
            if (now - ts >= MINING_WINDOW_MS) continue;
            if (bot.squaredDistanceTo(p) <= rSq) {
                return true;
            }
        }
        return false;
    }

    /** Convenience wrapper using the default proximity radius. */
    public static boolean isBotNearActiveMiner(ServerPlayerEntity bot) {
        return isBotNearActiveMiner(bot, BOT_MINING_PROXIMITY_RADIUS);
    }

    public static void clear() {
        lastBreakMs.clear();
    }
}
