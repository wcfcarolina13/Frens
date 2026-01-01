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
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.BotStuckService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.services.WorkDirectionService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillPreferences;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.StripMineSkill;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.GameAI.skills.impl.shelter.HovelPerimeterBuilder;
import net.shasankp000.GameAI.skills.impl.shelter.BurrowBuilder;
import net.shasankp000.GameAI.services.SkillResumeService;
import net.shasankp000.GameAI.services.SneakLockService;
import net.shasankp000.FunctionCaller.SharedStateUtils;
import net.shasankp000.PlayerUtils.MiningTool;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.EntityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Emergency shelter builder: erects a small hovel using cheap blocks (dirt/cobble/etc.)
 * around the bot, clearing interior space and adding a roof and torches.
 */
public final class ShelterSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final double REACH_DISTANCE_SQ = 20.25D; // ~4.5 blocks (survival reach)
    private static final int DEEP_UNDERGROUND_SURFACE_DELTA_Y = 14;
    private static final List<Item> BUILD_BLOCKS = List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.SANDSTONE, Items.RED_SANDSTONE,
            Items.ANDESITE, Items.GRANITE, Items.DIORITE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available for shelter.");
        }
        BlockPos origin = bot.getBlockPos();
        String type = getOption(context, "hovel");
        if ("burrow".equalsIgnoreCase(type)) {
            LOGGER.info("Burrow sequence start: descend 5 -> strip 5 -> descend 3 -> hollow");
            SkillExecutionResult burrowResult = new BurrowBuilder().build(context, source, bot, world, origin);
            if (!burrowResult.success()) {
                return burrowResult;
            }
            BlockPos chamberCenter = bot.getBlockPos();
            depositCheapLoot(world, bot, chamberCenter);
            ensurePickupSlot(bot);
            if (!inventoryFull(bot)) {
                sweepDrops(source, 12.0, 5.0, 24, 12_000L);
            } else {
                LOGGER.warn("Burrow: inventory still full after deposit; skipping drop sweep.");
            }
            ChatUtils.sendSystemMessage(source, "Emergency burrow built.");
            return SkillExecutionResult.success("Shelter (burrow) built.");
        }

        boolean resumeRequested = SkillResumeService.consumeResumeIntent(bot.getUuid());
        int radius = Math.max(2, Math.min(5, getInt(context, "radius", getInt(context, "count", 3))));
        int wallHeight = 5;
        Direction preferredDoorSide = null;
        Object directionParam = context.parameters().get("direction");
        if (directionParam instanceof Direction dir) {
            preferredDoorSide = dir;
        }

        // If we're in water (common at shorelines), step onto nearby dry land first.
        // Otherwise the "underground" check can misclassify shallow riverbeds and try to mine upward.
        if (tryStepOutOfWater(source, bot, world)) {
            origin = bot.getBlockPos();
        }

        // If we're clearly underground, get to the surface first (then re-plan the hovel site).
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        boolean skyVisible = world.isSkyVisible(origin.up());
        boolean underArtificialRoof = isLikelyUnderArtificialRoof(world, origin, 7);

        // If we fell far below the surface (common when dropping into a deep hole mid-task),
        // don't try to mine our way out here. Regroup and let follow-walk / teleport handle recovery.
        if (!skyVisible && !underArtificialRoof && bot.getBlockY() < surfaceY - DEEP_UNDERGROUND_SURFACE_DELTA_Y) {
            if (triggerEmergencyRegroupFromDeepUnderground(context, source, bot, world)) {
                return SkillExecutionResult.failure("Regrouping: fell deep underground.");
            }
        }

        if (!skyVisible && !underArtificialRoof && bot.getBlockY() < surfaceY - 3) {
            // If we're simply under a roof/walls near the surface, relocate to a nearby sky-visible opening
            // instead of trying to mine upward (which can hit shoreline water and abort).
            if (tryRelocateToNearbySurfaceOpening(source, bot, world, origin, 8)) {
                origin = bot.getBlockPos();
                surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
                skyVisible = world.isSkyVisible(origin.up());
            }
            if (!skyVisible && bot.getBlockY() < surfaceY - 3) {
                boolean surfaced = tryReachSurface(source, bot, world, origin);
                if (surfaced) {
                    origin = bot.getBlockPos();
                }
            }
        }
        surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (!world.isSkyVisible(origin.up()) && !underArtificialRoof && bot.getBlockY() < surfaceY - 2) {
            return SkillExecutionResult.failure("I can't build a surface shelter while underground; I couldn't reach the surface.");
        }

        // If we're near a cave mouth / low overhang, the footprint can include tight headroom that causes
        // repeated in-wall clipping and "mining out" loops. Prefer relocating a short distance to a more
        // open patch of ground before starting the build.
        if (!isOpenFootprint(world, origin, radius)) {
            BlockPos relocated = findNearbyOpenFootprint(world, origin, radius, 12);
            if (relocated != null) {
                boolean moved = moveToBuildSite(source, bot, relocated);
                if (moved) {
                    origin = bot.getBlockPos();
                }
            }
        }

        return new HovelPerimeterBuilder().build(context, source, bot, world, origin, radius, wallHeight, preferredDoorSide, resumeRequested);
    }

    private boolean isOpenFootprint(ServerWorld world, BlockPos center, int radius) {
        if (world == null || center == null) {
            return true;
        }
        if (radius <= 0) {
            return true;
        }

        // "Open" means: sky visible and 2-block headroom across most of the intended footprint.
        int total = 0;
        int open = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.add(dx, 0, dz);
                total++;
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                if (!world.isSkyVisible(pos.up())) {
                    continue;
                }
                BlockState feet = world.getBlockState(pos);
                BlockState head = world.getBlockState(pos.up());
                if (!feet.getCollisionShape(world, pos).isEmpty()) {
                    continue;
                }
                if (!head.getCollisionShape(world, pos.up()).isEmpty()) {
                    continue;
                }
                open++;
            }
        }

        if (total <= 0) {
            return true;
        }
        double ratio = (double) open / (double) total;
        return ratio >= 0.75D;
    }

    private BlockPos findNearbyOpenFootprint(ServerWorld world, BlockPos origin, int buildRadius, int searchRadius) {
        if (world == null || origin == null) {
            return null;
        }
        if (searchRadius <= 0) {
            return null;
        }

        BlockPos best = null;
        double bestScore = -1.0D;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!world.isChunkLoaded(candidate)) {
                    continue;
                }
                if (!world.isSkyVisible(candidate.up())) {
                    continue;
                }
                if (!isStandable(world, candidate)) {
                    continue;
                }
                if (TreeDetector.isNearHumanBlocks(world, candidate, 2)) {
                    continue;
                }

                // Score by footprint openness, with tie-breaker toward closer candidates.
                boolean open = isOpenFootprint(world, candidate, buildRadius);
                if (!open) {
                    continue;
                }
                double distSq = origin.getSquaredDistance(candidate);
                double score = 1.0D;
                if (score > bestScore || (score == bestScore && distSq < bestDistSq)) {
                    bestScore = score;
                    bestDistSq = distSq;
                    best = candidate.toImmutable();
                }
            }
        }

        return best;
    }

    private boolean triggerEmergencyRegroupFromDeepUnderground(SkillContext context,
                                                              ServerCommandSource botSource,
                                                              ServerPlayerEntity bot,
                                                              ServerWorld world) {
        if (botSource == null || bot == null || world == null) {
            return false;
        }

        // Prefer the player who issued the command (if available).
        ServerPlayerEntity commander = null;
        if (context != null && context.requestSource() != null) {
            try {
                commander = context.requestSource().getPlayer();
            } catch (Exception ignored) {
            }
        }
        if (commander != null && commander.getUuid().equals(bot.getUuid())) {
            commander = null;
        }
        if (commander != null && commander.isSpectator()) {
            commander = null;
        }
        if (commander == null) {
            net.minecraft.server.MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                commander = srv.getPlayerManager().getPlayerList().stream()
                        .filter(p -> p != null && !p.getUuid().equals(bot.getUuid()))
                        .filter(p -> !p.isSpectator())
                        .min(java.util.Comparator.comparingDouble(p -> p.squaredDistanceTo(bot)))
                        .orElse(null);
            }
        }

        BlockPos goal = null;
        if (commander != null && commander.getEntityWorld() instanceof ServerWorld commanderWorld) {
            goal = net.shasankp000.GameAI.services.SafePositionService.findForwardSafeSpot(commanderWorld, commander);
            if (goal == null) {
                goal = net.shasankp000.GameAI.services.SafePositionService.findSafeNear(commanderWorld, commander.getBlockPos(), 8);
            }
            if (goal == null) {
                goal = commander.getBlockPos().toImmutable();
            }
        }
        if (goal == null) {
            Vec3d safe = BotStuckService.getLastSafePosition(bot.getUuid());
            if (safe != null) {
                goal = BlockPos.ofFloored(safe);
                // Avoid teleporting/pathing into a 1-block hole.
                BlockPos safer = net.shasankp000.GameAI.services.SafePositionService.findSafeNear(world, goal, 6);
                if (safer != null) {
                    goal = safer;
                }
            }
        }
        if (goal == null) {
            return false;
        }

        TaskService.forceAbort(bot.getUuid(), "§cEmergency regroup: fell deep underground.");
        ChatUtils.sendSystemMessage(botSource, "Shelter: fell deep underground; regrouping...");

        boolean teleportAllowed = SkillPreferences.teleportDuringSkills(bot);
        if (!teleportAllowed) {
            // Safe regroup: do not allow come-recovery digging skills (ascent/stripmine).
            BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, false);
            return true;
        }

        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                goal,
                goal,
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.execute(botSource, bot, plan, Boolean.TRUE, true);
        return true;
    }

    private boolean tryStepOutOfWater(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        boolean inWater = !world.getFluidState(origin).isEmpty()
                || !world.getFluidState(origin.down()).isEmpty()
                || !world.getFluidState(origin.up()).isEmpty();
        if (!inWater) {
            return false;
        }

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int radius = 10;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!isStandable(world, pos)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 2)) {
                continue;
            }
            double sq = origin.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }
        if (best == null) {
            return false;
        }
        return moveToBuildSite(source, bot, best);
    }

    private boolean tryReachSurface(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, BlockPos origin) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        if (world.isSkyVisible(bot.getBlockPos().up())) {
            return true;
        }
        int targetY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        if (targetY <= bot.getBlockY()) {
            return world.isSkyVisible(bot.getBlockPos().up());
        }
        Direction facing = bot.getHorizontalFacing();
        Map<String, Object> shared = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("targetY", targetY);
        params.put("issuerFacing", facing.asString());
        params.put("lockDirection", true);
        SkillContext ctx = new SkillContext(source, shared, params);
        SkillExecutionResult res = new CollectDirtSkill().execute(ctx);
        if (!res.success()) {
            LOGGER.warn("Shelter: ascent to surface failed: {}", res.message());
            return false;
        }
        return world.isSkyVisible(bot.getBlockPos().up());
    }

    private boolean tryRelocateToNearbySurfaceOpening(ServerCommandSource source,
                                                      ServerPlayerEntity bot,
                                                      ServerWorld world,
                                                      BlockPos origin,
                                                      int searchRadius) {
        if (source == null || bot == null || world == null) {
            return false;
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-searchRadius, -1, -searchRadius),
                origin.add(searchRadius, 2, searchRadius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!world.isSkyVisible(pos.up())) {
                continue;
            }
            if (!isStandable(world, pos)) {
                continue;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 2)) {
                continue;
            }
            double sq = origin.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }
        if (best == null) {
            return false;
        }
        return moveToBuildSite(source, bot, best);
    }

    private boolean isLikelyUnderArtificialRoof(ServerWorld world, BlockPos origin, int maxHeight) {
        if (world == null || origin == null) {
            return false;
        }
        int max = Math.max(3, maxHeight);
        int roofBlocks = 0;
        int checked = 0;
        for (int dy = 1; dy <= max; dy++) {
            BlockPos pos = origin.up(dy);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.isReplaceable()) {
                checked++;
                if (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.WOOL)) {
                    roofBlocks++;
                }
            }
        }
        return checked >= 2 && roofBlocks >= 1;
    }

    private boolean moveToBuildSite(ServerCommandSource source, ServerPlayerEntity bot, BlockPos center) {
        if (bot == null || center == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(center) <= 4.0D) {
            return true;
        }
        var planOpt = MovementService.planLootApproach(bot, center, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        var res = MovementService.execute(source, bot, planOpt.get(), false, true, true, false);
        if (!res.success()) {
            LOGGER.warn("Shelter moveToBuildSite: MovementService failed ({}) endedAt={} target={}",
                    res.detail(), bot.getBlockPos().toShortString(), center.toShortString());
        }
        return res.success();
    }

    private boolean isStandable(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        if (!world.getFluidState(foot).isEmpty() || !world.getFluidState(foot.up()).isEmpty()) {
            return false;
        }
        BlockPos below = foot.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.getCollisionShape(world, below).isEmpty()) {
            return false;
        }
        BlockState footState = world.getBlockState(foot);
        if (!footState.getCollisionShape(world, foot).isEmpty()) {
            return false;
        }
        BlockState headState = world.getBlockState(foot.up());
        return headState.getCollisionShape(world, foot.up()).isEmpty();
    }

    private boolean isFarmBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(Blocks.FARMLAND) || state.isIn(BlockTags.CROPS);
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private String getOption(SkillContext context, String fallback) {
        if (context == null) {
            return fallback;
        }
        Object opts = context.parameters().get("options");
        if (opts instanceof List<?> list) {
            String best = null;
            for (Object val : list) {
                if (val == null) {
                    continue;
                }
                String opt = val.toString().trim().toLowerCase(Locale.ROOT);
                if (opt.isEmpty()) {
                    continue;
                }
                if ("burrow".equals(opt) || "hovel".equals(opt) || "hovel2".equals(opt)) {
                    return opt;
                }
                if (best == null) {
                    best = opt;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return fallback;
    }

    private int getInt(SkillContext context, String key, int fallback) {
        if (context == null || key == null) {
            return fallback;
        }
        Object val = context.parameters().get(key);
        if (val instanceof Number num) {
            return num.intValue();
        }
        if (val instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private void sweepDrops(ServerCommandSource source, double radius, double vRange, int maxTargets, long durationMs) {
        try {
            DropSweeper.sweep(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), radius, vRange, maxTargets, durationMs);
            DropSweeper.sweep(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), radius + 2.0, vRange + 1.0, Math.max(maxTargets, 40), durationMs + 3000); // second, wider pass
        } catch (Exception e) {
            LOGGER.warn("Shelter drop-sweep failed: {}", e.getMessage());
        }
    }

    private boolean hasOption(SkillContext context, String... names) {
        Object opts = context.parameters().get("options");
        if (!(opts instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        for (Object val : list) {
            if (val == null) continue;
            String opt = val.toString().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (opt.equals(name.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean placeBlock(ServerPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (isFarmBlock(state)) {
            return false;
        }
        if (!state.isAir() && !state.isReplaceable() && !state.isOf(Blocks.SNOW) && !state.isOf(Blocks.SNOW_BLOCK)) {
            return false;
        }
        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) {
            return false;
        }
        return BotActions.placeBlockAt(bot, pos, Direction.UP, BUILD_BLOCKS);
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        if (!BlockInteractionService.canInteract(bot, pos, REACH_DISTANCE_SQ)) {
            LOGGER.debug("Shelter: skip mining out-of-reach/blocked {}", pos.toShortString());
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (isFarmBlock(state)) {
            LOGGER.warn("Shelter: refusing to break farm block at {} (state={})", pos.toShortString(), state.getBlock().getName().getString());
            return;
        }
        BlockState below = world.getBlockState(pos.down());
        if (below.isOf(Blocks.FARMLAND)) {
            // Avoid breaking crops/blocks sitting on farmland.
            LOGGER.warn("Shelter: refusing to break block above farmland at {} (state={})", pos.toShortString(), state.getBlock().getName().getString());
            return;
        }
        if (yieldToImmediateThreats(bot, 2_000L)) {
            return;
        }
        try {
            MiningTool.mineBlock(bot, pos).get(3_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.warn("Shelter: failed to clear {}: {}", pos.toShortString(), e.getMessage());
        }
    }

    private boolean ensureReach(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        if (BlockInteractionService.canInteract(bot, target, REACH_DISTANCE_SQ)) {
            return true;
        }
        ServerCommandSource source = bot.getCommandSource();
        if (source == null) {
            return false;
        }
        var planOpt = MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult res = MovementService.execute(source, bot, planOpt.get(), false, true, true, false);
        return res.success() && BlockInteractionService.canInteract(bot, target, REACH_DISTANCE_SQ);
    }

    private boolean yieldToImmediateThreats(ServerPlayerEntity bot, long maxWaitMs) {
        if (bot == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        List<Entity> hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                .stream()
                .filter(EntityUtil::isHostile)
                .toList();
        if (hostiles.isEmpty()) {
            return false;
        }

        BotEventHandler.engageImmediateThreats(bot);

        long deadline = now + Math.max(250L, maxWaitMs);
        long lastEngage = now;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return true;
            }
            sleepQuiet(250L);
            hostiles = AutoFaceEntity.detectNearbyEntities(bot, 10.0D)
                    .stream()
                    .filter(EntityUtil::isHostile)
                    .toList();
            if (hostiles.isEmpty()) {
                return false;
            }
            long t = System.currentTimeMillis();
            if (t - lastEngage >= 900L) {
                BotEventHandler.engageImmediateThreats(bot);
                lastEngage = t;
            }
        }
        return true;
    }

    private void depositCheapLoot(ServerWorld world, ServerPlayerEntity bot, BlockPos chamberCenter) {
        BlockPos chestPos = findChestNear(world, chamberCenter, 3);
        if (chestPos == null) {
            if (!hasItem(bot, Items.CHEST)) {
                LOGGER.info("Burrow deposit: no chest nearby and none in inventory; skipping deposit.");
                return;
            }
            List<BlockPos> candidates = chestPlacementOptions(chamberCenter);
            for (BlockPos placement : candidates) {
                if (placement.equals(bot.getBlockPos())) {
                    continue;
                }
                BlockPos floor = placement.down();
                if (!world.getBlockState(floor).isSolidBlock(world, floor)) {
                    continue;
                }
                // Clear space at placement (foot + head)
                mineSoft(bot, placement);
                mineSoft(bot, placement.up());
                if (!ensureReach(bot, placement)) {
                    LOGGER.warn("Burrow deposit: could not reach {} for chest placement.", placement.toShortString());
                    continue;
                }
                BotActions.placeBlockAt(bot, placement, Direction.UP, List.of(Items.CHEST));
                BlockState placed = world.getBlockState(placement);
                if (!placed.isOf(Blocks.CHEST) && !placed.isOf(Blocks.TRAPPED_CHEST)) {
                    LOGGER.warn("Burrow deposit: chest placement at {} failed (state={}).", placement.toShortString(), placed.getBlock().getName().getString());
                    continue;
                }
                chestPos = placement;
                LOGGER.info("Burrow deposit: placed chest at {}", chestPos.toShortString());
                break;
            }
            if (chestPos == null) {
                LOGGER.warn("Burrow deposit: could not place a chest near {} after trying {} spots.", chamberCenter.toShortString(), candidates.size());
                return;
            }
        }
        BlockPos nearby = findChestNear(world, chamberCenter, 3);
        if (nearby != null) {
            chestPos = nearby;
        }
        net.minecraft.block.entity.ChestBlockEntity chest = awaitChest(world, chestPos);
        if (chest == null) {
            BlockState state = world.getBlockState(chestPos);
            LOGGER.warn("Burrow deposit: chest not ready at {} (state={}); skipping deposit.", chestPos.toShortString(), state.getBlock().getName().getString());
            return;
        }
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            LOGGER.warn("Burrow deposit: chest {} not interactable from {}; skipping remote deposit.",
                    chestPos.toShortString(), bot.getBlockPos().toShortString());
            return;
        }
        Integer moved = callOnServer(world, () -> moveCheapItems(bot, chest));
        if (moved == null) {
            LOGGER.warn("Burrow deposit: could not move items into chest at {}", chestPos.toShortString());
            return;
        }
        LOGGER.info("Burrow deposit: moved {} items into chest at {}", moved, chestPos.toShortString());
    }

    private boolean hasItem(ServerPlayerEntity bot, Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findChestNear(ServerWorld world, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -1, -radius), center.add(radius, 1, radius))) {
            var state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private List<BlockPos> chestPlacementOptions(BlockPos chamberCenter) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(chamberCenter);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            candidates.add(chamberCenter.offset(dir));
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            candidates.add(chamberCenter.offset(dir, 2));
        }
        return candidates;
    }

    private net.minecraft.block.entity.ChestBlockEntity awaitChest(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            var be = callOnServer(world, () -> world.getBlockEntity(pos));
            if (be instanceof net.minecraft.block.entity.ChestBlockEntity chest) {
                return chest;
            }
            BlockState state = callOnServer(world, () -> world.getBlockState(pos));
            if (state == null || !(state.getBlock() instanceof net.minecraft.block.ChestBlock)) {
                return null; // something else replaced the spot
            }
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        var be = callOnServer(world, () -> world.getBlockEntity(pos));
        return be instanceof net.minecraft.block.entity.ChestBlockEntity chest ? chest : null;
    }

    private <T> T callOnServer(ServerWorld world, java.util.function.Supplier<T> task) {
        if (world == null || task == null) {
            return null;
        }
        var server = world.getServer();
        if (server == null) {
            return null;
        }
        if (server.isOnThread()) {
            return task.get();
        }
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.complete(null);
            }
        });
        try {
            return future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    private int moveCheapItems(ServerPlayerEntity bot, net.minecraft.block.entity.ChestBlockEntity chest) {
        var chestInv = (net.minecraft.inventory.Inventory) chest;
        int moved = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isCheap(stack.getItem())) continue;
            ItemStack moving = stack.copy();
            moved += insertIntoChest(bot, chestInv, i, moving);
        }
        return moved;
    }

    private boolean isCheap(Item item) {
        if (BUILD_BLOCKS.contains(item)) return true;
        return item == Items.ROTTEN_FLESH
                || item == Items.BONE
                || item == Items.STRING
                || item == Items.SPIDER_EYE
                || item == Items.GUNPOWDER
                || item == Items.ARROW
                || item == Items.NAUTILUS_SHELL
                || item == Items.FEATHER;
    }

    private int insertIntoChest(ServerPlayerEntity bot, net.minecraft.inventory.Inventory chest, int slot, ItemStack moving) {
        ItemStack remaining = moving;
        for (int c = 0; c < chest.size() && !remaining.isEmpty(); c++) {
            ItemStack chestStack = chest.getStack(c);
            if (chestStack.isEmpty()) {
                chest.setStack(c, remaining.copy());
                bot.getInventory().setStack(slot, ItemStack.EMPTY);
                return moving.getCount();
            }
            if (ItemStack.areItemsEqual(chestStack, remaining)
                    && chestStack.getCount() < chestStack.getMaxCount()) {
                int canAdd = Math.min(chestStack.getMaxCount() - chestStack.getCount(), remaining.getCount());
                chestStack.increment(canAdd);
                remaining.decrement(canAdd);
                chest.setStack(c, chestStack);
            }
        }
        if (remaining.getCount() != moving.getCount()) {
            bot.getInventory().setStack(slot, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
        }
        return moving.getCount() - remaining.getCount();
    }

    private boolean inventoryFull(ServerPlayerEntity bot) {
        return bot.getInventory().getEmptySlot() < 0;
    }

    private void ensurePickupSlot(ServerPlayerEntity bot) {
        if (bot.getInventory().getEmptySlot() >= 0) {
            return;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                bot.dropItem(stack.split(stack.getCount()), false, false);
                LOGGER.warn("Shelter drop-sweep: inventory full, dropped {} to make space.", stack.getItem().getName().getString());
                return;
            }
        }
    }
}