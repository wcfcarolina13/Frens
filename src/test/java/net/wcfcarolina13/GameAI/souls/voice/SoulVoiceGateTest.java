package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.GameAI.souls.SoulTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceGateTest {

    @Test
    void localVoicesPositionalRemoteVoicesRadio() {
        assertEquals(SoulVoiceGate.Mode.POSITIONAL,
                SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.LOCAL).orElseThrow());
        assertEquals(SoulVoiceGate.Mode.RADIO,
                SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.REMOTE).orElseThrow());
    }

    @Test
    void anyGateFactFalseOrUnreachableSkipsVoice() {
        assertTrue(SoulVoiceGate.decide(false, true, true, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, false, true, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, false, SoulTypes.Reachability.LOCAL).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, true, SoulTypes.Reachability.UNREACHABLE).isEmpty());
        assertTrue(SoulVoiceGate.decide(true, true, true, null).isEmpty());
    }

    @Test
    void backoffLadderEscalatesThenSelfDisablesInsideTheWindow() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();
        assertEquals(1_000L, policy.onFailure(0L));
        assertEquals(5_000L, policy.onFailure(10_000L));
        assertEquals(15_000L, policy.onFailure(20_000L));
        assertEquals(-1L, policy.onFailure(30_000L));
    }

    @Test
    void successAndWindowExpiryResetTheLadder() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();
        policy.onFailure(0L);
        policy.onSuccess();
        assertEquals(1_000L, policy.onFailure(1_000L));
        // A failure past the 5-minute window starts fresh.
        assertEquals(1_000L, policy.onFailure(1_000L + 300_001L));
    }
}
