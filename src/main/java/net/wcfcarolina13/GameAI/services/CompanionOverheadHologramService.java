package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String HOLOGRAM_TAG = "frens.overhead_hologram";
    private static final long STALE_SWEEP_INTERVAL_MS = 30_000L;
    private static final long LEGACY_SWEEP_WINDOW_MS = 10 * 60_000L;
    private static final TextColor GOLD_TEXT_COLOR = TextColor.fromFormatting(Formatting.GOLD);

    // One hologram per bot.
    private static final ConcurrentHashMap<UUID, ActiveHologram> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean startupSweepDone = false;
    private static volatile long lastStaleSweepAtMs = 0L;
    private static volatile long startupSweepStartedAtMs = 0L;

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
        if (startupSweepStartedAtMs <= 0L) {
            startupSweepStartedAtMs = now;
        }
        boolean includeLegacyUntyped = (now - startupSweepStartedAtMs) <= LEGACY_SWEEP_WINDOW_MS;
        if (!startupSweepDone || now - lastStaleSweepAtMs >= STALE_SWEEP_INTERVAL_MS) {
            sweepLingeringHolograms(server, includeLegacyUntyped);
            startupSweepDone = true;
            lastStaleSweepAtMs = now;
        }

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
        stand.setHideBasePlate(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(styled(line));
        stand.setCustomNameVisible(true);
        stand.addCommandTag(HOLOGRAM_TAG);

        double standY = desiredStandY(bot, stand);
        stand.refreshPositionAndAngles(bot.getX(), standY, bot.getZ(), 0.0f, 0.0f);
        world.spawnEntity(stand);
        return stand;
    }

    private static void sweepLingeringHolograms(MinecraftServer server, boolean includeLegacyUntyped) {
        if (server == null) {
            return;
        }
        Set<UUID> activeStandIds = new HashSet<>();
        for (ActiveHologram holo : ACTIVE.values()) {
            if (holo != null && holo.stand() != null && !holo.stand().isRemoved()) {
                activeStandIds.add(holo.stand().getUuid());
            }
        }

        for (ServerWorld world : server.getWorlds()) {
            if (world == null) {
                continue;
            }
            List<ArmorStandEntity> stale = new ArrayList<>();
            for (var entity : world.iterateEntities()) {
                if (!(entity instanceof ArmorStandEntity stand) || stand.isRemoved()) {
                    continue;
                }
                if (activeStandIds.contains(stand.getUuid())) {
                    if (!stand.getCommandTags().contains(HOLOGRAM_TAG)) {
                        stand.addCommandTag(HOLOGRAM_TAG);
                    }
                    continue;
                }
                if (isManagedHologram(stand) || (includeLegacyUntyped && isLegacyCompanionHologram(stand))) {
                    stale.add(stand);
                }
            }
            for (ArmorStandEntity stand : stale) {
                stand.discard();
            }
        }
    }

    private static boolean isManagedHologram(ArmorStandEntity stand) {
        return stand != null && stand.getCommandTags().contains(HOLOGRAM_TAG);
    }

    private static boolean isLegacyCompanionHologram(ArmorStandEntity stand) {
        if (stand == null || stand.getCommandTags().contains(HOLOGRAM_TAG)) {
            return false;
        }
        if (!stand.isInvisible() || !stand.hasNoGravity() || !stand.isInvulnerable() || !stand.isSilent()) {
            return false;
        }
        if (!stand.isCustomNameVisible() || !stand.hasCustomName()) {
            return false;
        }
        if (!isUnarmed(stand)) {
            return false;
        }
        Text customName = stand.getCustomName();
        if (customName == null) {
            return false;
        }
        String raw = customName.getString();
        if (raw == null || raw.isBlank() || raw.length() > 96) {
            return false;
        }
        var style = customName.getStyle();
        if (style != null && style.isBold()) {
            TextColor color = style.getColor();
            if (color != null && GOLD_TEXT_COLOR != null && color.equals(GOLD_TEXT_COLOR)) {
                return true;
            }
        }
        // Legacy fallback: old builds did not consistently preserve style metadata.
        return raw.contains(" ")
                && raw.length() <= 72
                && !raw.contains("\n")
                && stand.getCommandTags().isEmpty();
    }

    private static boolean isUnarmed(ArmorStandEntity stand) {
        if (stand == null) {
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = stand.getEquippedStack(slot);
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
