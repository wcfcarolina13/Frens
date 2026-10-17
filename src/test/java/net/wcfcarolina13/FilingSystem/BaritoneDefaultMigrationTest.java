package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import net.wcfcarolina13.PathFinding.PathFinderChoice;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneDefaultMigrationTest {

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void newConfigDefaultsToUnifiedAndIsNotYetMarked() throws Exception {
        ManualConfig config = newRealConfig();
        assertNull(config.getPathfinder());
        assertEquals(PathFinderChoice.UNIFIED, config.getPathfinderChoice());
        assertTrue(config.isBaritonePathfinderEnabled()); // legacy initializer, read only by the migrations
        assertFalse(config.isBaritoneDefaultMigrated());
        assertFalse(config.isUnifiedDefaultMigrated());
    }

    // ── 1.1.261 step, run after the 1.1.240 step exactly as load() does ──

    private static boolean runBothMigrations(ManualConfig config) {
        boolean changed = config.applyBaritoneDefaultMigration();
        changed |= config.applyUnifiedDefaultMigration();
        return changed;
    }

    @Test
    void preBaritoneFileWithOldDefaultFalseEndsOnUnifiedNotClassic() throws Exception {
        ManualConfig config = newRealConfig();
        config.setBaritonePathfinderEnabled(false); // pre-1.1.240: false was only the old default

        assertTrue(runBothMigrations(config));
        assertNull(config.getPathfinder());
        assertEquals(PathFinderChoice.UNIFIED, config.getPathfinderChoice());
        assertFalse(config.isBaritonePathfinderEnabled()); // synced: true only for BARITONE
    }

    @Test
    void classicPickedUnder1240IsKeptAsClassic() throws Exception {
        ManualConfig config = newRealConfig();
        config.applyBaritoneDefaultMigration(); // marker already set in the file
        config.setBaritonePathfinderEnabled(false); // explicit classic pick

        assertTrue(runBothMigrations(config));
        assertEquals("classic", config.getPathfinder());
        assertEquals(PathFinderChoice.BIDIR, config.getPathfinderChoice());
        assertFalse(config.isBaritonePathfinderEnabled());
    }

    @Test
    void baritoneDefaultMovesToUnifiedOnceAndALaterBaritonePickIsKept() throws Exception {
        ManualConfig config = newRealConfig();
        config.applyBaritoneDefaultMigration(); // Nirn and sandbox saves: true + marker

        assertTrue(runBothMigrations(config));
        assertNull(config.getPathfinder());
        assertTrue(config.isUnifiedDefaultMigrated());

        config.setPathfinder("baritone"); // /bot config pathfinder baritone
        assertTrue(config.isBaritonePathfinderEnabled());
        assertFalse(runBothMigrations(config));
        assertEquals(PathFinderChoice.BARITONE, config.getPathfinderChoice());
    }

    @Test
    void setPathfinderChoiceStoresExplicitTokens() throws Exception {
        ManualConfig config = newRealConfig();
        config.setPathfinderChoice(PathFinderChoice.UNIFIED);
        assertEquals("unified", config.getPathfinder());
        assertFalse(config.isBaritonePathfinderEnabled());
        config.setPathfinderChoice(PathFinderChoice.BIDIR);
        assertEquals("classic", config.getPathfinder());
        config.setPathfinderChoice(PathFinderChoice.BARITONE);
        assertEquals("baritone", config.getPathfinder());
        assertTrue(config.isBaritonePathfinderEnabled());
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
