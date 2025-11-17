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
import net.shasankp000.GameAI.services.ProtectedZoneService;

import java.util.ArrayList;
import java.util.HashMap;
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

    private static final Map<Block, Block> DEEPSLATE_VARIANTS = Map.ofEntries(
            Map.entry(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
            Map.entry(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
            Map.entry(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
            Map.entry(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            Map.entry(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
            Map.entry(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            Map.entry(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)
    );

    private static final Map<Block, String> VALUABLE_MESSAGES = buildValuableMessages();

    private static Map<Block, String> buildValuableMessages() {
        Map<Block, String> values = new HashMap<>();
        registerRare(values, "I found diamonds!", Blocks.DIAMOND_ORE);
        values.put(Blocks.ANCIENT_DEBRIS, "I found ancient debris!");
        registerRare(values, "I found emeralds!", Blocks.EMERALD_ORE);
        registerRare(values, "I found gold!", Blocks.GOLD_ORE);
        values.put(Blocks.NETHER_GOLD_ORE, "I found gold!");
        registerRare(values, "I found redstone!", Blocks.REDSTONE_ORE);
        registerRare(values, "I found lapis!", Blocks.LAPIS_ORE);
        registerRare(values, "I found coal!", Blocks.COAL_ORE);
        registerRare(values, "I found iron!", Blocks.IRON_ORE);
        values.put(Blocks.NETHER_QUARTZ_ORE, "I found quartz!");
        return Map.copyOf(values);
    }

    private static void registerRare(Map<Block, String> sink, String message, Block baseOre) {
        if (sink == null || baseOre == null) {
            return;
        }
        sink.put(baseOre, message);
        Block deepslate = DEEPSLATE_VARIANTS.get(baseOre);
        if (deepslate != null) {
            sink.put(deepslate, message);
        }
    }

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
    private static final Map<UUID, Set<BlockPos>> DISCOVERED_RARES = new ConcurrentHashMap<>();
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
        
        // Check if position is in a protected zone
        if (ProtectedZoneService.isProtected(pos, world, null)) {
            ProtectedZoneService.ProtectedZone zone = ProtectedZoneService.getZoneAt(pos, world);
            String zoneName = zone != null ? zone.getLabel() : "protected area";
            return hazard(pos, "This is a protected zone (" + zoneName + ").", true, 
                    "Cannot break blocks in protected zone: " + zoneName);
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
            // Ores should pause the job for player inspection
            return hazard(pos, precious, true, "Mining paused: " + precious);
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
        return hazard(pos, chat, blocking, null);
    }

    private static Hazard hazard(BlockPos pos, String chat, boolean blocking, String failureOverride) {
        BlockPos immutable = pos != null ? pos.toImmutable() : null;
        String failure = blocking
                ? (failureOverride != null ? failureOverride : "Hazard: " + chat)
                : null;
        return new Hazard(chat, failure, immutable, blocking);
    }

    public static void clear(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        ACKNOWLEDGED_BLOCKERS.remove(uuid);
        // DON'T clear DISCOVERED_RARES - those persist across pauses within the same job
        WARNED_HAZARDS.remove(uuid);
        LAST_WARNING_TS.remove(uuid);
    }
    
    /**
     * Clears ALL state for a bot, including discovered rares.
     * Should only be called when a job is completely stopped or finished.
     */
    public static void clearAll(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        ACKNOWLEDGED_BLOCKERS.remove(uuid);
        DISCOVERED_RARES.remove(uuid);
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
            // Only announce ores if they have at least one exposed face (adjacent to air)
            if (!hasExposedFace(world, neighbor)) {
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
        
        // Check if this rare was already discovered this job session
        Set<BlockPos> rares = DISCOVERED_RARES.get(uuid);
        if (rares != null && rares.contains(pos)) {
            return false; // Already reported this rare
        }
        
        long now = System.currentTimeMillis();
        Long last = LAST_WARNING_TS.get(uuid);
        if (last != null && now - last < WARNING_COOLDOWN_MS) {
            return false;
        }
        Set<BlockPos> warned = WARNED_HAZARDS.computeIfAbsent(uuid, id -> new CopyOnWriteArraySet<>());
        if (!warned.add(pos.toImmutable())) {
            return false;
        }
        
        // Mark as discovered for this job session
        DISCOVERED_RARES.computeIfAbsent(uuid, id -> new CopyOnWriteArraySet<>())
                .add(pos.toImmutable());
        
        LAST_WARNING_TS.put(uuid, now);
        return true;
    }

    /**
     * Checks if a block has at least one face exposed to air.
     * Used to avoid announcing ores that are completely buried.
     */
    private static boolean hasExposedFace(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        // Check all 6 directions - if any adjacent block is air, the ore is exposed
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = pos.offset(dir);
            if (world.getBlockState(adjacent).isAir()) {
                return true;
            }
        }
        return false;
    }

    public static record Hazard(String chatMessage, String failureMessage, BlockPos location, boolean blocking) {
    }

    public static record DetectionResult(Optional<Hazard> blockingHazard, List<Hazard> adjacentWarnings) {
    }
}
