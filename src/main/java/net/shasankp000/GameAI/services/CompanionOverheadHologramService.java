package net.shasankp000.GameAI.services;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side fallback for overhead dialogue: spawns an invisible marker armor stand whose
 * custom-name is visible (classic "hologram" trick).
 *
 * <p>This avoids client-side rendering limitations for fakeplayer bots.
 * Best-effort and short-lived.
 */
public final class CompanionOverheadHologramService {

    // Name tags are rendered by the client at roughly: entityY + entityHeight + 0.5.
    // To make an armor-stand name tag appear just ABOVE the *bot's* name tag,
    // we place the stand such that its top is slightly above the bot's top.

    // Small vertical nudge so the hologram doesn't overlap the bot nameplate.
    private static final double NAMEPLATE_EXTRA_Y = 0.25D;

    // One hologram per bot.
    private static final ConcurrentHashMap<UUID, ActiveHologram> ACTIVE = new ConcurrentHashMap<>();

    private record ActiveHologram(UUID botUuid, ArmorStandEntity stand, String line, long expiresAtMs) {
    }

    private CompanionOverheadHologramService() {
    }

    public static void show(ServerPlayerEntity bot, String line, int durationMs) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID botId = bot.getUuid();
        if (botId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(250, durationMs);

        // If there is an existing stand, reuse it (cheaper + smoother).
        ActiveHologram existing = ACTIVE.get(botId);
        ArmorStandEntity stand = existing != null ? existing.stand : null;
        if (stand == null || stand.isRemoved() || stand.getEntityWorld() != world) {
            stand = spawnStand(world, bot, line);
            if (stand == null) {
                return;
            }
        } else {
            stand.setCustomName(styled(line));
            stand.setCustomNameVisible(true);
        }

        // Keep it positioned correctly immediately (not just next tick).
        double standY = desiredStandY(bot, stand);
        stand.refreshPositionAndAngles(bot.getX(), standY, bot.getZ(), 0.0f, 0.0f);

        ACTIVE.put(botId, new ActiveHologram(botId, stand, line, expiresAt));
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (var entry : ACTIVE.entrySet()) {
            UUID botId = entry.getKey();
            ActiveHologram holo = entry.getValue();
            if (botId == null || holo == null) {
                if (botId != null) ACTIVE.remove(botId);
                continue;
            }

            ArmorStandEntity stand = holo.stand;
            if (stand == null || stand.isRemoved()) {
                ACTIVE.remove(botId);
                continue;
            }

            if (now >= holo.expiresAtMs) {
                stand.discard();
                ACTIVE.remove(botId);
                continue;
            }

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved() || !(bot.getEntityWorld() instanceof ServerWorld world)) {
                stand.discard();
                ACTIVE.remove(botId);
                continue;
            }

            // Keep following the bot.
            if (stand.getEntityWorld() != world) {
                stand.discard();
                ACTIVE.remove(botId);
                continue;
            }

            // Refresh position each tick; marker armor stands are lightweight.
            double standY = desiredStandY(bot, stand);
            stand.refreshPositionAndAngles(bot.getX(), standY, bot.getZ(), 0.0f, 0.0f);
        }
    }

    private static Text styled(String line) {
        // Gold + bold reads well against foliage and bright biomes.
        return Text.literal(line).formatted(Formatting.GOLD, Formatting.BOLD);
    }

    private static double desiredStandY(ServerPlayerEntity bot, ArmorStandEntity stand) {
        double botTop = bot.getY() + bot.getHeight();
        double standH = stand != null ? stand.getHeight() : 1.975;
        return (botTop - standH) + NAMEPLATE_EXTRA_Y;
    }

    private static ArmorStandEntity spawnStand(ServerWorld world, ServerPlayerEntity bot, String line) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world, SpawnReason.COMMAND);
        if (stand == null) {
            return null;
        }

        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(styled(line));
        stand.setCustomNameVisible(true);

        double standY = desiredStandY(bot, stand);
        stand.refreshPositionAndAngles(bot.getX(), standY, bot.getZ(), 0.0f, 0.0f);
        world.spawnEntity(stand);
        return stand;
    }
}
