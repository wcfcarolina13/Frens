package net.wcfcarolina13.network;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FortBufferConfigTest {
    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void zeroBufferSurvivesReload() throws Exception {
        ManualConfig cfg = newRealConfig();
        assertEquals(8, cfg.getFortBufferRadius());
        cfg.setFortBufferRadius(0);
        assertEquals(0, cfg.getFortBufferRadius());
        cfg.setFortBufferRadius(-1);
        assertEquals(8, cfg.getFortBufferRadius());
    }
}
