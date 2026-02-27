package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.FortificationLayout;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.WallPoint;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Tracks entombment recovery state, surface-escape retry state, and recent carve
 * column cooldowns for the fortify village skill.  Pure state-tracking — no world
 * mutations, no movement.  The parent skill feeds shared context via
 * {@link #updateScope}, {@link #updateLayout}, and {@link #updateMovementEpoch}.
 */
final class FortifyEntombmentHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fortify-village");

    // ── constants ──────────────────────────────────────────────────────────────
    static final long FORTIFY_ENTOMBMENT_STATE_TTL_MS = 20_000L;
    static final long FORTIFY_RECENT_CARVE_SUPPRESS_MS = 20_000L;
    static final long FORTIFY_SURFACE_ESCAPE_FAIL_TTL_MS = 8_000L;
    static final int FORTIFY_SURFACE_ESCAPE_SAME_COLUMN_LIMIT = 2;

    // ── inner types ────────────────────────────────────────────────────────────

    static final class EntombmentRecoveryState {
        final String key;
        final BlockPos pos;
        final String contextPrefix;
        final long epoch;
        int noProgressCycles;
        int surfaceEscapeFailures;
        int scaffoldEscalationFailures;
        int breakThroughFailures;
        long lastUpdatedMs;

        EntombmentRecoveryState(String key, BlockPos pos, String contextPrefix, long epoch) {
            this.key = key;
            this.pos = pos == null ? null : pos.toImmutable();
            this.contextPrefix = contextPrefix;
            this.epoch = epoch;
            this.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    static final class SurfaceEscapeRetryState {
        int failures;
        long lastFailureMs;
    }

    // ── own state ──────────────────────────────────────────────────────────────

    private final Map<String, EntombmentRecoveryState> entombmentRecoveryStates = new HashMap<>();
    private final Map<String, SurfaceEscapeRetryState> surfaceEscapeRetryStates = new HashMap<>();
    private final Map<BlockPos, Long> recentCarveColumnsMs = new HashMap<>();

    // ── shared context (set by parent skill) ───────────────────────────────────

    private FortificationLayout currentLayout;
    private String activeScopeContext;
    private long activeScopeEpoch;
    private long movementEpoch;

    // ── context update API ─────────────────────────────────────────────────────

    void updateScope(String scopeContext, long scopeEpoch) {
        this.activeScopeContext = scopeContext;
        this.activeScopeEpoch = scopeEpoch;
    }

    void updateLayout(FortificationLayout layout) {
        this.currentLayout = layout;
    }

    void updateMovementEpoch(long epoch) {
        this.movementEpoch = epoch;
    }

    // ── static utility ─────────────────────────────────────────────────────────

    /**
     * Maps a navContext / scope context string to a short prefix
     * ({@code "fortify-edge"}, {@code "fortify-tower"}, {@code "fortify-gate"}).
     */
    static String fortifyContextPrefix(String navContext, String scopeContext) {
        if (navContext != null) {
            if (navContext.startsWith("fortify-edge:")) return "fortify-edge";
            if (navContext.startsWith("fortify-tower:")) return "fortify-tower";
            if (navContext.startsWith("fortify-gate:")) return "fortify-gate";
        }
        if (scopeContext == null) return null;
        if (scopeContext.startsWith("fortify-edge:")) return "fortify-edge";
        if (scopeContext.startsWith("fortify-tower:")) return "fortify-tower";
        if (scopeContext.startsWith("fortify-gate:")) return "fortify-gate";
        return null;
    }

    // ── entombment recovery state ──────────────────────────────────────────────

    private String entombmentRecoveryKey(BlockPos pos, String contextPrefix, long epoch) {
        if (pos == null || contextPrefix == null) return null;
        return contextPrefix + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private EntombmentRecoveryState getEntombmentRecoveryState(BlockPos pos, String contextPrefix,
                                                               long epoch, boolean create) {
        if (pos == null || contextPrefix == null) return null;
        pruneStaleEntombmentRecoveryStates();
        String key = entombmentRecoveryKey(pos, contextPrefix, epoch);
        if (key == null) return null;
        EntombmentRecoveryState state = entombmentRecoveryStates.get(key);
        if (state == null && create) {
            state = new EntombmentRecoveryState(key, pos, contextPrefix, epoch);
            entombmentRecoveryStates.put(key, state);
        }
        if (state != null) {
            state.lastUpdatedMs = System.currentTimeMillis();
        }
        return state;
    }

    private void pruneStaleEntombmentRecoveryStates() {
        if (entombmentRecoveryStates.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, EntombmentRecoveryState>> it = entombmentRecoveryStates.entrySet().iterator();
        while (it.hasNext()) {
            EntombmentRecoveryState state = it.next().getValue();
            if (state == null) {
                it.remove();
                continue;
            }
            if (now - state.lastUpdatedMs > FORTIFY_ENTOMBMENT_STATE_TTL_MS) {
                it.remove();
            }
        }
    }

    void noteEntombmentSurfaceEscapeFailure(ServerWorld world, BlockPos pos, String navContext) {
        if (world == null || pos == null) return;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return;
        if (!contextPrefix.startsWith("fortify-")) return;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, true);
        if (state != null) {
            state.surfaceEscapeFailures++;
        }
    }

    void noteEntombmentNoProgressCycle(ServerWorld world, BlockPos pos, String navContext) {
        if (world == null || pos == null) return;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, true);
        if (state != null) {
            state.noProgressCycles++;
        }
    }

    boolean hasFortifyPocketNoProgressBurst(ServerWorld world, BlockPos pos, String navContext, int minCycles) {
        if (world == null || pos == null || minCycles <= 0) return false;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return false;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, false);
        return state != null && state.noProgressCycles >= minCycles;
    }

    void noteEntombmentBreakFailure(ServerWorld world, BlockPos pos, String navContext) {
        if (world == null || pos == null) return;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, true);
        if (state != null) {
            state.breakThroughFailures++;
        }
    }

    void noteEntombmentScaffoldFailure(ServerWorld world, BlockPos pos, String navContext) {
        if (world == null || pos == null) return;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, true);
        if (state != null) {
            state.scaffoldEscalationFailures++;
        }
    }

    void noteEntombmentRecoverySuccess(ServerWorld world, BlockPos before, BlockPos after, String navContext) {
        if (world == null || before == null || after == null) return;
        if (before.equals(after)) return;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null) return;
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        boolean afterStillEntombed = isFortifyEntombmentCandidate(world, after, navContext);
        int beforeTerrainY = VillageFortificationLayoutService.terrainY(world, before.getX(), before.getZ());
        int afterTerrainY = VillageFortificationLayoutService.terrainY(world, after.getX(), after.getZ());
        int beforeDepth = Math.max(0, beforeTerrainY - before.getY());
        int afterDepth = Math.max(0, afterTerrainY - after.getY());
        boolean resolvedEntombment = !afterStillEntombed || afterDepth < beforeDepth || before.getSquaredDistance(after) >= 9.0D;

        EntombmentRecoveryState beforeState = getEntombmentRecoveryState(before, contextPrefix, epoch, false);
        if (beforeState != null) {
            if (resolvedEntombment) {
                beforeState.noProgressCycles = 0;
                beforeState.surfaceEscapeFailures = 0;
                beforeState.scaffoldEscalationFailures = 0;
                beforeState.breakThroughFailures = 0;
            }
            beforeState.lastUpdatedMs = System.currentTimeMillis();
        }
        EntombmentRecoveryState afterState = getEntombmentRecoveryState(after, contextPrefix, epoch, false);
        if (afterState != null) {
            if (resolvedEntombment) {
                afterState.noProgressCycles = 0;
                afterState.surfaceEscapeFailures = 0;
                afterState.scaffoldEscalationFailures = 0;
                afterState.breakThroughFailures = 0;
            }
            afterState.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    // ── hull / trap predicates (trivial delegates) ─────────────────────────────

    boolean isAdjacentToCurrentFortificationHull(BlockPos pos) {
        if (pos == null) return false;
        if (isInsideCurrentFortificationHull(pos)) return true;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isInsideCurrentFortificationHull(pos.offset(dir))) {
                return true;
            }
        }
        return false;
    }

    boolean isInsideCurrentFortificationHull(BlockPos pos) {
        if (pos == null || currentLayout == null) return false;
        List<WallPoint> hull = currentLayout.hullVertices();
        if (hull == null || hull.size() < 3) return false;
        return VillageFortificationLayoutService.pointInConvexHull(hull, pos.getX(), pos.getZ());
    }

    private boolean isTrapLikeCell(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        VoxelJunctionService.VoxelStandCell cell = VoxelJunctionService.analyzeStandCell(world, pos);
        return cell.topology() == VoxelJunctionService.CellTopology.POCKET
                || cell.topology() == VoxelJunctionService.CellTopology.DEAD_END
                || cell.openFaces() <= 1;
    }

    private boolean canStandAt(ServerWorld world, BlockPos pos) {
        return VoxelJunctionService.isStandable(world, pos);
    }

    // ── entombment candidate / prefer-escape ───────────────────────────────────

    boolean isFortifyEntombmentCandidate(ServerWorld world, BlockPos pos, String navContext) {
        if (world == null || pos == null) return false;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        if (contextPrefix == null || currentLayout == null) return false;
        if (!(contextPrefix.equals("fortify-edge") || contextPrefix.equals("fortify-tower"))) return false;
        int terrainY = VillageFortificationLayoutService.terrainY(world, pos.getX(), pos.getZ());
        int depth = terrainY - pos.getY();
        if (depth <= 0 || depth > 3) return false;
        if (!isTrapLikeCell(world, pos)) return false;
        return isAdjacentToCurrentFortificationHull(pos);
    }

    boolean shouldPreferEntombmentEscape(ServerWorld world, BlockPos pos, String navContext) {
        if (!isFortifyEntombmentCandidate(world, pos, navContext)) return false;
        String contextPrefix = fortifyContextPrefix(navContext, activeScopeContext);
        long epoch = activeScopeContext != null ? activeScopeEpoch : movementEpoch;
        EntombmentRecoveryState state = getEntombmentRecoveryState(pos, contextPrefix, epoch, false);
        if (state == null) return false;
        return state.surfaceEscapeFailures >= 2
                || state.noProgressCycles >= 2
                || state.scaffoldEscalationFailures >= 1
                || state.breakThroughFailures >= 2;
    }

    // ── surface escape retry tracking ──────────────────────────────────────────

    String surfaceEscapeRetryKey(BlockPos pos, int referenceSurfaceY) {
        if (pos == null) return null;
        final int bucketSize = 4;
        int bucketX = Math.floorDiv(pos.getX(), bucketSize);
        int bucketZ = Math.floorDiv(pos.getZ(), bucketSize);
        return referenceSurfaceY + "|" + bucketX + "," + bucketZ;
    }

    private void pruneSurfaceEscapeRetryStates() {
        if (surfaceEscapeRetryStates.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, SurfaceEscapeRetryState>> it = surfaceEscapeRetryStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SurfaceEscapeRetryState> entry = it.next();
            if (entry == null || entry.getValue() == null) {
                it.remove();
                continue;
            }
            SurfaceEscapeRetryState state = entry.getValue();
            if (now - state.lastFailureMs > FORTIFY_SURFACE_ESCAPE_FAIL_TTL_MS) {
                it.remove();
            }
        }
    }

    boolean shouldSkipRepeatedSurfaceEscape(BlockPos pos, int referenceSurfaceY) {
        String key = surfaceEscapeRetryKey(pos, referenceSurfaceY);
        if (key == null) return false;
        pruneSurfaceEscapeRetryStates();
        SurfaceEscapeRetryState state = surfaceEscapeRetryStates.get(key);
        if (state == null) return false;
        return state.failures >= FORTIFY_SURFACE_ESCAPE_SAME_COLUMN_LIMIT
                && (System.currentTimeMillis() - state.lastFailureMs) <= FORTIFY_SURFACE_ESCAPE_FAIL_TTL_MS;
    }

    int getSurfaceEscapeRetryFailureCount(BlockPos pos, int referenceSurfaceY) {
        String key = surfaceEscapeRetryKey(pos, referenceSurfaceY);
        if (key == null) return 0;
        pruneSurfaceEscapeRetryStates();
        SurfaceEscapeRetryState state = surfaceEscapeRetryStates.get(key);
        if (state == null) return 0;
        if ((System.currentTimeMillis() - state.lastFailureMs) > FORTIFY_SURFACE_ESCAPE_FAIL_TTL_MS) {
            return 0;
        }
        return state.failures;
    }

    int noteSurfaceEscapeRetryFailure(BlockPos pos, int referenceSurfaceY) {
        String key = surfaceEscapeRetryKey(pos, referenceSurfaceY);
        if (key == null) return 0;
        pruneSurfaceEscapeRetryStates();
        SurfaceEscapeRetryState state = surfaceEscapeRetryStates.computeIfAbsent(key, ignored -> new SurfaceEscapeRetryState());
        state.failures++;
        state.lastFailureMs = System.currentTimeMillis();
        return state.failures;
    }

    void clearSurfaceEscapeRetryState(BlockPos pos, int referenceSurfaceY) {
        String key = surfaceEscapeRetryKey(pos, referenceSurfaceY);
        if (key != null) {
            surfaceEscapeRetryStates.remove(key);
        }
    }

    // ── carve column cooldown ──────────────────────────────────────────────────

    private void pruneRecentCarveColumns() {
        if (recentCarveColumnsMs.isEmpty()) return;
        long now = System.currentTimeMillis();
        recentCarveColumnsMs.entrySet().removeIf(e -> e == null || e.getKey() == null || (now - e.getValue()) > FORTIFY_RECENT_CARVE_SUPPRESS_MS);
    }

    boolean isRecentCarveColumnOnCooldown(BlockPos pos) {
        if (pos == null) return false;
        pruneRecentCarveColumns();
        Long ts = recentCarveColumnsMs.get(pos);
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) <= FORTIFY_RECENT_CARVE_SUPPRESS_MS;
    }

    void noteRecentCarveColumn(BlockPos pos) {
        if (pos != null) {
            recentCarveColumnsMs.put(pos.toImmutable(), System.currentTimeMillis());
        }
    }

    // ── sealed surface-escape cell ─────────────────────────────────────────────

    boolean isSealedFortifyEntombmentSurfaceEscapeCell(ServerWorld world, BlockPos botPos, int referenceSurfaceY) {
        if (world == null || botPos == null) return false;
        int depth = referenceSurfaceY - botPos.getY();
        if (depth <= 0 || depth > 3) return false;
        if (!isFortifyEntombmentCandidate(world, botPos, null)) return false;
        BlockState overhead1 = world.getBlockState(botPos.up());
        BlockState overhead2 = world.getBlockState(botPos.up(2));
        boolean blockedOverhead = !overhead1.getCollisionShape(world, botPos.up()).isEmpty()
                || !overhead2.getCollisionShape(world, botPos.up(2)).isEmpty();
        if (!blockedOverhead) return false;

        int blockedDirs = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos nextFeet = botPos.offset(dir).up();
            if (!canStandAt(world, nextFeet)) {
                blockedDirs++;
                continue;
            }
            BlockPos nextHead = nextFeet.up();
            BlockPos nextOver = nextHead.up();
            boolean nextBlocked = !world.getBlockState(nextHead).getCollisionShape(world, nextHead).isEmpty()
                    || !world.getBlockState(nextOver).getCollisionShape(world, nextOver).isEmpty();
            if (nextBlocked) {
                blockedDirs++;
            }
        }
        return blockedDirs >= 4;
    }
}
