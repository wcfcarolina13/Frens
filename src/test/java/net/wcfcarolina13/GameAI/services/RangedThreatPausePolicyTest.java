package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for pausing a task when a hostile's projectile hits the bot. No net.minecraft types. */
class RangedThreatPausePolicyTest {

    @AfterEach
    void reset() {
        RangedThreatPausePolicy.resetForTests();
    }

    private static final String WOODCUT = "skill:woodcut";

    @Test
    void projectileHitFromHostileDuringTaskPauses() {
        assertTrue(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, 14.0D, false));
        assertTrue(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, RangedThreatPausePolicy.MAX_SHOOTER_DISTANCE, false));
    }

    @Test
    void eachMissingConditionKeepsWorking() {
        assertFalse(RangedThreatPausePolicy.shouldPause(null, true, true, 14.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(WOODCUT, false, true, 14.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(WOODCUT, true, false, 4.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, 14.0D, true));
        assertFalse(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, 16.5D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, Double.NaN, false));
    }

    @Test
    void retrievalSweepAndSurfaceRecoveryAreNeverPaused() {
        assertFalse(RangedThreatPausePolicy.shouldPause("skill:drop_sweep", true, true, 8.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause("Skill:Drop_Sweep", true, true, 8.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause("surface_recovery", true, true, 8.0D, false));
        assertTrue(RangedThreatPausePolicy.isNeverPauseTask("skill:drop_sweep"));
        assertTrue(RangedThreatPausePolicy.isNeverPauseTask("surface_recovery"));
        assertFalse(RangedThreatPausePolicy.isNeverPauseTask(WOODCUT));
        assertFalse(RangedThreatPausePolicy.isNeverPauseTask("skill:mining"));
        assertFalse(RangedThreatPausePolicy.isNeverPauseTask(null));
        // Other tasks, hobbies included, still pause.
        assertTrue(RangedThreatPausePolicy.shouldPause("skill:mining", true, true, 8.0D, false));
    }

    @Test
    void skipReasonNamesTheFirstFailingCheck() {
        assertEquals(null, RangedThreatPausePolicy.skipReason(WOODCUT, true, true, 9.0D, false));
        assertEquals("no-task", RangedThreatPausePolicy.skipReason(null, true, true, 9.0D, false));
        assertEquals("never-pause-task", RangedThreatPausePolicy.skipReason("skill:drop_sweep", true, true, 9.0D, false));
        assertEquals("not-hostile", RangedThreatPausePolicy.skipReason(WOODCUT, false, true, 9.0D, false));
        // An unarmed mob (e.g. a /summon with NBT gets no bow) hits in melee: not a projectile.
        assertEquals("not-projectile", RangedThreatPausePolicy.skipReason(WOODCUT, true, false, 1.2D, false));
        assertEquals("too-far", RangedThreatPausePolicy.skipReason(WOODCUT, true, true, 20.0D, false));
        assertEquals("too-far", RangedThreatPausePolicy.skipReason(WOODCUT, true, true, Double.NaN, false));
        assertEquals("debounced", RangedThreatPausePolicy.skipReason(WOODCUT, true, true, 9.0D, true));
    }

    @Test
    void skipLogIsThrottledPerBot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(RangedThreatPausePolicy.claimSkipLog(a, 100L));
        assertFalse(RangedThreatPausePolicy.claimSkipLog(a, 100L + RangedThreatPausePolicy.SKIP_LOG_THROTTLE_TICKS - 1));
        assertTrue(RangedThreatPausePolicy.claimSkipLog(b, 101L));
        assertTrue(RangedThreatPausePolicy.claimSkipLog(a, 100L + RangedThreatPausePolicy.SKIP_LOG_THROTTLE_TICKS));
        assertFalse(RangedThreatPausePolicy.claimSkipLog(null, 500L));
    }

    @Test
    void anyHostileProjectilePausesWhateverTheShooterType() {
        // The shooter type is a log field, not a rule: shouldPause takes no type at all.
        assertTrue(RangedThreatPausePolicy.shouldPause(WOODCUT, true, true, 8.0D, false));
    }

    @Test
    void shooterLogFieldNamesTheBowAndCrossbowMobs() {
        assertTrue(RangedThreatPausePolicy.isRangedShooter("minecraft:skeleton"));
        assertTrue(RangedThreatPausePolicy.isRangedShooter("minecraft:stray"));
        assertTrue(RangedThreatPausePolicy.isRangedShooter("minecraft:bogged"));
        assertTrue(RangedThreatPausePolicy.isRangedShooter("minecraft:parched"));
        assertTrue(RangedThreatPausePolicy.isRangedShooter("minecraft:pillager"));
        assertFalse(RangedThreatPausePolicy.isRangedShooter("minecraft:wither_skeleton"));
        assertFalse(RangedThreatPausePolicy.isRangedShooter("minecraft:skeleton_horse"));
        assertFalse(RangedThreatPausePolicy.isRangedShooter("minecraft:zombie"));
        assertFalse(RangedThreatPausePolicy.isRangedShooter(null));
    }

    @Test
    void recentWindowCoversABoggedFireInterval() {
        // Bogged fire every 50 (hard) to 70 ticks; the old 40-tick window closed between shots.
        assertTrue(RangedThreatPausePolicy.RECENT_DAMAGE_WINDOW_TICKS > 70);
    }

    @Test
    void pauseIsDebouncedPerBot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertFalse(RangedThreatPausePolicy.pausedRecently(a, 1_000L));
        RangedThreatPausePolicy.notePause(a, 1_000L);
        assertTrue(RangedThreatPausePolicy.pausedRecently(a, 1_000L));
        assertTrue(RangedThreatPausePolicy.pausedRecently(a, 1_000L + RangedThreatPausePolicy.PAUSE_DEBOUNCE_TICKS - 1));
        assertFalse(RangedThreatPausePolicy.pausedRecently(a, 1_000L + RangedThreatPausePolicy.PAUSE_DEBOUNCE_TICKS));
        assertFalse(RangedThreatPausePolicy.pausedRecently(b, 1_000L));
        // A tick counter that went backwards (server restart) is not "recent".
        assertFalse(RangedThreatPausePolicy.pausedRecently(a, 10L));
        assertFalse(RangedThreatPausePolicy.pausedRecently(null, 1_000L));
    }

    @Test
    void pauseMessageHasNoEmDashOrSemicolon() {
        String msg = RangedThreatPausePolicy.PAUSE_MESSAGE;
        assertFalse(msg.contains("—"));
        assertFalse(msg.contains(";"));
        assertTrue(msg.contains("being shot"));
    }
}
