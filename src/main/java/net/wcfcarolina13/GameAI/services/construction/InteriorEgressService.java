package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Band;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Cell;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Doorway;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Footprint;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Route;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walks a bot out of an active construction footprint through its doorway before a move to
 * a stance on the far side of a wall. Geometry lives in {@link InteriorEgressPolicy}; this
 * class reads the live world and drives {@link MovementService}.
 *
 * <p>Threading: blocking. Call only from the skill worker thread, the same place the
 * surrounding construction movement ({@code MovementService.execute}) already runs. On the
 * server thread it refuses and returns {@link Outcome#NOT_NEEDED}.</p>
 *
 * <p>Failure is bounded: every outcome other than {@link Outcome#OK} leaves the caller on its
 * existing path, and two consecutive failures switch egress off for the rest of the
 * construction session (Fortify's {@code gateRoutingFailures} rule).</p>
 */
public final class InteriorEgressService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-egress");
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final double ARRIVED_XZ_SQ = 1.0D;
    private static final double APPROACH_TOLERANCE_XZ_SQ = 4.0D;
    private static final double OPENING_TOLERANCE_XZ_SQ = 2.25D;
    private static final int EXTENSION_DISTANCE = 2;

    private static final ConcurrentHashMap<UUID, SessionState> STATE = new ConcurrentHashMap<>();

    private InteriorEgressService() {}

    public enum Outcome {
        /** No active construction, bot not strictly inside, or the target is inside too. */
        NOT_NEEDED,
        /** Two consecutive failures this session; caller keeps its current behaviour. */
        SUPPRESSED,
        /** Egress was needed but no open doorway gives a clear corridor. */
        NO_DOORWAY,
        OK,
        FAILED
    }

    private record SessionState(long sessionId, int consecutiveFailures, boolean noDoorwayLogged) {}

    /** Snapshot of the footprint the bot is inside, at its current walking band. */
    private record Context(ConstructionProtectionService.EgressView view, Band band, Footprint footprint,
                           int navY, BlockPos botPos) {}

    /**
     * If the bot is strictly inside the active construction footprint and neither
     * {@code target} nor {@code stance} is, walk it out through the best doorway.
     *
     * @param target the caller's requested position
     * @param stance where the mover will actually stand for it (null = same as target)
     */
    public static Outcome egressIfNeeded(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         BlockPos target,
                                         BlockPos stance) {
        if (source == null || bot == null || target == null) {
            return Outcome.NOT_NEEDED;
        }
        BlockPos resolvedStance = stance != null ? stance : target;
        Context ctx = context(bot).orElse(null);
        if (ctx == null || !InteriorEgressPolicy.needsEgress(ctx.footprint(),
                ctx.botPos().getX(), ctx.botPos().getZ(),
                target.getX(), target.getZ(),
                resolvedStance.getX(), resolvedStance.getZ())) {
            return Outcome.NOT_NEEDED;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Outcome.NOT_NEEDED;
        }
        if (world.getServer() != null && world.getServer().isOnThread()) {
            LOGGER.warn("[egress] bot={} refused on the server thread (movement blocks); target={}",
                    bot.getName().getString(), target.toShortString());
            return Outcome.NOT_NEEDED;
        }

        UUID botId = bot.getUuid();
        SessionState state = sessionState(botId, ctx.view().sessionId());
        if (state.consecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            LOGGER.debug("[egress] bot={} plan={} suppressed after {} failures",
                    bot.getName().getString(), ctx.view().taskId(), state.consecutiveFailures());
            return Outcome.SUPPRESSED;
        }

        Optional<Route> route = findRoute(world, ctx, target.getX(), target.getZ());
        if (route.isEmpty()) {
            if (!state.noDoorwayLogged()) {
                STATE.put(botId, new SessionState(state.sessionId(), state.consecutiveFailures(), true));
                LOGGER.info("[egress] bot={} plan={} door=none waypoints=0 outcome=skipped:no-doorway footprint={} y={} target={}",
                        bot.getName().getString(), ctx.view().taskId(), ctx.footprint().summary(),
                        ctx.navY(), target.toShortString());
            }
            return Outcome.NO_DOORWAY;
        }

        Route r = route.get();
        String failedStep = walkRoute(source, bot, ctx, r);
        boolean ok = failedStep == null;
        int failures = ok ? 0 : state.consecutiveFailures() + 1;
        STATE.put(botId, new SessionState(state.sessionId(), failures, state.noDoorwayLogged()));
        LOGGER.info("[egress] bot={} plan={} door={},{} src={} waypoints={} approachSkipped={} outcome={} botPos={} target={}{}",
                bot.getName().getString(),
                ctx.view().taskId(),
                r.doorway().x(), r.doorway().z(),
                r.doorway().source(),
                r.waypoints().size(),
                r.approachSkippable(),
                ok ? "ok" : "failed:" + failedStep,
                bot.getBlockPos().toShortString(),
                target.toShortString(),
                failures >= MAX_CONSECUTIVE_FAILURES ? " suppressed=true" : "");
        return ok ? Outcome.OK : Outcome.FAILED;
    }

    /**
     * Footprint that currently governs straight-line crossings, or empty when egress does not
     * apply: no active construction, bot not strictly inside, egress suppressed for the session,
     * or no doorway gives a route (then the old straight-line behaviour stays available).
     * Callers test candidates with {@link InteriorEgressPolicy#isLongCrossing}.
     */
    public static Optional<Footprint> crossingGuard(ServerPlayerEntity bot) {
        if (bot == null) {
            return Optional.empty();
        }
        Context ctx = context(bot).orElse(null);
        if (ctx == null || !ctx.footprint().strictlyInside(ctx.botPos().getX(), ctx.botPos().getZ())) {
            return Optional.empty();
        }
        SessionState state = STATE.get(bot.getUuid());
        if (state != null && state.sessionId() == ctx.view().sessionId()
                && state.consecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            return Optional.empty();
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        // Route existence does not depend on the target, only the doorway ordering does.
        if (findRoute(world, ctx, ctx.botPos().getX(), ctx.botPos().getZ()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ctx.footprint());
    }

    /** Convenience for a single straight-line move. */
    public static boolean blocksStraightLineTo(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        BlockPos botPos = bot.getBlockPos();
        return crossingGuard(bot)
                .map(f -> InteriorEgressPolicy.isLongCrossing(f, botPos.getX(), botPos.getZ(), target.getX(), target.getZ()))
                .orElse(false);
    }

    public static void clear(UUID botId) {
        if (botId != null) {
            STATE.remove(botId);
        }
    }

    private static Optional<Context> context(ServerPlayerEntity bot) {
        Optional<ConstructionProtectionService.EgressView> view = ConstructionProtectionService.egressView(bot.getUuid());
        if (view.isEmpty()) {
            return Optional.empty();
        }
        BlockPos botPos = bot.getBlockPos();
        // Guardrail "Gate Routing Y": the bot's actual Y, never a heightmap Y.
        int navY = botPos.getY();
        Band band = view.get().layout().bandAt(navY);
        if (band.footprint() == null) {
            return Optional.empty();
        }
        return Optional.of(new Context(view.get(), band, band.footprint(), navY, botPos));
    }

    private static SessionState sessionState(UUID botId, long sessionId) {
        SessionState current = STATE.get(botId);
        if (current == null || current.sessionId() != sessionId) {
            current = new SessionState(sessionId, 0, false);
            STATE.put(botId, current);
        }
        return current;
    }

    /**
     * Designed doorways (schematic gaps, planned doors) that are open in the world right now;
     * if none routes, unbuilt wall columns. The corridor check uses live obstacles, not the
     * plan: a planned floor or wall that is not built yet does not block the walk.
     */
    private static Optional<Route> findRoute(ServerWorld world, Context ctx, int targetX, int targetZ) {
        Footprint f = ctx.footprint();
        Set<Long> nowBlocked = new HashSet<>();
        for (int x = f.minX(); x <= f.maxX(); x++) {
            for (int z = f.minZ(); z <= f.maxZ(); z++) {
                if (!isWalkableColumn(world, x, ctx.navY(), z)) {
                    nowBlocked.add(InteriorEgressPolicy.packColumn(x, z));
                }
            }
        }
        int botX = ctx.botPos().getX();
        int botZ = ctx.botPos().getZ();

        List<Doorway> open = new ArrayList<>();
        for (Doorway doorway : ctx.band().designedDoorways()) {
            if (!nowBlocked.contains(InteriorEgressPolicy.packColumn(doorway.x(), doorway.z()))) {
                open.add(doorway);
            }
        }
        Optional<Route> route = InteriorEgressPolicy.chooseRoute(f, open, nowBlocked, botX, botZ, targetX, targetZ);
        if (route.isPresent()) {
            return route;
        }
        List<Doorway> gaps = InteriorEgressPolicy.unbuiltGaps(f, ctx.band().blockedColumns(), nowBlocked);
        return InteriorEgressPolicy.chooseRoute(f, gaps, nowBlocked, botX, botZ, targetX, targetZ);
    }

    /** Feet and head cells passable: no collision, or a door/gate the mover can open. */
    private static boolean isWalkableColumn(ServerWorld world, int x, int feetY, int z) {
        for (int y = feetY; y <= feetY + 1; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (state.getCollisionShape(world, pos).isEmpty()) {
                continue;
            }
            if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Walks approach -> opening -> exit. Returns null on success (bot no longer strictly inside),
     * else the step that failed. Arrival is judged in XZ: the doorway may have no floor under it
     * and the exit may sit a block lower, and MovementService re-targets a non-standable
     * destination to the nearest standable cell.
     */
    private static String walkRoute(ServerCommandSource source, ServerPlayerEntity bot, Context ctx, Route route) {
        Footprint f = ctx.footprint();
        if (!route.approachSkippable()) {
            walkLeg(source, bot, route.approach(), ctx.navY());
            if (SkillManager.shouldAbortSkill(bot)) {
                return "aborted";
            }
            if (xzDistSq(bot, route.approach()) > APPROACH_TOLERANCE_XZ_SQ) {
                return "approach";
            }
        }

        walkLeg(source, bot, route.opening(), ctx.navY());
        if (SkillManager.shouldAbortSkill(bot)) {
            return "aborted";
        }
        boolean reachedOpening = xzDistSq(bot, route.opening()) <= OPENING_TOLERANCE_XZ_SQ
                || !f.strictlyInside(bot.getBlockPos().getX(), bot.getBlockPos().getZ());

        walkLeg(source, bot, route.exit(), ctx.navY());
        if (SkillManager.shouldAbortSkill(bot)) {
            return "aborted";
        }
        BlockPos now = bot.getBlockPos();
        if (f.contains(now.getX(), now.getZ())) {
            // Still in the wall line or interior: one push further along the outward normal.
            Doorway d = route.doorway();
            Cell further = new Cell(route.exit().x() + d.outDx() * EXTENSION_DISTANCE,
                    route.exit().z() + d.outDz() * EXTENSION_DISTANCE);
            walkLeg(source, bot, further, ctx.navY());
            now = bot.getBlockPos();
        }
        if (f.strictlyInside(now.getX(), now.getZ())) {
            return reachedOpening ? "exit" : "opening";
        }
        return null;
    }

    private static void walkLeg(ServerCommandSource source, ServerPlayerEntity bot, Cell waypoint, int navY) {
        if (xzDistSq(bot, waypoint) <= ARRIVED_XZ_SQ) {
            return;
        }
        BlockPos dest = new BlockPos(waypoint.x(), navY, waypoint.z());
        // A DIRECT plan straight at the waypoint, not planLootApproach: that one stands on top
        // of a solid neighbour when it finds one, which beside a door frame is a wall top.
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, dest, dest, null, null, Direction.UP);
        // Same flags as the surrounding construction moves; door-escape subgoals off so the
        // mover keeps to the aligned waypoints (Fortify gate routing does the same).
        MovementService.withoutDoorEscape(() ->
                MovementService.execute(source, bot, plan, false, true, true, false));
    }

    private static double xzDistSq(ServerPlayerEntity bot, Cell cell) {
        double dx = bot.getX() - (cell.x() + 0.5D);
        double dz = bot.getZ() - (cell.z() + 0.5D);
        return dx * dx + dz * dz;
    }
}
