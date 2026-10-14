package net.wcfcarolina13.network;

import net.wcfcarolina13.network.BaseAccessPolicy.EntryKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BASE rule {@code BotHomeService.canEditBase} (base resize) now delegates to: the integrated
 * host edits any base like an operator, SERVER-owned and legacy (blank owner) bases stay admin-only.
 */
class BaseEditHostPolicyTest {
    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String SERVER = "SERVER";

    @Test
    void hostEditsServerOwnedLegacyAndOthersBases() {
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, SERVER, false, true));
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, null, false, true));
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, "", false, true));
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, OTHER, false, true));
    }

    @Test
    void operatorMatchesHost() {
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, SERVER, true, false));
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, null, true, false));
    }

    @Test
    void plainPlayerEditsOnlyOwnStampedBase() {
        assertTrue(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, ME, false, false));
        assertFalse(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, OTHER, false, false));
        assertFalse(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, SERVER, false, false));
        assertFalse(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, null, false, false));
        assertFalse(BaseAccessPolicy.canEdit(EntryKind.BASE, ME, " ", false, false));
    }
}
