package net.shasankp000.GameAI.skills.support;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
            Map.entry(Blocks.IRON_ORE, "I found iron!"),
            Map.entry(Blocks.DEEPSLATE_IRON_ORE, "I found iron!"),
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

    private static final Set<Block> GEODE_BLOCKS = Set.of(
            Blocks.AMETHYST_BLOCK,
            Blocks.BUDDING_AMETHYST,
            Blocks.AMETHYST_CLUSTER,
            Blocks.LARGE_AMETHYST_BUD,
            Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.SMALL_AMETHYST_BUD,
            Blocks.CALCITE
    );

    private static final int DROP_SCAN_DEPTH = 4;
    private static final long WARNING_COOLDOWN_MS = 1_500L;

    private static final Map<UUID, Set<BlockPos>> ACKNOWLEDGED_BLOCKERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<BlockPos>> WARNED_HAZARDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_WARNING_TS = new ConcurrentHashMap<>();
    private static final DetectionResult NO_HAZARDS = new DetectionResult(Optional.empty(), List.of());

    private MiningHazardDetector() {
    }

    public static DetectionResult detect(ServerPlayerEntity bot,
                                         List<BlockPos> breakTargets,
                                         List<BlockPos> stepTargets) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return NO_HAZARDS;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return NO_HAZARDS;
        }
        if (server.isOnThread()) {
            return detectInternal(bot, world, breakTargets, stepTargets);
        }
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(detectInternal(bot, world, breakTargets, stepTargets));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(200, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return NO_HAZARDS;
        } catch (Exception e) {
            return NO_HAZARDS;
        }
    }

    private static DetectionResult detectInternal(ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  List<BlockPos> breakTargets,
                                                  List<BlockPos> stepTargets) {
        List<BlockPos> inspection = breakTargets == null ? List.of() : breakTargets;
        List<BlockPos> steps = stepTargets == null ? List.of() : stepTargets;
        Hazard blocking = null;
        for (BlockPos target : inspection) {
            if (target == null) {
                continue;
            }
            if (isBlockerAcknowledged(bot, target)) {
                continue;
            }
            Hazard found = inspectBlock(world, target);
            if (found != null) {
                acknowledgeBlocker(bot, target);
                blocking = found;
                break;
            }
        }
        if (blocking == null) {
            for (BlockPos foot : steps) {
                if (foot == null) {
                    continue;
                }
                if (!isBlockerAcknowledged(bot, foot)) {
                    Hazard hazard = inspectBlock(world, foot);
                    if (hazard != null) {
                        acknowledgeBlocker(bot, foot);
                        blocking = hazard;
                        break;
                    }
                }
                if (isDangerousDrop(world, foot)) {
                    acknowledgeBlocker(bot, foot);
                    blocking = hazard(foot, "That's a big drop.", true);
                    break;
                }
            }
        }
        if (blocking != null) {
            return new DetectionResult(Optional.of(blocking), List.of());
        }
        List<Hazard> adjacentWarnings = collectAdjacentHazards(bot, world, inspection, steps);
        if (adjacentWarnings.isEmpty()) {
            return NO_HAZARDS;
        }
        return new DetectionResult(Optional.empty(), adjacentWarnings);
    }

    private static Hazard inspectBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        FluidState fluid = world.getFluidState(pos);
        if (fluid.isIn(FluidTags.LAVA)) {
            return hazard(pos, "There's lava ahead.", true);
        }
        if (fluid.isIn(FluidTags.WATER)) {
            return hazard(pos, "There's water ahead.", true);
        }
        Block block = state.getBlock();
        String precious = VALUABLE_MESSAGES.get(block);
        if (precious != null) {
            return hazard(pos, precious, true);
        }
        if (CHEST_BLOCKS.contains(block)) {
            return hazard(pos, "I found a chest!", true);
        }
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) {
            return hazard(pos, "I found a chest!", true);
        }
        if (GEODE_BLOCKS.contains(block)) {
            return hazard(pos, "I found an amethyst geode!", true);
        }
        if (STRUCTURE_BLOCKS.contains(block)) {
            return hazard(pos, "I found a structure.", true);
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

    private static Hazard hazard(BlockPos pos, String chat, boolean blocking) {
        BlockPos immutable = pos != null ? pos.toImmutable() : null;
        String failure = blocking ? "Hazard: " + chat : null;
        return new Hazard(chat, failure, immutable, blocking);
    }

    public static void clear(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        ACKNOWLEDGED_BLOCKERS.remove(uuid);
        WARNED_HAZARDS.remove(uuid);
        LAST_WARNING_TS.remove(uuid);
    }

    private static boolean isBlockerAcknowledged(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        Set<BlockPos> seen = ACKNOWLEDGED_BLOCKERS.get(bot.getUuid());
        return seen != null && seen.contains(pos);
    }

    private static void acknowledgeBlocker(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return;
        }
        ACKNOWLEDGED_BLOCKERS
                .computeIfAbsent(bot.getUuid(), uuid -> new CopyOnWriteArraySet<>())
                .add(pos.toImmutable());
    }

    private static List<Hazard> collectAdjacentHazards(ServerPlayerEntity bot,
                                                       ServerWorld world,
                                                       List<BlockPos> breakTargets,
                                                       List<BlockPos> stepTargets) {
        if (bot == null || world == null) {
            return List.of();
        }
        Set<BlockPos> primaries = new java.util.HashSet<>();
        if (breakTargets != null) {
            for (BlockPos target : breakTargets) {
                if (target != null) {
                    primaries.add(target.toImmutable());
                }
            }
        }
        if (stepTargets != null) {
            for (BlockPos target : stepTargets) {
                if (target != null) {
                    primaries.add(target.toImmutable());
                }
            }
        }
        if (primaries.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPos> neighbors = new LinkedHashSet<>();
        for (BlockPos primary : primaries) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = primary.offset(dir);
                if (primaries.contains(neighbor)) {
                    continue;
                }
                neighbors.add(neighbor);
            }
        }
        if (neighbors.isEmpty()) {
            return List.of();
        }
        List<Hazard> warnings = new ArrayList<>();
        for (BlockPos neighbor : neighbors) {
            if (isBlockerAcknowledged(bot, neighbor)) {
                continue;
            }
            Hazard hazard = inspectBlock(world, neighbor);
            if (hazard == null) {
                continue;
            }
            if (!registerWarning(bot, neighbor)) {
                continue;
            }
            warnings.add(hazard(neighbor, hazard.chatMessage(), false));
        }
        if (warnings.isEmpty()) {
            return List.of();
        }
        return List.copyOf(warnings);
    }

    private static boolean registerWarning(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        UUID uuid = bot.getUuid();
        long now = System.currentTimeMillis();
        Long last = LAST_WARNING_TS.get(uuid);
        if (last != null && now - last < WARNING_COOLDOWN_MS) {
            return false;
        }
        Set<BlockPos> warned = WARNED_HAZARDS.computeIfAbsent(uuid, id -> new CopyOnWriteArraySet<>());
        if (!warned.add(pos.toImmutable())) {
            return false;
        }
        LAST_WARNING_TS.put(uuid, now);
        return true;
    }

    public static record Hazard(String chatMessage, String failureMessage, BlockPos location, boolean blocking) {
    }

    public static record DetectionResult(Optional<Hazard> blockingHazard, List<Hazard> adjacentWarnings) {
    }
}
