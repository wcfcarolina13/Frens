package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.FuelOffloadPolicy.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuelOffloadPolicyTest {

    private static Candidate c(String id, int slot, int count, int tier) {
        return new Candidate(id, slot, count, tier);
    }

    @Test
    void tierForPicksCheapestMatchingClass() {
        assertEquals(0, FuelOffloadPolicy.tierFor(true, true, false, false));
        assertEquals(1, FuelOffloadPolicy.tierFor(false, true, false, false));
        assertEquals(2, FuelOffloadPolicy.tierFor(false, false, true, false));
        assertEquals(3, FuelOffloadPolicy.tierFor(false, false, false, true));
        assertEquals(-1, FuelOffloadPolicy.tierFor(false, false, false, false));
    }

    @Test
    void giveawaysAreOrderedCheapestTierFirst() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(
                c("planks", 5, 64, 3),
                c("stick", 3, 10, 2),
                c("leaf_litter", 9, 4, 0),
                c("oak_leaves", 1, 12, 1)
        ), 0);
        assertEquals(List.of("leaf_litter", "oak_leaves", "stick", "planks"),
                out.stream().map(Candidate::itemId).toList());
    }

    @Test
    void plankReserveIsConsumedAcrossTwoStacksInSlotOrder() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(
                c("planks_a", 2, 40, 3),
                c("planks_b", 7, 30, 3)
        ), 50);
        // 40 withheld from slot 2 (nothing left to give), 10 more withheld from slot 7 -> 20 given.
        assertEquals(1, out.size());
        assertEquals("planks_b", out.get(0).itemId());
        assertEquals(20, out.get(0).count());
        assertEquals(7, out.get(0).slot());
    }

    @Test
    void allPlanksReservedYieldsEmptyList() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(
                c("planks_a", 2, 16, 3),
                c("planks_b", 4, 16, 3)
        ), 64);
        assertTrue(out.isEmpty());
    }

    @Test
    void neverTierAndZeroCountsAreExcluded() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(
                c("diamond", 0, 5, -1),
                c("empty_leaves", 1, 0, 1),
                c("stick", 2, 3, 2)
        ), 0);
        assertEquals(1, out.size());
        assertEquals("stick", out.get(0).itemId());
        assertEquals(3, out.get(0).count());
    }

    @Test
    void slotOrderIsStableWithinATier() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(
                c("leaves_c", 8, 1, 1),
                c("leaves_a", 2, 1, 1),
                c("leaves_b", 5, 1, 1)
        ), 0);
        assertEquals(List.of(2, 5, 8), out.stream().map(Candidate::slot).toList());
    }

    @Test
    void nullOrEmptyInventoryYieldsEmptyList() {
        assertTrue(FuelOffloadPolicy.giveaways(null, 32).isEmpty());
        assertTrue(FuelOffloadPolicy.giveaways(List.of(), 32).isEmpty());
    }

    @Test
    void negativeReserveIsTreatedAsZero() {
        List<Candidate> out = FuelOffloadPolicy.giveaways(List.of(c("planks", 1, 12, 3)), -5);
        assertEquals(1, out.size());
        assertEquals(12, out.get(0).count());
    }
}
