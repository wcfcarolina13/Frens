package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import net.wcfcarolina13.GameAI.services.construction.FortifyExecutionPolicyUtil;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService.SavedFortification;
import net.wcfcarolina13.GameAI.services.construction.FortificationVisualizerService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.*;
import net.wcfcarolina13.GameAI.services.construction.execution.ConstructionExecutionService;
import net.wcfcarolina13.GameAI.services.construction.execution.ConstructionRecoveryService;
import net.wcfcarolina13.GameAI.services.construction.execution.ConstructionTaskSpec;
import net.wcfcarolina13.GameAI.services.construction.execution.ExecutionPolicy;
import net.wcfcarolina13.GameAI.services.construction.execution.ExecutionReport;
import net.wcfcarolina13.GameAI.services.construction.execution.FailureReason;
import net.wcfcarolina13.GameAI.services.construction.execution.PlacementTarget;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Skill for autonomously building a defensive wall perimeter around a village.
 * Uses a convex hull of village structures for natural wall placement.
 *
 * Usage:
 *   /bot fortify                     — detect village, build new wall
 *   /bot fortify dry_run             — preview hull layout with particles
 *   /bot fortify resume <name>       — continue saved wall
 *   /bot fortify patch <name>        — scan & repair existing wall
 *   /bot fortify status <name>       — show completion stats + particles
 *   /bot fortify list                — list saved walls for this world
 *   /bot fortify name <old> <new>    — rename a saved wall
 *   /bot fortify merge <name>        — merge current village into existing wall
 */
public final class FortifyVillageSkill implements Skill, FortifySkillOps.FortifyNavOps {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-village");
    private static final double REACH_DISTANCE_SQ = 20.25D;
    private static final int BLOCK_PLACE_DELAY_MS = 50;
    private static final int MAX_SCAFFOLD_HEIGHT = 8;
    private static final long MAX_BUILD_TIME_MS = 30 * 60_000L; // 30 minute cap
    private static final long PHASE_B_TIME_BUDGET_MS = 30 * 60_000L;
    private static final long MOAT_PASS2_TIME_BUDGET_MS = 12 * 60_000L;
    private static final int MAX_PASSES_PER_EDGE = 6;
    private static final int PATCH_MAX_EDGE_PASSES = 3;
    private static final int PATCH_NO_PROGRESS_PASSES = 2;
    private static final int PATCH_PER_TARGET_FAIL_CAP = 3;
    private static final long PATCH_TIME_BUDGET_MS = 8 * 60_000L;
    private static final long TOWER_VERTEX_TIME_BUDGET_MS = 45_000L;
    private static final int TOWER_LOCAL_MAX_ATTEMPTS = 6;
    private static final int TOWER_LOCAL_NO_PROGRESS_LIMIT = 3;
    private static final double TOWER_COMPLETION_TARGET_RATIO = 0.95D;
    private static final int TOWER_VERTEX_DEDUP_DISTANCE_SQ = 4; // <= 2 blocks in XZ
    private static final long EDGE_SEGMENT_TIME_BUDGET_MS = 30_000L;
    private static final int EDGE_SEGMENT_NO_PROGRESS_STOP = 2;
    private static final int EDGE_SEGMENT_PASS_CAP = 2;
    private static final int MAX_REPOSITION_ATTEMPTS_PER_BATCH = 6;
    private static final int MAX_BREAK_THROUGHS_PER_WALK = 3;
    private static final int PERIMETER_VERTEX_SKIP = 3;
    private static final int PERIMETER_WALK_SEARCH_RADIUS = 2;
    private static final int MIN_APPROACH_OPEN_EXITS = 2;
    private static final long DIG_RESULT_POLL_MS = 50L;
    private static final long DIG_RESULT_TIMEOUT_MS = 1_200L;
    private static final long STARTUP_RECOVERY_BUDGET_MS = 8_000L;
    private static final int STARTUP_NO_PROGRESS_LIMIT = 3;
    private static final int PASS1_NO_PROGRESS_FAIL_THRESHOLD = 8;
    private static final int PASS1_ZERO_MOVEMENT_FAIL_THRESHOLD = 3;
    private static final int PASS1_MAX_ATTEMPTS = 2;
    private static final int PASS2_MAX_RECOVERY_ATTEMPTS = 12;
    private static final int TOWER_LOCAL_REACHABILITY_RADIUS = 16;
    private static final int TOWER_LOCAL_REACHABILITY_MAX_NODES = 1024;
    private static final int FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST = 6;
    private static final int FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST = 8;
    private static final int FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST = 20;
    private static final long FORTIFY_MEDIUM_REPLAN_BUDGET_MS = 2_500L;
    private static final int FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST = 10;
    private static final int FORTIFY_SCAFFOLD_LEDGER_RADIUS_XZ = 2;
    private static final int FORTIFY_SCAFFOLD_LEDGER_RADIUS_Y = 8;
    private static final int FORTIFY_MANDATORY_REPLACE_RETRIES = 2;
    private static final long FORTIFY_MANDATORY_REPLACE_RETRY_SLEEP_MS = 60L;
    private static final int FORTIFY_CLEANUP_ACTIVE_RECOVERY_ATTEMPTS = 2;
    private static final int FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST = 12;
    private static final int FORTIFY_PATCH_SKIP_LOG_SAMPLE_LIMIT = 10;

    private static final int FORTIFY_GATE_EXIT_SIDESTEP_NO_PROGRESS_LIMIT = 2;

    private static final int FORTIFY_TRAP_CARVE_DEPTH_LIMIT = 6;

    private static final List<String> CAVITY_DIALOGUES = List.of(
            "Noted a pocket in the wall—flagging it for you.",
            "Found a tiny cavity here; it’s safe but needs eyes on.",
            "Here’s the gap I marked—no mobs, just a small void.",
            "Spotting the ignored cavity; we can patch later together.",
            "This nook is harmless, but I’m calling it out for you."
    );

    /** Positions that are part of the current fortification layout — never mine during navigation. */
    private Set<BlockPos> fortificationProtectedPositions = Set.of();
    /** Cavities deemed safe/ignored for this fortify run (skip reattempts). */
    private final Set<BlockPos> ignoredCavityPositions = new HashSet<>();
    /** Ignored cavity log for user reporting. */
    private final List<String> ignoredCavityNotes = new ArrayList<>();

    /** Entombment recovery, surface-escape retry, and carve-column cooldown tracking. */
    private final FortifyEntombmentHelper entombmentHelper = new FortifyEntombmentHelper();

    /** Current fortification layout — set during buildWall/handlePatch, used for gate-routing. */
    private FortificationLayout currentLayout = null;
    /** Tracks consecutive gate routing failures to skip repeated attempts within a session. */
    private int gateRoutingFailures = 0;
    /** Ephemeral fortify nav scope for tower/gate recovery. */
    private FortifyNavRuntimeScope activeFortifyNavScope = null;
    private long fortifyMovementEpoch = 0L;
    private boolean fortifyReplanActive = false;
    /** Tower vertices that yielded zero progress in the current auto-patch session — skip on next pass. */
    private final Set<Long> zeroProgressTowerVertices = new HashSet<>();
    /** Deferred cleanup queue, throttle state, and task-state helpers. */
    private final FortifyCleanupHelper cleanupHelper = new FortifyCleanupHelper();
    /** Layout query helper: block satisfaction, material fallbacks, edge ordering. */
    private final FortifyLayoutHelper layoutHelper = new FortifyLayoutHelper(ignoredCavityPositions);
    /** Escape/precipice-defense helper: hole escape, footing patches, shaft clearing. */
    private final FortifyEscapeHelper escapeHelper = new FortifyEscapeHelper(this, entombmentHelper, () -> fortificationProtectedPositions);

    @Override
    public FortifyNavRuntimeScope getActiveFortifyNavScope() {
        return activeFortifyNavScope;
    }

    private FortifyNavRuntimeScope beginFortifyNavScope(String context,
                                                        TowerNavAttemptState towerState,
                                                        WallPoint towerVertex,
                                                        BlockPos target,
                                                        boolean towerPatchContext,
                                                        boolean gateContext) {
        FortifyNavRuntimeScope prior = activeFortifyNavScope;
        fortifyMovementEpoch++;
        FortifyNavMode mode = towerState != null ? towerState.navMode : FortifyNavMode.REROUTE_ONLY;
        activeFortifyNavScope = new FortifyNavRuntimeScope(
                context, towerState, towerVertex, target, towerPatchContext, gateContext, mode, fortifyMovementEpoch);
        entombmentHelper.updateScope(context, fortifyMovementEpoch);
        entombmentHelper.updateMovementEpoch(fortifyMovementEpoch);
        return prior;
    }

    private void endFortifyNavScope(ServerPlayerEntity bot, ServerWorld world, FortifyNavRuntimeScope prior) {
        FortifyNavRuntimeScope scope = activeFortifyNavScope;
        try {
            if (scope != null && scope.carveSession != null) {
                boolean deferGateFinalize = scope.gateContext && isInsideCurrentFortificationHull(bot);
                if (deferGateFinalize && !scope.carveSession.deferredRepairs.isEmpty()) {
                    cleanupHelper.queueCarveRepairs(scope, scope.carveSession, new ArrayList<>(scope.carveSession.deferredRepairs));
                    scope.carveSession.deferredRepairs.clear();
                    scope.carveSession.cleanupState = CleanupState.FAILED_QUEUED;
                    LOGGER.info("[FortifyNav] Gate carve finalize deferred ctx={} target={} reason=still-inside-hull queued={}",
                            scope.context,
                            scope.carveSession.target != null ? scope.carveSession.target.toShortString() : "n/a",
                            cleanupHelper.queue.size());
                } else {
                    attemptFinalizeCarveTransaction(bot, world, scope);
                }
            }
            if (scope != null) {
                boolean skipGateCleanupInsideHull = scope.gateContext && isInsideCurrentFortificationHull(bot);
                if (!skipGateCleanupInsideHull) {
                    processDeferredFortifyCleanupQueue(bot, world, scope.context);
                }
            }
            if (scope != null && scope.towerState != null && scope.navMode == FortifyNavMode.CARVE_CORRIDOR) {
                scope.towerState.resetToRerouteMode();
            }
        } finally {
            activeFortifyNavScope = prior;
            entombmentHelper.updateScope(prior != null ? prior.context : null, prior != null ? prior.epoch : 0L);
        }
    }

    private void runWithFortifyEdgeNavScope(ServerPlayerEntity bot, ServerWorld world,
                                            String context, BlockPos target, Runnable action) {
        if (action == null) {
            return;
        }
        if (bot == null || world == null || context == null || !context.startsWith("fortify-edge:")) {
            action.run();
            return;
        }
        FortifyNavRuntimeScope prior = beginFortifyNavScope(context, null, null, target, false, false);
        try {
            action.run();
        } finally {
            endFortifyNavScope(bot, world, prior);
        }
    }

    private void runWithFortifyTowerNavScope(ServerPlayerEntity bot, ServerWorld world,
                                             String context,
                                             TowerNavAttemptState towerState,
                                             WallPoint towerVertex,
                                             BlockPos target,
                                             Runnable action) {
        if (action == null) {
            return;
        }
        if (bot == null || world == null || context == null || !context.startsWith("fortify-tower:")) {
            action.run();
            return;
        }
        FortifyNavRuntimeScope prior = beginFortifyNavScope(context, towerState, towerVertex, target, true, false);
        try {
            action.run();
        } finally {
            endFortifyNavScope(bot, world, prior);
        }
    }

    private String fortifyContextPrefix(String navContext, FortifyNavRuntimeScope scope) {
        return FortifyEntombmentHelper.fortifyContextPrefix(navContext, scope != null ? scope.context : null);
    }

    private static long packXZ(int x, int z) {
        return FortifyExecutionPolicyUtil.packXZ(x, z);
    }

    @Override
    public String name() {
        return "fortify_village";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot player available.");
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        MinecraftServer server = world.getServer();

        // Parse arguments
        String args = getArgument(context);
        if (args != null && !args.isBlank()) {
            String lower = args.trim().toLowerCase();

            // /bot fortify list
            if (lower.equals("list")) {
                return handleList(source, server, world);
            }

            if (lower.equals("report_cavities") || lower.equals("report cavities")) {
                return handleReportCavities(source, bot, world);
            }

            // /bot fortify name <old> <new>
            if (lower.startsWith("name ")) {
                return handleRename(source, server, world, args.trim().substring(5).trim());
            }

            // /bot fortify resume [name]
            if (lower.equals("resume")) {
                String wallName = findNearestWallName(server, world, bot.getBlockPos());
                if (wallName == null) {
                    return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
                }
                ChatUtils.sendSystemMessage(source, "§7[Fortify] Auto-detected nearest wall: §f" + wallName);
                return handleResume(source, bot, world, server, wallName);
            }
            if (lower.startsWith("resume ")) {
                String wallName = args.trim().substring(7).trim();
                return handleResume(source, bot, world, server, wallName);
            }

            // /bot fortify patch [name]
            if (lower.equals("patch")) {
                String wallName = findNearestWallName(server, world, bot.getBlockPos());
                if (wallName == null) {
                    return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
                }
                ChatUtils.sendSystemMessage(source, "§7[Fortify] Auto-detected nearest wall: §f" + wallName);
                return handlePatch(source, bot, world, server, wallName);
            }
            if (lower.startsWith("patch ")) {
                String wallName = args.trim().substring(6).trim();
                return handlePatch(source, bot, world, server, wallName);
            }

            // /bot fortify status [name]
            if (lower.equals("status")) {
                String wallName = findNearestWallName(server, world, bot.getBlockPos());
                if (wallName == null) {
                    return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
                }
                ChatUtils.sendSystemMessage(source, "§7[Fortify] Auto-detected nearest wall: §f" + wallName);
                return handleStatus(source, bot, world, server, wallName);
            }
            if (lower.startsWith("status ")) {
                String wallName = args.trim().substring(7).trim();
                return handleStatus(source, bot, world, server, wallName);
            }

            // /bot fortify moat [name]
            if (lower.equals("moat")) {
                String wallName = findNearestWallName(server, world, bot.getBlockPos());
                if (wallName == null) {
                    return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
                }
                ChatUtils.sendSystemMessage(source, "§7[Fortify] Auto-detected nearest wall: §f" + wallName);
                return executeMoat(source, bot, world, server, wallName);
            }
            if (lower.startsWith("moat ")) {
                String wallName = args.trim().substring(5).trim();
                return executeMoat(source, bot, world, server, wallName);
            }

            // /bot fortify merge [name]
            if (lower.equals("merge")) {
                String wallName = findNearestWallName(server, world, bot.getBlockPos());
                if (wallName == null) {
                    return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
                }
                ChatUtils.sendSystemMessage(source, "§7[Fortify] Auto-detected nearest wall: §f" + wallName);
                return handleMerge(source, bot, world, server, wallName);
            }
            if (lower.startsWith("merge ")) {
                String wallName = args.trim().substring(6).trim();
                return handleMerge(source, bot, world, server, wallName);
            }
        }

        // Default: detect village and build new wall (or dry_run)
        return handleNewBuild(source, bot, world, server, args);
    }

    // ── Command handlers ────────────────────────────────────────

    /**
     * Public entry point for the moat skill. Loads a saved wall, regenerates its layout,
     * and executes the moat dig + placement phase independently of the wall build.
     */
    public SkillExecutionResult executeMoat(ServerCommandSource source, ServerPlayerEntity bot,
                                             ServerWorld world, MinecraftServer server, String wallName) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification saved = opt.get();

        // Regenerate layout from saved hull + surface profile
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        VillageFortificationLayoutService.SurfaceProfile savedProfile =
                VillageFortificationLayoutService.SurfaceProfile.fromSaved(saved.getSurfaceProfile());
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter(), savedProfile);

        if (layout.allBlocks().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        this.currentLayout = layout;
        entombmentHelper.updateLayout(layout);
        this.gateRoutingFailures = 0;
        try {

        // Populate protected positions (wall blocks should not be mined during moat digging)
        Set<BlockPos> layoutPositions = new HashSet<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            layoutPositions.add(b.worldPos());
        }
        this.fortificationProtectedPositions = Collections.unmodifiableSet(layoutPositions);
        this.ignoredCavityPositions.clear();
        this.ignoredCavityNotes.clear();

        int referenceSurfaceY = computeReferenceSurfaceY(bot, layout, world);
        SurfaceProfile surfaceProfile = createSurfaceProfile(layout, referenceSurfaceY);

        // Ensure bot starts on solid, open ground
        if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
            ensureOnSurface(bot, world, referenceSurfaceY);
        }

        // Collect every MOAT_DIG + EXTERIOR_CLEAR block from all edges
        List<ProceduralWallBlock> allDigBlocks = new ArrayList<>();
        Set<BlockPos> wallPositions = new HashSet<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            if (b.type() == WallBlockType.MOAT_DIG || b.type() == WallBlockType.EXTERIOR_CLEAR) {
                allDigBlocks.add(b);
            } else {
                wallPositions.add(b.worldPos());
            }
        }

        // Filter out already-air blocks, densify, filter again
        allDigBlocks.removeIf(b -> world.getBlockState(b.worldPos()).isAir());
        allDigBlocks = densifyMoatDigTargets(allDigBlocks, wallPositions);
        allDigBlocks.removeIf(b -> world.getBlockState(b.worldPos()).isAir());

        int totalDug = 0;
        if (!allDigBlocks.isEmpty()) {
            showOverhead(bot, "Digging moat (" + allDigBlocks.size() + " blocks)...");

            List<BlockPos> perimeterPath = buildPerimeterPath(layout, world, surfaceProfile);
            Set<BlockPos> startupTargets = collectExistingDigTargets(world, allDigBlocks);
            if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
                StartupRecoveryResult startupRecovery = runStartupRecovery(
                        source, bot, world, referenceSurfaceY, surfaceProfile,
                        perimeterPath, startupTargets, "moat-startup");
                if (startupRecovery.failedNoSafeTile()) {
                    ChatUtils.sendChatMessages(source, "§c[Fortify] Stuck at moat start; no safe recovery position.");
                    return SkillExecutionResult.success("Stuck at moat start.");
                }
            }

            MoatDigResult digResult = digAllMoatBlocks(
                    source, bot, world, allDigBlocks,
                    referenceSurfaceY, surfaceProfile, perimeterPath);
            totalDug = digResult.dugCount();

            LOGGER.info("[Fortify] Moat dig phase complete: {} blocks cleared", totalDug);
            if (digResult.abortedNoSafeTile()) {
                ChatUtils.sendChatMessages(source, "§c[Fortify] Stuck during moat phase.");
                return SkillExecutionResult.success("Stuck during moat phase. " + totalDug + " blocks cleared.");
            }
        } else {
            ChatUtils.sendChatMessages(source, "§a[Fortify] No moat blocks to dig — moat already complete.");
        }

        // Place moat structural blocks: MOAT_FLOOR, MOAT_INNER_FACE, MOAT_OVERHANG
        List<ProceduralWallBlock> moatPlaceBlocks = new ArrayList<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            if (b.type() == WallBlockType.MOAT_FLOOR
                    || b.type() == WallBlockType.MOAT_INNER_FACE
                    || b.type() == WallBlockType.MOAT_OVERHANG) {
                if (!isPlannedBlockSatisfied(b, world.getBlockState(b.worldPos()))) {
                    moatPlaceBlocks.add(b);
                }
            }
        }

        int totalPlaced = 0;
        if (!moatPlaceBlocks.isEmpty()) {
            showOverhead(bot, "Placing moat structures (" + moatPlaceBlocks.size() + " blocks)...");
            // Sort by placement priority then distance
            moatPlaceBlocks.sort(Comparator.comparingInt((ProceduralWallBlock b) -> placePriority(b.type()))
                    .thenComparingDouble(b -> bot.squaredDistanceTo(Vec3d.ofCenter(b.worldPos()))));
            totalPlaced = buildBlockList(source, bot, world, moatPlaceBlocks);
            LOGGER.info("[Fortify] Moat placement phase complete: {} blocks placed", totalPlaced);
        }

        // Mark moat complete in persistence
        FortificationPersistenceService.setMoatComplete(server, worldKey, wallName, true);

        String summary = String.format("Moat complete for '%s'. %d blocks dug, %d blocks placed.",
                wallName, totalDug, totalPlaced);
        ChatUtils.sendChatMessages(source, "§a[Fortify] " + summary);
        showOverhead(bot, "Moat done!");
        return SkillExecutionResult.success(summary);

        } finally {
            this.currentLayout = null;
            entombmentHelper.updateLayout(null);
        }
    }

    private SkillExecutionResult handleList(ServerCommandSource source, MinecraftServer server, ServerWorld world) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        List<SavedFortification> forts = FortificationPersistenceService.listForWorld(server, worldKey);

        if (forts.isEmpty()) {
            ChatUtils.sendChatMessages(source, "§e[Fortify] No saved walls in this world.");
            return SkillExecutionResult.success("No saved walls.");
        }

        ChatUtils.sendChatMessages(source, "§a[Fortify] Saved walls (" + forts.size() + "):");
        for (SavedFortification f : forts) {
            // Compute completion from actual/planned counts if available
            int totalPlanned = 0;
            int totalActual = 0;
            for (int v : f.getEdgePlannedCounts().values()) totalPlanned += v;
            for (int v : f.getEdgeActualCounts().values()) totalActual += v;
            String status;
            if (f.isComplete()) {
                status = "§a[COMPLETE]";
            } else if (totalPlanned > 0) {
                int pct = (int) ((double) totalActual / totalPlanned * 100);
                status = "§e[" + pct + "%, " + f.getCompletedEdges().size() + " edges done]";
            } else {
                status = "§e[" + f.getCompletedEdges().size() + " edges done]";
            }
            ChatUtils.sendSystemMessage(source, String.format("§7  %s %s — center (%d,%d,%d), %d blocks placed",
                    f.getName(), status,
                    f.getCenter().getX(), f.getCenter().getY(), f.getCenter().getZ(),
                    f.getTotalBlocksPlaced()));
        }
        return SkillExecutionResult.success("Listed " + forts.size() + " walls.");
    }

    /**
     * Find the nearest saved wall to the given position.
     * Returns the wall name, or null if no walls exist for this world.
     */
    private String findNearestWallName(MinecraftServer server, ServerWorld world, BlockPos botPos) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        List<SavedFortification> forts = FortificationPersistenceService.listForWorld(server, worldKey);
        if (forts.isEmpty()) {
            return null;
        }
        String nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (SavedFortification f : forts) {
            BlockPos center = f.getCenter();
            double dx = botPos.getX() - center.getX();
            double dz = botPos.getZ() - center.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = f.getName();
            }
        }
        return nearest;
    }

    private SkillExecutionResult handleRename(ServerCommandSource source, MinecraftServer server,
                                               ServerWorld world, String nameArgs) {
        String[] parts = nameArgs.split("\\s+", 2);
        if (parts.length < 2) {
            return SkillExecutionResult.failure("Usage: /bot fortify name <old_name> <new_name>");
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        boolean ok = FortificationPersistenceService.rename(server, worldKey, parts[0], parts[1]);
        if (ok) {
            ChatUtils.sendChatMessages(source, "§a[Fortify] Renamed '" + parts[0] + "' to '" + parts[1] + "'.");
            return SkillExecutionResult.success("Renamed wall.");
        } else {
            return SkillExecutionResult.failure("Could not rename: wall '" + parts[0] + "' not found or '" + parts[1] + "' already exists.");
        }
    }

    private SkillExecutionResult handleResume(ServerCommandSource source, ServerPlayerEntity bot,
                                               ServerWorld world, MinecraftServer server, String wallName) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification saved = opt.get();
        if (saved.isComplete()) {
            ChatUtils.sendChatMessages(source, "§a[Fortify] Wall '" + wallName + "' is marked complete. "
                    + "Use §f/bot fortify patch " + wallName + "§a to repair any damage.");
            return SkillExecutionResult.success("Wall already complete. Use patch to repair.");
        }

        showOverhead(bot, "Resuming wall '" + wallName + "'...");

        // Regenerate layout from saved hull vertices, using saved surface profile for stability
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        VillageFortificationLayoutService.SurfaceProfile savedProfile =
                VillageFortificationLayoutService.SurfaceProfile.fromSaved(saved.getSurfaceProfile());
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter(), savedProfile);

        if (layout.edges().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        return buildWall(source, bot, world, server, layout, wallName, worldKey,
                saved.getCompletedEdges(), saved.getLastEdgeIndex(), saved.getTotalBlocksPlaced());
    }

    private SkillExecutionResult handlePatch(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, MinecraftServer server, String wallNameRaw) {
        // Parse 'auto' flag: "/bot fortify patch mywall auto" → autoRepeat=true, wallName="mywall"
        boolean autoRepeat = false;
        String wallName = wallNameRaw;
        String[] tokens = wallNameRaw.trim().split("\\s+");
        if (tokens.length >= 2 && tokens[tokens.length - 1].equalsIgnoreCase("auto")) {
            autoRepeat = true;
            wallName = wallNameRaw.trim().substring(0, wallNameRaw.trim().lastIndexOf(' ')).trim();
        }

        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification saved = opt.get();

        // Regenerate layout once using saved surface profile for stability
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        VillageFortificationLayoutService.SurfaceProfile savedProfile =
                VillageFortificationLayoutService.SurfaceProfile.fromSaved(saved.getSurfaceProfile());
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter(), savedProfile);

        if (layout.allBlocks().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        this.currentLayout = layout;
        entombmentHelper.updateLayout(layout);
        this.gateRoutingFailures = 0;
        try {

        // Populate protected positions so navigation break-through logic in patch mode
        // can identify fortification blocks (mirrors buildWall setup).
        Set<BlockPos> layoutPositions = new HashSet<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            layoutPositions.add(b.worldPos());
        }
        this.fortificationProtectedPositions = Collections.unmodifiableSet(layoutPositions);
        this.ignoredCavityPositions.clear();
        this.ignoredCavityNotes.clear();
        int grandTotalRepaired = 0;
        int passNumber = 0;
        boolean announcedPatchStart = false;

        do {
            passNumber++;
            if (SkillManager.shouldAbortSkill(bot)) break;

            if (autoRepeat && passNumber == 1) {
                showOverhead(bot, "Checking the blueprints...");
            }

            showOverhead(bot, autoRepeat
                    ? "Pass " + passNumber + ": Scanning for damage..."
                    : "Scanning for damage...");

            // Find missing/damaged blocks grouped by edge
            Map<Integer, Integer> totalByEdge = new LinkedHashMap<>();
            for (ProceduralWallBlock block : layout.allBlocks()) {
                if (!isActiveFortifyBlock(block)) {
                    continue;
                }
                totalByEdge.merge(block.edgeIndex(), 1, Integer::sum);
            }

            List<ProceduralWallBlock> repairList = new ArrayList<>();
            Map<Integer, List<ProceduralWallBlock>> repairByEdge = new LinkedHashMap<>();
            int interiorTowerSkipped = 0;
            int safeCavitiesIgnoredThisPass = 0;
            List<String> interiorTowerSkipSamples = new ArrayList<>();
            List<String> safeCavitySamples = new ArrayList<>();
            for (ProceduralWallBlock block : layout.allBlocks()) {
                if (!isActiveFortifyBlock(block)) {
                    continue;
                }
                BlockPos pos = block.worldPos();
                if ((block.type() == WallBlockType.TOWER_BASE
                        || block.type() == WallBlockType.TOWER_WALL
                        || block.type() == WallBlockType.TOWER_CAP)
                        && !isLayoutExteriorReachable(world, pos)) {
                    ignoredCavityPositions.add(pos.toImmutable());
                    ignoredCavityNotes.add(pos.toShortString() + " interior tower cell");
                    interiorTowerSkipped++;
                    if (interiorTowerSkipSamples.size() < FORTIFY_PATCH_SKIP_LOG_SAMPLE_LIMIT) {
                        interiorTowerSkipSamples.add(pos.toShortString());
                        LOGGER.info("[FortifyPatch] Skipping interior tower cell at {}", pos.toShortString());
                    }
                    continue;
                }
                if (ignoredCavityPositions.contains(pos)) {
                    continue;
                }
                BlockState current = world.getBlockState(block.worldPos());
                if (current.isAir() || current.isReplaceable()) {
                    CavityCheckResult cavity = evaluateCavity(world, pos);
                    if (cavity.safe()) {
                        ignoredCavityPositions.add(pos.toImmutable());
                        String note = String.format("%s air=%d spawnable=%s", pos.toShortString(), cavity.airCount(), cavity.spawnableCell());
                        ignoredCavityNotes.add(note);
                        safeCavitiesIgnoredThisPass++;
                        if (safeCavitySamples.size() < FORTIFY_PATCH_SKIP_LOG_SAMPLE_LIMIT) {
                            safeCavitySamples.add(note);
                            LOGGER.info("[FortifyPatch] Ignoring safe cavity at {} air={} spawnable={}",
                                    pos.toShortString(), cavity.airCount(), cavity.spawnableCell());
                        }
                        continue;
                    }
                    repairList.add(block);
                    repairByEdge.computeIfAbsent(block.edgeIndex(), k -> new ArrayList<>()).add(block);
                }
            }

            if (interiorTowerSkipped > 0) {
                LOGGER.info("[FortifyPatch] interiorTowerSkipped={} samples={}",
                        interiorTowerSkipped,
                        interiorTowerSkipSamples.isEmpty() ? "none" : interiorTowerSkipSamples);
            }
            if (safeCavitiesIgnoredThisPass > 0) {
                LOGGER.info("[FortifyPatch] safeCavitiesIgnored={} samples={}",
                        safeCavitiesIgnoredThisPass,
                        safeCavitySamples.isEmpty() ? "none" : safeCavitySamples);
            }

            if (repairList.isEmpty()) {
                String cavityNote = ignoredCavityPositions.isEmpty() ? "" : " (" + ignoredCavityPositions.size() + " cavities ignored)";
                String msg = autoRepeat && passNumber > 1
                        ? "§a[Fortify] Wall '" + wallName + "' is intact after " + (passNumber - 1) + " passes! (" + grandTotalRepaired + " total blocks repaired)" + cavityNote
                        : "§a[Fortify] Wall '" + wallName + "' is intact! No repairs needed." + cavityNote;
                ChatUtils.sendChatMessages(source, msg);
                ScaffoldService.teardownTrackedScaffolds(bot);
                return SkillExecutionResult.success("Wall intact. " + grandTotalRepaired + " total blocks repaired.");
            }

            if (autoRepeat && !announcedPatchStart) {
                showOverhead(bot, "Found damage. Patching...");
                announcedPatchStart = true;
            }

            // Per-edge completion stats
            showOverhead(bot, "Found " + repairList.size() + " blocks to repair");
            Map<Integer, Integer> prevActual = saved.getEdgeActualCounts();
            for (Map.Entry<Integer, Integer> edgeEntry : totalByEdge.entrySet()) {
                int edgeIdx = edgeEntry.getKey();
                int planned = edgeEntry.getValue();
                List<ProceduralWallBlock> edgeMissing = repairByEdge.getOrDefault(edgeIdx, List.of());
                int present = planned - edgeMissing.size();
                int pct = planned > 0 ? (present * 100) / planned : 100;

                String diagnosis = "";
                int prevPlaced = prevActual.getOrDefault(edgeIdx, 0);
                if (!edgeMissing.isEmpty()) {
                    if (prevPlaced > present) {
                        diagnosis = " §c(damaged)";
                    } else if (prevPlaced < planned / 2) {
                        diagnosis = " §7(never fully built)";
                    }
                }

                String color = pct >= 95 ? "§a" : pct >= 50 ? "§e" : "§c";
                String label = edgeIdx == -1 ? "Towers" : "Edge " + (edgeIdx + 1);
                ChatUtils.sendSystemMessage(source, String.format("§7  %s: %s%d/%d (%d%%)§7 — %d to repair%s",
                        label, color, present, planned, pct, edgeMissing.size(), diagnosis));
            }

            // Material check
            int buildBlocks = countBuildingBlocks(bot);
            if (buildBlocks == 0) {
                if (grandTotalRepaired > 0) {
                    showOverhead(bot, "Out of blocks. " + grandTotalRepaired + " repaired.");
                }
                return SkillExecutionResult.failure("No building blocks in inventory for repairs.");
            }

            // Sort repair blocks within each edge: FOUNDATION first, then ascending Y
            for (List<ProceduralWallBlock> blocks : repairByEdge.values()) {
                blocks.sort(Comparator
                        .comparingInt((ProceduralWallBlock b) -> b.type() == WallBlockType.FOUNDATION ? 0 : 1)
                        .thenComparingInt(b -> b.worldPos().getY()));
            }

            int referenceSurfaceY = computeReferenceSurfaceY(bot, layout, world);
            SurfaceProfile surfaceProfile = createSurfaceProfile(layout, referenceSurfaceY);

            if (!SkillManager.shouldAbortSkill(bot)) {
                runPatchStartPreflightRecovery(source, bot, world, referenceSurfaceY, surfaceProfile);
            }

            // Clean up stray scaffold pillars before repairing
            if (!SkillManager.shouldAbortSkill(bot)) {
                int scaffoldsCleared = scanAndRemoveStrayScaffolds(bot, world, layout, surfaceProfile);
                if (scaffoldsCleared > 0) {
                    ChatUtils.sendSystemMessage(source, "§7  Cleaned up " + scaffoldsCleared + " stray scaffold blocks.");
                }
            }

            // Greedy nearest-neighbor: pick the closest remaining edge/tower each iteration
            // using the bot's current position (which changes as it moves around the perimeter).
            List<Map.Entry<Integer, List<ProceduralWallBlock>>> remainingEdges = new ArrayList<>(repairByEdge.entrySet());

            int passRepaired = 0;
            int edgesPatched = 0;

            while (!remainingEdges.isEmpty()) {
                // Re-pick nearest to bot's CURRENT position each iteration
                BlockPos botPos = bot.getBlockPos();
                int nearestIdx = 0;
                double nearestDistSq = Double.MAX_VALUE;
                for (int ri = 0; ri < remainingEdges.size(); ri++) {
                    Map.Entry<Integer, List<ProceduralWallBlock>> candidate = remainingEdges.get(ri);
                    double distSq = FortifyLayoutHelper.patchEdgeDistanceSq(botPos, candidate.getKey(), candidate.getValue(), layout);
                    if (distSq < nearestDistSq) {
                        nearestDistSq = distSq;
                        nearestIdx = ri;
                    }
                }
                Map.Entry<Integer, List<ProceduralWallBlock>> entry = remainingEdges.remove(nearestIdx);
                if (SkillManager.shouldAbortSkill(bot)) break;
                if (countBuildingBlocks(bot) == 0) {
                    showOverhead(bot, "Out of blocks. " + passRepaired + " repaired this pass.");
                    break;
                }

                int edgeIdx = entry.getKey();

                if (edgeIdx == -1) {
                    int towerRepaired = patchTowerBlocks(
                            source,
                            bot,
                            world,
                            entry.getValue(),
                            layout.hullVertices(),
                            referenceSurfaceY,
                            surfaceProfile
                    );
                    passRepaired += towerRepaired;
                    if (towerRepaired > 0) edgesPatched++;
                } else if (edgeIdx >= 0 && edgeIdx < layout.edges().size()) {
                    WallEdge edge = layout.edges().get(edgeIdx);

                    // Focus on this edge: retry until 100% complete or stuck.
                    // Rescan the edge each attempt so we see freshly placed blocks.
                    int edgeRepaired = 0;
                    final ExecutionPolicy patchPolicy = new ExecutionPolicy(
                            PATCH_MAX_EDGE_PASSES,
                            PATCH_PER_TARGET_FAIL_CAP,
                            PATCH_NO_PROGRESS_PASSES,
                            PATCH_TIME_BUDGET_MS
                    );
                    for (int attempt = 1; attempt <= PATCH_MAX_EDGE_PASSES; attempt++) {
                        if (SkillManager.shouldAbortSkill(bot)) break;
                        if (countBuildingBlocks(bot) == 0) break;

                        // Rescan this specific edge for missing blocks
                        List<ProceduralWallBlock> edgeMissing = new ArrayList<>();
                        for (ProceduralWallBlock block : layout.blocksForEdge(edgeIdx)) {
                            if (!isActiveFortifyBlock(block)) {
                                continue;
                            }
                            if (block.type() == WallBlockType.MOAT_DIG || block.type() == WallBlockType.EXTERIOR_CLEAR) {
                                continue;
                            }
                            BlockState st = world.getBlockState(block.worldPos());
                            if (st.isAir() || st.isReplaceable()) {
                                edgeMissing.add(block);
                            }
                        }

                        if (edgeMissing.isEmpty()) {
                            if (attempt > 1) {
                                ChatUtils.sendSystemMessage(source, String.format(
                                        "§a  Edge %d complete after %d attempts!", edgeIdx + 1, attempt - 1));
                            }
                            break; // edge is 100% — move on
                        }

                        ChatUtils.sendSystemMessage(source, String.format("§7  Patching edge %d (%d missing blocks%s)...",
                                edgeIdx + 1, edgeMissing.size(),
                                attempt > 1 ? ", attempt " + attempt : ""));

                        // Sort: FOUNDATION first, then ascending Y
                        edgeMissing.sort(Comparator
                                .comparingInt((ProceduralWallBlock b) -> b.type() == WallBlockType.FOUNDATION ? 0 : 1)
                                .thenComparingInt(b -> b.worldPos().getY()));

                        // Navigate toward nearest missing block, not edge midpoint
                        BlockPos nearestMissing = edgeMissing.stream()
                                .min(Comparator.comparingDouble(b -> bot.getBlockPos().getSquaredDistance(b.worldPos())))
                                .map(ProceduralWallBlock::worldPos)
                                .orElse(null);
                        navigateToEdgeApproach(source, bot, world, edge, surfaceProfile, nearestMissing);
                        sleepQuiet(100);

                        int placed = placeEdgeBlocks(
                                source,
                                bot,
                                world,
                                edgeMissing,
                                edge,
                                layout.hullVertices(),
                                referenceSurfaceY,
                                surfaceProfile,
                                patchPolicy
                        );
                        edgeRepaired += placed;

                        if (placed == 0) break; // no progress, give up on this edge
                    }
                    passRepaired += edgeRepaired;
                    if (edgeRepaired > 0) edgesPatched++;
                }
            }

            grandTotalRepaired += passRepaired;

            if (!autoRepeat) {
                ScaffoldService.teardownTrackedScaffolds(bot);
                ChatUtils.sendChatMessages(source, "§a[Fortify] Patched " + passRepaired + " blocks across " + edgesPatched + " edges.");
                return SkillExecutionResult.success("Patched " + passRepaired + " blocks.");
            }

            // Auto mode: report pass results and rescan
            showOverhead(bot, "Pass " + passNumber + ": " + passRepaired + " repaired. Rescanning...");

            // If this pass repaired nothing, stop to avoid infinite loop
            if (passRepaired == 0) {
                showOverhead(bot, "Nothing repaired this pass. Stopping.");
                break;
            }

        } while (autoRepeat && !SkillManager.shouldAbortSkill(bot));

        boolean aborted = SkillManager.shouldAbortSkill(bot);
        if (aborted) {
            LOGGER.info("[FortifyAbortCleanup] begin final scaffold teardown");
        }
        ScaffoldService.teardownTrackedScaffolds(bot);
        if (aborted) {
            LOGGER.info("[FortifyAbortCleanup] final scaffold teardown complete");
        }

        String cavitySummary = ignoredCavityPositions.isEmpty()
                ? ""
                : " (" + ignoredCavityPositions.size() + " cavities ignored)";
        if (aborted) {
            ChatUtils.sendChatMessages(source, "§e[Fortify] Auto-patch aborted after " + passNumber
                    + " passes; " + grandTotalRepaired + " blocks repaired." + cavitySummary);
            return SkillExecutionResult.failure("Auto-patch aborted after " + passNumber
                    + " passes; " + grandTotalRepaired + " blocks repaired." + cavitySummary);
        }
        ChatUtils.sendChatMessages(source, "§a[Fortify] Auto-patch complete: " + grandTotalRepaired + " total blocks repaired across " + passNumber + " passes." + cavitySummary);
        return SkillExecutionResult.success("Auto-patched " + grandTotalRepaired + " blocks across " + passNumber + " passes." + cavitySummary);

        } finally {
            this.currentLayout = null;
            entombmentHelper.updateLayout(null);
        }
    }

    /**
     * Patch tower blocks grouped by vertex. Navigates to each vertex,
     * builds its repair blocks, then tears down scaffolds before moving on.
     */
    private int patchTowerBlocks(ServerCommandSource source, ServerPlayerEntity bot,
                                   ServerWorld world, List<ProceduralWallBlock> towerRepairs,
                                   List<WallPoint> hullVertices,
                                   int referenceSurfaceY,
                                   SurfaceProfile surfaceProfile) {
        List<WallPoint> towerVertices = orderAndDedupeTowerVertices(hullVertices, bot.getBlockPos());
        Map<Integer, List<ProceduralWallBlock>> byVertex =
                groupTowerBlocksByNearestVertex(towerRepairs, towerVertices);

        int totalRepaired = 0;
        for (int vi = 0; vi < towerVertices.size(); vi++) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;

            WallPoint vertex = towerVertices.get(vi);
            long vertexKey = ((long) vertex.x() << 32) | (vertex.z() & 0xFFFFFFFFL);
            List<ProceduralWallBlock> vertexRepairs = byVertex.getOrDefault(vi, List.of());
            int plannedCount = countActivePlannedBlocks(vertexRepairs);
            if (plannedCount <= 0) {
                continue;
            }
            int presentBefore = countPresentBlocks(world, vertexRepairs);
            int missingBefore = Math.max(0, plannedCount - presentBefore);
            if (missingBefore <= 0) {
                continue;
            }

            // Skip towers that had zero progress on a previous pass
            if (zeroProgressTowerVertices.contains(vertexKey)) {
                LOGGER.info("[FortifyTower] Skipping previously-stuck tower ({}, {}) — zero progress in earlier pass",
                        vertex.x(), vertex.z());
                continue;
            }

            ChatUtils.sendSystemMessage(source, String.format("§7  Patching tower at (%d, %d) — %d missing blocks (%d/%d present)",
                    vertex.x(), vertex.z(), missingBefore, presentBefore, plannedCount));

            int placed = executeTowerVertexWithRetries(
                    source,
                    bot,
                    world,
                    vertex,
                    vertexRepairs,
                    "fortify-patch-tower",
                    "patch-tower",
                    vi,
                    towerVertices.size(),
                    referenceSurfaceY,
                    surfaceProfile
            );
            totalRepaired += placed;

            int presentAfter = countPresentBlocks(world, vertexRepairs);
            if (!isTowerComplete(presentAfter, plannedCount)) {
                ChatUtils.sendSystemMessage(source, String.format(
                        "§7    Tower patch incomplete at (%d, %d): %d/%d present.",
                        vertex.x(), vertex.z(), presentAfter, plannedCount));
            }

            // Track zero-progress vertices so we skip them on the next auto-patch pass
            if (placed == 0) {
                zeroProgressTowerVertices.add(vertexKey);
            } else {
                // Clear the blacklist entry if we made any progress
                zeroProgressTowerVertices.remove(vertexKey);
            }

        }
        return totalRepaired;
    }

    private SkillExecutionResult handleStatus(ServerCommandSource source, ServerPlayerEntity bot,
                                               ServerWorld world, MinecraftServer server, String wallName) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification saved = opt.get();

        // Regenerate layout using saved surface profile for stability
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        VillageFortificationLayoutService.SurfaceProfile savedProfile =
                VillageFortificationLayoutService.SurfaceProfile.fromSaved(saved.getSurfaceProfile());
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter(), savedProfile);

        if (layout.allBlocks().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        // Compute per-type and per-edge stats
        Map<Integer, Integer> totalByEdge = new LinkedHashMap<>();
        Map<String, int[]> byType = new LinkedHashMap<>(); // type -> [total, present]
        List<ProceduralWallBlock> missingBlocks = new ArrayList<>();

        for (ProceduralWallBlock block : layout.allBlocks()) {
            totalByEdge.merge(block.edgeIndex(), 1, Integer::sum);
            String typeName = block.type().name();
            byType.computeIfAbsent(typeName, k -> new int[2]);
            byType.get(typeName)[0]++;

            BlockPos pos = block.worldPos();
            if (ignoredCavityPositions.contains(pos)) {
                byType.get(typeName)[1]++;
                continue;
            }
            BlockState current = world.getBlockState(pos);
            if (!current.isAir() && !current.isReplaceable()) {
                byType.get(typeName)[1]++;
            } else {
                missingBlocks.add(block);
            }
        }

        int totalPlanned = layout.allBlocks().size();
        int totalPresent = totalPlanned - missingBlocks.size();
        int overallPct = totalPlanned > 0 ? (totalPresent * 100) / totalPlanned : 0;

        // Overall summary
        String overallColor = overallPct >= 95 ? "§a" : overallPct >= 50 ? "§e" : "§c";
        ChatUtils.sendChatMessages(source, "§a[Fortify] Status for '" + wallName + "': "
                + overallColor + totalPresent + "/" + totalPlanned + " (" + overallPct + "%)§a"
                + (saved.isComplete() ? " §a[MARKED COMPLETE]" : ""));

        // Per-type summary
        for (Map.Entry<String, int[]> entry : byType.entrySet()) {
            int total = entry.getValue()[0];
            int present = entry.getValue()[1];
            int pct = total > 0 ? (present * 100) / total : 100;
            String color = pct >= 95 ? "§a" : pct >= 50 ? "§e" : "§c";
            ChatUtils.sendSystemMessage(source, String.format("§7  %-18s %s%d/%d (%d%%)",
                    entry.getKey(), color, present, total, pct));
        }

        // Per-edge summary
        Map<Integer, List<ProceduralWallBlock>> missingByEdge = new LinkedHashMap<>();
        for (ProceduralWallBlock block : missingBlocks) {
            missingByEdge.computeIfAbsent(block.edgeIndex(), k -> new ArrayList<>()).add(block);
        }

        ChatUtils.sendSystemMessage(source, "§7  ─── Per-edge breakdown ───");
        for (Map.Entry<Integer, Integer> edgeEntry : totalByEdge.entrySet()) {
            int edgeIdx = edgeEntry.getKey();
            int planned = edgeEntry.getValue();
            int missing = missingByEdge.containsKey(edgeIdx) ? missingByEdge.get(edgeIdx).size() : 0;
            int present = planned - missing;
            int pct = planned > 0 ? (present * 100) / planned : 100;
            String color = pct >= 95 ? "§a" : pct >= 50 ? "§e" : "§c";
            String label = edgeIdx == -1 ? "Towers" : "Edge " + (edgeIdx + 1);
            ChatUtils.sendSystemMessage(source, String.format("§7  %s: %s%d/%d (%d%%)§7 — %d missing",
                    label, color, present, planned, pct, missing));
        }

        // Spawn particles with missing blocks highlighted red
        FortificationVisualizerService.spawnStatusParticles(world, layout, missingBlocks, bot);
        ChatUtils.sendSystemMessage(source, "§7  Particles: §6orange§7=towers, §9blue§7=walls, §egold§7=gate, §1dark blue§7=moat, §5purple§7=overhang, §cred§7=missing, §agreen§7=hull");

        return SkillExecutionResult.success("Status: " + totalPresent + "/" + totalPlanned + " (" + overallPct + "%).");
    }

    private SkillExecutionResult handleReportCavities(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (ignoredCavityPositions.isEmpty()) {
            ChatUtils.sendChatMessages(source, "§7[Fortify] No ignored cavities recorded this session.");
            return SkillExecutionResult.success("No ignored cavities recorded.");
        }
        ChatUtils.sendChatMessages(source, "§e[Fortify] Ignored cavities: " + ignoredCavityPositions.size());
        for (String note : ignoredCavityNotes) {
            ChatUtils.sendSystemMessage(source, "§7  " + note);
        }
        boolean nearAny = false;
        if (bot != null) {
            BlockPos botPos = bot.getBlockPos();
            nearAny = ignoredCavityPositions.stream().anyMatch(p -> p.isWithinDistance(botPos, 4.5));
            if (nearAny) {
                String line = CAVITY_DIALOGUES.get(ThreadLocalRandom.current().nextInt(CAVITY_DIALOGUES.size()));
                CompanionOverheadDialogueService.showOverheadLine(bot, line, 4_000, 48.0D, "fortify", "cavity");
            }
        }
        if (currentLayout != null) {
            FortificationVisualizerService.spawnLayoutParticles(world, currentLayout, ignoredCavityPositions, bot);
            ChatUtils.sendSystemMessage(source, "§7  Red particles show ignored cavities on the layout.");
        }
        return SkillExecutionResult.success("Reported ignored cavities.");
    }

    private SkillExecutionResult handleMerge(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, MinecraftServer server, String wallName) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification existing = opt.get();
        List<WallPoint> existingHull = existing.getHullWallPoints();
        if (existingHull.size() < 3) {
            return SkillExecutionResult.failure("Existing wall '" + wallName + "' has invalid hull data.");
        }

        // Detect current village
        showOverhead(bot, "Scanning for village to merge...");
        BlockPos searchCenter = bot.getBlockPos();
        VillageBounds bounds = VillageFortificationLayoutService.detectVillageBounds(world, searchCenter, 64);
        if (bounds.foundPOIs() == 0) {
            return SkillExecutionResult.failure("No village detected nearby to merge.");
        }

        // Generate layout for current village to get its hull
        FortificationLayout currentLayout = VillageFortificationLayoutService.generateLayout(world, bounds.center(), 64);
        if (currentLayout.hullVertices().size() < 3) {
            return SkillExecutionResult.failure("Could not compute hull for current village.");
        }

        // Merge the two hulls
        List<WallPoint> mergedHull = VillageFortificationLayoutService.mergeHulls(
                existingHull, currentLayout.hullVertices());

        if (mergedHull.size() < 3) {
            return SkillExecutionResult.failure("Merged hull has too few vertices.");
        }

        // Generate new layout from merged hull
        FortificationLayout mergedLayout = VillageFortificationLayoutService.generateLayoutFromHull(
                mergedHull, world, existing.getCenter());

        if (mergedLayout.edges().isEmpty()) {
            return SkillExecutionResult.failure("Could not generate layout from merged hull.");
        }

        // Count blocks that are already placed from the old wall
        int alreadyPresent = 0;
        for (ProceduralWallBlock block : mergedLayout.allBlocks()) {
            BlockState current = world.getBlockState(block.worldPos());
            if (!current.isAir() && !current.isReplaceable()) {
                alreadyPresent++;
            }
        }

        // Compute per-edge planned counts for the merged layout
        Map<Integer, Integer> edgePlannedCounts = computeEdgePlannedCounts(mergedLayout);

        // Update the existing wall with the merged hull and reset progress
        SavedFortification merged = FortificationPersistenceService.create(
                wallName, worldKey, existing.getCenter(), mergedHull,
                existing.getSearchRadius(), edgePlannedCounts);
        merged.setTotalBlocksPlaced(alreadyPresent);
        if (mergedLayout.surfaceProfile() != null) {
            merged.setSurfaceProfile(mergedLayout.surfaceProfile().export());
        }
        FortificationPersistenceService.save(server, merged);

        String mergedDesc = VillageFortificationLayoutService.describePlan(mergedLayout);
        ChatUtils.sendChatMessages(source, "§a[Fortify] Merged! " + mergedDesc);
        ChatUtils.sendSystemMessage(source, "§7  " + alreadyPresent + " existing blocks retained. Auto-resuming build...");

        // Show the merged layout with particles
        FortificationVisualizerService.spawnLayoutParticles(world, mergedLayout, Set.of(), bot);

        // Auto-resume building the expanded wall
        return handleResume(source, bot, world, server, wallName);
    }

    private SkillExecutionResult handleNewBuild(ServerCommandSource source, ServerPlayerEntity bot,
                                                 ServerWorld world, MinecraftServer server, String args) {
        boolean dryRun = false;
        if (args != null && !args.isBlank()) {
            String lower = args.trim().toLowerCase();
            if (lower.equals("dry_run") || lower.equals("dryrun") || lower.equals("preview")) {
                dryRun = true;
            }
        }

        // Detect village bounds
        ChatUtils.sendChatMessages(source, "Writing the blueprints...");
        BlockPos searchCenter = bot.getBlockPos();
        VillageBounds bounds = VillageFortificationLayoutService.detectVillageBounds(world, searchCenter, 64);

        if (bounds.foundPOIs() == 0) {
            return SkillExecutionResult.failure("No village detected nearby.");
        }

        // Generate layout using convex hull
        FortificationLayout layout = VillageFortificationLayoutService.generateLayout(world, bounds.center(), 64);

        if (layout.edges().isEmpty()) {
            return SkillExecutionResult.failure("Could not generate wall layout — hull computation failed.");
        }

        String planDesc = VillageFortificationLayoutService.describePlan(layout);
        ChatUtils.sendChatMessages(source, "§a[Fortify] " + planDesc);
        LOGGER.info("Fortification layout: {}", planDesc);

        if (dryRun) {
            ChatUtils.sendSystemMessage(source, "§7Hull vertices (" + layout.hullVertices().size() + "):");
            for (int i = 0; i < layout.hullVertices().size(); i++) {
                WallPoint v = layout.hullVertices().get(i);
                ChatUtils.sendSystemMessage(source, String.format("§7  V%d: (%d, %d)", i, v.x(), v.z()));
            }
            ChatUtils.sendSystemMessage(source, "§7Edges (" + layout.edges().size() + "):");
            for (WallEdge e : layout.edges()) {
                ChatUtils.sendSystemMessage(source, String.format("§7  E%d: (%d,%d)->(%d,%d) len=%.0f%s",
                        e.index(), e.start().x(), e.start().z(), e.end().x(), e.end().z(), e.length(),
                        e.index() == layout.gatehouseEdgeIndex() ? " [GATE]" : ""));
            }
            // Spawn ground footprint particles (no missing overlay since nothing is built yet)
            FortificationVisualizerService.spawnLayoutParticles(world, layout, Set.of(), bot);
            ChatUtils.sendSystemMessage(source, "§7Particles: §6orange§7=towers, §9blue§7=walls, §egold§7=gate, §1dark blue§7=moat, §5purple§7=overhang, §4light red§7=clear, §agreen§7=hull");
            return SkillExecutionResult.success("Dry run complete. " + planDesc);
        }

        // Overlap detection: check existing walls for hull overlap
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        List<SavedFortification> existingWalls = FortificationPersistenceService.listForWorld(server, worldKey);
        for (SavedFortification existing : existingWalls) {
            List<WallPoint> existingHull = existing.getHullWallPoints();
            if (existingHull.size() < 3) continue;
            double overlap = VillageFortificationLayoutService.overlapPercentage(
                    layout.hullVertices(), existingHull);
            if (overlap > 0.30) {
                ChatUtils.sendChatMessages(source, "§c[Fortify] New wall overlaps " + (int)(overlap * 100)
                        + "% with existing wall '" + existing.getName() + "'. "
                        + "Use §f/bot fortify merge " + existing.getName() + "§c to combine them.");
                return SkillExecutionResult.failure("Wall overlaps >30% with '" + existing.getName() + "'. Use merge instead.");
            } else if (overlap > 0.05) {
                ChatUtils.sendChatMessages(source, "§e[Fortify] Warning: " + (int)(overlap * 100)
                        + "% overlap with '" + existing.getName() + "'. Overlapping edges will be auto-skipped.");
            }
        }

        // Material check
        int buildBlocks = countBuildingBlocks(bot);
        ChatUtils.sendSystemMessage(source, "§7Bot has " + buildBlocks + " building blocks.");
        if (buildBlocks == 0) {
            return SkillExecutionResult.failure("No building blocks in inventory. Give me stone bricks, cobblestone, or similar.");
        }
        if (buildBlocks < layout.totalBlocks()) {
            ChatUtils.sendChatMessages(source, "§eWarning: Only " + buildBlocks + " blocks available, need ~"
                    + layout.totalBlocks() + ". Will build as far as possible.");
        }

        // Compute per-edge planned block counts
        Map<Integer, Integer> edgePlannedCounts = computeEdgePlannedCounts(layout);

        // Create persistence entry
        String wallName = FortificationPersistenceService.autoName(layout.center());
        SavedFortification saved = FortificationPersistenceService.create(
                wallName, worldKey, layout.center(), layout.hullVertices(), 64, edgePlannedCounts);
        // Save original terrain Y values so patching uses stable positions
        if (layout.surfaceProfile() != null) {
            saved.setSurfaceProfile(layout.surfaceProfile().export());
        }
        FortificationPersistenceService.save(server, saved);

        showOverhead(bot, "Wall saved. Let's get started!");

        return buildWall(source, bot, world, server, layout, wallName, worldKey,
                new HashSet<>(), 0, 0);
    }

    // ── Core build loop ─────────────────────────────────────────

    /**
     * Build the fortification wall, edge by edge. Handles resume by skipping completed edges.
     */
    private SkillExecutionResult buildWall(ServerCommandSource source, ServerPlayerEntity bot,
                                            ServerWorld world, MinecraftServer server,
                                            FortificationLayout layout,
                                            String wallName, String worldKey,
                                            Set<Integer> completedEdges, int startEdgeIndex,
                                            int priorBlocksPlaced) {
        this.currentLayout = layout;
        entombmentHelper.updateLayout(layout);
        this.gateRoutingFailures = 0;
        try {

        int totalPlaced = priorBlocksPlaced;
        int edgesCompleted = completedEdges.size();
        int totalEdges = layout.edges().size();

        // Compute reference surface Y from layout FOUNDATION blocks (unaffected by moat digging).
        // terrainY() uses heightmap which changes after digging, so we need the original surface level.
        int referenceSurfaceY = computeReferenceSurfaceY(bot, layout, world);
        SurfaceProfile surfaceProfile = createSurfaceProfile(layout, referenceSurfaceY);
        boolean resumedRun = startEdgeIndex != 0 || priorBlocksPlaced > 0 || !completedEdges.isEmpty();
        int edgeStartIndex = layoutHelper.chooseEdgeStartIndex(bot, layout, completedEdges, startEdgeIndex);
        List<Integer> edgeBuildOrder = layoutHelper.orderedRemainingEdges(layout, completedEdges, edgeStartIndex);

        // Populate layout positions so break-through navigation knows which blocks to protect
        Set<BlockPos> layoutPositions = new HashSet<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            layoutPositions.add(b.worldPos());
        }
        this.fortificationProtectedPositions = Collections.unmodifiableSet(layoutPositions);
        this.ignoredCavityPositions.clear();
        this.ignoredCavityNotes.clear();

        // Ensure bot starts on solid, open ground — critical for resume from stuck positions
        if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
            ensureOnSurface(bot, world, referenceSurfaceY);
        }

        // Build towers with strict local completion per vertex.
        // On resume, skip tower stage to reduce startup latency; patch mode can repair misses.
        if (!completedEdges.contains(-1) && !resumedRun) {
            List<ProceduralWallBlock> towerBlocks = layout.blocksForEdge(-1);
            if (!towerBlocks.isEmpty()) {
                int towerPlaced = 0;
                List<WallPoint> towerVertices = orderAndDedupeTowerVertices(layout.hullVertices(), bot.getBlockPos());
                Map<Integer, List<ProceduralWallBlock>> blocksByVertex =
                        groupTowerBlocksByNearestVertex(towerBlocks, towerVertices);
                showOverhead(bot, "Building " + towerVertices.size() + " corner towers...");

                for (int vi = 0; vi < towerVertices.size(); vi++) {
                    if (SkillManager.shouldAbortSkill(bot)) break;
                    if (countBuildingBlocks(bot) == 0) break;

                    WallPoint vertex = towerVertices.get(vi);
                    List<ProceduralWallBlock> vertexBlocks = blocksByVertex.getOrDefault(vi, List.of());
                    int plannedCount = countActivePlannedBlocks(vertexBlocks);
                    if (plannedCount <= 0) {
                        continue;
                    }
                    int presentCount = countPresentBlocks(world, vertexBlocks);
                    if (isTowerComplete(presentCount, plannedCount)) {
                        continue;
                    }

                    ChatUtils.sendSystemMessage(source, String.format("§7  Tower %d/%d at (%d, %d) — %d blocks (%d present)",
                            vi + 1, towerVertices.size(), vertex.x(), vertex.z(), plannedCount, presentCount));

                    int placed = executeTowerVertexWithRetries(
                            source,
                            bot,
                            world,
                            vertex,
                            vertexBlocks,
                            "fortify-tower",
                            "tower",
                            vi,
                            towerVertices.size(),
                            referenceSurfaceY,
                            surfaceProfile
                    );
                    towerPlaced += placed;

                    int presentAfter = countPresentBlocks(world, vertexBlocks);
                    if (!isTowerComplete(presentAfter, plannedCount)) {
                        ChatUtils.sendSystemMessage(source, String.format(
                                "§7    Tower %d incomplete (%d/%d); moving on after bounded retries.",
                                vi + 1, presentAfter, plannedCount));
                    }

                }

                totalPlaced += towerPlaced;

                {
                    int towerPlanned = countActivePlannedBlocks(towerBlocks);
                    boolean towerEdgeComplete = FortificationPersistenceService.markEdgeComplete(
                            server, worldKey, wallName, -1, towerPlaced, towerPlanned);
                    if (towerEdgeComplete) {
                        completedEdges.add(-1);
                    }
                }

                // Check abort/resources
                if (SkillManager.shouldAbortSkill(bot)) {
                    saveAndReport(source, server, worldKey, wallName, startEdgeIndex, totalPlaced, "Aborted");
                    return SkillExecutionResult.success("Aborted. Progress saved as '" + wallName + "'.");
                }
                if (countBuildingBlocks(bot) == 0) {
                    return handleOutOfBlocks(source, server, worldKey, wallName, startEdgeIndex, totalPlaced);
                }
            }
        }

        // ══════════════════════════════════════════════════════════════
        // PHASE A: Moat dig — now a separate skill (FortifyMoatSkill).
        // Wall build skips moat/clearance work unconditionally.
        // ══════════════════════════════════════════════════════════════
        LOGGER.info("[Fortify] Moat stage is now a separate skill; skipping moat and clearance work.");
        showOverhead(bot, "Building walls and towers...");

        // ══════════════════════════════════════════════════════════════
        // PHASE B: Place ALL wall blocks, edge by edge
        // ══════════════════════════════════════════════════════════════
        if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
            escapeIfInHole(bot, world, referenceSurfaceY);
        }
        long phaseBStartMs = System.currentTimeMillis();
        Set<Integer> edgesVisited = new HashSet<>(completedEdges);

        int ei;
        while ((ei = layoutHelper.pickNearestRemainingEdge(bot, layout, edgesVisited)) >= 0) {

            if (SkillManager.shouldAbortSkill(bot)) {
                saveAndReport(source, server, worldKey, wallName, ei, totalPlaced, "Aborted");
                return SkillExecutionResult.success("Aborted. Progress saved as '" + wallName + "'.");
            }
            if ((System.currentTimeMillis() - phaseBStartMs) > PHASE_B_TIME_BUDGET_MS) {
                saveAndReport(source, server, worldKey, wallName, ei, totalPlaced, "Time budget reached");
                return SkillExecutionResult.success("Time budget reached. Progress saved as '" + wallName + "'.");
            }

            WallEdge edge = layout.edges().get(ei);
            // Only place blocks (no dig blocks — already handled in Phase A)
            List<ProceduralWallBlock> edgePlaceBlocks = new ArrayList<>();
            for (ProceduralWallBlock b : layout.blocksForEdge(ei)) {
                if (!isActiveFortifyBlock(b)) {
                    continue;
                }
                if (b.type() != WallBlockType.MOAT_DIG && b.type() != WallBlockType.EXTERIOR_CLEAR) {
                    edgePlaceBlocks.add(b);
                }
            }

            if (edgePlaceBlocks.isEmpty()) {
                completedEdges.add(ei);
                continue;
            }

            showOverhead(bot, String.format("Placing edge %d/%d (%.0f blocks)%s",
                    ei + 1, totalEdges, edge.length(),
                    ei == layout.gatehouseEdgeIndex() ? " [GATEHOUSE]" : ""));

            navigateToEdgeApproach(source, bot, world, edge, surfaceProfile);

            long edgeBudgetRemaining = Math.max(30_000L,
                    PHASE_B_TIME_BUDGET_MS - (System.currentTimeMillis() - phaseBStartMs));
            int edgePlaced = placeEdgeBlocks(source, bot, world, edgePlaceBlocks, edge,
                    layout.hullVertices(), referenceSurfaceY, surfaceProfile,
                    new ExecutionPolicy(MAX_PASSES_PER_EDGE, 2, 3, edgeBudgetRemaining));
            totalPlaced += edgePlaced;

            if (edgePlaced > 0) {
                int edgeTotalBlocks = edgePlaceBlocks.size();
                boolean edgeComplete = FortificationPersistenceService.markEdgeComplete(
                        server, worldKey, wallName, ei, edgePlaced, edgeTotalBlocks);
                if (edgeComplete) {
                    edgesCompleted++;
                    completedEdges.add(ei);
                }
            }

            // Mark this edge as visited so pickNearestRemainingEdge moves on
            edgesVisited.add(ei);

            if (countBuildingBlocks(bot) == 0 && edgesVisited.size() < totalEdges) {
                return handleOutOfBlocks(source, server, worldKey, wallName, ei + 1, totalPlaced);
            }
        }

        // Clear navigation protection set before cleanup
        this.fortificationProtectedPositions = Set.of();

        // Scaffold cleanup
        int tornDown = ScaffoldService.teardownTrackedScaffolds(bot);
        if (tornDown > 0) {
            showOverhead(bot, "Cleaned up " + tornDown + " scaffolds.");
        }

        // Final report — verify with world scan before marking complete
        int totalPlanned = layout.allBlocks().size();
        int presentInWorld = countPresentBlocks(world, layout.allBlocks());
        double overallRatio = totalPlanned > 0 ? (double) presentInWorld / totalPlanned : 0;
        int pct = (int) (overallRatio * 100);

        if (overallRatio >= FortificationPersistenceService.EDGE_COMPLETION_THRESHOLD) {
            FortificationPersistenceService.markComplete(server, worldKey, wallName);
            ChatUtils.sendChatMessages(source, "§a[Fortify] Fortification complete! "
                    + totalPlaced + " blocks placed (" + presentInWorld + "/" + totalPlanned
                    + " present, " + pct + "%). Saved as '" + wallName + "'.");
            return SkillExecutionResult.success("Fortification complete: " + totalPlaced + " blocks.");
        } else {
            FortificationPersistenceService.updateProgress(server, worldKey, wallName, edgesCompleted, totalPlaced);
            ChatUtils.sendChatMessages(source, "§e[Fortify] Partial completion: "
                    + totalPlaced + " blocks placed, " + presentInWorld + "/" + totalPlanned
                    + " present (" + pct + "%), " + edgesCompleted + "/" + totalEdges
                    + " edges. Use §f/bot fortify patch " + wallName + "§e to repair.");
            return SkillExecutionResult.success("Partial fortification: " + totalPlaced + " blocks, " + pct + "%.");
        }

        } finally {
            this.currentLayout = null;
            entombmentHelper.updateLayout(null);
        }
    }

    private SkillExecutionResult handleOutOfBlocks(ServerCommandSource source, MinecraftServer server,
                                                    String worldKey, String wallName,
                                                    int lastEdge, int totalPlaced) {
        FortificationPersistenceService.updateProgress(server, worldKey, wallName, lastEdge, totalPlaced);
        ScaffoldService.teardownTrackedScaffolds(source.getPlayer());
        ChatUtils.sendChatMessages(source, "§e[Fortify] Out of building blocks! Progress saved as '" + wallName
                + "'. Give me more blocks and use §f/bot fortify resume " + wallName + "§e.");
        return SkillExecutionResult.success("Out of blocks. Progress saved as '" + wallName + "'.");
    }

    private void saveAndReport(ServerCommandSource source, MinecraftServer server,
                                String worldKey, String wallName, int lastEdge, int totalPlaced, String reason) {
        FortificationPersistenceService.updateProgress(server, worldKey, wallName, lastEdge, totalPlaced);
        ScaffoldService.teardownTrackedScaffolds(source.getPlayer());
        ChatUtils.sendChatMessages(source, "§c[Fortify] " + reason + ". Progress saved as '" + wallName + "'.");
    }

    // ── Moat digging (unified across all edges) ─────────────────

    /**
     * Dig all moat/exterior-clear blocks using a radial stripmine pattern:
     * walk the perimeter one block at a time, mine everything in reach at each step.
     *
     * Pass 1: Walk the wall-line perimeter, mine reachable blocks from above.
     *         Only mines blocks at or below bot Y to prevent through-terrain mining.
     * Pass 2: Walk directly to any remaining blocks (typically EXTERIOR_CLEAR at
     *         offset +5/+6, unreachable from the wall line) and mine from adjacent.
     */
    private MoatDigResult digAllMoatBlocks(ServerCommandSource source, ServerPlayerEntity bot,
                                           ServerWorld world, List<ProceduralWallBlock> allDigBlocks,
                                           int referenceSurfaceY, SurfaceProfile surfaceProfile,
                                           List<BlockPos> perimeterPath) {
        if (allDigBlocks.isEmpty()) {
            return new MoatDigResult(0, false);
        }
        if (perimeterPath == null || perimeterPath.isEmpty()) {
            return new MoatDigResult(0, false);
        }

        Set<BlockPos> remaining = collectExistingDigTargets(world, allDigBlocks);
        if (remaining.isEmpty()) {
            return new MoatDigResult(allDigBlocks.size(), false);
        }

        int startIdx = nearestPathIndex(bot, perimeterPath);
        int totalDigBlocks = allDigBlocks.size();
        int dug = totalDigBlocks - remaining.size();
        int totalSteps = 0;
        int pass1AttemptsUsed = 0;

        while (pass1AttemptsUsed < PASS1_MAX_ATTEMPTS && !remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
            pass1AttemptsUsed++;
            int stepsThisAttempt = 0;
            int failedMoves = 0;
            int pass1MovedSteps = 0;
            int pass1MinedCount = 0;

            for (int offset = 0; offset < perimeterPath.size(); offset++) {
                if (SkillManager.shouldAbortSkill(bot) || remaining.isEmpty()) break;

                int idx = (startIdx + offset) % perimeterPath.size();
                BlockPos walkPos = perimeterPath.get(idx);
                stepsThisAttempt++;
                totalSteps++;

                double distSq = bot.squaredDistanceTo(walkPos.getX() + 0.5, bot.getY(), walkPos.getZ() + 0.5);
                if (distSq > 4.0) {
                    BlockPos beforeMove = bot.getBlockPos();
                    moveToDigPosition(source, bot, world, walkPos, surfaceProfile);
                    if (!beforeMove.equals(bot.getBlockPos())) {
                        pass1MovedSteps++;
                    }

                    double postDist = bot.squaredDistanceTo(
                            walkPos.getX() + 0.5, bot.getY(), walkPos.getZ() + 0.5);
                    if (postDist > 36.0) {
                        failedMoves++;
                        int failThreshold = moatPass1FailureThreshold(pass1MovedSteps);
                        if (failedMoves >= failThreshold) {
                            LOGGER.warn("Moat dig pass 1 attempt {}: repeated movement failures near {} ({} fails, threshold={})",
                                    pass1AttemptsUsed, walkPos.toShortString(), failedMoves, failThreshold);
                            break;
                        }
                        continue;
                    }
                    failedMoves = 0;
                }

                int botY = bot.getBlockPos().getY();
                int maxMineY = shouldTriggerDepthRecovery(botY, referenceSurfaceY)
                        ? referenceSurfaceY
                        : botY;
                Iterator<BlockPos> it = remaining.iterator();
                while (it.hasNext()) {
                    if (SkillManager.shouldAbortSkill(bot)) break;
                    BlockPos pos = it.next();

                    if (world.getBlockState(pos).isAir()) {
                        it.remove();
                        dug++;
                        continue;
                    }

                    if (pos.getY() > maxMineY) continue;
                    if (!isWithinMiningReach(bot, pos)) continue;

                    LookController.faceBlock(bot, pos);
                    sleepQuiet(50);
                    if (digBlock(bot, world, pos)) {
                        it.remove();
                        dug++;
                        pass1MinedCount++;
                    }
                }

                if (stepsThisAttempt % 20 == 0) {
                    LOGGER.info("Moat dig pass 1 attempt {}: {}/{} blocks cleared ({}/{} steps walked)",
                            pass1AttemptsUsed, dug, totalDigBlocks, stepsThisAttempt, perimeterPath.size());
                }
            }

            if (remaining.isEmpty() || SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            LOGGER.info("Moat dig pass 1 attempt {} summary: mined={} movedSteps={} remaining={}",
                    pass1AttemptsUsed, pass1MinedCount, pass1MovedSteps, remaining.size());

            if (pass1MinedCount > 0 || pass1MovedSteps > 0) {
                break;
            }

            StartupRecoveryResult recovery = runStartupRecovery(
                    source,
                    bot,
                    world,
                    referenceSurfaceY,
                    surfaceProfile,
                    perimeterPath,
                    remaining,
                    "pass1-zero-progress"
            );
            dug += recovery.minedCount();

            if (recovery.failedNoSafeTile()) {
                LOGGER.error("[FortifyFailsafe] failed-no-safe-tile aborting");
                return new MoatDigResult(dug, true);
            }

            if (!recovery.progressMade()) {
                LOGGER.warn("Moat dig pass 1: bounded exit after zero progress (movedSteps=0 mined=0)");
                return new MoatDigResult(dug, false);
            }

            if (!shouldRetryPass1AfterRecovery(pass1MinedCount, pass1AttemptsUsed - 1, recovery.progressMade())) {
                break;
            }
            startIdx = nearestPathIndex(bot, perimeterPath);
        }

        if (!remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
            LOGGER.info("Moat dig pass 2: {} blocks unreachable from perimeter, approaching directly", remaining.size());
            Map<BlockPos, Integer> directFailures = new HashMap<>();
            int consecutiveNoProgress = 0;
            int recoveryAttempts = 0;
            int attemptBudget = Math.max(64, remaining.size() * 3);
            long pass2DeadlineMs = System.currentTimeMillis() + MOAT_PASS2_TIME_BUDGET_MS;

            while (!remaining.isEmpty()
                    && !SkillManager.shouldAbortSkill(bot)
                    && attemptBudget-- > 0
                    && System.currentTimeMillis() < pass2DeadlineMs) {
                List<BlockPos> ordered = orderMoatDirectTargets(new ArrayList<>(remaining), bot.getBlockPos());
                if (ordered.isEmpty()) {
                    break;
                }
                BlockPos pos = ordered.get(0);

                if (world.getBlockState(pos).isAir()) {
                    remaining.remove(pos);
                    dug++;
                    consecutiveNoProgress = 0;
                    continue;
                }

                moveToDigPosition(source, bot, world, pos, surfaceProfile);

                boolean mined = false;
                if (isWithinMiningReach(bot, pos)) {
                    LookController.faceBlock(bot, pos);
                    sleepQuiet(50);
                    mined = digBlock(bot, world, pos);
                    if (mined) {
                        remaining.remove(pos);
                        dug++;
                        directFailures.remove(pos);
                    }
                }

                if (mined) {
                    consecutiveNoProgress = 0;
                } else {
                    int fails = directFailures.merge(pos, 1, Integer::sum);
                    if (fails >= 2) {
                        remaining.remove(pos);
                    }
                    consecutiveNoProgress++;
                    if (consecutiveNoProgress >= STARTUP_NO_PROGRESS_LIMIT) {
                        recoveryAttempts++;
                        StartupRecoveryResult recovery = runStartupRecovery(
                                source,
                                bot,
                                world,
                                referenceSurfaceY,
                                surfaceProfile,
                                perimeterPath,
                                remaining,
                                "pass2-no-progress"
                        );
                        dug += recovery.minedCount();
                        if (recovery.failedNoSafeTile()) {
                            LOGGER.error("[FortifyFailsafe] failed-no-safe-tile aborting");
                            return new MoatDigResult(dug, true);
                        }
                        if (recoveryAttempts >= PASS2_MAX_RECOVERY_ATTEMPTS) {
                            LOGGER.warn("Moat pass 2 bounded exit after {} recovery attempts with {} remaining targets",
                                    recoveryAttempts, remaining.size());
                            break;
                        }
                        consecutiveNoProgress = recovery.progressMade() ? 0 : STARTUP_NO_PROGRESS_LIMIT;
                        if (!recovery.progressMade()) {
                            break;
                        }
                    }
                }
            }
            if (!remaining.isEmpty() && System.currentTimeMillis() >= pass2DeadlineMs) {
                LOGGER.warn("Moat pass 2 timed out after {} ms with {} targets remaining",
                        MOAT_PASS2_TIME_BUDGET_MS, remaining.size());
            }

            if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
                escapeIfInHole(bot, world, referenceSurfaceY);
            }
        }

        if (!remaining.isEmpty()) {
            LOGGER.warn("Moat dig: {} blocks could not be mined (unreachable or blocked)", remaining.size());
        }
        LOGGER.info("Moat dig complete: {}/{} blocks cleared in {} perimeter steps", dug, totalDigBlocks, totalSteps);
        return new MoatDigResult(dug, false);
    }

    private List<ProceduralWallBlock> densifyMoatDigTargets(List<ProceduralWallBlock> allDigBlocks,
                                                             Set<BlockPos> protectedPositions) {
        if (allDigBlocks == null || allDigBlocks.isEmpty()) {
            return new ArrayList<>();
        }

        Map<BlockPos, ProceduralWallBlock> byPos = new LinkedHashMap<>();
        Set<BlockPos> moatPos = new HashSet<>();
        for (ProceduralWallBlock block : allDigBlocks) {
            byPos.putIfAbsent(block.worldPos(), block);
            if (block.type() == WallBlockType.MOAT_DIG) {
                moatPos.add(block.worldPos());
            }
        }

        int[][] diagonals = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int added = 0;
        for (BlockPos pos : new ArrayList<>(moatPos)) {
            ProceduralWallBlock seed = byPos.get(pos);
            if (seed == null) {
                continue;
            }
            for (int[] d : diagonals) {
                BlockPos diagonal = pos.add(d[0], 0, d[1]);
                if (!moatPos.contains(diagonal)) {
                    continue;
                }

                BlockPos bridgeA = pos.add(d[0], 0, 0);
                BlockPos bridgeB = pos.add(0, 0, d[1]);
                added += addMoatDigConnector(byPos, moatPos, protectedPositions, bridgeA, seed.edgeIndex());
                added += addMoatDigConnector(byPos, moatPos, protectedPositions, bridgeB, seed.edgeIndex());
            }
        }

        if (added > 0) {
            LOGGER.info("Densified moat dig targets by {} connector blocks", added);
        }
        return new ArrayList<>(byPos.values());
    }

    private int addMoatDigConnector(Map<BlockPos, ProceduralWallBlock> byPos,
                                    Set<BlockPos> moatPos,
                                    Set<BlockPos> protectedPositions,
                                    BlockPos connector,
                                    int edgeIndex) {
        if (connector == null || byPos.containsKey(connector)) {
            return 0;
        }
        if (protectedPositions != null && protectedPositions.contains(connector)) {
            return 0;
        }
        ProceduralWallBlock synthetic = new ProceduralWallBlock(
                connector,
                Blocks.AIR.getDefaultState(),
                WallBlockType.MOAT_DIG,
                edgeIndex
        );
        byPos.put(connector, synthetic);
        moatPos.add(connector);
        return 1;
    }

    private Set<BlockPos> collectExistingDigTargets(ServerWorld world, List<ProceduralWallBlock> allDigBlocks) {
        List<BlockPos> ordered = new ArrayList<>();
        for (ProceduralWallBlock block : allDigBlocks) {
            if (!world.getBlockState(block.worldPos()).isAir()) {
                ordered.add(block.worldPos());
            }
        }
        ordered.sort(Comparator
                .comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getY));
        return new LinkedHashSet<>(ordered);
    }

    private int nearestPathIndex(ServerPlayerEntity bot, List<BlockPos> path) {
        int startIdx = 0;
        double bestStartDist = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            double d = bot.squaredDistanceTo(p.getX() + 0.5, bot.getY(), p.getZ() + 0.5);
            if (d < bestStartDist) {
                bestStartDist = d;
                startIdx = i;
            }
        }
        return startIdx;
    }

    private StartupRecoveryResult runStartupRecovery(ServerCommandSource source, ServerPlayerEntity bot,
                                                     ServerWorld world, int referenceSurfaceY,
                                                     SurfaceProfile surfaceProfile, List<BlockPos> perimeterPath,
                                                     Set<BlockPos> remainingTargets, String context) {
        long startMs = System.currentTimeMillis();
        BlockPos spawn = bot.getBlockPos();
        int depth = referenceSurfaceY - spawn.getY();
        int minedCount = probeLocalMoatTargets(bot, world, remainingTargets, 12);
        boolean moved = movedByAtLeast(spawn, bot.getBlockPos(), 1.0);
        boolean progress = moved || minedCount > 0;

        LOGGER.info("[FortifyStartRecovery] spawn={} refY={} depth={} localProgress={} context={}",
                spawn.toShortString(), referenceSurfaceY, depth, progress, context);
        if (progress || SkillManager.shouldAbortSkill(bot)) {
            return new StartupRecoveryResult(progress, minedCount, false, false);
        }

        BlockPos anchor = nearestPathPoint(perimeterPath, bot.getBlockPos());
        int noProgressAttempts = 0;
        while (!SkillManager.shouldAbortSkill(bot)
                && (System.currentTimeMillis() - startMs) <= STARTUP_RECOVERY_BUDGET_MS
                && noProgressAttempts < STARTUP_NO_PROGRESS_LIMIT) {
            BlockPos before = bot.getBlockPos();
            boolean unwedged = tryUnwedgeFromTightSpace(
                    source, bot, world, surfaceProfile, anchor, context + ":unstick-" + noProgressAttempts);
            if (!unwedged) {
                BlockPos nudge = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2);
                if (nudge != null && !nudge.equals(bot.getBlockPos())) {
                    walkToTarget(source, bot, nudge, 1_200L);
                }
            }

            int minedNow = probeLocalMoatTargets(bot, world, remainingTargets, 6);
            minedCount += minedNow;
            progress = minedNow > 0 || movedByAtLeast(before, bot.getBlockPos(), 1.0);
            if (progress) {
                return new StartupRecoveryResult(true, minedCount, false, false);
            }
            noProgressAttempts++;
        }

        LOGGER.warn("[FortifyStartRecovery] escalation=emergency-snap reason=zero-progress attempts={} context={}",
                noProgressAttempts, context);
        boolean snapped = emergencySnapToSafePosition(bot, world, perimeterPath, remainingTargets, context, startMs);
        return snapped
                ? new StartupRecoveryResult(true, minedCount, true, false)
                : new StartupRecoveryResult(false, minedCount, false, true);
    }

    private void runPatchStartPreflightRecovery(ServerCommandSource source,
                                                ServerPlayerEntity bot,
                                                ServerWorld world,
                                                int referenceSurfaceY,
                                                SurfaceProfile surfaceProfile) {
        if (bot == null || world == null) {
            return;
        }
        BlockPos start = bot.getBlockPos();
        int depth = Math.max(0, referenceSurfaceY - start.getY());
        boolean trapLikeStart = isTrapLikeCell(world, start);
        if (depth <= 0 && !trapLikeStart) {
            return;
        }

        LOGGER.info("[FortifyPatch] preflight-start pos={} refY={} depth={} trapLike={} ctx=fortify-edge:patch-start-preflight",
                start.toShortString(), referenceSurfaceY, depth, trapLikeStart);

        final boolean[] unwedged = {false};
        final boolean[] carved = {false};
        final BlockPos[] carveTargetRef = {null};

        runWithFortifyEdgeNavScope(bot, world, "fortify-edge:patch-start-preflight", start, () -> {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
                escapeIfInHole(bot, world, referenceSurfaceY);
            }

            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (isTrapLikeCell(world, bot.getBlockPos())) {
                unwedged[0] = tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, null, "edge-patch-start");
            }

            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (isTrapLikeCell(world, bot.getBlockPos())) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 4);
                BlockPos carveTarget = safe != null ? safe.toImmutable() : bot.getBlockPos().offset(bot.getHorizontalFacing()).toImmutable();
                carveTargetRef[0] = carveTarget;
                carved[0] = tryBreakThroughObstacle(bot, world, carveTarget, "fortify-edge:patch-start-preflight");
            }
        });

        BlockPos end = bot.getBlockPos();
        if (!SkillManager.shouldAbortSkill(bot)
                && (isStandingOnScaffoldBlock(bot, world) || end.getY() > referenceSurfaceY + 1)) {
            boolean scaffoldHold = beginScaffoldEdgeHold(bot, world, end);
            int removed = ScaffoldService.teardownTrackedScaffolds(bot);
            endScaffoldEdgeHold(bot, scaffoldHold);
            if (removed > 0) {
                LOGGER.info("[FortifyPatch] preflight-scaffold-teardown removed={} pos={} refY={} onScaffoldBefore={}",
                        removed,
                        end.toShortString(),
                        referenceSurfaceY,
                        scaffoldHold);
                end = bot.getBlockPos();
            }
        }
        boolean trapLikeEnd = isTrapLikeCell(world, end);
        int endDepth = Math.max(0, referenceSurfaceY - end.getY());
        boolean progress = movedByAtLeast(start, end, 1.0)
                || (!trapLikeEnd && trapLikeStart)
                || (depth > 0 && endDepth < depth);
        LOGGER.info("[FortifyPatch] preflight-end start={} end={} progress={} trapLikeEnd={} depthEnd={} unwedged={} carved={} carveTarget={}",
                start.toShortString(),
                end.toShortString(),
                progress,
                trapLikeEnd,
                endDepth,
                unwedged[0],
                carved[0],
                carveTargetRef[0] != null ? carveTargetRef[0].toShortString() : "none");
    }

    private int probeLocalMoatTargets(ServerPlayerEntity bot, ServerWorld world,
                                      Set<BlockPos> remainingTargets, int maxMines) {
        if (remainingTargets == null || remainingTargets.isEmpty() || maxMines <= 0) {
            return 0;
        }

        int botY = bot.getBlockPos().getY();
        List<BlockPos> nearby = new ArrayList<>();
        for (BlockPos pos : remainingTargets) {
            if (world.getBlockState(pos).isAir()) {
                continue;
            }
            if (pos.getY() > botY + 1) {
                continue;
            }
            if (isWithinMiningReach(bot, pos)) {
                nearby.add(pos);
            }
        }

        nearby.sort(Comparator
                .comparingDouble((BlockPos p) -> bot.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))
                .thenComparing(Comparator.comparingInt(BlockPos::getY).reversed())
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        int mined = 0;
        for (BlockPos pos : nearby) {
            if (mined >= maxMines || SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            if (!remainingTargets.contains(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isAir()) {
                remainingTargets.remove(pos);
                continue;
            }
            LookController.faceBlock(bot, pos);
            sleepQuiet(50);
            if (digBlock(bot, world, pos)) {
                remainingTargets.remove(pos);
                mined++;
            }
        }
        return mined;
    }

    private boolean emergencySnapToSafePosition(ServerPlayerEntity bot, ServerWorld world,
                                                List<BlockPos> perimeterPath, Set<BlockPos> remainingTargets,
                                                String context, long phaseStartMs) {
        BlockPos from = bot.getBlockPos();
        BlockPos candidate = SafePositionService.findSafeNear(world, from, 6);
        if (isBetterSnapTarget(from, candidate)) {
            SafePositionService.snapTo(bot, candidate);
            LOGGER.warn("[FortifyFailsafe] snapped from={} to={} elapsedMs={} context={}",
                    from.toShortString(), candidate.toShortString(), System.currentTimeMillis() - phaseStartMs, context);
            return true;
        }

        if (perimeterPath != null && !perimeterPath.isEmpty()) {
            List<BlockPos> nearestPath = orderMoatDirectTargets(new ArrayList<>(perimeterPath), from);
            for (BlockPos pathPos : nearestPath) {
                BlockPos safe = SafePositionService.findSafeColumn(world, pathPos, -2, 2);
                if (isBetterSnapTarget(from, safe)) {
                    SafePositionService.snapTo(bot, safe);
                    LOGGER.warn("[FortifyFailsafe] snapped from={} to={} elapsedMs={} context={}",
                            from.toShortString(), safe.toShortString(), System.currentTimeMillis() - phaseStartMs, context);
                    return true;
                }
            }
        }

        if (remainingTargets != null && !remainingTargets.isEmpty()) {
            List<BlockPos> orderedTargets = orderMoatDirectTargets(new ArrayList<>(remainingTargets), from);
            for (BlockPos target : orderedTargets) {
                BlockPos safe = SafePositionService.findSafeNear(world, target, 4);
                if (isBetterSnapTarget(from, safe)) {
                    SafePositionService.snapTo(bot, safe);
                    LOGGER.warn("[FortifyFailsafe] snapped from={} to={} elapsedMs={} context={}",
                            from.toShortString(), safe.toShortString(), System.currentTimeMillis() - phaseStartMs, context);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isBetterSnapTarget(BlockPos from, BlockPos candidate) {
        return candidate != null && !candidate.equals(from);
    }

    private BlockPos nearestPathPoint(List<BlockPos> perimeterPath, BlockPos origin) {
        if (perimeterPath == null || perimeterPath.isEmpty() || origin == null) {
            return origin;
        }
        BlockPos nearest = perimeterPath.get(0);
        double best = origin.getSquaredDistance(nearest);
        for (BlockPos point : perimeterPath) {
            double dist = origin.getSquaredDistance(point);
            if (dist < best) {
                best = dist;
                nearest = point;
            }
        }
        return nearest;
    }

    static boolean shouldTriggerDepthRecovery(int botY, int referenceSurfaceY) {
        return FortifyExecutionPolicyUtil.shouldTriggerDepthRecovery(botY, referenceSurfaceY);
    }

    static int moatPass1FailureThreshold(int pass1MovedSteps) {
        return FortifyExecutionPolicyUtil.moatPass1FailureThreshold(
                pass1MovedSteps,
                PASS1_ZERO_MOVEMENT_FAIL_THRESHOLD,
                PASS1_NO_PROGRESS_FAIL_THRESHOLD
        );
    }

    static boolean shouldRetryPass1AfterRecovery(int pass1MinedCount, int retriesUsed, boolean recoveryProgress) {
        return FortifyExecutionPolicyUtil.shouldRetryPass1AfterRecovery(pass1MinedCount, retriesUsed, recoveryProgress, 1);
    }

    static List<BlockPos> orderMoatDirectTargets(List<BlockPos> targets, BlockPos botPos) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<BlockPos> ordered = new ArrayList<>(targets);
        BlockPos origin = botPos == null ? BlockPos.ORIGIN : botPos;
        ordered.sort((a, b) -> FortifyExecutionPolicyUtil.compareDirectTargets(
                origin.getX(), origin.getY(), origin.getZ(),
                a.getX(), a.getY(), a.getZ(),
                b.getX(), b.getY(), b.getZ()
        ));
        return ordered;
    }

    private boolean movedByAtLeast(BlockPos from, BlockPos to, double blocks) {
        return from != null && to != null && from.getSquaredDistance(to) >= (blocks * blocks);
    }

    /**
     * Build an ordered perimeter path around the hull, ~1 block apart.
     * Uses traceEdge (Bresenham) for each hull edge, with terrain Y lookup.
     */
    private List<BlockPos> buildPerimeterPath(FortificationLayout layout, ServerWorld world, SurfaceProfile surfaceProfile) {
        List<BlockPos> path = new ArrayList<>();
        List<WallEdge> edges = layout.edges();
        BlockPos previous = null;

        for (int i = 0; i < edges.size(); i++) {
            WallEdge edge = edges.get(i);
            List<WallPoint> traced = VillageFortificationLayoutService.traceEdge(edge.start(), edge.end());

            double edgeDx = edge.end().x() - edge.start().x();
            double edgeDz = edge.end().z() - edge.start().z();
            double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
            int normalX = 0;
            int normalZ = 0;
            if (edgeLen > 0.001) {
                normalX = (int) Math.round(edgeDz / edgeLen);
                normalZ = (int) Math.round(-edgeDx / edgeLen);
            }

            int start = Math.min(PERIMETER_VERTEX_SKIP, traced.size());
            int endExclusive = (i < edges.size() - 1) ? traced.size() - 1 : traced.size();
            endExclusive = Math.max(start, endExclusive - PERIMETER_VERTEX_SKIP);

            // Skip first/last points near vertices to avoid tower corners,
            // and walk one block outside the wall line.
            for (int j = start; j < endExclusive; j++) {
                WallPoint wp = traced.get(j);
                int walkX = wp.x() + normalX;
                int walkZ = wp.z() + normalZ;
                BlockPos walkPos = choosePerimeterWalkPos(world, surfaceProfile, walkX, walkZ, previous);
                if (path.isEmpty() || !path.get(path.size() - 1).equals(walkPos)) {
                    path.add(walkPos);
                    previous = walkPos;
                }
            }
        }

        return path;
    }

    private BlockPos choosePerimeterWalkPos(ServerWorld world, SurfaceProfile surfaceProfile,
                                            int targetX, int targetZ, BlockPos previous) {
        int baseY = safeSurfaceY(surfaceProfile, world, targetX, targetZ);
        BlockPos desired = new BlockPos(targetX, baseY, targetZ);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int r = 0; r <= PERIMETER_WALK_SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }

                    int x = targetX + dx;
                    int z = targetZ + dz;
                    int candidateBaseY = safeSurfaceY(surfaceProfile, world, x, z);
                    int[] yCandidates = {candidateBaseY, candidateBaseY - 1, candidateBaseY + 1};
                    for (int y : yCandidates) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!canStandAt(world, candidate)) {
                            continue;
                        }

                        int exits = countOpenExits(world, candidate, null);
                        double score = exits * 60.0;
                        score -= desired.getSquaredDistance(candidate) * 8.0;
                        if (previous != null) {
                            score -= previous.getSquaredDistance(candidate) * 2.0;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }

        return best != null ? best : desired;
    }

    /**
     * Move near a dig target. Uses an adjacent standable approach position so
     * pass-2 doesn't try to walk onto the solid block being mined.
     */
    private void moveToDigPosition(ServerCommandSource source, ServerPlayerEntity bot,
                                   ServerWorld world, BlockPos target, SurfaceProfile surfaceProfile) {
        BlockPos approach = chooseDigApproachPosition(bot, world, target, surfaceProfile);
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                approach,
                approach,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult result = MovementService.withoutDoorEscape(
                () -> MovementService.execute(source, bot, plan, false));
        if (!result.success()) {
            // Fast local fallback to avoid long DIRECT-path churn.
            walkTowardBlock(bot, approach, 1_500L);
            LOGGER.debug("moveToDigPosition: movement to {} via {} incomplete: {}",
                    target.toShortString(), approach.toShortString(), result.detail());
        }

        if (bot.squaredDistanceTo(approach.getX() + 0.5, bot.getY(), approach.getZ() + 0.5) > 9.0) {
            boolean unwedged = tryUnwedgeFromTightSpace(
                    source, bot, world, surfaceProfile, target, "moat-approach-fallback");
            if (!unwedged) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2);
                if (safe != null && !safe.equals(bot.getBlockPos())) {
                    walkToTarget(source, bot, safe, 1_000L);
                }
            }
        }
    }

    private BlockPos chooseDigApproachPosition(ServerPlayerEntity bot, ServerWorld world,
                                               BlockPos digTarget, SurfaceProfile surfaceProfile) {
        BlockPos botPos = bot.getBlockPos();
        int[][] offsets = {
                {0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] offset : offsets) {
            int x = digTarget.getX() + offset[0];
            int z = digTarget.getZ() + offset[1];
            int[] yCandidates = {
                    botPos.getY(),
                    digTarget.getY(),
                    digTarget.getY() + 1,
                    safeSurfaceY(surfaceProfile, world, x, z)
            };
            for (int y : yCandidates) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (!canStandAt(world, candidate)) {
                    continue;
                }

                int exits = countOpenExits(world, candidate, null);
                if (!candidate.equals(botPos) && exits < MIN_APPROACH_OPEN_EXITS) {
                    continue;
                }

                boolean canMine = isWithinMiningReachFrom(candidate, digTarget);
                double score = canMine ? 1_000 : 0;
                score += exits * 120.0;
                score -= botPos.getSquaredDistance(candidate) * 5.0;
                score -= Math.abs(candidate.getY() - digTarget.getY()) * 12.0;
                if (candidate.getY() > digTarget.getY() + 1) {
                    score -= 40.0;
                }

                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best != null ? best : digTarget;
    }

    private boolean isWithinMiningReachFrom(BlockPos standPos, BlockPos target) {
        double dx = (standPos.getX() + 0.5) - (target.getX() + 0.5);
        double dy = standPos.getY() - (target.getY() + 0.5);
        double dz = (standPos.getZ() + 0.5) - (target.getZ() + 0.5);
        return (dx * dx + dy * dy + dz * dz) <= 20.25D;
    }

    /**
     * Check reach using bot FEET position, matching MiningTool.mineBlock()'s own gate:
     * {@code bot.squaredDistanceTo(blockCenter) <= 20.25} (reach = 4.5 blocks from feet).
     * This is more generous than the eye-based isWithinReach() for blocks below the bot.
     */
    @Override
    public boolean isWithinMiningReach(ServerPlayerEntity bot, BlockPos pos) {
        return bot.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 20.25;
    }

    // ── Edge building (place phase only) ─────────────────────────

    /**
     * Place all non-dig blocks for a single edge using multi-pass pattern.
     * Dig blocks (MOAT_DIG, EXTERIOR_CLEAR) are handled separately in digAllMoatBlocks().
     */
    private int placeEdgeBlocks(ServerCommandSource source, ServerPlayerEntity bot,
                                 ServerWorld world, List<ProceduralWallBlock> placeBlocks,
                                 WallEdge edge, List<WallPoint> hullVertices,
                                 int referenceSurfaceY,
                                 SurfaceProfile surfaceProfile,
                                 ExecutionPolicy executionPolicy) {
        // Collision check: only count ABOVE-GROUND wall-type blocks for overlap detection.
        int wallBlockCount = 0;
        int occupiedCount = 0;
        for (ProceduralWallBlock block : placeBlocks) {
            WallBlockType t = block.type();
            if (t == WallBlockType.FOUNDATION || t == WallBlockType.WALL
                    || t == WallBlockType.WALL_TOP_SLAB || t == WallBlockType.MERLON
                    || t == WallBlockType.GATEHOUSE_PILLAR || t == WallBlockType.GATEHOUSE_LINTEL
                    || t == WallBlockType.GATEHOUSE_CAP) {
                wallBlockCount++;
                BlockState existing = world.getBlockState(block.worldPos());
                if (!existing.isAir() && !existing.isReplaceable()) {
                    occupiedCount++;
                }
            }
        }
        if (wallBlockCount > 0 && occupiedCount > wallBlockCount * 0.4) {
            LOGGER.warn("Edge {} has {}% overlap with existing structures, skipping",
                    edge.index(), (occupiedCount * 100) / wallBlockCount);
            showOverhead(bot, "Skipping edge #" + edge.index() + " (structure overlap)");
            return 0;
        }

        // Hybrid sort: segment along edge, then local bottom-up order.
        double eDx = edge.end().x() - edge.start().x();
        double eDz = edge.end().z() - edge.start().z();
        double eLen = Math.sqrt(eDx * eDx + eDz * eDz);
        double dX = eLen > 0.001 ? eDx / eLen : 1;
        double dZ = eLen > 0.001 ? eDz / eLen : 0;
        final double segSize = 8.0;

        placeBlocks.sort(Comparator
                .comparingInt((ProceduralWallBlock b) -> segmentBucket(edge, dX, dZ, segSize, b.worldPos()))
                .thenComparingInt(b -> b.worldPos().getY())
                .thenComparingInt(b -> placePriority(b.type())));

        Map<Integer, List<ProceduralWallBlock>> blocksBySegment = new TreeMap<>();
        for (ProceduralWallBlock block : placeBlocks) {
            int bucket = segmentBucket(edge, dX, dZ, segSize, block.worldPos());
            blocksBySegment.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(block);
        }

        int totalPlaced = 0;
        int segmentNoProgressStreak = 0;
        int segmentOrdinal = 0;
        long edgeStartMs = System.currentTimeMillis();
        int edgeMidX = (edge.start().x() + edge.end().x()) / 2;
        int edgeMidZ = (edge.start().z() + edge.end().z()) / 2;
        BlockPos edgeAnchor = new BlockPos(edgeMidX, safeSurfaceY(surfaceProfile, world, edgeMidX, edgeMidZ), edgeMidZ);

        for (Map.Entry<Integer, List<ProceduralWallBlock>> segmentEntry : blocksBySegment.entrySet()) {
            segmentOrdinal++;
            final int currentSegmentOrdinal = segmentOrdinal;
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            if (countBuildingBlocks(bot) == 0) {
                break;
            }

            long elapsedEdgeMs = System.currentTimeMillis() - edgeStartMs;
            long remainingBudgetMs = Math.max(0L, executionPolicy.timeBudgetMs() - elapsedEdgeMs);
            if (remainingBudgetMs <= 0L) {
                LOGGER.info("Edge {} timed out before segment {} started", edge.index(), segmentOrdinal);
                break;
            }

            List<ProceduralWallBlock> segmentBlocks = segmentEntry.getValue();

            // Navigate to far segments — use walkToTarget (not DIRECT mode, which mines through walls)
            if (!segmentBlocks.isEmpty()) {
                BlockPos firstBlockPos = segmentBlocks.get(0).worldPos();
                double distToSegmentSq = bot.getBlockPos().getSquaredDistance(firstBlockPos);
                if (distToSegmentSq > 15 * 15) {
                    BlockPos approachPos = firstBlockPos.withY(
                            safeSurfaceY(surfaceProfile, world, firstBlockPos.getX(), firstBlockPos.getZ()));
                    runWithFortifyEdgeNavScope(bot, world, "fortify-edge:segment-close", approachPos,
                            () -> walkToTarget(source, bot, approachPos, 5_000L, "fortify-edge:segment-close"));
                }
            }

            Map<BlockPos, ProceduralWallBlock> blockMap = new HashMap<>();
            Set<BlockPos> remaining = new LinkedHashSet<>();
            List<PlacementTarget> targets = new ArrayList<>(segmentBlocks.size());
            Set<BlockPos> scaffoldFailedPositions = new HashSet<>();

            for (ProceduralWallBlock block : segmentBlocks) {
                // Pre-filter: skip blocks already satisfied (avoids BLOCKED_BY_SOLID waste)
                BlockState current = world.getBlockState(block.worldPos());
                if (isPlannedBlockSatisfied(block, current)) {
                    continue;
                }
                remaining.add(block.worldPos());
                blockMap.put(block.worldPos(), block);
                targets.add(new PlacementTarget(
                        block.worldPos(),
                        block.state(),
                        block.type().name().contains("TOWER")
                                ? PlacementTarget.TargetKind.FORTIFY_TOWER
                                : PlacementTarget.TargetKind.FORTIFY_EDGE,
                        placePriority(block.type()),
                        "edge:" + edge.index() + ":segment:" + currentSegmentOrdinal
                ));
            }

            // Sort targets bottom-up so lower blocks (supports) are placed before upper blocks
            targets.sort(Comparator.comparingInt((PlacementTarget t) -> t.pos().getY())
                    .thenComparingInt(PlacementTarget::priorityBand));

            int[] repositionAttempt = new int[]{0};
            ScaffoldService.ScaffoldSession scaffoldSession = ScaffoldService.beginSession(bot);
            long segmentBudgetMs = Math.min(EDGE_SEGMENT_TIME_BUDGET_MS, remainingBudgetMs);
            ExecutionPolicy segmentPolicy = new ExecutionPolicy(
                    EDGE_SEGMENT_PASS_CAP,
                    2,  // fail fast per target — scaffold escalation handles NO_LOS
                    1,
                    segmentBudgetMs
            );

            ConstructionTaskSpec spec = new ConstructionTaskSpec(
                    "fortify-edge:" + edge.index() + ":segment:" + currentSegmentOrdinal,
                    world,
                    bot,
                    source,
                    targets,
                    segmentPolicy,
                    new ConstructionTaskSpec.SupportPolicy(true, true, MAX_SCAFFOLD_HEIGHT),
                    (target, pass) -> {
                        if (isWithinReach(bot, target.pos())) {
                            return ConstructionRecoveryService.RecoveryResult.success(false);
                        }
                        if (countBuildingBlocks(bot) == 0) {
                            return ConstructionRecoveryService.RecoveryResult.failure(FailureReason.NO_MATERIAL, false);
                        }
                        BlockPos beforePos = bot.getBlockPos();
                        double beforeDistSq = beforePos.getSquaredDistance(target.pos());
                        boolean unwedged = false;
                        if (shouldAttemptReachUnwedge(world, bot, target.pos(), pass)) {
                            unwedged = tryUnwedgeFromTightSpace(
                                    source, bot, world, surfaceProfile, edgeAnchor,
                                    "edge-" + edge.index() + ":reach-pass-" + pass);
                        }
                        int terrainY = safeSurfaceY(surfaceProfile, world, target.pos().getX(), target.pos().getZ());
                        int heightAboveGround = target.pos().getY() - terrainY;
                        boolean canReach = ensureCanReachBlockWithEffort(
                                source,
                                bot,
                                world,
                                target.pos(),
                                heightAboveGround,
                                pass,
                                referenceSurfaceY,
                                scaffoldFailedPositions
                        );
                        BlockPos afterPos = bot.getBlockPos();
                        double afterDistSq = afterPos.getSquaredDistance(target.pos());
                        double movedSq = beforePos.getSquaredDistance(afterPos);
                        boolean progress = unwedged
                                || movedSq >= 1.0D
                                || (beforeDistSq - afterDistSq) >= 1.0D;
                        return canReach
                                ? ConstructionRecoveryService.RecoveryResult.success(progress)
                                : ConstructionRecoveryService.RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
                    },
                    (target, pass) -> {
                        if (countBuildingBlocks(bot) == 0) {
                            return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.NO_MATERIAL);
                        }
                        boolean scaffoldSneak = beginScaffoldEdgeHold(bot, world, target.pos());
                        try {
                            if (shouldAvoidSelfTrapPlacement(world, bot, target.pos())) {
                                int removed = clearBlockingScaffoldsNearBot(bot, world, 1);
                                if (removed > 0) {
                                    return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.MOVEMENT_FAILED, true);
                                }
                                boolean moved = tryUnwedgeFromTightSpace(
                                        source, bot, world, surfaceProfile, edgeAnchor,
                                        "edge-" + edge.index() + ":placement-guard-pass-" + pass);
                                return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.MOVEMENT_FAILED, moved);
                            }
                            BotActions.PlaceResult placed = tryPlaceBlock(bot, world, target.pos(), target.desiredState());
                            if (placed.success()) {
                                remaining.remove(target.pos());
                                sleepQuiet(BLOCK_PLACE_DELAY_MS);
                                return ConstructionTaskSpec.PlacementOutcome.ok();
                            }
                            if (placed.reason() != null && placed.reason().startsWith("no-solid-support")) {
                                boolean filled = fillGroundUnder(bot, world, target.pos());
                                return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.NO_SUPPORT, filled);
                            }
                            // NO_LOS recovery: face the target and retry; for elevated blocks
                            // skip expensive perpendicular walks — scaffold escalation handles those
                            if (placed.reason() != null && placed.reason().startsWith("no-line-of-sight")) {
                                LookController.faceBlock(bot, target.pos());
                                sleepQuiet(50);
                                BotActions.PlaceResult retry = tryPlaceBlock(bot, world, target.pos(), target.desiredState());
                                if (retry.success()) {
                                    remaining.remove(target.pos());
                                    sleepQuiet(BLOCK_PLACE_DELAY_MS);
                                    return ConstructionTaskSpec.PlacementOutcome.ok();
                                }
                                // Only try perpendicular approach for blocks near bot Y level
                                // (elevated blocks need scaffolding, not angle changes)
                                boolean elevated = target.pos().getY() > bot.getBlockPos().getY() + 1;
                                if (!elevated) {
                                    BlockPos bp = bot.getBlockPos();
                                    double dx = target.pos().getX() - bp.getX();
                                    double dz = target.pos().getZ() - bp.getZ();
                                    Direction[] perpDirs = Math.abs(dx) >= Math.abs(dz)
                                            ? new Direction[]{Direction.NORTH, Direction.SOUTH}
                                            : new Direction[]{Direction.EAST, Direction.WEST};
                                    for (Direction dir : perpDirs) {
                                        BlockPos sidePos = target.pos().offset(dir, 2).withY(bp.getY());
                                        walkTowardBlock(bot, sidePos, 400L);
                                        LookController.faceBlock(bot, target.pos());
                                        sleepQuiet(50);
                                        BotActions.PlaceResult sideRetry = tryPlaceBlock(bot, world, target.pos(), target.desiredState());
                                        if (sideRetry.success()) {
                                            remaining.remove(target.pos());
                                            sleepQuiet(BLOCK_PLACE_DELAY_MS);
                                            return ConstructionTaskSpec.PlacementOutcome.ok();
                                        }
                                    }
                                }
                            }
                            return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.fromPlaceReason(placed.reason()));
                        } finally {
                            endScaffoldEdgeHold(bot, scaffoldSneak);
                        }
                    },
                    progress -> {
                        remaining.removeIf(pos -> {
                            ProceduralWallBlock planned = blockMap.get(pos);
                            return planned != null && isPlannedBlockSatisfied(planned, world.getBlockState(pos));
                        });
                        escapeIfInHole(bot, world, referenceSurfaceY);
                    },
                    (progress, noProgressStreak) -> {
                        if (noProgressStreak >= segmentPolicy.noProgressPasses()) {
                            LOGGER.debug("Edge {} segment {} reached no-progress threshold={}",
                                    edge.index(), currentSegmentOrdinal, noProgressStreak);
                            return;
                        }
                        if (clearBlockingScaffoldsNearBot(bot, world, 1) > 0) {
                            return;
                        }
                        if (repositionAttempt[0] >= MAX_REPOSITION_ATTEMPTS_PER_BATCH) {
                            BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 3);
                            if (safe != null && !safe.equals(bot.getBlockPos())) {
                                runWithFortifyEdgeNavScope(bot, world, "fortify-edge:safe-nudge", safe,
                                        () -> walkToTarget(source, bot, safe, 1_200L, "fortify-edge:safe-nudge"));
                            }
                            repositionAttempt[0] = 0;
                            return;
                        }
                        if (tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, edgeAnchor,
                                "edge-" + edge.index() + ":no-progress-" + noProgressStreak)) {
                            return;
                        }
                        if (edgeAnchor != null && tryWideArcReachReposition(source, bot, world, edgeAnchor)) {
                            return;
                        }
                        repositionForEdge(source, bot, world, edge, surfaceProfile, repositionAttempt[0]);
                        repositionAttempt[0]++;
                    },
                    scaffoldSession,
                    false,
                    Set.of()
            );

            ExecutionReport report = ConstructionExecutionService.execute(spec);
            totalPlaced += report.placedCount();
            LOGGER.info("[Fortify] edge={} segment={}/{} placed={} remaining={} failures={}",
                    edge.index(),
                    segmentOrdinal,
                    blocksBySegment.size(),
                    report.placedCount(),
                    report.remainingCount(),
                    report.remainingByReason());

            // ── Scaffold escalation: if LOS/reach failures dominate, pillar up and retry ──
            int noLosCount = report.remainingByReason().getOrDefault(FailureReason.NO_LOS, 0);
            int oorCount = report.remainingByReason().getOrDefault(FailureReason.OUT_OF_REACH, 0);
            int escalatableCount = noLosCount + oorCount;
            if (!remaining.isEmpty() && escalatableCount > 0 && escalatableCount >= remaining.size() / 2
                    && !SkillManager.shouldAbortSkill(bot) && countBuildingBlocks(bot) > 0) {
                int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
                int depth = terrainY - bot.getBlockPos().getY();
                if (entombmentHelper.shouldPreferEntombmentEscape(world, bot.getBlockPos(), "fortify-edge:segment-scaffold")
                        || (depth > 0 && depth <= 3 && isTrapLikeCell(world, bot.getBlockPos()) && entombmentHelper.isAdjacentToCurrentFortificationHull(bot.getBlockPos()))) {
                    LOGGER.info("[Fortify] Edge {} seg {}: suppress scaffold escalation entombed-local-edge pos={} depth={} failures={}",
                            edge.index(), currentSegmentOrdinal, bot.getBlockPos().toShortString(), depth, report.remainingByReason());
                } else {
                LOGGER.info("[Fortify] Edge {} seg {}: NO_LOS={} OOR={} escalatable={}/{} — scaffold escalation",
                        edge.index(), currentSegmentOrdinal, noLosCount, oorCount, escalatableCount, remaining.size());
                totalPlaced += attemptScaffoldEscalation(
                        source, bot, world, remaining, blockMap, surfaceProfile, edge, currentSegmentOrdinal);
                }
            }

            if (report.placedCount() == 0 && report.remainingCount() > 0) {
                int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
                int depth = terrainY - bot.getBlockPos().getY();
                LOGGER.info("[FortifyEdge] zero-progress segment edge={} seg={}/{} pos={} trapLike={} depth={} failures={}",
                        edge.index(), segmentOrdinal, blocksBySegment.size(), bot.getBlockPos().toShortString(),
                        isTrapLikeCell(world, bot.getBlockPos()), depth, report.remainingByReason());
                segmentNoProgressStreak++;
                if (shouldStopAfterNoProgressSegments(segmentNoProgressStreak, EDGE_SEGMENT_NO_PROGRESS_STOP)) {
                    LOGGER.info("Edge {} stopping after {} zero-progress segments",
                            edge.index(), segmentNoProgressStreak);
                    break;
                }
            } else {
                segmentNoProgressStreak = 0;
            }
        }

        return totalPlaced;
    }

    /**
     * Scaffold escalation for edge segments where LOS/reach failures dominate.
     * <p>Strategy order:
     * <ol>
     *   <li>Lateral repositioning for ground-level targets (cheaper than scaffolding)</li>
     *   <li>Multi-candidate scaffold position selection with overhead clearance validation</li>
     *   <li>Pillar up from best position, place from elevation, tear down</li>
     * </ol>
     */
    private int attemptScaffoldEscalation(
            ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world,
            Set<BlockPos> remaining, Map<BlockPos, ProceduralWallBlock> blockMap,
            SurfaceProfile surfaceProfile, WallEdge edge, int segmentOrdinal) {

        if (remaining.isEmpty()) return 0;

        // Compute centroid of remaining blocks
        int sumX = 0, sumZ = 0, highestY = Integer.MIN_VALUE, lowestY = Integer.MAX_VALUE;
        for (BlockPos pos : remaining) {
            sumX += pos.getX();
            sumZ += pos.getZ();
            if (pos.getY() > highestY) highestY = pos.getY();
            if (pos.getY() < lowestY) lowestY = pos.getY();
        }
        int cx = sumX / remaining.size();
        int cz = sumZ / remaining.size();

        // Build ProceduralWallBlock list for countReachableWithLOS scoring
        List<ProceduralWallBlock> remainingBlocks = new ArrayList<>();
        for (BlockPos pos : remaining) {
            ProceduralWallBlock b = blockMap.get(pos);
            if (b != null) remainingBlocks.add(b);
        }

        FortifyNavRuntimeScope priorScope = beginFortifyNavScope(
                "fortify-edge:scaffold-escalation", null, null, new BlockPos(cx, bot.getBlockPos().getY(), cz), false, false);
        try {
            int placed = 0;

            // ── Phase 1: Lateral repositioning for ground-level targets ──────────
            // If all remaining targets are near bot eye level, try placing from
            // different horizontal angles BEFORE scaffolding — pillaring up often
            // worsens the LOS angle for support faces at/below the bot.
            int currentY = bot.getBlockPos().getY();
            boolean targetsNearGroundLevel = highestY <= currentY + 2;
            if (targetsNearGroundLevel && !remaining.isEmpty()) {
                BlockPos botPos = bot.getBlockPos();
                double dxC = cx - botPos.getX();
                double dzC = cz - botPos.getZ();
                // Try positions perpendicular to the bot→centroid axis, 2-3 blocks out
                Direction[] perpDirs;
                if (Math.abs(dxC) >= Math.abs(dzC)) {
                    perpDirs = new Direction[]{Direction.NORTH, Direction.SOUTH};
                } else {
                    perpDirs = new Direction[]{Direction.EAST, Direction.WEST};
                }

                for (Direction dir : perpDirs) {
                    if (remaining.isEmpty() || SkillManager.shouldAbortSkill(bot)) break;
                    BlockPos lateralPos = new BlockPos(cx, currentY, cz).offset(dir, 2);
                    walkToTarget(source, bot, lateralPos, 2_000L, "fortify-edge:lateral-reposition");
                    if (SkillManager.shouldAbortSkill(bot)) break;

                    for (BlockPos pos : new ArrayList<>(remaining)) {
                        if (SkillManager.shouldAbortSkill(bot)) break;
                        if (countBuildingBlocks(bot) == 0) break;
                        ProceduralWallBlock block = blockMap.get(pos);
                        if (block == null) continue;
                        if (isPlannedBlockSatisfied(block, world.getBlockState(pos))) {
                            remaining.remove(pos);
                            continue;
                        }
                        if (!isWithinReach(bot, pos)) continue;
                        LookController.faceBlock(bot, pos);
                        sleepQuiet(50);
                        BotActions.PlaceResult result = tryPlaceBlock(bot, world, pos, block.state());
                        if (result.success()) {
                            remaining.remove(pos);
                            placed++;
                            sleepQuiet(BLOCK_PLACE_DELAY_MS);
                        }
                    }
                }

                if (placed > 0 || remaining.isEmpty()) {
                    LOGGER.info("[Fortify] Edge {} seg {}: lateral-reposition placed={}/{} remaining={}",
                            edge.index(), segmentOrdinal, placed, remainingBlocks.size(), remaining.size());
                    if (remaining.isEmpty()) return placed;
                }
            }

            // ── Phase 2: Multi-candidate scaffold position selection ─────────────
            // Generate several candidate positions and score them by overhead clearance
            // and predicted LOS coverage from the elevated eye position.
            BlockPos botPos = bot.getBlockPos();
            int dx = botPos.getX() - cx;
            int dz = botPos.getZ() - cz;
            double len = Math.sqrt(dx * dx + dz * dz);
            int botOffX, botOffZ;
            if (len > 0.5) {
                botOffX = (int) Math.round(2.0 * dx / len);
                botOffZ = (int) Math.round(2.0 * dz / len);
            } else {
                botOffX = 0;
                botOffZ = 2;
            }

            int groundY = safeSurfaceY(surfaceProfile, world, cx, cz);
            int neededY = Math.max(groundY, highestY - 2);
            int pillarSteps = Math.max(2, Math.min(MAX_SCAFFOLD_HEIGHT, neededY - groundY));

            // Build candidate list: toward-bot, plus 4 cardinal offsets from centroid
            List<BlockPos> candidates = new ArrayList<>();
            candidates.add(new BlockPos(cx + botOffX, groundY, cz + botOffZ));
            for (Direction dir : Direction.Type.HORIZONTAL) {
                int candX = cx + dir.getOffsetX() * 2;
                int candZ = cz + dir.getOffsetZ() * 2;
                int candY = safeSurfaceY(surfaceProfile, world, candX, candZ);
                BlockPos cand = new BlockPos(candX, candY, candZ);
                if (!candidates.contains(cand)) candidates.add(cand);
            }

            // Score each candidate: overhead clearance (required), then LOS coverage from
            // simulated elevated position, then distance from bot.
            BlockPos bestCandidate = null;
            int bestScore = -1;
            for (BlockPos cand : candidates) {
                // Check overhead clearance for pillarSteps blocks above
                boolean overheadClear = true;
                int candPillar = Math.max(2, Math.min(MAX_SCAFFOLD_HEIGHT, Math.max(cand.getY(), highestY - 2) - cand.getY()));
                for (int dy = 1; dy <= candPillar + 1; dy++) {
                    BlockPos above = cand.up(dy);
                    net.minecraft.block.BlockState st = world.getBlockState(above);
                    if (!st.isAir() && !st.isReplaceable()) {
                        overheadClear = false;
                        break;
                    }
                }
                if (!overheadClear) continue;

                // Score by LOS coverage from simulated elevated eye position
                BlockPos elevatedStand = cand.up(candPillar);
                int losCount = countReachableWithLOS(world, bot, elevatedStand, remainingBlocks);
                // Tie-break by proximity to bot (closer = fewer walk ticks)
                int distPenalty = (int) (cand.getSquaredDistance(botPos) / 10.0);
                int score = losCount * 100 - distPenalty;
                if (losCount > bestScore || (losCount == bestScore && score > (bestScore * 100))) {
                    bestCandidate = cand;
                    bestScore = losCount;
                }
            }

            if (bestCandidate == null) {
                if (Frens.CONFIG.isFortifyForcePlaceEnabled()) {
                    int forcePlaced = 0;
                    for (BlockPos pos : new ArrayList<>(remaining)) {
                        if (countBuildingBlocks(bot) == 0) break;
                        ProceduralWallBlock block = blockMap.get(pos);
                        if (block == null) continue;
                        if (isPlannedBlockSatisfied(block, world.getBlockState(pos))) {
                            remaining.remove(pos);
                            continue;
                        }
                        Item targetItem = block.state().getBlock().asItem();
                        List<Item> materials = List.of(targetItem, Items.COBBLESTONE, Items.STONE);
                        BotActions.PlaceResult result = BotActions.forceReplaceBlock(bot, pos, materials);
                        if (result.success()) {
                            remaining.remove(pos);
                            placed++;
                            forcePlaced++;
                        }
                    }
                    LOGGER.info("[Fortify] Edge {} seg {}: force-placed {} blocks (non-vanilla, no LOS candidate found)",
                            edge.index(), segmentOrdinal, forcePlaced);
                    return placed;
                }
                LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation — no candidate with clear overhead",
                        edge.index(), segmentOrdinal);
                return placed;
            }

            BlockPos scaffoldBase = bestCandidate;
            int scaffoldPillar = Math.max(2, Math.min(MAX_SCAFFOLD_HEIGHT, Math.max(scaffoldBase.getY(), highestY - 2) - scaffoldBase.getY()));

            // Walk to scaffold base
            walkToTarget(source, bot, scaffoldBase, 3_000L, "fortify-edge:scaffold-escalation");
            if (isTrapLikeCell(world, bot.getBlockPos())) {
                boolean nudged = tryPostCarvePocketEscapeToward(bot, world, scaffoldBase);
                if (nudged) {
                    LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation post-carve-escape nudged=true pos={}",
                            edge.index(), segmentOrdinal, bot.getBlockPos().toShortString());
                }
            }
            if (SkillManager.shouldAbortSkill(bot)) return placed;

            LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation at ({},{},{}) pillar={} highestTarget={} losScore={}",
                    edge.index(), segmentOrdinal, scaffoldBase.getX(), scaffoldBase.getY(), scaffoldBase.getZ(),
                    scaffoldPillar, highestY, bestScore);

            ScaffoldService.ScaffoldSession elevSession = ScaffoldService.beginSession(bot);
            boolean pillared = ScaffoldService.pillarToY(elevSession, bot.getBlockPos().getY() + scaffoldPillar);

            if (pillared || bot.getBlockPos().getY() > currentY) {
                // Sort remaining by distance to bot so we place nearest first
                List<BlockPos> sortedRemaining = new ArrayList<>(remaining);
                sortedRemaining.sort(Comparator.comparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));

                for (BlockPos pos : sortedRemaining) {
                    if (SkillManager.shouldAbortSkill(bot)) break;
                    if (countBuildingBlocks(bot) == 0) break;

                    ProceduralWallBlock block = blockMap.get(pos);
                    if (block == null) continue;

                    // Check if already satisfied
                    if (isPlannedBlockSatisfied(block, world.getBlockState(pos))) {
                        remaining.remove(pos);
                        continue;
                    }

                    // Check reach from elevated position
                    if (!isWithinReach(bot, pos)) continue;

                    LookController.faceBlock(bot, pos);
                    sleepQuiet(60);
                    BotActions.PlaceResult result = tryPlaceBlock(bot, world, pos, block.state());
                    if (result.success()) {
                        remaining.remove(pos);
                        placed++;
                        sleepQuiet(BLOCK_PLACE_DELAY_MS);
                    }
                }

                LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation placed={}/{}",
                        edge.index(), segmentOrdinal, placed, remainingBlocks.size());

                // Force-place any remaining blocks that vanilla placement couldn't reach
                if (!remaining.isEmpty() && Frens.CONFIG.isFortifyForcePlaceEnabled()) {
                    int forcePlaced = 0;
                    for (BlockPos pos : new ArrayList<>(remaining)) {
                        if (countBuildingBlocks(bot) == 0) break;
                        ProceduralWallBlock block = blockMap.get(pos);
                        if (block == null) continue;
                        if (isPlannedBlockSatisfied(block, world.getBlockState(pos))) {
                            remaining.remove(pos);
                            continue;
                        }
                        Item targetItem = block.state().getBlock().asItem();
                        List<Item> materials = List.of(targetItem, Items.COBBLESTONE, Items.STONE);
                        BotActions.PlaceResult result = BotActions.forceReplaceBlock(bot, pos, materials);
                        if (result.success()) {
                            remaining.remove(pos);
                            placed++;
                            forcePlaced++;
                        }
                    }
                    if (forcePlaced > 0) {
                        LOGGER.info("[Fortify] Edge {} seg {}: force-placed {} remaining blocks (non-vanilla, post-scaffold)",
                                edge.index(), segmentOrdinal, forcePlaced);
                    }
                }
            } else {
                LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation pillar failed (Y unchanged)",
                        edge.index(), segmentOrdinal);
                entombmentHelper.noteEntombmentScaffoldFailure(world, bot.getBlockPos(), "fortify-edge:scaffold-escalation");
            }

            // Tear down scaffold
            int tornDown = ScaffoldService.teardown(elevSession, Collections.emptySet());
            LOGGER.debug("[Fortify] Edge {} seg {}: scaffold teardown removed={}",
                    edge.index(), segmentOrdinal, tornDown);

            return placed;
        } finally {
            endFortifyNavScope(bot, world, priorScope);
        }
    }

    /** Priority order for placing blocks (lower = placed first). */
    private static int placePriority(WallBlockType type) {
        return switch (type) {
            case MOAT_FLOOR -> 0;
            case MOAT_INNER_FACE -> 1;
            case FOUNDATION, TOWER_BASE -> 2;
            case WALL, TOWER_WALL, GATEHOUSE_PILLAR -> 3;
            case WALL_TOP_SLAB, TOWER_CAP, GATEHOUSE_CAP, GATEHOUSE_LINTEL -> 4;
            case MERLON -> 5;
            case MOAT_OVERHANG -> 6;
            case MOAT_DIG, EXTERIOR_CLEAR -> 99; // should not be in place list
        };
    }

    /**
     * Build a list of blocks (used by tower building and patch mode). Returns blocks placed.
     * Separates dig blocks from place blocks, executes dig phase first.
     * Skips blocks that are too far away to avoid wasting time on unreachable blocks.
     * Uses fast bail movement — we've already navigated to the area before calling this.
     */
    private int buildBlockList(ServerCommandSource source, ServerPlayerEntity bot,
                                ServerWorld world, List<ProceduralWallBlock> blocks) {
        // Separate dig vs place
        List<ProceduralWallBlock> digBlocks = new ArrayList<>();
        List<ProceduralWallBlock> placeBlocks = new ArrayList<>();
        for (ProceduralWallBlock b : blocks) {
            if (!isActiveFortifyBlock(b)) {
                continue;
            }
            if (b.type() == WallBlockType.MOAT_DIG || b.type() == WallBlockType.EXTERIOR_CLEAR) {
                digBlocks.add(b);
            } else {
                placeBlocks.add(b);
            }
        }

        int totalPlaced = 0;

        // Dig phase (top-down)
        digBlocks.sort(Comparator.comparingInt((ProceduralWallBlock b) -> -b.worldPos().getY()));
        for (ProceduralWallBlock block : digBlocks) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            BlockState current = world.getBlockState(block.worldPos());
            if (current.isAir()) { totalPlaced++; continue; }

            double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(block.worldPos()));
            if (distSq > 400) continue;
            if (distSq > 25) walkTowardBlock(bot, block.worldPos(), 1500L);

            if (digBlock(bot, world, block.worldPos())) totalPlaced++;
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
        }

        // Place phase (layer-by-layer: Y ascending, then priority)
        placeBlocks.sort(Comparator.comparingInt((ProceduralWallBlock b) -> b.worldPos().getY())
                .thenComparingInt(b -> placePriority(b.type())));

        int consecutiveFails = 0;
        BlockPos lastBlockListPos = bot.getBlockPos();
        int blockListStuck = 0;
        for (ProceduralWallBlock block : placeBlocks) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;

            BlockState current = world.getBlockState(block.worldPos());
            if (!current.isAir() && !current.isReplaceable()) continue;

            double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(block.worldPos()));
            if (distSq > 400) continue;

            // Stuck detection — bail fast if bot hasn't moved
            BlockPos curPos = bot.getBlockPos();
            if (curPos.equals(lastBlockListPos)) {
                blockListStuck++;
                if (blockListStuck >= 4) break; // stop wasting time
            } else {
                blockListStuck = 0;
                lastBlockListPos = curPos;
            }

            if (distSq > 25) walkTowardBlock(bot, block.worldPos(), 1500L);

            int terrainY = VillageFortificationLayoutService.terrainY(world, block.worldPos().getX(), block.worldPos().getZ());
            int height = block.worldPos().getY() - terrainY;
            boolean reachable = ensureCanReachBlockWithEffort(source, bot, world, block.worldPos(), height, 1);
            if (!reachable) {
                consecutiveFails++;
                if (consecutiveFails >= 6) break;
                continue;
            }

            BotActions.PlaceResult placed = tryPlaceBlock(bot, world, block.worldPos(), block.state());
            if (placed.success()) {
                totalPlaced++;
                consecutiveFails = 0;
            } else {
                consecutiveFails++;
                if (consecutiveFails >= 6) break;
            }
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
        }
        return totalPlaced;
    }

    // ── Block placement ─────────────────────────────────────────

    private BotActions.PlaceResult tryPlaceBlock(ServerPlayerEntity bot, ServerWorld world,
                                                  BlockPos pos, BlockState targetState) {
        BlockState current = world.getBlockState(pos);
        if (current.equals(targetState)) {
            return new BotActions.PlaceResult(true, null);
        }

        // Air target (MOAT_DIG, EXTERIOR_CLEAR): these are handled by dig phase, not place
        if (targetState.isAir()) {
            return new BotActions.PlaceResult(true, null);
        }

        Item targetItem = targetState.getBlock().asItem();
        List<Item> candidates = layoutHelper.buildCandidateList(targetItem);

        boolean hasAny = false;
        for (Item candidate : candidates) {
            for (int i = 0; i < bot.getInventory().size(); i++) {
                if (bot.getInventory().getStack(i).isOf(candidate)) {
                    hasAny = true;
                    break;
                }
            }
            if (hasAny) break;
        }
        if (!hasAny) {
            return new BotActions.PlaceResult(false, "no-material");
        }

        return BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, candidates);
    }

    private boolean fillGroundUnder(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        List<BlockPos> toFill = new ArrayList<>();
        BlockPos cursor = pos.down();
        for (int i = 0; i < 4; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable()) break;
            toFill.add(cursor);
            cursor = cursor.down();
        }
        if (toFill.isEmpty()) return false;

        BlockState foundation = world.getBlockState(toFill.get(toFill.size() - 1).down());
        if (foundation.isAir()) return false;

        boolean anyPlaced = false;
        List<Item> fillBlocks = List.of(Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE);
        Set<BlockPos> scaffoldMemory = ScaffoldService.getScaffoldMemory(bot);
        for (int i = toFill.size() - 1; i >= 0; i--) {
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, toFill.get(i), Direction.UP, fillBlocks);
            if (!result.success()) break;
            scaffoldMemory.add(toFill.get(i).toImmutable());
            anyPlaced = true;
        }
        return anyPlaced;
    }

    // ── Block digging ────────────────────────────────────────────

    /** Blocks that must never be dug. */
    private static final Set<net.minecraft.block.Block> DIG_BLACKLIST = Set.of(
            net.minecraft.block.Blocks.BEDROCK,
            net.minecraft.block.Blocks.LECTERN
    );

    /**
     * Dig (mine) a single block at pos. Skips air, bedrock, doors, beds, and
     * blocks with negative hardness (unbreakable). Returns true if the block
     * was successfully removed or was already air.
     */
    @Override
    public boolean digBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (DIG_BLACKLIST.contains(state.getBlock())) return false;
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return false;
        if (state.getBlock() instanceof net.minecraft.block.BedBlock) return false;
        if (state.getBlock() instanceof FenceBlock) return false;
        if (state.getBlock() instanceof FenceGateBlock) return false;
        if (state.getBlock() instanceof WallBlock) return false;
        if (state.getBlock() instanceof PaneBlock) return false;
        if (state.getBlock() instanceof TrapdoorBlock) return false;
        if (state.getHardness(world, pos) < 0) return false;

        // Neighbor-based village structure protection: if 2+ adjacent blocks are village
        // structure blocks, this block is likely part of a building and should be preserved.
        if (isAdjacentToVillageStructure(world, pos, 2)) return false;

        try {
            CompletableFuture<String> result = MiningTool.mineBlock(bot, pos);
            String outcome = awaitMiningOutcome(result, () -> SkillManager.shouldAbortSkill(bot),
                    DIG_RESULT_TIMEOUT_MS, DIG_RESULT_POLL_MS);
            if (outcome == null) {
                return false;
            }
            return outcome != null && !outcome.startsWith("⚠️");
        } catch (Exception e) {
            LOGGER.debug("digBlock failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
    }

    private boolean digTemporarySurfaceEscapeRampBlock(ServerPlayerEntity bot, ServerWorld world,
                                                       BlockPos pos, Set<Item> allowedRampItems) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;

        if (allowedRampItems != null && !allowedRampItems.isEmpty()) {
            Item item = state.getBlock().asItem();
            if (!allowedRampItems.contains(item)) {
                return false;
            }
        }

        if (DIG_BLACKLIST.contains(state.getBlock())) return false;
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return false;
        if (state.getBlock() instanceof net.minecraft.block.BedBlock) return false;
        if (state.getBlock() instanceof FenceBlock) return false;
        if (state.getBlock() instanceof FenceGateBlock) return false;
        if (state.getBlock() instanceof WallBlock) return false;
        if (state.getBlock() instanceof PaneBlock) return false;
        if (state.getBlock() instanceof TrapdoorBlock) return false;
        if (state.getHardness(world, pos) < 0) return false;

        try {
            CompletableFuture<String> result = MiningTool.mineBlock(bot, pos);
            String outcome = awaitMiningOutcome(result, () -> SkillManager.shouldAbortSkill(bot),
                    DIG_RESULT_TIMEOUT_MS, DIG_RESULT_POLL_MS);
            if (outcome == null) {
                return false;
            }
            return !outcome.startsWith("⚠️");
        } catch (Exception e) {
            LOGGER.debug("digTemporarySurfaceEscapeRampBlock failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
    }

    /**
     * Scans near the fortification perimeter for leftover scaffold pillars and mines them.
     * Detection: for each XZ column near hull vertices and edge midpoints, look for columns
     * of 2+ scaffold-type blocks above the surface Y that are NOT in the layout.
     * Mines them top-down using digBlock().
     *
     * @return number of scaffold blocks removed
     */
    private int scanAndRemoveStrayScaffolds(ServerPlayerEntity bot, ServerWorld world,
                                             FortificationLayout layout, SurfaceProfile surfaceProfile) {
        // Build set of all layout positions for fast exclusion
        Set<BlockPos> layoutPositions = new HashSet<>();
        for (ProceduralWallBlock block : layout.allBlocks()) {
            layoutPositions.add(block.worldPos());
        }

        // Collect unique XZ scan points from hull vertices + edge midpoints
        Set<Long> scanColumns = new HashSet<>();
        int scanRadius = 4;

        for (WallPoint vertex : layout.hullVertices()) {
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    scanColumns.add(packXZ(vertex.x() + dx, vertex.z() + dz));
                }
            }
        }
        for (WallEdge edge : layout.edges()) {
            int midX = (edge.start().x() + edge.end().x()) / 2;
            int midZ = (edge.start().z() + edge.end().z()) / 2;
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    scanColumns.add(packXZ(midX + dx, midZ + dz));
                }
            }
        }

        int cleared = 0;

        for (long packed : scanColumns) {
            if (SkillManager.shouldAbortSkill(bot)) break;

            int x = (int) (packed >> 32);
            int z = (int) packed;
            int surfY = safeSurfaceY(surfaceProfile, world, x, z);

            // Walk upward from surface looking for scaffold columns
            List<BlockPos> scaffoldColumn = new ArrayList<>();
            for (int y = surfY + 1; y <= surfY + 20; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (layoutPositions.contains(pos)) {
                    // Part of the layout — stop scanning this column
                    scaffoldColumn.clear();
                    break;
                }
                BlockState state = world.getBlockState(pos);
                if (state.isAir()) {
                    break; // reached air — end of potential pillar
                }
                boolean isScaffold = ScaffoldService.SCAFFOLD_BLOCKS.stream()
                        .anyMatch(item -> state.getBlock().asItem().equals(item));
                if (isScaffold) {
                    scaffoldColumn.add(pos);
                } else {
                    // Non-scaffold, non-air block — not a stray pillar
                    scaffoldColumn.clear();
                    break;
                }
            }

            // Only remove columns of 2+ blocks (single dirt blocks could be natural terrain)
            if (scaffoldColumn.size() >= 2) {
                // Mine top-down
                for (int i = scaffoldColumn.size() - 1; i >= 0; i--) {
                    if (SkillManager.shouldAbortSkill(bot)) break;
                    BlockPos pos = scaffoldColumn.get(i);
                    if (digBlock(bot, world, pos)) {
                        cleared++;
                        sleepQuiet(50L);
                    }
                }
            }
        }

        if (cleared > 0) {
            LOGGER.info("[FortifyPatch] Cleared {} stray scaffold blocks", cleared);
        }
        return cleared;
    }

    /**
     * Checks if a block position has at least {@code threshold} adjacent blocks (6 faces)
     * that are village structure blocks (logs, planks, doors, cobblestone stairs, etc.).
     * Used to protect blocks that are part of village buildings even if the block itself
     * isn't on the explicit blacklist.
     */
    private boolean isAdjacentToVillageStructure(ServerWorld world, BlockPos pos, int threshold) {
        int count = 0;
        for (Direction dir : Direction.values()) {
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (!neighbor.isAir() && VillageFortificationLayoutService.isVillageStructureBlock(neighbor.getBlock())) {
                count++;
                if (count >= threshold) return true;
            }
        }
        return false;
    }

    // ── Break-through stuck recovery ─────────────────────────────

    /**
     * Check whether a non-layout block is safe to mine for navigation purposes.
     * Rejects village structures, containers, and hazards.
     */
    private boolean isSafeToBreakForNavigation(ServerWorld world, BlockPos pos) {
        return evaluateNonLayoutBreakForNavigation(world, pos).allowed();
    }

    private NavBreakCandidateEval evaluateNonLayoutBreakForNavigation(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return new NavBreakCandidateEval(false, NavBreakRejectReason.AIR_OR_REPLACEABLE);
        if (DIG_BLACKLIST.contains(state.getBlock())) return new NavBreakCandidateEval(false, NavBreakRejectReason.DIG_BLACKLIST);
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.DOOR);
        if (state.getBlock() instanceof net.minecraft.block.BedBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.BED);
        if (state.getBlock() instanceof FenceBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.FENCE);
        if (state.getBlock() instanceof FenceGateBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.FENCE_GATE);
        if (state.getBlock() instanceof WallBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.WALL);
        if (state.getBlock() instanceof PaneBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.PANE);
        if (state.getBlock() instanceof TrapdoorBlock) return new NavBreakCandidateEval(false, NavBreakRejectReason.TRAPDOOR);
        if (state.getHardness(world, pos) < 0) return new NavBreakCandidateEval(false, NavBreakRejectReason.UNBREAKABLE);
        if (world.getBlockEntity(pos) != null) return new NavBreakCandidateEval(false, NavBreakRejectReason.BLOCK_ENTITY);
        if (!state.getFluidState().isEmpty()) return new NavBreakCandidateEval(false, NavBreakRejectReason.FLUID);
        if (isAdjacentToVillageStructure(world, pos, 2)) return new NavBreakCandidateEval(false, NavBreakRejectReason.VILLAGE_ADJACENT);
        if (state.getCollisionShape(world, pos).isEmpty()) return new NavBreakCandidateEval(false, NavBreakRejectReason.NO_COLLISION);
        return new NavBreakCandidateEval(true, null);
    }

    /**
     * Check whether a fortification layout block can be temporarily mined for navigation.
     * More permissive than {@link #isSafeToBreakForNavigation} — allows layout blocks
     * but still rejects unbreakable, fluid, and block-entity blocks.
     * Callers MUST replace these blocks after walking through.
     */
    private boolean isLayoutBlockBreakableForNavigation(ServerWorld world, BlockPos pos) {
        return evaluateLayoutBreakForNavigation(world, pos).allowed();
    }

    private NavBreakCandidateEval evaluateLayoutBreakForNavigation(ServerWorld world, BlockPos pos) {
        if (!fortificationProtectedPositions.contains(pos)) return new NavBreakCandidateEval(false, NavBreakRejectReason.NOT_LAYOUT_BLOCK);
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return new NavBreakCandidateEval(false, NavBreakRejectReason.AIR_OR_REPLACEABLE);
        if (state.getHardness(world, pos) < 0) return new NavBreakCandidateEval(false, NavBreakRejectReason.UNBREAKABLE);
        if (world.getBlockEntity(pos) != null) return new NavBreakCandidateEval(false, NavBreakRejectReason.BLOCK_ENTITY);
        if (!state.getFluidState().isEmpty()) return new NavBreakCandidateEval(false, NavBreakRejectReason.FLUID);
        if (state.getCollisionShape(world, pos).isEmpty()) return new NavBreakCandidateEval(false, NavBreakRejectReason.NO_COLLISION);
        return new NavBreakCandidateEval(true, null);
    }

    @Override
    public NavBreakCandidateEval evaluateBreakForNavigation(ServerWorld world, BlockPos pos, boolean allowLayout) {
        if (fortificationProtectedPositions.contains(pos)) {
            if (!allowLayout) {
                return new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
            }
            return evaluateLayoutBreakForNavigation(world, pos);
        }
        return evaluateNonLayoutBreakForNavigation(world, pos);
    }

    private void incrementNavBreakReject(Map<NavBreakRejectReason, Integer> counts, NavBreakCandidateEval eval) {
        if (counts == null || eval == null || eval.allowed() || eval.rejectReason() == null) {
            return;
        }
        counts.merge(eval.rejectReason(), 1, Integer::sum);
    }

    private String formatNavBreakRejectSummary(Map<NavBreakRejectReason, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<NavBreakRejectReason, Integer> e : counts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey().name().toLowerCase(Locale.ROOT)).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Last-resort navigation: mine one blocking block between the bot and its target,
     * walk through the gap, then replace the mined block.
     * Returns true if the bot moved to a new position.
     *
     * <p>Two-tier safety: prefers non-layout blocks first, then allows breaking
     * fortification layout blocks (with mandatory replacement).
     * Max one break-through per call to prevent tunnel-mining.
     */
    private boolean tryBreakThroughObstacle(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        return tryBreakThroughObstacle(bot, world, target, null);
    }

    private boolean tryBreakThroughObstacle(ServerPlayerEntity bot, ServerWorld world, BlockPos target, String causeContext) {
        BlockPos botPos = bot.getBlockPos();
        FortifyNavRuntimeScope scope = activeFortifyNavScope;

        // Guardrail: if the bot is already below the local terrain surface, bail to surface
        // recovery instead of tunneling horizontally underground.
        int terrainY = VillageFortificationLayoutService.terrainY(world, botPos.getX(), botPos.getZ());
        if (botPos.getY() < terrainY - 1) {
            int depth = terrainY - botPos.getY();
            String effectiveCauseContext = causeContext != null ? causeContext : (scope != null ? scope.context : null);
            boolean scopeFortifyContext = scope != null && isFortifyCarveContext(scope);
            boolean inferredFortifyContext = scope == null
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-edge:") || effectiveCauseContext.startsWith("fortify-tower:"))
                    && currentLayout != null;
            boolean fortifyContext = scopeFortifyContext || inferredFortifyContext;
            boolean trapLike = isTrapLikeCell(world, botPos);
            boolean shallowDepth = depth <= 3;
            boolean trapPocketDepth = depth <= FORTIFY_TRAP_CARVE_DEPTH_LIMIT;
            boolean insideOrAdjacentHull = entombmentHelper.isAdjacentToCurrentFortificationHull(botPos);
            int surfaceEscapePocketFailures = entombmentHelper.getSurfaceEscapeRetryFailureCount(botPos, terrainY);
            boolean repeatedSurfaceEscapePocket = entombmentHelper.shouldSkipRepeatedSurfaceEscape(botPos, terrainY);
            boolean noProgressPocketBurst = entombmentHelper.hasFortifyPocketNoProgressBurst(world, botPos, effectiveCauseContext, 2);
            boolean towerTrapPocketContext = fortifyContext
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-tower:approach")
                    || effectiveCauseContext.startsWith("fortify-tower:local-step-replan")
                    || effectiveCauseContext.startsWith("fortify-tower:hard-reset")
                    || effectiveCauseContext.startsWith("fortify-tower:scaffold-base")
                    || effectiveCauseContext.startsWith("fortify-tower:unwedge"));
            boolean edgeTrapPocketContext = fortifyContext
                    && effectiveCauseContext != null
                    && (effectiveCauseContext.startsWith("fortify-edge:approach")
                    || effectiveCauseContext.startsWith("fortify-edge:approach-close")
                    || effectiveCauseContext.startsWith("fortify-edge:approach-retry")
                    || effectiveCauseContext.startsWith("fortify-edge:unwedge")
                    || effectiveCauseContext.startsWith("fortify-edge:patch-start-preflight"));
            boolean towerTrapPocketImmediateOverride = towerTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (depth >= 4 || surfaceEscapePocketFailures >= 1);
            boolean edgeTrapPocketImmediateOverride = edgeTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && surfaceEscapePocketFailures >= 1;
            boolean towerLocalTrapPocketOverride = fortifyContext
                    && (towerTrapPocketImmediateOverride
                    || (towerTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (repeatedSurfaceEscapePocket || noProgressPocketBurst)));
            boolean edgeLocalTrapPocketOverride = fortifyContext
                    && (edgeTrapPocketImmediateOverride
                    || (edgeTrapPocketContext
                    && trapLike
                    && trapPocketDepth
                    && (repeatedSurfaceEscapePocket || noProgressPocketBurst)));
            boolean fortifyTrapEntombment = fortifyContext
                    && trapLike
                    && ((shallowDepth && insideOrAdjacentHull) || towerLocalTrapPocketOverride || edgeLocalTrapPocketOverride);
            if (fortifyTrapEntombment) {
                LOGGER.info("[FortifyNav] Bot below surface at {} vs terrainY={} but trap-like fortify entombment detected (depth={} hullAdj={} surfaceEscapeFailures={} repeatedSurfaceEscape={} noProgressBurst={} towerOverride={} edgeOverride={}), allowing emergency carve",
                        botPos.toShortString(), terrainY, depth, insideOrAdjacentHull,
                        surfaceEscapePocketFailures,
                        repeatedSurfaceEscapePocket, noProgressPocketBurst,
                        towerLocalTrapPocketOverride, edgeLocalTrapPocketOverride);
            } else {
                List<String> rejectReasons = new ArrayList<>();
                if (!fortifyContext) {
                    rejectReasons.add(scope == null ? "scopeMissing" : "notFortifyContext");
                }
                if (!trapLike) rejectReasons.add("notTrapLike");
                if (!shallowDepth && !towerLocalTrapPocketOverride && !edgeLocalTrapPocketOverride) rejectReasons.add("depthTooHigh");
                if (!insideOrAdjacentHull && !towerLocalTrapPocketOverride && !edgeLocalTrapPocketOverride) rejectReasons.add("outsideHull");
                if (surfaceEscapePocketFailures <= 0) rejectReasons.add("noSurfaceEscapeFailures");
                if (!repeatedSurfaceEscapePocket) rejectReasons.add("noRepeatedSurfaceEscape");
                if (!noProgressPocketBurst) rejectReasons.add("noProgressBurst");
                if (rejectReasons.isEmpty()) {
                    rejectReasons.add("fallbackSurfaceEscape");
                }
                BlockPos beforeSurfaceEscape = bot.getBlockPos();
                ensureOnSurface(bot, world, terrainY);
                BlockPos afterSurfaceEscape = bot.getBlockPos();
                boolean escaped = afterSurfaceEscape.getY() >= terrainY - 1 || !beforeSurfaceEscape.equals(afterSurfaceEscape);
                if (!escaped) {
                    entombmentHelper.noteEntombmentSurfaceEscapeFailure(world, beforeSurfaceEscape, causeContext);
                } else {
                    entombmentHelper.noteEntombmentRecoverySuccess(world, beforeSurfaceEscape, afterSurfaceEscape, causeContext);
                }
                LOGGER.info("[FortifyNav] Bot below surface at {} vs terrainY={}, invoking surface escape instead of carving rejects={} escaped={} moved={}",
                        botPos.toShortString(), terrainY, String.join("|", rejectReasons), escaped, !beforeSurfaceEscape.equals(afterSurfaceEscape));
                return escaped;
            }
        }
        boolean carveMode = scope != null
                && scope.navMode == FortifyNavMode.CARVE_CORRIDOR
                && isCarveEligibleForBreakAttempt(scope, world, botPos, target);
        FortifyCarveSession carveSession = (scope != null) ? scope.carveSession : null;
        boolean emergencyTrapSearch = carveMode && isEmergencyTrapEscapeEligible(scope, world, botPos);
        List<BlockPos> candidateOffsets = buildBreakThroughCandidateOffsets(botPos, target, emergencyTrapSearch);
        boolean towerExteriorGuard = scope != null && scope.towerPatchContext;

        // Diagnostic counters for the "no viable candidates" case
        int diagAllAir = 0, diagCanBreak = 0, diagReach = 0, diagNoFloor = 0, diagRecentCooldown = 0;
        Map<NavBreakRejectReason, Integer> rejectReasonCounts = new EnumMap<>(NavBreakRejectReason.class);

        boolean allowGateLayoutOverride = scope != null && scope.gateContext && isInsideCurrentFortificationHull(botPos);
        String replaceContext = scope != null && scope.context != null
                ? scope.context
                : (causeContext != null ? causeContext : "fortify-nav");

        // Two passes: first try non-layout blocks, then allow layout blocks
        for (int pass = 0; pass < 2; pass++) {
            boolean allowLayout = (pass == 1);
            if (pass == 1) {
                diagAllAir = 0;
                diagCanBreak = 0;
                diagReach = 0;
                diagNoFloor = 0;
                diagRecentCooldown = 0;
                rejectReasonCounts.clear();
            }

            for (BlockPos offset : candidateOffsets) {
                BlockPos feetPos = botPos.add(offset);
                BlockPos headPos = feetPos.up();
                BlockPos overheadPos = headPos.up(); // Y+2: clear overhead for tall walls

                if (entombmentHelper.isRecentCarveColumnOnCooldown(feetPos)) {
                    diagRecentCooldown++;
                    continue;
                }

                boolean feetBlocking = !world.getBlockState(feetPos).getCollisionShape(world, feetPos).isEmpty();
                boolean headBlocking = !world.getBlockState(headPos).getCollisionShape(world, headPos).isEmpty();
                if (!feetBlocking && !headBlocking) {
                    diagAllAir++;
                    if (!emergencyTrapSearch) {
                        continue;
                    }
                    if (offset.getY() == 0) {
                        // Guardrail: in trap carve mode, never count same-level air candidates as break-through success.
                        // They caused A<->B oscillation and masked the actual tunnel opportunity.
                        if (!canStandAt(world, feetPos)) {
                            diagNoFloor++;
                        }
                        continue;
                    }
                    if (!canStandAt(world, feetPos)) {
                        diagNoFloor++;
                        continue;
                    }

                    BlockPos walkTarget = buildBreakThroughWalkTarget(botPos, offset);
                    BlockPos before = bot.getBlockPos();
                    walkTowardBlock(bot, walkTarget, 1_200L);
                    sleepQuiet(100);

                    boolean moved = !before.equals(bot.getBlockPos());
                    if (moved && !isMeaningfulTrapEscapeProgress(world, before, bot.getBlockPos(), target)) {
                        LOGGER.info("[FortifyNav] trap-step false-progress ctx={} before={} after={} target={} offset={}",
                                scope != null ? scope.context : "n/a",
                                before.toShortString(),
                                bot.getBlockPos().toShortString(),
                                target != null ? target.toShortString() : "n/a",
                                offset.toShortString());
                        moved = false;
                    }
                    if (moved) {
                        LOGGER.info("[FortifyNav] Trap step escape success, moved from {} to {}",
                                before.toShortString(), bot.getBlockPos().toShortString());
                        if (scope != null && carveMode && scope.carveSession != null) {
                            scope.carveSession.completed = true;
                            scope.carveSession.crossed = true;
                            scope.carveSession.touch();
                        }
                        return true;
                    }
                    continue;
                }

                if (emergencyTrapSearch && offset.getY() < 0) {
                    // Guardrail: "tunneling" means opening through the obstacle in front (wall opening),
                    // not excavating downward. Step-down is allowed only when already open+standable
                    // (handled in the all-air branch above), never by mining below the current floor.
                    continue;
                }

                // Check safety: tier 1 (non-layout) or tier 2 (layout with mandatory replace)
                if (feetBlocking) {
                    NavBreakCandidateEval feetEval = evaluateBreakForNavigation(world, feetPos, allowLayout);
                    if (!feetEval.allowed() && allowGateLayoutOverride && fortificationProtectedPositions.contains(feetPos)) {
                        feetEval = new NavBreakCandidateEval(true, null);
                    }
                    if (!feetEval.allowed() && canOverrideVillageAdjacentForCarve(scope, world, botPos, feetPos, target, carveSession, feetEval)) {
                        feetEval = new NavBreakCandidateEval(true, null);
                    }
                    if (towerExteriorGuard && fortificationProtectedPositions.contains(feetPos)
                            && !isLayoutExteriorReachable(world, feetPos)) {
                        feetEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                    }
                    if (!feetEval.allowed()) {
                        diagCanBreak++;
                        incrementNavBreakReject(rejectReasonCounts, feetEval);
                        continue;
                    }
                }
                if (headBlocking) {
                NavBreakCandidateEval headEval = evaluateBreakForNavigation(world, headPos, allowLayout);
                if (!headEval.allowed() && allowGateLayoutOverride && fortificationProtectedPositions.contains(headPos)) {
                    headEval = new NavBreakCandidateEval(true, null);
                }
                if (!headEval.allowed() && canOverrideVillageAdjacentForCarve(scope, world, botPos, headPos, target, carveSession, headEval)) {
                    headEval = new NavBreakCandidateEval(true, null);
                    }
                    if (towerExteriorGuard && fortificationProtectedPositions.contains(headPos)
                            && !isLayoutExteriorReachable(world, headPos)) {
                        headEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                    }
                    if (!headEval.allowed()) {
                        diagCanBreak++;
                        incrementNavBreakReject(rejectReasonCounts, headEval);
                        continue;
                    }
                }

                if (feetBlocking && !isWithinMiningReach(bot, feetPos)) { diagReach++; continue; }
                if (headBlocking && !isWithinMiningReach(bot, headPos)) { diagReach++; continue; }

                // Overhead is best-effort: mine if blocking AND reachable, soft-skip otherwise
                boolean overheadBlocking = !world.getBlockState(overheadPos).getCollisionShape(world, overheadPos).isEmpty();
                NavBreakCandidateEval overheadEval = overheadBlocking
                        ? evaluateBreakForNavigation(world, overheadPos, allowLayout)
                        : new NavBreakCandidateEval(false, NavBreakRejectReason.AIR_OR_REPLACEABLE);
                if (overheadBlocking && !overheadEval.allowed() && allowGateLayoutOverride && fortificationProtectedPositions.contains(overheadPos)) {
                    overheadEval = new NavBreakCandidateEval(true, null);
                }
                if (overheadBlocking && !overheadEval.allowed()
                        && canOverrideVillageAdjacentForCarve(scope, world, botPos, overheadPos, target, carveSession, overheadEval)) {
                    overheadEval = new NavBreakCandidateEval(true, null);
                }
                if (overheadBlocking && towerExteriorGuard && fortificationProtectedPositions.contains(overheadPos)
                        && !isLayoutExteriorReachable(world, overheadPos)) {
                    overheadEval = new NavBreakCandidateEval(false, NavBreakRejectReason.LAYOUT_NOT_ALLOWED);
                }
                boolean mineOverhead = overheadBlocking
                        && overheadEval.allowed()
                        && isWithinMiningReach(bot, overheadPos);

                // Must be able to stand on the block below
                BlockState belowState = world.getBlockState(feetPos.down());
                if (belowState.getCollisionShape(world, feetPos.down()).isEmpty()) { diagNoFloor++; continue; }

                boolean anyLayoutBreak = (feetBlocking && fortificationProtectedPositions.contains(feetPos))
                        || (headBlocking && fortificationProtectedPositions.contains(headPos))
                        || (mineOverhead && fortificationProtectedPositions.contains(overheadPos));

                LOGGER.info("[FortifyNav] Breaking through {} at {} (head={} overhead={})",
                        anyLayoutBreak ? "WALL" : "obstruction",
                        feetPos.toShortString(),
                        headBlocking ? headPos.toShortString() : "clear",
                        mineOverhead ? overheadPos.toShortString() : "skip");

                BlockState feetOriginal = feetBlocking ? world.getBlockState(feetPos) : null;
                BlockState headOriginal = headBlocking ? world.getBlockState(headPos) : null;
                BlockState overheadOriginal = mineOverhead ? world.getBlockState(overheadPos) : null;
                boolean feetMandatory = feetOriginal != null && fortificationProtectedPositions.contains(feetPos);
                boolean headMandatory = headOriginal != null && fortificationProtectedPositions.contains(headPos);
                boolean overheadMandatory = overheadOriginal != null && fortificationProtectedPositions.contains(overheadPos);
                boolean preferHeadFirstEscapeOrder = emergencyTrapSearch
                        || (scope != null && scope.towerPatchContext && isTrapLikeCell(world, botPos));

                // Emergency entombment escape (learned demo pattern) prefers head-first opening,
                // then overhead, then feet. This reduces self-trapping while creating a passable slot.
                boolean overheadMined = false;
                boolean headMined = true;
                if (preferHeadFirstEscapeOrder && headBlocking) {
                    headMined = digBlockForNavigation(bot, world, headPos);
                } else if (!preferHeadFirstEscapeOrder && mineOverhead) {
                    if (!digBlockForNavigation(bot, world, overheadPos)) {
                        // Soft-skip: overhead failure doesn't reject this candidate
                        mineOverhead = false;
                        overheadOriginal = null;
                    } else {
                        overheadMined = true;
                    }
                }
                if (!headMined && overheadOriginal != null && overheadMined) {
                    ReplaceBlockResult rollback = tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                    if (!rollback.success()) {
                        queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                    }
                }
                if (!headMined) {
                    verifyCarveRepairColumn(bot, world,
                            feetPos, feetOriginal, feetMandatory,
                            headPos, headOriginal, headMandatory,
                            overheadPos, overheadOriginal, overheadMandatory,
                            replaceContext);
                    continue;
                }
                if (preferHeadFirstEscapeOrder && mineOverhead) {
                    if (!digBlockForNavigation(bot, world, overheadPos)) {
                        mineOverhead = false;
                        overheadOriginal = null;
                    } else {
                        overheadMined = true;
                    }
                }
                if (!preferHeadFirstEscapeOrder && headBlocking) {
                    headMined = digBlockForNavigation(bot, world, headPos);
                    if (!headMined && overheadOriginal != null && overheadMined) {
                        ReplaceBlockResult rollback = tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        if (!rollback.success()) {
                            queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        }
                    }
                    if (!headMined) {
                        verifyCarveRepairColumn(bot, world,
                                feetPos, feetOriginal, feetMandatory,
                                headPos, headOriginal, headMandatory,
                                overheadPos, overheadOriginal, overheadMandatory,
                                replaceContext);
                        continue;
                    }
                }

                boolean feetMined = true;
                if (feetBlocking) {
                    feetMined = digBlockForNavigation(bot, world, feetPos);
                    if (!feetMined && headOriginal != null && headMined) {
                        ReplaceBlockResult rollback = tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                        if (!rollback.success()) {
                            queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                        }
                    }
                    if (!feetMined && overheadOriginal != null && overheadMined) {
                        ReplaceBlockResult rollback = tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        if (!rollback.success()) {
                            queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                        }
                    }
                }
                if (!feetMined) {
                    verifyCarveRepairColumn(bot, world,
                            feetPos, feetOriginal, feetMandatory,
                            headPos, headOriginal, headMandatory,
                            overheadPos, overheadOriginal, overheadMandatory,
                            replaceContext);
                    continue;
                }

                // Walk through the gap toward a point 4 blocks past the bot in the break direction.
                // walkTowardBlock bails at distSq < 6.0, so feetPos (1 block) is too close.
                // 4 blocks gives distSq >= 16 which reliably runs the walk loop.
                BlockPos walkTarget = buildBreakThroughWalkTarget(botPos, offset);
                BlockPos before = bot.getBlockPos();
                walkTowardBlock(bot, walkTarget, 900L);
                sleepQuiet(60);

                boolean moved = !before.equals(bot.getBlockPos());
                if (moved && emergencyTrapSearch && !isMeaningfulTrapEscapeProgress(world, before, bot.getBlockPos(), target)) {
                    LOGGER.info("[FortifyNav] trap-carve false-progress ctx={} before={} after={} target={} offset={}",
                            scope != null ? scope.context : "n/a",
                            before.toShortString(),
                            bot.getBlockPos().toShortString(),
                            target != null ? target.toShortString() : "n/a",
                            offset.toShortString());
                    moved = false;
                }

                boolean deferRepair = carveMode && scope != null;
                if (deferRepair) {
                    deferCarveRepair(scope, feetPos, feetOriginal, feetMandatory);
                    deferCarveRepair(scope, headPos, headOriginal, headMandatory);
                    deferCarveRepair(scope, overheadPos, overheadOriginal, overheadMandatory);
                } else {
                    // Replace mined blocks — emergency escapes restore from far/upper to near/lower to avoid re-entombing.
                    boolean replaceTopDown = emergencyTrapSearch || (moved && preferHeadFirstEscapeOrder);
                    if (replaceTopDown) {
                        if (overheadOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            }
                        }
                        if (headOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            }
                        }
                        if (feetOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            }
                        }
                    } else {
                        if (feetOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, replaceContext);
                            }
                        }
                        if (headOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, replaceContext);
                            }
                        }
                        if (overheadOriginal != null) {
                            ReplaceBlockResult result = tryReplaceMinedBlock(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            if (!result.success()) {
                                queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, replaceContext);
                            }
                        }
                    }
                }

                if (moved) {
                    LOGGER.info("[FortifyNav] Break-through success, moved from {} to {}",
                            before.toShortString(), bot.getBlockPos().toShortString());
                    if (scope != null && carveMode && scope.carveSession != null) {
                        scope.carveSession.completed = true;
                        scope.carveSession.crossed = true;
                        scope.carveSession.touch();
                    }
                }

                // Ensure all layout blocks mined during this attempt are either replaced immediately
                // or queued for deferred repair so no hole remains.
                if (deferRepair && scope != null && scope.carveSession != null && !scope.carveSession.deferredRepairs.isEmpty()) {
                    cleanupHelper.queueCarveRepairs(scope, scope.carveSession, new ArrayList<>(scope.carveSession.deferredRepairs));
                }
                verifyCarveRepairColumn(bot, world,
                        feetPos, feetOriginal, feetMandatory,
                        headPos, headOriginal, headMandatory,
                        overheadPos, overheadOriginal, overheadMandatory,
                        replaceContext);

                // Cooldown this carve column to avoid back-and-forth re-carving.
                if (feetPos != null) {
                    entombmentHelper.noteRecentCarveColumn(feetPos);
                }
                return moved;
            }
        }

        boolean villageDominated = rejectReasonCounts.getOrDefault(NavBreakRejectReason.VILLAGE_ADJACENT, 0) > 0;
        boolean trapLike = isTrapLikeCell(world, botPos);
        if (scope != null && scope.towerState != null) {
            scope.towerState.noteBreakRejects(rejectReasonCounts);
            boolean emergencyEligible = isEmergencyTrapEscapeEligible(scope, world, botPos);
            boolean gateVillageDeadlock = scope.gateContext
                    && villageDominated
                    && isCarveEligibleScope(scope, botPos, target);
            boolean trapEmergencyReady = emergencyEligible && villageDominated
                    && (scope.towerState.shouldActivateCarveMode()
                    || scope.towerState.sameStuckPosCount >= 1
                    || scope.towerState.villageAdjacentRejectBursts >= 1);
            if (gateVillageDeadlock && scope.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] gate-carve-activated ctx={} target={} sameStuck={} noRealProgressMs={} villageAdjBursts={}",
                        scope.context, target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.noRealProgressElapsedMs(),
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (trapEmergencyReady && scope.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] trap-detected ctx={} pos={} topology={} target={} emergencyEscape=activating sameStuck={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (scope.towerState.shouldActivateCarveMode() && isCarveEligibleScope(scope, botPos, target)) {
                scope.towerState.activateCarveMode();
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] Carve mode activated ctx={} target={} sameStuck={} noRealProgressMs={} villageAdjBursts={}",
                        scope.context, target.toShortString(),
                        scope.towerState.sameStuckPosCount,
                        scope.towerState.noRealProgressElapsedMs(),
                        scope.towerState.villageAdjacentRejectBursts);
            } else if (scope.towerState.shouldActivateCarveMode() && !isCarveEligibleScope(scope, botPos, target) && !emergencyEligible) {
                LOGGER.info("[FortifyNav] Carve mode suppressed ctx={} target={} dist={} (local-only)",
                        scope.context, target.toShortString(),
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(botPos.getSquaredDistance(target))));
            }
        } else if (scope != null && isFortifyCarveContext(scope)) {
            if (villageDominated) {
                if (scope.lastTrapRejectPos != null && scope.lastTrapRejectPos.equals(botPos)) {
                    scope.sameTrapRejectPosCount++;
                } else {
                    scope.lastTrapRejectPos = botPos.toImmutable();
                    scope.sameTrapRejectPosCount = 1;
                }
                if (trapLike) {
                    scope.localTrapRejectBursts++;
                } else {
                    scope.localTrapRejectBursts = Math.max(0, scope.localTrapRejectBursts - 1);
                }
            } else {
                scope.localTrapRejectBursts = Math.max(0, scope.localTrapRejectBursts - 1);
            }

            boolean emergencyEligible = isEmergencyTrapEscapeEligible(scope, world, botPos);
            boolean gateVillageDeadlock = scope.gateContext
                    && villageDominated
                    && isCarveEligibleScope(scope, botPos, target);
            if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR && gateVillageDeadlock) {
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] gate-carve-activated ctx={} pos={} topology={} target={} samePos={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.sameTrapRejectPosCount,
                        scope.localTrapRejectBursts);
            } else if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR && emergencyEligible
                    && villageDominated && (scope.sameTrapRejectPosCount >= 1 || scope.localTrapRejectBursts >= 1)) {
                scope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                if (scope.carveSession == null) {
                    scope.carveSession = new FortifyCarveSession(target, causeContext != null ? causeContext : scope.context);
                }
                LOGGER.info("[FortifyNav] trap-detected ctx={} pos={} topology={} target={} emergencyEscape=activating samePos={} villageAdjBursts={}",
                        scope.context,
                        botPos.toShortString(),
                        VoxelJunctionService.analyzeStandCell(world, botPos).topology(),
                        target.toShortString(),
                        scope.sameTrapRejectPosCount,
                        scope.localTrapRejectBursts);
            }
        }

        LOGGER.info("[FortifyNav] Break-through found no viable candidates at {} toward {} " +
                        "(allAir={} canBreak={} reach={} noFloor={} recentCooldown={} offsets={} rejectReasons={})",
                botPos.toShortString(), target.toShortString(),
                diagAllAir, diagCanBreak, diagReach, diagNoFloor, diagRecentCooldown, candidateOffsets.size(),
                formatNavBreakRejectSummary(rejectReasonCounts));
        return false;
    }

    private BlockPos buildBreakThroughWalkTarget(BlockPos botPos, BlockPos offset) {
        if (botPos == null || offset == null) {
            return botPos;
        }
        int maxAbs = Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ()));
        int scale = maxAbs >= 2 ? 2 : 4;
        return new BlockPos(
                botPos.getX() + offset.getX() * scale,
                botPos.getY() + offset.getY(),
                botPos.getZ() + offset.getZ() * scale);
    }

    private boolean isMeaningfulTrapEscapeProgress(ServerWorld world, BlockPos before, BlockPos after, BlockPos target) {
        if (world == null || before == null || after == null || before.equals(after)) {
            return false;
        }
        VoxelJunctionService.VoxelStandCell beforeCell = VoxelJunctionService.analyzeStandCell(world, before);
        VoxelJunctionService.VoxelStandCell afterCell = VoxelJunctionService.analyzeStandCell(world, after);

        double netDisp = Math.sqrt(before.getSquaredDistance(after));
        double distDelta = 0.0D;
        if (target != null) {
            distDelta = Math.sqrt(before.getSquaredDistance(target)) - Math.sqrt(after.getSquaredDistance(target));
        }

        boolean topologyImproved = afterCell.openFaces() > beforeCell.openFaces()
                || (!isTrapLikeCell(world, after) && isTrapLikeCell(world, before))
                || (afterCell.topology() != beforeCell.topology()
                && afterCell.topology() != VoxelJunctionService.CellTopology.POCKET
                && afterCell.topology() != VoxelJunctionService.CellTopology.DEAD_END);

        if (topologyImproved && netDisp >= 1.0D) {
            return true;
        }
        return distDelta >= 1.5D || netDisp >= 2.0D;
    }

    private boolean canOverrideVillageAdjacentForCarve(FortifyNavRuntimeScope scope,
                                                       ServerWorld world,
                                                       BlockPos botPos,
                                                       BlockPos blockPos,
                                                       BlockPos target,
                                                       FortifyCarveSession carveSession,
                                                       NavBreakCandidateEval eval) {
        if (scope == null || blockPos == null || botPos == null || target == null) return false;
        if (!isFortifyCarveContext(scope)) return false;
        if (scope.navMode != FortifyNavMode.CARVE_CORRIDOR) return false;
        boolean emergencyTrap = isEmergencyTrapEscapeEligible(scope, world, botPos);
        if (!emergencyTrap && !isCarveEligibleScope(scope, botPos, target)) return false;
        if (carveSession == null || !carveSession.canMineMore()) return false;
        if (eval == null || eval.allowed() || eval.rejectReason() != NavBreakRejectReason.VILLAGE_ADJACENT) return false;
        if (botPos.getSquaredDistance(blockPos) > (double) (FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST * FORTIFY_CARVE_MAX_BOT_TO_BLOCK_DIST)) {
            return false;
        }
        if (!emergencyTrap
                && target.getSquaredDistance(blockPos) > (double) (FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST * FORTIFY_CARVE_MAX_TARGET_TO_BLOCK_DIST)) {
            return false;
        }
        return true;
    }

    private void deferCarveRepair(FortifyNavRuntimeScope scope, BlockPos pos, BlockState originalState, boolean mandatory) {
        if (scope == null || pos == null || originalState == null) return;
        if (scope.carveSession == null) {
            scope.carveSession = new FortifyCarveSession(scope.primaryTarget, scope.context);
        }
        FortifyCarveSession session = scope.carveSession;
        if (session.entryPos == null) {
            session.entryPos = pos.toImmutable();
        }
        session.touch();
        for (DeferredRepair existing : session.deferredRepairs) {
            if (existing.pos().equals(pos)) {
                return;
            }
        }
        if (!session.canMineMore()) {
            return;
        }
        session.deferredRepairs.add(new DeferredRepair(pos.toImmutable(), originalState, mandatory));
        session.blocksMined++;
    }

    private boolean isCarveEligibleScope(FortifyNavRuntimeScope scope, BlockPos botPos, BlockPos target) {
        if (scope == null) return false;
        if (!isFortifyCarveContext(scope)) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (ctx.contains("long-range")) return false;
        BlockPos effectiveTarget = target != null ? target : scope.primaryTarget;
        if (botPos == null || effectiveTarget == null) return false;
        return botPos.getSquaredDistance(effectiveTarget)
                <= (double) (FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST * FORTIFY_CARVE_LOCAL_TARGET_MAX_DIST);
    }

    @Override
    public boolean isFortifyCarveContext(FortifyNavRuntimeScope scope) {
        if (scope == null) return false;
        if (scope.towerPatchContext || scope.gateContext) return true;
        String ctx = scope.context == null ? "" : scope.context;
        return ctx.startsWith("fortify-edge:");
    }

    private boolean isEmergencyTrapEscapeEligible(FortifyNavRuntimeScope scope, ServerWorld world, BlockPos botPos) {
        if (scope == null || world == null || botPos == null) return false;
        if (!isFortifyCarveContext(scope)) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (ctx.contains("long-range")) return false;
        return isTrapLikeCell(world, botPos);
    }

    private boolean isCarveEligibleForBreakAttempt(FortifyNavRuntimeScope scope, ServerWorld world, BlockPos botPos, BlockPos target) {
        return isCarveEligibleScope(scope, botPos, target) || isEmergencyTrapEscapeEligible(scope, world, botPos);
    }

    private List<BlockPos> buildBreakThroughCandidateOffsets(BlockPos botPos, BlockPos target, boolean emergencyTrapSearch) {
        LinkedHashSet<BlockPos> offsets = new LinkedHashSet<>();
        if (botPos != null && target != null) {
            int dx = target.getX() - botPos.getX();
            int dz = target.getZ() - botPos.getZ();
            int sx = Integer.signum(dx);
            int sz = Integer.signum(dz);
            
            if (sx != 0 && sz != 0) {
                offsets.add(new BlockPos(sx, 0, sz));
            }
            if (sx != 0) {
                offsets.add(new BlockPos(sx, 0, 0));
            }
            if (sz != 0) {
                offsets.add(new BlockPos(0, 0, sz));
            }
            
            // Add sideways offsets that still make progress along the major axis
            if (Math.abs(dx) >= Math.abs(dz) && sx != 0) {
                offsets.add(new BlockPos(sx, 0, 1));
                offsets.add(new BlockPos(sx, 0, -1));
            } else if (sz != 0) {
                offsets.add(new BlockPos(1, 0, sz));
                offsets.add(new BlockPos(-1, 0, sz));
            }
        }
        if (emergencyTrapSearch) {
            int[][] emergency = {
                    {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                    {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                    {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                    {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                    {1, 2}, {-1, 2}, {1, -2}, {-1, -2}
            };
            for (int[] e : emergency) {
                offsets.add(new BlockPos(e[0], 0, e[1]));
            }

            // Guardrail: allow step-up / step-down trap exits, but only for immediate neighbors.
            // This fixes dead-end pockets where same-Y candidates have no floor without reintroducing
            // the broken remote support-column behavior.
            List<BlockPos> verticalVariants = new ArrayList<>();
            for (BlockPos base : new ArrayList<>(offsets)) {
                if (base.getY() != 0) continue;
                if (Math.max(Math.abs(base.getX()), Math.abs(base.getZ())) > 1) continue;
                verticalVariants.add(new BlockPos(base.getX(), -1, base.getZ()));
                verticalVariants.add(new BlockPos(base.getX(), 1, base.getZ()));
            }
            for (BlockPos v : verticalVariants) {
                offsets.add(v);
            }
        }
        return new ArrayList<>(offsets);
    }

    private boolean tryRecoverTowardDeferredCleanup(ServerPlayerEntity bot, ServerWorld world, DeferredCleanupTask task) {
        if (bot == null || world == null || task == null || task.pos == null) return false;
        if (task.kind != FortifyCleanupKind.SCAFFOLD_REMOVE && task.kind != FortifyCleanupKind.CARVE_REPAIR) return false;
        if (task.attempts < FORTIFY_CLEANUP_ACTIVE_RECOVERY_ATTEMPTS) return false;
        double distSq = bot.getBlockPos().getSquaredDistance(task.pos);
        if (distSq > (double) (FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST * FORTIFY_CLEANUP_ACTIVE_RECOVERY_MAX_DIST)) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        walkTowardBlock(bot, task.pos, 700L);
        if (!before.equals(bot.getBlockPos())) {
            LOGGER.info("[FortifyCleanup] active-recovery moved toward {} pos={} from={} to={}",
                    task.kind, task.pos.toShortString(), before.toShortString(), bot.getBlockPos().toShortString());
            return true;
        }
        return false;
    }


    private boolean isTrapLikeCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        VoxelJunctionService.VoxelStandCell cell = VoxelJunctionService.analyzeStandCell(world, pos);
        return cell.topology() == VoxelJunctionService.CellTopology.POCKET
                || cell.topology() == VoxelJunctionService.CellTopology.DEAD_END
                || cell.openFaces() <= 1;
    }

    private boolean isInsideCurrentFortificationHull(ServerPlayerEntity bot) {
        return bot != null && isInsideCurrentFortificationHull(bot.getBlockPos());
    }

    private boolean isInsideCurrentFortificationHull(BlockPos pos) {
        if (pos == null || currentLayout == null) return false;
        List<WallPoint> hull = currentLayout.hullVertices();
        if (hull == null || hull.size() < 3) return false;
        return VillageFortificationLayoutService.pointInConvexHull(hull, pos.getX(), pos.getZ());
    }

    private boolean isLayoutExteriorReachable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return true;
        if (!fortificationProtectedPositions.contains(pos)) return true;
        if (!isInsideCurrentFortificationHull(pos)) return true;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(dir);
            if (!isInsideCurrentFortificationHull(neighbor)) {
                BlockState ns = world.getBlockState(neighbor);
                if (ns.isAir() || ns.isReplaceable() || ns.getCollisionShape(world, neighbor).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private CavityCheckResult evaluateCavity(ServerWorld world, BlockPos center) {
        if (world == null || center == null) return new CavityCheckResult(false, 0, false);
        int airCount = 0;
        boolean spawnable = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(p);
                    boolean open = state.isAir() || state.isReplaceable();
                    if (open) {
                        airCount++;
                        BlockPos below = p.down();
                        BlockState belowState = world.getBlockState(below);
                        boolean solidFloor = !belowState.getCollisionShape(world, below).isEmpty();
                        BlockState headState = world.getBlockState(p.up());
                        boolean headOpen = headState.isAir() || headState.isReplaceable();
                        if (solidFloor && headOpen) {
                            spawnable = true;
                        }
                    }
                }
            }
        }
        boolean safe = !spawnable;
        return new CavityCheckResult(safe, airCount, spawnable);
    }

    private boolean wouldRepairSealCurrentExit(ServerPlayerEntity bot, ServerWorld world, BlockPos repairPos) {
        if (bot == null || world == null || repairPos == null) return false;
        BlockPos botPos = bot.getBlockPos();
        if (botPos.equals(repairPos) || botPos.up().equals(repairPos)) {
            return true;
        }
        int dx = Math.abs(repairPos.getX() - botPos.getX());
        int dz = Math.abs(repairPos.getZ() - botPos.getZ());
        int dy = Math.abs(repairPos.getY() - botPos.getY());
        if (dx > 1 || dz > 1 || dy > 2) {
            return false;
        }
        int exitsBefore = countOpenExits(world, botPos, null);
        int exitsAfter = countOpenExits(world, botPos, repairPos);
        if (exitsAfter > exitsBefore) {
            return false;
        }
        if (exitsAfter == 0 && exitsAfter < exitsBefore) {
            return true;
        }
        return isTrapLikeCell(world, botPos) && exitsAfter == 0;
    }

    private boolean shouldDeferCarveFinalize(FortifyNavRuntimeScope scope, ServerPlayerEntity bot, ServerWorld world) {
        if (scope == null || scope.carveSession == null || bot == null || world == null) return false;
        if (scope.carveSession.deferredRepairs.isEmpty()) return false;
        String ctx = scope.context == null ? "" : scope.context;
        if (!ctx.startsWith("fortify-tower:scaffold-base")) {
            return false;
        }
        if (!scope.carveSession.crossed && !scope.carveSession.completed) {
            return false;
        }
        if (scope.primaryTarget != null && bot.getBlockPos().getSquaredDistance(scope.primaryTarget) > 25.0D) {
            return true;
        }
        if (isTrapLikeCell(world, bot.getBlockPos())) {
            return true;
        }
        for (DeferredRepair repair : scope.carveSession.deferredRepairs) {
            if (repair == null || repair.pos() == null) continue;
            if (world.getBlockState(repair.pos()).isAir() && wouldRepairSealCurrentExit(bot, world, repair.pos())) {
                return true;
            }
        }
        return false;
    }

    private void attemptFinalizeCarveTransaction(ServerPlayerEntity bot, ServerWorld world, FortifyNavRuntimeScope scope) {
        if (scope == null || scope.carveSession == null) return;
        FortifyCarveSession session = scope.carveSession;
        if (session.deferredRepairs.isEmpty()) return;

        List<DeferredRepair> pending = new ArrayList<>();
        for (DeferredRepair repair : session.deferredRepairs) {
            if (repair == null || repair.pos() == null || repair.state() == null) continue;
            // Already restored or filled by something else.
            if (!world.getBlockState(repair.pos()).isAir()) continue;
            pending.add(repair);
        }
        if (pending.isEmpty()) {
            session.deferredRepairs.clear();
            session.cleanupState = CleanupState.DONE;
            LOGGER.info("[FortifyNav] Carve repair ctx={} target={} mined={} state={} queued=0 (nothing pending)",
                    scope.context,
                    session.target != null ? session.target.toShortString() : "n/a",
                    session.blocksMined,
                    session.cleanupState);
            return;
        }

        if (shouldDeferCarveFinalize(scope, bot, world)) {
            cleanupHelper.queueCarveRepairs(scope, session, pending);
            session.deferredRepairs.clear();
            session.cleanupState = CleanupState.FAILED_QUEUED;
            LOGGER.info("[FortifyNav] Carve finalize deferred ctx={} target={} pending={} reason=still-trapped queued={} topology={} dist={}",
                    scope.context,
                    session.target != null ? session.target.toShortString() : "n/a",
                    pending.size(),
                    pending.size(),
                    VoxelJunctionService.analyzeStandCell(world, bot.getBlockPos()).topology(),
                    scope.primaryTarget != null
                            ? String.format(Locale.ROOT, "%.1f", Math.sqrt(bot.getBlockPos().getSquaredDistance(scope.primaryTarget)))
                            : "n/a");
            return;
        }

        BlockPos botPos = bot.getBlockPos();
        int reachableCount = 0;
        double minDist = Double.POSITIVE_INFINITY;
        BlockPos nearest = null;
        Vec3d eye = bot.getEyePos();
        for (DeferredRepair repair : pending) {
            BlockPos pos = repair.pos();
            double dist = Math.sqrt(botPos.getSquaredDistance(pos));
            if (dist < minDist) {
                minDist = dist;
                nearest = pos;
            }
            if (isWithinReach(bot, pos) && hasLineOfSight(world, bot, eye, pos)
                    && !botPos.equals(pos) && !botPos.up().equals(pos)) {
                reachableCount++;
            }
        }

        if (reachableCount < pending.size() && nearest != null && minDist <= FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST) {
            // Try a short reposition before giving up and queueing cleanup.
            BlockPos beforeStage = bot.getBlockPos();
            walkTowardBlock(bot, nearest, 900L);
            if (!beforeStage.equals(bot.getBlockPos())) {
                botPos = bot.getBlockPos();
                eye = bot.getEyePos();
                reachableCount = 0;
                minDist = Double.POSITIVE_INFINITY;
                for (DeferredRepair repair : pending) {
                    BlockPos pos = repair.pos();
                    double dist = Math.sqrt(botPos.getSquaredDistance(pos));
                    if (dist < minDist) minDist = dist;
                    if (isWithinReach(bot, pos) && hasLineOfSight(world, bot, eye, pos)
                            && !botPos.equals(pos) && !botPos.up().equals(pos)) {
                        reachableCount++;
                    }
                }
            }
        }

        LOGGER.info("[FortifyNav] Carve repair posture ctx={} target={} pending={} reachable={} minDist={} crossed={} completed={}",
                scope.context,
                session.target != null ? session.target.toShortString() : "n/a",
                pending.size(),
                reachableCount,
                minDist == Double.POSITIVE_INFINITY ? "n/a" : String.format(Locale.ROOT, "%.1f", minDist),
                session.crossed, session.completed);

        int repaired = 0;
        List<DeferredRepair> unresolved = new ArrayList<>();
        pending.sort((a, b) -> {
            boolean aSeal = wouldRepairSealCurrentExit(bot, world, a != null ? a.pos() : null);
            boolean bSeal = wouldRepairSealCurrentExit(bot, world, b != null ? b.pos() : null);
            if (aSeal != bSeal) return aSeal ? 1 : -1; // seal-risk repairs last
            BlockPos botPosSort = bot.getBlockPos();
            double aDist = a == null || a.pos() == null ? Double.NEGATIVE_INFINITY : botPosSort.getSquaredDistance(a.pos());
            double bDist = b == null || b.pos() == null ? Double.NEGATIVE_INFINITY : botPosSort.getSquaredDistance(b.pos());
            return Double.compare(bDist, aDist); // farther repairs first
        });
        for (DeferredRepair repair : pending) {
            BlockPos pos = repair.pos();
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            if (wouldRepairSealCurrentExit(bot, world, pos)) {
                unresolved.add(repair);
                LOGGER.info("[FortifyNav] Carve repair skip pos={} reason=seal-risk exitsAfter=0", pos.toShortString());
                continue;
            }
            if (bot.getBlockPos().equals(pos) || bot.getBlockPos().up().equals(pos)) {
                unresolved.add(repair);
                continue;
            }
            if (!isWithinReach(bot, pos) || !hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                unresolved.add(repair);
                continue;
            }
            ReplaceBlockResult replaceResult = tryReplaceMinedBlock(bot, world, pos, repair.state(), repair.mandatory(), scope.context);
            if (!world.getBlockState(pos).isAir()) {
                repaired++;
            } else {
                if (!replaceResult.success() && repair.mandatory()) {
                    queueMandatoryCarveRepairIfNeeded(bot, world, pos, repair.state(), true, scope.context);
                }
                unresolved.add(repair);
            }
        }

        int queued = 0;
        if (!unresolved.isEmpty()) {
            cleanupHelper.queueCarveRepairs(scope, session, unresolved);
            queued = unresolved.size();
        }
        session.deferredRepairs.clear();
        if (queued == 0) {
            session.cleanupState = CleanupState.DONE;
        } else if (repaired > 0) {
            session.cleanupState = CleanupState.PARTIAL;
        } else {
            session.cleanupState = CleanupState.FAILED_QUEUED;
        }
        LOGGER.info("[FortifyNav] Carve repair ctx={} target={} mined={} repaired={} queued={} state={} completed={}",
                scope.context,
                session.target != null ? session.target.toShortString() : "n/a",
                session.blocksMined, repaired, queued, session.cleanupState, session.completed);
    }

    private void processDeferredFortifyCleanupQueue(ServerPlayerEntity bot, ServerWorld world, String context) {
        if (bot == null || world == null || cleanupHelper.queue.isEmpty()) {
            return;
        }
        if ((SkillManager.shouldAbortSkill(bot) || bot.isRemoved()) && !cleanupHelper.isForcedContext(context)) {
            return;
        }
        boolean forcePass = cleanupHelper.isForcedContext(context);
        if (cleanupHelper.checkAndUpdateThrottle(forcePass)) return;
        long now = System.currentTimeMillis();
        boolean allowActiveRecoveryMovement = cleanupHelper.allowActiveRecovery(context);
        int started = cleanupHelper.queue.size();
        int repaired = 0;
        int removedScaffold = 0;
        int alreadyResolved = 0;
        int skipped = 0;
        int sealRiskSkips = 0;
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        for (Iterator<DeferredCleanupTask> it = cleanupHelper.queue.iterator(); it.hasNext(); ) {
            DeferredCleanupTask task = it.next();
            if (task == null || task.pos == null) {
                it.remove();
                continue;
            }
            if (task.nextEligibleMs > now) {
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "backoffDeferred");
                continue;
            }
            BlockPos pos = task.pos;
            // Proximity skip: don't attempt items that are very far away — just defer them.
            // The bot will pick them up when it's closer on a future pass.
            double distSqToItem = bot.getBlockPos().getSquaredDistance(pos);
            if (distSqToItem > 256.0D) { // > 16 blocks away
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "tooFar");
                continue;
            }
            if (task.kind == FortifyCleanupKind.CARVE_REPAIR) {
                if (task.originalState == null) {
                    it.remove();
                    continue;
                }
                if (!world.getBlockState(pos).isAir()) {
                    cleanupHelper.noteResolved(task);
                    alreadyResolved++;
                    it.remove();
                    continue;
                }
                if (wouldRepairSealCurrentExit(bot, world, pos)) {
                    cleanupHelper.noteSkip(task, "sealRisk");
                    skipped++;
                    sealRiskSkips++;
                    FortifyCleanupHelper.incrementReason(skipReasons, "sealRisk");
                    continue;
                }
                if (!isWithinReach(bot, pos)) {
                    boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                    if (recovered && isWithinReach(bot, pos)) {
                        now = System.currentTimeMillis();
                    } else {
                        cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                        skipped++;
                        FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                        continue;
                    }
                }
                if (!hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                    boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                    if (!recovered || !hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                        cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                        skipped++;
                        FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                        continue;
                    }
                }
                ReplaceBlockResult replace = tryReplaceMinedBlock(bot, world, pos, task.originalState, task.mandatory,
                        task.context != null ? task.context : context);
                if (!world.getBlockState(pos).isAir()) {
                    cleanupHelper.noteImmediateRetry(task, null);
                    cleanupHelper.noteResolved(task);
                    repaired++;
                    it.remove();
                } else {
                    cleanupHelper.noteSkip(task, "replaceFail");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, "replaceFail");
                }
                continue;
            }

            // Scaffold removal cleanup
            BlockState current = world.getBlockState(pos);
            if (current.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(current.getBlock().asItem())) {
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                alreadyResolved++;
                it.remove();
                continue;
            }
            if (!isWithinMiningReach(bot, pos)) {
                boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                if (recovered && isWithinMiningReach(bot, pos)) {
                    now = System.currentTimeMillis();
                } else {
                    cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedReach" : "blockedReachNoMove");
                    continue;
                }
            }
            if (!hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                boolean recovered = allowActiveRecoveryMovement && tryRecoverTowardDeferredCleanup(bot, world, task);
                if (!recovered || !hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                    cleanupHelper.noteSkip(task, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                    skipped++;
                    FortifyCleanupHelper.incrementReason(skipReasons, allowActiveRecoveryMovement ? "blockedLOS" : "blockedLOSNoMove");
                    continue;
                }
            }
            LookController.faceBlock(bot, pos);
            sleepQuiet(30L);
            boolean mined = digBlock(bot, world, pos);
            BlockState after = world.getBlockState(pos);
            if (mined && (after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem()))) {
                cleanupHelper.noteImmediateRetry(task, null);
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                removedScaffold++;
                it.remove();
            } else if (after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem())) {
                cleanupHelper.noteImmediateRetry(task, null);
                ScaffoldService.getScaffoldMemory(bot).remove(pos);
                cleanupHelper.noteResolved(task);
                alreadyResolved++;
                it.remove();
            } else {
                cleanupHelper.noteSkip(task, "digFailed");
                skipped++;
                FortifyCleanupHelper.incrementReason(skipReasons, "digFailed");
                continue;
            }
        }

        if (started > 0) {
            LOGGER.info("[FortifyCleanup] queue-process ctx={} started={} repaired={} scaffoldRemoved={} alreadyResolved={} skipped={} sealRiskSkips={} remaining={} reasons={}",
                    context, started, repaired, removedScaffold, alreadyResolved, skipped, sealRiskSkips,
                    cleanupHelper.queue.size(), FortifyCleanupHelper.formatReasonSummary(skipReasons));
        }
    }

    /**
     * Unified check: can this block be mined for navigation?
     * When {@code allowLayout} is false, only non-layout blocks pass.
     * When {@code allowLayout} is true, layout blocks also pass (for wall traversal).
     */
    private boolean canBreakForNavigation(ServerWorld world, BlockPos pos, boolean allowLayout) {
        return evaluateBreakForNavigation(world, pos, allowLayout).allowed();
    }

    /** Mine a single block for navigation break-through. Thin wrapper around MiningTool. */
    @Override
    public boolean digBlockForNavigation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        try {
            LookController.faceBlock(bot, pos);
            sleepQuiet(50);
            CompletableFuture<String> result = MiningTool.mineBlock(bot, pos);
            String outcome = awaitMiningOutcome(result, () -> SkillManager.shouldAbortSkill(bot),
                    DIG_RESULT_TIMEOUT_MS, DIG_RESULT_POLL_MS);
            return outcome != null && !outcome.startsWith("⚠️");
        } catch (Exception e) {
            LOGGER.debug("[FortifyNav] digBlockForNavigation failed at {}: {}", pos.toShortString(), e.getMessage());
            return false;
        }
    }

    private ReplaceFailureKind classifyReplaceFailureKind(String reason) {
        if (reason == null || reason.isBlank()) return ReplaceFailureKind.OTHER;
        if (reason.startsWith("bot-intersects-target")) return ReplaceFailureKind.BOT_OCCUPIES;
        if (reason.startsWith("out-of-reach")) return ReplaceFailureKind.OUT_OF_REACH;
        if (reason.startsWith("no-line-of-sight")) return ReplaceFailureKind.LOS_BLOCKED;
        if (reason.startsWith("no-block-item-available")) return ReplaceFailureKind.NO_MATERIAL;
        return ReplaceFailureKind.OTHER;
    }

    private ReplaceBlockResult tryReplaceMinedBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                    BlockState originalState, boolean mandatory, String context) {
        if (bot == null || world == null || pos == null || originalState == null) {
            return new ReplaceBlockResult(false, ReplaceFailureKind.OTHER, "invalid-args");
        }
        if (!world.getBlockState(pos).isAir()) {
            return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
        }

        Item originalItem = originalState.getBlock().asItem();
        List<Item> replacements;
        if (mandatory) {
            Set<Item> seen = new LinkedHashSet<>();
            if (originalItem != Items.AIR) seen.add(originalItem);
            seen.addAll(FortifyLayoutHelper.STONE_BRICK_FALLBACKS);
            seen.addAll(FortifyLayoutHelper.COBBLE_FALLBACKS);
            replacements = new ArrayList<>(seen);
        } else if (originalItem != Items.AIR) {
            replacements = List.of(originalItem, Items.COBBLESTONE, Items.STONE, Items.DIRT);
        } else {
            replacements = List.of(Items.COBBLESTONE, Items.STONE, Items.DIRT);
        }

        int attempts = mandatory ? (1 + FORTIFY_MANDATORY_REPLACE_RETRIES) : 1;
        ReplaceBlockResult last = new ReplaceBlockResult(false, ReplaceFailureKind.OTHER, "not-attempted");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (bot.getBoundingBox().intersects(new net.minecraft.util.math.Box(pos))) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2);
                if (safe != null && !safe.equals(bot.getBlockPos())) {
                    walkToTarget(bot.getCommandSource(), bot, safe, 1000L);
                }
            }

            BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, replacements, false);
            if (place.success()) {
                LOGGER.info("[FortifyNav] Replaced mined block at {}", pos.toShortString());
                return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
            }
            ReplaceFailureKind kind = classifyReplaceFailureKind(place.reason());
            last = new ReplaceBlockResult(false, kind, place.reason());

            boolean finalAttempt = attempt >= attempts;
            if (!finalAttempt && mandatory && last.retryable()) {
                LookController.faceBlock(bot, pos);
                if (kind == ReplaceFailureKind.OUT_OF_REACH
                        && bot.getBlockPos().getSquaredDistance(pos)
                        <= (double) (FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST * FORTIFY_CLEANUP_REPAIR_STAGE_MAX_DIST)) {
                    walkTowardBlock(bot, pos, 500L);
                } else {
                    BotActions.stop(bot);
                }
                sleepQuiet(FORTIFY_MANDATORY_REPLACE_RETRY_SLEEP_MS);
                if (!world.getBlockState(pos).isAir()) {
                    return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
                }
            }
        }

        // Fallback: bypass vanilla placement with direct setBlockState for mandatory repairs.
        // This handles cases where blockItem.place() rejects placement due to entity collision,
        // line-of-sight issues, or worker-thread read races on block state.
        // Note: forceReplaceBlock has its own server-thread isAir() guard, so no outer check needed.
        if (mandatory) {
            BotActions.PlaceResult forced = BotActions.forceReplaceBlock(bot, pos, replacements);
            if (forced.success() || !world.getBlockState(pos).isAir()) {
                LOGGER.info("[FortifyNav] Force-replaced mined block at {} (bypassed vanilla placement)", pos.toShortString());
                return new ReplaceBlockResult(true, ReplaceFailureKind.NONE, null);
            }
            LOGGER.warn("[FortifyNav] Force-replace also failed pos={} reason={}", pos.toShortString(), forced.reason());
        }

        String ctx = context == null ? "fortify-nav" : context;
        if (mandatory) {
            LOGGER.warn("[FortifyNav] replace-fail ctx={} pos={} mandatory=true reason={}",
                    ctx, pos.toShortString(), last.reason());
        } else {
            LOGGER.debug("[FortifyNav] replace-fail ctx={} pos={} mandatory=false reason={}",
                    ctx, pos.toShortString(), last.reason());
        }
        return last;
    }

    private void queueMandatoryCarveRepairIfNeeded(ServerPlayerEntity bot, ServerWorld world,
                                                   BlockPos pos, BlockState originalState,
                                                   boolean mandatory, String context) {
        if (!mandatory || pos == null || originalState == null) return;
        if (world != null && !world.getBlockState(pos).isAir()) return;
        String ctx = context == null ? "fortify-nav" : context;
        cleanupHelper.queue(FortifyCleanupKind.CARVE_REPAIR, pos, originalState, true, ctx);
    }

    private void verifyCarveRepairColumn(ServerPlayerEntity bot, ServerWorld world,
                                         BlockPos feetPos, BlockState feetOriginal, boolean feetMandatory,
                                         BlockPos headPos, BlockState headOriginal, boolean headMandatory,
                                         BlockPos overheadPos, BlockState overheadOriginal, boolean overheadMandatory,
                                         String context) {
        queueMandatoryCarveRepairIfNeeded(bot, world, feetPos, feetOriginal, feetMandatory, context);
        queueMandatoryCarveRepairIfNeeded(bot, world, headPos, headOriginal, headMandatory, context);
        queueMandatoryCarveRepairIfNeeded(bot, world, overheadPos, overheadOriginal, overheadMandatory, context);
    }

    /**
     * Replace a mined block. For layout blocks ({@code mandatory=true}), uses the
     * wall material fallback lists and logs a warning on failure so auto-patch can
     * repair it. For non-layout blocks, best-effort with common materials.
     */
    private boolean replaceMinedBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                      BlockState originalState, boolean mandatory) {
        return tryReplaceMinedBlock(bot, world, pos, originalState, mandatory, "fortify-nav").success();
    }

    // ── Navigation ──────────────────────────────────────────────

    /**
     * Navigate to an approach position for the given edge.
     * Stands 3 blocks outside the edge midpoint along the outward normal.
     */
    private void navigateToEdgeApproach(ServerCommandSource source, ServerPlayerEntity bot,
                                         ServerWorld world, WallEdge edge, SurfaceProfile surfaceProfile) {
        navigateToEdgeApproach(source, bot, world, edge, surfaceProfile, null);
    }

    /**
     * Navigate to an approach position for the given edge.
     * If {@code nearTarget} is provided (e.g. the first damaged block in patch mode),
     * the approach point is placed near that target instead of the edge midpoint.
     */
    private void navigateToEdgeApproach(ServerCommandSource source, ServerPlayerEntity bot,
                                         ServerWorld world, WallEdge edge, SurfaceProfile surfaceProfile,
                                         BlockPos nearTarget) {
        // Use nearTarget position if provided, otherwise fall back to edge midpoint
        int refX, refZ;
        if (nearTarget != null) {
            refX = nearTarget.getX();
            refZ = nearTarget.getZ();
        } else {
            refX = (edge.start().x() + edge.end().x()) / 2;
            refZ = (edge.start().z() + edge.end().z()) / 2;
        }

        // Compute outward normal (90° CW rotation of edge direction for CCW hull)
        double edgeDx = edge.end().x() - edge.start().x();
        double edgeDz = edge.end().z() - edge.start().z();
        double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
        if (edgeLen < 0.001) {
            BlockPos fallback = new BlockPos(refX, bot.getBlockPos().getY(), refZ);
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:approach-fallback", fallback,
                    () -> walkToTarget(source, bot, fallback, 15_000L, "fortify-edge:approach-fallback"));
            return;
        }

        // Outward normal for CCW hull: (dz, -dx) normalized
        double nx = edgeDz / edgeLen;
        double nz = -edgeDx / edgeLen;

        int approachX = (int) Math.round(refX + nx * 3);
        int approachZ = (int) Math.round(refZ + nz * 3);
        int approachY = safeSurfaceY(surfaceProfile, world, approachX, approachZ);

        BlockPos approachPos = new BlockPos(approachX, approachY, approachZ);

        // If bot is inside the hull and approach is outside, route through gatehouse first
        if (navigateThroughGateIfNeeded(source, bot, world, approachPos, surfaceProfile)) {
            if (SkillManager.shouldAbortSkill(bot)) return;
        }

        double distSq = bot.squaredDistanceTo(approachX + 0.5, bot.getY(), approachZ + 0.5);
        if (distSq > 144.0D) { // > 12 blocks away — use proper pathfinding
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, approachPos, MovementService.MovementOptions.skillLoot());
            if (plan.isPresent() && !SkillManager.shouldAbortSkill(bot)) {
                LOGGER.info("[FortifyEdge] long-range nav to edge approach={} dist={}",
                        approachPos.toShortString(), String.format("%.0f", Math.sqrt(distSq)));
                // Suppress obstruction mining and door traversal during fortify navigation
                MovementService.withoutDoorEscape(() ->
                        MovementService.withoutObstructionMining(
                                () -> MovementService.execute(source, bot, plan.get(), null)));
            }
        } else {
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:approach", approachPos,
                    () -> walkToTarget(source, bot, approachPos, 8_000L, "fortify-edge:approach"));
        }

        // After long-range pathfinding, the bot is often near but not adjacent to the wall.
        // walkToTarget closes the remaining gap with tick-by-tick movement and has built-in
        // stuck detection that triggers break-through when the bot hits the wall.
        double postDistSq = bot.squaredDistanceTo(approachX + 0.5, bot.getY(), approachZ + 0.5);
        if (postDistSq > 9.0D) { // > 3 blocks — walkToTarget will close the gap
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:approach-close", approachPos,
                    () -> walkToTarget(source, bot, approachPos, 8_000L, "fortify-edge:approach-close"));
            postDistSq = bot.squaredDistanceTo(approachX + 0.5, bot.getY(), approachZ + 0.5);
        }
        if (postDistSq > 25.0D) { // still far — wider-arc approach
            int wideX = (int) Math.round(refX + nx * 7);
            int wideZ = (int) Math.round(refZ + nz * 7);
            int wideY = safeSurfaceY(surfaceProfile, world, wideX, wideZ);
            BlockPos widePos = new BlockPos(wideX, wideY, wideZ);
            LOGGER.info("[FortifyEdge] wider-arc retry via {}", widePos.toShortString());
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:wide-arc", widePos,
                    () -> walkToTarget(source, bot, widePos, 6_000L, "fortify-edge:wide-arc"));
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:approach-retry", approachPos,
                    () -> walkToTarget(source, bot, approachPos, 6_000L, "fortify-edge:approach-retry"));
        }
    }

    /**
     * Strict interior check: returns true only if the point is strictly inside the hull
     * (all cross products > 0). Points on the hull boundary (any cross product == 0) return false.
     * This is needed for gate routing: tower vertices are hull vertices (on boundary) and must
     * be treated as "outside" so the bot routes through the gate to reach them.
     */
    private static boolean pointStrictlyInsideHull(List<WallPoint> hull, int px, int pz) {
        if (hull.size() < 3) return false;
        int n = hull.size();
        for (int i = 0; i < n; i++) {
            WallPoint a = hull.get(i);
            WallPoint b = hull.get((i + 1) % n);
            long cross = (long)(b.x() - a.x()) * (pz - a.z()) - (long)(b.z() - a.z()) * (px - a.x());
            if (cross <= 0) return false;
        }
        return true;
    }

    /**
     * If the bot is inside the convex hull and the target is outside, route through the
     * gatehouse opening first to avoid pathfinding into the wall.
     *
     * @return true if gate routing was performed (bot should now be outside the hull)
     */
    private boolean navigateThroughGateIfNeeded(ServerCommandSource source, ServerPlayerEntity bot,
                                                 ServerWorld world, BlockPos target,
                                                 SurfaceProfile surfaceProfile) {
        FortificationLayout layout = this.currentLayout;
        if (layout == null) return false;

        List<WallPoint> hull = layout.hullVertices();
        if (hull.size() < 3) return false;

        int botX = bot.getBlockPos().getX();
        int botZ = bot.getBlockPos().getZ();
        boolean botInside = VillageFortificationLayoutService.pointInConvexHull(hull, botX, botZ);
        if (!botInside) return false;

        BlockPos botPos = bot.getBlockPos();
        int botTerrainY = VillageFortificationLayoutService.terrainY(world, botX, botZ);
        int botDepth = Math.max(0, botTerrainY - botPos.getY());
        if (botDepth >= 2 && isTrapLikeCell(world, botPos)) {
            LOGGER.info("[FortifyGate] suppress gate route: trapped-below-surface pos={} depth={} target={}",
                    botPos.toShortString(), botDepth, target.toShortString());
            return false;
        }

        // Use strict interior check for target: hull boundary points (like tower vertices)
        // must be treated as "outside" so the bot routes through the gate to reach them.
        // pointInConvexHull uses cross >= 0 which includes boundary; strict uses cross > 0.
        boolean targetStrictlyInside = pointStrictlyInsideHull(hull, target.getX(), target.getZ());
        if (targetStrictlyInside) return false;

        // Bot is inside, target is outside — route through gatehouse
        // Skip if gate routing has failed twice already this session — saves 30+ seconds per edge
        if (gateRoutingFailures >= 2) {
            LOGGER.info("[FortifyGate] Skipping gate routing (failed {} times this session)", gateRoutingFailures);
            return false;
        }
        if (activeFortifyNavScope != null && activeFortifyNavScope.towerPatchContext) {
            double localTargetDistSq = bot.getBlockPos().getSquaredDistance(target);
            boolean localTrap = activeFortifyNavScope.towerState != null
                    && (activeFortifyNavScope.towerState.sameStuckPosCount >= 1
                    || activeFortifyNavScope.towerState.noRealProgressElapsedMs() > 0L);
            if (localTrap && localTargetDistSq <= 400.0D) {
                LOGGER.info("[FortifyGate] Suppressing gate route for local tower patch target={} dist={} (prefer local reroute/carve)",
                        target.toShortString(), String.format(Locale.ROOT, "%.1f", Math.sqrt(localTargetDistSq)));
                return false;
            }
        }
        if (activeFortifyNavScope != null
                && !activeFortifyNavScope.gateContext
                && !activeFortifyNavScope.towerPatchContext
                && isFortifyCarveContext(activeFortifyNavScope)) {
            double localTargetDistSq = bot.getBlockPos().getSquaredDistance(target);
            boolean entombedLocalEdge = localTargetDistSq <= 625.0D
                    && entombmentHelper.shouldPreferEntombmentEscape(world, bot.getBlockPos(), activeFortifyNavScope.context);
            if (entombedLocalEdge) {
                LOGGER.info("[FortifyGate] suppress gate route: entombed-local-edge pos={} target={} dist={}",
                        bot.getBlockPos().toShortString(),
                        target.toShortString(),
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(localTargetDistSq)));
                return false;
            }
        }

        int gateEdgeIdx = layout.gatehouseEdgeIndex();
        if (gateEdgeIdx < 0 || gateEdgeIdx >= layout.edges().size()) return false;

        WallEdge gateEdge = layout.edges().get(gateEdgeIdx);
        List<WallPoint> traced = VillageFortificationLayoutService.traceEdge(gateEdge.start(), gateEdge.end());
        if (traced.size() < 9) return false; // edge too short for a gatehouse

        // Gate center is at the midpoint of the traced edge (same logic as layout generation)
        WallPoint gateCenter = traced.get(traced.size() / 2);

        // Use the bot's actual Y for all navigation waypoints. safeSurfaceY is unreliable at
        // the gate center because the heightmap hits the stone brick lintel above the gap,
        // returning lintel Y instead of walkable ground Y. The Y difference (often 2+ blocks)
        // inflates 3D distance checks, preventing walkToTarget/walkTowardBlock from ever
        // considering the bot "arrived" at the waypoint.
        int navY = bot.getBlockPos().getY();
        BlockPos gateCenterPos = new BlockPos(gateCenter.x(), navY, gateCenter.z());

        // Gate exit: extend along outward normal until the point is verifiably outside the hull.
        // On large hulls, 4 blocks may land right on the boundary; we start at 6 and extend if needed.
        double edgeDx = gateEdge.end().x() - gateEdge.start().x();
        double edgeDz = gateEdge.end().z() - gateEdge.start().z();
        double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
        if (edgeLen < 0.001) return false;
        double nx = edgeDz / edgeLen;
        double nz = -edgeDx / edgeLen;

        BlockPos exitPos = null;
        for (int dist = 6; dist <= 20; dist += 2) {
            int ex = (int) Math.round(gateCenter.x() + nx * dist);
            int ez = (int) Math.round(gateCenter.z() + nz * dist);
            if (!VillageFortificationLayoutService.pointInConvexHull(hull, ex, ez)) {
                exitPos = new BlockPos(ex, navY, ez);
                break;
            }
        }
        if (exitPos == null) {
            LOGGER.warn("[FortifyGate] Could not find exit point outside hull along gate normal");
            return false;
        }

        // Interior approach: 3 blocks INWARD from gate center (no moat on interior side).
        int interiorX = (int) Math.round(gateCenter.x() - nx * 3);
        int interiorZ = (int) Math.round(gateCenter.z() - nz * 3);
        BlockPos interiorPos = new BlockPos(interiorX, navY, interiorZ);

        LOGGER.info("[FortifyGate] Bot inside hull at ({},{}). Routing through gatehouse edge {} " +
                        "interior={} gateCenter={} exit={}",
                botX, botZ, gateEdgeIdx, interiorPos.toShortString(),
                gateCenterPos.toShortString(), exitPos.toShortString());

        // Step 1: Navigate to interior approach point via multi-hop (may be 80+ blocks away).
        // This point is inside the hull, no moat, at surface level — safe for pathfinding.
        for (int hop = 0; hop < 3; hop++) {
            if (SkillManager.shouldAbortSkill(bot)) return false;
            double distSq = bot.squaredDistanceTo(
                    interiorX + 0.5, bot.getY(), interiorZ + 0.5);
            if (distSq <= 25.0D) break; // within 5 blocks — close enough

            LOGGER.info("[FortifyGate] hop {} to interior approach, dist={}", hop + 1,
                    String.format("%.0f", Math.sqrt(distSq)));
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, interiorPos, MovementService.MovementOptions.skillLoot());
            if (plan.isPresent() && !SkillManager.shouldAbortSkill(bot)) {
                MovementService.withoutDoorEscape(() ->
                        MovementService.withoutObstructionMining(
                                () -> MovementService.execute(source, bot, plan.get(), null)));
            }
            if (SkillManager.shouldAbortSkill(bot)) return false;

            // Close remaining gap with tick-by-tick walk
            double postDist = bot.squaredDistanceTo(
                    interiorX + 0.5, bot.getY(), interiorZ + 0.5);
            if (postDist > 9.0D) {
                FortifyNavRuntimeScope prior = beginFortifyNavScope("fortify-gate:interior-hop", activeFortifyNavScope != null ? activeFortifyNavScope.towerState : null,
                        activeFortifyNavScope != null ? activeFortifyNavScope.towerVertex : null, interiorPos, false, true);
                try {
                    walkToTarget(source, bot, interiorPos, 12_000L, "fortify-gate:interior-hop");
                } finally {
                    endFortifyNavScope(bot, world, prior);
                }
            }

            // Check if we made progress; if not, no point retrying
            double newDistSq = bot.squaredDistanceTo(
                    interiorX + 0.5, bot.getY(), interiorZ + 0.5);
            if (newDistSq >= distSq - 4.0D) {
                LOGGER.info("[FortifyGate] hop {} made no progress (dist still {}), stopping hops",
                        hop + 1, String.format("%.0f", Math.sqrt(newDistSq)));
                break;
            }
        }
        if (SkillManager.shouldAbortSkill(bot)) return false;

        // Final close-range approach to interior point
        double finalDistToInterior = bot.squaredDistanceTo(
                interiorX + 0.5, bot.getY(), interiorZ + 0.5);
        if (finalDistToInterior > 9.0D) {
            FortifyNavRuntimeScope prior = beginFortifyNavScope("fortify-gate:interior-final", activeFortifyNavScope != null ? activeFortifyNavScope.towerState : null,
                    activeFortifyNavScope != null ? activeFortifyNavScope.towerVertex : null, interiorPos, false, true);
            try {
                walkToTarget(source, bot, interiorPos, 10_000L, "fortify-gate:interior-final");
            } finally {
                endFortifyNavScope(bot, world, prior);
            }
        }
        if (SkillManager.shouldAbortSkill(bot)) return false;

        // Bail if still far — something is blocking the interior path
        finalDistToInterior = bot.squaredDistanceTo(
                interiorX + 0.5, bot.getY(), interiorZ + 0.5);
        if (finalDistToInterior > 100.0D) { // > 10 blocks
            LOGGER.warn("[FortifyGate] Could not reach interior approach (dist={}). Giving up.",
                    String.format("%.0f", Math.sqrt(finalDistToInterior)));
            return false;
        }

        // Step 2: Walk to gate center (1-3 blocks from interior, through the gap opening)
        LOGGER.info("[FortifyGate] At interior (dist={}). Walking to gate center={}",
                String.format("%.1f", Math.sqrt(finalDistToInterior)), gateCenterPos.toShortString());
        FortifyNavRuntimeScope gateCenterScope = beginFortifyNavScope("fortify-gate:center", activeFortifyNavScope != null ? activeFortifyNavScope.towerState : null,
                activeFortifyNavScope != null ? activeFortifyNavScope.towerVertex : null, gateCenterPos, false, true);
        try {
            walkToTarget(source, bot, gateCenterPos, 8_000L, "fortify-gate:center");
        } finally {
            endFortifyNavScope(bot, world, gateCenterScope);
        }
        if (SkillManager.shouldAbortSkill(bot)) return false;

        // Step 3: Walk through gate to exit position (across the moat bridge)
        LOGGER.info("[FortifyGate] Walking through gate to exit={}", exitPos.toShortString());
        FortifyNavRuntimeScope gateExitScope = beginFortifyNavScope("fortify-gate:exit", activeFortifyNavScope != null ? activeFortifyNavScope.towerState : null,
                activeFortifyNavScope != null ? activeFortifyNavScope.towerVertex : null, exitPos, false, true);
        try {
            walkToTarget(source, bot, exitPos, 12_000L, "fortify-gate:exit");
        } finally {
            endFortifyNavScope(bot, world, gateExitScope);
        }

        // Verify we made it outside
        int newBotX = bot.getBlockPos().getX();
        int newBotZ = bot.getBlockPos().getZ();
        boolean stillInside = VillageFortificationLayoutService.pointInConvexHull(hull, newBotX, newBotZ);
        if (stillInside) {
            // Retry: walk further along the normal to clear the hull boundary
            LOGGER.info("[FortifyGate] Still inside at ({},{}), extending walk along normal", newBotX, newBotZ);
            int extX = (int) Math.round(newBotX + nx * 10);
            int extZ = (int) Math.round(newBotZ + nz * 10);
            walkToTarget(source, bot, new BlockPos(extX, bot.getBlockPos().getY(), extZ), 10_000L);

            newBotX = bot.getBlockPos().getX();
            newBotZ = bot.getBlockPos().getZ();
            stillInside = VillageFortificationLayoutService.pointInConvexHull(hull, newBotX, newBotZ);
        }

        if (stillInside) {
            gateRoutingFailures++;
            LOGGER.warn("[FortifyGate] Still inside hull after gate routing at ({},{}). " +
                    "Fallback to normal navigation. (failure #{})", newBotX, newBotZ, gateRoutingFailures);
            // Last resort: attempt a controlled carve along gate normal through layout blocks only
            boolean carved = gateExitClearCorridor(source, bot, world, gateCenterPos, exitPos);
            if (!carved) {
                return false;
            }
        }

        if (!cleanupHelper.queue.isEmpty()) {
            processDeferredFortifyCleanupQueue(bot, world, "fortify-gate:post-success");
        }
        gateRoutingFailures = 0; // reset on success
        LOGGER.info("[FortifyGate] Successfully exited hull at ({},{})", newBotX, newBotZ);
        return true;
    }

    /**
     * As a last resort when gate routing leaves the bot inside the hull, clear a 1x3 column
     * along the gate outward normal (gate center → exit) through fortification layout blocks only.
     * Mines feet/head/overhead, walks through, then replaces any mined layout blocks.
     */
    private boolean gateExitClearCorridor(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world,
                                          BlockPos gateCenterPos, BlockPos exitPos) {
        if (bot == null || world == null || gateCenterPos == null || exitPos == null) return false;
        if (currentLayout == null) return false;

        // Direction from gate center to exit (outward normal)
        int dx = Integer.signum(exitPos.getX() - gateCenterPos.getX());
        int dz = Integer.signum(exitPos.getZ() - gateCenterPos.getZ());
        if (dx == 0 && dz == 0) return false;

        BlockPos start = bot.getBlockPos();
        List<BlockPos> mined = new ArrayList<>();

        for (int step = 0; step < 4; step++) { // up to 3 blocks out (inclusive of 0)
            BlockPos columnBase = gateCenterPos.add(dx * step, 0, dz * step);
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos pos = columnBase.up(dy);
                if (!fortificationProtectedPositions.contains(pos)) continue;
                BlockState state = world.getBlockState(pos);
                if (state.getCollisionShape(world, pos).isEmpty()) continue;
                if (!isWithinMiningReach(bot, pos)) continue;
                if (digBlockForNavigation(bot, world, pos)) {
                    mined.add(pos.toImmutable());
                }
            }
        }

        BlockPos walkTarget = exitPos;
        walkToTarget(source, bot, walkTarget, 3_500L, "fortify-gate:carve-exit");

        // Replace mined blocks (mandatory — they are layout blocks)
        for (BlockPos pos : mined) {
            BlockState original = currentLayoutBlockState(pos);
            if (original != null) {
                replaceMinedBlock(bot, world, pos, original, true);
            }
        }

        boolean outside = !isInsideCurrentFortificationHull(bot);
        if (outside) {
            LOGGER.info("[FortifyGate] Carve-exit cleared {} blocks and exited hull", mined.size());
        } else {
            LOGGER.warn("[FortifyGate] Carve-exit failed to exit hull (moved={} mined={})",
                    !start.equals(bot.getBlockPos()), mined.size());
        }
        return outside;
    }

    private BlockState currentLayoutBlockState(BlockPos pos) {
        if (currentLayout == null || pos == null) return null;
        for (ProceduralWallBlock b : currentLayout.allBlocks()) {
            if (pos.equals(b.worldPos())) return b.state();
        }
        return null;
    }

    /**
     * Reposition for better access to an edge. Cycles through 4 vantage points:
     * outside-start, outside-end, inside-start, inside-end.
     */
    private void repositionForEdge(ServerCommandSource source, ServerPlayerEntity bot,
                                    ServerWorld world, WallEdge edge, SurfaceProfile surfaceProfile, int attempt) {
        double edgeDx = edge.end().x() - edge.start().x();
        double edgeDz = edge.end().z() - edge.start().z();
        double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
        if (edgeLen < 0.001) return;

        // Outward normal
        double nx = edgeDz / edgeLen;
        double nz = -edgeDx / edgeLen;

        int mode = Math.floorMod(attempt, 4);
        int targetX, targetZ;
        int offset = 3;

        switch (mode) {
            case 0 -> { // Outside start
                targetX = (int) Math.round(edge.start().x() + nx * offset);
                targetZ = (int) Math.round(edge.start().z() + nz * offset);
            }
            case 1 -> { // Outside end
                targetX = (int) Math.round(edge.end().x() + nx * offset);
                targetZ = (int) Math.round(edge.end().z() + nz * offset);
            }
            case 2 -> { // Inside start
                targetX = (int) Math.round(edge.start().x() - nx * offset);
                targetZ = (int) Math.round(edge.start().z() - nz * offset);
            }
            default -> { // Inside end
                targetX = (int) Math.round(edge.end().x() - nx * offset);
                targetZ = (int) Math.round(edge.end().z() - nz * offset);
            }
        }

        int targetY = safeSurfaceY(surfaceProfile, world, targetX, targetZ);

        LOGGER.debug("Repositioning to mode {} at ({},{},{})", mode, targetX, targetY, targetZ);
        BlockPos repositionTarget = new BlockPos(targetX, targetY, targetZ);
        runWithFortifyEdgeNavScope(bot, world, "fortify-edge:reposition", repositionTarget,
                () -> walkToTarget(source, bot, repositionTarget, 5_000L, "fortify-edge:reposition"));
    }

    /**
     * Compute the original surface Y level from the layout's FOUNDATION blocks near the bot.
     * This is immune to moat digging since foundations were placed at the original terrain level.
     * Falls back to hull vertex average if no nearby foundations found.
     */
    private int computeReferenceSurfaceY(ServerPlayerEntity bot, FortificationLayout layout, ServerWorld world) {
        BlockPos botPos = bot.getBlockPos();
        int bestY = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;

        // Find the nearest FOUNDATION block — its Y is the original terrain level
        for (ProceduralWallBlock b : layout.allBlocks()) {
            if (b.type() == WallBlockType.FOUNDATION) {
                double dx = b.worldPos().getX() - botPos.getX();
                double dz = b.worldPos().getZ() - botPos.getZ();
                double distSq = dx * dx + dz * dz;
                // Prefer the closest foundation, or the highest if equidistant
                if (distSq < bestDistSq - 1 || (distSq < bestDistSq + 1 && b.worldPos().getY() > bestY)) {
                    bestY = b.worldPos().getY();
                    bestDistSq = distSq;
                }
            }
        }

        if (bestY != Integer.MIN_VALUE) {
            LOGGER.debug("Reference surface Y={} from nearest FOUNDATION block (dist={})", bestY, (int) Math.sqrt(bestDistSq));
            return bestY;
        }

        // Fallback: use the average hull vertex terrain Y
        int sum = 0;
        for (WallPoint v : layout.hullVertices()) {
            sum += VillageFortificationLayoutService.terrainY(world, v.x(), v.z());
        }
        int avgY = layout.hullVertices().isEmpty() ? botPos.getY()
                : sum / layout.hullVertices().size();
        LOGGER.debug("Reference surface Y={} from hull vertex average (no nearby foundations)", avgY);
        return avgY;
    }

    private SurfaceProfile createSurfaceProfile(FortificationLayout layout, int referenceSurfaceY) {
        Map<Long, Integer> plannedYByXZ = new HashMap<>();
        for (ProceduralWallBlock block : layout.allBlocks()) {
            if (block.type() != WallBlockType.FOUNDATION && block.type() != WallBlockType.TOWER_BASE) {
                continue;
            }
            long key = packXZ(block.worldPos().getX(), block.worldPos().getZ());
            plannedYByXZ.merge(key, block.worldPos().getY(), Math::max);
        }
        return new SurfaceProfile(referenceSurfaceY, plannedYByXZ);
    }

    private int safeSurfaceY(SurfaceProfile profile, ServerWorld world, int x, int z) {
        int terrainY = VillageFortificationLayoutService.terrainY(world, x, z);
        return safeSurfaceY(profile.referenceSurfaceY(), profile.plannedYByXZ(), terrainY, x, z);
    }

    static int safeSurfaceY(int referenceSurfaceY, Map<Long, Integer> plannedYByXZ, int terrainY, int x, int z) {
        return FortifyExecutionPolicyUtil.safeSurfaceY(referenceSurfaceY, plannedYByXZ, terrainY, x, z);
    }

    static int segmentBucketForTest(int startX, int startZ, int endX, int endZ, int x, int z, double segSize) {
        return FortifyExecutionPolicyUtil.segmentBucketForLine(startX, startZ, endX, endZ, x, z, segSize);
    }

    static boolean shouldStopAfterNoProgressSegments(int noProgressSegments, int threshold) {
        return FortifyExecutionPolicyUtil.shouldStopAfterNoProgressSegments(noProgressSegments, threshold);
    }

    private int segmentBucket(WallEdge edge, double dX, double dZ, double segSize, BlockPos pos) {
        double px = pos.getX() - edge.start().x();
        double pz = pos.getZ() - edge.start().z();
        return (int) Math.floor((px * dX + pz * dZ) / segSize);
    }

    private int executeLocalPlacementBatch(ServerCommandSource source,
                                           ServerPlayerEntity bot,
                                           ServerWorld world,
                                           List<ProceduralWallBlock> blocks,
                                           String taskId,
                                           String groupId,
                                           int referenceSurfaceY,
                                           SurfaceProfile surfaceProfile,
                                           ExecutionPolicy executionPolicy,
                                           BlockPos anchorPos,
                                           PlacementTarget.TargetKind targetKind) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }

        List<ProceduralWallBlock> digBlocks = new ArrayList<>();
        List<ProceduralWallBlock> placeBlocks = new ArrayList<>();
        for (ProceduralWallBlock block : blocks) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            if (block.type() == WallBlockType.MOAT_DIG || block.type() == WallBlockType.EXTERIOR_CLEAR) {
                digBlocks.add(block);
            } else {
                placeBlocks.add(block);
            }
        }

        int placedCount = 0;
        long batchStartMs = System.currentTimeMillis();

        digBlocks.sort(Comparator.comparingInt((ProceduralWallBlock b) -> -b.worldPos().getY()));
        for (ProceduralWallBlock block : digBlocks) {
            if (abortFortifyPhase(bot, "local-batch:dig", batchStartMs)) {
                return placedCount;
            }
            BlockState current = world.getBlockState(block.worldPos());
            if (current.isAir()) {
                placedCount++;
                continue;
            }

            double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(block.worldPos()));
            if (distSq > 400) continue;
            if (distSq > 25) {
                walkTowardBlock(bot, block.worldPos(), 1_500L);
            }

            if (digBlock(bot, world, block.worldPos())) {
                placedCount++;
            }
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
        }

        if (placeBlocks.isEmpty()) {
            return placedCount;
        }

        placeBlocks.sort(Comparator.comparingInt((ProceduralWallBlock b) -> b.worldPos().getY())
                .thenComparingInt(b -> placePriority(b.type())));

        Map<BlockPos, ProceduralWallBlock> blockMap = new HashMap<>();
        Set<BlockPos> remaining = new LinkedHashSet<>();
        List<PlacementTarget> targets = new ArrayList<>(placeBlocks.size());
        Set<BlockPos> scaffoldFailedPositions = new HashSet<>();

        for (ProceduralWallBlock block : placeBlocks) {
            // Pre-filter: skip blocks already satisfied (avoids BLOCKED_BY_SOLID waste)
            BlockState current = world.getBlockState(block.worldPos());
            if (isPlannedBlockSatisfied(block, current)) {
                continue;
            }
            remaining.add(block.worldPos());
            blockMap.put(block.worldPos(), block);
            targets.add(new PlacementTarget(
                    block.worldPos(),
                    block.state(),
                    targetKind,
                    placePriority(block.type()),
                    groupId
            ));
        }

        int[] repositionAttempt = new int[]{0};
        ScaffoldService.ScaffoldSession scaffoldSession = ScaffoldService.beginSession(bot);

        ConstructionTaskSpec spec = new ConstructionTaskSpec(
                taskId,
                world,
                bot,
                source,
                targets,
                executionPolicy,
                new ConstructionTaskSpec.SupportPolicy(true, true, MAX_SCAFFOLD_HEIGHT),
                (target, pass) -> {
                    if (isWithinReach(bot, target.pos())) {
                        return ConstructionRecoveryService.RecoveryResult.success(false);
                    }
                    if (countBuildingBlocks(bot) == 0) {
                        return ConstructionRecoveryService.RecoveryResult.failure(FailureReason.NO_MATERIAL, false);
                    }
                    BlockPos beforePos = bot.getBlockPos();
                    double beforeDistSq = beforePos.getSquaredDistance(target.pos());
                    boolean unwedged = false;
                    if (shouldAttemptReachUnwedge(world, bot, target.pos(), pass)) {
                        unwedged = tryUnwedgeFromTightSpace(
                                source, bot, world, surfaceProfile, anchorPos,
                                taskId + ":reach-pass-" + pass);
                    }
                    int terrainY = safeSurfaceY(surfaceProfile, world, target.pos().getX(), target.pos().getZ());
                    int heightAboveGround = target.pos().getY() - terrainY;
                    boolean canReach = ensureCanReachBlockWithEffort(
                            source,
                            bot,
                            world,
                            target.pos(),
                            heightAboveGround,
                            pass,
                            referenceSurfaceY,
                            scaffoldFailedPositions
                    );
                    BlockPos afterPos = bot.getBlockPos();
                    double afterDistSq = afterPos.getSquaredDistance(target.pos());
                    double movedSq = beforePos.getSquaredDistance(afterPos);
                    boolean progress = unwedged
                            || movedSq >= 1.0D
                            || (beforeDistSq - afterDistSq) >= 1.0D;
                    return canReach
                            ? ConstructionRecoveryService.RecoveryResult.success(progress)
                            : ConstructionRecoveryService.RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, progress);
                },
                (target, pass) -> {
                    if (countBuildingBlocks(bot) == 0) {
                        return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.NO_MATERIAL);
                    }
                    boolean scaffoldSneak = beginScaffoldEdgeHold(bot, world, target.pos());
                    try {
                        if (shouldAvoidSelfTrapPlacement(world, bot, target.pos())) {
                            int removed = clearBlockingScaffoldsNearBot(bot, world, 1);
                            if (removed > 0) {
                                return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.MOVEMENT_FAILED, true);
                            }
                            boolean moved = tryUnwedgeFromTightSpace(
                                    source, bot, world, surfaceProfile, anchorPos,
                                    taskId + ":placement-guard-pass-" + pass);
                            return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.MOVEMENT_FAILED, moved);
                        }
                        BotActions.PlaceResult placed = tryPlaceBlock(bot, world, target.pos(), target.desiredState());
                        if (placed.success()) {
                            remaining.remove(target.pos());
                            sleepQuiet(BLOCK_PLACE_DELAY_MS);
                            return ConstructionTaskSpec.PlacementOutcome.ok();
                        }
                        if (placed.reason() != null && placed.reason().startsWith("no-solid-support")) {
                            boolean filled = fillGroundUnder(bot, world, target.pos());
                            return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.NO_SUPPORT, filled);
                        }
                        // NO_LOS recovery: face the target and retry once
                        if (placed.reason() != null && placed.reason().startsWith("no-line-of-sight")) {
                            LookController.faceBlock(bot, target.pos());
                            sleepQuiet(50);
                            BotActions.PlaceResult retry = tryPlaceBlock(bot, world, target.pos(), target.desiredState());
                            if (retry.success()) {
                                remaining.remove(target.pos());
                                sleepQuiet(BLOCK_PLACE_DELAY_MS);
                                return ConstructionTaskSpec.PlacementOutcome.ok();
                            }
                        }
                        return ConstructionTaskSpec.PlacementOutcome.fail(FailureReason.fromPlaceReason(placed.reason()));
                    } finally {
                        endScaffoldEdgeHold(bot, scaffoldSneak);
                    }
                },
                progress -> {
                    remaining.removeIf(pos -> {
                        ProceduralWallBlock planned = blockMap.get(pos);
                        return planned != null && isPlannedBlockSatisfied(planned, world.getBlockState(pos));
                    });
                    escapeIfInHole(bot, world, referenceSurfaceY);
                },
                (progress, noProgressStreak) -> {
                    if (noProgressStreak >= executionPolicy.noProgressPasses()) {
                        return;
                    }
                    if (clearBlockingScaffoldsNearBot(bot, world, 1) > 0) {
                        return;
                    }
                    if (repositionAttempt[0] >= MAX_REPOSITION_ATTEMPTS_PER_BATCH) {
                        BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 3);
                        if (safe != null && !safe.equals(bot.getBlockPos())) {
                            walkToTarget(source, bot, safe, 1_200L);
                        }
                        repositionAttempt[0] = 0;
                        return;
                    }
                    if (tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, anchorPos,
                            taskId + ":no-progress-" + noProgressStreak)) {
                        return;
                    }
                    if (anchorPos != null && tryWideArcReachReposition(source, bot, world, anchorPos)) {
                        return;
                    }
                    repositionNearAnchor(source, bot, world, anchorPos, surfaceProfile, repositionAttempt[0]);
                    repositionAttempt[0]++;
                },
                scaffoldSession,
                false,
                Set.of()
        );

        ExecutionReport report = ConstructionExecutionService.execute(spec);
        LOGGER.debug("[Fortify] local batch taskId={} placed={} remaining={} failures={}",
                taskId, report.placedCount(), report.remainingCount(), report.remainingByReason());
        return placedCount + report.placedCount();
    }

    private int countActivePlannedBlocks(List<ProceduralWallBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ProceduralWallBlock block : blocks) {
            if (isActiveFortifyBlock(block)) {
                count++;
            }
        }
        return count;
    }

    private boolean isTowerComplete(int presentCount, int plannedCount) {
        if (plannedCount <= 0) {
            return true;
        }
        int required = (int) Math.ceil(plannedCount * TOWER_COMPLETION_TARGET_RATIO);
        return presentCount >= Math.max(1, required);
    }

    private List<WallPoint> orderAndDedupeTowerVertices(List<WallPoint> hullVertices, BlockPos origin) {
        if (hullVertices == null || hullVertices.isEmpty()) {
            return List.of();
        }
        int ox = origin != null ? origin.getX() : 0;
        int oz = origin != null ? origin.getZ() : 0;

        List<WallPoint> ordered = new ArrayList<>(hullVertices);
        ordered.sort(Comparator
                .comparingDouble((WallPoint v) -> {
                    double dx = v.x() - ox;
                    double dz = v.z() - oz;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(WallPoint::x)
                .thenComparingInt(WallPoint::z));

        List<WallPoint> deduped = new ArrayList<>();
        for (WallPoint candidate : ordered) {
            boolean duplicate = false;
            for (WallPoint existing : deduped) {
                int dx = candidate.x() - existing.x();
                int dz = candidate.z() - existing.z();
                if ((dx * dx + dz * dz) <= TOWER_VERTEX_DEDUP_DISTANCE_SQ) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    private Map<Integer, List<ProceduralWallBlock>> groupTowerBlocksByNearestVertex(List<ProceduralWallBlock> towerBlocks,
                                                                                     List<WallPoint> towerVertices) {
        Map<Integer, List<ProceduralWallBlock>> byVertex = new LinkedHashMap<>();
        for (int i = 0; i < towerVertices.size(); i++) {
            byVertex.put(i, new ArrayList<>());
        }
        if (towerVertices.isEmpty() || towerBlocks == null || towerBlocks.isEmpty()) {
            return byVertex;
        }

        for (ProceduralWallBlock block : towerBlocks) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            int nearestVi = 0;
            double nearestDistSq = Double.MAX_VALUE;
            for (int vi = 0; vi < towerVertices.size(); vi++) {
                WallPoint vertex = towerVertices.get(vi);
                double dx = block.worldPos().getX() - vertex.x();
                double dz = block.worldPos().getZ() - vertex.z();
                double distSq = dx * dx + dz * dz;
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestVi = vi;
                }
            }
            byVertex.computeIfAbsent(nearestVi, ignored -> new ArrayList<>()).add(block);
        }
        return byVertex;
    }

    private int executeTowerVertexWithRetries(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              List<ProceduralWallBlock> vertexBlocks,
                                              String taskPrefix,
                                              String groupPrefix,
                                              int vertexOrdinal,
                                              int totalVertices,
                                              int referenceSurfaceY,
                                              SurfaceProfile surfaceProfile) {
        int plannedCount = countActivePlannedBlocks(vertexBlocks);
        if (plannedCount <= 0) {
            return 0;
        }

        int presentCount = countPresentBlocks(world, vertexBlocks);
        int newlyPlaced = 0;
        int noProgressAttempts = 0;
        BlockPos lastAttemptPos = null;
        int stagnantAttemptStreak = 0;
        int hardResetNoProgressStreak = 0;
        TowerNavAttemptState towerNavState = new TowerNavAttemptState(vertex);

        // Track overall time for the entire tower vertex (approach + local placement + scaffold).
        // Prevents a single stuck tower from monopolizing the session.
        long towerOverallStartMs = System.currentTimeMillis();

        // Long-range navigation: if bot is far from this tower, use MovementService
        // to get to the general area before starting short-range local approach retries.
        double distToTowerSq = bot.squaredDistanceTo(vertex.x() + 0.5, bot.getY(), vertex.z() + 0.5);
        if (distToTowerSq > 400.0D) { // > 20 blocks away
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0, vertexBlocks, towerNavState);
            if (towerApproach != null) {
                // Avoid huge gate detours for near-local tower patch recovery; prefer local reroute/carve.
                if (distToTowerSq > 625.0D) { // > 25 blocks
                    FortifyNavRuntimeScope priorGateScope = beginFortifyNavScope(
                            "fortify-tower:pre-gate", towerNavState, vertex, towerApproach, true, false);
                    try {
                        navigateThroughGateIfNeeded(source, bot, world, towerApproach, surfaceProfile);
                    } finally {
                        endFortifyNavScope(bot, world, priorGateScope);
                    }
                } else {
                    LOGGER.info("[FortifyTower] skipping pre-gate route for local tower target {} dist={}",
                            towerApproach.toShortString(), String.format(Locale.ROOT, "%.1f", Math.sqrt(distToTowerSq)));
                }
                if (SkillManager.shouldAbortSkill(bot)) return 0;
                Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                        bot, towerApproach, MovementService.MovementOptions.skillLoot());
                if (plan.isPresent() && !SkillManager.shouldAbortSkill(bot)) {
                    LOGGER.info("[FortifyTower] long-range nav to tower {}/{} approach={} dist={}",
                            vertexOrdinal + 1, totalVertices, towerApproach.toShortString(),
                            String.format("%.0f", Math.sqrt(distToTowerSq)));
                    // Suppress obstruction mining and door traversal during fortify navigation
                    MovementService.withoutDoorEscape(() ->
                            MovementService.withoutObstructionMining(
                                    () -> MovementService.execute(source, bot, plan.get(), null)));
                }
                // walkToTarget closes any remaining gap with built-in break-through
                double postDistSq = bot.squaredDistanceTo(towerApproach.getX() + 0.5, bot.getY(), towerApproach.getZ() + 0.5);
                if (postDistSq > 9.0D && !SkillManager.shouldAbortSkill(bot)) {
                    runWithFortifyTowerNavScope(bot, world, "fortify-tower:long-range-close",
                            towerNavState, vertex, towerApproach,
                            () -> walkToTarget(source, bot, towerApproach, 6_000L, "fortify-tower:long-range-close"));
                }
            }
        }

        long towerStartMs = System.currentTimeMillis();

        for (int attempt = 1; attempt <= TOWER_LOCAL_MAX_ATTEMPTS; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            if (countBuildingBlocks(bot) == 0) {
                break;
            }
            if (isTowerComplete(presentCount, plannedCount)) {
                break;
            }
            // Overall time cap: includes approach navigation + local placement.
            // Prevents a single tower from monopolizing the session (approach alone can take 15-20s).
            long overallElapsedMs = System.currentTimeMillis() - towerOverallStartMs;
            if (overallElapsedMs > TOWER_VERTEX_TIME_BUDGET_MS) {
                LOGGER.info("[FortifyTower] tower {}/{} hit overall time budget ({}s) placed={}, aborting",
                        vertexOrdinal + 1, totalVertices,
                        overallElapsedMs / 1000, newlyPlaced);
                break;
            }
            // Safety net: bail out after a time budget with zero blocks placed.
            // If the bot is at a known failed sub-surface position, use a shorter budget (8s)
            // to avoid burning 20+ seconds revisiting the same stuck trap.
            boolean atKnownBadPos = towerNavState != null
                    && towerNavState.isKnownSubSurfaceFailure(bot.getBlockPos());
            long zeroBudgetMs = atKnownBadPos ? 8_000L : 20_000L;
            if (newlyPlaced == 0 && System.currentTimeMillis() - towerStartMs > zeroBudgetMs) {
                LOGGER.info("[FortifyTower] tower {}/{} timed out after {}s with 0 blocks placed{}, skipping",
                        vertexOrdinal + 1, totalVertices,
                        (System.currentTimeMillis() - towerStartMs) / 1000,
                        atKnownBadPos ? " (known bad sub-surface pos)" : "");
                break;
            }

            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, attempt - 1);
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attempt - 1, vertexBlocks, towerNavState);
            boolean approached = moveToTowerApproach(
                    source, bot, world, vertex, towerApproach, surfaceProfile,
                    taskPrefix + ":" + vertexOrdinal + ":approach-" + attempt,
                    towerNavState);

            if (!approached) {
                TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                        source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal, towerNavState);
                if (hardReset.moved()) {
                    if (hardReset.meaningful()) {
                        noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                        hardResetNoProgressStreak = 0;
                    } else {
                        hardResetNoProgressStreak++;
                        LOGGER.info("[FortifyTower] hard-reset movement was non-meaningful tower=({}, {}) streak={}/{}",
                                vertex.x(), vertex.z(), hardResetNoProgressStreak, TOWER_HARD_RESET_NO_PROGRESS_LIMIT);
                        if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                            LOGGER.info("[FortifyTower] skipping remaining local retries for tower ({},{}) due to repeated non-meaningful hard resets",
                                    vertex.x(), vertex.z());
                            break;
                        }
                    }
                    continue;
                }
                hardResetNoProgressStreak++;
                noProgressAttempts++;
                if (noProgressAttempts >= TOWER_LOCAL_NO_PROGRESS_LIMIT) {
                    break;
                }
                if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                    LOGGER.info("[FortifyTower] aborting local retries for tower ({},{}) due to hard-reset no-progress streak",
                            vertex.x(), vertex.z());
                    break;
                }
                continue;
            }
            hardResetNoProgressStreak = 0;

            BlockPos botPos = bot.getBlockPos();
            if (Math.abs(botPos.getX() - vertex.x()) <= 1 && Math.abs(botPos.getZ() - vertex.z()) <= 1) {
                tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, towerApproach,
                        taskPrefix + ":" + vertexOrdinal + ":inside-footprint-" + attempt);
            }

            int before = presentCount;
            int reportedPlaced = executeLocalPlacementBatch(
                    source,
                    bot,
                    world,
                    vertexBlocks,
                    taskPrefix + ":" + vertexOrdinal + ":attempt-" + attempt,
                    groupPrefix + ":" + vertexOrdinal,
                    referenceSurfaceY,
                    surfaceProfile,
                    new ExecutionPolicy(4, 3, 2, TOWER_VERTEX_TIME_BUDGET_MS),
                    towerApproach,
                    PlacementTarget.TargetKind.FORTIFY_TOWER
            );

            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, attempt);
            presentCount = countPresentBlocks(world, vertexBlocks);
            int gained = Math.max(0, presentCount - before);
            newlyPlaced += gained;

            if (gained > 0) {
                noProgressAttempts = 0;
                stagnantAttemptStreak = 0;
            } else {
                noProgressAttempts++;
                tryUnwedgeFromTightSpace(source, bot, world, surfaceProfile, towerApproach,
                        taskPrefix + ":" + vertexOrdinal + ":no-progress-" + attempt);
                TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                        source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal, towerNavState);
                if (hardReset.moved() && hardReset.meaningful()) {
                    noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                    hardResetNoProgressStreak = 0;
                } else {
                    hardResetNoProgressStreak++;
                }
            }

            BlockPos attemptPos = bot.getBlockPos();
            if (lastAttemptPos != null && lastAttemptPos.equals(attemptPos) && gained == 0) {
                stagnantAttemptStreak++;
                if (stagnantAttemptStreak >= 2) {
                    TowerHardResetResult hardReset = tryTowerHardResetPositionDetailed(
                            source, bot, world, vertex, surfaceProfile,
                            attempt + vertexOrdinal + stagnantAttemptStreak, towerNavState);
                    if (hardReset.moved() && hardReset.meaningful()) {
                        noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                        hardResetNoProgressStreak = 0;
                    } else {
                        hardResetNoProgressStreak++;
                    }
                }
            } else {
                stagnantAttemptStreak = 0;
            }
            lastAttemptPos = attemptPos.toImmutable();

            LOGGER.info("[FortifyTower] tower={}/{} pos=({}, {}) attempt={} gained={} reportedPlaced={} present={}/{}",
                    vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z(), attempt,
                    gained, reportedPlaced, presentCount, plannedCount);

            if (noProgressAttempts >= TOWER_LOCAL_NO_PROGRESS_LIMIT) {
                break;
            }
            if (hardResetNoProgressStreak >= TOWER_HARD_RESET_NO_PROGRESS_LIMIT) {
                LOGGER.info("[FortifyTower] ending local tower retries early tower=({}, {}) hardResetNoProgressStreak={}",
                        vertex.x(), vertex.z(), hardResetNoProgressStreak);
                break;
            }
        }

        // ── Scaffold phase: reach upper tower layers from elevated position ──
        presentCount = countPresentBlocks(world, vertexBlocks);
        if (!isTowerComplete(presentCount, plannedCount)
                && !SkillManager.shouldAbortSkill(bot)
                && countBuildingBlocks(bot) > 0
                && (System.currentTimeMillis() - towerOverallStartMs) < TOWER_VERTEX_TIME_BUDGET_MS) {
            int scaffoldGained = executeTowerScaffoldPhase(
                    source, bot, world, vertex, vertexBlocks, surfaceProfile,
                    vertexOrdinal, totalVertices, plannedCount, referenceSurfaceY, towerNavState);
            newlyPlaced += scaffoldGained;
        }

        if (newlyPlaced == 0 && !isTowerComplete(countPresentBlocks(world, vertexBlocks), plannedCount)) {
            int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
            int depth = terrainY - bot.getBlockPos().getY();
            LOGGER.info("[FortifyTower] zero-progress tower vertex={}/{} pos=({}, {}) botPos={} trapLike={} depth={} noProgressAttempts={} sameStuckPosCount={}",
                    vertexOrdinal + 1, totalVertices,
                    vertex.x(), vertex.z(),
                    bot.getBlockPos().toShortString(),
                    isTrapLikeCell(world, bot.getBlockPos()),
                    depth,
                    noProgressAttempts,
                    towerNavState != null ? towerNavState.sameStuckPosCount : 0);
        }

        return newlyPlaced;
    }

    // ── Tower scaffold phase ──────────────────────────────────────

    private static final long TOWER_SCAFFOLD_TIME_BUDGET_MS = 60_000L;
    private static final int TOWER_SCAFFOLD_MAX_SIDES = 6;
    private static final long TOWER_SCAFFOLD_STEP_TIMEOUT_MS = 900L;
    private static final long TOWER_SCAFFOLD_RETURN_TIMEOUT_MS = 900L;
    private static final int TOWER_TOP_VERIFY_MAX_ATTEMPTS = 4;
    private static final long TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS = 320L;
    private static final double TOWER_SCAFFOLD_RECENTER_TOLERANCE_SQ = 0.14D * 0.14D;
    private static final int TOWER_SCAFFOLD_NO_HEADROOM_SAME_POS_LIMIT = 2;
    private static final int TOWER_HARD_RESET_NO_PROGRESS_LIMIT = 3;

    /**
     * Scaffold phase for incomplete towers: pillar up on cardinal sides and place
     * remaining upper blocks from an elevated position, then tear down the scaffold.
     */
    private int executeTowerScaffoldPhase(ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          ServerWorld world,
                                          WallPoint vertex,
                                          List<ProceduralWallBlock> vertexBlocks,
                                          SurfaceProfile surfaceProfile,
                                          int vertexOrdinal,
                                          int totalVertices,
                                          int plannedCount,
                                          int referenceSurfaceY,
                                          TowerNavAttemptState towerNavState) {
        int presentCount = countPresentBlocks(world, vertexBlocks);
        if (isTowerComplete(presentCount, plannedCount)) {
            return 0;
        }

        // Find the highest target Y among unsatisfied blocks
        int maxTargetY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (!isActiveFortifyBlock(block)) continue;
            BlockState current = world.getBlockState(block.worldPos());
            if (!isPlannedBlockSatisfied(block, current)) {
                maxTargetY = Math.max(maxTargetY, block.worldPos().getY());
            }
        }
        if (maxTargetY == Integer.MIN_VALUE) {
            return 0;
        }

        // Optimal scaffold Y: puts bot feet at the highest target block level.
        // This allows stepping onto placed wall blocks at that height, and the
        // bot's eye (Y+1.62) has downward reach to all lower blocks.
        int optimalY = maxTargetY;
        int groundY = safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        if (optimalY <= groundY) {
            return 0; // no benefit from scaffolding
        }

        LOGGER.info("[FortifyTower] scaffold phase for tower {}/{} pos=({},{}) present={}/{} optimalY={}",
                vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z(),
                presentCount, plannedCount, optimalY);
        showOverhead(bot, "Scaffolding tower " + (vertexOrdinal + 1) + "/" + totalVertices);

        if (towerNavState != null && towerNavState.sameStuckPosCount >= 1 && towerNavState.noRealProgressElapsedMs() >= 2_000L) {
            FortifyNavRuntimeScope prior = beginFortifyNavScope("fortify-tower:scaffold-pre-escape", towerNavState, vertex, bot.getBlockPos(), true, false);
            try {
                if (towerNavState.navMode != FortifyNavMode.CARVE_CORRIDOR) {
                    towerNavState.activateCarveMode();
                    if (activeFortifyNavScope != null) activeFortifyNavScope.navMode = FortifyNavMode.CARVE_CORRIDOR;
                    if (activeFortifyNavScope != null && activeFortifyNavScope.carveSession == null) {
                        activeFortifyNavScope.carveSession = new FortifyCarveSession(bot.getBlockPos(), "tower-scaffold-pre-escape");
                    }
                    LOGGER.info("[FortifyTower] scaffold pre-escape activating carve mode for tower ({},{})",
                            vertex.x(), vertex.z());
                }
                BlockPos escape = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, 3);
                if (escape != null) {
                    walkToTarget(source, bot, escape, 2_000L, "fortify-tower:scaffold-pre-escape");
                }
            } finally {
                endFortifyNavScope(bot, world, prior);
            }
            if (towerNavState.sameStuckPosCount >= 1 && bot.getBlockPos().getSquaredDistance(new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z())) > 25.0D) {
                towerNavState.noteRealProgress();
            }
        }

        long phaseStart = System.currentTimeMillis();
        int totalGained = 0;
        Set<Integer> triedSides = new HashSet<>();
        Map<BlockPos, Integer> repeatedNoHeadroomByPos = new HashMap<>();

        for (int sideAttempt = 0; sideAttempt < TOWER_SCAFFOLD_MAX_SIDES; sideAttempt++) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;
            if (System.currentTimeMillis() - phaseStart > TOWER_SCAFFOLD_TIME_BUDGET_MS) break;

            presentCount = countPresentBlocks(world, vertexBlocks);
            if (isTowerComplete(presentCount, plannedCount)) break;

            BlockPos scaffoldBase = chooseTowerScaffoldPos(world, vertex, surfaceProfile, triedSides);
            if (scaffoldBase == null) break;

            // Navigate to scaffold base
            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, sideAttempt);
            FortifyNavRuntimeScope scaffoldScope = beginFortifyNavScope("fortify-tower:scaffold-base", towerNavState, vertex, scaffoldBase, true, false);
            try {
                walkToTarget(source, bot, scaffoldBase, 3_000L, "fortify-tower:scaffold-base");
            } finally {
                endFortifyNavScope(bot, world, scaffoldScope);
            }
            double distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            if (distSq > 9.0) {
                walkTowardBlock(bot, scaffoldBase, 2_000L);
                distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            }
            if (distSq <= 25.0D && isTrapLikeCell(world, bot.getBlockPos())) {
                boolean nudged = tryPostCarvePocketEscapeToward(bot, world, scaffoldBase);
                if (nudged) {
                    distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
                    LOGGER.info("[FortifyTower] scaffold-base post-carve-escape side={} nudged=true dist={}",
                            sideAttempt, String.format(Locale.ROOT, "%.1f", Math.sqrt(distSq)));
                }
            }

            // If we're too far from the scaffold base, skip this side
            if (distSq > 16.0) {
                LOGGER.info("[FortifyTower] scaffold base unreachable side={} dist={} for tower ({},{})",
                        sideAttempt, String.format("%.1f", Math.sqrt(distSq)), vertex.x(), vertex.z());
                if (towerNavState != null) {
                    towerNavState.noteNoRealProgress();
                }
                if (towerNavState != null && towerNavState.sameStuckPosCount >= 1 && towerNavState.noRealProgressElapsedMs() >= 3_000L) {
                    LOGGER.info("[FortifyTower] skipping remaining scaffold sides for tower ({},{}) due to repeated local trap",
                            vertex.x(), vertex.z());
                    break;
                }
                continue;
            }

            TowerScaffoldSideOutcome sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_HARD;
            boolean recoverableScaffoldFailure = false;

            int localTerrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
            if (bot.getBlockPos().getY() < localTerrainY - 1) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck below-surface pos={} terrainY={} tower=({}, {})",
                        bot.getBlockPos().toShortString(), localTerrainY, vertex.x(), vertex.z());
                final int surfaceYForPrecheck = localTerrainY;
                runWithFortifyTowerNavScope(bot, world, "fortify-tower:scaffold-base-surface-precheck",
                        towerNavState, vertex, scaffoldBase,
                        () -> ensureOnSurface(bot, world, surfaceYForPrecheck));
                recoverableScaffoldFailure = true;
            }
            BlockPos launchPrecheckPos = bot.getBlockPos().toImmutable();
            localTerrainY = VillageFortificationLayoutService.terrainY(world, launchPrecheckPos.getX(), launchPrecheckPos.getZ());
            int launchDepth = Math.max(0, localTerrainY - launchPrecheckPos.getY());
            boolean launchBelowSurface = launchPrecheckPos.getY() < localTerrainY - 1;

            if (!canStandAt(world, launchPrecheckPos)) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck invalid-stand pos={} tower=({}, {})",
                        launchPrecheckPos.toShortString(), vertex.x(), vertex.z());
                entombmentHelper.noteEntombmentScaffoldFailure(world, launchPrecheckPos, "fortify-tower:scaffold-base");
                if (towerNavState != null) towerNavState.noteNoRealProgress();
                recoverableScaffoldFailure = true;
                continue;
            }
            if (!hasTowerPillarLaunchHeadroom(world, launchPrecheckPos)) {
                LOGGER.info("[FortifyTower] scaffold-base launch-precheck no-headroom pos={} tower=({}, {})",
                        launchPrecheckPos.toShortString(), vertex.x(), vertex.z());
                entombmentHelper.noteEntombmentScaffoldFailure(world, launchPrecheckPos, "fortify-tower:scaffold-base");
                if (towerNavState != null) towerNavState.noteNoRealProgress();
                int repeatCount = repeatedNoHeadroomByPos.merge(launchPrecheckPos, 1, Integer::sum);
                if (launchBelowSurface && launchDepth >= 2 && repeatCount >= TOWER_SCAFFOLD_NO_HEADROOM_SAME_POS_LIMIT) {
                    LOGGER.info("[FortifyTower] scaffold-base launch-precheck aborting scaffold phase tower=({}, {}) pos={} depth={} repeatedNoHeadroom={}",
                            vertex.x(), vertex.z(), launchPrecheckPos.toShortString(), launchDepth, repeatCount);
                    break;
                }
                recoverableScaffoldFailure = true;
                continue;
            }
            repeatedNoHeadroomByPos.remove(launchPrecheckPos);
            if (isTrapLikeCell(world, launchPrecheckPos)) {
                boolean nudged = tryPostCarvePocketEscapeToward(bot, world, scaffoldBase);
                if (!nudged && isTrapLikeCell(world, bot.getBlockPos())) {
                    LOGGER.info("[FortifyTower] scaffold-base launch-precheck trap-like pos={} tower=({}, {})",
                            bot.getBlockPos().toShortString(), vertex.x(), vertex.z());
                    entombmentHelper.noteEntombmentScaffoldFailure(world, bot.getBlockPos(), "fortify-tower:scaffold-base");
                    if (towerNavState != null) towerNavState.noteNoRealProgress();
                    recoverableScaffoldFailure = true;
                    continue;
                }
            }

            // Pillar up
            int startBotY = bot.getBlockPos().getY();
            int stepsNeeded = Math.max(0, optimalY - startBotY);
            LOGGER.info("[FortifyTower] scaffold pillar attempt side={} botY={} optimalY={} steps={} for tower ({},{})",
                    sideAttempt, startBotY, optimalY, stepsNeeded, vertex.x(), vertex.z());
            // Engage sneak BEFORE pillaring — prevents bot from walking off the
            // scaffold column if block placement timing is slightly off.
            SneakLockService.acquire(bot.getUuid());
            BotActions.sneak(bot, true);

            ScaffoldService.ScaffoldSession session = ScaffoldService.beginSession(bot);
            boolean pillared = ScaffoldService.pillarToY(session, optimalY);
            int postPillarY = bot.getBlockPos().getY();
            TowerPillarOutcome pillarOutcome = pillared
                    ? TowerPillarOutcome.OK
                    : (postPillarY > startBotY && !session.trackedPositions().isEmpty()
                    ? TowerPillarOutcome.PARTIAL
                    : TowerPillarOutcome.FAIL);
            // Accept partial success: if the bot gained height and has scaffolds,
            // it can still reach tower blocks from the elevated position.
            // Previously, placing 3/4 blocks (1 short of target) → teardown all 3 → waste.
            boolean usable = pillared || (postPillarY > startBotY && !session.trackedPositions().isEmpty());
            if (!usable) {
                LOGGER.info("[FortifyTower] scaffold pillar failed side={} pillared={} tracked={} botY={} for tower ({},{})",
                        sideAttempt, pillared, session.trackedPositions().size(),
                        postPillarY, vertex.x(), vertex.z());
                SneakLockService.release(bot.getUuid());
                if (!SneakLockService.isLocked(bot.getUuid())) {
                    BotActions.sneak(bot, false);
                }
                teardownScaffoldSurvival(bot, world, session);
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_RECOVERABLE;
                continue;
            }

            // Sneak already acquired before pillar — mark as held for endScaffoldEdgeHold cleanup
            boolean scaffoldSneak = true;

            // Place remaining blocks within reach
            int sidePlaced = 0;
            for (ProceduralWallBlock block : vertexBlocks) {
                if (SkillManager.shouldAbortSkill(bot)) break;
                if (!isActiveFortifyBlock(block)) continue;
                BlockState current = world.getBlockState(block.worldPos());
                if (isPlannedBlockSatisfied(block, current)) continue;
                if (!isWithinReach(bot, block.worldPos())) continue;

                LookController.faceBlock(bot, block.worldPos());
                sleepQuiet(BLOCK_PLACE_DELAY_MS);
                BotActions.PlaceResult result = tryPlaceBlock(bot, world, block.worldPos(), block.state());
                if (result.success()) {
                    sidePlaced++;
                }
            }

            // ── Step-onto-structure: extend reach by sneaking onto intended tower top surface ──
            int stepOnGained = 0;
            List<ProceduralWallBlock> remaining = new ArrayList<>();
            for (ProceduralWallBlock block : vertexBlocks) {
                if (!isActiveFortifyBlock(block)) continue;
                if (isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
                remaining.add(block);
            }
            TowerStepAttemptResult stepResult = null;
            TowerReturnAttemptResult returnResult = null;
            BlockPos scaffoldReturn = bot.getBlockPos();
            if (!remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                TowerSummitStepCandidate summitCandidate =
                        chooseTowerSummitStepCandidate(world, vertex, scaffoldReturn, remaining);
                if (summitCandidate != null) {
                    LOGGER.info("[FortifyTower] step-onto-structure dir={} target={} newReachable={} score={}",
                            summitCandidate.dir(), summitCandidate.pos().toShortString(),
                            summitCandidate.newReachable(), summitCandidate.score());
                    stepResult = attemptSneakStepToTowerSurface(bot, world, vertex, scaffoldReturn, summitCandidate);
                    if (stepResult.outcome() == TowerStepOutcome.OK) {
                        stepOnGained += placeReachableTowerBlocksFromCurrentSummit(bot, world, remaining);
                        if (!remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot)) {
                            TowerSummitRoamResult roamResult = roamTowerSummitForPlacements(
                                    bot, world, vertex, scaffoldReturn, remaining, 4);
                            stepOnGained += roamResult.placed();
                            if (roamResult.recoverableFailure()) {
                                recoverableScaffoldFailure = true;
                            }
                        }
                        returnResult = attemptSneakReturnToScaffold(bot, scaffoldReturn);
                        if (returnResult.outcome() != TowerReturnOutcome.OK) {
                            recoverableScaffoldFailure = true;
                        }
                    } else if (stepResult.outcome() != TowerStepOutcome.NO_MOVE) {
                        recoverableScaffoldFailure = true;
                    }
                }
                sidePlaced += stepOnGained;
            }

            boolean fellDuringSummit = stepResult != null && stepResult.outcome() == TowerStepOutcome.FELL;
            boolean offTargetSummit = stepResult != null && stepResult.outcome() == TowerStepOutcome.OFF_TARGET;
            boolean returnFailed = returnResult != null && returnResult.outcome() == TowerReturnOutcome.FAIL;
            boolean needsFallRecoveryCleanup = fellDuringSummit || offTargetSummit || returnFailed;
            int topVerifyPlaced = 0;
            if (!needsFallRecoveryCleanup) {
                topVerifyPlaced = verifyAndPlaceTowerTopLayer(bot, world, vertexBlocks);
                sidePlaced += topVerifyPlaced;
            }
            if (needsFallRecoveryCleanup) {
                LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery transition tower=({}, {}) step={} return={} current={}",
                        vertex.x(), vertex.z(),
                        stepResult != null ? stepResult.outcome() : TowerStepOutcome.NO_MOVE,
                        returnResult != null ? returnResult.outcome() : TowerReturnOutcome.OK,
                        bot.getBlockPos().toShortString());
            }

            // Keep sneak through pillar/step/place/return and release only once we transition into cleanup.
            endScaffoldEdgeHold(bot, scaffoldSneak);

            ScaffoldTeardownResult teardownResult = needsFallRecoveryCleanup
                    ? recoverAndTeardownScaffoldColumn(source, bot, world, session, "tower-side-" + sideAttempt)
                    : teardownScaffoldSurvival(bot, world, session);

            boolean gainedUsableScaffoldHeight = postPillarY > startBotY && !session.trackedPositions().isEmpty();

            if (sidePlaced > 0) {
                sideOutcome = TowerScaffoldSideOutcome.PROGRESS;
            } else if (recoverableScaffoldFailure
                    || pillarOutcome == TowerPillarOutcome.FAIL
                    || needsFallRecoveryCleanup
                    || teardownResult.queued() > 0
                    || gainedUsableScaffoldHeight) {
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_RECOVERABLE;
            } else {
                sideOutcome = TowerScaffoldSideOutcome.NO_PROGRESS_HARD;
            }

            totalGained += sidePlaced;
            LOGGER.info("[FortifyTower] scaffold side={} placed={} topVerify={} outcome={} teardownRemoved={} teardownQueued={} for tower {}/{} ({},{})",
                    sideAttempt, sidePlaced, topVerifyPlaced, sideOutcome,
                    teardownResult.removed(), teardownResult.queued(),
                    vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z());

            if (sidePlaced == 0 && sideOutcome == TowerScaffoldSideOutcome.NO_PROGRESS_HARD) {
                break;
            }
        }

        return totalGained;
    }

    /**
     * Pick a scaffold position near a tower vertex. Tries cardinal directions at
     * distances 1, 2, 3, 4 blocks out, then diagonals. Distance 1 is preferred
     * because it places the scaffold adjacent to the tower structure, enabling
     * step-onto-structure placement for far-side blocks.
     */
    private BlockPos chooseTowerScaffoldPos(ServerWorld world, WallPoint vertex,
                                            SurfaceProfile surfaceProfile,
                                            Set<Integer> triedSides) {
        // 8 directions: 4 cardinal + 4 diagonal
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},  // cardinal (indices 0-3)
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal (indices 4-7)
        };
        // Try increasing distances: 1, 2, 3, 4 blocks out (prefer closest)
        for (int dist = 1; dist <= 4; dist++) {
            for (int i = 0; i < directions.length; i++) {
                // Encode side as (direction_index * 10 + dist) for uniqueness
                int sideKey = i * 10 + dist;
                if (triedSides.contains(sideKey)) continue;
                int x = vertex.x() + directions[i][0] * dist;
                int z = vertex.z() + directions[i][1] * dist;
                // Skip positions inside the 3x3 tower footprint
                if (Math.abs(x - vertex.x()) <= 1 && Math.abs(z - vertex.z()) <= 1) continue;
                int y = safeSurfaceY(surfaceProfile, world, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (canStandAt(world, pos)) {
                    triedSides.add(sideKey);
                    LOGGER.debug("[FortifyTower] scaffold pos chosen: dir={} dist={} pos={}",
                            i, dist, pos.toShortString());
                    return pos;
                }
                // Also try one Y above (wall blocks may raise the floor)
                BlockPos posUp = new BlockPos(x, y + 1, z);
                if (canStandAt(world, posUp)) {
                    triedSides.add(sideKey);
                    LOGGER.debug("[FortifyTower] scaffold pos chosen (Y+1): dir={} dist={} pos={}",
                            i, dist, posUp.toShortString());
                    return posUp;
                }
            }
        }
        LOGGER.info("[FortifyTower] no standable scaffold pos found near tower ({},{})",
                vertex.x(), vertex.z());
        return null;
    }

    private boolean hasTowerPillarLaunchHeadroom(ServerWorld world, BlockPos feetPos) {
        if (world == null || feetPos == null) return false;
        BlockPos head = feetPos.up(2);
        BlockPos head2 = feetPos.up(3);
        BlockState h1 = world.getBlockState(head);
        BlockState h2 = world.getBlockState(head2);
        return h1.getCollisionShape(world, head).isEmpty()
                && h2.getCollisionShape(world, head2).isEmpty();
    }

    private TowerSummitStepCandidate chooseTowerSummitStepCandidate(ServerWorld world,
                                                                    WallPoint vertex,
                                                                    BlockPos scaffoldReturn,
                                                                    List<ProceduralWallBlock> remaining) {
        if (world == null || vertex == null || scaffoldReturn == null || remaining == null || remaining.isEmpty()) {
            return null;
        }
        int maxRemainingY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : remaining) {
            if (block == null) continue;
            maxRemainingY = Math.max(maxRemainingY, block.worldPos().getY());
        }
        TowerSummitStepCandidate best = null;
        Set<BlockPos> seenCandidates = new HashSet<>();
        for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, scaffoldReturn)) {
            if (transition == null || transition.requiresCarve()) continue;
            BlockPos candidatePos = transition.to();
            if (candidatePos == null || !seenCandidates.add(candidatePos.toImmutable())) {
                continue;
            }
            if (Math.abs(candidatePos.getY() - scaffoldReturn.getY()) > 1) {
                continue;
            }
            if (!isInsideTowerFootprint(candidatePos, vertex)) {
                continue;
            }
            if (!canStandAt(world, candidatePos)) {
                continue;
            }
            BlockPos supportPos = candidatePos.down();
            BlockState supportState = world.getBlockState(supportPos);
            if (supportState.getCollisionShape(world, supportPos).isEmpty()) {
                continue;
            }
            Direction dir = horizontalDirectionToward(scaffoldReturn, candidatePos);
            if (dir == null) {
                continue;
            }
            int newReachable = 0;
            int topReachable = 0;
            int score = 0;
            Vec3d stepEye = Vec3d.ofCenter(candidatePos).add(0, 1.62, 0);
            Vec3d perchEye = Vec3d.ofCenter(scaffoldReturn).add(0, 1.62, 0);
            for (ProceduralWallBlock b : remaining) {
                if (b == null) continue;
                Vec3d targetCenter = Vec3d.ofCenter(b.worldPos());
                boolean fromPerch = perchEye.squaredDistanceTo(targetCenter) <= REACH_DISTANCE_SQ;
                boolean fromStep = stepEye.squaredDistanceTo(targetCenter) <= REACH_DISTANCE_SQ;
                if (fromStep && !fromPerch) {
                    newReachable++;
                    if (b.worldPos().getY() >= maxRemainingY - 1) {
                        topReachable++;
                    }
                }
            }
            if (newReachable == 0) {
                continue;
            }
            score += topReachable * 100;
            score += newReachable * 20;
            score += countOpenExits(world, candidatePos, null) * 5;
            if (hasOneStepReturnToScaffold(world, candidatePos, scaffoldReturn)) {
                score += 25;
            } else {
                score -= 30;
            }
            score += switch (transition.kind()) {
                case CARDINAL -> 20;
                case STEP_UP -> 15;
                case STEP_DOWN -> 8;
                case NARROW_GAP -> 5;
                case REQUIRES_CARVE -> -50;
            };
            if (best == null || score > best.score()) {
                best = new TowerSummitStepCandidate(candidatePos, dir, newReachable, score);
            }
        }
        return best;
    }

    private int placeReachableTowerBlocksFromCurrentSummit(ServerPlayerEntity bot,
                                                           ServerWorld world,
                                                           List<ProceduralWallBlock> remaining) {
        if (bot == null || world == null || remaining == null || remaining.isEmpty()) {
            return 0;
        }
        int placed = 0;
        Iterator<ProceduralWallBlock> it = remaining.iterator();
        while (it.hasNext()) {
            ProceduralWallBlock b = it.next();
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (b == null || !isWithinReach(bot, b.worldPos())) continue;
            LookController.faceBlock(bot, b.worldPos());
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
            BotActions.PlaceResult placeResult = tryPlaceBlock(bot, world, b.worldPos(), b.state());
            if (placeResult.success()) {
                placed++;
                it.remove();
            }
        }
        return placed;
    }

    private TowerSummitRoamResult roamTowerSummitForPlacements(ServerPlayerEntity bot,
                                                               ServerWorld world,
                                                               WallPoint vertex,
                                                               BlockPos scaffoldReturn,
                                                               List<ProceduralWallBlock> remaining,
                                                               int maxMoves) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || remaining == null || remaining.isEmpty()) {
            return new TowerSummitRoamResult(0, false);
        }
        int placed = 0;
        boolean recoverableFailure = false;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(bot.getBlockPos().toImmutable());
        for (int move = 0; move < Math.max(0, maxMoves) && !remaining.isEmpty() && !SkillManager.shouldAbortSkill(bot); move++) {
            BlockPos current = bot.getBlockPos();
            BlockPos bestNext = null;
            int bestScore = Integer.MIN_VALUE;
            for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, current)) {
                if (transition == null || transition.requiresCarve()) continue;
                BlockPos candidate = transition.to();
                if (candidate == null) continue;
                if (visited.contains(candidate)) continue;
                if (!isInsideTowerFootprint(candidate, vertex)) continue;
                if (!canStandAt(world, candidate)) continue;
                if (Math.abs(candidate.getY() - scaffoldReturn.getY()) > 1) continue;
                int score = countSummitReachableBlocks(bot, candidate, remaining) * 20;
                if (score <= 0) continue;
                if (hasOneStepReturnToScaffold(world, candidate, scaffoldReturn)) score += 20;
                score += switch (transition.kind()) {
                    case CARDINAL -> 10;
                    case STEP_UP -> 8;
                    case STEP_DOWN -> 4;
                    case NARROW_GAP -> 2;
                    case REQUIRES_CARVE -> -50;
                };
                if (score > bestScore) {
                    bestScore = score;
                    bestNext = candidate.toImmutable();
                }
            }
            if (bestNext == null) {
                break;
            }
            TowerStepAttemptResult moveResult = attemptSneakMoveOnTowerSurface(bot, world, vertex, scaffoldReturn, bestNext, "tower-step-sweep");
            if (moveResult.outcome() != TowerStepOutcome.OK) {
                if (moveResult.outcome() != TowerStepOutcome.NO_MOVE) {
                    recoverableFailure = true;
                }
                break;
            }
            visited.add(bot.getBlockPos().toImmutable());
            placed += placeReachableTowerBlocksFromCurrentSummit(bot, world, remaining);
        }
        return new TowerSummitRoamResult(placed, recoverableFailure);
    }

    private int countSummitReachableBlocks(ServerPlayerEntity bot, BlockPos standPos, List<ProceduralWallBlock> remaining) {
        if (bot == null || standPos == null || remaining == null || remaining.isEmpty()) {
            return 0;
        }
        Vec3d eye = Vec3d.ofCenter(standPos).add(0, 1.62, 0);
        int count = 0;
        for (ProceduralWallBlock b : remaining) {
            if (b == null) continue;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(b.worldPos())) <= REACH_DISTANCE_SQ) {
                count++;
            }
        }
        return count;
    }

    private Direction horizontalDirectionToward(BlockPos from, BlockPos to) {
        if (from == null || to == null) return null;
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 1 && dz == 0) return Direction.EAST;
        if (dx == -1 && dz == 0) return Direction.WEST;
        if (dx == 0 && dz == 1) return Direction.SOUTH;
        if (dx == 0 && dz == -1) return Direction.NORTH;
        return null;
    }

    private boolean hasOneStepReturnToScaffold(ServerWorld world, BlockPos from, BlockPos scaffoldReturn) {
        if (world == null || from == null || scaffoldReturn == null) {
            return false;
        }
        for (VoxelJunctionService.VoxelTransition transition : VoxelJunctionService.transitionsFrom(world, from)) {
            if (transition == null || transition.requiresCarve()) continue;
            BlockPos to = transition.to();
            if (to == null) continue;
            if (sameBlockColumnWithinOneY(to, scaffoldReturn) || to.equals(scaffoldReturn)) {
                return true;
            }
        }
        return false;
    }

    private TowerStepAttemptResult attemptSneakStepToTowerSurface(ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  WallPoint vertex,
                                                                  BlockPos scaffoldReturn,
                                                                  TowerSummitStepCandidate candidate) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || candidate == null) {
            return new TowerStepAttemptResult(TowerStepOutcome.NO_MOVE, null, bot != null ? bot.getBlockPos() : null);
        }
        BlockPos expected = candidate.pos();
        BlockPos before = bot.getBlockPos();
        Vec3d stepVec = Vec3d.ofCenter(expected);
        settleSneakStepOnBlockCenter(bot, scaffoldReturn, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_STEP_TIMEOUT_MS;
        int stableTicksOnExpected = 0;
        while (System.currentTimeMillis() < deadline) {
            BlockPos current = bot.getBlockPos();
            if (sameBlockColumnWithinOneY(current, expected)) {
                stableTicksOnExpected++;
                if (stableTicksOnExpected >= 2) {
                    break;
                }
                sleepQuiet(35L);
                continue;
            }
            stableTicksOnExpected = 0;
            LookController.faceBlock(bot, expected);
            double horizDist = horizontalDistanceToCenter(bot, expected);
            double impulse = horizDist > 0.55D ? 0.12D : 0.08D;
            BotActions.applyMovementInput(bot, stepVec, impulse);
            sleepQuiet(40L);
        }
        BlockPos after = bot.getBlockPos();
        TowerStepOutcome outcome;
        if (sameBlockColumnWithinOneY(after, expected)) {
            settleSneakStepOnBlockCenter(bot, after, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
            after = bot.getBlockPos();
            outcome = TowerStepOutcome.OK;
        } else if (after.equals(before)) {
            outcome = TowerStepOutcome.NO_MOVE;
        } else if (after.getY() < scaffoldReturn.getY() - 1 || !isInsideTowerFootprint(after, vertex)) {
            outcome = TowerStepOutcome.FELL;
        } else {
            outcome = TowerStepOutcome.OFF_TARGET;
        }
        LOGGER.info("[FortifyTower] tower-step-out result={} expected={} actual={} dir={} newReachable={}",
                outcome,
                expected != null ? expected.toShortString() : "n/a",
                after != null ? after.toShortString() : "n/a",
                candidate.dir(),
                candidate.newReachable());
        return new TowerStepAttemptResult(outcome, expected, after);
    }

    private TowerStepAttemptResult attemptSneakMoveOnTowerSurface(ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  WallPoint vertex,
                                                                  BlockPos scaffoldReturn,
                                                                  BlockPos expected,
                                                                  String logLabel) {
        if (bot == null || world == null || vertex == null || scaffoldReturn == null || expected == null) {
            return new TowerStepAttemptResult(TowerStepOutcome.NO_MOVE, expected, bot != null ? bot.getBlockPos() : null);
        }
        BlockPos before = bot.getBlockPos();
        Vec3d stepVec = Vec3d.ofCenter(expected);
        settleSneakStepOnBlockCenter(bot, before, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_STEP_TIMEOUT_MS;
        int stableTicksOnExpected = 0;
        while (System.currentTimeMillis() < deadline) {
            BlockPos current = bot.getBlockPos();
            if (sameBlockColumnWithinOneY(current, expected)) {
                stableTicksOnExpected++;
                if (stableTicksOnExpected >= 2) break;
                sleepQuiet(35L);
                continue;
            }
            stableTicksOnExpected = 0;
            LookController.faceBlock(bot, expected);
            double horizDist = horizontalDistanceToCenter(bot, expected);
            double impulse = horizDist > 0.55D ? 0.11D : 0.07D;
            BotActions.applyMovementInput(bot, stepVec, impulse);
            sleepQuiet(40L);
        }
        BlockPos after = bot.getBlockPos();
        TowerStepOutcome outcome;
        if (sameBlockColumnWithinOneY(after, expected)) {
            settleSneakStepOnBlockCenter(bot, after, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
            outcome = TowerStepOutcome.OK;
        } else if (after.equals(before)) {
            outcome = TowerStepOutcome.NO_MOVE;
        } else if (after.getY() < scaffoldReturn.getY() - 1 || !isInsideTowerFootprint(after, vertex)) {
            outcome = TowerStepOutcome.FELL;
        } else {
            outcome = TowerStepOutcome.OFF_TARGET;
        }
        LOGGER.info("[FortifyTower] {} result={} expected={} actual={}",
                logLabel, outcome, expected.toShortString(), after.toShortString());
        return new TowerStepAttemptResult(outcome, expected, after);
    }

    private TowerReturnAttemptResult attemptSneakReturnToScaffold(ServerPlayerEntity bot, BlockPos scaffoldReturn) {
        if (bot == null || scaffoldReturn == null) {
            return new TowerReturnAttemptResult(TowerReturnOutcome.FAIL, scaffoldReturn, bot != null ? bot.getBlockPos() : null);
        }
        settleSneakStepOnBlockCenter(bot, bot.getBlockPos(), Math.min(200L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        Vec3d returnVec = Vec3d.ofCenter(scaffoldReturn);
        long deadline = System.currentTimeMillis() + TOWER_SCAFFOLD_RETURN_TIMEOUT_MS;
        int stableTicksOnReturn = 0;
        while (System.currentTimeMillis() < deadline) {
            if (sameBlockColumnWithinOneY(bot.getBlockPos(), scaffoldReturn)) {
                stableTicksOnReturn++;
                if (stableTicksOnReturn >= 2) {
                    break;
                }
                sleepQuiet(35L);
                continue;
            }
            stableTicksOnReturn = 0;
            LookController.faceBlock(bot, scaffoldReturn);
            double horizDist = horizontalDistanceToCenter(bot, scaffoldReturn);
            double impulse = horizDist > 0.55D ? 0.12D : 0.08D;
            BotActions.applyMovementInput(bot, returnVec, impulse);
            sleepQuiet(40L);
        }
        settleSneakStepOnBlockCenter(bot, scaffoldReturn, Math.min(220L, TOWER_SCAFFOLD_RECENTER_TIMEOUT_MS));
        BlockPos after = bot.getBlockPos();
        TowerReturnOutcome outcome = sameBlockColumnWithinOneY(after, scaffoldReturn) ? TowerReturnOutcome.OK : TowerReturnOutcome.FAIL;
        LOGGER.info("[FortifyTower] tower-step-back result={} expected={} actual={}",
                outcome, scaffoldReturn.toShortString(), after.toShortString());
        return new TowerReturnAttemptResult(outcome, scaffoldReturn, after);
    }

    private boolean sameBlockColumnWithinOneY(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        return a.getX() == b.getX()
                && a.getZ() == b.getZ()
                && Math.abs(a.getY() - b.getY()) <= 1;
    }

    private double horizontalDistanceToCenter(ServerPlayerEntity bot, BlockPos targetBlock) {
        if (bot == null || targetBlock == null) return Double.POSITIVE_INFINITY;
        Vec3d center = Vec3d.ofCenter(targetBlock);
        double dx = center.x - bot.getX();
        double dz = center.z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void settleSneakStepOnBlockCenter(ServerPlayerEntity bot, BlockPos standPos, long timeoutMs) {
        if (bot == null || standPos == null) return;
        if (!sameBlockColumnWithinOneY(bot.getBlockPos(), standPos)) return;
        Vec3d center = Vec3d.ofCenter(standPos);
        long deadline = System.currentTimeMillis() + Math.max(80L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return;
            }
            if (!sameBlockColumnWithinOneY(bot.getBlockPos(), standPos)) {
                return;
            }
            double dx = center.x - bot.getX();
            double dz = center.z - bot.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq <= TOWER_SCAFFOLD_RECENTER_TOLERANCE_SQ) {
                BotActions.stop(bot);
                return;
            }
            BotActions.applyMovementInput(bot, new Vec3d(center.x, bot.getY(), center.z), 0.07D);
            sleepQuiet(30L);
        }
        BotActions.stop(bot);
    }

    private int verifyAndPlaceTowerTopLayer(ServerPlayerEntity bot,
                                            ServerWorld world,
                                            List<ProceduralWallBlock> vertexBlocks) {
        if (bot == null || world == null || vertexBlocks == null || vertexBlocks.isEmpty()) {
            return 0;
        }
        List<ProceduralWallBlock> remaining = new ArrayList<>();
        int maxY = Integer.MIN_VALUE;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (block == null || !isActiveFortifyBlock(block)) continue;
            if (isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
            remaining.add(block);
            maxY = Math.max(maxY, block.worldPos().getY());
        }
        if (remaining.isEmpty()) {
            return 0;
        }
        int topY = maxY;
        remaining.sort(Comparator
                .comparingInt((ProceduralWallBlock b) -> b.worldPos().getY() >= topY - 1 ? 0 : 1)
                .thenComparingInt((ProceduralWallBlock b) -> -b.worldPos().getY())
                .thenComparingDouble(b -> bot.getBlockPos().getSquaredDistance(b.worldPos())));
        int placed = 0;
        int attempted = 0;
        for (ProceduralWallBlock block : remaining) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (!isWithinReach(bot, block.worldPos())) continue;
            if (attempted >= TOWER_TOP_VERIFY_MAX_ATTEMPTS) break;
            attempted++;
            LookController.faceBlock(bot, block.worldPos());
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
            BotActions.PlaceResult placeResult = tryPlaceBlock(bot, world, block.worldPos(), block.state());
            if (placeResult.success()) {
                placed++;
            }
        }
        if (attempted > 0) {
            LOGGER.info("[FortifyTower] tower-top-verify remainingTopLayerCount={} placed={} attempts={}",
                    remaining.stream().filter(b -> b.worldPos().getY() >= topY - 1).count(),
                    placed, attempted);
        }
        return placed;
    }

    private ScaffoldTeardownResult recoverAndTeardownScaffoldColumn(ServerCommandSource source,
                                                                    ServerPlayerEntity bot,
                                                                    ServerWorld world,
                                                                    ScaffoldService.ScaffoldSession session,
                                                                    String context) {
        if (bot == null || world == null) {
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }
        LinkedHashSet<BlockPos> tracked = new LinkedHashSet<>();
        if (session != null) {
            tracked.addAll(session.trackedPositions());
        }
        tracked.addAll(ScaffoldService.getScaffoldMemory(bot));
        BlockPos focus = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos pos : tracked) {
            if (pos == null) continue;
            double dist = bot.getBlockPos().getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                focus = pos;
            }
        }
        if (focus != null && (!isWithinMiningReach(bot, focus) || !hasLineOfSight(world, bot, bot.getEyePos(), focus))) {
            BlockPos bestStand = null;
            double bestStandScore = Double.POSITIVE_INFINITY;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = focus.add(dx, dy, dz);
                        if (!canStandAt(world, candidate)) continue;
                        double score = candidate.getSquaredDistance(focus) + bot.getBlockPos().getSquaredDistance(candidate) * 0.25D;
                        if (score < bestStandScore) {
                            bestStandScore = score;
                            bestStand = candidate;
                        }
                    }
                }
            }
            if (bestStand != null && !bestStand.equals(bot.getBlockPos())) {
                LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery moving ctx={} from={} to={} focus={}",
                        context,
                        bot.getBlockPos().toShortString(),
                        bestStand.toShortString(),
                        focus.toShortString());
                walkToTarget(source, bot, bestStand, 1_500L, "fortify-tower:scaffold-recover");
                if (!bot.getBlockPos().equals(bestStand)) {
                    walkTowardBlock(bot, bestStand, 900L);
                }
            }
        }
        ScaffoldTeardownResult result = teardownScaffoldSurvival(bot, world, session);
        LOGGER.info("[FortifyTower] tower-scaffold-fall-recovery result ctx={} removed={} queued={} failedMine={} outOfReach={}",
                context, result.removed(), result.queued(), result.failedMine(), result.outOfReach());
        return result;
    }

    /**
     * Mine tracked scaffold blocks top-down. Each block mined drops the bot 1 block
     * (no fall damage from scaffold height). Uses digBlock() for survival-mode mining.
     */
    private ScaffoldLedger buildScaffoldLedger(ServerPlayerEntity bot, ServerWorld world,
                                               ScaffoldService.ScaffoldSession session) {
        ScaffoldLedger ledger = new ScaffoldLedger();
        if (bot == null || world == null) {
            return ledger;
        }
        if (session != null) {
            for (BlockPos pos : session.trackedPositions()) {
                if (pos != null) ledger.sessionTracked.add(pos.toImmutable());
            }
        }

        BlockPos botPos = bot.getBlockPos();
        for (BlockPos pos : new ArrayList<>(ScaffoldService.getScaffoldMemory(bot))) {
            if (pos == null) continue;
            if (Math.abs(pos.getX() - botPos.getX()) <= 6
                    && Math.abs(pos.getZ() - botPos.getZ()) <= 6
                    && Math.abs(pos.getY() - botPos.getY()) <= 16) {
                ledger.memoryTracked.add(pos.toImmutable());
            }
        }

        // Do not infer scaffold candidates from nearby material scans. Scaffold blocks must be
        // explicitly tagged (session/memory tracked) or we risk tearing up terrain/walls made
        // from common scaffold materials (dirt/cobble/stone), especially below the bot.
        return ledger;
    }

    private ScaffoldTeardownResult teardownScaffoldSurvival(ServerPlayerEntity bot, ServerWorld world,
                                                            ScaffoldService.ScaffoldSession session) {
        if (bot == null || world == null) {
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }

        ScaffoldLedger ledger = buildScaffoldLedger(bot, world, session);
        List<BlockPos> toRemove = ledger.allCandidatesTopDown();
        if (toRemove.isEmpty()) {
            LOGGER.info("[FortifyScaffold] teardown tracked=0 removed=0 queued=0 (no candidates)");
            return new ScaffoldTeardownResult(0, 0, 0, 0, 0, 0, 0, 0);
        }

        Set<BlockPos> memory = ScaffoldService.getScaffoldMemory(bot);
        int removed = 0;
        int alreadyAir = 0;
        int failedMine = 0;
        int outOfReach = 0;
        int queued = 0;

        for (BlockPos pos : toRemove) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                alreadyAir++;
                memory.remove(pos);
                continue;
            }
            if (!ScaffoldService.SCAFFOLD_BLOCKS.contains(state.getBlock().asItem())) {
                memory.remove(pos);
                continue;
            }
            if (!isWithinMiningReach(bot, pos)) {
                outOfReach++;
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
                continue;
            }
            if (!hasLineOfSight(world, bot, bot.getEyePos(), pos)) {
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
                continue;
            }
            LookController.faceBlock(bot, pos);
            sleepQuiet(50L);
            boolean mined = digBlock(bot, world, pos);
            BlockState after = world.getBlockState(pos);
            boolean cleared = after.isAir() || !ScaffoldService.SCAFFOLD_BLOCKS.contains(after.getBlock().asItem());
            if (mined && cleared) {
                removed++;
                memory.remove(pos);
            } else if (cleared) {
                alreadyAir++;
                memory.remove(pos);
            } else {
                failedMine++;
                queued++;
                cleanupHelper.queue(FortifyCleanupKind.SCAFFOLD_REMOVE, pos, null, false, "scaffold-teardown");
            }
        }

        ScaffoldTeardownResult result = new ScaffoldTeardownResult(
                ledger.sessionTracked.size(),
                ledger.memoryTracked.size(),
                ledger.reconciledNearby.size(),
                removed, alreadyAir, failedMine, outOfReach, queued);
        LOGGER.info("[FortifyScaffold] teardown trackedSession={} trackedMemory={} reconciledNearby={} removed={} alreadyAir={} failedMine={} outOfReach={} queued={}",
                result.trackedSession(), result.trackedMemory(), result.reconciledNearby(),
                result.removed(), result.alreadyAir(), result.failedMine(), result.outOfReach(), result.queued());
        if ((result.trackedSession() + result.trackedMemory()) > 0
                && result.removed() == 0 && result.queued() == 0 && result.alreadyAir() == 0) {
            LOGGER.warn("[FortifyScaffold] teardown produced no removals despite tracked scaffolds (session={} memory={})",
                    result.trackedSession(), result.trackedMemory());
        }
        processDeferredFortifyCleanupQueue(bot, world, "scaffold-teardown");
        return result;
    }

    private void forceExitTowerFootprint(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         WallPoint vertex,
                                         SurfaceProfile surfaceProfile,
                                         int attemptOffset) {
        BlockPos botPos = bot.getBlockPos();
        if (!isInsideTowerFootprint(botPos, vertex)) {
            return;
        }
        if (SkillManager.shouldAbortSkill(bot)) {
            return;
        }

        BlockPos escape = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, attemptOffset);
        if (escape == null || escape.equals(botPos)) {
            return;
        }

        LOGGER.info("[FortifyTower] forced-footprint-exit from={} to={} tower=({}, {})",
                botPos.toShortString(), escape.toShortString(), vertex.x(), vertex.z());
        runWithFortifyTowerNavScope(bot, world, "fortify-tower:forced-footprint-exit",
                null, vertex, escape,
                () -> walkToTarget(source, bot, escape, 1_500L, "fortify-tower:forced-footprint-exit"));
        if (isInsideTowerFootprint(bot.getBlockPos(), vertex)) {
            walkTowardBlock(bot, escape, 1_000L);
            if (isInsideTowerFootprint(bot.getBlockPos(), vertex)) {
                tryTowerHardResetPosition(source, bot, world, vertex, surfaceProfile, attemptOffset + 7);
            }
        }
    }

    private boolean isInsideTowerFootprint(BlockPos botPos, WallPoint vertex) {
        return Math.abs(botPos.getX() - vertex.x()) <= 1 && Math.abs(botPos.getZ() - vertex.z()) <= 1;
    }

    private BlockPos chooseTowerEscapePos(ServerPlayerEntity bot,
                                          ServerWorld world,
                                          WallPoint vertex,
                                          SurfaceProfile surfaceProfile,
                                          int attemptOffset) {
        // Tower footprint is vertex ±1 (3×3). Min offset 3 = 1 block clearance from edge.
        int[][] candidates = {
                {3, 0}, {-3, 0}, {0, 3}, {0, -3},
                {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
                {1, 3}, {-1, 3}, {1, -3}, {-1, -3},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}
        };
        int startIndex = Math.floorMod(attemptOffset, candidates.length);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos botPos = bot.getBlockPos();

        for (int i = 0; i < candidates.length; i++) {
            int[] offset = candidates[(startIndex + i) % candidates.length];
            int x = vertex.x() + offset[0];
            int z = vertex.z() + offset[1];
            int y = safeSurfaceY(surfaceProfile, world, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!canStandAt(world, candidate)) {
                continue;
            }
            int exits = countOpenExits(world, candidate, null);
            if (exits < 2) {
                continue;
            }
            double distFromTowerSq = Math.pow(candidate.getX() - vertex.x(), 2) + Math.pow(candidate.getZ() - vertex.z(), 2);
            double distFromBotSq = botPos.getSquaredDistance(candidate);
            double score = distFromTowerSq * 30.0 + exits * 80.0 - distFromBotSq * 4.0 + i;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            return best;
        }
        return SafePositionService.findSafeNear(world, botPos, 3);
    }

    private boolean tryTowerHardResetPosition(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              SurfaceProfile surfaceProfile,
                                              int attemptOffset) {
        return tryTowerHardResetPositionDetailed(source, bot, world, vertex, surfaceProfile, attemptOffset, null).moved();
    }

    private boolean tryTowerHardResetPosition(ServerCommandSource source,
                                              ServerPlayerEntity bot,
                                              ServerWorld world,
                                              WallPoint vertex,
                                              SurfaceProfile surfaceProfile,
                                              int attemptOffset,
                                              TowerNavAttemptState navState) {
        return tryTowerHardResetPositionDetailed(source, bot, world, vertex, surfaceProfile, attemptOffset, navState).moved();
    }

    private TowerHardResetResult tryTowerHardResetPositionDetailed(ServerCommandSource source,
                                                                   ServerPlayerEntity bot,
                                                                   ServerWorld world,
                                                                   WallPoint vertex,
                                                                   SurfaceProfile surfaceProfile,
                                                                   int attemptOffset,
                                                                   TowerNavAttemptState navState) {
        BlockPos start = bot.getBlockPos();
        TowerNavCandidate bestCandidate = selectReachableTowerHardResetCandidate(
                bot, world, vertex, surfaceProfile, attemptOffset, navState);
        BlockPos best = bestCandidate != null ? bestCandidate.pos() : null;
        if (best == null) {
            best = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, attemptOffset + 9);
            if (best != null) {
                LOGGER.info("[FortifyTower] hard-reset-select tower=({}, {}) fallback={} reason=escape-pos",
                        vertex.x(), vertex.z(), best.toShortString());
            }
        }
        if (best == null || best.equals(start)) {
            if (navState != null && best != null) {
                navState.recordHardResetFailure(best);
            }
            return new TowerHardResetResult(false, false);
        }

        if (bestCandidate != null) {
            LOGGER.info("[FortifyTower] hard-reset-select tower=({}, {}) pos={} score={} exits={} locallyReachable={} prevFailed={}",
                    vertex.x(), vertex.z(), best.toShortString(),
                    String.format(Locale.ROOT, "%.1f", bestCandidate.score()),
                    bestCandidate.exits(), bestCandidate.locallyReachable(), bestCandidate.previouslyFailed());
        }
        LOGGER.info("[FortifyTower] hard-reset tower=({}, {}) from={} to={}",
                vertex.x(), vertex.z(), start.toShortString(), best.toShortString());
        double beforeTargetDistSq = bot.getBlockPos().getSquaredDistance(best);
        long hardResetStartMs = System.currentTimeMillis();
        FortifyNavRuntimeScope priorScope = beginFortifyNavScope(
                "fortify-tower:hard-reset", navState, vertex, best, true, false);
        try {
            walkToTarget(source, bot, best, 2_300L, "fortify-tower:hard-reset");
        } finally {
            endFortifyNavScope(bot, world, priorScope);
        }
        if (start.equals(bot.getBlockPos())) {
            walkTowardBlock(bot, best, 1_000L);
        }
        FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                start, bot.getBlockPos(), beforeTargetDistSq, bot.getBlockPos().getSquaredDistance(best),
                System.currentTimeMillis() - hardResetStartMs);
        boolean moved = !start.equals(bot.getBlockPos());
        boolean meaningful = progress.meaningful();
        if (!moved && navState != null) {
            navState.recordHardResetFailure(best);
            navState.noteNoRealProgress();
        } else if (navState != null) {
            navState.noteMovement(start, bot.getBlockPos(), meaningful);
            if (meaningful) navState.noteRealProgress(); else navState.noteNoRealProgress();
            if (!meaningful) {
                navState.recordHardResetFailure(best);
            }
        }
        LOGGER.info("[FortifyTower] hard-reset-result tower=({}, {}) target={} moved={} meaningful={} distDelta={} netDisp={}",
                vertex.x(), vertex.z(), best.toShortString(), moved, meaningful,
                String.format(Locale.ROOT, "%.1f", progress.targetDeltaBlocks()),
                String.format(Locale.ROOT, "%.1f", progress.netDisplacementBlocks()));
        return new TowerHardResetResult(moved, meaningful);
    }

    private boolean moveToTowerApproach(ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        ServerWorld world,
                                        WallPoint vertex,
                                        BlockPos towerApproach,
                                        SurfaceProfile surfaceProfile,
                                        String context,
                                        TowerNavAttemptState navState) {
        if (towerApproach == null) {
            return false;
        }
        Vec3d approachVec = Vec3d.ofCenter(towerApproach);
        if (bot.squaredDistanceTo(approachVec) <= 9.0) {
            return true;
        }

        // Avoid blocking A* pathfinding here; a long tower-approach solve can make fortify look "stuck"
        // before it has placed its first block. Use bounded local movement + unwedge attempts instead.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            BlockPos cycleStart = bot.getBlockPos();
            double beforeTargetDistSq = bot.squaredDistanceTo(approachVec);
            long cycleStartMs = System.currentTimeMillis();
            FortifyNavRuntimeScope priorScope = beginFortifyNavScope(
                    "fortify-tower:approach", navState, vertex, towerApproach, true, false);
            long walkBudgetMs = bot.squaredDistanceTo(approachVec) > 196.0D ? 2_200L : 1_200L;
            try {
                walkToTarget(source, bot, towerApproach, walkBudgetMs, "fortify-tower:approach");
            } finally {
                endFortifyNavScope(bot, world, priorScope);
            }
            if (bot.squaredDistanceTo(approachVec) <= 9.0D) {
                FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                        cycleStart, bot.getBlockPos(), beforeTargetDistSq, bot.squaredDistanceTo(approachVec),
                        System.currentTimeMillis() - cycleStartMs);
                if (navState != null) {
                    navState.noteMovement(cycleStart, bot.getBlockPos(), progress.meaningful());
                    if (progress.meaningful()) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                return true;
            }

            boolean unwedged = tryUnwedgeFromTightSpace(
                    source,
                    bot,
                    world,
                    surfaceProfile,
                    towerApproach,
                    context + ":unwedge-" + (attempt + 1)
            );
            if (!unwedged) {
                BlockPos safe = SafePositionService.findSafeNear(world, bot.getBlockPos(), 2 + attempt);
                if (safe != null && !safe.equals(bot.getBlockPos())) {
                    runWithFortifyTowerNavScope(bot, world, "fortify-tower:approach-safe-nudge",
                            navState, vertex, safe,
                            () -> walkToTarget(source, bot, safe, 900L, "fortify-tower:approach-safe-nudge"));
                }
            }

            if (bot.squaredDistanceTo(approachVec) > 9.0D) {
                walkTowardBlock(bot, towerApproach, 900L + (attempt * 300L));
            }
            BlockPos cycleEnd = bot.getBlockPos();
            FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                    cycleStart, cycleEnd, beforeTargetDistSq, bot.squaredDistanceTo(approachVec),
                    System.currentTimeMillis() - cycleStartMs);
            boolean movedThisCycle = !cycleStart.equals(cycleEnd);
            boolean meaningfulProgress = progress.meaningful();
            int samePosCount = 0;
            if (navState != null) {
                if (meaningfulProgress) {
                    navState.noteMovement(cycleStart, cycleEnd, true);
                    navState.noteRealProgress();
                } else {
                    navState.noteNoRealProgress();
                    samePosCount = navState.noteStuckPosition(cycleEnd);
                    LOGGER.info("[FortifyTower] approach-stuck tower=({}, {}) pos={} target={} samePosCount={} elapsedMs={} attempt={}",
                            vertex.x(), vertex.z(), cycleEnd.toShortString(), towerApproach.toShortString(),
                            samePosCount, navState.stuckElapsedMs(), attempt + 1);
                }
            }

            if (bot.squaredDistanceTo(approachVec) > 9.0D
                    && (!meaningfulProgress || samePosCount >= 2)) {
                BlockPos replanTarget = towerApproach;
                if (!isLocallyReachableStandPos(world, bot.getBlockPos(), towerApproach,
                        new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z()),
                        TOWER_LOCAL_REACHABILITY_RADIUS)) {
                    BlockPos staging = chooseReachableTowerPatchStagingPos(bot, world, vertex, towerApproach, surfaceProfile, navState);
                    if (staging != null) {
                        replanTarget = staging;
                    }
                }
                boolean replanned = attemptTowerMediumRangeReplan(source, bot, world, replanTarget,
                        "tower=(" + vertex.x() + "," + vertex.z() + ") " + context + " attempt=" + (attempt + 1),
                        vertex, navState);
                if (replanned && navState != null) {
                    double postReplanDistSq = bot.squaredDistanceTo(approachVec);
                    FortifyNavProgressWindow postReplanProgress = new FortifyNavProgressWindow(
                            cycleStart, bot.getBlockPos(), beforeTargetDistSq, postReplanDistSq,
                            System.currentTimeMillis() - cycleStartMs);
                    navState.noteMovement(cycleStart, bot.getBlockPos(), postReplanProgress.meaningful());
                    if (postReplanProgress.meaningful()) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                if (bot.squaredDistanceTo(approachVec) <= 16.0D) {
                    return true;
                }
            }

            LOGGER.info("[FortifyTower] approach-cycle tower=({}, {}) attempt={} target={} moved={} meaningful={} dist={} distDelta={} netDisp={}",
                    vertex.x(), vertex.z(), attempt + 1, towerApproach.toShortString(), movedThisCycle, meaningfulProgress,
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(bot.squaredDistanceTo(approachVec))),
                    String.format(Locale.ROOT, "%.1f", progress.targetDeltaBlocks()),
                    String.format(Locale.ROOT, "%.1f", progress.netDisplacementBlocks()));
            if (bot.squaredDistanceTo(approachVec) <= 16.0D) {
                if (navState != null) {
                    navState.noteMovement(cycleStart, bot.getBlockPos(), meaningfulProgress);
                    if (meaningfulProgress) navState.noteRealProgress(); else navState.noteNoRealProgress();
                }
                return true;
            }
        }

        if (navState != null) {
            navState.recordApproachFailure(towerApproach);
        }
        return false;
    }

    private void repositionNearAnchor(ServerCommandSource source, ServerPlayerEntity bot,
                                      ServerWorld world, BlockPos anchorPos, SurfaceProfile surfaceProfile,
                                      int attempt) {
        if (anchorPos == null) {
            return;
        }
        int[][] offsets = {
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {3, 3}, {-3, -3}, {3, -3}, {-3, 3}
        };
        int[] offset = offsets[Math.floorMod(attempt, offsets.length)];
        int targetX = anchorPos.getX() + offset[0];
        int targetZ = anchorPos.getZ() + offset[1];
        int targetY = safeSurfaceY(surfaceProfile, world, targetX, targetZ);
        walkToTarget(source, bot, new BlockPos(targetX, targetY, targetZ), 3_000L);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0, null, null);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attemptOffset, null, null);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset,
                                            List<ProceduralWallBlock> vertexBlocks,
                                            TowerNavAttemptState navState) {
        return selectReachableTowerApproach(bot, world, vertex, surfaceProfile, attemptOffset, vertexBlocks, navState);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset,
                                            List<ProceduralWallBlock> vertexBlocks) {
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attemptOffset, vertexBlocks, null);
    }

    private BlockPos selectReachableTowerApproach(ServerPlayerEntity bot, ServerWorld world,
                                                  WallPoint vertex, SurfaceProfile surfaceProfile,
                                                  int attemptOffset,
                                                  List<ProceduralWallBlock> vertexBlocks,
                                                  TowerNavAttemptState navState) {
        // Tower footprint is vertex ±1 (3×3). Min offset 3 = 1 block clearance from edge.
        int[][] candidates = {
                {3, 0}, {-3, 0}, {0, 3}, {0, -3},
                {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
                {1, 3}, {-1, 3}, {1, -3}, {-1, -3},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}
        };

        Set<BlockPos> locallyReachable = computeLocalReachableStandPositions(
                world, bot.getBlockPos(), new BlockPos(vertex.x(), bot.getBlockPos().getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS, bot.getBlockPos().getY() - 1, bot.getBlockPos().getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);

        List<TowerNavCandidate> preferredCandidates = new ArrayList<>();
        List<TowerNavCandidate> shallowRetryCandidates = new ArrayList<>();
        List<TowerNavCandidate> shallowFallbackCandidates = new ArrayList<>();
        List<TowerNavCandidate> deepFallbackCandidates = new ArrayList<>();
        int badHeadroomOrStand = 0;
        int noFloor = 0;
        int insideFootprint = 0;
        int notLocallyReachable = 0;
        int losZero = 0;
        int lowExits = 0;
        int tooDeep = 0;
        // Track best clearable-headroom candidate for fallback when all normal candidates fail.
        // These are positions with floor support where we could mine the feet/head blocks.
        BlockPos bestClearableHeadroomPos = null;
        double bestClearableHeadroomScore = Double.NEGATIVE_INFINITY;
        int startIndex = Math.floorMod(attemptOffset, candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            int[] c = candidates[(startIndex + i) % candidates.length];
            int x = vertex.x() + c[0];
            int z = vertex.z() + c[1];
            int safeY = safeSurfaceY(surfaceProfile, world, x, z);
            LinkedHashSet<Integer> yCandidates = new LinkedHashSet<>();
            for (int dy = -2; dy <= 2; dy++) yCandidates.add(safeY + dy);
            for (int dy = -1; dy <= 1; dy++) yCandidates.add(bot.getBlockPos().getY() + dy);
            BlockPos safeCol = SafePositionService.findSafeColumn(world, new BlockPos(x, safeY, z), -3, 3);
            if (safeCol != null) yCandidates.add(safeCol.getY());

            for (int y : yCandidates) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState feet = world.getBlockState(pos);
                BlockState head = world.getBlockState(pos.up());
                BlockState below = world.getBlockState(pos.down());
                boolean feetClear = feet.isAir() || feet.isReplaceable();
                boolean headClear = head.isAir() || head.isReplaceable();
                boolean support = !below.isAir() && !below.isReplaceable();
                if (!(feetClear && headClear && support)) {
                    if (!support) {
                        noFloor++;
                    } else {
                        badHeadroomOrStand++;
                        // Track clearable headroom candidates: has floor, but feet/head need mining.
                        // Only consider if blocks are mineable (hardness >= 0, not bedrock etc.).
                        boolean feetMineable = feetClear || (feet.getHardness(world, pos) >= 0);
                        boolean headMineable = headClear || (head.getHardness(world, pos.up()) >= 0);
                        if (feetMineable && headMineable && !isInsideTowerFootprint(pos, vertex)) {
                            int safeYH = safeSurfaceY(surfaceProfile, world, pos.getX(), pos.getZ());
                            int depthH = Math.max(0, safeYH - y);
                            if (depthH <= 1) {
                                double distSqH = bot.squaredDistanceTo(pos.getX() + 0.5, y, pos.getZ() + 0.5);
                                double scoreH = -distSqH - depthH * 220.0;
                                if (scoreH > bestClearableHeadroomScore) {
                                    bestClearableHeadroomScore = scoreH;
                                    bestClearableHeadroomPos = pos;
                                }
                            }
                        }
                    }
                    continue;
                }
                if (isInsideTowerFootprint(pos, vertex)) {
                    insideFootprint++;
                    continue;
                }
                int exits = countOpenExits(world, pos, null);
                if (exits < MIN_APPROACH_OPEN_EXITS) {
                    lowExits++;
                    continue;
                }
                boolean locallyReachableCandidate = isLocallyReachableStandPos(locallyReachable, pos);
                if (!locallyReachableCandidate) {
                    notLocallyReachable++;
                }
                int depthBelowSurface = Math.max(0, safeY - y);
                if (depthBelowSurface > 1) {
                    tooDeep++;
                }
                double distSq = bot.squaredDistanceTo(x + 0.5, y, z + 0.5);
                int losReachable = 0;
                if (vertexBlocks != null && !vertexBlocks.isEmpty()) {
                    losReachable = countReachableWithLOS(world, bot, pos, vertexBlocks);
                    if (losReachable == 0) {
                        losZero++;
                        continue;
                    }
                }
                double score = exits * 120.0 + losReachable * 200.0 - distSq;
                score -= depthBelowSurface * 220.0;
                if (depthBelowSurface > 1) {
                    score -= 1_800.0 + (depthBelowSurface - 1) * 500.0;
                }
                if (!locallyReachableCandidate) {
                    score -= 900.0;
                }
                if (attemptOffset > 0) {
                    score += i * 6.0;
                }
                boolean previouslyFailed = navState != null && navState.failedApproachCandidates.contains(pos);
                if (previouslyFailed) {
                    score -= 200.0;
                }
                TowerNavCandidate candidate = new TowerNavCandidate(pos, score, exits, locallyReachableCandidate, previouslyFailed);
                boolean shallowEnough = depthBelowSurface <= 1;
                if (shallowEnough && locallyReachableCandidate && !previouslyFailed) {
                    preferredCandidates.add(candidate);
                } else if (shallowEnough && locallyReachableCandidate) {
                    shallowRetryCandidates.add(candidate);
                } else if (shallowEnough) {
                    shallowFallbackCandidates.add(candidate);
                } else {
                    deepFallbackCandidates.add(candidate);
                }
            }
        }

        TowerNavCandidate best = chooseBestTowerNavCandidate(preferredCandidates);
        if (best == null) {
            best = chooseBestTowerNavCandidate(shallowRetryCandidates);
        }
        if (best == null) {
            best = chooseBestTowerNavCandidate(shallowFallbackCandidates);
        }
        if (best == null) {
            best = chooseBestTowerNavCandidate(deepFallbackCandidates);
        }

        if (best != null) {
            int bestSurfaceY = safeSurfaceY(surfaceProfile, world, best.pos().getX(), best.pos().getZ());
            int bestDepth = Math.max(0, bestSurfaceY - best.pos().getY());
            LOGGER.info("[FortifyTower] approach-select tower=({}, {}) pos={} score={} exits={} locallyReachable={} prevFailed={} depth={}",
                    vertex.x(), vertex.z(), best.pos().toShortString(),
                    String.format(Locale.ROOT, "%.1f", best.score()),
                    best.exits(), best.locallyReachable(), best.previouslyFailed(), bestDepth);
            return best.pos();
        }
        // Headroom-clearing fallback: if all candidates were rejected due to bad headroom,
        // try to mine the obstructing blocks at the best clearable position.
        if (bestClearableHeadroomPos != null) {
            BlockPos clearPos = bestClearableHeadroomPos;
            BlockState clFeet = world.getBlockState(clearPos);
            BlockState clHead = world.getBlockState(clearPos.up());
            boolean cleared = false;
            if (!clFeet.isAir() && !clFeet.isReplaceable() && clFeet.getHardness(world, clearPos) >= 0) {
                digBlock(bot, world, clearPos);
                sleepQuiet(80);
                cleared = true;
            }
            if (!clHead.isAir() && !clHead.isReplaceable() && clHead.getHardness(world, clearPos.up()) >= 0) {
                digBlock(bot, world, clearPos.up());
                sleepQuiet(80);
                cleared = true;
            }
            if (cleared) {
                LOGGER.info("[FortifyTower] approach-select tower=({}, {}) cleared-headroom at {} bad_headroom={} no_floor={}",
                        vertex.x(), vertex.z(), clearPos.toShortString(), badHeadroomOrStand, noFloor);
                return clearPos;
            }
        }
        int y = safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        BlockPos fallback = new BlockPos(vertex.x(), y, vertex.z());
        LOGGER.info("[FortifyTower] approach-select tower=({}, {}) fallback={} reason=no-standable-candidate bad_headroom={} no_floor={} inside_footprint={} not_locally_reachable={} los_zero={} low_exits={} too_deep={}",
                vertex.x(), vertex.z(), fallback.toShortString(),
                badHeadroomOrStand, noFloor, insideFootprint, notLocallyReachable, losZero, lowExits, tooDeep);
        return fallback;
    }

    private boolean canStandAt(ServerWorld world, BlockPos pos) {
        return VoxelJunctionService.isStandable(world, pos);
    }

    private TowerNavCandidate chooseBestTowerNavCandidate(List<TowerNavCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        TowerNavCandidate best = null;
        for (TowerNavCandidate c : candidates) {
            if (c == null) continue;
            if (best == null || c.score() > best.score()) {
                best = c;
            }
        }
        return best;
    }

    private Set<BlockPos> computeLocalReachableStandPositions(ServerWorld world,
                                                              BlockPos start,
                                                              BlockPos areaCenter,
                                                              int horizRadius,
                                                              int minY,
                                                              int maxY,
                                                              int maxNodes) {
        return VoxelJunctionService.computeReachableStandCells(
                world, start, areaCenter, horizRadius, minY, maxY, maxNodes,
                seed -> SafePositionService.findSafeNear(world, seed, 2));
    }

    private boolean isLocallyReachableStandPos(Set<BlockPos> reachable, BlockPos candidate) {
        if (candidate == null || reachable == null || reachable.isEmpty()) {
            return false;
        }
        if (reachable.contains(candidate)) {
            return true;
        }
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos probe = candidate.add(0, dy, 0);
            if (reachable.contains(probe)) return true;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (reachable.contains(probe.offset(dir))) return true;
            }
        }
        return false;
    }

    private boolean isLocallyReachableStandPos(ServerWorld world, BlockPos start, BlockPos candidate,
                                               BlockPos areaCenter, int horizRadius) {
        if (world == null || start == null || candidate == null || areaCenter == null) {
            return false;
        }
        Set<BlockPos> reachable = computeLocalReachableStandPositions(
                world, start, areaCenter, horizRadius, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);
        return isLocallyReachableStandPos(reachable, candidate);
    }

    private TowerNavCandidate selectReachableTowerHardResetCandidate(ServerPlayerEntity bot,
                                                                     ServerWorld world,
                                                                     WallPoint vertex,
                                                                     SurfaceProfile surfaceProfile,
                                                                     int attemptOffset,
                                                                     TowerNavAttemptState navState) {
        BlockPos start = bot.getBlockPos();
        int ringStart = 3 + Math.floorMod(attemptOffset, 2);
        Set<BlockPos> locallyReachable = computeLocalReachableStandPositions(
                world, start, new BlockPos(vertex.x(), start.getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS + 2, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);

        for (int r = ringStart; r <= 7; r++) {
            List<TowerNavCandidate> reachableRing = new ArrayList<>();
            List<TowerNavCandidate> fallbackRing = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = vertex.x() + dx;
                    int z = vertex.z() + dz;
                    int baseY = safeSurfaceY(surfaceProfile, world, x, z);
                    int[] yCandidates = {baseY, baseY + 1, start.getY(), start.getY() + 1};
                    for (int y : yCandidates) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!canStandAt(world, candidate)) continue;
                        if (isInsideTowerFootprint(candidate, vertex)) continue;
                        int exits = countOpenExits(world, candidate, null);
                        if (exits < 3) continue;
                        double distFromTowerSq = Math.pow(candidate.getX() - vertex.x(), 2)
                                + Math.pow(candidate.getZ() - vertex.z(), 2);
                        if (distFromTowerSq < 9.0D) continue;
                        boolean locallyReachableCandidate = isLocallyReachableStandPos(locallyReachable, candidate);
                        boolean prevFailed = navState != null && navState.failedHardResetCandidates.contains(candidate);
                        double score = exits * 140.0 + distFromTowerSq * 16.0 - start.getSquaredDistance(candidate) * 4.5;
                        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) score += 20.0;
                        if (prevFailed) score -= 220.0;
                        TowerNavCandidate t = new TowerNavCandidate(candidate, score, exits, locallyReachableCandidate, prevFailed);
                        if (locallyReachableCandidate && !prevFailed) {
                            reachableRing.add(t);
                        } else {
                            fallbackRing.add(t);
                        }
                    }
                }
            }
            TowerNavCandidate bestReachable = chooseBestTowerNavCandidate(reachableRing);
            if (bestReachable != null) {
                return bestReachable;
            }
            TowerNavCandidate bestFallback = chooseBestTowerNavCandidate(fallbackRing);
            if (bestFallback != null) {
                return bestFallback;
            }
        }
        return null;
    }

    private BlockPos chooseReachableTowerPatchStagingPos(ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         WallPoint vertex,
                                                         BlockPos towerApproach,
                                                         SurfaceProfile surfaceProfile,
                                                         TowerNavAttemptState navState) {
        if (bot == null || world == null || towerApproach == null || vertex == null) {
            return null;
        }
        BlockPos start = bot.getBlockPos();
        Set<BlockPos> reachable = computeLocalReachableStandPositions(
                world, start, new BlockPos(vertex.x(), start.getY(), vertex.z()),
                TOWER_LOCAL_REACHABILITY_RADIUS + 2, start.getY() - 1, start.getY() + 2,
                TOWER_LOCAL_REACHABILITY_MAX_NODES);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int r = 2; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = towerApproach.getX() + dx;
                    int z = towerApproach.getZ() + dz;
                    int baseY = safeSurfaceY(surfaceProfile, world, x, z);
                    int[] yCandidates = {baseY, start.getY(), baseY + 1};
                    for (int y : yCandidates) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (!canStandAt(world, candidate)) continue;
                        if (isInsideTowerFootprint(candidate, vertex)) continue;
                        if (!isLocallyReachableStandPos(reachable, candidate)) continue;
                        if (navState != null && navState.failedHardResetCandidates.contains(candidate)) continue;
                        int exits = countOpenExits(world, candidate, null);
                        if (exits < 2) continue;
                        double score = exits * 100.0;
                        score -= candidate.getSquaredDistance(towerApproach) * 8.0;
                        score -= start.getSquaredDistance(candidate) * 2.5;
                        if (candidate.getSquaredDistance(towerApproach) < start.getSquaredDistance(towerApproach)) {
                            score += 60.0;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) break;
        }
        if (best != null) {
            LOGGER.info("[FortifyTower] staging-select tower=({}, {}) target={} staging={}",
                    vertex.x(), vertex.z(), towerApproach.toShortString(), best.toShortString());
        }
        return best;
    }

    private boolean attemptTowerMediumRangeReplan(ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos target,
                                                  String reason,
                                                  WallPoint towerVertex,
                                                  TowerNavAttemptState towerState) {
        if (source == null || bot == null || world == null || target == null) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        double distSq = bot.squaredDistanceTo(target.getX() + 0.5, bot.getY(), target.getZ() + 0.5);
        if (distSq < 16.0D || distSq > 400.0D) { // fortify patch local only (<=20 blocks)
            return false;
        }
        if (fortifyReplanActive) {
            LOGGER.info("[FortifyTower] medium-replan skipped target={} reason={} nested-replan-active",
                    target.toShortString(), reason);
            return false;
        }

        // Prefer local stepping for very close targets; avoids long blocking MovementService calls.
        if (distSq <= 144.0D) { // <= 12 blocks
            LOGGER.info("[FortifyTower] medium-replan using local-step target={} reason={}",
                    target.toShortString(), reason);
            BlockPos beforeLocal = bot.getBlockPos();
            runWithFortifyTowerNavScope(bot, world, "fortify-tower:local-step-replan",
                    towerState, towerVertex, target,
                    () -> walkToTarget(source, bot, target, 1_500L, "fortify-tower:local-step-replan"));
            FortifyNavProgressWindow localProgress = new FortifyNavProgressWindow(
                    beforeLocal, bot.getBlockPos(), beforeLocal.getSquaredDistance(target),
                    bot.getBlockPos().getSquaredDistance(target), 1_500L);
            return localProgress.meaningful();
        }

        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) {
            LOGGER.info("[FortifyTower] medium-replan skipped target={} reason={} no-plan",
                    target.toShortString(), reason);
            return false;
        }
        LOGGER.info("[FortifyTower] medium-replan target={} reason={} dist={}",
                target.toShortString(), reason, String.format(Locale.ROOT, "%.1f", Math.sqrt(distSq)));
        long epoch = ++fortifyMovementEpoch;
        long startMs = System.currentTimeMillis();
        fortifyReplanActive = true;
        try {
            MovementService.withoutDoorEscape(() ->
                    MovementService.withoutObstructionMining(
                            () -> MovementService.execute(source, bot, plan.get(), null)));
        } finally {
            fortifyReplanActive = false;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        if (epoch != fortifyMovementEpoch) {
            LOGGER.info("[FortifyTower] medium-replan ignored target={} reason={} stale-epoch", target.toShortString(), reason);
            return false;
        }
        boolean moved = !before.equals(bot.getBlockPos());
        FortifyNavProgressWindow progress = new FortifyNavProgressWindow(
                before, bot.getBlockPos(), before.getSquaredDistance(target),
                bot.getBlockPos().getSquaredDistance(target), elapsed);
        if (moved) {
            runWithFortifyTowerNavScope(bot, world, "fortify-tower:post-replan",
                    towerState, towerVertex, target,
                    () -> walkToTarget(source, bot, target, 1_200L, "fortify-tower:post-replan"));
        }
        if (elapsed > FORTIFY_MEDIUM_REPLAN_BUDGET_MS && !progress.meaningful()) {
            LOGGER.info("[FortifyTower] medium-replan over-budget target={} elapsedMs={} meaningful=false",
                    target.toShortString(), elapsed);
            return false;
        }
        return progress.meaningful();
    }

    private boolean tryNaturalStepUpTowardTarget(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        if (bot == null || world == null || target == null) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        for (Direction dir : prioritizedDirectionsToward(before, target)) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            BlockPos lane = before.offset(dir);
            BlockPos stepUp = lane.up();
            if (!canStandAt(world, stepUp)) {
                continue;
            }
            BlockState headNow = world.getBlockState(before.up(2));
            BlockState headAhead = world.getBlockState(lane.up(2));
            boolean clearNow = headNow.isAir() || headNow.isReplaceable();
            boolean clearAhead = headAhead.isAir() || headAhead.isReplaceable();
            if (!clearNow || !clearAhead) {
                continue;
            }
            BotActions.jump(bot);
            walkTowardBlock(bot, stepUp, 900L);
            if (!before.equals(bot.getBlockPos())) {
                return true;
            }
        }
        return false;
    }

    private boolean tryWideArcReachReposition(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, BlockPos target) {
        BlockPos waypoint = chooseWideArcReachWaypoint(bot, world, target);
        if (waypoint == null || waypoint.equals(bot.getBlockPos())) {
            return false;
        }
        BlockPos before = bot.getBlockPos();
        String scopeCtx = activeFortifyNavScope != null ? activeFortifyNavScope.context : null;
        if (scopeCtx != null && scopeCtx.startsWith("fortify-edge:")) {
            runWithFortifyEdgeNavScope(bot, world, "fortify-edge:wide-arc-reach", waypoint,
                    () -> walkToTarget(source, bot, waypoint, 1_400L, "fortify-edge:wide-arc-reach"));
        } else if (scopeCtx != null && scopeCtx.startsWith("fortify-tower:")) {
            TowerNavAttemptState towerState = activeFortifyNavScope != null ? activeFortifyNavScope.towerState : null;
            WallPoint towerVertex = activeFortifyNavScope != null ? activeFortifyNavScope.towerVertex : null;
            runWithFortifyTowerNavScope(bot, world, "fortify-tower:wide-arc-reach",
                    towerState, towerVertex, waypoint,
                    () -> walkToTarget(source, bot, waypoint, 1_400L, "fortify-tower:wide-arc-reach"));
        } else {
            walkToTarget(source, bot, waypoint, 1_400L);
        }
        if (!before.equals(bot.getBlockPos())) {
            return true;
        }
        walkTowardBlock(bot, waypoint, 900L);
        return !before.equals(bot.getBlockPos());
    }

    private boolean tryPostCarvePocketEscapeToward(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        if (bot == null || world == null) return false;
        BlockPos start = bot.getBlockPos();
        VoxelJunctionService.VoxelStandCell startCell = VoxelJunctionService.analyzeStandCell(world, start);
        if (startCell.openFaces() > 1 && startCell.topology() != VoxelJunctionService.CellTopology.POCKET
                && startCell.topology() != VoxelJunctionService.CellTopology.DEAD_END) {
            return false;
        }

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (VoxelJunctionService.VoxelTransition t : VoxelJunctionService.transitionsFrom(world, start)) {
            if (t == null || t.requiresCarve() || t.to() == null) continue;
            BlockPos candidate = t.to();
            if (!canStandAt(world, candidate)) continue;
            VoxelJunctionService.VoxelStandCell cell = VoxelJunctionService.analyzeStandCell(world, candidate);
            double score = cell.openFaces() * 120.0;
            score -= start.getSquaredDistance(candidate) * 3.0;
            if (target != null) {
                score -= candidate.getSquaredDistance(target) * 0.8;
                if (candidate.getSquaredDistance(target) < start.getSquaredDistance(target)) {
                    score += 75.0;
                }
            }
            if (cell.topology() == VoxelJunctionService.CellTopology.OPENING) score += 80.0;
            if (cell.topology() == VoxelJunctionService.CellTopology.CORRIDOR) score += 40.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || best.equals(start)) {
            return false;
        }
        LOGGER.info("[FortifyNav] post-carve escape nudge from={} to={} target={}",
                start.toShortString(), best.toShortString(), target != null ? target.toShortString() : "n/a");
        walkTowardBlock(bot, best, 900L);
        return !start.equals(bot.getBlockPos());
    }

    private BlockPos chooseWideArcReachWaypoint(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        if (bot == null || world == null || target == null) {
            return null;
        }
        BlockPos botPos = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = botPos.add(dx, dy, dz);
                        if (!canStandAt(world, candidate)) {
                            continue;
                        }
                        int exits = countOpenExits(world, candidate, null);
                        if (exits < 2) {
                            continue;
                        }

                        double towardGain = botPos.getSquaredDistance(target) - candidate.getSquaredDistance(target);
                        double score = exits * 110.0;
                        score += towardGain * 5.5;
                        score -= botPos.getSquaredDistance(candidate) * 6.5;
                        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) {
                            score += 24.0;
                        }
                        if (isWithinMiningReachFrom(candidate, target)) {
                            score += 140.0;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) {
                break;
            }
        }
        return best;
    }

    private List<Direction> prioritizedDirectionsToward(BlockPos from, BlockPos target) {
        List<Direction> dirs = new ArrayList<>(List.of(
                Direction.NORTH,
                Direction.SOUTH,
                Direction.EAST,
                Direction.WEST
        ));
        if (from == null || target == null) {
            return dirs;
        }
        dirs.sort(Comparator.comparingDouble(dir -> from.offset(dir).getSquaredDistance(target)));
        return dirs;
    }

    private boolean shouldAvoidSelfTrapPlacement(ServerWorld world, ServerPlayerEntity bot, BlockPos targetPos) {
        BlockPos botPos = bot.getBlockPos();
        if (targetPos.equals(botPos) || targetPos.equals(botPos.up())) {
            return true;
        }

        int dy = Math.abs(targetPos.getY() - botPos.getY());
        int manhattan = Math.abs(targetPos.getX() - botPos.getX()) + Math.abs(targetPos.getZ() - botPos.getZ());
        if (dy > 1 || manhattan > 1) {
            return false;
        }

        int exitsAfter = countOpenExits(world, botPos, targetPos);
        return exitsAfter <= 1;
    }

    private boolean shouldAttemptReachUnwedge(ServerWorld world, ServerPlayerEntity bot, BlockPos targetPos, int passNumber) {
        if (world == null || bot == null) {
            return false;
        }
        // Skip unwedge when bot is within 10 blocks — ensureCanReachBlockWithEffort handles positioning
        if (targetPos != null) {
            double horizSq = Math.pow(targetPos.getX() - bot.getBlockPos().getX(), 2)
                    + Math.pow(targetPos.getZ() - bot.getBlockPos().getZ(), 2);
            if (horizSq <= 100.0D) {
                return false;
            }
        }
        int exits = countOpenExits(world, bot.getBlockPos(), null);
        if (exits <= 1) {
            return true;
        }
        return false;
    }

    private boolean isStandingOnScaffoldBlock(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        BlockState below = world.getBlockState(bot.getBlockPos().down());
        Item belowItem = below.getBlock().asItem();
        return ScaffoldService.SCAFFOLD_BLOCKS.contains(belowItem);
    }

    private int clearBlockingScaffoldsNearBot(ServerPlayerEntity bot, ServerWorld world, int radius) {
        if (bot == null || world == null || radius < 0) {
            return 0;
        }
        Set<BlockPos> memory = ScaffoldService.getScaffoldMemory(bot);
        if (memory.isEmpty()) {
            return 0;
        }

        BlockPos botPos = bot.getBlockPos();
        List<BlockPos> nearby = new ArrayList<>();
        for (BlockPos pos : new ArrayList<>(memory)) {
            if (Math.abs(pos.getX() - botPos.getX()) > radius
                    || Math.abs(pos.getZ() - botPos.getZ()) > radius
                    || Math.abs(pos.getY() - botPos.getY()) > 2) {
                continue;
            }
            Item current = world.getBlockState(pos).getBlock().asItem();
            if (!ScaffoldService.SCAFFOLD_BLOCKS.contains(current)) {
                memory.remove(pos);
                continue;
            }
            nearby.add(pos);
        }
        if (nearby.isEmpty()) {
            return 0;
        }

        int removed = ScaffoldService.teardownScaffolds(bot, world, nearby, Set.of());
        if (removed <= 0) {
            return 0;
        }

        for (BlockPos pos : nearby) {
            Item current = world.getBlockState(pos).getBlock().asItem();
            if (!ScaffoldService.SCAFFOLD_BLOCKS.contains(current)) {
                memory.remove(pos);
            }
        }
        LOGGER.debug("Cleared {} blocking scaffolds near {}", removed, botPos.toShortString());
        return removed;
    }

    @Override
    public boolean beginScaffoldEdgeHold(ServerPlayerEntity bot, ServerWorld world, BlockPos focusPos) {
        if (bot == null || world == null) {
            return false;
        }
        if (!isStandingOnScaffoldBlock(bot, world)) {
            return false;
        }
        if (focusPos != null && bot.getBlockPos().getSquaredDistance(focusPos) > 49.0D) {
            return false;
        }
        SneakLockService.acquire(bot.getUuid());
        BotActions.sneak(bot, true);
        return true;
    }

    @Override
    public void endScaffoldEdgeHold(ServerPlayerEntity bot, boolean held) {
        if (!held || bot == null) {
            return;
        }
        SneakLockService.release(bot.getUuid());
        if (!SneakLockService.isLocked(bot.getUuid())) {
            BotActions.sneak(bot, false);
        }
    }

    private boolean tryUnwedgeFromTightSpace(ServerCommandSource source, ServerPlayerEntity bot,
                                             ServerWorld world, SurfaceProfile surfaceProfile,
                                             BlockPos anchorPos, String context) {
        BlockPos botPos = bot.getBlockPos();
        int exits = countOpenExits(world, botPos, null);
        if (exits >= 3) {
            return false;
        }

        if (tryImmediateLateralOrStepEscape(bot, world, anchorPos)) {
            LOGGER.debug("Unwedge: context={} local-escape success from={} exitsBefore={}",
                    context, botPos.toShortString(), exits);
            return true;
        }

        double currentAnchorDistSq = anchorPos != null
                ? botPos.getSquaredDistance(anchorPos)
                : 0.0;
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int tx = botPos.getX() + dx;
                    int tz = botPos.getZ() + dz;
                    int baseY = safeSurfaceY(surfaceProfile, world, tx, tz);
                    int[] yCandidates = {baseY, botPos.getY(), botPos.getY() + 1, baseY + 1};
                    for (int ty : yCandidates) {
                        BlockPos candidate = new BlockPos(tx, ty, tz);
                        if (!canStandAt(world, candidate)) {
                            continue;
                        }
                        int candidateExits = countOpenExits(world, candidate, null);
                        if (candidateExits < 2) {
                            continue;
                        }
                        double moveSq = botPos.getSquaredDistance(candidate);
                        if (moveSq < 2.25D && candidateExits < 3) {
                            continue;
                        }

                        int score = candidateExits * 120;
                        score -= (int) Math.round(botPos.getSquaredDistance(candidate) * 10.0);
                        score += (int) Math.round(Math.min(120.0, moveSq * 10.0));
                        if (candidate.getY() > botPos.getY()) {
                            score += 25;
                        }
                        if (anchorPos != null && candidate.getSquaredDistance(anchorPos) > currentAnchorDistSq + 0.5) {
                            score += 40;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) {
                break;
            }
        }

        if (best == null) {
            return false;
        }

        BlockPos before = bot.getBlockPos();
        LOGGER.debug("Unwedge: context={} from={} to={} exitsBefore={}", context, before.toShortString(),
                best.toShortString(), exits);
        String navCtx = null;
        if (context != null) {
            if (context.startsWith("edge-")) {
                navCtx = "fortify-edge:unwedge";
            } else if (context.startsWith("fortify-tower") || context.contains("tower")) {
                navCtx = "fortify-tower:unwedge";
            }
        }
        if (navCtx != null && navCtx.startsWith("fortify-edge:")) {
            final String edgeNavCtx = navCtx;
            final BlockPos edgeBest = best;
            runWithFortifyEdgeNavScope(bot, world, edgeNavCtx, best,
                    () -> walkToTarget(source, bot, edgeBest, 2_500L, edgeNavCtx));
        } else if (navCtx != null && navCtx.startsWith("fortify-tower:")) {
            FortifyNavRuntimeScope scope = activeFortifyNavScope;
            TowerNavAttemptState towerState = scope != null ? scope.towerState : null;
            WallPoint towerVertex = scope != null ? scope.towerVertex : null;
            final String towerNavCtx = navCtx;
            final BlockPos towerBest = best;
            runWithFortifyTowerNavScope(bot, world, towerNavCtx, towerState, towerVertex, towerBest,
                    () -> walkToTarget(source, bot, towerBest, 2_500L, towerNavCtx));
        } else if (navCtx != null) {
            walkToTarget(source, bot, best, 2_500L, navCtx);
        } else {
            walkToTarget(source, bot, best, 2_500L);
        }
        BlockPos after = bot.getBlockPos();
        int exitsAfter = countOpenExits(world, after, null);
        double movedSq = before.getSquaredDistance(after);
        boolean meaningful = movedSq >= 2.25D || exitsAfter >= Math.max(3, exits + 1);
        if (!meaningful && !before.equals(after)) {
            walkTowardBlock(bot, best, 900L);
            after = bot.getBlockPos();
            exitsAfter = countOpenExits(world, after, null);
            movedSq = before.getSquaredDistance(after);
            meaningful = movedSq >= 2.25D || exitsAfter >= Math.max(3, exits + 1);
        }
        // Last resort: if still stuck, try breaking through toward the unwedge target
        if (!meaningful) {
            if (tryBreakThroughObstacle(bot, world, best, navCtx != null ? navCtx : context)) {
                return true;
            }
        }
        return meaningful;
    }

    private boolean tryImmediateLateralOrStepEscape(ServerPlayerEntity bot, ServerWorld world, BlockPos anchorPos) {
        if (bot == null || world == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();
        List<Direction> dirs;
        if (anchorPos != null) {
            Direction towardAnchor = dominantHorizontalDirection(start, anchorPos);
            dirs = List.of(
                    towardAnchor.rotateYClockwise(),
                    towardAnchor.rotateYCounterclockwise(),
                    towardAnchor.getOpposite(),
                    towardAnchor
            );
        } else {
            dirs = List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        }

        for (Direction dir : dirs) {
            BlockPos lateral = start.offset(dir);
            if (canStandAt(world, lateral)) {
                walkTowardBlock(bot, lateral, 700L);
                if (!start.equals(bot.getBlockPos())) {
                    return true;
                }
            }

            BlockPos stepUp = lateral.up();
            if (!canStandAt(world, stepUp)) {
                continue;
            }
            BlockState headNow = world.getBlockState(start.up(2));
            BlockState headAhead = world.getBlockState(lateral.up(2));
            boolean clearNow = headNow.isAir() || headNow.isReplaceable();
            boolean clearAhead = headAhead.isAir() || headAhead.isReplaceable();
            if (!clearNow || !clearAhead) {
                continue;
            }
            BotActions.jump(bot);
            walkTowardBlock(bot, stepUp, 900L);
            if (!start.equals(bot.getBlockPos())) {
                return true;
            }
        }
        return false;
    }

    private Direction dominantHorizontalDirection(BlockPos from, BlockPos to) {
        return FortifyEscapeHelper.dominantHorizontalDirection(from, to);
    }

    private int countOpenExits(ServerWorld world, BlockPos center, BlockPos forcedSolidPos) {
        int exits = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = center.offset(dir);
            if (canStandAtWithForcedSolid(world, neighbor, forcedSolidPos)) {
                exits++;
            }
        }
        return exits;
    }

    private boolean canStandAtWithForcedSolid(ServerWorld world, BlockPos pos, BlockPos forcedSolidPos) {
        BlockPos feetPos = pos;
        BlockPos headPos = pos.up();
        BlockPos belowPos = pos.down();

        boolean feetClear = !isForcedSolid(forcedSolidPos, feetPos);
        if (feetClear) {
            BlockState feet = world.getBlockState(feetPos);
            feetClear = feet.isAir() || feet.isReplaceable();
        }

        boolean headClear = !isForcedSolid(forcedSolidPos, headPos);
        if (headClear) {
            BlockState head = world.getBlockState(headPos);
            headClear = head.isAir() || head.isReplaceable();
        }

        boolean hasSupport;
        if (isForcedSolid(forcedSolidPos, belowPos)) {
            hasSupport = true;
        } else {
            BlockState below = world.getBlockState(belowPos);
            hasSupport = !below.isAir() && !below.isReplaceable();
        }

        return feetClear && headClear && hasSupport;
    }

    private boolean isForcedSolid(BlockPos forcedSolidPos, BlockPos testPos) {
        return forcedSolidPos != null && forcedSolidPos.equals(testPos);
    }

    private boolean isFortifyPrecipiceDefenseContext(String navContext) {
        return escapeHelper.isFortifyPrecipiceDefenseContext(navContext);
    }

    private boolean hasDangerousFortifyPrecipiceAhead(ServerPlayerEntity bot, ServerWorld world,
                                                      BlockPos target, String navContext) {
        return escapeHelper.hasDangerousFortifyPrecipiceAhead(bot, world, target, navContext);
    }

    private boolean tryPatchFortifyFootingNearWorksite(ServerPlayerEntity bot, ServerWorld world,
                                                       BlockPos target, String navContext, String reason) {
        return escapeHelper.tryPatchFortifyFootingNearWorksite(bot, world, target, navContext, reason);
    }

    private boolean isFortifyEscapeContext(String contextTag) {
        return escapeHelper.isFortifyEscapeContext(contextTag);
    }

    private int countEscapeShaftBlockers(ServerPlayerEntity bot, ServerWorld world, int stepsToClimb) {
        return escapeHelper.countEscapeShaftBlockers(bot, world, stepsToClimb);
    }

    private int clearEscapeShaftHeadroom(ServerPlayerEntity bot, ServerWorld world, int stepsToClimb, String contextTag) {
        return escapeHelper.clearEscapeShaftHeadroom(bot, world, stepsToClimb, contextTag);
    }

    private int clearImmediateOverheadForEscape(ServerPlayerEntity bot, ServerWorld world) {
        return escapeHelper.clearImmediateOverheadForEscape(bot, world);
    }

    private boolean tryPillarEscapeFirst(ServerPlayerEntity bot, ServerWorld world,
                                         int referenceSurfaceY, String contextTag) {
        return escapeHelper.tryPillarEscapeFirst(this, bot, world, referenceSurfaceY, contextTag);
    }

    // ── Hole escape ──────────────────────────────────────────────

    /**
     * Escape from a hole/moat using heightmap terrain Y.
     */
    @Override
    public void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world) {
        escapeHelper.escapeIfInHole(this, bot, world);
    }

    /**
     * Escape from a hole/moat below the given reference surface Y.
     */
    @Override
    public void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        escapeHelper.escapeIfInHole(this, bot, world, referenceSurfaceY);
    }

    /**
     * Ensure the bot is standing on solid ground at the reference surface level.
     * Called at the start of buildWall to handle resume from stuck positions (e.g. in moat).
     */
    @Override
    public void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        escapeHelper.ensureOnSurface(this, bot, world, referenceSurfaceY);
    }

    /**
     * Ensure the bot is standing on solid ground (heightmap-based, for non-moat contexts).
     */
    @Override
    public void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world) {
        escapeHelper.ensureOnSurface(this, bot, world);
    }

    // ── Movement & reach ────────────────────────────────────────

    /**
     * Get the bot within reach of a target block. Uses ONLY tick-based movement
     * (no A* pathfinding) to avoid door-escape loops near village structures.
     *
     * Strategy order:
     *   1. Tick-based walk toward the block
     *   2. Side approach from 4 directions (pass 2+)
     *   3. Local lateral/jump/arc maneuvers to avoid self-trap corners
     *   4. Scaffolding only as bounded last resort (pass 2+)
     */
    @Override
    public boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber) {
        int fallbackSurfaceY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        return ensureCanReachBlockWithEffort(source, bot, world, target, heightAboveGround, passNumber, fallbackSurfaceY, null);
    }

    @Override
    public boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber,
                                                   int referenceSurfaceY) {
        return ensureCanReachBlockWithEffort(source, bot, world, target, heightAboveGround, passNumber, referenceSurfaceY, null);
    }

    @Override
    public boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber,
                                                   int referenceSurfaceY,
                                                   Set<BlockPos> scaffoldFailedPositions) {
        if (isWithinReach(bot, target)) return true;
        if (SkillManager.shouldAbortSkill(bot)) {
            BotActions.stop(bot);
            return false;
        }

        BlockPos botPos = bot.getBlockPos();
        double horizontalDistSq = Math.pow(target.getX() - botPos.getX(), 2) + Math.pow(target.getZ() - botPos.getZ(), 2);
        int verticalDiff = target.getY() - botPos.getY();

        // Too far — caller should use walkToTarget first
        if (horizontalDistSq > 400) return false;

        // If bot is below terrain (in a moat/hole), escape first before attempting scaffold
        if (shouldTriggerDepthRecovery(botPos.getY(), referenceSurfaceY)) {
            escapeIfInHole(bot, world, referenceSurfaceY);
            botPos = bot.getBlockPos(); // refresh after escape
            verticalDiff = target.getY() - botPos.getY();
            if (isWithinReach(bot, target)) return true;
        }

        // Strategy 1: Walk toward the block first (covers most cases — all Y+0..Y+4 reachable from ground)
        if (horizontalDistSq > REACH_DISTANCE_SQ) {
            walkTowardBlock(bot, target, 1500L);
            if (isWithinReach(bot, target)) return true;
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
        }

        // Strategy 2: Side approach from 2 perpendicular directions (pass 2+)
        if (passNumber >= 2) {
            double dx = target.getX() - botPos.getX();
            double dz = target.getZ() - botPos.getZ();
            // Pick the two directions perpendicular to bot→target axis
            Direction[] perpDirs = Math.abs(dx) >= Math.abs(dz)
                    ? new Direction[]{Direction.NORTH, Direction.SOUTH}
                    : new Direction[]{Direction.EAST, Direction.WEST};
            for (Direction dir : perpDirs) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    BotActions.stop(bot);
                    return false;
                }
                BlockPos sidePos = target.offset(dir, 2).withY(bot.getBlockPos().getY());
                walkTowardBlock(bot, sidePos, 600L);
                if (isWithinReach(bot, target)) return true;
            }
        }

        // Strategy 3: Prefer natural motion escapes before scaffolding.
        verticalDiff = target.getY() - bot.getBlockPos().getY();
        if (verticalDiff >= 1 && verticalDiff <= 2) {
            if (tryNaturalStepUpTowardTarget(bot, world, target) && isWithinReach(bot, target)) {
                return true;
            }
        }

        if (passNumber >= 2 || countOpenExits(world, bot.getBlockPos(), null) <= 2) {
            if (tryWideArcReachReposition(source, bot, world, target) && isWithinReach(bot, target)) {
                return true;
            }
        }

        // Strategy 4: Scaffolding for elevated blocks still out of reach.
        // Two tiers: blocks 4+ above bot are clearly unreachable from ground (jumping
        // only reaches +2.5), so scaffold immediately on any pass. For marginal cases
        // (verticalDiff 2-3), wait until pass 2 to let movement/jump/arc retries run first.
        verticalDiff = target.getY() - bot.getBlockPos().getY(); // refresh after walking
        boolean shouldScaffold = (verticalDiff >= 4 && heightAboveGround > 0 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT)
                || (passNumber >= 2 && verticalDiff >= 2 && heightAboveGround > 0 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT);
        if (shouldScaffold) {
            BlockPos scaffoldBase = new BlockPos(target.getX(), bot.getBlockPos().getY(), target.getZ());
            boolean scaffoldBlacklisted = scaffoldFailedPositions != null
                    && scaffoldFailedPositions.contains(scaffoldBase);

            if (!scaffoldBlacklisted) {
                if (!isWithinReachXZ(bot, scaffoldBase, 2.0)) {
                    walkTowardBlock(bot, scaffoldBase, 1500L);
                    if (SkillManager.shouldAbortSkill(bot)) {
                        BotActions.stop(bot);
                        return false;
                    }
                }

                int currentBotY = bot.getBlockPos().getY();
                int optimalY = target.getY() - 1;
                int stepsNeeded = Math.max(0, optimalY - currentBotY);

                if (stepsNeeded > 0 && stepsNeeded <= MAX_SCAFFOLD_HEIGHT) {
                    boolean pillared = ScaffoldService.pillarUp(bot, stepsNeeded, true);
                    if (SkillManager.shouldAbortSkill(bot)) {
                        BotActions.stop(bot);
                        return false;
                    }
                    if (pillared && isWithinReach(bot, target)) return true;
                    if (!pillared && scaffoldFailedPositions != null) {
                        scaffoldFailedPositions.add(scaffoldBase);
                    }
                }
            }
        }

        return isWithinReach(bot, target);
    }

    /**
     * Simple tick-based walk toward a block position. No pathfinding,
     * no door handling — just face and walk. Fast bail on stuck.
     */
    @Override
    public void walkTowardBlock(ServerPlayerEntity bot, BlockPos target, long timeoutMs) {
        Vec3d targetVec = Vec3d.ofCenter(target);
        long deadline = System.currentTimeMillis() + timeoutMs;
        long phaseStartMs = System.currentTimeMillis();
        int tickCount = 0;
        double lastDistSq = Double.MAX_VALUE; // don't compare on first tick
        int stuckTicks = 0;
        boolean scaffoldHold = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (abortFortifyPhase(bot, "walkTowardBlock", phaseStartMs)) {
                    return;
                }
                double distSq = bot.squaredDistanceTo(targetVec);
                if (distSq < 6.0) return; // close enough for block placement

                ServerWorld world = (ServerWorld) bot.getEntityWorld();
                boolean onScaffold = isStandingOnScaffoldBlock(bot, world);
                if (onScaffold && !scaffoldHold) {
                    scaffoldHold = beginScaffoldEdgeHold(bot, world, target);
                }

                String activeNavCtx = activeFortifyNavScope != null ? activeFortifyNavScope.context : null;
                if (hasDangerousFortifyPrecipiceAhead(bot, world, target, activeNavCtx)) {
                    boolean patched = tryPatchFortifyFootingNearWorksite(bot, world, target, activeNavCtx, "precipice-defense:walkToward");
                    if (!patched) {
                        BotActions.stop(bot);
                        sleepQuiet(50);
                        stuckTicks++;
                        if (stuckTicks >= 2) return;
                        continue;
                    }
                    sleepQuiet(50);
                    continue;
                }

                // Apply movement FIRST, then check stuck on subsequent ticks
                LookController.faceBlock(bot, target);
                double impulse = onScaffold ? 0.12D : 0.28D;
                BotActions.applyMovementInput(bot, targetVec, impulse);
                sleepQuiet(50);
                tickCount++;

                // Only check stuck after at least 3 ticks of movement input
                if (tickCount >= 3) {
                    if (Math.abs(distSq - lastDistSq) < 0.3) {
                        stuckTicks++;
                        if (stuckTicks >= 3) return; // bail after ~150ms of no progress
                    } else {
                        stuckTicks = 0;
                    }
                }
                lastDistSq = distSq;
            }
        } finally {
            endScaffoldEdgeHold(bot, scaffoldHold);
        }
    }

    /**
     * Stuck recovery for local navigation: deliberately back up, then bias toward a wider
     * side lane before resuming toward the target. Uses only local movement (no A*).
     */
    private boolean tryBacktrackArcWalkRecovery(ServerPlayerEntity bot, ServerWorld world,
                                                BlockPos target, int attemptOrdinal) {
        if (bot == null || world == null || target == null) {
            return false;
        }

        BlockPos start = bot.getBlockPos();
        Direction toward = dominantHorizontalDirection(start, target);
        Direction back = toward.getOpposite();
        Direction sideA = back.rotateYClockwise();
        Direction sideB = back.rotateYCounterclockwise();
        if ((attemptOrdinal & 1) == 0) {
            Direction tmp = sideA;
            sideA = sideB;
            sideB = tmp;
        }

        int[][] offsets = new int[][]{
                {back.getOffsetX() * 3, back.getOffsetZ() * 3},
                {back.getOffsetX() * 2 + sideA.getOffsetX() * 2, back.getOffsetZ() * 2 + sideA.getOffsetZ() * 2},
                {back.getOffsetX() * 2 + sideB.getOffsetX() * 2, back.getOffsetZ() * 2 + sideB.getOffsetZ() * 2},
                {back.getOffsetX() * 4 + sideA.getOffsetX(), back.getOffsetZ() * 4 + sideA.getOffsetZ()},
                {back.getOffsetX() * 4 + sideB.getOffsetX(), back.getOffsetZ() * 4 + sideB.getOffsetZ()}
        };
        int[] yOffsets = new int[]{0, 1, -1};

        BlockPos backup = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] off : offsets) {
            for (int dy : yOffsets) {
                BlockPos candidate = start.add(off[0], dy, off[1]);
                if (!canStandAt(world, candidate)) {
                    continue;
                }
                int exits = countOpenExits(world, candidate, null);
                if (exits < 2) {
                    continue;
                }
                double score = exits * 140.0;
                score -= start.getSquaredDistance(candidate) * 6.0;
                score -= candidate.getSquaredDistance(target) * 0.45;
                if (dy == 0) {
                    score += 12.0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    backup = candidate;
                }
            }
        }

        if (backup == null) {
            return false;
        }

        LOGGER.debug("[FortifyNav] backtrack-arc backup {}", backup.toShortString());
        walkTowardBlock(bot, backup, 1_100L);
        if (start.equals(bot.getBlockPos())) {
            return false;
        }

        BlockPos arcWaypoint = chooseWideArcReachWaypoint(bot, world, target);
        if (arcWaypoint != null && !arcWaypoint.equals(bot.getBlockPos())) {
            LOGGER.debug("[FortifyNav] backtrack-arc waypoint {}", arcWaypoint.toShortString());
            BlockPos beforeArc = bot.getBlockPos();
            walkTowardBlock(bot, arcWaypoint, 1_100L);
            if (!beforeArc.equals(bot.getBlockPos())) {
                return true;
            }
        }

        return !start.equals(bot.getBlockPos());
    }

    /**
     * Walk toward a target position using tick-based impulse movement.
     * Pure tick-based — does NOT fall back to A* pathfinding, which can hang
     * in door-escape loops near village structures. Individual block placement
     * uses ensureCanReachBlockWithEffort for fine-grained precision.
     */
    @Override
    public void walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target, long timeoutMs) {
        walkToTarget(source, bot, target, timeoutMs, null);
    }

    @Override
    public void walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target,
                              long timeoutMs, String navContext) {
        Vec3d targetVec = Vec3d.ofCenter(target);
        double distSq = bot.squaredDistanceTo(targetVec);
        if (distSq < 9.0) return; // already within 3 blocks

        // Scale timeout with distance: at least 400ms per block of distance, minimum 2s.
        // Prevents premature timeout on long-range walks (e.g. 100+ blocks with a 3s cap).
        double distBlocks = Math.sqrt(distSq);
        long scaledTimeout = Math.max(timeoutMs, Math.max(2_000L, (long) (distBlocks * 400.0)));

        long deadline = System.currentTimeMillis() + scaledTimeout;
        long phaseStartMs = System.currentTimeMillis();
        BlockPos lastBlockPos = bot.getBlockPos();
        int stuckTicks = 0;
        boolean scaffoldHold = false;
        int breakThroughCount = 0;
        int stuckRecoveryAttempts = 0;
        boolean microPathAttempted = navContext != null && navContext.startsWith("fortify-gate"); // skip micro-path for gates
        boolean allowBreakThrough = navContext == null || !navContext.startsWith("fortify-gate");
        boolean gateBreakEscalated = false; // track if we've escalated gate exit to allow break-through
        boolean gateContext = navContext != null && navContext.startsWith("fortify-gate:");
        boolean gateExitContext = navContext != null && navContext.startsWith("fortify-gate:exit");
        int gateSidestepNoProgressBursts = 0;
        int stuckThreshold = (navContext != null && navContext.startsWith("fortify-gate")) ? 1 : 5; // far faster gate carve decision
        try {
            while (System.currentTimeMillis() < deadline) {
                if (abortFortifyPhase(bot, "walkToTarget", phaseStartMs)) return;

                double currentDistSq = bot.squaredDistanceTo(targetVec);
                if (currentDistSq < 9.0) return; // close enough

                // Stuck detection: BlockPos-based — immune to floating-point oscillation
                // when the bot bounces off walls. If the bot's block position hasn't
                // changed in 10 ticks (~0.5s), it's stuck.
                BlockPos currentBlockPos = bot.getBlockPos();
                if (currentBlockPos.equals(lastBlockPos)) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                    lastBlockPos = currentBlockPos;
                }
                if (stuckTicks > stuckThreshold) {
                    stuckRecoveryAttempts++;
                    // Escalate gate-exit to allow break-through after repeated failures
                    // rather than circling indefinitely via arc recovery
                    if (gateExitContext && !gateBreakEscalated && stuckRecoveryAttempts >= 6) {
                        allowBreakThrough = true;
                        gateBreakEscalated = true;
                        LOGGER.info("[FortifyNav] Gate-exit escalation: enabling break-through after {} stuck cycles", stuckRecoveryAttempts);
                    }
                    BotActions.jump(bot);
                    sleepQuiet(100);
                    boolean recovered = false;
                    boolean pathTried = false;
                    boolean breakTried = false;
                    boolean sidestepTried = false;
                    boolean arcTried = false;
                    boolean breakSuppressed = false;
                    boolean sidestepSuppressed = false;
                    boolean arcSuppressed = false;
                    String recoveryResult = "no-progress";
                    ServerWorld recoveryWorld = (ServerWorld) bot.getEntityWorld();
                    BlockPos recoveryStart = bot.getBlockPos();
                    int recoveryTerrainY = VillageFortificationLayoutService.terrainY(recoveryWorld, recoveryStart.getX(), recoveryStart.getZ());
                    int recoveryDepth = Math.max(0, recoveryTerrainY - recoveryStart.getY());
                    boolean recoveryTrapLike = isTrapLikeCell(recoveryWorld, recoveryStart);
                    boolean entombmentMode = entombmentHelper.shouldPreferEntombmentEscape(recoveryWorld, recoveryStart, navContext);
                    boolean repeatedSurfaceEscapePocket = entombmentHelper.shouldSkipRepeatedSurfaceEscape(recoveryStart, recoveryTerrainY);
                    boolean noProgressPocketBurst = entombmentHelper.hasFortifyPocketNoProgressBurst(recoveryWorld, recoveryStart, navContext, 1);
                    boolean towerStepupLoopMode = navContext != null
                            && (navContext.startsWith("fortify-tower:approach")
                            || navContext.startsWith("fortify-tower:local-step-replan"))
                            && recoveryTrapLike
                            && recoveryDepth >= 2
                            && (repeatedSurfaceEscapePocket || noProgressPocketBurst);
                    boolean edgeStepupLoopMode = navContext != null
                            && (navContext.startsWith("fortify-edge:approach")
                            || navContext.startsWith("fortify-edge:approach-close")
                            || navContext.startsWith("fortify-edge:approach-retry"))
                            && recoveryTrapLike
                            && recoveryDepth >= 1
                            && (repeatedSurfaceEscapePocket || noProgressPocketBurst);
                    boolean carvePreferredMode = entombmentMode || towerStepupLoopMode || edgeStepupLoopMode;
                    String recoveryModeLabel = entombmentMode
                            ? "entombment_escape"
                            : (towerStepupLoopMode ? "tower_pocket_escape"
                            : (edgeStepupLoopMode ? "edge_pocket_escape" : "normal"));
                    FortifyNavRuntimeScope recoveryScope = activeFortifyNavScope;
                    if (towerStepupLoopMode) {
                        LOGGER.info("[FortifyTower] walk-recovery stepup-loop-detected ctx={} pos={} target={} depth={} trapLike={} surfaceY={}",
                                navContext, recoveryStart.toShortString(), target.toShortString(),
                                recoveryDepth, recoveryTrapLike, recoveryTerrainY);
                    }
                    if (edgeStepupLoopMode) {
                        LOGGER.info("[FortifyEdge] walk-recovery stepup-loop-detected ctx={} pos={} target={} depth={} trapLike={} surfaceY={} repeatedSurfaceEscape={} noProgressBurst={}",
                                navContext, recoveryStart.toShortString(), target.toShortString(),
                                recoveryDepth, recoveryTrapLike, recoveryTerrainY,
                                repeatedSurfaceEscapePocket, noProgressPocketBurst);
                    }
                    LOGGER.info("[FortifyNav] stuck-recovery-start ctx={} pos={} target={} scopePresent={} scopeCtx={} trapLike={} belowSurfaceDepth={} recoveryMode={} suppress[path={},break={},sidestep={},arc={}]",
                            navContext != null ? navContext : "none",
                            recoveryStart.toShortString(),
                            target.toShortString(),
                            recoveryScope != null,
                            recoveryScope != null ? recoveryScope.context : "none",
                            recoveryTrapLike,
                            recoveryDepth,
                            recoveryModeLabel,
                            carvePreferredMode,
                            false,
                            carvePreferredMode,
                            carvePreferredMode);

                    if (!recovered && isFortifyPrecipiceDefenseContext(navContext)
                            && tryPatchFortifyFootingNearWorksite(bot, recoveryWorld, target, navContext, "stuck-recovery")) {
                        recovered = true;
                        recoveryResult = "bridge-footing";
                        stuckTicks = 0;
                        lastBlockPos = bot.getBlockPos();
                        entombmentHelper.noteEntombmentRecoverySuccess(recoveryWorld, recoveryStart, bot.getBlockPos(), navContext);
                        continue;
                    }

                    // Try a short-range path plan before destructive recovery.
                    if (!microPathAttempted && !carvePreferredMode) {
                        Optional<MovementService.MovementPlan> microPlan = MovementService.planLootApproach(
                                bot, target, MovementService.MovementOptions.skillLoot());
                        microPathAttempted = true;
                        if (microPlan.isPresent()) {
                            pathTried = true;
                            MovementService.MovementResult microResult = MovementService.withoutDoorEscape(() ->
                                    MovementService.withoutObstructionMining(
                                            () -> MovementService.execute(source, bot, microPlan.get(), false, true, true, false)));
                            double postPathDistSq = bot.squaredDistanceTo(targetVec);
                            if (microResult.success() &&
                                    (postPathDistSq < currentDistSq - 4.0D || !bot.getBlockPos().equals(lastBlockPos))) {
                                recovered = true;
                                recoveryResult = "micro-path";
                                stuckTicks = 0;
                                lastBlockPos = bot.getBlockPos();
                                entombmentHelper.noteEntombmentRecoverySuccess(recoveryWorld, recoveryStart, bot.getBlockPos(), navContext);
                                continue;
                            }
                        }
                    }

                    // Try breaking through obstacle (up to MAX_BREAK_THROUGHS_PER_WALK times)
                    if (allowBreakThrough && breakThroughCount < MAX_BREAK_THROUGHS_PER_WALK) {
                        breakTried = true;
                        ServerWorld w = recoveryWorld;
                        if (tryBreakThroughObstacle(bot, w, target, navContext)) {
                            breakThroughCount++;
                            stuckTicks = 0;
                            lastBlockPos = bot.getBlockPos();
                            recovered = true;
                            recoveryResult = "carved";
                            entombmentHelper.noteEntombmentRecoverySuccess(recoveryWorld, recoveryStart, bot.getBlockPos(), navContext);
                            continue;
                        }
                        if (carvePreferredMode) {
                            entombmentHelper.noteEntombmentBreakFailure(recoveryWorld, recoveryStart, navContext);
                        }
                        if (!recovered && activeFortifyNavScope != null
                                && activeFortifyNavScope.navMode == FortifyNavMode.CARVE_CORRIDOR
                                && isCarveEligibleForBreakAttempt(activeFortifyNavScope, w, bot.getBlockPos(), target)
                                && activeFortifyNavScope.carveSession != null
                                && activeFortifyNavScope.carveSession.canMineMore()) {
                            if (tryBreakThroughObstacle(bot, w, target, navContext + ":carve")) {
                                breakThroughCount++;
                                stuckTicks = 0;
                                lastBlockPos = bot.getBlockPos();
                                recovered = true;
                                recoveryResult = "carved";
                                entombmentHelper.noteEntombmentRecoverySuccess(recoveryWorld, recoveryStart, bot.getBlockPos(), navContext);
                                continue;
                            }
                            if (carvePreferredMode) {
                                entombmentHelper.noteEntombmentBreakFailure(recoveryWorld, recoveryStart, navContext + ":carve");
                            }
                        }
                        // Count failed attempts too — prevents endless "no viable candidates"
                        // cycling when the bot is stuck far from any wall
                        breakThroughCount++;
                    } else if (!allowBreakThrough) {
                        breakSuppressed = true;
                    }
                    if (!recovered && !carvePreferredMode && !(gateContext && gateSidestepNoProgressBursts >= FORTIFY_GATE_EXIT_SIDESTEP_NO_PROGRESS_LIMIT)) {
                        // Lateral sidestep: move perpendicular to target direction
                        sidestepTried = true;
                        double toTargetX = targetVec.x - bot.getX();
                        double toTargetZ = targetVec.z - bot.getZ();
                        double len = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
                        if (len > 0.01) {
                            double perpX = -toTargetZ / len;
                            double perpZ = toTargetX / len;
                            // Alternate sides each stuck episode
                            if (stuckRecoveryAttempts % 2 == 1) { perpX = -perpX; perpZ = -perpZ; }
                            BlockPos sideStep = bot.getBlockPos().add(
                                    (int) Math.round(perpX * 4), 0, (int) Math.round(perpZ * 4));
                            BlockPos before = bot.getBlockPos();
                            LOGGER.debug("[FortifyNav] lateral sidestep to {}", sideStep.toShortString());
                            walkTowardBlock(bot, sideStep, 1_500L);
                            if (!before.equals(bot.getBlockPos())) {
                                if (gateContext) {
                                    double sidestepGain = Math.sqrt(Math.max(0.0D, currentDistSq))
                                            - Math.sqrt(Math.max(0.0D, bot.squaredDistanceTo(targetVec)));
                                    if (sidestepGain < 0.75D) {
                                        gateSidestepNoProgressBursts++;
                                        recoveryResult = "sidestep-no-progress";
                                    } else {
                                        gateSidestepNoProgressBursts = 0;
                                        recovered = true;
                                        recoveryResult = "sidestep";
                                    }
                                } else {
                                    recovered = true;
                                    recoveryResult = "sidestep";
                                }
                            }
                        }
                    } else if (!recovered) {
                        sidestepSuppressed = true;
                    }
                    if (!recovered && !carvePreferredMode) {
                        ServerWorld w = recoveryWorld;
                        arcTried = true;
                        recovered = tryBacktrackArcWalkRecovery(bot, w, target, stuckRecoveryAttempts);
                        if (recovered) {
                            if (gateContext) {
                                gateSidestepNoProgressBursts = 0;
                            }
                            recoveryResult = "arc";
                        }
                    } else if (!recovered) {
                        arcSuppressed = true;
                    }
                    if (!recovered && carvePreferredMode && recoveryDepth > 0 && recoveryTrapLike) {
                        recoveryResult = "surface-escape-failed";
                    }
                    if (navContext != null && navContext.startsWith("fortify-tower")) {
                        LOGGER.info("[FortifyTower] walk-recovery ctx={} target={} attempt={} pathTried={} breakTried={} sidestepTried={} arcTried={} moved={}",
                                navContext, target.toShortString(), stuckRecoveryAttempts,
                                pathTried, breakTried, sidestepTried, arcTried, recovered);
                    }
                    LOGGER.info("[FortifyNav] stuck-recovery-end ctx={} pos={} target={} scopePresent={} scopeCtx={} trapLike={} belowSurfaceDepth={} recoveryMode={} tried[path={},break={},sidestep={},arc={}] suppressed[break={},sidestep={},arc={}] result={} moved={}",
                            navContext != null ? navContext : "none",
                            recoveryStart.toShortString(),
                            target.toShortString(),
                            activeFortifyNavScope != null,
                            activeFortifyNavScope != null ? activeFortifyNavScope.context : "none",
                            recoveryTrapLike,
                            recoveryDepth,
                            recoveryModeLabel,
                            pathTried, breakTried, sidestepTried, arcTried,
                            breakSuppressed, sidestepSuppressed, arcSuppressed,
                            recoveryResult,
                            recovered);
                    if (recovered) {
                        if (gateContext && !Objects.equals(recoveryResult, "sidestep")) {
                            gateSidestepNoProgressBursts = 0;
                        }
                        stuckTicks = 0;
                        lastBlockPos = bot.getBlockPos();
                        entombmentHelper.noteEntombmentRecoverySuccess(recoveryWorld, recoveryStart, bot.getBlockPos(), navContext);
                        continue;
                    }
                    entombmentHelper.noteEntombmentNoProgressCycle(recoveryWorld, recoveryStart, navContext);
                    int maxRecoveryAttempts = (towerStepupLoopMode || edgeStepupLoopMode) ? 1 : 4;
                    if (stuckRecoveryAttempts < maxRecoveryAttempts) {
                        LOGGER.debug("[FortifyNav] local recovery attempt {} failed at {}, retrying",
                                stuckRecoveryAttempts, bot.getBlockPos().toShortString());
                        stuckTicks = 0;
                        lastBlockPos = bot.getBlockPos();
                        sleepQuiet(100);
                        continue;
                    }
                    LOGGER.debug("Walk to {} stuck after {} recovery attempts, giving up",
                            target.toShortString(), stuckRecoveryAttempts);
                    return;
                }

                ServerWorld world = (ServerWorld) bot.getEntityWorld();
                boolean onScaffold = isStandingOnScaffoldBlock(bot, world);
                if (onScaffold && !scaffoldHold) {
                    scaffoldHold = beginScaffoldEdgeHold(bot, world, target);
                }

                if (hasDangerousFortifyPrecipiceAhead(bot, world, target, navContext)) {
                    boolean patched = tryPatchFortifyFootingNearWorksite(bot, world, target, navContext, "precipice-defense");
                    if (!patched) {
                        LOGGER.info("[FortifyNav] precipice-defense hold ctx={} pos={} target={}",
                                navContext != null ? navContext : "none",
                                bot.getBlockPos().toShortString(),
                                target.toShortString());
                        BotActions.stop(bot);
                        stuckTicks = Math.max(stuckTicks, stuckThreshold + 1);
                        sleepQuiet(50);
                        continue;
                    }
                    sleepQuiet(50);
                    continue;
                }

                LookController.faceBlock(bot, target);
                BotActions.sprint(bot, !onScaffold); // sprint when navigating between sections
                double impulse = onScaffold ? 0.12D : 0.28D;
                BotActions.applyMovementInput(bot, targetVec, impulse);

                sleepQuiet(50);
            }
        } finally {
            endScaffoldEdgeHold(bot, scaffoldHold);
        }

        if (navContext != null && navContext.startsWith("fortify-tower")) {
            LOGGER.info("[FortifyTower] walk-timeout ctx={} target={} dist={}",
                    navContext, target.toShortString(),
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(bot.squaredDistanceTo(targetVec))));
        } else {
            LOGGER.debug("Walk to {} timed out at dist={}", target.toShortString(),
                    Math.sqrt(bot.squaredDistanceTo(targetVec)));
        }
    }

    private boolean moveToReachBlock(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) return false;
        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true, true, false);
        return result.success();
    }

    @Override
    public boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        // Use feet position to match BotActions.tryPlaceBlockAt() which gates on
        // bot.squaredDistanceTo() (feet-based). Using eye position here created a
        // mismatch where blocks passed this pre-check but failed actual placement
        // with "out-of-reach" — especially for blocks below the bot.
        double distSq = bot.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return distSq <= REACH_DISTANCE_SQ;
    }

    private boolean isWithinReachXZ(ServerPlayerEntity bot, BlockPos pos, double maxDist) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        return (dx * dx + dz * dz) <= maxDist * maxDist;
    }

    @Override
    public boolean hasLineOfSight(ServerWorld world, ServerPlayerEntity bot, Vec3d eye, BlockPos target) {
        Vec3d targetCenter = Vec3d.ofCenter(target);
        RaycastContext ctx = new RaycastContext(eye, targetCenter,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, bot);
        BlockHitResult hit = world.raycast(ctx);
        if (hit.getType() != HitResult.Type.BLOCK) return true;
        return hit.getBlockPos().equals(target);
    }

    private int countReachableWithLOS(ServerWorld world, ServerPlayerEntity bot,
                                       BlockPos standPos, List<ProceduralWallBlock> vertexBlocks) {
        Vec3d eye = Vec3d.ofCenter(standPos).add(0, 1.12, 0); // 0.5 + 1.12 = 1.62 eye height
        int count = 0;
        for (ProceduralWallBlock block : vertexBlocks) {
            if (!isActiveFortifyBlock(block)) continue;
            if (isPlannedBlockSatisfied(block, world.getBlockState(block.worldPos()))) continue;
            Vec3d blockCenter = Vec3d.ofCenter(block.worldPos());
            if (eye.squaredDistanceTo(blockCenter) > REACH_DISTANCE_SQ) continue;
            if (hasLineOfSight(world, bot, eye, block.worldPos())) count++;
        }
        return count;
    }

    // ── Helpers ─────────────────────────────────────────────────

    private boolean isActiveFortifyBlock(ProceduralWallBlock block) {
        return layoutHelper.isActiveFortifyBlock(block);
    }

    private boolean isPlannedBlockSatisfied(ProceduralWallBlock planned, BlockState current) {
        return layoutHelper.isPlannedBlockSatisfied(planned, current);
    }

    /** Compute per-edge planned block counts from a layout. */
    private Map<Integer, Integer> computeEdgePlannedCounts(FortificationLayout layout) {
        return layoutHelper.computeEdgePlannedCounts(layout);
    }

    /** Count how many planned blocks are satisfied by desired/fallback material state. */
    private int countPresentBlocks(ServerWorld world, List<ProceduralWallBlock> allBlocks) {
        return layoutHelper.countPresentBlocks(world, allBlocks);
    }

    private int countBuildingBlocks(ServerPlayerEntity bot) {
        int count = 0;
        Set<Item> buildItems = Set.of(
                Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE, Items.COBBLED_DEEPSLATE,
                Items.ANDESITE, Items.DIRT, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
                Items.JUNGLE_LOG, Items.CHISELED_STONE_BRICKS, Items.STONE_BRICK_SLAB,
                Items.STONE_BRICK_STAIRS, Items.OAK_PLANKS, Items.SPRUCE_PLANKS,
                Items.COBBLESTONE_SLAB, Items.STONE_SLAB
        );
        for (int i = 0; i < bot.getInventory().size(); i++) {
            var stack = bot.getInventory().getStack(i);
            if (buildItems.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private String getArgument(SkillContext context) {
        Object opts = context.parameters().get("options");
        if (opts instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object val : list) {
                if (val != null) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(val.toString());
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        Object argObj = context.parameters().get("arguments");
        if (argObj instanceof String s && !s.isEmpty()) return s;
        return null;
    }

    static String awaitMiningOutcome(CompletableFuture<String> future,
                                     java.util.function.BooleanSupplier abortCheck,
                                     long timeoutMs,
                                     long pollIntervalMs) {
        return FortifyExecutionPolicyUtil.awaitFutureResult(future, abortCheck, timeoutMs, pollIntervalMs);
    }

    @Override
    public boolean abortFortifyPhase(ServerPlayerEntity bot, String phase, long phaseStartMs) {
        if (!SkillManager.shouldAbortSkill(bot)) {
            return false;
        }
        BotActions.stop(bot);
        LOGGER.info("[FortifyAbort] phase={} elapsedMs={}", phase, (System.currentTimeMillis() - phaseStartMs));
        return true;
    }

    /** Show a transient overhead hologram for in-progress fortify status. */
    @Override
    public void showOverhead(ServerPlayerEntity bot, String text) {
        CompanionOverheadDialogueService.showOverheadLine(
                bot, text, 4_000, 48.0D, "fortify", null);
    }

    @Override
    public void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
