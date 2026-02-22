package net.wcfcarolina13.GameAI.services.construction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Pure helpers used by fortify execution policies.
 * These utilities intentionally avoid Minecraft class references
 * so they can be unit tested in a plain JVM test runtime.
 */
public final class FortifyExecutionPolicyUtil {

    private FortifyExecutionPolicyUtil() {}

    public static long packXZ(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public static int safeSurfaceY(int referenceSurfaceY, Map<Long, Integer> plannedYByXZ, int terrainY, int x, int z) {
        int raw = terrainY;
        if (plannedYByXZ != null) {
            raw = plannedYByXZ.getOrDefault(packXZ(x, z), terrainY);
        }
        return Math.max(raw, referenceSurfaceY - 1);
    }

    public static int segmentBucketForLine(int startX, int startZ, int endX, int endZ, int x, int z, double segSize) {
        double eDx = endX - startX;
        double eDz = endZ - startZ;
        double eLen = Math.sqrt(eDx * eDx + eDz * eDz);
        double dX = eLen > 0.001 ? eDx / eLen : 1;
        double dZ = eLen > 0.001 ? eDz / eLen : 0;
        double px = x - startX;
        double pz = z - startZ;
        return (int) Math.floor((px * dX + pz * dZ) / segSize);
    }

    public static boolean shouldStopAfterNoProgressSegments(int noProgressSegments, int threshold) {
        return threshold > 0 && noProgressSegments >= threshold;
    }

    public static boolean shouldTriggerDepthRecovery(int botY, int referenceSurfaceY) {
        return botY <= (referenceSurfaceY - 2);
    }

    public static int moatPass1FailureThreshold(int pass1MovedSteps,
                                                 int zeroMovementThreshold,
                                                 int normalThreshold) {
        return pass1MovedSteps <= 0 ? zeroMovementThreshold : normalThreshold;
    }

    public static boolean shouldRetryPass1AfterRecovery(int pass1MinedCount,
                                                        int retriesUsed,
                                                        boolean recoveryProgress,
                                                        int maxRetries) {
        return pass1MinedCount <= 0 && recoveryProgress && retriesUsed < maxRetries;
    }

    public static int compareDirectTargets(int botX, int botY, int botZ,
                                           int ax, int ay, int az,
                                           int bx, int by, int bz) {
        double aHoriz = horizontalDistSq(botX, botZ, ax, az);
        double bHoriz = horizontalDistSq(botX, botZ, bx, bz);
        int cmp = Double.compare(aHoriz, bHoriz);
        if (cmp != 0) return cmp;

        cmp = Integer.compare(Math.abs(ay - botY), Math.abs(by - botY));
        if (cmp != 0) return cmp;

        cmp = Integer.compare(by, ay); // descending Y
        if (cmp != 0) return cmp;

        cmp = Integer.compare(ax, bx);
        if (cmp != 0) return cmp;

        cmp = Integer.compare(az, bz);
        if (cmp != 0) return cmp;

        return Integer.compare(ay, by);
    }

    private static double horizontalDistSq(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    public static <T> T awaitFutureResult(CompletableFuture<T> future,
                                          BooleanSupplier abortCheck,
                                          long timeoutMs,
                                          long pollIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (abortCheck != null && abortCheck.getAsBoolean()) {
                future.cancel(true);
                return null;
            }
            if (future.isDone()) {
                try {
                    return future.getNow(null);
                } catch (Exception ignored) {
                    return null;
                }
            }
            try {
                Thread.sleep(Math.max(1L, pollIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return null;
            }
        }
        future.cancel(true);
        return null;
    }
}
