package net.wcfcarolina13.PathFinding;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.wcfcarolina13.GameAI.services.BotCampfireAvoidanceService;
import net.wcfcarolina13.PathFinding.unified.ExecUnit;
import net.wcfcarolina13.PathFinding.unified.NoPathReason;
import net.wcfcarolina13.PathFinding.unified.PathPlan;
import net.wcfcarolina13.PathFinding.unified.PathPlanIds;
import net.wcfcarolina13.PathFinding.unified.PlanAdapter;
import net.wcfcarolina13.PathFinding.unified.PlanRequest;
import net.wcfcarolina13.PathFinding.unified.PlanStatus;
import net.wcfcarolina13.PathFinding.unified.PlannedMove;
import net.wcfcarolina13.PathFinding.unified.SearchContext;
import net.wcfcarolina13.PathFinding.unified.UnifiedPathFinder;
import net.wcfcarolina13.PathFinding.unified.WorldTerrainView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

public class PathFinder {

    public static final Logger LOGGER = LoggerFactory.getLogger("frens");
    private static final int MAX_SEGMENT_MANHATTAN_SPAN = 6;

    /**
     * Which finder {@link #calculatePath} and {@link #plan} use when a bot has no override. UNIFIED
     * is the default since 1.1.261; Frens.java, the shared-config save, the GUI toggle and
     * {@code /bot config pathfinder} copy the saved choice in through
     * {@link PathFinderDefaultPolicy#fromSaved} (null is UNIFIED, "baritone" and "classic" are opt-in).
     */
    public static volatile PathFinderChoice ACTIVE = PathFinderChoice.DEFAULT;

    public static class PathNode {
        public final BlockPos pos;
        public final String blockName;
        public final boolean jump;
        public boolean walkable;

        public PathNode(BlockPos pos, String blockName, boolean walkable, boolean jump) {
            this.pos = pos;
            this.blockName = blockName;
            this.jump = jump;
            this.walkable = walkable;
        }

        @Override
        public String toString() {
            return "PathNode{" +
                    "pos=" + pos +
                    ", blockName='" + blockName + '\'' +
                    ", jump=" + jump +
                    '}';
        }

        public boolean jumpNeeded() {
            return jump;
        }

        public BlockPos getPos() {
            return pos;
        }
    }


    /**
     * Set by either finder on this thread: the last search found no path because the start cell had
     * no executable neighbour (Baritone: open set exhausted after 1 node; bidir: forward open set
     * exhausted after expanding only the start). Thread-local because the shadow compare runs the
     * other finder on its own thread. Reset at the start of every search.
     */
    private static final ThreadLocal<Boolean> LAST_SEARCH_BOXED_IN = ThreadLocal.withInitial(() -> false);

    static void noteSearchBoxedIn(boolean boxedIn) {
        LAST_SEARCH_BOXED_IN.set(boxedIn);
    }

    /** Whether the last search on this thread failed with a boxed-in start (see {@link #noteSearchBoxedIn}). */
    public static boolean lastSearchBoxedIn() {
        return Boolean.TRUE.equals(LAST_SEARCH_BOXED_IN.get());
    }

    /**
     * {planId, target.asLong()} of the last bot plan made on this thread (1.1.242). walkTo plans and
     * records its outcome on the same worker thread, so the outcome line can carry the plan ID.
     */
    private static final ThreadLocal<long[]> LAST_PLAN = new ThreadLocal<>();

    static void noteLastPlan(long planId, BlockPos target) {
        if (planId > 0 && target != null) {
            LAST_PLAN.set(new long[]{planId, target.asLong()});
        }
    }

    /**
     * The plan ID of this thread's last bot plan toward {@code destination}, or 0 when the last plan
     * went elsewhere or there was none. Clears it, so a later walk that never plans gets 0, not a
     * stale ID.
     */
    public static long takeLastPlanId(BlockPos destination) {
        long[] last = LAST_PLAN.get();
        LAST_PLAN.remove();
        if (last == null || destination == null || last[1] != destination.asLong()) {
            return 0L;
        }
        return last[0];
    }

