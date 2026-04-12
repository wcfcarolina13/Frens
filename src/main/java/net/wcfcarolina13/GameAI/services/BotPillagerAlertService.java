package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects illager groups (2+ visible within 16 blocks, LOS-gated), goes
 * defensive (shield up, don't chase), and sends a one-shot alert via the
 * best available channel. Escalates to normal combat when illagers aggro.
 *
 * <p>Piggybacks on the existing {@code engageHostiles} combat path — no
 * separate tick service or world scan. See
 * docs/superpowers/specs/2026-04-11-pillager-patrol-alert-design.md.</p>
 */
public final class BotPillagerAlertService {

    private static final Logger LOGGER = LoggerFactory.getLogger("pillager-alert");

    // ── Tunable constants ───────────────────────────────────────────────
    // _TICKS = server game-ticks; _MS = System.currentTimeMillis()

    /** Minimum visible illagers for "patrol detected". */
    private static final int ILLAGER_COUNT_THRESHOLD = 2;

    /** Only count illagers every N ticks inside engageHostiles. */
    private static final int SCAN_CADENCE_TICKS = 20;

    /** How long the patrol-detected flag persists after last detection. */
    private static final long PATROL_FLAG_DECAY_TICKS = 200L;

    /** Per-bot cooldown between alerts. */
    private static final long ALERT_COOLDOWN_MS = 60_000L;

    /** Goat horn overhead message range — both above ground. */
    private static final double HORN_OVERHEAD_RANGE_ABOVE = 128.0D;

    /** Goat horn overhead message range — either underground. */
    private static final double HORN_OVERHEAD_RANGE_BELOW = 48.0D;

    /** Max distance bot will travel to a signal fire. */
    private static final int SIGNAL_FIRE_SEARCH_RADIUS = 24;

    /** Overhead message range from the campfire position. */
    private static final double SIGNAL_FIRE_OVERHEAD_RANGE = 64.0D;

    /** Direct message overhead range (magic-comm gated). */
    private static final double DIRECT_MSG_RANGE = 48.0D;

    /** Fallback overhead range (always available, must be near bot). */
    private static final double FALLBACK_RANGE = 16.0D;

    /** How close to an enchanting table counts for the comm gate. */
    private static final int ENCHANT_TABLE_PROXIMITY = 8;

    /** Bot won't chase illagers beyond this distance in defensive mode. */
    private static final double PURSUIT_SUPPRESS_LEASH_SQ = 6.0D * 6.0D;

    // ── State ───────────────────────────────────────────────────────────

    /** botUuid -> expireGameTick. The patrol-detected flag. */
    private static final Map<UUID, Long> PATROL_DETECTED = new ConcurrentHashMap<>();

    /** botUuid -> lastAlertEpochMillis. Alert cooldown tracker. */
    private static final Map<UUID, Long> LAST_ALERT_MS = new ConcurrentHashMap<>();

    /** botUuid -> lastScanGameTick. Scan cadence tracker. */
    private static final Map<UUID, Long> LAST_SCAN_TICK = new ConcurrentHashMap<>();

    private BotPillagerAlertService() {}

    // ── Public API (called from BotEventHandler.engageHostiles) ─────────

    /**
     * Called from {@code engageHostiles} after the hostile list is built.
     * Counts visible illagers, manages the patrol-detected flag, fires
     * one-shot alerts on first detection. Returns true if pursuit of
     * illager targets should be suppressed (defensive posture active AND
     * no illager has aggroed on the bot, commander, or defended animals).
     */
    public static boolean checkForPatrolAndSuppressPursuit(
            ServerPlayerEntity bot, MinecraftServer server,
            List<Entity> hostileList) {
        // Stub: filled in chunk 2.
        return false;
    }

    /**
     * Called from {@code engageHostiles} before each {@code moveToward}
     * that chases the target. Returns true if the target is an illager AND
     * the bot is in patrol-defensive mode (pursuit should be suppressed).
     */
    public static boolean shouldSuppressPursuit(
            ServerPlayerEntity bot, Entity target) {
        // Stub: filled in chunk 2.
        return false;
    }

    /** Cleanup for SERVER_STOPPING. */
    public static void reset() {
        PATROL_DETECTED.clear();
        LAST_ALERT_MS.clear();
        LAST_SCAN_TICK.clear();
        LOGGER.info("BotPillagerAlertService reset (server stopping)");
    }

    // ── Alert channel dispatch (stubs, chunk 2) ─────────────────────────

    @SuppressWarnings("unused")
    private static void fireAlert(
            ServerPlayerEntity bot, MinecraftServer server) {
        // Stub: filled in chunk 2.
    }

    @SuppressWarnings("unused")
    private static boolean tryGoatHorn(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean trySignalFire(
            ServerPlayerEntity bot, ServerWorld world) {
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean tryDirectMessage(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }

    @SuppressWarnings("unused")
    private static void fireFallback(ServerPlayerEntity bot) {
        // Stub: filled in chunk 2.
    }

    // ── Helpers (stubs, chunk 2) ────────────────────────────────────────

    /** True if the entity is an illager or ravager. */
    @SuppressWarnings("unused")
    private static boolean isIllagerOrRavager(Entity entity) {
        return entity instanceof IllagerEntity || entity instanceof RavagerEntity;
    }

    /** True if any illager in the list has aggroed on bot/commander/defended animal. */
    @SuppressWarnings("unused")
    private static boolean anyIllagerAggroed(
            ServerPlayerEntity bot, List<Entity> illagers,
            MinecraftServer server) {
        return false;
    }
}
