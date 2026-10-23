package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WallClipNudgePolicyTest {
    @Test
    void allFourDiagonalCornersHaveAnEscape() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                double x = sx > 0 ? 0.9 : 0.1;
                double z = sz > 0 ? 0.9 : 0.1;
                var nudge = WallClipNudgePolicy.candidates(x, z, x - 0.3, x + 0.3, z - 0.3, z + 0.3)
                        .stream().filter(n -> n.wallX() == sx && n.wallZ() == sz).findFirst().orElseThrow();
                assertTrue(nudge.dx() * sx < 0);
                assertTrue(nudge.dz() * sz < 0);
                assertEquals(0.4, Math.hypot(nudge.dx(), nudge.dz()), 1e-12);
                assertTrue(x + nudge.dx() + 0.3 <= sx || x + nudge.dx() - 0.3 >= sx + 1
                        || z + nudge.dz() + 0.3 <= sz || z + nudge.dz() - 0.3 >= sz + 1);
            }
        }
    }

    @Test
    void nearExtremeCornersHaveABoundedAlternativeThatClearsTheWholeCube() {
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                double x = sx > 0 ? 0.99 : 0.01;
                double z = sz > 0 ? 0.99 : 0.01;
                var candidates = WallClipNudgePolicy.candidates(x, z,
                                x - 0.3, x + 0.3, z - 0.3, z + 0.3)
                        .stream().filter(n -> n.wallX() == sx && n.wallZ() == sz).toList();
                assertFalse(candidates.isEmpty());
                var first = candidates.getFirst();
                assertTrue(overlapsCube(x + first.dx(), z + first.dz(), sx, sz));
                assertTrue(candidates.stream().anyMatch(n ->
                        !overlapsCube(x + n.dx(), z + n.dz(), sx, sz)));
                for (var nudge : candidates) {
                    assertTrue(Math.hypot(nudge.dx(), nudge.dz()) <= 0.4 + 1e-12);
                    assertTrue(nudge.dx() * sx <= 0);
                    assertTrue(nudge.dz() * sz <= 0);
                }
            }
        }
    }

    private static boolean overlapsCube(double x, double z, int wallX, int wallZ) {
        return x + 0.3 > wallX && x - 0.3 < wallX + 1
                && z + 0.3 > wallZ && z - 0.3 < wallZ + 1;
    }

    @Test
    void cardinalNudgeKeepsItsOriginalDistance() {
        var nudge = WallClipNudgePolicy.candidates(0.9, 0.5, 0.6, 1.2, 0.2, 0.8).getFirst();
        assertEquals(1, nudge.wallX());
        assertEquals(0, nudge.wallZ());
        assertEquals(-0.4, nudge.dx());
        assertEquals(0, nudge.dz());
    }

    @Test
    void centeredBodyHasNoWallCandidates() {
        assertTrue(WallClipNudgePolicy.candidates(0.5, 0.5, 0.2, 0.8, 0.2, 0.8).isEmpty());
    }

    @Test
    void touchingWithoutOverlapIsNotAClip() {
        assertTrue(WallClipNudgePolicy.candidates(0.7, 0.7, 0.4, 1, 0.4, 1).isEmpty());
    }

    @Test
    void negativeWorldCoordinatesUseFloor() {
        assertTrue(WallClipNudgePolicy.candidates(-0.1, -0.1, -0.4, 0.2, -0.4, 0.2)
                .stream().anyMatch(n -> n.wallX() == 0 && n.wallZ() == 0 && n.dx() < 0 && n.dz() < 0));
    }

    @Test
    void bothClipPathsUseGeometryAndFullDestinationCollision() throws Exception {
        String source = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotRescueService.java"));
        assertEquals(2, source.split("WallClipNudgePolicy.candidates", -1).length - 1);
        assertEquals(2, source.split("isNudgeDestinationSafe\\(bot, world,", -1).length - 1);
        int safety = source.indexOf("private static boolean isNudgeDestinationSafe");
        String guard = source.substring(safety, source.indexOf("/** Cell", safety));
        assertTrue(guard.contains("getBoundingBox().offset(x - bot.getX(), y - bot.getY(), z - bot.getZ())"));
        assertTrue(guard.contains("world.isSpaceEmpty(bot, targetBox)"));
        assertTrue(guard.contains("feetClear && headClear && !hazard"));
    }
}
