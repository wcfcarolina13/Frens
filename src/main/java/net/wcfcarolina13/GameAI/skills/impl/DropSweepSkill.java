package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.FunctionCaller.SharedStateUtils;
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
    // Caps for the per-pass widening (default: up to 8 blocks past the start).
    double maxRadius = Math.max(3.0D, getDoubleParameter(context.parameters(), "maxRadius", radius + 8.0D));
    double maxVertical = Math.max(4.0D, getDoubleParameter(context.parameters(), "maxVertical", vertical + 8.0D));
    // noOffload: a full inventory stops the sweep instead of storing into or placing chests here.
    boolean noOffload = getBooleanParameter(context.parameters(), "noOffload", false);

    if (context.sharedState() != null) {
        SharedStateUtils.setValue(context.sharedState(), "dropSweep.last.radius", radius);
        SharedStateUtils.setValue(context.sharedState(), "dropSweep.last.vertical", vertical);
    }

    if (SkillManager.shouldAbortSkill(bot)) {
        LOGGER.warn("drop_sweep aborted before starting due to cancellation request.");
        return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
    }

        double effectiveRadius = Math.min(Math.max(radius, 3.0D), maxRadius);
        double effectiveVertical = Math.min(Math.max(vertical, 4.0D), maxVertical);
        ServerWorld world = source.getWorld();
        int pass = 0;
        int collectedCount = 0;
        int remaining = countItemsInRange(world, bot, effectiveRadius, effectiveVertical);
        // Items lying in range at the start: drops already in pickup reach, collectNearbyDrops
        // and vanilla pickups while walking are never counted by the sweep, so the summary
        // uses whichever of the two counts is larger.
        int initialInRange = remaining;

        if (noOffload) {
            DropSweeper.setNoOffload(bot.getUuid(), true);
        }
        try {
            while (pass < passes) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    LOGGER.warn("drop_sweep aborted during pass {} due to cancellation request.", pass + 1);
                    return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
                }
                
                // Check if inventory is full - ALWAYS terminate drop_sweep if full
                if (!DropSweeper.ensureSpaceForDropSweep(source, bot)) {
                    String invFullMsg = DropSweepMessages.inventoryFull(collectedCount);
                    LOGGER.info("drop_sweep terminated due to full inventory after {} pass(es), collected {} item(s)",
                            pass, collectedCount);
                    bot.sendMessage(Text.literal(invFullMsg), false);
                    return SkillExecutionResult.success(invFullMsg);
                }

                if (reuseRecent) {
                    BotEventHandler.collectNearbyDrops(bot, effectiveRadius);
                }

                try {
                    collectedCount += DropSweeper.sweepCounted(
                            source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
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
                        item -> !item.isRemoved() && DropSweeper.isAllowedDrop(bot.getUuid(), item)
                );
                residual.sort(Comparator.comparingDouble(bot::squaredDistanceTo));

                boolean collectedThisPass = false;
                for (ItemEntity item : residual) {
                    if (SkillManager.shouldAbortSkill(bot)) {
                        LOGGER.warn("drop_sweep aborted during targeted pickup due to cancellation request.");
                        return SkillExecutionResult.failure("drop_sweep paused due to nearby threat.");
                    }
                    
                    // Check inventory before attempting each item pickup
                    if (!DropSweeper.ensureSpaceForDropSweep(source, bot)) {
                        String invFullMsg = DropSweepMessages.inventoryFull(collectedCount);
                        LOGGER.info("drop_sweep terminated during pickup due to full inventory, collected {} item(s)",
                                collectedCount);
                        bot.sendMessage(Text.literal(invFullMsg), false);
                        return SkillExecutionResult.success(invFullMsg);
                    }
                    
                    int collected = collectSingleDrop(source, bot, item);
                    if (collected > 0) {
                        collectedCount += collected;
                        collectedThisPass = true;
                    }
                    BotEventHandler.collectNearbyDrops(bot, 2.0D);
                }

                remaining = countItemsInRange(world, bot, effectiveRadius, effectiveVertical);

                if (remaining == 0) {
                    break;
                }

                pass++;
                effectiveRadius = Math.min(effectiveRadius + 2.5D, maxRadius);
                effectiveVertical = Math.min(effectiveVertical + 2.5D, maxVertical);

                if (!collectedThisPass && pass >= passes) {
                    LOGGER.info("drop_sweep reached max passes with remaining items; stopping expansion.");
                }
            }
        } finally {
            BotActions.sneak(bot, false);
            if (noOffload) {
                DropSweeper.setNoOffload(bot.getUuid(), false);
            }
        }

        String detail = String.format(Locale.ROOT,
                "Swept drops within radius %.1f (%.1f vertical) across %d pass(es). Remaining items: %d, collected: %d",
                effectiveRadius, effectiveVertical, pass + 1, Math.max(remaining, 0), collectedCount);
        LOGGER.info("drop_sweep completed: {}", detail);
        return SkillExecutionResult.success(DropSweepMessages.summary(
                DropSweepMessages.pickedUpCount(initialInRange, remaining, collectedCount), remaining));
}

/** Items (stack counts) still lying within the sweep range. */
private static int countItemsInRange(ServerWorld world, ServerPlayerEntity bot, double radius, double vertical) {
    int total = 0;
    for (ItemEntity item : world.getEntitiesByClass(
            ItemEntity.class,
            bot.getBoundingBox().expand(radius, vertical, radius),
            item -> !item.isRemoved() && DropSweeper.isAllowedDrop(bot.getUuid(), item))) {
        total += item.getStack().getCount();
    }
    return total;
}

/** @return items actually picked up from {@code drop} (0 if it is still lying there untouched). */
private int collectSingleDrop(ServerCommandSource source, ServerPlayerEntity bot, ItemEntity drop) {
    if (drop == null || drop.isRemoved()) {
        return 0;
    }
    int countBefore = drop.getStack().getCount();
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
    return DropSweeper.pickedUpFrom(drop, countBefore);
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
    
    /**
     * Check if bot's inventory is full (fewer than 3 empty slots).
     * Drop sweep always terminates when inventory is full.
     */
}
