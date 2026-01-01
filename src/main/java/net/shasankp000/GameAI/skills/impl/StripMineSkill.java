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
import net.shasankp000.GameAI.skills.support.FallingBlockStabilizer;
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
            ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), 
                    "Returning to mining position...");
            
            // Navigate back to pause position
            if (!moveTo(source, player, resumeTarget)) {
                LOGGER.warn("Failed to return to pause position {}", resumeTarget.toShortString());
                ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), 
                        "Couldn't return to exact position, continuing from here.");
            }
            WorkDirectionService.clearPausePosition(player.getUuid());
        }
        
        int length = Math.max(1, getIntParameter(context, "count", DEFAULT_LENGTH));
        int completed = 0;

        Direction tunnelDirection = determineTunnelDirection(context, player, resumeRequested);
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
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), hazard.chatMessage()));
            if (detection.blockingHazard().isPresent()) {
                Hazard hazard = detection.blockingHazard().get();
                // Store current position for resume
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                SkillResumeService.flagManualResume(player);
                ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), hazard.chatMessage());
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
                    WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                    SkillResumeService.flagManualResume(player);
                    String blockName = player.getEntityWorld().getBlockState(block).getBlock().getName().getString();
                    return SkillExecutionResult.failure("Stripmine paused: unable to clear " + blockName + " at " + block.toShortString() + ". Use /bot resume.");
                }
            }

            // Falling blocks (sand/gravel/etc.) can refill the corridor after mining.
            // Mirror descent safety: wait for settling, then re-clear falling refills before stepping forward.
            if (!FallingBlockStabilizer.stabilizeAndReclear(
                    player,
                    source,
                    footTarget,
                    workVolume,
                    this::isPassableForTunnel,
                    StripMineSkill::isTorchBlock,
                    pos -> mineBlock(player, pos),
                    true
            )) {
                WorkDirectionService.setPausePosition(player.getUuid(), player.getBlockPos());
                SkillResumeService.flagManualResume(player);
                return SkillExecutionResult.failure("Stripmine paused: falling blocks won't settle. Use /bot resume.");
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
                    ChatUtils.sendChatMessages(player.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), 
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
        
        if (!(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
            return false;
        }

        // If already passable, treat as cleared.
        if (isPassableForTunnel(world, blockPos)) {
            return true;
        }

        // Mirror the stair miner: retry transient failures/timeouts instead of hard-aborting.
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (SkillManager.shouldAbortSkill(player)) {
                return false;
            }

            if (!isWithinReach(player, blockPos)) {
                // Nudge closer (common if we got slightly misaligned in a tight tunnel).
                runOnServerThread(player, () -> {
                    LookController.faceBlock(player, blockPos);
                    BotActions.moveForwardStep(player, 0.65);
                    if (blockPos.getY() > player.getBlockY()) {
                        BotActions.jump(player);
                    }
                });
                try {
                    Thread.sleep(140L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (!isWithinReach(player, blockPos)) {
                    Vec3d target = Vec3d.ofCenter(blockPos);
                    double distance = Math.sqrt(player.squaredDistanceTo(target.x, target.y, target.z));
                    LOGGER.warn("Block {} is out of reach for {} (distance {}).", blockPos, player.getName().getString(),
                            String.format("%.2f", distance));
                    return false;
                }
            }

            LookController.faceBlock(player, blockPos);
            CompletableFuture<String> future = MiningTool.mineBlock(player, blockPos);
            try {
                // MiningTool has its own failsafe; wait long enough for slow tools.
                future.get(13, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                LOGGER.warn("Stripmine mining attempt {}/3 timed out waiting at {} (state={})",
                        attempt,
                        blockPos.toShortString(),
                        world.getBlockState(blockPos).getBlock().getName().getString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                LOGGER.warn("Stripmine mining attempt {}/3 failed at {}: {}", attempt, blockPos.toShortString(), e.getMessage());
            }

            if (isPassableForTunnel(world, blockPos)) {
                return true;
            }

            try {
                Thread.sleep(160L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        LOGGER.warn("Stripmine couldn't clear {} after multiple attempts (state={})",
                blockPos.toShortString(),
                world.getBlockState(blockPos).getBlock().getName().getString());
        return false;
    }

    private boolean isWithinReach(ServerPlayerEntity player, BlockPos blockPos) {
        if (player == null || blockPos == null) {
            return false;
        }
        Vec3d target = Vec3d.ofCenter(blockPos);
        return player.squaredDistanceTo(target.x, target.y, target.z) <= 25.0D;
    }

    private boolean isPassableForTunnel(net.minecraft.server.world.ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isTorchBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        return block == Blocks.TORCH
                || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH
                || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH;
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

    private Direction determineTunnelDirection(SkillContext context, ServerPlayerEntity player, boolean resumeRequested) {
        boolean lockDirection = getBooleanParameter(context, "lockDirection", false);

        Direction explicit = null;
        Object directionParam = context.parameters().get("direction");
        if (directionParam == null) {
            directionParam = context.parameters().get("issuerFacing");
        }
        if (directionParam instanceof Direction dir) {
            explicit = dir;
        } else if (directionParam instanceof String dirStr) {
            explicit = parseDirection(dirStr);
        }

        Optional<Direction> stored = WorkDirectionService.getDirection(player.getUuid());
        Direction workDirection;

        if (resumeRequested) {
            workDirection = stored.orElse(explicit);
        } else if (lockDirection && stored.isPresent()) {
            workDirection = stored.get();
        } else if (explicit != null) {
            workDirection = explicit;
        } else {
            Direction buttonDir = scanForButtonDirection(player);
            workDirection = buttonDir != null ? buttonDir : player.getHorizontalFacing();
        }
        if (workDirection == null) {
            workDirection = player.getHorizontalFacing();
        }

        // Re-orient bot immediately to face workDirection before starting (map horizontal direction to yaw)
        Object issuerYawObj = context.parameters().get("issuerYaw");
        Float issuerYaw = null;
        if (issuerYawObj instanceof Number n) issuerYaw = n.floatValue();
        float yaw = issuerYaw != null ? issuerYaw : switch (workDirection) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> player.getYaw();
        };
        player.setYaw(yaw);
        player.setHeadYaw(yaw);

        WorkDirectionService.setDirection(player.getUuid(), workDirection);
        LOGGER.info("Using work direction {} for bot {} (lockDirection={}, resumeRequested={})",
                workDirection.asString(), player.getName().getString(), lockDirection, resumeRequested);
        return workDirection;
    }

    private boolean getBooleanParameter(SkillContext context, String key, boolean defaultValue) {
        Object value = context.parameters().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return defaultValue;
    }

    private Direction scanForButtonDirection(ServerPlayerEntity player) {
        if (player == null) return null;
        net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) player.getEntityWorld();
        BlockPos origin = player.getBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    Block block = state.getBlock();
                    if (isButtonBlock(block)) {
                        Direction facing = null;
                        if (state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                            facing = state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
                        } else {
                            Vec3d to = Vec3d.ofCenter(pos).subtract(player.getX(), player.getY(), player.getZ());
                            facing = horizontalFromVector(to);
                        }
                        if (facing != null) return facing;
                    }
                }
            }
        }
        return null;
    }

    private boolean isButtonBlock(Block block) {
        return block == Blocks.STONE_BUTTON || block == Blocks.OAK_BUTTON || block == Blocks.BIRCH_BUTTON ||
               block == Blocks.SPRUCE_BUTTON || block == Blocks.JUNGLE_BUTTON || block == Blocks.DARK_OAK_BUTTON ||
               block == Blocks.ACACIA_BUTTON || block == Blocks.CHERRY_BUTTON || block == Blocks.MANGROVE_BUTTON ||
               block == Blocks.CRIMSON_BUTTON || block == Blocks.WARPED_BUTTON || block == Blocks.POLISHED_BLACKSTONE_BUTTON;
    }

    private Direction horizontalFromVector(Vec3d v) {
        if (v == null) return null;
        double ax = Math.abs(v.x); double az = Math.abs(v.z);
        if (ax >= az) {
            return v.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return v.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
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
