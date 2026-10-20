package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.PlayerUtils.BreakTimePolicy;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Shared shoreline escape helpers for tasks that require dry footing.
 */
public final class BotWaterEscapeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-water-escape");

    /** Wait for one ledge-cell swing when the block's break time cannot be read. */
    static final long CARVE_MINE_FALLBACK_TIMEOUT_MS = 45_000L;
    /** Added to MiningTool's own deadline so its timeout, not ours, normally ends a slow swing. */
    static final long CARVE_MINE_MARGIN_MS = 3_000L;
    /** Let the break land before re-reading the cell (same settle as BotFleeService's dig-down). */
    private static final long CARVE_SETTLE_MS = 200L;
    private static final long SERVER_READ_TIMEOUT_MS = 2_000L;
    /** How far up the bot's column to look for the water's top cell. */
    private static final int WATER_SURFACE_SCAN = 4;
    /** "No side to carve" repeats every recovery cycle: INFO once a minute per bot, else DEBUG. */
    private static final long CARVE_LOG_INTERVAL_MS = 60_000L;
    private static final Map<UUID, String> CARVE_LOG_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CARVE_LOG_TIME = new ConcurrentHashMap<>();

    private BotWaterEscapeService() {
    }

    /** Forget one bot's carve-log throttle. */
    public static void clearLogThrottle(UUID botId) {
        if (botId != null) {
            CARVE_LOG_STATE.remove(botId);
            CARVE_LOG_TIME.remove(botId);
        }
    }

    /** Server shutdown: forget every bot's carve-log throttle. */
    public static void clearAllLogThrottles() {
        CARVE_LOG_STATE.clear();
        CARVE_LOG_TIME.clear();
    }

    /**
     * Carve a one-block ledge beside a bot floating in a flooded pocket with no shore, then swim
     * onto it (finding 48). With {@code W} the top water cell of the bot's column and {@code N} a
     * solid horizontal neighbour of it, the bot ends standing in {@code N.up()} on {@code N}, so
     * {@code N.up()} and {@code N.up(2)} are mined when not already empty. The side comes from
     * {@link ShoreStepPolicy}: at most two natural, unprotected blocks, never a cell whose opening
     * would let fluid in or drop a falling block, shallowest column to sky first.
     *
     * <p>Worker thread only (blocks on mining and swimming). World reads follow the rest of this
     * class (read-only off-thread); the mining itself goes through {@link MiningTool}, which keeps
     * its own claim, village and never-break gates.</p>
     *
     * @return true when the bot ends out of the water
     */
    public static boolean carveShoreStep(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!isInWater(bot)) {
            return true;
        }
        String name = bot.getName().getString();
        UUID botId = bot.getUuid();

        BlockPos top = waterSurface(world, bot.getBlockPos());
        if (top == null) {
            logCarveSkip(bot, "no open air above the water (submerged under a ceiling)");
            return false;
        }

        Direction[] dirs = Direction.Type.HORIZONTAL.stream().toArray(Direction[]::new);
        List<ShoreStepPolicy.Side> sides = new ArrayList<>(dirs.length);
        for (int i = 0; i < dirs.length; i++) {
            sides.add(assessSide(bot, world, top.offset(dirs[i]), i));
        }
        int chosen = ShoreStepPolicy.choose(sides);
        if (chosen == ShoreStepPolicy.NO_SIDE) {
            logCarveSkip(bot, "no side of natural rock to carve " + describeSides(sides, dirs));
            return false;
        }

        BlockPos floor = top.offset(dirs[chosen]);
        ShoreStepPolicy.Side side = sides.get(chosen);
        LOGGER.info("Bot {} trapped in water at {}: carving a ledge on {} floor={} mine={} depthToSky={}",
                name, bot.getBlockPos().toShortString(), dirs[chosen].asString(), floor.toShortString(),
                ShoreStepPolicy.blocksToMine(side), side.depthToSky());

        if (side.feet() == ShoreStepPolicy.Cell.MINEABLE && !mineLedgeCell(bot, world, floor.up())) {
            return false;
        }
        if (TaskService.isAbortRequested(botId)) {
            return false;
        }
        if (side.head() == ShoreStepPolicy.Cell.MINEABLE && !mineLedgeCell(bot, world, floor.up(2))) {
            return false;
        }
        if (TaskService.isAbortRequested(botId)) {
            return false;
        }

        boolean out = swimToShore(bot, 3, 4_000L);
        LOGGER.info("Bot {} ledge at {} ready, climbed out={} now at {}",
                name, floor.up().toShortString(), out, bot.getBlockPos().toShortString());
        if (out) {
            clearLogThrottle(botId);
        }
        return out;
    }

    /** The top water cell of the column at {@code feet} when the cell above it is open air, else null. */
    private static BlockPos waterSurface(ServerWorld world, BlockPos feet) {
        BlockPos cell = feet;
        if (!world.getFluidState(cell).isIn(FluidTags.WATER)) {
            cell = cell.up();
            if (!world.getFluidState(cell).isIn(FluidTags.WATER)) {
                return null;
            }
        }
        for (int i = 0; i < WATER_SURFACE_SCAN && world.getFluidState(cell.up()).isIn(FluidTags.WATER); i++) {
            cell = cell.up();
        }
        BlockPos above = cell.up();
        if (!world.getFluidState(above).isEmpty()
                || !world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
            return null;
        }
        return cell.toImmutable();
    }

    private static ShoreStepPolicy.Side assessSide(ServerPlayerEntity bot, ServerWorld world, BlockPos floor, int index) {
        if (!world.isChunkLoaded(floor)) {
            return new ShoreStepPolicy.Side(index, false, ShoreStepPolicy.Cell.BLOCKED,
                    ShoreStepPolicy.Cell.BLOCKED, true, -1);
        }
        BlockState floorState = world.getBlockState(floor);
        boolean floorSolid = world.getFluidState(floor).isEmpty()
                && !floorState.getCollisionShape(world, floor).isEmpty();
        BlockPos feet = floor.up();
        BlockPos head = floor.up(2);
        boolean hazard = fluidBeside(world, feet) || fluidBeside(world, head)
                || !world.getFluidState(head.up()).isEmpty()
                || world.getBlockState(head.up()).getBlock() instanceof FallingBlock;
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, floor.getX(), floor.getZ());
        int depthToSky = Math.max(0, topY - feet.getY());
        return new ShoreStepPolicy.Side(index, floorSolid, classifyCell(bot, world, feet),
                classifyCell(bot, world, head), hazard, depthToSky);
    }

    private static boolean fluidBeside(ServerWorld world, BlockPos pos) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (!world.getFluidState(pos.offset(dir)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stand cell is mineable only when it is natural stone, dirt or ore (never a player build),
     * breakable, not a falling block, and outside protected zones and mapped villages.
     */
    private static ShoreStepPolicy.Cell classifyCell(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!world.getFluidState(pos).isEmpty()) {
            return ShoreStepPolicy.Cell.BLOCKED;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return ShoreStepPolicy.Cell.OPEN;
        }
        if (state.getHardness(world, pos) < 0.0F
                || state.getBlock() instanceof FallingBlock
                || !isNaturalLedgeRock(state)
                || ProtectedStructureBlockHelper.isNeverBreakAt(world, pos)
                || ProtectedZoneService.isProtectedForBot(bot.getUuid(), pos, world, null)
                || MappedVillageService.isInsideMappedVillage(world, pos)) {
            return ShoreStepPolicy.Cell.BLOCKED;
        }
        return ShoreStepPolicy.Cell.MINEABLE;
    }

    private static boolean isNaturalLedgeRock(BlockState state) {
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.COAL_ORES)
                || state.isIn(BlockTags.IRON_ORES)
                || state.isIn(BlockTags.COPPER_ORES)
                || state.isIn(BlockTags.GOLD_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES)
                || state.isIn(BlockTags.LAPIS_ORES)
                || state.isIn(BlockTags.EMERALD_ORES)
                || state.isIn(BlockTags.DIAMOND_ORES);
    }

    /**
     * Mines one ledge cell and waits as long as MiningTool itself would: its deadline is
     * {@link BreakTimePolicy#timeoutMs} of the ticks needed with the tool it just picked, which in
     * water or off the ground can run to minutes (stone by hand). A swing still running after that
     * is cancelled, so the next recovery cycle never starts a second swing on the same cell. The
     * cell is then re-read on the server thread after a short settle.
     */
    private static boolean mineLedgeCell(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        MinecraftServer server = world.getServer();
        CompletableFuture<String> mining = MiningTool.mineBlock(bot, pos, false);
        long waitMs = CARVE_MINE_FALLBACK_TIMEOUT_MS;
        if (!mining.isDone()) {
            // MiningTool picked and equipped its tool before returning, so this is the delta it armed its deadline with.
            Float delta = callOnServer(server,
                    () -> world.getBlockState(pos).calcBlockBreakingDelta(bot, world, pos),
                    SERVER_READ_TIMEOUT_MS, null);
            waitMs = carveWaitMs(delta == null ? Float.NaN : delta);
        }
        String outcome;
        try {
            outcome = mining.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            mining.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            mining.cancel(true);
            outcome = "no result after " + waitMs + "ms, swing cancelled";
        } catch (Exception e) {
            outcome = "error: " + e.getClass().getSimpleName();
        }
        if (!mining.isDone()) {
            mining.cancel(true);
        }
        try {
            Thread.sleep(CARVE_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean open = Boolean.TRUE.equals(callOnServer(server,
                () -> world.getBlockState(pos).getCollisionShape(world, pos).isEmpty(),
                SERVER_READ_TIMEOUT_MS, Boolean.FALSE));
        if (!open) {
            LOGGER.info("Bot {} could not open ledge cell {}: {}",
                    bot.getName().getString(), pos.toShortString(), outcome);
        }
        return open;
    }

    /**
     * How long to wait for one ledge-cell swing: MiningTool's own deadline for a block with this
     * break delta plus {@link #CARVE_MINE_MARGIN_MS}, or the fallback when the delta is unknown or
     * the block cannot be broken.
     */
    static long carveWaitMs(float delta) {
        if (Float.isNaN(delta) || delta <= 0.0f) {
            return CARVE_MINE_FALLBACK_TIMEOUT_MS;
        }
        return BreakTimePolicy.timeoutMs(BreakTimePolicy.requiredTicks(delta)) + CARVE_MINE_MARGIN_MS;
    }

    /** Bounded server-thread read from a worker; runs inline when already on the server thread. */
    private static <T> T callOnServer(MinecraftServer server, Supplier<T> action, long timeoutMs, T fallback) {
        if (server == null) {
            return fallback;
        }
        if (server.isOnThread()) {
            return action.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String describeSides(List<ShoreStepPolicy.Side> sides, Direction[] dirs) {
        StringBuilder sb = new StringBuilder("[");
        for (ShoreStepPolicy.Side s : sides) {
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(dirs[s.side()].asString()).append(':')
                    .append(s.floorSolid() ? "floor" : "nofloor").append('/')
                    .append(s.feet()).append('/').append(s.head())
                    .append(s.hazardNear() ? "/hazard" : "");
        }
        return sb.append(']').toString();
    }

    private static void logCarveSkip(ServerPlayerEntity bot, String why) {
        UUID id = bot.getUuid();
        boolean changed = LogThrottle.changed(CARVE_LOG_STATE, id, why);
        boolean due = LogThrottle.due(CARVE_LOG_TIME, id, System.currentTimeMillis(), CARVE_LOG_INTERVAL_MS);
        if (changed || due) {
            LOGGER.info("Bot {} trapped in water, no ledge carved: {}", bot.getName().getString(), why);
        } else {
            LOGGER.debug("Bot {} trapped in water, no ledge carved: {}", bot.getName().getString(), why);
        }
    }

    public static boolean isInWater(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos feet = bot.getBlockPos();
        return bot.isTouchingWater()
                || bot.isSwimming()
                || bot.isSubmergedInWater()
                || world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    /**
     * Swim to a shore stand without the cliff-check that normal land nav uses.
     *
     * <p>Generic navigation ({@link net.wcfcarolina13.GameAI.services.MovementService}
     * and skill-level helpers like FishingSkill#safeNudge) refuse to step onto a
     * block whose below-neighbour has an empty collision shape, because that's
     * how they detect cliff edges.  A swimming bot sits on water — empty
     * collision shape — so every adjacent block looks like a cliff and the bot
     * gives up on reaching shore.
     *
     * <p>This helper drives swim-forward input + auto-jump directly, polling on
     * the skill worker thread.  Iterates multiple shore candidates so a single
     * unreachable stand doesn't abort the whole attempt.  Returns true when
     * {@link #isInWater} goes false.
     *
     * <p>Must be called from a worker thread (blocks via {@code Thread.sleep}).
     * Returns false if the bot is aborted, the timeout elapses, or no
     * candidate produces a water-exit.
     */
    public static boolean swimToShore(ServerPlayerEntity bot, int searchRadius, long totalTimeoutMs) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) return false;
        if (!isInWater(bot)) return true;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;

        List<BlockPos> candidates = collectShoreStandCandidates(bot, world, Math.max(3, searchRadius), 5);
        if (candidates.isEmpty()) return false;

        long overallDeadline = System.currentTimeMillis() + Math.max(1000L, totalTimeoutMs);
        long perCandidateMs = Math.max(1500L, totalTimeoutMs / Math.max(1, candidates.size()));

        for (BlockPos target : candidates) {
            if (System.currentTimeMillis() >= overallDeadline) break;
            if (bot.isRemoved() || !bot.isAlive()) return false;
            if (!isInWater(bot)) return true;

            if (swimTowardOnce(bot, target, perCandidateMs, overallDeadline)) {
                return true;
            }
        }
        return !isInWater(bot);
    }

    private static boolean swimTowardOnce(ServerPlayerEntity bot, BlockPos target,
                                          long perCandidateMs, long overallDeadline) {
        long deadline = Math.min(System.currentTimeMillis() + perCandidateMs, overallDeadline);
        int stagnantTicks = 0;
        double lastDistSq = Double.MAX_VALUE;

        while (System.currentTimeMillis() < deadline) {
            if (bot.isRemoved() || !bot.isAlive()) return false;
            if (!isInWater(bot)) return true;

            Vec3d targetCenter = Vec3d.ofCenter(target);
            LookController.faceBlock(bot, target);
            BotActions.sprint(bot, false); // no sprint — swim is slow, sprint-swim can overshoot
            // Full forward impulse.  Unlike safeNudge we intentionally skip the
            // "is block-below empty?" cliff check — we're already in water, so
            // the concept doesn't apply.
            BotActions.applyMovementInput(bot, targetCenter, 1.0D);
            // Auto-jump handles climbing onto the shore block once we brush it.
            BotActions.autoJumpIfNeeded(bot);

            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }

            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= 2.25D) {
                // Arrived at the stand — one extra jump-assist in case we're
                // on the water column directly below the shore block.
                BotActions.autoJumpIfNeeded(bot);
                try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                if (!isInWater(bot)) return true;
            }

            // Stuck-detection: if we haven't made meaningful progress for ~2s, bail to the next candidate.
            if (distSq >= lastDistSq - 0.01D) {
                stagnantTicks++;
                if (stagnantTicks >= 20) {
                    return false;
                }
            } else {
                stagnantTicks = 0;
            }
            lastDistSq = distSq;
        }
        return !isInWater(bot);
    }

    /**
     * Returns up to {@code maxCandidates} shore-adjacent dry stands, ordered by the same
     * shoreline-priority score used in {@link #findNearestShoreStand}.
     */
    private static List<BlockPos> collectShoreStandCandidates(ServerPlayerEntity bot, ServerWorld world,
                                                              int radius, int maxCandidates) {
        BlockPos origin = bot.getBlockPos();
        record Scored(BlockPos pos, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (BlockPos candidate : BlockPos.iterate(origin.add(-radius, -3, -radius), origin.add(radius, 4, radius))) {
            if (!world.isChunkLoaded(candidate)) continue;
            if (!isStandable(world, candidate)) continue;

            int dx = candidate.getX() - origin.getX();
            int dz = candidate.getZ() - origin.getZ();
            int dy = candidate.getY() - origin.getY();
            double horizontalDistSq = dx * dx + dz * dz;
            double verticalPenalty = Math.abs(dy) * 1.4D;
            double shorelinePenalty = isAdjacentToWater(world, candidate) ? 0.0D : 7.0D;
            scored.add(new Scored(candidate.toImmutable(), horizontalDistSq + verticalPenalty + shorelinePenalty));
        }
        scored.sort(Comparator.comparingDouble(Scored::score));

        List<BlockPos> out = new ArrayList<>(Math.min(maxCandidates, scored.size()));
        for (int i = 0; i < Math.min(maxCandidates, scored.size()); i++) {
            out.add(scored.get(i).pos());
        }
        return out;
    }

    /**
     * Find nearby dry land while preferring shoreline-adjacent stands.
     */
    public static BlockPos findNearestShoreStand(ServerPlayerEntity bot, int radius) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return null;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        int r = Math.max(3, radius);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos candidate : BlockPos.iterate(origin.add(-r, -3, -r), origin.add(r, 4, r))) {
            if (!world.isChunkLoaded(candidate)) {
                continue;
            }
            if (!isStandable(world, candidate)) {
                continue;
            }

            int dx = candidate.getX() - origin.getX();
            int dz = candidate.getZ() - origin.getZ();
            int dy = candidate.getY() - origin.getY();
            double horizontalDistSq = dx * dx + dz * dz;
            double verticalPenalty = Math.abs(dy) * 1.4D;
            boolean shoreline = isAdjacentToWater(world, candidate);
            double shorelinePenalty = shoreline ? 0.0D : 7.0D;
            double score = horizontalDistSq + verticalPenalty + shorelinePenalty;

            if (score < bestScore) {
                bestScore = score;
                best = candidate.toImmutable();
            }
        }
        return best;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        if (world.getFluidState(feet).isIn(FluidTags.WATER) || world.getFluidState(feet.up()).isIn(FluidTags.WATER)) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        BlockPos floor = feet.down();
        BlockState floorState = world.getBlockState(floor);
        return !floorState.getCollisionShape(world, floor).isEmpty();
    }

    private static boolean isAdjacentToWater(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (!world.isChunkLoaded(side)) {
                continue;
            }
            if (world.getFluidState(side).isIn(FluidTags.WATER) || world.getFluidState(side.down()).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
