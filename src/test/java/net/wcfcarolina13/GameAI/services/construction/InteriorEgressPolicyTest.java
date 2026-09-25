package net.wcfcarolina13.GameAI.services.construction;

import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Band;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Cell;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Doorway;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Footprint;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Layout;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Route;
import net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.Source;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.wcfcarolina13.GameAI.services.construction.InteriorEgressPolicy.packColumn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteriorEgressPolicyTest {

    // 5x5 box, walls on the ring 0..4.
    private static final Footprint BOX = new Footprint(0, 0, 4, 4);

    // ---- inside test: boundary counts as outside ----

    @Test
    void strictInsideExcludesBoundary() {
        assertTrue(BOX.strictlyInside(2, 2));
        assertTrue(BOX.strictlyInside(1, 3));
        assertFalse(BOX.strictlyInside(0, 2), "west wall line is outside");
        assertFalse(BOX.strictlyInside(2, 4), "south wall line is outside");
        assertFalse(BOX.strictlyInside(4, 4), "corner is outside");
        assertFalse(BOX.strictlyInside(-3, 2));
        assertTrue(BOX.onBoundary(0, 0));
        assertFalse(BOX.onBoundary(-1, 0));
    }

    @Test
    void emptyFootprintRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Footprint(3, 0, 2, 4));
    }

    @Test
    void needsEgressOnlyFromInteriorToNonInterior() {
        assertTrue(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 7, 2), "inside -> outside");
        assertTrue(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 4, 2), "inside -> boundary counts as outside");
        assertFalse(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 3, 3), "inside -> inside");
        assertFalse(InteriorEgressPolicy.needsEgress(BOX, 0, 2, 7, 2), "bot on the wall line is not inside");
        assertFalse(InteriorEgressPolicy.needsEgress(BOX, 7, 2, 2, 2), "outside -> inside is not egress");
        assertFalse(InteriorEgressPolicy.needsEgress(null, 2, 2, 7, 2), "no footprint, no egress");
    }

    @Test
    void needsEgressRequiresTargetAndStanceBothOutside() {
        // Interior target whose stance lands on a wall top: reached from inside.
        assertFalse(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 3, 2, 4, 2));
        // Wall target whose stance is on the interior side: no exit needed.
        assertFalse(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 4, 2, 3, 2));
        // Wall target with an exterior stance: exit.
        assertTrue(InteriorEgressPolicy.needsEgress(BOX, 2, 2, 4, 2, 5, 2));
    }

    @Test
    void footprintWithoutInteriorNeverNeedsEgress() {
        // defensive_gatehouse: 3 wide x 2 deep, no strictly-inside cell.
        Footprint gatehouse = new Footprint(0, 0, 2, 1);
        assertFalse(gatehouse.hasInterior());
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 1; z++) {
                assertFalse(InteriorEgressPolicy.needsEgress(gatehouse, x, z, 10, 10));
            }
        }
        assertTrue(InteriorEgressPolicy.deriveDoorways(gatehouse, Set.of(), Set.of()).isEmpty());
    }

    @Test
    void longCrossingExemptsAdjacentSteps() {
        assertFalse(InteriorEgressPolicy.isLongCrossing(BOX, 1, 2, 0, 2), "one step into the wall line");
        assertFalse(InteriorEgressPolicy.isLongCrossing(BOX, 1, 1, 0, 0), "diagonal single step");
        assertTrue(InteriorEgressPolicy.isLongCrossing(BOX, 2, 2, 5, 2), "straight line across the wall");
        assertFalse(InteriorEgressPolicy.isLongCrossing(BOX, 1, 1, 3, 3), "interior to interior");
    }

    // ---- doorway derivation from real built-in layouts ----

    @Test
    void smallShelterDoorIsTheNorthGap() {
        Band band = smallShelter().bandAt(1); // feet on the floor (rel Y 1)
        assertEquals(new Footprint(0, 0, 4, 4), band.footprint());
        List<Doorway> doorways = band.designedDoorways();
        assertEquals(List.of(new Doorway(2, 0, 0, -1, Source.DESIGNED_GAP)), doorways);
    }

    @Test
    void smallShelterBandAboveTheRoofHasNoFootprint() {
        assertNull(smallShelter().bandAt(5).footprint());
    }

    @Test
    void smallShelterWallTopBandHasNoDoorway() {
        // Standing on a one-block scaffold inside: the lintel at rel Y 3 closes the gap.
        Band band = smallShelter().bandAt(2);
        assertNotNull(band.footprint());
        assertTrue(band.designedDoorways().isEmpty());
    }

    @Test
    void smallHutEntrancePlatformDoesNotMoveTheWallLine() {
        Band band = smallHut().bandAt(0); // floor at rel -1, walls at 0..1
        assertEquals(new Footprint(0, 0, 4, 4), band.footprint(),
                "the z=-1 entrance platform sits one level down and must not widen the footprint");
        assertEquals(List.of(new Doorway(2, 0, 0, -1, Source.DESIGNED_GAP)), band.designedDoorways());
    }

    @Test
    void watchtowerHasTwoEastDoorCellsAndIgnoresArrowSlits() {
        Band band = watchtower().bandAt(1);
        assertEquals(new Footprint(0, 0, 3, 3), band.footprint());
        assertEquals(List.of(
                new Doorway(3, 1, 1, 0, Source.DESIGNED_GAP),
                new Doorway(3, 2, 1, 0, Source.DESIGNED_GAP)), band.designedDoorways());
    }

    @Test
    void plannedDoorBlockIsAWalkableDoorway() {
        assertEquals(List.of(new Doorway(2, 0, 0, -1, Source.DOOR_BLOCK)), boxWithDoorBlock().bandAt(1).designedDoorways());
    }

    @Test
    void solidCellInTheColumnWinsOverADoorMark() {
        // Wall planned at the same column as a door mark: not walkable.
        Layout layout = closedBox().door(2, 1, 0).door(2, 2, 0).build();
        assertTrue(layout.bandAt(1).designedDoorways().isEmpty());
    }

    @Test
    void closedBoxHasNoDoorway() {
        assertTrue(closedBox().build().bandAt(1).designedDoorways().isEmpty());
    }

    // ---- routes ----

    @Test
    void routeAlignsOnTheDoorAxisAndExitsTwoOut() {
        Band band = smallShelter().bandAt(1);
        Doorway door = band.designedDoorways().get(0);
        // Bot in the far south-east corner of the room.
        Route route = InteriorEgressPolicy.route(band.footprint(), door, band.blockedColumns(), 3, 3).orElseThrow();
        // Torch column (2,3) caps the clear corridor at depth 2.
        assertEquals(new Cell(2, 2), route.approach());
        assertEquals(new Cell(2, 0), route.opening());
        assertEquals(new Cell(2, -2), route.exit());
        assertFalse(route.approachSkippable());
        assertEquals(List.of(new Cell(2, 2), new Cell(2, 0), new Cell(2, -2)), route.waypoints());
    }

    @Test
    void botBesideTheDoorSidestepsOntoTheAxis() {
        Band band = smallShelter().bandAt(1);
        Doorway door = band.designedDoorways().get(0);
        Route route = InteriorEgressPolicy.route(band.footprint(), door, band.blockedColumns(), 3, 1).orElseThrow();
        assertEquals(new Cell(2, 1), route.approach(), "approach at the bot's own depth, not 3 deep");
        assertFalse(route.approachSkippable());
    }

    @Test
    void botAlreadyOnTheAxisSkipsTheApproach() {
        Band band = smallShelter().bandAt(1);
        Doorway door = band.designedDoorways().get(0);
        Route route = InteriorEgressPolicy.route(band.footprint(), door, band.blockedColumns(), 2, 2).orElseThrow();
        assertTrue(route.approachSkippable());
    }

    @Test
    void deepBotLinesUpThreeIn() {
        Footprint big = new Footprint(0, 0, 10, 10);
        Doorway door = new Doorway(5, 0, 0, -1, Source.DESIGNED_GAP);
        Route route = InteriorEgressPolicy.route(big, door, Set.of(), 8, 8).orElseThrow();
        assertEquals(new Cell(5, 3), route.approach());
        assertEquals(new Cell(5, -2), route.exit());
    }

    @Test
    void blockedCellBehindTheOpeningMakesTheDoorUnroutable() {
        Doorway door = new Doorway(2, 0, 0, -1, Source.DESIGNED_GAP);
        Optional<Route> route = InteriorEgressPolicy.route(BOX, door, Set.of(packColumn(2, 1)), 2, 2);
        assertTrue(route.isEmpty());
    }

    @Test
    void exitExtendsUntilClearOfAFootprintThatReachesPastTheDoor() {
        // A doorway that is not on the boundary (caller-supplied): exit must still leave the bounds.
        Footprint deep = new Footprint(0, -3, 4, 4);
        Doorway door = new Doorway(2, 0, 0, -1, Source.DOOR_BLOCK);
        Route route = InteriorEgressPolicy.route(deep, door, Set.of(), 2, 2).orElseThrow();
        assertEquals(new Cell(2, -4), route.exit());
    }

    @Test
    void doorOnTheFarSideStillRoutesThroughIt() {
        // One door on the north wall; target is south of the building.
        Band band = smallShelter().bandAt(1);
        Optional<Route> route = InteriorEgressPolicy.chooseRoute(
                band.footprint(), band.designedDoorways(), band.blockedColumns(), 2, 3, 2, 9);
        assertTrue(route.isPresent());
        assertEquals(new Cell(2, 0), route.get().opening());
    }

    @Test
    void nearSideDoorWinsWhenThereAreTwo() {
        Footprint big = new Footprint(0, 0, 10, 10);
        Doorway north = new Doorway(5, 0, 0, -1, Source.DESIGNED_GAP);
        Doorway south = new Doorway(5, 10, 0, 1, Source.DESIGNED_GAP);
        // Bot near the south wall, target south of the building: south door.
        Route toSouth = InteriorEgressPolicy.chooseRoute(big, List.of(north, south), Set.of(), 5, 8, 5, 14).orElseThrow();
        assertEquals(south, toSouth.doorway());
        // Bot near the south wall, target north of the building: the north door is the shorter trip.
        Route toNorth = InteriorEgressPolicy.chooseRoute(big, List.of(north, south), Set.of(), 5, 8, 5, -4).orElseThrow();
        assertEquals(north, toNorth.doorway());
    }

    @Test
    void blockedDoorFallsThroughToTheNextOne() {
        Footprint big = new Footprint(0, 0, 10, 10);
        Doorway north = new Doorway(5, 0, 0, -1, Source.DESIGNED_GAP);
        Doorway south = new Doorway(5, 10, 0, 1, Source.DESIGNED_GAP);
        Route route = InteriorEgressPolicy.chooseRoute(big, List.of(north, south),
                Set.of(packColumn(5, 9)), 5, 8, 5, 14).orElseThrow();
        assertEquals(north, route.doorway());
    }

    @Test
    void noDoorwayGivesNoRoute() {
        assertTrue(InteriorEgressPolicy.chooseRoute(BOX, List.of(), Set.of(), 2, 2, 9, 2).isEmpty());
        Band closed = closedBox().build().bandAt(1);
        assertTrue(InteriorEgressPolicy.chooseRoute(closed.footprint(), closed.designedDoorways(),
                closed.blockedColumns(), 2, 2, 9, 2).isEmpty());
    }

    @Test
    void unbuiltWallColumnIsAFallbackDoorway() {
        Band closed = closedBox().build().bandAt(1);
        // Everything planned is built except the east wall column (4,2).
        Set<Long> now = new HashSet<>(closed.blockedColumns());
        now.remove(packColumn(4, 2));
        List<Doorway> gaps = InteriorEgressPolicy.unbuiltGaps(closed.footprint(), closed.blockedColumns(), now);
        assertEquals(List.of(new Doorway(4, 2, 1, 0, Source.UNBUILT_GAP)), gaps);
        Route route = InteriorEgressPolicy.chooseRoute(closed.footprint(), gaps, now, 1, 2, 9, 2).orElseThrow();
        assertEquals(new Cell(6, 2), route.exit());
    }

    @Test
    void unbuiltGapsIgnoreCornersAndBuiltWalls() {
        Band closed = closedBox().build().bandAt(1);
        assertTrue(InteriorEgressPolicy.unbuiltGaps(closed.footprint(), closed.blockedColumns(),
                closed.blockedColumns()).isEmpty(), "fully built: nothing open");
        // Only corners open: corners have no single outward normal.
        Set<Long> now = new HashSet<>(closed.blockedColumns());
        now.remove(packColumn(0, 0));
        now.remove(packColumn(4, 4));
        assertTrue(InteriorEgressPolicy.unbuiltGaps(closed.footprint(), closed.blockedColumns(), now).isEmpty());
    }

    @Test
    void tripCostSumsBothLegs() {
        Doorway door = new Doorway(2, 0, 0, -1, Source.DESIGNED_GAP);
        assertEquals(3 + 9, InteriorEgressPolicy.tripCost(door, 2, 3, 2, -9));
    }

    @Test
    void doorwayRejectsNonUnitNormal() {
        assertThrows(IllegalArgumentException.class, () -> new Doorway(0, 0, 1, 1, Source.DESIGNED_GAP));
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long column = packColumn(-30_000_000, 29_999_999);
        assertEquals(-30_000_000, InteriorEgressPolicy.unpackX(column));
        assertEquals(29_999_999, InteriorEgressPolicy.unpackZ(column));
        long other = packColumn(12, -7);
        assertEquals(12, InteriorEgressPolicy.unpackX(other));
        assertEquals(-7, InteriorEgressPolicy.unpackZ(other));
    }

    // ---- int-encoded copies of SimpleSchematicBuilder layouts ----

    /** SimpleSchematicBuilder.smallShelter(): floor y0 (gap at 2,0), walls y1..3 (door gap 2,0 at y1..2), torch 2,2,3, roof y4. */
    private static Layout smallShelter() {
        Layout.Builder b = Layout.builder();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                if (x == 2 && z == 0) continue;
                b.solid(x, 0, z);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 4; x++) {
                if (!(x == 2 && y <= 2)) b.solid(x, y, 0);
                b.solid(x, y, 4);
            }
            for (int z = 1; z <= 3; z++) {
                b.solid(0, y, z);
                b.solid(4, y, z);
            }
        }
        b.solid(2, 2, 3);
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                b.solid(x, 4, z);
            }
        }
        return b.build();
    }

    /** SimpleSchematicBuilder.smallHut(): floor y-1 over z -1..4, walls y0..1 (door gap 2,0), torch 2,0,3, roof y2. */
    private static Layout smallHut() {
        Layout.Builder b = Layout.builder();
        for (int x = 0; x <= 4; x++) {
            for (int z = -1; z <= 4; z++) {
                b.solid(x, -1, z);
            }
        }
        for (int y = 0; y <= 1; y++) {
            for (int x = 0; x <= 4; x++) {
                if (x != 2) b.solid(x, y, 0);
                b.solid(x, y, 4);
            }
            for (int z = 1; z <= 3; z++) {
                b.solid(0, y, z);
                b.solid(4, y, z);
            }
        }
        b.solid(2, 0, 3);
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                b.solid(x, 2, z);
            }
        }
        return b.build();
    }

    /** Lower part of SimpleSchematicBuilder.watchtower(): floor y0, corner logs, walls y1..4 with slits at y2..3, east door at y1..2, ladder at 1,y,2. */
    private static Layout watchtower() {
        Layout.Builder b = Layout.builder();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                b.solid(x, 0, z);
            }
        }
        for (int y = 1; y <= 7; y++) {
            b.solid(0, y, 0);
            b.solid(3, y, 0);
            b.solid(0, y, 3);
            b.solid(3, y, 3);
            b.solid(1, y, 2); // ladder
        }
        for (int y = 1; y <= 4; y++) {
            boolean slit = y == 2 || y == 3;
            if (!slit) {
                b.solid(1, y, 0);
                b.solid(2, y, 0);
                b.solid(1, y, 3);
                b.solid(2, y, 3);
                b.solid(0, y, 1);
                b.solid(0, y, 2);
            }
            if (y > 2) {
                b.solid(3, y, 1);
                b.solid(3, y, 2);
            }
        }
        return b.build();
    }

    /** 5x5 floor at y0 with a full three-high wall ring y1..3 and no opening. */
    private static Layout.Builder closedBox() {
        Layout.Builder b = Layout.builder();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                b.solid(x, 0, z);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 4; x++) {
                b.solid(x, y, 0);
                b.solid(x, y, 4);
            }
            for (int z = 1; z <= 3; z++) {
                b.solid(0, y, z);
                b.solid(4, y, z);
            }
        }
        return b;
    }

    /** Same box as a .nbt schematic would plan it: a DoorBlock at (2, 1..2, 0) instead of wall. */
    private static Layout boxWithDoorBlock() {
        Layout.Builder b = Layout.builder();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                b.solid(x, 0, z);
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x <= 4; x++) {
                if (x == 2 && y <= 2) {
                    b.door(x, y, 0);
                } else {
                    b.solid(x, y, 0);
                }
                b.solid(x, y, 4);
            }
            for (int z = 1; z <= 3; z++) {
                b.solid(0, y, z);
                b.solid(4, y, z);
            }
        }
        return b.build();
    }
}
