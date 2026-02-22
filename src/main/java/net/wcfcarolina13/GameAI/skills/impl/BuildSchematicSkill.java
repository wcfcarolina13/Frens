package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.schematic.SchematicData;
import net.wcfcarolina13.GameAI.schematic.SchematicReader;
import net.wcfcarolina13.GameAI.schematic.SimpleSchematicBuilder;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionBlueprintService;
import net.wcfcarolina13.GameAI.services.construction.ConstructionBlueprintService.ConstructionPlan;
import net.wcfcarolina13.GameAI.services.construction.DoorPlacementService;
import net.wcfcarolina13.GameAI.services.construction.DoorwayAccessService;
import net.wcfcarolina13.GameAI.services.construction.PerimeterService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    private static final int BLOCK_PLACE_DELAY_MS = 50; // Delay between block placements
    private static final int MAX_SCAFFOLD_HEIGHT = 8; // Maximum height to pillar up

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

        // Calculate origin - use exact target position from context if provided (from build_look command)
        // Otherwise fall back to centering on bot's position
        BlockPos origin;
        if (context.parameters().containsKey("targetX") && 
            context.parameters().containsKey("targetY") && 
            context.parameters().containsKey("targetZ")) {
            // Target position is center of preview (with forward offset applied)
            int targetX = getIntParameter(context, "targetX", bot.getBlockPos().getX());
            int targetY = getIntParameter(context, "targetY", bot.getBlockPos().getY());
            int targetZ = getIntParameter(context, "targetZ", bot.getBlockPos().getZ());
            BlockPos centerPos = new BlockPos(targetX, targetY, targetZ);
            
            // Calculate origin (corner) from center using actual schematic dimensions
            int offsetX = -schematic.sizeX() / 2;
            int offsetZ = -schematic.sizeZ() / 2;
            origin = centerPos.add(offsetX, 0, offsetZ);
            
            // Bot walks to a corner for better building access
            BlockPos buildCorner = origin.add(schematic.sizeX() - 1, 0, 0);
            
            LOGGER.info("Building centered at {}, origin at {}, bot moving to corner at {}", 
                centerPos.toShortString(), origin.toShortString(), buildCorner.toShortString());
                
            // Move bot to corner before building (no teleport, no snap for survival-style movement)
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, buildCorner, MovementService.MovementOptions.skillLoot());
            if (plan.isPresent()) {
                MovementService.execute(source, bot, plan.get(), false, true, true, false);
            }
        } else {
            // Fall back to bot's current position as center
            BlockPos centerPos = bot.getBlockPos();
            int offsetX = -schematic.sizeX() / 2;
            int offsetZ = -schematic.sizeZ() / 2;
            origin = centerPos.add(offsetX, 0, offsetZ);
            LOGGER.info("Building with bot position as center, origin at: {}", origin.toShortString());
        }
        
        // Build the schematic
        ChatUtils.sendSystemMessage(source, "Building schematic '" + schematic.name() + "' (" + schematic.blockCount() + " blocks) at " + origin.toShortString() + "...");
        int blocksPlaced = buildSchematic(source, bot, world, schematic, origin);

        if (blocksPlaced > 0) {
            return SkillExecutionResult.success("Built schematic '" + schematic.name() + "': " + blocksPlaced + "/" + schematic.blockCount() + " blocks placed.");
        } else {
            return SkillExecutionResult.failure("Failed to place any blocks from schematic '" + schematic.name() + "'. Check bot has materials and is in a clear area.");
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
                materials.merge(item, 1, Integer::sum);
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
                available.merge(item, stack.getCount(), Integer::sum);
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

        // Generate construction plan with intelligent ordering
        Direction facing = bot.getHorizontalFacing();
        ConstructionPlan plan = ConstructionBlueprintService.planConstruction(schematic, origin, facing);

        List<PlacementTarget> orderedTargets = ConstructionBlueprintService.toPlacementTargets(plan);
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        Set<BlockPos> remainingForVantage = new HashSet<>();
        for (PlacementTarget target : orderedTargets) {
            blockStates.put(target.pos(), target.desiredState());
            remainingForVantage.add(target.pos());
        }

        int totalBlocks = orderedTargets.size();

        // Log construction plan info
        LOGGER.info("Construction plan: {} blocks, {} corners, {} roof, suggested door side: {}",
                totalBlocks, plan.cornerPositions().size(), plan.roofPositions().size(), plan.suggestedDoorSide());

        ChatUtils.sendChatMessages(source, "§e[Build] Starting construction of " + schematic.name() + 
                " (" + totalBlocks + " blocks)...");

        int maxPasses = Math.max(6, Math.min(12, 3 + (totalBlocks / 120)));
        long maxBuildMs = Math.max(6 * 60_000L, Math.min(20 * 60_000L, 2_000L * maxPasses));
        int[] repositionAttempt = new int[]{0};
        ScaffoldService.ScaffoldSession scaffoldSession = ScaffoldService.beginSession(bot);

        ConstructionTaskSpec spec = new ConstructionTaskSpec(
                "build:" + schematic.name(),
                world,
                bot,
                source,
                orderedTargets,
                new ExecutionPolicy(maxPasses, 3, 2, maxBuildMs),
                new ConstructionTaskSpec.SupportPolicy(true, true, MAX_SCAFFOLD_HEIGHT),
                (target, pass) -> {
                    int blockHeight = target.pos().getY() - origin.getY();
                    return ensureCanReachBlockWithEffort(source, bot, world, target.pos(), blockHeight, pass, scaffoldSession);
                },
                (target, pass) -> {
                    BotActions.PlaceResult placed = tryPlaceBlockWithRecovery(
                            source, bot, world, target.pos(), target.desiredState(), blockStates, scaffoldSession);
                    if (placed.success()) {
                        remainingForVantage.remove(target.pos());
                        return ConstructionTaskSpec.PlacementOutcome.ok();
                    }
                    FailureReason reason = FailureReason.fromPlaceReason(placed.reason());
                    return ConstructionTaskSpec.PlacementOutcome.fail(reason);
                },
                progress -> {
                    remainingForVantage.removeIf(pos -> {
                        BlockState desired = blockStates.get(pos);
                        return desired != null && world.getBlockState(pos).equals(desired);
                    });
                    ChatUtils.sendChatMessages(source, "§7[Build] Pass " + progress.passNumber() + "/" + maxPasses
                            + " complete - " + progress.placedThisPass() + " placed, "
                            + progress.remaining() + " remaining.");
                },
                (progress, streak) -> {
                    if (!remainingForVantage.isEmpty()) {
                        ChatUtils.sendChatMessages(source, "§7[Build] Repositioning for better access...");
                        moveToVantagePosition(source, bot, remainingForVantage, repositionAttempt[0]);
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

        // Clear doorway access paths and place doors using DoorwayAccessService and DoorPlacementService
        if (!SkillManager.shouldAbortSkill(bot)) {
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
        }

        // Final status
        int finalRemaining = report.remainingCount();
        if (finalRemaining > 0) {
            String summary = formatFailureSummary(report.remainingByReason());
            ChatUtils.sendChatMessages(source, "§e[Build] Completed with " + report.placedCount() + "/" + totalBlocks +
                    " blocks. " + finalRemaining + " blocks couldn't be placed." + (summary.isEmpty() ? "" : (" " + summary)));
            if (!report.remainingByReason().isEmpty()) {
                LOGGER.info("Remaining placement failures: {}", report.remainingByReason());
            }
        } else {
            ChatUtils.sendChatMessages(source, "§a[Build] Construction complete! " + report.placedCount() + " blocks placed.");
        }

        LOGGER.info("Schematic build complete: {} placed, {} remaining (aborted={}, timeout={})",
                report.placedCount(), finalRemaining, report.aborted(), report.timedOut());
        return report.placedCount();
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
    private void moveToVantagePosition(ServerCommandSource source, ServerPlayerEntity bot, Set<BlockPos> remaining, int attempt) {
        if (remaining == null || remaining.isEmpty() || bot == null) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        double avgX = 0.0;
        double avgZ = 0.0;

        for (BlockPos pos : remaining) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            minY = Math.min(minY, pos.getY());
            avgX += pos.getX();
            avgZ += pos.getZ();
        }
        avgX /= remaining.size();
        avgZ /= remaining.size();
        
        // Use PerimeterService to get proper corner positions with offset
        BlockPos center = new BlockPos((int) Math.round(avgX), minY, (int) Math.round(avgZ));
        int radiusX = (maxX - minX) / 2 + 2; // +2 for standing offset
        int radiusZ = (maxZ - minZ) / 2 + 2;
        List<BlockPos> cornerPositions = PerimeterService.getCornerPositions(center, radiusX, radiusZ, minY);

        // Cycle through corner positions (0-3), then centroid (4), then edge centers (5-8)
        int mode = Math.floorMod(attempt, 9);
        BlockPos targetPos;
        
        if (mode < 4 && mode < cornerPositions.size()) {
            // Use PerimeterService corner positions
            targetPos = cornerPositions.get(mode);
            LOGGER.debug("Repositioning to corner {} at {}", mode, targetPos.toShortString());
        } else if (mode == 4) {
            // Centroid
            targetPos = center;
            LOGGER.debug("Repositioning to centroid at {}", targetPos.toShortString());
        } else {
            // Edge centers (5-8)
            int edgeMode = mode - 5;
            int tx, tz;
            switch (edgeMode) {
                case 0 -> { tx = (int) Math.round(avgX); tz = minZ - 2; } // North edge
                case 1 -> { tx = maxX + 2; tz = (int) Math.round(avgZ); } // East edge
                case 2 -> { tx = (int) Math.round(avgX); tz = maxZ + 2; } // South edge
                default -> { tx = minX - 2; tz = (int) Math.round(avgZ); } // West edge
            }
            targetPos = new BlockPos(tx, minY, tz);
            LOGGER.debug("Repositioning to edge {} at {}", edgeMode, targetPos.toShortString());
        }

        moveToReachBlock(source, bot, targetPos);
    }

    /**
     * Ensure the bot can reach the target block with variable effort based on pass number.
     */
    private ConstructionRecoveryService.RecoveryResult ensureCanReachBlockWithEffort(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            ServerWorld world,
            BlockPos target,
            int heightAboveGround,
            int passNumber,
            ScaffoldService.ScaffoldSession scaffoldSession
    ) {
        if (heightAboveGround > MAX_SCAFFOLD_HEIGHT + 3 && !isWithinReach(bot, target)) {
            return ConstructionRecoveryService.RecoveryResult.failure(FailureReason.OUT_OF_REACH, false);
        }

        int scaffoldCap = heightAboveGround <= MAX_SCAFFOLD_HEIGHT ? MAX_SCAFFOLD_HEIGHT : 0;
        return ConstructionRecoveryService.ensureReachWithScaffold(
                source,
                bot,
                world,
                target,
                passNumber,
                REACH_DISTANCE_SQ,
                scaffoldCap,
                scaffoldSession
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
        List<Item> candidates = new ArrayList<>();
        candidates.add(targetItem);
        
        // Add fallback blocks for common materials
        addFallbacks(candidates, targetItem);

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
        Vec3d eye = bot.getEyePos();
        double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
        boolean inReach = distSq <= REACH_DISTANCE_SQ;
        LOGGER.debug("Placing {} at {} - botPos={}, eyeY={}, dist={}, inReach={}",
                targetState.getBlock().getName().getString(), pos.toShortString(),
                bot.getBlockPos().toShortString(), String.format("%.2f", eye.y), String.format("%.2f", Math.sqrt(distSq)), inReach);

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
     * Add fallback block options for common materials.
     */
    private void addFallbacks(List<Item> candidates, Item primary) {
        // Wood substitutions
        if (primary == Items.OAK_PLANKS) {
            candidates.add(Items.SPRUCE_PLANKS);
            candidates.add(Items.BIRCH_PLANKS);
            candidates.add(Items.JUNGLE_PLANKS);
            candidates.add(Items.ACACIA_PLANKS);
            candidates.add(Items.DARK_OAK_PLANKS);
        }
        if (primary == Items.OAK_LOG) {
            candidates.add(Items.SPRUCE_LOG);
            candidates.add(Items.BIRCH_LOG);
            candidates.add(Items.JUNGLE_LOG);
        }
        // Stone substitutions
        if (primary == Items.COBBLESTONE) {
            candidates.add(Items.STONE);
            candidates.add(Items.COBBLED_DEEPSLATE);
            candidates.add(Items.ANDESITE);
        }
        // Universal fallbacks
        candidates.add(Items.DIRT);
        candidates.add(Items.COBBLESTONE);
    }

    /**
     * Check if a position is within the bot's reach (3D distance).
     */
    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d eye = bot.getEyePos();
        double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
        return distSq <= REACH_DISTANCE_SQ;
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
}
