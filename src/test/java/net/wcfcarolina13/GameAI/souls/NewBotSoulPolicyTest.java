package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewBotSoulPolicyTest {

    @Test
    void yesGivesANewBotASoul() {
        assertTrue(NewBotSoulPolicy.shouldEnable(true, false));
    }

    @Test
    void notAskedOrNoNeverEnables() {
        assertFalse(NewBotSoulPolicy.shouldEnable(null, false));
        assertFalse(NewBotSoulPolicy.shouldEnable(false, false));
    }

    @Test
    void aBotWithSoulStateIsNeverReEnabled() {
        // A bot someone switched off has a soul.json (active=false): it stays off.
        assertFalse(NewBotSoulPolicy.shouldEnable(true, true));
        assertFalse(NewBotSoulPolicy.shouldEnable(false, true));
    }

    @Test
    void configStartsNotAskedAndKeepsTheAnswer() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ManualConfig config = constructor.newInstance();
        assertNull(config.getSoulNewBotDefault(), "a fresh config has not asked the question");
        config.setSoulNewBotDefault(true);
        assertEquals(Boolean.TRUE, config.getSoulNewBotDefault());
        config.setSoulNewBotDefault(false);
        assertEquals(Boolean.FALSE, config.getSoulNewBotDefault());
    }
}
