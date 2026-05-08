package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.BlockItem;
import net.wcfcarolina13.GameAI.services.CompanionOverheadHologramService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.FollowMovementService;
import net.wcfcarolina13.GameAI.services.ToolProvisionService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.MappedVillageNavigationService;
import net.wcfcarolina13.GameAI.services.ReturnBaseStuckService;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.WoodcutCleanupMemoryService;
import net.wcfcarolina13.GameAI.services.WoodcutKnowledgeService;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService;
import net.wcfcarolina13.GameAI.services.construction.BridgeScaffoldService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.GameAI.skills.support.WoodcutHazardScanner;
import net.wcfcarolina13.PathFinding.GoTo;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Fell natural trees while avoiding player-built structures. Uses an axe for logs and
 * switches to non-axes for leaves when clearing access. Crafts an axe if materials exist.
 */
public final class WoodcutSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-woodcut");
    private static final int DEFAULT_TREE_COUNT_INTERNAL = 1;
    private static final int DEFAULT_TREE_COUNT_STANDALONE = 4;
    private static final int DEFAULT_SEARCH_RADIUS = 12;
    private static final long WOODCUT_REROUTE_BLACKLIST_MS = 12_000L;
    private static final int DEFAULT_VERTICAL_RANGE = 6;
    private static final int MAX_CONSECUTIVE_FAILURES = 6;
    private static final int SUNSET_TIME_OF_DAY = 12000;
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks (survival reach)
    private static final long PILLAR_STEP_DELAY_MS = 100L;
    private static final long MINING_TIMEOUT_MS = 12_000L;
    private static final int MAX_RETRY_MINING = 5;
    private static final int MAX_LOS_CLEAR_ATTEMPTS = 3;
    private static final int MAX_LEAF_CLEAR_BLOCKS_PER_TARGET = 24;
    private static final int MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT = 8;
    private static final int MAX_TRUNK_LOS_RECOVERY_ATTEMPTS = 2;
    private static final int MAX_COLUMN_PILLAR_STEPS = 18;
    private static final int WOODCUT_LOG_SCAN_EXPANSION = 4;
    private static final int MAX_IDENTICAL_REPOSITION_FAILURES = 2;
    private static final int MAX_MINOR_TERRAIN_CORRECTIONS_PER_TARGET = 2;
    private static final int MAX_RELOCATIONS = 3;
    private static final int PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION = 2;
    private static final int RELOCATION_SEARCH_RADIUS = 120;
    private static final int RELOCATION_VERTICAL_RANGE = 24;
    private static final int RELOCATION_NEARBY_RADIUS = 20;
    private static final int RELOCATION_NEARBY_VERTICAL_RANGE = 10;
    private static final long RELOCATION_NEARBY_TIME_BUDGET_MS = 400L;
    private static final long RELOCATION_BROAD_TIME_BUDGET_MS = 1_500L;
    private static final long RELOCATION_SLOW_SUMMARY_THRESHOLD_MS = 3_000L;
    private static final double QUICK_TREE_SWEEP_RADIUS = 9.0D;
    private static final double QUICK_TREE_SWEEP_VERTICAL_RANGE = 6.0D;
    private static final double QUICK_TREE_SWEEP_RADIUS_SCAFFOLD = 12.0D;
    private static final double QUICK_TREE_SWEEP_VERTICAL_RANGE_SCAFFOLD = 9.0D;
    private static final long WOODCUT_COLLECTION_MAINTENANCE_INTERVAL_MS = 10_000L;
    private static final int MAX_REPEATED_TRUNK_ENTRY_STALLS = 1;
    private static final int WOODCUT_PROACTIVE_DEPOSIT_EMPTY_SLOTS = 6;
    private static final int WOODCUT_PROACTIVE_DEPOSIT_WOOD_COUNT = 96;
    private static final int WOODCUT_OFFLOAD_LOCAL_CHEST_RADIUS = 48;
    private static final int WOODCUT_OFFLOAD_LOCAL_CHEST_YSPAN = 8;
    private static final double WOODCUT_NEARBY_REMEMBERED_PROBE_DIST_SQ = 56.0D * 56.0D;
    private RequestedWorkBounds activeRequestedBounds;
    // Per-tree work envelope overlay. While a tree is being felled, positions inside
    // this box are treated as within the requested bounds so the bot may prune
    // canopy/leaves that overhang the caller's bounds (e.g. farm AABB) for the
    // currently-targeted tree. Cleared when the tree's iteration ends.
    private BlockPos activeTreeWorkEnvelopeMin;
    private BlockPos activeTreeWorkEnvelopeMax;
    private static final int LOCAL_TREE_CLEANUP_RADIUS = 6;
    private static final int LOCAL_TREE_CLEANUP_VERTICAL_RANGE = 8;
    private static final long LOCAL_TREE_CLEANUP_DURATION_MS = 6_000L;
    private static final int LOCAL_TREE_CLEANUP_MAX_LOGS = 16;
    private static final int LOCAL_TREE_CLEANUP_MAX_SCAFFOLD = 24;
    private static final int BLIND_WALK_DISTANCE = 80;
    private static final int MAPPED_VILLAGE_EXIT_CLEARANCE = 12;
    private static final MovementService.MovementOptions WOODCUT_MOVEMENT_OPTIONS =
            new MovementService.MovementOptions(false, 0, false, 0);
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
            Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS,
            Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS,
            Items.CHERRY_PLANKS,
            Items.MANGROVE_PLANKS,
            Items.CRIMSON_PLANKS,
            Items.WARPED_PLANKS
    );

    // Ephemeral memory (persists across woodcut/woodcut_cleanup until another skill starts).
    private static final String WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY = "woodcut.scaffoldMemory.positions";
    private static final String WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY = "woodcut.scaffoldMemory.dimension";
    private static final String WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY = "woodcut.scaffoldMemory.updatedAt";
    private static final int WOODCUT_SCAFFOLD_MEMORY_MAX = 2048;
    private static final List<Item> SAPLING_ITEMS = List.of(
            Items.OAK_SAPLING,
            Items.SPRUCE_SAPLING,
            Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.MANGROVE_PROPAGULE,
            Items.CHERRY_SAPLING,
            Items.BAMBOO
    );
    private static final List<Item> SEED_ITEMS = List.of(
            Items.WHEAT_SEEDS,
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.TORCHFLOWER_SEEDS,
            Items.PITCHER_POD
    );
    private static final List<Item> RAW_FOOD_ITEMS = List.of(
            Items.BEEF,
            Items.PORKCHOP,
            Items.CHICKEN,
            Items.MUTTON,
            Items.RABBIT,
            Items.COD,
            Items.SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH
    );
    private static final Set<Item> MOB_DROP_JUNK = Set.of(
            Items.ROTTEN_FLESH,
            Items.BONE,
            Items.STRING,
            Items.GUNPOWDER,
            Items.SPIDER_EYE,
            Items.SLIME_BALL,
            Items.LEATHER,
            Items.FEATHER,
            Items.ENDER_PEARL,
            Items.PHANTOM_MEMBRANE,
            Items.INK_SAC,
            Items.GLOW_INK_SAC,
            Items.RABBIT_HIDE,
            Items.RABBIT_FOOT,
            Items.ARMADILLO_SCUTE,
            Items.HONEYCOMB,
            Items.COBWEB
    );
    private static final Set<Item> MISC_OFFLOAD_JUNK = Set.of(
            Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD,
            Items.LAPIS_LAZULI, Items.REDSTONE, Items.DIAMOND,
            Items.EMERALD, Items.AMETHYST_SHARD, Items.EGG
    );

    private record WoodcutDetectionSnapshot(
            Optional<TreeDetector.TreeTarget> nearestTree,
            Optional<TreeDetector.TreeTarget> nearestLooseTree,
            BlockPos floatingLog,
            BlockPos looseLog,
            BlockPos anyLog,
            int totalLogs,
            int visitedLogs,
            int protectedLogs,
            int humanAdjacentLogs,
            int soilFail,
            int leafFail,
            Map<String, Integer> protectedReasonCounts,
            List<String> rejectSamples,
            boolean allLocalLogsProtectedOrHuman) {
    }

    private record RequestedWorkBounds(int minX,
                                       int maxX,
                                       int minY,
                                       int maxY,
                                       int minZ,
                                       int maxZ) {
        private record CleanupBounds(int minX,
                                     int maxX,
                                     int minY,
                                     int maxY,
                                     int minZ,
                                     int maxZ) {
        }

        private static RequestedWorkBounds fromParameters(Map<String, Object> params) {
            if (params == null
                    || !params.containsKey("minX")
                    || !params.containsKey("maxX")
                    || !params.containsKey("minY")
                    || !params.containsKey("maxY")
                    || !params.containsKey("minZ")
                    || !params.containsKey("maxZ")) {
                return null;
            }
            int rawMinX = getIntParameter(params, "minX", 0);
            int rawMaxX = getIntParameter(params, "maxX", 0);
            int rawMinY = getIntParameter(params, "minY", 0);
            int rawMaxY = getIntParameter(params, "maxY", 0);
            int rawMinZ = getIntParameter(params, "minZ", 0);
            int rawMaxZ = getIntParameter(params, "maxZ", 0);
            return new RequestedWorkBounds(
                    Math.min(rawMinX, rawMaxX),
                    Math.max(rawMinX, rawMaxX),
                    Math.min(rawMinY, rawMaxY),
                    Math.max(rawMinY, rawMaxY),
                    Math.min(rawMinZ, rawMaxZ),
                    Math.max(rawMinZ, rawMaxZ)
            );
        }

        private boolean contains(BlockPos pos) {
            return pos != null
                    && pos.getX() >= minX
                    && pos.getX() <= maxX
                    && pos.getY() >= minY
                    && pos.getY() <= maxY
                    && pos.getZ() >= minZ
                    && pos.getZ() <= maxZ;
        }

        private boolean containsXZ(BlockPos pos) {
            return pos != null
                    && pos.getX() >= minX
                    && pos.getX() <= maxX
                    && pos.getZ() >= minZ
                    && pos.getZ() <= maxZ;
        }

        private boolean intersectsBox(BlockPos boxMin, BlockPos boxMax) {
            if (boxMin == null || boxMax == null) {
                return false;
            }
            return !(boxMax.getX() < minX
                    || boxMin.getX() > maxX
                    || boxMax.getY() < minY
                    || boxMin.getY() > maxY
                    || boxMax.getZ() < minZ
                    || boxMin.getZ() > maxZ);
        }

        private boolean containsBox(BlockPos boxMin, BlockPos boxMax) {
            if (boxMin == null || boxMax == null) {
                return false;
            }
            return boxMin.getX() >= minX
                    && boxMax.getX() <= maxX
                    && boxMin.getY() >= minY
                    && boxMax.getY() <= maxY
                    && boxMin.getZ() >= minZ
                    && boxMax.getZ() <= maxZ;
        }

        private CleanupBounds clampCleanupBounds(int cleanupMinX,
                                                 int cleanupMaxX,
                                                 int cleanupMinY,
                                                 int cleanupMaxY,
                                                 int cleanupMinZ,
                                                 int cleanupMaxZ) {
            return new CleanupBounds(
                    Math.max(minX, cleanupMinX),
                    Math.min(maxX, cleanupMaxX),
                    Math.max(minY, cleanupMinY),
                    Math.min(maxY, cleanupMaxY),
                    Math.max(minZ, cleanupMinZ),
                    Math.min(maxZ, cleanupMaxZ)
            );
        }
    }

    private enum WoodcutFailureReason {
        NO_TARGET,
        PROTECTED_AT_MINING,
        PATH_OR_REACH_FAILURE,
        INVENTORY_BLOCKED,
        TRUNK_NEVER_STARTED,
        PILLAR_NO_REACH,
        SCAFFOLD_CLEANUP_INCOMPLETE
    }

    private record MineAttemptResult(boolean success,
                                     WoodcutFailureReason failureReason,
                                     String detail) {
    }

    private record TreeHarvestResult(boolean success,
                                     WoodcutFailureReason failureReason,
                                     String detail) {
    }


    private static final class WoodcutWorkAreaState {
        private final BlockPos searchOrigin;
        private BlockPos activeTargetBase;
        private BlockPos lastSuccessfulBase;
        private BlockPos lastFailedBase;

        private WoodcutWorkAreaState(BlockPos searchOrigin) {
            this.searchOrigin = searchOrigin == null ? null : searchOrigin.toImmutable();
        }

        private void markActiveTarget(BlockPos base) {
            activeTargetBase = base == null ? null : base.toImmutable();
        }

        private void markSuccess(BlockPos base) {
            if (base != null) {
                lastSuccessfulBase = base.toImmutable();
                activeTargetBase = base.toImmutable();
            }
        }

        private void markFailure(BlockPos base) {
            if (base != null) {
                lastFailedBase = base.toImmutable();
                activeTargetBase = base.toImmutable();
            }
        }

        private BlockPos preferredAnchor(boolean hasPendingFloaters) {
            if (hasPendingFloaters && activeTargetBase != null) {
                return activeTargetBase;
            }
            if (lastSuccessfulBase != null) {
                return lastSuccessfulBase;
            }
            if (lastFailedBase != null) {
                return lastFailedBase;
            }
            return searchOrigin;
        }
    }

    private static final class WoodcutReachSession {
        private final List<BlockPos> placedBlocks = new ArrayList<>();
        private final Set<Long> placedKeys = new HashSet<>();
        private final Map<BlockPos, BlockState> temporaryEntryTerrainRepairs = new LinkedHashMap<>();
        private final WoodcutRerouteBlacklist failedReroutes = new WoodcutRerouteBlacklist(WOODCUT_REROUTE_BLACKLIST_MS);
        private int leafBlocksBroken;
        private int losClearAttempts;
        private int trunkMineAttemptsStarted;
        private int scaffoldPlaced;
        private int scaffoldRemoved;
        private int verifiedPillarSteps;
        private int strictTreeRejects;
        private boolean cleanupIncomplete;
        private boolean usedScaffold;
        private boolean preReachLeafClearAttempted;
        private int repeatedRepositionFailures;
        private int minorTerrainCorrections;
        private int rerouteFailures;
        private int rerouteBlacklistSkips;
        private int unsafeAnchorsRejected;
        private int pillarEscalationsRejected;
        private int terrainRestoreAttempted;
        private int terrainRestoreCompleted;
        private boolean replantAttempted;
        private int replantPlanted;
        private String replantStatus = "not-run";
        private String lastRepositionFailureSignature;
        private BlockPos lastGroundedWoodcutStand;
        private WoodcutHazardScanner.TreeHazardProfile hazardProfile;

        private void recordPlacement(BlockPos pos) {
            if (pos == null) {
                return;
            }
            long key = pos.asLong();
            if (placedKeys.add(key)) {
                placedBlocks.add(pos.toImmutable());
                scaffoldPlaced++;
                usedScaffold = true;
            }
        }

        private void recordRemoval(BlockPos pos) {
            if (pos == null) {
                return;
            }
            if (placedKeys.remove(pos.asLong())) {
                scaffoldRemoved++;
            }
        }

        private List<BlockPos> placementsDescending() {
            List<BlockPos> snapshot = new ArrayList<>(placedBlocks);
            Collections.reverse(snapshot);
            return snapshot;
        }

        private boolean hasPlacements() {
            return !placedKeys.isEmpty();
        }

        private void recordTemporaryEntryTerrainRepair(BlockPos pos, BlockState originalState) {
            if (pos == null || originalState == null || originalState.isAir()) {
                return;
            }
            temporaryEntryTerrainRepairs.putIfAbsent(pos.toImmutable(), originalState);
        }

        private Map<BlockPos, BlockState> consumeTemporaryEntryTerrainRepairs() {
            Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>(temporaryEntryTerrainRepairs);
            temporaryEntryTerrainRepairs.clear();
            return snapshot;
        }

        private int pendingTemporaryEntryTerrainRepairs() {
            return temporaryEntryTerrainRepairs.size();
        }

        private void recordFailedReroute(BlockPos target, BlockPos candidate, String reason) {
            if (candidate == null) {
                return;
            }
            failedReroutes.recordFailure(target, candidate, System.currentTimeMillis());
            rerouteFailures++;
        }

        private boolean isBlacklistedReroute(BlockPos target, BlockPos candidate) {
            return failedReroutes.isBlacklisted(target, candidate, System.currentTimeMillis());
        }

        private boolean hasVerifiedPillarSteps() {
            return verifiedPillarSteps > 0;
        }

        private void rememberGroundedWoodcutStand(BlockPos pos) {
            if (pos != null) {
                lastGroundedWoodcutStand = pos.toImmutable();
            }
        }

        private BlockPos groundedWoodcutStand() {
            return lastGroundedWoodcutStand == null ? null : lastGroundedWoodcutStand.toImmutable();
        }

        private void recordRepositionFailure(BlockPos target, BlockPos approach, String detail) {
            String signature = (target == null ? "null" : target.toShortString())
                    + "->"
                    + normalizedApproachSignature(target, approach)
                    + "::"
                    + normalizeRepositionFailureDetail(detail);
            if (signature.equals(lastRepositionFailureSignature)) {
                repeatedRepositionFailures++;
            } else {
                lastRepositionFailureSignature = signature;
                repeatedRepositionFailures = 1;
            }
        }

        private String normalizedApproachSignature(BlockPos target, BlockPos approach) {
            if (target == null || approach == null) {
                return approach == null ? "null" : approach.toShortString();
            }
            int dx = Integer.compare(approach.getX(), target.getX());
            int dy = Integer.compare(approach.getY(), target.getY());
            int dz = Integer.compare(approach.getZ(), target.getZ());
            return dx + "," + dy + "," + dz;
        }

        private String normalizeRepositionFailureDetail(String detail) {
            if (detail == null || detail.isBlank()) {
                return "none";
            }
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("walk blocked")) {
                return "walk-blocked";
            }
            if (lower.contains("no walkable path")) {
                return "no-walkable-path";
            }
            if (lower.contains("already at destination")) {
                return "already-at-destination";
            }
            if (lower.contains("walk ended")) {
                return "walk-ended-short";
            }
            if (lower.contains("timed out")) {
                return "timed-out";
            }
            return lower;
        }

        private void clearRepositionFailureTracking() {
            lastRepositionFailureSignature = null;
            repeatedRepositionFailures = 0;
        }
    }

    @Override
    public String name() {
        return "woodcut";
    }

    private boolean isBoundedWorkMode() {
        return activeRequestedBounds != null;
    }

    private boolean isWithinRequestedBounds(BlockPos pos) {
        if (activeRequestedBounds == null) {
            return true;
        }
        if (pos == null) {
            return false;
        }
        if (activeRequestedBounds.contains(pos)) {
            return true;
        }
        // Per-tree overlay: let the bot prune canopy/leaves of the tree it's
        // actively felling even if they overhang the caller's bounds.
        return isWithinActiveTreeWorkEnvelope(pos);
    }

    private boolean isWithinActiveTreeWorkEnvelope(BlockPos pos) {
        if (activeTreeWorkEnvelopeMin == null || activeTreeWorkEnvelopeMax == null || pos == null) {
            return false;
        }
        return pos.getX() >= activeTreeWorkEnvelopeMin.getX()
                && pos.getX() <= activeTreeWorkEnvelopeMax.getX()
                && pos.getY() >= activeTreeWorkEnvelopeMin.getY()
                && pos.getY() <= activeTreeWorkEnvelopeMax.getY()
                && pos.getZ() >= activeTreeWorkEnvelopeMin.getZ()
                && pos.getZ() <= activeTreeWorkEnvelopeMax.getZ();
    }

    private boolean isWithinRequestedBoundsXZ(BlockPos pos) {
        return activeRequestedBounds == null || activeRequestedBounds.containsXZ(pos);
    }

    private boolean isTreeWorkEnvelopeWithinBounds(TreeDetector.TreeTarget target) {
        if (activeRequestedBounds == null || target == null) {
            return true;
        }
        // Only the trunk (base → top) must be inside the caller's bounds. The
        // leaf envelope may overhang — once selected, mining authority for the
        // canopy is granted via the per-tree work envelope overlay (see
        // isWithinRequestedBounds / activeTreeWorkEnvelope*).
        return activeRequestedBounds.contains(target.base())
                && activeRequestedBounds.contains(target.top());
    }

    private RequestedWorkBounds.CleanupBounds clampCleanupBounds(int minX,
                                                                 int maxX,
                                                                 int minY,
                                                                 int maxY,
                                                                 int minZ,
                                                                 int maxZ) {
        if (activeRequestedBounds == null) {
            return new RequestedWorkBounds.CleanupBounds(minX, maxX, minY, maxY, minZ, maxZ);
        }
        return activeRequestedBounds.clampCleanupBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private MovementService.MovementResult executeMovementPlan(ServerCommandSource source,
                                                               ServerPlayerEntity bot,
                                                               MovementService.MovementPlan plan,
                                                               String label) {
        if (plan == null) {
            return new MovementService.MovementResult(false, null, bot == null ? null : bot.getBlockPos(), "null-plan");
        }
        BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
        if (!isWithinRequestedBounds(approach) || !isWithinRequestedBounds(plan.finalDestination())) {
            LOGGER.info("Woodcut bounded: rejecting movement [{}] final={} approach={} bounds={}",
                    label,
                    plan.finalDestination() == null ? "null" : plan.finalDestination().toShortString(),
                    approach == null ? "null" : approach.toShortString(),
                    activeRequestedBounds);
            return new MovementService.MovementResult(false, plan.mode(), bot == null ? null : bot.getBlockPos(), "requested-bounds");
        }
        return MovementService.execute(source, bot, plan, false, true, true, false);
    }

    private int maxBridgeLengthWithinBounds(BlockPos origin, Direction direction, int requestedLength) {
        if (activeRequestedBounds == null || origin == null || direction == null) {
            return requestedLength;
        }
        int maxAllowed = 0;
        for (int step = 1; step <= requestedLength; step++) {
            BlockPos walkPos = origin.offset(direction, step);
            BlockPos bridgePos = walkPos.down();
            if (!isWithinRequestedBounds(walkPos) || !isWithinRequestedBounds(bridgePos)) {
                break;
            }
            maxAllowed = step;
        }
        return maxAllowed;
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        SkillResumeService.consumeResumeIntent(bot.getUuid());

        Map<String, Object> sharedState = context.sharedState();

        boolean internal = getBooleanParameter(context.parameters(), "internal", false);
        boolean replantSaplings = getBooleanParameter(context.parameters(), "replantSaplings", !internal);
        boolean isHuntPrerequisite = "hunt_prerequisite".equals(context.parameters().get("_origin"));
        boolean openEnded = isOpenEnded(context.parameters());
        boolean hasExplicitCount = context.parameters() != null && context.parameters().containsKey("count");
        boolean radiusClearMode = getBooleanParameter(context.parameters(), "clearRadius", !openEnded && !hasExplicitCount);
        int targetTrees;
        if (openEnded) {
            targetTrees = Integer.MAX_VALUE;
        } else {
            int defaultTrees = internal ? DEFAULT_TREE_COUNT_INTERNAL : DEFAULT_TREE_COUNT_STANDALONE;
            targetTrees = Math.max(1, getIntParameter(context.parameters(), "count", defaultTrees));
        }
        int searchRadius = Math.max(6, getIntParameter(context.parameters(), "searchRadius", DEFAULT_SEARCH_RADIUS));
        int verticalRange = Math.max(4, getIntParameter(context.parameters(), "verticalRange", DEFAULT_VERTICAL_RANGE));
        RequestedWorkBounds requestedBounds = RequestedWorkBounds.fromParameters(context.parameters());
        activeRequestedBounds = requestedBounds;
        try {
            int startTimeOfDay = bot.getEntityWorld() != null ? (int) (bot.getEntityWorld().getTimeOfDay() % 24000L) : 0;
        if (!internal && startTimeOfDay >= SUNSET_TIME_OF_DAY) {
            return SkillExecutionResult.failure("It's getting late; I'll cut trees tomorrow.");
        }

        if (bot.getEntityWorld() instanceof ServerWorld world
                && !ensureWoodcutOperationalSurface(source, bot, world, sharedState)) {
            // Still sweep drops before giving up.
            if (requestedBounds == null) {
                try {
                    DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), 8.0D, 4.0D);
                } catch (Exception ignored) {
                }
            }
            return SkillExecutionResult.failure("I couldn't reach the surface to cut trees.");
        }

        if (!prepareWoodcutTooling(source, bot)) {
            return SkillExecutionResult.failure(
                    "I have no axe and can't make or find one. Get me an axe (or planks + sticks) and try again.");
        }

        Set<BlockPos> visitedBases = new HashSet<>();
        Set<BlockPos> failedBases = new HashSet<>();
        Map<BlockPos, String> failedBaseReasons = new HashMap<>();
        Set<BlockPos> pendingFloaters = new HashSet<>();
        int felled = 0;
        int consecutiveFailures = 0;
        int totalFailures = 0;
        int sinceCleanup = 0;
        int relocations = 0;
        int protectedOnlyScanStreak = 0;
        int selectedTargets = 0;
        int miningProtectedFailures = 0;
        int pathFailures = 0;
        int trunkMineStarts = 0;
        int leafBlocksBroken = 0;
        int scaffoldPlaced = 0;
        int scaffoldRemoved = 0;
        int strictTreeRejects = 0;
        long lastCollectionMaintenanceAtMs = 0L;
        boolean abortRequested = false;
        WoodcutFailureReason lastFailureReason = WoodcutFailureReason.NO_TARGET;
        BlockPos startPos = bot.getBlockPos();
        WoodcutWorkAreaState workAreaState = new WoodcutWorkAreaState(startPos);
        int minX = requestedBounds == null ? startPos.getX() : requestedBounds.minX();
        int maxX = requestedBounds == null ? startPos.getX() : requestedBounds.maxX();
        int minY = requestedBounds == null ? startPos.getY() : requestedBounds.minY();
        int maxY = requestedBounds == null ? startPos.getY() : requestedBounds.maxY();
        int minZ = requestedBounds == null ? startPos.getZ() : requestedBounds.minZ();
        int maxZ = requestedBounds == null ? startPos.getZ() : requestedBounds.maxZ();

        SkillExecutionResult finalResult;
        try {
            while (openEnded || radiusClearMode || felled < targetTrees) {
                if (isAbortRequested(bot)) {
                    abortRequested = true;
                    break;
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    if (requestedBounds != null) {
                        LOGGER.info("Woodcut: bounded work area exhausted at {}; not relocating outside requested bounds {}", bot.getBlockPos().toShortString(), requestedBounds);
                        break;
                    }
                    if (relocations >= MAX_RELOCATIONS) {
                        break;
                    }
                    relocations++;
                    if (relocateToUnprotectedTree(source, bot, effectiveSearchRadius(bot, searchRadius), failedBases, failedBaseReasons)) {
                        consecutiveFailures = 0;
                        visitedBases.clear();
                        LOGGER.info("Woodcut: relocated ({}/{}), resuming search from {}",
                                relocations, MAX_RELOCATIONS, bot.getBlockPos().toShortString());
                        continue;
                    }
                    // Relocation failed — but don't break; let the loop try again
                    // with the next iteration (bot may have moved partway)
                    consecutiveFailures = 0;
                    visitedBases.clear();
                    LOGGER.info("Woodcut: relocation {}/{} failed, retrying from {}",
                            relocations, MAX_RELOCATIONS, bot.getBlockPos().toShortString());
                    continue;
                }
                if (!internal) {
                    int timeOfDay = (int) (bot.getEntityWorld().getTimeOfDay() % 24000L);
                    if (timeOfDay >= SUNSET_TIME_OF_DAY) {
                        ChatUtils.sendSystemMessage(source, "It's getting late; I'm stopping woodcut for the day.");
                        break;
                    }
                }

                if (!ensureWoodSpaceOrDeposit(source, bot, isHuntPrerequisite, workAreaState, pendingFloaters)) {
                    totalFailures++;
                    consecutiveFailures++;
                    lastFailureReason = WoodcutFailureReason.INVENTORY_BLOCKED;
                    ChatUtils.sendSystemMessage(source, "Inventory is full and I couldn't store items; stopping woodcut.");
                    break;
                }

                int effectiveSearchRadius = effectiveSearchRadius(bot, searchRadius);
                CompanionOverheadHologramService.show(bot, "Scanning for trees...", 5_000);
                long detectionStartedAt = System.nanoTime();
                WoodcutDetectionSnapshot detection = scanDetectionSnapshot(bot, effectiveSearchRadius, verticalRange, visitedBases, failedBases, failedBaseReasons, requestedBounds);
                long detectionDurationMs = (System.nanoTime() - detectionStartedAt) / 1_000_000L;
                logDetectionSnapshot(detection, detectionDurationMs);
                strictTreeRejects += detection.rejectSamples().stream().filter(sample -> sample.contains("strictStandingReject=true")).count();
                Optional<TreeDetector.TreeTarget> targetOpt = detection.nearestTree();
                if (detection.nearestLooseTree().isPresent()) {
                    TreeDetector.TreeTarget looseTree = detection.nearestLooseTree().get();
                    if (targetOpt.isEmpty()) {
                        LOGGER.info("Woodcut detect: promoting nearby loose tree base={} top={} reason=no-strict-tree",
                                looseTree.base().toShortString(),
                                looseTree.top().toShortString());
                        targetOpt = detection.nearestLooseTree();
                    } else {
                        TreeDetector.TreeTarget strictTree = targetOpt.get();
                        double strictDistSq = bot.getBlockPos().getSquaredDistance(strictTree.base());
                        double looseDistSq = bot.getBlockPos().getSquaredDistance(looseTree.base());
                        if (looseDistSq <= 64.0D && strictDistSq - looseDistSq >= 36.0D) {
                            LOGGER.info("Woodcut detect: preferring nearby loose tree base={} over strict base={} looseDistSq={} strictDistSq={}",
                                    looseTree.base().toShortString(),
                                    strictTree.base().toShortString(),
                                    (int) looseDistSq,
                                    (int) strictDistSq);
                            targetOpt = detection.nearestLooseTree();
                        }
                    }
                }
                if (targetOpt.isEmpty()) {
                    if (detection.floatingLog() != null && !failedBases.contains(detection.floatingLog())) {
                        LOGGER.warn("Woodcut: cleaning floating log at {}", detection.floatingLog().toShortString());
                        TreeDetector.TreeTarget synthetic = new TreeDetector.TreeTarget(detection.floatingLog(), detection.floatingLog(), 1);
                        targetOpt = Optional.of(synthetic);
                    } else {
                        if (!openEnded && radiusClearMode && felled >= targetTrees) {
                            LOGGER.info("Woodcut: radius-clear complete near {} after {} cleared target(s); no selectable targets remain in {}x{}",
                                    bot.getBlockPos().toShortString(), felled, effectiveSearchRadius, verticalRange);
                            break;
                        }
                        if (detection.allLocalLogsProtectedOrHuman()) {
                            protectedOnlyScanStreak++;
                            LOGGER.info("Woodcut: protected-only local scan streak {}/{} at {}",
                                    protectedOnlyScanStreak,
                                    PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION,
                                    bot.getBlockPos().toShortString());
                            if (protectedOnlyScanStreak >= PROTECTED_ONLY_SCAN_STREAK_FOR_RELOCATION) {
                                LOGGER.info("Woodcut: protected-only scans triggered staged relocation after {}ms local-scan time",
                                        detectionDurationMs);
                                consecutiveFailures = MAX_CONSECUTIVE_FAILURES;
                                continue;
                            }
                        } else {
                            protectedOnlyScanStreak = 0;
                        }
                        if (detection.totalLogs() <= 0) {
                            LOGGER.warn("Woodcut: found no detectable trees/logs within {}x{}", effectiveSearchRadius, verticalRange);
                            consecutiveFailures = MAX_CONSECUTIVE_FAILURES;
                            continue;
                        }
                        // All logs detected but none selectable (soilFail etc.) — skip to relocation
                        if (detection.soilFail() >= detection.totalLogs()) {
                            LOGGER.info("Woodcut: all {} detected logs failed soil check, forcing relocation", detection.totalLogs());
                            consecutiveFailures = MAX_CONSECUTIVE_FAILURES;
                            continue;
                        }
                        consecutiveFailures++;
                        continue;
                    }
                }

                TreeDetector.TreeTarget target = targetOpt.get();
                protectedOnlyScanStreak = 0;
                if (!isTreeWorkEnvelopeWithinBounds(target)) {
                    LOGGER.info("Woodcut bounded: rejecting target base={} top={} envelope=[{} -> {}] bounds={} reason=out-of-bounds-tree-envelope",
                            target.base().toShortString(),
                            target.top().toShortString(),
                            target.envelopeMin().toShortString(),
                            target.envelopeMax().toShortString(),
                            requestedBounds);
                    failedBases.add(target.base().toImmutable());
                    failedBaseReasons.put(target.base().toImmutable(), "out-of-bounds-tree-envelope");
                    totalFailures++;
                    consecutiveFailures++;
                    lastFailureReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                    continue;
                }
                if (failedBases.contains(target.base())) {
                    LOGGER.info("Woodcut: skipping previously failed base {} (reason={})",
                            target.base().toShortString(),
                            failedBaseReasons.getOrDefault(target.base(), "previous-failure"));
                    totalFailures++;
                    consecutiveFailures++;
                    lastFailureReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                    continue;
                }
                LOGGER.info("Woodcut: selected tree target base={} top={} height={}",
                        target.base().toShortString(), target.top().toShortString(), target.height());
                selectedTargets++;
                if (bot.getEntityWorld() instanceof ServerWorld targetWorld) {
                    TreeDetector.WoodcutProtectionDecision detectionProtection =
                            TreeDetector.getWoodcutProtectionDecision(targetWorld, target.base(), Math.max(4, target.height()));
                    TreeDetector.WoodcutProtectionDecision miningProtection =
                            getWoodcutMutationDecision(targetWorld, target.base(), target.base());
                    LOGGER.info("Woodcut target permission: base={} detectionReason={} miningReason={}",
                            target.base().toShortString(),
                            detectionProtection.reason(),
                            miningProtection.reason());
                    if (!Objects.equals(detectionProtection.reason(), miningProtection.reason())
                            || detectionProtection.blocked() != miningProtection.blocked()) {
                        LOGGER.warn("Woodcut protection mismatch for {}: detectionBlocked={} detectionReason={} miningBlocked={} miningReason={}",
                                target.base().toShortString(),
                                detectionProtection.blocked(),
                                detectionProtection.reason(),
                                miningProtection.blocked(),
                                miningProtection.reason());
                    }
                    if (detectionProtection.blocked()) {
                        LOGGER.info("Woodcut: rejecting protected target at {} (reason={})",
                                target.base().toShortString(), detectionProtection.reason());
                        totalFailures++;
                        consecutiveFailures++;
                        lastFailureReason = WoodcutFailureReason.PROTECTED_AT_MINING;
                        continue;
                    }
                }
                visitedBases.add(target.base());
                WoodcutReachSession reachSession = new WoodcutReachSession();
                if (bot.getEntityWorld() instanceof ServerWorld hazardWorld) {
                    reachSession.hazardProfile = WoodcutHazardScanner.scan(hazardWorld, target.base());
                }

                // Activate per-tree work envelope so the felling phase may prune
                // canopy/leaves overhanging the caller's bounds for this tree.
                activeTreeWorkEnvelopeMin = target.envelopeMin().add(
                        -WOODCUT_LOG_SCAN_EXPANSION, -1, -WOODCUT_LOG_SCAN_EXPANSION);
                activeTreeWorkEnvelopeMax = target.envelopeMax().add(
                        WOODCUT_LOG_SCAN_EXPANSION, WOODCUT_LOG_SCAN_EXPANSION, WOODCUT_LOG_SCAN_EXPANSION);
                try {

                // Track footprint to size post-run drop sweep.
                BlockPos posNow = bot.getBlockPos();
                if (requestedBounds == null) {
                    minX = Math.min(minX, posNow.getX());
                    maxX = Math.max(maxX, posNow.getX());
                    minY = Math.min(minY, posNow.getY());
                    maxY = Math.max(maxY, posNow.getY());
                    minZ = Math.min(minZ, posNow.getZ());
                    maxZ = Math.max(maxZ, posNow.getZ());
                }

                workAreaState.markActiveTarget(target.base());
                if (!approachBase(source, bot, target.base(), sharedState, reachSession)) {
                    totalFailures++;
                    consecutiveFailures++;
                    pathFailures++;
                    failedBases.add(target.base().toImmutable());
                    failedBaseReasons.put(target.base().toImmutable(), "approach-failed");
                    trunkMineStarts += reachSession.trunkMineAttemptsStarted;
                    leafBlocksBroken += reachSession.leafBlocksBroken;
                    scaffoldPlaced += reachSession.scaffoldPlaced;
                    scaffoldRemoved += reachSession.scaffoldRemoved;
                    lastFailureReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                    workAreaState.markFailure(target.base());
                    logTargetTrace(target, reachSession, "APPROACH_FAILED");
                    logWoodcutMaintenanceSummary(target.base(), reachSession, "approach-failed");
                    continue;
                }
                TreeHarvestResult harvest = fellTree(source, bot, target, sharedState, replantSaplings, pendingFloaters, reachSession);
                trunkMineStarts += reachSession.trunkMineAttemptsStarted;
                leafBlocksBroken += reachSession.leafBlocksBroken;
                scaffoldPlaced += reachSession.scaffoldPlaced;
                scaffoldRemoved += reachSession.scaffoldRemoved;
                if (!harvest.success()) {
                    totalFailures++;
                    consecutiveFailures++;
                    failedBases.add(target.base().toImmutable());
                    failedBaseReasons.put(target.base().toImmutable(), harvest.detail());
                    if (harvest.failureReason() == WoodcutFailureReason.PROTECTED_AT_MINING) {
                        miningProtectedFailures++;
                    } else if (harvest.failureReason() == WoodcutFailureReason.PATH_OR_REACH_FAILURE) {
                        pathFailures++;
                        // Recover to surface before trying the next tree — bot likely fell
                        // into a hole during the failed harvest attempt.
                        if (bot.getEntityWorld() instanceof ServerWorld recoveryWorld) {
                            int groundY = SafePositionService.getWalkableGroundY(
                                    recoveryWorld, bot.getBlockX(), bot.getBlockZ());
                            if (groundY - bot.getBlockY() >= 2) {
                                recoverSurfacePosition(bot, recoveryWorld, source, groundY,
                                        reachSession, sharedState, "inter-tree-recovery");
                            }
                        }
                        lastCollectionMaintenanceAtMs = runPostAttemptCollectionMaintenance(
                                source, bot, reachSession, isHuntPrerequisite, workAreaState, pendingFloaters, lastCollectionMaintenanceAtMs);
                    }
                    lastFailureReason = harvest.failureReason();
                    workAreaState.markFailure(target.base());
                    logTargetTrace(target, reachSession, harvest.failureReason().name());
                    // Replant even on failed trees if trunk was actually cut
                    if (replantSaplings && reachSession.trunkMineAttemptsStarted > 0 && !isAbortRequested(bot)) {
                        plantSaplings(bot, source, target.base(), reachSession);
                    }
                    logWoodcutMaintenanceSummary(target.base(), reachSession,
                            "failed-" + harvest.failureReason().name().toLowerCase(Locale.ROOT));
                    continue;
                }
                logTargetTrace(target, reachSession, "SUCCESS");
                workAreaState.markSuccess(target.base());

                felled++;
                consecutiveFailures = 0;
                sinceCleanup++;
                if (openEnded) {
                    ChatUtils.sendSystemMessage(source, "Woodcut target cleared (" + felled + ")");
                } else {
                    ChatUtils.sendSystemMessage(source, "Woodcut target cleared (" + felled + " min " + targetTrees + ")");
                }
                runPerTreeMaintenance(context, source, bot, target, reachSession, pendingFloaters, replantSaplings);
                if (shouldProactivelyDepositAfterMaintenance(bot, isHuntPrerequisite)) {
                    ensureWoodSpaceOrDeposit(source, bot, isHuntPrerequisite, workAreaState, pendingFloaters);
                }
                lastCollectionMaintenanceAtMs = System.currentTimeMillis();

                if (openEnded && sinceCleanup >= 5) {
                    runWoodcutCleanup(context, source, bot, startPos, minX, maxX, minY, maxY, minZ, maxZ,
                            Math.max(searchRadius + 6, 16), Math.max(verticalRange + 8, 12),
                            false);
                    sinceCleanup = 0;
                }
                } finally {
                    activeTreeWorkEnvelopeMin = null;
                    activeTreeWorkEnvelopeMax = null;
                }
            }

            // Second-pass cleanup: attempt unreachable logs from partial harvests from a fresh position
            if (!pendingFloaters.isEmpty() && !abortRequested && bot.getEntityWorld() instanceof ServerWorld floaterWorld) {
                LOGGER.info("Woodcut: attempting cleanup of {} floater log(s) from partial harvests", pendingFloaters.size());
                for (BlockPos floater : new HashSet<>(pendingFloaters)) {
                    if (!floaterWorld.getBlockState(floater).isIn(BlockTags.LOGS)) {
                        forgetCleanupFloater(bot, sharedState, floaterWorld, floater);
                        pendingFloaters.remove(floater);
                        continue;
                    }
                    if (isAbortRequested(bot)) break;
                    WoodcutReachSession floaterSession = new WoodcutReachSession();
                    // Clear leaves toward floater before approach
                    Direction towardFloater = directionToward(bot.getBlockPos(), floater);
                    if (!isWithinRequestedBounds(floater)) {
                        LOGGER.info("Woodcut bounded: skipping floater {} outside requested bounds {}", floater.toShortString(), requestedBounds);
                        continue;
                    }
                    if (towardFloater != null) {
                        MovementService.clearLeafObstructionDetailed(bot, towardFloater);
                    }
                    // Approach from below — different angle than original attempt
                    MovementService.planLootApproach(bot, floater.down(), WOODCUT_MOVEMENT_OPTIONS)
                            .ifPresent(plan -> executeMovementPlan(source, bot, plan, "floater-approach"));
                    MineAttemptResult floaterResult = mineWithRetries(bot, source, floater, floaterSession, false, sharedState, floater);
                    if (floaterResult.success() || !floaterWorld.getBlockState(floater).isIn(BlockTags.LOGS)) {
                        forgetCleanupFloater(bot, sharedState, floaterWorld, floater);
                        pendingFloaters.remove(floater);
                    }
                    cleanupReachSession(source, bot, floater, floaterSession, sharedState);
                }
            }

            if (abortRequested) {
                finalResult = SkillExecutionResult.failure("woodcut paused due to nearby threat.");
            } else if (felled == 0 && selectedTargets > 0) {
                finalResult = switch (lastFailureReason) {
                    case PROTECTED_AT_MINING -> SkillExecutionResult.failure("Found a tree, but couldn’t cut it; protection checks blocked the trunk.");
                    case PATH_OR_REACH_FAILURE -> SkillExecutionResult.failure("Found a tree, but couldn’t finish cutting it after repeated reach/path failures.");
                    case INVENTORY_BLOCKED -> SkillExecutionResult.failure("Inventory is full and I couldn't store items; stopping woodcut.");
                    case TRUNK_NEVER_STARTED -> SkillExecutionResult.failure("Found a tree, but never reached a valid trunk-hit state.");
                    case PILLAR_NO_REACH -> SkillExecutionResult.failure("Found a tree, but pillar reach still never reached the trunk.");
                    case SCAFFOLD_CLEANUP_INCOMPLETE -> SkillExecutionResult.failure("Stopped after reaching the tree, but scaffold cleanup could not finish cleanly.");
                    case NO_TARGET -> SkillExecutionResult.failure("No valid trees nearby. Try moving closer or adjust radius.");
                };
            } else if (felled == 0) {
                finalResult = SkillExecutionResult.failure("No valid trees nearby. Try moving closer or adjust radius.");
            } else if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                finalResult = SkillExecutionResult.failure("Stopped after cutting " + felled + " tree(s); repeated failures reaching remaining targets (path/LOS/inventory).");
            } else if (radiusClearMode && !openEnded) {
                finalResult = SkillExecutionResult.success("Cleared the local woodcut area after " + felled + " target(s).");
            } else if (felled < targetTrees) {
                finalResult = SkillExecutionResult.success("Stopped after cutting " + felled + " tree(s).");
            } else {
                finalResult = SkillExecutionResult.success("Cut " + felled + " tree(s).");
            }
        } finally {
            // Best-effort: if we did any work, attempt cleanup (when not aborted) and always perform a
            // wide drop sweep at the end so items don't get left on the ground even after termination.
        if ((felled > 0 || !visitedBases.isEmpty() || totalFailures > 0)
                && !TaskService.isServerStopping()
                && !isAbortRequested(bot)) {
            try {
                ensureWoodSpaceOrDeposit(source, bot, isHuntPrerequisite, workAreaState, pendingFloaters);
            } catch (Exception ignored) {
            }

                double horizRadius = Math.max(6.0,
                        Math.max(Math.abs(maxX - startPos.getX()), Math.abs(minX - startPos.getX())) + 3.0);
                double vertRange = Math.max(4.0, (maxY - minY) + 3.0);

                if (!abortRequested) {
                    if (requestedBounds != null) {
                        // In bounded mode (e.g. farm clearing trees) this finally
                        // block runs in two cases:
                        //   (a) all trees felled cleanly -> per-tree maintenance
                        //       (runLocalTreeCleanup) already handled them.
                        //   (b) fellTree threw / bounded work area exhausted ->
                        //       scanning the bounded region for floaters will
                        //       produce zero actionable targets (the trees are
                        //       still intact) and waste ~35 seconds.
                        // Either way the full-region cleanup adds nothing here.
                        LOGGER.info("Woodcut bounded: skipping full-region cleanup pass (felled={}); bounded caller owns cleanup responsibility.", felled);
                    } else {
                        // Cleanup pass first (break floating logs/scaffolds).
                        runWoodcutCleanup(context, source, bot, startPos, minX, maxX, minY, maxY, minZ, maxZ,
                                (int) Math.ceil(horizRadius), (int) Math.ceil(vertRange),
                                false);
                    }
                } else {
                    LOGGER.info("Woodcut: abort requested; skipping cleanup pass and performing final sweep.");
                }

                // Always attempt a wide drop sweep at the end (even if the run was terminated). Still
                // avoid sweeping when the inventory is full.
                if (!isInventoryFull(bot) && requestedBounds == null) {
                    try {
                        DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), horizRadius, vertRange);
                    } catch (Exception e) {
                        LOGGER.warn("Drop sweep after woodcut failed: {}", e.getMessage());
                    }
                }
            }
        }

        LOGGER.info("Woodcut end summary: selectedTargets={} miningProtectedFailures={} pathFailures={} failedBaseCount={} trunkMineStarts={} leafBlocksBroken={} scaffoldPlaced={} scaffoldRemoved={} strictTreeRejects={} finalReason={}",
                selectedTargets, miningProtectedFailures, pathFailures, failedBases.size(),
                trunkMineStarts, leafBlocksBroken, scaffoldPlaced, scaffoldRemoved, strictTreeRejects, lastFailureReason);

        return finalResult;
        } finally {
            activeRequestedBounds = null;
            activeTreeWorkEnvelopeMin = null;
            activeTreeWorkEnvelopeMax = null;
        }
    }

    private void recordScaffoldPlacement(Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (sharedState == null || world == null || pos == null) {
            return;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        try {
            Object rawDim = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
            if (rawDim instanceof String s && !s.equals(dimension)) {
                sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            }
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dimension);
            sharedState.put(WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY, System.currentTimeMillis());

            Object raw = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            @SuppressWarnings("unchecked")
            Set<Long> set = raw instanceof Set<?> existing ? (Set<Long>) existing : null;
            if (set == null) {
                set = new HashSet<>();
                sharedState.put(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY, set);
            }
            set.add(pos.asLong());
            if (set.size() > WOODCUT_SCAFFOLD_MEMORY_MAX) {
                int toRemove = set.size() - WOODCUT_SCAFFOLD_MEMORY_MAX;
                var it = set.iterator();
                while (toRemove > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                    toRemove--;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Long> getScaffoldMemory(Map<String, Object> sharedState, ServerWorld world, boolean create) {
        if (sharedState == null || world == null) {
            return null;
        }
        String dimension = world.getRegistryKey().getValue().toString();
        Object rawDim = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
        if (rawDim instanceof String stored && !stored.equals(dimension)) {
            sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
        }
        sharedState.put(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY, dimension);

        Object raw = sharedState.get(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
        if (raw instanceof Set<?> existing) {
            try {
                return (Set<Long>) existing;
            } catch (ClassCastException ignored) {
                sharedState.remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
            }
        }
        if (!create) {
            return null;
        }
        Set<Long> created = new HashSet<>();
        sharedState.put(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY, created);
        return created;
    }

    private void forgetScaffoldPlacement(Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (sharedState == null || world == null || pos == null) {
            return;
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory != null) {
            memory.remove(pos.asLong());
        }
    }

    private void recordCleanupFloater(ServerPlayerEntity bot, Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (bot == null || sharedState == null || world == null || pos == null) {
            return;
        }
        WoodcutCleanupMemoryService.remember(sharedState, bot.getUuid(), world, pos);
    }

    private void forgetCleanupFloater(ServerPlayerEntity bot, Map<String, Object> sharedState, ServerWorld world, BlockPos pos) {
        if (bot == null || sharedState == null || world == null || pos == null) {
            return;
        }
        WoodcutCleanupMemoryService.forget(sharedState, bot.getUuid(), world, pos);
    }

    private void runWoodcutCleanup(SkillContext context,
                                  ServerCommandSource source,
                                  ServerPlayerEntity bot,
                                  BlockPos startPos,
                                  int minX,
                                  int maxX,
                                  int minY,
                                  int maxY,
                                  int minZ,
                                  int maxZ,
                                  int radius,
                                  int verticalRange,
                                  boolean sweepDrops) {
        if (bot == null || source == null) {
            return;
        }
        if (isAbortRequested(bot)) {
            return;
        }
        try {
            RequestedWorkBounds.CleanupBounds clamped = clampCleanupBounds(minX, maxX, minY, maxY, minZ, maxZ);
            Map<String, Object> params = new HashMap<>();
            params.put("radius", Math.max(8, radius));
            params.put("verticalRange", Math.max(6, verticalRange));
            params.put("maxLogs", 64);
            params.put("maxScaffold", 96);
            params.put("durationMs", 35_000L);
            params.put("sweep", sweepDrops);
            params.put("scaffold", true);

            // Provide a tighter region for the cleanup scan.
            params.put("minX", clamped.minX());
            params.put("maxX", clamped.maxX());
            params.put("minY", clamped.minY());
            params.put("maxY", clamped.maxY());
            params.put("minZ", clamped.minZ());
            params.put("maxZ", clamped.maxZ());

            // Keep internal child call from changing behavior like sunset gating.
            params.put("internal", true);

            WoodcutCleanupSkill cleanup = new WoodcutCleanupSkill();
            SkillContext cleanupCtx = new SkillContext(source, context.sharedState(), params, context.requestSource());
            var res = cleanup.execute(cleanupCtx);
            LOGGER.info("Woodcut cleanup pass result: success={} message='{}'", res.success(), res.message());
        } catch (Exception e) {
            LOGGER.warn("Woodcut cleanup pass failed: {}", e.getMessage());
        }
    }

    private void runPerTreeMaintenance(SkillContext context,
                                       ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       TreeDetector.TreeTarget target,
                                       WoodcutReachSession reachSession,
                                       Set<BlockPos> pendingFloaters,
                                       boolean replantSaplings) {
        if (context == null || source == null || bot == null || target == null || isAbortRequested(bot)) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        // Replant first — before cleanup/sweep drifts the bot away from the tree base
        if (replantSaplings && !isAbortRequested(bot)) {
            plantSaplings(bot, source, target.base(), reachSession);
        }
        if (shouldRunLocalTreeCleanup(bot, world, target.base(), reachSession, pendingFloaters, context.sharedState())) {
            runLocalTreeCleanup(context, source, bot, target.base());
        }
        if (!isInventoryFull(bot)) {
            runQuickPerTreeDropSweep(bot, source, reachSession);
        }
        restoreTemporaryEntryTerrain(bot, source, world, target.base(), reachSession);
        logWoodcutMaintenanceSummary(target.base(), reachSession, "per-tree");
    }

    private boolean shouldRunLocalTreeCleanup(ServerPlayerEntity bot,
                                              ServerWorld world,
                                              BlockPos base,
                                              WoodcutReachSession reachSession,
                                              Set<BlockPos> pendingFloaters,
                                              Map<String, Object> sharedState) {
        if (bot == null || world == null || base == null) {
            return false;
        }
        if (reachSession != null && (reachSession.cleanupIncomplete
                || reachSession.scaffoldPlaced > reachSession.scaffoldRemoved
                || reachSession.usedScaffold)) {
            return true;
        }
        if (pendingFloaters != null) {
            for (BlockPos floater : pendingFloaters) {
                if (floater != null
                        && Math.abs(floater.getX() - base.getX()) <= LOCAL_TREE_CLEANUP_RADIUS
                        && Math.abs(floater.getY() - base.getY()) <= LOCAL_TREE_CLEANUP_VERTICAL_RANGE
                        && Math.abs(floater.getZ() - base.getZ()) <= LOCAL_TREE_CLEANUP_RADIUS) {
                    return true;
                }
            }
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory != null) {
            for (Long packed : memory) {
                if (packed == null) {
                    continue;
                }
                BlockPos pos = BlockPos.fromLong(packed);
                if (Math.abs(pos.getX() - base.getX()) <= LOCAL_TREE_CLEANUP_RADIUS
                        && Math.abs(pos.getY() - base.getY()) <= LOCAL_TREE_CLEANUP_VERTICAL_RANGE
                        && Math.abs(pos.getZ() - base.getZ()) <= LOCAL_TREE_CLEANUP_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    private void runLocalTreeCleanup(SkillContext context,
                                     ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos base) {
        if (context == null || source == null || bot == null || base == null || isAbortRequested(bot)) {
            return;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("radius", LOCAL_TREE_CLEANUP_RADIUS);
            params.put("verticalRange", LOCAL_TREE_CLEANUP_VERTICAL_RANGE);
            params.put("maxLogs", LOCAL_TREE_CLEANUP_MAX_LOGS);
            params.put("maxScaffold", LOCAL_TREE_CLEANUP_MAX_SCAFFOLD);
            params.put("durationMs", LOCAL_TREE_CLEANUP_DURATION_MS);
            params.put("sweep", false);
            params.put("scaffold", true);
            RequestedWorkBounds.CleanupBounds clamped = clampCleanupBounds(
                    base.getX() - LOCAL_TREE_CLEANUP_RADIUS,
                    base.getX() + LOCAL_TREE_CLEANUP_RADIUS,
                    base.getY() - 3,
                    base.getY() + LOCAL_TREE_CLEANUP_VERTICAL_RANGE,
                    base.getZ() - LOCAL_TREE_CLEANUP_RADIUS,
                    base.getZ() + LOCAL_TREE_CLEANUP_RADIUS);
            params.put("minX", clamped.minX());
            params.put("maxX", clamped.maxX());
            params.put("minY", clamped.minY());
            params.put("maxY", clamped.maxY());
            params.put("minZ", clamped.minZ());
            params.put("maxZ", clamped.maxZ());
            params.put("internal", true);
            WoodcutCleanupSkill cleanup = new WoodcutCleanupSkill();
            SkillContext cleanupCtx = new SkillContext(source, context.sharedState(), params, context.requestSource());
            var res = cleanup.execute(cleanupCtx);
            LOGGER.info("Woodcut local cleanup result for {}: success={} message='{}'",
                    base.toShortString(), res.success(), res.message());
        } catch (Exception e) {
            LOGGER.warn("Woodcut local cleanup failed near {}: {}", base.toShortString(), e.getMessage());
        }
    }

    private void runQuickPerTreeDropSweep(ServerPlayerEntity bot,
                                          ServerCommandSource source,
                                          WoodcutReachSession reachSession) {
        if (bot == null || source == null || isInventoryFull(bot) || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return;
        }
        if (isBoundedWorkMode()) {
            return;
        }
        double radius = QUICK_TREE_SWEEP_RADIUS;
        double verticalRange = QUICK_TREE_SWEEP_VERTICAL_RANGE;
        if (reachSession != null && (reachSession.usedScaffold || reachSession.cleanupIncomplete)) {
            radius = QUICK_TREE_SWEEP_RADIUS_SCAFFOLD;
            verticalRange = QUICK_TREE_SWEEP_VERTICAL_RANGE_SCAFFOLD;
        }
        try {
            DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), radius, verticalRange);
        } catch (Exception sweepError) {
            LOGGER.warn("Woodcut per-tree drop sweep failed: {}", sweepError.getMessage());
        }
    }

    private long runPostAttemptCollectionMaintenance(ServerCommandSource source,
                                                     ServerPlayerEntity bot,
                                                     WoodcutReachSession reachSession,
                                                     boolean huntPrerequisite,
                                                     WoodcutWorkAreaState workAreaState,
                                                     Set<BlockPos> pendingFloaters,
                                                     long lastCollectionMaintenanceAtMs) {
        if (bot == null || source == null || isAbortRequested(bot) || TaskService.isServerStopping()) {
            return lastCollectionMaintenanceAtMs;
        }
        long now = System.currentTimeMillis();
        boolean progressLikelyDroppedItems = reachSession != null
                && (reachSession.trunkMineAttemptsStarted > 0
                || reachSession.leafBlocksBroken > 0
                || reachSession.usedScaffold
                || reachSession.cleanupIncomplete);
        boolean sweepDueByTime = now - lastCollectionMaintenanceAtMs >= WOODCUT_COLLECTION_MAINTENANCE_INTERVAL_MS;
        if ((progressLikelyDroppedItems || sweepDueByTime) && !isInventoryFull(bot)) {
            runQuickPerTreeDropSweep(bot, source, reachSession);
            now = System.currentTimeMillis();
        }
        if (shouldProactivelyDepositAfterMaintenance(bot, huntPrerequisite)) {
            ensureWoodSpaceOrDeposit(source, bot, huntPrerequisite, true, workAreaState, pendingFloaters);
        }
        return now;
    }

    private boolean shouldProactivelyDepositAfterMaintenance(ServerPlayerEntity bot, boolean huntPrerequisite) {
        if (bot == null) {
            return false;
        }
        int empty = countEmptySlots(bot);
        int woodCount = countWood(bot);
        int emptyThreshold = huntPrerequisite ? 2 : WOODCUT_PROACTIVE_DEPOSIT_EMPTY_SLOTS;
        int woodThreshold = huntPrerequisite ? 128 : WOODCUT_PROACTIVE_DEPOSIT_WOOD_COUNT;
        return empty <= emptyThreshold || woodCount >= woodThreshold;
    }

    private boolean isOpenEnded(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        if (getBooleanParameter(params, "open_ended", false)) {
            return true;
        }
        Object opts = params.get("options");
        if (opts instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                if (o instanceof String str) {
                    if ("until_sunset".equalsIgnoreCase(str)
                            || "sunset".equalsIgnoreCase(str)
                            || "open_ended".equalsIgnoreCase(str)
                            || "open-ended".equalsIgnoreCase(str)) {
                        return true;
                    }
                }
            }
        }
        Object origin = params.get("_origin");
        if (origin instanceof String s && "ambient".equalsIgnoreCase(s)) {
            return true;
        }
        return false;
    }

    private boolean getBooleanParameter(Map<String, Object> parameters, String key, boolean fallback) {
        if (parameters == null || key == null) {
            return fallback;
        }
        Object value = parameters.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return fallback;
    }

    private boolean ensureWoodcutOperationalSurface(ServerCommandSource source,
                                                    ServerPlayerEntity bot,
                                                    ServerWorld world,
                                                    Map<String, Object> sharedState) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        if (BotFleeService.ensureAtSurface(bot, world) || BotFleeService.isAtSurface(bot, world)) {
            return true;
        }

        // Under dense canopy or on uneven terrain, the generic surface gate can fail even though
        // the bot is in a shallow pit or leaf-covered depression. Nudge to a nearby safe column
        // first, then escalate into the stronger pillar/ascent recovery path instead of bailing out.
        BlockPos repositioned = SafePositionService.findSafeColumn(world, bot.getBlockPos(), -2, 2);
        if (repositioned != null && !repositioned.equals(bot.getBlockPos())) {
            net.wcfcarolina13.GameAI.services.MovementService.nudgeTowardUntilClose(
                    bot, repositioned, 1.5D, 3_000L, 0.20D, "woodcut-reposition");
        }
        if (BotFleeService.isAtSurface(bot, world) || BotFleeService.ensureAtSurfaceForHobby(bot, world)) {
            return true;
        }

        WoodcutReachSession recoverySession = new WoodcutReachSession();
        int targetSurfaceY = estimateWoodcutSurfaceRecoveryY(world, bot);
        LOGGER.info("Woodcut startup surface recovery: escalating from {} toward surfaceY={}",
                bot.getBlockPos().toShortString(), targetSurfaceY);
        boolean recovered = recoverSurfacePosition(
                bot,
                world,
                source,
                targetSurfaceY,
                recoverySession,
                sharedState,
                "startup-surface");
        if (!recovered) {
            LOGGER.warn("Woodcut startup surface recovery failed at {} targetSurfaceY={}",
                    bot.getBlockPos().toShortString(), targetSurfaceY);
        }
        return recovered || BotFleeService.isAtSurface(bot, world);
    }

    private int estimateWoodcutSurfaceRecoveryY(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return 0;
        }
        int targetSurfaceY = bot.getBlockY() + 1;
        int groundY = SafePositionService.getWalkableGroundY(world, bot.getBlockX(), bot.getBlockZ());
        if (groundY > world.getBottomY()) {
            targetSurfaceY = Math.max(targetSurfaceY, groundY);
        }
        SafePositionService.SurfaceStagingCandidate staging =
                SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 10, false);
        if (staging != null) {
            targetSurfaceY = Math.max(targetSurfaceY, staging.pos().getY());
        }
        return targetSurfaceY;
    }

    private TreeHarvestResult fellTree(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       TreeDetector.TreeTarget target,
                                       Map<String, Object> sharedState,
                                       boolean replantSaplings,
                                       Set<BlockPos> pendingFloaters,
                                       WoodcutReachSession reachSession) {
        if (!(source.getWorld() instanceof ServerWorld world)) {
            return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "no-world");
        }
        boolean success = false;
        int totalMined = 0;
        try {
            BlockPos trunkBase = target.base();
            BlockPos column = trunkBase; // unified: always pillar at trunk position
            String terminalReason = "no-work";
            boolean wasSneaking = bot.isSneaking();
            try {
                bot.setSneaking(false);

                // Phase 1: Mine reachable trunk logs from ground level
                rememberGroundedWoodcutStandIfSafe(world, bot, reachSession, "fell-start");
                int groundMined = mineReachableBranches(bot, world, reachSession, target);
                totalMined += groundMined;

                List<BlockPos> remainingAfterGround = collectRemainingEnvelopeLogs(bot, world, target);
                if (remainingAfterGround.isEmpty()) {
                    success = true;
                    terminalReason = "all-logs-cleared";
                }

                if (!success && !isAbortRequested(bot)) {
                    // Phase 2: Position at trunk base (walk into cleared column)
                    boolean positioned = false;
                    if (isDryWoodcutStandCell(world, trunkBase)) {
                        positioned = moveToStand(source, bot, world, trunkBase, trunkBase, reachSession);
                    }
                    if (!positioned) {
                        MovementService.nudgeTowardUntilClose(bot, trunkBase, 2.25D, 1_800L, 0.20D, "fell-enter-trunk");
                        positioned = isBotInColumn(bot, column);
                    }
                    if (!positioned) {
                        BlockPos staging = findEntryStagingStand(world, trunkBase, bot.getBlockPos());
                        if (staging != null) {
                            moveToStand(source, bot, world, staging, trunkBase, reachSession);
                            MovementService.nudgeTowardUntilClose(bot, trunkBase, 2.25D, 1_200L, 0.20D, "fell-staged-nudge");
                            positioned = isBotInColumn(bot, column);
                        }
                    }

                    // Phase 3: Ascent loop (pillar up + mine branches + bridge sweep)
                    // The moveToStand distance tolerance can claim success when the bot
                    // is 1 block off from the column. Always verify with isBotInColumn
                    // and accept adjacent positions if exact positioning failed.
                    if (!isBotInColumn(bot, column)) {
                        BlockPos botPos = bot.getBlockPos();
                        if (Math.abs(botPos.getX() - column.getX()) <= 1
                                && Math.abs(botPos.getZ() - column.getZ()) <= 1
                                && isDryWoodcutStandCell(world, botPos)) {
                            column = new BlockPos(botPos.getX(), column.getY(), botPos.getZ());
                            positioned = true;
                            LOGGER.info("Woodcut pillar: using adjacent column at {} (trunk at {})",
                                    column.toShortString(), trunkBase.toShortString());
                        }
                    }
                    if (positioned || isBotInColumn(bot, column)) {
                        int maxSteps = Math.min(target.height() + 4, MAX_COLUMN_PILLAR_STEPS);
                        ensurePillarStock(bot, Math.min(maxSteps, 6), source, target.top().getY(), reachSession, sharedState);
                        bot.setSneaking(true);
                        int maxScanY = target.top().getY() + 2;
                        int pillarSteps = 0;
                        for (int step = 0; step <= maxSteps; step++) {
                            if (TaskService.isServerStopping() || isAbortRequested(bot)) {
                                terminalReason = "abort-requested";
                                break;
                            }

                            int minedThisLevel = mineReachableBranches(bot, world, reachSession, target);
                            totalMined += minedThisLevel;
                            boolean reachableRemain = !scanReachableLogs(bot, world, target).isEmpty();

                            boolean logsAbove = hasLogsAboveInColumn(world, column, bot.getBlockY(), maxScanY);
                            if (!logsAbove && !reachableRemain) {
                                break;
                            }
                            if (!isSupportedWoodcutStance(bot, world, column, target, reachSession, true)) {
                                LOGGER.info("Woodcut pillar: invalid stance before step at {} support={}",
                                        bot.getBlockPos().toShortString(), describeSupportBlock(world, bot.getBlockPos()));
                                terminalReason = "unsupported-stance";
                                break;
                            }
                            if (!clearOverheadForClimb(bot, world, target.base(), reachSession)) {
                                terminalReason = "no-jump-headroom";
                                break;
                            }

                            int startY = bot.getBlockY();
                            List<BlockPos> placed = ScaffoldService.pillarUpWithPositions(bot, 1);
                            LOGGER.info("Woodcut pillar step {} via ScaffoldService placed={}",
                                    step + 1, placed.size());
                            if (placed.isEmpty()) {
                                terminalReason = "pillar-failed";
                                break;
                            }
                            recordRecoveryPlacements(world, placed, sharedState, reachSession);
                            bot.setSneaking(true);
                            sleepQuiet(PILLAR_STEP_DELAY_MS);
                            if (!verifyPillarProgress(bot, world, column, target, reachSession, startY)) {
                                LOGGER.info("Woodcut pillar verification failed: startY={} bot={} support={}",
                                        startY, bot.getBlockPos().toShortString(), describeSupportBlock(world, bot.getBlockPos()));
                                terminalReason = "pillar-drifted";
                                break;
                            }
                            pillarSteps++;
                            if (reachSession != null) {
                                reachSession.verifiedPillarSteps++;
                            }

                            // Bridge sweep from elevated pillar: reach distant branch logs laterally
                            if (!isAbortRequested(bot) && pillarSteps >= 2) {
                                BlockPos preBridgePos = bot.getBlockPos().toImmutable();
                                for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
                                    if (isAbortRequested(bot)) break;
                                    boolean hasTargetsInDir = false;
                                    int maxBridgeLength = maxBridgeLengthWithinBounds(bot.getBlockPos(), bridgeDir, 6);
                                    if (maxBridgeLength <= 0) {
                                        continue;
                                    }
                                    for (int d = 2; d <= maxBridgeLength && !hasTargetsInDir; d++) {
                                        BlockPos probe = bot.getBlockPos().offset(bridgeDir, d);
                                        for (int dy = -2; dy <= 4; dy++) {
                                            BlockPos check = probe.up(dy);
                                            if (!isWithinRequestedBounds(check)) {
                                                continue;
                                            }
                                            if (world.getBlockState(check).isIn(BlockTags.LOGS)) {
                                                hasTargetsInDir = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (!hasTargetsInDir) continue;
                                    if (reachSession != null && reachSession.hazardProfile != null) {
                                        WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(bridgeDir);
                                        if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                                                || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                                            LOGGER.debug("Woodcut bridge: skipping {} direction (hazard={})", bridgeDir, rating);
                                            continue;
                                        }
                                    }
                                    BridgeScaffoldService.BridgeResult bridgeResult =
                                            BridgeScaffoldService.bridgeAndRetract(
                                                    bot, bridgeDir, maxBridgeLength, false,
                                                    state -> state.isIn(BlockTags.LOGS),
                                                    target.base(), PILLAR_BLOCKS);
                                    if (bridgeResult.targetsMined() > 0) {
                                        totalMined += bridgeResult.targetsMined();
                                        LOGGER.info("Woodcut ascent bridge: dir={} mined={} placed={} adopted={}",
                                                bridgeDir.asString(), bridgeResult.targetsMined(),
                                                bridgeResult.placedBlocks().size(), bridgeResult.adoptedBlocks());
                                    }
                                    // Re-center on pillar if bridge drifted the bot
                                    if (!bot.getBlockPos().equals(preBridgePos)) {
                                        MovementService.nudgeTowardUntilClose(
                                                bot, preBridgePos, 1.0, 1_500L, 0.15, "bridge-recenter");
                                    }
                                }
                            }
                        }

                        // Phase 4: Y-level-aware scaffold descent with elevated sweeps
                        totalMined += descendScaffoldColumn(source, bot, world, target, sharedState, reachSession);

                        // Phase 5: Final sweep
                        totalMined += mineReachableBranches(bot, world, reachSession, target);
                        totalMined += clearNearbyLowHangingCaps(source, bot, world, column, target, reachSession);
                    } else {
                        terminalReason = "positioning-failed";
                    }
                }
            } finally {
                bot.setSneaking(wasSneaking);
            }

            LOGGER.info("Woodcut unified: mined {} logs for base {}", totalMined, trunkBase.toShortString());
            reachSession.trunkMineAttemptsStarted = totalMined;

            // Fallback: if leftovers remain, pillar up near the tree and bridge outward
            List<BlockPos> leftoverClusterLogs = collectRemainingEnvelopeLogs(bot, world, target);
            if (!leftoverClusterLogs.isEmpty() && !isAbortRequested(bot)) {
                LOGGER.info("Woodcut fallback bridge: attempting pillar+bridge for {} remaining logs at base {}",
                        leftoverClusterLogs.size(), trunkBase.toShortString());
                int highestLogY = leftoverClusterLogs.stream().mapToInt(BlockPos::getY).max().orElse(trunkBase.getY());
                int pillarNeeded = Math.max(0, highestLogY - bot.getBlockY() - 1);
                pillarNeeded = Math.min(pillarNeeded, MAX_COLUMN_PILLAR_STEPS);
                boolean canBridge = pillarNeeded <= 0;
                if (pillarNeeded > 0) {
                    List<BlockPos> pillarPlaced = ScaffoldService.pillarUpWithPositions(bot, pillarNeeded);
                    if (!pillarPlaced.isEmpty()) {
                        recordRecoveryPlacements(world, pillarPlaced, sharedState, reachSession);
                        sleepQuiet(PILLAR_STEP_DELAY_MS);
                        canBridge = true;
                    } else {
                        LOGGER.info("Woodcut fallback bridge: pillar failed, skipping bridge sweep");
                    }
                }
                if (canBridge) {
                    for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
                        if (isAbortRequested(bot)) break;
                        boolean hasTargetsInDir = false;
                        int maxBridgeLength = maxBridgeLengthWithinBounds(bot.getBlockPos(), bridgeDir, 6);
                        if (maxBridgeLength <= 0) {
                            continue;
                        }
                        for (int d = 1; d <= maxBridgeLength && !hasTargetsInDir; d++) {
                            BlockPos probe = bot.getBlockPos().offset(bridgeDir, d);
                            for (int dy = -3; dy <= 4; dy++) {
                                BlockPos check = probe.up(dy);
                                if (!isWithinRequestedBounds(check)) {
                                    continue;
                                }
                                if (world.getBlockState(check).isIn(BlockTags.LOGS)) {
                                    hasTargetsInDir = true;
                                    break;
                                }
                            }
                        }
                        if (!hasTargetsInDir) continue;
                        if (reachSession != null && reachSession.hazardProfile != null) {
                            WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(bridgeDir);
                            if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                                    || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                                LOGGER.debug("Woodcut bridge: skipping {} direction (hazard={})", bridgeDir, rating);
                                continue;
                            }
                        }
                        BridgeScaffoldService.BridgeResult bridgeResult =
                                BridgeScaffoldService.bridgeAndRetract(
                                        bot, bridgeDir, maxBridgeLength, false,
                                        state -> state.isIn(BlockTags.LOGS),
                                        trunkBase, PILLAR_BLOCKS);
                        if (bridgeResult.targetsMined() > 0) {
                            totalMined += bridgeResult.targetsMined();
                            LOGGER.info("Woodcut fallback bridge: dir={} mined={} placed={} adopted={}",
                                    bridgeDir.asString(), bridgeResult.targetsMined(),
                                    bridgeResult.placedBlocks().size(), bridgeResult.adoptedBlocks());
                        }
                    }
                    totalMined += mineReachableBranches(bot, world, reachSession, target);
                }
                // Descend scaffold before cleanup — same as Phase 4
                if (reachSession != null && reachSession.hasPlacements() && !isAbortRequested(bot)) {
                    totalMined += descendScaffoldColumn(source, bot, world, target, sharedState, reachSession);
                }
                if (reachSession != null && reachSession.hasPlacements() && !isAbortRequested(bot)) {
                    cleanupReachSession(source, bot, target.base(), reachSession, sharedState);
                }
                leftoverClusterLogs = collectRemainingEnvelopeLogs(bot, world, target);
                if (totalMined > 0) {
                    reachSession.trunkMineAttemptsStarted = totalMined;
                }
            }
            if (!leftoverClusterLogs.isEmpty()) {
                WoodcutKnowledgeService.updateTreeSite(bot, world, target, leftoverClusterLogs, Map.of());
                leftoverClusterLogs.forEach(pos -> {
                    pendingFloaters.add(pos.toImmutable());
                    recordCleanupFloater(bot, sharedState, world, pos);
                });
                LOGGER.info("Tree {} has {} leftover log(s): {}",
                        trunkBase.toShortString(),
                        leftoverClusterLogs.size(),
                        leftoverClusterLogs.stream().limit(4).map(BlockPos::toShortString).toList());
                return new TreeHarvestResult(false, totalMined > 0 ? WoodcutFailureReason.PATH_OR_REACH_FAILURE : WoodcutFailureReason.TRUNK_NEVER_STARTED,
                        totalMined > 0 ? "cluster-logs-remained" : terminalReason);
            }
            WoodcutKnowledgeService.updateTreeSite(bot, world, target, List.of(), Map.of());

            success = totalMined > 0 || terminalReason.equals("all-logs-cleared");
            return new TreeHarvestResult(success, success ? WoodcutFailureReason.NO_TARGET : WoodcutFailureReason.PATH_OR_REACH_FAILURE,
                    success ? "success" : terminalReason);
        } finally {
            if (reachSession != null && reachSession.hasPlacements()) {
                if (!isAbortRequested(bot)) {
                    cleanupReachSession(source, bot, target.base(), reachSession, sharedState);
                    cleanupNearbyScaffold(bot, target.base(), sharedState);
                    cleanupNearbyScaffold(bot, bot.getBlockPos(), sharedState);
                }
            }
        }
    }

    private List<BlockPos> collectRemainingEnvelopeLogs(ServerPlayerEntity bot,
                                                        ServerWorld world,
                                                        TreeDetector.TreeTarget target) {
        if (world == null || target == null) {
            return List.of();
        }
        BlockPos seedMin = target.envelopeMin().add(-WOODCUT_LOG_SCAN_EXPANSION, -1, -WOODCUT_LOG_SCAN_EXPANSION);
        BlockPos seedMax = target.envelopeMax().add(WOODCUT_LOG_SCAN_EXPANSION, WOODCUT_LOG_SCAN_EXPANSION, WOODCUT_LOG_SCAN_EXPANSION);
        BlockPos clusterMin = seedMin.add(-2, -2, -2);
        BlockPos clusterMax = seedMax.add(2, 4, 2);
        Map<Long, BlockPos> allLogs = new LinkedHashMap<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        for (BlockPos candidate : BlockPos.iterate(clusterMin, clusterMax)) {
            if (world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
                allLogs.put(candidate.asLong(), candidate.toImmutable());
            }
        }
        if (allLogs.isEmpty()) {
            return List.of();
        }
        for (BlockPos candidate : BlockPos.iterate(seedMin, seedMax)) {
            if (!world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
                continue;
            }
            BlockPos seed = candidate.toImmutable();
            if (visited.add(seed.asLong())) {
                frontier.add(seed);
            }
        }
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.add(dx, dy, dz);
                        BlockPos candidate = allLogs.get(neighbor.asLong());
                        if (candidate == null || visited.contains(candidate.asLong())) {
                            continue;
                        }
                        if (!isClusterConnectedByLeaves(world, current, candidate)) {
                            continue;
                        }
                        visited.add(candidate.asLong());
                        frontier.add(candidate);
                    }
                }
            }
        }
        List<BlockPos> remaining = new ArrayList<>();
        for (Long key : visited) {
            BlockPos pos = allLogs.get(key);
            if (pos != null && isWithinRequestedBounds(pos)) {
                remaining.add(pos);
            }
        }
        remaining = bot != null
                ? WoodcutKnowledgeService.mergeRememberedLogs(bot, world, target, remaining)
                : remaining;
        if (activeRequestedBounds != null) {
            // NOTE: Stream.toList() returns an unmodifiable list. The
            // subsequent sort() call below would throw UOE. Wrap in a
            // mutable ArrayList.
            remaining = remaining.stream()
                    .filter(this::isWithinRequestedBounds)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        remaining.sort(Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingInt(pos -> Math.abs(pos.getX() - target.base().getX()) + Math.abs(pos.getZ() - target.base().getZ())));
        return remaining;
    }


    private List<BlockPos> currentColumnPlacements(WoodcutReachSession reachSession, BlockPos column) {
        List<BlockPos> placements = new ArrayList<>();
        if (reachSession == null || column == null) {
            return placements;
        }
        for (BlockPos placed : reachSession.placementsDescending()) {
            if (placed == null || !reachSession.placedKeys.contains(placed.asLong())) {
                continue;
            }
            if (placed.getX() == column.getX() && placed.getZ() == column.getZ()) {
                placements.add(placed);
            }
        }
        return placements;
    }

    /**
     * Descends through the scaffold column level-by-level (top to bottom), mining bridge
     * blocks at each Y, running elevated/bridge sweeps for logs, then mining the column
     * block under the bot's feet to trigger a gravity drop to the next level.
     *
     * This replaces the old Phase 4 which iterated scaffold blocks but never moved the bot,
     * leaving it stranded at canopy height.
     *
     * @return total log blocks mined during descent
     */
    private int descendScaffoldColumn(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       ServerWorld world,
                                       TreeDetector.TreeTarget target,
                                       Map<String, Object> sharedState,
                                       WoodcutReachSession reachSession) {
        int logsMined = 0;

        // Group all remaining scaffold placements by Y-level (descending order)
        TreeMap<Integer, List<BlockPos>> byLevel = new TreeMap<>(Comparator.reverseOrder());
        for (BlockPos placed : reachSession.placementsDescending()) {
            if (placed == null) continue;
            if (!reachSession.placedKeys.contains(placed.asLong())) continue;
            if (world.getBlockState(placed).isAir()) continue;
            byLevel.computeIfAbsent(placed.getY(), k -> new ArrayList<>()).add(placed);
        }

        int botY = bot.getBlockPos().getY();
        int botX = bot.getBlockPos().getX();
        int botZ = bot.getBlockPos().getZ();

        for (Map.Entry<Integer, List<BlockPos>> entry : byLevel.entrySet()) {
            if (TaskService.isServerStopping() || isAbortRequested(bot)) break;

            int levelY = entry.getKey();
            List<BlockPos> blocks = entry.getValue();

            // Skip levels above the bot — should have been mined during ascent
            if (levelY > botY) continue;

            // If bot has fallen below this level, stop descent
            if (bot.getBlockPos().getY() < levelY) break;

            bot.setSneaking(true);

            // Identify the scaffold column X/Z from the blocks at this level (not the bot's
            // position, which may have drifted). The column block is the one that was part of
            // the pillar — look for the most common X/Z among blocks at this level.
            BlockPos columnBlock = null;
            List<BlockPos> bridgeBlocks = new ArrayList<>();
            for (BlockPos bp : blocks) {
                // Column blocks are vertically aligned with the pillar — check if any block
                // at this level shares X/Z with blocks at adjacent levels
                boolean isColumn = false;
                for (BlockPos other : reachSession.placementsDescending()) {
                    if (other.getY() != bp.getY() && other.getX() == bp.getX() && other.getZ() == bp.getZ()) {
                        isColumn = true;
                        break;
                    }
                }
                if (isColumn && columnBlock == null) {
                    columnBlock = bp;
                } else {
                    bridgeBlocks.add(bp);
                }
            }

            // Step 1: Mine bridge blocks (horizontal extensions) — reachable from current pos
            bot.setSneaking(true);
            for (BlockPos bridge : bridgeBlocks) {
                if (isAbortRequested(bot)) break;
                if (world.getBlockState(bridge).isAir()) continue;
                if (!reachSession.placedKeys.contains(bridge.asLong())) continue;
                if (isWithinReach(bot, bridge)) {
                    LookController.faceBlock(bot, bridge);
                    if (mineBlock(bot, bridge, false)) {
                        forgetScaffoldPlacement(sharedState, world, bridge);
                        reachSession.recordRemoval(bridge);
                    }
                }
            }

            // Step 2: Elevated sweep — mine reachable logs WITHOUT moving off the scaffold.
            // During descent, the bot must stay on the column. Only mine logs within reach
            // from the current position — do NOT call clearPathToTarget which would walk
            // the bot off the scaffold.
            bot.setSneaking(true);
            for (int sweepPass = 0; sweepPass < 3; sweepPass++) {
                if (isAbortRequested(bot)) break;
                BlockPos botPos = bot.getBlockPos();
                BlockPos found = null;
                for (BlockPos check : BlockPos.iterate(botPos.add(-4, -2, -4), botPos.add(4, 4, 4))) {
                    if (!world.getBlockState(check).isIn(BlockTags.LOGS)) continue;
                    if (!isWithinReach(bot, check)) continue;
                    TreeDetector.WoodcutProtectionDecision prot =
                            getWoodcutMutationDecision(world, check, target.base());
                    if (prot.blocked()) continue;
                    found = check.toImmutable();
                    break;
                }
                if (found == null) break;
                // Mine in place — do NOT walk toward the log
                ensureAxeOrRetrieve(bot);
                LookController.faceBlock(bot, found);
                if (mineBlock(bot, found, true)) {
                    logsMined++;
                }
                bot.setSneaking(true); // re-assert sneak after mining
            }

            // Step 3: Bridge sweep — try bridging in each cardinal direction for logs
            if (!isAbortRequested(bot)) {
                for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
                    if (isAbortRequested(bot)) break;
                    boolean hasTargetsInDir = false;
                    for (int d = 2; d <= 6 && !hasTargetsInDir; d++) {
                        BlockPos probe = bot.getBlockPos().offset(bridgeDir, d);
                        for (int dy = -2; dy <= 4; dy++) {
                            if (world.getBlockState(probe.up(dy)).isIn(BlockTags.LOGS)) {
                                hasTargetsInDir = true;
                                break;
                            }
                        }
                    }
                    if (!hasTargetsInDir) continue;
                    if (reachSession.hazardProfile != null) {
                        WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(bridgeDir);
                        if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                                || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                            LOGGER.debug("Woodcut bridge: skipping {} direction (hazard={})", bridgeDir, rating);
                            continue;
                        }
                    }
                    BridgeScaffoldService.BridgeResult bridgeResult =
                            BridgeScaffoldService.bridgeAndRetract(
                                    bot, bridgeDir, 6, false,
                                    state -> state.isIn(BlockTags.LOGS),
                                    target.base(),
                                    PILLAR_BLOCKS);
                    if (bridgeResult.targetsMined() > 0) {
                        logsMined += bridgeResult.targetsMined();
                        LOGGER.info("Woodcut descent bridge sweep: dir={} mined={} placed={} adopted={}",
                                bridgeDir.asString(),
                                bridgeResult.targetsMined(),
                                bridgeResult.placedBlocks().size(),
                                bridgeResult.adoptedBlocks());
                    }
                }
            }

            // Step 4: Mine the column block under the bot's feet — triggers gravity drop.
            // The bot MUST be precisely on the column block so it falls straight down.
            // After sweeps the bot may have drifted — reposition before mining.
            bot.setSneaking(true);
            if (columnBlock != null && !isAbortRequested(bot)) {
                BlockPos aboveColumn = columnBlock.up();

                // Ensure bot is standing exactly on the column block
                if (!bot.getBlockPos().equals(aboveColumn)) {
                    // Try nudge first
                    MovementService.nudgeTowardUntilClose(
                            bot, aboveColumn, 0.5, 2_000L, 0.15, "scaffold-descent-recenter");

                    // If still not on the right block, force-position onto the column center.
                    // This is safe: the bot is on its own scaffold, we just need it centered
                    // so the gravity drop goes straight down.
                    if (!bot.getBlockPos().equals(aboveColumn)) {
                        Vec3d center = Vec3d.ofBottomCenter(aboveColumn);
                        world.getServer().execute(() -> {
                            bot.setPosition(center.x, center.y, center.z);
                            bot.setVelocity(Vec3d.ZERO);
                        });
                        sleepQuiet(100L);
                    }
                }

                // Final check: only mine if we're actually on the column
                if (!bot.getBlockPos().equals(aboveColumn)) {
                    LOGGER.warn("Scaffold descent: cannot position on column at {}, skipping drop",
                            aboveColumn.toShortString());
                } else if (!world.getBlockState(columnBlock).isAir() && reachSession.placedKeys.contains(columnBlock.asLong())) {
                    if (mineAdaptiveBlock(bot, columnBlock, target.base(), reachSession)) {
                        forgetScaffoldPlacement(sharedState, world, columnBlock);
                        reachSession.recordRemoval(columnBlock);

                        // Wait for gravity
                        sleepQuiet(200L);

                        // Check if we landed on solid non-scaffold ground — stop descent
                        BlockPos support = bot.getBlockPos().down();
                        if (!world.getBlockState(support).isAir() && !reachSession.placedKeys.contains(support.asLong())) {
                            LOGGER.debug("Woodcut descent: landed on solid ground at Y={}", bot.getBlockPos().getY());
                            // Harvest any remaining reachable logs after landing
                            bot.setSneaking(true);
                            logsMined += mineReachableBranches(bot, world, reachSession, target);
                            break;
                        }
                    }
                }
            }

            // Update bot position for next iteration
            botY = bot.getBlockPos().getY();
        }

        return logsMined;
    }

    /** Checks if the trunk/scaffold column has any log blocks above the bot's current reach. */
    private boolean hasLogsAboveInColumn(ServerWorld world, BlockPos trunkXZ, int botY, int maxScanY) {
        for (int y = botY + 2; y <= maxScanY; y++) {
            BlockPos check = new BlockPos(trunkXZ.getX(), y, trunkXZ.getZ());
            if (world.getBlockState(check).isIn(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans for all log blocks within reach distance of the bot.
     * Does NOT check tree ownership — mines any log within reach.
     */
    private List<BlockPos> scanReachableLogs(ServerPlayerEntity bot,
                                            ServerWorld world,
                                            TreeDetector.TreeTarget target) {
        List<BlockPos> reachable = new ArrayList<>();
        if (bot == null || world == null) {
            return reachable;
        }
        List<BlockPos> clusterLogs = target != null ? collectRemainingEnvelopeLogs(bot, world, target) : List.of();
        for (BlockPos candidate : clusterLogs) {
            if (isWithinReach(bot, candidate)) {
                reachable.add(candidate.toImmutable());
            }
        }
        reachable.sort(Comparator
                .comparingInt((BlockPos p) -> p.getY())
                .thenComparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));
        return reachable;
    }

    /** Clears the 2 blocks above the bot's head so it can jump-place from inside the column. */
    private boolean clearOverheadForClimb(ServerPlayerEntity bot,
                                          ServerWorld world,
                                          BlockPos associatedTargetBase,
                                          WoodcutReachSession reachSession) {
        boolean clear = true;
        for (BlockPos pos : new BlockPos[]{bot.getBlockPos().up(), bot.getBlockPos().up(2)}) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (!mineAdaptiveBlock(bot, pos, associatedTargetBase, reachSession)) {
                clear = false;
            }
        }
        return clear && isDryWoodcutStandCell(world, bot.getBlockPos()) && isJumpHeadroomClear(world, bot.getBlockPos());
    }

    private boolean verifyPillarProgress(ServerPlayerEntity bot,
                                         ServerWorld world,
                                         BlockPos column,
                                         TreeDetector.TreeTarget target,
                                         WoodcutReachSession reachSession,
                                         int startY) {
        if (bot == null || world == null || bot.getBlockY() <= startY) {
            return false;
        }
        if (!isSupportedWoodcutStance(bot, world, column, target, reachSession, false)) {
            return false;
        }
        BlockPos support = bot.getBlockPos().down();
        if (support.getY() != startY) {
            return false;
        }
        return reachSession == null
                || !reachSession.hasPlacements()
                || reachSession.placedKeys.contains(support.asLong());
    }

    private String describeSupportBlock(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null) {
            return "none";
        }
        BlockPos below = stand.down();
        BlockState state = world.getBlockState(below);
        return below.toShortString() + ":" + state.getBlock().getTranslationKey();
    }


    private boolean isClusterConnectedByLeaves(ServerWorld world, BlockPos from, BlockPos to) {
        if (world == null || from == null || to == null) {
            return false;
        }
        int minX = Math.min(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxY = Math.max(from.getY(), to.getY());
        int maxZ = Math.max(from.getZ(), to.getZ());
        for (BlockPos check : BlockPos.iterate(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState state = world.getBlockState(check);
            if (state.isAir()
                    || state.isIn(BlockTags.LOGS)
                    || state.isIn(BlockTags.LEAVES)
                    || state.isReplaceable()
                    || state.isOf(Blocks.SNOW)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** Breaks leaf blocks along the ray to a target log so the bot can reach it. */
    private void clearPathToTarget(ServerPlayerEntity bot, BlockPos target) {
        if (!isWithinRequestedBounds(target)) {
            return;
        }
        if (hasLineOfSight(bot, Vec3d.ofCenter(target))) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        // Break up to 5 obstructing leaves along the ray (dense canopy needs 4-5)
        for (int i = 0; i < 5; i++) {
            if (hasLineOfSight(bot, Vec3d.ofCenter(target))) return;
            RaycastContext ctx = new RaycastContext(bot.getEyePos(), Vec3d.ofCenter(target),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, bot);
            var hit = world.raycast(ctx);
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
            BlockPos hitPos = hit.getBlockPos();
            if (hitPos.equals(target)) return;
            if (!isWithinRequestedBounds(hitPos)) {
                return;
            }
            BlockState state = world.getBlockState(hitPos);
            if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW)) {
                selectHandsOrHarmlessItem(bot);
                LookController.faceBlock(bot, hitPos);
                mineBlock(bot, hitPos, true);
                sleepQuiet(40L);
            } else {
                return; // hit something solid that isn't a leaf — stop
            }
        }
    }

    /** Mines all log blocks within reach at the current position. Breaks obstructing leaves. */
    private int mineReachableBranches(ServerPlayerEntity bot,
                                      ServerWorld world,
                                      WoodcutReachSession reachSession,
                                      TreeDetector.TreeTarget target) {
        int mined = 0;
        Set<Long> protectedSkips = new HashSet<>();
        for (int pass = 0; pass < 20; pass++) {
            if (isAbortRequested(bot)) {
                break;
            }
            List<BlockPos> logs = scanReachableLogs(bot, world, target);
            if (!protectedSkips.isEmpty()) {
                logs.removeIf(p -> protectedSkips.contains(p.asLong()));
            }
            if (logs.isEmpty()) {
                break;
            }
            // Prefer logs with clear line-of-sight to avoid wasting leaf-clear budget
            logs.sort(Comparator
                    .comparingInt((BlockPos p) -> hasLineOfSight(bot, Vec3d.ofCenter(p)) ? 0 : 1)
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));
            BlockPos log = logs.get(0);
            clearPathToTarget(bot, log);
            LookController.faceBlock(bot, log);
            ensureAxeOrRetrieve(bot);
            if (mineBlock(bot, log, true)) {
                mined++;
            } else {
                // Block still exists after failed mine — likely protected; skip on future passes
                if (!world.getBlockState(log).isAir()) {
                    protectedSkips.add(log.asLong());
                }
                logs.remove(0);
                if (logs.isEmpty()) {
                    break;
                }
            }
        }
        return mined;
    }

    private int clearNearbyLowHangingCaps(ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          ServerWorld world,
                                          BlockPos column,
                                          TreeDetector.TreeTarget target,
                                          WoodcutReachSession reachSession) {
        if (source == null || bot == null || world == null || column == null || target == null) {
            return 0;
        }
        int mined = 0;
        for (int pass = 0; pass < 4; pass++) {
            int minedThisPass = mineReachableBranches(bot, world, reachSession, target);
            mined += minedThisPass;

            List<BlockPos> remaining = collectRemainingEnvelopeLogs(bot, world, target).stream()
                    .filter(pos -> Math.abs(pos.getX() - column.getX()) <= 2)
                    .filter(pos -> Math.abs(pos.getZ() - column.getZ()) <= 2)
                    .filter(pos -> pos.getY() <= bot.getBlockY() + 3)
                    .sorted(Comparator
                            .comparingInt(BlockPos::getY)
                            .thenComparingDouble(pos -> bot.getBlockPos().getSquaredDistance(pos)))
                    .toList();
            if (remaining.isEmpty()) {
                break;
            }

            BlockPos next = remaining.get(0);
            BlockPos stand = resolveColumnStand(world,
                    new BlockPos(next.getX(), determineColumnAnchorY(next, remaining, target), next.getZ()),
                    next.getY() - 1);
            if (stand == null || bot.getBlockPos().getSquaredDistance(stand) > 16.0D) {
                break;
            }

            boolean wasSneaking = bot.isSneaking();
            bot.setSneaking(false);
            try {
                if (!moveToStand(source, bot, world, stand, next)) {
                    break;
                }
            } finally {
                bot.setSneaking(wasSneaking);
            }
            LOGGER.info("Woodcut cap cleanup: repositioned to {} for low cap {}", stand.toShortString(), next.toShortString());
        }
        return mined;
    }

    private long toColumnKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), 0, pos.getZ());
    }

    private boolean isBotInColumn(ServerPlayerEntity bot, BlockPos column) {
        BlockPos botPos = bot.getBlockPos();
        return botPos.getX() == column.getX() && botPos.getZ() == column.getZ();
    }

    private boolean isSupportedWoodcutStance(ServerPlayerEntity bot,
                                             ServerWorld world,
                                             BlockPos column,
                                             TreeDetector.TreeTarget target,
                                             WoodcutReachSession reachSession,
                                             boolean requireJumpHeadroom) {
        if (bot == null || world == null || column == null || !isBotInColumn(bot, column)) {
            return false;
        }
        BlockPos foot = bot.getBlockPos();
        if (!isDryWoodcutStandCell(world, foot)) {
            return false;
        }
        if (target != null) {
            int lowestTargetY = collectRemainingEnvelopeLogs(bot, world, target).stream()
                    .filter(pos -> pos.getX() == column.getX() && pos.getZ() == column.getZ())
                    .mapToInt(BlockPos::getY)
                    .min()
                    .orElse(target.top().getY());
            if (foot.getY() >= lowestTargetY) {
                return false;
            }
        }
        if (requireJumpHeadroom && !isJumpHeadroomClear(world, foot)) {
            return false;
        }
        BlockPos below = foot.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        if (reachSession == null || !reachSession.hasPlacements()) {
            return true;
        }
        return reachSession.placedKeys.contains(below.asLong())
                || below.getY() < column.getY()
                || belowState.isIn(BlockTags.LOGS);
    }

    private int determineColumnAnchorY(BlockPos columnHint,
                                       List<BlockPos> remainingLogs,
                                       TreeDetector.TreeTarget target) {
        int fallback = target != null ? target.base().getY() : (columnHint != null ? columnHint.getY() : 0);
        if (columnHint == null || remainingLogs == null || remainingLogs.isEmpty()) {
            return fallback;
        }
        int best = Integer.MAX_VALUE;
        for (BlockPos log : remainingLogs) {
            if (log == null) {
                continue;
            }
            if (log.getX() == columnHint.getX() && log.getZ() == columnHint.getZ()) {
                best = Math.min(best, log.getY() - 1);
            }
        }
        if (best != Integer.MAX_VALUE) {
            return Math.max(best, fallback - 2);
        }
        int nearestY = Integer.MAX_VALUE;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos log : remainingLogs) {
            if (log == null) {
                continue;
            }
            double dx = log.getX() - columnHint.getX();
            double dz = log.getZ() - columnHint.getZ();
            double dist = dx * dx + dz * dz;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestY = log.getY() - 1;
            }
        }
        if (nearestY != Integer.MAX_VALUE) {
            return Math.max(nearestY, fallback - 2);
        }
        return fallback;
    }

    private Direction directionToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return null;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (dx > 0) {
                return Direction.EAST;
            }
            if (dx < 0) {
                return Direction.WEST;
            }
        } else {
            if (dz > 0) {
                return Direction.SOUTH;
            }
            if (dz < 0) {
                return Direction.NORTH;
            }
        }
        return null;
    }


    private BlockPos resolveColumnStand(ServerWorld world, BlockPos column, int anchorY) {
        if (world == null || column == null) {
            return null;
        }
        List<BlockPos> safeCandidates = new ArrayList<>();
        // Scan upward first (cleared trunk interior), then downward
        for (int phase = 0; phase < 2; phase++) {
            int start = phase == 0 ? 0 : -1;
            int end   = phase == 0 ? 6 : -18;
            int step  = phase == 0 ? 1 : -1;
            for (int dy = start; phase == 0 ? dy <= end : dy >= end; dy += step) {
                BlockPos foot = new BlockPos(column.getX(), anchorY + dy, column.getZ());
                if (!world.isChunkLoaded(foot) || !world.isChunkLoaded(foot.up()) || !world.isChunkLoaded(foot.down())) {
                    continue;
                }
                if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.up()).isEmpty()) {
                    continue;
                }
                if (isSafeWoodcutWorkStand(world, foot)) {
                    safeCandidates.add(foot.toImmutable());
                }
            }
        }
        return safeCandidates.stream()
                .min(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingInt(pos -> Math.abs(anchorY - pos.getY())))
                .orElse(null);
    }

    private boolean isJumpHeadroomClear(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        BlockPos jumpHead = foot.up(2);
        return world.getBlockState(jumpHead).getCollisionShape(world, jumpHead).isEmpty();
    }


    private BlockPos findEntryStagingStand(ServerWorld world, BlockPos stand, BlockPos botPos) {
        if (world == null || stand == null) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (Math.abs(dx) + Math.abs(dz) > 3) {
                    continue;
                }
                BlockPos horizontal = stand.add(dx, 0, dz);
                candidates.add(horizontal);
                candidates.add(horizontal.down());
                candidates.add(horizontal.up());
            }
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && !candidate.equals(stand))
                .filter(candidate -> isSafeWoodcutWorkStand(world, candidate))
                .min(Comparator
                        .comparingDouble((BlockPos candidate) -> candidate.getSquaredDistance(stand))
                        .thenComparingDouble(candidate ->
                                botPos == null ? 0.0D : botPos.getSquaredDistance(candidate)))
                .orElse(null);
    }

    /**
     * Trunk-climb approach: ascend the empty trunk column placing scaffold,
     * then descend mining branch logs at each level.
     */
    private boolean approachBase(ServerCommandSource source,
                                 ServerPlayerEntity bot,
                                 BlockPos base,
                                 Map<String, Object> sharedState,
                                 WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!isWithinRequestedBounds(base)) {
            LOGGER.info("Woodcut bounded: rejecting approach base {} outside requested bounds {}", base.toShortString(), activeRequestedBounds);
            return false;
        }
        LOGGER.info("Woodcut approach: target base={} from {}", base.toShortString(), bot.getBlockPos().toShortString());
        clearBaseObstacles(world, bot, base);
        if (isTrunkWithinReach(world, base, bot)) {
            return true;
        }
        // First try a low, human-like standable near the base.
        BlockPos nearby = findDryStandableNear(world, base, 4, 4);
        if (nearby != null && isWithinRequestedBounds(nearby)) {
            LOGGER.info("Woodcut approach: direct dry stand {} for {}", nearby.toShortString(), base.toShortString());
            Direction towardNearby = directionToward(bot.getBlockPos(), nearby);
            if (towardNearby != null) {
                MovementService.clearLeafObstructionDetailed(bot, towardNearby);
            }
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    nearby,
                    nearby,
                    null,
                    null,
                    bot.getHorizontalFacing());
            MovementService.MovementResult res = executeMovementPlan(source, bot, plan, "approach-direct");
            if (res.success() || isTrunkWithinReach(world, base, bot)) {
                return true;
            }
            LOGGER.info("Woodcut approach: abandoned dry stand {} for {} (reason=path failed detail={})",
                    nearby.toShortString(), base.toShortString(), res.detail());
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        // Fallback to planner if simple stand failed.
        MovementService.MovementOptions options = WOODCUT_MOVEMENT_OPTIONS;
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, base, options);
        if (planOpt.isPresent()) {
            MovementService.MovementPlan plan = planOpt.get();
            BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
            if (!isWithinRequestedBounds(approach)) {
                LOGGER.info("Woodcut bounded: rejecting planner approach {} for {} reason=out-of-bounds", approach.toShortString(), base.toShortString());
            } else if (!isUsableWoodcutStand(world, approach)) {
                LOGGER.info("Woodcut approach: planner candidate {} for {} rejected (reason=no dry stand)",
                        approach.toShortString(), base.toShortString());
            } else {
                LOGGER.info("Woodcut approach: planner target {} for {} mode={}",
                        approach.toShortString(), base.toShortString(), plan.mode());
                Direction towardApproach = directionToward(bot.getBlockPos(), approach);
                if (towardApproach != null) {
                    MovementService.clearLeafObstructionDetailed(bot, towardApproach);
                }
                MovementService.MovementResult result = executeMovementPlan(source, bot, plan, "approach-plan");
                if (result.success() || isTrunkWithinReach(world, base, bot)) {
                    return true;
                }
                LOGGER.warn("Woodcut approach: planner move to {} for {} failed (reason=path failed detail={})",
                        approach.toShortString(), base.toShortString(), result.detail());
            }
            // Clear overhead soft blocks around bot before repositioning — the bot may be in a
            // depression with leaves at head level preventing all diagonal movement.
            clearLocalOverheadObstacles(world, bot);
            if (tryDryRepositionAroundTrunk(source, bot, world, base, reachSession, sharedState)) {
                return true;
            }
            ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
        } else {
            LOGGER.info("Woodcut approach: no planner path for {} (reason=no dry stand)", base.toShortString());
        }
        // Last resort: try to pillar from here to reach the trunk directly.
        if (prepareReach(bot, source, base, reachSession, sharedState)) {
            return true;
        }
        if (reachSession != null && reachSession.hasPlacements()) {
            LOGGER.warn("Woodcut approach: cleaning incomplete temporary scaffold for {}", base.toShortString());
            cleanupReachSession(source, bot, base, reachSession, sharedState);
        }
        ReturnBaseStuckService.tickAndCheckStuck(bot, Vec3d.ofCenter(base), ReturnBaseStuckService.StuckProfile.WOODCUT);
        LOGGER.warn("Woodcut approach: no trunk reach for {} (reason=no trunk reach)", base.toShortString());
        return false;
    }

    private boolean tryDryRepositionAroundTrunk(ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos base,
                                                WoodcutReachSession reachSession,
                                                Map<String, Object> sharedState) {
        List<BlockPos> candidates = new ArrayList<>();
        Set<Long> attemptedStands = new HashSet<>();
        int uniqueAttempts = 0;
        for (int radius = 1; radius <= 2; radius++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                candidates.add(base.offset(direction, radius).toImmutable());
                candidates.add(base.offset(direction, radius).down().toImmutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));
        for (BlockPos candidate : candidates) {
            if (isAbortRequested(bot)) {
                return false;
            }
            BlockPos stand = findDryStandableNear(world, candidate, 1, 2);
            if (stand == null || !isUsableWoodcutStand(world, stand)) {
                continue;
            }
            if (!isWithinRequestedBounds(stand)) {
                continue;
            }
            if (!attemptedStands.add(stand.asLong())) {
                continue;
            }
            uniqueAttempts++;
            if (uniqueAttempts > 8) {
                LOGGER.info("Woodcut approach: abandoning dry reposition search for {} after {} unique stand attempts",
                        base.toShortString(), uniqueAttempts - 1);
                break;
            }
            LOGGER.info("Woodcut approach: retrying trunk reposition via {}", stand.toShortString());
            if (moveToStand(source, bot, world, stand, base)) {
                if (reachSession != null) {
                    reachSession.clearRepositionFailureTracking();
                }
                return true;
            }
            if (reachSession != null) {
                reachSession.recordRepositionFailure(base, stand, "reposition-around-trunk");
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES
                        && tryBoundedTerrainCorrection(bot, source, world, base, stand, reachSession, sharedState)) {
                    reachSession.clearRepositionFailureTracking();
                    return true;
                }
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES * 2) {
                    LOGGER.info("Woodcut approach: abandoning repeated trunk reposition failures for {} via {}",
                            base.toShortString(), stand.toShortString());
                    break;
                }
            }
        }
        LOGGER.info("Woodcut approach: abandoned {} (reason=no trunk reach)", base.toShortString());
        return false;
    }

    private void clearBaseObstacles(ServerWorld world, ServerPlayerEntity bot, BlockPos base) {
        int radius = 2;
        for (BlockPos pos : BlockPos.iterate(base.add(-radius, 0, -radius), base.add(radius, 2, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LEAVES) || state.isReplaceable()) {
                breakSoftBlock(world, bot, pos);
            }
        }
    }

    /** Clear soft overhead blocks around the bot's current position (1-block radius, Y+1 to Y+3).
     *  Prevents the bot from getting stuck in depressions with overhead leaves blocking diagonal movement. */
    private void clearLocalOverheadObstacles(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) return;
        BlockPos feet = bot.getBlockPos();
        int cleared = 0;
        for (BlockPos pos : BlockPos.iterate(feet.add(-1, 1, -1), feet.add(1, 3, 1))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isReplaceable()) {
                breakSoftBlock(world, bot, pos);
                cleared++;
            }
        }
        if (cleared > 0) {
            LOGGER.info("Woodcut approach: cleared {} overhead soft blocks around {} for stuck recovery",
                    cleared, feet.toShortString());
        }
    }

    private boolean ensureWoodSpaceOrDeposit(ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             boolean huntPrerequisite,
                                             WoodcutWorkAreaState workAreaState,
                                             Set<BlockPos> pendingFloaters) {
        return ensureWoodSpaceOrDeposit(source, bot, huntPrerequisite, false, workAreaState, pendingFloaters);
    }

    private boolean ensureWoodSpaceOrDeposit(ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             boolean huntPrerequisite,
                                             boolean forceDeposit,
                                             WoodcutWorkAreaState workAreaState,
                                             Set<BlockPos> pendingFloaters) {
        if (bot == null || source == null) {
            return true;
        }
        int empty = countEmptySlots(bot);
        int woodCount = countWood(bot);
        // Hunt prerequisite only needs ~8 logs; 1 empty slot is enough. Normal runs need more room.
        int minEmptySlots = huntPrerequisite ? 0 : 2;
        boolean needsDeposit = forceDeposit || empty <= minEmptySlots || woodCount > 256;
        LOGGER.info("Woodcut inventory: emptySlots={} woodCount={} needsDeposit={} forceDeposit={}",
                empty, woodCount, needsDeposit, forceDeposit);
        if (!needsDeposit) {
            return true;
        }
        if (isBoundedWorkMode()) {
            LOGGER.info("Woodcut bounded: refusing offload outside requested bounds {}; inventory remains constrained", activeRequestedBounds);
            return false;
        }
        List<ChestStoreService.StorageChestCandidate> candidates = ChestStoreService.listDepositChestCandidates(
                source,
                bot,
                WoodcutSkill::isWoodSnapshot,
                WOODCUT_OFFLOAD_LOCAL_CHEST_RADIUS,
                WOODCUT_OFFLOAD_LOCAL_CHEST_YSPAN,
                140.0D * 140.0D);
        long localCount = candidates.stream().filter(candidate -> "local-scan".equals(candidate.source())).count();
        long rememberedCount = candidates.stream().filter(candidate -> candidate.source().startsWith("remembered-owner")).count();
        LOGGER.info("Woodcut deposit candidates: local={} remembered={} localRadius={} probeRadius={} chosenOrder={}",
                localCount,
                rememberedCount,
                WOODCUT_OFFLOAD_LOCAL_CHEST_RADIUS,
                String.format(Locale.ROOT, "%.1f", Math.sqrt(WOODCUT_NEARBY_REMEMBERED_PROBE_DIST_SQ)),
                candidates.stream().limit(5).map(candidate -> candidate.source() + ":" + candidate.pos().toShortString()).toList());
        Set<Long> blockedCandidates = new HashSet<>();
        if (woodCount > 0) {
            for (ChestStoreService.StorageChestCandidate candidate : candidates) {
                if (isAbortRequested(bot)) {
                    return false;
                }
                if (candidate == null || candidate.pos() == null || blockedCandidates.contains(candidate.pos().asLong())) {
                    continue;
                }
                logWoodcutOffloadProbeCandidate(candidate, "wood-only");
                ChestStoreService.DepositProbeResult probe =
                        ChestStoreService.probeDepositMatchingObstacleAware(source, bot, candidate.pos(), this::isWoodStack);
                logWoodcutOffloadProbeResult(candidate, probe, "wood-only");
                if (probe.moved() > 0) {
                    ChatUtils.sendSystemMessage(source,
                            candidate.source().startsWith("remembered-owner")
                                    ? "Deposited wood into a remembered chest."
                                    : "Deposited wood into a nearby chest.");
                    if (candidate.source().startsWith("remembered-owner")) {
                        returnToWoodcutAreaAfterDeposit(source, bot, candidate, workAreaState, pendingFloaters);
                    }
                    return true;
                }
                if (shouldBlacklistFailedOffloadProbe(probe)) {
                    blockedCandidates.add(candidate.pos().asLong());
                }
            }
        }

        List<ChestStoreService.StorageChestCandidate> genericFallbackCandidates = candidates.stream()
                .filter(candidate -> candidate != null && candidate.pos() != null && !blockedCandidates.contains(candidate.pos().asLong()))
                .filter(candidate -> shouldAttemptGenericWoodcutOffloadCandidate(candidate, woodCount, empty, forceDeposit))
                .toList();
        if (genericFallbackCandidates.size() < candidates.size()) {
            long skippedRemembered = candidates.size() - genericFallbackCandidates.size();
            LOGGER.info("Woodcut generic offload candidates filtered: kept={} skipped={} woodCount={} emptySlots={} forceDeposit={}",
                    genericFallbackCandidates.size(),
                    skippedRemembered,
                    woodCount,
                    empty,
                    forceDeposit);
        }

        for (ChestStoreService.StorageChestCandidate candidate : genericFallbackCandidates) {
            if (isAbortRequested(bot)) {
                return false;
            }
            logWoodcutOffloadProbeCandidate(candidate, "generic");
            ChestStoreService.DepositProbeResult probe =
                    ChestStoreService.probeDepositMatchingObstacleAware(source, bot, candidate.pos(), this::isWoodcutOffloadCandidate);
            logWoodcutOffloadProbeResult(candidate, probe, "generic");
            if (probe.moved() > 0) {
                ChatUtils.sendSystemMessage(source,
                        candidate.source().startsWith("remembered-owner")
                                ? "Stored extra items in a remembered chest."
                                : "Stored extra items in a nearby chest.");
                if (candidate.source().startsWith("remembered-owner")) {
                    returnToWoodcutAreaAfterDeposit(source, bot, candidate, workAreaState, pendingFloaters);
                }
                return true;
            }
        }

        BlockPos placed = ChestStoreService.placeChestNearBot(source, bot, true);
        if (placed != null) {
            ChestStoreService.DepositProbeResult placedWoodProbe =
                    ChestStoreService.probeDepositMatchingObstacleAware(source, bot, placed, this::isWoodStack);
            LOGGER.info("Woodcut deposit attempt (placed chest): chest={} moved={} reached={} interacted={}",
                    placed.toShortString(),
                    placedWoodProbe.moved(),
                    placedWoodProbe.reachedStand(),
                    placedWoodProbe.interacted());
            if (placedWoodProbe.moved() > 0) {
                ChatUtils.sendSystemMessage(source, "Deposited wood into the new chest.");
                return true;
            }
            ChestStoreService.DepositProbeResult placedFallbackProbe =
                    ChestStoreService.probeDepositMatchingObstacleAware(source, bot, placed, this::isWoodcutOffloadCandidate);
            LOGGER.info("Woodcut deposit attempt (placed chest fallback): chest={} moved={} reached={} interacted={}",
                    placed.toShortString(),
                    placedFallbackProbe.moved(),
                    placedFallbackProbe.reachedStand(),
                    placedFallbackProbe.interacted());
            if (placedFallbackProbe.moved() > 0) {
                ChatUtils.sendSystemMessage(source, "Stored extra items in the new chest.");
                return true;
            }
        }

        recoverAfterUtilityPlacementFailure(source, bot);
        LOGGER.warn("Inventory full and couldn't store items (no reachable/usable chest, or chests are full).");
        ChatUtils.sendSystemMessage(source, "Inventory is full and I couldn't store items (no reachable chest or chests are full).");
        return false;
    }

    private boolean shouldAttemptGenericWoodcutOffloadCandidate(ChestStoreService.StorageChestCandidate candidate,
                                                                int woodCount,
                                                                int emptySlots,
                                                                boolean forceDeposit) {
        if (candidate == null) {
            return false;
        }
        if (!candidate.source().startsWith("remembered-owner")) {
            return true;
        }
        if (candidate.knownCapacity() && candidate.emptySlots() > 0) {
            LOGGER.info("Woodcut offload probe candidate accepted: chest={} source={} mode=known-capacity dist={}",
                    candidate.pos().toShortString(),
                    candidate.source(),
                    String.format(Locale.ROOT, "%.2f", Math.sqrt(candidate.distSq())));
            return true;
        }
        if (!candidate.knownCapacity() && candidate.distSq() <= WOODCUT_NEARBY_REMEMBERED_PROBE_DIST_SQ) {
            LOGGER.info("Woodcut offload probe candidate accepted: chest={} source={} mode=nearby-unknown dist={}",
                    candidate.pos().toShortString(),
                    candidate.source(),
                    String.format(Locale.ROOT, "%.2f", Math.sqrt(candidate.distSq())));
            return true;
        }
        if (forceDeposit && emptySlots <= 0 && woodCount > 0 && candidate.knownCapacity() && candidate.emptySlots() > 0) {
            LOGGER.info("Woodcut offload probe candidate accepted: chest={} source={} mode=forced-known-capacity dist={}",
                    candidate.pos().toShortString(),
                    candidate.source(),
                    String.format(Locale.ROOT, "%.2f", Math.sqrt(candidate.distSq())));
            return true;
        }
        LOGGER.info("Woodcut offload candidate rejected: chest={} source={} reason=too-remote-unknown knownCapacity={} emptySlots={} dist={}",
                candidate.pos().toShortString(),
                candidate.source(),
                candidate.knownCapacity(),
                candidate.emptySlots(),
                String.format(Locale.ROOT, "%.2f", Math.sqrt(candidate.distSq())));
        return false;
    }

    private void logWoodcutOffloadProbeCandidate(ChestStoreService.StorageChestCandidate candidate, String phase) {
        if (candidate == null || candidate.pos() == null) {
            return;
        }
        LOGGER.info("Woodcut offload probe candidate accepted: phase={} chest={} source={} preferred={} knownCapacity={} emptySlots={} dist={}",
                phase,
                candidate.pos().toShortString(),
                candidate.source(),
                candidate.preferredContents(),
                candidate.knownCapacity(),
                candidate.emptySlots(),
                String.format(Locale.ROOT, "%.2f", Math.sqrt(candidate.distSq())));
    }

    private void logWoodcutOffloadProbeResult(ChestStoreService.StorageChestCandidate candidate,
                                              ChestStoreService.DepositProbeResult probe,
                                              String phase) {
        if (candidate == null || candidate.pos() == null || probe == null) {
            return;
        }
        String outcome;
        if (probe.moved() > 0) {
            outcome = "probed-and-deposited";
        } else if (!probe.chestPresent()) {
            outcome = "rejected-before-move";
        } else if (!probe.reachedStand()) {
            outcome = "probed-but-unreachable";
        } else if (!probe.interacted()) {
            outcome = "probed-but-blocked";
        } else {
            outcome = "probed-but-full";
        }
        LOGGER.info("Woodcut offload probe result: phase={} outcome={} chest={} source={} moved={} reached={} interacted={}",
                phase,
                outcome,
                candidate.pos().toShortString(),
                candidate.source(),
                probe.moved(),
                probe.reachedStand(),
                probe.interacted());
    }

    private boolean shouldBlacklistFailedOffloadProbe(ChestStoreService.DepositProbeResult probe) {
        if (probe == null) {
            return false;
        }
        return !probe.chestPresent() || !probe.reachedStand() || !probe.interacted() || probe.moved() == 0;
    }

    private static boolean isWoodSnapshot(BotChestRegistryService.ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.itemId == null) {
            return false;
        }
        String itemId = snapshot.itemId.toLowerCase(Locale.ROOT);
        return itemId.contains("_log")
                || itemId.contains("_wood")
                || itemId.contains("_stem")
                || itemId.contains("_hyphae")
                || itemId.contains("_planks")
                || itemId.contains("bamboo_block");
    }

    private void returnToWoodcutAreaAfterDeposit(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 ChestStoreService.StorageChestCandidate candidate,
                                                 WoodcutWorkAreaState workAreaState,
                                                 Set<BlockPos> pendingFloaters) {
        if (source == null || bot == null || candidate == null || workAreaState == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos anchor = workAreaState.preferredAnchor(pendingFloaters != null && !pendingFloaters.isEmpty());
        if (anchor == null) {
            return;
        }
        if (candidate.pos().getSquaredDistance(anchor) <= 64.0D) {
            LOGGER.info("Woodcut return anchor skipped: chest={} anchor={} source={} reason=already-local",
                    candidate.pos().toShortString(), anchor.toShortString(), candidate.source());
            return;
        }
        BlockPos stand = findDryStandableNear(world, anchor, 4, 4);
        if (stand == null) {
            stand = findDryStandableNear(world, anchor, 6, 6);
        }
        if (stand == null) {
            LOGGER.info("Woodcut return anchor failed: chest={} anchor={} source={} reason=no-safe-stand",
                    candidate.pos().toShortString(), anchor.toShortString(), candidate.source());
            return;
        }
        boolean returned = moveToStand(source, bot, world, stand, anchor);
        LOGGER.info("Woodcut return anchor: chest={} anchor={} stand={} source={} success={} bot={}",
                candidate.pos().toShortString(),
                anchor.toShortString(),
                stand.toShortString(),
                candidate.source(),
                returned,
                bot.getBlockPos().toShortString());
    }

    private void recoverAfterUtilityPlacementFailure(ServerCommandSource source, ServerPlayerEntity bot) {
        if (source == null || bot == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (tryUtilityPlacementLocalRecovery(source, bot, world, "utility-placement-failure")) {
            return;
        }
        int targetSurfaceY = estimateWoodcutSurfaceRecoveryY(world, bot);
        if (targetSurfaceY > bot.getBlockY()) {
            LOGGER.info("Woodcut utility placement recovery: escalating surface recovery from {} toward {}",
                    bot.getBlockPos().toShortString(), targetSurfaceY);
            recoverSurfacePosition(bot, world, source, targetSurfaceY, new WoodcutReachSession(), new HashMap<>(), "utility-placement-failure");
        }
    }

    private boolean tryUtilityPlacementLocalRecovery(ServerCommandSource source,
                                                     ServerPlayerEntity bot,
                                                     ServerWorld world,
                                                     String label) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        BlockPos dryStand = findDryStandableNear(world, bot.getBlockPos(), 4, 4);
        if (dryStand != null && !dryStand.equals(bot.getBlockPos())) {
            LOGGER.info("Woodcut {}: local relocation {} -> {}",
                    label,
                    bot.getBlockPos().toShortString(),
                    dryStand.toShortString());
            if (moveToStand(source, bot, world, dryStand, dryStand)) {
                return true;
            }
        }
        SafePositionService.SurfaceStagingCandidate staging =
                SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 8, false);
        if (staging != null && !staging.pos().equals(bot.getBlockPos())) {
            LOGGER.info("Woodcut {}: local staging {} score={}",
                    label,
                    staging.pos().toShortString(),
                    staging.score());
            if (moveTowardRecoveryStaging(source, bot, staging.pos(), label + "-staging")) {
                return true;
            }
        }
        return false;
    }

    private boolean prepareReach(ServerPlayerEntity bot,
                                 ServerCommandSource source,
                                 BlockPos target,
                                 WoodcutReachSession reachSession,
                                 Map<String, Object> sharedState) {
        boolean onPillar = reachSession != null && reachSession.hasPlacements();
        if (isWithinReach(bot, target)) {
            LOGGER.debug("Woodcut reach: {} already within reach", target.toShortString());
            return true;
        }
        if (!onPillar) {
            moveUnderTarget(source, bot, target);
            if (isWithinReach(bot, target)) {
                LOGGER.debug("Woodcut reach: moved under {}", target.toShortString());
                return true;
            }
            if (tryReposition(bot, source, target, reachSession, sharedState)) {
                LOGGER.debug("Woodcut reach: repositioned near {}", target.toShortString());
                return true;
            }
            boolean canAttemptLeafClear = reachSession == null || !reachSession.preReachLeafClearAttempted;
            if (canAttemptLeafClear) {
                if (reachSession != null) {
                    reachSession.preReachLeafClearAttempted = true;
                }
                // Uneven terrain and low canopies often need one small leaf-clear pass before a usable stand opens up.
                clearObstructionAlongRay(bot, Vec3d.ofCenter(target), target);
                clearBlockingLeaves(bot, target, target, reachSession);
                if (isReadyToMineTarget(bot, target)) {
                    LOGGER.debug("Woodcut reach: leaf-clear opened LOS to {}", target.toShortString());
                    return true;
                }
                moveUnderTarget(source, bot, target);
                if (isWithinReach(bot, target)) {
                    LOGGER.debug("Woodcut reach: moved under {} after leaf-clear", target.toShortString());
                    return true;
                }
                if (tryReposition(bot, source, target, reachSession, sharedState)) {
                    LOGGER.debug("Woodcut reach: repositioned near {} after leaf-clear", target.toShortString());
                    return true;
                }
            }
        }
        int needed = target.getY() - bot.getBlockY() - 1;
        if (needed <= 0) {
            // We are above the target (canopy case). Try descending near the trunk and re-evaluate reach.
            if (descendTowardTarget(bot, source, target)) {
                return true;
            }
            return false;
        }
        LOGGER.info("Woodcut reach: pillaring {} blocks to reach {}", needed, target.toShortString());
        if (pillarUp(bot, needed, reachSession, source, sharedState)) {
            if (isReadyToMineTarget(bot, target)) {
                return true;
            }
            LOGGER.warn("Woodcut reach: pillar built but no trunk reach for {}", target.toShortString());
            if (tryReposition(bot, source, target, reachSession, sharedState) && isReadyToMineTarget(bot, target)) {
                LOGGER.info("Woodcut reach: post-pillar reposition succeeded for {}", target.toShortString());
                return true;
            }
            LOGGER.warn("Woodcut reach: post-pillar reposition failed for {}", target.toShortString());
            return false;
        }
        // Emergency: try a single underfoot placement to break climb-stall
        LOGGER.warn("Pillar placement failed; attempting emergency underfoot scaffold");
        if (!emergencyStep(bot, reachSession, sharedState)) {
            return false;
        }
        return isReadyToMineTarget(bot, target);
    }

    private boolean tryReposition(ServerPlayerEntity bot,
                                  ServerCommandSource source,
                                  BlockPos target,
                                  WoodcutReachSession reachSession,
                                  Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MovementService.MovementOptions options = WOODCUT_MOVEMENT_OPTIONS;
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, target, options);
        if (planOpt.isEmpty()) {
            LOGGER.info("Woodcut approach: reposition around {} failed (reason=no dry stand)", target.toShortString());
            if (reachSession != null && tryBoundedTerrainCorrection(bot, source, world, target, null, reachSession, sharedState)) {
                reachSession.clearRepositionFailureTracking();
                return true;
            }
            return false;
        }
        MovementService.MovementPlan plan = planOpt.get();
        BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
        if (!isWithinRequestedBounds(approach)) {
            LOGGER.info("Woodcut bounded: reposition candidate {} for {} rejected (reason=requested-bounds)",
                    approach.toShortString(), target.toShortString());
            return false;
        }
        if (!isUsableWoodcutStand(world, approach)) {
            LOGGER.info("Woodcut approach: reposition candidate {} for {} rejected (reason=no dry stand)",
                    approach.toShortString(), target.toShortString());
            if (reachSession != null && tryBoundedTerrainCorrection(bot, source, world, target, approach, reachSession, sharedState)) {
                reachSession.clearRepositionFailureTracking();
                return true;
            }
            return false;
        }
        LOGGER.info("Woodcut approach: repositioning toward {} via {}", target.toShortString(), approach.toShortString());
        MovementService.MovementResult res = executeMovementPlan(source, bot, plan, "reposition");
        if (!res.success() && !isWithinReach(bot, target)) {
            LOGGER.info("Woodcut approach: reposition toward {} failed (reason=path failed detail={})",
                    target.toShortString(), res.detail());
            if (reachSession != null) {
                reachSession.recordRepositionFailure(target, approach, res.detail());
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES
                        && tryBoundedTerrainCorrection(bot, source, world, target, approach, reachSession, sharedState)) {
                    reachSession.clearRepositionFailureTracking();
                    return true;
                }
                if (reachSession.repeatedRepositionFailures >= MAX_IDENTICAL_REPOSITION_FAILURES * 2) {
                    LOGGER.info("Woodcut approach: abandoning repeated blocked reposition toward {} via {} detail={}",
                            target.toShortString(), approach.toShortString(), res.detail());
                    return false;
                }
            }
        } else if (reachSession != null) {
            reachSession.clearRepositionFailureTracking();
        }
        return res.success() || isWithinReach(bot, target);
    }

    private boolean tryBoundedTerrainCorrection(ServerPlayerEntity bot,
                                                ServerCommandSource source,
                                                ServerWorld world,
                                                BlockPos target,
                                                BlockPos preferredStand,
                                                WoodcutReachSession reachSession,
                                                Map<String, Object> sharedState) {
        if (bot == null || source == null || world == null || target == null || reachSession == null) {
            return false;
        }
        if (reachSession.minorTerrainCorrections >= MAX_MINOR_TERRAIN_CORRECTIONS_PER_TARGET) {
            return false;
        }
        List<BlockPos> candidates = collectTerrainCorrectionCandidates(world, target, preferredStand);
        for (BlockPos stand : candidates) {
            if (stand == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
                break;
            }
            if (isUsableWoodcutStand(world, stand)) {
                if (moveToStand(source, bot, world, stand, target)) {
                    LOGGER.info("Woodcut terrain recovery: reused sloped stand {} for {}", stand.toShortString(), target.toShortString());
                    return true;
                }
                continue;
            }
            if (!canCreateMinorSupportStand(world, stand)) {
                continue;
            }
            BlockPos support = stand.down();
            LOGGER.info("Woodcut terrain recovery: placing temporary support {} for {}", support.toShortString(), target.toShortString());
            if (!tryPlaceScaffold(bot, support, sharedState, reachSession)) {
                continue;
            }
            reachSession.minorTerrainCorrections++;
            if (moveToStand(source, bot, world, stand, target)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> collectTerrainCorrectionCandidates(ServerWorld world,
                                                              BlockPos target,
                                                              BlockPos preferredStand) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        if (preferredStand != null) {
            candidates.add(preferredStand.toImmutable());
            for (Direction direction : Direction.Type.HORIZONTAL) {
                candidates.add(preferredStand.offset(direction).toImmutable());
                candidates.add(preferredStand.offset(direction).down().toImmutable());
            }
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            candidates.add(target.offset(direction).toImmutable());
            candidates.add(target.offset(direction).down().toImmutable());
        }
        List<BlockPos> ordered = new ArrayList<>(candidates);
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(target)));
        return ordered;
    }

    private boolean canCreateMinorSupportStand(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null || !world.isChunkLoaded(stand) || !world.isChunkLoaded(stand.down())) {
            return false;
        }
        BlockPos head = stand.up();
        BlockPos support = stand.down();
        BlockPos supportBelow = support.down();
        if (!world.isChunkLoaded(head) || !world.isChunkLoaded(supportBelow)) {
            return false;
        }
        if (!world.getFluidState(stand).isEmpty()
                || !world.getFluidState(head).isEmpty()
                || !world.getFluidState(support).isEmpty()) {
            return false;
        }
        if (!isPlaceableTarget(world, stand) || !isPlaceableTarget(world, support)) {
            return false;
        }
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        return !world.getBlockState(supportBelow).getCollisionShape(world, supportBelow).isEmpty();
    }

    private boolean moveToStand(ServerCommandSource source,
                                ServerPlayerEntity bot,
                                ServerWorld world,
                                BlockPos stand,
                                BlockPos target) {
        return moveToStand(source, bot, world, stand, target, null);
    }

    private boolean moveToStand(ServerCommandSource source,
                                ServerPlayerEntity bot,
                                ServerWorld world,
                                BlockPos stand,
                                BlockPos target,
                                WoodcutReachSession reachSession) {
        if (source == null || bot == null || world == null || stand == null) {
            return false;
        }
        if (!isWithinRequestedBounds(stand) || (target != null && !isWithinRequestedBounds(target))) {
            LOGGER.info("Woodcut bounded: rejecting stand move stand={} target={} bounds={}",
                    stand.toShortString(),
                    target == null ? "none" : target.toShortString(),
                    activeRequestedBounds);
            if (reachSession != null) {
                reachSession.recordFailedReroute(target, stand, "requested-bounds");
            }
            return false;
        }
        if (!isSafeWoodcutWorkStand(world, stand)) {
            LOGGER.info("Woodcut canopy route rejected: stand={} target={} reason=unsafe-work-stand canopy={} support={}",
                    stand.toShortString(),
                    target == null ? "none" : target.toShortString(),
                    isLeafFloorStand(world, stand),
                    describeSupportBlock(world, stand));
            if (reachSession != null) {
                reachSession.recordFailedReroute(target, stand, "unsafe-target");
            }
            return tryRerouteUnsafeWoodcutMove(source, bot, world, stand, target, "unsafe-target", reachSession);
        }
        boolean closeEnough = moveToStandDirect(source, bot, world, stand, target, reachSession);
        if (!closeEnough && reachSession != null) {
            reachSession.recordFailedReroute(target, stand, "move-failed");
        }
        if (closeEnough && isUnsafeWoodcutPerch(world, bot.getBlockPos())) {
            LOGGER.info("Woodcut unsafe perch after move: bot={} stand={} target={} support={}",
                    bot.getBlockPos().toShortString(),
                    stand.toShortString(),
                    target == null ? "none" : target.toShortString(),
                    describeSupportBlock(world, bot.getBlockPos()));
            if (reachSession != null) {
                reachSession.recordFailedReroute(target, stand, "unsafe-post-move");
            }
            return tryRerouteUnsafeWoodcutMove(source, bot, world, stand, target, "post-move", reachSession);
        }
        return closeEnough;
    }

    private boolean moveToStandDirect(ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      ServerWorld world,
                                      BlockPos stand,
                                      BlockPos target,
                                      WoodcutReachSession reachSession) {
        if (source == null || bot == null || world == null || stand == null) {
            return false;
        }
        if (!isWithinRequestedBounds(stand) || (target != null && !isWithinRequestedBounds(target))) {
            return false;
        }
        breakSoftBlock(world, bot, stand);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult result = executeMovementPlan(source, bot, plan, "move-to-stand");
        if (!result.success()) {
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        return result.success()
                || bot.getBlockPos().getSquaredDistance(stand) <= 4.0D
                || (target != null && isWithinReach(bot, target));
    }

    private boolean tryRerouteUnsafeWoodcutMove(ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos desiredStand,
                                                BlockPos target,
                                                String label,
                                                WoodcutReachSession reachSession) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        List<BlockPos> candidates = collectUnsafeWoodcutRerouteCandidates(world, bot, desiredStand, target, reachSession);
        for (BlockPos candidate : candidates) {
            if (candidate == null || candidate.equals(bot.getBlockPos())) {
                continue;
            }
            if (reachSession != null && reachSession.isBlacklistedReroute(target, candidate)) {
                reachSession.rerouteBlacklistSkips++;
                continue;
            }
            LOGGER.info("Woodcut reroute [{}]: bot={} desired={} candidate={} target={}",
                    label,
                    bot.getBlockPos().toShortString(),
                    desiredStand == null ? "none" : desiredStand.toShortString(),
                    candidate.toShortString(),
                    target == null ? "none" : target.toShortString());
            if (moveToStandDirect(source, bot, world, candidate, target, reachSession) && !isUnsafeWoodcutPerch(world, bot.getBlockPos())) {
                return true;
            }
            if (reachSession != null) {
                reachSession.recordFailedReroute(target, candidate, "reroute-rejected");
            }
        }
        return false;
    }

    private List<BlockPos> collectUnsafeWoodcutRerouteCandidates(ServerWorld world,
                                                                 ServerPlayerEntity bot,
                                                                 BlockPos desiredStand,
                                                                 BlockPos target,
                                                                 WoodcutReachSession reachSession) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        if (world == null || bot == null) {
            return List.of();
        }
        if (target != null) {
            BlockPos trunkStand = findColumnStand(world, target);
            addSafeWoodcutRerouteCandidate(candidates, world, trunkStand);
            addSafeWoodcutRerouteCandidate(candidates, world, findDryStandableNear(world, target, 2, 6));
            addSafeWoodcutRerouteCandidate(candidates, world, findDryStandableNear(world, target.down(), 3, 8));
        }
        if (desiredStand != null) {
            addSafeWoodcutRerouteCandidate(candidates, world, findEntryStagingStand(world, desiredStand, bot.getBlockPos()));
            addSafeWoodcutRerouteCandidate(candidates, world, findDryStandableNear(world, desiredStand, 2, 4));
        }
        addSafeWoodcutRerouteCandidate(candidates, world, findDryStandableNear(world, bot.getBlockPos(), 2, 3));
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(this::isWithinRequestedBounds)
                .filter(pos -> reachSession == null || !reachSession.isBlacklistedReroute(target, pos))
                .sorted(Comparator.comparingDouble(pos -> {
                    SafePositionService.SurfaceCandidateAssessment assessment =
                            SafePositionService.analyzeSurfaceCandidate(world, pos, pos);
                    boolean precisionChurnRisk = WoodcutRecoveryHeuristics.isPrecisionChurnRisk(
                            assessment.cardinalStandableNeighbors(),
                            assessment.totalStandableNeighbors(),
                            assessment.steepDropNeighbors(),
                            assessment.blockedCardinals());
                    return WoodcutRecoveryHeuristics.rerouteCandidateScore(
                            bot.getBlockPos(),
                            pos,
                            target,
                            assessment.cardinalStandableNeighbors(),
                            assessment.totalStandableNeighbors(),
                            assessment.steepDropNeighbors(),
                            assessment.blockedCardinals(),
                            precisionChurnRisk);
                }))
                .toList();
    }

    private void addSafeWoodcutRerouteCandidate(Set<BlockPos> candidates,
                                                ServerWorld world,
                                                BlockPos candidate) {
        if (candidates == null || world == null || candidate == null) {
            return;
        }
        if (!isSafeWoodcutWorkStand(world, candidate)) {
            return;
        }
        candidates.add(candidate.toImmutable());
    }


    private boolean isReadyToMineTarget(ServerPlayerEntity bot, BlockPos target) {
        return bot != null
                && target != null
                && isWithinReach(bot, target)
                && hasLineOfSight(bot, Vec3d.ofCenter(target));
    }

    private void rememberGroundedWoodcutStandIfSafe(ServerWorld world,
                                                    ServerPlayerEntity bot,
                                                    WoodcutReachSession reachSession,
                                                    String label) {
        if (world == null || bot == null || reachSession == null) {
            return;
        }
        if (!isWoodcutOperationallyGrounded(world, bot, reachSession)) {
            return;
        }
        BlockPos previous = reachSession.groundedWoodcutStand();
        BlockPos current = bot.getBlockPos().toImmutable();
        reachSession.rememberGroundedWoodcutStand(current);
        if (!current.equals(previous)) {
            LOGGER.info("Woodcut grounded stance: label={} stand={} support={}",
                    label,
                    current.toShortString(),
                    describeSupportBlock(world, current));
        }
    }

    private boolean isWoodcutOperationallyGrounded(ServerWorld world,
                                                   ServerPlayerEntity bot,
                                                   WoodcutReachSession reachSession) {
        if (world == null || bot == null) {
            return false;
        }
        BlockPos foot = bot.getBlockPos();
        if (!isSafeWoodcutWorkStand(world, foot)) {
            return false;
        }
        int groundY = SafePositionService.getWalkableGroundY(world, foot.getX(), foot.getZ());
        if (groundY > world.getBottomY() && foot.getY() - groundY >= 3) {
            return false;
        }
        if (reachSession == null || !reachSession.hasPlacements()) {
            return true;
        }
        BlockPos support = foot.down();
        return !reachSession.placedKeys.contains(support.asLong()) && !reachSession.hasVerifiedPillarSteps();
    }

    private List<BlockPos> collectWoodcutLocalRecoveryCandidates(ServerWorld world,
                                                                 ServerPlayerEntity bot,
                                                                 BlockPos focus,
                                                                 BlockPos base,
                                                                 WoodcutReachSession reachSession) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        if (reachSession != null) {
            candidates.add(reachSession.groundedWoodcutStand());
        }
        if (focus != null) {
            candidates.add(findDryStandableNear(world, focus, 3, 3));
            candidates.add(findDryStandableNear(world, focus, 5, 4));
        }
        if (base != null) {
            candidates.add(findDryStandableNear(world, base, 3, 3));
            candidates.add(findDryStandableNear(world, base, 5, 4));
        }
        if (bot != null) {
            candidates.add(findDryStandableNear(world, bot.getBlockPos(), 3, 3));
            candidates.add(findDryStandableNear(world, bot.getBlockPos(), 5, 4));
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingDouble(pos -> bot == null ? 0.0D : bot.getBlockPos().getSquaredDistance(pos)))
                .toList();
    }

    private boolean tryRecoverWoodcutLocalStance(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 ServerWorld world,
                                                 BlockPos focus,
                                                 BlockPos base,
                                                 WoodcutReachSession reachSession,
                                                 String label) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        if (isWoodcutOperationallyGrounded(world, bot, reachSession)) {
            rememberGroundedWoodcutStandIfSafe(world, bot, reachSession, label + "-already-grounded");
            return true;
        }
        for (BlockPos candidate : collectWoodcutLocalRecoveryCandidates(world, bot, focus, base, reachSession)) {
            if (candidate.equals(bot.getBlockPos())) {
                continue;
            }
            LOGGER.info("Woodcut local recovery: label={} bot={} candidate={} focus={}",
                    label,
                    bot.getBlockPos().toShortString(),
                    candidate.toShortString(),
                    focus == null ? "none" : focus.toShortString());
            if (!moveToStand(source, bot, world, candidate, focus == null ? candidate : focus, reachSession)) {
                continue;
            }
            if (isWoodcutOperationallyGrounded(world, bot, reachSession)) {
                rememberGroundedWoodcutStandIfSafe(world, bot, reachSession, label + "-moved");
                return true;
            }
        }
        return false;
    }


    private void logTargetTrace(TreeDetector.TreeTarget target,
                                WoodcutReachSession reachSession,
                                String terminalReason) {
        if (target == null || reachSession == null) {
            return;
        }
        LOGGER.info("Woodcut target trace: base={} top={} height={} detectionMode={} trunkMineStarts={} leafBlocksBroken={} scaffoldPlaced={} scaffoldRemoved={} terminalReason={}",
                target.base().toShortString(),
                target.top().toShortString(),
                target.height(),
                TreeDetector.TreeDetectionMode.STRICT_STANDING,
                reachSession.trunkMineAttemptsStarted,
                reachSession.leafBlocksBroken,
                reachSession.scaffoldPlaced,
                reachSession.scaffoldRemoved,
                terminalReason);
        if (reachSession.trunkMineAttemptsStarted == 0) {
            LOGGER.warn("Woodcut target {} selected but no trunk mining call was ever issued", target.base().toShortString());
        }
        if (reachSession.cleanupIncomplete) {
            LOGGER.warn("Woodcut target {} left scaffold cleanup incomplete", target.base().toShortString());
        }
    }

    private boolean descendTowardTarget(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (isWithinReach(bot, target)) {
            return true;
        }
        List<BlockPos> descentCandidates = new ArrayList<>();
        BlockPos trunkStand = findColumnStand(world, target);
        if (trunkStand != null) {
            descentCandidates.add(trunkStand.toImmutable());
        }
        BlockPos nearbyStand = findDryStandableNear(world, target, 2, 6);
        if (nearbyStand != null) {
            descentCandidates.add(nearbyStand.toImmutable());
        }
        BlockPos baseStand = findDryStandableNear(world, target.down(), 3, 8);
        if (baseStand != null) {
            descentCandidates.add(baseStand.toImmutable());
        }
        descentCandidates = descentCandidates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator
                        .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - target.getY()))
                        .thenComparingDouble(pos -> pos.getSquaredDistance(target)))
                .toList();
        for (BlockPos candidate : descentCandidates) {
            if (candidate.getY() >= bot.getBlockY()) {
                continue;
            }
            if (isUnsafeWoodcutPerch(world, candidate)) {
                LOGGER.info("Woodcut dangerous drop blocked: trunk={} candidate={} reason=unsafe-perch",
                        target.toShortString(),
                        candidate.toShortString());
                continue;
            }
            LOGGER.info("Woodcut trunk descent: target={} from={} toward={}",
                    target.toShortString(),
                    bot.getBlockPos().toShortString(),
                    candidate.toShortString());
            if (moveToStand(source, bot, world, candidate, target) && !isUnsafeWoodcutPerch(world, bot.getBlockPos())) {
                return isWithinReach(bot, target)
                        || bot.getBlockPos().getY() <= Math.max(target.getY() + 1, candidate.getY());
            }
        }
        return false;
    }

    private boolean pillarUp(ServerPlayerEntity bot,
                             int steps,
                             WoodcutReachSession reachSession,
                             ServerCommandSource source,
                             Map<String, Object> sharedState) {
        if (steps <= 0) {
            return true;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!ensurePillarStock(bot, steps, source, bot.getBlockY() + steps, reachSession, sharedState)) {
            LOGGER.warn("No scaffold blocks available to pillar up {} steps", steps);
            return false;
        }
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        LOGGER.info("Woodcut pillar: starting {} steps from {} with {} scaffold blocks",
                steps, bot.getBlockPos().toShortString(), countPillarBlocks(bot));
        for (int i = 0; i < steps; i++) {
            if (isAbortRequested(bot)) {
                bot.setSneaking(wasSneaking);
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
            boolean placed = tryPlaceScaffold(bot, candidate, sharedState, reachSession);
            if (!placed) {
                LOGGER.warn("Failed to place scaffold block at {}", candidate.toShortString());
                bot.setSneaking(wasSneaking);
                return false;
            }
            if (reachSession != null) {
                reachSession.recordPlacement(candidate);
            }
            LOGGER.debug("Woodcut pillar: placed at {}", candidate.toShortString());
            sleepQuiet(PILLAR_STEP_DELAY_MS);
        }
        bot.setSneaking(wasSneaking);
        return true;
    }

    private WoodcutDetectionSnapshot scanDetectionSnapshot(ServerPlayerEntity bot,
                                                           int radius,
                                                           int vertical,
                                                           Set<BlockPos> visited,
                                                           Set<BlockPos> failedBases,
                                                           Map<BlockPos, String> failedBaseReasons,
                                                           RequestedWorkBounds requestedBounds) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new WoodcutDetectionSnapshot(Optional.empty(), Optional.empty(), null, null, null, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), false);
        }
        BlockPos origin = bot.getBlockPos();
        BotBeehiveRegistryService.discoverBeehivesNear(world, origin, radius, vertical);
        int totalLogs = 0;
        int visitedLogs = 0;
        int protectedCount = 0;
        int humanProx = 0;
        int soilFail = 0;
        int leafFail = 0;
        TreeDetector.TreeTarget nearestTree = null;
        double nearestTreeDist = Double.MAX_VALUE;
        TreeDetector.TreeTarget nearestLooseTree = null;
        double nearestLooseTreeDist = Double.MAX_VALUE;
        BlockPos bestFloating = null;
        double bestFloatingDist = Double.MAX_VALUE;
        BlockPos bestLoose = null;
        double bestLooseDist = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDist = Double.MAX_VALUE;
        Map<String, Integer> protectedReasons = new LinkedHashMap<>();
        List<String> rejectSamples = new ArrayList<>(3);
        int scanMinX = origin.getX() - radius;
        int scanMaxX = origin.getX() + radius;
        int scanMinY = origin.getY() - vertical;
        int scanMaxY = origin.getY() + vertical;
        int scanMinZ = origin.getZ() - radius;
        int scanMaxZ = origin.getZ() + radius;
        if (requestedBounds != null) {
            scanMinX = Math.max(scanMinX, requestedBounds.minX());
            scanMaxX = Math.min(scanMaxX, requestedBounds.maxX());
            scanMinY = Math.max(scanMinY, requestedBounds.minY());
            scanMaxY = Math.min(scanMaxY, requestedBounds.maxY());
            scanMinZ = Math.max(scanMinZ, requestedBounds.minZ());
            scanMaxZ = Math.min(scanMaxZ, requestedBounds.maxZ());
        }
        if (scanMinX > scanMaxX || scanMinY > scanMaxY || scanMinZ > scanMaxZ) {
            return new WoodcutDetectionSnapshot(Optional.empty(), Optional.empty(), null, null, null, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), false);
        }

        for (BlockPos pos : BlockPos.iterate(scanMinX, scanMinY, scanMinZ, scanMaxX, scanMaxY, scanMaxZ)) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                continue;
            }
            totalLogs++;

            double distSq = origin.getSquaredDistance(pos);
            boolean nearHuman = TreeDetector.isNearHumanBlocks(world, pos, 4);
            TreeDetector.WoodcutProtectionDecision protectionDecision =
                    TreeDetector.getWoodcutProtectionDecision(world, pos, 4);
            boolean protectedTarget = protectionDecision.blocked();
            boolean soilValid = TreeDetector.isValidSoil(world.getBlockState(pos.down()));
            boolean leavesNearby = TreeDetector.hasLeavesNearby(world, pos, 4, 4);

            if (nearHuman) {
                humanProx++;
            }
            if (protectedTarget) {
                protectedCount++;
                protectedReasons.merge(protectionDecision.reason(), 1, Integer::sum);
            }
            if (!soilValid) {
                soilFail++;
            }
            if (!leavesNearby) {
                leafFail++;
            }

            if (!nearHuman && !protectedTarget) {
                Optional<TreeDetector.TreeTarget> treeOpt = TreeDetector.detectTreeAtForWoodcut(
                        world, pos, TreeDetector.TreeDetectionMode.STRICT_STANDING);
                if (treeOpt.isPresent()) {
                    TreeDetector.TreeTarget tree = treeOpt.get();
                    if (visited != null && visited.contains(tree.base())) {
                        visitedLogs++;
                        continue;
                    }
                    if (failedBases != null && failedBases.contains(tree.base())) {
                        if (rejectSamples.size() < 3) {
                            rejectSamples.add(String.format("%s (failedBase=true, reason=%s)",
                                    tree.base().toShortString(),
                                    failedBaseReasons == null ? "previous-failure" : failedBaseReasons.getOrDefault(tree.base(), "previous-failure")));
                        }
                        continue;
                    }
                    // Hazard terrain check: reject trees fully enclosed by ravines/deep water
                    WoodcutHazardScanner.TreeHazardProfile hazard =
                            WoodcutHazardScanner.scan(world, tree.base());
                    if (hazard.fullyEnclosed()) {
                        failedBaseReasons.put(tree.base().toImmutable(), "hazardous terrain on all sides");
                        failedBases.add(tree.base().toImmutable());
                        continue;
                    }
                    double treeDistSq = origin.getSquaredDistance(tree.base());
                    if (treeDistSq < nearestTreeDist) {
                        nearestTreeDist = treeDistSq;
                        nearestTree = tree;
                    }
                    continue;
                }

                Optional<TreeDetector.TreeTarget> looseTreeOpt = TreeDetector.detectTreeAtForWoodcut(
                        world, pos, TreeDetector.TreeDetectionMode.LOOSE_FRAGMENT);
                if (looseTreeOpt.isPresent()) {
                    TreeDetector.TreeTarget looseTree = looseTreeOpt.get();
                    if ((visited == null || !visited.contains(looseTree.base()))
                            && (failedBases == null || !failedBases.contains(looseTree.base()))) {
                        double looseTreeDistSq = origin.getSquaredDistance(looseTree.base());
                        if (looseTreeDistSq < nearestLooseTreeDist) {
                            nearestLooseTreeDist = looseTreeDistSq;
                            nearestLooseTree = looseTree;
                        }
                    }
                }

                TreeDetector.CleanupLogDisposition cleanupDisposition =
                        TreeDetector.classifyCleanupLog(world, pos, false);
                if (cleanupDisposition.actionable() && distSq < bestFloatingDist) {
                    bestFloatingDist = distSq;
                    bestFloating = pos.toImmutable();
                }
                if (leavesNearby && distSq < bestLooseDist) {
                    bestLooseDist = distSq;
                    bestLoose = pos.toImmutable();
                }
                if (distSq < bestAnyDist) {
                    bestAnyDist = distSq;
                    bestAny = pos.toImmutable();
                }
            }

            if (rejectSamples.size() < 3) {
                boolean strictStandingReject = !nearHuman
                        && !protectedTarget
                        && TreeDetector.detectTreeAtForWoodcut(world, pos, TreeDetector.TreeDetectionMode.LOOSE_FRAGMENT).isPresent()
                        && TreeDetector.detectTreeAtForWoodcut(world, pos, TreeDetector.TreeDetectionMode.STRICT_STANDING).isEmpty();
                rejectSamples.add(String.format("%s (soilFail=%s, leafFail=%s, humanFail=%s, protected=%s, protection=%s%s)",
                        pos.toShortString(),
                        !soilValid,
                        !leavesNearby,
                        nearHuman,
                        protectedTarget,
                        protectionDecision.reason(),
                        strictStandingReject ? ", strictStandingReject=true" : ""));
            }
        }

        int unvisitedLogs = Math.max(0, totalLogs - visitedLogs);
        if (nearestTree == null && requestedBounds == null) {
            Optional<TreeDetector.TreeTarget> rememberedTarget = WoodcutKnowledgeService.findRememberedTarget(
                    bot, world, origin, radius, vertical, visited, failedBases);
            if (rememberedTarget.isPresent()) {
                nearestTree = rememberedTarget.get();
                nearestTreeDist = origin.getSquaredDistance(nearestTree.base());
                LOGGER.info("Woodcut detect: revived remembered target base={} top={} distanceSq={}",
                        nearestTree.base().toShortString(),
                        nearestTree.top().toShortString(),
                        nearestTreeDist);
            }
        }
        boolean allProtectedOrHuman = nearestTree == null
                && bestFloating == null
                && bestLoose == null
                && bestAny == null
                && unvisitedLogs > 0
                && protectedCount + humanProx >= Math.max(1, unvisitedLogs - 1);

        return new WoodcutDetectionSnapshot(
                Optional.ofNullable(nearestTree),
                Optional.ofNullable(nearestLooseTree),
                bestFloating,
                bestLoose,
                bestAny,
                totalLogs,
                visitedLogs,
                protectedCount,
                humanProx,
                soilFail,
                leafFail,
                Map.copyOf(protectedReasons),
                List.copyOf(rejectSamples),
                allProtectedOrHuman
        );
    }

    private void logDetectionSnapshot(WoodcutDetectionSnapshot snapshot, long durationMs) {
        BlockPos nearest = snapshot.nearestTree().map(TreeDetector.TreeTarget::base).orElse(null);
        LOGGER.info("Woodcut detect: durationMs={} logs={} visited={} humanProx={} protected={} nearest={}",
                durationMs,
                snapshot.totalLogs(),
                snapshot.visitedLogs(),
                snapshot.humanAdjacentLogs(),
                snapshot.protectedLogs(),
                nearest == null ? "none" : nearest.toShortString());
        for (int i = 0; i < snapshot.rejectSamples().size(); i++) {
            LOGGER.info("Woodcut detect reject sample {} at {}", i + 1, snapshot.rejectSamples().get(i));
        }
        LOGGER.info("Woodcut detect diagnostics: logs={} visited={} soilFail={} leafFail={} humanFail={} protectedFail={} protectReasons={} allProtectedOrHuman={}",
                snapshot.totalLogs(),
                snapshot.visitedLogs(),
                snapshot.soilFail(),
                snapshot.leafFail(),
                snapshot.humanAdjacentLogs(),
                snapshot.protectedLogs(),
                TreeDetector.summarizeProtectionReasons(snapshot.protectedReasonCounts()),
                snapshot.allLocalLogsProtectedOrHuman());
    }

    private void clearBlockingLeaves(ServerPlayerEntity bot,
                                     BlockPos target,
                                     BlockPos associatedTargetBase,
                                     WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (reachSession != null && reachSession.leafBlocksBroken >= MAX_LEAF_CLEAR_BLOCKS_PER_TARGET) {
            return;
        }
        selectLeafTool(bot);
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        Vec3d center = Vec3d.ofCenter(target);
        RaycastContext ctx = new RaycastContext(
                bot.getEyePos(),
                center,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (!hitPos.equals(target)) {
                candidates.add(hitPos.toImmutable());
                for (Direction direction : Direction.values()) {
                    candidates.add(hitPos.offset(direction).toImmutable());
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1) {
                        candidates.add(target.add(dx, dy, dz).toImmutable());
                    }
                }
            }
        }
        int clearedThisAttempt = 0;
        for (BlockPos leafPos : candidates) {
            if (reachSession != null && reachSession.leafBlocksBroken >= MAX_LEAF_CLEAR_BLOCKS_PER_TARGET) {
                break;
            }
            if (clearedThisAttempt >= MAX_LEAF_CLEAR_BLOCKS_PER_ATTEMPT) {
                break;
            }
            BlockState state = world.getBlockState(leafPos);
            if (!state.isIn(BlockTags.LEAVES)) {
                continue;
            }
            if (breakLeaf(bot, leafPos, associatedTargetBase, reachSession)) {
                clearedThisAttempt++;
            }
        }
        if (reachSession != null && clearedThisAttempt > 0) {
            reachSession.losClearAttempts++;
        }
    }

    private void breakLeaf(ServerPlayerEntity bot, BlockPos pos) {
        breakLeaf(bot, pos, null, null);
    }

    private boolean breakLeaf(ServerPlayerEntity bot,
                              BlockPos pos,
                              BlockPos associatedTargetBase,
                              WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (isAbortRequested(bot)) {
            return false;
        }
        TreeDetector.WoodcutProtectionDecision decision = getWoodcutMutationDecision(world, pos, associatedTargetBase);
        if (decision.blocked()) {
            LOGGER.info("Woodcut: refusing to mine {} (reason={})", pos.toShortString(), decision.reason());
            return false;
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        if (botPos.squaredDistanceTo(center) > REACH_DISTANCE_SQ) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isIn(BlockTags.LOGS)) {
            ensureAxeOrRetrieve(bot);
        } else {
            selectLeafTool(bot);
        }
        LookController.faceBlock(bot, pos);
        CompletableFuture<String> mining;
        try {
            mining = MiningTool.mineBlock(bot, pos, true, false);
        } catch (Exception e) {
            LOGGER.warn("Leaf break scheduling failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
        try {
            String result = mining.get(3_000, TimeUnit.MILLISECONDS);
            boolean success = result != null && result.toLowerCase().contains("complete");
            if (success && reachSession != null) {
                reachSession.leafBlocksBroken++;
            }
            return success || world.getBlockState(pos).isAir();
        } catch (Exception e) {
            mining.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private int effectiveSearchRadius(ServerPlayerEntity bot, int configuredRadius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return configuredRadius;
        }
        int adjusted = Math.max(6, configuredRadius);
        if (world.getServer() != null) {
            for (BotHomeService.BaseEntry base : BotHomeService.listBases(world.getServer(), world)) {
                if (base == null || base.pos() == null) {
                    continue;
                }
                double dist = Math.sqrt(bot.getBlockPos().getSquaredDistance(base.pos()));
                if (dist <= base.radius()) {
                    int exitDistance = (int) Math.ceil(base.radius() - dist);
                    adjusted = Math.max(adjusted, exitDistance + 8);
                }
            }
        }
        if (TreeDetector.isProtected(world, bot.getBlockPos(), 4)) {
            adjusted = Math.max(adjusted, configuredRadius + 12);
        }
        return adjusted;
    }

    /**
     * When all nearby trees are protected, search outward in a 360-degree ring for
     * the nearest unprotected tree and walk there.  If the ring search fails (village
     * protection extends beyond the search radius), blind-walk away from village
     * signals and let the next loop iteration search from the new position.
     */
    private boolean relocateToUnprotectedTree(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               int currentSearchRadius,
                                               Set<BlockPos> failedBases,
                                               Map<BlockPos, String> failedBaseReasons) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (isAbortRequested(bot)) {
            return false;
        }

        long relocationStartedAt = System.nanoTime();
        long immediateStageMs = 0L;
        long nearbyStageMs = 0L;
        long broadStageMs = 0L;
        BlockPos origin = bot.getBlockPos();
        long immediateStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, 4, "immediate-local", failedBaseReasons);
        BlockPos immediateTarget = TreeDetector.findNearestUnprotectedTreeNearby(
                world, origin, 4, RELOCATION_NEARBY_VERTICAL_RANGE, failedBases);
        immediateStageMs = (System.nanoTime() - immediateStageStartedAt) / 1_000_000L;
        if (immediateTarget != null) {
            double immediateDist = Math.sqrt(origin.getSquaredDistance(immediateTarget));
            LOGGER.info("Woodcut relocation: immediate local tree candidate at {} (dist={}) from {}",
                    immediateTarget.toShortString(), (int) immediateDist, origin.toShortString());
            if (origin.getSquaredDistance(immediateTarget) <= 9.0D || walkToTarget(source, bot, immediateTarget)) {
                logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "immediate-local");
                return true;
            }
        }

        if (MappedVillageService.isInsideMappedVillage(world, origin)
                && tryExitMappedVillage(source, bot, world, origin)) {
            logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "mapped-village-exit");
            return true;
        }

        origin = bot.getBlockPos();
        long nearbyStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, Math.max(RELOCATION_NEARBY_RADIUS, currentSearchRadius + 8), "nearby-sampled", failedBaseReasons);
        BlockPos nearbyTarget = TreeDetector.findNearestUnprotectedTreeBySampling(
                world, origin,
                Math.max(2, currentSearchRadius),
                Math.max(RELOCATION_NEARBY_RADIUS, currentSearchRadius + 8),
                RELOCATION_NEARBY_VERTICAL_RANGE,
                RELOCATION_NEARBY_TIME_BUDGET_MS,
                6,
                16,
                failedBases);
        nearbyStageMs = (System.nanoTime() - nearbyStageStartedAt) / 1_000_000L;
        if (nearbyTarget != null) {
            double dist = Math.sqrt(origin.getSquaredDistance(nearbyTarget));
            LOGGER.info("Woodcut relocation: nearby sampled tree at {} (dist={}) from {} after {}ms",
                    nearbyTarget.toShortString(), (int) dist, origin.toShortString(), nearbyStageMs);
            if (walkToTarget(source, bot, nearbyTarget)) {
                logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs, "nearby-sampled");
                return true;
            }
        }

        origin = bot.getBlockPos();
        long broadStageStartedAt = System.nanoTime();
        logFailedBaseSkips(origin, failedBases, RELOCATION_SEARCH_RADIUS, "broad-sampled", failedBaseReasons);
        BlockPos target = TreeDetector.findNearestUnprotectedTreeBySampling(
                world, origin,
                Math.max(currentSearchRadius, RELOCATION_NEARBY_RADIUS),
                RELOCATION_SEARCH_RADIUS,
                RELOCATION_VERTICAL_RANGE,
                RELOCATION_BROAD_TIME_BUDGET_MS,
                8,
                24,
                failedBases);
        broadStageMs = (System.nanoTime() - broadStageStartedAt) / 1_000_000L;

        if (target == null) {
            LOGGER.info("Woodcut relocation: no unprotected tree within {} blocks of {} after {}ms sampled search, trying blind walk",
                    RELOCATION_SEARCH_RADIUS, origin.toShortString(), broadStageMs);
            boolean blindWalkResult = blindWalkAwayFromProtection(source, bot, world, origin);
            logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs,
                    blindWalkResult ? "blind-walk-success" : "blind-walk-failed");
            return blindWalkResult;
        }

        double dist = Math.sqrt(origin.getSquaredDistance(target));
        LOGGER.info("Woodcut relocation: broad sampled tree at {} (dist={}) from {} after {}ms",
                target.toShortString(), (int) dist, origin.toShortString(), broadStageMs);
        boolean walked = walkToTarget(source, bot, target);
        logRelocationStageTimings(relocationStartedAt, immediateStageMs, nearbyStageMs, broadStageMs,
                walked ? "broad-sampled" : "broad-sampled-walk-failed");
        return walked;
    }

    private void logRelocationStageTimings(long relocationStartedAt,
                                           long immediateStageMs,
                                           long nearbyStageMs,
                                           long broadStageMs,
                                           String outcome) {
        long totalMs = (System.nanoTime() - relocationStartedAt) / 1_000_000L;
        LOGGER.info("Woodcut relocation: stage timings immediate={}ms nearby={}ms broad={}ms total={}ms outcome={}",
                immediateStageMs, nearbyStageMs, broadStageMs, totalMs, outcome);
        if (totalMs < RELOCATION_SLOW_SUMMARY_THRESHOLD_MS) {
            return;
        }
        String slowStage = "immediate";
        long slowStageMs = immediateStageMs;
        if (nearbyStageMs >= slowStageMs) {
            slowStage = "nearby-sampled";
            slowStageMs = nearbyStageMs;
        }
        if (broadStageMs >= slowStageMs) {
            slowStage = "broad-sampled";
            slowStageMs = broadStageMs;
        }
        LOGGER.warn("Woodcut relocation: slow startup total={}ms dominantStage={}({}ms) outcome={}",
                totalMs, slowStage, slowStageMs, outcome);
    }

    private boolean tryExitMappedVillage(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         BlockPos origin) {
        Optional<MappedVillageNavigationService.EscapePlan> planOpt =
                MappedVillageNavigationService.planExit(world, origin, MAPPED_VILLAGE_EXIT_CLEARANCE);
        if (planOpt.isEmpty()) {
            LOGGER.info("Woodcut relocation: mapped-village exit mode skipped at {} (reason=no-exit-geometry)",
                    origin.toShortString());
            return false;
        }

        MappedVillageNavigationService.EscapePlan plan = planOpt.get();
        LOGGER.info("Woodcut relocation: mapped-village exit mode village='{}' from {} edge={} target={} validCandidates={}/5 clearance={}",
                plan.villageName(),
                origin.toShortString(),
                plan.nearestEdgePoint().toShortString(),
                plan.exitTarget().toShortString(),
                plan.candidateTargets().size(),
                plan.clearance());

        if (plan.candidateTargets().isEmpty()) {
            LOGGER.info("Woodcut relocation: mapped-village exit village='{}' has no usable candidates (reason=exit-candidates-all-invalid)",
                    plan.villageName());
            return false;
        }

        for (BlockPos candidate : plan.candidateTargets()) {
            if (isAbortRequested(bot)) {
                return false;
            }
            if (!isDryStableWoodcutStand(world, candidate)) {
                LOGGER.info("Woodcut relocation: mapped-village exit candidate {} rejected (reason=no-dry-stand)",
                        candidate.toShortString());
                continue;
            }
            LOGGER.info("Woodcut relocation: trying mapped-village exit candidate {} for '{}'",
                    candidate.toShortString(), plan.villageName());
            if (!walkToTarget(source, bot, candidate)) {
                LOGGER.info("Woodcut relocation: mapped-village exit candidate {} failed (reason=path-unreachable)",
                        candidate.toShortString());
                continue;
            }

            BlockPos postWalk = bot.getBlockPos();
            boolean outside = MappedVillageNavigationService.isOutsideTarget(world, postWalk, plan.villageName());
            LOGGER.info("Woodcut relocation: mapped-village exit post-walk village='{}' nowAt={} outside={}",
                    plan.villageName(), postWalk.toShortString(), outside);
            if (outside) {
                LOGGER.info("Woodcut relocation: left mapped village '{}' and will resume normal tree search from {}",
                        plan.villageName(), postWalk.toShortString());
                return true;
            }

            LOGGER.info("Woodcut relocation: mapped-village exit candidate {} ended at {} but bot is still inside village '{}' (reason=movement-ended-still-inside)",
                    candidate.toShortString(), postWalk.toShortString(), plan.villageName());
        }

        LOGGER.info("Woodcut relocation: mapped-village exit village='{}' exhausted valid candidates (reason=exit-candidates-path-failed)",
                plan.villageName());
        return false;
    }

    /**
     * Walk away from village protection by sampling 8 compass directions and trying
     * each unprotected direction until a walk succeeds.
     */
    private boolean blindWalkAwayFromProtection(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 ServerWorld world,
                                                 BlockPos origin) {
        // 8 compass directions: N, NE, E, SE, S, SW, W, NW
        int[][] dirs = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1},
            {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
        };

        int triedCount = 0;
        for (int[] dir : dirs) {
            if (isAbortRequested(bot)) return false;
            int tx = origin.getX() + dir[0] * BLIND_WALK_DISTANCE;
            int tz = origin.getZ() + dir[1] * BLIND_WALK_DISTANCE;
            BlockPos probe = new BlockPos(tx, origin.getY(), tz);
            if (!world.isChunkLoaded(probe)) continue;
            int surfaceY = findSurfaceY(world, probe);
            if (surfaceY <= world.getBottomY()) continue;
            BlockPos surfaceProbe = new BlockPos(tx, surfaceY, tz);
            if (TreeDetector.isProtectedForWoodcut(world, surfaceProbe, 4)) continue;
            BlockPos dryProbe = findDryStandableNear(world, surfaceProbe, 3, 3);
            if (dryProbe == null) {
                LOGGER.info("Woodcut blind walk: rejecting direction ({},{}) at {} (reason=no-dry-stand)",
                        dir[0], dir[1], surfaceProbe.toShortString());
                continue;
            }

            triedCount++;
            LOGGER.info("Woodcut blind walk: trying direction ({},{}) toward {} (attempt {})",
                    dir[0], dir[1], dryProbe.toShortString(), triedCount);
            if (walkToTarget(source, bot, dryProbe)) {
                return true;
            }
            // Walk failed — try the next direction from current position
            origin = bot.getBlockPos();
        }

        if (triedCount == 0) {
            LOGGER.info("Woodcut blind walk: all {} directions still protected at {} blocks",
                    dirs.length, BLIND_WALK_DISTANCE);
        } else {
            LOGGER.info("Woodcut blind walk: tried {} directions, all walks failed", triedCount);
        }
        return false;
    }

    private static int findSurfaceY(ServerWorld world, BlockPos column) {
        return SafePositionService.getWalkableGroundY(world, column.getX(), column.getZ());
    }

    private boolean walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!isWithinRequestedBounds(target)) {
            return false;
        }
        MovementService.MovementPlan plan = MovementService.planLootApproach(
                        bot,
                        target,
                        WOODCUT_MOVEMENT_OPTIONS)
                .orElseGet(() -> new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        target,
                        target,
                        null,
                        null,
                        bot.getHorizontalFacing()
                ));
        BlockPos approach = plan.approachDestination() != null ? plan.approachDestination() : plan.finalDestination();
        if (!isWithinRequestedBounds(approach) || !isUsableWoodcutStand(world, approach)) {
            LOGGER.info("Woodcut relocation: rejected target {} via {} (reason=no-dry-stand)",
                    target.toShortString(), approach.toShortString());
            return false;
        }
        LOGGER.info("Woodcut relocation: movement plan mode={} final={} approach={}",
                plan.mode(),
                plan.finalDestination().toShortString(),
                plan.approachDestination() != null ? plan.approachDestination().toShortString() : "null");
        MovementService.MovementResult res = executeMovementPlan(source, bot, plan, "relocation");
        boolean success = res.success();
        if (success) {
            double postDistSq = bot.getBlockPos().getSquaredDistance(target);
            // Relocation doesn't need pinpoint accuracy — within 5 blocks is fine
            success = postDistSq <= 25.0;
        }
        if (!success) {
            // Even if movement reported failure, check if we got close enough
            double postDistSq = bot.getBlockPos().getSquaredDistance(target);
            if (postDistSq <= 25.0) {
                success = true;
            }
        }
        if (success) {
            LOGGER.info("Woodcut relocation: arrived near {} (now at {})",
                    target.toShortString(), bot.getBlockPos().toShortString());
        } else {
            LOGGER.info("Woodcut relocation: walk to {} failed: {}", target.toShortString(), res.detail());
        }
        return success;
    }

    private boolean tryPlaceScaffold(ServerPlayerEntity bot,
                                     BlockPos target,
                                     Map<String, Object> sharedState,
                                     WoodcutReachSession reachSession) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!isWithinRequestedBounds(target)) {
            LOGGER.info("Woodcut bounded: refusing scaffold placement outside bounds at {}", target.toShortString());
            return false;
        }
        if (countPillarBlocks(bot) == 0) {
            LOGGER.warn("No valid scaffold blocks available to place at {}", target.toShortString());
            return false;
        }
        BlockPos placePos = target;
        if (!isPlaceableTarget(world, placePos)) {
            // try to clear replaceable block
            breakSoftBlock(world, bot, placePos);
        }
        ensureSupportBlock(bot, placePos, sharedState, reachSession);
        if (BotActions.placeBlockAt(bot, placePos, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(sharedState, world, placePos);
            if (reachSession != null) {
                reachSession.recordPlacement(placePos);
            }
            return true;
        }
        // Try nearby offsets ONLY if on solid ground — from a scaffold pillar,
        // horizontal offsets place blocks into thin air or the tree canopy
        boolean onScaffold = reachSession != null && reachSession.hasPlacements()
                && reachSession.placedKeys.contains(bot.getBlockPos().down().asLong());
        if (!onScaffold) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos alt = placePos.offset(dir);
                // Skip directions with ravine or deep water hazard
                if (reachSession != null && reachSession.hazardProfile != null) {
                    WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(dir);
                    if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                            || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                        continue;
                    }
                }
                // Never place scaffold over water (even shallow)
                if (world.getFluidState(alt.down()).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                    continue;
                }
                if (!isWithinRequestedBounds(alt)) {
                    continue;
                }
                if (!isPlaceableTarget(world, alt)) {
                    breakSoftBlock(world, bot, alt);
                }
                ensureSupportBlock(bot, alt, sharedState, reachSession);
                if (BotActions.placeBlockAt(bot, alt, Direction.UP, PILLAR_BLOCKS)) {
                    recordScaffoldPlacement(sharedState, world, alt);
                    if (reachSession != null) {
                        reachSession.recordPlacement(alt);
                    }
                    LOGGER.debug("Woodcut pillar: placed via offset {} at {}", dir, alt.toShortString());
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureSupportBlock(ServerPlayerEntity bot,
                                    BlockPos target,
                                    Map<String, Object> sharedState,
                                    WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos below = target.down();
        if (!isWithinRequestedBounds(below)) {
            return;
        }
        BlockState belowState = world.getBlockState(below);
        if (!belowState.getCollisionShape(world, below).isEmpty()) {
            return;
        }
        if (!isPlaceableTarget(world, below)) {
            breakSoftBlock(world, bot, below);
        }
        if (BotActions.placeBlockAt(bot, below, Direction.UP, PILLAR_BLOCKS)) {
            recordScaffoldPlacement(sharedState, world, below);
            if (reachSession != null) {
                reachSession.recordPlacement(below);
            }
            LOGGER.debug("Woodcut pillar: placed support at {}", below.toShortString());
        }
    }

    private boolean isPlaceableTarget(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(net.minecraft.block.Blocks.SNOW);
    }

    private void breakSoftBlock(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (state.isIn(BlockTags.LEAVES) || state.isOf(net.minecraft.block.Blocks.SNOW) || state.isReplaceable()) {
            breakLeaf(bot, pos);
        }
    }

    private boolean emergencyStep(ServerPlayerEntity bot,
                                  WoodcutReachSession reachSession,
                                  Map<String, Object> sharedState) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos foot = bot.getBlockPos();
        BlockPos below = foot.down();
        breakSoftBlock(world, bot, foot);
        breakSoftBlock(world, bot, below);
        if (tryPlaceScaffold(bot, below, sharedState, reachSession)) {
            if (reachSession != null) {
                reachSession.recordPlacement(below);
            }
            LOGGER.info("Emergency scaffold placed at {}", below.toShortString());
            return true;
        }
        LOGGER.warn("Emergency scaffold placement failed at {}", below.toShortString());
        return false;
    }

    private boolean mineAdaptiveBlock(ServerPlayerEntity bot,
                                      BlockPos pos,
                                      BlockPos associatedTargetBase,
                                      WoodcutReachSession reachSession) {
        if (bot == null || pos == null || isAbortRequested(bot)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!isWithinReach(bot, pos)) {
            return false;
        }
        if (!hasLineOfSight(bot, Vec3d.ofCenter(pos))) {
            clearObstructionAlongRay(bot, Vec3d.ofCenter(pos), associatedTargetBase != null ? associatedTargetBase : pos);
            clearBlockingLeaves(bot, pos, associatedTargetBase != null ? associatedTargetBase : pos, reachSession);
            if (!hasLineOfSight(bot, Vec3d.ofCenter(pos))) {
                return false;
            }
        }
        if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isReplaceable()) {
            return breakLeaf(bot, pos, associatedTargetBase, reachSession);
        }

        LookController.faceBlock(bot, pos);
        selectAdaptiveToolOrHands(bot, state);
        CompletableFuture<String> miningFuture = MiningTool.mineBlock(bot, pos, true, false);
        try {
            String result = miningFuture.get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String lower = result == null ? "" : result.toLowerCase();
            if (lower.contains("complete")) {
                return true;
            }
            return world.getBlockState(pos).isAir();
        } catch (TimeoutException timeout) {
            miningFuture.cancel(true);
            LOGGER.warn("Adaptive mining timed out at {}", pos.toShortString());
            return false;
        } catch (Exception e) {
            miningFuture.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Adaptive mining failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
    }

    private boolean mineBlock(ServerPlayerEntity bot, BlockPos pos, boolean preferAxe) {
        return mineBlockDetailed(bot, pos, preferAxe, null, null).success();
    }

    private MineAttemptResult mineBlockDetailed(ServerPlayerEntity bot,
                                                BlockPos pos,
                                                boolean preferAxe,
                                                BlockPos associatedTargetBase,
                                                WoodcutReachSession reachSession) {
        if (isAbortRequested(bot)) {
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "no-world");
        }
        if (!isWithinRequestedBounds(pos)) {
            LOGGER.info("Woodcut bounded: refusing to mine {} outside requested bounds {}", pos.toShortString(), activeRequestedBounds);
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "requested-bounds");
        }
        TreeDetector.WoodcutProtectionDecision decision = getWoodcutMutationDecision(world, pos, associatedTargetBase);
        if (decision.blocked()) {
            LOGGER.info("Woodcut: refusing to mine {} (reason={})", pos.toShortString(), decision.reason());
            return new MineAttemptResult(false, WoodcutFailureReason.PROTECTED_AT_MINING, decision.reason());
        }
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double distSq = botPos.squaredDistanceTo(center);
        if (distSq > REACH_DISTANCE_SQ) {
            LOGGER.warn("Refusing to mine {} - out of reach (dist={})", pos.toShortString(), Math.sqrt(distSq));
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "out-of-reach");
        }
        if (!hasLineOfSight(bot, center)) {
            LOGGER.warn("LOS blocked for {} from eye {} (bot={})", pos.toShortString(), bot.getEyePos(), bot.getBlockPos().toShortString());
            if (reachSession != null && reachSession.hasPlacements()) {
                clearHeadroom(bot, associatedTargetBase != null ? associatedTargetBase : pos, reachSession);
            }
            clearObstructionAlongRay(bot, center, associatedTargetBase != null ? associatedTargetBase : pos);
            clearBlockingLeaves(bot, pos, associatedTargetBase != null ? associatedTargetBase : pos, reachSession);
            if (!hasLineOfSight(bot, center)) {
                return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "los-blocked");
            }
        }
        LookController.faceBlock(bot, pos);
        if (preferAxe) {
            ensureAxeOrRetrieve(bot);
        } else {
            selectAdaptiveToolOrHands(bot, world.getBlockState(pos));
        }
        if (reachSession != null && associatedTargetBase != null && world.getBlockState(pos).isIn(BlockTags.LOGS)) {
            reachSession.trunkMineAttemptsStarted++;
        }
        CompletableFuture<String> miningFuture = MiningTool.mineBlock(bot, pos, true, false);
        try {
            String result = miningFuture.get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String lower = result == null ? "" : result.toLowerCase();
            if (lower.contains("complete")) {
                return new MineAttemptResult(true, WoodcutFailureReason.NO_TARGET, "complete");
            }
            if (result != null && result.startsWith("⚠️")) {
                LOGGER.warn("Woodcut mining returned '{}' at {}", result, pos.toShortString());
                if (lower.contains("protected village block") || lower.contains("protected claim")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PROTECTED_AT_MINING, result);
                }
                if (lower.contains("no line of sight")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "los-blocked");
                }
                if (lower.contains("out of reach")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "out-of-reach");
                }
                if (lower.contains("aborted")) {
                    return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
                }
            }
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE,
                    result == null || result.isBlank() ? "mining-incomplete" : result);
        } catch (TimeoutException timeout) {
            LOGGER.warn("Mining {} timed out", pos.toShortString());
            miningFuture.cancel(true);
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "timeout");
        } catch (Exception e) {
            LOGGER.error("Failed to mine {}: {}", pos.toShortString(), e.getMessage());
            miningFuture.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new MineAttemptResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "exception");
        }
    }

    private TreeDetector.WoodcutProtectionDecision getWoodcutMutationDecision(ServerWorld world,
                                                                              BlockPos pos,
                                                                              BlockPos associatedTargetBase) {
        if (world == null || pos == null) {
            return new TreeDetector.WoodcutProtectionDecision(false, "none");
        }
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.LEAVES)) {
            return new TreeDetector.WoodcutProtectionDecision(false, "none");
        }
        BlockPos decisionPos = associatedTargetBase != null ? associatedTargetBase : pos;
        return TreeDetector.getWoodcutProtectionDecision(world, decisionPos, 4);
    }

    private void cleanupReachSession(ServerCommandSource source,
                                     ServerPlayerEntity bot,
                                     BlockPos base,
                                     WoodcutReachSession reachSession,
                                     Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (reachSession == null || !reachSession.hasPlacements()) {
            return;
        }
        prepareCleanupSurfacePosition(source, bot, world, base, reachSession, sharedState);
        boolean wasSneaking = bot.isSneaking();
        if (reachSession.hasVerifiedPillarSteps()) {
            bot.setSneaking(true);
            LOGGER.info("Woodcut pillar: descending, blocks placed={}", reachSession.placedBlocks.size());
        } else {
            bot.setSneaking(false);
            LOGGER.info("Woodcut entry cleanup: removing temporary supports, blocks placed={}", reachSession.placedBlocks.size());
        }
        for (BlockPos placed : reachSession.placementsDescending()) {
            if (isAbortRequested(bot) || TaskService.isServerStopping()) {
                bot.setSneaking(wasSneaking);
                return;
            }
            if (!isWithinRequestedBounds(placed)) {
                LOGGER.info("Woodcut bounded: skipping scaffold cleanup outside requested bounds at {}", placed.toShortString());
                reachSession.cleanupIncomplete = true;
                continue;
            }
            if (world.getBlockState(placed).isAir()) {
                forgetScaffoldPlacement(sharedState, world, placed);
                reachSession.recordRemoval(placed);
                continue;
            }
            if (!isWithinReach(bot, placed) && !moveNearScaffoldForCleanup(source, bot, world, placed, base, reachSession)) {
                // Normal cleanup failed — try bridge-to-safety recovery
                if (tryBridgeToSafetyForCleanup(source, bot, world, placed, sharedState, reachSession)) {
                    LOGGER.info("Woodcut scaffold cleanup: bridged to safety, cleaned {} from new position",
                            placed.toShortString());
                    if (world.getBlockState(placed).isAir()) {
                        forgetScaffoldPlacement(sharedState, world, placed);
                        reachSession.recordRemoval(placed);
                    }
                    continue;
                }
                // Bridge failed — abandon scaffold and retreat
                LOGGER.warn("Woodcut scaffold cleanup: abandoning unreachable {} from {}",
                        placed.toShortString(), bot.getBlockPos().toShortString());
                reachSession.cleanupIncomplete = true;

                // Try to escape to safe ground
                BlockPos escape = findEscapeStandNear(world, bot.getBlockPos(), 8);
                if (escape != null && isWithinRequestedBounds(escape)) {
                    MovementService.MovementPlan escapePlan = new MovementService.MovementPlan(
                            MovementService.Mode.DIRECT, escape, escape, null, null, bot.getHorizontalFacing());
                    executeMovementPlan(source, bot, escapePlan, "cleanup-escape");
                }
                continue;
            }
            LookController.faceBlock(bot, placed);
            if (mineBlock(bot, placed, false) || world.getBlockState(placed).isAir()) {
                forgetScaffoldPlacement(sharedState, world, placed);
                reachSession.recordRemoval(placed);
                // Wait for gravity to settle the bot onto the next block down.
                sleepQuiet(150L);
            } else {
                reachSession.cleanupIncomplete = true;
                sleepQuiet(80L);
            }
        }
        bot.setSneaking(wasSneaking);
        rememberGroundedWoodcutStandIfSafe(world, bot, reachSession, "cleanup-finished");
    }

    private boolean moveNearScaffoldForCleanup(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos placed,
                                               BlockPos base,
                                               WoodcutReachSession reachSession) {
        if (isWithinReach(bot, placed)) {
            return true;
        }
        if (!isWithinRequestedBounds(placed)) {
            return false;
        }
        BlockPos stand = reachSession == null ? null : reachSession.groundedWoodcutStand();
        if (stand == null || stand.getY() > bot.getBlockY()) {
            stand = findDryStandableNear(world, placed, 1, 3);
        }
        if ((stand == null || stand.getY() > bot.getBlockY()) && base != null) {
            stand = findDryStandableNear(world, base, 2, 3);
        }
        if (stand == null) {
            return false;
        }
        if (!isWithinRequestedBounds(stand)) {
            return false;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult result = executeMovementPlan(source, bot, plan, "cleanup-stand");
        return isWithinReach(bot, placed)
                || (result.success() && isWithinReach(bot, placed));
    }

    /**
     * When scaffold is unreachable during cleanup, try to bridge to safe ground.
     * Returns true if the bot reached safe ground (scaffold may or may not be cleaned).
     */
    private boolean tryBridgeToSafetyForCleanup(ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos placed,
                                                  Map<String, Object> sharedState,
                                                  WoodcutReachSession reachSession) {
        // Find nearest safe ground within 6 blocks
        BlockPos safeGround = findDryStandableNear(world, bot.getBlockPos(), 6, 4);
        if (safeGround == null) {
            safeGround = findEscapeStandNear(world, bot.getBlockPos(), 8);
        }
        if (safeGround == null) {
            return false;
        }
        if (!isWithinRequestedBounds(safeGround)) {
            LOGGER.info("Woodcut bounded: refusing cleanup bridge toward {} outside requested bounds {}", safeGround.toShortString(), activeRequestedBounds);
            return false;
        }

        if (countPillarBlocks(bot) < 2) {
            return false;
        }

        // Determine bridge direction
        double dx = safeGround.getX() - bot.getBlockPos().getX();
        double dz = safeGround.getZ() - bot.getBlockPos().getZ();
        Direction bridgeDir;
        if (Math.abs(dx) >= Math.abs(dz)) {
            bridgeDir = dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            bridgeDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        int bridgeLength = Math.min(6, (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(safeGround)));
        bridgeLength = maxBridgeLengthWithinBounds(bot.getBlockPos(), bridgeDir, bridgeLength);
        if (bridgeLength <= 0) {
            return false;
        }
        List<BlockPos> bridgeBlocks = new ArrayList<>();
        BlockPos current = bot.getBlockPos();
        for (int i = 0; i < bridgeLength; i++) {
            if (isAbortRequested(bot)) break;
            BlockPos bridgePos = current.offset(bridgeDir, i + 1).down();
            if (!isWithinRequestedBounds(bridgePos)) {
                break;
            }
            if (world.getBlockState(bridgePos).getCollisionShape(world, bridgePos).isEmpty()) {
                if (BotActions.placeBlockAt(bot, bridgePos, Direction.UP, PILLAR_BLOCKS)) {
                    bridgeBlocks.add(bridgePos);
                    recordScaffoldPlacement(sharedState, world, bridgePos);
                    if (reachSession != null) reachSession.recordPlacement(bridgePos);
                }
            }
            // Walk forward
            BlockPos nextPos = current.offset(bridgeDir, i + 1);
            if (!isWithinRequestedBounds(nextPos)) {
                break;
            }
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT, nextPos, nextPos, null, null, bridgeDir);
            executeMovementPlan(source, bot, plan, "cleanup-bridge");
            sleepQuiet(100L);

            if (isSafeWoodcutWorkStand(world, bot.getBlockPos())) {
                // Reached safe ground — try to clean target scaffold from here
                if (isWithinReach(bot, placed)) {
                    LookController.faceBlock(bot, placed);
                    mineBlock(bot, placed, false);
                }
                // Clean up bridge blocks
                for (BlockPos bp : bridgeBlocks) {
                    if (isWithinReach(bot, bp)) {
                        mineBlock(bot, bp, false);
                        forgetScaffoldPlacement(sharedState, world, bp);
                        if (reachSession != null) reachSession.recordRemoval(bp);
                    }
                }
                return true;
            }
        }

        // Bridge didn't reach safe ground — clean bridge and return false
        for (int i = bridgeBlocks.size() - 1; i >= 0; i--) {
            BlockPos bp = bridgeBlocks.get(i);
            if (isWithinReach(bot, bp)) {
                mineBlock(bot, bp, false);
                forgetScaffoldPlacement(sharedState, world, bp);
                if (reachSession != null) reachSession.recordRemoval(bp);
            }
        }
        return false;
    }

    private void prepareCleanupSurfacePosition(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               ServerWorld world,
                                               BlockPos base,
                                               WoodcutReachSession reachSession,
                                               Map<String, Object> sharedState) {
        if (source == null || bot == null || world == null || base == null) {
            return;
        }
        if (isWoodcutOperationallyGrounded(world, bot, reachSession)) {
            rememberGroundedWoodcutStandIfSafe(world, bot, reachSession, "cleanup-prep-grounded");
            return;
        }
        LOGGER.info("Woodcut scaffold cleanup: local recovery near {} from {} reason=woodcut-local-recovery",
                base.toShortString(), bot.getBlockPos().toShortString());
        if (tryRecoverWoodcutLocalStance(source, bot, world, base, base, reachSession, "cleanup")) {
            return;
        }
        LOGGER.info("Woodcut scaffold cleanup: rejected generic surface recovery near {} from {} reason=rejected-generic-surface-recovery",
                base.toShortString(),
                bot.getBlockPos().toShortString());
    }

    /**
     * Equips an axe for woodcutting. Tries in order: existing inventory axe →
     * crafting (with wooden fallback so the bootstrap path works even when the
     * commander has no axe-crafting history) → registered chest retrieval.
     *
     * @return {@code true} if an axe is now equipped, {@code false} otherwise.
     *         The caller MUST terminate the woodcut task on {@code false} —
     *         falling through to hands lets the bot select whatever's in
     *         hotbar slot 0 as a "last resort," which is often a pickaxe and
     *         leads to logs being mined with the wrong tool. (2026-05-06.)
     */
    private boolean prepareWoodcutTooling(ServerCommandSource source, ServerPlayerEntity bot) {
        if (selectAxe(bot)) {
            return true;
        }
        // Wooden axe is the bootstrap craft — always allow it, regardless of
        // craft-history gating. Commander/bot may have planks-and-sticks but no
        // recorded axe craft yet, and we'd rather the bot make a wooden axe than
        // give up on the task.
        boolean crafted = ToolProvisionService.ensureAxe(bot, source, source.getPlayer(), true);
        if (crafted && selectAxe(bot)) {
            return true;
        }
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            boolean retrieved = ToolProvisionService.retrieveToolFromChests(
                    bot, world, source,
                    ToolProvisionService.allowedAxeSnapshotFilter(),
                    ToolProvisionService.allowedWoodcutAxePredicate(),
                    ToolProvisionService.axeTierComparator(),
                    32);
            if (retrieved && selectAxe(bot)) {
                ChatUtils.sendSystemMessage(source, "Found an axe in a nearby chest.");
                return true;
            }
        }
        return false;
    }

    private boolean selectAxe(ServerPlayerEntity bot) {
        return ensureAxeEquipped(bot);
    }

    private boolean selectLeafTool(ServerPlayerEntity bot) {
        // Use empty hand or harmless item — never waste tool durability on leaves.
        return selectHandsOrHarmlessItem(bot);
    }

    private boolean hasToolKeyword(ServerPlayerEntity bot, String keyword) {
        if (bot == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        String needle = keyword.toLowerCase();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
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
        // Prefer an empty hotbar slot (true "fist" mining).
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return true;
            }
        }
        // All hotbar slots are occupied — pick a non-tool, non-weapon, non-block item
        // so the bot doesn't mine with cobblestone or arrows.
        for (int i = 0; i < 9; i++) {
            var stack = bot.getInventory().getStack(i);
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe")
                    || key.contains("shovel") || key.contains("hoe") || key.contains("arrow")
                    || key.contains("cobblestone") || key.contains("dirt") || key.contains("stone")
                    || key.contains("sand") || key.contains("gravel") || key.contains("netherrack")
                    || key.contains("deepslate") || key.contains("scaffold")
                    || key.contains("log") || key.contains("plank") || key.contains("wood")
                    || key.contains("beef") || key.contains("pork") || key.contains("chicken")
                    || key.contains("mutton") || key.contains("rabbit") || key.contains("cod")
                    || key.contains("salmon") || key.contains("potato") || key.contains("carrot")
                    || key.contains("apple") || key.contains("bread") || key.contains("stew")
                    || key.contains("berries") || key.contains("kelp") || key.contains("melon")
                    || key.contains("cookie") || key.contains("pie") || key.contains("bottle")
                    || key.contains("bucket")) {
                continue;
            }
            BotActions.selectHotbarSlot(bot, i);
            return true;
        }
        // Last resort: reset to slot 0 to avoid sticking on a previously selected tool.
        BotActions.selectHotbarSlot(bot, 0);
        return true;
    }

    private void selectScaffoldToolOrHands(ServerPlayerEntity bot) {
        if (hasToolKeyword(bot, "pickaxe")) {
            BotActions.selectBestTool(bot, "pickaxe", null);
            return;
        }
        if (hasToolKeyword(bot, "shovel")) {
            BotActions.selectBestTool(bot, "shovel", "axe");
            return;
        }
        selectHandsOrHarmlessItem(bot);
    }

    private void selectAdaptiveToolOrHands(ServerPlayerEntity bot, BlockState blockState) {
        if (blockState == null) {
            selectHandsOrHarmlessItem(bot);
            return;
        }
        if (blockState.isIn(BlockTags.LOGS) || blockState.isIn(BlockTags.AXE_MINEABLE)) {
            if (ensureAxeEquipped(bot)) {
                return;
            }
        }
        if (blockState.isIn(BlockTags.PICKAXE_MINEABLE)) {
            BotActions.selectHarvestToolOrHands(bot, "pickaxe");
            return;
        }
        if (blockState.isIn(BlockTags.SHOVEL_MINEABLE)) {
            BotActions.selectHarvestToolOrHands(bot, "shovel");
            return;
        }
        if (blockState.isIn(BlockTags.LEAVES) || blockState.isOf(Blocks.SNOW) || blockState.isReplaceable()) {
            selectHandsOrHarmlessItem(bot);
            return;
        }
        selectHandsOrHarmlessItem(bot);
    }

    private boolean ensurePillarStock(ServerPlayerEntity bot,
                                      int needed,
                                      ServerCommandSource source,
                                      int workingY,
                                      WoodcutReachSession reachSession,
                                      Map<String, Object> sharedState) {
        if (bot == null || source == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return false;
        }
        int available = countPillarBlocks(bot);
        if (available >= needed) {
            LOGGER.debug("Pillar stock ok: {} blocks available for {} needed", available, needed);
            return true;
        }
        int toGather = needed - available;
        LOGGER.info("Pillar stock shortfall: need {} additional blocks to reach target", toGather);
        CompanionOverheadHologramService.show(bot, "Collecting scaffolding material...", 8_000);
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (gatherNearbyPillarBlocks(bot, world, toGather) > 0 && countPillarBlocks(bot) >= needed) {
            runShortRecoveryDropSweep(bot, source, "local-scaffold-recovery");
            return true;
        }
        int current = countPillarBlocks(bot);
        if (current >= needed) {
            return true;
        }
        // Craft planks from logs if no scaffold blocks available
        int shortfall = needed - current;
        int planksNeeded = shortfall;
        int logsToConvert = (int) Math.ceil(planksNeeded / 4.0);
        int craftedPlanks = craftPlanksForScaffold(bot, world, logsToConvert);
        if (craftedPlanks > 0) {
            LOGGER.info("Pillar stock: crafted {} planks from logs for scaffold", craftedPlanks);
            current = countPillarBlocks(bot);
            if (current >= needed) {
                return true;
            }
        }
        int surfaceY = findSurfaceY(world, bot.getBlockPos());
        boolean skyVisible = world.isSkyVisible(bot.getBlockPos().up());
        if (WoodcutScaffoldRecoveryHeuristics.shouldRecoverSurface(bot.getBlockY(), workingY, surfaceY, skyVisible)) {
            LOGGER.warn("Still short on scaffold after local gather. Recovering surface position instead of dirt farming (need={}, have={}, workingY={}, surfaceY={}).",
                    needed, current, workingY, surfaceY);
            boolean recovered = recoverSurfacePosition(bot, world, source, surfaceY, reachSession, sharedState, "scaffold-recovery");
            if (!recovered) {
                LOGGER.warn("Woodcut scaffold recovery failed to regain a usable surface position; skipping elevated target.");
                runShortRecoveryDropSweep(bot, source, "failed-scaffold-recovery");
                return false;
            }
            current = countPillarBlocks(bot);
            if (current < needed) {
                gatherNearbyPillarBlocks(bot, world, needed - current);
            }
        }
        runShortRecoveryDropSweep(bot, source, "post-scaffold-recovery");
        int finalCount = countPillarBlocks(bot);
        LOGGER.info("Pillar stock after bounded recovery: {} (needed {})", finalCount, needed);
        return finalCount >= needed;
    }

    private int craftPlanksForScaffold(ServerPlayerEntity bot, ServerWorld world, int logsToConvert) {
        if (bot == null || logsToConvert <= 0) {
            return 0;
        }
        int crafted = 0;
        for (int i = 0; i < bot.getInventory().size() && logsToConvert > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isIn(ItemTags.LOGS)) {
                continue;
            }
            int use = Math.min(stack.getCount(), logsToConvert);
            Item plankItem = mapLogToPlank(stack.getItem());
            if (plankItem == null || plankItem == Items.AIR) {
                continue;
            }
            int produce = use * 4;
            stack.decrement(use);
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
            bot.getInventory().insertStack(new ItemStack(plankItem, produce));
            crafted += produce;
            logsToConvert -= use;
        }
        return crafted;
    }

    private static Item mapLogToPlank(Item logItem) {
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.ITEM.getId(logItem);
        String path = id.getPath();
        String base = path;
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                base = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        net.minecraft.util.Identifier plankId = net.minecraft.util.Identifier.of(id.getNamespace(), base + "_planks");
        Item mapped = net.minecraft.registry.Registries.ITEM.get(plankId);
        return (mapped == null || mapped == Items.AIR) ? Items.OAK_PLANKS : mapped;
    }

    private int gatherNearbyPillarBlocks(ServerPlayerEntity bot, ServerWorld world, int toGather) {
        if (bot == null || world == null || toGather <= 0) {
            return 0;
        }
        int gathered = 0;
        BlockPos origin = bot.getBlockPos();
        int radius = 3;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 1, radius))) {
            if (gathered >= toGather || TaskService.isServerStopping() || isAbortRequested(bot)) {
                break;
            }
            if (pos.equals(origin) || pos.equals(origin.down())) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            Item blockItem = state.getBlock().asItem();
            if (state.isOf(net.minecraft.block.Blocks.SNOW)) {
                BlockPos below = pos.down();
                BlockState belowState = world.getBlockState(below);
                if (PILLAR_BLOCKS.contains(belowState.getBlock().asItem())) {
                    mineBlock(bot, pos, false);
                    state = belowState;
                    pos = below;
                    blockItem = state.getBlock().asItem();
                }
            }
            if (!PILLAR_BLOCKS.contains(blockItem)) {
                continue;
            }
            LookController.faceBlock(bot, pos);
            selectScaffoldToolOrHands(bot);
            if (mineBlock(bot, pos, false)) {
                gathered++;
                LOGGER.debug("Woodcut scaffold collected at {}", pos.toShortString());
            }
        }
        return gathered;
    }

    private boolean recoverSurfacePosition(ServerPlayerEntity bot,
                                           ServerWorld world,
                                           ServerCommandSource source,
                                           int targetSurfaceY,
                                           WoodcutReachSession reachSession,
                                           Map<String, Object> sharedState,
                                           String label) {
        if (bot == null || world == null || source == null || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return false;
        }
        boolean utilityRecovery = label != null && label.contains("utility-placement");
        if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
            return true;
        }
        int available = countPillarBlocks(bot);
        if (available <= 0) {
            SafePositionService.SurfaceStagingCandidate staging =
                    SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 8, false);
            if (staging != null) {
                LOGGER.info("Woodcut {}: no scaffold stock, trying nearby staging {} score={} before ascent",
                        label,
                        staging.pos().toShortString(),
                        staging.score());
                if (moveTowardRecoveryStaging(source, bot, staging.pos(), label + "-staging")
                        && (world.isSkyVisible(bot.getBlockPos().up())
                        || bot.getBlockY() + 1 >= targetSurfaceY
                        || BotFleeService.isAtSurface(bot, world))) {
                    return true;
                }
            }
            gatherNearbyPillarBlocks(bot, world, 1);
            available = countPillarBlocks(bot);
            if (available <= 0) {
                LOGGER.info("Woodcut {}: no scaffold stock after staging/gather; skipping further pillar-style recovery at {}",
                        label,
                        bot.getBlockPos().toShortString());
                return world.isSkyVisible(bot.getBlockPos().up())
                        || bot.getBlockY() + 1 >= targetSurfaceY
                        || BotFleeService.isAtSurface(bot, world);
            }
        }
        int pillarSteps = WoodcutScaffoldRecoveryHeuristics.desiredPillarRecoverySteps(bot.getBlockY(), targetSurfaceY, available);
        if (pillarSteps > 0) {
            LOGGER.info("Woodcut {}: pillar-up {} step(s) from {} toward surfaceY={}",
                    label, pillarSteps, bot.getBlockPos().toShortString(), targetSurfaceY);
            List<BlockPos> placed = ScaffoldService.pillarUpWithPositions(bot, pillarSteps);
            recordRecoveryPlacements(world, placed, sharedState, reachSession);
            if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
                return true;
            }
            if (utilityRecovery && !placed.isEmpty()) {
                if (stabilizeUtilityPlacementAfterPillar(source, bot, world, targetSurfaceY, label)) {
                    return true;
                }
                LOGGER.info("Woodcut {}: pillar stabilized at {} but no controlled local route was found; skipping synchronous ascent",
                        label,
                        bot.getBlockPos().toShortString());
                return false;
            }
        }
        if (utilityRecovery && tryUtilityPlacementLocalRecovery(source, bot, world, label + "-pre-ascent")) {
            return true;
        }
        if (utilityRecovery && !shouldEscalateUtilityRecoveryToAscent(world, bot, targetSurfaceY)) {
            LOGGER.info("Woodcut {}: staying in local recovery mode at {}; not escalating to synchronous ascent",
                    label,
                    bot.getBlockPos().toShortString());
            return false;
        }
        LOGGER.info("Woodcut {}: switching to synchronous ascent recovery from {} toward surfaceY={}",
                label, bot.getBlockPos().toShortString(), targetSurfaceY);
        Map<String, Object> params = new HashMap<>();
        params.put("ascentToSurface", true);
        params.put("skipTorches", true);
        params.put("allowChestStore", false);
        params.put("maxNoProgressSteps", available > 0 ? 4 : 2);
        SkillExecutionResult result = new CollectDirtSkill().execute(new SkillContext(source, new HashMap<>(), params));
        if (!result.success()) {
            LOGGER.warn("Woodcut {} ascent failed: {}", label, result.message());
        }
        return world.isSkyVisible(bot.getBlockPos().up()) || bot.getBlockY() + 1 >= targetSurfaceY;
    }

    private boolean stabilizeUtilityPlacementAfterPillar(ServerCommandSource source,
                                                         ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         int targetSurfaceY,
                                                         String label) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        BotActions.stop(bot);
        sleepQuiet(180L);
        BotActions.stop(bot);
        sleepQuiet(120L);
        BlockPos support = bot.getBlockPos().down();
        boolean supported = !world.getBlockState(support).getCollisionShape(world, support).isEmpty();
        LOGGER.info("Woodcut {}: pillar stabilize pos={} onGround={} supported={} support={}",
                label,
                bot.getBlockPos().toShortString(),
                bot.isOnGround(),
                supported,
                describeSupportBlock(world, bot.getBlockPos()));
        if (!supported) {
            return false;
        }
        if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
            return true;
        }
        return tryUtilityPlacementLocalRecovery(source, bot, world, label + "-post-pillar");
    }

    private boolean shouldEscalateUtilityRecoveryToAscent(ServerWorld world,
                                                          ServerPlayerEntity bot,
                                                          int targetSurfaceY) {
        if (world == null || bot == null) {
            return false;
        }
        if (world.isSkyVisible(bot.getBlockPos().up()) && bot.getBlockY() + 1 >= targetSurfaceY) {
            return false;
        }
        return targetSurfaceY - bot.getBlockY() >= 2;
    }

    private boolean moveTowardRecoveryStaging(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              BlockPos target,
                                              String label) {
        if (source == null || bot == null || target == null) {
            return false;
        }
        if (!isWithinRequestedBounds(target)) {
            return false;
        }
        Optional<MovementService.MovementPlan> plan =
                MovementService.planLootApproach(bot, target, WOODCUT_MOVEMENT_OPTIONS);
        if (plan.isPresent()) {
            MovementService.MovementResult result = executeMovementPlan(source, bot, plan.get(), label);
            if (result.success() || bot.getBlockPos().getSquaredDistance(target) <= 4.0D) {
                return true;
            }
        }
        return MovementService.nudgeTowardUntilClose(bot, target, 2.0D, 3_000L, 0.20D, label);
    }

    private void recordRecoveryPlacements(ServerWorld world,
                                          List<BlockPos> placements,
                                          Map<String, Object> sharedState,
                                          WoodcutReachSession reachSession) {
        if (world == null || placements == null || placements.isEmpty()) {
            return;
        }
        for (BlockPos placed : placements) {
            if (placed == null) {
                continue;
            }
            if (!isWithinRequestedBounds(placed)) {
                LOGGER.info("Woodcut bounded: dropping out-of-bounds recovery placement {}", placed.toShortString());
                continue;
            }
            recordScaffoldPlacement(sharedState, world, placed);
            if (reachSession != null) {
                reachSession.recordPlacement(placed);
            }
        }
    }

    private void runShortRecoveryDropSweep(ServerPlayerEntity bot, ServerCommandSource source, String label) {
        if (bot == null || source == null || isInventoryFull(bot) || TaskService.isServerStopping() || isAbortRequested(bot)) {
            return;
        }
        if (isBoundedWorkMode()) {
            return;
        }
        try {
            DropSweeper.safeSweep(bot, source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), 6.0D, 4.0D);
        } catch (Exception sweepError) {
            LOGGER.warn("Woodcut {} drop sweep failed: {}", label, sweepError.getMessage());
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

    private boolean ensureAxeEquipped(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        ItemStack best = ItemStack.EMPTY;
        int bestSlot = -1;
        float bestSpeed = 0.0f;
        BlockState ref = net.minecraft.block.Blocks.OAK_LOG.getDefaultState();
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (!key.contains("axe") || key.contains("pickaxe")) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(ref);
            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                if (remaining < 8) {
                    speed -= 0.5f;
                }
            }
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

    private boolean ensureAxeOrRetrieve(ServerPlayerEntity bot) {
        if (ensureAxeEquipped(bot)) return true;
        if (isAbortRequested(bot)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        ServerCommandSource source = bot.getCommandSource();
        // Mid-task replenishment: if the last axe broke, try crafting a new one
        // with the wooden fallback enabled (matches prepareWoodcutTooling at task
        // start; without this we'd silently continue with whatever non-axe tool
        // is currently equipped, which has been observed to cause logs being
        // mined with pickaxes).
        boolean crafted = ToolProvisionService.ensureAxe(bot, source, source.getPlayer(), true);
        if (crafted && ensureAxeEquipped(bot)) {
            return true;
        }
        boolean retrieved = ToolProvisionService.retrieveToolFromChests(
                bot, world, source,
                ToolProvisionService.allowedAxeSnapshotFilter(),
                ToolProvisionService.allowedWoodcutAxePredicate(),
                ToolProvisionService.axeTierComparator(),
                32);
        if (retrieved && ensureAxeEquipped(bot)) {
            ChatUtils.sendSystemMessage(source, "Found an axe in a nearby chest.");
            return true;
        }
        return false;
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

    private boolean isTrunkWithinReach(ServerWorld world, BlockPos base, ServerPlayerEntity bot) {
        List<BlockPos> trunk = TreeDetector.collectTrunk(world, base);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        for (BlockPos log : trunk) {
            if (botPos.squaredDistanceTo(Vec3d.ofCenter(log)) <= REACH_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean moveUnderTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos stand = findColumnStand(world, target);
        if (stand == null) {
            stand = findDryStandableNear(world, target, 1, 3);
        }
        if (stand == null) {
            LOGGER.warn("Woodcut approach: no dry stand under/near {} (reason=no dry stand)", target.toShortString());
            return false;
        }
        if (!isWithinRequestedBounds(stand) || !isWithinRequestedBounds(target)) {
            return false;
        }
        breakSoftBlock(world, bot, stand);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult res = executeMovementPlan(source, bot, plan, "column-stand");
        if (!res.success()) {
            MovementService.clearRecentWalkAttempt(bot.getUuid());
        }
        return bot.getBlockPos().getSquaredDistance(stand) <= 4.0 || isWithinReach(bot, target);
    }

    private BlockPos findColumnStand(ServerWorld world, BlockPos target) {
        BlockPos cursor = target.down();
        List<BlockPos> candidates = new ArrayList<>();
        for (int i = 0; i < 6 && cursor.getY() > world.getBottomY(); i++) {
            BlockPos foot = cursor.toImmutable();
            if (isSafeWoodcutWorkStand(world, foot)) {
                candidates.add(foot);
            }
            cursor = cursor.down();
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingDouble(pos -> pos.getSquaredDistance(target)));
        return candidates.get(0);
    }

    private MineAttemptResult mineWithRetries(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              BlockPos target,
                                              WoodcutReachSession reachSession,
                                              boolean preferAxe,
                                              Map<String, Object> sharedState,
                                              BlockPos associatedTargetBase) {
        WoodcutFailureReason lastReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
        String lastDetail = "unknown";
        for (int attempt = 0; attempt < MAX_RETRY_MINING; attempt++) {
            LOGGER.info("Woodcut mining attempt {} for {}", attempt + 1, target.toShortString());
            if (horizontalDistance(bot.getBlockPos(), target) > 2.5) {
                moveUnderTarget(source, bot, target);
            }
            if (!prepareReach(bot, source, target, reachSession, sharedState)) {
                LOGGER.warn("Prepare reach failed for {} on attempt {}", target.toShortString(), attempt + 1);
                lastReason = WoodcutFailureReason.PATH_OR_REACH_FAILURE;
                lastDetail = "prepare-reach-failed";
                continue;
            }
            boolean wasSneak = bot.isSneaking();
            if (reachSession != null && reachSession.hasPlacements()) {
                bot.setSneaking(true);
            }
            MineAttemptResult mined = mineBlockDetailed(bot, target, preferAxe, associatedTargetBase, reachSession);
            if (reachSession != null && reachSession.hasPlacements()) {
                bot.setSneaking(wasSneak);
            }
            if (mined.success()) {
                return mined;
            }
            if (mined.failureReason() == WoodcutFailureReason.PROTECTED_AT_MINING) {
                return mined;
            }
            lastReason = mined.failureReason();
            lastDetail = mined.detail();
            if ("los-blocked".equals(mined.detail()) && attempt < MAX_TRUNK_LOS_RECOVERY_ATTEMPTS) {
                LOGGER.info("Woodcut mining: LOS recovery for {} after failed attempt {}",
                        target.toShortString(), attempt + 1);
                // If the target is above us and LOS is blocked by the trunk itself,
                // repositioning horizontally won't help — we need to pillar up.
                int heightDiff = target.getY() - bot.getBlockY();
                if (heightDiff >= 3 && (reachSession == null || !reachSession.hasPlacements())) {
                    int needed = heightDiff - 1;
                    LOGGER.info("Woodcut mining: LOS blocked by trunk, pillaring {} to reach {}",
                            needed, target.toShortString());
                    pillarUp(bot, needed, reachSession, source, sharedState);
                } else {
                    tryReposition(bot, source, associatedTargetBase != null ? associatedTargetBase : target, reachSession, sharedState);
                }
            }
        }
        LOGGER.warn("Failed to mine {} after {} attempts", target.toShortString(), MAX_RETRY_MINING);
        return new MineAttemptResult(false, lastReason, lastDetail);
    }

    private double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private BlockPos findDryStandableNear(ServerWorld world, BlockPos center, int radius, int ySpan) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -ySpan, -radius), center.add(radius, ySpan, radius))) {
            BlockPos foot = pos.toImmutable();
            if (isSafeWoodcutWorkStand(world, foot)) {
                candidates.add(foot);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return candidates.get(0);
    }

    /**
     * Find an escape position with relaxed safety criteria. Unlike findDryStandableNear,
     * this allows positions near dangerous drops IF at least 2 cardinal neighbors are
     * standable, and allows positions adjacent to shallow water. Returns the nearest
     * standable neighbor of any dangerous-drop candidate (not the drop position itself).
     */
    private BlockPos findEscapeStandNear(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -4, -radius), center.add(radius, 4, radius))) {
            BlockPos foot = pos.toImmutable();
            if (!world.isChunkLoaded(foot)) continue;
            if (!isUsableWoodcutStand(world, foot)) continue;

            // Normal safe stand — best option
            if (isSafeWoodcutWorkStand(world, foot)) {
                candidates.add(foot);
                continue;
            }

            // Relaxed: dangerous drop OK if 2+ cardinal neighbors are standable
            if (FollowMovementService.isDangerousDropCell(world, foot)) {
                int standableNeighbors = 0;
                BlockPos bestNeighbor = null;
                double bestNeighborDist = Double.MAX_VALUE;
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos neighbor = foot.offset(dir);
                    if (isUsableWoodcutStand(world, neighbor)
                            && !FollowMovementService.isDangerousDropCell(world, neighbor)) {
                        standableNeighbors++;
                        double dist = neighbor.getSquaredDistance(center);
                        if (dist < bestNeighborDist) {
                            bestNeighborDist = dist;
                            bestNeighbor = neighbor;
                        }
                    }
                }
                if (standableNeighbors >= 2 && bestNeighbor != null) {
                    // Return the safe neighbor, not the dangerous position
                    candidates.add(bestNeighbor);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return candidates.get(0);
    }

    private boolean isUsableWoodcutStand(ServerWorld world, BlockPos foot) {
        return isDryStableWoodcutStand(world, foot) || isMinorSlopeWoodcutStand(world, foot);
    }

    private boolean isSafeWoodcutWorkStand(ServerWorld world, BlockPos foot) {
        return isUsableWoodcutStand(world, foot) && !isUnsafeWoodcutPerch(world, foot);
    }

    private boolean isUnsafeWoodcutPerch(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null || !world.isChunkLoaded(foot) || !world.isChunkLoaded(foot.down())) {
            return true;
        }
        if (!isUsableWoodcutStand(world, foot)) {
            return true;
        }
        BlockState support = world.getBlockState(foot.down());
        boolean leafFloor = support.isIn(BlockTags.LEAVES);
        boolean dangerousDrop = FollowMovementService.isDangerousDropCell(world, foot);
        if (leafFloor && !isStableWorkingLeafSupport(support)) {
            return true;
        }
        SafePositionService.SurfaceCandidateAssessment assessment =
                SafePositionService.analyzeSurfaceCandidate(world, foot, foot);
        return WoodcutCanopyHeuristics.isUnsafeCanopyAssessment(
                assessment.cardinalStandableNeighbors(),
                assessment.totalStandableNeighbors(),
                assessment.steepDropNeighbors(),
                assessment.blockedCardinals(),
                leafFloor,
                dangerousDrop);
    }

    static boolean isStableWorkingLeafSupport(BlockState support) {
        if (support == null || !support.isIn(BlockTags.LEAVES)) {
            return true;
        }
        boolean persistent = support.contains(LeavesBlock.PERSISTENT)
                && Boolean.TRUE.equals(support.get(LeavesBlock.PERSISTENT));
        if (persistent) {
            return true;
        }
        if (support.contains(LeavesBlock.DISTANCE)) {
            Integer distance = support.get(LeavesBlock.DISTANCE);
            return distance == null || distance < 7;
        }
        return true;
    }

    private boolean isLeafFloorStand(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null || !world.isChunkLoaded(foot.down())) {
            return false;
        }
        return world.getBlockState(foot.down()).isIn(BlockTags.LEAVES);
    }

    private boolean isDryStableWoodcutStand(ServerWorld world, BlockPos foot) {
        if (!isDryWoodcutStandCell(world, foot)) {
            return false;
        }
        int stableExits = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = foot.offset(direction);
            if (isDryWoodcutStandCell(world, neighbor)) {
                stableExits++;
            }
        }
        return stableExits >= 1;
    }

    private boolean isMinorSlopeWoodcutStand(ServerWorld world, BlockPos foot) {
        if (!isDryWoodcutStandCell(world, foot)) {
            return false;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = foot.offset(direction);
            if (isDryWoodcutStandCell(world, neighbor)
                    || isDryWoodcutStandCell(world, neighbor.down())
                    || isDryWoodcutStandCell(world, neighbor.up())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDryWoodcutStandCell(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null || !world.isChunkLoaded(foot)) {
            return false;
        }
        BlockPos head = foot.up();
        BlockPos below = foot.down();
        if (!world.isChunkLoaded(head) || !world.isChunkLoaded(below)) {
            return false;
        }
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

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private void clearHeadroom(ServerPlayerEntity bot,
                               BlockPos associatedTargetBase,
                               WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos head = bot.getBlockPos().up();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = head.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)) {
                        breakLeaf(bot, pos, associatedTargetBase, reachSession);
                    }
                }
            }
        }
    }

    private void clearObstructionAlongRay(ServerPlayerEntity bot, Vec3d targetCenter, BlockPos associatedTargetBase) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        RaycastContext ctx = new RaycastContext(
                bot.getEyePos(),
                targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot);
        var hit = world.raycast(ctx);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockPos targetPos = BlockPos.ofFloored(targetCenter);
        if (hitPos.equals(targetPos)) {
            return;
        }
        BlockState state = world.getBlockState(hitPos);
        if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS)) {
            LOGGER.debug("Clearing LOS obstruction at {}", hitPos.toShortString());
            breakLeaf(bot, hitPos, associatedTargetBase, null);
        }
    }

    private void logFailedBaseSkips(BlockPos origin,
                                    Set<BlockPos> failedBases,
                                    int radius,
                                    String stage,
                                    Map<BlockPos, String> failedBaseReasons) {
        if (origin == null || failedBases == null || failedBases.isEmpty()) {
            return;
        }
        int radiusSq = radius * radius;
        for (BlockPos failedBase : failedBases) {
            if (failedBase == null || failedBase.getSquaredDistance(origin) > radiusSq) {
                continue;
            }
            LOGGER.info("Woodcut relocation: skipping failed base {} during {} search (reason={})",
                    failedBase.toShortString(),
                    stage,
                    failedBaseReasons == null ? "previous-failure" : failedBaseReasons.getOrDefault(failedBase, "previous-failure"));
        }
    }

    private void plantSaplings(ServerPlayerEntity bot,
                               ServerCommandSource source,
                               BlockPos base,
                               WoodcutReachSession reachSession) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (bot == null || source == null || base == null) {
            return;
        }
        if (reachSession != null) {
            reachSession.replantAttempted = true;
        }
        if (TaskService.isServerStopping() || isAbortRequested(bot)) {
            if (reachSession != null) {
                reachSession.replantStatus = "abort/server-stop";
            }
            LOGGER.info("Woodcut replant skip at {}: abort/server-stop", base.toShortString());
            return;
        }
        int toPlant = 0;
        for (Item sap : SAPLING_ITEMS) {
            toPlant += countItem(bot, sap);
        }
        if (toPlant <= 0) {
            if (reachSession != null) {
                reachSession.replantStatus = "no-saplings";
            }
            LOGGER.info("Woodcut replant skip at {}: no saplings", base.toShortString());
            return;
        }
        if (!ensureInSaplingPlantingRange(source, bot, world, base)) {
            if (reachSession != null) {
                reachSession.replantStatus = "out-of-range";
            }
            LOGGER.info("Woodcut replant skip at {}: could not return to planting range from {}",
                    base.toShortString(), bot.getBlockPos().toShortString());
            return;
        }
        int radius = 4;
        int planted = 0;
        int validSoil = 0;
        for (BlockPos soil : BlockPos.iterate(base.add(-radius, -1, -radius), base.add(radius, 1, radius))) {
            if (toPlant <= 0) {
                break;
            }
            BlockPos target = soil.up();
            if (!canPlantSapling(world, soil, target)) {
                continue;
            }
            validSoil++;
            List<Item> availableSaplings = availableSaplingItems(bot);
            if (availableSaplings.isEmpty()) {
                break;
            }
            if (BotActions.placeBlockAt(bot, target, Direction.UP, availableSaplings)) {
                toPlant--;
                planted++;
                LOGGER.info("Planted sapling at {}", target.toShortString());
            }
        }
        if (planted == 0) {
            if (reachSession != null) {
                reachSession.replantStatus = "no-placement-succeeded";
            }
            LOGGER.info("Woodcut replant skip at {}: no placement succeeded (validSoil={} bot={} remainingSaplings={})",
                    base.toShortString(), validSoil, bot.getBlockPos().toShortString(), toPlant);
        } else {
            if (reachSession != null) {
                reachSession.replantPlanted = planted;
                reachSession.replantStatus = "planted";
            }
            LOGGER.info("Woodcut replant summary at {}: planted={} validSoil={} remainingSaplings={}",
                    base.toShortString(), planted, validSoil, toPlant);
        }
    }

    private boolean ensureInSaplingPlantingRange(ServerCommandSource source,
                                                 ServerPlayerEntity bot,
                                                 ServerWorld world,
                                                 BlockPos base) {
        if (source == null || bot == null || world == null || base == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(base) <= 36.0D) {
            return true;
        }
        BlockPos stand = findDryStandableNear(world, base, 3, 3);
        if (stand == null) {
            stand = findDryStandableNear(world, base, 5, 4);
        }
        if (stand == null) {
            return false;
        }
        boolean moved = moveToStand(source, bot, world, stand, base);
        return moved || bot.getBlockPos().getSquaredDistance(base) <= 36.0D;
    }

    private void restoreTemporaryEntryTerrain(ServerPlayerEntity bot,
                                              ServerCommandSource source,
                                              ServerWorld world,
                                              BlockPos base,
                                              WoodcutReachSession reachSession) {
        if (bot == null || source == null || world == null || reachSession == null) {
            return;
        }
        Map<BlockPos, BlockState> repairs = reachSession.consumeTemporaryEntryTerrainRepairs();
        reachSession.terrainRestoreAttempted += repairs.size();
        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> entry : repairs.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState original = entry.getValue();
            if (pos == null || original == null) {
                continue;
            }
            if (bot.getBlockPos().equals(pos) || bot.getBlockPos().equals(pos.up())) {
                continue;
            }
            if (ScaffoldService.isTrackedScaffold(bot.getUuid(), pos)) {
                continue;
            }
            if (!isPlaceableTarget(world, pos)) {
                continue;
            }
            List<Item> preferred = preferredTerrainRepairBlocks(original);
            if (preferred.isEmpty()) {
                continue;
            }
            if (BotActions.placeBlockAt(bot, pos, Direction.UP, preferred)) {
                restored++;
                LOGGER.info("Woodcut terrain restore: filled {} near {} using {}",
                        pos.toShortString(),
                        base == null ? "unknown" : base.toShortString(),
                        preferred.get(0).getName().getString());
            }
        }
        reachSession.terrainRestoreCompleted += restored;
        LOGGER.info("Woodcut terrain restore summary: base={} attempted={} restored={}",
                base == null ? "unknown" : base.toShortString(),
                repairs.size(),
                restored);
    }

    private void logWoodcutMaintenanceSummary(BlockPos base,
                                              WoodcutReachSession reachSession,
                                              String stage) {
        if (base == null || reachSession == null) {
            return;
        }
        LOGGER.info("Woodcut maintenance summary: base={} stage={} rerouteFailures={} rerouteBlacklistSkips={} unsafeAnchorsRejected={} pillarEscalationsRejected={} terrainRestoreAttempted={} terrainRestoreRestored={} pendingTerrainRepairs={} replantAttempted={} replantPlanted={} replantStatus={}",
                base.toShortString(),
                stage,
                reachSession.rerouteFailures,
                reachSession.rerouteBlacklistSkips,
                reachSession.unsafeAnchorsRejected,
                reachSession.pillarEscalationsRejected,
                reachSession.terrainRestoreAttempted,
                reachSession.terrainRestoreCompleted,
                reachSession.pendingTemporaryEntryTerrainRepairs(),
                reachSession.replantAttempted,
                reachSession.replantPlanted,
                reachSession.replantStatus);
    }

    private List<Item> preferredTerrainRepairBlocks(BlockState original) {
        LinkedHashSet<Item> preferred = new LinkedHashSet<>();
        if (original != null) {
            if (original.isOf(Blocks.COARSE_DIRT)) {
                preferred.add(Items.COARSE_DIRT);
            } else if (original.isOf(Blocks.ROOTED_DIRT)) {
                preferred.add(Items.ROOTED_DIRT);
            } else if (original.isIn(BlockTags.DIRT) || original.isOf(Blocks.GRASS_BLOCK) || original.isOf(Blocks.PODZOL)) {
                preferred.add(Items.DIRT);
            } else if (original.isOf(Blocks.GRAVEL)) {
                preferred.add(Items.GRAVEL);
            } else if (original.isOf(Blocks.SAND)) {
                preferred.add(Items.SAND);
            } else if (original.isOf(Blocks.RED_SAND)) {
                preferred.add(Items.RED_SAND);
            } else if (original.isIn(BlockTags.LOGS)) {
                preferred.add(Items.DIRT);
            }
        }
        preferred.addAll(PILLAR_BLOCKS);
        return new ArrayList<>(preferred);
    }

    private boolean canPlantSapling(ServerWorld world, BlockPos soil, BlockPos target) {
        BlockState soilState = world.getBlockState(soil);
        BlockState targetState = world.getBlockState(target);
        if (!targetState.isAir()) {
            return false;
        }
        if (!soilState.isIn(BlockTags.DIRT) && !soilState.isOf(net.minecraft.block.Blocks.FARMLAND)) {
            return false;
        }
        int checkRadius = 3;
        for (BlockPos pos : BlockPos.iterate(target.add(-checkRadius, -1, -checkRadius), target.add(checkRadius, 1, checkRadius))) {
            if (world.getBlockState(pos).isIn(BlockTags.SAPLINGS)) {
                return false;
            }
        }
        return true;
    }

    private List<Item> availableSaplingItems(ServerPlayerEntity bot) {
        List<Item> found = new ArrayList<>();
        for (Item sap : SAPLING_ITEMS) {
            if (countItem(bot, sap) > 0) {
                found.add(sap);
            }
        }
        return found;
    }

    private int countItem(ServerPlayerEntity bot, Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countEmptySlots(ServerPlayerEntity bot) {
        int empty = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private boolean isInventoryFull(ServerPlayerEntity bot) {
        return bot != null && bot.getInventory().getEmptySlot() == -1;
    }

    private int countWood(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isWoodStack(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }


    private boolean isWoodStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isIn(ItemTags.LOGS_THAT_BURN) || stack.isIn(ItemTags.PLANKS)) {
            return true;
        }
        if (stack.isOf(Items.STICK)) {
            return true;
        }
        if (stack.getItem() instanceof BlockItem bi) {
            BlockState state = bi.getBlock().getDefaultState();
            return state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS);
        }
        return false;
    }

    private boolean isWoodcutOffloadCandidate(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isOf(Items.CHEST) || stack.isOf(Items.CRAFTING_TABLE)) {
            return false;
        }
        if (PILLAR_BLOCKS.contains(stack.getItem())) {
            return false;
        }
        if (isWoodStack(stack)) {
            return true;
        }
        if (SAPLING_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (SEED_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (RAW_FOOD_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (stack.isOf(Items.LEAF_LITTER)) {
            return true;
        }
        if (MOB_DROP_JUNK.contains(stack.getItem())) {
            return true;
        }
        if (MISC_OFFLOAD_JUNK.contains(stack.getItem())) {
            return true;
        }
        if (stack.isIn(ItemTags.FLOWERS)) {
            return true;
        }
        return stack.isOf(Items.APPLE);
    }

    private void cleanupNearbyScaffold(ServerPlayerEntity bot, BlockPos base, Map<String, Object> sharedState) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world) || base == null || sharedState == null) {
            return;
        }
        Set<Long> memory = getScaffoldMemory(sharedState, world, false);
        if (memory == null || memory.isEmpty()) {
            return;
        }
        int radius = 10;
        List<Long> snapshot = new ArrayList<>(memory);
        for (Long packed : snapshot) {
            if (packed == null) {
                continue;
            }
            BlockPos pos = BlockPos.fromLong(packed);
            if (!isWithinRequestedBounds(pos)) {
                continue;
            }
            if (Math.abs(pos.getX() - base.getX()) > radius
                    || Math.abs(pos.getY() - base.getY()) > 12
                    || Math.abs(pos.getZ() - base.getZ()) > radius) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                memory.remove(packed);
                continue;
            }
            if (!PILLAR_BLOCKS.contains(state.getBlock().asItem())) {
                memory.remove(packed);
                continue;
            }
            // Avoid touching actual logs/planks to prevent structure damage
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                continue;
            }
            if (mineBlock(bot, pos, false) || world.getBlockState(pos).isAir()) {
                memory.remove(packed);
            }
        }
    }

    private static int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private boolean isAbortRequested(ServerPlayerEntity bot) {
        return bot != null && (TaskService.isServerStopping() || SkillManager.shouldAbortSkill(bot) || !TaskService.hasActiveTask(bot.getUuid()));
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Minimal drop sweep hook to avoid pulling in full skill stack.
     */
    private static final class DropSweeper {
        static void safeSweep(ServerPlayerEntity bot, ServerCommandSource source, double radius, double verticalRange) {
            try {
                net.wcfcarolina13.GameAI.DropSweeper.sweep(
                        source,
                        radius,
                        verticalRange,
                        Math.max(16, (int) Math.ceil(radius * 3)),
                        14_000L
                );
            } catch (Exception e) {
                LOGGER.warn("Drop sweep after woodcut failed: {}", e.getMessage());
            }
        }
    }
}
