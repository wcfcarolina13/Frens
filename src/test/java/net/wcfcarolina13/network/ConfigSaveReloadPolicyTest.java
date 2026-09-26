package net.wcfcarolina13.network;

import net.wcfcarolina13.FilingSystem.SharedConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSaveReloadPolicyTest {
    @Test
    void denialIsLimitedToOnePerFiveSeconds() {
        assertTrue(ConfigSaveReloadPolicy.shouldNotifyDenial(1000, null));
        assertFalse(ConfigSaveReloadPolicy.shouldNotifyDenial(5999, 1000L));
        assertTrue(ConfigSaveReloadPolicy.shouldNotifyDenial(6000, 1000L));
    }

    @Test
    void soulAndDialogueFieldsReloadButUnrelatedFieldsDoNot() {
        SharedConfig before = new SharedConfig();
        SharedConfig after = new SharedConfig();
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.gameplayTipsEnabled = true;
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.soulsEnabled = true;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.soulsEnabled = null;
        after.soulVoiceEnabled = true;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.soulVoiceEnabled = null;
        after.soulPartyEnabled = true;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.soulPartyEnabled = null;
        after.dialogueScriptedRate = 50;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
    }
}
