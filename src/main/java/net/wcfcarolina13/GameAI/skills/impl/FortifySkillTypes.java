package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.WallPoint;

import java.util.*;

/**
 * Package-private types shared across FortifyVillageSkill and its extracted helpers.
 *
 * Contains:
 *  - All enums (FortifyNavMode, FortifyCleanupKind, CleanupState, NavBreakRejectReason,
 *    ReplaceFailureKind, TowerPillarOutcome, TowerStepOutcome, TowerReturnOutcome, TowerScaffoldSideOutcome)
 *  - Result/data records (NavBreakCandidateEval, CavityCheckResult, DeferredRepair, ReplaceBlockResult,
 *    TowerStepAttemptResult, TowerReturnAttemptResult, TowerSummitStepCandidate, TowerSummitRoamResult,
 *    TowerHardResetResult, FortifyNavProgressWindow, TowerNavCandidate, ApproachCandidateEval,
 *    ScaffoldTeardownResult, SurfaceProfile, StartupRecoveryResult, MoatDigResult)
 *  - State/session classes (DeferredCleanupTask, FortifyCarveSession, FortifyNavRuntimeScope,
 *    TowerNavAttemptState, ScaffoldLedger)
 *
 * Extracted from FortifyVillageSkill.java (was 9594 lines) in commit after 2ee2253.
 * These were previously private inner types; now package-private top-level types
 * so FortifyEntombmentHelper and future helpers can reference them.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

enum FortifyNavMode {
    REROUTE_ONLY,
    CARVE_CORRIDOR
}

enum FortifyCleanupKind {
    CARVE_REPAIR,
    SCAFFOLD_REMOVE
}

enum CleanupState {
    PENDING,
    PARTIAL,
    DONE,
    FAILED_QUEUED
}

enum NavBreakRejectReason {
    AIR_OR_REPLACEABLE,
    DIG_BLACKLIST,
    DOOR,
    BED,
    FENCE,
    FENCE_GATE,
    WALL,
    PANE,
    TRAPDOOR,
    UNBREAKABLE,
    BLOCK_ENTITY,
    FLUID,
    VILLAGE_ADJACENT,
    NO_COLLISION,
    LAYOUT_NOT_ALLOWED,
    NOT_LAYOUT_BLOCK
}

enum ReplaceFailureKind {
    NONE,
    BOT_OCCUPIES,
    OUT_OF_REACH,
    LOS_BLOCKED,
    NO_MATERIAL,
    OTHER
}

enum TowerPillarOutcome {
    OK,
    PARTIAL,
    FAIL
}

enum TowerStepOutcome {
    OK,
    NO_MOVE,
    OFF_TARGET,
    FELL
}

enum TowerReturnOutcome {
    OK,
    FAIL
}

enum TowerScaffoldSideOutcome {
    PROGRESS,
    NO_PROGRESS_RECOVERABLE,
    NO_PROGRESS_HARD
}

// ── Simple result records ──────────────────────────────────────────────────────

record NavBreakCandidateEval(boolean allowed, NavBreakRejectReason rejectReason) {}

record CavityCheckResult(boolean safe, int airCount, boolean spawnableCell) {}

record DeferredRepair(BlockPos pos, BlockState state, boolean mandatory) {}

record ReplaceBlockResult(boolean success, ReplaceFailureKind failureKind, String reason) {
    boolean retryable() {
        return failureKind == ReplaceFailureKind.OUT_OF_REACH
                || failureKind == ReplaceFailureKind.LOS_BLOCKED
                || failureKind == ReplaceFailureKind.BOT_OCCUPIES
                || (failureKind == ReplaceFailureKind.OTHER
                && reason != null
                && (reason.startsWith("bot-intersects-target")
                || reason.startsWith("no-line-of-sight")
                || reason.startsWith("out-of-reach")));
    }
}

record TowerStepAttemptResult(TowerStepOutcome outcome, BlockPos expected, BlockPos actual) {}

record TowerReturnAttemptResult(TowerReturnOutcome outcome, BlockPos expected, BlockPos actual) {}

record TowerSummitStepCandidate(BlockPos pos, Direction dir, int newReachable, int score) {}

record TowerSummitRoamResult(int placed, boolean recoverableFailure) {}

record TowerHardResetResult(boolean moved, boolean meaningful) {}

record FortifyNavProgressWindow(BlockPos startPos, BlockPos endPos,
                                double beforeTargetDistSq, double afterTargetDistSq,
                                long elapsedMs) {
    double targetDeltaBlocks() {
        return Math.sqrt(Math.max(0.0D, beforeTargetDistSq)) - Math.sqrt(Math.max(0.0D, afterTargetDistSq));
    }

    double netDisplacementBlocks() {
        return startPos == null || endPos == null ? 0.0D : Math.sqrt(startPos.getSquaredDistance(endPos));
    }

    boolean meaningful() {
        return targetDeltaBlocks() >= 1.5D || (netDisplacementBlocks() >= 2.0D && elapsedMs >= 300L);
    }
}

record TowerNavCandidate(BlockPos pos, double score, int exits, boolean locallyReachable,
                         boolean previouslyFailed) {}

record ApproachCandidateEval(boolean standable, boolean insideFootprint,
                              boolean locallyReachable, boolean losZero,
                              int exits) {}

record ScaffoldTeardownResult(int trackedSession, int trackedMemory, int reconciledNearby,
                               int removed, int alreadyAir, int failedMine, int outOfReach,
                               int queued) {}

record SurfaceProfile(int referenceSurfaceY, Map<Long, Integer> plannedYByXZ) {}

record StartupRecoveryResult(boolean progressMade, int minedCount, boolean snapped,
                              boolean failedNoSafeTile) {}

record MoatDigResult(int dugCount, boolean abortedNoSafeTile) {}

// ── State / session classes ────────────────────────────────────────────────────

final class DeferredCleanupTask {
    final FortifyCleanupKind kind;
    final BlockPos pos;
    final BlockState originalState;
    final boolean mandatory;
    final String context;
    int attempts;
    final long queuedAtMs;
    long lastAttemptMs;
    long nextEligibleMs;
    String lastFailureReason;

    DeferredCleanupTask(FortifyCleanupKind kind, BlockPos pos, BlockState originalState,
                        boolean mandatory, String context) {
        this.kind = kind;
        this.pos = pos == null ? null : pos.toImmutable();
        this.originalState = originalState;
        this.mandatory = mandatory;
        this.context = context;
        this.queuedAtMs = System.currentTimeMillis();
        this.lastAttemptMs = 0L;
        this.nextEligibleMs = 0L;
        this.lastFailureReason = null;
    }
}

final class FortifyCarveSession {
    /** Maximum blocks that may be mined in a single carve episode. */
    static final int FORTIFY_CARVE_MAX_BLOCKS_PER_EPISODE = 6;

    final List<DeferredRepair> deferredRepairs = new ArrayList<>();
    int blocksMined = 0;
    final BlockPos target;
    final String reason;
    boolean completed = false;
    boolean crossed = false;
    CleanupState cleanupState = CleanupState.PENDING;
    BlockPos entryPos = null;
    long lastTouchedMs = System.currentTimeMillis();

    FortifyCarveSession(BlockPos target, String reason) {
        this.target = target == null ? null : target.toImmutable();
        this.reason = reason;
    }

    boolean canMineMore() {
        return blocksMined < FORTIFY_CARVE_MAX_BLOCKS_PER_EPISODE;
    }

    void touch() {
        lastTouchedMs = System.currentTimeMillis();
    }
}

