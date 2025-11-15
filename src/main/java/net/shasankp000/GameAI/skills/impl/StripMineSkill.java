package net.shasankp000.GameAI.skills.impl;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector.Hazard;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Carves a straight 1×3 tunnel in front of the bot for the requested distance.
 */
public final class StripMineSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-stripmine");
    private static final int DEFAULT_LENGTH = 8;
    private static final long STEP_DELAY_MS = 120L;
    private static final int STEP_ATTEMPTS = 20;

    @Override
    public String name() {
        return "stripmine";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity player = Objects.requireNonNull(source.getPlayer(), "player");
        net.shasankp000.GameAI.skills.support.MiningHazardDetector.clear(player);
        int length = Math.max(1, getIntParameter(context, "count", DEFAULT_LENGTH));
        int completed = 0;

        Direction tunnelDirection = determineTunnelDirection(context, player);
        Direction finalDirection = tunnelDirection;
        runOnServerThread(player, () -> LookController.faceBlock(player, player.getBlockPos().offset(finalDirection)));

        for (int step = 0; step < length; step++) {
            if (SkillManager.shouldAbortSkill(player)) {
                return SkillExecutionResult.failure("stripmine paused due to cancellation.");
            }
            BlockPos footTarget = player.getBlockPos().offset(tunnelDirection);

            List<BlockPos> workVolume = buildCrossSection(footTarget);
            Optional<Hazard> hazard = MiningHazardDetector.detect(player, workVolume, List.of(footTarget));
            if (hazard.isPresent()) {
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), hazard.get().chatMessage());
                return SkillExecutionResult.failure(hazard.get().failureMessage());
            }

            for (BlockPos block : workVolume) {
                if (!player.getEntityWorld().getBlockState(block).isAir()) {
                    if (!mineBlock(player, block)) {
                        return SkillExecutionResult.failure("Stripmine aborted: unable to clear corridor.");
                    }
                }
            }

            if (!moveTo(source, player, footTarget)) {
                return SkillExecutionResult.failure("Stripmine aborted: failed to advance tunnel.");
            }
            completed++;
        }

        return SkillExecutionResult.success("Stripmine cleared " + completed + " blocks.");
    }

    private List<BlockPos> buildCrossSection(BlockPos forward) {
        List<BlockPos> blocks = new ArrayList<>(3);
        blocks.add(forward);
        blocks.add(forward.up());
        blocks.add(forward.up(2));
        return blocks;
    }

    private boolean moveTo(ServerCommandSource source, ServerPlayerEntity player, BlockPos destination) {
        if (destination == null) {
            return false;
        }
        for (int attempt = 0; attempt < STEP_ATTEMPTS; attempt++) {
            if (player.getBlockPos().equals(destination)) {
                return true;
            }
            runOnServerThread(player, () -> {
                LookController.faceBlock(player, destination);
                if (destination.getY() > player.getBlockY()) {
                    BotActions.jumpForward(player);
                } else {
                    BotActions.moveForward(player);
                }
            });
            try {
                Thread.sleep(STEP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return player.getBlockPos().equals(destination);
    }

    private boolean mineBlock(ServerPlayerEntity player, BlockPos blockPos) {
        if (blockPos == null) {
            return true;
        }
        if (!isWithinReach(player, blockPos)) {
            Vec3d target = Vec3d.ofCenter(blockPos);
            double distance = Math.sqrt(player.squaredDistanceTo(target.x, target.y, target.z));
            LOGGER.warn("Block {} is out of reach for {} (distance {}).", blockPos, player.getName().getString(),
                    String.format("%.2f", distance));
            return false;
        }
        LookController.faceBlock(player, blockPos);
        CompletableFuture<String> future = MiningTool.mineBlock(player, blockPos);
        try {
            String result = future.get(6, TimeUnit.SECONDS);
            if (result == null || !result.toLowerCase().contains("complete")) {
                LOGGER.warn("Mining {} returned unexpected result: {}", blockPos, result);
                return false;
            }
            return player.getEntityWorld().getBlockState(blockPos).isAir();
        } catch (TimeoutException timeout) {
            future.cancel(true);
            LOGGER.warn("Mining {} timed out", blockPos);
        } catch (Exception e) {
            LOGGER.warn("Mining {} failed", blockPos, e);
        }
        return false;
    }

    private boolean isWithinReach(ServerPlayerEntity player, BlockPos blockPos) {
        if (player == null || blockPos == null) {
            return false;
        }
        Vec3d target = Vec3d.ofCenter(blockPos);
        return player.squaredDistanceTo(target.x, target.y, target.z) <= 25.0D;
    }

    private int getIntParameter(SkillContext context, String key, int defaultValue) {
        Object value = context.parameters().get(key);
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

    private Direction determineTunnelDirection(SkillContext context, ServerPlayerEntity player) {
        Direction current = player.getHorizontalFacing();
        Map<String, Object> shared = context.sharedState();
        if (shared != null) {
            String key = "stripmine.direction." + player.getUuid();
            Object stored = shared.get(key);
            if (stored instanceof String str) {
                Direction storedDir = parseDirection(str);
                if (storedDir != null) {
                    current = storedDir;
                }
            }
            shared.put(key, current.asString());
        }
        return current;
    }

    private Direction parseDirection(String name) {
        if (name == null) {
            return null;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (dir.asString().equalsIgnoreCase(name)) {
                return dir;
            }
        }
        return null;
    }

    private void runOnServerThread(ServerPlayerEntity player, Runnable action) {
        if (player == null || action == null) {
            return;
        }
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) {
            action.run();
            return;
        }
        if (server.isOnThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }
}
