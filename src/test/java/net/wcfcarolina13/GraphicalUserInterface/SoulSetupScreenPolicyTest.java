package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoulSetupScreenPolicyTest {
    @Test
    void modeUsesIntegratedServerAndAuthoritativePermission() {
        for (boolean available : new boolean[] {false, true}) {
            for (boolean editor : new boolean[] {false, true}) {
                assertEquals(SoulSetupScreenPolicy.Mode.LOCAL,
                        SoulSetupScreenPolicy.mode(true, available, available, editor, 5_000));
            }
        }
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_VIEWER,
                SoulSetupScreenPolicy.mode(false, true, true, false, 5_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR,
                SoulSetupScreenPolicy.mode(false, true, true, true, 5_000));
    }

    @Test
    void unsupportedSurfaceTakesPrecedenceOverCachedStatusAndPermission() {
        for (boolean status : new boolean[] {false, true}) {
            for (boolean editor : new boolean[] {false, true}) {
                assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_UNSUPPORTED,
                        SoulSetupScreenPolicy.mode(false, false, status, editor, 10_000));
            }
        }
    }

    @Test
    void waitingTimesOutAtFiveSecondsAndRefreshRestartsWaiting() {
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_WAITING,
                SoulSetupScreenPolicy.mode(false, true, false, false, 4_999));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT,
                SoulSetupScreenPolicy.mode(false, true, false, false, 5_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT,
                SoulSetupScreenPolicy.mode(false, true, false, true, 10_000));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_WAITING,
                SoulSetupScreenPolicy.mode(false, true, false, true, 0));
        assertEquals(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR,
                SoulSetupScreenPolicy.mode(false, true, true, true, 10_000));
    }

    @Test
    void remoteStatesExplainWhyControlsAreDisabled() {
        assertEquals("This server's Frens version can't be set up from here — ask the host to update Frens.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_UNSUPPORTED));
        assertEquals("Asking the server…",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_WAITING));
        assertEquals("The server didn't answer — try Refresh.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_TIMEOUT));
        assertEquals("Only the server operator or host can change this.",
                SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_VIEWER));
        assertEquals("", SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.LOCAL));
        assertEquals("", SoulSetupScreenPolicy.statusMessage(SoulSetupScreenPolicy.Mode.REMOTE_EDITOR));
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
