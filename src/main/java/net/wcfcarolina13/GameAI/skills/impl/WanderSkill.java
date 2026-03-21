package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Lightweight local stroll used as an ambient fallback when no resource-based hobbies are available.
 */
public final class WanderSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-wander");
    private static final Random RNG = new Random();

    @Override
    public String name() {
        return "wander";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("World unavailable.");
        }

        int radius = clamp(getInt(context.parameters(), "radius", 12), 6, 20);
        int steps = clamp(getInt(context.parameters(), "steps", 3), 1, 5);
        boolean shadowCompanion = getBoolean(context.parameters(), "shadow_companion");
        BlockPos anchorSeed = shadowCompanion
                ? resolveShadowAnchor(bot, world)
                : BotHomeService.resolveHomeTarget(bot).orElse(bot.getBlockPos()).toImmutable();
        SafePositionService.SurfaceStagingCandidate initialStaging =
                SafePositionService.findBestSurfaceStaging(world, anchorSeed, 8, true);
        BlockPos anchor = initialStaging != null ? initialStaging.pos() : anchorSeed;

        boolean moved = false;
        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Wander paused by another task.");
            }
            int dx = RNG.nextInt((radius * 2) + 1) - radius;
            int dz = RNG.nextInt((radius * 2) + 1) - radius;
            if (dx == 0 && dz == 0) {
                continue;
            }

            if (shadowCompanion) {
                BlockPos refreshed = resolveShadowAnchor(bot, world);
                if (refreshed != null) {
                    anchor = refreshed;
                }
            }
            BlockPos rough = anchor.add(dx, 0, dz);
            SafePositionService.SurfaceStagingCandidate staging =
                    SafePositionService.findBestSurfaceStaging(world, rough, 5, true);
            BlockPos target = staging != null ? staging.pos() : SafePositionService.findSafeNear(world, rough, 5);
            if (target == null) {
                continue;
            }
            if (bot.getBlockPos().getSquaredDistance(target) <= 4.0D) {
                continue;
            }

            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    target,
                    target,
                    null,
                    null,
                    null
            );
            MovementService.MovementResult result = MovementService.execute(
                    source,
                    bot,
                    plan,
                    Boolean.FALSE,
                    true,
                    true,   // allowPursuit — get as close as possible instead of hard-failing
                    false
            );
            LOGGER.debug("Wander attempt target={} success={} detail='{}'",
                    target.toShortString(),
                    result != null && result.success(),
                    result != null ? result.detail() : "null");
            if (result != null && result.success()) {
                moved = true;
                anchor = target;
            }
        }

        if (!moved) {
            return SkillExecutionResult.failure("Couldn't find a safe place to wander.");
        }
        return SkillExecutionResult.success("Went for a short stroll.");
    }

    private static BlockPos resolveShadowAnchor(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        UUID owner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (owner == null) {
            return bot.getBlockPos().toImmutable();
        }
        ServerPlayerEntity best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (ServerPlayerEntity other : world.getPlayers()) {
            if (other == null || other == bot || other.isRemoved() || !other.isAlive()) {
                continue;
            }
            if (!net.wcfcarolina13.GameAI.BotEventHandler.isRegisteredBot(other)) {
                continue;
            }
            if (!owner.equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(other))) {
                continue;
            }
            TaskService.ActiveTaskInfo task = TaskService.getActiveTaskInfo(other.getUuid()).orElse(null);
            if (task == null || task.origin() != TaskService.Origin.AMBIENT) {
                continue;
            }
            double distSq = bot.squaredDistanceTo(other);
            if (distSq < 9.0D || distSq > 20.0D * 20.0D) {
                continue;
            }
            if (distSq < bestSq) {
                bestSq = distSq;
                best = other;
            }
        }
        return best != null ? best.getBlockPos().toImmutable() : bot.getBlockPos().toImmutable();
    }

    private static int getInt(Map<String, Object> params, String key, int fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> params, String key) {
        if (params == null || key == null) {
            return false;
        }
        Object raw = params.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
