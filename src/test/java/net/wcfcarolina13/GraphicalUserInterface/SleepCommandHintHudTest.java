package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepCommandHintHudTest {

    @Test
    void onlyShowsWhileSleepingOnTheSleepingChatScreen() {
        assertTrue(SleepCommandHintHud.shouldRender(true, true));
        assertFalse(SleepCommandHintHud.shouldRender(false, true));
        assertFalse(SleepCommandHintHud.shouldRender(true, false));
    }
}
