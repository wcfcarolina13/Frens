package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Standalone cleanup pass for woodcutting areas.
 *
 * <p>Targets:
 * <ul>
 *   <li>Stray/floating logs (avoiding protected zones & obvious player-built areas)</li>
 *   <li>Leftover scaffold/pillar blocks placed during woodcut attempts (best-effort heuristics)</li>
 * </ul>
 *
 * <p>Also optionally performs a drop sweep at the end.
 */
public final class WoodcutCleanupSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-woodcut-cleanup");

    private static final int DEFAULT_RADIUS = 18;
    private static final int DEFAULT_VERTICAL_RANGE = 12;
    private static final int DEFAULT_MAX_LOGS = 64;
    private static final int DEFAULT_MAX_SCAFFOLD = 48;
    private static final long DEFAULT_DURATION_MS = 45_000L;

    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    private static final long MINING_TIMEOUT_MS = 12_000L;
    private static final int MAX_RETRY_MINING = 4;

    private static final long PILLAR_STEP_DELAY_MS = 160L;

    // Ephemeral memory shared with woodcut. Cleared when any non-woodcut skill starts.
    private static final String WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY = "woodcut.scaffoldMemory.positions";
    private static final String WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY = "woodcut.scaffoldMemory.dimension";
    private static final int WOODCUT_SCAFFOLD_MEMORY_MAX = 2048;

    private static final List<Item> PILLAR_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.SCAFFOLDING,
            Items.GRAVEL,
            Items.SAND,
            Items.RED_SAND,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.NETHERRACK
    );

    private static final Set<Item> DIRT_LIKE_SCAFFOLD = Set.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT
        );

    @Override
    public String name() {
        return "woodcut_cleanup";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No server world available for cleanup.");
        }

        Map<String, Object> params = context.parameters();

        // For chat command ergonomics: allow numeric "count" to act as radius if no explicit radius is provided.
        int radius = getIntParameter(params, "radius", getIntParameter(params, "count", DEFAULT_RADIUS));
        int verticalRange = getIntParameter(params, "verticalRange", DEFAULT_VERTICAL_RANGE);
        int maxLogs = Math.max(0, getIntParameter(params, "maxLogs", DEFAULT_MAX_LOGS));
        int maxScaffold = Math.max(0, getIntParameter(params, "maxScaffold", DEFAULT_MAX_SCAFFOLD));
        long durationMs = Math.max(2_000L, getLongParameter(params, "durationMs", DEFAULT_DURATION_MS));
        boolean removeScaffold = getBooleanParameter(params, "scaffold", true);
        boolean sweepDrops = getBooleanParameter(params, "sweep", true);
        boolean floatingLogsOnly = getBooleanParameter(params, "floatingLogsOnly", true);

        // Optional region override (primarily for internal calls from woodcut).
        Integer minX = getNullableIntParameter(params, "minX");
        Integer maxX = getNullableIntParameter(params, "maxX");
        Integer minY = getNullableIntParameter(params, "minY");
        Integer maxY = getNullableIntParameter(params, "maxY");
        Integer minZ = getNullableIntParameter(params, "minZ");
        Integer maxZ = getNullableIntParameter(params, "maxZ");

        BlockPos origin = bot.getBlockPos();
        BlockPos scanMin;
        BlockPos scanMax;
        if (minX != null && maxX != null && minY != null && maxY != null && minZ != null && maxZ != null) {
            // Expand slightly to catch canopy scraps.
            scanMin = new BlockPos(minX - 3, minY - 2, minZ - 3);
            scanMax = new BlockPos(maxX + 3, maxY + 12, maxZ + 3);
            radius = (int) Math.max(radius, Math.max(Math.abs(maxX - origin.getX()), Math.abs(minX - origin.getX())) + 4);
            verticalRange = (int) Math.max(verticalRange, (scanMax.getY() - scanMin.getY()));
        } else {
            scanMin = origin.add(-radius, -2, -radius);
            scanMax = origin.add(radius, verticalRange, radius);
        }

        long deadline = System.currentTimeMillis() + durationMs;
        int logsMined = 0;
        int scaffoldMined = 0;
        int failures = 0;

        LOGGER.info("woodcut_cleanup starting: origin={} radius={} vertical={} maxLogs={} maxScaffold={} sweep={} region=[{} -> {}]",
                origin.toShortString(), radius, verticalRange, maxLogs, maxScaffold, sweepDrops,
                scanMin.toShortString(), scanMax.toShortString());

        // Track blocks we place during reach attempts so we can reliably undo them.
        List<BlockPos> placedPillar = new ArrayList<>();
        Set<Long> scaffoldMemory = getOrCreateScaffoldMemory(context.sharedState(), world);

        try {
            // Logs: only target floating/orphan logs by default, to avoid felling fresh/standing trees.
            List<BlockPos> logTargets = collectLogTargets(world, bot, scanMin, scanMax, deadline, floatingLogsOnly);
            logTargets.sort(Comparator.<BlockPos>comparingDouble(p -> p.getSquaredDistance(bot.getBlockPos()))
                    .thenComparingInt(BlockPos::getY));
            for (BlockPos next : logTargets) {
                if (logsMined >= maxLogs || System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (SkillManager.shouldAbortSkill(bot)) {
                    return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                }
                if (!world.getBlockState(next).isIn(BlockTags.LOGS)) {
                    continue;
                }
                if (TreeDetector.isNearHumanBlocks(world, next, 4) || TreeDetector.isProtected(world, next)) {
                    continue;
                }
                boolean ok = mineWithRetries(bot, source, next, placedPillar, true);
                if (ok) {
                    logsMined++;
                } else {
                    failures++;
                    if (failures >= 10) {
                        LOGGER.warn("woodcut_cleanup giving up after {} failures", failures);
                        break;
                    }
                }
            }

            if (removeScaffold && System.currentTimeMillis() < deadline && !SkillManager.shouldAbortSkill(bot)) {
                // 1) Remove remembered scaffold placements (from woodcut) first.
                if (scaffoldMemory != null && !scaffoldMemory.isEmpty()) {
                    List<BlockPos> remembered = new ArrayList<>();
                    for (Long packed : new ArrayList<>(scaffoldMemory)) {
                        if (packed == null) {
                            continue;
                        }
                        BlockPos pos = BlockPos.fromLong(packed);
                        if (!isWithinRegion(pos, scanMin, scanMax)) {
                            continue;
                        }
                        remembered.add(pos);
                    }
                    remembered.sort(Comparator.<BlockPos>comparingDouble(p -> p.getSquaredDistance(bot.getBlockPos()))
                            .thenComparing(Comparator.comparingInt(BlockPos::getY).reversed()));

                    for (BlockPos pos : remembered) {
                        if (scaffoldMined >= maxScaffold || System.currentTimeMillis() >= deadline) {
                            break;
                        }
                        if (SkillManager.shouldAbortSkill(bot)) {
                            return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                        }
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || !PILLAR_BLOCKS.contains(state.getBlock().asItem())) {
                            scaffoldMemory.remove(pos.asLong());
                            continue;
                        }
                        if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                            continue;
                        }
                        boolean ok = mineWithRetries(bot, source, pos, placedPillar, false);
                        if (ok || world.getBlockState(pos).isAir()) {
                            scaffoldMined++;
                            scaffoldMemory.remove(pos.asLong());
                        }
                    }
                }

                // 2) Heuristic scan for remaining scaffold/pillar blocks.
                if (scaffoldMined < maxScaffold && System.currentTimeMillis() < deadline && !SkillManager.shouldAbortSkill(bot)) {
                    List<BlockPos> scaffoldTargets = findSuspiciousScaffold(world, origin, scanMin, scanMax, bot, deadline);
                    scaffoldTargets.sort(
                            Comparator.<BlockPos>comparingDouble(p -> p.getSquaredDistance(bot.getBlockPos()))
                                    .thenComparing(Comparator.comparingInt(BlockPos::getY).reversed())
                    );
                    int processed = 0;
                    for (BlockPos pos : scaffoldTargets) {
                        if (processed >= maxScaffold || scaffoldMined >= maxScaffold || System.currentTimeMillis() >= deadline) {
                            break;
                        }
                        if (SkillManager.shouldAbortSkill(bot)) {
                            return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                        }
                        if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                            continue;
                        }
                        boolean ok = mineWithRetries(bot, source, pos, placedPillar, false);
                        if (ok) {
                            scaffoldMined++;
                        }
                        processed++;
                    }
                }
            }
        } finally {
            if (!placedPillar.isEmpty()) {
                descendAndCleanup(bot, placedPillar);
            }
        }

        // Fallback permissive sweep: small, dense local pass to catch missed logs or scaffold
        if ((logsMined < maxLogs || scaffoldMined < maxScaffold) && System.currentTimeMillis() < deadline && !SkillManager.shouldAbortSkill(bot)) {
            int fallbackRadius = Math.min(8, Math.max(6, radius / 3));
            BlockPos fMin = origin.add(-fallbackRadius, -1, -fallbackRadius);
            BlockPos fMax = origin.add(fallbackRadius, verticalRange, fallbackRadius);
            int fallbackRemoved = 0;
            for (BlockPos pos : BlockPos.iterate(fMin, fMax)) {
                if (System.currentTimeMillis() >= deadline) break;
                if (SkillManager.shouldAbortSkill(bot)) break;
                BlockState state = world.getBlockState(pos);
                if (state.isAir()) continue;

                // Try stray logs that weren't found earlier (still avoid full trees)
                if (logsMined < maxLogs && state.isIn(BlockTags.LOGS)) {
                    try {
                        if (TreeDetector.detectTreeAt(world, pos).isPresent()) {
                            continue;
                        }
                    } catch (Exception ignored) {
                    }
                    if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                        continue;
                    }
                    boolean ok = mineWithRetries(bot, source, pos, placedPillar, true);
                    if (ok) {
                        logsMined++;
                        fallbackRemoved++;
                    }
                }

                // Try scaffold-like blocks in the small area
                if (scaffoldMined < maxScaffold) {
                    Item it = state.getBlock().asItem();
                    if (PILLAR_BLOCKS.contains(it)) {
                        if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                            continue;
                        }
                        if (DIRT_LIKE_SCAFFOLD.contains(it)) {
                            if (!isSunExposedOnAnyFace(world, pos) || hasGrassPlantAbove(world, pos)) {
                                continue;
                            }
                        }
                        boolean ok = mineWithRetries(bot, source, pos, placedPillar, false);
                        if (ok) {
                            scaffoldMined++;
                            fallbackRemoved++;
                            if (scaffoldMemory != null) {
                                scaffoldMemory.remove(pos.asLong());
                            }
                        }
                    }
                }
            }
            if (fallbackRemoved > 0) {
                LOGGER.info("woodcut_cleanup: fallback sweep removed {} extra items (logs+scaffold)", fallbackRemoved);
            }
        }

        if (sweepDrops && !SkillManager.shouldAbortSkill(bot) && !isInventoryFull(bot)) {
            try {
                // Use a modest sweep radius; caller can always run drop_sweep for a heavier pass.
                net.shasankp000.GameAI.DropSweeper.sweep(
                        source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                        Math.max(6.0D, radius),
                        Math.max(6.0D, verticalRange),
                        Math.max(12, (int) Math.ceil(radius * 2.0)),
                        12_000L
                );
            } catch (Exception e) {
                LOGGER.warn("woodcut_cleanup drop sweep failed: {}", e.getMessage());
            }
        }

        String summary = String.format(Locale.ROOT,
                "Cleanup done: minedLogs=%d removedScaffold=%d (radius=%d vertical=%d)",
                logsMined, scaffoldMined, radius, verticalRange);
        LOGGER.info("woodcut_cleanup completed: {}", summary);
        return SkillExecutionResult.success(summary);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> getOrCreateScaffoldMemory(Map<String, Object> sharedState, ServerWorld world) {
        if (sharedState == null || world == null) {
            return null;
        }

        String dim = world.getRegistryKey().getValue().toString();
        Object existingDim = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
        if (existingDim instanceof String stored && !stored.equals(dim)) {
            // Dimension changed; discard old memory.
            sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dim);
        } else if (existingDim == null) {
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dim);
        }

        Object existing = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
        if (existing instanceof Set<?> rawSet) {
            try {
                Set<Long> typed = (Set<Long>) rawSet;
                trimScaffoldMemoryInPlace(typed);
                return typed;
            } catch (ClassCastException ignored) {
                // Fall through and replace with a clean set.
            }
        }

        Set<Long> created = new HashSet<>();
        sharedState.put(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY, created);
        return created;
    }

    private void trimScaffoldMemoryInPlace(Set<Long> memory) {
        if (memory == null) {
            return;
        }
        int overflow = memory.size() - WOODCUT_SCAFFOLD_MEMORY_MAX;
        if (overflow <= 0) {
            return;
        }
        // Best-effort trim: drop arbitrary entries.
        var it = memory.iterator();
        while (overflow > 0 && it.hasNext()) {
            it.next();
            it.remove();
            overflow--;
        }
    }

    private boolean isWithinRegion(BlockPos pos, BlockPos min, BlockPos max) {
        if (pos == null || min == null || max == null) {
            return false;
        }
        int x0 = Math.min(min.getX(), max.getX());
        int x1 = Math.max(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY());
        int y1 = Math.max(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ());
        int z1 = Math.max(min.getZ(), max.getZ());
        return pos.getX() >= x0 && pos.getX() <= x1
                && pos.getY() >= y0 && pos.getY() <= y1
                && pos.getZ() >= z0 && pos.getZ() <= z1;
    }

    private List<BlockPos> collectLogTargets(ServerWorld world,
                                            ServerPlayerEntity bot,
                                            BlockPos min,
                                            BlockPos max,
                                            long deadline,
                                            boolean floatingLogsOnly) {
        List<BlockPos> targets = new ArrayList<>();
        int i = 0;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            i++;
            if ((i & 0x7F) == 0) { // every ~128 blocks - be more responsive to aborts
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (SkillManager.shouldAbortSkill(bot)) {
                    break;
                }
            }

            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                continue;
            }
            // Skip if this log is part of a full-valid tree (avoid felling intact trees).
            try {
                if (TreeDetector.detectTreeAt(world, pos).isPresent()) {
                    LOGGER.debug("woodcut_cleanup: skipping log {} because it's part of a detected full tree", pos.toShortString());
                    continue;
                }
            } catch (Exception ignored) {
            }
            if (floatingLogsOnly && !isFloatingOrOrphanLog(world, pos, bot, deadline)) {
                continue;
            }
            targets.add(pos.toImmutable());
        }
        return targets;
    }

    private boolean isFloatingOrOrphanLog(ServerWorld world, BlockPos start, ServerPlayerEntity bot, long deadline) {
        // Determine if this log is part of a component that connects to the ground.
        // This avoids chopping intact diagonal/leaning trunks (e.g. acacia).
        int limit = 1024;

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        visited.add(start);

        int steps = 0;
        while (!q.isEmpty() && visited.size() < limit) {
            steps++;
            if ((steps & 0xF) == 0) { // every ~16 nodes - check abort more frequently
                if (System.currentTimeMillis() >= deadline) {
                    return false;
                }
                if (bot != null && SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
            }

            BlockPos pos = q.removeFirst();
            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);
            boolean belowIsLog = belowState.isIn(BlockTags.LOGS);
            boolean belowIsSolid = !belowState.getCollisionShape(world, below).isEmpty() && !belowState.isIn(BlockTags.LEAVES);

            if (!belowIsLog && belowIsSolid) {
                // Connected to ground => treat as standing tree / placed log structure.
                return false;
            }

            for (Direction dir : Direction.values()) {
                BlockPos n = pos.offset(dir);
                if (visited.contains(n)) {
                    continue;
                }
                if (!world.getBlockState(n).isIn(BlockTags.LOGS)) {
                    continue;
                }
                visited.add(n);
                q.addLast(n);
                if (visited.size() >= limit) {
                    // Too large/uncertain: be conservative and treat as not orphan.
                    return false;
                }
            }
        }

        // No grounded logs found in the connected component.
        return true;
    }

    private List<BlockPos> findSuspiciousScaffold(ServerWorld world,
                                                  BlockPos origin,
                                                  BlockPos min,
                                                  BlockPos max,
                                                  ServerPlayerEntity bot,
                                                  long deadline) {
        List<BlockPos> targets = new ArrayList<>();
        int originY = origin.getY();

        int i = 0;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            i++;
            if ((i & 0x7F) == 0) {
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (bot != null && SkillManager.shouldAbortSkill(bot)) {
                    break;
                }
            }

            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                continue;
            }
            if (!PILLAR_BLOCKS.contains(state.getBlock().asItem())) {
                continue;
            }

            // For dirt-like scaffold, be stricter to avoid damaging natural terrain.
            if (DIRT_LIKE_SCAFFOLD.contains(state.getBlock().asItem())) {
                if (!isSunExposedOnAnyFace(world, pos)) {
                    continue;
                }
                if (hasGrassPlantAbove(world, pos)) {
                    continue;
                }
            }

            // Heuristic: only remove scaffold-ish blocks above the local ground band.
            if (pos.getY() < originY - 2) {
                continue;
            }

            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);

            boolean floating = belowState.isAir() || belowState.isReplaceable() || belowState.isIn(BlockTags.LEAVES) || belowState.isIn(BlockTags.LOGS);
            boolean isolated = true;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockState n = world.getBlockState(pos.offset(dir));
                if (!n.isAir() && !n.isReplaceable() && !n.isIn(BlockTags.LEAVES)) {
                    isolated = false;
                    break;
                }
            }

            // If it looks like a pillar segment or a floating block, it's fair game.
            boolean pillarLike = PILLAR_BLOCKS.contains(world.getBlockState(pos.up()).getBlock().asItem())
                    || PILLAR_BLOCKS.contains(world.getBlockState(pos.down()).getBlock().asItem());

            // Also consider horizontal neighbors that are pillar blocks (scaffold clusters).
            if (!pillarLike) {
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    if (PILLAR_BLOCKS.contains(world.getBlockState(pos.offset(dir)).getBlock().asItem())) {
                        pillarLike = true;
                        break;
                    }
                }
            }

            if (floating || (isolated && pillarLike)) {
                LOGGER.debug("woodcut_cleanup: scaffold candidate ACCEPT {} state={} floating={} isolated={} pillarLike={}", pos.toShortString(), state.getBlock().toString(), floating, isolated, pillarLike);
                targets.add(pos.toImmutable());
            } else {
                LOGGER.debug("woodcut_cleanup: scaffold candidate REJECT {} state={} floating={} isolated={} pillarLike={}", pos.toShortString(), state.getBlock().toString(), floating, isolated, pillarLike);
            }
        }

        return targets;
    }

    private boolean mineWithRetries(ServerPlayerEntity bot,
                                    ServerCommandSource source,
                                    BlockPos target,
                                    List<BlockPos> placedPillar,
                                    boolean preferAxe) {
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            if (!prepareReach(bot, source, target, placedPillar)) {
                continue;
            }

            clearBlockingLeaves(bot, target);
            boolean mined = mineBlock(bot, target, preferAxe);
            if (mined) {
                return true;
            }
        }
        return false;
    }

    private boolean prepareReach(ServerPlayerEntity bot,
                                 ServerCommandSource source,
                                 BlockPos target,
                                 List<BlockPos> placedPillar) {
        if (isWithinReach(bot, target)) {
            return true;
        }

        // Try to get under it first.
        moveUnderTarget(source, bot, target);
        if (isWithinReach(bot, target)) {
            return true;
        }

        int needed = target.getY() - bot.getBlockY() - 1;
        if (needed <= 0) {
            return false;
        }
        return pillarUp(bot, needed, placedPillar);
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placedPillar) {
        if (steps <= 0) {
            return true;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (countPillarBlocks(bot) < steps) {
            LOGGER.warn("woodcut_cleanup: insufficient scaffold blocks to pillar {} steps", steps);
            return false;
        }

        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        try {
            for (int i = 0; i < steps; i++) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
                BlockPos candidate = bot.getBlockPos();
                if (!world.getBlockState(candidate).isAir()) {
                    candidate = candidate.up();
                }
                BotActions.jump(bot);
                sleepQuiet(PILLAR_STEP_DELAY_MS);
                if (!world.getBlockState(candidate).isAir()) {
                    candidate = candidate.up();
                }
                if (!tryPlaceScaffold(bot, candidate)) {
                    return false;
                }
                placedPillar.add(candidate.toImmutable());
                sleepQuiet(PILLAR_STEP_DELAY_MS);
            }
            return true;
        } finally {
            bot.setSneaking(wasSneaking);
        }
    }

    private boolean tryPlaceScaffold(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos placePos = target;
        if (!isPlaceableTarget(world, placePos)) {
            breakSoftBlock(world, bot, placePos);
        }
        ensureSupportBlock(bot, placePos);
        if (BotActions.placeBlockAt(bot, placePos, Direction.UP, PILLAR_BLOCKS)) {
            return true;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = placePos.offset(dir);
            if (!isPlaceableTarget(world, alt)) {
                breakSoftBlock(world, bot, alt);
            }
            ensureSupportBlock(bot, alt);
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                return true;
            }
        }
        return false;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return;
        }
        if (!isPlaceableTarget(world, below)) {
            breakSoftBlock(world, bot, below);
        }
        BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS);
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW);
    }

    private void breakSoftBlock(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isReplaceable()) {
            breakLeaf(bot, pos);
        }
    }

    private void clearBlockingLeaves(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        selectLeafTool(bot);
        int radius = 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos leafPos = target.add(dx, dy, dz);
                    if (!world.getBlockState(leafPos).isIn(BlockTags.LEAVES)) {
                        continue;
                    }
                    breakLeaf(bot, leafPos);
                }
            }
        }
    }

    private boolean selectLeafTool(ServerPlayerEntity bot) {
        if (BotActions.selectBestTool(bot, "shears", "")) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return true;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe") || key.contains("shovel") || key.contains("hoe")) {
                continue;
            }
            BotActions.selectHotbarSlot(bot, i);
            return true;
        }
        return true;
    }

    private void breakLeaf(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (botPos.squaredDistanceTo(center) > REACH_DISTANCE_SQ) {
            return;
        }
        LookController.faceBlock(bot, pos);
        CompletableFuture<String> mining;
        try {
            mining = MiningTool.mineBlock(bot, pos);
        } catch (Exception e) {
            return;
        }
        try {
            mining.get(3_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            mining.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean mineBlock(ServerPlayerEntity bot, BlockPos pos, boolean preferAxe) {
        if (SkillManager.shouldAbortSkill(bot)) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double distSq = botPos.squaredDistanceTo(center);
        if (distSq > REACH_DISTANCE_SQ) {
            return false;
        }

        if (!hasLineOfSight(bot, center)) {
            clearBlockingLeaves(bot, pos);
            if (!hasLineOfSight(bot, center)) {
                return false;
            }
        }

        LookController.faceBlock(bot, pos);
        if (preferAxe) {
            ensureAxeEquipped(bot);
        } else {
            // Avoid axes for scaffold; prefer shovel if possible.
            BotActions.selectBestTool(bot, "shovel", "axe");
        }

        CompletableFuture<String> miningFuture = MiningTool.mineBlock(bot, pos);
        long start = System.currentTimeMillis();
        while (true) {
            try {
                String result = miningFuture.get(200, TimeUnit.MILLISECONDS);
                return result != null && result.toLowerCase(Locale.ROOT).contains("complete");
            } catch (TimeoutException te) {
                // Not done yet; check for abort or timeout more frequently
                if (SkillManager.shouldAbortSkill(bot)) {
                    miningFuture.cancel(true);
                    return false;
                }
                if (System.currentTimeMillis() - start > MINING_TIMEOUT_MS) {
                    miningFuture.cancel(true);
                    return false;
                }
                // otherwise loop and wait some more
            } catch (Exception e) {
                miningFuture.cancel(true);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
    }

    private boolean isSunExposedOnAnyFace(ServerWorld world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos adj = pos.offset(dir);
            BlockState adjState = world.getBlockState(adj);
            if (!adjState.isAir() && !adjState.isReplaceable() && !adjState.isIn(BlockTags.LEAVES)) {
                continue;
            }
            if (world.isSkyVisible(adj)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGrassPlantAbove(ServerWorld world, BlockPos pos) {
        BlockState above = world.getBlockState(pos.up());
        // Consider the grass-colored top (grass block) in addition to plant entities.
        if (above.isOf(Blocks.GRASS_BLOCK)) {
            return true;
        }
        return above.isOf(Blocks.SHORT_GRASS)
                || above.isOf(Blocks.TALL_GRASS)
                || above.isOf(Blocks.FERN)
                || above.isOf(Blocks.LARGE_FERN);
    }

    private boolean ensureAxeEquipped(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        ItemStack best = ItemStack.EMPTY;
        int bestSlot = -1;
        float bestSpeed = 0.0f;
        BlockState ref = Blocks.OAK_LOG.getDefaultState();

        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (!key.contains("axe") || key.contains("pickaxe")) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(ref);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
                best = stack;
            }
        }

        if (bestSlot == -1 || best.isEmpty()) {
            return false;
        }
        int hotbarSlot = bestSlot < 9 ? bestSlot : bot.getInventory().getSelectedSlot();
        if (bestSlot >= 9) {
            ItemStack hotbarStack = bot.getInventory().getStack(hotbarSlot);
            bot.getInventory().setStack(bestSlot, hotbarStack);
            bot.getInventory().setStack(hotbarSlot, best);
        }
        BotActions.selectHotbarSlot(bot, hotbarSlot);
        return true;
    }

    private void descendAndCleanup(ServerPlayerEntity bot, List<BlockPos> placedPillar) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        try {
            Collections.reverse(placedPillar);
            for (BlockPos placed : placedPillar) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return;
                }
                if (world.getBlockState(placed).isAir()) {
                    continue;
                }
                LookController.faceBlock(bot, placed);
                mineBlock(bot, placed, false);
                sleepQuiet(80L);
            }
        } finally {
            bot.setSneaking(wasSneaking);
        }
    }

    private boolean hasLineOfSight(ServerPlayerEntity bot, Vec3d targetCenter) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        Vec3d eye = bot.getEyePos();
        RaycastContext ctx = new RaycastContext(
                eye,
                targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(BlockPos.ofFloored(targetCenter));
    }

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private void moveUnderTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        try {
            var planOpt = net.shasankp000.GameAI.services.MovementService.planLootApproach(
                    bot,
                    target,
                    net.shasankp000.GameAI.services.MovementService.MovementOptions.skillLoot()
            );
            planOpt.ifPresent(plan -> net.shasankp000.GameAI.services.MovementService.execute(source, bot, plan, false, true, false, false));
        } catch (Exception ignored) {
        }
    }

    private int countPillarBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (PILLAR_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isInventoryFull(ServerPlayerEntity player) {
        return player != null && player.getInventory().getEmptySlot() == -1;
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean getBooleanParameter(Map<String, Object> params, String key, boolean fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object v = params.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return fallback;
    }

    private int getIntParameter(Map<String, Object> params, String key, int fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private long getLongParameter(Map<String, Object> params, String key, long fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private Integer getNullableIntParameter(Map<String, Object> params, String key) {
        if (params == null || key == null) {
            return null;
        }
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
