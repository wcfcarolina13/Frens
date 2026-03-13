package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.schematic.SchematicData;
import net.wcfcarolina13.GameAI.schematic.SchematicReader;
import net.wcfcarolina13.GameAI.schematic.SimpleSchematicBuilder;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.construction.ActiveBuildRepairSession;
import net.wcfcarolina13.GameAI.services.construction.BlockReplacementService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionBlueprintService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionBlueprintService.ConstructionPlan;
import net.wcfcarolina13.GameAI.services.construction.ConstructionPlacementRules;
import net.wcfcarolina13.GameAI.services.construction.ConstructionProtectionService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionRepairService;
import net.wcfcarolina13.GameAI.services.construction.DoorPlacementService;
import net.wcfcarolina13.GameAI.services.construction.DoorwayAccessService;
import net.wcfcarolina13.GameAI.services.construction.PerimeterService;
import net.wcfcarolina13.GameAI.services.construction.RoofAccessService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.EnumMap;

/**
 * Skill for building structures from schematic/blueprint files.
 * 
 * Usage:
 *   /bot build <schematic_name>       - Build a built-in or loaded schematic
 *   /bot build list                   - List available schematics
 *   /bot build preview <schematic>    - Show info about a schematic without building
 * 
 * Supported schematic sources:
 *   1. Built-in schematics (SimpleSchematicBuilder)
 *   2. .nbt files in assets/frens/schematics/
 *   3. .nbt files in world/schematics/ folder (planned)
 */
