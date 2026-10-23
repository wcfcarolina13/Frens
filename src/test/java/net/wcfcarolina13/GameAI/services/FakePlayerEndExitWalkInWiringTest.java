package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bot that walks into the End exit portal before seeing credits goes through vanilla
 * detachForDimensionChange (not teleportTo) and waits for a client PERFORM_RESPAWN that the fake
 * connection never sends. createFakePlayer answers it itself, through the same helper /tp uses.
 */
class FakePlayerEndExitWalkInWiringTest {
    private static final Path SOURCE = Path.of("src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java");

    @Test
    void walkInDetachCallsSuperFirstThenRequestsRespawnWhenInLimbo() throws Exception {
        String body = methodBody(Files.readString(SOURCE), "public void detachForDimensionChange()");
        int superCall = body.indexOf("super.detachForDimensionChange();");
        int guard = body.indexOf("if (notInAnyWorld)");
        int respawn = body.indexOf("requestRespawnFromLimbo();");
        assertTrue(superCall >= 0, "override must call super");
        assertTrue(guard > superCall, "limbo guard must follow super");
        assertTrue(respawn > guard, "respawn must sit inside the limbo guard");
        assertEquals(1, count(body, "requestRespawnFromLimbo();"));
        assertFalse(body.contains("server.execute("), "runs inline on the server thread, like the /tp path");
    }

    @Test
    void overrideIsAnnotated() throws Exception {
        String source = Files.readString(SOURCE);
        int sig = source.indexOf("public void detachForDimensionChange()");
        assertTrue(sig >= 0);
        String before = source.substring(Math.max(0, sig - 40), sig);
        assertTrue(before.contains("@Override"));
    }

    @Test
    void helperSendsPerformRespawnThroughTheNetworkHandler() throws Exception {
        String body = methodBody(Files.readString(SOURCE), "private void requestRespawnFromLimbo()");
        assertTrue(body.contains("ClientStatusC2SPacket.Mode.PERFORM_RESPAWN"));
        assertTrue(body.contains("networkHandler.onClientStatus("));
    }

    @Test
    void teleportToUsesTheSameHelper() throws Exception {
        String source = Files.readString(SOURCE);
        String body = methodBody(source, "public ServerPlayerEntity teleportTo(TeleportTarget target)");
        int teleport = body.indexOf("super.teleportTo(target);");
        int guard = body.indexOf("if (notInAnyWorld)");
        int respawn = body.indexOf("requestRespawnFromLimbo();");
        assertTrue(teleport >= 0 && guard > teleport && respawn > guard);
        assertFalse(body.contains("PERFORM_RESPAWN"), "the packet is built only in the helper");
        assertEquals(1, count(source, "Mode.PERFORM_RESPAWN"), "one place builds the respawn packet");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start);
        return source.substring(start, end);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
