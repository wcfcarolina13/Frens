package net.wcfcarolina13.GameAI.services;

import com.mojang.datafixers.util.Pair;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.registry.entry.RegistryEntry;
import java.util.*;

/**
 * Mirrors the commander's elytra flight. When the commander starts gliding,
 * each following bot equips an elytra, launches, steers toward the commander,
 * and boosts with firework rockets. On landing the original chestplate is restored.
 *
 * <p>State machine per bot: NONE → EQUIPPING → LAUNCHING → GLIDING → LANDING → CLEANUP → NONE</p>
 */
public final class ElytraFlightService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElytraFlightService.class);

    // ── Phase enum ──────────────────────────────────────────────────────────
    private enum FlightPhase {
        NONE,
        EQUIPPING,
        LAUNCHING,
        GLIDING,
        LANDING,
        CLEANUP
    }

    // ── Per-bot state maps ──────────────────────────────────────────────────
    private static final Map<UUID, FlightPhase> PHASE = new HashMap<>();
    private static final Map<UUID, Long> PHASE_START_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_BOOST_TICK = new HashMap<>();
    private static final Map<UUID, ItemStack> SAVED_CHESTPLATE = new HashMap<>();
    private static final Map<UUID, UUID> FLIGHT_COMMANDER = new HashMap<>();
    /** When set, the bot is performing an autonomous descent (not mirroring commander). */
    private static final Map<UUID, Boolean> AUTONOMOUS_DESCENT = new HashMap<>();
    /** Preferred launch perch during autonomous descent launch. */
    private static final Map<UUID, BlockPos> AUTONOMOUS_LAUNCH_PERCH = new HashMap<>();
    /** Preferred launch direction from perch during autonomous descent launch. */
    private static final Map<UUID, Direction> AUTONOMOUS_LAUNCH_DIR = new HashMap<>();
    /** Cooldown for autonomous follow-flight attempts while commander is not gliding. */
    private static final Map<UUID, Long> LAST_AUTONOMOUS_FOLLOW_ATTEMPT_TICK = new HashMap<>();

    // ── Timing constants ────────────────────────────────────────────────────
    private static final long EQUIP_TIMEOUT_TICKS = 40L;
    private static final long LAUNCH_TIMEOUT_TICKS = 60L;
    private static final long LANDING_TIMEOUT_TICKS = 200L;
    private static final long BOOST_INTERVAL_TICKS = 60L;     // 3 seconds
    private static final double BOOST_DISTANCE_SQ = 15.0 * 15.0; // 225 blocks²
    private static final long AUTONOMOUS_FOLLOW_ATTEMPT_COOLDOWN_TICKS = 40L; // 2s
    private static final long AUTONOMOUS_GLIDE_LAUNCH_GRACE_TICKS = 10L;
    private static final int AUTONOMOUS_LAUNCH_PERCH_RADIUS = 3;
    private static final int AUTONOMOUS_LAUNCH_CORRIDOR_DEPTH = 4;
    private static final int AUTONOMOUS_LAUNCH_MIN_CORRIDOR = 1;
    private static final int AUTONOMOUS_LAUNCH_PERCH_MAX_RISE = 2;
    private static final double AUTONOMOUS_FOLLOW_FAR_HORIZ_DIST_SQ = 14.0D * 14.0D;
    private static final double AUTONOMOUS_FOLLOW_VERY_FAR_HORIZ_DIST_SQ = 24.0D * 24.0D;
    private static final double AUTONOMOUS_FOLLOW_MIN_HEIGHT_ADVANTAGE = 4.0D;
    private static final double AUTONOMOUS_FOLLOW_HIGH_DROP_ADVANTAGE = 8.0D;
    private static final double AUTONOMOUS_FOLLOW_MIN_INTERCEPT_HORIZ_DIST_SQ = 2.0D * 2.0D;
    private static final double AUTONOMOUS_FOLLOW_MAX_HEIGHT_DEFICIT = -2.0D;

    // ── Flight parameters ───────────────────────────────────────────────────
    private static final float PITCH_MIN = -50.0F;
    private static final float PITCH_MAX = 40.0F;
    private static final float PITCH_DESCENT_SHALLOW = -30.0F;
    private static final float PITCH_LANDING = 10.0F;
    private static final double ALTITUDE_GAIN_THRESHOLD = -5.0;  // bot below commander by 5
    private static final double ALTITUDE_LOSE_THRESHOLD = 15.0;  // bot above commander by 15
    private static final double STALL_SPEED_SQ = 0.5 * 0.5;     // 0.25
    private static final double ANTI_STALL_MAGNITUDE = 0.3;
    private static final float EMERGENCY_HEALTH = 8.0F;          // 4 hearts

    private ElytraFlightService() {}

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given bot is currently in any active flight phase.
     */
    public static boolean isInFlight(UUID botUuid) {
        FlightPhase phase = PHASE.get(botUuid);
        return phase != null && phase != FlightPhase.NONE;
    }

    /**
     * Start an autonomous descent — the bot equips its elytra and glides down to safety.
     * Unlike commander-mirroring, this is self-initiated (e.g. when stuck on a tree or
     * at a cliff edge with no bypass).
     *
     * <p>Conditions: not already in flight, has elytra, on ground, and elevated enough to
     * justify a glide (either broad ledge exposure or a deep local drop).</p>
     *
     * @return {@code true} if the descent was initiated.
     */
    public static boolean tryAutonomousDescent(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return false;
        UUID botId = bot.getUuid();
        if (isInFlight(botId)) return false;
        if (!bot.isOnGround()) return false;
        if (bot.isClimbing()) return false;
        if (bot.hasVehicle() || bot.isTouchingWater()) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;

        // Prefer ladder/vine descent over elytra when a climbable reaches near the target Y.
        if (hasUsableDescentClimbable(bot, world)) return false;

        // Must have an elytra (equipped or in inventory).
        boolean hasElytra = hasElytraAvailable(bot);
        if (!hasElytra) return false;

        // Elevation check:
        // 1) broad exposure: at least 1 of 4 cardinal directions has deep air,
        // 2) local drop: deep air directly below current feet (tower edge / parapet case).
        if (!isElevatedForAutonomousFlight(bot)) return false;
        BlockPos launchPerch = findBestAutonomousLaunchPerch(world, bot.getBlockPos());
        if (launchPerch == null) return false;
        Direction launchDir = bestLaunchDirection(world, launchPerch);
        if (launchDir == null) return false;

        // All conditions met — begin autonomous descent.
        long now = server.getTicks();
        AUTONOMOUS_DESCENT.put(botId, Boolean.TRUE);
        AUTONOMOUS_LAUNCH_PERCH.put(botId, launchPerch.toImmutable());
        AUTONOMOUS_LAUNCH_DIR.put(botId, launchDir);
        // Store follow target as the "flight commander" for steering purposes.
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget != null) {
            FLIGHT_COMMANDER.put(botId, followTarget);
        }
        setPhase(botId, FlightPhase.EQUIPPING, now);
        LOGGER.info("ElytraFlight: {} entering AUTONOMOUS DESCENT", bot.getName().getString());
        return true;
    }

    /**
     * Returns {@code true} if there is a climbable column (ladder/vine) within 3 blocks
     * that extends downward toward the follow target's Y level, making ladder descent
     * preferable to elytra flight.
     */
    private static boolean hasUsableDescentClimbable(ServerPlayerEntity bot, ServerWorld world) {
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget == null) return false;
        ServerPlayerEntity target = world.getServer().getPlayerManager().getPlayer(followTarget);
        if (target == null) return false;
        double targetY = target.getY();
        if (bot.getY() - targetY < 2.0D) return false; // not a meaningful descent

        BlockPos botPos = bot.getBlockPos();
        int scanRadius = 3;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                BlockPos top = botPos.add(dx, 0, dz);
                if (!world.getBlockState(top).isIn(BlockTags.CLIMBABLE)) continue;
                // Check how far this climbable column extends downward
                int lowestClimbY = top.getY();
                for (int dy = -1; dy >= -(int)(bot.getY() - targetY + 3); dy--) {
                    if (world.getBlockState(top.add(0, dy, 0)).isIn(BlockTags.CLIMBABLE)) {
                        lowestClimbY = top.getY() + dy;
                    } else {
                        break;
                    }
                }
                // If the climbable extends to within 3 blocks of the target Y, prefer it
                if (lowestClimbY <= targetY + 3.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Immediate follow-mode hook so BotEventHandler can start autonomous follow-flight before
     * normal follow movement walks the bot off a tower edge.
     */
    public static boolean tryAutonomousFollowFlightNow(MinecraftServer server, ServerPlayerEntity bot, long now) {
        if (server == null || bot == null) {
            return false;
        }
        if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.FOLLOW) {
            return false;
        }
        ServerPlayerEntity commander = resolveCommander(server, bot);
        if (commander == null || commander.isGliding()) {
            return false;
        }
        if (!shouldAttemptAutonomousFollowFlight(bot, commander, now)) {
            return false;
        }
        UUID botId = bot.getUuid();
        LAST_AUTONOMOUS_FOLLOW_ATTEMPT_TICK.put(botId, now);
        if (tryAutonomousDescent(bot, server)) {
            LOGGER.info("ElytraFlight: {} autonomous follow-flight trigger toward commander {}",
                    bot.getName().getString(), commander.getName().getString());
            return true;
        }
        LOGGER.info("ElytraFlight: {} autonomous follow-flight skipped ({})",
                bot.getName().getString(), autonomousFollowSkipReason(bot));
        tryRestoreChestArmorOutOfFlight(bot);
        return false;
    }

    /**
     * Tick entry point — registered on {@code ServerTickEvents.END_SERVER_TICK}.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        List<ServerPlayerEntity> bots = BotEventHandler.getRegisteredBots(server);
        if (bots.isEmpty()) {
            return;
        }

        long now = server.getTicks();

        for (ServerPlayerEntity bot : bots) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                clearState(bot != null ? bot.getUuid() : null);
                continue;
            }
            tickBot(server, bot, now);
        }
    }

    // ── Core tick logic ─────────────────────────────────────────────────────

    private static void tickBot(MinecraftServer server, ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();
        FlightPhase phase = PHASE.getOrDefault(botId, FlightPhase.NONE);

        switch (phase) {
            case NONE      -> tickNone(server, bot, now);
            case EQUIPPING -> tickEquipping(bot, now);
            case LAUNCHING -> tickLaunching(bot, now);
            case GLIDING   -> tickGliding(server, bot, now);
            case LANDING   -> tickLanding(bot, now);
            case CLEANUP   -> tickCleanup(bot);
        }
    }

    // ── NONE phase ──────────────────────────────────────────────────────────

    private static void tickNone(MinecraftServer server, ServerPlayerEntity bot, long now) {
        // Only trigger for bots in FOLLOW mode
        if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.FOLLOW) {
            return;
        }

        ServerPlayerEntity commander = resolveCommander(server, bot);
        if (commander == null) {
            return;
        }

        UUID botId = bot.getUuid();
        if (commander.isGliding()) {
            // Commander is gliding — begin mirror flight sequence.
            FLIGHT_COMMANDER.put(botId, commander.getUuid());
            setPhase(botId, FlightPhase.EQUIPPING, now);
            LOGGER.info("ElytraFlight: {} entering EQUIPPING (commander {} is gliding)",
                    bot.getName().getString(), commander.getName().getString());
            return;
        }

        // Commander is not gliding: allow autonomous catch-up flights from high vantage points.
        if (shouldAttemptAutonomousFollowFlight(bot, commander, now)) {
            LAST_AUTONOMOUS_FOLLOW_ATTEMPT_TICK.put(botId, now);
            if (tryAutonomousDescent(bot, server)) {
                LOGGER.info("ElytraFlight: {} autonomous follow-flight trigger toward commander {}",
                        bot.getName().getString(), commander.getName().getString());
            } else {
                String reason = autonomousFollowSkipReason(bot);
                LOGGER.info("ElytraFlight: {} autonomous follow-flight skipped ({})",
                        bot.getName().getString(), reason);
                tryRestoreChestArmorOutOfFlight(bot);
            }
        } else {
            tryRestoreChestArmorOutOfFlight(bot);
        }
    }

    // ── EQUIPPING phase ─────────────────────────────────────────────────────

    private static void tickEquipping(ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();

        // Timeout check
        if (now - PHASE_START_TICK.getOrDefault(botId, now) > EQUIP_TIMEOUT_TICKS) {
            LOGGER.warn("ElytraFlight: {} EQUIPPING timed out", bot.getName().getString());
            setPhase(botId, FlightPhase.NONE, now);
            clearState(botId);
            return;
        }

        // Already wearing elytra?
        ItemStack chestSlot = bot.getEquippedStack(EquipmentSlot.CHEST);
        if (chestSlot.isOf(Items.ELYTRA)) {
            ItemStack saved = SAVED_CHESTPLATE.get(botId);
            if (saved == null || saved.isEmpty()) {
                int backupSlot = findBestChestArmorSlot(bot);
                if (backupSlot >= 0) {
                    SAVED_CHESTPLATE.put(botId, bot.getInventory().getStack(backupSlot).copy());
                } else {
                    SAVED_CHESTPLATE.putIfAbsent(botId, ItemStack.EMPTY);
                }
            }
            setPhase(botId, FlightPhase.LAUNCHING, now);
            return;
        }

        // Search inventory for elytra
        int elytraSlot = findItemSlot(bot, Items.ELYTRA);
        if (elytraSlot < 0) {
            LOGGER.info("ElytraFlight: {} has no elytra, aborting", bot.getName().getString());
            setPhase(botId, FlightPhase.NONE, now);
            clearState(botId);
            return;
        }

        // Filter: check if elytra is preserved below threshold
        ItemStack elytraCandidate = bot.getInventory().getStack(elytraSlot);
        if (DurabilityPolicyService.shouldAvoid(bot, elytraCandidate)) {
            LOGGER.info("ElytraFlight: {} refusing to equip preserved elytra below threshold", bot.getName().getString());
            CompanionOverheadDialogueService.tryShowGearNoReplacement(bot);
            DurabilityFallbackService.requestRefresh(bot, DurabilityFallbackService.GearCategory.ELYTRA);
            setPhase(botId, FlightPhase.NONE, now);
            clearState(botId);
            return;
        }

        // Save current chestplate, equip elytra
        SAVED_CHESTPLATE.put(botId, chestSlot.copy());

        ItemStack elytraStack = bot.getInventory().getStack(elytraSlot);
        bot.getInventory().setStack(elytraSlot, chestSlot); // put old chestplate in inventory
        bot.equipStack(EquipmentSlot.CHEST, elytraStack);
        bot.getInventory().markDirty();

        broadcastEquipment(bot, EquipmentSlot.CHEST, elytraStack);
        LOGGER.info("ElytraFlight: {} equipped elytra", bot.getName().getString());

        setPhase(botId, FlightPhase.LAUNCHING, now);
    }

    // ── LAUNCHING phase ─────────────────────────────────────────────────────

    private static void tickLaunching(ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();
        long elapsed = now - PHASE_START_TICK.getOrDefault(botId, now);
        boolean autonomous = AUTONOMOUS_DESCENT.getOrDefault(botId, false);

        // Timeout
        if (elapsed > LAUNCH_TIMEOUT_TICKS) {
            LOGGER.warn("ElytraFlight: {} LAUNCHING timed out", bot.getName().getString());
            setPhase(botId, FlightPhase.CLEANUP, now);
            return;
        }

        // If already gliding (e.g. launched off cliff), advance immediately
        if (bot.isGliding()) {
            // Treat gliding-on-ground as a failed launch posture for autonomous descent.
            if (!(autonomous && bot.isOnGround())) {
                setPhase(botId, FlightPhase.GLIDING, now);
                LAST_BOOST_TICK.put(botId, now);
                LOGGER.info("ElytraFlight: {} is now GLIDING", bot.getName().getString());
                return;
            }
            bot.stopGliding();
        }

        // Try jumping to get off ground
        if (bot.isOnGround()) {
            if (autonomous && bot.getEntityWorld() instanceof ServerWorld world) {
                BlockPos perch = AUTONOMOUS_LAUNCH_PERCH.get(botId);
                if (perch == null || !isLaunchStandable(world, perch)) {
                    perch = findBestAutonomousLaunchPerch(world, bot.getBlockPos());
                    if (perch != null) {
                        AUTONOMOUS_LAUNCH_PERCH.put(botId, perch.toImmutable());
                    }
                }
                if (perch == null) {
                    LOGGER.info("ElytraFlight: {} autonomous launch failed (no safe perch)", bot.getName().getString());
                    setPhase(botId, FlightPhase.CLEANUP, now);
                    return;
                }
                if (perch != null) {
                    Vec3d perchCenter = Vec3d.ofCenter(perch);
                    if (bot.squaredDistanceTo(perchCenter) > 0.45D) {
                        BotActions.sprint(bot, false);
                        BotActions.autoJumpIfNeeded(bot);
                        BotActions.applyMovementInput(bot, perchCenter, 0.16D);
                        return;
                    }
                    Direction launchDir = AUTONOMOUS_LAUNCH_DIR.get(botId);
                    if (launchDir == null || launchCorridorDepth(world, perch, launchDir, AUTONOMOUS_LAUNCH_CORRIDOR_DEPTH) < AUTONOMOUS_LAUNCH_MIN_CORRIDOR) {
                        launchDir = bestLaunchDirection(world, perch);
                        if (launchDir != null) {
                            AUTONOMOUS_LAUNCH_DIR.put(botId, launchDir);
                        }
                    }
                    if (launchDir != null) {
                        int corridorDepth = launchCorridorDepth(world, perch, launchDir, AUTONOMOUS_LAUNCH_CORRIDOR_DEPTH);
                        if (corridorDepth < AUTONOMOUS_LAUNCH_MIN_CORRIDOR) {
                            return;
                        }
                        int runDepth = Math.max(1, Math.min(2, corridorDepth));
                        Vec3d runTarget = Vec3d.ofCenter(perch.offset(launchDir, runDepth));
                        bot.setYaw(yawForDirection(launchDir));
                        BotActions.applyMovementInput(bot, runTarget, 0.20D);
                        return; // Don't jump — walk off edge to start gliding
                    } else {
                        return;
                    }
                }
            }
            // After 30 ticks, look up to help launch
            if (elapsed > 30) {
                bot.setPitch(-40.0F);
            }
            bot.jump();
            return;
        }

        // In the air — attempt to start gliding
        if (!bot.isTouchingWater() && !bot.hasVehicle()) {
            bot.startGliding();
            if (bot.isGliding()) {
                setPhase(botId, FlightPhase.GLIDING, now);
                LAST_BOOST_TICK.put(botId, now);
                LOGGER.info("ElytraFlight: {} is now GLIDING", bot.getName().getString());
            }
        }
    }

    // ── GLIDING phase ───────────────────────────────────────────────────────

    private static void tickGliding(MinecraftServer server, ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();
        boolean autonomous = AUTONOMOUS_DESCENT.getOrDefault(botId, false);

        if (autonomous) {
            tickGlidingAutonomous(server, bot, now);
        } else {
            tickGlidingCommander(server, bot, now);
        }
    }

    /** Gliding tick when mirroring the commander's elytra flight. */
    private static void tickGlidingCommander(MinecraftServer server, ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();

        // Resolve commander
        ServerPlayerEntity commander = resolveFlightCommander(server, botId);
        if (commander == null) {
            LOGGER.info("ElytraFlight: {} commander lost, landing", bot.getName().getString());
            setPhase(botId, FlightPhase.LANDING, now);
            return;
        }

        // Exit condition: commander stopped gliding or landed
        if (!commander.isGliding() || commander.isOnGround()) {
            LOGGER.info("ElytraFlight: {} commander landed/stopped, entering LANDING",
                    bot.getName().getString());
            setPhase(botId, FlightPhase.LANDING, now);
            return;
        }

        // Emergency exit: low health or in water
        if (bot.getHealth() < EMERGENCY_HEALTH || bot.isTouchingWater()) {
            LOGGER.info("ElytraFlight: {} emergency landing (health={} water={})",
                    bot.getName().getString(), bot.getHealth(), bot.isTouchingWater());
            setPhase(botId, FlightPhase.LANDING, now);
            return;
        }

        // Re-establish gliding if vanilla un-set it
        if (!bot.isGliding()) {
            if (canBotGlide(bot)) {
                bot.startGliding();
            }
            if (!bot.isGliding()) {
                // Can't re-enter glide — land
                setPhase(botId, FlightPhase.LANDING, now);
                return;
            }
        }

        // ── Steering toward commander ──
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d cmdPos = new Vec3d(commander.getX(), commander.getY(), commander.getZ());
        applyGlideSteering(bot, botPos, cmdPos);

        // ── Anti-stall velocity nudge ──
        applyAntiStall(bot);

        // ── Firework boosting ──
        long lastBoost = LAST_BOOST_TICK.getOrDefault(botId, 0L);
        double distSqToCommander = bot.squaredDistanceTo(commander);
        if (now - lastBoost >= BOOST_INTERVAL_TICKS && distSqToCommander > BOOST_DISTANCE_SQ) {
            tryFireworkBoost(bot, now);
        }
    }

    /** Gliding tick for autonomous descent (bot-initiated, not mirroring commander). */
    private static void tickGlidingAutonomous(MinecraftServer server, ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();
        long glidingElapsed = now - PHASE_START_TICK.getOrDefault(botId, now);

        // Exit condition: bot has landed
        if (bot.isOnGround()) {
            // If we "land" immediately after entering gliding, this was likely a false launch.
            // Re-enter launch phase instead of dropping back to normal follow/ladder loops.
            if (glidingElapsed <= AUTONOMOUS_GLIDE_LAUNCH_GRACE_TICKS) {
                setPhase(botId, FlightPhase.LAUNCHING, now);
                return;
            }
            LOGGER.info("ElytraFlight: {} autonomous descent landed", bot.getName().getString());
            setPhase(botId, FlightPhase.CLEANUP, now);
            return;
        }

        // Emergency exit: low health or in water
        if (bot.getHealth() < EMERGENCY_HEALTH || bot.isTouchingWater()) {
            LOGGER.info("ElytraFlight: {} autonomous descent emergency landing (health={} water={})",
                    bot.getName().getString(), bot.getHealth(), bot.isTouchingWater());
            setPhase(botId, FlightPhase.LANDING, now);
            return;
        }

        // Re-establish gliding if vanilla un-set it
        if (!bot.isGliding()) {
            if (canBotGlide(bot)) {
                bot.startGliding();
            }
            if (!bot.isGliding()) {
                setPhase(botId, FlightPhase.CLEANUP, now);
                return;
            }
        }

        // ── Steering: toward follow target or gentle descent ──
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        ServerPlayerEntity target = resolveFlightCommander(server, botId);
        if (target != null) {
            Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
            applyGlideSteering(bot, botPos, targetPos);
        } else {
            // No target — just descend gently forward.
            bot.setPitch(PITCH_LANDING);
        }

        // ── Anti-stall velocity nudge ──
        applyAntiStall(bot);

        // No firework boosting during autonomous descent — gravity only.
    }

    // ── LANDING phase ───────────────────────────────────────────────────────

    private static void tickLanding(ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();
        long elapsed = now - PHASE_START_TICK.getOrDefault(botId, now);

        // Timeout — force stop
        if (elapsed > LANDING_TIMEOUT_TICKS) {
            LOGGER.warn("ElytraFlight: {} LANDING timed out, forcing stop", bot.getName().getString());
            bot.stopGliding();
            setPhase(botId, FlightPhase.CLEANUP, now);
            return;
        }

        // Gentle descent angle
        if (bot.isGliding()) {
            bot.setPitch(PITCH_LANDING);
        }

        // Landed?
        if (bot.isOnGround() || bot.isTouchingWater()) {
            bot.stopGliding();
            setPhase(botId, FlightPhase.CLEANUP, now);
            LOGGER.info("ElytraFlight: {} has landed", bot.getName().getString());
        }
    }

    // ── CLEANUP phase ───────────────────────────────────────────────────────

    private static void tickCleanup(ServerPlayerEntity bot) {
        UUID botId = bot.getUuid();

        // Stop gliding if still active
        if (bot.isGliding()) {
            bot.stopGliding();
        }

        restoreChestArmorIfPossible(bot, true);

        clearState(botId);
        LOGGER.info("ElytraFlight: {} returned to NONE", bot.getName().getString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void setPhase(UUID botId, FlightPhase phase, long now) {
        if (phase == FlightPhase.NONE) {
            PHASE.remove(botId);
            PHASE_START_TICK.remove(botId);
        } else {
            PHASE.put(botId, phase);
            PHASE_START_TICK.put(botId, now);
        }
    }

    private static void clearState(UUID botId) {
        if (botId == null) {
            return;
        }
        PHASE.remove(botId);
        PHASE_START_TICK.remove(botId);
        LAST_BOOST_TICK.remove(botId);
        SAVED_CHESTPLATE.remove(botId);
        FLIGHT_COMMANDER.remove(botId);
        AUTONOMOUS_DESCENT.remove(botId);
        AUTONOMOUS_LAUNCH_PERCH.remove(botId);
        AUTONOMOUS_LAUNCH_DIR.remove(botId);
    }

    private static boolean isDropPassable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty();
    }

    private static boolean shouldAttemptAutonomousFollowFlight(ServerPlayerEntity bot,
                                                               ServerPlayerEntity commander,
                                                               long now) {
        if (bot == null || commander == null) {
            return false;
        }
        UUID botId = bot.getUuid();
        long lastAttempt = LAST_AUTONOMOUS_FOLLOW_ATTEMPT_TICK.getOrDefault(botId, Long.MIN_VALUE / 4L);
        if ((now - lastAttempt) < AUTONOMOUS_FOLLOW_ATTEMPT_COOLDOWN_TICKS) {
            return false;
        }
        if (!bot.isOnGround() || bot.isClimbing() || bot.hasVehicle() || bot.isUsingItem() || bot.isTouchingWater()) {
            return false;
        }

        double dx = commander.getX() - bot.getX();
        double dz = commander.getZ() - bot.getZ();
        double horizDistSq = dx * dx + dz * dz;
        double verticalDiff = bot.getY() - commander.getY();

        boolean botWellAboveCommander = verticalDiff >= AUTONOMOUS_FOLLOW_MIN_HEIGHT_ADVANTAGE;
        boolean botNotMuchLowerThanCommander = verticalDiff >= AUTONOMOUS_FOLLOW_MAX_HEIGHT_DEFICIT;
        boolean highDropIntercept = verticalDiff >= AUTONOMOUS_FOLLOW_HIGH_DROP_ADVANTAGE
                && horizDistSq >= AUTONOMOUS_FOLLOW_MIN_INTERCEPT_HORIZ_DIST_SQ;
        boolean far = horizDistSq >= AUTONOMOUS_FOLLOW_FAR_HORIZ_DIST_SQ;
        boolean veryFar = horizDistSq >= AUTONOMOUS_FOLLOW_VERY_FAR_HORIZ_DIST_SQ;

        return (botWellAboveCommander && (far || !bot.canSee(commander)))
                || (veryFar && botNotMuchLowerThanCommander)
                || highDropIntercept;
    }

    private static boolean hasElytraAvailable(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        return bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(Items.ELYTRA)
                || findItemSlot(bot, Items.ELYTRA) >= 0;
    }

    private static boolean isElevatedForAutonomousFlight(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        int minAirBlocks = 5;
        BlockPos origin = bot.getBlockPos();

        // Nearby ledge scan: allows tower-top autonomous launches even when the bot stands
        // a block or two away from the actual edge (e.g., near ladders/hatches).
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if ((dx * dx) + (dz * dz) > 9) {
                    continue;
                }
                BlockPos probe = origin.add(dx, 0, dz);
                if (!isDropPassable(world, probe)) {
                    continue;
                }
                if (countDropAir(world, probe, minAirBlocks) >= minAirBlocks) {
                    return true;
                }
            }
        }

        int selfDropAir = countDropAir(world, origin, minAirBlocks);
        return selfDropAir >= minAirBlocks;
    }

    private static int countDropAir(ServerWorld world, BlockPos origin, int minAirBlocks) {
        if (world == null || origin == null) {
            return 0;
        }
        int air = 0;
        for (int y = 0; y < minAirBlocks + 1; y++) {
            BlockPos below = origin.down(y + 1);
            if (!isDropPassable(world, below)) {
                break;
            }
            air++;
        }
        return air;
    }

    private static boolean isLaunchStandable(ServerWorld world, BlockPos feetPos) {
        if (world == null || feetPos == null) {
            return false;
        }
        if (!isDropPassable(world, feetPos) || !isDropPassable(world, feetPos.up())) {
            return false;
        }
        return !isDropPassable(world, feetPos.down());
    }

    private static int launchCorridorDepth(ServerWorld world, BlockPos origin, Direction dir, int maxDepth) {
        if (world == null || origin == null || dir == null || maxDepth <= 0) {
            return 0;
        }
        int depth = 0;
        for (int i = 1; i <= maxDepth; i++) {
            BlockPos feet = origin.offset(dir, i);
            BlockPos head = feet.up();
            if (!isDropPassable(world, feet) || !isDropPassable(world, head)) {
                break;
            }
            depth++;
        }
        return depth;
    }

    private static Direction bestLaunchDirection(ServerWorld world, BlockPos perch) {
        if (world == null || perch == null) {
            return null;
        }
        Direction bestDir = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            int corridor = launchCorridorDepth(world, perch, dir, AUTONOMOUS_LAUNCH_CORRIDOR_DEPTH);
            if (corridor <= 0) {
                continue;
            }
            int dropAhead = countDropAir(world, perch.offset(dir), 5);
            int score = (corridor * 4) + (dropAhead * 2);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        return bestDir;
    }

    private static float yawForDirection(Direction dir) {
        if (dir == null) {
            return 0.0F;
        }
        return switch (dir) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> -90.0F;
            default -> 0.0F;
        };
    }

    private static BlockPos findBestAutonomousLaunchPerch(ServerWorld world, BlockPos origin) {
        if (world == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dx = -AUTONOMOUS_LAUNCH_PERCH_RADIUS; dx <= AUTONOMOUS_LAUNCH_PERCH_RADIUS; dx++) {
            for (int dz = -AUTONOMOUS_LAUNCH_PERCH_RADIUS; dz <= AUTONOMOUS_LAUNCH_PERCH_RADIUS; dz++) {
                if ((dx * dx) + (dz * dz) > (AUTONOMOUS_LAUNCH_PERCH_RADIUS * AUTONOMOUS_LAUNCH_PERCH_RADIUS)) {
                    continue;
                }
                for (int dy = -1; dy <= AUTONOMOUS_LAUNCH_PERCH_MAX_RISE; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!isLaunchStandable(world, candidate)) {
                        continue;
                    }
                    Direction launchDir = bestLaunchDirection(world, candidate);
                    if (launchDir == null) {
                        continue;
                    }
                    int corridor = launchCorridorDepth(world, candidate, launchDir, AUTONOMOUS_LAUNCH_CORRIDOR_DEPTH);
                    if (corridor < AUTONOMOUS_LAUNCH_MIN_CORRIDOR) {
                        continue;
                    }
                    int dropAhead = countDropAir(world, candidate.offset(launchDir), 6);
                    if (dropAhead < 4) {
                        continue;
                    }
                    double distSq = candidate.getSquaredDistance(origin);
                    int rise = Math.max(0, candidate.getY() - origin.getY());
                    double score = (corridor * 6.0D) + (dropAhead * 3.0D) + (rise * 5.0D) - (distSq * 0.6D);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static String autonomousFollowSkipReason(ServerPlayerEntity bot) {
        if (bot == null) {
            return "bot unavailable";
        }
        if (isInFlight(bot.getUuid())) {
            return "already in flight";
        }
        if (!bot.isOnGround()) {
            return "not on ground";
        }
        if (bot.isClimbing()) {
            return "on ladder/vine";
        }
        if (bot.hasVehicle()) {
            return "mounted";
        }
        if (bot.isTouchingWater()) {
            return "in water";
        }
        if (!hasElytraAvailable(bot)) {
            return "no elytra";
        }
        if (!isElevatedForAutonomousFlight(bot)) {
            return "not elevated";
        }
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            BlockPos perch = findBestAutonomousLaunchPerch(world, bot.getBlockPos());
            if (perch == null || bestLaunchDirection(world, perch) == null) {
                return "no launch perch";
            }
        }
        return "launch blocked";
    }

    private static boolean restoreChestArmorIfPossible(ServerPlayerEntity bot, boolean logNoFallback) {
        if (bot == null) {
            return false;
        }
        UUID botId = bot.getUuid();
        ItemStack currentChest = bot.getEquippedStack(EquipmentSlot.CHEST);
        if (!currentChest.isOf(Items.ELYTRA)) {
            return false;
        }

        ItemStack savedChest = SAVED_CHESTPLATE.getOrDefault(botId, ItemStack.EMPTY);
        if (!savedChest.isEmpty()) {
            for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) {
                if (ItemStack.areItemsAndComponentsEqual(bot.getInventory().getStack(i), savedChest)) {
                    bot.getInventory().setStack(i, ItemStack.EMPTY);
                    break;
                }
            }
            bot.getInventory().insertStack(currentChest.copy());
            bot.equipStack(EquipmentSlot.CHEST, savedChest);
            bot.getInventory().markDirty();
            broadcastEquipment(bot, EquipmentSlot.CHEST, savedChest);
            LOGGER.info("ElytraFlight: {} re-equipped chestplate", bot.getName().getString());
            return true;
        }

        int fallbackSlot = findBestChestArmorSlot(bot);
        if (fallbackSlot >= 0) {
            ItemStack fallback = bot.getInventory().getStack(fallbackSlot).copy();
            bot.getInventory().setStack(fallbackSlot, ItemStack.EMPTY);
            bot.getInventory().insertStack(currentChest.copy());
            bot.equipStack(EquipmentSlot.CHEST, fallback);
            bot.getInventory().markDirty();
            broadcastEquipment(bot, EquipmentSlot.CHEST, fallback);
            LOGGER.info("ElytraFlight: {} re-equipped fallback chest armor", bot.getName().getString());
            return true;
        }

        if (logNoFallback) {
            LOGGER.info("ElytraFlight: {} kept elytra (no fallback chest armor found)", bot.getName().getString());
        }
        return false;
    }

    private static void tryRestoreChestArmorOutOfFlight(ServerPlayerEntity bot) {
        if (bot == null || isInFlight(bot.getUuid())) {
            return;
        }
        if (!bot.isOnGround() || bot.isClimbing() || bot.hasVehicle() || bot.isTouchingWater()) {
            return;
        }
        restoreChestArmorIfPossible(bot, false);
    }

    private static int findBestChestArmorSlot(ServerPlayerEntity bot) {
        if (bot == null) {
            return -1;
        }
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || stack.isOf(Items.ELYTRA)) {
                continue;
            }
            EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) {
                continue;
            }
            double durabilityScore = stack.isDamageable()
                    ? (double) (stack.getMaxDamage() - stack.getDamage()) / (double) Math.max(1, stack.getMaxDamage())
                    : 1.0D;
            final double[] armorScore = {0.0D};
            stack.applyAttributeModifiers(EquipmentSlot.CHEST, (RegistryEntry<EntityAttribute> attr, EntityAttributeModifier mod) -> {
                if (attr.equals(EntityAttributes.ARMOR)) {
                    armorScore[0] += mod.value();
                } else if (attr.equals(EntityAttributes.ARMOR_TOUGHNESS)) {
                    armorScore[0] += mod.value() * 0.1D;
                }
            });
            double score = (armorScore[0] * 100.0D) + durabilityScore;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** Shared steering logic: steer the bot toward a target position. */
    private static void applyGlideSteering(ServerPlayerEntity bot, Vec3d botPos, Vec3d targetPos) {
        Vec3d direction = targetPos.subtract(botPos);
        double horizDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        double vertDist = direction.y;

        float targetYaw = (float) (Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float targetPitch;

        if (horizDist < 1.0) {
            targetPitch = vertDist > 0 ? PITCH_DESCENT_SHALLOW : PITCH_LANDING;
        } else {
            targetPitch = (float) (-Math.toDegrees(Math.atan2(vertDist, horizDist)));
        }

        double altitudeDiff = bot.getY() - targetPos.y;
        if (altitudeDiff < ALTITUDE_GAIN_THRESHOLD) {
            targetPitch = Math.min(targetPitch, PITCH_DESCENT_SHALLOW);
        } else if (altitudeDiff > ALTITUDE_LOSE_THRESHOLD) {
            targetPitch = Math.max(targetPitch, 20.0F);
        }

        targetPitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, targetPitch));

        bot.setPitch(targetPitch);
        bot.setYaw(targetYaw);
        bot.setHeadYaw(targetYaw);
    }

    /** Shared anti-stall nudge. */
    private static void applyAntiStall(ServerPlayerEntity bot) {
        Vec3d velocity = bot.getVelocity();
        if (velocity.lengthSquared() < STALL_SPEED_SQ) {
            Vec3d lookVec = bot.getRotationVector();
            bot.setVelocity(velocity.add(lookVec.multiply(ANTI_STALL_MAGNITUDE)));
            bot.velocityDirty = true;
        }
    }

    private static ServerPlayerEntity resolveCommander(MinecraftServer server, ServerPlayerEntity bot) {
        UUID followTarget = BotEventHandler.getFollowTargetUuid(bot);
        if (followTarget == null) {
            return null;
        }
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(followTarget);
        if (target != null && !target.isRemoved() && !target.isSpectator()) {
            return target;
        }
        return null;
    }

    private static ServerPlayerEntity resolveFlightCommander(MinecraftServer server, UUID botId) {
        UUID commanderId = FLIGHT_COMMANDER.get(botId);
        if (commanderId == null) {
            return null;
        }
        ServerPlayerEntity commander = server.getPlayerManager().getPlayer(commanderId);
        if (commander != null && !commander.isRemoved() && commander.isAlive()) {
            return commander;
        }
        return null;
    }

    /**
     * Mirrors the logic of {@code PlayerEntity.canGlide()} which has protected access.
     * A player can glide when: not on ground, no vehicle, not in creative flight,
     * and wearing elytra in the chest slot.
     */
    private static boolean canBotGlide(ServerPlayerEntity bot) {
        return !bot.isOnGround()
                && !bot.hasVehicle()
                && !bot.getAbilities().flying
                && !bot.isTouchingWater()
                && bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private static int findItemSlot(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private static void tryFireworkBoost(ServerPlayerEntity bot, long now) {
        UUID botId = bot.getUuid();

        // Find a firework rocket in inventory
        int rocketSlot = findItemSlot(bot, Items.FIREWORK_ROCKET);
        if (rocketSlot < 0) {
            return; // No rockets — glide without boost
        }

        ItemStack rocketStack = bot.getInventory().getStack(rocketSlot);
        ItemStack toUse = rocketStack.copy();
        toUse.setCount(1);

        // Consume one rocket
        rocketStack.decrement(1);
        if (rocketStack.isEmpty()) {
            bot.getInventory().setStack(rocketSlot, ItemStack.EMPTY);
        }
        bot.getInventory().markDirty();

        // Spawn player-attached firework for elytra propulsion
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            FireworkRocketEntity rocket = new FireworkRocketEntity(world, toUse, bot);
            world.spawnEntity(rocket);
            LAST_BOOST_TICK.put(botId, now);
            LOGGER.debug("ElytraFlight: {} used firework boost", bot.getName().getString());
        }
    }

    private static void broadcastEquipment(ServerPlayerEntity bot, EquipmentSlot slot, ItemStack stack) {
        List<Pair<EquipmentSlot, ItemStack>> updates = List.of(new Pair<>(slot, stack));
        bot.getEntityWorld().getPlayers().forEach(player -> {
            if (player instanceof ServerPlayerEntity spe) {
                spe.networkHandler.sendPacket(new EntityEquipmentUpdateS2CPacket(bot.getId(), updates));
            }
        });
    }
}
