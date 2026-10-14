package net.wcfcarolina13.network;

import net.wcfcarolina13.network.SkinChangePolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkinChangePolicyTest {

    @Test
    void adminsMaySetAnySkinOnAnyBot() {
        for (boolean everyone : new boolean[]{false, true}) {
            for (boolean custom : new boolean[]{false, true}) {
                assertEquals(Decision.ALLOW, SkinChangePolicy.decide(true, false, false, everyone, custom, true));
                // The integrated-server host is an admin too.
                assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, true, false, everyone, custom, true));
            }
        }
    }

    @Test
    void ownerSetsPresetsAlwaysAndCustomOnlyWhenAllowed() {
        // allowEveryone off: the owner may still reskin their own bot with presets (D4).
        assertEquals(Decision.PRESET_ONLY, SkinChangePolicy.decide(false, false, true, false, false, false));
        assertEquals(Decision.PRESET_ONLY, SkinChangePolicy.decide(false, false, true, false, false, true));
        assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, false, true, false, true, true));
        assertEquals(Decision.PRESET_ONLY, SkinChangePolicy.decide(false, false, true, true, false, true));
        assertEquals(Decision.ALLOW, SkinChangePolicy.decide(false, false, true, true, true, false));
    }

    @Test
    void nonOwnerIsRefusedEvenWhenEveryoneMayChangeSkins() {
        // The security fix: allowEveryone on used to let anyone reskin any bot.
        for (boolean everyone : new boolean[]{false, true}) {
            for (boolean custom : new boolean[]{false, true}) {
                for (boolean wantsUrl : new boolean[]{false, true}) {
                    assertEquals(Decision.DENY,
                            SkinChangePolicy.decide(false, false, false, everyone, custom, wantsUrl));
                }
            }
        }
    }

    @Test
    void denyMessageHasNoSemicolonOrEmDash() {
        assertFalse(SkinChangePolicy.DENY_MESSAGE.contains(";"));
        assertFalse(SkinChangePolicy.DENY_MESSAGE.contains("—"));
    }
}
