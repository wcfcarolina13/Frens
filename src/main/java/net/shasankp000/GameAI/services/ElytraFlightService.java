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

        // ── Steering ──
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d cmdPos = new Vec3d(commander.getX(), commander.getY(), commander.getZ());
        Vec3d direction = cmdPos.subtract(botPos);
        double horizDist = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        double vertDist = direction.y;

        // Calculate target pitch and yaw
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        float targetPitch;

        if (horizDist < 1.0) {
            // Commander is directly above/below — use altitude-based pitch
            targetPitch = vertDist > 0 ? PITCH_DESCENT_SHALLOW : PITCH_LANDING;
        } else {
            targetPitch = (float) (-Math.toDegrees(Math.atan2(vertDist, horizDist)));
        }

        // Altitude management overrides
        double altitudeDiff = bot.getY() - commander.getY();
        if (altitudeDiff < ALTITUDE_GAIN_THRESHOLD) {
            // Bot is too low — pitch up
            targetPitch = Math.min(targetPitch, PITCH_DESCENT_SHALLOW);
        } else if (altitudeDiff > ALTITUDE_LOSE_THRESHOLD) {
            // Bot is too high — pitch down
            targetPitch = Math.max(targetPitch, 20.0F);
        }

        // Clamp pitch
        targetPitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, targetPitch));

        bot.setPitch(targetPitch);
        bot.setYaw(targetYaw);
        bot.setHeadYaw(targetYaw);

        // ── Anti-stall velocity nudge ──
        Vec3d velocity = bot.getVelocity();
        if (velocity.lengthSquared() < STALL_SPEED_SQ) {
            Vec3d lookVec = bot.getRotationVector();
            bot.setVelocity(velocity.add(lookVec.multiply(ANTI_STALL_MAGNITUDE)));
            bot.velocityDirty = true;
        }

        // ── Firework boosting ──
        long lastBoost = LAST_BOOST_TICK.getOrDefault(botId, 0L);
        double distSqToCommander = bot.squaredDistanceTo(commander);
        if (now - lastBoost >= BOOST_INTERVAL_TICKS && distSqToCommander > BOOST_DISTANCE_SQ) {
            tryFireworkBoost(bot, now);
        }
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
