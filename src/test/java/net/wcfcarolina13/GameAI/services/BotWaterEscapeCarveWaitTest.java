package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.PlayerUtils.BreakTimePolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 48 fix wave: a ledge-cell swing is waited on for MiningTool's own deadline (plus a
 * margin), not a flat 45 s, and a swing still running after that is cancelled so the next
 * recovery cycle never starts a second swing on the same cell.
 */
class BotWaterEscapeCarveWaitTest {

    private static final String SERVICE = "src/main/java/net/wcfcarolina13/GameAI/services/BotWaterEscapeService.java";

    @Test
    void waitMatchesMiningToolDeadlinePlusMargin() {
        // Stone by hand in water and off the ground: 1/1.5/100/25 per tick -> 3750 ticks, ~3 minutes.
        float slow = BreakTimePolicy.delta(1.0f, 1.5f, false) / 25.0f;
        int ticks = BreakTimePolicy.requiredTicks(slow);
        long wait = BotWaterEscapeService.carveWaitMs(slow);
        assertEquals(BreakTimePolicy.timeoutMs(ticks) + BotWaterEscapeService.CARVE_MINE_MARGIN_MS, wait);
        assertTrue(wait > 180_000L, "a ~3 minute swing must not be cut at 45 s: " + wait);
    }

    @Test
    void fastBlockUsesMiningToolFloorPlusMargin() {
        assertEquals(BreakTimePolicy.MIN_TIMEOUT_MS + BotWaterEscapeService.CARVE_MINE_MARGIN_MS,
                BotWaterEscapeService.carveWaitMs(1.0f));
        assertEquals(BreakTimePolicy.MIN_TIMEOUT_MS + BotWaterEscapeService.CARVE_MINE_MARGIN_MS,
                BotWaterEscapeService.carveWaitMs(Float.POSITIVE_INFINITY));
    }

    @Test
    void unknownOrUnbreakableDeltaFallsBack() {
        assertEquals(BotWaterEscapeService.CARVE_MINE_FALLBACK_TIMEOUT_MS, BotWaterEscapeService.carveWaitMs(Float.NaN));
        assertEquals(BotWaterEscapeService.CARVE_MINE_FALLBACK_TIMEOUT_MS, BotWaterEscapeService.carveWaitMs(0.0f));
        assertEquals(BotWaterEscapeService.CARVE_MINE_FALLBACK_TIMEOUT_MS, BotWaterEscapeService.carveWaitMs(-1.0f));
    }

    @Test
    void timedOutSwingIsCancelledAndCellReadOnServerThread() throws IOException {
        String src = Files.readString(Paths.get(SERVICE), StandardCharsets.UTF_8);
        int start = src.indexOf("private static boolean mineLedgeCell(");
        assertTrue(start >= 0, "mineLedgeCell not found");
        String body = src.substring(start, src.indexOf("static long carveWaitMs(", start));
        assertTrue(body.contains("catch (TimeoutException e) {\n            mining.cancel(true);"),
                "a timed-out swing must be cancelled");
        assertTrue(body.contains("mining.get(waitMs, TimeUnit.MILLISECONDS)"), "wait must be sized per block");
        assertTrue(body.contains("callOnServer(server,\n                () -> world.getBlockState(pos).getCollisionShape"),
                "the cell must be re-read on the server thread");
    }
}
