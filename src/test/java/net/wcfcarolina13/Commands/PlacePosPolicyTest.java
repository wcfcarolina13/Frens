package net.wcfcarolina13.Commands;

import net.wcfcarolina13.Commands.PlacePosPolicy.Cell;
import net.wcfcarolina13.Commands.PlacePosPolicy.Face;
import net.wcfcarolina13.Commands.PlacePosPolicy.Stop;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

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
    void instantSnowClearIsGatedOnReachAndLineOfSight() throws IOException {
        String body = method("private static BlockPos attemptPlacement(");
        int snow = body.indexOf("Blocks.SNOW_BLOCK");
        int gate = body.indexOf("BlockInteractionService.canInteract(bot, placePos)");
        int breakAt = body.indexOf("breakBlock(placePos, false)");
        assertTrue(snow >= 0 && gate > snow, "the snow check must carry the reach + line-of-sight gate");
        assertTrue(breakAt > gate, "the clear must come after the reach + line-of-sight gate");
        assertTrue(breakAt < body.indexOf("BotActions.placeBlockAt("),
                "the gated clear still runs before the in-reach placement");
    }

    @Test
    void placeLoopCatchesUnexpectedErrors() throws IOException {
        String body = method("private static PlaceRun runPlaceLoop(");
        assertTrue(body.contains("catch (RuntimeException e)"));
        assertTrue(body.contains("LOGGER.error(\"Unexpected error in /bot place {}\", item, e)"));
        int catchAt = body.indexOf("catch (RuntimeException e)");
        assertTrue(body.indexOf("PlacePosPolicy.Stop.ERROR", catchAt) > catchAt,
                "an error ends the run with the count so far, so the report still says what was placed");
    }

    // --- 1.1.296: out of reach, a worker walks over and places ---

    @Test
    void onlyAServerThreadReachFailureHandsOff() {
        assertTrue(PlacePosPolicy.handOffToWorker(true, Stop.OUT_OF_REACH));
        for (Stop stop : Stop.values()) {
            assertFalse(PlacePosPolicy.handOffToWorker(false, stop), "a worker pass never hands off again: " + stop);
            if (stop != Stop.OUT_OF_REACH) {
                assertFalse(PlacePosPolicy.handOffToWorker(true, stop), stop.name());
            }
        }
    }

    @Test
    void reportLinesPerStop() {
        assertEquals(List.of("Placed 1 chest."), PlacePosPolicy.reportLines(1, Stop.DONE, "chest"));
        assertEquals(List.of("Placed 3 chests."), PlacePosPolicy.reportLines(3, Stop.DONE, "chest"));
        assertEquals(List.of("I don't have any furnace to place."), PlacePosPolicy.reportLines(0, Stop.NO_ITEMS, "furnace"));
        assertEquals(List.of("Placed 2 chests."), PlacePosPolicy.reportLines(2, Stop.NO_ITEMS, "chest"));
        // a reach failure is not "no items"
        assertEquals(List.of("I can't reach that spot."), PlacePosPolicy.reportLines(0, Stop.OUT_OF_REACH, "chest"));
        assertEquals(List.of("I can't reach that spot.", "Placed 1 chest."),
                PlacePosPolicy.reportLines(1, Stop.OUT_OF_REACH, "chest"));
        // /bot stop already says it stopped; only what was placed is added
        assertEquals(List.of(), PlacePosPolicy.reportLines(0, Stop.ABORTED, "chest"));
        assertEquals(List.of("Placed 1 chest."), PlacePosPolicy.reportLines(1, Stop.ABORTED, "chest"));
        assertEquals(List.of("Something went wrong placing the chest, so I stopped.", "Placed 2 chests."),
                PlacePosPolicy.reportLines(2, Stop.ERROR, "chest"));
        assertEquals(List.of("Something went wrong placing the chest, so I stopped."),
                PlacePosPolicy.reportLines(0, Stop.ERROR, "chest"));
    }

    @Test
    void placeCommandHandsTheWalkToAWorkerWithATicket() throws IOException {
        String body = method("private static int executePlaceGeneric(");
        int firstPass = body.indexOf("runPlaceLoop(bot, target, item, placeItem, count, 0, null)");
        int gate = body.indexOf("PlacePosPolicy.handOffToWorker(true, first.stop())");
        int ticket = body.indexOf("TaskService.beginSkill(\"place\", source, botUuid)");
        int submit = body.indexOf("skillExecutor.submit(");
        int rest = body.indexOf("runPlaceLoop(bot, target, item, placeItem, count, first.placed(), first.lastPlaced())");
        int report = body.indexOf("server.execute(() -> reportPlacement(source, rest, item))");
        int complete = body.indexOf("TaskService.complete(ticket, success)");
        assertTrue(firstPass >= 0 && gate > firstPass, "in-reach pass first, then the hand-off gate");
        assertTrue(ticket > gate && submit > ticket, "a /bot stop-able ticket before the worker hop");
        assertTrue(rest > submit, "the walking pass runs on the worker");
        assertTrue(report > rest, "the result goes back to the server thread");
        assertTrue(complete > report && body.lastIndexOf("finally", complete) > rest, "the ticket always completes");
        assertTrue(body.indexOf("return 1;", submit) > submit, "the command returns at once after the hop");
    }

    @Test
    void walkingPlacementKeepsMutationsOnTheServerThread() throws IOException {
        String body = method("private static BlockPos attemptPlacement(");
        int yaw = body.indexOf("bot.setYaw(target.yaw)");
        assertTrue(body.lastIndexOf("BotActions.callOnServerThread(", yaw) >= 0, "rotation runs on the server thread");
        int breakAt = body.indexOf("breakBlock(placePos, false)");
        int hop = body.lastIndexOf("BotActions.callOnServerThread(", breakAt);
        assertTrue(hop > yaw, "the snow clear runs inside its own server-thread call");
        assertTrue(body.contains("TaskService.isAbortRequested(bot.getUuid())"), "/bot stop ends the walk loop");
        String loop = method("private static PlaceRun runPlaceLoop(");
        assertTrue(loop.contains("BotActions.callOnServerThread(bot, () -> findItem(bot, placeItem)"));
        assertTrue(loop.contains("PlacePosPolicy.Stop.ABORTED"));
    }
}
