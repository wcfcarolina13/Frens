package net.wcfcarolina13.network;

import net.wcfcarolina13.network.SkinChangePolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkinChangePolicyTest {

    @Test
    void adminsMaySetAnySkinOnAnyBot() {
        for (boolean custom : new boolean[]{false, true}) {
            assertEquals(Decision.ALLOW, SkinChangePolicy.decide(true, false, false, custom, true));
            // The integrated-server host is an admin too.
            assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, true, false, custom, true));
        }
    }

    @Test
    void ownerSetsPresetsAlwaysAndCustomOnlyWhenAllowed() {
        // The owner may always reskin their own bot with presets (D4).
        assertEquals(Decision.PRESET_ONLY, SkinChangePolicy.decide(false, false, true, false, false));
        assertEquals(Decision.PRESET_ONLY, SkinChangePolicy.decide(false, false, true, false, true));
        assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, false, true, true, true));
        assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, false, true, true, false));
    }

    @Test
    void nonOwnerIsRefused() {
        // The security rule: nobody reskins another player's bot.
        for (boolean custom : new boolean[]{false, true}) {
            for (boolean wantsUrl : new boolean[]{false, true}) {
                assertEquals(Decision.DENY,
                        SkinChangePolicy.decide(false, false, false, custom, wantsUrl));
            }
        }
    }

    @Test
    void denyMessageHasNoSemicolonOrEmDash() {
        assertFalse(SkinChangePolicy.DENY_MESSAGE.contains(";"));
        assertFalse(SkinChangePolicy.DENY_MESSAGE.contains("—"));
    }
}
