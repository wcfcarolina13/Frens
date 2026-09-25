package net.wcfcarolina13.GameAI.skills.impl.shelter;

import net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.Cell;
import net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.EgressStep;
import net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.FootprintZone;
import net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.Waypoints;
import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.FootprintZone.INSIDE;
import static net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.FootprintZone.ON_WALL;
import static net.wcfcarolina13.GameAI.skills.impl.shelter.HovelEgressGeometry.FootprintZone.OUTSIDE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hovel doorway egress geometry (1.1.218 field report: a bot building a hovel got stuck inside trying to
 * reach the far side of a corner instead of using the doorway).
 *
 * <p>Two gaps are pinned here: the old inside/outside tests were both strict, so a wall-line cell was
 * "neither" and the door exit was skipped; and the door waypoints were loose nearest-standable picks, not
 * cells lined up through the door.</p>
 */
class HovelEgressGeometryTest {

    private static final int CX = 100;
    private static final int CZ = -40;
    private static final int STAND_Y = 65;

    // Door normals (Direction.getOffsetX/Z): NORTH = -Z, SOUTH = +Z, EAST = +X, WEST = -X.
    private static final int[][] NORMALS = { {0, -1}, {0, 1}, {1, 0}, {-1, 0} };

    // ---- classify ------------------------------------------------------------------------------------

    @Test
    void wallLineIsItsOwnZone() {
        int r = 3;
        assertEquals(INSIDE, HovelEgressGeometry.classify(CX, CZ, CX, CZ, r));
        assertEquals(INSIDE, HovelEgressGeometry.classify(CX + 2, CZ - 2, CX, CZ, r));
        assertEquals(ON_WALL, HovelEgressGeometry.classify(CX + 3, CZ, CX, CZ, r));
        assertEquals(ON_WALL, HovelEgressGeometry.classify(CX - 3, CZ + 2, CX, CZ, r));
        assertEquals(ON_WALL, HovelEgressGeometry.classify(CX + 1, CZ - 3, CX, CZ, r));
        assertEquals(OUTSIDE, HovelEgressGeometry.classify(CX + 4, CZ, CX, CZ, r));
        assertEquals(OUTSIDE, HovelEgressGeometry.classify(CX + 3, CZ + 4, CX, CZ, r),
                "one coordinate past the wall is outside even if the other is on the wall line");
    }

    @Test
    void cornersAreOnTheWall() {
        for (int r = 2; r <= 5; r++) {
            for (int sx : new int[] {-1, 1}) {
                for (int sz : new int[] {-1, 1}) {
                    assertEquals(ON_WALL, HovelEgressGeometry.classify(CX + sx * r, CZ + sz * r, CX, CZ, r),
                            "corner r=" + r + " (" + sx + "," + sz + ")");
                    assertEquals(OUTSIDE, HovelEgressGeometry.classify(CX + sx * (r + 1), CZ + sz * (r + 1), CX, CZ, r),
                            "diagonal outside-corner stance r=" + r);
                }
            }
        }
    }

    @Test
    void zonesPartitionTheGridAndMatchTheStrictTests() {
        for (int r = 2; r <= 5; r++) {
            for (int dx = -r - 3; dx <= r + 3; dx++) {
                for (int dz = -r - 3; dz <= r + 3; dz++) {
                    FootprintZone zone = HovelEgressGeometry.classify(CX + dx, CZ + dz, CX, CZ, r);
                    // Same formulas as HovelGeometryService.isInsideFootprint / isOutsideFootprint.
                    boolean strictInside = Math.abs(dx) < r && Math.abs(dz) < r;
                    boolean strictOutside = Math.abs(dx) > r || Math.abs(dz) > r;
                    assertEquals(strictInside, zone == INSIDE, "inside r=" + r + " d=" + dx + "," + dz);
                    assertEquals(strictOutside, zone == OUTSIDE, "outside r=" + r + " d=" + dx + "," + dz);
                    assertEquals(!strictInside && !strictOutside, zone == ON_WALL,
                            "the old 'neither' cells are exactly ON_WALL, r=" + r + " d=" + dx + "," + dz);
                }
            }
        }
    }