    /**
     * Plans for a known bot and returns today's node list. A per-bot {@link PathFinderOverrides}
     * entry wins over {@link #ACTIVE} (debug/A-B only). With the unified finder the request is
     * {@link PlanRequest#legacyWalk()}: the old move set priced in ticks, so the result is walkable by
     * {@code walkSegment} unchanged. Callers that execute break, place, swim or fall moves use
     * {@link #plan(BlockPos, BlockPos, ServerWorld, ServerPlayerEntity, PlanRequest)} instead.
     */
    public static List<PathNode> calculatePath(BlockPos start, BlockPos target, ServerWorld world,
                                               UUID botUuid, String botName) {
        return dispatch(start, target, world, botUuid, botName, PlanRequest.legacyWalk(),
                LazySnapshot.none()).nodes();
    }

    /**
     * One plan, whatever the finder: the node list every caller can walk, plus the unified
     * {@link PathPlan} and its {@link SearchContext} when the unified finder ran (null otherwise).
     *
     * @param planId  the {@code plan=} token on this plan's log lines ({@link PathPlanIds#format})
     * @param context the context the unified search ran with; its terrain is the searching thread's
     *                view and must not be read from another thread ({@link WorldTerrainView#fresh()})
     */
    public record PlanResult(PathFinderChoice finder, long planId, List<PathNode> nodes, PathPlan plan,
                             SearchContext context, long elapsedMs, boolean boxedIn) {
        public PlanResult {
            nodes = nodes == null ? new ArrayList<>() : nodes;
        }

        public boolean isUnified() {
            return plan != null;
        }

        /** The unified reason, or BOXED_IN / NONE from the old finders' boxed-in flag. */
        public NoPathReason reason() {
            if (plan != null) {
                return plan.reason();
            }
            return nodes.isEmpty() && boxedIn ? NoPathReason.BOXED_IN : NoPathReason.NONE;
        }

        /** The executor's unit list (empty for the old finders, which only have {@link #nodes()}). */
        public List<ExecUnit> units() {
            return plan == null ? List.of() : PlanAdapter.toUnits(plan, context);
        }
    }

    /**
     * The 1.1.242 entry for a bot walk: tool speeds, dirt count, sprint and territory are snapshotted
     * from {@code bot} on the calling thread, and never read live during the search. The snapshot is
     * taken only when something reads it (a unified plan, or the PathCompare shadow), so Baritone and
     * classic walks never touch the bot's inventory here.
     */
    public static PlanResult plan(BlockPos start, BlockPos goal, ServerWorld world, ServerPlayerEntity bot,
                                  PlanRequest req) {
        return dispatch(start, goal, world,
                bot != null ? bot.getUuid() : null,
                bot != null ? bot.getName().getString() : null,
                req != null ? req : PlanRequest.legacyWalk(),
                new LazySnapshot(() -> WorldTerrainView.BotSnapshot.of(bot)));
    }

    /** As above for a bot known only by ID (hand-only tools, no dirt, no territory claim check). */
    public static PlanResult plan(BlockPos start, BlockPos goal, ServerWorld world, UUID botUuid, String botName,
                                  PlanRequest req) {
        return dispatch(start, goal, world, botUuid, botName, req != null ? req : PlanRequest.legacyWalk(),
                LazySnapshot.none());
    }

    /**
     * Reads the bot snapshot at most once, on first use. A failed read is remembered and rethrown,
     * so the unified run and the PathCompare shadow see the same outcome.
     */
    private static final class LazySnapshot {
        /** The no-bot snapshot. A fresh holder per plan: the holder is mutable and plans run on many threads. */
        static LazySnapshot none() {
            return new LazySnapshot(() -> WorldTerrainView.BotSnapshot.NONE);
        }

