package net.shasankp000.GameAI.services;

import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ── Timing constants ────────────────────────────────────────────────────
    private static final long EQUIP_TIMEOUT_TICKS = 40L;
    private static final long LAUNCH_TIMEOUT_TICKS = 60L;
    private static final long LANDING_TIMEOUT_TICKS = 200L;
    private static final long BOOST_INTERVAL_TICKS = 60L;     // 3 seconds
    private static final double BOOST_DISTANCE_SQ = 15.0 * 15.0; // 225 blocks²

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
     * <p>Conditions: not already in flight, has elytra, on ground, elevated (significant
     * air below in at least 2 of 4 cardinal directions).</p>
     *
     * @return {@code true} if the descent was initiated.
     */
    public static boolean tryAutonomousDescent(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return false;
        UUID botId = bot.getUuid();
        if (isInFlight(botId)) return false;
        if (!bot.isOnGround()) return false;
        if (bot.hasVehicle() || bot.isTouchingWater()) return false;

        // Must have an elytra (equipped or in inventory).
        boolean hasElytra = bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(Items.ELYTRA)
                || findItemSlot(bot, Items.ELYTRA) >= 0;
        if (!hasElytra) return false;

        // Elevation check: at least 2 of 4 cardinal directions must have > 6 blocks of air below.
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        int elevatedDirs = 0;
        int minAirBlocks = 6;
        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH,
                net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST,
                net.minecraft.util.math.Direction.WEST}) {
            BlockPos probe = bot.getBlockPos().offset(dir);
            int air = 0;
            for (int y = 0; y < minAirBlocks + 1; y++) {
                BlockPos below = probe.down(y + 1);
                if (world.getBlockState(below).isAir()
                        || world.getBlockState(below).isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                    air++;
                } else {
                    break;
                }
            }
            if (air >= minAirBlocks) elevatedDirs++;
        }
        if (elevatedDirs < 2) return false;

        // All conditions met — begin autonomous descent.
        long now = server.getTicks();
        AUTONOMOUS_DESCENT.put(botId, Boolean.TRUE);
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
        if (commander == null || !commander.isGliding()) {
            return;
        }

        // Commander is gliding — begin elytra sequence
        UUID botId = bot.getUuid();
        FLIGHT_COMMANDER.put(botId, commander.getUuid());
        setPhase(botId, FlightPhase.EQUIPPING, now);
        LOGGER.info("ElytraFlight: {} entering EQUIPPING (commander {} is gliding)",
                bot.getName().getString(), commander.getName().getString());
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
            SAVED_CHESTPLATE.putIfAbsent(botId, ItemStack.EMPTY);
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

        // Timeout
        if (elapsed > LAUNCH_TIMEOUT_TICKS) {
            LOGGER.warn("ElytraFlight: {} LAUNCHING timed out", bot.getName().getString());
            setPhase(botId, FlightPhase.CLEANUP, now);
            return;
        }

        // If already gliding (e.g. launched off cliff), advance immediately
        if (bot.isGliding()) {
            setPhase(botId, FlightPhase.GLIDING, now);
            LAST_BOOST_TICK.put(botId, now);
            LOGGER.info("ElytraFlight: {} is now GLIDING", bot.getName().getString());
            return;
        }

        // Try jumping to get off ground
        if (bot.isOnGround()) {
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

        // Exit condition: bot has landed
        if (bot.isOnGround()) {
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

        // Re-equip saved chestplate
        ItemStack savedChest = SAVED_CHESTPLATE.getOrDefault(botId, ItemStack.EMPTY);
        if (!savedChest.isEmpty()) {
            ItemStack currentElytra = bot.getEquippedStack(EquipmentSlot.CHEST);

            // During EQUIPPING the original chestplate was swapped into the inventory
            // slot that held the elytra (for crash/death safety). Find and remove it
            // now so we don't duplicate when re-equipping the saved copy.
            for (int i = 0; i < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; i++) {
                if (ItemStack.areItemsAndComponentsEqual(bot.getInventory().getStack(i), savedChest)) {
                    bot.getInventory().setStack(i, ItemStack.EMPTY);
                    break;
                }
            }

            // Put elytra back in inventory
            if (!currentElytra.isEmpty()) {
                bot.getInventory().insertStack(currentElytra);
            }
            bot.equipStack(EquipmentSlot.CHEST, savedChest);
            bot.getInventory().markDirty();
            broadcastEquipment(bot, EquipmentSlot.CHEST, savedChest);
            LOGGER.info("ElytraFlight: {} re-equipped chestplate", bot.getName().getString());
        }

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
