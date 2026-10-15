package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathFinderOverridesTest {

    @AfterEach
    void reset() {
        PathFinderOverrides.clearAll();
    }

    @Test
    void overrideWinsOverGlobal() {
        assertEquals(PathFinderChoice.BIDIR, PathFinderOverrides.resolve(PathFinderChoice.BIDIR, true));
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolve(PathFinderChoice.BARITONE, false));
    }

    @Test
    void noOverrideFollowsGlobal() {
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolve(null, true));
        assertEquals(PathFinderChoice.BIDIR, PathFinderOverrides.resolve(null, false));
    }

    @Test
    void perBotSetAndClear() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PathFinderOverrides.set(a, PathFinderChoice.BIDIR);
        assertEquals(PathFinderChoice.BIDIR, PathFinderOverrides.resolveFor(a, true));
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolveFor(b, true));
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolveFor(null, true));
        PathFinderOverrides.set(a, null);
        assertNull(PathFinderOverrides.get(a));
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolveFor(a, true));
    }

    @Test
    void clearAllDropsEveryOverride() {
        PathFinderOverrides.set(UUID.randomUUID(), PathFinderChoice.BIDIR);
        PathFinderOverrides.set(UUID.randomUUID(), PathFinderChoice.BARITONE);
        assertEquals(2, PathFinderOverrides.size());
        PathFinderOverrides.clearAll();
        assertEquals(0, PathFinderOverrides.size());
    }

    @Test
    void parseAcceptsCommandTokens() {
        assertEquals(PathFinderChoice.BARITONE, PathFinderChoice.parse("Baritone"));
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.parse("classic"));
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.parse("bidir"));
        assertNull(PathFinderChoice.parse("default"));
        assertNull(PathFinderChoice.parse(null));
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.BARITONE.other());
        assertEquals("bidir", PathFinderChoice.BIDIR.label());
    }
}
