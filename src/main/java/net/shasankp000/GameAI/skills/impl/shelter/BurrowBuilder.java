package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.StripMineSkill;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.GameAI.services.WorkDirectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BurrowBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final double REACH_DISTANCE_SQ = 20.25D;

    public SkillExecutionResult build(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos start) {
        // Require at least 2 torches before starting; avoid mid-run pauses.
        int torches = countTorches(bot);
        if (torches < 2) {
            String msg = "Burrow needs at least 2 torches; provide some and rerun.";
            ChatUtils.sendSystemMessage(source, msg);
            LOGGER.warn("Burrow aborted: {}", msg);
            return SkillExecutionResult.failure(msg);
        }

        // Phase 1: descend 5 blocks using the standard stair descent.
        Direction burrowDir = WorkDirectionService.getDirection(bot.getUuid()).orElse(bot.getHorizontalFacing());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        Map<String, Object> sharedState = new HashMap<>();
        CollectDirtSkill collect = new CollectDirtSkill();
        Map<String, Object> params = new HashMap<>();
        params.put("descentBlocks", 5);
        params.put("issuerFacing", burrowDir.asString());
        params.put("lockDirection", true);
        SkillContext descentCtx = new SkillContext(source, sharedState, params);
        SkillExecutionResult descentResult = collect.execute(descentCtx);
        if (!descentResult.success()) {
            LOGGER.warn("Burrow descent(6) failed: {}", descentResult.message());
            ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult.message());
            return descentResult;
        }

        // Phase 2: stripmine forward 4 blocks at this level to create entry throat.
        LOGGER.info("Burrow stripmine throat: direction={} length=4 start={}", burrowDir, bot.getBlockPos().toShortString());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        StripMineSkill strip = new StripMineSkill();
        Map<String, Object> stripParams = new HashMap<>();
        stripParams.put("count", 4);
        stripParams.put("issuerFacing", burrowDir.asString());
        stripParams.put("lockDirection", true);
        SkillExecutionResult stripResult = strip.execute(new SkillContext(source, sharedState, stripParams));
        if (!stripResult.success()) {
            LOGGER.warn("Burrow stripmine throat failed: {}", stripResult.message());
            return stripResult;
        }

        // Phase 3: descend final 3 blocks to the chamber depth.
        Map<String, Object> params2 = new HashMap<>();
        params2.put("descentBlocks", 3);
        params2.put("issuerFacing", burrowDir.asString());
        params2.put("lockDirection", true);
        SkillContext descentCtx2 = new SkillContext(source, sharedState, params2);
        LOGGER.info("Burrow final descent: 3 blocks from {}", bot.getBlockPos().toShortString());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);
        SkillExecutionResult descentResult2 = collect.execute(descentCtx2);
        if (!descentResult2.success()) {
            LOGGER.warn("Burrow descent(3) failed: {}", descentResult2.message());
            ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult2.message());
            return descentResult2;
        }

        BlockPos chamberCenter = bot.getBlockPos();
        Direction ascentDir = resolveAscentDirection(start, chamberCenter);
        Set<BlockPos> protectedSteps = computeProtectedSteps(chamberCenter, ascentDir, 5);
        int radius = 3;
        int height = 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = chamberCenter.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (protectedSteps.contains(pos)) {
                        continue; // keep the stair spine intact
                    }
                    if (!state.isAir()) {
                        mineSoft(bot, pos);
                    }
                }
            }
        }
        List<BlockPos> torchPositions = new ArrayList<>();
        torchPositions.add(chamberCenter.add(radius, 0, radius));
        torchPositions.add(chamberCenter.add(-radius, 0, -radius));
        torchPositions.add(chamberCenter.add(radius, 0, -radius));
        torchPositions.add(chamberCenter.add(-radius, 0, radius));
        for (BlockPos pos : torchPositions) {
            if (world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) && world.getBlockState(pos).isAir()) {
                BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(Items.TORCH));
            }
        }
        smoothStairExit(world, bot, chamberCenter, ascentDir, 6);
        return SkillExecutionResult.success("Burrow finished.");
    }

    private int countTorches(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(Items.TORCH)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (bot.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > REACH_DISTANCE_SQ) return;
        try { MiningTool.mineBlock(bot, pos).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private Direction resolveAscentDirection(BlockPos surface, BlockPos chamber) {
        int dx = surface.getX() - chamber.getX();
        int dz = surface.getZ() - chamber.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private Set<BlockPos> computeProtectedSteps(BlockPos chamberCenter, Direction ascentDir, int count) {
        Set<BlockPos> protectedSteps = new HashSet<>();
        BlockPos current = chamberCenter;
        Direction reverse = ascentDir.getOpposite();
        for (int i = 0; i < count; i++) {
            protectedSteps.add(current);
            protectedSteps.add(current.up());
            current = current.offset(reverse);
        }
        return protectedSteps;
    }

    private void smoothStairExit(ServerWorld world, ServerPlayerEntity bot, BlockPos chamberCenter, Direction ascentDir, int steps) {
        BlockPos current = chamberCenter;
        Direction reverse = ascentDir.getOpposite();
        for (int i = 0; i < steps; i++) {
            BlockPos head = current.up(2);
            if (!world.getBlockState(head).isAir()) mineSoft(bot, head);
            current = current.offset(reverse).up();
        }
    }

    private void faceDirection(ServerPlayerEntity bot, Direction dir) {
        float yaw = switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> bot.getYaw();
        };
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}