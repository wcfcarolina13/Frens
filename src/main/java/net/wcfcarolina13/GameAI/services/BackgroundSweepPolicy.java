package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

public final class BackgroundSweepPolicy {

    private BackgroundSweepPolicy() {
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
