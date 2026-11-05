package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SunsetReturnAnnouncePolicyTest {

    @Test
    void firstStartOfSessionAnnounces() {
        assertTrue(SunsetReturnAnnouncePolicy.shouldAnnounce(false));
    }

    @Test
    void resumeAfterAnnounceStaysQuiet() {
        assertFalse(SunsetReturnAnnouncePolicy.shouldAnnounce(true));
    }

    @Test
    void startedAnnouncingReturnMarksSession() {
        assertTrue(SunsetReturnAnnouncePolicy.announcedAfter(false, true, true));
    }

    @Test
    void refusedStartDoesNotMarkSession() {
        assertFalse(SunsetReturnAnnouncePolicy.announcedAfter(false, true, false));
    }

    @Test
    void quietStartDoesNotMarkSession() {
        assertFalse(SunsetReturnAnnouncePolicy.announcedAfter(false, false, true));
    }

    @Test
    void markStaysSetOnceAnnounced() {
        assertTrue(SunsetReturnAnnouncePolicy.announcedAfter(true, false, false));
    }

    @Test
    void sessionSequenceAnnouncesExactlyOnce() {
        boolean announced = false;
        int lines = 0;
        // start, then three resumes after interruptions
        for (int i = 0; i < 4; i++) {
            boolean say = SunsetReturnAnnouncePolicy.shouldAnnounce(announced);
            if (say) {
                lines++;
            }
            announced = SunsetReturnAnnouncePolicy.announcedAfter(announced, say, true);
        }
        assertTrue(lines == 1, "expected one line, got " + lines);
    }
}
