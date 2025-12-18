package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: safety and "burial rescue" routines extracted from BotEventHandler.
 *
 * <p>Includes: suffocation detection/alerts, spawn-in-blocks checks, and periodic burial scanning.</p>
 */
public final class BotRescueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-rescue");

    // Track recent obstruction damage to gate escape mining (2s)
    private static final Map<UUID, Long> LAST_OBSTRUCT_DAMAGE_TICK = new ConcurrentHashMap<>();
    private static final int OBSTRUCT_WINDOW_TICKS = 40; // 2s @20tps
    private static final Map<UUID, Long> LAST_MINING_ESCAPE_ATTEMPT = new ConcurrentHashMap<>();
    private static final int MINING_ESCAPE_COOLDOWN_TICKS = 60; // 3s between mining attempts
    private static final Map<UUID, Long> LAST_ESCAPE_NUDGE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STUCK_LOG_MS = new ConcurrentHashMap<>();
    private static final long ESCAPE_NUDGE_COOLDOWN_MS = 1_200L;

    private static final Map<UUID, Long> LAST_SUFFOCATION_ALERT_TICK = new ConcurrentHashMap<>();
    private static final long SUFFOCATION_ALERT_COOLDOWN_TICKS = 20L; // 1 second for urgent warnings
    private static long lastBurialScanTick = -1L;

    private BotRescueService() {}

    public static void reset() {
        LAST_SUFFOCATION_ALERT_TICK.clear();
        LAST_OBSTRUCT_DAMAGE_TICK.clear();
        LAST_MINING_ESCAPE_ATTEMPT.clear();
        LAST_ESCAPE_NUDGE_MS.clear();
        LAST_STUCK_LOG_MS.clear();
        lastBurialScanTick = -1L;
    }

    public static void noteObstructDamage(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv != null) {
            LAST_OBSTRUCT_DAMAGE_TICK.put(bot.getUuid(), (long) srv.getTicks());
        }
    }

    private static boolean tookRecentObstructDamage(ServerPlayerEntity bot) {
        MinecraftServer srv = bot.getCommandSource().getServer();
        if (srv == null) return false;
        long now = srv.getTicks();
        long last = LAST_OBSTRUCT_DAMAGE_TICK.getOrDefault(bot.getUuid(), -1L);
        return last >= 0 && (now - last) <= OBSTRUCT_WINDOW_TICKS;
    }

    public static boolean rescueFromBurial(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return false;
        }

        // Check both damage AND physical block presence to catch suffocation early
        boolean takingSuffocationDamage = tookRecentSuffocation(bot);
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();

        BlockState headState = world.getBlockState(head);
        BlockState feetState = world.getBlockState(feet);
        boolean headIsDoor = headState.getBlock() instanceof DoorBlock;
        boolean feetIsDoor = feetState.getBlock() instanceof DoorBlock;

        // Stuck logic: Ignore Farmland, Water, crops, and protected partial blocks like fences.
        boolean headBlocked = !headState.isAir()
                && !headState.isOf(Blocks.WATER)
                && !headState.isOf(Blocks.WHEAT)
                && !headState.getCollisionShape(world, head).isEmpty()
                && !isRescueProtectedBlock(headState);
        boolean feetBlocked = !feetState.isAir()
                && !feetState.isOf(Blocks.WATER)
                && !feetState.isOf(Blocks.FARMLAND)
                && !feetState.isOf(Blocks.WHEAT)
                && !feetState.getCollisionShape(world, feet).isEmpty()
                && !isRescueProtectedBlock(feetState);

        // Being "in" a door block is common during doorway traversal and should not trigger burial rescue
        // unless we're actually taking suffocation damage. Otherwise, this fights follow's door traversal.
        if (!takingSuffocationDamage && (headIsDoor || feetIsDoor) && headBlocked && feetBlocked) {
            LAST_SUFFOCATION_ALERT_TICK.remove(bot.getUuid());
            LAST_STUCK_LOG_MS.remove(bot.getUuid());
            return false;
        }

        boolean stuckInBlocks = (headBlocked || feetBlocked);

        // Exit if not suffocating AND not stuck in blocks
        if (!takingSuffocationDamage && !stuckInBlocks) {
            LAST_SUFFOCATION_ALERT_TICK.remove(bot.getUuid());
            LAST_STUCK_LOG_MS.remove(bot.getUuid());
            return false;
        }

        long nowMs = System.currentTimeMillis();
        long lastLog = LAST_STUCK_LOG_MS.getOrDefault(bot.getUuid(), -1L);
        if (nowMs - lastLog >= 900L) {
            LOGGER.info("Bot {} is stuck! takingSuffocationDamage={}, stuckInBlocks={}, headState={}, feetState={}",
                    bot.getName().getString(), takingSuffocationDamage, stuckInBlocks,
                    headState.getBlock().getName().getString(), feetState.getBlock().getName().getString());
            LAST_STUCK_LOG_MS.put(bot.getUuid(), nowMs);
        }

        // If we're stuck inside a door (common in enclosures), try to open it instead of mining it.
        if (headState.getBlock() instanceof DoorBlock || feetState.getBlock() instanceof DoorBlock) {
            boolean opened = MovementService.tryOpenDoorAt(bot, head) || MovementService.tryOpenDoorAt(bot, feet);
            if (opened) {
                attemptEscapeMovement(bot, world, feet, head);
                return true;
            }
        }

        // FIRST: Try to move out of suffocating position in all directions
        if (attemptEscapeMovement(bot, world, feet, head)) {
            LOGGER.info("Bot {} attempting to move out of suffocating position", bot.getName().getString());
            return true;
        }

        // If we're only stuck in protected blocks (fences/walls/gates), never mine them; just keep nudging.
        if (!takingSuffocationDamage && (isRescueProtectedBlock(headState) || isRescueProtectedBlock(feetState))) {
            return attemptEscapeMovement(bot, world, feet, head);
        }

        // Bot is suffocating or stuck - prioritize clearing headspace first
        // Step 1: If head is blocked, mine upward to free headspace
        if (headBlocked && !headState.isOf(Blocks.BEDROCK)) {
            if (headState.getBlock() instanceof DoorBlock) {
                // Never mine doors as a "suffocation escape" — try interaction only.
                return false;
            }
            if (isRescueProtectedBlock(headState)) {
                return attemptEscapeMovement(bot, world, feet, head);
            }
            String keyword = preferredToolKeyword(headState);
            if (keyword != null) {
                BotActions.selectBestTool(bot, keyword, "sword");
            }

            String blockName = headState.getBlock().getName().getString();
            String toolName = bot.getMainHandStack().isEmpty() ? "bare hands" : bot.getMainHandStack().getName().getString();
            alertSuffocationWithDetails(bot, blockName, toolName, "above");

            MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                UUID uuid = bot.getUuid();
                long now = srv.getTicks();
                long lastAttempt = LAST_MINING_ESCAPE_ATTEMPT.getOrDefault(uuid, -1L);

                if (lastAttempt < 0 || (now - lastAttempt) >= MINING_ESCAPE_COOLDOWN_TICKS) {
                    LAST_MINING_ESCAPE_ATTEMPT.put(uuid, now);
                    LOGGER.info("Bot {} clearing headspace by mining {}", bot.getName().getString(), blockName);
                    MiningTool.mineBlock(bot, head);
                    return true;
                }
            }
            return false;
        }

        // Step 2: Head is clear - check if bot is stuck in a hole or surrounded
        // Check horizontal directions for escape
        Direction escapeDirection = findNearestAirDirection(world, feet, head);

        if (escapeDirection != null) {
            // Found an escape direction - mine blocks that way
            BlockPos targetPos = feet.offset(escapeDirection);
            BlockState targetState = world.getBlockState(targetPos);

            if (!targetState.isAir() && !targetState.isOf(Blocks.BEDROCK) && targetState.blocksMovement()) {
                if (targetState.getBlock() instanceof DoorBlock) {
                    if (MovementService.tryOpenDoorAt(bot, targetPos)) {
                        return true;
                    }
                    return false;
                }
                if (isRescueProtectedBlock(targetState)) {
                    return attemptEscapeMovement(bot, world, feet, head);
                }
                String keyword = preferredToolKeyword(targetState);
                if (keyword != null) {
                    BotActions.selectBestTool(bot, keyword, "sword");
                }

                String blockName = targetState.getBlock().getName().getString();
                String toolName = bot.getMainHandStack().isEmpty() ? "bare hands" : bot.getMainHandStack().getName().getString();
                String directionName = escapeDirection.toString().toLowerCase();
                alertSuffocationWithDetails(bot, blockName, toolName, "toward " + directionName);

                MinecraftServer srv = bot.getCommandSource().getServer();
                if (srv != null) {
                    UUID uuid = bot.getUuid();
                    long now = srv.getTicks();
                    long lastAttempt = LAST_MINING_ESCAPE_ATTEMPT.getOrDefault(uuid, -1L);

                    if (lastAttempt < 0 || (now - lastAttempt) >= MINING_ESCAPE_COOLDOWN_TICKS) {
                        LAST_MINING_ESCAPE_ATTEMPT.put(uuid, now);
                        LOGGER.info("Bot {} mining escape path {} toward {}",
                                bot.getName().getString(), blockName, directionName);
                        MiningTool.mineBlock(bot, targetPos);
                        return true;
                    }
                }
            }
        } else if (feetBlocked && !feetState.isOf(Blocks.BEDROCK)) {
            // Stuck at feet level
            if (feetState.getBlock() instanceof DoorBlock) {
                if (MovementService.tryOpenDoorAt(bot, feet)) {
                    return true;
                }
                return false;
            }
            if (isRescueProtectedBlock(feetState)) {
                return attemptEscapeMovement(bot, world, feet, head);
            }
            String keyword = preferredToolKeyword(feetState);
            if (keyword != null) {
                BotActions.selectBestTool(bot, keyword, "sword");
            }

            String blockName = feetState.getBlock().getName().getString();
            String toolName = bot.getMainHandStack().isEmpty() ? "bare hands" : bot.getMainHandStack().getName().getString();
            alertSuffocationWithDetails(bot, blockName, toolName, "at feet");

            MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                UUID uuid = bot.getUuid();
                long now = srv.getTicks();
                long lastAttempt = LAST_MINING_ESCAPE_ATTEMPT.getOrDefault(uuid, -1L);

                if (lastAttempt < 0 || (now - lastAttempt) >= MINING_ESCAPE_COOLDOWN_TICKS) {
                    LAST_MINING_ESCAPE_ATTEMPT.put(uuid, now);
                    LOGGER.info("Bot {} mining block at feet: {}", bot.getName().getString(), blockName);
                    MiningTool.mineBlock(bot, feet);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Proactively checks if bot is stuck in blocks and initiates time-based mining to escape.
     * Uses MiningTool.mineBlock() for physical, tool-based breaking.
     */
    public static boolean checkAndEscapeSuffocation(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return false;
        }

        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();

        // If we're embedded in a protected block (e.g., chest/bed/fence), never mine it — just nudge away.
        BlockState feetStateInitial = world.getBlockState(feet);
        BlockState headStateInitial = world.getBlockState(head);
        if ((isRescueProtectedBlock(feetStateInitial) && feetStateInitial.blocksMovement() && !feetStateInitial.getCollisionShape(world, feet).isEmpty())
                || (isRescueProtectedBlock(headStateInitial) && headStateInitial.blocksMovement() && !headStateInitial.getCollisionShape(world, head).isEmpty())) {
            return attemptEscapeMovement(bot, world, feet, head);
        }

        // Check critical positions (head and feet most important)
        List<BlockPos> criticalPositions = List.of(feet, head);
        List<BlockPos> adjacentPositions = List.of(
                feet.north(), feet.south(), feet.east(), feet.west(),
                head.north(), head.south(), head.east(), head.west()
        );

        boolean criticalBlocked = false;
        BlockPos blockedPos = null;
        BlockState blockedState = null;

        // Check critical positions first
        for (BlockPos pos : criticalPositions) {
            BlockState state = world.getBlockState(pos);
            // Ignore Farmland and other partial blocks that don't suffocate
            if (!state.isAir()
                    && !state.isOf(Blocks.BEDROCK)
                    && !state.isOf(Blocks.FARMLAND)
                    && !state.isOf(Blocks.WATER)
                    && state.blocksMovement()
                    && !isRescueProtectedBlock(state)) {
                // Double check collision shape to be sure
                if (!state.getCollisionShape(world, pos).isEmpty()) {
                    criticalBlocked = true;
                    blockedPos = pos;
                    blockedState = state;
                    break;
                }
            }
        }

        // If no critical blocks, check adjacent
        if (!criticalBlocked) {
            for (BlockPos pos : adjacentPositions) {
                BlockState state = world.getBlockState(pos);
                if (!state.isAir()
                        && !state.isOf(Blocks.BEDROCK)
                        && !state.isOf(Blocks.FARMLAND)
                        && state.blocksMovement()
                        && !isRescueProtectedBlock(state)) {
                    criticalBlocked = true;
                    blockedPos = pos;
                    blockedState = state;
                    break;
                }
            }
        }

        // Gate escape: require obstructing blocks AND recent obstruct damage (<=2s)
        if (!criticalBlocked || !tookRecentObstructDamage(bot)) {
            return false;
        }

        // Bot is stuck and damaged - mine out using time-based mining
        if (blockedPos != null && blockedState != null) {
            if (blockedState.getBlock() instanceof DoorBlock) {
                // Doors should be interacted with, not mined, to preserve survival mechanics.
                return MovementService.tryOpenDoorAt(bot, blockedPos);
            }
            // Select appropriate tool
            String keyword = preferredToolKeyword(blockedState);
            if (keyword != null) {
                BotActions.selectBestTool(bot, keyword, "sword");
            }

            String blockName = blockedState.getBlock().getName().getString();
            String toolName = bot.getMainHandStack().isEmpty() ? "bare hands" : bot.getMainHandStack().getName().getString();
            String location = blockedPos.equals(head) ? "above" : blockedPos.equals(feet) ? "at feet" : "nearby";

            // Send urgent alert
            alertSuffocationWithDetails(bot, blockName, toolName, location);

            // Start time-based mining (throttled)
            MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                UUID uuid = bot.getUuid();
                long now = srv.getTicks();
                long lastAttempt = LAST_MINING_ESCAPE_ATTEMPT.getOrDefault(uuid, -1L);

                if (lastAttempt < 0 || (now - lastAttempt) >= MINING_ESCAPE_COOLDOWN_TICKS) {
                    LAST_MINING_ESCAPE_ATTEMPT.put(uuid, now);
                    LOGGER.info("Bot {} stuck in {} {} - mining with {}",
                            bot.getName().getString(), blockName, location, toolName);
                    MiningTool.mineBlock(bot, blockedPos);
                    return true;
                }
            }
        }

        return false;
    }

    public static boolean ensureHeadspaceClearance(ServerPlayerEntity bot) {
        // Programmatic block breaking disabled
        return true; // Always return true to allow bot to continue
    }

    public static void checkForSpawnInBlocks(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) {
            return;
        }

        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();

        BlockState headState = world.getBlockState(head);
        BlockState feetState = world.getBlockState(feet);

        // If we're inside a protected block (chest/bed/fence/etc), never mine it; nudge out.
        if ((isRescueProtectedBlock(headState) && headState.blocksMovement() && !headState.getCollisionShape(world, head).isEmpty())
                || (isRescueProtectedBlock(feetState) && feetState.blocksMovement() && !feetState.getCollisionShape(world, feet).isEmpty())) {
            attemptEscapeMovement(bot, world, feet, head);
            return;
        }

        BlockPos targetPos = null;
        BlockState targetState = null;
        String location = "";

        // Check if bot is inside blocks (avoid protected partial blocks like fences/walls/gates).
        if (!headState.isAir() && headState.blocksMovement() && !headState.isOf(Blocks.BEDROCK) && !isRescueProtectedBlock(headState)) {
            targetPos = head;
            targetState = headState;
            location = "above";
        } else if (!feetState.isAir() && feetState.blocksMovement() && !feetState.isOf(Blocks.BEDROCK) && !isRescueProtectedBlock(feetState)) {
            targetPos = feet;
            targetState = feetState;
            location = "at feet";
        }

        if (targetPos != null && targetState != null) {
            String blockName = targetState.getBlock().getName().getString();

            // Select appropriate tool
            String keyword = preferredToolKeyword(targetState);
            if (keyword != null) {
                BotActions.selectBestTool(bot, keyword, "sword");
            }

            String toolName = bot.getMainHandStack().isEmpty() ? "bare hands" : bot.getMainHandStack().getName().getString();

            // Alert player
            String message = String.format("Spawned inside %s! Mining out with %s...", blockName, toolName);
            ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4), message);

            LOGGER.info("Bot {} spawned inside {} {} - starting time-based mining with {}",
                    bot.getName().getString(), blockName, location, toolName);

            // Start mining immediately (no cooldown on spawn check)
            MiningTool.mineBlock(bot, targetPos);

            // Mark as having attempted escape to avoid immediate re-attempts
            MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                LAST_MINING_ESCAPE_ATTEMPT.put(bot.getUuid(), (long) srv.getTicks());
            }
        }
    }

    private static boolean isRescueProtectedBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        // Never mine player structures like fences/walls/gates as "escape"; nudge out instead.
        return state.isIn(BlockTags.FENCES)
                || state.isIn(BlockTags.WALLS)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.ENDER_CHEST)
                || state.isIn(BlockTags.BEDS)
                || state.isIn(BlockTags.SHULKER_BOXES);
    }

    private static boolean tookRecentSuffocation(ServerPlayerEntity bot) {
        DamageSource recent = bot.getRecentDamageSource();
        if (recent == null) {
            return false;
        }
        return recent.isOf(DamageTypes.IN_WALL);
    }

    @SuppressWarnings("unused")
    private static boolean ensureRescueTool(ServerPlayerEntity bot, ServerWorld world, BlockPos center) {
        List<BlockPos> samples = List.of(center, center.up(), center.down());
        for (BlockPos sample : samples) {
            BlockState state = world.getBlockState(sample);
            String keyword = preferredToolKeyword(state);
            if (keyword != null && BotActions.selectBestTool(bot, keyword, "sword")) {
                return true;
            }
        }
        return BotActions.selectBestTool(bot, "pickaxe", "sword")
                || BotActions.selectBestTool(bot, "shovel", "sword")
                || BotActions.selectBestTool(bot, "axe", "sword");
    }

    private static String preferredToolKeyword(BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
            return "pickaxe";
        }
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) {
            return "shovel";
        }
        if (state.isIn(BlockTags.AXE_MINEABLE)) {
            return "axe";
        }
        return null;
    }

    private static Direction findNearestAirDirection(ServerWorld world, BlockPos feet, BlockPos head) {
        Direction[] horizontalDirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction bestDir = null;
        int shortestDistance = Integer.MAX_VALUE;

        for (Direction dir : horizontalDirs) {
            for (int distance = 1; distance <= 3; distance++) {
                BlockPos checkFeet = feet.offset(dir, distance);
                BlockPos checkHead = checkFeet.up();

                BlockState feetState = world.getBlockState(checkFeet);
                BlockState headState = world.getBlockState(checkHead);

                // Found air space the bot can stand in
                if (feetState.isAir() && headState.isAir()) {
                    if (distance < shortestDistance) {
                        shortestDistance = distance;
                        bestDir = dir;
                    }
                    break; // Found air in this direction, check next direction
                }
            }
        }

        return bestDir;
    }

    private static void alertSuffocation(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        ServerCommandSource source = bot.getCommandSource();
        MinecraftServer srv = source != null ? source.getServer() : null;
        if (srv == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        long now = srv.getTicks();
        long last = LAST_SUFFOCATION_ALERT_TICK.getOrDefault(uuid, -1L);
        if (last >= 0 && (now - last) < SUFFOCATION_ALERT_COOLDOWN_TICKS) {
            return;
        }
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), "I'm suffocating!");
        LAST_SUFFOCATION_ALERT_TICK.put(uuid, now);
    }

    private static boolean attemptEscapeMovement(ServerPlayerEntity bot, ServerWorld world, BlockPos feet, BlockPos head) {
        Direction[] allDirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};

        long nowMs = System.currentTimeMillis();
        long lastNudge = LAST_ESCAPE_NUDGE_MS.getOrDefault(bot.getUuid(), -1L);
        if (nowMs - lastNudge < ESCAPE_NUDGE_COOLDOWN_MS) {
            return false;
        }

        for (Direction dir : allDirs) {
            BlockPos checkFeet = feet.offset(dir);
            BlockPos checkHead = checkFeet.up();

            BlockState feetState = world.getBlockState(checkFeet);
            BlockState headState = world.getBlockState(checkHead);

            // Found adjacent clear space
            if (feetState.isAir() && headState.isAir()) {
                Vec3d targetPos = Vec3d.ofCenter(checkFeet);
                Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
                Vec3d direction = targetPos.subtract(currentPos).normalize();

                // Apply movement velocity toward clear space
                bot.setVelocity(direction.multiply(0.3));
                bot.velocityModified = true;
                LAST_ESCAPE_NUDGE_MS.put(bot.getUuid(), nowMs);

                LOGGER.info("Bot {} attempting escape movement toward {}", bot.getName().getString(), dir);
                return true;
            }
        }

        return false;
    }

    private static void alertSuffocationWithDetails(ServerPlayerEntity bot, String blockName, String toolName, String location) {
        if (bot == null) {
            return;
        }
        ServerCommandSource source = bot.getCommandSource();
        MinecraftServer srv = source != null ? source.getServer() : null;
        if (srv == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        long now = srv.getTicks();
        long last = LAST_SUFFOCATION_ALERT_TICK.getOrDefault(uuid, -1L);
        if (last >= 0 && (now - last) < SUFFOCATION_ALERT_COOLDOWN_TICKS) {
            return;
        }
        String message = String.format("I'm stuck in %s %s! Mining with %s...", blockName, location, toolName);
        ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), message);
        LAST_SUFFOCATION_ALERT_TICK.put(uuid, now);
    }

    public static void tickBurialRescue(MinecraftServer server) {
        if (server == null || BotRegistry.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        if (lastBurialScanTick == now) {
            return;
        }
        lastBurialScanTick = now;
        for (UUID uuid : BotRegistry.ids()) {
            ServerPlayerEntity candidate = server.getPlayerManager().getPlayer(uuid);
            if (candidate != null && candidate.isAlive()) {
                rescueFromBurial(candidate);

                // Also check if bot is crawling and can stand up
                if (candidate.isCrawling() || candidate.isSwimming()) {
                    // Ensure headspace is clear before standing
                    ensureHeadspaceClearance(candidate);
                }
            }
        }
    }
}
