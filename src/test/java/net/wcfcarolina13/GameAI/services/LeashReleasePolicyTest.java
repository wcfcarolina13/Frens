package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LeashReleasePolicyTest {

    @Test
    void holdingNothingSaysSo() {
        assertEquals("Bob isn't holding any leads.", LeashReleasePolicy.reply("Bob", 0, 0, false));
    }

    @Test
    void holdingNothingButTrackingAnAnimalSaysItWillStopGoingBack() {
        assertEquals("Bob wasn't holding a lead, but it will stop going back for the animal it was leading.",
                LeashReleasePolicy.reply("Bob", 0, 0, true));
        assertEquals("Bob let go of 1 lead. It is in its inventory. It will stop going back for the animal it was leading.",
                LeashReleasePolicy.reply("Bob", 1, 0, true));
    }

    @Test
    void leadsGoBackIntoTheInventory() {
        assertEquals("Bob let go of 1 lead. It is in its inventory.", LeashReleasePolicy.reply("Bob", 1, 0, false));
        assertEquals("Bob let go of 3 leads. They are in its inventory.", LeashReleasePolicy.reply("Bob", 3, 0, false));
    }

    @Test
    void aFullInventoryDropsTheLeadsAtItsFeet() {
        assertEquals("Bob let go of 2 leads. Its inventory is full, so they are on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 2, 2, false));
        assertEquals("Bob let go of 3 leads. 1 in its inventory and 2 on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 3, 2, false));
        assertEquals("Bob let go of 1 lead. Its inventory is full, so it is on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 1, 5, false));
    }

    @Test
    void repliesHaveNoEmDashOrSemicolon() {
        for (int released = 0; released <= 3; released++) {
            for (int dropped = 0; dropped <= released; dropped++) {
                for (boolean forgot : new boolean[] {false, true}) {
                    String reply = LeashReleasePolicy.reply("Bob", released, dropped, forgot);
                    assertFalse(reply.contains("—") || reply.contains(";"), reply);
                }
            }
        }
        assertEquals("The bot isn't holding any leads.", LeashReleasePolicy.reply(null, 0, 0, false));
    }
}
