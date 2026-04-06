package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.wcfcarolina13.GameAI.services.CompanionOverheadHologramService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.WoodcutCleanupMemoryService;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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
    private static final int DEFAULT_VERTICAL_RANGE = 20;
    private static final int DEFAULT_MAX_LOGS = 64;
    private static final int DEFAULT_MAX_SCAFFOLD = 96;
    private static final long DEFAULT_DURATION_MS = 120_000L;

    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    private static final long MINING_TIMEOUT_MS = 12_000L;
    private static final int MAX_RETRY_MINING = 4;
    private static final long FAILURE_COOLDOWN_MS = 8_000L;
    private static final int MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT = 6;
    /** Maximum pillar height the cleanup skill will attempt. Higher targets are skipped. */
    private static final int MAX_CLEANUP_PILLAR_STEPS = 7;
    /** How many blocks to mine before forcing a rescan (otherwise reuse cached list). */
    private static final int RESCAN_INTERVAL = 4;
    /** How many blocks to mine before doing an interleaved drop sweep. */
    private static final int DROP_SWEEP_INTERVAL = 3;

    private static final long PILLAR_STEP_DELAY_MS = 250L;

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
            Items.NETHERRACK,
            Items.DIORITE,
            Items.ANDESITE,
            Items.GRANITE,
            Items.STONE,
            Items.TUFF
    );

    private static final Set<Item> DIRT_LIKE_SCAFFOLD = Set.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT
    );

    private enum CleanupTargetKind {
        REMEMBERED_LEFTOVER,
        OVERHEAD_SUSPENDED,
        GENERIC_FLOATING
    }

    private record CleanupTarget(BlockPos pos, CleanupTargetKind kind) {
    }

    private record CleanupProtectionDecision(boolean blocked, String reason) {
    }

    private record ReachResult(boolean ready, boolean visibilityChanged, String failureReason) {
    }

    private record MineAttemptResult(boolean mined, boolean visibilityChanged, String failureReason) {
    }

    private record CleanupTargetCollection(List<CleanupTarget> targets, WoodcutCleanupDiagnostics.CleanupScanStats stats) {
    }

    private final class CleanupScanAccumulator {
        private final LinkedHashMap<Long, CleanupTarget> orderedTargets = new LinkedHashMap<>();
        private final Map<Long, TreeDetector.CleanupLogDispositionKind> dispositions = new HashMap<>();
        private final Map<String, Integer> protectedReasons = new HashMap<>();

        void record(BlockPos pos, CleanupTarget target, TreeDetector.CleanupLogDisposition disposition) {
            if (pos == null || disposition == null || !disposition.floaterLike()) {
                return;
            }
            long key = pos.asLong();
            if (disposition.kind() == TreeDetector.CleanupLogDispositionKind.BLOCKED_PROTECTED
                    && disposition.reason() != null
                    && !disposition.reason().isBlank()) {
                protectedReasons.merge(disposition.reason(), 1, Integer::sum);
            }
            if (target != null && disposition.actionable()) {
                if (orderedTargets.putIfAbsent(key, target) == null) {
                    dispositions.put(key, disposition.kind());
                }
                return;
            }
            dispositions.putIfAbsent(key, disposition.kind());
        }

        CleanupTargetCollection build(ServerPlayerEntity bot) {
            List<CleanupTarget> targets = new ArrayList<>(orderedTargets.values());
            targets.sort(Comparator.<CleanupTarget>comparingInt(t -> priorityFor(t.kind()))
                    .thenComparingInt(t -> hasLineOfSight(bot, Vec3d.ofCenter(t.pos())) ? 0 : 1)
                    .thenComparingDouble(t -> t.pos().getSquaredDistance(bot.getBlockPos()))
                    .thenComparing((a, b) -> Integer.compare(b.pos().getY(), a.pos().getY())));

            int actionable = 0;
            int blockedProtected = 0;
            int blockedHuman = 0;
            int rejectedFullTree = 0;
            int rejectedGrounded = 0;
            for (TreeDetector.CleanupLogDispositionKind kind : dispositions.values()) {
                switch (kind) {
                    case REMEMBERED_LEFTOVER, LONE_FLOATING, SUSPENDED_FRAGMENT, ORPHAN_CLUSTER -> actionable++;
                    case BLOCKED_PROTECTED -> blockedProtected++;
                    case BLOCKED_HUMAN_ADJACENT -> blockedHuman++;
                    case REJECTED_FULL_TREE -> rejectedFullTree++;
                    case REJECTED_GROUNDED_OR_NON_FLOATER -> rejectedGrounded++;
                    case REJECTED_NON_LOG -> {
                    }
                }
            }

            return new CleanupTargetCollection(targets,
                    new WoodcutCleanupDiagnostics.CleanupScanStats(
                            actionable,
                            blockedProtected,
                            blockedHuman,
                            rejectedFullTree,
                            rejectedGrounded,
                            summarizeReasons(protectedReasons)));
        }

        private String summarizeReasons(Map<String, Integer> reasons) {
            if (reasons == null || reasons.isEmpty()) {
                return "none";
            }
            return reasons.entrySet().stream()
                    .sorted((a, b) -> {
                        int cmp = Integer.compare(b.getValue(), a.getValue());
                        if (cmp != 0) {
                            return cmp;
                        }
                        return a.getKey().compareToIgnoreCase(b.getKey());
                    })
                    .limit(3)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((a, b) -> a + "," + b)
                    .orElse("none");
        }
    }

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

        // Ensure the bot is at the surface before starting cleanup.
        // Without this, the bot starts in a hole from prior sessions and every target
        // needs too many pillar steps, wasting the entire cleanup session.
        if (!net.wcfcarolina13.GameAI.services.BotFleeService.ensureAtSurface(bot, world)) {
            try {
                net.wcfcarolina13.GameAI.DropSweeper.sweep(
                        source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                        6.0D, 4.0D, 12, 6_000L);
            } catch (Exception ignored) {
            }
            return SkillExecutionResult.failure("Couldn't reach the surface to run cleanup.");
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
        boolean constrainedRegion = minX != null && maxX != null && minY != null && maxY != null && minZ != null && maxZ != null;

        BlockPos origin = bot.getBlockPos();
        BlockPos scanMin;
        BlockPos scanMax;
        if (constrainedRegion) {
            // Expand slightly to catch canopy scraps.
            scanMin = new BlockPos(minX - 3, minY - 2, minZ - 3);
            scanMax = new BlockPos(maxX + 3, maxY + 12, maxZ + 3);
            radius = (int) Math.max(radius, Math.max(Math.abs(maxX - origin.getX()), Math.abs(minX - origin.getX())) + 4);
            verticalRange = (int) Math.max(verticalRange, (scanMax.getY() - scanMin.getY()));
        } else {
            scanMin = origin.add(-radius, -2, -radius);
            scanMax = origin.add(radius, verticalRange, radius);
        }

        int sweepMinX = Math.min(origin.getX(), Math.min(scanMin.getX(), scanMax.getX()));
        int sweepMaxX = Math.max(origin.getX(), Math.max(scanMin.getX(), scanMax.getX()));
        int sweepMinY = Math.min(origin.getY(), Math.min(scanMin.getY(), scanMax.getY()));
        int sweepMaxY = Math.max(origin.getY(), Math.max(scanMin.getY(), scanMax.getY()));
        int sweepMinZ = Math.min(origin.getZ(), Math.min(scanMin.getZ(), scanMax.getZ()));
        int sweepMaxZ = Math.max(origin.getZ(), Math.max(scanMin.getZ(), scanMax.getZ()));

        long deadline = System.currentTimeMillis() + durationMs;
        UUID botUuid = bot.getUuid();
        int logsMined = 0;
        int scaffoldMined = 0;
        int failures = 0;
        int attemptedTargets = 0;
        int emptyVisibleLogPasses = 0;
        Map<Long, Long> failedLogCooldowns = new HashMap<>();
        // Positions already classified as non-actionable (full tree, grounded, human, etc.).
        // Persists across rescans so we skip expensive BFS/tree-detection on unchanged blocks.
        // ConcurrentHashMap-backed for thread safety during async scans.
        Set<Long> rejectionCache = ConcurrentHashMap.newKeySet();

        LOGGER.info("woodcut_cleanup starting: origin={} radius={} vertical={} maxLogs={} maxScaffold={} sweep={} region=[{} -> {}]",
                origin.toShortString(), radius, verticalRange, maxLogs, maxScaffold, sweepDrops,
                scanMin.toShortString(), scanMax.toShortString());

        // Track blocks we place during reach attempts so we can reliably undo them.
        List<BlockPos> placedPillar = new ArrayList<>();
        Set<Long> scaffoldMemory = getOrCreateScaffoldMemory(context.sharedState(), world);
        Set<Long> floaterMemory = WoodcutCleanupMemoryService.getOrCreate(context.sharedState(), botUuid, world);

        try {
            // Scan once, then work through the cached list.  Only rescan when the
            // list is exhausted or after mining RESCAN_INTERVAL blocks (world state
            // may have changed enough to reveal new targets).
            List<CleanupTarget> cachedTargets = new ArrayList<>();
            int sinceLastScan = RESCAN_INTERVAL; // force initial scan
            int sinceLastSweep = 0;
            WoodcutCleanupDiagnostics.CleanupScanStats lastScanStats = null;
            CompletableFuture<CleanupTargetCollection> asyncScan = null;

            while (logsMined < maxLogs && System.currentTimeMillis() < deadline) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                }
                BlockPos botPos = bot.getBlockPos();
                sweepMinX = Math.min(sweepMinX, botPos.getX());
                sweepMaxX = Math.max(sweepMaxX, botPos.getX());
                sweepMinY = Math.min(sweepMinY, botPos.getY());
                sweepMaxY = Math.max(sweepMaxY, botPos.getY());
                sweepMinZ = Math.min(sweepMinZ, botPos.getZ());
                sweepMaxZ = Math.max(sweepMaxZ, botPos.getZ());

                // ── Merge async scan results when ready ─────────────────────
                if (asyncScan != null && asyncScan.isDone()) {
                    try {
                        CleanupTargetCollection fullScan = asyncScan.get();
                        lastScanStats = fullScan.stats();
                        Set<Long> existing = cachedTargets.stream()
                                .map(t -> t.pos().asLong()).collect(Collectors.toSet());
                        int added = 0;
                        for (CleanupTarget t : fullScan.targets()) {
                            long key = t.pos().asLong();
                            // Skip targets already cached or on cooldown (cooldowns applied
                            // AFTER the async scan started won't be in the scan's snapshot).
                            if (!existing.contains(key) && !isCoolingDown(t.pos(), failedLogCooldowns)) {
                                cachedTargets.add(t);
                                added++;
                            }
                        }
                        LOGGER.info("woodcut_cleanup async scan merged: {} new targets, {} total",
                                added, cachedTargets.size());
                    } catch (Exception e) {
                        LOGGER.warn("woodcut_cleanup async scan failed: {}", e.getMessage());
                    }
                    asyncScan = null;
                }

                // ── Rescan when needed ──────────────────────────────────────
                if (cachedTargets.isEmpty() || sinceLastScan >= RESCAN_INTERVAL) {
                    // Fast pass: collect remembered + overhead targets instantly.
                    CompanionOverheadHologramService.show(bot, "Scanning for cleanup targets...", 5_000);
                    CleanupTargetCollection fastCollection = collectFastTargets(
                            world, bot, scanMin, scanMax, failedLogCooldowns, floaterMemory);
                    cachedTargets = new ArrayList<>(fastCollection.targets());
                    lastScanStats = fastCollection.stats();
                    sinceLastScan = 0;
                    int rememberedTargets = (int) cachedTargets.stream().filter(t -> t.kind() == CleanupTargetKind.REMEMBERED_LEFTOVER).count();
                    int overheadTargets = (int) cachedTargets.stream().filter(t -> t.kind() == CleanupTargetKind.OVERHEAD_SUSPENDED).count();
                    LOGGER.info("woodcut_cleanup fast scan: rememberedTargets={} overheadTargets={} attemptedTargets={} minedLogs={}",
                            rememberedTargets, overheadTargets, attemptedTargets, logsMined);

                    // Launch full region scan in background (thread-safe: read-only world access).
                    if (asyncScan == null || asyncScan.isDone()) {
                        final Map<Long, Long> cooldownSnapshot = new HashMap<>(failedLogCooldowns);
                        final BlockPos asyncScanMin = scanMin;
                        final BlockPos asyncScanMax = scanMax;
                        final long asyncDeadline = deadline;
                        final boolean asyncFloatingOnly = floatingLogsOnly;
                        asyncScan = CompletableFuture.supplyAsync(() ->
                                collectCleanupTargets(world, bot, asyncScanMin, asyncScanMax,
                                        asyncDeadline, asyncFloatingOnly, cooldownSnapshot,
                                        floaterMemory, rejectionCache));
                    }
                }
                if (cachedTargets.isEmpty()) {
                    // No fast targets — wait briefly for async scan to finish.
                    if (asyncScan != null && !asyncScan.isDone()) {
                        for (int waitAttempt = 0; waitAttempt < 4 && !asyncScan.isDone(); waitAttempt++) {
                            sleepQuiet(250L);
                            if (SkillManager.shouldAbortSkill(bot)) {
                                return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                            }
                        }
                        continue; // re-enter loop to merge results at top
                    }
                    if (constrainedRegion
                            && lastScanStats != null
                            && lastScanStats.actionableTargets() == 0) {
                        LOGGER.info("woodcut_cleanup bounded: no actionable targets remain inside constrained region [{} -> {}]; skipping roam",
                                scanMin.toShortString(),
                                scanMax.toShortString());
                        break;
                    }
                    emptyVisibleLogPasses++;
                    if (emptyVisibleLogPasses >= 2) {
                        if (constrainedRegion) {
                            LOGGER.info("woodcut_cleanup bounded: empty target scan streak reached inside constrained region; stopping without roam");
                            break;
                        }
                        // Nothing in this area. Walk to a new spot and rescan from there.
                        BlockPos newSpot = findRoamTarget(bot, world, scanMin, scanMax, radius);
                        if (newSpot == null || System.currentTimeMillis() >= deadline) {
                            break; // nowhere to go or out of time
                        }
                        CompanionOverheadHologramService.show(bot, "Moving to new area...", 5_000);
                        LOGGER.info("woodcut_cleanup: roaming from {} to {} to find more targets",
                                bot.getBlockPos().toShortString(), newSpot.toShortString());
                        try {
                            net.wcfcarolina13.PathFinding.GoTo.goTo(source, newSpot.getX(), newSpot.getY(), newSpot.getZ(), false);
                        } catch (Exception e) {
                            LOGGER.warn("woodcut_cleanup roam failed: {}", e.getMessage());
                            break;
                        }
                        // Update scan region to new position.
                        BlockPos newOrigin = bot.getBlockPos();
                        scanMin = newOrigin.add(-radius, -2, -radius);
                        scanMax = newOrigin.add(radius, verticalRange, radius);
                        emptyVisibleLogPasses = 0;
                        sinceLastScan = RESCAN_INTERVAL;
                        rejectionCache.clear();
                        continue;
                    }
                    // Only rescan once more — don't spin launching async scans endlessly.
                    if (asyncScan == null) {
                        sinceLastScan = RESCAN_INTERVAL;
                    }
                    continue;
                }
                emptyVisibleLogPasses = 0;

                // ── Pick next target from cached list ───────────────────────
                CleanupTarget next = cachedTargets.remove(0);
                // Verify the block is still a log (may have been broken by gravity/decay since scan).
                if (!world.getBlockState(next.pos()).isIn(BlockTags.LOGS)) {
                    if (next.kind() == CleanupTargetKind.REMEMBERED_LEFTOVER) {
                        WoodcutCleanupMemoryService.forget(context.sharedState(), botUuid, world, next.pos());
                    }
                    continue;
                }
                attemptedTargets++;
                LOGGER.info("woodcut_cleanup target selected: kind={} pos={} visible={}",
                        next.kind(), next.pos().toShortString(), hasLineOfSight(bot, Vec3d.ofCenter(next.pos())));
                int pillarSizeBefore = placedPillar.size();
                MineAttemptResult mineResult = mineWithRetries(bot, source, next, placedPillar, scaffoldMemory, true);
                boolean usedPillar = placedPillar.size() > pillarSizeBefore;
                if (mineResult.mined()) {
                    logsMined++;
                    sinceLastScan++;
                    sinceLastSweep++;
                    failures = 0;
                    failedLogCooldowns.remove(next.pos().asLong());
                    if (next.kind() == CleanupTargetKind.REMEMBERED_LEFTOVER) {
                        WoodcutCleanupMemoryService.forget(context.sharedState(), botUuid, world, next.pos());
                    }
                    // Remove from cached list if present.
                    cachedTargets.removeIf(t -> t.pos().equals(next.pos()));

                    // ── Cluster mining: chase adjacent connected logs ────────
                    int clusterMined = chaseAdjacentLogs(bot, source, world, next.pos(),
                            placedPillar, scaffoldMemory, failedLogCooldowns, maxLogs - logsMined, deadline);
                    if (clusterMined > 0) {
                        logsMined += clusterMined;
                        sinceLastScan += clusterMined;
                        sinceLastSweep += clusterMined;
                        LOGGER.info("woodcut_cleanup cluster: mined {} adjacent logs near {}",
                                clusterMined, next.pos().toShortString());
                    }

                    // ── While elevated on pillar, mine ALL reachable logs ────
                    if (usedPillar) {
                        int elevatedMined = mineAllReachableFromElevation(bot, source, world,
                                failedLogCooldowns, maxLogs - logsMined, deadline);
                        if (elevatedMined > 0) {
                            logsMined += elevatedMined;
                            sinceLastScan += elevatedMined;
                            sinceLastSweep += elevatedMined;
                            LOGGER.info("woodcut_cleanup elevated sweep: mined {} reachable logs while on pillar",
                                    elevatedMined);
                        }
                    }

                    // ── Interleaved drop sweep ──────────────────────────────
                    if (sweepDrops && sinceLastSweep >= DROP_SWEEP_INTERVAL
                            && !SkillManager.shouldAbortSkill(bot) && !isInventoryFull(bot)) {
                        sinceLastSweep = 0;
                        try {
                            net.wcfcarolina13.GameAI.DropSweeper.sweep(
                                    source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                                    6.0D, 6.0D, 12, 6_000L
                            );
                        } catch (Exception e) {
                            LOGGER.warn("woodcut_cleanup interleaved sweep failed: {}", e.getMessage());
                        }
                    }
                    // ── Chest offload when inventory is getting full ─────────
                    if (isInventoryFull(bot) && !SkillManager.shouldAbortSkill(bot)) {
                        tryCleanupChestOffload(source, bot);
                    }
                } else {
                    if (next.kind() == CleanupTargetKind.REMEMBERED_LEFTOVER && !world.getBlockState(next.pos()).isIn(BlockTags.LOGS)) {
                        WoodcutCleanupMemoryService.forget(context.sharedState(), botUuid, world, next.pos());
                    }
                    LOGGER.warn("woodcut_cleanup target failed: kind={} pos={} reason={}",
                            next.kind(), next.pos().toShortString(), mineResult.failureReason());
                    // Pillar failures and unreachable targets get a long cooldown (effectively session-blacklisted)
                    // to avoid wasting time retrying the same unreachable floater and generating scaffold mess.
                    String reason = mineResult.failureReason();
                    boolean hardFailure = reason != null && (reason.contains("pillar") || reason.contains("unreachable") || reason.contains("too-high"));
                    long cooldown = hardFailure ? 300_000L : FAILURE_COOLDOWN_MS; // 5 minutes vs 8 seconds
                    failedLogCooldowns.put(next.pos().asLong(), System.currentTimeMillis() + cooldown);
                    failures++;
                    if (failures >= 10) {
                        LOGGER.warn("woodcut_cleanup giving up after {} failures", failures);
                        break;
                    }
                }
                // Clean up pillar blocks AFTER all elevated mining is done.
                cleanupFreshPillarBlocks(bot, placedPillar, pillarSizeBefore);
                tryHoleRecovery(bot, source, world, placedPillar, scaffoldMemory);
                if (mineResult.visibilityChanged()) {
                    emptyVisibleLogPasses = 0;
                    // Visibility changed — force rescan so newly-visible targets get picked up.
                    sinceLastScan = RESCAN_INTERVAL;
                }
            }

            // Scaffold removal gets its own time budget — the main mining loop may have
            // consumed the original deadline, but scaffold cleanup is essential to avoid
            // leaving dirt pillars behind.
            long scaffoldDeadline = Math.max(deadline, System.currentTimeMillis() + 20_000L);
            LOGGER.info("woodcut_cleanup scaffold phase: removeScaffold={} shouldAbort={}", removeScaffold, SkillManager.shouldAbortSkill(bot));
            if (removeScaffold && !SkillManager.shouldAbortSkill(bot)) {
                CompanionOverheadHologramService.show(bot, "Removing scaffolding...", 8_000);
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
                        if (scaffoldMined >= maxScaffold || System.currentTimeMillis() >= scaffoldDeadline) {
                            break;
                        }
                        if (SkillManager.shouldAbortSkill(bot)) {
                            return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                        }
                        BlockPos botPos = bot.getBlockPos();
                        sweepMinX = Math.min(sweepMinX, botPos.getX());
                        sweepMaxX = Math.max(sweepMaxX, botPos.getX());
                        sweepMinY = Math.min(sweepMinY, botPos.getY());
                        sweepMaxY = Math.max(sweepMaxY, botPos.getY());
                        sweepMinZ = Math.min(sweepMinZ, botPos.getZ());
                        sweepMaxZ = Math.max(sweepMaxZ, botPos.getZ());
                        BlockState state = world.getBlockState(pos);
                        if (state.isAir() || !PILLAR_BLOCKS.contains(state.getBlock().asItem())) {
                            scaffoldMemory.remove(pos.asLong());
                            continue;
                        }
                        if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                            continue;
                        }
                        if (trySimpleScaffoldMine(bot, source, world, pos)) {
                            scaffoldMined++;
                            scaffoldMemory.remove(pos.asLong());
                        }
                    }
                }

                // 2) Heuristic scan for remaining scaffold/pillar blocks.
                //    Always runs as a second pass — catches leftover pillars from previous
                //    sessions whose scaffold memory was already cleared.
                if (scaffoldMined < maxScaffold
                        && System.currentTimeMillis() < scaffoldDeadline
                        && !SkillManager.shouldAbortSkill(bot)) {
                    // Scaffold can be much higher than the tree canopy — extend Y ceiling
                    BlockPos scaffoldScanMax = new BlockPos(scanMax.getX(), Math.max(scanMax.getY(), origin.getY() + 40), scanMax.getZ());
                    List<BlockPos> scaffoldTargets = findSuspiciousScaffold(world, origin, scanMin, scaffoldScanMax, bot, scaffoldDeadline);
                    scaffoldTargets.sort(
                            Comparator.<BlockPos>comparingDouble(p -> p.getSquaredDistance(bot.getBlockPos()))
                                    .thenComparing(Comparator.comparingInt(BlockPos::getY).reversed())
                    );
                    int processed = 0;
                    for (BlockPos pos : scaffoldTargets) {
                        if (processed >= maxScaffold || scaffoldMined >= maxScaffold || System.currentTimeMillis() >= scaffoldDeadline) {
                            break;
                        }
                        if (SkillManager.shouldAbortSkill(bot)) {
                            return SkillExecutionResult.failure("woodcut_cleanup paused due to nearby threat.");
                        }
                        BlockPos botPos = bot.getBlockPos();
                        sweepMinX = Math.min(sweepMinX, botPos.getX());
                        sweepMaxX = Math.max(sweepMaxX, botPos.getX());
                        sweepMinY = Math.min(sweepMinY, botPos.getY());
                        sweepMaxY = Math.max(sweepMaxY, botPos.getY());
                        sweepMinZ = Math.min(sweepMinZ, botPos.getZ());
                        sweepMaxZ = Math.max(sweepMaxZ, botPos.getZ());
                        if (TreeDetector.isNearHumanBlocks(world, pos, 3) || TreeDetector.isProtected(world, pos)) {
                            continue;
                        }
                        if (trySimpleScaffoldMine(bot, source, world, pos)) {
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
                BlockPos botPos = bot.getBlockPos();
                sweepMinX = Math.min(sweepMinX, botPos.getX());
                sweepMaxX = Math.max(sweepMaxX, botPos.getX());
                sweepMinY = Math.min(sweepMinY, botPos.getY());
                sweepMaxY = Math.max(sweepMaxY, botPos.getY());
                sweepMinZ = Math.min(sweepMinZ, botPos.getZ());
                sweepMaxZ = Math.max(sweepMaxZ, botPos.getZ());
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
                    MineAttemptResult mineResult = mineWithRetries(bot, source, pos, placedPillar, scaffoldMemory, true);
                    if (mineResult.mined()) {
                        logsMined++;
                        fallbackRemoved++;
                        if (floaterMemory != null) {
                            floaterMemory.remove(pos.asLong());
                        }
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
                        MineAttemptResult mineResult = mineWithRetries(bot, source, pos, placedPillar, scaffoldMemory, false);
                        if (mineResult.mined()) {
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
            double sweepRadius = Math.max(6.0D,
                    Math.max(Math.abs(sweepMaxX - origin.getX()), Math.abs(sweepMinX - origin.getX())) + 3.0D);
            double sweepVertical = Math.max(6.0D, (sweepMaxY - sweepMinY) + 3.0D);
            try {
                // Use a modest sweep radius; caller can always run drop_sweep for a heavier pass.
                net.wcfcarolina13.GameAI.DropSweeper.sweep(
                        source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                        sweepRadius,
                        sweepVertical,
                        Math.max(12, (int) Math.ceil(sweepRadius * 2.0)),
                        12_000L
                );
            } catch (Exception e) {
                LOGGER.warn("woodcut_cleanup drop sweep failed: {}", e.getMessage());
            }
        }

        int remainingRemembered = countRemainingRememberedTargets(world, scanMin, scanMax, floaterMemory);
        CleanupTargetCollection remainingCollection = collectCleanupTargets(
                world,
                bot,
                scanMin,
                scanMax,
                Math.max(System.currentTimeMillis() + 250L, deadline),
                floatingLogsOnly,
                new HashMap<>(),
                floaterMemory,
                new HashSet<>() // fresh cache for final count — we want an accurate picture
        );
        WoodcutCleanupDiagnostics.CleanupScanStats remainingStats = remainingCollection.stats();
        List<CleanupTarget> remainingVisibleLogs = remainingCollection.targets();
        int remainingOverhead = (int) remainingVisibleLogs.stream()
                .filter(t -> t.kind() == CleanupTargetKind.OVERHEAD_SUSPENDED)
                .count();
        String summary;
        if (remainingRemembered == 0 && remainingOverhead == 0 && remainingVisibleLogs.isEmpty()) {
            if (logsMined == 0 && attemptedTargets == 0 && remainingStats.hasBlockedOrRejectedFloaters()) {
                summary = WoodcutCleanupDiagnostics.buildCompletionSummary(
                        false,
                        logsMined,
                        scaffoldMined,
                        attemptedTargets,
                        radius,
                        verticalRange,
                        remainingRemembered,
                        remainingOverhead,
                        remainingVisibleLogs.size(),
                        remainingStats
                );
            } else {
                summary = WoodcutCleanupDiagnostics.buildCompletionSummary(
                        true,
                        logsMined,
                        scaffoldMined,
                        attemptedTargets,
                        radius,
                        verticalRange,
                        remainingRemembered,
                        remainingOverhead,
                        remainingVisibleLogs.size(),
                        remainingStats
                );
            }
        } else {
            summary = WoodcutCleanupDiagnostics.buildCompletionSummary(
                    false,
                    logsMined,
                    scaffoldMined,
                    attemptedTargets,
                    radius,
                    verticalRange,
                    remainingRemembered,
                    remainingOverhead,
                    remainingVisibleLogs.size(),
                    remainingStats
            );
        }
        LOGGER.info("woodcut_cleanup completed: {} [{} remainingRemembered={} remainingOverhead={}]",
                summary, remainingStats.formatCounts(), remainingRemembered, remainingOverhead);
        boolean success = remainingRemembered == 0
                && remainingOverhead == 0
                && remainingVisibleLogs.isEmpty()
                && !(logsMined == 0 && attemptedTargets == 0 && remainingStats.hasBlockedOrRejectedFloaters());
        return success
                ? SkillExecutionResult.success(summary)
                : SkillExecutionResult.failure(summary);
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

    private void recordScaffoldPlacement(Set<Long> memory, BlockPos pos) {
        if (memory == null || pos == null) {
            return;
        }
        memory.add(pos.asLong());
        trimScaffoldMemoryInPlace(memory);
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

    private int countRemainingRememberedTargets(ServerWorld world,
                                                BlockPos min,
                                                BlockPos max,
                                                Set<Long> floaterMemory) {
        if (world == null || floaterMemory == null || floaterMemory.isEmpty()) {
            return 0;
        }
        int remaining = 0;
        for (Long packed : new ArrayList<>(floaterMemory)) {
            if (packed == null) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(packed);
            if (!isWithinRegion(pos, min, max)) {
                continue;
            }
            if (world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                remaining++;
            } else {
                floaterMemory.remove(packed);
            }
        }
        return remaining;
    }

    /**
     * Fast target collection: only remembered floaters + immediate overhead.
     * Returns in milliseconds, giving the bot something to work on while the
     * full region scan runs asynchronously.
     */
    private CleanupTargetCollection collectFastTargets(ServerWorld world,
                                                       ServerPlayerEntity bot,
                                                       BlockPos min,
                                                       BlockPos max,
                                                       Map<Long, Long> failedCooldowns,
                                                       Set<Long> floaterMemory) {
        CleanupScanAccumulator accumulator = new CleanupScanAccumulator();
        pruneExpiredCooldowns(failedCooldowns);
        collectRememberedCleanupTargets(world, min, max, failedCooldowns, floaterMemory, accumulator);
        collectImmediateOverheadCleanupTargets(world, bot, min, max, failedCooldowns, accumulator);
        return accumulator.build(bot);
    }

    private CleanupTargetCollection collectCleanupTargets(ServerWorld world,
                                                          ServerPlayerEntity bot,
                                                          BlockPos min,
                                                          BlockPos max,
                                                          long deadline,
                                                          boolean floatingLogsOnly,
                                                          Map<Long, Long> failedCooldowns,
                                                          Set<Long> floaterMemory,
                                                          Set<Long> rejectionCache) {
        CleanupScanAccumulator accumulator = new CleanupScanAccumulator();
        pruneExpiredCooldowns(failedCooldowns);

        collectRememberedCleanupTargets(world, min, max, failedCooldowns, floaterMemory, accumulator);
        collectImmediateOverheadCleanupTargets(world, bot, min, max, failedCooldowns, accumulator);

        int scanned = 0;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            scanned++;
            if ((scanned & 0x7F) == 0) {
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (SkillManager.shouldAbortSkill(bot)) {
                    break;
                }
            }
            long packed = pos.asLong();
            if (accumulator.orderedTargets.containsKey(packed)) {
                continue;
            }
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            if (isCoolingDown(pos, failedCooldowns)) {
                continue;
            }
            // Skip positions previously classified as non-actionable (full tree, grounded, etc.).
            // This avoids re-running the expensive BFS + tree detection on blocks that haven't changed.
            if (rejectionCache.contains(packed)) {
                continue;
            }
            CleanupTarget target = new CleanupTarget(pos.toImmutable(), CleanupTargetKind.GENERIC_FLOATING);
            TreeDetector.CleanupLogDisposition disposition = TreeDetector.classifyCleanupLog(world, pos, false);
            if (!disposition.actionable()) {
                accumulator.record(pos, null, disposition);
                rejectionCache.add(packed);
                continue;
            }
            accumulator.record(pos, target, disposition);
        }
        return accumulator.build(bot);
    }

    private void collectRememberedCleanupTargets(ServerWorld world,
                                                 BlockPos min,
                                                 BlockPos max,
                                                 Map<Long, Long> failedCooldowns,
                                                 Set<Long> floaterMemory,
                                                 CleanupScanAccumulator accumulator) {
        if (world == null || floaterMemory == null || floaterMemory.isEmpty()) {
            return;
        }
        for (Long packed : new ArrayList<>(floaterMemory)) {
            if (packed == null) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(packed);
            if (!isWithinRegion(pos, min, max)) {
                continue;
            }
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                floaterMemory.remove(packed);
                continue;
            }
            if (isCoolingDown(pos, failedCooldowns)) {
                continue;
            }
            CleanupTarget target = new CleanupTarget(pos.toImmutable(), CleanupTargetKind.REMEMBERED_LEFTOVER);
            TreeDetector.CleanupLogDisposition disposition = classifyCleanupTarget(world, target);
            accumulator.record(pos, disposition.actionable() ? target : null, disposition);
        }
    }

    private void collectImmediateOverheadCleanupTargets(ServerWorld world,
                                                        ServerPlayerEntity bot,
                                                        BlockPos min,
                                                        BlockPos max,
                                                        Map<Long, Long> failedCooldowns,
                                                        CleanupScanAccumulator accumulator) {
        if (world == null || bot == null) {
            return;
        }
        BlockPos origin = bot.getBlockPos();
        BlockPos scanMin = new BlockPos(
                Math.max(min.getX(), origin.getX() - 4),
                Math.max(min.getY(), origin.getY()),
                Math.max(min.getZ(), origin.getZ() - 4)
        );
        BlockPos scanMax = new BlockPos(
                Math.min(max.getX(), origin.getX() + 4),
                Math.min(max.getY(), origin.getY() + 10),
                Math.min(max.getZ(), origin.getZ() + 4)
        );
        for (BlockPos pos : BlockPos.iterate(scanMin, scanMax)) {
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            if (isCoolingDown(pos, failedCooldowns)) {
                continue;
            }
            CleanupTarget target = new CleanupTarget(pos.toImmutable(), CleanupTargetKind.OVERHEAD_SUSPENDED);
            TreeDetector.CleanupLogDisposition disposition = classifyCleanupTarget(world, target);
            if (!disposition.actionable()
                    || disposition.kind() != TreeDetector.CleanupLogDispositionKind.SUSPENDED_FRAGMENT) {
                accumulator.record(pos, null, disposition);
                continue;
            }
            // Reject branches of living trees — the BFS classification can miss offset
            // canopy branches, so verify the log isn't connected to a soil-grounded trunk.
            if (isConnectedToGroundedTree(world, pos, 12)) {
                accumulator.record(pos, null, disposition);
                continue;
            }
            accumulator.record(pos, target, disposition);
        }
    }

    /**
     * BFS through connected log blocks to determine if this log is part of a tree
     * rooted in soil. Handles offset trunks, 2x2 oaks, diagonal branches — follows
     * actual log topology. A log component grounded in soil is a living tree; one
     * sitting on stone/cobblestone/air is scaffold debris or a genuine floater.
     */
    private static boolean isConnectedToGroundedTree(ServerWorld world, BlockPos start, int maxTraversal) {
        if (world == null || start == null || !world.getBlockState(start).isIn(BlockTags.LOGS)) {
            return false;
        }
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(start.toImmutable());
        visited.add(start.asLong());
        while (!queue.isEmpty() && visited.size() <= maxTraversal) {
            BlockPos pos = queue.poll();
            // Check if this log is sitting on valid soil.
            BlockState below = world.getBlockState(pos.down());
            if (!below.isIn(BlockTags.LOGS) && TreeDetector.isValidSoil(below)) {
                return true;
            }
            // Expand to adjacent log blocks.
            for (Direction dir : Direction.values()) {
                BlockPos nb = pos.offset(dir);
                if (visited.contains(nb.asLong())) {
                    continue;
                }
                if (world.getBlockState(nb).isIn(BlockTags.LOGS)) {
                    visited.add(nb.asLong());
                    queue.add(nb.toImmutable());
                }
            }
        }
        return false;
    }

    private CleanupProtectionDecision getCleanupProtectionDecision(ServerWorld world, CleanupTarget target) {
        if (world == null || target == null || target.pos() == null) {
            return new CleanupProtectionDecision(false, "none");
        }
        TreeDetector.CleanupLogDisposition disposition = classifyCleanupTarget(world, target);
        return disposition.actionable()
                ? new CleanupProtectionDecision(false, "none")
                : new CleanupProtectionDecision(true, disposition.reason());
    }

    private TreeDetector.CleanupLogDisposition classifyCleanupTarget(ServerWorld world, CleanupTarget target) {
        boolean remembered = target != null && target.kind() == CleanupTargetKind.REMEMBERED_LEFTOVER;
        return TreeDetector.classifyCleanupLog(world, target != null ? target.pos() : null, remembered);
    }

    private int priorityFor(CleanupTargetKind kind) {
        return switch (kind) {
            case REMEMBERED_LEFTOVER -> 0;
            case OVERHEAD_SUSPENDED -> 1;
            case GENERIC_FLOATING -> 2;
        };
    }

    private boolean isCleanupFloaterCandidate(ServerWorld world,
                                              ServerPlayerEntity bot,
                                              BlockPos pos,
                                              long deadline,
                                              boolean floatingLogsOnly) {
        if (world == null || bot == null || pos == null) {
            return false;
        }
        if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
            return false;
        }
        try {
            if (TreeDetector.detectTreeAt(world, pos).isPresent()) {
                LOGGER.debug("woodcut_cleanup: skipping log {} because it's part of a detected full tree", pos.toShortString());
                return false;
            }
        } catch (Exception ignored) {
        }
        boolean visible = hasLineOfSight(bot, Vec3d.ofCenter(pos));
        if (visible) {
            return true;
        }
        boolean floatingOrOrphan = isFloatingOrOrphanLog(world, pos, bot, deadline);
        boolean suspended = isSuspendedCleanupLog(world, pos);
        if (floatingLogsOnly) {
            return floatingOrOrphan || suspended;
        }
        return floatingOrOrphan || suspended || TreeDetector.hasLeavesNearby(world, pos, 3, 3);
    }

    private boolean isSuspendedCleanupLog(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || !world.getBlockState(pos).isIn(BlockTags.LOGS)) {
            return false;
        }
        BlockPos lowest = pos.toImmutable();
        int depth = 0;
        while (depth < 16 && world.getBlockState(lowest.down()).isIn(BlockTags.LOGS)) {
            lowest = lowest.down();
            depth++;
        }
        BlockPos below = lowest.down();
        BlockState belowState = world.getBlockState(below);
        boolean unsupported = belowState.isAir()
                || belowState.isReplaceable()
                || belowState.isIn(BlockTags.LEAVES)
                || belowState.getCollisionShape(world, below).isEmpty();
        return unsupported && TreeDetector.hasLeavesNearby(world, pos, 3, 3);
    }

    private void pruneExpiredCooldowns(Map<Long, Long> failedCooldowns) {
        if (failedCooldowns == null || failedCooldowns.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        failedCooldowns.entrySet().removeIf(entry -> entry == null || entry.getValue() == null || entry.getValue() <= now);
    }

    private boolean isCoolingDown(BlockPos pos, Map<Long, Long> failedCooldowns) {
        if (pos == null || failedCooldowns == null || failedCooldowns.isEmpty()) {
            return false;
        }
        Long until = failedCooldowns.get(pos.asLong());
        return until != null && until > System.currentTimeMillis();
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

            // Non-dirt scaffold (cobble, stone variants, sand, gravel, etc.) doesn't occur
            // naturally in vertical pillars, so accept even if not isolated — BUT must have
            // at least one exposed face (air/leaves/replaceable). Blocks fully encased in
            // solid terrain are natural underground formations, not scaffold.
            boolean hardBlockPillar = pillarLike && !DIRT_LIKE_SCAFFOLD.contains(state.getBlock().asItem())
                    && (floating || isolated);
            // Dirt columns 3+ tall are clearly bot-placed, even if not isolated.
            boolean tallDirtColumn = false;
            if (DIRT_LIKE_SCAFFOLD.contains(state.getBlock().asItem()) && pillarLike && !hasGrassPlantAbove(world, pos)) {
                // Only count UPWARD — probing downward hits natural terrain (dirt all the way down).
                int columnHeight = 1;
                BlockPos probe = pos.up();
                while (columnHeight < 5 && PILLAR_BLOCKS.contains(world.getBlockState(probe).getBlock().asItem())) {
                    columnHeight++;
                    probe = probe.up();
                }
                // 3+ blocks: always a pillar. 2 blocks: only if the top is plain dirt
                // (not grass_block — natural terrain grows grass on exposed top blocks,
                // but bot-placed dirt stays grassless).
                if (columnHeight >= 3) {
                    tallDirtColumn = true;
                } else if (columnHeight == 2) {
                    // Find the top of this 2-block column.
                    BlockPos top = pos;
                    if (PILLAR_BLOCKS.contains(world.getBlockState(pos.up()).getBlock().asItem())) {
                        top = pos.up();
                    }
                    // Top must be plain dirt (not grass_block) and sun-exposed to be scaffold.
                    BlockState topState = world.getBlockState(top);
                    tallDirtColumn = !topState.isOf(Blocks.GRASS_BLOCK)
                            && isSunExposedOnAnyFace(world, top)
                            && world.getBlockState(top.up()).isAir();
                }
            }
            // For dirt-like blocks, only accept if floating (air below) or proven tall column.
            // The (isolated && pillarLike) path catches natural terrain (exposed grassless dirt on ledges).
            boolean isDirtLike = DIRT_LIKE_SCAFFOLD.contains(state.getBlock().asItem());
            if (floating || (!isDirtLike && isolated && pillarLike) || hardBlockPillar || tallDirtColumn) {
                LOGGER.debug("woodcut_cleanup: scaffold candidate ACCEPT {} state={} floating={} isolated={} pillarLike={}", pos.toShortString(), state.getBlock().toString(), floating, isolated, pillarLike);
                targets.add(pos.toImmutable());
            } else {
                LOGGER.debug("woodcut_cleanup: scaffold candidate REJECT {} state={} floating={} isolated={} pillarLike={}", pos.toShortString(), state.getBlock().toString(), floating, isolated, pillarLike);
            }
        }

        LOGGER.info("woodcut_cleanup scaffold scan: found {} candidates in region {} -> {} (scanned {} blocks)",
                targets.size(), min.toShortString(), max.toShortString(), i);
        return targets;
    }

    /**
     * After mining a block, flood-fill its 6 neighbours for connected logs and mine them
     * in sequence.  Stops at the max/deadline or when no reachable neighbours remain.
     */
    /**
     * While elevated on a pillar, scan for ALL log blocks within reach and mine them
     * before descending. This avoids the wasteful pillar-up/descend/pillar-up cycle
     * where the bot builds and tears down scaffold for each individual target.
     */
    private int mineAllReachableFromElevation(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              ServerWorld world,
                                              Map<Long, Long> failedCooldowns,
                                              int maxRemaining,
                                              long deadline) {
        if (maxRemaining <= 0 || bot == null || world == null) {
            return 0;
        }
        int mined = 0;
        Set<Long> attempted = new HashSet<>();
        // Keep scanning until no more reachable logs found.
        for (int pass = 0; pass < 20 && mined < maxRemaining && System.currentTimeMillis() < deadline; pass++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            boolean foundAny = false;
            BlockPos botPos = bot.getBlockPos();
            // Scan a 5-block cube around the bot for log blocks.
            for (BlockPos pos : BlockPos.iterate(botPos.add(-4, -2, -4), botPos.add(4, 4, 4))) {
                if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    continue;
                }
                long key = pos.asLong();
                if (attempted.contains(key) || isCoolingDown(pos, failedCooldowns)) {
                    continue;
                }
                if (!isReadyToMineTarget(bot, pos)) {
                    continue;
                }
                // Only mine logs that are genuine floaters, not branches of living trees.
                TreeDetector.CleanupLogDisposition disp = TreeDetector.classifyCleanupLog(world, pos, false);
                if (!disp.actionable() || isConnectedToGroundedTree(world, pos, 12)) {
                    attempted.add(key);
                    continue;
                }
                attempted.add(key);
                boolean success = mineBlock(bot, pos, true);
                if (success) {
                    mined++;
                    foundAny = true;
                    failedCooldowns.remove(key);
                }
            }
            if (!foundAny) {
                break;
            }
        }
        return mined;
    }

    private int chaseAdjacentLogs(ServerPlayerEntity bot,
                                  ServerCommandSource source,
                                  ServerWorld world,
                                  BlockPos origin,
                                  List<BlockPos> placedPillar,
                                  Set<Long> scaffoldMemory,
                                  Map<Long, Long> failedCooldowns,
                                  int maxRemaining,
                                  long deadline) {
        if (maxRemaining <= 0) {
            return 0;
        }
        int mined = 0;
        Deque<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        visited.add(origin.asLong());
        // Seed frontier with neighbours of the just-mined block.
        for (Direction dir : Direction.values()) {
            BlockPos nb = origin.offset(dir).toImmutable();
            if (visited.add(nb.asLong())) {
                frontier.add(nb);
            }
        }
        while (!frontier.isEmpty() && mined < maxRemaining && System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            BlockPos pos = frontier.poll();
            if (!world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            if (isCoolingDown(pos, failedCooldowns)) {
                continue;
            }
            // Don't chase into a living tree — stop if this log is part of a grounded trunk.
            if (isConnectedToGroundedTree(world, pos, 12)) {
                continue;
            }
            // Try to mine this neighbour (reuse reach + mine logic).
            int pillarSizeBefore = placedPillar.size();
            MineAttemptResult result = mineWithRetries(bot, source, pos, placedPillar, scaffoldMemory, true);
            cleanupFreshPillarBlocks(bot, placedPillar, pillarSizeBefore);
            tryHoleRecovery(bot, source, world, placedPillar, scaffoldMemory);
            if (result.mined()) {
                mined++;
                failedCooldowns.remove(pos.asLong());
                // Expand frontier with this block's neighbours.
                for (Direction dir : Direction.values()) {
                    BlockPos nb = pos.offset(dir).toImmutable();
                    if (visited.add(nb.asLong())) {
                        frontier.add(nb);
                    }
                }
            } else {
                failedCooldowns.put(pos.asLong(), System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
            }
        }
        return mined;
    }

    private MineAttemptResult mineWithRetries(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              CleanupTarget target,
                                              List<BlockPos> placedPillar,
                                              Set<Long> scaffoldMemory,
                                              boolean preferAxe) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new MineAttemptResult(false, false, "no-world");
        }
        CleanupProtectionDecision decision = getCleanupProtectionDecision(world, target);
        if (decision.blocked()) {
            return new MineAttemptResult(false, false, decision.reason());
        }
        boolean visibilityChanged = false;
        String lastFailureReason = "path-failed";
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return new MineAttemptResult(false, visibilityChanged, "abort-requested");
            }

            ReachResult reachResult = prepareReach(bot, source, target.pos(), placedPillar, scaffoldMemory);
            visibilityChanged = visibilityChanged || reachResult.visibilityChanged();
            if (!reachResult.ready()) {
                return new MineAttemptResult(false, visibilityChanged, reachResult.failureReason());
            }

            if (!hasLineOfSight(bot, Vec3d.ofCenter(target.pos()))) {
                int leavesBroken = clearBlockingLeaves(bot, target.pos());
                visibilityChanged = visibilityChanged || leavesBroken > 0;
                if (!hasLineOfSight(bot, Vec3d.ofCenter(target.pos())) && !tryLineOfSightStand(source, bot, target.pos())) {
                    return new MineAttemptResult(false, visibilityChanged, "no-los-stand");
                }
            }
            boolean mined = mineBlock(bot, target.pos(), preferAxe);
            if (mined) {
                return new MineAttemptResult(true, visibilityChanged, "complete");
            }
            lastFailureReason = "mine-failed";
        }
        return new MineAttemptResult(false, visibilityChanged, lastFailureReason);
    }

    private MineAttemptResult mineWithRetries(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              BlockPos target,
                                              List<BlockPos> placedPillar,
                                              Set<Long> scaffoldMemory,
                                              boolean preferAxe) {
        boolean visibilityChanged = false;
        String lastFailureReason = "path-failed";
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return new MineAttemptResult(false, visibilityChanged, "abort-requested");
            }

            ReachResult reachResult = prepareReach(bot, source, target, placedPillar, scaffoldMemory);
            visibilityChanged = visibilityChanged || reachResult.visibilityChanged();
            if (!reachResult.ready()) {
                return new MineAttemptResult(false, visibilityChanged, reachResult.failureReason());
            }

            if (!hasLineOfSight(bot, Vec3d.ofCenter(target))) {
                int leavesBroken = clearBlockingLeaves(bot, target);
                visibilityChanged = visibilityChanged || leavesBroken > 0;
                if (!hasLineOfSight(bot, Vec3d.ofCenter(target)) && !tryLineOfSightStand(source, bot, target)) {
                    return new MineAttemptResult(false, visibilityChanged, "no-los-stand");
                }
            }
            boolean mined = mineBlock(bot, target, preferAxe);
            if (mined) {
                return new MineAttemptResult(true, visibilityChanged, "complete");
            }
            lastFailureReason = "mine-failed";
        }
        return new MineAttemptResult(false, visibilityChanged, lastFailureReason);
    }

    private ReachResult prepareReach(ServerPlayerEntity bot,
                                     ServerCommandSource source,
                                     BlockPos target,
                                     List<BlockPos> placedPillar,
                                     Set<Long> scaffoldMemory) {
        boolean visibilityChanged = false;
        if (isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, false, "ready");
        }

        int heightDelta = target.getY() - bot.getBlockY();
        boolean overheadTarget = heightDelta >= 3;

        if (overheadTarget) {
            // ── Fast path for overhead blocks ───────────────────────────
            // Skip futile pathfinding to target Y (unreachable without pillar).
            // Instead: walk to the XZ column below the target, clear leaves, pillar up.
            moveToColumnBelow(source, bot, target);
            // Clear leaves between us and the target before attempting pillar/mine.
            int leavesBroken = clearBlockingLeaves(bot, target);
            visibilityChanged = leavesBroken > 0;
            if (isReadyToMineTarget(bot, target)) {
                return new ReachResult(true, visibilityChanged, "overhead-direct");
            }
            int reachBaselineY = bot.getBlockY();
            int needed = WoodcutCleanupReachHeuristics.requiredPillarSteps(bot.getBlockY(), target.getY());
            if (needed > MAX_CLEANUP_PILLAR_STEPS) {
                LOGGER.info("woodcut_cleanup reach: target {} needs {} pillar steps (max {}), skipping",
                        target.toShortString(), needed, MAX_CLEANUP_PILLAR_STEPS);
                return new ReachResult(false, visibilityChanged, "pillar-too-high");
            }
            if (needed > 0) {
                LOGGER.info("woodcut_cleanup reach: pillaring {} blocks to reach {}", needed, target.toShortString());
                if (!pillarUp(bot, needed, placedPillar, scaffoldMemory)) {
                    return new ReachResult(false, visibilityChanged,
                            WoodcutCleanupReachHeuristics.elevatedReachFailureReason(reachBaselineY, target.getY(), false));
                }
                leavesBroken = clearBlockingLeaves(bot, target);
                visibilityChanged = visibilityChanged || leavesBroken > 0;
                if (isReadyToMineTarget(bot, target)) {
                    return new ReachResult(true, visibilityChanged, "pillar-reached");
                }
            }
            // After pillar + leaf clear, if still not reachable, give up quickly.
            // tryLineOfSightStand is expensive (walks to multiple candidate positions)
            // and rarely helps after a pillar attempt already got the bot close.
            return new ReachResult(false, visibilityChanged, "overhead-unreachable");
        }

        // ── Standard path for blocks near bot level ─────────────────
        moveUnderTarget(source, bot, target);
        if (isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, false, "moved-under-target");
        }
        if (tryReposition(bot, source, target) && isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, false, "repositioned");
        }

        int leavesBroken = clearBlockingLeaves(bot, target);
        visibilityChanged = leavesBroken > 0;
        if (isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "leaf-clear-opened-los");
        }
        moveUnderTarget(source, bot, target);
        if (isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "moved-under-target-after-leaf-clear");
        }
        if (tryReposition(bot, source, target) && isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "repositioned-after-leaf-clear");
        }
        if (tryLineOfSightStand(source, bot, target) && isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "line-of-sight-stand");
        }

        int reachBaselineY = bot.getBlockY();
        int needed = WoodcutCleanupReachHeuristics.requiredPillarSteps(bot.getBlockY(), target.getY());
        if (needed <= 0) {
            LOGGER.debug("woodcut_cleanup reach blocked: bot={} target={} reason=no-reachable-stand",
                    bot.getName().getString(),
                    target.toShortString());
            return new ReachResult(false, visibilityChanged, "no-reachable-stand");
        }
        if (needed > MAX_CLEANUP_PILLAR_STEPS) {
            LOGGER.info("woodcut_cleanup reach: target {} needs {} pillar steps (max {}), skipping",
                    target.toShortString(), needed, MAX_CLEANUP_PILLAR_STEPS);
            return new ReachResult(false, visibilityChanged, "pillar-too-high");
        }

        LOGGER.info("woodcut_cleanup reach: pillaring {} blocks to reach {}", needed, target.toShortString());
        if (!pillarUp(bot, needed, placedPillar, scaffoldMemory)) {
            LOGGER.warn("woodcut_cleanup reach blocked: bot={} target={} reason={}",
                    bot.getName().getString(),
                    target.toShortString(),
                    WoodcutCleanupReachHeuristics.elevatedReachFailureReason(reachBaselineY, target.getY(), false));
            return new ReachResult(false, visibilityChanged, WoodcutCleanupReachHeuristics.elevatedReachFailureReason(reachBaselineY, target.getY(), false));
        }
        if (isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "pillar-reached");
        }
        if (tryReposition(bot, source, target) && isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "post-pillar-reposition");
        }
        if (tryLineOfSightStand(source, bot, target) && isReadyToMineTarget(bot, target)) {
            return new ReachResult(true, visibilityChanged, "post-pillar-los-stand");
        }
        LOGGER.warn("woodcut_cleanup reach blocked: bot={} target={} reason={}",
                bot.getName().getString(),
                target.toShortString(),
                WoodcutCleanupReachHeuristics.elevatedReachFailureReason(reachBaselineY, target.getY(), true));
        return new ReachResult(false, visibilityChanged, WoodcutCleanupReachHeuristics.elevatedReachFailureReason(reachBaselineY, target.getY(), true));
    }

    /**
     * Walk to the ground-level position directly below the target.
     * Unlike moveUnderTarget (which paths to the target Y), this stays at ground level.
     */
    private void moveToColumnBelow(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        // Find solid ground below the target.
        BlockPos ground = findColumnStand(world, target);
        if (ground == null) {
            ground = new BlockPos(target.getX(), bot.getBlockY(), target.getZ());
        }
        try {
            net.wcfcarolina13.GameAI.services.MovementService.MovementPlan plan =
                    new net.wcfcarolina13.GameAI.services.MovementService.MovementPlan(
                            net.wcfcarolina13.GameAI.services.MovementService.Mode.DIRECT,
                            ground,
                            ground,
                            null,
                            null,
                            bot.getHorizontalFacing());
            net.wcfcarolina13.GameAI.services.MovementService.execute(source, bot, plan, false, true, true, false);
        } catch (Exception ignored) {
        }
    }

    private boolean hasLeafBlockerOnRay(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world) || bot == null || target == null) {
            return false;
        }
        Vec3d eye = bot.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        int steps = Math.max(4, (int) Math.ceil(eye.distanceTo(center) * 4.0D));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            BlockPos sample = BlockPos.ofFloored(eye.lerp(center, t));
            if (sample.equals(target)) {
                continue;
            }
            if (world.getBlockState(sample).isIn(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryLineOfSightStand(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos column = findColumnStand(world, target);
        if (column != null) {
            candidates.add(column);
        }
        for (int radius = 1; radius <= 2; radius++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos probe = target.offset(direction, radius);
                BlockPos stand = findDryStandableNear(world, probe, 1, 3);
                if (stand != null) {
                    candidates.add(stand.toImmutable());
                }
            }
        }
        candidates.sort(Comparator.<BlockPos>comparingInt(p -> hasLineOfSightFrom(world, bot, p, target) ? 0 : 1)
                .thenComparingDouble(p -> p.getSquaredDistance(bot.getBlockPos())));
        for (BlockPos stand : candidates) {
            if (!isDryStableCleanupStand(world, stand)) {
                continue;
            }
            net.wcfcarolina13.GameAI.services.MovementService.MovementPlan plan =
                    new net.wcfcarolina13.GameAI.services.MovementService.MovementPlan(
                            net.wcfcarolina13.GameAI.services.MovementService.Mode.DIRECT,
                            stand,
                            stand,
                            null,
                            null,
                            bot.getHorizontalFacing());
            net.wcfcarolina13.GameAI.services.MovementService.MovementResult result =
                    net.wcfcarolina13.GameAI.services.MovementService.execute(source, bot, plan, false, true, true, false);
            if ((result.success() || bot.getBlockPos().getSquaredDistance(stand) <= 4.0D)
                    && isWithinReach(bot, target)
                    && hasLineOfSight(bot, Vec3d.ofCenter(target))) {
                return true;
            }
        }
        return false;
    }

    private boolean tryReposition(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        try {
            var planOpt = net.wcfcarolina13.GameAI.services.MovementService.planLootApproach(
                    bot,
                    target,
                    net.wcfcarolina13.GameAI.services.MovementService.MovementOptions.skillLoot()
            );
            if (planOpt.isEmpty()) {
                return false;
            }
            net.wcfcarolina13.GameAI.services.MovementService.MovementPlan plan = planOpt.get();
            BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
            if (approach != null && !isDryStableCleanupStand(world, approach)) {
                return false;
            }
            net.wcfcarolina13.GameAI.services.MovementService.MovementResult result =
                    net.wcfcarolina13.GameAI.services.MovementService.execute(source, bot, plan, false, true, true, false);
            return result.success() || canAttemptMineTarget(bot, target);
        } catch (Exception e) {
            LOGGER.debug("woodcut_cleanup reposition failed for {}: {}", target.toShortString(), e.getMessage());
            return false;
        }
    }

    private boolean pillarUp(ServerPlayerEntity bot, int steps, List<BlockPos> placedPillar, Set<Long> scaffoldMemory) {
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
        int startY = bot.getBlockY();
        try {
            for (int i = 0; i < steps; i++) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return false;
                }
                // Abort if the bot has fallen below its starting Y — something went wrong
                // and continuing would just dig the bot deeper via clearOverheadForPillar.
                if (bot.getBlockY() < startY) {
                    LOGGER.warn("woodcut_cleanup pillarUp: bot fell from Y={} to Y={}, aborting", startY, bot.getBlockY());
                    return false;
                }
                // Clear overhead blocks before jumping — dirt, gravel, leaves, etc.
                // Without this the bot gets stuck when terrain is above its head.
                clearOverheadForPillar(bot, world);
                BlockPos candidate = bot.getBlockPos();
                if (!world.getBlockState(candidate).isAir()) {
                    candidate = candidate.up();
                }
                BotActions.jump(bot);
                sleepQuiet(PILLAR_STEP_DELAY_MS);
                if (!world.getBlockState(candidate).isAir()) {
                    candidate = candidate.up();
                }
                if (!tryPlaceScaffold(bot, candidate, scaffoldMemory)) {
                    return false;
                }
                placedPillar.add(candidate.toImmutable());
                sleepQuiet(PILLAR_STEP_DELAY_MS);
                // Update startY to track upward progress.
                startY = Math.max(startY, bot.getBlockY());
            }
            return true;
        } finally {
            bot.setSneaking(wasSneaking);
        }
    }

    /**
     * Mine blocks directly above the bot's head (up to 3 blocks) to ensure
     * the next jump during pillaring won't be blocked by terrain.
     */
    private void clearOverheadForPillar(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos head = bot.getBlockPos().up(2); // block above head
        for (int dy = 0; dy < 3; dy++) {
            BlockPos check = head.up(dy);
            BlockState state = world.getBlockState(check);
            if (state.isAir() || state.getCollisionShape(world, check).isEmpty()) {
                break; // clear above — no more obstacles
            }
            // Mine the overhead block.
            LookController.faceBlock(bot, check);
            CompletableFuture<String> future = MiningTool.mineBlock(bot, check);
            try {
                future.get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.debug("clearOverheadForPillar failed at {}: {}", check.toShortString(), e.getMessage());
                break;
            }
        }
    }

    private boolean tryPlaceScaffold(ServerPlayerEntity bot, BlockPos target, Set<Long> scaffoldMemory) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos placePos = target;
        if (!canMutateRecoveryBlock(world, placePos)) {
            return false;
        }
        if (!isPlaceableTarget(world, placePos)) {
            breakSoftBlock(world, bot, placePos);
        }
        ensureSupportBlock(bot, placePos, scaffoldMemory);
        if (BotActions.placeBlockAt(bot, placePos, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(scaffoldMemory, placePos);
            return true;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos alt = placePos.offset(dir);
            if (!canMutateRecoveryBlock(world, alt)) {
                continue;
            }
            if (!isPlaceableTarget(world, alt)) {
                breakSoftBlock(world, bot, alt);
            }
            ensureSupportBlock(bot, alt, scaffoldMemory);
            if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                recordScaffoldPlacement(scaffoldMemory, alt);
                return true;
            }
        }
        return false;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot, BlockPos target, Set<Long> scaffoldMemory) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos below = target.down();
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return;
        }
        if (!canMutateRecoveryBlock(world, below)) {
            return;
        }
        if (!isPlaceableTarget(world, below)) {
            breakSoftBlock(world, bot, below);
        }
        if (BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(scaffoldMemory, below);
        }
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW);
    }

    private void breakSoftBlock(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (!canMutateRecoveryBlock(world, pos)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isReplaceable()) {
            breakLeaf(bot, pos);
        }
    }

    private int clearBlockingLeaves(ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return 0;
        }
        selectLeafTool(bot);
        Vec3d eye = bot.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        int steps = Math.max(4, (int) Math.ceil(eye.distanceTo(center) * 4.0D));
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            BlockPos sample = BlockPos.ofFloored(eye.lerp(center, t));
            if (world.getBlockState(sample).isIn(BlockTags.LEAVES)) {
                candidates.add(sample.toImmutable());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos neighbor = target.add(dx, dy, dz);
                    if (world.getBlockState(neighbor).isIn(BlockTags.LEAVES)) {
                        candidates.add(neighbor.toImmutable());
                    }
                }
            }
        }
        int broken = 0;
        for (BlockPos leafPos : candidates) {
            if (broken >= MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT) {
                break;
            }
            BlockState leafState = world.getBlockState(leafPos);
            if (!leafState.isIn(BlockTags.LEAVES)) {
                continue;
            }
            // Skip leaves that are already decaying (distance=7, not player-placed).
            // They will disappear on their own via random tick within seconds.
            if (isDecayingLeaf(leafState)) {
                continue;
            }
            if (!canMutateRecoveryBlock(world, leafPos)) {
                continue;
            }
            boolean before = leafState.isAir();
            breakLeaf(bot, leafPos);
            if (!before && world.getBlockState(leafPos).isAir()) {
                broken++;
            }
        }
        return broken;
    }

    /**
     * Returns true if this leaf block will decay naturally (distance=7 and not player-placed).
     * Such leaves have lost connection to any log and will disappear on a random tick.
     */
    private static boolean isDecayingLeaf(BlockState state) {
        if (state == null || !state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        // Player-placed leaves have persistent=true and never decay.
        if (state.contains(LeavesBlock.PERSISTENT) && Boolean.TRUE.equals(state.get(LeavesBlock.PERSISTENT))) {
            return false;
        }
        // distance=7 means no log within 6 blocks through leaf chain → will decay.
        if (state.contains(LeavesBlock.DISTANCE)) {
            return state.get(LeavesBlock.DISTANCE) >= 7;
        }
        return false;
    }

    private boolean selectLeafTool(ServerPlayerEntity bot) {
        if (BotActions.selectBestTool(bot, "shears", "")) {
            return true;
        }
        return selectHandsOrHarmlessItem(bot);
    }

    private boolean hasToolKeyword(ServerPlayerEntity bot, String keyword) {
        if (bot == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (key.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean selectHandsOrHarmlessItem(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
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
        BotActions.selectHotbarSlot(bot, 0);
        return true;
    }

    /**
     * Select the best tool for mining a scaffold block.
     * Dirt/gravel/sand → shovel.  Cobblestone/deepslate/netherrack → pickaxe.
     * Falls back to any available tool rather than mining with dirt in hand.
     */
    private void selectScaffoldTool(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null) {
            return;
        }
        if (pos != null && bot.getEntityWorld() instanceof ServerWorld world) {
            BlockState state = world.getBlockState(pos);
            String blockKey = state.getBlock().getTranslationKey().toLowerCase(Locale.ROOT);
            if (blockKey.contains("cobble") || blockKey.contains("deepslate") || blockKey.contains("netherrack") || blockKey.contains("stone")) {
                if (hasToolKeyword(bot, "pickaxe")) {
                    BotActions.selectBestTool(bot, "pickaxe", "");
                    return;
                }
            }
        }
        if (hasToolKeyword(bot, "shovel")) {
            BotActions.selectBestTool(bot, "shovel", "axe");
            return;
        }
        if (hasToolKeyword(bot, "pickaxe")) {
            BotActions.selectBestTool(bot, "pickaxe", "");
            return;
        }
        selectHandsOrHarmlessItem(bot);
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
            mining = MiningTool.mineBlock(bot, pos, true);
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
            return false;
        }

        LookController.faceBlock(bot, pos);
        if (preferAxe) {
            ensureAxeEquipped(bot);
        } else {
            // For scaffold removal, select tool based on block type.
            selectScaffoldTool(bot, pos);
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

    /**
     * If pillar blocks were placed during a mine attempt, immediately descend and clean
     * them up rather than deferring to the finally block (where the bot may have already
     * fallen and can't reach upper blocks).
     */
    /**
     * Scaffold removal: walk to the block, pillar if elevated, mine it.
     * Keeps movement fast (5-second budget per walk) and cleans up any pillar blocks used.
     */
    private boolean trySimpleScaffoldMine(ServerPlayerEntity bot, ServerCommandSource source,
                                          ServerWorld world, BlockPos pos) {
        if (world.getBlockState(pos).isAir()) {
            return true; // already gone
        }
        // Already within reach? Mine directly.
        if (isReadyToMineTarget(bot, pos)) {
            selectScaffoldTool(bot, pos);
            return mineBlock(bot, pos, false);
        }
        // Walk to column below the target.
        moveToColumnBelow(source, bot, pos);
        if (isReadyToMineTarget(bot, pos)) {
            selectScaffoldTool(bot, pos);
            return mineBlock(bot, pos, false);
        }
        // If scaffold is above us, pillar up to reach it.
        int needed = WoodcutCleanupReachHeuristics.requiredPillarSteps(bot.getBlockY(), pos.getY());
        if (needed > 0 && needed <= MAX_CLEANUP_PILLAR_STEPS) {
            LOGGER.info("woodcut_cleanup scaffold: pillaring {} to reach {} (botY={})",
                    needed, pos.toShortString(), bot.getBlockY());
            List<BlockPos> tempPillar = new ArrayList<>();
            Set<Long> tempMemory = new HashSet<>();
            if (pillarUp(bot, needed, tempPillar, tempMemory)) {
                selectScaffoldTool(bot, pos);
                boolean mined = mineBlock(bot, pos, false);
                // Mine everything reachable while elevated (other floating scaffold).
                mineAllReachableFromElevation(bot, source, world, new HashMap<>(), 8, System.currentTimeMillis() + 10_000L);
                descendAndCleanup(bot, tempPillar);
                return mined || world.getBlockState(pos).isAir();
            }
            LOGGER.warn("woodcut_cleanup scaffold: pillar failed for {}", pos.toShortString());
            descendAndCleanup(bot, tempPillar);
        } else if (needed > MAX_CLEANUP_PILLAR_STEPS) {
            LOGGER.info("woodcut_cleanup scaffold: {} needs {} pillar steps (max {}), skipping",
                    pos.toShortString(), needed, MAX_CLEANUP_PILLAR_STEPS);
        }
        return world.getBlockState(pos).isAir();
    }

    private void cleanupFreshPillarBlocks(ServerPlayerEntity bot, List<BlockPos> placedPillar, int sizeBefore) {
        if (placedPillar.size() <= sizeBefore) {
            return;
        }
        List<BlockPos> fresh = new ArrayList<>(placedPillar.subList(sizeBefore, placedPillar.size()));
        placedPillar.subList(sizeBefore, placedPillar.size()).clear();
        descendAndCleanup(bot, fresh);
    }

    /**
     * Detect if the bot has fallen into a hole and attempt to recover.
     * Strategy 1: Try walking to nearby surface-level ground (Baritone handles step-ups).
     * Strategy 2: Pillar up but KEEP the blocks — they serve as an escape route and the
     *             scaffold removal phase's heuristic scan will clean them up later.
     */
    private void tryHoleRecovery(ServerPlayerEntity bot, ServerCommandSource source,
                                 ServerWorld world,
                                 List<BlockPos> placedPillar, Set<Long> scaffoldMemory) {
        // Use walkable ground Y that scans through tree trunks (heightmap counts logs).
        int botX = bot.getBlockX();
        int botZ = bot.getBlockZ();
        int medianSurface = SafePositionService.getWalkableGroundY(world, botX, botZ);
        int depth = medianSurface - bot.getBlockY();
        if (depth < 2) {
            return;
        }
        LOGGER.info("woodcut_cleanup hole recovery: bot Y={}, surface Y={}, depth={}",
                bot.getBlockY(), medianSurface, depth);
        CompanionOverheadHologramService.show(bot, "Climbing out of hole...", 5_000);

        // Strategy 1: Try walking to nearby surface-level ground in each cardinal direction.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos walkTarget = findNearestSurfaceWalkTarget(world, bot.getBlockPos(), dir, 10);
            if (walkTarget == null) {
                continue;
            }
            try {
                net.wcfcarolina13.GameAI.services.MovementService.MovementPlan plan =
                        new net.wcfcarolina13.GameAI.services.MovementService.MovementPlan(
                                net.wcfcarolina13.GameAI.services.MovementService.Mode.DIRECT,
                                walkTarget, walkTarget, null, null, bot.getHorizontalFacing());
                net.wcfcarolina13.GameAI.services.MovementService.execute(source, bot, plan, false, true, true, false);
            } catch (Exception ignored) {
            }
            // Check if bot reached surface.
            int newSurfaceY = SafePositionService.getWalkableGroundY(world, bot.getBlockX(), bot.getBlockZ());
            if (bot.getBlockY() >= newSurfaceY - 1) {
                LOGGER.info("woodcut_cleanup hole recovery: walked out via {} to Y={}",
                        dir.asString(), bot.getBlockY());
                return;
            }
        }

        // Strategy 2: Pillar up and KEEP the blocks (don't collapse — scaffold removal handles later).
        int needed = Math.min(depth + 1, 8);
        LOGGER.info("woodcut_cleanup hole recovery: walking failed, pillaring {} blocks (keeping scaffold)", needed);
        pillarUp(bot, needed, placedPillar, scaffoldMemory);
        // NOTE: no cleanupFreshPillarBlocks — keeping the pillar as escape route
    }

    /**
     * Scan along a direction for the nearest position at surface level (heightmap Y).
     * Returns null if no walkable surface-level position found within maxDist blocks.
     */
    /**
     * Find a position outside the current scan region to roam to.
     * Picks the nearest standable surface position ~radius blocks away in each
     * cardinal direction and returns the best one.
     */
    private BlockPos findRoamTarget(ServerPlayerEntity bot, ServerWorld world,
                                    BlockPos scanMin, BlockPos scanMax, int radius) {
        BlockPos botPos = bot.getBlockPos();
        int roamDist = Math.max(12, radius);
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos probe = botPos.offset(dir, roamDist);
            int surfaceY = SafePositionService.getWalkableGroundY(world, probe.getX(), probe.getZ());
            BlockPos candidate = new BlockPos(probe.getX(), surfaceY, probe.getZ());
            // Must be outside current scan region.
            if (isWithinRegion(candidate, scanMin, scanMax)) {
                continue;
            }
            // Must be standable.
            if (world.getBlockState(candidate.down()).getCollisionShape(world, candidate.down()).isEmpty()) {
                continue;
            }
            double distSq = botPos.getSquaredDistance(candidate);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    private BlockPos findNearestSurfaceWalkTarget(ServerWorld world, BlockPos origin, Direction dir, int maxDist) {
        for (int dist = 1; dist <= maxDist; dist++) {
            BlockPos probe = origin.offset(dir, dist);
            int topY = SafePositionService.getWalkableGroundY(world, probe.getX(), probe.getZ());
            // Surface-level: the top Y is close to the probe XZ ground, not another hole.
            if (topY > origin.getY() && topY - origin.getY() <= 4) {
                // Check the position is actually standable (solid below, air at feet+head).
                BlockPos stand = new BlockPos(probe.getX(), topY - 1, probe.getZ());
                if (!world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()
                        && world.getBlockState(stand.up()).isAir()) {
                    return stand;
                }
            }
        }
        return null;
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

    private boolean hasLineOfSightFrom(ServerWorld world, ServerPlayerEntity bot, BlockPos foot, BlockPos target) {
        if (world == null || bot == null || foot == null || target == null) {
            return false;
        }
        Vec3d eye = new Vec3d(foot.getX() + 0.5D, foot.getY() + bot.getStandingEyeHeight(), foot.getZ() + 0.5D);
        Vec3d targetCenter = Vec3d.ofCenter(target);
        RaycastContext ctx = new RaycastContext(
                eye,
                targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private boolean isReadyToMineTarget(ServerPlayerEntity bot, BlockPos target) {
        return canAttemptMineTarget(bot, target) && hasLineOfSight(bot, Vec3d.ofCenter(target));
    }

    private boolean canAttemptMineTarget(ServerPlayerEntity bot, BlockPos target) {
        return bot != null
                && target != null
                && isWithinReach(bot, target)
                && (hasLineOfSight(bot, Vec3d.ofCenter(target)) || hasLeafBlockerOnRay(bot, target));
    }

    private BlockPos findColumnStand(ServerWorld world, BlockPos target) {
        BlockPos cursor = target.down();
        for (int i = 0; i < 6 && cursor.getY() > world.getBottomY(); i++) {
            BlockPos foot = cursor.toImmutable();
            if (isDryStableCleanupStand(world, foot)) {
                return foot;
            }
            cursor = cursor.down();
        }
        return null;
    }

    private BlockPos findDryStandableNear(ServerWorld world, BlockPos center, int radius, int ySpan) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -ySpan, -radius), center.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            if (isDryStableCleanupStand(world, foot)) {
                candidates.add(foot);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return candidates.get(0);
    }

    private boolean isDryStableCleanupStand(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null || !world.isChunkLoaded(foot)) {
            return false;
        }
        BlockPos head = foot.up();
        BlockPos below = foot.down();
        if (!world.getFluidState(foot).isEmpty()
                || !world.getFluidState(head).isEmpty()
                || !world.getFluidState(below).isEmpty()) {
            return false;
        }
        if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
            return false;
        }
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    private boolean canMutateRecoveryBlock(ServerWorld world, BlockPos pos) {
        return world != null
                && pos != null
                && !TreeDetector.isProtected(world, pos)
                && !TreeDetector.isNearHumanBlocks(world, pos, 3);
    }

    private void moveUnderTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        try {
            var planOpt = net.wcfcarolina13.GameAI.services.MovementService.planLootApproach(
                    bot,
                    target,
                    net.wcfcarolina13.GameAI.services.MovementService.MovementOptions.skillLoot()
            );
            planOpt.ifPresent(plan -> net.wcfcarolina13.GameAI.services.MovementService.execute(source, bot, plan, false, true, false, false));
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

    /**
     * Offload cleanup loot (logs, saplings, leaf litter, apples, dirt, gravel) to a
     * nearby chest when inventory is full. Places a new chest if none found.
     */
    private void tryCleanupChestOffload(ServerCommandSource source, ServerPlayerEntity bot) {
        if (bot == null || source == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        LOGGER.info("woodcut_cleanup: inventory full, attempting chest offload");
        CompanionOverheadHologramService.show(bot, "Storing items...", 5_000);
        // Find nearby chests.
        BlockPos botPos = bot.getBlockPos();
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos probe : BlockPos.iterate(botPos.add(-18, -6, -18), botPos.add(18, 6, 18))) {
            BlockState state = world.getBlockState(probe);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                chests.add(probe.toImmutable());
            }
        }
        chests.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(botPos)));
        for (BlockPos chest : chests) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chest, this::isCleanupOffloadItem);
            if (moved > 0) {
                LOGGER.info("woodcut_cleanup: offloaded {} items to chest at {}", moved, chest.toShortString());
                return;
            }
        }
        // No existing chest worked — place a new one.
        BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, true);
        if (placed != null) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, placed, this::isCleanupOffloadItem);
            LOGGER.info("woodcut_cleanup: placed chest at {}, offloaded {} items", placed.toShortString(), moved);
        }
    }

    private boolean isCleanupOffloadItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        // Keep pillar blocks (scaffold material) and tools — offload everything else.
        if (PILLAR_BLOCKS.contains(stack.getItem())) {
            return false;
        }
        if (stack.isOf(Items.CHEST) || stack.isOf(Items.CRAFTING_TABLE)) {
            return false;
        }
        // Offload: logs, planks, saplings, leaf litter, apples, seeds, raw food.
        if (stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.PLANKS)) {
            return true;
        }
        if (stack.isOf(Items.STICK) || stack.isOf(Items.APPLE) || stack.isOf(Items.LEAF_LITTER)) {
            return true;
        }
        Item item = stack.getItem();
        if (item instanceof BlockItem bi) {
            BlockState bs = bi.getBlock().getDefaultState();
            if (bs.isIn(BlockTags.LOGS) || bs.isIn(BlockTags.PLANKS) || bs.isIn(BlockTags.SAPLINGS)) {
                return true;
            }
        }
        return false;
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
