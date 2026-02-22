package net.wcfcarolina13.GameAI.services.construction.execution;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * A single target block in an ordered construction plan.
 */
public record PlacementTarget(
        BlockPos pos,
        BlockState desiredState,
        TargetKind kind,
        int priorityBand,
        String groupId
) {
    public enum TargetKind {
        FOUNDATION,
        WALL,
        CORNER,
        ROOF,
        DOOR,
        INTERIOR,
        DECORATION,
        FORTIFY_EDGE,
        FORTIFY_TOWER,
        UNKNOWN
    }
}
