package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoulSetupScreenPolicyTest {
    @Test
    void modeUsesIntegratedServerAndAuthoritativePermission() {
        for (boolean available : new boolean[] {false, true}) {
            for (boolean editor : new boolean[] {false, true}) {
                assertEquals(SoulSetupScreenPolicy.Mode.LOCAL,
                        SoulSetupScreenPolicy.mode(true, available, editor));
            }
        }
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_UNKNOWN, SoulSetupScreenPolicy.mode(false, false, true));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_VIEWER, SoulSetupScreenPolicy.mode(false, true, false));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR, SoulSetupScreenPolicy.mode(false, true, true));
    }

    @Test
    void modelButtonCoversEveryModeAndBooleanCombination() {
        for (SoulSetupScreenPolicy.Mode mode : SoulSetupScreenPolicy.Mode.values()) {
            for (boolean installed : new boolean[] {false, true}) {
                for (boolean selected : new boolean[] {false, true}) {
                    for (boolean downloading : new boolean[] {false, true}) {
                        var button = SoulSetupScreenPolicy.modelButton(mode, installed, selected, downloading);
                        assertEquals(!installed ? SoulSetupScreenPolicy.ModelLabel.DOWNLOAD
                                : selected ? SoulSetupScreenPolicy.ModelLabel.SELECTED
                                : SoulSetupScreenPolicy.ModelLabel.USE, button.label());
                        assertEquals(!downloading && !(installed && selected)
                                && (mode == SoulSetupScreenPolicy.Mode.LOCAL
                                || mode == SoulSetupScreenPolicy.Mode.REMOTE_EDITOR && installed), button.enabled());
                    }
                }
            }
        }
        assertTrue(SoulSetupScreenPolicy.modelButton(SoulSetupScreenPolicy.Mode.LOCAL,
                false, true, false).enabled());
        assertFalse(SoulSetupScreenPolicy.modelButton(SoulSetupScreenPolicy.Mode.LOCAL,
                false, true, true).enabled());
    }
}
