package net.wcfcarolina13.PathFinding;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in shadow compare for head-to-head finder data (1.1.240 slice D). When on, every walkTo
 * plan also runs the OTHER finder on the same start/goal on one low-priority daemon thread and
 * logs one {@link PathCompareFormat} line. The shadow result is never used for movement.
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
     * Queues the shadow run. Called by {@link PathFinder} right after the primary search, with
     * the primary's timing and an immutable snapshot of its node positions.
     */
    static void submit(String botName, BlockPos start, BlockPos target, ServerWorld world,
                       PathFinderChoice primary, long primaryMs, List<BlockPos> primaryPath) {
        if (!enabled || world == null || start == null || target == null) {
            return;
        }
        ThreadPoolExecutor executor = exec;
        if (executor == null || executor.isShutdown()) {
            DROPPED.incrementAndGet();
            return;
        }
        SUBMITTED.incrementAndGet();
        // The rejection handler counts a drop when the queue is full.
        executor.execute(() -> runShadow(botName, start.toImmutable(), target.toImmutable(), world,
                primary, primaryMs, primaryPath));
    }

    private static void runShadow(String botName, BlockPos start, BlockPos target, ServerWorld world,
                                  PathFinderChoice primary, long primaryMs, List<BlockPos> primaryPath) {
        try {
            if (!enabled || net.wcfcarolina13.GameAI.services.TaskService.isServerStopping()
                    || Thread.currentThread().isInterrupted()) {
                return;
            }
            PathFinderChoice shadow = primary.other();
            long t0 = System.nanoTime();
            List<PathFinder.PathNode> shadowNodes = PathFinder.runFinder(shadow, start, target, world);
            long shadowMs = (System.nanoTime() - t0) / 1_000_000L;
            if (net.wcfcarolina13.GameAI.services.TaskService.isServerStopping()) {
                return;
            }
            List<BlockPos> shadowPath = positions(shadowNodes);

            // Both re-checks here, back to back, so they read the same world.
            String primaryStatus = PathCompareFormat.status(primaryPath.size(),
                    PathFinder.isPathExecutable(primaryPath, world));
            String shadowStatus = PathCompareFormat.status(shadowPath.size(),
                    PathFinder.isPathExecutable(shadowPath, world));

            boolean primaryIsBidir = primary == PathFinderChoice.BIDIR;
            String line = PathCompareFormat.line(botName,
                    start.getX(), start.getY(), start.getZ(),
                    target.getX(), target.getY(), target.getZ(),
                    primary,
                    primaryIsBidir ? primaryMs : shadowMs,
                    primaryIsBidir ? primaryPath.size() : shadowPath.size(),
                    primaryIsBidir ? primaryStatus : shadowStatus,
                    primaryIsBidir ? shadowMs : primaryMs,
                    primaryIsBidir ? shadowPath.size() : primaryPath.size(),
                    primaryIsBidir ? shadowStatus : primaryStatus);
            LOGGER.info(line);
            LOGGED.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.debug("[PathCompare] shadow run failed for bot={}: {}", botName, t.toString());
        }
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