final class FortifyNavRuntimeScope {
    final String context;
    final TowerNavAttemptState towerState;
    final WallPoint towerVertex;
    final BlockPos primaryTarget;
    final boolean towerPatchContext;
    final boolean gateContext;
    FortifyNavMode navMode;
    FortifyCarveSession carveSession;
    final long epoch;
    int localTrapRejectBursts = 0;
    BlockPos lastTrapRejectPos = null;
    int sameTrapRejectPosCount = 0;

    FortifyNavRuntimeScope(String context,
                           TowerNavAttemptState towerState,
                           WallPoint towerVertex,
                           BlockPos primaryTarget,
                           boolean towerPatchContext,
                           boolean gateContext,
                           FortifyNavMode navMode,
                           long epoch) {
        this.context = context;
        this.towerState = towerState;
        this.towerVertex = towerVertex;
        this.primaryTarget = primaryTarget == null ? null : primaryTarget.toImmutable();
        this.towerPatchContext = towerPatchContext;
        this.gateContext = gateContext;
        this.navMode = navMode;
        this.epoch = epoch;
    }
}

final class TowerNavAttemptState {
    final WallPoint vertex;
    final Set<BlockPos> failedApproachCandidates = new LinkedHashSet<>();
    final Set<BlockPos> failedHardResetCandidates = new LinkedHashSet<>();
    /** Positions where pillar escape has failed below surface — used to bail faster on revisit. */
    final Set<BlockPos> failedSubSurfacePositions = new LinkedHashSet<>();
    BlockPos lastStuckPos = null;
    int sameStuckPosCount = 0;
    long lastStuckStartMs = 0L;
    long noRealProgressSinceMs = 0L;
    FortifyNavMode navMode = FortifyNavMode.REROUTE_ONLY;
    int villageAdjacentRejectBursts = 0;

