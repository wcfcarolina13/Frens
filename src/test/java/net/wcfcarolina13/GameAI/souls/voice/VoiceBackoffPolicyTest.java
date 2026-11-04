package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceBackoffPolicyTest {

    @Test
    void failuresFollowDelayLadderThenDisable() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();

        assertEquals(1_000L, policy.onFailure(10_000L));
        assertEquals(5_000L, policy.onFailure(10_001L));
        assertEquals(15_000L, policy.onFailure(10_002L));
        assertEquals(-1L, policy.onFailure(10_003L));
    }

    @Test
    void failureAfterWindowResetsTheLadder() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();

        assertEquals(1_000L, policy.onFailure(10_000L));
        assertEquals(5_000L, policy.onFailure(10_001L));
        assertEquals(1_000L, policy.onFailure(310_001L));
    }

    @Test
    void failureAtWindowBoundaryStaysInTheCurrentLadder() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();

        assertEquals(1_000L, policy.onFailure(10_000L));
        assertEquals(5_000L, policy.onFailure(310_000L));
    }

    @Test
    void successResetsTheLadder() {
        VoiceBackoffPolicy policy = new VoiceBackoffPolicy();

        assertEquals(1_000L, policy.onFailure(10_000L));
        assertEquals(5_000L, policy.onFailure(10_001L));
        policy.onSuccess();
        assertEquals(1_000L, policy.onFailure(10_002L));
    }
}
