package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DropSweepMessagesTest {

    @Test
    void nothingAround() {
        assertEquals("Nothing to pick up nearby.", DropSweepMessages.summary(0, 0));
    }

    @Test
    void pickedUpAll() {
        assertEquals("Picked up 4 items.", DropSweepMessages.summary(4, 0));
    }

    @Test
    void pickedUpOneIsSingular() {
        assertEquals("Picked up 1 item.", DropSweepMessages.summary(1, 0));
    }

    @Test
    void pickedUpCountUsesThePileShrinkWhenTheSweepUndercounted() {
        // Field: 3 drops in reach were taken by plain pickup, the sweep counted none of them.
        assertEquals(3, DropSweepMessages.pickedUpCount(3, 0, 0, 3));
        assertEquals(5, DropSweepMessages.pickedUpCount(7, 2, 1, 5));
    }

    @Test
    void pickedUpCountKeepsTheSweepCountWhenTheRangeGrew() {
        // The range widens each pass, so the end count can exceed the start count.
        assertEquals(4, DropSweepMessages.pickedUpCount(2, 6, 4, 4));
        assertEquals(0, DropSweepMessages.pickedUpCount(0, 0, 0, 0));
        assertEquals(0, DropSweepMessages.pickedUpCount(-1, -1, -1, -1));
    }

    @Test
    void pickedUpCountIsCappedByWhatTheBagGained() {
        // Field (finding 11): chat said 27, the sweep counted 20, the bag gained 21.
        assertEquals(21, DropSweepMessages.pickedUpCount(27, 0, 20, 21));
        // Drops that only left the measured range do not count as picked up.
        assertEquals(6, DropSweepMessages.pickedUpCount(10, 0, 2, 6));
    }

    @Test
    void pickedUpCountKeepsTheSweepCountWhenTheBagShrankFromOffload() {
        // The sweep stored into a chest mid-run, so the bag gain undercounts.
        assertEquals(8, DropSweepMessages.pickedUpCount(12, 0, 8, -30));
        assertEquals(8, DropSweepMessages.pickedUpCount(12, 0, 8, 3));
    }

    @Test
    void pickedUpCountNeverExceedsTheShrinkWhenTheBagGainedMore() {
        // Bag also grew from something outside the sweep range (e.g. an earlier stack merged).
        assertEquals(5, DropSweepMessages.pickedUpCount(5, 0, 1, 40));
    }

    @Test
    void pickedUpSomeWithLeftovers() {
        assertEquals("Picked up 3 items, 2 out of reach.", DropSweepMessages.summary(3, 2));
    }

    @Test
    void couldNotReachAny() {
        assertEquals("Couldn't reach the 2 items nearby.", DropSweepMessages.summary(0, 2));
        assertEquals("Couldn't reach the 1 item nearby.", DropSweepMessages.summary(0, 1));
    }

    @Test
    void negativeCountsClampToZero() {
        assertEquals("Nothing to pick up nearby.", DropSweepMessages.summary(-3, -1));
    }

    @Test
    void inventoryFullMessages() {
        assertEquals("My inventory is full, so I stopped picking things up.", DropSweepMessages.inventoryFull(0));
        assertEquals("My inventory is full, so I stopped after picking up 5 items.", DropSweepMessages.inventoryFull(5));
    }

    @Test
    void noEmDashesOrSemicolonsInPlayerText() {
        String[] samples = {
                DropSweepMessages.summary(0, 0), DropSweepMessages.summary(3, 0),
                DropSweepMessages.summary(3, 2), DropSweepMessages.summary(0, 2),
                DropSweepMessages.inventoryFull(0), DropSweepMessages.inventoryFull(7)
        };
        for (String s : samples) {
            assertFalse(s.contains("—"), s);
            assertFalse(s.contains("–"), s);
            assertFalse(s.contains(";"), s);
        }
    }
}
