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
    // Vertical gap between stacked name tags: one name-tag line is ~10 font px at
    // ~0.025 block/px, so 0.27 leaves a sliver between the dark backgrounds.
    private static final double LINE_SPACING_Y = 0.27D;
    private static final String HOLOGRAM_TAG = "frens.overhead_hologram";
    private static final long STALE_SWEEP_INTERVAL_MS = 30_000L;
    private static final long LEGACY_SWEEP_WINDOW_MS = 10 * 60_000L;
    private static final TextColor GOLD_TEXT_COLOR = TextColor.fromFormatting(Formatting.GOLD);

    // One hologram per bot (up to OverheadLinePolicy.DEFAULT_MAX_LINES stacked stands).
    private static final ConcurrentHashMap<UUID, ActiveHologram> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean startupSweepDone = false;
    private static volatile long lastStaleSweepAtMs = 0L;
    private static volatile long startupSweepStartedAtMs = 0L;

    /** {@code stands.get(0)} is the top line. */
    private record ActiveHologram(UUID botUuid, List<ArmorStandEntity> stands, String line, long expiresAtMs) {
    }

    private CompanionOverheadHologramService() {
    }

    /**
     * Discard all active hologram armor stands and clear tracking state.
     * Called on server shutdown to prevent orphaned entities persisting to disk.
     */
    public static void removeAll() {
        for (var entry : ACTIVE.values()) {
            if (entry != null) {
                discardAll(entry.stands);
            }
        }
        ACTIVE.clear();
        startupSweepDone = false;
        lastStaleSweepAtMs = 0L;
        startupSweepStartedAtMs = 0L;
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
        // Entity spawning + positioning must happen on the server thread. Callers like
        // WoodcutSkill.execute run on skill worker threads; re-enqueue rather than crashing
        // C2ME's preventAsyncEntityLoad guard.
        MinecraftServer server = world.getServer();
        if (server != null && !server.isOnThread()) {
            server.execute(() -> show(bot, line, durationMs));
            return;
        }
        UUID botId = bot.getUuid();
        if (botId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(250, durationMs);

        List<String> lines = OverheadLinePolicy.wrap(line);
        if (lines.isEmpty()) {
            return;
        }

        // Reuse existing stands where possible (cheaper + smoother); discard the extras.
        ActiveHologram existing = ACTIVE.get(botId);
        List<ArmorStandEntity> old = existing != null ? existing.stands : List.of();
        List<ArmorStandEntity> stands = new ArrayList<>(lines.size());
        try {
            for (int i = 0; i < lines.size(); i++) {
                ArmorStandEntity stand = i < old.size() ? old.get(i) : null;
                if (stand == null || stand.isRemoved() || stand.getEntityWorld() != world) {
                    if (stand != null && !stand.isRemoved()) {
                        stand.discard();
                    }
                    stand = spawnStand(world, bot, lines.get(i));
                    if (stand == null) {
                        discardAll(stands);
                        discardAll(old.subList(Math.min(i + 1, old.size()), old.size()));
                        ACTIVE.remove(botId);
                        return;
                    }
                } else {
                    stand.setCustomName(styled(lines.get(i)));
                    stand.setCustomNameVisible(true);
                }
                stands.add(stand);
            }
            if (old.size() > lines.size()) {
                discardAll(old.subList(lines.size(), old.size()));
            }

            // Keep them positioned correctly immediately (not just next tick).
            positionStands(bot, stands);
        } catch (RuntimeException e) {
            // A spawn that throws mid-stack would leave the new stands untracked until the
            // 30 s sweep. Drop the whole stack, old and new, so nothing lingers.
            discardAll(stands);
            discardAll(old);
            ACTIVE.remove(botId);
            throw e;
        }

        ACTIVE.put(botId, new ActiveHologram(botId, List.copyOf(stands), line, expiresAt));
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

            List<ArmorStandEntity> stands = holo.stands;
            if (stands == null || stands.isEmpty() || anyRemoved(stands)) {
                // A half-gone stack would read as a broken sentence; drop the rest too.
                if (stands != null) discardAll(stands);
                ACTIVE.remove(botId);
                continue;
            }

            if (now >= holo.expiresAtMs) {
                discardAll(stands);
                ACTIVE.remove(botId);
                continue;
            }

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved() || !(bot.getEntityWorld() instanceof ServerWorld world)) {
                discardAll(stands);
                ACTIVE.remove(botId);
                continue;
            }

            // Keep following the bot.
            if (anyInOtherWorld(stands, world)) {
                discardAll(stands);
                ACTIVE.remove(botId);
                continue;
            }

            // Refresh position each tick; marker armor stands are lightweight.
            positionStands(bot, stands);
        }
    }

    private static Text styled(String line) {
        // Gold + bold reads well against foliage and bright biomes.
        return Text.literal(line).formatted(Formatting.GOLD, Formatting.BOLD);
    }

    /** Bottom line keeps the old single-line height; earlier lines stack above it. */
    private static void positionStands(ServerPlayerEntity bot, List<ArmorStandEntity> stands) {
        int n = stands.size();
        for (int i = 0; i < n; i++) {
            ArmorStandEntity stand = stands.get(i);
            double standY = desiredStandY(bot, stand) + (n - 1 - i) * LINE_SPACING_Y;
            stand.refreshPositionAndAngles(bot.getX(), standY, bot.getZ(), 0.0f, 0.0f);
        }
    }

    private static void discardAll(List<ArmorStandEntity> stands) {
        for (ArmorStandEntity stand : stands) {
            if (stand != null && !stand.isRemoved()) {
                stand.discard();
            }
        }
    }

    private static boolean anyRemoved(List<ArmorStandEntity> stands) {
        for (ArmorStandEntity stand : stands) {
            if (stand == null || stand.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyInOtherWorld(List<ArmorStandEntity> stands, ServerWorld world) {
        for (ArmorStandEntity stand : stands) {
            if (stand.getEntityWorld() != world) {
                return true;
            }
        }
        return false;
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
            if (holo == null || holo.stands() == null) {
                continue;
            }
            for (ArmorStandEntity stand : holo.stands()) {
                if (stand != null && !stand.isRemoved()) {
                    activeStandIds.add(stand.getUuid());
                }
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
