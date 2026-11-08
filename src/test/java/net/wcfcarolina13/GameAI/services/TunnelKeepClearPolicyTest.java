package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelKeepClearPolicyTest {

    private static final int D = TunnelKeepClearPolicy.DEFAULT_DEPTH;

    @Test
    void theDugCellAheadIsKeptClear() {
        // PR #70 sandbox: bot at 6400,120,1700 tunnelling east put its table at 6401,120,1700
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6401, 120, 1700, D));
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6401, 121, 1700, D));
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6402, 120, 1700, D));
    }

    @Test
    void cellsBehindBesideOrBeyondTheDepthAreFree() {
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6399, 120, 1700, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6401, 120, 1701, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6403, 120, 1700, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6401, 122, 1700, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6401, 119, 1700, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(6400, 120, 1700, 1, 0, 6400, 120, 1700, D));
    }

    @Test
    void everyHeadingWorks() {
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, -1, 0, -1, 64, 0, D));
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 0, 1, 0, 65, 2, D));
        assertTrue(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 0, -1, 0, 64, -1, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 0, -1, 0, 64, 1, D));
    }

    @Test
    void noHeadingKeepsNothing() {
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 0, 0, 1, 64, 0, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 1, 1, 1, 64, 1, D));
        assertFalse(TunnelKeepClearPolicy.blocksTunnel(0, 64, 0, 1, 0, 1, 64, 0, 0));
    }

    @Test
    void serviceAppliesOnlyWhileAHeadingIsSet() {
        UUID bot = UUID.randomUUID();
        assertFalse(TunnelKeepClearService.blocks(bot, 0, 64, 0, 1, 64, 0));
        TunnelKeepClearService.set(bot, 1, 0);
        assertTrue(TunnelKeepClearService.blocks(bot, 0, 64, 0, 1, 64, 0));
        // the bot moved one step: the kept cells move with it
        assertFalse(TunnelKeepClearService.blocks(bot, 1, 64, 0, 0, 64, 0));
        assertTrue(TunnelKeepClearService.blocks(bot, 1, 64, 0, 3, 65, 0));
        TunnelKeepClearService.clear(bot);
        assertFalse(TunnelKeepClearService.blocks(bot, 0, 64, 0, 1, 64, 0));
    }
}
