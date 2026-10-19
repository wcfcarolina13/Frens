package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DangerAlertPolicyTest {

    @Test
    void announcesOnlyWhenSomethingWasPausedAndEpisodeIsFresh() {
        assertTrue(DangerAlertPolicy.shouldAnnounce(true, false));
        assertFalse(DangerAlertPolicy.shouldAnnounce(true, true));
        assertFalse(DangerAlertPolicy.shouldAnnounce(false, false));
        assertFalse(DangerAlertPolicy.shouldAnnounce(false, true));
    }

    @Test
    void episodeResetsOnlyWhenNoHostileInRange() {
        assertTrue(DangerAlertPolicy.shouldResetEpisode(false));
        assertFalse(DangerAlertPolicy.shouldResetEpisode(true));
    }

    @Test
    void saidOncePerEpisodeWhileHostileStays() {
        Map<UUID, Boolean> episodes = new ConcurrentHashMap<>();
        UUID bot = UUID.randomUUID();
        DangerAlertPolicy.onHostileScan(episodes, bot, true);
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, bot, true));
        for (int tick = 0; tick < 20; tick++) {
            DangerAlertPolicy.onHostileScan(episodes, bot, true);
            assertFalse(DangerAlertPolicy.tryAnnounce(episodes, bot, true));
        }
    }

    @Test
    void newEpisodeAfterHostilesClear() {
        Map<UUID, Boolean> episodes = new ConcurrentHashMap<>();
        UUID bot = UUID.randomUUID();
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, bot, true));
        DangerAlertPolicy.onHostileScan(episodes, bot, false);
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, bot, true));
    }

    @Test
    void nothingPausedDoesNotStartAnEpisode() {
        Map<UUID, Boolean> episodes = new ConcurrentHashMap<>();
        UUID bot = UUID.randomUUID();
        assertFalse(DangerAlertPolicy.tryAnnounce(episodes, bot, false));
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, bot, true));
    }

    @Test
    void episodesArePerBot() {
        Map<UUID, Boolean> episodes = new ConcurrentHashMap<>();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, a, true));
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, b, true));
        assertFalse(DangerAlertPolicy.tryAnnounce(episodes, a, true));
        DangerAlertPolicy.onHostileScan(episodes, a, false);
        assertTrue(DangerAlertPolicy.tryAnnounce(episodes, a, true));
        assertFalse(DangerAlertPolicy.tryAnnounce(episodes, b, true));
    }

    @Test
    void nullInputsAreSafe() {
        assertFalse(DangerAlertPolicy.tryAnnounce(null, UUID.randomUUID(), true));
        assertFalse(DangerAlertPolicy.tryAnnounce(new ConcurrentHashMap<>(), null, true));
        DangerAlertPolicy.onHostileScan(null, UUID.randomUUID(), false);
        DangerAlertPolicy.onHostileScan(new ConcurrentHashMap<>(), null, false);
    }
}
