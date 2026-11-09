package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Rebind or keep": a persona picked on purpose survives re-enable and the legacy migration. */
class PersonaBindingPolicyTest {

    private static final String JAKE = "frens:jake";
    private static final String BOB = "frens:bob";
    private static final String SILAS = "frens:silas";

    @Test
    void enableKeepsAPickedRegisteredBinding() {
        PersonaBindingPolicy.EnableBinding b = PersonaBindingPolicy.onEnable(true, SILAS, true, BOB);
        assertTrue(b.keep());
        assertEquals(SILAS, b.profileId());
    }

    @Test
    void enableWithoutAPickBindsTheNameMatchAsToday() {
        PersonaBindingPolicy.EnableBinding b = PersonaBindingPolicy.onEnable(false, SILAS, true, BOB);
        assertFalse(b.keep());
        assertEquals(BOB, b.profileId());
        assertEquals(JAKE, PersonaBindingPolicy.onEnable(false, "", false, JAKE).profileId(), "fresh bot, Jake fallback");
    }

    @Test
    void aPickToAProfileThatIsGoneOrBlankRebinds() {
        assertFalse(PersonaBindingPolicy.onEnable(true, SILAS, false, BOB).keep(), "profile no longer registered");
        assertEquals(BOB, PersonaBindingPolicy.onEnable(true, SILAS, false, BOB).profileId());
        assertFalse(PersonaBindingPolicy.onEnable(true, "", true, BOB).keep());
        assertFalse(PersonaBindingPolicy.onEnable(true, null, true, BOB).keep());
    }

    @Test
    void aPickOnlyCountsWhileItIsTheCurrentBinding() {
        assertTrue(PersonaBindingPolicy.isPicked(SILAS, SILAS));
        assertFalse(PersonaBindingPolicy.isPicked(SILAS, BOB), "a later plain rebind leaves a stale pick");
        assertFalse(PersonaBindingPolicy.isPicked(null, SILAS));
        assertFalse(PersonaBindingPolicy.isPicked("", ""));
    }

    @Test
    void migrationSkipsAPickedJakeBinding() {
        assertTrue(PersonaBindingPolicy.shouldMigrateLegacy(false, JAKE, BOB, JAKE), "legacy Bob-on-Jake moves");
        assertFalse(PersonaBindingPolicy.shouldMigrateLegacy(true, JAKE, BOB, JAKE), "Jake picked for Bob stays");
        assertFalse(PersonaBindingPolicy.shouldMigrateLegacy(false, SILAS, BOB, JAKE), "only Jake bindings migrate");
        assertFalse(PersonaBindingPolicy.shouldMigrateLegacy(false, JAKE, null, JAKE), "no profile of its own");
        assertFalse(PersonaBindingPolicy.shouldMigrateLegacy(false, JAKE, JAKE, JAKE), "Jake himself");
    }
}
