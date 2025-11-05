package net.shasankp000.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone drop collection skill. Sweeps nearby item entities using the existing DropSweeper
 * utility and reports how many sweeps were attempted.
 */
public final class DropSweepSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-drop-sweep");

    @Override
    public String name() {
        return "drop_sweep";
    }


@Override
public SkillExecutionResult execute(SkillContext context) {
    ServerCommandSource source = context.botSource();
    ServerPlayerEntity bot = source.getPlayer();
    if (bot == null) {
        return SkillExecutionResult.failure("No bot player found for drop sweep.");
    }

    double radius = getDoubleParameter(context.parameters(), "radius", 8.0D);
    double vertical = getDoubleParameter(context.parameters(), "vertical", Math.max(radius, 6.0D));
    int maxTargets = getIntParameter(context.parameters(), "maxTargets", 24);
    long durationMs = getLongParameter(context.parameters(), "durationMs", 8000L);
    int passes = Math.max(1, getIntParameter(context.parameters(), "passes", 3));
    boolean reuseRecent = getBooleanParameter(context.parameters(), "reuseRecent", true);

    if (context.sharedState() != null) {
        SharedStateUtils.setValue(context.sharedState(), "dropSweep.last.radius", radius);
        SharedStateUtils.setValue(context.sharedState(), "dropSweep.last.vertical", vertical);
    }

    if (SkillManager.shouldAbortSkill(bot)) {
        LOGGER.warn("drop_sweep aborted before starting due to cancellation request.");
        return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
    }

        double effectiveRadius = Math.max(radius, 3.0D);
        double effectiveVertical = Math.max(vertical, 4.0D);
        ServerWorld world = source.getWorld();
        int pass = 0;
        int collectedCount = 0;
        int remaining = world.getEntitiesByClass(
                ItemEntity.class,
                bot.getBoundingBox().expand(effectiveRadius, effectiveVertical, effectiveRadius),
                item -> !item.isRemoved()
        ).size();

        try {
            while (pass < passes) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    LOGGER.warn("drop_sweep aborted during pass {} due to cancellation request.", pass + 1);
                    return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
                }

                if (reuseRecent) {
                    BotEventHandler.collectNearbyDrops(bot, effectiveRadius);
                }

                try {
                    DropSweeper.sweep(
                            source.withSilent().withMaxLevel(4),
                            effectiveRadius,
                            effectiveVertical,
                            Math.max(1, maxTargets),
                            durationMs
                    );
                } catch (Exception sweepError) {
                    LOGGER.warn("drop_sweep sweep (pass {}) failed: {}", pass + 1, sweepError.getMessage(), sweepError);
                    return SkillExecutionResult.failure("drop_sweep failed: " + sweepError.getMessage());
                }

                List<ItemEntity> residual = world.getEntitiesByClass(
                        ItemEntity.class,
                        bot.getBoundingBox().expand(effectiveRadius, effectiveVertical, effectiveRadius),
                        item -> !item.isRemoved()
                );
                residual.sort(Comparator.comparingDouble(bot::squaredDistanceTo));

                boolean collectedThisPass = false;
                for (ItemEntity item : residual) {
                    if (SkillManager.shouldAbortSkill(bot)) {
                        LOGGER.warn("drop_sweep aborted during targeted pickup due to cancellation request.");
                        return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
                    }
                    boolean collected = collectSingleDrop(source, bot, item);
                    if (collected) {
                        collectedCount++;
                        collectedThisPass = true;
                    }
                    BotEventHandler.collectNearbyDrops(bot, 2.0D);
                }

                remaining = world.getEntitiesByClass(
                        ItemEntity.class,
                        bot.getBoundingBox().expand(effectiveRadius, effectiveVertical, effectiveRadius),
                        item -> !item.isRemoved()
                ).size();

                if (remaining == 0) {
                    break;
                }

                pass++;
                effectiveRadius = Math.min(effectiveRadius + 2.5D, radius + 8.0D);
                effectiveVertical = Math.min(effectiveVertical + 2.5D, vertical + 8.0D);

                if (!collectedThisPass && pass >= passes) {
                    LOGGER.info("drop_sweep reached max passes with remaining items; stopping expansion.");
                }
            }
        } finally {
            BotActions.sneak(bot, false);
        }

        String summary = String.format(Locale.ROOT,
                "Swept drops within radius %.1f (%.1f vertical) across %d pass(es). Remaining items: %d, collected: %d",
                effectiveRadius, effectiveVertical, pass + 1, Math.max(remaining, 0), collectedCount);
        LOGGER.info("drop_sweep completed: {}", summary);
        return SkillExecutionResult.success(summary);
}

private boolean collectSingleDrop(ServerCommandSource source, ServerPlayerEntity bot, ItemEntity drop) {
    if (drop == null || drop.isRemoved()) {
        return false;
    }
    BlockPos dropPos = drop.getBlockPos().toImmutable();
    MovementService.MovementOptions options = MovementService.MovementOptions.lootCollection();
    boolean moved = false;
    var planOpt = MovementService.planLootApproach(bot, dropPos, options);
    if (planOpt.isPresent()) {
        MovementService.MovementResult moveResult = MovementService.execute(source, bot, planOpt.get());
        LOGGER.info("drop_sweep targeted movement ({}) -> {}", planOpt.get().mode(), moveResult.detail());
        moved = moveResult.success();
        sleepQuietly(125);
    }
    if (!moved) {
        moved = attemptEdgeApproach(bot, dropPos);
    }
    boolean crouched = false;
    try {
        if (dropPos.getY() < bot.getY() - 0.5D) {
            BotActions.sneak(bot, true);
            crouched = true;
        }
        if (!drop.isRemoved()) {
            DropSweeper.attemptManualNudge(bot, drop, dropPos);
        }
        BotEventHandler.collectNearbyDrops(bot, 2.0D);
    } finally {
        if (crouched) {
            BotActions.sneak(bot, false);
        }
    }
    return drop.isRemoved();
}

private boolean attemptEdgeApproach(ServerPlayerEntity bot, BlockPos dropPos) {
    double targetX = dropPos.getX() + 0.5D;
    double targetZ = dropPos.getZ() + 0.5D;
    double dx = targetX - bot.getX();
    double dz = targetZ - bot.getZ();
    double horizontal = Math.sqrt(dx * dx + dz * dz);
    if (horizontal < 0.2D) {
        return true;
    }
    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
    bot.setYaw(yaw);
    bot.setHeadYaw(yaw);
    bot.setBodyYaw(yaw);
    BotActions.sprint(bot, false);
    int steps = Math.max(1, (int) Math.ceil(horizontal / 0.45D));
    for (int i = 0; i < steps; i++) {
        BotActions.moveForward(bot);
        sleepQuietly(110);
        if (bot.getBlockPos().getSquaredDistance(dropPos) <= 4) {
            return true;
        }
    }
    return bot.getBlockPos().getSquaredDistance(dropPos) <= 9;
}

private void sleepQuietly(long millis) {
    try {
        Thread.sleep(millis);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}

    private double getDoubleParameter(Map<String, Object> params, String key, double defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private long getLongParameter(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean getBooleanParameter(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }
}
