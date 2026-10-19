package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ToolChestCandidatePolicy.Candidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolChestCandidatePolicyTest {

    /** Lower rank is the better tool, as axeTierComparator orders copper, stone, wooden. */
    private static final Comparator<Integer> TIER = Comparator.naturalOrder();

    private static Candidate<Integer> c(long pos, Long partner, int tier, double distSq) {
        return new Candidate<>(pos, partner, tier, distSq);
    }

    private static List<Long> positions(List<Candidate<Integer>> merged) {
        List<Long> out = new ArrayList<>();
        for (Candidate<Integer> m : merged) {
            out.add(m.pos());
        }
        return out;
    }

    @Test
    void emptyAndNullInputsGiveNothing() {
        assertTrue(ToolChestCandidatePolicy.merge(List.<Candidate<Integer>>of(), List.of(), TIER).isEmpty());
        assertTrue(ToolChestCandidatePolicy.<Integer>merge(null, null, TIER).isEmpty());
    }

    @Test
    void aPlayerChestTheRegistryNeverSawIsAsked() {
        // Finding 8: the registry is empty, the base chest only shows up in the scan.
        List<Candidate<Integer>> merged =
                ToolChestCandidatePolicy.merge(List.of(), List.of(c(7L, null, 2, 25.0)), TIER);
        assertEquals(List.of(7L), positions(merged));
    }

    @Test
    void bestTierFirstThenNearest() {
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(
                List.of(c(1L, null, 3, 4.0), c(2L, null, 1, 100.0)),
                List.of(c(3L, null, 1, 9.0), c(4L, null, 3, 1.0)),
                TIER);
        assertEquals(List.of(3L, 2L, 4L, 1L), positions(merged));
    }

    @Test
    void theSamePositionCountsOnceKeepingTheBetterEntry() {
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(
                List.of(c(5L, null, 3, 16.0)),
                List.of(c(5L, null, 1, 16.0)),
                TIER);
        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).best(), "the fresher scan saw the better axe");
    }

    @Test
    void onATieTheRegistryEntryIsKept() {
        Candidate<Integer> reg = c(5L, null, 2, 16.0);
        Candidate<Integer> scan = c(5L, 6L, 2, 16.0);
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(List.of(reg), List.of(scan), TIER);
        assertEquals(List.of(reg), merged);
    }

    @Test
    void theOtherHalfOfADoubleChestIsDroppedEitherWay() {
        // Scanned half B (partner A) is better: A, from the registry without a partner, is dropped.
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(
                List.of(c(10L, null, 3, 9.0)),
                List.of(c(11L, 10L, 1, 10.0)),
                TIER);
        assertEquals(List.of(11L), positions(merged));
        // Registry half A is better: scanned half B, whose partner is A, is dropped.
        merged = ToolChestCandidatePolicy.merge(
                List.of(c(10L, null, 1, 9.0)),
                List.of(c(11L, 10L, 3, 10.0)),
                TIER);
        assertEquals(List.of(10L), positions(merged));
        // Both halves scanned: the nearer of equal tools is kept.
        merged = ToolChestCandidatePolicy.merge(
                List.of(),
                List.of(c(20L, 21L, 2, 30.0), c(21L, 20L, 2, 12.0)),
                TIER);
        assertEquals(List.of(21L), positions(merged));
    }

    @Test
    void singleChestsWithoutPartnersAllStay() {
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(
                List.of(c(1L, null, 1, 1.0)),
                Arrays.asList(c(2L, null, 1, 2.0), null, c(3L, null, 1, 3.0)),
                TIER);
        assertEquals(List.of(1L, 2L, 3L), positions(merged));
    }

    @Test
    void withoutATierOnlyDistanceOrders() {
        List<Candidate<Integer>> merged = ToolChestCandidatePolicy.merge(
                List.of(c(1L, null, 1, 50.0)), List.of(c(2L, null, 3, 5.0)), null);
        assertEquals(List.of(2L, 1L), positions(merged));
    }
}
