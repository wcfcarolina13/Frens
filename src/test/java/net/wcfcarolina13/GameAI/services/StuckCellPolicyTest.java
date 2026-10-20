package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckCellPolicyTest {

    // Collision max Y of the vanilla shapes the callers see. 0.0 with empty=true means no collision.
    private static final double SNOW_1 = 0.0;      // layer 1: empty collision
    private static final double SNOW_2 = 0.125;    // layer 2
    private static final double SNOW_3 = 0.25;     // layer 3
    private static final double SNOW_4 = 0.375;    // layer 4
    private static final double CARPET = 0.0625;
    private static final double SLAB = 0.5;
    private static final double FULL = 1.0;
    private static final double FENCE = 1.5;

    /** Mirrors WalkablePartialBlocks.isPathable's shape fallback. */
    private static boolean pathable(boolean empty, double maxY) {
        return empty || maxY <= StuckCellPolicy.THIN_MAX_Y;
    }

    private static boolean enterable(boolean feetEmpty, double feetMaxY, boolean headEmpty, double headMaxY) {
        return StuckCellPolicy.sideEnterable(pathable(feetEmpty, feetMaxY), pathable(headEmpty, headMaxY),
                false, false, false);
    }

    // --- sideEnterable -------------------------------------------------------

    @Test
    void shortGrassAndLeafLitterSideCellsAreEnterable() {
        // short grass, leaf litter, flowers: empty collision in both rows
        assertTrue(enterable(true, 0, true, 0));
    }

    @Test
    void thinSnowAndCarpetAtFeetAreEnterable() {
        assertTrue(enterable(true, SNOW_1, true, 0));
        assertTrue(enterable(false, SNOW_2, true, 0));
        assertTrue(enterable(false, CARPET, true, 0));
    }

    @Test
    void snowLayerThreeOrMoreAtFeetIsNotEnterable() {
        // Documented decision: 3+ layers is a step to stand on, not a cell to move into.
        assertFalse(enterable(false, SNOW_3, true, 0));
        assertFalse(enterable(false, SNOW_4, true, 0));
        assertFalse(enterable(false, SLAB, true, 0));
    }

    @Test
    void anyThickCollisionAtHeadIsNotEnterable() {
        assertFalse(enterable(true, 0, false, SNOW_3));
        assertFalse(enterable(true, 0, false, SLAB));
        assertFalse(enterable(true, 0, false, FULL));
    }

    @Test
    void hazardsRejectTheCellEvenWithEmptyCollision() {
        // fire, powder snow, cobweb all have empty collision: only the hazard bit catches them
        assertFalse(StuckCellPolicy.sideEnterable(true, true, true, false, false));
        assertFalse(StuckCellPolicy.sideEnterable(true, true, false, true, false));
    }

    @Test
    void lavaIsRejectedAsHazardAndAsNonWaterFluid() {
        assertFalse(StuckCellPolicy.sideEnterable(true, true, true, false, true));
        assertFalse(StuckCellPolicy.sideEnterable(true, true, false, false, true));
    }

    @Test
    void waterIsEnterable() {
        assertTrue(StuckCellPolicy.sideEnterable(true, true, false, false, false));
    }

    // --- wallAt ---------------------------------------------------------------

    @Test
    void fullBlockAtFeetIsAWall() {
        assertTrue(StuckCellPolicy.wallAt(false, true, false, true));
        assertFalse(StuckCellPolicy.wallHeadOnly(false, true, false, true));
    }

    @Test
    void headLevelBlockBesideTheBotIsAWall() {
        assertTrue(StuckCellPolicy.wallAt(true, false, true, false));
        assertTrue(StuckCellPolicy.wallHeadOnly(true, false, true, false));
    }

    @Test
    void thinPartialsAndOpenCellsAreNotWalls() {
        assertFalse(StuckCellPolicy.wallAt(true, true, true, true));
        // carpet at feet: non-empty collision but pathable
        assertFalse(StuckCellPolicy.wallAt(false, true, true, true));
        assertFalse(StuckCellPolicy.isWallCell(false, true));
        assertFalse(StuckCellPolicy.isWallCell(true, true));
        assertTrue(StuckCellPolicy.isWallCell(false, false));
    }

    // --- blocksHorizontal -------------------------------------------------------

    @Test
    void fenceAndWallBlockAtFeet() {
        assertTrue(StuckCellPolicy.blocksHorizontal(false, FENCE, false));
        assertTrue(StuckCellPolicy.blocksHorizontal(false, FULL, false));
    }

    @Test
    void slabAndLowSnowDoNotBlockAtFeet() {
        assertFalse(StuckCellPolicy.blocksHorizontal(false, SLAB, false));
        assertFalse(StuckCellPolicy.blocksHorizontal(false, SNOW_4, false));
        assertFalse(StuckCellPolicy.blocksHorizontal(false, StuckCellPolicy.STEP_HEIGHT, false));
    }

    @Test
    void slabBlocksAtHead() {
        assertTrue(StuckCellPolicy.blocksHorizontal(false, SLAB, true));
        assertTrue(StuckCellPolicy.blocksHorizontal(false, SNOW_3, true));
    }

    @Test
    void thinPartialDoesNotBlockAtHead() {
        assertFalse(StuckCellPolicy.blocksHorizontal(false, CARPET, true));
        assertFalse(StuckCellPolicy.blocksHorizontal(false, SNOW_2, true));
    }

    @Test
    void emptyCollisionNeverBlocks() {
        // grass, open fence gate, cobweb, water
        assertFalse(StuckCellPolicy.blocksHorizontal(true, 0, false));
        assertFalse(StuckCellPolicy.blocksHorizontal(true, 0, true));
    }

    @Test
    void passableDoorNeverBlocksButClosedIronDoorDoes() {
        // A door panel reports max Y 1.0: it would block on shape alone.
        assertFalse(StuckCellPolicy.blocksHorizontalCell(true, false, false, FULL, false));
        assertFalse(StuckCellPolicy.blocksHorizontalCell(true, false, false, FULL, true));
        assertTrue(StuckCellPolicy.blocksHorizontalCell(false, false, false, FULL, false));
        assertTrue(StuckCellPolicy.blocksHorizontalCell(false, false, false, FENCE, true));
    }

    @Test
    void standableCellsAtFeetDoNotBlock() {
        double stair = 1.0, farmland = 0.9375, snow8 = 0.875;
        assertFalse(StuckCellPolicy.blocksHorizontalCell(false, true, false, stair, false));
        assertFalse(StuckCellPolicy.blocksHorizontalCell(false, true, false, farmland, false)); // also dirt path
        assertFalse(StuckCellPolicy.blocksHorizontalCell(false, true, false, snow8, false));
    }

    @Test
    void standableIsIgnoredAtHead() {
        assertTrue(StuckCellPolicy.blocksHorizontalCell(false, true, false, 1.0, true));
        assertTrue(StuckCellPolicy.blocksHorizontalCell(false, true, false, 0.9375, true));
    }

    @Test
    void fenceAtFeetStillBlocksWhenNotStandable() {
        assertTrue(StuckCellPolicy.blocksHorizontalCell(false, false, false, FENCE, false));
    }

    // --- dropOneAllowed ---------------------------------------------------------

    @Test
    void dropOneOntoSolidFloorWithClearCellsIsAllowed() {
        assertTrue(StuckCellPolicy.dropOneAllowed(true, true, true, true, false, false, false));
    }

    @Test
    void dropOneNeedsACollisionFloor() {
        assertFalse(StuckCellPolicy.dropOneAllowed(true, true, true, false, false, false, false));
    }

    @Test
    void dropOneRefusesHazardOrFluid() {
        assertFalse(StuckCellPolicy.dropOneAllowed(true, true, true, true, true, false, false));  // magma floor
        assertFalse(StuckCellPolicy.dropOneAllowed(true, true, true, true, false, true, false));  // fire, cobweb
        assertFalse(StuckCellPolicy.dropOneAllowed(true, true, true, true, false, false, true));  // water, lava
    }

    @Test
    void dropOneNeedsAllThreeRowsPathable() {
        assertFalse(StuckCellPolicy.dropOneAllowed(false, true, true, true, false, false, false));
        assertFalse(StuckCellPolicy.dropOneAllowed(true, false, true, true, false, false, false));
        assertFalse(StuckCellPolicy.dropOneAllowed(true, true, false, true, false, false, false));
    }

    // --- jumpSprintEscaped ------------------------------------------------------

    @Test
    void jumpSprintSameCellHigherYIsNotAnEscape() {
        // Same X/Z, Y+1 (mid-jump or back on the same column): the caller never passes Y.
        assertFalse(StuckCellPolicy.jumpSprintEscaped(10, -4, 10, -4, true, false));
        assertFalse(StuckCellPolicy.jumpSprintEscaped(10, -4, 10, -4, false, false));
        assertFalse(StuckCellPolicy.jumpSprintEscaped(10, -4, 10, -4, false, true));
    }

    @Test
    void jumpSprintSidewaysOnGroundIsAnEscape() {
        assertTrue(StuckCellPolicy.jumpSprintEscaped(10, -4, 11, -4, true, false));
        assertTrue(StuckCellPolicy.jumpSprintEscaped(10, -4, 10, -5, true, false));
    }

    @Test
    void jumpSprintSidewaysReadEarlyWhileAirborneIsNotAnEscape() {
        assertFalse(StuckCellPolicy.jumpSprintEscaped(10, -4, 11, -4, false, false));
    }

    @Test
    void jumpSprintSidewaysStillAirborneAfterTheWholeLandingWaitIsAnEscape() {
        // The stop zeroed velocity near the apex; a bot still falling at the cap but in a new
        // column (e.g. dropping down a slope) did get out.
        assertTrue(StuckCellPolicy.jumpSprintEscaped(10, -4, 11, -4, false, true));
    }

    @Test
    void jumpSprintJudgeWaitsForTheLandingBoundedAfterTheStop() throws IOException {
        String source = mainSource("src/main/java/net/wcfcarolina13/GameAI/services/ReturnBaseStuckService.java");
        String body = bodyOf(source, "private static void tryBackupAndSidestep(");
        int stop = body.indexOf("BotActions.stop(bot);");
        int poll = body.indexOf("while (!onGround && System.currentTimeMillis() < landBy");
        int judge = body.indexOf("StuckCellPolicy.jumpSprintEscaped(");
        assertTrue(stop >= 0 && poll > stop && judge > poll, "stop, then the bounded landing poll, then the judge");
        assertTrue(body.contains("System.currentTimeMillis() + JUMP_SPRINT_LAND_WAIT_MS"), "the poll is bounded");
        assertTrue(body.contains("onGround, landWaitTimedOut)"), "the judge gets the timed-out flag");
        assertTrue(source.contains("JUMP_SPRINT_LAND_WAIT_MS = 800L"));
        assertFalse(source.contains("JUMP_SPRINT_SETTLE_MS"), "the fixed 200 ms settle is gone");
        // The constant no longer splits the method's javadoc from the method.
        int doc = source.indexOf("     * If all directions are blocked, delegates to mining (pillar escape).\n     */\n");
        assertTrue(doc >= 0 && source.startsWith("    private static void tryBackupAndSidestep(",
                doc + "     * If all directions are blocked, delegates to mining (pillar escape).\n     */\n".length()),
                "the javadoc sits on tryBackupAndSidestep");
    }

    @Test
    void aNewReturnKeepsTheEscapeInProgressFlag() throws IOException {
        String stuck = mainSource("src/main/java/net/wcfcarolina13/GameAI/services/ReturnBaseStuckService.java");
        String counters = bodyOf(stuck, "public static void clearCounters(UUID botId)");
        assertFalse(counters.contains("ESCAPE_IN_PROGRESS"), "clearCounters leaves a running escape's flag");
        assertTrue(counters.contains("STAGNANT_TICKS.remove(botId)"));
        String clear = bodyOf(stuck, "public static void clear(UUID botId)");
        assertTrue(clear.contains("clearCounters(botId)") && clear.contains("ESCAPE_IN_PROGRESS.remove(botId)"),
                "clear is clearCounters plus the flag");
        String handler = mainSource("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java");
        String ret = bodyOf(handler, "private static String setReturnToBaseInternal(");
        assertTrue(ret.contains("ReturnBaseStuckService.clearCounters(id);"), "the new-return path keeps the flag");
        assertFalse(ret.contains("ReturnBaseStuckService.clear(id);"));
    }

    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " not found");
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError(signature + ": unbalanced body");
    }

    private static String mainSource(String relative) throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
    }
}
