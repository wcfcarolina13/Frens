package net.shasankp000.GameAI.skills.impl.shelter;

import net.minecraft.block.BlockState;
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
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.GameAI.services.WorkDirectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BurrowBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final double REACH_DISTANCE_SQ = 20.25D;

    private static final int PHASE_DESCENT_1 = 1;
    private static final int PHASE_THROAT_STRIP = 2;
    private static final int PHASE_DESCENT_2 = 3;
    private static final int PHASE_HOLLOW = 4;

    public SkillExecutionResult build(SkillContext parentContext, ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos start) {
        Map<String, Object> sharedState = parentContext != null && parentContext.sharedState() != null
                ? parentContext.sharedState()
                : new HashMap<>();

        String prefix = "burrow." + bot.getUuid() + ".";
        int phase = getInt(sharedState, prefix + "phase", PHASE_DESCENT_1);

        // Persist the surface origin so resumes don't re-base descent targets from the bot's new position.
        BlockPos surfaceOrigin = readBlockPos(sharedState, prefix + "origin", start);
        if (surfaceOrigin == null) {
            surfaceOrigin = start;
        }
        writeBlockPos(sharedState, prefix + "origin", surfaceOrigin);

        // Require at least 2 torches before starting; avoid mid-run pauses.
        // Only enforce this before the job begins so a resume doesn't get blocked due to torch usage.
        if (phase == PHASE_DESCENT_1) {
            int torches = countTorches(bot);
            if (torches < 2) {
                String msg = "Burrow needs at least 2 torches; provide some and rerun.";
                ChatUtils.sendSystemMessage(source, msg);
                LOGGER.warn("Burrow aborted: {}", msg);
                return SkillExecutionResult.failure(msg);
            }
        }

        Direction burrowDir = WorkDirectionService.getDirection(bot.getUuid()).orElse(bot.getHorizontalFacing());
        WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
        faceDirection(bot, burrowDir);

        CollectDirtSkill collect = new CollectDirtSkill();

        // Phase 1: descend 5 blocks from the original surface origin.
        if (phase <= PHASE_DESCENT_1) {
            int targetY = getInt(sharedState, prefix + "phase1.targetY", surfaceOrigin.getY() - 5);
            sharedState.put(prefix + "phase1.targetY", targetY);
            Map<String, Object> params = new HashMap<>();
            params.put("descentTargetY", targetY);
            params.put("issuerFacing", burrowDir.asString());
            params.put("lockDirection", true);
            SkillExecutionResult descentResult = collect.execute(new SkillContext(source, sharedState, params));
            if (!descentResult.success()) {
                LOGGER.warn("Burrow descent(5) failed: {}", descentResult.message());
                ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult.message());
                sharedState.put(prefix + "phase", PHASE_DESCENT_1);
                return descentResult;
            }
            sharedState.put(prefix + "phase", PHASE_THROAT_STRIP);
            phase = PHASE_THROAT_STRIP;
        }

        // Phase 2: stripmine forward 4 blocks at this level to create entry throat.
        if (phase <= PHASE_THROAT_STRIP) {
            final int throatLength = 4;
            sharedState.put(prefix + "phase2.length", throatLength);
            BlockPos throatStart = readBlockPos(sharedState, prefix + "phase2.start", bot.getBlockPos());
            if (throatStart == null) {
                throatStart = bot.getBlockPos();
            }
            writeBlockPos(sharedState, prefix + "phase2.start", throatStart);

            int progressed = computeProgressAlong(throatStart, bot.getBlockPos(), burrowDir);
            int remaining = Math.max(0, throatLength - progressed);

            if (remaining > 0) {
                LOGGER.info("Burrow stripmine throat: direction={} remaining={} start={} current={}",
                        burrowDir,
                        remaining,
                        throatStart.toShortString(),
                        bot.getBlockPos().toShortString());
                WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
                faceDirection(bot, burrowDir);
                StripMineSkill strip = new StripMineSkill();
                Map<String, Object> stripParams = new HashMap<>();
                stripParams.put("count", remaining);
                stripParams.put("issuerFacing", burrowDir.asString());
                stripParams.put("lockDirection", true);
                SkillExecutionResult stripResult = strip.execute(new SkillContext(source, sharedState, stripParams));
                if (!stripResult.success()) {
                    LOGGER.warn("Burrow stripmine throat failed: {}", stripResult.message());
                    ChatUtils.sendSystemMessage(source, "Burrow paused: " + stripResult.message());
                    sharedState.put(prefix + "phase", PHASE_THROAT_STRIP);
                    return stripResult;
                }
            }

            sharedState.put(prefix + "phase", PHASE_DESCENT_2);
            phase = PHASE_DESCENT_2;
        }

        // Phase 3: descend final 3 blocks to the chamber depth (absolute target persisted on entry).
        if (phase <= PHASE_DESCENT_2) {
            int targetY = getInt(sharedState, prefix + "phase3.targetY", bot.getBlockY() - 3);
            sharedState.put(prefix + "phase3.targetY", targetY);
            Map<String, Object> params2 = new HashMap<>();
            params2.put("descentTargetY", targetY);
            params2.put("issuerFacing", burrowDir.asString());
            params2.put("lockDirection", true);
            LOGGER.info("Burrow final descent: targetY={} from {}", targetY, bot.getBlockPos().toShortString());
            WorkDirectionService.setDirection(bot.getUuid(), burrowDir);
            faceDirection(bot, burrowDir);
            SkillExecutionResult descentResult2 = collect.execute(new SkillContext(source, sharedState, params2));
            if (!descentResult2.success()) {
                LOGGER.warn("Burrow descent(3) failed: {}", descentResult2.message());
                ChatUtils.sendSystemMessage(source, "Burrow paused: " + descentResult2.message());
                sharedState.put(prefix + "phase", PHASE_DESCENT_2);
                return descentResult2;
            }
            sharedState.put(prefix + "phase", PHASE_HOLLOW);
            phase = PHASE_HOLLOW;
        }

        // Phase 4: hollow the chamber.
        BlockPos chamberCenter = bot.getBlockPos();
        Direction ascentDir = resolveAscentDirection(surfaceOrigin, chamberCenter);
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

        // Cleanup state so subsequent burrows start fresh.
        sharedState.remove(prefix + "phase");
        sharedState.remove(prefix + "origin.x");
        sharedState.remove(prefix + "origin.y");
        sharedState.remove(prefix + "origin.z");
        sharedState.remove(prefix + "phase1.targetY");
        sharedState.remove(prefix + "phase2.start.x");
        sharedState.remove(prefix + "phase2.start.y");
        sharedState.remove(prefix + "phase2.start.z");
        sharedState.remove(prefix + "phase2.length");
        sharedState.remove(prefix + "phase3.targetY");

        return SkillExecutionResult.success("Burrow finished.");
    }

    private int computeProgressAlong(BlockPos start, BlockPos current, Direction dir) {
        if (start == null || current == null || dir == null) {
            return 0;
        }
        return switch (dir) {
            case EAST -> Math.max(0, current.getX() - start.getX());
            case WEST -> Math.max(0, start.getX() - current.getX());
            case SOUTH -> Math.max(0, current.getZ() - start.getZ());
            case NORTH -> Math.max(0, start.getZ() - current.getZ());
            default -> 0;
        };
    }

    private int getInt(Map<String, Object> state, String key, int defaultValue) {
        if (state == null || key == null) {
            return defaultValue;
        }
        Object value = state.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private BlockPos readBlockPos(Map<String, Object> state, String keyPrefix, BlockPos fallback) {
        if (state == null || keyPrefix == null) {
            return fallback;
        }
        Object x = state.get(keyPrefix + ".x");
        Object y = state.get(keyPrefix + ".y");
        Object z = state.get(keyPrefix + ".z");
        if (x instanceof Number xn && y instanceof Number yn && z instanceof Number zn) {
            return new BlockPos(xn.intValue(), yn.intValue(), zn.intValue());
        }
        return fallback;
    }

    private void writeBlockPos(Map<String, Object> state, String keyPrefix, BlockPos pos) {
        if (state == null || keyPrefix == null || pos == null) {
            return;
        }
        state.put(keyPrefix + ".x", pos.getX());
        state.put(keyPrefix + ".y", pos.getY());
        state.put(keyPrefix + ".z", pos.getZ());
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