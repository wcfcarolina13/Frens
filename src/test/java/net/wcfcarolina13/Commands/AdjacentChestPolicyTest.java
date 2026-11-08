package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdjacentChestPolicyTest {

    private static final AdjacentChestPolicy.Cell ANCHOR = new AdjacentChestPolicy.Cell(10, 64, -3);

    @Test
    void theNewChestGoesIntoTheNeighbourNotTheAnchor() {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            AdjacentChestPolicy.Target t = AdjacentChestPolicy.target(ANCHOR, s[0], s[1]);
            assertNotEquals(ANCHOR, t.place(), "place cell must not be the chest already there");
            assertEquals(new AdjacentChestPolicy.Cell(10 + s[0], 64, -3 + s[1]), t.place());
            assertEquals(ANCHOR, t.hit(), "the anchor chest is the clicked support");
            assertEquals(s[0], t.faceDx());
            assertEquals(s[1], t.faceDz());
        }
    }

    @Test
    void onlyHorizontalUnitSteps() {
        assertThrows(IllegalArgumentException.class, () -> AdjacentChestPolicy.target(ANCHOR, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> AdjacentChestPolicy.target(ANCHOR, 0, 0));
    }
}
