package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneDefaultMigrationTest {

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void newConfigDefaultsToBaritoneAndIsNotYetMarked() throws Exception {
        ManualConfig config = newRealConfig();
        assertTrue(config.isBaritonePathfinderEnabled());
        assertFalse(config.isBaritoneDefaultMigrated());
    }

    @Test
    void persistedOldDefaultFalseFlipsToBaritoneOnce() throws Exception {
        ManualConfig config = newRealConfig();
        config.setBaritonePathfinderEnabled(false); // what a pre-1.1.240 file holds

        assertTrue(config.applyBaritoneDefaultMigration());
        assertTrue(config.isBaritonePathfinderEnabled());
        assertTrue(config.isBaritoneDefaultMigrated());

        assertFalse(config.applyBaritoneDefaultMigration());
        assertTrue(config.isBaritonePathfinderEnabled());
    }

    @Test
    void classicChosenAfterMigrationIsKept() throws Exception {
        ManualConfig config = newRealConfig();
        config.setBaritonePathfinderEnabled(false);
        config.applyBaritoneDefaultMigration();

        config.setBaritonePathfinderEnabled(false); // user picks classic later
        assertFalse(config.applyBaritoneDefaultMigration());
        assertFalse(config.isBaritonePathfinderEnabled());
    }

    @Test
    void alreadyBaritoneOnlySetsTheMarker() throws Exception {
        ManualConfig config = newRealConfig();
        assertTrue(config.applyBaritoneDefaultMigration());
        assertTrue(config.isBaritonePathfinderEnabled());
        assertTrue(config.isBaritoneDefaultMigrated());
    }
}