public final class BuildSchematicSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-build-schematic");
    private static final double REACH_DISTANCE_SQ = ConstructionPlacementRules.REACH_DISTANCE_SQ;
    private static final int MAX_SCAFFOLD_HEIGHT = ConstructionPlacementRules.DEFAULT_MAX_SCAFFOLD_HEIGHT;
    private static final int DEFAULT_FLOOR_SCAN_DEPTH = 8;

    // Blocks suitable for scaffolding (will be torn down after)
    private static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

    @Override
    public String name() {
        return "build";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available for building.");
        }

        // Parse arguments
        String arg = getArgument(context);
        if (arg == null || arg.isEmpty()) {
            return SkillExecutionResult.failure("Usage: /bot build <schematic_name> or /bot build list");
        }

        String[] parts = arg.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();

        // Handle special commands
        if (command.equals("list")) {
            return listSchematics(source);
        }

        if (command.equals("preview") && parts.length > 1) {
            return previewSchematic(source, parts[1]);
        }

        // Try to load the schematic
        String schematicName = command;
        Optional<SchematicData> schematicOpt = loadSchematic(schematicName);

        if (schematicOpt.isEmpty()) {
            return SkillExecutionResult.failure("Schematic '" + schematicName + "' not found. Use '/bot build list' to see available schematics.");
        }

        SchematicData schematic = schematicOpt.get();
        String displayName = schematic.name();
        int rotationTurns = Math.floorMod(getIntParameter(context, "rotation", 0), 4);
        if (rotationTurns != 0) {
            schematic = schematic.rotated(rotationTurns);
            LOGGER.info("Applying preview rotation: schematic={} turns={} effectiveSize={}x{}x{}",
                displayName, rotationTurns, schematic.sizeX(), schematic.sizeY(), schematic.sizeZ());
        }
        
        // Check for special requirements (small_hut needs torch)
        if ("small_hut".equals(schematicName)) {
            if (!hasItemInInventory(bot, Items.TORCH)) {
                return SkillExecutionResult.failure("Small hut requires at least 1 torch in inventory.");
            }
        }

        // Validate schematic
        if (!schematic.isReasonableSize()) {
            return SkillExecutionResult.failure("Schematic too large: " + schematic.sizeX() + "x" + schematic.sizeY() + "x" + schematic.sizeZ() + " (" + schematic.blockCount() + " blocks)");
        }

        // Calculate materials needed
        Map<Item, Integer> materialsNeeded = calculateMaterials(schematic);
        Map<Item, Integer> materialsAvailable = countAvailableMaterials(bot, materialsNeeded);

        // Check if we have enough materials (warning only for now)
        StringBuilder missingMaterials = new StringBuilder();
        int totalMissing = 0;
        for (Map.Entry<Item, Integer> entry : materialsNeeded.entrySet()) {
            int available = materialsAvailable.getOrDefault(entry.getKey(), 0);
            if (available < entry.getValue()) {
                if (missingMaterials.length() > 0) missingMaterials.append(", ");
                missingMaterials.append(entry.getKey().getName().getString())
                        .append(": need ").append(entry.getValue())
                        .append(", have ").append(available);
                totalMissing += (entry.getValue() - available);
            }
        }

        // Also count total available building blocks
        int totalBuildBlocks = countAllBuildBlocks(bot);
        ChatUtils.sendSystemMessage(source, "§7Bot has " + totalBuildBlocks + " total building blocks in inventory.");

        if (missingMaterials.length() > 0) {
            ChatUtils.sendSystemMessage(source, "§eWarning: Missing " + totalMissing + " blocks: " + missingMaterials);
            ChatUtils.sendSystemMessage(source, "§eBuild will use substitute blocks (dirt, cobblestone) where possible.");
        }

        if (totalBuildBlocks == 0) {
            return SkillExecutionResult.failure("Bot has no building blocks! Give the bot some cobblestone, dirt, or planks.");
        }

        boolean hasExplicitTarget = context.parameters().containsKey("targetX")
            && context.parameters().containsKey("targetY")
            && context.parameters().containsKey("targetZ");
        BlockPos requestedCenter = hasExplicitTarget
            ? new BlockPos(
            getIntParameter(context, "targetX", bot.getBlockPos().getX()),
            getIntParameter(context, "targetY", bot.getBlockPos().getY()),
            getIntParameter(context, "targetZ", bot.getBlockPos().getZ()))
            : bot.getBlockPos();

        SchematicAnchorResolution anchor = resolveSchematicAnchor(world, schematic, requestedCenter);

        int offsetX = -schematic.sizeX() / 2;
        int offsetZ = -schematic.sizeZ() / 2;
        BlockPos centerPos = new BlockPos(requestedCenter.getX(), anchor.originY() + anchor.anchorLocalY(), requestedCenter.getZ());
        BlockPos origin = new BlockPos(centerPos.getX() + offsetX, anchor.originY(), centerPos.getZ() + offsetZ);

        // Compute perimeter build stations around the schematic, then pick the
        // one closest to the bot's current position.  This avoids the old
        // behaviour of always walking to a fixed corner (which created movement
        // thrashing when the bot was already near a different viable station).
        Set<BlockPos> earlyPlanned = new HashSet<>();
        for (var bp : schematic.blocks()) {
            BlockState st = schematic.getState(bp.paletteIndex());
            if (st != null && !st.isAir()) {
                earlyPlanned.add(origin.add(bp.relativePos()));
            }
        }
        List<BlockPos> earlyStations = computeBuildStations(earlyPlanned);
        BlockPos buildCorner;
        if (earlyStations.isEmpty()) {
            buildCorner = origin.add(schematic.sizeX() - 1, 0, 0);
        } else {
            BlockPos botPos = bot.getBlockPos();
            buildCorner = earlyStations.stream()
                    .min(Comparator.comparingDouble(s -> s.getSquaredDistance(botPos)))
                    .orElse(origin.add(schematic.sizeX() - 1, 0, 0));
        }

        LOGGER.info(
            "Resolved schematic anchor: schematic={} requestedCenter={} explicitTarget={} floorY={} minLocalY={} anchorLocalY={} origin={} buildCorner={}",
            displayName,
            requestedCenter.toShortString(),
            hasExplicitTarget,
            anchor.floorBlockY(),
            anchor.minOccupiedLocalY(),
            anchor.anchorLocalY(),
            origin.toShortString(),
            buildCorner.toShortString());

        // Move bot to nearest station before building (no teleport, no snap for survival-style movement)
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
            bot, buildCorner, MovementService.MovementOptions.skillLoot());
        if (plan.isPresent()) {
            MovementService.execute(source, bot, plan.get(), false, true, true, false);
        }
        
        // Build the schematic
        String rotationSuffix = rotationTurns == 0 ? "" : (" [rotated " + (rotationTurns * 90) + "°]");
        ChatUtils.sendSystemMessage(source, "Building schematic '" + displayName + "'" + rotationSuffix + " (" + schematic.blockCount() + " blocks) at " + origin.toShortString() + "...");
        int blocksPlaced = buildSchematic(source, bot, world, schematic, origin);

        if (blocksPlaced > 0) {
            return SkillExecutionResult.success("Built schematic '" + displayName + "'" + rotationSuffix + ": " + blocksPlaced + "/" + schematic.blockCount() + " blocks placed.");
        } else {
            return SkillExecutionResult.failure("Failed to place any blocks from schematic '" + displayName + "'" + rotationSuffix + ". Check bot has materials and is in a clear area.");
        }
    }

    /**
     * Count all building blocks in the bot's inventory.
     */
    private int countAllBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Load a schematic by name from various sources.
     */
    private Optional<SchematicData> loadSchematic(String name) {
        // 1. Try built-in schematics first
        SchematicData builtIn = SimpleSchematicBuilder.getBuiltIn(name);
        if (builtIn != null) {
            LOGGER.info("Loaded built-in schematic: {}", name);
            return Optional.of(builtIn);
        }

        // 2. Try loading from mod resources
        Optional<SchematicData> fromResources = SchematicReader.loadFromResources(name);
        if (fromResources.isPresent()) {
            LOGGER.info("Loaded schematic from resources: {}", name);
            return fromResources;
        }

        // 3. Try loading from external file (world/schematics/ folder)
        // This would need the world folder path - skipping for now
        // Path externalPath = Paths.get("schematics", name + ".nbt");
        // Optional<SchematicData> fromFile = SchematicReader.loadFromFile(externalPath);

        return Optional.empty();
    }

    /**
     * List all available schematics.
     */
    private SkillExecutionResult listSchematics(ServerCommandSource source) {
        List<String> builtIn = SimpleSchematicBuilder.listBuiltIn();
        
        StringBuilder sb = new StringBuilder("§aAvailable schematics:");
        sb.append("\n§7Built-in: §f").append(String.join(", ", builtIn));
        
        // TODO: List schematics from resources and external files
        
        ChatUtils.sendSystemMessage(source, sb.toString());
        return SkillExecutionResult.success("Listed " + builtIn.size() + " schematics.");
    }

    /**
     * Show information about a schematic without building it.
     */
    private SkillExecutionResult previewSchematic(ServerCommandSource source, String name) {
        Optional<SchematicData> schematicOpt = loadSchematic(name);
        if (schematicOpt.isEmpty()) {
            return SkillExecutionResult.failure("Schematic '" + name + "' not found.");
        }

        SchematicData schematic = schematicOpt.get();
        Map<Item, Integer> materials = calculateMaterials(schematic);

        StringBuilder sb = new StringBuilder("§aSchematic: §f" + schematic.name());
        sb.append("\n§7Size: §f").append(schematic.sizeX()).append("x").append(schematic.sizeY()).append("x").append(schematic.sizeZ());
        sb.append("\n§7Blocks: §f").append(schematic.blockCount());
        sb.append("\n§7Materials needed:");

        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            sb.append("\n  §7- §f").append(entry.getKey().getName().getString()).append(": ").append(entry.getValue());
        }

        ChatUtils.sendSystemMessage(source, sb.toString());
        return SkillExecutionResult.success("Previewed schematic '" + name + "'.");
    }

    /**
     * Calculate the materials needed for a schematic.
     */
    private Map<Item, Integer> calculateMaterials(SchematicData schematic) {
        Map<Item, Integer> materials = new HashMap<>();

        for (SchematicData.BlockPlacement placement : schematic.blocks()) {
            BlockState state = schematic.getState(placement.paletteIndex());
            if (state == null || state.isAir()) continue;

            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                materials.merge(item, 1, (current, added) -> Integer.valueOf(current + added));
            }
        }

        return materials;
    }

    /**
     * Count available materials in the bot's inventory.
     */
    private Map<Item, Integer> countAvailableMaterials(ServerPlayerEntity bot, Map<Item, Integer> needed) {
        Map<Item, Integer> available = new HashMap<>();
        
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            
            Item item = stack.getItem();
            if (item instanceof BlockItem && needed.containsKey(item)) {
                available.merge(item, stack.getCount(), (current, added) -> Integer.valueOf(current + added));
            }
        }

        return available;
    }

    /**
     * Build the schematic at the given origin.
     * Uses ConstructionBlueprintService for intelligent build ordering (corners first, layer-by-layer)
     * and multi-pass approach with scaffolding for elevated blocks.
     */
    private int buildSchematic(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world,
                               SchematicData schematic, BlockPos origin) {
        // Clear legacy scaffold memory from earlier implementations.
        ScaffoldService.clearScaffoldMemory(bot);
        RoofAccessService.clearRoofPillars(bot);
        String taskId = "build:" + schematic.name();

        // Generate construction plan with intelligent ordering
        Direction facing = bot.getHorizontalFacing();
        ConstructionPlan plan = ConstructionBlueprintService.planConstruction(schematic, origin, facing);

        List<PlacementTarget> orderedTargets = ConstructionBlueprintService.toPlacementTargets(plan);
        boolean useCenterPillarPhases = shouldUseCenterPillarPhases(schematic, plan);
        List<PlacementTarget> mainTargets = useCenterPillarPhases
            ? orderCentroidPhaseTargets(orderedTargets, plan.center())
            : orderedTargets;
        List<PlacementTarget> deferredRoofTargets = useCenterPillarPhases
            ? orderedTargets.stream().filter(target -> target.kind() == PlacementTarget.TargetKind.ROOF).toList()
                : List.of();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        Set<BlockPos> remainingForVantage = new HashSet<>();
        for (PlacementTarget target : orderedTargets) {
            blockStates.put(target.pos(), target.desiredState());
            if (!useCenterPillarPhases || target.kind() != PlacementTarget.TargetKind.ROOF) {
                remainingForVantage.add(target.pos());
            }
        }

        int totalBlocks = orderedTargets.size();
        List<BlockPos> buildStations = computeBuildStations(blockStates.keySet());
        ActiveBuildRepairSession repairSession = ActiveBuildRepairSession.begin(taskId, world, blockStates);

        // Log construction plan info
        LOGGER.info("Construction plan: {} blocks, {} corners, {} roof, suggested door side: {}",
                totalBlocks, plan.cornerPositions().size(), plan.roofPositions().size(), plan.suggestedDoorSide());

        ChatUtils.sendChatMessages(source, "§e[Build] Starting construction of " + schematic.name() + 
                " (" + totalBlocks + " blocks)...");

        int maxPasses = Math.max(6, Math.min(12, 3 + (totalBlocks / 120)));
        long maxBuildMs = Math.max(6 * 60_000L, Math.min(20 * 60_000L, 2_000L * maxPasses));
        int[] repositionAttempt = new int[]{0};
        boolean[] centeredAfterFirstPass = new boolean[]{false};
        ScaffoldService.ScaffoldSession scaffoldSession = ScaffoldService.beginSession(bot);
        Set<BlockPos> protectedPlannedPositions = Set.copyOf(blockStates.keySet());

        ConstructionProtectionService.activate(
                bot.getUuid(),
                taskId,
                protectedPlannedPositions,
            Set.copyOf(buildStations)
        );
        ConstructionRepairService.register(bot.getUuid(), repairSession);
        LOGGER.info("Activated schematic protection: bot={} schematic={} planned={} stations={}",
            bot.getName().getString(),
            schematic.name(),
            protectedPlannedPositions.size(),
            buildStations.size());

        try {
            ConstructionTaskSpec spec = new ConstructionTaskSpec(
                    taskId,
                    world,
                    bot,
                    source,
                    mainTargets,
                    new ExecutionPolicy(maxPasses, 3, 2, maxBuildMs),
                    new ConstructionTaskSpec.SupportPolicy(true, true, MAX_SCAFFOLD_HEIGHT),
                    (target, pass) -> {
                        int blockHeight = target.pos().getY() - origin.getY();
                        return ensureCanReachBlockWithEffort(source, bot, world, target, blockHeight, pass, scaffoldSession);
                    },
                    (target, pass) -> {
                        BotActions.PlaceResult placed = tryPlaceBlockWithRecovery(
                                source, bot, world, target.pos(), target.desiredState(), blockStates, scaffoldSession);
                        if (placed.success()) {
                            remainingForVantage.remove(target.pos());
                            repairSession.markPlaced(target.pos());
                            return ConstructionTaskSpec.PlacementOutcome.ok();
                        }
                        FailureReason reason = FailureReason.fromPlaceReason(placed.reason());
                        return ConstructionTaskSpec.PlacementOutcome.fail(reason);
                    },
                    progress -> {
                        remainingForVantage.removeIf(pos -> {
                            BlockState desired = blockStates.get(pos);
                            return desired != null && BlockReplacementService.stateSatisfies(world.getBlockState(pos), desired);
                        });
                        ActiveBuildRepairSession.RepairSweepResult repairSweep = repairSession.sweep(
                                source, bot, world, REACH_DISTANCE_SQ, false);
                        ChatUtils.sendChatMessages(source, "§7[Build] Pass " + progress.passNumber() + "/" + maxPasses
                                + " complete - " + progress.placedThisPass() + " placed, "
                                + progress.remaining() + " remaining.");
                        if (repairSweep.repairedCount() > 0 || repairSweep.queuedCount() > 0 || repairSweep.damagedCount() > 0) {
                            LOGGER.info("Active-build repair pass: task={} pass={} damaged={} repaired={} queued={} remainingQueue={} throttled={}",
                                    taskId,
                                    progress.passNumber(),
                                    repairSweep.damagedCount(),
                                    repairSweep.repairedCount(),
                                    repairSweep.queuedCount(),
                                    repairSweep.remainingQueue(),
                                    repairSweep.throttled());
                            if (repairSweep.repairedCount() > 0 || repairSweep.queuedCount() > 0) {
                                ChatUtils.sendChatMessages(source, "§7[Build] Repair sweep: "
                                        + repairSweep.repairedCount() + " repaired, "
                                        + repairSweep.remainingQueue() + " queued.");
                            }
                        }
                        if (useCenterPillarPhases && progress.passNumber() == 1 && !centeredAfterFirstPass[0]) {
                            ChatUtils.sendChatMessages(source, "§7[Build] Switching to centroid fill...");
                            moveToReachBlock(source, bot, plan.center());
                            centeredAfterFirstPass[0] = true;
                        }
                    },
                    (progress, streak) -> {
                        if (useCenterPillarPhases) {
                            moveToReachBlock(source, bot, plan.center());
                        } else if (!remainingForVantage.isEmpty()) {
                            ChatUtils.sendChatMessages(source, "§7[Build] Repositioning for better access...");
                            moveToVantagePosition(source, bot, buildStations, remainingForVantage, repositionAttempt[0]);
                            repositionAttempt[0]++;
                        }
                    },
                    scaffoldSession,
                    true,
                    Set.of()
            );

            ExecutionReport report = ConstructionExecutionService.execute(spec);
            if (report.scaffoldsRemoved() > 0) {
                ChatUtils.sendChatMessages(source, "§7[Build] Cleaned up " + report.scaffoldsRemoved() + " scaffold blocks.");
            }

            CenterPillarPhaseResult centerPhase = CenterPillarPhaseResult.empty();
            if (useCenterPillarPhases && !report.aborted() && !report.timedOut()) {
                List<PlacementTarget> unresolvedTargets = unresolvedStructuralTargets(world, orderedTargets);
                if (!unresolvedTargets.isEmpty()) {
                    centerPhase = completeFromCenterPillar(source, bot, world, plan, unresolvedTargets, blockStates, repairSession);
                }
            }

            ActiveBuildRepairSession.RepairSweepResult finalRepairSweep = repairSession.sweep(
                    source, bot, world, REACH_DISTANCE_SQ, true);
            int repairRemaining = repairSession.remainingDamageCount(world);
            if (finalRepairSweep.repairedCount() > 0 || finalRepairSweep.remainingQueue() > 0 || repairRemaining > 0) {
                LOGGER.info("Active-build repair final sweep: task={} repaired={} queued={} remainingDamage={} throttled={}",
                        taskId,
                        finalRepairSweep.repairedCount(),
                        finalRepairSweep.remainingQueue(),
                        repairRemaining,
                        finalRepairSweep.throttled());
                ChatUtils.sendChatMessages(source, "§7[Build] Final repair sweep: "
                        + finalRepairSweep.repairedCount() + " repaired, "
                        + repairRemaining + " structure issues remaining.");
            }

                Map<FailureReason, Integer> finalFailures = new EnumMap<>(FailureReason.class);
                int totalPlaced;
                int finalRemaining;
                if (useCenterPillarPhases && !report.aborted() && !report.timedOut()) {
                    finalFailures.putAll(centerPhase.remainingByReason());
                    totalPlaced = report.placedCount() + centerPhase.placedCount();
                    finalRemaining = centerPhase.remainingCount();
                } else {
                    finalFailures.putAll(report.remainingByReason());
                    totalPlaced = report.placedCount();
                    finalRemaining = report.remainingCount();
                    if (useCenterPillarPhases && finalRemaining == 0 && !deferredRoofTargets.isEmpty()) {
                        finalRemaining = countRemainingTargets(world, deferredRoofTargets);
                        if (finalRemaining > 0) {
                            finalFailures.put(FailureReason.MOVEMENT_FAILED, finalRemaining);
                        }
                    }
                }
                // Allow door placement even if a few repair items remain (e.g. scaffold-gap
                // patch couldn't reach one block).  The structure is functionally complete.
                boolean shouldProcessDoors = repairRemaining <= 2 && finalRemaining <= Math.max(4, totalBlocks / 10);

                // Clear doorway access paths and place doors using DoorwayAccessService and DoorPlacementService
                if (!SkillManager.shouldAbortSkill(bot) && shouldProcessDoors) {
                DoorwayAccessService.AccessResult doorwayResult = DoorwayAccessService.clearAllDoorways(
                        world, source, bot, origin, schematic.sizeX(), schematic.sizeZ(), origin.getY());
                if (doorwayResult.blocksCleared() > 0) {
                    ChatUtils.sendChatMessages(source, "§7[Build] Cleared " + doorwayResult.blocksCleared() +
                            " doorway obstructions.");
                }

                // Place doors at detected doorways if bot has door items
                if (DoorPlacementService.hasDoorItem(bot)) {
                    List<DoorwayAccessService.Doorway> doorways = DoorwayAccessService.detectDoorways(
                            world, origin, schematic.sizeX(), schematic.sizeZ(), origin.getY());
                    for (DoorwayAccessService.Doorway doorway : doorways) {
                        if (SkillManager.shouldAbortSkill(bot)) break;
                        // Place door facing outward (toward exterior approach)
                        boolean placed = DoorPlacementService.placeDoor(
                                world, source, bot, doorway.bottomPos(), doorway.facingOutward());
                        if (placed) {
                            ChatUtils.sendChatMessages(source, "§7[Build] Placed door at " +
                                    doorway.bottomPos().toShortString());
                        }
                    }
                }
            } else if (!SkillManager.shouldAbortSkill(bot) && finalRemaining > 0) {
                LOGGER.info("Skipping doorway placement for incomplete schematic build: schematic={} placed={}/{} remaining={} repairRemaining={}",
                        schematic.name(), totalPlaced, totalBlocks, finalRemaining, repairRemaining);
            }

            // Final status
            if (finalRemaining > 0 || repairRemaining > 0) {
                String summary = formatFailureSummary(finalFailures);
                ChatUtils.sendChatMessages(source, "§e[Build] Completed with " + totalPlaced + "/" + totalBlocks +
                        " blocks. " + finalRemaining + " blocks couldn't be placed, " + repairRemaining
                        + " active-build repairs remain." + (summary.isEmpty() ? "" : (" " + summary)));
                if (!finalFailures.isEmpty()) {
                    LOGGER.info("Remaining placement failures: {}", finalFailures);
                }
            } else {
                ChatUtils.sendChatMessages(source, "§a[Build] Construction complete! " + totalPlaced + " blocks placed.");
            }

            // Navigate the bot outside through the doorway before clearing
            // protection, so that follow/movement won't mine through a wall.
            if (!SkillManager.shouldAbortSkill(bot)) {
                List<DoorwayAccessService.Doorway> exitDoorways = DoorwayAccessService.detectDoorways(
                        world, origin, schematic.sizeX(), schematic.sizeZ(), origin.getY());
                if (!exitDoorways.isEmpty()) {
                    DoorwayAccessService.Doorway exitDoor = exitDoorways.get(0);
                    BlockPos outsidePos = exitDoor.bottomPos().offset(exitDoor.facingOutward(), 2);
                    LOGGER.info("Navigating bot outside through doorway at {} -> {}",
                            exitDoor.bottomPos().toShortString(), outsidePos.toShortString());
                    moveToReachBlock(source, bot, outsidePos);
                }
            }

            LOGGER.info("Schematic build complete: {} placed, {} remaining, repairRemaining={} (aborted={}, timeout={})",
                    totalPlaced, finalRemaining, repairRemaining, report.aborted(), report.timedOut());
            return totalPlaced;
        } finally {
            LOGGER.info("Clearing schematic protection: bot={} schematic={}",
                    bot.getName().getString(),
                    schematic.name());
            ConstructionRepairService.clear(bot.getUuid());
            ConstructionProtectionService.clear(bot.getUuid());
            ScaffoldService.clearScaffoldMemory(bot);
            RoofAccessService.clearRoofPillars(bot);
        }
    }

    private boolean shouldUseCenterPillarPhases(SchematicData schematic, ConstructionPlan plan) {
        if (schematic == null || plan == null || plan.roofPositions().isEmpty()) {
            return false;
        }
        if (!"small_shelter".equalsIgnoreCase(schematic.name())) {
            return false;
        }
        int roofY = plan.roofPositions().get(0).getY();
        for (BlockPos pos : plan.roofPositions()) {
            if (pos.getY() != roofY) {
                return false;
            }
        }
        return true;
    }

    private List<PlacementTarget> orderCentroidPhaseTargets(List<PlacementTarget> orderedTargets, BlockPos center) {
        if (orderedTargets == null || orderedTargets.isEmpty() || center == null) {
            return orderedTargets == null ? List.of() : orderedTargets;
        }

        List<PlacementTarget> corners = orderedTargets.stream()
                .filter(target -> target.kind() == PlacementTarget.TargetKind.CORNER)
                .toList();
        List<PlacementTarget> body = orderedTargets.stream()
                .filter(target -> target.kind() != PlacementTarget.TargetKind.CORNER
                        && target.kind() != PlacementTarget.TargetKind.ROOF)
                .sorted(Comparator
                        .comparingInt((PlacementTarget target) -> centroidBand(target.kind()))
                        .thenComparingDouble(target -> target.pos().getSquaredDistance(center))
                        .thenComparingInt(PlacementTarget::priorityBand))
                .toList();

        List<PlacementTarget> ordered = new ArrayList<>(corners.size() + body.size());
        ordered.addAll(corners);
        ordered.addAll(body);
        return List.copyOf(ordered);
    }

    private int centroidBand(PlacementTarget.TargetKind kind) {
        if (kind == null) {
            return 99;
        }
        return switch (kind) {
            case FOUNDATION -> 0;
            case WALL -> 1;
            case INTERIOR, DECORATION -> 2;
            case DOOR -> 3;
            case ROOF -> 4;
            default -> 5;
        };
    }

    private CenterPillarPhaseResult completeFromCenterPillar(ServerCommandSource source,
                                                             ServerPlayerEntity bot,
                                                             ServerWorld world,
                                                             ConstructionPlan plan,
                                                             List<PlacementTarget> unresolvedTargets,
                                                             Map<BlockPos, BlockState> plannedStates,
                                                             ActiveBuildRepairSession repairSession) {
        if (unresolvedTargets == null || unresolvedTargets.isEmpty() || plan == null) {
            return CenterPillarPhaseResult.empty();
        }

        int roofY = plan.roofPositions().get(0).getY();
        int groundY = plan.minY();
        int placed = 0;
        Map<FailureReason, Integer> failures = new EnumMap<>(FailureReason.class);

        ChatUtils.sendChatMessages(source, "§7[Build] Pillaring from center for remaining blocks...");
        moveToReachBlock(source, bot, plan.center());

        BlockPos perch = RoofAccessService.buildRoofAccessPillar(world, source, bot, roofY, null);
        if (perch == null) {
            int remaining = countRemainingTargets(world, unresolvedTargets);
            if (remaining > 0) {
                failures.put(FailureReason.MOVEMENT_FAILED, remaining);
            }
            return new CenterPillarPhaseResult(0, remaining, Map.copyOf(failures));
        }

        List<PlacementTarget> orderedTargets = unresolvedTargets.stream()
                .sorted(Comparator
                        .comparingInt((PlacementTarget target) -> target.kind() == PlacementTarget.TargetKind.CORNER ? 0 : 1)
                        .thenComparingDouble((PlacementTarget target) -> -target.pos().getSquaredDistance(plan.center()))
                        .thenComparingInt(PlacementTarget::priorityBand))
                .toList();

        int placedFromPerch = 0;
        for (PlacementTarget target : orderedTargets) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            if (!world.getBlockState(target.pos()).equals(target.desiredState())) {
                if (!ConstructionRecoveryService.isWithinReach(bot, target.pos(), REACH_DISTANCE_SQ)) {
                    continue;
                }

                BotActions.PlaceResult result = tryPlaceBlockWithRecovery(
                        null,
                        bot,
                        world,
                        target.pos(),
                        target.desiredState(),
                        plannedStates,
                        null);
                if (result.success()) {
                    placed++;
                    placedFromPerch++;
                    if (repairSession != null) {
                        repairSession.markPlaced(target.pos());
                    }
                }
            }
        }

        LOGGER.info("Center pillar phase: perch={} placed={} remaining={}",
                perch.toShortString(),
                placedFromPerch,
                countRemainingTargets(world, unresolvedTargets));

        RoofAccessService.descendFromRoof(world, source, bot, groundY);
        int cleanupRemoved = RoofAccessService.cleanupAllRoofPillars(bot, world);
        if (cleanupRemoved > 0) {
            ChatUtils.sendChatMessages(source, "§7[Build] Cleaned up " + cleanupRemoved + " center-pillar scaffold blocks.");
        }

        // Patch pass: the scaffold column may have overlapped planned positions
        // (typically the roof block directly above the pillar).  Re-pillar and fill.
        if (!SkillManager.shouldAbortSkill(bot) && cleanupRemoved > 0) {
            List<PlacementTarget> scaffoldGaps = new ArrayList<>();
            for (PlacementTarget t : unresolvedTargets) {
                if (world.getBlockState(t.pos()).isAir() && plannedStates.containsKey(t.pos())) {
                    scaffoldGaps.add(t);
                }
            }
            if (!scaffoldGaps.isEmpty()) {
                ChatUtils.sendChatMessages(source, "§7[Build] Patching " + scaffoldGaps.size() + " scaffold-gap blocks...");
                moveToReachBlock(source, bot, plan.center());
                BlockPos patchPerch = RoofAccessService.buildRoofAccessPillar(world, source, bot, roofY, null);
                if (patchPerch != null) {
                    for (PlacementTarget gap : scaffoldGaps) {
                        if (SkillManager.shouldAbortSkill(bot)) break;
                        if (!world.getBlockState(gap.pos()).isAir()) continue;
                        if (!ConstructionRecoveryService.isWithinReach(bot, gap.pos(), REACH_DISTANCE_SQ)) continue;
                        BotActions.PlaceResult result = tryPlaceBlockWithRecovery(
                                null, bot, world, gap.pos(), gap.desiredState(), plannedStates, null);
                        if (result.success()) {
                            placed++;
                            if (repairSession != null) repairSession.markPlaced(gap.pos());
                        }
                    }
                    RoofAccessService.descendFromRoof(world, source, bot, groundY);
                    RoofAccessService.cleanupAllRoofPillars(bot, world);
                }
            }
        }

        int remaining = countRemainingTargets(world, unresolvedTargets);
        if (remaining > 0) {
            failures.put(FailureReason.MOVEMENT_FAILED, remaining);
        }
        return new CenterPillarPhaseResult(placed, remaining, Map.copyOf(failures));
    }

    private int countRemainingTargets(ServerWorld world, List<PlacementTarget> targets) {
        int remaining = 0;
        for (PlacementTarget target : targets) {
            if (!world.getBlockState(target.pos()).equals(target.desiredState())) {
                remaining++;
            }
        }
        return remaining;
    }

    private List<PlacementTarget> unresolvedStructuralTargets(ServerWorld world, List<PlacementTarget> targets) {
        if (targets == null || targets.isEmpty() || world == null) {
            return List.of();
        }
        List<PlacementTarget> unresolved = new ArrayList<>();
        for (PlacementTarget target : targets) {
            if (target.kind() == PlacementTarget.TargetKind.DOOR) {
                continue;
            }
            if (!world.getBlockState(target.pos()).equals(target.desiredState())) {
                unresolved.add(target);
            }
        }
        return List.copyOf(unresolved);
    }

    private record CenterPillarPhaseResult(int placedCount,
                                           int remainingCount,
                                           Map<FailureReason, Integer> remainingByReason) {
        private static CenterPillarPhaseResult empty() {
            return new CenterPillarPhaseResult(0, 0, Map.of());
        }
    }

    private String formatFailureSummary(Map<FailureReason, Integer> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addBucket(parts, buckets, FailureReason.BLOCKED_BY_SOLID, "blocked");
        addBucket(parts, buckets, FailureReason.NO_SUPPORT, "needs-support");
        addBucket(parts, buckets, FailureReason.NO_LOS, "no-LOS");
        addBucket(parts, buckets, FailureReason.PLACE_REJECTED, "rejected");
        addBucket(parts, buckets, FailureReason.MOVEMENT_FAILED, "move-failed");
        addBucket(parts, buckets, FailureReason.OUT_OF_REACH, "out-of-reach");
        addBucket(parts, buckets, FailureReason.NO_MATERIAL, "no-material");
        addBucket(parts, buckets, FailureReason.TIME_BUDGET, "timeout");
        addBucket(parts, buckets, FailureReason.ABORTED, "aborted");
        addBucket(parts, buckets, FailureReason.UNKNOWN, "other");
        if (parts.isEmpty()) {
            return "";
        }
        return "(reasons: " + String.join(", ", parts) + ")";
    }

    private void addBucket(List<String> parts, Map<FailureReason, Integer> buckets, FailureReason bucket, String label) {
        Integer count = buckets.get(bucket);
        if (count != null && count > 0) {
            parts.add(label + "=" + count);
        }
    }

    /**
     * Move the bot to a new vantage point based on the remaining blocks.
     * Uses PerimeterService corners and centroid cycling for better approach angles.
     */
    private void moveToVantagePosition(ServerCommandSource source,
                                       ServerPlayerEntity bot,
                                       List<BlockPos> buildStations,
                                       Set<BlockPos> remaining,
                                       int attempt) {
        if (remaining == null || remaining.isEmpty() || bot == null || buildStations == null || buildStations.isEmpty()) {
            return;
        }

        double avgX = 0.0;
        double avgZ = 0.0;

        for (BlockPos pos : remaining) {
            avgX += pos.getX();
            avgZ += pos.getZ();
        }
        avgX /= remaining.size();
        avgZ /= remaining.size();

        BlockPos focus = new BlockPos((int) Math.round(avgX), bot.getBlockY(), (int) Math.round(avgZ));
        List<BlockPos> orderedStations = new ArrayList<>(buildStations);
        orderedStations.sort((a, b) -> {
            int byFocus = Double.compare(a.getSquaredDistance(focus), b.getSquaredDistance(focus));
            if (byFocus != 0) {
                return byFocus;
            }
            return Double.compare(a.getSquaredDistance(bot.getBlockPos()), b.getSquaredDistance(bot.getBlockPos()));
        });

        BlockPos targetPos = orderedStations.get(Math.floorMod(attempt, orderedStations.size()));
        LOGGER.debug("Repositioning to build station {} / {} at {} (focus={})",
                Math.floorMod(attempt, orderedStations.size()) + 1,
                orderedStations.size(),
                targetPos.toShortString(),
                focus.toShortString());

        moveToReachBlock(source, bot, targetPos);
    }

    private List<BlockPos> computeBuildStations(Set<BlockPos> plannedPositions) {
        if (plannedPositions == null || plannedPositions.isEmpty()) {
            return List.of();
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        double avgX = 0.0D;
        double avgZ = 0.0D;

        for (BlockPos pos : plannedPositions) {
            if (pos == null) {
                continue;
            }
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            minY = Math.min(minY, pos.getY());
            avgX += pos.getX();
            avgZ += pos.getZ();
        }

        avgX /= plannedPositions.size();
        avgZ /= plannedPositions.size();

        BlockPos center = new BlockPos((int) Math.round(avgX), minY, (int) Math.round(avgZ));
        int radiusX = Math.max(1, (maxX - minX) / 2 + 2);
        int radiusZ = Math.max(1, (maxZ - minZ) / 2 + 2);

        LinkedHashSet<BlockPos> stations = new LinkedHashSet<>();
        stations.addAll(PerimeterService.getCornerPositions(center, radiusX, radiusZ, minY));
        stations.add(new BlockPos((int) Math.round(avgX), minY, minZ - 2));
        stations.add(new BlockPos(maxX + 2, minY, (int) Math.round(avgZ)));
        stations.add(new BlockPos((int) Math.round(avgX), minY, maxZ + 2));
        stations.add(new BlockPos(minX - 2, minY, (int) Math.round(avgZ)));

        return List.copyOf(stations);
    }

    /**
     * Ensure the bot can reach the target block with variable effort based on pass number.
     */
        private ConstructionRecoveryService.RecoveryResult ensureCanReachBlockWithEffort(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            ServerWorld world,
            PlacementTarget target,
            int heightAboveGround,
            int passNumber,
            ScaffoldService.ScaffoldSession scaffoldSession
    ) {
        if (target == null) {
            return ConstructionRecoveryService.RecoveryResult.failure(FailureReason.MOVEMENT_FAILED, false);
        }
        BlockPos targetPos = target.pos();
        int verticalDiff = targetPos.getY() - bot.getBlockPos().getY();
        if (verticalDiff > MAX_SCAFFOLD_HEIGHT + 3
            && !ConstructionRecoveryService.isWithinReach(bot, targetPos, REACH_DISTANCE_SQ)) {
            return ConstructionRecoveryService.RecoveryResult.failure(FailureReason.OUT_OF_REACH, false);
        }

        boolean aggressiveScaffold = target.kind() == PlacementTarget.TargetKind.CORNER
            || target.kind() == PlacementTarget.TargetKind.ROOF;

        return ConstructionRecoveryService.ensureReachWithScaffold(
                source,
                bot,
                world,
            targetPos,
                passNumber,
                REACH_DISTANCE_SQ,
                MAX_SCAFFOLD_HEIGHT,
            scaffoldSession,
            aggressiveScaffold
        );
    }

    /**
     * Move the bot closer to a target block using pathfinding.
     */
    private boolean moveToReachBlock(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        // Find a standable position near the target
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        
        if (plan.isEmpty()) {
            LOGGER.debug("No movement plan found for {}", target.toShortString());
            return false;
        }

        // Use survival-style movement: no teleport override, no snap
        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true, true, false);
        
        if (!result.success()) {
            LOGGER.debug("Movement to {} failed: {}", target.toShortString(), result.detail());
            return false;
        }

        LOGGER.debug("Moved to reach block at {}", target.toShortString());
        return true;
    }


    /**
     * Place a single block, finding a suitable item from inventory.
     * Uses BotActions.tryPlaceBlockAt to get actionable failure reasons.
     */
    private BotActions.PlaceResult tryPlaceBlockWithRecovery(ServerCommandSource source,
                                                            ServerPlayerEntity bot,
                                                            ServerWorld world,
                                                            BlockPos pos,
                                                            BlockState targetState,
                                                            Map<BlockPos, BlockState> planned,
                                                            ScaffoldService.ScaffoldSession scaffoldSession) {
        // Skip door blocks - doors are handled by DoorPlacementService after construction
        if (targetState.getBlock() instanceof net.minecraft.block.DoorBlock) {
            LOGGER.debug("Skipping door block at {} - will be placed by DoorPlacementService", pos.toShortString());
            return new BotActions.PlaceResult(true, null); // Return success to remove from pending
        }
        
        // Skip if already the correct block
        BlockState currentState = world.getBlockState(pos);
        if (currentState.equals(targetState)) {
            LOGGER.debug("Block at {} already correct", pos.toShortString());
            return new BotActions.PlaceResult(true, null);
        }

        // Find matching or substitute block in inventory
        Item targetItem = targetState.getBlock().asItem();
        List<Item> candidates = new ArrayList<>(BlockReplacementService.buildReplacementCandidates(targetState, false));

        // Check if bot has any of the candidate items
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
            LOGGER.warn("No suitable blocks in inventory for {} at {} (wanted {} or fallbacks)", 
                    targetState.getBlock().getName().getString(), pos.toShortString(), targetItem.getName().getString());
            return new BotActions.PlaceResult(false, "no-material");
        }

        // Log reach info before attempting placement
        double distSq = bot.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        boolean inReach = distSq <= REACH_DISTANCE_SQ;
        LOGGER.debug("Placing {} at {} - botPos={}, eyeY={}, dist={}, inReach={}",
                targetState.getBlock().getName().getString(), pos.toShortString(),
            bot.getBlockPos().toShortString(), String.format("%.2f", bot.getEyeY()), String.format("%.2f", Math.sqrt(distSq)), inReach);

        Direction preferredFace = preferredFaceFor(targetState);
        BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, pos, preferredFace, candidates);
        if (!result.success()) {
            LOGGER.warn("BotActions.tryPlaceBlockAt failed for {} at {} (dist={}, inReach={}, reason={})",
                    targetState.getBlock().getName().getString(), pos.toShortString(),
                    String.format("%.2f", Math.sqrt(distSq)), inReach, result.reason());

            // If placement failed due to missing support, attempt a temporary scaffold column below the target and retry.
            if (result.reason() != null && result.reason().startsWith("no-solid-support")) {
                boolean supported = tryCreateTemporarySupportUnder(
                        source, bot, world, pos, planned, scaffoldSession);
                if (supported) {
                    BotActions.PlaceResult retry = BotActions.tryPlaceBlockAt(bot, pos, preferredFace, candidates);
                    if (retry.success()) {
                        LOGGER.debug("Placed {} at {} after temporary support", targetState.getBlock().getName().getString(), pos.toShortString());
                    }
                    return retry;
                }
            }

            // If LOS is the issue, try a few micro-repositions around the target and retry.
            if (result.reason() != null && result.reason().startsWith("no-line-of-sight-to-support") && source != null) {
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos nudge = pos.offset(dir);
                    moveToReachBlock(source, bot, nudge);
                    BotActions.PlaceResult retry = BotActions.tryPlaceBlockAt(bot, pos, preferredFace, candidates);
                    if (retry.success()) {
                        LOGGER.debug("Placed {} at {} after micro-reposition", targetState.getBlock().getName().getString(), pos.toShortString());
                        return retry;
                    }
                }
            }
        } else {
            LOGGER.debug("Placed {} at {}", targetState.getBlock().getName().getString(), pos.toShortString());
        }

        return result;
    }

    private Direction preferredFaceFor(BlockState targetState) {
        if (targetState == null) {
            return Direction.UP;
        }
        if (targetState.contains(Properties.HORIZONTAL_FACING)) {
            Direction d = targetState.get(Properties.HORIZONTAL_FACING);
            if (d != null && d.getAxis().isHorizontal()) {
                return d;
            }
        }
        if (targetState.contains(Properties.FACING)) {
            Direction d = targetState.get(Properties.FACING);
            if (d != null && d.getAxis().isHorizontal()) {
                return d;
            }
        }
        return Direction.UP;
    }

    /**
     * Create a temporary, removable support column below {@code target}.
     * Only uses scaffold blocks, and avoids placing into positions that are part of the schematic plan.
     */
    private boolean tryCreateTemporarySupportUnder(ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos target,
                                                  Map<BlockPos, BlockState> planned,
                                                  ScaffoldService.ScaffoldSession scaffoldSession) {
        if (source == null || bot == null || world == null || target == null) {
            return false;
        }
        Set<BlockPos> plannedNonAir = planned == null ? Set.of() : planned.keySet();
        ConstructionRecoveryService.RecoveryResult result = ConstructionRecoveryService.tryCreateTemporarySupportUnder(
                source,
                bot,
                world,
                target,
                plannedNonAir,
                MAX_SCAFFOLD_HEIGHT,
                SCAFFOLD_BLOCKS,
                scaffoldSession,
                REACH_DISTANCE_SQ
        );
        return result.success();
    }

    /**
     * Get the schematic argument from context.
     * The rawArgs are parsed into the "options" list by modCommandRegistry.
     */
    private String getArgument(SkillContext context) {
        // First try the options list (where parsed tokens go)
        Object opts = context.parameters().get("options");
        if (opts instanceof List<?> list && !list.isEmpty()) {
            // Reconstruct the full argument string from options
            StringBuilder sb = new StringBuilder();
            for (Object val : list) {
                if (val != null) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(val.toString());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        
        // Fall back to raw arguments if present
        Object argObj = context.parameters().get("arguments");
        if (argObj instanceof String s && !s.isEmpty()) {
            return s;
        }
        
        return null;
    }
    
    private int getIntParameter(SkillContext context, String key, int defaultValue) {
        Object value = context.parameters().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
    
    private boolean hasItemInInventory(ServerPlayerEntity bot, Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private SchematicAnchorResolution resolveSchematicAnchor(ServerWorld world, SchematicData schematic, BlockPos requestedCenter) {
        int minOccupiedLocalY = Integer.MAX_VALUE;
        boolean hasGradeLayer = false;

        for (SchematicData.BlockPlacement placement : schematic.blocks()) {
            if (placement == null || placement.relativePos() == null) {
                continue;
            }
            int localY = placement.relativePos().getY();
            minOccupiedLocalY = Math.min(minOccupiedLocalY, localY);
            if (localY == 0) {
                hasGradeLayer = true;
            }
        }

        if (minOccupiedLocalY == Integer.MAX_VALUE) {
            minOccupiedLocalY = 0;
        }

        int anchorLocalY = hasGradeLayer ? 0 : minOccupiedLocalY;
        int floorBlockY = detectPlacementFloorBlockY(world, requestedCenter, DEFAULT_FLOOR_SCAN_DEPTH);
        int requestedAnchorY = requestedCenter != null ? requestedCenter.getY() : floorBlockY + 1;
        int resolvedAnchorWorldY = Math.max(requestedAnchorY, floorBlockY + 1);
        int originY = resolvedAnchorWorldY - anchorLocalY;

        return new SchematicAnchorResolution(originY, floorBlockY, minOccupiedLocalY, anchorLocalY);
    }

    private int detectPlacementFloorBlockY(ServerWorld world, BlockPos center, int scanDepth) {
        if (center == null) {
            return 0;
        }
        if (world == null) {
            return center.getY() - 1;
        }

        int startY = center.getY();
        int minY = Math.max(world.getBottomY(), startY - Math.max(1, scanDepth));
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = new BlockPos(center.getX(), y, center.getZ());
            BlockState state = world.getBlockState(pos);
            if (!world.getFluidState(pos).isEmpty()) {
                continue;
            }
            if (!state.getCollisionShape(world, pos).isEmpty()) {
                return y;
            }
        }
        return startY - 1;
    }

    private record SchematicAnchorResolution(int originY,
                                             int floorBlockY,
                                             int minOccupiedLocalY,
                                             int anchorLocalY) {
    }
}
