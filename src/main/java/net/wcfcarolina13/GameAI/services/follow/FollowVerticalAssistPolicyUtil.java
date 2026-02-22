package net.wcfcarolina13.GameAI.services.follow;

public final class FollowVerticalAssistPolicyUtil {

    private FollowVerticalAssistPolicyUtil() {
    }

    public static boolean shouldKeepVerticalLock(double verticalGap,
                                                 double remainingTopGap,
                                                 long nowMs,
                                                 long expiresAtMs,
                                                 int noProgressTicks,
                                                 int hardFailTicks) {
        if (verticalGap <= -2.0D) {
            return false;
        }
        if (verticalGap <= 1.5D && remainingTopGap <= 0.8D) {
            return false;
        }
        if (nowMs >= expiresAtMs) {
            return false;
        }
        return noProgressTicks < hardFailTicks;
    }

    public static boolean isReachableEntryStand(int botY,
                                                int standY,
                                                double horizontalDistanceSq,
                                                int maxVerticalDelta,
                                                double maxHorizontalDistanceSq) {
        return Math.abs(standY - botY) <= maxVerticalDelta && horizontalDistanceSq <= maxHorizontalDistanceSq;
    }

    public static double scoreCandidate(boolean reachableEntry,
                                        int absGoalTopY,
                                        double botHorizontalDistanceSq,
                                        double goalHorizontalDistanceSq) {
        double reachPenalty = reachableEntry ? 0.0D : 1_000_000.0D;
        return reachPenalty
                + (absGoalTopY * 100.0D)
                + botHorizontalDistanceSq
                + (goalHorizontalDistanceSq * 0.35D);
    }
}