        private final Supplier<WorldTerrainView.BotSnapshot> source;
        private WorldTerrainView.BotSnapshot value;
        private RuntimeException failure;
        private boolean read;

        LazySnapshot(Supplier<WorldTerrainView.BotSnapshot> source) {
            this.source = source;
        }

        WorldTerrainView.BotSnapshot get() {
            if (!read) {
                read = true;
                try {
                    value = source.get();
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static PlanResult dispatch(BlockPos start, BlockPos target, ServerWorld world, UUID botUuid,
                                       String botName, PlanRequest req, LazySnapshot snapshot) {
        PathFinderChoice choice = PathFinderOverrides.resolveFor(botUuid, ACTIVE);
        noteSearchBoxedIn(false);
        long planId = PathPlanIds.next();
        if (isServerThread(world)) {
            req = req.onServerThread();
        }
        long startTime = System.nanoTime();
        PlanResult result;
        if (choice == PathFinderChoice.UNIFIED) {
            result = runUnifiedSafely(start, target, world, req, snapshot, planId);
        } else {
            result = new PlanResult(choice, planId, runFinder(choice, start, target, world), null, null, 0L, false);
        }
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        result = new PlanResult(result.finder(), planId, result.nodes(), result.plan(), result.context(), elapsedMs,
                result.nodes().isEmpty() && lastSearchBoxedIn());
        if (result.plan() != null) {
            PathPlan p = result.plan();
            LOGGER.info("[PathFinder] mode={} elapsed={}ms pathLen={} from={} to={} plan={} status={} reason={} moves={} nodes={}",
                    choice.label(), elapsedMs, result.nodes().size(), start.toShortString(), target.toShortString(),
                    PathPlanIds.format(planId), p.status(), p.reason(), p.moves().size(), p.nodesExpanded());
        } else {
            LOGGER.info("[PathFinder] mode={} elapsed={}ms pathLen={} from={} to={} plan={}",
                    result.finder().label(), elapsedMs, result.nodes().size(), start.toShortString(),
                    target.toShortString(), PathPlanIds.format(planId));
        }
        if (botName != null) {
            noteLastPlan(planId, target);
            if (PathCompareService.isEnabled()) {
                WorldTerrainView.BotSnapshot shadowSnapshot = null;
                try {
                    shadowSnapshot = snapshot.get();
                } catch (RuntimeException e) {
                    LOGGER.warn("[PathFinder] bot snapshot failed plan={}: {}; skipping PathCompare",
                            PathPlanIds.format(planId), e.toString());
                }
                if (shadowSnapshot != null) {
                    PathCompareService.submit(botName, start, target, world, result, req, shadowSnapshot);
                }
            }
        }
        return result;
    }

    private static boolean isServerThread(ServerWorld world) {
        MinecraftServer server = world != null ? world.getServer() : null;
        return server != null && server.isOnThread();
    }

    /**
     * The unified finder, guarded for the field window: an exception (a world read the view did not
     * expect, or a failed bot snapshot read) logs one WARN and falls back to the Baritone-style finder,
     * so a walk never dies on it.
     */
    private static PlanResult runUnifiedSafely(BlockPos start, BlockPos target, ServerWorld world, PlanRequest req,
                                               LazySnapshot snapshot, long planId) {
        try {
            return runUnified(start, target, world, req, snapshot.get(), planId);
        } catch (RuntimeException e) {
            LOGGER.warn("[PathFinder] unified search failed plan={} from={} to={}: {}; using baritone",
                    PathPlanIds.format(planId), start.toShortString(), target.toShortString(), e.toString());
            return new PlanResult(PathFinderChoice.BARITONE, planId,
                    BaritoneStylePathFinder.calculatePath(start, target, world), null, null, 0L, false);
        }
    }

    /** Builds the per-search view and context on this thread and runs the unified search. */
    static PlanResult runUnified(BlockPos start, BlockPos target, ServerWorld world, PlanRequest req,
                                 WorldTerrainView.BotSnapshot snapshot, long planId) {
        SearchContext ctx = unifiedContext(start, target, world, req, snapshot, planId);
        PathPlan searched = UnifiedPathFinder.search(ctx);
        UnifiedPathFinder.Truncation cut = UnifiedPathFinder.truncateToExecutable(ctx, searched);
        PathPlan plan = cut.plan();
        if (cut.truncated()) {
            logTruncation(planId, searched, cut);
        }
        List<PathNode> nodes = plan.status() == PlanStatus.NO_PATH
                ? new ArrayList<>() : new ArrayList<>(PlanAdapter.toPathNodes(plan, ctx));
        if (plan.status() == PlanStatus.NO_PATH) {
            // walkTo's boxed-in branch reads the old flag until slice C reads the reason. A protected
            // box with only the start expanded is boxed in for a bot that cannot break there.
            noteSearchBoxedIn(plan.reason() == NoPathReason.BOXED_IN
                    || (plan.reason() == NoPathReason.BLOCKED_PROTECTED && plan.nodesExpanded() <= 1));
        }
        return new PlanResult(PathFinderChoice.UNIFIED, planId, nodes, plan, ctx, plan.elapsedMs(), false);
    }

    /**
     * One INFO line per truncated plan (6ad-6al field bug F), grep "unified plan truncated at move": the
     * cut move and the verifier's reason, then every earlier move with its planned breaks, so a field
     * check can see which earlier break the cut move needed as footing.
     */
    private static void logTruncation(long planId, PathPlan searched, UnifiedPathFinder.Truncation cut) {
        List<PlannedMove> moves = searched.moves();
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < cut.cutAt(); i++) {
            before.append(i == 0 ? "" : " | ").append(i).append(':').append(moves.get(i));
        }
        LOGGER.info("[PathFinder] unified plan truncated at move {} {} plan={} was={} now={} moves={} kept={} reason={} earlier=[{}]",
                cut.cutAt(), moves.get(cut.cutAt()).kind(),
                PathPlanIds.format(planId), searched.status(), cut.plan().status(), moves.size(),
                cut.plan().moves().size(), cut.reason(), before);
    }

    /** The search context for one unified search: a fresh {@link WorldTerrainView} owned by this thread. */
    public static SearchContext unifiedContext(BlockPos start, BlockPos target, ServerWorld world, PlanRequest req,
                                               WorldTerrainView.BotSnapshot snapshot, long planId) {
        WorldTerrainView.BotSnapshot snap = snapshot != null ? snapshot : WorldTerrainView.BotSnapshot.NONE;
        WorldTerrainView view = new WorldTerrainView(world, snap, req.allowBreak(), req.allowPlace());
        return req.applyTo(SearchContext.builder(view,
                        start.getX(), start.getY(), start.getZ(),
                        target.getX(), target.getY(), target.getZ()))
                .canSprint(snap.canSprint())
                .scaffoldCount(snap.scaffoldCount())
                .hazardPenaltyTicks(view::hazardPenaltyTicks)
                .anchorBonusTicks(view::anchorBonusTicks)
                .planId(planId)
                .build();
    }

    /**
     * The same search context over a fresh {@link WorldTerrainView} (same world, snapshot and gates,
     * empty memos) built on the calling thread, with the hazard and anchor functions rebound to it.
     * For reading a plan's terrain on another thread (PathCompare) or live before a unit (CostVerifier).
     */
    public static SearchContext rebindToFreshView(SearchContext ctx) {
        if (!(ctx.terrain() instanceof WorldTerrainView old)) {
            return ctx;
        }
        WorldTerrainView view = old.fresh();
        return ctx.toBuilder()
                .terrain(view)
                .hazardPenaltyTicks(view::hazardPenaltyTicks)
                .anchorBonusTicks(view::anchorBonusTicks)
                .build();
    }

    /**
     * Runs one finder with the timeout it normally gets (Baritone: its primary timeout; unified:
     * {@link PlanRequest#legacyWalk()}). No
     * {@code [PathFinder] mode=} line, so the shadow compare can call it without skewing the mode counts.
     */
    static List<PathNode> runFinder(PathFinderChoice choice, BlockPos start, BlockPos target, ServerWorld world) {
        if (choice == PathFinderChoice.UNIFIED) {
            return runUnified(start, target, world, PlanRequest.legacyWalk(), WorldTerrainView.BotSnapshot.NONE, 0L)
                    .nodes();
        }
        return BaritoneStylePathFinder.calculatePath(start, target, world);
    }

    /**
     * Re-checks a finished path against {@link PathMovePolicy} with the world, the way the
     * finders read it: every consecutive pair must be one horizontal step apart and an
     * executable edge (standing landing, headroom for a rise, head lane for a drop). Diagonal
     * corner clearance is not re-checked (the finder owns it). An empty or one-node path has no
     * edges and passes; callers map an empty path to "none" first.
     */
    public static boolean isPathExecutable(List<BlockPos> path, ServerWorld world) {
        if (path == null || world == null) {
            return false;
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            int dy = b.getY() - a.getY();
            if (!PathCompareFormat.isAdjacentStep(b.getX() - a.getX(), b.getZ() - a.getZ())) {
                return false;
            }
            boolean bodyDry = isPassable(world, b);
            boolean bodyWet = !bodyDry && isWadeable(world, b);
            if (!PathMovePolicy.isExecutable(dy,
                    hasSupportedFloor(world, b),
                    bodyDry,
                    bodyWet,
                    isHeadClear(world, b.up(), bodyWet),
                    dy > 0 && isPassable(world, a.up(2)),
                    dy < 0 && isPassable(world, b.up(2)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Quick reachability probe. Returns true if the bot's finder finds ANY path (even partial) within
     * the given time budget. Does NOT cache or return the path. Use for "can I walk there?" decisions
     * without committing to full navigation. The finder is the bot's {@link PathFinderOverrides}
     * entry, else {@link #ACTIVE}, made budget-safe by {@link PathFinderProbePolicy} (classic probes
     * with Baritone); see {@link #probePlan}. Unified succeeds on any status but NO_PATH, Baritone on
     * a non-empty node list. No log line, no plan ID. A null {@code botUuid} follows the global choice.
     */
    public static boolean canReach(BlockPos start, BlockPos goal, ServerWorld world, UUID botUuid, long timeoutMs) {
        // Status only: no node conversion; after the budget only the truncation check (one evaluate per move).
        PlanResult result = probe(start, goal, world, botUuid, timeoutMs, false);
        if (result.plan() != null) {
            return result.plan().status() != PlanStatus.NO_PATH;
        }
        return !result.nodes().isEmpty();
    }

    /**
     * A budgeted, bot-aware plan for callers that need the path but cannot afford a full walk plan,
     * including the server thread (sunset segment routing, {@link #canReach}). The finder is
     * {@link PathFinderProbePolicy#budgetedFinder} of the bot's resolved choice; every branch is
     * bounded by {@code timeoutMs}:
     *
     * <ul>
     *   <li>UNIFIED: {@link PlanRequest#probe(long)} (the budget is the whole budget, no failure
     *   extension, the probe node cap) with {@link WorldTerrainView.BotSnapshot#NONE}. An exception
     *   logs one WARN and falls back to Baritone with what is left of {@code timeoutMs} (at least
     *   1 ms), returned as a BARITONE result.</li>
     *   <li>BARITONE (and classic): the Baritone-style finder with {@code timeoutMs}.</li>
     * </ul>
     *
     * Quiet like {@link #canReach}: no {@code [PathFinder] mode=} line, no plan ID, no PathCompare
     * shadow, no {@link #noteLastPlan}, no bot inventory read. The caller logs what it decides.
     */
    public static PlanResult probePlan(BlockPos start, BlockPos goal, ServerWorld world, UUID botUuid,
                                       long timeoutMs) {
        return probe(start, goal, world, botUuid, timeoutMs, true);
    }

    /**
     * {@link #probePlan}; with {@code withNodes} false a unified result carries the plan but an empty
     * node list ({@link #canReach} reads only the status).
     */
    private static PlanResult probe(BlockPos start, BlockPos goal, ServerWorld world, UUID botUuid,
                                    long timeoutMs, boolean withNodes) {
        long probeStart = System.nanoTime();
        PathFinderChoice choice = PathFinderProbePolicy.budgetedFinder(PathFinderOverrides.resolveFor(botUuid, ACTIVE));
        if (choice == PathFinderChoice.UNIFIED) {
            try {
                SearchContext ctx = unifiedContext(start, goal, world, PlanRequest.probe(timeoutMs),
                        WorldTerrainView.BotSnapshot.NONE, 0L);
                // What the walk would run (runUnified truncates the same way). Runs after the search's
                // budget, bounded at one evaluate per move of the plan.
                PathPlan plan = UnifiedPathFinder.truncateToExecutable(ctx, UnifiedPathFinder.search(ctx)).plan();
                List<PathNode> nodes = !withNodes || plan.status() == PlanStatus.NO_PATH
                        ? new ArrayList<>() : new ArrayList<>(PlanAdapter.toPathNodes(plan, ctx));
                return new PlanResult(PathFinderChoice.UNIFIED, 0L, nodes, plan, ctx, plan.elapsedMs(), false);
            } catch (RuntimeException e) {
                LOGGER.warn("[PathFinder] unified probe failed from={} to={}: {}; using baritone",
                        start.toShortString(), goal.toShortString(), e.toString());
            }
        }
        // The fallback gets only what the unified attempt left, so the probe stays within timeoutMs.
        long spentMs = (System.nanoTime() - probeStart) / 1_000_000;
        long budgetMs = Math.max(1L, timeoutMs - spentMs);
        return new PlanResult(PathFinderChoice.BARITONE, 0L,
                BaritoneStylePathFinder.calculatePath(start, goal, world, budgetMs), null, null, 0L, false);
    }


    public static List<PathNode> simplifyPath(List<PathNode> path, ServerWorld world) {
        if (path.isEmpty()) return path;

        List<PathNode> simplifiedPath = new ArrayList<>();
        PathNode prev = null;

        for (PathNode current : path) {
            BlockPos pos = current.pos;

            BlockPos feetPos = pos.down();
            BlockPos bodyPos = pos;
            BlockPos headPos = pos.up();

            boolean solidBelow = isSolidBlock(world, feetPos);
            boolean bodyDry = isPassable(world, bodyPos);
            boolean bodyWet = !bodyDry && isWadeable(world, bodyPos);
            boolean headClear = isHeadClear(world, headPos, bodyWet);

            boolean canStand = solidBelow && (bodyDry || bodyWet) && headClear;
            boolean jumpNeeded = current.jumpNeeded();

            // 1.1.240: never lift a node onto the block above it (that turned a planned cell
            // into a hop nobody checked). The first node is the bot's own cell and stays where
            // it is even mid-jump; a later cell that stopped being standable since planning
            // is skipped, as before.
            if (!canStand && !simplifiedPath.isEmpty()) {
                LOGGER.debug("Unwalkable cell at {}, skipping.", pos);
                continue;
            }

            String feetBlockType = world.getBlockState(feetPos).getBlock().getName().getString().toLowerCase();

            PathNode node = new PathNode(pos, feetBlockType, canStand, jumpNeeded);

            if (prev == null || !prev.pos.equals(pos)) {
                simplifiedPath.add(node);
                prev = node;
            } else {
                LOGGER.debug("Duplicate node at {}, skipping.", pos);
            }
        }

        LOGGER.debug("Simplified path length after filtering: {}", simplifiedPath.size());
        return simplifiedPath;
    }


    public static Queue<Segment> convertPathToSegments(List<PathNode> simplifiedPath, boolean sprint) {
        Queue<Segment> segments = new LinkedList<>();

        if (simplifiedPath.isEmpty()) {
            return segments;
        }

        PathNode segmentStart = simplifiedPath.get(0);
        String currentAxis = null;
        String currentDirectionKey = null;

        for (int i = 1; i < simplifiedPath.size(); i++) {
            PathNode prev = simplifiedPath.get(i - 1);
            PathNode current = simplifiedPath.get(i);

            // Calculate the difference
            int dx = current.getPos().getX() - prev.getPos().getX();
            int dz = current.getPos().getZ() - prev.getPos().getZ();
            int dy = current.getPos().getY() - prev.getPos().getY();

            String axis = movementAxis(dx, dy, dz);
            if (axis == null) {
                // No movement, skip
                continue;
            }
            String directionKey = movementDirectionKey(dx, dy, dz);

            // Initialize axis and sign for the first comparison
            if (currentAxis == null) {
                currentAxis = axis;
                currentDirectionKey = directionKey;
            }

            boolean axisChanged = !axis.equals(currentAxis);
            boolean directionChanged = !Objects.equals(directionKey, currentDirectionKey);
            boolean verticalStep = dy != 0;
            int segmentSpan = Math.abs(current.getPos().getX() - segmentStart.getPos().getX())
                    + Math.abs(current.getPos().getY() - segmentStart.getPos().getY())
                    + Math.abs(current.getPos().getZ() - segmentStart.getPos().getZ());
            boolean spanBreak = segmentSpan > MAX_SEGMENT_MANHATTAN_SPAN;

            // Check if a jump is needed at either the start or end of the segment
            boolean jumpBreak = current.jumpNeeded() || prev.jumpNeeded();

            if (jumpBreak || verticalStep || axisChanged || directionChanged || spanBreak) {
                // Create the segment up to prev node, use jump flag from either node
                if (!segmentStart.getPos().equals(prev.getPos())) { // Skip zero-length segments
                    segments.add(new Segment(segmentStart.getPos(), prev.getPos(), segmentStart.jumpNeeded() || prev.jumpNeeded(), sprint));
                }
                segmentStart = prev;

                // Update current direction state
                currentAxis = axis;
                currentDirectionKey = directionKey;
            }
        }

        // Add final segment, use jump flag from either start or end node
        PathNode lastNode = simplifiedPath.get(simplifiedPath.size() - 1);
        if (!segmentStart.getPos().equals(lastNode.getPos())) { // Skip zero-length segments
            segments.add(new Segment(segmentStart.getPos(), lastNode.getPos(), segmentStart.jumpNeeded() || lastNode.jumpNeeded(), sprint));
        }

        return segments;
    }

    static String movementAxis(int dx, int dy, int dz) {
        if (dx != 0 && dz != 0) {
            return "diag";
        }
        if (dx != 0) {
            return "x";
        }
        if (dz != 0) {
            return "z";
        }
        if (dy != 0) {
            return "y";
        }
        return null;
    }

    static String movementDirectionKey(int dx, int dy, int dz) {
        String axis = movementAxis(dx, dy, dz);
        if (axis == null) {
            return null;
        }
        if ("diag".equals(axis)) {
            return Integer.signum(dx) + "," + Integer.signum(dz);
        }
        if ("x".equals(axis)) {
            return Integer.toString(Integer.signum(dx));
        }
        if ("z".equals(axis)) {
            return Integer.toString(Integer.signum(dz));
        }
        return Integer.toString(Integer.signum(dy));
    }

    // These two are outdated methods, but I am still keeping them for a while.

//    public static List<String> identifyPrimaryAxis(List<BlockPos> path) {
//
//        int xChanges = 0, yChanges = 0, zChanges = 0;
//
//        BlockPos prevPos = path.get(0);
//
//
//
//        for (BlockPos pos : path) {
//
//            if (pos.getX() != prevPos.getX()) xChanges++;
//
//            if (pos.getY() != prevPos.getY()) yChanges++;
//
//            if (pos.getZ() != prevPos.getZ()) zChanges++;
//
//            prevPos = pos;
//
//        }
//
//
//
//        List<String> axisPriorityList = new ArrayList<>();
//
//        if (xChanges > 0) axisPriorityList.add("x");
//
//        if (yChanges > 0) axisPriorityList.add("y");
//
//        if (zChanges > 0) axisPriorityList.add("z");
//
//
//
//        final int finalX = xChanges, finalY = yChanges, finalZ = zChanges;
//
//
//
//        axisPriorityList.sort((a, b) -> {
//
//            int aChanges = a.equals("x") ? finalX : a.equals("y") ? finalY : finalZ;
//
//            int bChanges = b.equals("x") ? finalX : b.equals("y") ? finalY : finalZ;
//
//            return Integer.compare(bChanges, aChanges);
//
//        });
//
//
//
//        return axisPriorityList;
//
//    }
//
//
//    private static boolean isSameDirection(BlockPos start, BlockPos current, BlockPos segmentStart) {
//        int dx1 = Integer.signum(segmentStart.getX() - start.getX());
//        int dz1 = Integer.signum(segmentStart.getZ() - start.getZ());
//
//        int dx2 = Integer.signum(current.getX() - start.getX());
//        int dz2 = Integer.signum(current.getZ() - start.getZ());
//
//        return dx1 == dx2 && dz1 == dz2;
//    }


    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState blockState = world.getBlockState(pos);
        // Deadly blocks (fire, soul fire, lava, magma, campfires, cactus, sweet berry
        // bush, wither rose, powder snow, pointed dripstone) — reject as path cells so
        // the bot routes around them. Some have empty collision shapes (fire) and
        // would otherwise be walked into.
        if (net.wcfcarolina13.GameAI.services.BotHazardService.isDeadlyBlock(blockState)) {
            return false;
        }
        if (blockState.getBlock() instanceof DoorBlock) {
            if (blockState.isOf(Blocks.IRON_DOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        if (blockState.getBlock() instanceof TrapdoorBlock) {
            if (blockState.isOf(Blocks.IRON_TRAPDOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        // Lava has an empty collision shape but must be rejected.
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isEmpty()) {
            return false;
        }
        // Air OR thin walkable partial (carpets, pressure plates, rails, tripwire, lily pad, ...).
        // Slabs/stairs/snow layers are NOT whitelisted here: the bot walks onto their top surface,
        // it doesn't walk through the cell laterally. See WalkablePartialBlocks for the rationale.
        return blockState.isAir()
                || net.wcfcarolina13.GameAI.services.WalkablePartialBlocks.isPathable(blockState, world, pos);
    }


    /** Shallow water a body can wade through (see {@link WadeableWater}). */
    private static boolean isWadeable(ServerWorld world, BlockPos pos) {
        return WadeableWater.isWadeable(world.getBlockState(pos), world, pos);
    }

    /**
     * Head cell: always dry passable (never fluid). Over a wading body it must also have no
     * collision (a lily pad on the pond sits at head height).
     */
    private static boolean isHeadClear(ServerWorld world, BlockPos head, boolean overWater) {
        if (!isPassable(world, head)) {
            return false;
        }
        return !overWater || WadeableWater.headFitsOverWater(world.getBlockState(head), world, head);
    }

    private static boolean isSolidBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.isOpaque();
    }

    private static boolean hasSupportedFloor(ServerWorld world, BlockPos pos) {
        if (isSolidBlock(world, pos.down())) return true;
        return false;
    }


}
