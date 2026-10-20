package net.wcfcarolina13.GameAI.skills.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The soil under trees any bot felled or partly felled lately, shared by every bot's woodcut
 * cleanup (finding 52: with two bots cutting near each other, one bot's cleanup sweep dug out the
 * dirt under the other bot's tree, because each run only protected the soil of its own trees).
 * A felled base protects the 3x3 ring one block below it; an entry expires on its own after
 * {@link #DEFAULT_TTL_MS}. Pure: a cell is its dimension id plus coords, and time is passed in.
 */
public final class RecentTreeSoilLedger {

    /** How long a felled tree's soil stays protected. */
    public static final long DEFAULT_TTL_MS = 10 * 60_000L;

    /** Entries kept before a record purges the expired ones. */
    private static final int PURGE_THRESHOLD = 4096;

    /** A soil cell: the dimension id (e.g. {@code minecraft:overworld}) and its coords. */
    public record Cell(String dimension, int x, int y, int z) {
    }

    private static final RecentTreeSoilLedger SHARED = new RecentTreeSoilLedger();

    private final Map<Cell, Long> expiresAtByCell = new ConcurrentHashMap<>();

    /** The one ledger all bots share. */
    public static RecentTreeSoilLedger shared() {
        return SHARED;
    }

    /**
     * Whether ({@code x}, {@code y}, {@code z}) is in the soil ring of a trunk based at
     * ({@code baseX}, {@code baseY}, {@code baseZ}): one block below the base and at most one block
     * away on both horizontal axes. The base is one column of the trunk; a 2x2 trunk's other
     * columns may lie on any side of it, so the whole ring is soil.
     */
    public static boolean soilRingContains(int baseX, int baseY, int baseZ, int x, int y, int z) {
        return y == baseY - 1 && Math.abs(x - baseX) <= 1 && Math.abs(z - baseZ) <= 1;
    }

    /** Records the soil ring under the base, protected until {@code nowMs + ttlMs}. */
    public void recordSoilRing(String dimension, int baseX, int baseY, int baseZ, long nowMs, long ttlMs) {
        if (dimension == null) {
            return;
        }
        long expiresAt = nowMs + Math.max(0L, ttlMs);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                expiresAtByCell.merge(new Cell(dimension, baseX + dx, baseY - 1, baseZ + dz), expiresAt, Math::max);
            }
        }
        if (expiresAtByCell.size() > PURGE_THRESHOLD) {
            purgeExpired(nowMs);
        }
    }

    /** Records the soil ring with the default lifetime. */
    public void recordSoilRing(String dimension, int baseX, int baseY, int baseZ, long nowMs) {
        recordSoilRing(dimension, baseX, baseY, baseZ, nowMs, DEFAULT_TTL_MS);
    }

    /** Whether the cell is the live soil of a recently felled tree, in that dimension. */
    public boolean contains(String dimension, int x, int y, int z, long nowMs) {
        if (dimension == null) {
            return false;
        }
        Long expiresAt = expiresAtByCell.get(new Cell(dimension, x, y, z));
        return expiresAt != null && nowMs < expiresAt;
    }

    /** Drops every expired entry. */
    public void purgeExpired(long nowMs) {
        expiresAtByCell.values().removeIf(expiresAt -> nowMs >= expiresAt);
    }

    /** Entries held, expired ones included until the next purge. */
    public int size() {
        return expiresAtByCell.size();
    }
}
