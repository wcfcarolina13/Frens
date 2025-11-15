package net.shasankp000.GameAI.skills.support;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Centralised hazard detection for mining-style skills. Scans the immediate work
 * area for dangerous drops, liquids, structural blocks, chests, and valuable ores.
 */
public final class MiningHazardDetector {

    private static final Map<Block, String> VALUABLE_MESSAGES = Map.ofEntries(
            Map.entry(Blocks.DIAMOND_ORE, "I found diamonds!"),
            Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, "I found diamonds!"),
            Map.entry(Blocks.ANCIENT_DEBRIS, "I found ancient debris!"),
            Map.entry(Blocks.EMERALD_ORE, "I found emeralds!"),
            Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, "I found emeralds!"),
            Map.entry(Blocks.GOLD_ORE, "I found gold!"),
            Map.entry(Blocks.DEEPSLATE_GOLD_ORE, "I found gold!"),
            Map.entry(Blocks.NETHER_GOLD_ORE, "I found gold!"),
            Map.entry(Blocks.REDSTONE_ORE, "I found redstone!"),
            Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, "I found redstone!"),
            Map.entry(Blocks.LAPIS_ORE, "I found lapis!"),
            Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, "I found lapis!"),
            Map.entry(Blocks.COAL_ORE, "I found coal!"),
            Map.entry(Blocks.DEEPSLATE_COAL_ORE, "I found coal!"),
            Map.entry(Blocks.NETHER_QUARTZ_ORE, "I found quartz!")
    );

    private static final Set<Block> STRUCTURE_BLOCKS = Set.of(
            Blocks.SPAWNER,
            Blocks.OAK_PLANKS,
            Blocks.OAK_FENCE,
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.DETECTOR_RAIL,
            Blocks.ACTIVATOR_RAIL
    );

    private static final Set<Block> CHEST_BLOCKS = Set.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL
    );

    private static final int DROP_SCAN_DEPTH = 4;

    private MiningHazardDetector() {
    }

    public static Optional<Hazard> detect(ServerPlayerEntity bot,
                                          List<BlockPos> breakTargets,
                                          List<BlockPos> stepTargets) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        List<BlockPos> inspection = breakTargets == null ? List.of() : breakTargets;
        for (BlockPos target : inspection) {
            if (target == null) {
                continue;
            }
            Hazard hazard = inspectBlock(world, target);
            if (hazard != null) {
                return Optional.of(hazard);
            }
        }
        List<BlockPos> steps = stepTargets == null ? List.of() : stepTargets;
        for (BlockPos foot : steps) {
            if (foot == null) {
                continue;
            }
            if (isDangerousDrop(world, foot)) {
                return Optional.of(hazard("That's a big drop."));
            }
        }
        return Optional.empty();
    }

    private static Hazard inspectBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        FluidState fluid = world.getFluidState(pos);
        if (fluid.isIn(FluidTags.LAVA)) {
            return hazard("There's lava ahead.");
        }
        if (fluid.isIn(FluidTags.WATER)) {
            return hazard("There's water ahead.");
        }
        Block block = state.getBlock();
        String precious = VALUABLE_MESSAGES.get(block);
        if (precious != null) {
            return hazard(precious);
        }
        if (CHEST_BLOCKS.contains(block)) {
            return hazard("I found a chest!");
        }
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) {
            return hazard("I found a chest!");
        }
        if (STRUCTURE_BLOCKS.contains(block)) {
            return hazard("I found a structure.");
        }
        return null;
    }

    private static boolean isDangerousDrop(ServerWorld world, BlockPos foot) {
        BlockPos cursor = foot.down();
        for (int depth = 0; depth < DROP_SCAN_DEPTH; depth++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.getCollisionShape(world, cursor).isEmpty()) {
                return false;
            }
            cursor = cursor.down();
        }
        return true;
    }

    private static Hazard hazard(String chat) {
        return new Hazard(chat, "Hazard: " + chat);
    }

    public static record Hazard(String chatMessage, String failureMessage) {
    }
}
