package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LeashReleasePolicyTest {

    @Test
    void holdingNothingSaysSo() {
        assertEquals("Bob isn't holding any animals on a lead.", LeashReleasePolicy.reply("Bob", 0, 0));
    }

    @Test
    void leadsGoBackIntoTheInventory() {
        assertEquals("Bob let go of 1 animal. The lead is in its inventory.", LeashReleasePolicy.reply("Bob", 1, 0));
        assertEquals("Bob let go of 3 animals. The leads are in its inventory.", LeashReleasePolicy.reply("Bob", 3, 0));
    }

    @Test
    void aFullInventoryDropsTheLeadsAtItsFeet() {
        assertEquals("Bob let go of 2 animals. Its inventory is full, so the leads are on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 2, 2));
        assertEquals("Bob let go of 3 animals. 1 lead is in its inventory and 2 on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 3, 2));
        assertEquals("Bob let go of 1 animal. Its inventory is full, so the lead is on the ground at its feet.",
                LeashReleasePolicy.reply("Bob", 1, 5));
    }

    @Test
    void repliesHaveNoEmDashOrSemicolon() {
        for (int released = 0; released <= 3; released++) {
            for (int dropped = 0; dropped <= released; dropped++) {
                String reply = LeashReleasePolicy.reply("Bob", released, dropped);
                assertFalse(reply.contains("—") || reply.contains(";"), reply);
            }
        }
        assertEquals("The bot isn't holding any animals on a lead.", LeashReleasePolicy.reply(null, 0, 0));
    }
}
