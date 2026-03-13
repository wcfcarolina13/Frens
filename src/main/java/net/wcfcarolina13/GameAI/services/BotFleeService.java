package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages flee behavior for IDLE-mode bots that are outnumbered or critically wounded.
 *
 * <p>Bots in FOLLOW, GUARD, PATROL, or STAY modes never flee (they have duties).
 * IDLE bots evaluate the threat and sprint away from the centroid of hostile positions
 * when the situation is unwinnable.</p>
 */
public final class BotFleeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-flee");

    /** Maximum ticks a flee can last before force-stopping (10 seconds). */
    private static final int MAX_FLEE_TICKS = 200;
    /** Squared distance threshold to consider "safe" from all hostiles. */
    private static final double SAFE_DISTANCE_SQ = 20.0 * 20.0;
    /** Cooldown ticks after a flee ends before re-evaluating (5 seconds). */
    private static final int COOLDOWN_TICKS = 100;

    private static final ConcurrentHashMap<UUID, FleeState> FLEE_STATES = new ConcurrentHashMap<>();

    private BotFleeService() {}

    private static final class FleeState {
        boolean isFleeing;
        long fleeStartTick;
        Vec3d fleeDirection;
        long fleeCooldownUntilTick;
    }

    /**
     * Evaluates whether the bot should flee and, if already fleeing, continues the flee movement.
     * Returns true if the bot is actively fleeing (caller should skip normal combat/idle).
     *
     * <p>Only IDLE-mode bots can flee. All other modes return false immediately.</p>
     */
    public static boolean tickFlee(ServerPlayerEntity bot, MinecraftServer server,
                                   List<Entity> hostiles, BotEventHandler.Mode mode) {
        if (bot == null || server == null) return false;
        if (mode != BotEventHandler.Mode.IDLE) return false;
        if (hostiles == null || hostiles.isEmpty()) {
            // No threats — stop fleeing if we were
            FleeState state = FLEE_STATES.get(bot.getUuid());
            if (state != null && state.isFleeing) {
                stopFleeing(state, server.getTicks());
            }
            return false;
        }

        long currentTick = server.getTicks();
        FleeState state = FLEE_STATES.computeIfAbsent(bot.getUuid(), id -> new FleeState());

        // Already fleeing — continue or stop
        if (state.isFleeing) {
            return continueFlee(bot, state, hostiles, currentTick);
        }

        // Cooldown check
        if (currentTick < state.fleeCooldownUntilTick) {
            return false;
        }

        // Threat assessment
        if (shouldFlee(bot, hostiles)) {
            startFleeing(bot, state, hostiles, currentTick);
            return true;
        }

        return false;
    }

    private static boolean shouldFlee(ServerPlayerEntity bot, List<Entity> hostiles) {
        int hostileCount = hostiles.size();
        float healthRatio = bot.getHealth() / bot.getMaxHealth();
        boolean hasWeapon = BotActions.hasMeleeWeapon(bot) || BotActions.hasRangedWeapon(bot);

        // Critical health — flee regardless
        if (healthRatio <= 0.30f) return true;
        // Heavily outnumbered — flee regardless of weapon
        if (hostileCount >= 3) return true;
        // Outnumbered and unarmed
        if (hostileCount >= 2 && !hasWeapon) return true;

        return false;
    }

    private static void startFleeing(ServerPlayerEntity bot, FleeState state,
                                     List<Entity> hostiles, long currentTick) {
        Vec3d fleeDir = computeFleeDirection(bot, hostiles);
        state.isFleeing = true;
        state.fleeStartTick = currentTick;
        state.fleeDirection = fleeDir;
        LOGGER.info("Bot {} fleeing from {} hostiles (health={}/{})",
                bot.getName().getString(), hostiles.size(),
                String.format("%.1f", bot.getHealth()),
                String.format("%.1f", bot.getMaxHealth()));
        applyFleeMovement(bot, state);
    }

    private static boolean continueFlee(ServerPlayerEntity bot, FleeState state,
                                        List<Entity> hostiles, long currentTick) {
        // Timeout
        if (currentTick - state.fleeStartTick >= MAX_FLEE_TICKS) {
            stopFleeing(state, currentTick);
            return false;
        }

        // Check if we've reached safety (all hostiles are far enough)
        boolean allSafe = hostiles.stream().allMatch(
                e -> e.squaredDistanceTo(bot) >= SAFE_DISTANCE_SQ);
        if (allSafe) {
            stopFleeing(state, currentTick);
            return false;
        }

        // Recompute direction periodically (hostiles may have shifted)
        if ((currentTick - state.fleeStartTick) % 20 == 0) {
            state.fleeDirection = computeFleeDirection(bot, hostiles);
        }

        applyFleeMovement(bot, state);
        return true;
    }

    private static void applyFleeMovement(ServerPlayerEntity bot, FleeState state) {
        Vec3d target = new Vec3d(
                bot.getX() + state.fleeDirection.x * 25,
                bot.getY(),
                bot.getZ() + state.fleeDirection.z * 25);
        BotActions.sprint(bot, true);
        FollowMovementService.moveToward(bot, target, 1.0, true, null);
    }

    private static Vec3d computeFleeDirection(ServerPlayerEntity bot, List<Entity> hostiles) {
        double cx = 0, cz = 0;
        for (Entity e : hostiles) {
            cx += e.getX();
            cz += e.getZ();
        }
        cx /= hostiles.size();
        cz /= hostiles.size();

        double dx = bot.getX() - cx;
        double dz = bot.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            // Surrounded — pick an arbitrary direction
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new Vec3d(dx / len, 0, dz / len);
    }

    private static void stopFleeing(FleeState state, long currentTick) {
        state.isFleeing = false;
        state.fleeDirection = null;
        state.fleeCooldownUntilTick = currentTick + COOLDOWN_TICKS;
    }

    /** Clear flee state for a bot (on death, respawn, or mode change). */
    public static void reset(UUID botId) {
        FLEE_STATES.remove(botId);
    }
}
