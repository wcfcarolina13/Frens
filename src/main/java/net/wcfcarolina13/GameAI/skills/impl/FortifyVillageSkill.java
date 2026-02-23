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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
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
public final class FortifyVillageSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-village");
    private static final double REACH_DISTANCE_SQ = 20.25D;
    private static final int BLOCK_PLACE_DELAY_MS = 50;
    // Temporary focus mode: disable moat/clearance work and build only fortification structures.
    private static final boolean ENABLE_MOAT_STAGE = false;
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

    // Building material fallback lists
    private static final List<Item> STONE_BRICK_FALLBACKS = List.of(
            Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE,
            Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.DIRT
    );
    private static final List<Item> OAK_LOG_FALLBACKS = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> CHISELED_FALLBACKS = List.of(
            Items.CHISELED_STONE_BRICKS, Items.STONE_BRICKS, Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> SLAB_FALLBACKS = List.of(
            Items.STONE_BRICK_SLAB, Items.COBBLESTONE_SLAB, Items.STONE_SLAB,
            Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> COBBLE_FALLBACKS = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DIRT
    );

    /** Positions that are part of the current fortification layout — never mine during navigation. */
    private Set<BlockPos> fortificationProtectedPositions = Set.of();

    private record SurfaceProfile(int referenceSurfaceY, Map<Long, Integer> plannedYByXZ) {}
    private record StartupRecoveryResult(boolean progressMade, int minedCount, boolean snapped, boolean failedNoSafeTile) {}
    private record MoatDigResult(int dugCount, boolean abortedNoSafeTile) {}

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

            // /bot fortify status <name>
            if (lower.startsWith("status ")) {
                String wallName = args.trim().substring(7).trim();
                return handleStatus(source, bot, world, server, wallName);
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
            for (ProceduralWallBlock block : layout.allBlocks()) {
                if (!isActiveFortifyBlock(block)) {
                    continue;
                }
                BlockState current = world.getBlockState(block.worldPos());
                if (current.isAir() || current.isReplaceable()) {
                    repairList.add(block);
                    repairByEdge.computeIfAbsent(block.edgeIndex(), k -> new ArrayList<>()).add(block);
                }
            }

            if (repairList.isEmpty()) {
                String msg = autoRepeat && passNumber > 1
                        ? "§a[Fortify] Wall '" + wallName + "' is intact after " + (passNumber - 1) + " passes! (" + grandTotalRepaired + " total blocks repaired)"
                        : "§a[Fortify] Wall '" + wallName + "' is intact! No repairs needed.";
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

            // Sort edges by proximity to bot (nearest first) to minimize travel
            BlockPos botPos = bot.getBlockPos();
            List<Map.Entry<Integer, List<ProceduralWallBlock>>> sortedEdges = new ArrayList<>(repairByEdge.entrySet());
            sortedEdges.sort(Comparator.comparingDouble(entry -> {
                int edgeIdx = entry.getKey();
                if (edgeIdx == -1) {
                    double avgX = 0, avgZ = 0;
                    for (ProceduralWallBlock b : entry.getValue()) {
                        avgX += b.worldPos().getX();
                        avgZ += b.worldPos().getZ();
                    }
                    avgX /= entry.getValue().size();
                    avgZ /= entry.getValue().size();
                    return Math.pow(botPos.getX() - avgX, 2) + Math.pow(botPos.getZ() - avgZ, 2);
                }
                if (edgeIdx >= 0 && edgeIdx < layout.edges().size()) {
                    WallEdge e = layout.edges().get(edgeIdx);
                    double midX = (e.start().x() + e.end().x()) / 2.0;
                    double midZ = (e.start().z() + e.end().z()) / 2.0;
                    return Math.pow(botPos.getX() - midX, 2) + Math.pow(botPos.getZ() - midZ, 2);
                }
                return Double.MAX_VALUE;
            }));

            int passRepaired = 0;
            int edgesPatched = 0;

            for (Map.Entry<Integer, List<ProceduralWallBlock>> entry : sortedEdges) {
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

        // Final scaffold cleanup
        ScaffoldService.teardownTrackedScaffolds(bot);

        ChatUtils.sendChatMessages(source, "§a[Fortify] Auto-patch complete: " + grandTotalRepaired + " total blocks repaired across " + passNumber + " passes.");
        return SkillExecutionResult.success("Auto-patched " + grandTotalRepaired + " blocks across " + passNumber + " passes.");
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

            BlockState current = world.getBlockState(block.worldPos());
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
        int totalPlaced = priorBlocksPlaced;
        int edgesCompleted = completedEdges.size();
        int totalEdges = layout.edges().size();

        // Compute reference surface Y from layout FOUNDATION blocks (unaffected by moat digging).
        // terrainY() uses heightmap which changes after digging, so we need the original surface level.
        int referenceSurfaceY = computeReferenceSurfaceY(bot, layout, world);
        SurfaceProfile surfaceProfile = createSurfaceProfile(layout, referenceSurfaceY);
        boolean resumedRun = startEdgeIndex != 0 || priorBlocksPlaced > 0 || !completedEdges.isEmpty();
        int edgeStartIndex = chooseEdgeStartIndex(bot, layout, completedEdges, startEdgeIndex);
        List<Integer> edgeBuildOrder = orderedRemainingEdges(layout, completedEdges, edgeStartIndex);

        // Populate layout positions so break-through navigation knows which blocks to protect
        Set<BlockPos> layoutPositions = new HashSet<>();
        for (ProceduralWallBlock b : layout.allBlocks()) {
            layoutPositions.add(b.worldPos());
        }
        this.fortificationProtectedPositions = Collections.unmodifiableSet(layoutPositions);

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
        // PHASE A: Dig ALL moats across all remaining edges in one sweep
        // ══════════════════════════════════════════════════════════════
        if (ENABLE_MOAT_STAGE) {
            // Collect every MOAT_DIG + EXTERIOR_CLEAR block from remaining edges
            List<ProceduralWallBlock> allDigBlocks = new ArrayList<>();
            Set<BlockPos> protectedPositions = new HashSet<>();
            for (int ei : edgeBuildOrder) {
                for (ProceduralWallBlock b : layout.blocksForEdge(ei)) {
                    if (b.type() == WallBlockType.MOAT_DIG || b.type() == WallBlockType.EXTERIOR_CLEAR) {
                        allDigBlocks.add(b);
                    } else {
                        protectedPositions.add(b.worldPos());
                    }
                }
            }

            // Filter out already-air blocks before sorting
            allDigBlocks.removeIf(b -> world.getBlockState(b.worldPos()).isAir());
            allDigBlocks = densifyMoatDigTargets(allDigBlocks, protectedPositions);
            allDigBlocks.removeIf(b -> world.getBlockState(b.worldPos()).isAir());

            if (!allDigBlocks.isEmpty()) {
                showOverhead(bot, "Digging moat (" + allDigBlocks.size() + " blocks)...");

                List<BlockPos> perimeterPath = buildPerimeterPath(layout, world, surfaceProfile);
                Set<BlockPos> startupTargets = collectExistingDigTargets(world, allDigBlocks);
                if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
                    StartupRecoveryResult startupRecovery = runStartupRecovery(
                            source,
                            bot,
                            world,
                            referenceSurfaceY,
                            surfaceProfile,
                            perimeterPath,
                            startupTargets,
                            "startup"
                    );
                    if (startupRecovery.failedNoSafeTile()) {
                        saveAndReport(source, server, worldKey, wallName, startEdgeIndex, totalPlaced,
                                "Stuck at fortify start; no safe recovery position");
                        return SkillExecutionResult.success("Stuck at fortify start; no safe recovery position. Progress saved.");
                    }
                }

                MoatDigResult digResult = digAllMoatBlocks(
                        source,
                        bot,
                        world,
                        allDigBlocks,
                        referenceSurfaceY,
                        surfaceProfile,
                        perimeterPath
                );
                totalPlaced += digResult.dugCount();

                LOGGER.info("Moat dig phase complete: {} blocks cleared", digResult.dugCount());
                if (digResult.abortedNoSafeTile()) {
                    saveAndReport(source, server, worldKey, wallName, startEdgeIndex, totalPlaced,
                            "Stuck during moat phase; no safe recovery position");
                    return SkillExecutionResult.success("Stuck during moat phase; no safe recovery position. Progress saved.");
                }

                if (SkillManager.shouldAbortSkill(bot)) {
                    saveAndReport(source, server, worldKey, wallName, startEdgeIndex, totalPlaced, "Aborted");
                    return SkillExecutionResult.success("Aborted. Progress saved as '" + wallName + "'.");
                }
            }
        } else {
            LOGGER.info("[Fortify] Moat stage disabled by configuration; skipping moat and clearance work.");
            showOverhead(bot, "Building walls and towers...");
        }

        // ══════════════════════════════════════════════════════════════
        // PHASE B: Place ALL wall blocks, edge by edge
        // ══════════════════════════════════════════════════════════════
        if (shouldTriggerDepthRecovery(bot.getBlockPos().getY(), referenceSurfaceY)) {
            escapeIfInHole(bot, world, referenceSurfaceY);
        }
        long phaseBStartMs = System.currentTimeMillis();

        for (int ei : edgeBuildOrder) {

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

            if (countBuildingBlocks(bot) == 0 && ei < totalEdges - 1) {
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
    private boolean isWithinMiningReach(ServerPlayerEntity bot, BlockPos pos) {
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
                    walkToTarget(source, bot, approachPos, 5_000L);
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
                                walkToTarget(source, bot, safe, 1_200L);
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

            // ── Scaffold escalation: if NO_LOS dominates remaining failures, pillar up and retry ──
            int noLosCount = report.remainingByReason().getOrDefault(FailureReason.NO_LOS, 0);
            if (!remaining.isEmpty() && noLosCount > 0 && noLosCount >= remaining.size() / 2
                    && !SkillManager.shouldAbortSkill(bot) && countBuildingBlocks(bot) > 0) {
                LOGGER.info("[Fortify] Edge {} seg {}: NO_LOS={}/{} — scaffold escalation",
                        edge.index(), currentSegmentOrdinal, noLosCount, remaining.size());
                totalPlaced += attemptScaffoldEscalation(
                        source, bot, world, remaining, blockMap, surfaceProfile, edge, currentSegmentOrdinal);
            }

            if (report.placedCount() == 0 && report.remainingCount() > 0) {
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
     * Scaffold escalation for edge segments where NO_LOS dominates.
     * Pillar up near remaining blocks, place from elevation, then tear down.
     */
    private int attemptScaffoldEscalation(
            ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world,
            Set<BlockPos> remaining, Map<BlockPos, ProceduralWallBlock> blockMap,
            SurfaceProfile surfaceProfile, WallEdge edge, int segmentOrdinal) {

        if (remaining.isEmpty()) return 0;

        // Compute centroid of remaining blocks
        int sumX = 0, sumZ = 0, highestY = Integer.MIN_VALUE;
        for (BlockPos pos : remaining) {
            sumX += pos.getX();
            sumZ += pos.getZ();
            if (pos.getY() > highestY) highestY = pos.getY();
        }
        int cx = sumX / remaining.size();
        int cz = sumZ / remaining.size();
        int groundY = safeSurfaceY(surfaceProfile, world, cx, cz);

        // Find scaffold position: offset 2 blocks toward bot from centroid (inside the wall)
        BlockPos botPos = bot.getBlockPos();
        int dx = botPos.getX() - cx;
        int dz = botPos.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        int offX, offZ;
        if (len > 0.5) {
            offX = (int) Math.round(2.0 * dx / len);
            offZ = (int) Math.round(2.0 * dz / len);
        } else {
            // Bot is right at centroid — offset toward edge anchor direction
            offX = 0;
            offZ = 2;
        }

        BlockPos scaffoldBase = new BlockPos(cx + offX, groundY, cz + offZ);

        // Walk to scaffold base
        walkToTarget(source, bot, scaffoldBase, 3_000L);
        if (SkillManager.shouldAbortSkill(bot)) return 0;

        // Calculate how high to pillar: need eye level near the highest remaining block
        // Bot eye height is at Y + 1.62, reach is ~4.5 blocks
        int currentY = bot.getBlockPos().getY();
        int neededY = Math.max(currentY, highestY - 2); // eye at neededY+1.62, reach 4.5 down
        int pillarSteps = Math.max(2, Math.min(MAX_SCAFFOLD_HEIGHT, neededY - currentY));

        LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation at ({},{},{}) pillar={} highestTarget={}",
                edge.index(), segmentOrdinal, scaffoldBase.getX(), scaffoldBase.getY(), scaffoldBase.getZ(),
                pillarSteps, highestY);

        ScaffoldService.ScaffoldSession elevSession = ScaffoldService.beginSession(bot);
        boolean pillared = ScaffoldService.pillarToY(elevSession, currentY + pillarSteps);

        int placed = 0;
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
                    edge.index(), segmentOrdinal, placed, sortedRemaining.size());
        } else {
            LOGGER.info("[Fortify] Edge {} seg {}: scaffold escalation pillar failed (Y unchanged)",
                    edge.index(), segmentOrdinal);
        }

        // Tear down scaffold
        int tornDown = ScaffoldService.teardown(elevSession, Collections.emptySet());
        LOGGER.debug("[Fortify] Edge {} seg {}: scaffold teardown removed={}",
                edge.index(), segmentOrdinal, tornDown);

        return placed;
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
        List<Item> candidates = buildCandidateList(targetItem);

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

    private List<Item> buildCandidateList(Item primary) {
        if (primary == Items.STONE_BRICKS || primary == Items.STONE_BRICK_STAIRS) {
            return new ArrayList<>(STONE_BRICK_FALLBACKS);
        }
        if (primary == Items.STONE_BRICK_SLAB || primary == Items.COBBLESTONE_SLAB
                || primary == Items.STONE_SLAB) {
            return new ArrayList<>(SLAB_FALLBACKS);
        }
        if (primary == Items.CHISELED_STONE_BRICKS) {
            return new ArrayList<>(CHISELED_FALLBACKS);
        }
        if (primary == Items.OAK_LOG) {
            return new ArrayList<>(OAK_LOG_FALLBACKS);
        }
        if (primary == Items.COBBLESTONE || primary == Items.COBBLED_DEEPSLATE) {
            return new ArrayList<>(COBBLE_FALLBACKS);
        }
        List<Item> list = new ArrayList<>();
        list.add(primary);
        list.add(Items.COBBLESTONE);
        list.add(Items.DIRT);
        return list;
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
    private boolean digBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
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
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return false;
        if (DIG_BLACKLIST.contains(state.getBlock())) return false;
        if (state.getBlock() instanceof net.minecraft.block.DoorBlock) return false;
        if (state.getBlock() instanceof net.minecraft.block.BedBlock) return false;
        if (state.getBlock() instanceof FenceBlock) return false;
        if (state.getBlock() instanceof FenceGateBlock) return false;
        if (state.getBlock() instanceof WallBlock) return false;
        if (state.getBlock() instanceof PaneBlock) return false;
        if (state.getBlock() instanceof TrapdoorBlock) return false;
        if (state.getHardness(world, pos) < 0) return false;        // unbreakable
        if (world.getBlockEntity(pos) != null) return false;         // chests, furnaces, etc.
        if (!state.getFluidState().isEmpty()) return false;          // lava/water
        if (isAdjacentToVillageStructure(world, pos, 2)) return false;
        if (state.getCollisionShape(world, pos).isEmpty()) return false; // must have collision to be blocking
        return true;
    }

    /**
     * Check whether a fortification layout block can be temporarily mined for navigation.
     * More permissive than {@link #isSafeToBreakForNavigation} — allows layout blocks
     * but still rejects unbreakable, fluid, and block-entity blocks.
     * Callers MUST replace these blocks after walking through.
     */
    private boolean isLayoutBlockBreakableForNavigation(ServerWorld world, BlockPos pos) {
        if (!fortificationProtectedPositions.contains(pos)) return false; // not a layout block
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return false;
        if (state.getHardness(world, pos) < 0) return false;
        if (world.getBlockEntity(pos) != null) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getCollisionShape(world, pos).isEmpty()) return false;
        return true;
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
        BlockPos botPos = bot.getBlockPos();

        // Determine direction toward target
        int dx = target.getX() - botPos.getX();
        int dz = target.getZ() - botPos.getZ();

        // Build candidate directions: primary diagonal, then primary cardinals, then secondary cardinals
        List<BlockPos> candidateOffsets = new ArrayList<>();
        if (dx != 0 && dz != 0) {
            candidateOffsets.add(new BlockPos(Integer.signum(dx), 0, Integer.signum(dz)));
        }
        if (dx != 0) {
            candidateOffsets.add(new BlockPos(Integer.signum(dx), 0, 0));
        }
        if (dz != 0) {
            candidateOffsets.add(new BlockPos(0, 0, Integer.signum(dz)));
        }
        if (dx != 0) {
            candidateOffsets.add(new BlockPos(Integer.signum(dx), 0, 1));
            candidateOffsets.add(new BlockPos(Integer.signum(dx), 0, -1));
        }
        if (dz != 0) {
            candidateOffsets.add(new BlockPos(1, 0, Integer.signum(dz)));
            candidateOffsets.add(new BlockPos(-1, 0, Integer.signum(dz)));
        }

        // Two passes: first try non-layout blocks, then allow layout blocks
        for (int pass = 0; pass < 2; pass++) {
            boolean allowLayout = (pass == 1);

            for (BlockPos offset : candidateOffsets) {
                BlockPos feetPos = botPos.add(offset);
                BlockPos headPos = feetPos.up();

                boolean feetBlocking = !world.getBlockState(feetPos).getCollisionShape(world, feetPos).isEmpty();
                boolean headBlocking = !world.getBlockState(headPos).getCollisionShape(world, headPos).isEmpty();
                if (!feetBlocking && !headBlocking) continue;

                // Check safety: tier 1 (non-layout) or tier 2 (layout with mandatory replace)
                if (feetBlocking && !canBreakForNavigation(world, feetPos, allowLayout)) continue;
                if (headBlocking && !canBreakForNavigation(world, headPos, allowLayout)) continue;

                if (feetBlocking && !isWithinMiningReach(bot, feetPos)) continue;
                if (headBlocking && !isWithinMiningReach(bot, headPos)) continue;

                // Must be able to stand on the block below
                BlockState belowState = world.getBlockState(feetPos.down());
                if (belowState.getCollisionShape(world, feetPos.down()).isEmpty()) continue;

                boolean isLayoutBreak = (feetBlocking && fortificationProtectedPositions.contains(feetPos))
                        || (headBlocking && fortificationProtectedPositions.contains(headPos));

                LOGGER.info("[FortifyNav] Breaking through {} at {} (head={})",
                        isLayoutBreak ? "WALL" : "obstruction",
                        feetPos.toShortString(), headBlocking ? headPos.toShortString() : "clear");

                BlockState feetOriginal = feetBlocking ? world.getBlockState(feetPos) : null;
                BlockState headOriginal = headBlocking ? world.getBlockState(headPos) : null;

                // Mine head first (if needed), then feet
                if (headBlocking) {
                    if (!digBlockForNavigation(bot, world, headPos)) continue;
                }
                if (feetBlocking) {
                    if (!digBlockForNavigation(bot, world, feetPos)) {
                        if (headOriginal != null) {
                            replaceMinedBlock(bot, world, headPos, headOriginal, isLayoutBreak);
                        }
                        continue;
                    }
                }

                // Walk through the gap
                BlockPos before = bot.getBlockPos();
                walkTowardBlock(bot, feetPos, 1_200L);
                sleepQuiet(100);

                boolean moved = !before.equals(bot.getBlockPos());

                // Replace mined blocks — mandatory for layout blocks, best-effort otherwise
                if (moved) {
                    if (feetOriginal != null) replaceMinedBlock(bot, world, feetPos, feetOriginal, isLayoutBreak);
                    if (headOriginal != null) replaceMinedBlock(bot, world, headPos, headOriginal, isLayoutBreak);
                } else {
                    // Didn't move — still replace immediately to restore wall integrity
                    if (feetOriginal != null) replaceMinedBlock(bot, world, feetPos, feetOriginal, isLayoutBreak);
                    if (headOriginal != null) replaceMinedBlock(bot, world, headPos, headOriginal, isLayoutBreak);
                }

                if (moved) {
                    LOGGER.info("[FortifyNav] Break-through success, moved from {} to {}",
                            before.toShortString(), bot.getBlockPos().toShortString());
                }
                return moved;
            }
        }

        return false;
    }

    /**
     * Unified check: can this block be mined for navigation?
     * When {@code allowLayout} is false, only non-layout blocks pass.
     * When {@code allowLayout} is true, layout blocks also pass (for wall traversal).
     */
    private boolean canBreakForNavigation(ServerWorld world, BlockPos pos, boolean allowLayout) {
        if (fortificationProtectedPositions.contains(pos)) {
            return allowLayout && isLayoutBlockBreakableForNavigation(world, pos);
        }
        return isSafeToBreakForNavigation(world, pos);
    }

    /** Mine a single block for navigation break-through. Thin wrapper around MiningTool. */
    private boolean digBlockForNavigation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
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

    /**
     * Replace a mined block. For layout blocks ({@code mandatory=true}), uses the
     * wall material fallback lists and logs a warning on failure so auto-patch can
     * repair it. For non-layout blocks, best-effort with common materials.
     */
    private void replaceMinedBlock(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                   BlockState originalState, boolean mandatory) {
        // Don't replace if something is already there
        if (!world.getBlockState(pos).isAir()) return;
        // Don't replace if the bot is occupying the position
        if (bot.getBlockPos().equals(pos) || bot.getBlockPos().up().equals(pos)) return;

        Item originalItem = originalState.getBlock().asItem();

        // For layout (wall) blocks, use the broad wall material fallback list
        List<Item> replacements;
        if (mandatory) {
            // Try original first, then all stone-brick fallbacks (the primary wall material)
            Set<Item> seen = new LinkedHashSet<>();
            if (originalItem != Items.AIR) seen.add(originalItem);
            seen.addAll(STONE_BRICK_FALLBACKS);
            seen.addAll(COBBLE_FALLBACKS);
            replacements = new ArrayList<>(seen);
        } else if (originalItem != Items.AIR) {
            replacements = List.of(originalItem, Items.COBBLESTONE, Items.STONE, Items.DIRT);
        } else {
            replacements = List.of(Items.COBBLESTONE, Items.STONE, Items.DIRT);
        }

        BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, replacements);
        if (result.success()) {
            LOGGER.info("[FortifyNav] Replaced mined block at {}", pos.toShortString());
        } else if (mandatory) {
            LOGGER.warn("[FortifyNav] FAILED to replace wall block at {} — auto-patch should repair",
                    pos.toShortString());
        } else {
            LOGGER.debug("[FortifyNav] Could not replace block at {} (no suitable material)", pos.toShortString());
        }
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
            walkToTarget(source, bot, new BlockPos(refX, bot.getBlockPos().getY(), refZ), 15_000L);
            return;
        }

        // Outward normal for CCW hull: (dz, -dx) normalized
        double nx = edgeDz / edgeLen;
        double nz = -edgeDx / edgeLen;

        int approachX = (int) Math.round(refX + nx * 3);
        int approachZ = (int) Math.round(refZ + nz * 3);
        int approachY = safeSurfaceY(surfaceProfile, world, approachX, approachZ);

        BlockPos approachPos = new BlockPos(approachX, approachY, approachZ);
        double distSq = bot.squaredDistanceTo(approachX + 0.5, bot.getY(), approachZ + 0.5);
        if (distSq > 400.0D) { // > 20 blocks away — use proper pathfinding
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, approachPos, MovementService.MovementOptions.skillLoot());
            if (plan.isPresent() && !SkillManager.shouldAbortSkill(bot)) {
                LOGGER.info("[FortifyEdge] long-range nav to edge approach={} dist={}",
                        approachPos.toShortString(), String.format("%.0f", Math.sqrt(distSq)));
                // Suppress obstruction mining and door traversal during fortify navigation
                MovementService.withoutDoorEscape(() ->
                        MovementService.withoutObstructionMining(
                                () -> MovementService.execute(source, bot, plan.get(), null)));
                // If still far from approach after pathfinding, try breaking through an obstacle
                double postNavDistSq = bot.squaredDistanceTo(approachX + 0.5, bot.getY(), approachZ + 0.5);
                if (postNavDistSq > 25.0D) { // > 5 blocks away still
                    tryBreakThroughObstacle(bot, world, approachPos);
                }
                return; // MovementService already navigated; skip the short-range walkToTarget
            }
        }
        walkToTarget(source, bot, approachPos, 8_000L);
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
        walkToTarget(source, bot, new BlockPos(targetX, targetY, targetZ), 5_000L);
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

        // Long-range navigation: if bot is far from this tower, use MovementService
        // to get to the general area before starting short-range local approach retries.
        double distToTowerSq = bot.squaredDistanceTo(vertex.x() + 0.5, bot.getY(), vertex.z() + 0.5);
        if (distToTowerSq > 400.0D) { // > 20 blocks away
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0);
            if (towerApproach != null) {
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
            }
        }

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

            forceExitTowerFootprint(source, bot, world, vertex, surfaceProfile, attempt - 1);
            BlockPos towerApproach = chooseTowerApproachPos(bot, world, vertex, surfaceProfile, attempt - 1);
            boolean approached = moveToTowerApproach(
                    source, bot, world, towerApproach, surfaceProfile,
                    taskPrefix + ":" + vertexOrdinal + ":approach-" + attempt);

            if (!approached) {
                boolean hardReset = tryTowerHardResetPosition(
                        source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal);
                if (hardReset) {
                    noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                    continue;
                }
                noProgressAttempts++;
                if (noProgressAttempts >= TOWER_LOCAL_NO_PROGRESS_LIMIT) {
                    break;
                }
                continue;
            }

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
                if (tryTowerHardResetPosition(source, bot, world, vertex, surfaceProfile, attempt + vertexOrdinal)) {
                    noProgressAttempts = Math.max(0, noProgressAttempts - 1);
                }
            }

            BlockPos attemptPos = bot.getBlockPos();
            if (lastAttemptPos != null && lastAttemptPos.equals(attemptPos) && gained == 0) {
                stagnantAttemptStreak++;
                if (stagnantAttemptStreak >= 2) {
                    if (tryTowerHardResetPosition(source, bot, world, vertex, surfaceProfile,
                            attempt + vertexOrdinal + stagnantAttemptStreak)) {
                        noProgressAttempts = Math.max(0, noProgressAttempts - 1);
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
        }

        // ── Scaffold phase: reach upper tower layers from elevated position ──
        presentCount = countPresentBlocks(world, vertexBlocks);
        if (!isTowerComplete(presentCount, plannedCount)
                && !SkillManager.shouldAbortSkill(bot)
                && countBuildingBlocks(bot) > 0) {
            int scaffoldGained = executeTowerScaffoldPhase(
                    source, bot, world, vertex, vertexBlocks, surfaceProfile,
                    vertexOrdinal, totalVertices, plannedCount, referenceSurfaceY);
            newlyPlaced += scaffoldGained;
        }

        return newlyPlaced;
    }

    // ── Tower scaffold phase ──────────────────────────────────────

    private static final long TOWER_SCAFFOLD_TIME_BUDGET_MS = 60_000L;
    private static final int TOWER_SCAFFOLD_MAX_SIDES = 6;

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
                                          int referenceSurfaceY) {
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

        // Optimal scaffold Y: puts bot eye level near the upper blocks
        int optimalY = maxTargetY - 2;
        int groundY = safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        if (optimalY <= groundY) {
            return 0; // no benefit from scaffolding
        }

        LOGGER.info("[FortifyTower] scaffold phase for tower {}/{} pos=({},{}) present={}/{} optimalY={}",
                vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z(),
                presentCount, plannedCount, optimalY);
        showOverhead(bot, "Scaffolding tower " + (vertexOrdinal + 1) + "/" + totalVertices);

        long phaseStart = System.currentTimeMillis();
        int totalGained = 0;
        Set<Integer> triedSides = new HashSet<>();

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
            walkToTarget(source, bot, scaffoldBase, 3_000L);
            double distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            if (distSq > 9.0) {
                walkTowardBlock(bot, scaffoldBase, 2_000L);
                distSq = bot.squaredDistanceTo(scaffoldBase.getX() + 0.5, scaffoldBase.getY(), scaffoldBase.getZ() + 0.5);
            }

            // If we're too far from the scaffold base, skip this side
            if (distSq > 16.0) {
                LOGGER.info("[FortifyTower] scaffold base unreachable side={} dist={} for tower ({},{})",
                        sideAttempt, String.format("%.1f", Math.sqrt(distSq)), vertex.x(), vertex.z());
                continue;
            }

            // Pillar up
            int stepsNeeded = Math.max(0, optimalY - bot.getBlockPos().getY());
            LOGGER.info("[FortifyTower] scaffold pillar attempt side={} botY={} optimalY={} steps={} for tower ({},{})",
                    sideAttempt, bot.getBlockPos().getY(), optimalY, stepsNeeded, vertex.x(), vertex.z());
            ScaffoldService.ScaffoldSession session = ScaffoldService.beginSession(bot);
            boolean pillared = ScaffoldService.pillarToY(session, optimalY);
            if (!pillared || session.trackedPositions().isEmpty()) {
                LOGGER.info("[FortifyTower] scaffold pillar failed side={} pillared={} tracked={} botY={} for tower ({},{})",
                        sideAttempt, pillared, session.trackedPositions().size(),
                        bot.getBlockPos().getY(), vertex.x(), vertex.z());
                teardownScaffoldSurvival(bot, world, session);
                continue;
            }

            // Sneak for safety on scaffold
            boolean scaffoldSneak = beginScaffoldEdgeHold(bot, world, bot.getBlockPos());

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

            endScaffoldEdgeHold(bot, scaffoldSneak);

            // Tear down scaffold blocks (top-down, survival mining)
            teardownScaffoldSurvival(bot, world, session);

            totalGained += sidePlaced;
            LOGGER.info("[FortifyTower] scaffold side={} placed={} for tower {}/{} ({},{})",
                    sideAttempt, sidePlaced, vertexOrdinal + 1, totalVertices, vertex.x(), vertex.z());

            // Progress guard: if a side placed nothing, stop trying more sides
            if (sidePlaced == 0) {
                break;
            }
        }

        return totalGained;
    }

    /**
     * Pick a scaffold position near a tower vertex. Tries cardinal directions at
     * distances 2, 3, 4 blocks out, then diagonals. Each "side index" corresponds to
     * a direction so we don't retry the same direction.
     */
    private BlockPos chooseTowerScaffoldPos(ServerWorld world, WallPoint vertex,
                                            SurfaceProfile surfaceProfile,
                                            Set<Integer> triedSides) {
        // 8 directions: 4 cardinal + 4 diagonal
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},  // cardinal (indices 0-3)
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}  // diagonal (indices 4-7)
        };
        // Try increasing distances: 2, 3, 4 blocks out
        for (int dist = 2; dist <= 4; dist++) {
            for (int i = 0; i < directions.length; i++) {
                // Encode side as (direction_index * 10 + dist) for uniqueness
                int sideKey = i * 10 + dist;
                if (triedSides.contains(sideKey)) continue;
                int x = vertex.x() + directions[i][0] * dist;
                int z = vertex.z() + directions[i][1] * dist;
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

    /**
     * Mine tracked scaffold blocks top-down. Each block mined drops the bot 1 block
     * (no fall damage from scaffold height). Uses digBlock() for survival-mode mining.
     */
    private void teardownScaffoldSurvival(ServerPlayerEntity bot, ServerWorld world,
                                          ScaffoldService.ScaffoldSession session) {
        if (session == null || session.trackedPositions().isEmpty()) return;

        // Sort top-down for safe descent
        List<BlockPos> toRemove = new ArrayList<>(session.trackedPositions());
        toRemove.sort(Comparator.comparingInt(BlockPos::getY).reversed());

        for (BlockPos pos : toRemove) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            LookController.faceBlock(bot, pos);
            sleepQuiet(50L);
            digBlock(bot, world, pos);
        }
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
        walkToTarget(source, bot, escape, 1_500L);
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
        BlockPos start = bot.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int ringStart = 3 + Math.floorMod(attemptOffset, 2);

        for (int r = ringStart; r <= 7; r++) {
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
                        if (!canStandAt(world, candidate)) {
                            continue;
                        }
                        if (isInsideTowerFootprint(candidate, vertex)) {
                            continue;
                        }
                        int exits = countOpenExits(world, candidate, null);
                        if (exits < 3) {
                            continue;
                        }
                        double distFromTowerSq = Math.pow(candidate.getX() - vertex.x(), 2) + Math.pow(candidate.getZ() - vertex.z(), 2);
                        if (distFromTowerSq < 9.0D) {
                            continue;
                        }
                        double score = exits * 140.0 + distFromTowerSq * 16.0 - start.getSquaredDistance(candidate) * 4.5;
                        if (Math.abs(dx) > 0 && Math.abs(dz) > 0) {
                            score += 20.0;
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
            best = chooseTowerEscapePos(bot, world, vertex, surfaceProfile, attemptOffset + 9);
        }
        if (best == null || best.equals(start)) {
            return false;
        }

        LOGGER.info("[FortifyTower] hard-reset tower=({}, {}) from={} to={}",
                vertex.x(), vertex.z(), start.toShortString(), best.toShortString());
        walkToTarget(source, bot, best, 2_300L);
        if (start.equals(bot.getBlockPos())) {
            walkTowardBlock(bot, best, 1_000L);
        }
        return !start.equals(bot.getBlockPos());
    }

    private boolean moveToTowerApproach(ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        ServerWorld world,
                                        BlockPos towerApproach,
                                        SurfaceProfile surfaceProfile,
                                        String context) {
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

            long walkBudgetMs = bot.squaredDistanceTo(approachVec) > 196.0D ? 2_200L : 1_200L;
            walkToTarget(source, bot, towerApproach, walkBudgetMs);
            if (bot.squaredDistanceTo(approachVec) <= 9.0D) {
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
                    walkToTarget(source, bot, safe, 900L);
                }
            }

            if (bot.squaredDistanceTo(approachVec) > 9.0D) {
                walkTowardBlock(bot, towerApproach, 900L + (attempt * 300L));
            }
            if (bot.squaredDistanceTo(approachVec) <= 16.0D) {
                return true;
            }
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
        return chooseTowerApproachPos(bot, world, vertex, surfaceProfile, 0);
    }

    private BlockPos chooseTowerApproachPos(ServerPlayerEntity bot, ServerWorld world,
                                            WallPoint vertex, SurfaceProfile surfaceProfile,
                                            int attemptOffset) {
        // Tower footprint is vertex ±1 (3×3). Min offset 3 = 1 block clearance from edge.
        int[][] candidates = {
                {3, 0}, {-3, 0}, {0, 3}, {0, -3},
                {3, 1}, {3, -1}, {-3, 1}, {-3, -1},
                {1, 3}, {-1, 3}, {1, -3}, {-1, -3},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}
        };

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int startIndex = Math.floorMod(attemptOffset, candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            int[] c = candidates[(startIndex + i) % candidates.length];
            int x = vertex.x() + c[0];
            int z = vertex.z() + c[1];
            int y = safeSurfaceY(surfaceProfile, world, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!canStandAt(world, pos)) {
                continue;
            }
            int exits = countOpenExits(world, pos, null);
            if (exits < MIN_APPROACH_OPEN_EXITS) {
                continue;
            }
            double distSq = bot.squaredDistanceTo(x + 0.5, y, z + 0.5);
            double score = exits * 120.0 - distSq;
            if (attemptOffset > 0) {
                score += i * 6.0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }

        if (best != null) {
            return best;
        }
        int y = safeSurfaceY(surfaceProfile, world, vertex.x(), vertex.z());
        return new BlockPos(vertex.x(), y, vertex.z());
    }

    private boolean canStandAt(ServerWorld world, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());
        boolean feetClear = feet.isAir() || feet.isReplaceable();
        boolean headClear = head.isAir() || head.isReplaceable();
        boolean hasSupport = !below.isAir() && !below.isReplaceable();
        return feetClear && headClear && hasSupport;
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
        walkToTarget(source, bot, waypoint, 1_400L);
        if (!before.equals(bot.getBlockPos())) {
            return true;
        }
        walkTowardBlock(bot, waypoint, 900L);
        return !before.equals(bot.getBlockPos());
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

    private boolean beginScaffoldEdgeHold(ServerPlayerEntity bot, ServerWorld world, BlockPos focusPos) {
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

    private void endScaffoldEdgeHold(ServerPlayerEntity bot, boolean held) {
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
        walkToTarget(source, bot, best, 2_500L);
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
            if (tryBreakThroughObstacle(bot, world, best)) {
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
        if (from == null || to == null) {
            return Direction.NORTH;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
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

    // ── Hole escape ──────────────────────────────────────────────

    /**
     * If the bot is stuck below terrain level (e.g. in a moat hole it just dug),
     * escape back to terrain level. Tries strategies in order:
     *   1. Jump (shallow holes with headroom)
     *   2. Mine block above head if blocked, then jump
     *   3. Walk horizontally toward open ground at terrain level
     */
    /**
     * Escape from a hole/moat using heightmap terrain Y.
     * Only works when the heightmap hasn't been altered by moat digging at this XZ.
     */
    private void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world) {
        int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        escapeIfInHole(bot, world, terrainY);
    }

    /**
     * Escape from a hole/moat below the given reference surface Y.
     * Uses referenceSurfaceY instead of terrainY() because the heightmap changes
     * after moat digging — the bot needs to know the ORIGINAL surface level.
     *
     * Strategy: builds a diagonal staircase ramp upward by placing blocks and
     * clearing headroom, one step at a time, until at or above referenceSurfaceY.
     */
    private void escapeIfInHole(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        long phaseStartMs = System.currentTimeMillis();
        if (abortFortifyPhase(bot, "escapeIfInHole:entry", phaseStartMs)) {
            return;
        }
        BlockPos botPos = bot.getBlockPos();
        int depth = referenceSurfaceY - botPos.getY();
        if (depth <= 0) return; // at or above surface

        LOGGER.info("Bot below surface by {} blocks at {} (surfaceY={}), building staircase ramp",
                depth, botPos.toShortString(), referenceSurfaceY);

        // Strategy 1: Shallow (1 block) — just jump
        if (depth == 1) {
            BlockPos headBlock = botPos.up(2);
            BlockState headState = world.getBlockState(headBlock);
            if (!headState.isAir() && headState.getHardness(world, headBlock) >= 0) {
                digBlock(bot, world, headBlock);
                sleepQuiet(100);
            }
            BotActions.jump(bot);
            sleepQuiet(400);
            if (bot.getBlockPos().getY() >= referenceSurfaceY) return;
        }

        // Strategy 2: Build a staircase ramp upward.
        // Pick the best direction: check 4 cardinal directions for open air above surface level.
        // Prefer directions where the terrain is higher (i.e. not another moat column).
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        int bestDirIdx = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int d = 0; d < dirs.length; d++) {
            if (abortFortifyPhase(bot, "escapeIfInHole:direction-scan", phaseStartMs)) {
                return;
            }
            int tx = botPos.getX() + dirs[d][0] * 2;
            int tz = botPos.getZ() + dirs[d][1] * 2;
            // Check what's at the reference surface level in this direction
            BlockPos surfCheck = new BlockPos(tx, referenceSurfaceY + 1, tz);
            int score = 0;
            if (world.getBlockState(surfCheck).isAir()) score += 5; // open air at surface = great
            if (world.getBlockState(surfCheck.up()).isAir()) score += 3;
            // Prefer directions where there's solid ground at surface level
            BlockPos groundCheck = new BlockPos(tx, referenceSurfaceY, tz);
            if (!world.getBlockState(groundCheck).isAir()) score += 2;
            if (score > bestScore) {
                bestScore = score;
                bestDirIdx = d;
            }
        }
        int stepDx = dirs[bestDirIdx][0];
        int stepDz = dirs[bestDirIdx][1];

        LOGGER.info("Escape direction: dx={}, dz={} (score={}), building {} steps up",
                stepDx, stepDz, bestScore, depth);

        List<Item> rampMats = List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
        int maxSteps = depth + 2;
        int currentX = botPos.getX();
        int currentY = botPos.getY();
        int currentZ = botPos.getZ();

        for (int step = 0; step < maxSteps; step++) {
            if (abortFortifyPhase(bot, "escapeIfInHole:stair-step", phaseStartMs)) {
                return;
            }
            if (currentY >= referenceSurfaceY) break;

            // Clear headroom: mine the 2 blocks above the current position
            BlockPos stepPos = new BlockPos(currentX, currentY, currentZ);
            for (int above = 1; above <= 2; above++) {
                if (abortFortifyPhase(bot, "escapeIfInHole:clear-headroom", phaseStartMs)) {
                    return;
                }
                BlockPos clearPos = stepPos.up(above);
                BlockState clearState = world.getBlockState(clearPos);
                if (!clearState.isAir() && clearState.getHardness(world, clearPos) >= 0) {
                    digBlock(bot, world, clearPos);
                }
            }

            // Place a block under our feet if standing on air
            BlockPos feetBlock = new BlockPos(currentX, currentY - 1, currentZ);
            BlockState feetState = world.getBlockState(feetBlock);
            if (feetState.isAir() || feetState.isReplaceable()) {
                BotActions.tryPlaceBlockAt(bot, feetBlock, Direction.UP, rampMats);
            }

            // Next step: one block forward + one block up
            int nextX = currentX + stepDx;
            int nextZ = currentZ + stepDz;
            int nextY = currentY + 1;

            // Place the next stair step block
            BlockPos nextStep = new BlockPos(nextX, nextY - 1, nextZ);
            if (world.getBlockState(nextStep).isAir() || world.getBlockState(nextStep).isReplaceable()) {
                BotActions.tryPlaceBlockAt(bot, nextStep, Direction.UP, rampMats);
            }

            // Clear headroom above the next step
            for (int above = 0; above <= 2; above++) {
                if (abortFortifyPhase(bot, "escapeIfInHole:clear-next-headroom", phaseStartMs)) {
                    return;
                }
                BlockPos clearPos = new BlockPos(nextX, nextY + above, nextZ);
                BlockState clearState = world.getBlockState(clearPos);
                if (!clearState.isAir() && clearState.getHardness(world, clearPos) >= 0) {
                    digBlock(bot, world, clearPos);
                }
            }

            // Walk onto the next step
            BotActions.jump(bot);
            sleepQuiet(200);
            walkTowardBlock(bot, new BlockPos(nextX, nextY, nextZ), 800L);

            currentX = nextX;
            currentY = nextY;
            currentZ = nextZ;
        }

        // Verify escape
        botPos = bot.getBlockPos();
        if (botPos.getY() >= referenceSurfaceY) {
            LOGGER.info("Escaped hole via staircase ramp to {}", botPos.toShortString());
            // Walk 3 more blocks in the escape direction to move AWAY from the moat edge
            // so the bot doesn't immediately fall back in
            BlockPos safePos = new BlockPos(
                    botPos.getX() + stepDx * 3,
                    referenceSurfaceY,
                    botPos.getZ() + stepDz * 3);
            if (abortFortifyPhase(bot, "escapeIfInHole:post-escape-walk", phaseStartMs)) {
                return;
            }
            walkTowardBlock(bot, safePos, 1500L);
        } else {
            LOGGER.warn("Staircase escape incomplete, bot at {} vs surfaceY={}", botPos.toShortString(), referenceSurfaceY);
            int remaining = referenceSurfaceY - botPos.getY();
            if (remaining > 0 && remaining <= MAX_SCAFFOLD_HEIGHT) {
                ScaffoldService.pillarUp(bot, remaining + 1, true);
            }
        }
    }

    /**
     * Ensure the bot is standing on solid ground at the reference surface level.
     * Called at the start of buildWall to handle resume from stuck positions (e.g. in moat).
     */
    private void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world, int referenceSurfaceY) {
        BlockPos botPos = bot.getBlockPos();
        if (botPos.getY() >= referenceSurfaceY) return;

        LOGGER.info("Bot is below surface at {}, Y={} vs surfaceY={}, building ramp to escape",
                botPos.toShortString(), botPos.getY(), referenceSurfaceY);

        escapeIfInHole(bot, world, referenceSurfaceY);
    }

    /**
     * Ensure the bot is standing on solid ground (heightmap-based, for non-moat contexts).
     */
    private void ensureOnSurface(ServerPlayerEntity bot, ServerWorld world) {
        int terrainY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        ensureOnSurface(bot, world, terrainY);
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
    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber) {
        int fallbackSurfaceY = VillageFortificationLayoutService.terrainY(world, bot.getBlockPos().getX(), bot.getBlockPos().getZ());
        return ensureCanReachBlockWithEffort(source, bot, world, target, heightAboveGround, passNumber, fallbackSurfaceY, null);
    }

    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber,
                                                   int referenceSurfaceY) {
        return ensureCanReachBlockWithEffort(source, bot, world, target, heightAboveGround, passNumber, referenceSurfaceY, null);
    }

    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
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

        // Strategy 4: Scaffolding as LAST RESORT for elevated blocks still out of reach.
        // Never scaffold on the first pass; let movement/jump/arc retries run first.
        // Threshold lowered to verticalDiff >= 2: blocks 2+ above bot need scaffold to
        // get line-of-sight for placement (lower wall layers block the view from ground).
        verticalDiff = target.getY() - bot.getBlockPos().getY(); // refresh after walking
        if (passNumber >= 2 && verticalDiff >= 2 && heightAboveGround > 0 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT) {
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
                int optimalY = target.getY() - 2;
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
    private void walkTowardBlock(ServerPlayerEntity bot, BlockPos target, long timeoutMs) {
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
     * Walk toward a target position using tick-based impulse movement.
     * Pure tick-based — does NOT fall back to A* pathfinding, which can hang
     * in door-escape loops near village structures. Individual block placement
     * uses ensureCanReachBlockWithEffort for fine-grained precision.
     */
    private void walkToTarget(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target, long timeoutMs) {
        Vec3d targetVec = Vec3d.ofCenter(target);
        double distSq = bot.squaredDistanceTo(targetVec);
        if (distSq < 9.0) return; // already within 3 blocks

        long deadline = System.currentTimeMillis() + timeoutMs;
        long phaseStartMs = System.currentTimeMillis();
        double lastDistSq = distSq;
        int stuckTicks = 0;
        boolean scaffoldHold = false;
        boolean breakThroughAttempted = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (abortFortifyPhase(bot, "walkToTarget", phaseStartMs)) return;

                double currentDistSq = bot.squaredDistanceTo(targetVec);
                if (currentDistSq < 9.0) return; // close enough

                // Stuck detection: if we haven't moved significantly in 15 ticks (~0.75s), bail
                if (Math.abs(currentDistSq - lastDistSq) < 0.5) {
                    stuckTicks++;
                    if (stuckTicks > 15) {
                        // Try a brief jump to unstick
                        BotActions.jump(bot);
                        sleepQuiet(100);
                        // Last resort: try breaking through one blocking block (max once per walk)
                        if (!breakThroughAttempted) {
                            breakThroughAttempted = true;
                            ServerWorld w = (ServerWorld) bot.getEntityWorld();
                            if (tryBreakThroughObstacle(bot, w, target)) {
                                stuckTicks = 0;
                                lastDistSq = bot.squaredDistanceTo(targetVec);
                                continue; // reset and keep walking
                            }
                        }
                        LOGGER.debug("Walk to {} stuck after {} ticks, giving up", target.toShortString(), stuckTicks);
                        return;
                    }
                } else {
                    stuckTicks = 0;
                    lastDistSq = currentDistSq;
                }

                ServerWorld world = (ServerWorld) bot.getEntityWorld();
                boolean onScaffold = isStandingOnScaffoldBlock(bot, world);
                if (onScaffold && !scaffoldHold) {
                    scaffoldHold = beginScaffoldEdgeHold(bot, world, target);
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

        LOGGER.debug("Walk to {} timed out at dist={}", target.toShortString(),
                Math.sqrt(bot.squaredDistanceTo(targetVec)));
    }

    private boolean moveToReachBlock(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) return false;
        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true, true, false);
        return result.success();
    }

    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d eye = bot.getEyePos();
        double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
        return distSq <= REACH_DISTANCE_SQ;
    }

    private boolean isWithinReachXZ(ServerPlayerEntity bot, BlockPos pos, double maxDist) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        return (dx * dx + dz * dz) <= maxDist * maxDist;
    }

    // ── Helpers ─────────────────────────────────────────────────

    private int chooseEdgeStartIndex(ServerPlayerEntity bot, FortificationLayout layout,
                                     Set<Integer> completedEdges, int savedEdgeIndex) {
        int totalEdges = layout.edges().size();
        if (totalEdges <= 0) {
            return 0;
        }

        if (savedEdgeIndex >= 0 && savedEdgeIndex < totalEdges && !completedEdges.contains(savedEdgeIndex)) {
            return savedEdgeIndex;
        }

        if (savedEdgeIndex >= 0 && savedEdgeIndex < totalEdges) {
            for (int i = 1; i <= totalEdges; i++) {
                int idx = (savedEdgeIndex + i) % totalEdges;
                if (!completedEdges.contains(idx)) {
                    return idx;
                }
            }
        }

        BlockPos botPos = bot.getBlockPos();
        int nearest = -1;
        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < totalEdges; i++) {
            if (completedEdges.contains(i)) {
                continue;
            }
            WallEdge edge = layout.edges().get(i);
            double midX = (edge.start().x() + edge.end().x()) / 2.0;
            double midZ = (edge.start().z() + edge.end().z()) / 2.0;
            double dx = botPos.getX() - midX;
            double dz = botPos.getZ() - midZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = i;
            }
        }
        return nearest >= 0 ? nearest : 0;
    }

    private List<Integer> orderedRemainingEdges(FortificationLayout layout,
                                                Set<Integer> completedEdges,
                                                int startEdgeIndex) {
        int totalEdges = layout.edges().size();
        if (totalEdges <= 0) {
            return List.of();
        }
        int start = Math.floorMod(startEdgeIndex, totalEdges);
        List<Integer> order = new ArrayList<>(totalEdges);
        for (int i = 0; i < totalEdges; i++) {
            int idx = (start + i) % totalEdges;
            if (!completedEdges.contains(idx)) {
                order.add(idx);
            }
        }
        return order;
    }

    private static boolean isMoatRelatedType(WallBlockType type) {
        return type == WallBlockType.MOAT_DIG
                || type == WallBlockType.MOAT_FLOOR
                || type == WallBlockType.MOAT_INNER_FACE
                || type == WallBlockType.MOAT_OVERHANG
                || type == WallBlockType.EXTERIOR_CLEAR;
    }

    private boolean isActiveFortifyBlock(ProceduralWallBlock block) {
        if (block == null) {
            return false;
        }
        if (!ENABLE_MOAT_STAGE && isMoatRelatedType(block.type())) {
            return false;
        }
        return true;
    }

    private boolean isPlannedBlockSatisfied(ProceduralWallBlock planned, BlockState current) {
        if (planned == null || current == null) {
            return false;
        }
        BlockState desired = planned.state();
        if (current.equals(desired)) {
            return true;
        }
        if (current.isAir() || current.isReplaceable()) {
            return false;
        }
        if (desired.isAir()) {
            return current.isAir();
        }

        // Foundation/tower-base blocks: any solid non-air block satisfies the requirement.
        // The terrain (grass, dirt, stone) already provides the structural base the wall needs.
        if (planned.type() == WallBlockType.FOUNDATION || planned.type() == WallBlockType.TOWER_BASE) {
            return true; // existing solid block serves as foundation
        }

        Item desiredItem = desired.getBlock().asItem();
        Item currentItem = current.getBlock().asItem();
        if (currentItem == Items.AIR) {
            return false;
        }
        List<Item> candidates = buildCandidateList(desiredItem);
        return candidates.contains(currentItem);
    }

    /** Compute per-edge planned block counts from a layout. */
    private Map<Integer, Integer> computeEdgePlannedCounts(FortificationLayout layout) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ProceduralWallBlock block : layout.allBlocks()) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            counts.merge(block.edgeIndex(), 1, Integer::sum);
        }
        return counts;
    }

    /** Count how many planned blocks are satisfied by desired/fallback material state. */
    private int countPresentBlocks(ServerWorld world, List<ProceduralWallBlock> allBlocks) {
        int count = 0;
        for (ProceduralWallBlock block : allBlocks) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            BlockState current = world.getBlockState(block.worldPos());
            if (isPlannedBlockSatisfied(block, current)) {
                count++;
            }
        }
        return count;
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

    private boolean abortFortifyPhase(ServerPlayerEntity bot, String phase, long phaseStartMs) {
        if (!SkillManager.shouldAbortSkill(bot)) {
            return false;
        }
        BotActions.stop(bot);
        LOGGER.info("[FortifyAbort] phase={} elapsedMs={}", phase, (System.currentTimeMillis() - phaseStartMs));
        return true;
    }

    /** Show a transient overhead hologram for in-progress fortify status. */
    private void showOverhead(ServerPlayerEntity bot, String text) {
        CompanionOverheadDialogueService.showOverheadLine(
                bot, text, 4_000, 48.0D, "fortify", null);
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
