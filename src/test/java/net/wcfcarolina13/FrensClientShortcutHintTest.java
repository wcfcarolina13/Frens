package net.wcfcarolina13;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrensClientShortcutHintTest {

    @Test
    void formatsDirectCompositeAndUnboundShortcuts() {
        assertEquals("[`]", FrensClient.formatCompanionShortcutInstruction("`", null, null));
        assertEquals("[R]", FrensClient.formatCompanionShortcutInstruction("R", "\\", 2));
        assertEquals("Hold [\\], then [4]", FrensClient.formatCompanionShortcutInstruction(null, "\\", 4));
        assertEquals(
                "Unbound — configure in Controls",
                FrensClient.formatCompanionShortcutInstruction(null, null, null)
        );
    }

    @Test
    void mapsOverlayActionsToTheirActualNumberKeys() {
        assertEquals(2, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.RESUME));
        assertEquals(3, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.SPELLS));
        assertEquals(4, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.HOME));
        assertEquals(5, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.SLEEP));
        assertEquals(6, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.REGROUP));
        assertEquals(7, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.STRIPMINE));
        assertEquals(8, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.ASCENT));
        assertEquals(9, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.DESCENT));
        assertEquals(0, FrensClient.overlaySlotFor(FrensClient.CompanionShortcut.CLEANUP));
    }
}
