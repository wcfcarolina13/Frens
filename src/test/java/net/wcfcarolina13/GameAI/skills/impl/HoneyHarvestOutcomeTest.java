package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoneyHarvestOutcomeTest {

    private static Map<HoneyHarvestOutcome, Integer> map(Object... pairs) {
        Map<HoneyHarvestOutcome, Integer> m = new EnumMap<>(HoneyHarvestOutcome.class);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((HoneyHarvestOutcome) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void noOutcomesMeansNoHarvestableHives() {
        assertEquals("No harvestable beehives nearby.", HoneyHarvestOutcome.failureMessage(map()));
        assertEquals("No harvestable beehives nearby.", HoneyHarvestOutcome.failureMessage(null));
    }

    @Test
    void allUnreachableSaysCouldNotReach() {
        assertEquals("Couldn't reach any beehives.",
                HoneyHarvestOutcome.failureMessage(map(HoneyHarvestOutcome.UNREACHABLE, 3)));
    }

    @Test
    void anyRejectedInteractionSaysCouldNotHarvest() {
        assertEquals("Couldn't harvest any beehives.",
                HoneyHarvestOutcome.failureMessage(map(HoneyHarvestOutcome.INTERACT_REJECTED, 1)));
        assertEquals("Couldn't harvest any beehives.",
                HoneyHarvestOutcome.failureMessage(map(
                        HoneyHarvestOutcome.UNREACHABLE, 4,
                        HoneyHarvestOutcome.INTERACT_REJECTED, 1)));
    }

    @Test
    void allNoLongerHarvestableSaysNoHarvestableHives() {
        assertEquals("No harvestable beehives nearby.",
                HoneyHarvestOutcome.failureMessage(map(HoneyHarvestOutcome.NOT_HARVESTABLE, 2)));
    }

    @Test
    void mixedOutcomesSayCouldNotHarvest() {
        assertEquals("Couldn't harvest any beehives.",
                HoneyHarvestOutcome.failureMessage(map(
                        HoneyHarvestOutcome.UNREACHABLE, 1,
                        HoneyHarvestOutcome.NOT_HARVESTABLE, 1)));
        assertEquals("Couldn't harvest any beehives.",
                HoneyHarvestOutcome.failureMessage(map(HoneyHarvestOutcome.INVENTORY_FULL, 1)));
        assertEquals("Couldn't harvest any beehives.",
                HoneyHarvestOutcome.failureMessage(map(HoneyHarvestOutcome.EQUIP_FAILED, 2)));
    }

    @Test
    void zeroCountsAreIgnored() {
        assertEquals("Couldn't reach any beehives.",
                HoneyHarvestOutcome.failureMessage(map(
                        HoneyHarvestOutcome.UNREACHABLE, 2,
                        HoneyHarvestOutcome.INTERACT_REJECTED, 0)));
    }

    @Test
    void playerLinesHaveNoEmDashesOrSemicolons() {
        for (String s : new String[]{HoneyHarvestOutcome.NO_HIVES,
                HoneyHarvestOutcome.COULD_NOT_REACH, HoneyHarvestOutcome.COULD_NOT_HARVEST}) {
            assertFalse(s.contains("—"), s);
            assertFalse(s.contains(";"), s);
        }
    }

    @Test
    void reachFromThePadTwoBelowARaisedHive() {
        // Sweep Woods repro: hive two above the pad floor, bot three blocks out.
        assertTrue(HoneyHarvestOutcome.withinReach(0, 2, 3));
        assertTrue(HoneyHarvestOutcome.withinReach(0, 1, -3));
    }

    @Test
    void diagonalTwoBelowIsOutOfFeetReach() {
        // Measured from the eye this passed (18.77) and canInteract then always refused it (24.25).
        assertFalse(HoneyHarvestOutcome.withinReach(3, 2, 3));
        assertTrue(HoneyHarvestOutcome.withinReach(3, 1, 3)); // 9 + 2.25 + 9 = 20.25, the edge
        assertTrue(HoneyHarvestOutcome.withinReach(0, 4, 0)); // 4.5^2 = 20.25, the edge
        assertFalse(HoneyHarvestOutcome.withinReach(0, 5, 0));
    }

    /**
     * Pins withinReach to canInteract's reach test: squared distance from the player's position (feet,
     * standing at the centre of the feet cell's floor) to the hive's block centre, at most 20.25.
     */
    @Test
    void reachAgreesWithCanInteractDistanceForEveryStandOffset() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    double feetX = 0.5D, feetY = 0.0D, feetZ = 0.5D;
                    double hiveX = dx + 0.5D, hiveY = dy + 0.5D, hiveZ = dz + 0.5D;
                    double ex = hiveX - feetX, ey = hiveY - feetY, ez = hiveZ - feetZ;
                    boolean canInteractReach = ex * ex + ey * ey + ez * ez <= 20.25D;
                    assertEquals(canInteractReach, HoneyHarvestOutcome.withinReach(dx, dy, dz),
                            "offset " + dx + "," + dy + "," + dz);
                }
            }
        }
    }

    @Test
    void depositOnlyWhenStillFullAndCarryingHoney() {
        assertTrue(HoneyHarvestOutcome.shouldDeposit(0, 2));
        assertFalse(HoneyHarvestOutcome.shouldDeposit(1, 2));
        assertFalse(HoneyHarvestOutcome.shouldDeposit(5, 3));
        assertFalse(HoneyHarvestOutcome.shouldDeposit(0, 0));
    }

    @Test
    void reachRejectsCellsPastSurvivalReach() {
        assertFalse(HoneyHarvestOutcome.withinReach(4, 2, 2));
        assertFalse(HoneyHarvestOutcome.withinReach(0, 6, 0));
        assertFalse(HoneyHarvestOutcome.withinReach(5, 0, 0));
    }

    @Test
    void reachFromAboveTheHive() {
        assertTrue(HoneyHarvestOutcome.withinReach(1, -1, 0));
        assertTrue(HoneyHarvestOutcome.withinReach(0, -3, 0));
        assertTrue(HoneyHarvestOutcome.withinReach(3, -1, 3)); // 9 + 0.25 + 9
        assertTrue(HoneyHarvestOutcome.withinReach(3, -3, 2)); // 9 + 6.25 + 4 = 19.25 from the feet
        assertFalse(HoneyHarvestOutcome.withinReach(3, -3, 3)); // 24.25
    }
}
