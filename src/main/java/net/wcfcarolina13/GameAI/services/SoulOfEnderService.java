package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the Soul of Ender timed buff per bot. While active, all teleportation
 * gates (fixedGoalActive, forceWalk, allowTeleportPref) are bypassed so the bot
 * can shadow its commander fluidly via wolf-teleport.
 */
public final class SoulOfEnderService {

    private static final Logger LOGGER = LoggerFactory.getLogger("soul-of-ender");

    /** Default buff duration: 75 seconds (1500 game ticks). */
    public static final int DEFAULT_DURATION_TICKS = 1500;

    private record ActiveBuff(long expiryTick, UUID commanderUuid, String botAlias) {}

    /** Bot UUID → active buff state. */
    private static final Map<UUID, ActiveBuff> ACTIVE_BUFFS = new ConcurrentHashMap<>();

    private SoulOfEnderService() {}

    /** Check whether the Soul of Ender buff is currently active for a bot. O(1). */
    public static boolean isActive(UUID botUuid) {
        if (botUuid == null) return false;
        ActiveBuff buff = ACTIVE_BUFFS.get(botUuid);
        return buff != null;
    }

    /** Remaining ticks on the buff, or 0 if inactive. */
    public static long remainingTicks(UUID botUuid, long currentTick) {
        if (botUuid == null) return 0L;
        ActiveBuff buff = ACTIVE_BUFFS.get(botUuid);
        if (buff == null) return 0L;
        return Math.max(0L, buff.expiryTick() - currentTick);
    }

    /** Activate the buff for a bot. */
    public static void activate(UUID botUuid, long currentTick, int durationTicks,
                                UUID commanderUuid, String botAlias) {
        if (botUuid == null) return;
        long expiry = currentTick + durationTicks;
        ACTIVE_BUFFS.put(botUuid, new ActiveBuff(expiry, commanderUuid, botAlias));
        LOGGER.info("Soul of Ender activated for '{}' ({}), expires at tick {}",
                botAlias, botUuid, expiry);
    }

    /** Called every server tick. Expires buffs and notifies commanders. */
    public static void onServerTick(MinecraftServer server) {
        if (ACTIVE_BUFFS.isEmpty()) return;

        long now = server.getOverworld().getTime();
        Iterator<Map.Entry<UUID, ActiveBuff>> it = ACTIVE_BUFFS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveBuff> entry = it.next();
            ActiveBuff buff = entry.getValue();
            if (now >= buff.expiryTick()) {
                it.remove();
                LOGGER.info("Soul of Ender expired for '{}' ({})", buff.botAlias(), entry.getKey());

                // Notify commander if online.
                if (buff.commanderUuid() != null) {
                    ServerPlayerEntity commander = server.getPlayerManager().getPlayer(buff.commanderUuid());
                    if (commander != null) {
                        commander.sendMessage(Text.literal(
                                "\u00A77The Soul of Ender fades. " + buff.botAlias()
                                + "'s teleportation returns to normal.\u00A7r"), false);
                    }
                }
            }
        }
    }

    /** Clear all state on server stop. */
    public static void resetSession() {
        ACTIVE_BUFFS.clear();
    }

    /** Clear buff for a single bot (e.g. on death or disconnect). */
    public static void clearBot(UUID botUuid) {
        if (botUuid != null) {
            ACTIVE_BUFFS.remove(botUuid);
        }
    }
}
