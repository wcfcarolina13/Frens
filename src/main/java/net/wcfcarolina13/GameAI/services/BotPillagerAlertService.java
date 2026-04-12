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
        if (bot == null || server == null || hostileList == null || hostileList.isEmpty()) {
            return isPatrolDetected(bot, server);
        }
        long now = server.getTicks();
        UUID botId = bot.getUuid();

        // Scan cadence: only count illagers every SCAN_CADENCE_TICKS.
        Long lastScan = LAST_SCAN_TICK.get(botId);
        if (lastScan != null && now - lastScan < SCAN_CADENCE_TICKS) {
            return isPatrolDetected(bot, server);
        }
        LAST_SCAN_TICK.put(botId, now);

        // Count visible illagers in the existing hostile list.
        int visibleIllagers = 0;
        for (Entity e : hostileList) {
            if (!isIllagerOrRavager(e)) continue;
            if (!(e instanceof LivingEntity living)) continue;
            if (!bot.canSee(living)) continue;
            visibleIllagers++;
        }

        boolean wasDetected = isPatrolDetected(bot, server);

        if (visibleIllagers >= ILLAGER_COUNT_THRESHOLD) {
            // Refresh or set the patrol-detected flag.
            PATROL_DETECTED.put(botId, now + PATROL_FLAG_DECAY_TICKS);
            if (!wasDetected) {
                // First detection — fire one-shot alert if cooldown allows.
                long lastAlert = LAST_ALERT_MS.getOrDefault(botId, 0L);
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastAlert >= ALERT_COOLDOWN_MS) {
                    LAST_ALERT_MS.put(botId, nowMs);
                    fireAlert(bot, server);
                }
            }
            return !anyIllagerAggroed(bot, hostileList, server);
        }

        return isPatrolDetected(bot, server) && !anyIllagerAggroed(bot, hostileList, server);
    }

    /** True if the patrol-detected flag is set and not expired. */
    private static boolean isPatrolDetected(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return false;
        Long expiry = PATROL_DETECTED.get(bot.getUuid());
        if (expiry == null) return false;
        if (server.getTicks() >= expiry) {
            PATROL_DETECTED.remove(bot.getUuid());
            return false;
        }
        return true;
    }

    /**
     * Called from {@code engageHostiles} before each {@code moveToward}
     * that chases the target. Returns true if the target is an illager AND
     * the bot is in patrol-defensive mode (pursuit should be suppressed).
     */
    public static boolean shouldSuppressPursuit(
            ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null) return false;
        if (!isIllagerOrRavager(target)) return false;
        Long expiry = PATROL_DETECTED.get(bot.getUuid());
        if (expiry == null) return false;
        // Check distance: only suppress if bot is within leash range.
        // Beyond the leash, the bot would already need to chase.
        double distSq = bot.squaredDistanceTo(target);
        return distSq > PURSUIT_SUPPRESS_LEASH_SQ;
    }

    /** Cleanup for SERVER_STOPPING. */
    public static void reset() {
        PATROL_DETECTED.clear();
        LAST_ALERT_MS.clear();
        LAST_SCAN_TICK.clear();
        LOGGER.info("BotPillagerAlertService reset (server stopping)");
    }

    // ── Alert channel dispatch (stubs, chunk 2) ─────────────────────────

    /**
     * Dispatches the best available alert channel (first match wins):
     * goat horn > signal fire > direct message > fallback.
     */
    private static void fireAlert(
            ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return;
        UUID commanderUuid = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        ServerPlayerEntity commander = commanderUuid != null
                ? server.getPlayerManager().getPlayer(commanderUuid) : null;

        if (tryGoatHorn(bot, commander)) {
            LOGGER.info("pillager-alert: bot={} channel=goat-horn", bot.getName().getString());
            return;
        }
        if (bot.getEntityWorld() instanceof ServerWorld world && trySignalFire(bot, world)) {
            LOGGER.info("pillager-alert: bot={} channel=signal-fire", bot.getName().getString());
            return;
        }
        if (commander != null && tryDirectMessage(bot, commander)) {
            LOGGER.info("pillager-alert: bot={} channel=direct-message", bot.getName().getString());
            return;
        }
        fireFallback(bot);
        LOGGER.info("pillager-alert: bot={} channel=fallback", bot.getName().getString());
    }

    /**
     * Priority 1: Goat horn. Bot equips, uses (plays vanilla instrument
     * sound ~256 blocks), re-equips original item. Overhead message range
     * depends on above/below ground.
     */
    private static boolean tryGoatHorn(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null) return false;
        // Find a goat horn in the bot's inventory.
        int hornSlot = -1;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.GOAT_HORN)) {
                hornSlot = i;
                break;
            }
        }
        if (hornSlot < 0) return false;

        // Determine overhead range based on above/below ground.
        boolean botAbove = bot.getEntityWorld().isSkyVisible(bot.getBlockPos().up());
        boolean commanderAbove = commander != null
                && commander.getEntityWorld().isSkyVisible(commander.getBlockPos().up());
        double overheadRange = (botAbove && commanderAbove)
                ? HORN_OVERHEAD_RANGE_ABOVE
                : HORN_OVERHEAD_RANGE_BELOW;

        // Equip the horn, use it, re-equip original. If the horn is in the
        // main inventory (slot 9+), swap it to hotbar slot 0 temporarily and
        // reverse the swap after use so the bot's inventory layout is preserved.
        int originalSlot = bot.getInventory().getSelectedSlot();
        boolean swappedFromMain = false;
        if (hornSlot < 9) {
            // Horn is in hotbar — select it directly.
            bot.getInventory().setSelectedSlot(hornSlot);
        } else {
            // Horn is in main inventory — swap to hotbar slot 0 temporarily.
            ItemStack hornStack = bot.getInventory().getStack(hornSlot);
            ItemStack displaced = bot.getInventory().getStack(0);
            bot.getInventory().setStack(0, hornStack);
            bot.getInventory().setStack(hornSlot, displaced);
            bot.getInventory().setSelectedSlot(0);
            swappedFromMain = true;
        }
        // Use the horn (plays the vanilla instrument sound event).
        ItemStack held = bot.getMainHandStack();
        if (held != null && !held.isEmpty()) {
            held.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
        }
        // Reverse the swap if we moved the horn from main inventory.
        if (swappedFromMain) {
            ItemStack hornInZero = bot.getInventory().getStack(0);
            ItemStack displacedInOrig = bot.getInventory().getStack(hornSlot);
            bot.getInventory().setStack(hornSlot, hornInZero);
            bot.getInventory().setStack(0, displacedInOrig);
        }
        // Re-equip original slot.
        bot.getInventory().setSelectedSlot(originalSlot);

        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Patrol spotted \u2014 sounding the horn!",
                3_500, overheadRange, "pillager-alert", "goat-horn");
        return true;
    }

    /**
     * Priority 2: Signal fire (lit campfire + hay bale below, within 24
     * blocks). Suppressed in FOLLOW mode. Bot walks to the campfire
     * non-blockingly via MovementService, then emits overhead line.
     */
    private static boolean trySignalFire(
            ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return false;
        // Mode gate: FOLLOW mode = skip (don't leave the commander).
        if (BotEventHandler.isFollowingPlayer(bot)) return false;

        // Search for the nearest signal fire within radius.
        BlockPos botPos = bot.getBlockPos();
        BlockPos bestFire = null;
        double bestDistSq = Double.MAX_VALUE;
        int r = SIGNAL_FIRE_SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.iterate(botPos.add(-r, -3, -r), botPos.add(r, 3, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof CampfireBlock)) continue;
            if (!state.get(CampfireBlock.LIT)) continue;
            // Hay bale directly below = signal fire.
            BlockPos below = pos.down();
            if (!world.getBlockState(below).isOf(Blocks.HAY_BLOCK)) continue;
            double dSq = botPos.getSquaredDistance(pos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestFire = pos.toImmutable();
            }
        }
        if (bestFire == null) return false;

        // Non-blocking walk to the campfire. MovementService.nudgeTowardUntilClose
        // is async and returns immediately — the bot will walk over the next few
        // ticks. The overhead line fires immediately since the alert is urgent;
        // the bot arriving at the campfire provides the visual (smoke column).
        MovementService.nudgeTowardUntilClose(
                bot, bestFire, 4.0D, 5_000L, 0.22D, "patrol-signal-fire");

        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Patrol nearby \u2014 signaling from the fire!",
                3_500, SIGNAL_FIRE_OVERHEAD_RANGE, "pillager-alert", "signal-fire");
        return true;
    }

    /**
     * Priority 3: Direct message via magic-comm gate. Uses the
     * canLongRangeComm helper on CompanionCommunicationPolicy.
     */
    private static boolean tryDirectMessage(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) return false;
        if (!CompanionCommunicationPolicy.canLongRangeComm(bot, commander)) return false;
        CompanionOverheadDialogueService.showOverheadLine(
                bot, "I see a patrol \u2014 stay alert.",
                3_000, DIRECT_MSG_RANGE, "pillager-alert", "direct-message");
        return true;
    }

    /**
     * Priority 4: Fallback — always available, short range (16 blocks).
     * The "you should have brought a comm item" scenario.
     */
    private static void fireFallback(ServerPlayerEntity bot) {
        if (bot == null) return;
        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Something's not right...",
                2_800, FALLBACK_RANGE, "pillager-alert", "fallback");
    }

    // ── Helpers (stubs, chunk 2) ────────────────────────────────────────

    /** True if the entity is an illager or ravager. */
    private static boolean isIllagerOrRavager(Entity entity) {
        return entity instanceof IllagerEntity || entity instanceof RavagerEntity;
    }

    /**
     * Returns true if any illager in the hostile list has aggroed on the bot,
     * the commander, or any Feature A defended animal. When this returns true,
     * pursuit suppression lifts and the bot fights normally.
     */
    private static boolean anyIllagerAggroed(
            ServerPlayerEntity bot, List<Entity> hostileList,
            MinecraftServer server) {
        if (bot == null || hostileList == null) return false;
        UUID commanderUuid = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        for (Entity e : hostileList) {
            if (!isIllagerOrRavager(e)) continue;
            if (!(e instanceof MobEntity mob)) continue;
            LivingEntity target = mob.getTarget();
            if (target == null) continue;
            // Aggroed on the bot itself.
            if (target == bot) return true;
            // Aggroed on the commander.
            if (commanderUuid != null && target instanceof ServerPlayerEntity sp
                    && commanderUuid.equals(sp.getUuid())) return true;
            // Aggroed on a defended animal (Feature A check).
            if (commanderUuid != null
                    && BotAnimalDefenseService.defenseBoost(bot, e) > 0.0D) return true;
        }
        return false;
    }
}
