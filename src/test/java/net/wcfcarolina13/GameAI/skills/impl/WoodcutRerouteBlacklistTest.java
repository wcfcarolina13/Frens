package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WoodcutRerouteBlacklistTest {

    @Test
    void rejectsRecentlyFailedStandForSameTarget() {
        WoodcutRerouteBlacklist blacklist = new WoodcutRerouteBlacklist(1_000L);
        BlockPos target = new BlockPos(5, 80, 5);
        BlockPos stand = new BlockPos(4, 79, 5);

        blacklist.recordFailure(target, stand, 10L);

        assertTrue(blacklist.isBlacklisted(target, stand, 500L));
        assertFalse(blacklist.isBlacklisted(target, stand, 1_011L));
    }

    @Test
    void keepsTargetsIndependent() {
        WoodcutRerouteBlacklist blacklist = new WoodcutRerouteBlacklist(5_000L);
        BlockPos stand = new BlockPos(4, 79, 5);

        blacklist.recordFailure(new BlockPos(5, 80, 5), stand, 10L);

        assertFalse(blacklist.isBlacklisted(new BlockPos(9, 80, 5), stand, 100L));
    }
}
