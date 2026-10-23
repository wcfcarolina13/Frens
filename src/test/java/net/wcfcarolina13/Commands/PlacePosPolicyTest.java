package net.wcfcarolina13.Commands;

import net.wcfcarolina13.Commands.PlacePosPolicy.Cell;
import net.wcfcarolina13.Commands.PlacePosPolicy.Face;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /bot place onto a snow layer placed into the cell above the snow, failed, walked the bot on the
 * server thread (frozen tick, no movement) and then threw rotating the UP face. 1.1.295.
 */
class PlacePosPolicyTest {

    @Test
    void replaceableHitPlacesIntoTheHitCell() {
        assertEquals(Cell.HIT, PlacePosPolicy.placementCell(true));
        assertEquals(Cell.BESIDE_FACE, PlacePosPolicy.placementCell(false));
    }

    @Test
    void serverThreadNeverWalksAndTriesOnce() {
        assertFalse(PlacePosPolicy.mayWalk(true));
        assertTrue(PlacePosPolicy.mayWalk(false));
        assertEquals(1, PlacePosPolicy.maxAttempts(true));
        assertEquals(10, PlacePosPolicy.maxAttempts(false));
    }

    @Test
    void yawFacingMatchesMinecraftConvention() {
        assertEquals(Face.SOUTH, PlacePosPolicy.facingFromYaw(0f));
        assertEquals(Face.WEST, PlacePosPolicy.facingFromYaw(90f));
        assertEquals(Face.NORTH, PlacePosPolicy.facingFromYaw(180f));
        assertEquals(Face.NORTH, PlacePosPolicy.facingFromYaw(-180f));
        assertEquals(Face.EAST, PlacePosPolicy.facingFromYaw(-90f));
        assertEquals(Face.EAST, PlacePosPolicy.facingFromYaw(270f));
        assertEquals(Face.SOUTH, PlacePosPolicy.facingFromYaw(44f));
        assertEquals(Face.WEST, PlacePosPolicy.facingFromYaw(46f));
        assertEquals(Face.SOUTH, PlacePosPolicy.facingFromYaw(720f));
    }

    @Test
    void horizontalFaceRotatesClockwiseLikeDirection() {
        assertEquals(Face.EAST, PlacePosPolicy.sidestep(Face.NORTH, 0f));
        assertEquals(Face.SOUTH, PlacePosPolicy.sidestep(Face.EAST, 0f));
        assertEquals(Face.WEST, PlacePosPolicy.sidestep(Face.SOUTH, 0f));
        assertEquals(Face.NORTH, PlacePosPolicy.sidestep(Face.WEST, 0f));
    }

    @Test
    void verticalFaceSidestepsFromYawAndNeverThrows() {
        // facing SOUTH (yaw 0) -> clockwise WEST
        assertEquals(Face.WEST, PlacePosPolicy.sidestep(Face.UP, 0f));
        assertEquals(Face.WEST, PlacePosPolicy.sidestep(Face.DOWN, 0f));
        assertEquals(Face.EAST, PlacePosPolicy.sidestep(Face.UP, 180f));
        assertEquals(Face.WEST, PlacePosPolicy.sidestep(null, 0f));
        for (Face f : Face.values()) {
            for (float yaw = -360f; yaw <= 360f; yaw += 7.5f) {
                assertFalse(PlacePosPolicy.sidestep(f, yaw).vertical());
            }
        }
    }

    @Test
    void faceNamesMirrorMinecraftDirection() {
        // the registry maps Direction <-> Face by name()
        String[] direction = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
        for (String name : direction) {
            assertEquals(name, Face.valueOf(name).name());
        }
        assertEquals(direction.length, Face.values().length);
    }

    // --- wiring, from source text (the command needs the Minecraft runtime) ---

    private static final String COMMANDS = "src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java";

    private static String method(String signature) throws IOException {
        String src = Files.readString(Paths.get(COMMANDS), StandardCharsets.UTF_8);
        int start = src.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, "could not find the end of " + signature);
        return src.substring(start, end).replaceAll("\\s+", " ");
    }

    @Test
    void captureUsesReplaceableRule() throws IOException {
        String body = method("private static PlacementTarget capturePlacementTarget(");
        assertTrue(body.contains("isReplaceable()"));
        assertTrue(body.contains("PlacePosPolicy.placementCell("));
    }

    @Test
    void attemptPlacementGatesWalksAndNeverRotatesTheRawFace() throws IOException {
        String body = method("private static BlockPos attemptPlacement(");
        assertTrue(body.contains("isOnThread()"));
        assertTrue(body.contains("PlacePosPolicy.maxAttempts(onServerThread)"));
        assertTrue(body.contains("PlacePosPolicy.mayWalk(onServerThread)"));
        assertFalse(body.contains("target.face.rotateYClockwise()"));
        assertTrue(body.contains("placementSidestep("));
        int gate = body.indexOf("PlacePosPolicy.mayWalk(onServerThread)");
        int walk = body.indexOf("MovementService.execute(");
        assertTrue(gate >= 0 && walk > gate, "walk must come after the server-thread gate");
        assertTrue(body.contains("Blocks.SNOW"), "/bot place keeps its own instant snow clear (1.1.288)");
    }

    @Test
    void placeCommandCatchesUnexpectedErrors() throws IOException {
        String body = method("private static int executePlaceGeneric(");
        assertTrue(body.contains("catch (RuntimeException e)"));
        assertTrue(body.contains("LOGGER.error(\"Unexpected error in /bot place {}\", item, e)"));
        int catchAt = body.indexOf("catch (RuntimeException e)");
        int summaryInCatch = body.indexOf("placedLine(placed, item)", catchAt);
        int catchReturn = body.indexOf("return placed > 0 ? 1 : 0;", catchAt);
        assertTrue(summaryInCatch > catchAt && summaryInCatch < catchReturn,
                "an error after some placements still reports what was placed");
    }

    @Test
    void reachFailureIsNotFollowedByTheNoItemLine() throws IOException {
        String body = method("private static int executePlaceGeneric(");
        int reach = body.indexOf("\"I can't reach that spot.\"");
        assertTrue(reach >= 0);
        assertTrue(body.indexOf("reachFailed = true;", reach) > reach);
        int noItem = body.indexOf("\"I don't have any \"");
        assertTrue(noItem > 0);
        assertTrue(body.lastIndexOf("!reachFailed", noItem) > reach,
                "the no-item line must be gated on the reach failure");
    }
}
