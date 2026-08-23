package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneStylePathFinderTest {

    @Test
    void diagonalMovementRequiresAtLeastOneCardinalLaneOpen() {
        assertTrue(BaritoneStylePathFinder.diagonalMovementAllowed(false, true));
        assertTrue(BaritoneStylePathFinder.diagonalMovementAllowed(true, false));
        assertFalse(BaritoneStylePathFinder.diagonalMovementAllowed(true, true));
    }
}
