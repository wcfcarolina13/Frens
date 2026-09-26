package net.wcfcarolina13.network;

import net.wcfcarolina13.FilingSystem.SharedConfig;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSaveReloadPolicyTest {
    @Test
    void denialIsLimitedToOnePerFiveSeconds() {
        assertTrue(ConfigSaveReloadPolicy.shouldNotifyDenial(1000, null));
        assertFalse(ConfigSaveReloadPolicy.shouldNotifyDenial(5999, 1000L));
        assertTrue(ConfigSaveReloadPolicy.shouldNotifyDenial(6000, 1000L));
    }

    @Test
    void snapshotFieldsReloadWhenEnabledOrDisabled() {
        SharedConfig before = new SharedConfig();
        SharedConfig after = new SharedConfig();
        before.soulsEnabled = false;
        after.soulsEnabled = false;
        before.soulVoiceEnabled = false;
        after.soulVoiceEnabled = false;
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        after.soulsEnabled = true;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(after, before));
        after.soulsEnabled = false;
        after.soulVoiceEnabled = true;
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        assertTrue(ConfigSaveReloadPolicy.needsSoulReload(after, before));
    }

    @Test
    void liveSoulAndDialogueFieldsDoNotReload() {
        assertLiveChangeDoesNotReload(c -> c.soulPartyEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulBanterEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulLocalChatEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulBanterActiveEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulMemoryDigestEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulNoveltyRejectionEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulRelationsEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulStructuredOutputEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.soulBanterIdleRate = 50);
        assertLiveChangeDoesNotReload(c -> c.soulBanterActiveRate = 50);
        assertLiveChangeDoesNotReload(c -> c.soulLocalRate = 50);
        assertLiveChangeDoesNotReload(c -> c.dialogueScriptedRate = 50);
        assertLiveChangeDoesNotReload(c -> c.textDialogueEnabled = true);
        assertLiveChangeDoesNotReload(c -> c.voicedDialogueEnabled = true);
    }

    @Test
    void unrelatedAndMissingSnapshotsDoNotReload() {
        assertLiveChangeDoesNotReload(c -> c.gameplayTipsEnabled = true);
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(null, new SharedConfig()));
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(new SharedConfig(), null));
    }

    private static void assertLiveChangeDoesNotReload(Consumer<SharedConfig> change) {
        SharedConfig before = new SharedConfig();
        SharedConfig after = new SharedConfig();
        change.accept(after);
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(before, after));
        assertFalse(ConfigSaveReloadPolicy.needsSoulReload(after, before));
    }
}
