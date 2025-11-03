package net.shasankp000.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PathFinding.GoTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class CollectDirtSkill implements Skill {

    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_ATTEMPTS_WITHOUT_PROGRESS = 3;
    private static final long MAX_RUNTIME_MILLIS = 60_000L;
    private static final int MAX_HORIZONTAL_RADIUS = 16;
    private static final int HORIZONTAL_RADIUS_STEP = 2;
    private static final int MAX_VERTICAL_RANGE = 8;
    private static final int VERTICAL_RANGE_STEP = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-collect-dirt");

    @Override
    public String name() {
        return "collect_dirt";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        int targetCount = getIntParameter(context, "count", DEFAULT_COUNT);
        int horizontalRadius = getIntParameter(context, "searchRadius", 6);
        int verticalRange = getIntParameter(context, "verticalRange", 4);

        DirtShovelSkill shovelSkill = new DirtShovelSkill();
        ServerCommandSource source = context.botSource();

        int collected = 0;
        int failuresInRow = 0;
        int attempt = 0;
        String lastMessage = "";
        long startTime = System.currentTimeMillis();
        int radiusBoost = 0;
        int verticalBoost = 0;
        Set<BlockPos> unreachable = new HashSet<>();

        while (collected < targetCount
                && failuresInRow < MAX_ATTEMPTS_WITHOUT_PROGRESS
                && System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS
                && !Thread.currentThread().isInterrupted()) {

            attempt++;
            int effectiveHorizontal = Math.min(horizontalRadius + radiusBoost, MAX_HORIZONTAL_RADIUS);
            int effectiveVertical = Math.min(verticalRange + verticalBoost, MAX_VERTICAL_RANGE);

            LOGGER.info("collect_dirt iteration {} (collected {}/{}, radius={}, vertical={})",
                    attempt, collected, targetCount, effectiveHorizontal, effectiveVertical);

            SkillExecutionResult result = shovelSkill.perform(
                    source,
                    context.sharedState(),
                    effectiveHorizontal,
                    effectiveVertical,
                    unreachable
            );

            lastMessage = result.message();

            if (result.success()) {
                collected++;
                failuresInRow = 0;
                radiusBoost = 0;
                verticalBoost = 0;
                moveTowardLoot(source, context.sharedState());
                LOGGER.info("collect_dirt iteration {} succeeded: {}", attempt, lastMessage);
            } else {
                failuresInRow++;
                LOGGER.warn("collect_dirt iteration {} failed ({} consecutive failures): {}",
                        attempt, failuresInRow, lastMessage);
                if (isUnreachableFailure(lastMessage)) {
                    BlockPos pending = getPendingTarget(context.sharedState());
                    if (pending != null) {
                        unreachable.add(pending);
                        LOGGER.debug("Added {} to unreachable dirt targets (size={})", pending, unreachable.size());
                    }
                }
                if (shouldExpandSearch(lastMessage) && radiusBoost + horizontalRadius < MAX_HORIZONTAL_RADIUS) {
                    radiusBoost = Math.min(radiusBoost + HORIZONTAL_RADIUS_STEP, MAX_HORIZONTAL_RADIUS - horizontalRadius);
                    verticalBoost = Math.min(verticalBoost + VERTICAL_RANGE_STEP, MAX_VERTICAL_RANGE - verticalRange);
                    LOGGER.info("collect_dirt expanding search to radius={} vertical={} after message '{}'",
                            horizontalRadius + radiusBoost, verticalRange + verticalBoost, lastMessage);
                }
            }
        }

        if (collected == 0) {
            LOGGER.warn("collect_dirt finished with no success after {} attempts (runtime {} ms)",
                    attempt, System.currentTimeMillis() - startTime);
            return SkillExecutionResult.failure(lastMessage.isEmpty() ? "Unable to collect any dirt." : lastMessage);
        }

        String summary = "Collected " + collected + " dirt block" + (collected == 1 ? "" : "s")
                + (collected < targetCount ? " before running out of reachable dirt." : ".");
        LOGGER.info("collect_dirt completed: {} (runtime {} ms, attempts {}, failures in a row {})",
                summary, System.currentTimeMillis() - startTime, attempt, failuresInRow);
        return SkillExecutionResult.success(summary);
    }

    private boolean shouldExpandSearch(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no dirt block detected")
                || normalized.contains("no safe approach")
                || normalized.contains("failed to reach dirt block")
                || normalized.contains("too far from dirt block");
    }

    private boolean isUnreachableFailure(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("no safe approach")
                || normalized.contains("too far from dirt block");
    }

    private BlockPos getPendingTarget(Map<String, Object> sharedState) {
        if (sharedState == null) {
            return null;
        }
        Object xObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.x");
        Object yObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.y");
        Object zObj = SharedStateUtils.getValue(sharedState, "pendingDirtTarget.z");
        if (xObj instanceof Number x && yObj instanceof Number y && zObj instanceof Number z) {
            return new BlockPos(x.intValue(), y.intValue(), z.intValue());
        }
        return null;
    }

    private void moveTowardLoot(ServerCommandSource source, Map<String, Object> sharedState) {
        if (sharedState == null) {
            return;
        }
        Object xObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.x");
        Object yObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.y");
        Object zObj = SharedStateUtils.getValue(sharedState, "lastShoveledBlock.z");
        if (!(xObj instanceof Number xNum) || !(yObj instanceof Number yNum) || !(zObj instanceof Number zNum)) {
            return;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return;
        }

        BlockPos lootPos = new BlockPos(xNum.intValue(), yNum.intValue(), zNum.intValue());
        BlockPos destination = findLootDestination(player, lootPos);
        ServerWorld world = source.getWorld();

        ItemEntity nearestDrop = findNearestDrop(world, player, lootPos);
        if (nearestDrop != null) {
            destination = findLootDestination(player, nearestDrop.getBlockPos());
        }

        if (destination == null) {
            LOGGER.info("collect_dirt loot pickup skipped: no safe destination near {}", lootPos);
            return;
        }
        if (player.getBlockPos().equals(destination)) {
            LOGGER.info("collect_dirt loot pickup skipped: already at {}", destination);
            return;
        }

        String result = GoTo.goTo(source, destination.getX(), destination.getY(), destination.getZ(), false);
        LOGGER.info("collect_dirt loot pickup navigation result: {}", result);
    }

    private ItemEntity findNearestDrop(ServerWorld world, ServerPlayerEntity player, BlockPos center) {
        Box search = Box.enclosing(center, center).expand(2.5, 1.5, 2.5);
        ItemEntity closest = null;
        double bestDistance = Double.MAX_VALUE;

        for (ItemEntity entity : world.getEntitiesByClass(ItemEntity.class, search, item -> !item.isRemoved())) {
            double distance = player.squaredDistanceTo(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = entity;
            }
        }
        return closest;
    }

    private BlockPos findLootDestination(ServerPlayerEntity player, BlockPos lootPos) {
        BlockPos origin = player.getBlockPos();
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos foot = lootPos.offset(direction);
            BlockPos head = foot.up();
            if (!isStandable(player, foot, head)) {
                continue;
            }
            double distance = origin.getSquaredDistance(head);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPos = head;
            }
        }

        if (bestPos == null && lootPos.getY() >= origin.getY()) {
            BlockPos footAbove = lootPos;
            BlockPos headAbove = lootPos.up();
            if (isStandable(player, footAbove, headAbove)) {
                bestPos = headAbove;
            }
        }
        return bestPos;
    }

    private boolean isStandable(ServerPlayerEntity player, BlockPos foot, BlockPos head) {
        return !player.getEntityWorld().getBlockState(foot).getCollisionShape(player.getEntityWorld(), foot).isEmpty()
                && player.getEntityWorld().getBlockState(head).getCollisionShape(player.getEntityWorld(), head).isEmpty();
    }

    private int getIntParameter(SkillContext context, String key, int defaultValue) {
        Map<String, Object> params = context.parameters();
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
}
