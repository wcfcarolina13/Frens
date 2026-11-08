package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Cell;
import net.wcfcarolina13.GameAI.services.HandDigStairPolicy.Dir;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Bradley's ruling 2026-11-08: one 1-high step onto open ground, never a dig into a hillside. */
class HandDigStepOntoGroundTest {

    /** Cells keyed "dx,dy,dz"; anything unset is air. */
    private static HandDigStairPolicy.CellView view(Map<String, Cell> cells) {
        return (dx, dy, dz) -> cells.getOrDefault(dx + "," + dy + "," + dz, Cell.air());
    }

    /** The notch the stair ended in: ground blocks at feet level on every side. */
    private static Map<String, Cell> flatNotch() {
        Map<String, Cell> cells = new HashMap<>();
        for (Dir d : Dir.values()) {
            cells.put(d.dx + ",0," + d.dz, Cell.solid(20));
        }
        return cells;
    }

    @Test
    void aNotchOnFlatGroundStepsUpTheStairWay() {
        assertEquals(Dir.WEST, HandDigStairPolicy.stepOntoGround(view(flatNotch()), List.of(Dir.WEST)));
    }

    @Test
    void aHillsideWallIsNeverDugInto() {
        Map<String, Cell> cells = flatNotch();
        for (Dir d : Dir.values()) {
            cells.put(d.dx + ",1," + d.dz, Cell.solid(20)); // the slope rises a second block on every side
        }
        assertNull(HandDigStairPolicy.stepOntoGround(view(cells), List.of(Dir.WEST)));
    }

    @Test
    void anOverhangIsNotOpenGround() {
        Map<String, Cell> cells = flatNotch();
        for (Dir d : Dir.values()) {
            cells.put(d.dx + ",3," + d.dz, Cell.solid(20));
        }
        assertNull(HandDigStairPolicy.stepOntoGround(view(cells), List.of(Dir.NORTH)));
    }

    @Test
    void anotherSideIsTakenWhenTheStairWayRisesFurther() {
        Map<String, Cell> cells = flatNotch();
        cells.put("-1,1,0", Cell.solid(20)); // west climbs into the hill
        assertEquals(Dir.NORTH, HandDigStairPolicy.stepOntoGround(view(cells), List.of(Dir.WEST)));
    }

    @Test
    void noTreadNoStep() {
        Map<String, Cell> cells = new HashMap<>();
        cells.put("0,0,-1", Cell.partial(5)); // a slab or fence top is not a tread
        assertNull(HandDigStairPolicy.stepOntoGround(view(cells), List.of(Dir.NORTH)));
    }
}
