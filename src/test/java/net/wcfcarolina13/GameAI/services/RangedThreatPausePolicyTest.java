package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for pausing a task when a hostile's projectile hits the bot. No net.minecraft types. */
class RangedThreatPausePolicyTest {

    @AfterEach
    void reset() {
        RangedThreatPausePolicy.resetForTests();
    }

    @Test
    void projectileHitFromHostileDuringTaskPauses() {
        assertTrue(RangedThreatPausePolicy.shouldPause(true, true, true, 14.0D, false));
        assertTrue(RangedThreatPausePolicy.shouldPause(true, true, true, RangedThreatPausePolicy.MAX_SHOOTER_DISTANCE, false));
    }

    @Test
    void eachMissingConditionKeepsWorking() {
        assertFalse(RangedThreatPausePolicy.shouldPause(false, true, true, 14.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(true, false, true, 14.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(true, true, false, 4.0D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(true, true, true, 14.0D, true));
        assertFalse(RangedThreatPausePolicy.shouldPause(true, true, true, 16.5D, false));
        assertFalse(RangedThreatPausePolicy.shouldPause(true, true, true, Double.NaN, false));
    }

    @Test
    void rangedShootersAreTheBowAndCrossbowMobs() {
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
