package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;

public final class BackgroundSweepPolicy {

    private BackgroundSweepPolicy() {
    }

    public static boolean isIdleSweepOriginSafe(
            SafePositionService.SurfaceCandidateAssessment assessment) {
        if (assessment == null || !assessment.standable()) {
            return false;
        }
        if (!assessment.openSky() && !assessment.nearSurface()) {
            return false;
        }
        return assessment.steepDropNeighbors() <= 1
                && assessment.blockedCardinals() <= 2;
    }

    public static Map<BlockPos, Long> pruneAndGetIdleSweepBlacklist(UUID botUuid, long currentServerTick) {
        if (botUuid == null) {
            return null;
        }
        Map<BlockPos, Long> blacklist = FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.get(botUuid);
        if (blacklist == null || blacklist.isEmpty()) {
            return null;
        }
        blacklist.entrySet().removeIf(entry -> entry.getValue() <= currentServerTick);
        if (blacklist.isEmpty()) {
            FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.remove(botUuid);
            return null;
        }
        return blacklist;
    }

    public static boolean clearPendingIdleSweepState(UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        boolean hadState = FollowStateService.IDLE_SWEEP_START_TICK.containsKey(botUuid)
                || FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.containsKey(botUuid)
                || FollowStateService.IDLE_SWEEP_BOT_BLOCK.containsKey(botUuid)
                || FollowStateService.IDLE_SWEEP_ACTIVE.containsKey(botUuid)
                || FollowStateService.IDLE_SWEEP_TARGET.containsKey(botUuid);
        FollowStateService.clearIdleSweep(botUuid);
        return hadState;
    }
}
