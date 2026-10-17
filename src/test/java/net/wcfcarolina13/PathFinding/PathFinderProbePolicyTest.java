package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathFinderProbePolicyTest {

    @Test
    void budgetedFinderKeepsUnifiedAndBaritone() {
        assertEquals(PathFinderChoice.UNIFIED, PathFinderProbePolicy.budgetedFinder(PathFinderChoice.UNIFIED));
        assertEquals(PathFinderChoice.BARITONE, PathFinderProbePolicy.budgetedFinder(PathFinderChoice.BARITONE));
    }

    @Test
    void classicHasNoBudgetedEntryAndProbesWithBaritone() {
        assertEquals(PathFinderChoice.BARITONE, PathFinderProbePolicy.budgetedFinder(PathFinderChoice.BIDIR));
    }

    @Test
    void nullIsTheDefault() {
        assertEquals(PathFinderProbePolicy.budgetedFinder(PathFinderChoice.DEFAULT),
                PathFinderProbePolicy.budgetedFinder(null));
        assertEquals(PathFinderChoice.UNIFIED, PathFinderProbePolicy.budgetedFinder(null));
    }

    @Test
    void everyChoiceMapsToAFinderWithABudgetedEntry() {
        for (PathFinderChoice c : PathFinderChoice.values()) {
            PathFinderChoice probed = PathFinderProbePolicy.budgetedFinder(c);
            assertEquals(true, probed == PathFinderChoice.UNIFIED || probed == PathFinderChoice.BARITONE,
                    c + " probes with " + probed);
        }
    }

    private static PathFinderChoice probeFor(PathFinderChoice override, String savedConfig) {
        return PathFinderProbePolicy.budgetedFinder(
                PathFinderOverrides.resolve(override, PathFinderDefaultPolicy.fromSaved(savedConfig)));
    }

    @Test
    void savedConfigWithoutOverride() {
        assertEquals(PathFinderChoice.UNIFIED, probeFor(null, null));
        assertEquals(PathFinderChoice.UNIFIED, probeFor(null, "unified"));
        assertEquals(PathFinderChoice.BARITONE, probeFor(null, "baritone"));
        assertEquals(PathFinderChoice.BARITONE, probeFor(null, "classic"));
    }

    @Test
    void perBotOverrideBeatsSavedConfig() {
        assertEquals(PathFinderChoice.BARITONE, probeFor(PathFinderChoice.BARITONE, "unified"));
        assertEquals(PathFinderChoice.BARITONE, probeFor(PathFinderChoice.BARITONE, null));
        assertEquals(PathFinderChoice.UNIFIED, probeFor(PathFinderChoice.UNIFIED, "baritone"));
        assertEquals(PathFinderChoice.UNIFIED, probeFor(PathFinderChoice.UNIFIED, "classic"));
        assertEquals(PathFinderChoice.BARITONE, probeFor(PathFinderChoice.BIDIR, "unified"));
    }
}
