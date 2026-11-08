package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;

/** Result of {@link LeafClearService#clearLeafObstructionDetailed}. */
public record LeafClearResult(LeafClearAction action,
                              boolean leafInPath,
                              int bypassFailures,
                              BlockPos leafTarget,
                              String detail) {
    public boolean countsAsCleared() {
        return action == LeafClearAction.MINING_STARTED
                || action == LeafClearAction.MINING_IN_PROGRESS
                || action == LeafClearAction.BYPASS_PROGRESS;
    }
}
