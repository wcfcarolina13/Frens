package net.wcfcarolina13.PathFinding;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.PathFinding.unified.MoveSimulator;
import net.wcfcarolina13.PathFinding.unified.PathPlan;
import net.wcfcarolina13.PathFinding.unified.PlanRequest;
import net.wcfcarolina13.PathFinding.unified.PlanStatus;
import net.wcfcarolina13.PathFinding.unified.SearchContext;
import net.wcfcarolina13.PathFinding.unified.UnifiedPathFinder;
import net.wcfcarolina13.PathFinding.unified.WorldTerrainView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in shadow compare for head-to-head finder data (1.1.240 slice D, three finders since 1.1.242).
 * When on, every bot plan also runs the two NON-primary finders on the same start/goal on one
 * low-priority daemon thread and logs one {@link PathCompareFormat} line with all three and the
 * primary's plan ID. The shadow results are never used for movement. Old finders' status comes from
 * {@link PathFinder#isPathExecutable}; the unified finder's from {@link MoveSimulator} on this thread's
 * own {@link WorldTerrainView} (never the primary thread's).
 *
 * <p>Never runs on the server thread and never blocks the primary: the queue is bounded and a
 * full queue drops the job (counted). World reads on the shadow thread follow the same rules as
 * the finders' existing off-thread reads (they already run on skill worker threads).
 * In-memory only; SERVER_STOPPING turns it off and stops the thread.
 */
public final class PathCompareService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");
    private static final int QUEUE_CAPACITY = 8;
    static final String THREAD_NAME = "frens-pathcompare";

    private static volatile boolean enabled = false;
    private static volatile ThreadPoolExecutor exec;
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong LOGGED = new AtomicLong();

    private PathCompareService() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            restartExecutors();
        }
        LOGGER.info("[PathCompare] shadow compare {} (submitted={} logged={} dropped={})",
                on ? "ON" : "OFF", SUBMITTED.get(), LOGGED.get(), DROPPED.get());
    }

    public static long submittedCount() {
        return SUBMITTED.get();
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    public static long loggedCount() {
        return LOGGED.get();
    }

    /**
     * Queues the shadow run. Called by {@link PathFinder} right after the primary search, with the
     * primary's result, the request and bot snapshot it ran with (the unified shadow reuses both, so
     * it plans what the primary caller would have executed), and an immutable copy of its positions.
     */
    static void submit(String botName, BlockPos start, BlockPos target, ServerWorld world,
                       PathFinder.PlanResult primary, PlanRequest request, WorldTerrainView.BotSnapshot snapshot) {
        if (!enabled || world == null || start == null || target == null || primary == null) {
            return;
        }
        ThreadPoolExecutor executor = exec;
        if (executor == null || executor.isShutdown()) {
            DROPPED.incrementAndGet();
            return;
        }
        SUBMITTED.incrementAndGet();
        List<BlockPos> primaryPath = positions(primary.nodes());
        // The rejection handler counts a drop when the queue is full.
        executor.execute(() -> runShadow(botName, start.toImmutable(), target.toImmutable(), world,
                primary, primaryPath, request, snapshot));
    }

    /** One finder's {@code <ms>ms/<len>/<status>} value. */
    /** {@code reason}: a unified plan's NoPathReason when it is not FULL, else null. */
    private record Value(long ms, int len, String status, String reason) {
        Value(long ms, int len, String status) {
            this(ms, len, status, null);
        }
    }

    private static void runShadow(String botName, BlockPos start, BlockPos target, ServerWorld world,
                                  PathFinder.PlanResult primary, List<BlockPos> primaryPath,
                                  PlanRequest request, WorldTerrainView.BotSnapshot snapshot) {
        try {
            if (!enabled || net.wcfcarolina13.GameAI.services.TaskService.isServerStopping()
                    || Thread.currentThread().isInterrupted()) {
                return;
            }
            PathFinderChoice primaryChoice = primary.finder();
            Map<PathFinderChoice, Value> values = new EnumMap<>(PathFinderChoice.class);
            for (PathFinderChoice shadow : primaryChoice.others()) {
                if (net.wcfcarolina13.GameAI.services.TaskService.isServerStopping()
                        || Thread.currentThread().isInterrupted()) {
                    return;
                }
                values.put(shadow, runOne(shadow, start, target, world, request, snapshot));
            }
            // The primary's re-check after the shadows, back to back with theirs, on this thread's own view.
            values.put(primaryChoice, primaryValue(primary, primaryPath, world));

            Value bidir = values.get(PathFinderChoice.BIDIR);
            Value baritone = values.get(PathFinderChoice.BARITONE);
            Value unified = values.get(PathFinderChoice.UNIFIED);
            String line = PathCompareFormat.line(botName,
                    start.getX(), start.getY(), start.getZ(),
                    target.getX(), target.getY(), target.getZ(),
                    primaryChoice,
                    bidir.ms(), bidir.len(), bidir.status(),
                    baritone.ms(), baritone.len(), baritone.status(),
                    unified.ms(), unified.len(), unified.status(), unified.reason(),
                    primary.planId());
            LOGGER.info(line);
            LOGGED.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.debug("[PathCompare] shadow run failed for bot={}: {}", botName, t.toString());
        }
    }

    private static Value runOne(PathFinderChoice choice, BlockPos start, BlockPos target, ServerWorld world,
                                PlanRequest request, WorldTerrainView.BotSnapshot snapshot) {
        long t0 = System.nanoTime();
        if (choice == PathFinderChoice.UNIFIED) {
            SearchContext ctx = PathFinder.unifiedContext(start, target, world, request, snapshot, 0L);
            // The plan the walk would run: runUnified truncates the same way (one evaluate per move).
            PathPlan plan = UnifiedPathFinder.truncateToExecutable(ctx, UnifiedPathFinder.search(ctx)).plan();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return unifiedValue(ms, plan, ctx);
        }
        List<BlockPos> path = positions(PathFinder.runFinder(choice, start, target, world));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new Value(ms, path.size(), PathCompareFormat.status(path.size(), PathFinder.isPathExecutable(path, world)));
    }

    private static Value primaryValue(PathFinder.PlanResult primary, List<BlockPos> primaryPath, ServerWorld world) {
        if (primary.plan() != null && primary.context() != null) {
            // The primary's view belongs to the primary's thread: simulate on a fresh one built here.
            return unifiedValue(primary.elapsedMs(), primary.plan(), PathFinder.rebindToFreshView(primary.context()));
        }
        return new Value(primary.elapsedMs(), primaryPath.size(),
                PathCompareFormat.status(primaryPath.size(), PathFinder.isPathExecutable(primaryPath, world)));
    }

    /** Status from {@link MoveSimulator} on a read-only overlay of {@code ctx}'s terrain (this thread's view). */
    private static Value unifiedValue(long ms, PathPlan plan, SearchContext ctx) {
        boolean noPath = plan.status() == PlanStatus.NO_PATH;
        boolean executable = !noPath && MoveSimulator.simulate(ctx, plan).executable();
        return new Value(ms, PathCompareFormat.unifiedLen(noPath, plan.moves().size()),
                PathCompareFormat.unifiedStatus(noPath, executable),
                PathCompareFormat.unifiedReason(plan.reason().name()));
    }

    static List<BlockPos> positions(List<PathFinder.PathNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>(nodes.size());
        for (PathFinder.PathNode n : nodes) {
            out.add(n.pos.toImmutable());
        }
        return List.copyOf(out);
    }

    public static synchronized void restartExecutors() {
        ThreadPoolExecutor current = exec;
        if (current != null && !current.isShutdown()) {
            return;
        }
        exec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, THREAD_NAME);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                },
                (r, executor) -> DROPPED.incrementAndGet());
    }

    /**
     * SERVER_STOPPING: turn the compare off and stop the thread. The await is short on purpose:
     * an off-thread chunk read can wait on the server thread, which is the thread running this.
     */
    public static synchronized void shutdownExecutors() {
        enabled = false;
        ThreadPoolExecutor current = exec;
        exec = null;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            current.awaitTermination(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
