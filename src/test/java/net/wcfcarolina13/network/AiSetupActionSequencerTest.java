package net.wcfcarolina13.network;

import net.wcfcarolina13.network.AiSetupActionPolicy.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AiSetupNetworkManager.ActionSequencer}: an older async completion never beats a newer action. */
class AiSetupActionSequencerTest {

    private static final String BOB = "00000000-0000-0000-0000-000000000001";
    private static final String AMY = "00000000-0000-0000-0000-000000000002";

    @Test
    void kindsGroupWhatSupersedesWhat() {
        assertEquals(AiSetupNetworkManager.ActionSequencer.kindOf(Action.SOULS_ON, ""),
                AiSetupNetworkManager.ActionSequencer.kindOf(Action.SOULS_OFF, ""), "on and off are one switch");
        assertEquals("MODEL", AiSetupNetworkManager.ActionSequencer.kindOf(Action.SELECT_MODEL, "llama3.2:3b"));
        assertEquals("MODEL", AiSetupNetworkManager.ActionSequencer.kindOf(Action.SELECT_MODEL, "qwen2.5:7b"));
        assertNotEquals(AiSetupNetworkManager.ActionSequencer.kindOf(Action.ENABLE_BOT, BOB),
                AiSetupNetworkManager.ActionSequencer.kindOf(Action.ENABLE_BOT, AMY), "per bot");
        assertEquals(AiSetupNetworkManager.ActionSequencer.kindOf(Action.ENABLE_BOT, BOB),
                AiSetupNetworkManager.ActionSequencer.kindOf(Action.ENABLE_BOT, BOB.toUpperCase()));
        assertEquals("", AiSetupNetworkManager.ActionSequencer.kindOf(null, ""));
    }

    @Test
    void newerActionOfTheSameKindSupersedesOlder() {
        AiSetupNetworkManager.ActionSequencer seq = new AiSetupNetworkManager.ActionSequencer();
        long first = seq.accept("MODEL");
        assertTrue(seq.isLatest("MODEL", first));
        long second = seq.accept("MODEL");
        assertFalse(seq.isLatest("MODEL", first), "the slow probe for the first pick must not apply");
        assertTrue(seq.isLatest("MODEL", second));
    }

    @Test
    void otherKindsDoNotSupersede() {
        AiSetupNetworkManager.ActionSequencer seq = new AiSetupNetworkManager.ActionSequencer();
        long model = seq.accept("MODEL");
        seq.accept("SOULS");
        seq.accept("VOICE_ENGINE");
        assertTrue(seq.isLatest("MODEL", model));
    }

    @Test
    void unknownOrClearedKindsAreNeverLatest() {
        AiSetupNetworkManager.ActionSequencer seq = new AiSetupNetworkManager.ActionSequencer();
        assertFalse(seq.isLatest("MODEL", 1));
        long s = seq.accept("MODEL");
        seq.clear();
        assertFalse(seq.isLatest("MODEL", s), "a server restart drops every pending completion");
        assertTrue(seq.accept("MODEL") > s, "sequence stays monotonic across clear");
    }
}
