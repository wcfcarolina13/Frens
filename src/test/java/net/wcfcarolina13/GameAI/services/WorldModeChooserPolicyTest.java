package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.WorldModeChooserPolicy.Decision;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.FilingSystem.SharedConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModeChooserPolicyTest {

    @Test
    void flagOnUnsetWorldShowsChooserExactlyAsBefore() {
        assertEquals(Decision.SHOW_CHOOSER, WorldModeChooserPolicy.decide(true, false, null, true));
        // Post-reset state: done=false, mode=null. Also done=true with a null mode.
        assertEquals(Decision.SHOW_CHOOSER, WorldModeChooserPolicy.decide(true, true, null, true));
        assertEquals(Decision.SHOW_CHOOSER, WorldModeChooserPolicy.decide(true, false, "admin", true));
    }

    @Test
    void flagOffUnsetWorldBecomesAdmin() {
        assertEquals(Decision.AUTO_ADMIN, WorldModeChooserPolicy.decide(false, false, null, true));
        assertEquals(Decision.AUTO_ADMIN, WorldModeChooserPolicy.decide(false, true, null, true));
        assertEquals(Decision.AUTO_ADMIN, WorldModeChooserPolicy.decide(false, true, "  ", true));
    }

    @Test
    void savedModeIsNeverTouched() {
        for (boolean flag : new boolean[] {true, false}) {
            assertEquals(Decision.NONE, WorldModeChooserPolicy.decide(flag, true, "questing", true));
            assertEquals(Decision.NONE, WorldModeChooserPolicy.decide(flag, true, "admin", true));
        }
    }

    @Test
    void playerWhoCannotChooseGetsNothing() {
        for (boolean flag : new boolean[] {true, false}) {
            assertEquals(Decision.NONE, WorldModeChooserPolicy.decide(flag, false, null, false));
        }
    }

    @Test
    void adminIsAlwaysAllowed() {
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(false, false, null));
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(false, false, "questing"));
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(true, false, "admin"));
    }

    @Test
    void questingNeedsTheFlagUnlessAlreadyQuesting() {
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(true, true, null));
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(true, true, "admin"));
        assertFalse(WorldModeChooserPolicy.allowsWorldMode(false, true, null));
        assertFalse(WorldModeChooserPolicy.allowsWorldMode(false, true, "admin"));
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(false, true, "questing"));
        assertTrue(WorldModeChooserPolicy.allowsWorldMode(false, true, " Questing "));
    }

    @Test
    void linesHaveNoEmDashOrSemicolon() {
        for (String line : new String[] {WorldModeChooserPolicy.QUESTING_OFF_LINE, WorldModeChooserPolicy.AUTO_ADMIN_LINE}) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains(";"), line);
        }
    }

    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void flagDefaultsOffAndAnOldSettingsFileReadsOff() throws Exception {
        assertFalse(newRealConfig().isQuestingModeEnabled());
        ManualConfig fromOldFile = new com.google.gson.Gson().fromJson("{}", ManualConfig.class);
        assertFalse(fromOldFile.isQuestingModeEnabled());
    }

    @Test
    void flagTravelsInTheServerConfigSync() throws Exception {
        ManualConfig server = newRealConfig();
        server.setQuestingModeEnabled(true);
        ManualConfig client = newRealConfig();
        SharedConfig.capture(server).applyTo(client);
        assertTrue(client.isQuestingModeEnabled());
    }
}
