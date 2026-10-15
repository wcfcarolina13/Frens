package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.WallOffPolicy.Arrival;
import net.wcfcarolina13.GameAI.services.WallOffPolicy.Candidate;
import net.wcfcarolina13.GameAI.services.WallOffPolicy.Cell;
import net.wcfcarolina13.GameAI.services.WallOffPolicy.Face;
import net.wcfcarolina13.GameAI.services.WallOffPolicy.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.wcfcarolina13.GameAI.services.WallOffPolicy.CLEAR;
import static net.wcfcarolina13.GameAI.services.WallOffPolicy.HAZARD;
import static net.wcfcarolina13.GameAI.services.WallOffPolicy.PARTIAL;
import static net.wcfcarolina13.GameAI.services.WallOffPolicy.PROTECTED;
import static net.wcfcarolina13.GameAI.services.WallOffPolicy.SOLID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallOffPolicyTest {

    private static final Set<String> SEAL_IDS = Set.of(
            "minecraft:cobblestone", "minecraft:dirt", "minecraft:stone", "minecraft:oak_planks");

    /** A 15x15x8 box of solid rock with no sky; tests carve pockets into it. */
    private static final class Grid {
        final int radius;
        final byte[] cells;
        final boolean[] sky;
        final List<int[]> hostiles = new ArrayList<>();

        Grid() {
            this(WallOffPolicy.READ_RADIUS);
        }

        Grid(int radius) {
            this.radius = radius;
            int volume = Snapshot.volume(radius, WallOffPolicy.READ_DY_MIN, WallOffPolicy.READ_DY_MAX);
            cells = new byte[volume];
            sky = new boolean[volume];
            Arrays.fill(cells, SOLID);
        }

        Grid set(int dx, int dy, int dz, byte code) {
            cells[Snapshot.index(radius, WallOffPolicy.READ_DY_MIN, dx, dy, dz)] = code;
            return this;
        }

        Grid sky(int dx, int dy, int dz) {
            sky[Snapshot.index(radius, WallOffPolicy.READ_DY_MIN, dx, dy, dz)] = true;
            return this;
        }

        /** Two clear cells at (x, y, z) under solid cover on solid footing, all faces closed. */
        Grid pocket(int x, int y, int z) {
            return set(x, y, z, CLEAR).set(x, y + 1, z, CLEAR);
        }

        /** Opens a face of the pocket at (x, y, z) to the open air outside the overhang. */
        Grid open(int x, int y, int z, Face f) {
            int nx = x + f.dx;
            int nz = z + f.dz;
            return set(nx, y, nz, CLEAR).set(nx, y + 1, nz, CLEAR).sky(nx, y, nz).sky(nx, y + 1, nz);
        }

        Grid hostile(int dx, int dy, int dz) {
            hostiles.add(new int[]{dx, dy, dz});
            return this;
        }

        Snapshot snap(int blocks) {
            return new Snapshot(radius, WallOffPolicy.READ_DY_MIN, WallOffPolicy.READ_DY_MAX,
                    cells, sky, hostiles.toArray(new int[0][]), blocks, true);
        }
    }

    private static List<Candidate> rank(Grid g) {
        return WallOffPolicy.rank(g.snap(64), WallOffPolicy.MAX_PLACEMENTS);
    }

    // 1. countSealable

    @Test
    void countSealableCountsOnlyTheAllowlist() {
        assertEquals(4, WallOffPolicy.countSealable(
                Map.of("minecraft:dirt", 3, "minecraft:cobblestone", 1), SEAL_IDS));
        int valuable = WallOffPolicy.countSealable(
                Map.of("minecraft:diamond_block", 64, "minecraft:dirt", 3), SEAL_IDS);
        assertEquals(3, valuable);
        assertTrue(valuable < WallOffPolicy.MIN_BLOCKS);
        assertEquals(0, WallOffPolicy.countSealable(Map.of(), SEAL_IDS));
    }

    // 2-5. Open faces

    @Test
    void oneOpenFaceGivesOneCandidateWithTwoPlacements() {
        List<Candidate> ranked = rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH));
        assertEquals(1, ranked.size());
        Candidate c = ranked.get(0);
        assertEquals(new Cell(0, 0, 0), c.feet());
        assertEquals(2, c.placements().size());
        assertEquals(1, c.openFaces());
        assertEquals(Face.NORTH, c.primaryFace());
        assertTrue(c.placements().contains(new Cell(0, 0, -1)));
        assertTrue(c.placements().contains(new Cell(0, 1, -1)));
    }

    @Test
    void eastFaceMapsToPositiveDx() {
        List<Candidate> ranked = rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.EAST));
        assertEquals(1, ranked.size());
        assertEquals(Face.EAST, ranked.get(0).primaryFace());
        assertEquals(List.of(new Cell(1, 0, 0), new Cell(1, 1, 0)),
                WallOffPolicy.placementOrder(ranked.get(0)));
    }

    @Test
    void twoOpenFacesGiveFourPlacements() {
        List<Candidate> ranked = rank(new Grid().pocket(0, 0, 0)
                .open(0, 0, 0, Face.NORTH).open(0, 0, 0, Face.WEST));
        assertEquals(1, ranked.size());
        assertEquals(4, ranked.get(0).placements().size());
        assertEquals(2, ranked.get(0).openFaces());
    }

    @Test
    void threeOpenFacesAreTooExposed() {
        assertTrue(rank(new Grid().pocket(0, 0, 0)
                .open(0, 0, 0, Face.NORTH).open(0, 0, 0, Face.WEST).open(0, 0, 0, Face.EAST)).isEmpty());
    }

    @Test
    void zeroOpenFacesIsAlreadyEnclosed() {
        assertTrue(rank(new Grid().pocket(0, 0, 0)).isEmpty());
    }

    @Test
    void headLevelGapAloneIsAnOpenFace() {
        Grid g = new Grid().pocket(0, 0, 0).set(0, 1, -1, CLEAR).sky(0, 1, -1);
        List<Candidate> ranked = rank(g);
        assertEquals(1, ranked.size());
        assertEquals(List.of(new Cell(0, 1, -1)), ranked.get(0).placements());
        assertEquals(Face.NORTH, ranked.get(0).primaryFace());
    }

    // 6-9. Collision rules

    @Test
    void torchInAFaceCellMakesItUnsealable() {
        Grid g = new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, 0, -1, PARTIAL);
        assertTrue(rank(g).isEmpty());
    }

    @Test
    void skyAtHeadIsRejected() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).sky(0, 1, 0)).isEmpty());
    }

    @Test
    void partialCoverIsNotCover() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, 2, 0, PARTIAL)).isEmpty());
    }

    @Test
    void carpetOverAirIsNotFooting() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, -1, 0, PARTIAL)).isEmpty());
    }

    @Test
    void slabFootingCountsBecauseItHasCollision() {
        // Slabs and stairs read as SOLID by collision; the plain pocket is the accepted shape.
        assertEquals(1, rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH)).size());
    }

    @Test
    void openFaceWithoutSupportUnderTheFeetSealIsRejected() {
        Grid g = new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, -1, -1, CLEAR).sky(0, -1, -1);
        assertTrue(rank(g).isEmpty());
    }

    // 10. Protection, hazards, hostiles

    @Test
    void protectedFaceCellIsRejected() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, 1, -1, PROTECTED)).isEmpty());
    }

    @Test
    void lavaNeighbourIsRejected() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, 0, -1, HAZARD)).isEmpty());
    }

    @Test
    void hazardFloorIsRejected() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).set(0, -1, 0, HAZARD)).isEmpty());
    }

    @Test
    void hostileWithinFourBlocksIsRejected() {
        assertTrue(rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).hostile(0, 0, -4)).isEmpty());
    }

    @Test
    void hostileAtFiveBlocksIsAccepted() {
        assertEquals(1, rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).hostile(0, 0, -5)).size());
    }

    // 11. Block budget

    @Test
    void blockBudgetCapsPlacements() {
        Grid g = new Grid()
                .pocket(0, 0, 0).open(0, 0, 0, Face.NORTH).open(0, 0, 0, Face.WEST) // 4 placements
                .pocket(4, 0, 4).open(4, 0, 4, Face.SOUTH);                         // 2 placements
        List<Candidate> withThree = WallOffPolicy.rank(g.snap(3), WallOffPolicy.MAX_PLACEMENTS);
        assertEquals(1, withThree.size());
        assertEquals(new Cell(4, 0, 4), withThree.get(0).feet());

        List<Candidate> maxTwo = WallOffPolicy.rank(g.snap(64), 2);
        assertEquals(1, maxTwo.size());
        assertEquals(new Cell(4, 0, 4), maxTwo.get(0).feet());

        assertEquals(2, WallOffPolicy.rank(g.snap(64), WallOffPolicy.MAX_PLACEMENTS).size());
        assertTrue(WallOffPolicy.rank(g.snap(0), WallOffPolicy.MAX_PLACEMENTS).isEmpty());
    }

    // 12. Ordering

    @Test
    void fewerPlacementsBeatCloser() {
        Grid g = new Grid()
                .pocket(1, 0, 0).open(1, 0, 0, Face.EAST)                      // 2 placements, near
                .pocket(-5, 0, -5).set(-5, 1, -6, CLEAR).sky(-5, 1, -6);       // 1 placement, far
        List<Candidate> ranked = rank(g);
        assertEquals(2, ranked.size());
        assertEquals(new Cell(-5, 0, -5), ranked.get(0).feet());
        assertEquals(new Cell(1, 0, 0), ranked.get(1).feet());
    }

    @Test
    void tiesGoToTheCloserCellAndTheListIsCappedAtThree() {
        Grid g = new Grid()
                .pocket(5, 0, 5).open(5, 0, 5, Face.SOUTH)
                .pocket(-4, 0, 0).open(-4, 0, 0, Face.WEST)
                .pocket(0, 0, 2).open(0, 0, 2, Face.SOUTH)
                .pocket(3, 1, -3).open(3, 1, -3, Face.NORTH);
        List<Candidate> ranked = rank(g);
        assertEquals(3, ranked.size());
        assertEquals(new Cell(0, 0, 2), ranked.get(0).feet());
        assertEquals(new Cell(-4, 0, 0), ranked.get(1).feet());
        assertEquals(new Cell(3, 1, -3), ranked.get(2).feet());
    }

    // 13. Box edge

    @Test
    void neighbourhoodOutsideTheBoxIsRejected() {
        Grid g = new Grid(WallOffPolicy.SEARCH_RADIUS).pocket(6, 0, 0).open(6, 0, 0, Face.NORTH);
        assertTrue(rank(g).isEmpty());
        Grid inside = new Grid().pocket(6, 0, 0).open(6, 0, 0, Face.NORTH);
        assertEquals(1, rank(inside).size());
    }

    // 14. Placement order

    @Test
    void placementOrderPutsFeetBeforeHeadAndThePrimaryFaceLast() {
        Grid g = new Grid().pocket(0, 0, 0)
                .open(0, 0, 0, Face.EAST)                    // two placements: primary
                .set(0, 1, -1, CLEAR).sky(0, 1, -1);         // north head gap: one placement
        Candidate c = rank(g).get(0);
        assertEquals(Face.EAST, c.primaryFace());
        assertEquals(List.of(new Cell(0, 1, -1), new Cell(1, 0, 0), new Cell(1, 1, 0)),
                WallOffPolicy.placementOrder(c));
    }

    @Test
    void placementOrderWithTwoFullFacesKeepsFeetBeforeHeadInEach() {
        Candidate c = rank(new Grid().pocket(0, 0, 0).open(0, 0, 0, Face.SOUTH).open(0, 0, 0, Face.WEST)).get(0);
        List<Cell> order = WallOffPolicy.placementOrder(c);
        assertEquals(4, order.size());
        assertEquals(0, order.get(0).dy());
        assertEquals(1, order.get(1).dy());
        assertEquals(0, order.get(2).dy());
        assertEquals(1, order.get(3).dy());
        Cell lastFeet = order.get(2);
        assertEquals(c.primaryFace().dx, lastFeet.dx());
        assertEquals(c.primaryFace().dz, lastFeet.dz());
    }

    // 15. Arrival

    @Test
    void arrivalVerdicts() {
        assertEquals(Arrival.ARRIVED, WallOffPolicy.arrival(10.5, 64.0, -3.5, true, 10, 64, -4));
        assertEquals(Arrival.OFF_CENTRE, WallOffPolicy.arrival(10.9, 64.0, -3.5, true, 10, 64, -4));
        // 0.25 off: the 0.6-wide box would reach 0.05 into the neighbouring seal cell.
        assertEquals(Arrival.OFF_CENTRE, WallOffPolicy.arrival(10.75, 64.0, -3.5, true, 10, 64, -4));
        assertEquals(Arrival.ARRIVED, WallOffPolicy.arrival(10.65, 64.0, -3.35, true, 10, 64, -4));
        assertEquals(Arrival.OFF_CELL, WallOffPolicy.arrival(11.2, 64.0, -3.5, true, 10, 64, -4));
        assertEquals(Arrival.AIRBORNE, WallOffPolicy.arrival(10.5, 64.4, -3.5, false, 10, 64, -4));
    }

    // 16-18. Abort, teardown

    @Test
    void staleStopLatchIsClearedOnlyWhenNoTaskOwnsIt() {
        assertTrue(WallOffPolicy.clearStaleLatchAtStart(true, false));   // /bot stop a minute ago, idle
        assertFalse(WallOffPolicy.clearStaleLatchAtStart(true, true));   // a running task is being cancelled
        assertFalse(WallOffPolicy.clearStaleLatchAtStart(false, false)); // nothing to clear
    }

    @Test
    void walkAbortsOnAFourPointDrop() {
        assertTrue(WallOffPolicy.abortWalk(20f, 16f));
        assertFalse(WallOffPolicy.abortWalk(20f, 17f));
    }

    @Test
    void teardownOnlyRemovesSealFamilyBlocks() {
        assertTrue(WallOffPolicy.teardownAllowed("minecraft:cobblestone", SEAL_IDS));
        assertFalse(WallOffPolicy.teardownAllowed("minecraft:glass", SEAL_IDS));
        assertFalse(WallOffPolicy.teardownAllowed("minecraft:chest", SEAL_IDS));
        assertFalse(WallOffPolicy.teardownAllowed(null, SEAL_IDS));
    }

    @Test
    void onlyAHostileRejectionKeepsTheWall() {
        assertTrue(WallOffPolicy.teardownOnReject("entrance_unsealed"));
        assertTrue(WallOffPolicy.teardownOnReject("open_sky"));
        assertFalse(WallOffPolicy.teardownOnReject("hostile_exposed"));
        assertFalse(WallOffPolicy.teardownOnReject("HOSTILE_EXPOSED"));
        assertTrue(WallOffPolicy.teardownOnReject("something_new"));
        assertTrue(WallOffPolicy.teardownOnReject(null));
    }

    @Test
    void revalidationKeepsASealedWallWithAMobOutside() {
        assertTrue(WallOffPolicy.keepOnRevalidate("hostile_exposed", true));
        assertFalse(WallOffPolicy.keepOnRevalidate("hostile_exposed", false));
        assertFalse(WallOffPolicy.keepOnRevalidate("entrance_unsealed", true));
        assertFalse(WallOffPolicy.keepOnRevalidate("open_sky", true));
        assertFalse(WallOffPolicy.keepOnRevalidate(null, true));
    }
}
