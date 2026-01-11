package net.shasankp000.GameAI.services;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.network.CompanionOverheadLinePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows short-lived, in-world overhead dialogue above a companion/bot (not in chat).
 *
 * Intended for quick, high-signal “state” moments (e.g., foliage obstruction) where chat spam would be annoying.
 */
public final class CompanionOverheadDialogueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("companion-overhead");

    private static final long COOLDOWN_MS = 8_000L;
    private static final int DURATION_MS = 2_800;
    private static final double RANGE = 32.0;

    private static final ConcurrentHashMap<UUID, Long> LAST_LEAF_STUCK_MS = new ConcurrentHashMap<>();

    // Separate cooldowns for berry bush reactions so they don't suppress foliage stuck UX.
    private static final long BERRY_STING_COOLDOWN_MS = 6_000L;
    private static final long BERRY_EDIBLE_COOLDOWN_MS = 18_000L;
    private static final ConcurrentHashMap<UUID, Long> LAST_BERRY_STING_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_BERRY_EDIBLE_MS = new ConcurrentHashMap<>();

    private static final String[] LEAF_STUCK_LINES = new String[] {
            "These branches are thick!",
            "Hold on — stuck in some branches.",
            "Can't get through these leaves.",
            "Just a sec… foliage's got me.",
            "Ugh. Leaves in the way."
    };

    private static final String[] BERRY_STING_LINES = new String[] {
            "Ouch!",
            "These are thorny!",
            "Yowch!"
    };

    private static final String[] BERRY_EDIBLE_LINES = new String[] {
            "These are edible...I think."
    };

    private static final java.util.Random RNG = new java.util.Random();

    private CompanionOverheadDialogueService() {
    }

    /**
     * Best-effort: show a short foliage-stuck line above the bot to nearby players.
     * Rate-limited per bot.
     */
    public static void tryShowLeafStuck(ServerPlayerEntity bot, String reason) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = LAST_LEAF_STUCK_MS.getOrDefault(id, 0L);
        if (now - last < COOLDOWN_MS) {
            return;
        }
        LAST_LEAF_STUCK_MS.put(id, now);

        String line = LEAF_STUCK_LINES[RNG.nextInt(LEAF_STUCK_LINES.length)];
        int durationMs = DURATION_MS;

        double r2 = RANGE * RANGE;
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer == null || viewer.isRemoved()) {
                continue;
            }
            if (viewer.squaredDistanceTo(bot) > r2) {
                continue;
            }
            try {
                ServerPlayNetworking.send(viewer, new CompanionOverheadLinePayload(id, line, durationMs));
            } catch (Throwable t) {
                // Best-effort only.
            }
        }

        // Server-side fallback: a short-lived hologram above the bot. This is more reliable for
        // fakeplayer bots than client-only nameplate overrides.
        CompanionOverheadHologramService.show(bot, line, durationMs);

        if (reason != null && !reason.isBlank()) {
            LOGGER.debug("Overhead line (leaf-stuck) bot={} reason={} line={}", bot.getName().getString(), reason, line);
        } else {
            LOGGER.debug("Overhead line (leaf-stuck) bot={} line={}", bot.getName().getString(), line);
        }
    }

    /**
     * Quick reaction when a bot walks through a Sweet Berry Bush.
     */
    public static void tryShowSweetBerryBushSting(ServerPlayerEntity bot, String reason) {
        tryShowGeneric(bot, LAST_BERRY_STING_MS, BERRY_STING_COOLDOWN_MS, BERRY_STING_LINES, "berry-sting", reason);
    }

    /**
     * Rare, ambient observation when adjacent to Sweet Berry Bushes.
     */
    public static void tryShowSweetBerryBushEdible(ServerPlayerEntity bot, String reason) {
        tryShowGeneric(bot, LAST_BERRY_EDIBLE_MS, BERRY_EDIBLE_COOLDOWN_MS, BERRY_EDIBLE_LINES, "berry-edible", reason);
    }

    /**
     * Show an arbitrary overhead line (no built-in cooldown). Intended for callers that already
     * have their own throttling and want to avoid chat spam.
     */
    public static void showOverheadLine(ServerPlayerEntity bot, String line, int durationMs, double range, String tag, String reason) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (line == null || line.isBlank()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }

        int dur = durationMs > 0 ? durationMs : DURATION_MS;
        double r = range > 0.0 ? range : RANGE;
        double r2 = r * r;

        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer == null || viewer.isRemoved()) {
                continue;
            }
            if (viewer.squaredDistanceTo(bot) > r2) {
                continue;
            }
            try {
                ServerPlayNetworking.send(viewer, new CompanionOverheadLinePayload(id, line, dur));
            } catch (Throwable ignored) {
                // Best-effort only.
            }
        }

        // Server-side fallback.
        CompanionOverheadHologramService.show(bot, line, dur);

        if (tag != null && !tag.isBlank()) {
            if (reason != null && !reason.isBlank()) {
                LOGGER.debug("Overhead line ({}) bot={} reason={} line={}", tag, bot.getName().getString(), reason, line);
            } else {
                LOGGER.debug("Overhead line ({}) bot={} line={}", tag, bot.getName().getString(), line);
            }
        }
    }

    private static void tryShowGeneric(
            ServerPlayerEntity bot,
            ConcurrentHashMap<UUID, Long> lastMap,
            long cooldownMs,
            String[] lines,
            String tag,
            String reason
    ) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return;
        }
        if (lines == null || lines.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastMap.getOrDefault(id, 0L);
        if (now - last < Math.max(0L, cooldownMs)) {
            return;
        }
        lastMap.put(id, now);

        String line = lines[RNG.nextInt(lines.length)];
        int durationMs = DURATION_MS;

        double r2 = RANGE * RANGE;
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer == null || viewer.isRemoved()) {
                continue;
            }
            if (viewer.squaredDistanceTo(bot) > r2) {
                continue;
            }
            try {
                ServerPlayNetworking.send(viewer, new CompanionOverheadLinePayload(id, line, durationMs));
            } catch (Throwable t) {
                // Best-effort only.
            }
        }

        // Server-side fallback.
        CompanionOverheadHologramService.show(bot, line, durationMs);

        if (reason != null && !reason.isBlank()) {
            LOGGER.debug("Overhead line ({}) bot={} reason={} line={}", tag, bot.getName().getString(), reason, line);
        } else {
            LOGGER.debug("Overhead line ({}) bot={} line={}", tag, bot.getName().getString(), line);
        }
    }
}
