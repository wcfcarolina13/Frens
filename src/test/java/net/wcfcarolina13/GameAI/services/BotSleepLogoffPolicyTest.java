package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.BotSleepLogoffPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Bots log off when they can't sleep" (1.1.298): the logoff decision and the wake rule. */
class BotSleepLogoffPolicyTest {

    /** A real failure at night in the Overworld with the setting on and the cycle running. */
    private static Decision failedAtNight(boolean settingOn, boolean daylightCycleOn) {
        return BotSleepLogoffPolicy.decide(settingOn, false, false, false, false,
                true, false, false, false, daylightCycleOn);
    }

    @Test
    void settingOnAndARealFailureLogsOffAtOnce() {
        assertEquals(Decision.LOG_OFF, failedAtNight(true, true));
    }

    @Test
    void settingOffStaysOnlineAndSaysSo() {
        assertEquals(Decision.STAY_SETTING_OFF, failedAtNight(false, true));
        assertEquals(Decision.STAY_SETTING_OFF, failedAtNight(false, false));
    }

    @Test
    void daylightCycleOffNeverLogsOffBecauseDawnWouldNeverCome() {
        assertEquals(Decision.STAY_NO_DAYLIGHT_CYCLE, failedAtNight(true, false));
    }

    @Test
    void successOrAlreadySleepingStaysSilent() {
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, true, false, false, false, true, false, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, true, false, true, false, false, false, true));
    }

    @Test
    void cancelledGoneOrStoppingStaysSilent() {
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, true, false, false, true, false, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, false, true, true, false, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, false, false, true, false, true, false, true));
    }

    @Test
    void netherOrEndMountedOrDaytimeNeverLogsOff() {
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, false, false, false, false, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, false, false, true, true, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                true, false, false, false, false, true, false, false, true, true));
    }

    @Test
    void safeguardsWinOverTheSettingOffMessage() {
        // A cancelled or mounted bot with the setting off says nothing: it didn't really fail.
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                false, false, true, false, false, true, false, false, false, true));
        assertEquals(Decision.STAY_SILENT, BotSleepLogoffPolicy.decide(
                false, false, false, false, false, true, true, false, false, true));
    }

    @Test
    void daytimeAlwaysWakes() {
        assertTrue(BotSleepLogoffPolicy.shouldWake(true, false, false, true, true));
        assertTrue(BotSleepLogoffPolicy.shouldWake(true, true, true, false, true));
    }

    @Test
    void dawnOnlyBotsWaitForDawnEvenWhenTheOwnerIsAwake() {
        assertFalse(BotSleepLogoffPolicy.shouldWake(false, true, false, true, true));
        assertFalse(BotSleepLogoffPolicy.shouldWake(false, true, true, true, true));
    }

    @Test
    void olderEntriesKeepTheHumanRule() {
        // dawnOnly false (entries saved before 1.1.298): back when a human is awake in the world.
        assertTrue(BotSleepLogoffPolicy.shouldWake(false, true, false, false, true));
        assertFalse(BotSleepLogoffPolicy.shouldWake(false, true, true, false, true));
        assertFalse(BotSleepLogoffPolicy.shouldWake(false, false, false, false, true));
    }

    @Test
    void dawnOnlyFallsBackToTheHumanRuleIfTheDaylightCycleWasTurnedOff() {
        assertTrue(BotSleepLogoffPolicy.shouldWake(false, true, false, true, false));
        assertFalse(BotSleepLogoffPolicy.shouldWake(false, true, true, true, false));
    }

    @Test
    void aBotAlreadyOnlineIsNotSpawnedTwice() {
        assertFalse(BotSleepLogoffPolicy.shouldRespawn(true));
        assertTrue(BotSleepLogoffPolicy.shouldRespawn(false));
    }

    @Test
    void daytimeMatchesTheWakeMonitorBoundaries() {
        assertTrue(BotSleepLogoffPolicy.isDaytime(0));
        assertTrue(BotSleepLogoffPolicy.isDaytime(11_999));
        assertFalse(BotSleepLogoffPolicy.isDaytime(12_000));
        assertFalse(BotSleepLogoffPolicy.isDaytime(23_999));
        assertTrue(BotSleepLogoffPolicy.isDaytime(24_000 + 100));
        assertFalse(BotSleepLogoffPolicy.isDaytime(-1));
    }
}
