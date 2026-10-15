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
        assertEquals(PathFinderChoice.BIDIR, PathFinderOverrides.resolve(PathFinderChoice.BIDIR, PathFinderChoice.UNIFIED));
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolve(PathFinderChoice.BARITONE, PathFinderChoice.BIDIR));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolve(PathFinderChoice.UNIFIED, PathFinderChoice.BARITONE));
    }

    @Test
    void noOverrideFollowsGlobal() {
        for (PathFinderChoice active : PathFinderChoice.values()) {
            assertEquals(active, PathFinderOverrides.resolve(null, active));
        }
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolve(null, null));
    }

    @Test
    void legacyFlagMapsToUnifiedOrClassic() {
        // Field window: the persisted boolean stays; true (the migrated default) is UNIFIED, false BIDIR.
        assertEquals(PathFinderChoice.UNIFIED, PathFinderChoice.fromLegacyFlag(true));
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.fromLegacyFlag(false));
        // Through the dispatch precedence: a per-bot override still beats the mapped global.
        UUID bot = UUID.randomUUID();
        PathFinderOverrides.set(bot, PathFinderChoice.BARITONE);
        assertEquals(PathFinderChoice.BARITONE, PathFinderOverrides.resolveFor(bot, PathFinderChoice.fromLegacyFlag(true)));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolveFor(UUID.randomUUID(), PathFinderChoice.fromLegacyFlag(true)));
    }

    @Test
    void othersAreTheTwoShadowFinders() {
        assertEquals(java.util.List.of(PathFinderChoice.BIDIR, PathFinderChoice.BARITONE), PathFinderChoice.UNIFIED.others());
        assertEquals(java.util.List.of(PathFinderChoice.BARITONE, PathFinderChoice.UNIFIED), PathFinderChoice.BIDIR.others());
        assertEquals(java.util.List.of(PathFinderChoice.BIDIR, PathFinderChoice.UNIFIED), PathFinderChoice.BARITONE.others());
    }

    @Test
    void perBotSetAndClear() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PathFinderOverrides.set(a, PathFinderChoice.BIDIR);
        assertEquals(PathFinderChoice.BIDIR, PathFinderOverrides.resolveFor(a, PathFinderChoice.UNIFIED));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolveFor(b, PathFinderChoice.UNIFIED));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolveFor(null, PathFinderChoice.UNIFIED));
        PathFinderOverrides.set(a, null);
        assertNull(PathFinderOverrides.get(a));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderOverrides.resolveFor(a, PathFinderChoice.UNIFIED));
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
        assertEquals(PathFinderChoice.UNIFIED, PathFinderChoice.parse("unified"));
        assertEquals("unified", PathFinderChoice.UNIFIED.label());
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.parse("classic"));
        assertEquals(PathFinderChoice.BIDIR, PathFinderChoice.parse("bidir"));
        assertNull(PathFinderChoice.parse("default"));
        assertNull(PathFinderChoice.parse(null));
        assertEquals("bidir", PathFinderChoice.BIDIR.label());
    }
}
