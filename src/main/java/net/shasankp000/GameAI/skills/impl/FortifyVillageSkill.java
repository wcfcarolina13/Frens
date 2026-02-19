package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.construction.FortificationPersistenceService;
import net.shasankp000.GameAI.services.construction.FortificationPersistenceService.SavedFortification;
import net.shasankp000.GameAI.services.construction.ScaffoldService;
import net.shasankp000.GameAI.services.construction.VillageFortificationLayoutService;
import net.shasankp000.GameAI.services.construction.VillageFortificationLayoutService.*;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Skill for autonomously building a defensive wall perimeter around a village.
 * Uses a convex hull of village structures for natural wall placement.
 *
 * Usage:
 *   /bot fortify                     — detect village, build new wall
 *   /bot fortify dry_run             — preview hull layout
 *   /bot fortify resume <name>       — continue saved wall
 *   /bot fortify patch <name>        — scan & repair existing wall
 *   /bot fortify list                — list saved walls for this world
 *   /bot fortify name <old> <new>    — rename a saved wall
 */
public final class FortifyVillageSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-village");
    private static final double REACH_DISTANCE_SQ = 20.25D;
    private static final int BLOCK_PLACE_DELAY_MS = 50;
    private static final int MAX_SCAFFOLD_HEIGHT = 8;
    private static final long MAX_BUILD_TIME_MS = 30 * 60_000L; // 30 minute cap
    private static final int MAX_PASSES_PER_EDGE = 4;

    private static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

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

            // /bot fortify resume <name>
            if (lower.startsWith("resume ")) {
                String wallName = args.trim().substring(7).trim();
                return handleResume(source, bot, world, server, wallName);
            }

            // /bot fortify patch <name>
            if (lower.startsWith("patch ")) {
                String wallName = args.trim().substring(6).trim();
                return handlePatch(source, bot, world, server, wallName);
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
            String status = f.isComplete() ? "§a[COMPLETE]" : "§e[" + f.getCompletedEdges().size() + " edges done]";
            ChatUtils.sendSystemMessage(source, String.format("§7  %s %s — center (%d,%d,%d), %d blocks placed",
                    f.getName(), status,
                    f.getCenter().getX(), f.getCenter().getY(), f.getCenter().getZ(),
                    f.getTotalBlocksPlaced()));
        }
        return SkillExecutionResult.success("Listed " + forts.size() + " walls.");
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
            ChatUtils.sendChatMessages(source, "§a[Fortify] Wall '" + wallName + "' is already complete.");
            return SkillExecutionResult.success("Wall already complete.");
        }

        ChatUtils.sendChatMessages(source, "§e[Fortify] Resuming wall '" + wallName + "' from edge #" + saved.getLastEdgeIndex() + "...");

        // Regenerate layout from saved hull vertices
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter());

        if (layout.edges().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        return buildWall(source, bot, world, server, layout, wallName, worldKey,
                saved.getCompletedEdges(), saved.getLastEdgeIndex(), saved.getTotalBlocksPlaced());
    }

    private SkillExecutionResult handlePatch(ServerCommandSource source, ServerPlayerEntity bot,
                                              ServerWorld world, MinecraftServer server, String wallName) {
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        Optional<SavedFortification> opt = FortificationPersistenceService.load(server, worldKey, wallName);
        if (opt.isEmpty()) {
            return SkillExecutionResult.failure("No saved wall named '" + wallName + "'. Use `/bot fortify list` to see saved walls.");
        }

        SavedFortification saved = opt.get();
        ChatUtils.sendChatMessages(source, "§e[Fortify] Scanning wall '" + wallName + "' for damage...");

        // Regenerate layout
        List<WallPoint> hullVertices = saved.getHullWallPoints();
        FortificationLayout layout = VillageFortificationLayoutService.generateLayoutFromHull(
                hullVertices, world, saved.getCenter());

        if (layout.allBlocks().isEmpty()) {
            return SkillExecutionResult.failure("Could not regenerate layout from saved hull.");
        }

        // Find missing/damaged blocks
        List<ProceduralWallBlock> repairList = new ArrayList<>();
        for (ProceduralWallBlock block : layout.allBlocks()) {
            BlockState current = world.getBlockState(block.worldPos());
            if (current.isAir() || current.isReplaceable()) {
                repairList.add(block);
            }
        }

        if (repairList.isEmpty()) {
            ChatUtils.sendChatMessages(source, "§a[Fortify] Wall '" + wallName + "' is intact! No repairs needed.");
            return SkillExecutionResult.success("Wall intact, no repairs needed.");
        }

        ChatUtils.sendChatMessages(source, "§e[Fortify] Found " + repairList.size() + " blocks to repair.");

        // Material check
        int buildBlocks = countBuildingBlocks(bot);
        if (buildBlocks == 0) {
            return SkillExecutionResult.failure("No building blocks in inventory for repairs.");
        }

        // Group by edge
        Map<Integer, List<ProceduralWallBlock>> byEdge = new LinkedHashMap<>();
        for (ProceduralWallBlock block : repairList) {
            byEdge.computeIfAbsent(block.edgeIndex(), k -> new ArrayList<>()).add(block);
        }

        // Sort edges by proximity to bot (nearest first) to minimize travel
        BlockPos botPos = bot.getBlockPos();
        List<Map.Entry<Integer, List<ProceduralWallBlock>>> sortedEdges = new ArrayList<>(byEdge.entrySet());
        sortedEdges.sort(Comparator.comparingDouble(entry -> {
            int edgeIdx = entry.getKey();
            if (edgeIdx == -1) {
                // Tower blocks — use centroid of repair blocks
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

        int totalRepaired = 0;
        int edgesPatched = 0;

        for (Map.Entry<Integer, List<ProceduralWallBlock>> entry : sortedEdges) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) {
                ChatUtils.sendChatMessages(source, "§e[Fortify] Out of blocks during patching. "
                        + totalRepaired + " blocks repaired so far.");
                break;
            }

            int edgeIdx = entry.getKey();
            List<ProceduralWallBlock> edgeRepairs = entry.getValue();

            if (edgeIdx == -1) {
                // Tower repairs — group by vertex and build per-vertex
                int towerRepaired = patchTowerBlocks(source, bot, world, edgeRepairs, layout.hullVertices());
                totalRepaired += towerRepaired;
                if (towerRepaired > 0) edgesPatched++;
            } else if (edgeIdx >= 0 && edgeIdx < layout.edges().size()) {
                WallEdge edge = layout.edges().get(edgeIdx);
                ChatUtils.sendSystemMessage(source, String.format("§7  Patching edge %d (%d missing blocks)...",
                        edgeIdx + 1, edgeRepairs.size()));

                // Navigate to edge then use full multi-pass buildEdge
                navigateToEdgeApproach(source, bot, edge, layout.hullVertices());
                sleepQuiet(100);

                int repaired = buildEdge(source, bot, world, edgeRepairs, edge, layout.hullVertices());
                totalRepaired += repaired;
                if (repaired > 0) edgesPatched++;
            }
        }

        // Final scaffold cleanup
        ScaffoldService.teardownTrackedScaffolds(bot);

        ChatUtils.sendChatMessages(source, "§a[Fortify] Patched " + totalRepaired + " blocks across " + edgesPatched + " edges.");
        return SkillExecutionResult.success("Patched " + totalRepaired + " blocks.");
    }

    /**
     * Patch tower blocks grouped by vertex. Navigates to each vertex,
     * builds its repair blocks, then tears down scaffolds before moving on.
     */
    private int patchTowerBlocks(ServerCommandSource source, ServerPlayerEntity bot,
                                   ServerWorld world, List<ProceduralWallBlock> towerRepairs,
                                   List<WallPoint> hullVertices) {
        // Group by nearest vertex
        Map<Integer, List<ProceduralWallBlock>> byVertex = new LinkedHashMap<>();
        for (ProceduralWallBlock block : towerRepairs) {
            int nearestVi = 0;
            double nearestDist = Double.MAX_VALUE;
            for (int vi = 0; vi < hullVertices.size(); vi++) {
                WallPoint v = hullVertices.get(vi);
                double d = Math.pow(block.worldPos().getX() - v.x(), 2) + Math.pow(block.worldPos().getZ() - v.z(), 2);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestVi = vi;
                }
            }
            byVertex.computeIfAbsent(nearestVi, k -> new ArrayList<>()).add(block);
        }

        // Sort vertices by proximity to bot
        BlockPos botPos = bot.getBlockPos();
        List<Map.Entry<Integer, List<ProceduralWallBlock>>> sortedVertices = new ArrayList<>(byVertex.entrySet());
        sortedVertices.sort(Comparator.comparingDouble(entry -> {
            WallPoint v = hullVertices.get(entry.getKey());
            return Math.pow(botPos.getX() - v.x(), 2) + Math.pow(botPos.getZ() - v.z(), 2);
        }));

        int totalRepaired = 0;
        for (Map.Entry<Integer, List<ProceduralWallBlock>> entry : sortedVertices) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;

            int vi = entry.getKey();
            WallPoint vertex = hullVertices.get(vi);
            List<ProceduralWallBlock> vertexRepairs = entry.getValue();

            int ty = VillageFortificationLayoutService.terrainY(world, vertex.x(), vertex.z());
            walkToTarget(source, bot, new BlockPos(vertex.x(), ty, vertex.z()), 8_000L);
            sleepQuiet(100);

            ChatUtils.sendSystemMessage(source, String.format("§7  Patching tower at (%d, %d) — %d missing blocks",
                    vertex.x(), vertex.z(), vertexRepairs.size()));

            int placed = buildBlockList(source, bot, world, vertexRepairs);
            totalRepaired += placed;

            // Clean up scaffolding after each vertex
            ScaffoldService.teardownTrackedScaffolds(bot);
            ScaffoldService.clearScaffoldMemory(bot);
        }
        return totalRepaired;
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
        ChatUtils.sendChatMessages(source, "§e[Fortify] Scanning for village...");
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
            return SkillExecutionResult.success("Dry run complete. " + planDesc);
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

        // Create persistence entry
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        String wallName = FortificationPersistenceService.autoName(layout.center());
        SavedFortification saved = FortificationPersistenceService.create(
                wallName, worldKey, layout.center(), layout.hullVertices(), 64);
        FortificationPersistenceService.save(server, saved);

        ChatUtils.sendChatMessages(source, "§7[Fortify] Wall saved as '" + wallName + "'.");

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
        long startMs = System.currentTimeMillis();
        int totalPlaced = priorBlocksPlaced;
        int edgesCompleted = completedEdges.size();
        int totalEdges = layout.edges().size();

        // Build towers vertex by vertex (instead of flat list, to avoid long-distance pathfinding)
        if (!completedEdges.contains(-1)) {
            List<ProceduralWallBlock> towerBlocks = layout.blocksForEdge(-1);
            if (!towerBlocks.isEmpty()) {
                int towerPlaced = 0;
                int numVertices = layout.hullVertices().size();
                ChatUtils.sendChatMessages(source, "§e[Fortify] Building " + numVertices + " corner towers...");

                for (int vi = 0; vi < numVertices; vi++) {
                    if (SkillManager.shouldAbortSkill(bot)) break;
                    if (countBuildingBlocks(bot) == 0) break;

                    WallPoint vertex = layout.hullVertices().get(vi);
                    int ty = VillageFortificationLayoutService.terrainY(world, vertex.x(), vertex.z());

                    // Navigate to this vertex (tick-based, avoids door-stuck pathfinding)
                    walkToTarget(source, bot, new BlockPos(vertex.x(), ty, vertex.z()), 8_000L);
                    sleepQuiet(100);

                    // Filter tower blocks near this vertex (3x3 tower = within 1 block XZ)
                    List<ProceduralWallBlock> vertexBlocks = new ArrayList<>();
                    for (ProceduralWallBlock b : towerBlocks) {
                        if (Math.abs(b.worldPos().getX() - vertex.x()) <= 1
                                && Math.abs(b.worldPos().getZ() - vertex.z()) <= 1) {
                            vertexBlocks.add(b);
                        }
                    }

                    ChatUtils.sendSystemMessage(source, String.format("§7  Tower %d/%d at (%d, %d) — %d blocks",
                            vi + 1, numVertices, vertex.x(), vertex.z(), vertexBlocks.size()));

                    int placed = buildBlockList(source, bot, world, vertexBlocks);
                    towerPlaced += placed;

                    // Tear down scaffolding after each vertex to avoid getting stranded elevated
                    ScaffoldService.teardownTrackedScaffolds(bot);
                    ScaffoldService.clearScaffoldMemory(bot);
                }

                totalPlaced += towerPlaced;

                if (towerPlaced > 0) {
                    completedEdges.add(-1);
                    FortificationPersistenceService.updateProgress(server, worldKey, wallName, -1, totalPlaced);
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

        // Build each edge
        for (int ei = 0; ei < totalEdges; ei++) {
            if (completedEdges.contains(ei)) continue;

            if (SkillManager.shouldAbortSkill(bot)) {
                saveAndReport(source, server, worldKey, wallName, ei, totalPlaced, "Aborted");
                return SkillExecutionResult.success("Aborted. Progress saved as '" + wallName + "'.");
            }
            if ((System.currentTimeMillis() - startMs) > MAX_BUILD_TIME_MS) {
                saveAndReport(source, server, worldKey, wallName, ei, totalPlaced, "Time budget reached");
                return SkillExecutionResult.success("Time budget reached. Progress saved as '" + wallName + "'.");
            }

            WallEdge edge = layout.edges().get(ei);
            List<ProceduralWallBlock> edgeBlocks = layout.blocksForEdge(ei);

            if (edgeBlocks.isEmpty()) {
                completedEdges.add(ei);
                continue;
            }

            ChatUtils.sendChatMessages(source, String.format("§e[Fortify] Building edge %d/%d (%.0f blocks long)%s...",
                    ei + 1, totalEdges, edge.length(),
                    ei == layout.gatehouseEdgeIndex() ? " [GATEHOUSE]" : ""));

            // Navigate to edge approach position
            navigateToEdgeApproach(source, bot, edge, layout.hullVertices());
            sleepQuiet(100);

            int edgePlaced = buildEdge(source, bot, world, edgeBlocks, edge, layout.hullVertices());
            totalPlaced += edgePlaced;

            if (edgePlaced > 0) {
                edgesCompleted++;
                FortificationPersistenceService.markEdgeComplete(server, worldKey, wallName, ei, edgePlaced);
            }

            // Check resources after each edge
            if (countBuildingBlocks(bot) == 0 && ei < totalEdges - 1) {
                return handleOutOfBlocks(source, server, worldKey, wallName, ei + 1, totalPlaced);
            }

            sleepQuiet(100);
        }

        // Scaffold cleanup
        int tornDown = ScaffoldService.teardownTrackedScaffolds(bot);
        if (tornDown > 0) {
            ChatUtils.sendChatMessages(source, "§7[Fortify] Cleaned up " + tornDown + " scaffold blocks.");
        }

        // Final report
        if (edgesCompleted >= totalEdges) {
            FortificationPersistenceService.markComplete(server, worldKey, wallName);
            ChatUtils.sendChatMessages(source, "§a[Fortify] Fortification complete! "
                    + totalPlaced + " blocks placed across " + totalEdges + " edges. Saved as '" + wallName + "'.");
            return SkillExecutionResult.success("Fortification complete: " + totalPlaced + " blocks.");
        } else {
            FortificationPersistenceService.updateProgress(server, worldKey, wallName, edgesCompleted, totalPlaced);
            ChatUtils.sendChatMessages(source, "§e[Fortify] Partial completion: "
                    + totalPlaced + " blocks placed, " + edgesCompleted + "/" + totalEdges + " edges. Saved as '" + wallName + "'.");
            return SkillExecutionResult.success("Partial fortification: " + totalPlaced + " blocks.");
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

    // ── Edge building ───────────────────────────────────────────

    /**
     * Build all blocks for a single edge using multi-pass pattern.
     * Returns total blocks placed.
     */
    private int buildEdge(ServerCommandSource source, ServerPlayerEntity bot,
                           ServerWorld world, List<ProceduralWallBlock> edgeBlocks,
                           WallEdge edge, List<WallPoint> hullVertices) {
        ScaffoldService.clearScaffoldMemory(bot);

        // Sort: foundations first (lowest Y), then by distance from edge start
        List<ProceduralWallBlock> sorted = new ArrayList<>(edgeBlocks);
        sorted.sort(Comparator.comparingInt((ProceduralWallBlock b) -> b.worldPos().getY())
                .thenComparingDouble(b -> {
                    double dx = b.worldPos().getX() - edge.start().x();
                    double dz = b.worldPos().getZ() - edge.start().z();
                    return dx * dx + dz * dz;
                }));

        // Collision check: skip edge if >40% of positions occupied by existing structures
        int occupiedCount = 0;
        for (ProceduralWallBlock block : sorted) {
            BlockState existing = world.getBlockState(block.worldPos());
            if (!existing.isAir() && !existing.isReplaceable()) {
                occupiedCount++;
            }
        }
        if (!sorted.isEmpty() && occupiedCount > sorted.size() * 0.4) {
            LOGGER.warn("Edge {} has {}% overlap with existing structures, skipping",
                    edge.index(), (occupiedCount * 100) / sorted.size());
            ChatUtils.sendChatMessages(source, "§e[Fortify] Skipping edge #" + edge.index()
                    + " — overlaps with existing structures (" + occupiedCount + "/" + sorted.size() + " blocks).");
            return 0;
        }

        Set<BlockPos> remaining = new LinkedHashSet<>();
        Map<BlockPos, ProceduralWallBlock> blockMap = new HashMap<>();
        Map<BlockPos, Integer> failCounts = new HashMap<>();
        Set<BlockPos> scaffoldFailedPositions = new HashSet<>();

        for (ProceduralWallBlock b : sorted) {
            remaining.add(b.worldPos());
            blockMap.put(b.worldPos(), b);
        }

        int totalPlaced = 0;
        int consecutiveNoProgress = 0;
        int repositionAttempt = 0;

        for (int pass = 1; pass <= MAX_PASSES_PER_EDGE && !remaining.isEmpty(); pass++) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;

            int passPlaced = 0;
            int passConsecutiveFails = 0;

            for (BlockPos worldPos : new ArrayList<>(remaining)) {
                if (SkillManager.shouldAbortSkill(bot)) break;
                if (countBuildingBlocks(bot) == 0) break;

                ProceduralWallBlock target = blockMap.get(worldPos);
                BlockState current = world.getBlockState(worldPos);

                // Already has correct block or any solid block
                if (current.equals(target.state()) || (!current.isAir() && !current.isReplaceable())) {
                    remaining.remove(worldPos);
                    if (current.equals(target.state())) passPlaced++;
                    passConsecutiveFails = 0;
                    continue;
                }

                int priorFails = failCounts.getOrDefault(worldPos, 0);
                if (priorFails >= 2) continue; // reduced from 3 — fail faster

                // Compute height above terrain for scaffolding decisions
                int terrainY = VillageFortificationLayoutService.terrainY(world, worldPos.getX(), worldPos.getZ());
                int heightAboveGround = worldPos.getY() - terrainY;

                boolean canReach = ensureCanReachBlockWithEffort(
                        source, bot, world, worldPos, heightAboveGround, pass, scaffoldFailedPositions);
                if (!canReach) {
                    failCounts.merge(worldPos, 1, Integer::sum);
                    passConsecutiveFails++;
                    // If many consecutive fails in this pass, skip the rest of this pass
                    if (passConsecutiveFails >= 15) break;
                    continue;
                }

                BotActions.PlaceResult placed = tryPlaceBlock(bot, world, worldPos, target.state());
                if (placed.success()) {
                    passPlaced++;
                    remaining.remove(worldPos);
                    failCounts.remove(worldPos);
                    passConsecutiveFails = 0;
                } else {
                    failCounts.merge(worldPos, 1, Integer::sum);
                    passConsecutiveFails++;
                    if (placed.reason() != null && placed.reason().startsWith("no-solid-support")) {
                        fillGroundUnder(bot, world, worldPos);
                    }
                    if (passConsecutiveFails >= 15) break;
                }

                sleepQuiet(BLOCK_PLACE_DELAY_MS);
            }

            totalPlaced += passPlaced;

            if (passPlaced == 0) {
                consecutiveNoProgress++;
                if (consecutiveNoProgress >= 2) { // reduced from 3 — fail faster
                    LOGGER.info("Edge {}: {} consecutive passes with no progress, giving up",
                            edge.index(), consecutiveNoProgress);
                    break;
                }
                // Reposition for better access
                repositionForEdge(source, bot, edge, hullVertices, repositionAttempt);
                repositionAttempt++;
            } else {
                consecutiveNoProgress = 0;
            }
        }

        // Clean up scaffolds for this edge
        ScaffoldService.teardownTrackedScaffolds(bot);

        LOGGER.info("Edge {} complete: {} blocks placed, {} remaining",
                edge.index(), totalPlaced, remaining.size());
        return totalPlaced;
    }

    /**
     * Build a list of blocks (used by tower building and patch mode). Returns blocks placed.
     * Skips blocks that are too far away to avoid wasting time on unreachable blocks.
     * Uses fast bail movement — we've already navigated to the area before calling this.
     */
    private int buildBlockList(ServerCommandSource source, ServerPlayerEntity bot,
                                ServerWorld world, List<ProceduralWallBlock> blocks) {
        int totalPlaced = 0;
        int consecutiveFails = 0;

        for (ProceduralWallBlock block : blocks) {
            if (SkillManager.shouldAbortSkill(bot)) break;
            if (countBuildingBlocks(bot) == 0) break;

            BlockState current = world.getBlockState(block.worldPos());
            if (!current.isAir() && !current.isReplaceable()) continue;

            // Skip blocks that are too far — we already navigated to the area
            double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(block.worldPos()));
            if (distSq > 400) { // > 20 blocks — skip entirely
                continue;
            }

            // For medium distance (5-20 blocks), quick walk with fast bail
            if (distSq > 25) {
                walkTowardBlock(bot, block.worldPos(), 1500L);
            }

            // Try to reach
            int terrainY = VillageFortificationLayoutService.terrainY(world, block.worldPos().getX(), block.worldPos().getZ());
            int height = block.worldPos().getY() - terrainY;
            boolean reachable = ensureCanReachBlockWithEffort(source, bot, world, block.worldPos(), height, 1);
            if (!reachable) {
                consecutiveFails++;
                if (consecutiveFails >= 10) break; // stop wasting time if nothing is reachable
                continue;
            }

            BotActions.PlaceResult placed = tryPlaceBlock(bot, world, block.worldPos(), block.state());
            if (placed.success()) {
                totalPlaced++;
                consecutiveFails = 0;
            } else {
                consecutiveFails++;
                if (consecutiveFails >= 10) break;
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
        if (primary == Items.STONE_BRICKS || primary == Items.STONE_BRICK_SLAB
                || primary == Items.STONE_BRICK_STAIRS) {
            return new ArrayList<>(STONE_BRICK_FALLBACKS);
        }
        if (primary == Items.CHISELED_STONE_BRICKS) {
            return new ArrayList<>(CHISELED_FALLBACKS);
        }
        if (primary == Items.OAK_LOG) {
            return new ArrayList<>(OAK_LOG_FALLBACKS);
        }
        List<Item> list = new ArrayList<>();
        list.add(primary);
        list.add(Items.COBBLESTONE);
        list.add(Items.DIRT);
        return list;
    }

    private void fillGroundUnder(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        List<BlockPos> toFill = new ArrayList<>();
        BlockPos cursor = pos.down();
        for (int i = 0; i < 4; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable()) break;
            toFill.add(cursor);
            cursor = cursor.down();
        }
        if (toFill.isEmpty()) return;

        BlockState foundation = world.getBlockState(toFill.get(toFill.size() - 1).down());
        if (foundation.isAir()) return;

        List<Item> fillBlocks = List.of(Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE);
        for (int i = toFill.size() - 1; i >= 0; i--) {
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, toFill.get(i), Direction.UP, fillBlocks);
            if (!result.success()) break;
        }
    }

    // ── Navigation ──────────────────────────────────────────────

    /**
     * Navigate to an approach position for the given edge.
     * Stands 3 blocks outside the edge midpoint along the outward normal.
     */
    private void navigateToEdgeApproach(ServerCommandSource source, ServerPlayerEntity bot,
                                         WallEdge edge, List<WallPoint> hullVertices) {
        // Compute edge midpoint
        int midX = (edge.start().x() + edge.end().x()) / 2;
        int midZ = (edge.start().z() + edge.end().z()) / 2;

        // Compute outward normal (90° CW rotation of edge direction for CCW hull)
        double edgeDx = edge.end().x() - edge.start().x();
        double edgeDz = edge.end().z() - edge.start().z();
        double edgeLen = Math.sqrt(edgeDx * edgeDx + edgeDz * edgeDz);
        if (edgeLen < 0.001) {
            walkToTarget(source, bot, new BlockPos(midX, bot.getBlockPos().getY(), midZ), 15_000L);
            return;
        }

        // Outward normal for CCW hull: (dz, -dx) normalized
        double nx = edgeDz / edgeLen;
        double nz = -edgeDx / edgeLen;

        int approachX = (int) Math.round(midX + nx * 3);
        int approachZ = (int) Math.round(midZ + nz * 3);
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int approachY = VillageFortificationLayoutService.terrainY(world, approachX, approachZ);

        walkToTarget(source, bot, new BlockPos(approachX, approachY, approachZ), 8_000L);
    }

    /**
     * Reposition for better access to an edge. Cycles through 4 vantage points:
     * outside-start, outside-end, inside-start, inside-end.
     */
    private void repositionForEdge(ServerCommandSource source, ServerPlayerEntity bot,
                                    WallEdge edge, List<WallPoint> hullVertices, int attempt) {
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

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int targetY = VillageFortificationLayoutService.terrainY(world, targetX, targetZ);

        LOGGER.debug("Repositioning to mode {} at ({},{},{})", mode, targetX, targetY, targetZ);
        walkToTarget(source, bot, new BlockPos(targetX, targetY, targetZ), 5_000L);
    }

    // ── Movement & reach ────────────────────────────────────────

    /**
     * Get the bot within reach of a target block. Uses ONLY tick-based movement
     * (no A* pathfinding) to avoid door-escape loops near village structures.
     *
     * Strategy order:
     *   1. Scaffolding FIRST for elevated blocks (fastest decision)
     *   2. Tick-based walk toward the block
     *   3. Side approach from 4 directions (pass 2+)
     */
    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber) {
        return ensureCanReachBlockWithEffort(source, bot, world, target, heightAboveGround, passNumber, null);
    }

    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target,
                                                   int heightAboveGround, int passNumber,
                                                   Set<BlockPos> scaffoldFailedPositions) {
        if (isWithinReach(bot, target)) return true;

        BlockPos botPos = bot.getBlockPos();
        double horizontalDistSq = Math.pow(target.getX() - botPos.getX(), 2) + Math.pow(target.getZ() - botPos.getZ(), 2);
        int verticalDiff = target.getY() - botPos.getY();

        // Too far — caller should use walkToTarget first
        if (horizontalDistSq > 400) return false;

        // Strategy 1: Scaffolding FIRST for elevated blocks (merlons Y+4, tower tops Y+5)
        if (verticalDiff > 2 && heightAboveGround > 0 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT) {
            BlockPos scaffoldBase = new BlockPos(target.getX(), botPos.getY(), target.getZ());
            boolean scaffoldBlacklisted = scaffoldFailedPositions != null
                    && scaffoldFailedPositions.contains(scaffoldBase);

            if (!scaffoldBlacklisted) {
                // Walk under the target first using tick-based movement
                if (!isWithinReachXZ(bot, scaffoldBase, 2.0)) {
                    walkTowardBlock(bot, scaffoldBase, 1500L);
                }

                int currentBotY = bot.getBlockPos().getY();
                int optimalY = target.getY() - 2;
                int stepsNeeded = Math.max(0, optimalY - currentBotY);

                if (stepsNeeded > 0 && stepsNeeded <= MAX_SCAFFOLD_HEIGHT) {
                    boolean pillared = ScaffoldService.pillarUp(bot, stepsNeeded, true);
                    if (pillared && isWithinReach(bot, target)) return true;
                    // Failed — blacklist this position so we don't retry
                    if (!pillared && scaffoldFailedPositions != null) {
                        scaffoldFailedPositions.add(scaffoldBase);
                    }
                }
            }
        }

        // Strategy 2: Tick-based walk toward the block (no pathfinding, no door loops)
        if (horizontalDistSq > REACH_DISTANCE_SQ) {
            walkTowardBlock(bot, target, 1500L);
            if (isWithinReach(bot, target)) return true;
        }

        // Strategy 3: Side approach from 4 directions (pass 2+)
        if (passNumber >= 2) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos sidePos = target.offset(dir, 2).withY(botPos.getY());
                walkTowardBlock(bot, sidePos, 800L);
                if (isWithinReach(bot, target)) return true;
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
        int tickCount = 0;
        double lastDistSq = Double.MAX_VALUE; // don't compare on first tick
        int stuckTicks = 0;

        while (System.currentTimeMillis() < deadline) {
            double distSq = bot.squaredDistanceTo(targetVec);
            if (distSq < 6.0) return; // close enough for block placement

            // Apply movement FIRST, then check stuck on subsequent ticks
            LookController.faceBlock(bot, target);
            BotActions.applyMovementInput(bot, targetVec, 0.28D);
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
        double lastDistSq = distSq;
        int stuckTicks = 0;

        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) return;

            double currentDistSq = bot.squaredDistanceTo(targetVec);
            if (currentDistSq < 9.0) return; // close enough

            // Stuck detection: if we haven't moved significantly in 15 ticks (~0.75s), bail
            if (Math.abs(currentDistSq - lastDistSq) < 0.5) {
                stuckTicks++;
                if (stuckTicks > 15) {
                    LOGGER.debug("Walk to {} stuck after {} ticks, giving up", target.toShortString(), stuckTicks);
                    // Try a brief jump to unstick
                    BotActions.jump(bot);
                    sleepQuiet(100);
                    return;
                }
            } else {
                stuckTicks = 0;
                lastDistSq = currentDistSq;
            }

            LookController.faceBlock(bot, target);
            BotActions.sprint(bot, false);
            BotActions.applyMovementInput(bot, targetVec, 0.28D);

            sleepQuiet(50);
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

    private int countBuildingBlocks(ServerPlayerEntity bot) {
        int count = 0;
        Set<Item> buildItems = Set.of(
                Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE, Items.COBBLED_DEEPSLATE,
                Items.ANDESITE, Items.DIRT, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
                Items.JUNGLE_LOG, Items.CHISELED_STONE_BRICKS, Items.STONE_BRICK_SLAB,
                Items.STONE_BRICK_STAIRS, Items.OAK_PLANKS, Items.SPRUCE_PLANKS
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

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
