package net.wcfcarolina13.Entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakePlayerFallPolicyTest {

    private static FakePlayerFallPolicy.Kind move(double dx, double dy, double dz) {
        return FakePlayerFallPolicy.classify(dx, dy, dz, false, false, true);
    }

    @Test
    void ordinaryWalkingAndFallingAreMoves() {
        assertEquals(FakePlayerFallPolicy.Kind.MOVE, move(0.2, 0, 0.1));
        assertEquals(FakePlayerFallPolicy.Kind.MOVE, move(0, -0.08, 0));
        assertEquals(FakePlayerFallPolicy.Kind.MOVE, move(0, -3.92, 0), "terminal velocity is still a fall");
        assertEquals(FakePlayerFallPolicy.Kind.MOVE, move(0, 0.42, 0), "a jump");
        assertEquals(FakePlayerFallPolicy.Kind.MOVE, move(2.5, -0.3, 0), "a boosted glide");
    }

    @Test
    void bigJumpsInOneTickAreTeleports() {
        assertEquals(FakePlayerFallPolicy.Kind.TELEPORT, move(0, -30, 0));
        assertEquals(FakePlayerFallPolicy.Kind.TELEPORT, move(0, 12, 0));
        assertEquals(FakePlayerFallPolicy.Kind.TELEPORT, move(40, 0, -3));
        assertEquals(FakePlayerFallPolicy.Kind.TELEPORT, move(Double.NaN, 0, 0));
    }

    @Test
    void ridingSpectatingOrDeadIsSkipped() {
        assertEquals(FakePlayerFallPolicy.Kind.SKIP, FakePlayerFallPolicy.classify(0, -1, 0, true, false, true));
        assertEquals(FakePlayerFallPolicy.Kind.SKIP, FakePlayerFallPolicy.classify(0, -1, 0, false, true, true));
        assertEquals(FakePlayerFallPolicy.Kind.SKIP, FakePlayerFallPolicy.classify(0, -1, 0, false, false, false));
    }

    /**
     * Source wiring: the tick measures its own move and feeds handleFall, and the old fall()
     * override is gone. That override called handleFall, which calls fall(): if vanilla ever did
     * call it for a bot, it would recurse forever.
     */
    @Test
    void tickFeedsHandleFallAndTheRecursiveOverrideIsGone() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java"));
        assertFalse(src.contains("protected void fall(double y, boolean onGround, BlockState state, BlockPos pos)"),
                "no fall() override");
        int tick = src.indexOf("public void tick()");
        int track = src.indexOf("trackFall(", tick);
        int playerTick = src.indexOf("this.playerTick();", tick);
        assertTrue(tick >= 0 && playerTick > tick && track > playerTick, "trackFall runs after the movement tick");
        assertTrue(src.contains("handleFall(dx, dy, dz, this.isOnGround())"), "feeds handleFall like the network handler");
        int up = src.indexOf("if (dy > 0.0D) {", src.indexOf("handleFall(dx, dy, dz"));
        assertTrue(up > 0 && src.indexOf("this.onLanding();", up) > up, "an upward move resets the fall, as the network handler does");
        assertTrue(src.contains("FakePlayerFallPolicy.classify("), "uses the policy");
    }
}