    TowerNavAttemptState(WallPoint vertex) {
        this.vertex = vertex;
    }

    void recordApproachFailure(BlockPos pos) {
        if (pos != null) {
            failedApproachCandidates.add(pos.toImmutable());
            trim(failedApproachCandidates, 8);
        }
    }

    void recordHardResetFailure(BlockPos pos) {
        if (pos != null) {
            failedHardResetCandidates.add(pos.toImmutable());
            trim(failedHardResetCandidates, 8);
        }
    }

    void recordSubSurfaceFailure(BlockPos pos) {
        if (pos != null) {
            failedSubSurfacePositions.add(pos.toImmutable());
            trim(failedSubSurfacePositions, 12);
        }
    }

    boolean isKnownSubSurfaceFailure(BlockPos pos) {
        return pos != null && failedSubSurfacePositions.contains(pos);
    }

    void noteMovement(BlockPos before, BlockPos after) {
        noteMovement(before, after, false);
    }

    void noteMovement(BlockPos before, BlockPos after, boolean meaningfulProgress) {
        if (before == null || after == null) return;
        boolean movedFar = before.getSquaredDistance(after) >= 9.0D;
        if (meaningfulProgress) {
            noRealProgressSinceMs = 0L;
            villageAdjacentRejectBursts = 0;
        }
        if (movedFar || meaningfulProgress) {
            failedApproachCandidates.clear();
            failedHardResetCandidates.clear();
            lastStuckPos = null;
            sameStuckPosCount = 0;
            lastStuckStartMs = 0L;
        }
    }

    int noteStuckPosition(BlockPos pos) {
        if (pos == null) return 0;
        BlockPos immutable = pos.toImmutable();
        if (immutable.equals(lastStuckPos)) {
            sameStuckPosCount++;
        } else {
            lastStuckPos = immutable;
            sameStuckPosCount = 1;
            lastStuckStartMs = System.currentTimeMillis();
        }
        return sameStuckPosCount;
    }

    long stuckElapsedMs() {
        if (lastStuckStartMs <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - lastStuckStartMs);
    }

    void noteRealProgress() {
        noRealProgressSinceMs = 0L;
        villageAdjacentRejectBursts = 0;
    }

    void noteNoRealProgress() {
        if (noRealProgressSinceMs <= 0L) {
            noRealProgressSinceMs = System.currentTimeMillis();
        }
    }

    long noRealProgressElapsedMs() {
        if (noRealProgressSinceMs <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - noRealProgressSinceMs);
    }

    void noteBreakRejects(Map<NavBreakRejectReason, Integer> counts) {
        int village = counts == null ? 0 : counts.getOrDefault(NavBreakRejectReason.VILLAGE_ADJACENT, 0);
        int total = 0;
        if (counts != null) {
            for (int v : counts.values()) total += v;
        }
        if (village > 0 && total > 0 && village * 100 >= total * 80) {
            villageAdjacentRejectBursts++;
        } else {
            villageAdjacentRejectBursts = Math.max(0, villageAdjacentRejectBursts - 1);
        }
    }

    boolean shouldActivateCarveMode() {
        return navMode != FortifyNavMode.CARVE_CORRIDOR
                && (sameStuckPosCount >= 2
                || noRealProgressElapsedMs() >= 3_000L
                || villageAdjacentRejectBursts >= 2);
    }

    void activateCarveMode() {
        navMode = FortifyNavMode.CARVE_CORRIDOR;
    }

    void resetToRerouteMode() {
        navMode = FortifyNavMode.REROUTE_ONLY;
    }

    static void trim(Set<BlockPos> set, int max) {
        while (set.size() > max) {
            Iterator<BlockPos> it = set.iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }
}

final class ScaffoldLedger {
    final LinkedHashSet<BlockPos> sessionTracked = new LinkedHashSet<>();
    final LinkedHashSet<BlockPos> memoryTracked = new LinkedHashSet<>();
    final LinkedHashSet<BlockPos> reconciledNearby = new LinkedHashSet<>();

    List<BlockPos> allCandidatesTopDown() {
        LinkedHashSet<BlockPos> all = new LinkedHashSet<>();
        all.addAll(sessionTracked);
        all.addAll(memoryTracked);
        all.addAll(reconciledNearby);
        List<BlockPos> out = new ArrayList<>(all);
        out.sort(Comparator.comparingInt(BlockPos::getY).reversed());
        return out;
    }
}