    @Test
    void noFootprintMeansOutside() {
        assertEquals(OUTSIDE, HovelEgressGeometry.classify(CX, CZ, CX, CZ, 0));
        assertEquals(OUTSIDE, HovelEgressGeometry.classify(CX, CZ, CX, CZ, -2));
    }

    // ---- needsDoorEgress -----------------------------------------------------------------------------

    private static boolean needs(FootprintZone bot, FootprintZone target) {
        // Far apart, bot at floor stand level.
        return HovelEgressGeometry.needsDoorEgress(bot, target, 0, 0, STAND_Y, 10, 10, STAND_Y);
    }

    @Test
    void egressMatrix() {
        assertTrue(needs(INSIDE, OUTSIDE), "the classic case");
        assertTrue(needs(INSIDE, ON_WALL), "a wall-line target counts as outside");
        assertTrue(needs(ON_WALL, OUTSIDE), "a bot on the wall line at floor level still uses the door");
        assertTrue(needs(ON_WALL, ON_WALL), "wall-line to far wall-line goes round via the door");
        assertFalse(needs(INSIDE, INSIDE));
        assertFalse(needs(ON_WALL, INSIDE), "entering is ensureInteriorAccess's job");
        assertFalse(needs(OUTSIDE, OUTSIDE));
        assertFalse(needs(OUTSIDE, ON_WALL));
        assertFalse(needs(OUTSIDE, INSIDE));
        assertFalse(HovelEgressGeometry.needsDoorEgress(null, OUTSIDE, 0, 0, STAND_Y, 10, 10, STAND_Y));
        assertFalse(HovelEgressGeometry.needsDoorEgress(INSIDE, null, 0, 0, STAND_Y, 10, 10, STAND_Y));
    }

    @Test
    void botOnWallLineHeadingForTheOppositeOutsideCornerNeedsTheDoor() {
        int r = 3;
        int botX = CX + r;      // east wall line, e.g. standing in an unfinished gap
        int botZ = CZ;
        int targetX = CX - (r + 1);  // north-west diagonal outside-corner stance
        int targetZ = CZ - (r + 1);
        assertTrue(HovelEgressGeometry.needsDoorEgress(
                HovelEgressGeometry.classify(botX, botZ, CX, CZ, r),
                HovelEgressGeometry.classify(targetX, targetZ, CX, CZ, r),
                botX, botZ, STAND_Y, targetX, targetZ, STAND_Y));
    }

    @Test
    void interiorBotHeadingForAWallLineTargetNeedsTheDoor() {
        int r = 4;
        int targetX = CX - r;   // west wall line
        int targetZ = CZ + 1;
        assertTrue(HovelEgressGeometry.needsDoorEgress(
                INSIDE, HovelEgressGeometry.classify(targetX, targetZ, CX, CZ, r),
                CX + 1, CZ, STAND_Y, targetX, targetZ, STAND_Y));
    }

    @Test
    void singleStepNeverNeedsTheDoor() {
        // Already on the destination cell, or one step from it: a one-block wall cannot be crossed
        // strictly inside -> strictly outside in one step, and the door detour would be absurd.
        assertFalse(HovelEgressGeometry.needsDoorEgress(ON_WALL, ON_WALL, 3, 0, STAND_Y, 3, 0, STAND_Y));
        assertFalse(HovelEgressGeometry.needsDoorEgress(ON_WALL, OUTSIDE, 3, 0, STAND_Y, 4, 1, STAND_Y));
        assertFalse(HovelEgressGeometry.needsDoorEgress(INSIDE, ON_WALL, 2, 0, STAND_Y, 3, 1, STAND_Y));
        // Two cells apart straight across the wall is the real trap and still needs the door.
        assertTrue(HovelEgressGeometry.needsDoorEgress(INSIDE, OUTSIDE, 2, 0, STAND_Y, 4, 0, STAND_Y));
    }

