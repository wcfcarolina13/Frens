package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookFilterPolicyTest {

    @Test
    void fishTakesRawCodAndSalmon() {
        // Field 2026-10-18: the fishing skill's "fish" filter found nothing in a bag of raw cod.
        assertTrue(CookFilterPolicy.matches("fish", "Raw Cod", "cod"));
        assertTrue(CookFilterPolicy.matches("fish", "Raw Salmon", "salmon"));
        assertTrue(CookFilterPolicy.matches("fishes", "Raw Cod", "cod"));
        assertTrue(CookFilterPolicy.matches("Fish", "Tropical Fish", "tropical_fish"));
        assertTrue(CookFilterPolicy.matches("fish", "Pufferfish", "pufferfish"));
    }

    @Test
    void fishLeavesOtherMeat() {
        assertFalse(CookFilterPolicy.matches("fish", "Raw Beef", "beef"));
        assertFalse(CookFilterPolicy.matches("fish", "Raw Chicken", "chicken"));
    }

    @Test
    void aNamedItemStillMatchesByNameOrId() {
        assertTrue(CookFilterPolicy.matches("cod", "Raw Cod", "cod"));
        assertTrue(CookFilterPolicy.matches("chicken", "Raw Chicken", "chicken"));
        assertTrue(CookFilterPolicy.matches("raw_chicken", "Raw Chicken", "chicken"));
        assertTrue(CookFilterPolicy.matches("rawbeef", "Raw Beef", "beef"));
        assertTrue(CookFilterPolicy.matches("iron ore", "Iron Ore", "iron_ore"));
        assertFalse(CookFilterPolicy.matches("cod", "Raw Salmon", "salmon"));
    }

    @Test
    void theIdPathCountsWhenTheDisplayNameIsRenamed() {
        assertTrue(CookFilterPolicy.matches("cod", "Lunch", "cod"));
        assertTrue(CookFilterPolicy.matches("fish", "Lunch", "salmon"));
    }

    @Test
    void aBlankFilterMatchesEverything() {
        assertTrue(CookFilterPolicy.matches("", "Raw Beef", "beef"));
        assertTrue(CookFilterPolicy.matches("   ", "Raw Beef", "beef"));
        assertTrue(CookFilterPolicy.matches(null, "Raw Beef", "beef"));
    }

    @Test
    void missingNamesNeverThrow() {
        assertFalse(CookFilterPolicy.matches("cod", null, null));
        assertFalse(CookFilterPolicy.matches("fish", "", null));
    }
}
