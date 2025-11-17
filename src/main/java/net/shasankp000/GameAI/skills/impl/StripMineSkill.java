package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.services.WorkDirectionService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector.Hazard;
import net.shasankp000.GameAI.skills.support.MiningHazardDetector.DetectionResult;
import net.shasankp000.GameAI.skills.support.TorchPlacer;
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
    private static final int TORCH_CHECK_INTERVAL = 8; // Check for torch placement every 8 blocks

    @Override
    public String name() {
        return "stripmine";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity player = Objects.requireNonNull(source.getPlayer(), "player");
        boolean resumeRequested = SkillResumeService.consumeResumeIntent(player.getUuid());
        if (!resumeRequested) {
            net.shasankp000.GameAI.skills.support.MiningHazardDetector.clear(player);
        }
        
        // Check if resuming from a paused position
        Optional<BlockPos> pausePos = WorkDirectionService.getPausePosition(player.getUuid());
        if (resumeRequested && pausePos.isPresent()) {
            BlockPos resumeTarget = pausePos.get();
            LOGGER.info("Stripmine resuming - navigating back to pause position {}", resumeTarget.toShortString());
            ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), 
                    "Returning to mining position...");
            
            // Navigate back to pause position
            if (!moveTo(source, player, resumeTarget)) {
                LOGGER.warn("Failed to return to pause position {}", resumeTarget.toShortString());
                ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), 
                        "Couldn't return to exact position, continuing from here.");
            }
            WorkDirectionService.clearPausePosition(player.getUuid());
        }
        
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
            DetectionResult detection = MiningHazardDetector.detect(player, workVolume, List.of(footTarget));
            if (SkillManager.shouldAbortSkill(player)) {
                return SkillExecutionResult.failure("stripmine paused due to cancellation.");
            }
            detection.adjacentWarnings().forEach(hazard ->
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withMaxLevel(4), hazard.chatMessage()));
            if (detection.blockingHazard().isPresent()) {
                Hazard hazard = detection.blockingHazard().get();
                // Store current position for resume
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withMaxLevel(4), hazard.chatMessage());
                return SkillExecutionResult.failure(hazard.failureMessage());
            }

            for (BlockPos block : workVolume) {
                if (SkillManager.shouldAbortSkill(player)) {
                    return SkillExecutionResult.failure("stripmine paused due to cancellation.");
                }
                BlockState state = player.getEntityWorld().getBlockState(block);
                if (state.isAir()) {
                    continue;
                }
                // Skip torches - extra safety layer
                Block blockType = state.getBlock();
                if (blockType == Blocks.TORCH || blockType == Blocks.WALL_TORCH || 
                    blockType == Blocks.SOUL_TORCH || blockType == Blocks.SOUL_WALL_TORCH ||
                    blockType == Blocks.REDSTONE_TORCH || blockType == Blocks.REDSTONE_WALL_TORCH) {
                    continue;
                }
                if (!mineBlock(player, block)) {
                    return SkillExecutionResult.failure("Stripmine aborted: unable to clear corridor.");
                }
            }

            if (SkillManager.shouldAbortSkill(player)) {
                return SkillExecutionResult.failure("stripmine paused due to cancellation.");
            }
            if (!moveTo(source, player, footTarget)) {
                if (SkillManager.shouldAbortSkill(player)) {
                    return SkillExecutionResult.failure("stripmine paused due to cancellation.");
                }
                return SkillExecutionResult.failure("Stripmine aborted: failed to advance tunnel.");
            }
            completed++;
            
            // Place torch AFTER moving (every 6 blocks)
            if (completed % 6 == 0 && TorchPlacer.shouldPlaceTorch(player)) {
                TorchPlacer.PlacementResult torchResult = TorchPlacer.placeTorch(player, tunnelDirection);
                if (torchResult == TorchPlacer.PlacementResult.NO_TORCHES) {
                    SkillResumeService.flagManualResume(player);
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withMaxLevel(4), 
                            "Ran out of torches!");
                    return SkillExecutionResult.failure("Stripmine paused: out of torches. Provide torches and /bot resume.");
                }
            }
        }

        // Job completed successfully - clear all hazard state including discovered rares
        MiningHazardDetector.clearAll(player);
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
            if (SkillManager.shouldAbortSkill(player)) {
                return false;
            }
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
        
        // Skip torches - don't break the lights we placed
        BlockState state = player.getEntityWorld().getBlockState(blockPos);
        Block block = state.getBlock();
        if (block == Blocks.TORCH || block == Blocks.WALL_TORCH || 
            block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH ||
            block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH) {
            return true; // Skip torches, treat as already cleared
        }
        
        if (SkillManager.shouldAbortSkill(player)) {
            return false;
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
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(6);
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(player)) {
                future.cancel(true);
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            long waitWindow = Math.min(remaining, 200L);
            try {
                String result = future.get(waitWindow, TimeUnit.MILLISECONDS);
                if (result == null || !result.toLowerCase().contains("complete")) {
                    LOGGER.warn("Mining {} returned unexpected result: {}", blockPos, result);
                    return false;
                }
                if (!player.getEntityWorld().getBlockState(blockPos).isAir()) {
                    LOGGER.warn("{} still present after mining attempt", blockPos);
                    return false;
                }
                return true;
            } catch (TimeoutException timeout) {
                // keep waiting while polling for cancellation
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return false;
            } catch (Exception e) {
                LOGGER.warn("Mining {} failed", blockPos, e);
                return false;
            }
        }
        future.cancel(true);
        LOGGER.warn("Mining {} timed out", blockPos);
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
        // Try to get stored work direction first
        Optional<Direction> stored = WorkDirectionService.getDirection(player.getUuid());
        if (stored.isPresent()) {
            LOGGER.info("Using stored work direction {} for bot {}", stored.get().asString(), player.getName().getString());
            return stored.get();
        }
        
        // If no stored direction, capture from command issuer (the player who issued the command)
        // The context should contain the issuer's facing direction when the command was started
        Direction commandIssuerFacing = null;
        Object directionParam = context.parameters().get("direction");
        if (directionParam instanceof Direction dir) {
            commandIssuerFacing = dir;
        } else if (directionParam instanceof String dirStr) {
            commandIssuerFacing = parseDirection(dirStr);
        }
        
        // Fallback to bot's current facing if no direction parameter provided
        Direction workDirection = commandIssuerFacing != null ? commandIssuerFacing : player.getHorizontalFacing();
        
        // Store this direction for future jobs
        WorkDirectionService.setDirection(player.getUuid(), workDirection);
        LOGGER.info("Captured initial work direction {} for bot {}", workDirection.asString(), player.getName().getString());
        
        return workDirection;
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