    @Test
    void onWallAboveTheFloorCourseIsOnTopOfTheWallNotBehindIt() {
        assertTrue(HovelEgressGeometry.needsDoorEgress(ON_WALL, OUTSIDE, 3, 0, STAND_Y + 1, 9, 9, STAND_Y));
        assertTrue(HovelEgressGeometry.needsDoorEgress(ON_WALL, OUTSIDE, 3, 0, STAND_Y - 1, 9, 9, STAND_Y));
        assertFalse(HovelEgressGeometry.needsDoorEgress(ON_WALL, OUTSIDE, 3, 0, STAND_Y + 4, 9, 9, STAND_Y),
                "roof walk / pillar top on the wall line");
        assertTrue(HovelEgressGeometry.needsDoorEgress(INSIDE, OUTSIDE, 0, 0, STAND_Y + 4, 9, 9, STAND_Y),
                "the INSIDE rule is unchanged (no height gate)");
    }

    // ---- door waypoints ------------------------------------------------------------------------------

    @Test
    void waypointsAreAlignedThroughTheDoorOnEverySide() {
        for (int r = 2; r <= 5; r++) {
            for (int[] n : NORMALS) {
                Waypoints wp = HovelEgressGeometry.doorWaypoints(CX, CZ, r, n[0], n[1]);
                String ctx = "r=" + r + " n=" + n[0] + "," + n[1];

                // Door cell matches HovelBlueprint.isDoorGap: center.offset(doorSide, radius).
                assertEquals(new Cell(CX + n[0] * r, CZ + n[1] * r), wp.door(), ctx);
                assertEquals(ON_WALL, zone(wp.door(), r), ctx);

                assertEquals(0, lateral(wp.approach(), n), "approach on the door axis " + ctx);
                assertEquals(Math.max(0, r - 2), along(wp.approach(), n), ctx);
                assertEquals(INSIDE, zone(wp.approach(), r), ctx);

                assertEquals(3, wp.exitCandidates().size(), ctx);
                assertEquals(r + 2, along(wp.exitCandidates().get(0), n),
                        "preferred exit is on the perimeter ring (radius + PERIMETER_RING_OFFSET) " + ctx);
                assertEquals(r + 1, along(wp.exitCandidates().get(1), n), ctx);
                assertEquals(r + 3, along(wp.exitCandidates().get(2), n), ctx);
                for (Cell exit : wp.exitCandidates()) {
                    assertEquals(0, lateral(exit, n), "exit on the door axis " + ctx);
                    assertEquals(OUTSIDE, zone(exit, r), ctx);
                    assertTrue(HovelEgressGeometry.isInFrontOfDoor(exit.x(), exit.z(), CX, CZ, r, n[0], n[1]), ctx);
                }
            }
        }
    }

    @Test
    void concreteNorthDoorRadiusThree() {
        Waypoints wp = HovelEgressGeometry.doorWaypoints(CX, CZ, 3, 0, -1);
        assertEquals(new Cell(CX, CZ - 1), wp.approach());
        assertEquals(new Cell(CX, CZ - 3), wp.door());
        assertEquals(new Cell(CX, CZ - 5), wp.exitCandidates().get(0));
    }

    @Test
    void radiusTwoApproachIsTheCenter() {
        Waypoints wp = HovelEgressGeometry.doorWaypoints(CX, CZ, 2, 1, 0);
        assertEquals(new Cell(CX, CZ), wp.approach());
        assertEquals(new Cell(CX + 2, CZ), wp.door());
    }

    @Test
    void radiusTwoBotAtCenterGoesStraightToTheDoor() {
        // Radius 2: the approach cell is the center itself.
        assertEquals(EgressStep.DOOR, HovelEgressGeometry.firstStep(CX, CZ, CX, CZ, 2, 0, 1));
        assertEquals(EgressStep.APPROACH, HovelEgressGeometry.firstStep(CX, CZ - 1, CX, CZ, 2, 0, 1));
    }

