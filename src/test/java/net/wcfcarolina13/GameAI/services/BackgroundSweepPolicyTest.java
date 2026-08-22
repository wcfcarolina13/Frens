package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundSweepPolicyTest {

    @Test
    void rejectsOpenSkyOriginThatIsNotStandable() {
        SafePositionService.SurfaceCandidateAssessment unsafeOrigin =
                new SafePositionService.SurfaceCandidateAssessment(
                        true,
                        false,
                        true,
                        0,
                        1,
                        4,
                        4,
                        5,
                        0.0D
                );

        assertFalse(BackgroundSweepPolicy.isIdleSweepOriginSafe(unsafeOrigin));
    }

    @Test
    void rejectsStandableOriginAtRavineEdge() {
        SafePositionService.SurfaceCandidateAssessment ravineEdge =
                new SafePositionService.SurfaceCandidateAssessment(
                        true,
                        true,
                        true,
                        2,
                        4,
                        2,
                        2,
                        5,
                        0.0D
                );

        assertFalse(BackgroundSweepPolicy.isIdleSweepOriginSafe(ravineEdge));
    }

    @Test
    void rejectsEnclosedUndergroundOrigin() {
        SafePositionService.SurfaceCandidateAssessment underground =
                new SafePositionService.SurfaceCandidateAssessment(
                        false,
                        true,
                        false,
                        3,
                        6,
                        0,
                        1,
                        1,
                        0.0D
                );

        assertFalse(BackgroundSweepPolicy.isIdleSweepOriginSafe(underground));
    }

    @Test
    void acceptsStandableOriginAwayFromSteepDrops() {
        SafePositionService.SurfaceCandidateAssessment safeOrigin =
                new SafePositionService.SurfaceCandidateAssessment(
                        true,
                        true,
                        true,
                        3,
                        6,
                        1,
                        1,
                        2,
                        0.0D
                );

        assertTrue(BackgroundSweepPolicy.isIdleSweepOriginSafe(safeOrigin));
    }

    @Test
    void pruneIdleSweepBlacklistKeepsOnlyTargetsAfterCurrentServerTick() {
        UUID botId = UUID.randomUUID();
        BlockPos expired = new BlockPos(1, 64, 1);
        BlockPos boundary = new BlockPos(2, 64, 2);
        BlockPos active = new BlockPos(3, 64, 3);
        FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST
                .computeIfAbsent(botId, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(expired, 99L);
        FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.get(botId).put(boundary, 100L);
        FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.get(botId).put(active, 101L);

        try {
            java.util.Map<BlockPos, Long> remaining =
                    BackgroundSweepPolicy.pruneAndGetIdleSweepBlacklist(botId, 100L);

            assertFalse(remaining.containsKey(expired));
            assertFalse(remaining.containsKey(boundary));
            assertTrue(remaining.containsKey(active));
        } finally {
            FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.remove(botId);
        }
    }

    @Test
    void pruneIdleSweepBlacklistRemovesEmptyPerBotMap() {
        UUID botId = UUID.randomUUID();
        FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST
                .computeIfAbsent(botId, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(new BlockPos(1, 64, 1), 50L);

        assertNull(BackgroundSweepPolicy.pruneAndGetIdleSweepBlacklist(botId, 50L));
        assertFalse(FollowStateService.IDLE_SWEEP_TARGET_BLACKLIST.containsKey(botId));
    }

    @Test
    void clearPendingIdleSweepStateRemovesAllIdleSweepMarkers() {
        UUID botId = UUID.randomUUID();
        FollowStateService.IDLE_SWEEP_START_TICK.put(botId, 42L);
        FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.put(botId, new BlockPos(1, 64, 1));
        FollowStateService.IDLE_SWEEP_BOT_BLOCK.put(botId, new BlockPos(2, 64, 2));
        FollowStateService.IDLE_SWEEP_ACTIVE.put(botId, true);
        FollowStateService.IDLE_SWEEP_TARGET.put(botId, new BlockPos(3, 64, 3));

        try {
            assertTrue(BackgroundSweepPolicy.clearPendingIdleSweepState(botId));
            assertFalse(FollowStateService.IDLE_SWEEP_START_TICK.containsKey(botId));
            assertFalse(FollowStateService.IDLE_SWEEP_PLAYER_BLOCK.containsKey(botId));
            assertFalse(FollowStateService.IDLE_SWEEP_BOT_BLOCK.containsKey(botId));
            assertFalse(FollowStateService.IDLE_SWEEP_ACTIVE.containsKey(botId));
            assertFalse(FollowStateService.IDLE_SWEEP_TARGET.containsKey(botId));
        } finally {
            FollowStateService.clearIdleSweep(botId);
        }
    }

    @Test
    void clearPendingIdleSweepStateReportsNoStateWhenNothingWasQueued() {
        UUID botId = UUID.randomUUID();
        FollowStateService.clearIdleSweep(botId);
        assertFalse(BackgroundSweepPolicy.clearPendingIdleSweepState(botId));
    }
}
