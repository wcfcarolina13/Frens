package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.schematic.SchematicData;
import net.shasankp000.GameAI.schematic.SimpleSchematicBuilder;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService.BuildTarget;
import net.shasankp000.GameAI.services.construction.ConstructionBlueprintService.ConstructionPlan;
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
 * Detects village bounds, plans wall layout, then builds corners, wall sections,
 * and gatehouses sequentially using the defensive structure schematics.
 *
 * Usage:
 *   /bot fortify              — auto-detect village, build wall
 *   /bot fortify dry_run      — preview layout only
 *   /bot fortify radius=25    — explicit radius
 *   /bot fortify center=100,200 — explicit center XZ
 *   /bot fortify gates=2      — number of gatehouse sides (default 1)
 */
public final class FortifyVillageSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-village");
    private static final double REACH_DISTANCE_SQ = 20.25D;
    private static final int BLOCK_PLACE_DELAY_MS = 50;
    private static final int MAX_SCAFFOLD_HEIGHT = 8;
    private static final long MAX_BUILD_TIME_MS = 30 * 60_000L; // 30 minute cap
    private static final int MAX_PASSES_PER_SEGMENT = 4;

    private static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

    // Building material fallback lists for wall schematics
    private static final List<Item> STONE_BRICK_FALLBACKS = List.of(
            Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE,
            Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.DIRT
    );
    private static final List<Item> OAK_LOG_FALLBACKS = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.COBBLESTONE, Items.DIRT
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

        // Parse arguments
        String args = getArgument(context);
        boolean dryRun = false;
        int explicitRadius = -1;
        BlockPos explicitCenter = null;
        int gateCount = 1;

        if (args != null && !args.isBlank()) {
            for (String token : args.split("\\s+")) {
                String lower = token.toLowerCase();
                if (lower.equals("dry_run") || lower.equals("dryrun") || lower.equals("preview")) {
                    dryRun = true;
                } else if (lower.startsWith("radius=")) {
                    try {
                        explicitRadius = Integer.parseInt(lower.substring(7));
                    } catch (NumberFormatException ignored) {}
                } else if (lower.startsWith("center=")) {
                    String[] parts = lower.substring(7).split(",");
                    if (parts.length == 2) {
                        try {
                            int cx = Integer.parseInt(parts[0]);
                            int cz = Integer.parseInt(parts[1]);
                            int cy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                            explicitCenter = new BlockPos(cx, cy, cz);
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (lower.startsWith("gates=")) {
                    try {
                        gateCount = Math.max(0, Math.min(4, Integer.parseInt(lower.substring(6))));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Detect village bounds
        ChatUtils.sendChatMessages(source, "§e[Fortify] Scanning for village...");
        BlockPos searchCenter = explicitCenter != null ? explicitCenter : bot.getBlockPos();
        VillageBounds bounds = VillageFortificationLayoutService.detectVillageBounds(world, searchCenter, 64);

        if (bounds.foundPOIs() == 0 && explicitRadius <= 0) {
            return SkillExecutionResult.failure("No village detected nearby. Use radius= to specify manually.");
        }

        int radiusX = explicitRadius > 0 ? explicitRadius : bounds.radiusX();
        int radiusZ = explicitRadius > 0 ? explicitRadius : bounds.radiusZ();
        BlockPos center = explicitCenter != null ? explicitCenter : bounds.center();

        // Determine gatehouse sides
        EnumSet<CardinalSide> gateSides = EnumSet.noneOf(CardinalSide.class);
        CardinalSide[] sideOrder = { CardinalSide.SOUTH, CardinalSide.EAST, CardinalSide.NORTH, CardinalSide.WEST };
        for (int i = 0; i < Math.min(gateCount, 4); i++) {
            gateSides.add(sideOrder[i]);
        }

        // Generate layout
        FortificationLayout layout = VillageFortificationLayoutService.generateLayout(
                world, center, radiusX, radiusZ, gateSides
        );

        String planDesc = VillageFortificationLayoutService.describePlan(layout);
        ChatUtils.sendChatMessages(source, "§a[Fortify] " + planDesc);
        LOGGER.info("Fortification layout: {}", planDesc);

        if (dryRun) {
            // Report each segment position
            for (WallSegment seg : layout.segments()) {
                ChatUtils.sendSystemMessage(source, String.format("§7  %s #%d (%s) at %s, rot=%d",
                        seg.type(), seg.segmentIndex(), seg.side(),
                        seg.origin().toShortString(), seg.quarterTurns()));
            }
            return SkillExecutionResult.success("Dry run complete. " + planDesc);
        }

        // Material check
        int buildBlocks = countBuildingBlocks(bot);
        ChatUtils.sendSystemMessage(source, "§7Bot has " + buildBlocks + " building blocks.");
        if (buildBlocks == 0) {
            return SkillExecutionResult.failure("No building blocks in inventory. Give me stone bricks, cobblestone, or similar.");
        }
        if (buildBlocks < layout.totalBlockEstimate()) {
            ChatUtils.sendChatMessages(source, "§eWarning: Only " + buildBlocks + " blocks available, need ~"
                    + layout.totalBlockEstimate() + ". Will build as far as possible.");
        }

        // Build loop
        long startMs = System.currentTimeMillis();
        int totalPlaced = 0;
        int segmentsCompleted = 0;
        int segmentsTotal = layout.segments().size();

        for (int si = 0; si < segmentsTotal; si++) {
            WallSegment segment = layout.segments().get(si);

            if (SkillManager.shouldAbortSkill(bot)) {
                ChatUtils.sendChatMessages(source, "§c[Fortify] Aborted.");
                break;
            }
            if ((System.currentTimeMillis() - startMs) > MAX_BUILD_TIME_MS) {
                ChatUtils.sendChatMessages(source, "§e[Fortify] Time budget reached after " + segmentsCompleted + " segments.");
                break;
            }

            ChatUtils.sendChatMessages(source, String.format("§e[Fortify] Building %s %d/%d (%s side)...",
                    segment.type(), si + 1, segmentsTotal, segment.side()));

            // Load and rotate schematic
            SchematicData schematic = loadSchematicForSegment(segment.type());
            if (schematic == null) {
                LOGGER.error("Failed to load schematic for segment type {}", segment.type());
                continue;
            }
            if (segment.quarterTurns() != 0) {
                schematic = schematic.rotated(segment.quarterTurns());
            }

            // Navigate to approach position (2 blocks outside the wall)
            BlockPos approachPos = computeApproachPos(segment);
            boolean moved = moveToReachBlock(source, bot, approachPos);
            if (!moved) {
                // Try direct navigation to origin
                moveToReachBlock(source, bot, segment.origin());
            }
            sleepQuiet(200);

            // Build this segment
            int placed = buildSegment(source, bot, world, schematic, segment.origin());
            totalPlaced += placed;

            if (placed > 0) {
                segmentsCompleted++;
            }

            // Brief pause between segments
            sleepQuiet(300);
        }

        // Scaffold cleanup
        int tornDown = ScaffoldService.teardownTrackedScaffolds(bot);
        if (tornDown > 0) {
            ChatUtils.sendChatMessages(source, "§7[Fortify] Cleaned up " + tornDown + " scaffold blocks.");
        }

        // Final report
        if (segmentsCompleted == segmentsTotal) {
            ChatUtils.sendChatMessages(source, "§a[Fortify] Fortification complete! "
                    + totalPlaced + " blocks placed across " + segmentsCompleted + " segments.");
            return SkillExecutionResult.success("Fortification complete: " + totalPlaced + " blocks, "
                    + segmentsCompleted + "/" + segmentsTotal + " segments.");
        } else {
            ChatUtils.sendChatMessages(source, "§e[Fortify] Partial completion: "
                    + totalPlaced + " blocks placed, " + segmentsCompleted + "/" + segmentsTotal + " segments done.");
            return SkillExecutionResult.success("Partial fortification: " + totalPlaced + " blocks, "
                    + segmentsCompleted + "/" + segmentsTotal + " segments.");
        }
    }

    // ── Segment building ────────────────────────────────────────

    /**
     * Build a single wall segment using the proven multi-pass pattern from BuildSchematicSkill.
     */
    private int buildSegment(ServerCommandSource source, ServerPlayerEntity bot,
                             ServerWorld world, SchematicData schematic, BlockPos origin) {
        ScaffoldService.clearScaffoldMemory(bot);

        Direction facing = bot.getHorizontalFacing();
        ConstructionPlan plan = ConstructionBlueprintService.planConstruction(schematic, origin, facing);

        List<BuildTarget> orderedTargets = new ArrayList<>(plan.orderedTargets());
        Set<BlockPos> remainingBlocks = new HashSet<>();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        Map<BlockPos, Integer> failCounts = new HashMap<>();

        for (BuildTarget target : orderedTargets) {
            remainingBlocks.add(target.worldPos());
            blockStates.put(target.worldPos(), target.state());
        }

        int totalPlaced = 0;
        int layerBaseY = origin.getY();

        for (int pass = 1; pass <= MAX_PASSES_PER_SEGMENT && !remainingBlocks.isEmpty(); pass++) {
            if (SkillManager.shouldAbortSkill(bot)) break;

            List<BlockPos> sortedRemaining = new ArrayList<>();
            for (BuildTarget target : orderedTargets) {
                if (remainingBlocks.contains(target.worldPos())) {
                    sortedRemaining.add(target.worldPos());
                }
            }

            int passPlaced = 0;

            for (BlockPos worldPos : sortedRemaining) {
                if (SkillManager.shouldAbortSkill(bot)) break;

                BlockState targetState = blockStates.get(worldPos);
                BlockState currentState = world.getBlockState(worldPos);
                if (currentState.equals(targetState)) {
                    remainingBlocks.remove(worldPos);
                    passPlaced++;
                    continue;
                }
                if (!currentState.isAir() && !currentState.isReplaceable()) {
                    continue;
                }

                int priorFails = failCounts.getOrDefault(worldPos, 0);
                if (priorFails >= 3) continue;

                int blockHeight = worldPos.getY() - layerBaseY;
                boolean canPlace = ensureCanReachBlockWithEffort(source, bot, world, worldPos, blockHeight, pass);

                if (!canPlace) {
                    failCounts.merge(worldPos, 1, Integer::sum);
                    continue;
                }

                BotActions.PlaceResult placed = tryPlaceBlock(bot, world, worldPos, targetState);
                if (placed.success()) {
                    passPlaced++;
                    remainingBlocks.remove(worldPos);
                    failCounts.remove(worldPos);
                } else {
                    failCounts.merge(worldPos, 1, Integer::sum);
                    // If no support, try filling ground under wall
                    if (placed.reason() != null && placed.reason().startsWith("no-solid-support")) {
                        fillGroundUnder(bot, world, worldPos);
                    }
                }

                sleepQuiet(BLOCK_PLACE_DELAY_MS);
            }

            totalPlaced += passPlaced;

            // If no progress this pass and remaining blocks exist, try repositioning
            if (passPlaced == 0 && !remainingBlocks.isEmpty() && pass < MAX_PASSES_PER_SEGMENT) {
                BlockPos avg = averagePos(remainingBlocks);
                moveToReachBlock(source, bot, avg);
            }
        }

        // Clean up scaffolds for this segment
        ScaffoldService.teardownTrackedScaffolds(bot);

        LOGGER.info("Segment at {} complete: {} blocks placed, {} remaining",
                origin.toShortString(), totalPlaced, remainingBlocks.size());
        return totalPlaced;
    }

    // ── Block placement ─────────────────────────────────────────

    /**
     * Place a single block with fallback materials.
     */
    private BotActions.PlaceResult tryPlaceBlock(ServerPlayerEntity bot, ServerWorld world,
                                                  BlockPos pos, BlockState targetState) {
        BlockState current = world.getBlockState(pos);
        if (current.equals(targetState)) {
            return new BotActions.PlaceResult(true, null);
        }

        Item targetItem = targetState.getBlock().asItem();
        List<Item> candidates = buildCandidateList(targetItem);

        // Check inventory
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

    /**
     * Build candidate item list with fallbacks for wall materials.
     */
    private List<Item> buildCandidateList(Item primary) {
        if (primary == Items.STONE_BRICKS || primary == Items.CHISELED_STONE_BRICKS
                || primary == Items.STONE_BRICK_SLAB || primary == Items.STONE_BRICK_STAIRS) {
            return new ArrayList<>(STONE_BRICK_FALLBACKS);
        }
        if (primary == Items.OAK_LOG) {
            return new ArrayList<>(OAK_LOG_FALLBACKS);
        }
        // Generic fallback
        List<Item> list = new ArrayList<>();
        list.add(primary);
        list.add(Items.COBBLESTONE);
        list.add(Items.DIRT);
        return list;
    }

    /**
     * Fill air gaps under a wall position with cobblestone for terrain adaptation.
     * Scans downward to find solid ground, then fills bottom-up so each block
     * has support for the one above.
     */
    private void fillGroundUnder(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        // Scan down to find the air column that needs filling
        List<BlockPos> toFill = new ArrayList<>();
        BlockPos cursor = pos.down();
        for (int i = 0; i < 4; i++) {
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable()) break;
            toFill.add(cursor);
            cursor = cursor.down();
        }
        if (toFill.isEmpty()) return;

        // Check that we found solid ground at the bottom
        BlockState foundation = world.getBlockState(toFill.get(toFill.size() - 1).down());
        if (foundation.isAir()) return; // no foundation to build on

        // Fill bottom-up so each placed block supports the next
        List<Item> fillBlocks = List.of(Items.COBBLESTONE, Items.DIRT, Items.COBBLED_DEEPSLATE);
        for (int i = toFill.size() - 1; i >= 0; i--) {
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, toFill.get(i), Direction.UP, fillBlocks);
            if (!result.success()) break;
        }
    }

    // ── Movement & reach ────────────────────────────────────────

    private boolean ensureCanReachBlockWithEffort(ServerCommandSource source, ServerPlayerEntity bot,
                                                   ServerWorld world, BlockPos target, int heightAboveGround, int passNumber) {
        if (isWithinReach(bot, target)) return true;

        BlockPos botPos = bot.getBlockPos();
        double horizontalDistSq = Math.pow(target.getX() - botPos.getX(), 2) + Math.pow(target.getZ() - botPos.getZ(), 2);
        int verticalDiff = target.getY() - botPos.getY();

        // Horizontal movement
        if (horizontalDistSq > REACH_DISTANCE_SQ) {
            BlockPos approachPos = findApproachPosition(world, target, botPos);
            if (approachPos != null) {
                moveToReachBlock(source, bot, approachPos);
                if (isWithinReach(bot, target)) return true;
            } else {
                moveToReachBlock(source, bot, target);
                if (isWithinReach(bot, target)) return true;
            }
        }

        // Scaffolding for elevated blocks
        if (verticalDiff > 2 && heightAboveGround <= MAX_SCAFFOLD_HEIGHT) {
            BlockPos nearTarget = new BlockPos(target.getX(), botPos.getY(), target.getZ());
            if (!isWithinReachXZ(bot, nearTarget, 2.0)) {
                moveToReachBlock(source, bot, nearTarget);
            }

            int currentBotY = bot.getBlockPos().getY();
            int optimalY = target.getY() - 2;
            int stepsNeeded = Math.max(0, optimalY - currentBotY);

            if (stepsNeeded > 0 && stepsNeeded <= MAX_SCAFFOLD_HEIGHT) {
                boolean pillared = ScaffoldService.pillarUp(bot, stepsNeeded, true);
                if (pillared && isWithinReach(bot, target)) return true;
            }
        }

        // Pass 3+: Try different approach directions
        if (passNumber >= 3) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos sidePos = target.offset(dir, 2).withY(botPos.getY());
                moveToReachBlock(source, bot, sidePos);
                if (isWithinReach(bot, target)) return true;
            }
        }

        return isWithinReach(bot, target);
    }

    private BlockPos findApproachPosition(ServerWorld world, BlockPos target, BlockPos current) {
        int targetGroundY = target.getY() - 1;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(dir).withY(targetGroundY);
            BlockPos below = candidate.down();
            BlockPos above = candidate.up();
            if (!world.getBlockState(below).isAir()
                    && world.getBlockState(candidate).isAir()
                    && world.getBlockState(above).isAir()) {
                return candidate;
            }
        }
        return null;
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

    private SchematicData loadSchematicForSegment(SegmentType type) {
        return switch (type) {
            case CORNER -> SimpleSchematicBuilder.getBuiltIn("defensive_wall_corner");
            case WALL_SECTION -> SimpleSchematicBuilder.getBuiltIn("defensive_wall_section");
            case GATEHOUSE -> SimpleSchematicBuilder.getBuiltIn("defensive_gatehouse");
        };
    }

    /**
     * Compute an approach position 2 blocks outside the wall segment.
     */
    private BlockPos computeApproachPos(WallSegment segment) {
        int offset = 3; // stand a bit outside
        return switch (segment.side()) {
            case NORTH -> segment.origin().add(0, 0, -offset);
            case SOUTH -> segment.origin().add(0, 0, offset);
            case EAST -> segment.origin().add(offset, 0, 0);
            case WEST -> segment.origin().add(-offset, 0, 0);
        };
    }

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

    private BlockPos averagePos(Set<BlockPos> positions) {
        if (positions.isEmpty()) return BlockPos.ORIGIN;
        double ax = 0, ay = 0, az = 0;
        for (BlockPos p : positions) {
            ax += p.getX();
            ay += p.getY();
            az += p.getZ();
        }
        int n = positions.size();
        return new BlockPos((int) (ax / n), (int) (ay / n), (int) (az / n));
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
