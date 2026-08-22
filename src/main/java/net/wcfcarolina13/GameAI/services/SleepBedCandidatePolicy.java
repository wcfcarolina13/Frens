package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

final class SleepBedCandidatePolicy {

    private SleepBedCandidatePolicy() {}

    static boolean addUniqueFoot(Set<BlockPos> seenFeet, BlockPos foot) {
        return seenFeet != null && foot != null && seenFeet.add(foot.toImmutable());
    }
}
