package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFinderDefaultPolicyTest {

    @Test
    void fromSavedNullBlankOrUnknownIsUnified() {
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved(null));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved(""));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved("   "));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved("astar"));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved("default"));
    }

    @Test
    void fromSavedReadsExplicitPicks() {
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved("unified"));
        assertEquals(PathFinderChoice.BARITONE, PathFinderDefaultPolicy.fromSaved("Baritone"));
        assertEquals(PathFinderChoice.BIDIR, PathFinderDefaultPolicy.fromSaved("classic"));
        assertEquals(PathFinderChoice.BIDIR, PathFinderDefaultPolicy.fromSaved("bidir"));
    }

    @Test
    void toSavedRoundTripsThroughFromSaved() {
        for (PathFinderChoice choice : PathFinderChoice.values()) {
            assertEquals(choice, PathFinderDefaultPolicy.fromSaved(PathFinderDefaultPolicy.toSaved(choice)));
        }
        assertEquals("classic", PathFinderDefaultPolicy.toSaved(PathFinderChoice.BIDIR));
        assertNull(PathFinderDefaultPolicy.toSaved(null));
    }

    @Test
    void legacyFalseIsKeptAsClassic() {
        PathFinderDefaultPolicy.MigrationResult result = PathFinderDefaultPolicy.migrate(null, false, false);
        assertEquals("classic", result.pathfinder());
        assertTrue(result.needsSave());
    }

    @Test
    void legacyTrueMovesToUnified() {
        PathFinderDefaultPolicy.MigrationResult result = PathFinderDefaultPolicy.migrate(null, true, false);
        assertNull(result.pathfinder());
        assertTrue(result.needsSave());
        assertEquals(PathFinderChoice.UNIFIED, PathFinderDefaultPolicy.fromSaved(result.pathfinder()));
    }

    @Test
    void explicitBaritoneAfterMigrationIsKept() {
        PathFinderDefaultPolicy.MigrationResult result = PathFinderDefaultPolicy.migrate("baritone", true, true);
        assertEquals("baritone", result.pathfinder());
        assertFalse(result.needsSave());
    }

    @Test
    void explicitStringBeforeMigrationIsKeptAndOnlyStamped() {
        PathFinderDefaultPolicy.MigrationResult result = PathFinderDefaultPolicy.migrate("baritone", false, false);
        assertEquals("baritone", result.pathfinder());
        assertTrue(result.needsSave());
    }

    @Test
    void migrationRunsOnce() {
        PathFinderDefaultPolicy.MigrationResult first = PathFinderDefaultPolicy.migrate(null, true, false);
        assertTrue(first.needsSave());
        // After the marker is stamped, even a legacy false (an older client) changes nothing.
        PathFinderDefaultPolicy.MigrationResult second = PathFinderDefaultPolicy.migrate(first.pathfinder(), false, true);
        assertFalse(second.needsSave());
        assertNull(second.pathfinder());
    }
}
