package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages the deferred cleanup queue for FortifyVillageSkill.
 *
 * Owns the queue list, throttle timestamp, backoff constants, and all pure
 * queue-management helpers.  The skill's processDeferredFortifyCleanupQueue
 * method iterates {@link #queue} directly and delegates state transitions here.
 *
 * Extracted from FortifyVillageSkill.java in the session after commit 38a3526.
 * These were previously private methods on the skill; now package-private so the
 * skill (same package) can access them unchanged.
 */
final class FortifyCleanupHelper {

    // ── constants ──────────────────────────────────────────────────────────────
    static final long BACKOFF_BASE_MS = 250L;
    static final long BACKOFF_MAX_MS = 1_000L;
    static final long PROCESS_MIN_INTERVAL_MS = 350L;

    // ── state ──────────────────────────────────────────────────────────────────
    /** The deferred cleanup queue. Package-private for direct iteration by the skill. */
    final List<DeferredCleanupTask> queue = new ArrayList<>();
    /** Timestamp of the last queue process pass — used for throttling. */
    long lastProcessMs = 0L;

    // ── queue management ───────────────────────────────────────────────────────

    /** Add a cleanup task; silently deduplicates by (kind, pos). */
    void queue(FortifyCleanupKind kind, BlockPos pos, BlockState originalState,
               boolean mandatory, String context) {
        if (kind == null || pos == null) return;
        for (DeferredCleanupTask task : queue) {
            if (task == null) continue;
            if (task.kind == kind && pos.equals(task.pos)) return;
        }
        queue.add(new DeferredCleanupTask(kind, pos, originalState, mandatory, context));
    }

    /** Queue CARVE_REPAIR tasks for all unresolved repairs in a carve session. */
    void queueCarveRepairs(FortifyNavRuntimeScope scope, FortifyCarveSession session,
                           List<DeferredRepair> unresolved) {
        if (scope == null || session == null || unresolved == null || unresolved.isEmpty()) return;
        for (DeferredRepair repair : unresolved) {
            if (repair == null || repair.pos() == null || repair.state() == null) continue;
            queue(FortifyCleanupKind.CARVE_REPAIR, repair.pos(), repair.state(),
                    repair.mandatory(), scope.context);
        }
    }

    // ── task state transitions ─────────────────────────────────────────────────

    /** Record a skip; applies exponential backoff to defer the next attempt. */
    void noteSkip(DeferredCleanupTask task, String reason) {
        if (task == null) return;
        task.attempts++;
        task.lastAttemptMs = System.currentTimeMillis();
        task.lastFailureReason = reason;
        long multiplier = 1L << Math.min(2, Math.max(0, task.attempts - 1));
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * multiplier);
        task.nextEligibleMs = task.lastAttemptMs + delay;
    }

    /** Record a failure that should be retried immediately (no backoff). */
    void noteImmediateRetry(DeferredCleanupTask task, String reason) {
        if (task == null) return;
        task.attempts++;
        task.lastAttemptMs = System.currentTimeMillis();
        task.lastFailureReason = reason;
        task.nextEligibleMs = 0L;
    }

    /** Mark a task as resolved, clearing failure state. */
    void noteResolved(DeferredCleanupTask task) {
        if (task == null) return;
        task.lastAttemptMs = System.currentTimeMillis();
        task.lastFailureReason = null;
        task.nextEligibleMs = 0L;
    }

    // ── throttle check ─────────────────────────────────────────────────────────

    /**
     * Returns true if the queue should be skipped this call (throttled).
     * If not throttled, updates {@link #lastProcessMs} to now.
     *
     * @param forcePass when true, always runs and always resets the timer
     */
    boolean checkAndUpdateThrottle(boolean forcePass) {
        long now = System.currentTimeMillis();
        if (!forcePass && (now - lastProcessMs) < PROCESS_MIN_INTERVAL_MS) {
            return true; // throttled — skip
        }
        lastProcessMs = now;
        return false; // not throttled — proceed
    }

    // ── context predicates ─────────────────────────────────────────────────────

    /** Returns true for contexts that must process the cleanup queue even during abort/removal. */
    boolean isForcedContext(String context) {
        if (context == null) return false;
        return context.equals("scaffold-teardown")
                || context.startsWith("fortify-gate:post-success")
                || context.startsWith("fortifyabort")
                || context.startsWith("fortify-abort");
    }

    /** Returns true for contexts that permit active-recovery movement toward cleanup targets. */
    boolean allowActiveRecovery(String context) {
        if (context == null) return false;
        // Allow active recovery for all fortify-related contexts — the bot should
        // always attempt to walk toward unreachable repair items when possible.
        return context.startsWith("fortify") || context.startsWith("scaffold");
    }

    // ── static logging utilities ───────────────────────────────────────────────

    static void incrementReason(Map<String, Integer> counts, String reason) {
        if (counts == null || reason == null || reason.isBlank()) return;
        counts.merge(reason, 1, Integer::sum);
    }

    static String formatReasonSummary(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
