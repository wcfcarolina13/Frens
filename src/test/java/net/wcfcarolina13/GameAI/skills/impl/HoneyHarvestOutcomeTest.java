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
        assertTrue(HoneyHarvestOutcome.withinReach(3, 2, 3));
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
        assertFalse(HoneyHarvestOutcome.withinReach(3, -3, 2));
    }
}
