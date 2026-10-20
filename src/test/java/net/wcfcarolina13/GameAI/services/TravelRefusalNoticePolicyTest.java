package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TravelRefusalNoticePolicyTest {
    @Test
    void automaticRetriesRepeatOnlyAfterThreeMinutesOrAReasonChange() {
        var policy = new TravelRefusalNoticePolicy();
        UUID bot = UUID.randomUUID();
        assertTrue(policy.shouldNotify(bot, "combat", true, 100));
        assertFalse(policy.shouldNotify(bot, "combat", true, 101));
        assertFalse(policy.shouldNotify(bot, "combat", true, 3699));
        assertTrue(policy.shouldNotify(bot, "combat", true, 3700));
        assertTrue(policy.shouldNotify(bot, "food", true, 3701));
        assertTrue(policy.shouldNotify(bot, "combat", true, 3702));
    }

    @Test
    void botsAreIndependentAndCommandsAlwaysReplyWithoutChangingAutomaticWindow() {
        var policy = new TravelRefusalNoticePolicy();
        UUID bot = UUID.randomUUID();
        assertTrue(policy.shouldNotify(bot, "food", true, 100));
        assertTrue(policy.shouldNotify(UUID.randomUUID(), "food", true, 101));
        assertTrue(policy.shouldNotify(bot, "food", false, 102));
        assertTrue(policy.shouldNotify(bot, "food", false, 103));
        assertFalse(policy.shouldNotify(bot, "food", true, 104));
    }

    @Test
    void resetAndBackwardClockAllowFreshNotice() {
        var policy = new TravelRefusalNoticePolicy();
        UUID bot = UUID.randomUUID();
        assertTrue(policy.shouldNotify(bot, "cooldown", true, 100));
        assertTrue(policy.shouldNotify(bot, "cooldown", true, 99));
        policy.clear();
        assertTrue(policy.shouldNotify(bot, "cooldown", true, 100));
    }
}
