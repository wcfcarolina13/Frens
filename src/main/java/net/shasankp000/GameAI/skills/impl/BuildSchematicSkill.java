package net.shasankp000.GameAI.skills.impl;

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
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.schematic.SchematicData;
import net.shasankp000.GameAI.schematic.SchematicReader;
import net.shasankp000.GameAI.schematic.SimpleSchematicBuilder;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService.BuildTarget;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService.ConstructionPlan;
import net.shasankp000.GameAI.services.construction.DoorPlacementService;
import net.shasankp000.GameAI.services.construction.DoorwayAccessService;
import net.shasankp000.GameAI.services.construction.PerimeterService;
import net.shasankp000.GameAI.services.construction.ScaffoldService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
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
 *   2. .nbt files in assets/ai-player/schematics/
 *   3. .nbt files in world/schematics/ folder (planned)
 */
public final class BuildSchematicSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-build-schematic");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks
    private static final int BLOCK_PLACE_DELAY_MS = 50; // Delay between block placements
    private static final long MOVEMENT_TIMEOUT_MS = 10_000L; // Max time to spend reaching a block
    private static final int MAX_SCAFFOLD_HEIGHT = 8; // Maximum height to pillar up

    // Blocks suitable for scaffolding (will be torn down after)
    private static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

    // Track scaffold positions to tear down later
    private final Set<BlockPos> scaffoldPositions = new HashSet<>();

    private enum FailureBucket {
        MOVEMENT,
        BLOCKED,
        NO_SOLID_SUPPORT,
        NO_LINE_OF_SIGHT,
        OCCUPIED,
        PLACE_REJECTED,
        OUT_OF_REACH,
        OTHER
    }

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
        // Clear scaffold tracking from previous builds (use ScaffoldService for new builds)
        scaffoldPositions.clear();
        ScaffoldService.clearScaffoldMemory(bot);
        
        // Generate construction plan with intelligent ordering
        Direction facing = bot.getHorizontalFacing();
        ConstructionPlan plan = ConstructionBlueprintService.planConstruction(schematic, origin, facing);
        
        // Track which blocks still need to be placed (using BuildTarget for ordering info)
        List<BuildTarget> orderedTargets = new ArrayList<>(plan.orderedTargets());
        Set<BlockPos> remainingBlocks = new HashSet<>();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        Map<BlockPos, String> lastFailureReason = new HashMap<>();
        Map<BlockPos, Integer> failCounts = new HashMap<>(); // Track how many times each block failed
        
        // Initialize tracking from the construction plan
        for (BuildTarget target : orderedTargets) {
            remainingBlocks.add(target.worldPos());
            blockStates.put(target.worldPos(), target.state());
        }

        int totalBlocks = remainingBlocks.size();
        int totalPlaced = 0;
        int layerBaseY = origin.getY();
        
        // Log construction plan info
        LOGGER.info("Construction plan: {} blocks, {} corners, {} roof, suggested door side: {}",
                totalBlocks, plan.cornerPositions().size(), plan.roofPositions().size(), plan.suggestedDoorSide());

        ChatUtils.sendChatMessages(source, "§e[Build] Starting construction of " + schematic.name() + 
                " (" + totalBlocks + " blocks)...");

        // Multi-pass building: keep retrying from different angles until complete or we stop making progress.
        // The original 3-pass limit was too eager to give up for real-world builds.
        int maxPasses = Math.max(6, Math.min(12, 3 + (totalBlocks / 120)));
        long startMs = System.currentTimeMillis();
        long maxBuildMs = Math.max(6 * 60_000L, Math.min(20 * 60_000L, 2_000L * maxPasses));
        int consecutiveNoProgressPasses = 0;
        int repositionAttempt = 0;

        for (int pass = 1; pass <= maxPasses && !remainingBlocks.isEmpty(); pass++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                ChatUtils.sendChatMessages(source, "§c[Build] Construction aborted.");
                break;
            }

            if ((System.currentTimeMillis() - startMs) > maxBuildMs) {
                ChatUtils.sendChatMessages(source, "§e[Build] Time budget reached; stopping with "
                        + remainingBlocks.size() + " blocks remaining.");
                break;
            }

            ChatUtils.sendChatMessages(source, "§7[Build] Pass " + pass + "/" + maxPasses + " - " + 
                    remainingBlocks.size() + " blocks remaining...");

                int scaffoldsAtStartOfPass = scaffoldPositions.size();
                int scaffoldsFromService = ScaffoldService.getScaffoldMemory(bot).size();

            // Use construction plan ordering: layer → category → priority, then filter to remaining
            // This ensures corners are placed before walls, foundations before walls, etc.
            List<BlockPos> sortedRemaining = new ArrayList<>();
            for (BuildTarget target : orderedTargets) {
                if (remainingBlocks.contains(target.worldPos())) {
                    sortedRemaining.add(target.worldPos());
                }
            }

            int passPlaced = 0;
            int passSkipped = 0;
            int currentY = -999;

            for (BlockPos worldPos : sortedRemaining) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    break;
                }

                // Skip if already placed (by world state check)
                BlockState targetState = blockStates.get(worldPos);
                BlockState currentState = world.getBlockState(worldPos);
                if (currentState.equals(targetState)) {
                    remainingBlocks.remove(worldPos);
                    lastFailureReason.remove(worldPos);
                    passPlaced++; // Already correct
                    continue;
                }
                if (!currentState.isAir() && !currentState.isReplaceable()) {
                    // Don't mark solid wrong blocks as "done"; report as blocked instead.
                    passSkipped++;
                    lastFailureReason.put(worldPos, "blocked-by=" + currentState.getBlock().getName().getString());
                    continue;
                }

                int blockHeight = worldPos.getY() - layerBaseY;
                
                // Skip blocks that have failed too many times (likely permanently unreachable)
                int priorFails = failCounts.getOrDefault(worldPos, 0);
                if (priorFails >= 4) {
                    passSkipped++;
                    continue; // Already tried 4+ times, skip to avoid infinite loop
                }

                // Log layer progress
                if (worldPos.getY() != currentY) {
                    currentY = worldPos.getY();
                    if (pass == 1) {
                        LOGGER.info("Pass {} - Building layer Y={}, progress: {}/{}", 
                                pass, currentY, totalPlaced + passPlaced, totalBlocks);
                    }
                }

                // Try to reach the block position with increasing effort based on pass number
                boolean canPlace = ensureCanReachBlockWithEffort(source, bot, world, worldPos, blockHeight, pass);
                
                if (!canPlace) {
                    passSkipped++;
                    failCounts.merge(worldPos, 1, Integer::sum);
                    lastFailureReason.put(worldPos, "movement");
                    continue; // Will retry in next pass
                }

                // Try to place the block
                BotActions.PlaceResult placed = tryPlaceBlockWithRecovery(source, bot, world, worldPos, targetState, blockStates);
                if (placed.success()) {
                    passPlaced++;
                    remainingBlocks.remove(worldPos);
                    lastFailureReason.remove(worldPos);
                    failCounts.remove(worldPos);
                } else {
                    passSkipped++;
                    failCounts.merge(worldPos, 1, Integer::sum);
                    if (placed.reason() != null && !placed.reason().isBlank()) {
                        lastFailureReason.put(worldPos, placed.reason());
                    }
                }

                // Small delay to prevent server overload
                sleepQuiet(BLOCK_PLACE_DELAY_MS);
            }

            totalPlaced += passPlaced;
            LOGGER.info("Pass {} complete: {} placed, {} skipped, {} remaining", 
                    pass, passPlaced, passSkipped, remainingBlocks.size());

            // Check if we made progress (blocks placed or scaffolds added from either system)
            int scaffoldsNow = scaffoldPositions.size() + ScaffoldService.getScaffoldMemory(bot).size();
            boolean madeProgress = passPlaced > 0 || scaffoldsNow > (scaffoldsAtStartOfPass + scaffoldsFromService);
            if (!madeProgress) {
                consecutiveNoProgressPasses++;
            } else {
                consecutiveNoProgressPasses = 0;
            }

            // If we made no progress this pass, try moving to a different vantage point.
            if (!madeProgress && !remainingBlocks.isEmpty() && pass < maxPasses) {
                ChatUtils.sendChatMessages(source, "§7[Build] Repositioning for better access...");
                moveToVantagePosition(source, bot, remainingBlocks, repositionAttempt);
                repositionAttempt++;
            }

            // If we repeatedly fail to place anything, stop rather than looping forever.
            if (consecutiveNoProgressPasses >= 4 && !remainingBlocks.isEmpty()) {
                ChatUtils.sendChatMessages(source, "§e[Build] No progress after multiple passes; stopping.");
                break;
            }
        }

        // Tear down any scaffolding we built
        // Tear down any scaffolding we built (from both old system and ScaffoldService)
        int tornDown = tearDownScaffolding(bot, world);
        int tornDownFromService = ScaffoldService.teardownTrackedScaffolds(bot);
        int totalTornDown = tornDown + tornDownFromService;
        if (totalTornDown > 0) {
            LOGGER.info("Removed {} scaffold blocks ({} local, {} from service)", 
                    totalTornDown, tornDown, tornDownFromService);
            ChatUtils.sendChatMessages(source, "§7[Build] Cleaned up " + totalTornDown + " scaffold blocks.");
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
        int finalRemaining = remainingBlocks.size();
        if (finalRemaining > 0) {
            Map<FailureBucket, Integer> buckets = summarizeRemainingFailures(remainingBlocks, lastFailureReason);
            String summary = formatFailureSummary(buckets);
            ChatUtils.sendChatMessages(source, "§e[Build] Completed with " + totalPlaced + "/" + totalBlocks +
                    " blocks. " + finalRemaining + " blocks couldn't be placed." + (summary.isEmpty() ? "" : (" " + summary)));
            if (!buckets.isEmpty()) {
                LOGGER.info("Remaining placement failure buckets: {}", buckets);
            }
        } else {
            ChatUtils.sendChatMessages(source, "§a[Build] Construction complete! " + totalPlaced + " blocks placed.");
        }

        LOGGER.info("Schematic build complete: {} placed, {} remaining", totalPlaced, finalRemaining);
        return totalPlaced;
    }

    private Map<FailureBucket, Integer> summarizeRemainingFailures(Set<BlockPos> remaining, Map<BlockPos, String> lastReason) {
        Map<FailureBucket, Integer> buckets = new HashMap<>();
        if (remaining == null || remaining.isEmpty()) {
            return buckets;
        }
        for (BlockPos pos : remaining) {
            FailureBucket b = bucketizeReason(lastReason != null ? lastReason.get(pos) : null);
            buckets.merge(b, 1, Integer::sum);
        }
        return buckets;
    }

    private String formatFailureSummary(Map<FailureBucket, Integer> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addBucket(parts, buckets, FailureBucket.BLOCKED, "blocked");
        addBucket(parts, buckets, FailureBucket.NO_SOLID_SUPPORT, "needs-support");
        addBucket(parts, buckets, FailureBucket.NO_LINE_OF_SIGHT, "no-LOS");
        addBucket(parts, buckets, FailureBucket.OCCUPIED, "occupied");
        addBucket(parts, buckets, FailureBucket.PLACE_REJECTED, "rejected");
        addBucket(parts, buckets, FailureBucket.MOVEMENT, "move-failed");
        addBucket(parts, buckets, FailureBucket.OUT_OF_REACH, "out-of-reach");
        addBucket(parts, buckets, FailureBucket.OTHER, "other");
        if (parts.isEmpty()) {
            return "";
        }
        return "(reasons: " + String.join(", ", parts) + ")";
    }

    private void addBucket(List<String> parts, Map<FailureBucket, Integer> buckets, FailureBucket bucket, String label) {
        Integer count = buckets.get(bucket);
        if (count != null && count > 0) {
            parts.add(label + "=" + count);
        }
    }

    private FailureBucket bucketizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return FailureBucket.OTHER;
        }
        String r = reason.toLowerCase();
        if (r.equals("movement") || r.contains("movement")) {
            return FailureBucket.MOVEMENT;
        }
        if (r.startsWith("blocked-by=")) {
            return FailureBucket.BLOCKED;
        }
        if (r.startsWith("no-solid-support")) {
            return FailureBucket.NO_SOLID_SUPPORT;
        }
        if (r.startsWith("no-line-of-sight-to-support")) {
            return FailureBucket.NO_LINE_OF_SIGHT;
        }
        if (r.startsWith("occupied=")) {
            return FailureBucket.OCCUPIED;
        }
        if (r.startsWith("place-rejected")) {
            return FailureBucket.PLACE_REJECTED;
        }
        if (r.startsWith("out-of-reach")) {
            return FailureBucket.OUT_OF_REACH;
        }
        return FailureBucket.OTHER;
    }

    /**
     * Move the bot to the average position of remaining blocks for better access.
     */
    private void moveToAveragePosition(ServerCommandSource source, ServerPlayerEntity bot, Set<BlockPos> remaining) {
        if (remaining.isEmpty()) return;

        // Calculate centroid of remaining blocks
        double avgX = 0, avgZ = 0;
        int minY = Integer.MAX_VALUE;
        for (BlockPos pos : remaining) {
            avgX += pos.getX();
            avgZ += pos.getZ();
            minY = Math.min(minY, pos.getY());
        }
        avgX /= remaining.size();
        avgZ /= remaining.size();

        // Try to move to a position near the centroid at ground level
        BlockPos targetPos = new BlockPos((int) avgX, minY, (int) avgZ);
        moveToReachBlock(source, bot, targetPos);
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
    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot, 
                                                   ServerWorld world, BlockPos target, int heightAboveGround, int passNumber) {
        // Already in reach?
        if (isWithinReach(bot, target)) {
            return true;
        }

        BlockPos botPos = bot.getBlockPos();
        double horizontalDistSq = Math.pow(target.getX() - botPos.getX(), 2) + Math.pow(target.getZ() - botPos.getZ(), 2);
        int verticalDiff = target.getY() - botPos.getY();

        // All passes can use scaffolding when needed
        // Pass 1: Movement + scaffolding for clearly elevated blocks
        // Pass 2+: More aggressive scaffolding and repositioning

        // Try horizontal movement first
        if (horizontalDistSq > REACH_DISTANCE_SQ) {
            // Find a good approach position
            BlockPos approachPos = findApproachPosition(world, target, botPos);
            if (approachPos != null) {
                boolean moved = moveToReachBlock(source, bot, approachPos);
                if (moved && isWithinReach(bot, target)) {
                    return true;
                }
            } else {
                moveToReachBlock(source, bot, target);
                if (isWithinReach(bot, target)) {
                    return true;
                }
            }
        }

        // Try scaffolding for elevated blocks on ALL passes
        // verticalDiff > 2 means the block is more than 2 above us (out of normal reach)
        if (verticalDiff > 2 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT) {
            LOGGER.debug("Attempting scaffold for {} (vertDiff={}, height={})", 
                    target.toShortString(), verticalDiff, heightAboveGround);
            
            // Move under/near the target first
            BlockPos nearTarget = new BlockPos(target.getX(), botPos.getY(), target.getZ());
            if (!isWithinReachXZ(bot, nearTarget, 2.0)) {
                boolean moved = moveToReachBlock(source, bot, nearTarget);
                LOGGER.debug("Moved to near target: {}", moved);
            }
            
            // Pillar up - need to reach the block from below
            // Bot's eye is at Y+1.62, reach is ~4.5 blocks
            // So to reach a block at targetY, we need bot at approximately targetY-3
            int currentBotY = bot.getBlockPos().getY();
            int optimalY = target.getY() - 2; // Want to be about 2 blocks below target
            int stepsNeeded = Math.max(0, optimalY - currentBotY);
            
            if (stepsNeeded > 0 && stepsNeeded <= MAX_SCAFFOLD_HEIGHT) {
                LOGGER.debug("Pillaring up {} blocks to reach {} (using ScaffoldService)", 
                        stepsNeeded, target.toShortString());
                // Use ScaffoldService for pillar with tracking for cleanup
                boolean pillared = ScaffoldService.pillarUp(bot, stepsNeeded, true);
                if (pillared) {
                    LOGGER.debug("Pillared successfully, checking reach");
                    if (isWithinReach(bot, target)) {
                        return true;
                    } else {
                        LOGGER.debug("Still not in reach after pillaring");
                    }
                } else {
                    // Fallback to local pillarUp if ScaffoldService fails
                    LOGGER.debug("ScaffoldService pillar failed, trying local pillarUp");
                    pillared = pillarUp(bot, world, stepsNeeded);
                    if (pillared && isWithinReach(bot, target)) {
                        return true;
                    }
                }
            } else if (stepsNeeded == 0) {
                // Already at good height, just check reach
                if (isWithinReach(bot, target)) {
                    return true;
                }
            }
        }

        // Pass 3: Try approaching from different directions
        if (passNumber >= 3) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos sidePos = target.offset(dir, 2).withY(botPos.getY());
                moveToReachBlock(source, bot, sidePos);
                if (isWithinReach(bot, target)) {
                    return true;
                }
            }
        }

        return isWithinReach(bot, target);
    }

    /**
     * Find a good approach position to reach a target block.
     */
    private BlockPos findApproachPosition(ServerWorld world, BlockPos target, BlockPos current) {
        // Try positions around the target at ground level
        int targetGroundY = target.getY() - 1; // Stand next to the block
        
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(dir).withY(targetGroundY);
            BlockPos below = candidate.down();
            BlockPos above = candidate.up();
            
            // Check if this is a standable position
            if (!world.getBlockState(below).isAir() && 
                world.getBlockState(candidate).isAir() && 
                world.getBlockState(above).isAir()) {
                return candidate;
            }
        }
        return null;
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
     * Pillar up by the specified number of blocks.
     * Uses jump-place technique to build a scaffold tower.
     */
    private boolean pillarUp(ServerPlayerEntity bot, ServerWorld world, int steps) {
        if (steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return steps <= 0; // 0 steps = success, too many = fail
        }

        LOGGER.debug("Pillaring up {} blocks", steps);

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            // Stop horizontal movement
            BotActions.stop(bot);
            sleepQuiet(60L);

            // Wait to be on ground
            if (!waitForOnGround(bot, 1200L)) {
                LOGGER.warn("Not on ground for pillar step {}", i);
                return false;
            }

            BlockPos targetPos = bot.getBlockPos();
            double startY = bot.getY();

            // Jump
            BotActions.jump(bot);

            // Wait until airborne
            if (!waitForAirborne(bot, 800L)) {
                LOGGER.warn("Failed to become airborne for pillar step {}", i);
                return false;
            }

            // Wait for good placement window (near apex of jump)
            waitForJumpPlaceWindow(bot, startY, 600L);

            // Clear any existing block if needed
            BlockState existing = world.getBlockState(targetPos);
            if (!existing.isAir() && existing.isReplaceable()) {
                // Can place through it
            } else if (!existing.isAir()) {
                LOGGER.warn("Cannot pillar at {} - solid block exists", targetPos.toShortString());
                return false;
            }

            // Place scaffold block
            boolean placed = false;
            for (int attempt = 0; attempt < 3 && !placed; attempt++) {
                placed = BotActions.placeBlockAt(bot, targetPos, Direction.UP, SCAFFOLD_BLOCKS);
                if (!placed) sleepQuiet(50L);
            }

            if (!placed) {
                LOGGER.warn("Failed to place scaffold block at {}", targetPos.toShortString());
                return false;
            }

            // Track scaffold for later removal
            scaffoldPositions.add(targetPos.toImmutable());

            // Wait for bot to land on the new block
            waitForYIncrease(bot, targetPos.getY(), 1000L);
        }

        return true;
    }

    /**
     * Tear down scaffold blocks we placed during building.
     */
    private int tearDownScaffolding(ServerPlayerEntity bot, ServerWorld world) {
        if (scaffoldPositions.isEmpty()) {
            return 0;
        }

        int removed = 0;
        // Remove from top to bottom
        List<BlockPos> sorted = new ArrayList<>(scaffoldPositions);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        for (BlockPos pos : sorted) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue; // Already gone
            }

            // Check if we're standing on this block - need to drop first
            BlockPos botPos = bot.getBlockPos();
            if (botPos.equals(pos.up())) {
                // We're standing on this scaffold - let gravity work
                sleepQuiet(100L);
            }

            // Break the block if we can reach it
            if (isWithinReach(bot, pos)) {
                LookController.faceBlock(bot, pos);
                BlockState stateNow = world.getBlockState(pos);
                boolean isScaffoldType = SCAFFOLD_BLOCKS.contains(stateNow.getBlock().asItem());
                // BotActions.breakBlockAt is intentionally disabled; we only tear down our own scaffold types.
                boolean broke = isScaffoldType && world.breakBlock(pos, true);
                if (broke) {
                    removed++;
                    sleepQuiet(BLOCK_PLACE_DELAY_MS);
                }
            }
        }

        scaffoldPositions.clear();
        return removed;
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
                                                            Map<BlockPos, BlockState> planned) {
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
                boolean supported = tryCreateTemporarySupportUnder(source, bot, world, pos, planned);
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
                                                  Map<BlockPos, BlockState> planned) {
        if (source == null || bot == null || world == null || target == null) {
            return false;
        }

        BlockPos cursor = target.down();
        List<BlockPos> toFill = new ArrayList<>();
        for (int i = 0; i < MAX_SCAFFOLD_HEIGHT; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty()) {
                break; // Found solid foundation
            }
            // Avoid placing scaffolds into cells that the schematic intends to be non-air.
            if (planned != null && planned.containsKey(cursor)) {
                return false;
            }
            toFill.add(cursor);
            cursor = cursor.down();
        }

        if (toFill.isEmpty()) {
            return false;
        }

        // If we never found a foundation, don't build a floating column.
        BlockState foundation = world.getBlockState(toFill.get(toFill.size() - 1).down());
        if (foundation.isAir() || !foundation.getFluidState().isEmpty()) {
            return false;
        }

        // Place from bottom to top so each placed block supports the next.
        for (int i = toFill.size() - 1; i >= 0; i--) {
            BlockPos pos = toFill.get(i);
            BlockState existing = world.getBlockState(pos);
            if (!existing.isAir() && !existing.isReplaceable() && existing.getFluidState().isEmpty()) {
                continue;
            }
            boolean moved = ensureCanReachBlockWithEffort(source, bot, world, pos, 0, 2);
            if (!moved) {
                return false;
            }
            BotActions.PlaceResult placed = BotActions.tryPlaceBlockAt(bot, pos, Direction.UP, SCAFFOLD_BLOCKS);
            if (!placed.success()) {
                return false;
            }
            scaffoldPositions.add(pos.toImmutable());
            sleepQuiet(BLOCK_PLACE_DELAY_MS);
        }

        return true;
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
     * Check if a position is within horizontal reach (XZ plane).
     */
    private boolean isWithinReachXZ(ServerPlayerEntity bot, BlockPos pos, double maxDist) {
        double dx = pos.getX() + 0.5 - bot.getX();
        double dz = pos.getZ() + 0.5 - bot.getZ();
        return (dx * dx + dz * dz) <= maxDist * maxDist;
    }

    /**
     * Wait until the bot is on the ground.
     */
    private boolean waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) return false;
            if (bot.isOnGround() || bot.isClimbing() || bot.isTouchingWater() || bot.isInLava()) {
                return true;
            }
            sleepQuiet(25L);
        }
        return bot.isOnGround();
    }

    /**
     * Wait until the bot is airborne (not on ground).
     */
    private boolean waitForAirborne(ServerPlayerEntity bot, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) return false;
            if (!bot.isOnGround()) {
                return true;
            }
            sleepQuiet(25L);
        }
        return !bot.isOnGround();
    }

    /**
     * Wait for the optimal placement window during a jump (near apex).
     */
    private void waitForJumpPlaceWindow(ServerPlayerEntity bot, double startY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            double y = bot.getY();
            double vy = bot.getVelocity().y;
            // Wait until we've risen at least 0.4 blocks and velocity is slowing
            if (y >= startY + 0.4 && vy < 0.15) {
                return;
            }
            sleepQuiet(20L);
        }
    }

    /**
     * Wait for the bot's Y position to increase (landed on placed block).
     */
    private void waitForYIncrease(ServerPlayerEntity bot, int targetY, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) return;
            if (bot.getBlockPos().getY() > targetY) {
                return;
            }
            sleepQuiet(25L);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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