    @Test
    void nonCardinalNormalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HovelEgressGeometry.doorWaypoints(CX, CZ, 3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HovelEgressGeometry.doorWaypoints(CX, CZ, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> HovelEgressGeometry.firstStep(CX, CZ, CX, CZ, 3, 0, 2));
    }

    // ---- where the route starts ----------------------------------------------------------------------

    @Test
    void firstStepOnEverySide() {
        int r = 4;
        for (int[] n : NORMALS) {
            String ctx = "n=" + n[0] + "," + n[1];
            // Bot in the doorway (ON_WALL): straight out.
            assertEquals(EgressStep.EXIT, step(n, r, 0, r), ctx);
            // Bot on the door axis between the approach cell (r - 2) and the door: straight at the door.
            assertEquals(EgressStep.DOOR, step(n, r - 1, 0, r), ctx);
            assertEquals(EgressStep.DOOR, step(n, r - 2, 0, r), ctx);
            // On the axis but deeper than the approach (center, far wall): walk up to the approach first.
            assertEquals(EgressStep.APPROACH, step(n, 0, 0, r), ctx);
            assertEquals(EgressStep.APPROACH, step(n, -r, 0, r), "far wall line on the axis " + ctx);
            // Off the axis (including the wall cell right beside the door): line up first.
            assertEquals(EgressStep.APPROACH, step(n, r - 1, 1, r), ctx);
            assertEquals(EgressStep.APPROACH, step(n, r, -1, r), ctx);
            assertEquals(EgressStep.APPROACH, step(n, 0, r, r), "side wall line " + ctx);
        }
    }

    // ---- fallback filter + vertical scan -------------------------------------------------------------

    @Test
    void inFrontOfDoorExcludesCellsAroundTheCorner() {
        int r = 3;
        // East door.
        assertTrue(HovelEgressGeometry.isInFrontOfDoor(CX + 4, CZ + 3, CX, CZ, r, 1, 0));
        assertTrue(HovelEgressGeometry.isInFrontOfDoor(CX + 6, CZ - 5, CX, CZ, r, 1, 0));
        assertFalse(HovelEgressGeometry.isInFrontOfDoor(CX + 3, CZ + 4, CX, CZ, r, 1, 0),
                "outside, but beside the door wall rather than in front of it");
        assertFalse(HovelEgressGeometry.isInFrontOfDoor(CX + 3, CZ, CX, CZ, r, 1, 0), "the doorway itself");
        assertFalse(HovelEgressGeometry.isInFrontOfDoor(CX - 5, CZ, CX, CZ, r, 1, 0), "behind the far wall");
    }

    @Test
    void verticalScanIsNearestFirstDownBeforeUp() {
        assertArrayEquals(new int[] {0}, HovelEgressGeometry.verticalScanOffsets(0));
        assertArrayEquals(new int[] {0, -1, 1}, HovelEgressGeometry.verticalScanOffsets(1));
        assertArrayEquals(new int[] {0, -1, 1, -2, 2}, HovelEgressGeometry.verticalScanOffsets(2));
        assertArrayEquals(new int[] {0}, HovelEgressGeometry.verticalScanOffsets(-3));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static FootprintZone zone(Cell c, int r) {
        return HovelEgressGeometry.classify(c.x(), c.z(), CX, CZ, r);
    }

    private static int along(Cell c, int[] n) {
        return (c.x() - CX) * n[0] + (c.z() - CZ) * n[1];
    }

    private static int lateral(Cell c, int[] n) {
        return (c.x() - CX) * -n[1] + (c.z() - CZ) * n[0];
    }

    /** Bot placed {@code alongN} out along the normal and {@code lat} to the side of it. */
    private static EgressStep step(int[] n, int alongN, int lat, int radius) {
        int x = CX + n[0] * alongN + -n[1] * lat;
        int z = CZ + n[1] * alongN + n[0] * lat;
        return HovelEgressGeometry.firstStep(x, z, CX, CZ, radius, n[0], n[1]);
    }
}
