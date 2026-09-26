package net.wcfcarolina13.network;

import net.wcfcarolina13.FilingSystem.SharedConfig;
import java.util.Objects;

/** Pure decisions for shared-config saves. */
public final class ConfigSaveReloadPolicy {
    public static final long DENIAL_INTERVAL_MS = 5_000L;
    private ConfigSaveReloadPolicy() {}

    public static boolean shouldNotifyDenial(long nowMs, Long previousMs) {
        return previousMs == null || nowMs - previousMs >= DENIAL_INTERVAL_MS;
    }

    public static boolean needsSoulReload(SharedConfig a, SharedConfig b) {
        if (a == null || b == null) return false;
        return !Objects.equals(a.soulsEnabled, b.soulsEnabled)
                || !Objects.equals(a.soulVoiceEnabled, b.soulVoiceEnabled)
                || !Objects.equals(a.soulPartyEnabled, b.soulPartyEnabled)
                || !Objects.equals(a.soulBanterEnabled, b.soulBanterEnabled)
                || !Objects.equals(a.soulLocalChatEnabled, b.soulLocalChatEnabled)
                || !Objects.equals(a.soulBanterActiveEnabled, b.soulBanterActiveEnabled)
                || !Objects.equals(a.soulMemoryDigestEnabled, b.soulMemoryDigestEnabled)
                || !Objects.equals(a.soulNoveltyRejectionEnabled, b.soulNoveltyRejectionEnabled)
                || !Objects.equals(a.soulRelationsEnabled, b.soulRelationsEnabled)
                || !Objects.equals(a.soulStructuredOutputEnabled, b.soulStructuredOutputEnabled)
                || !Objects.equals(a.soulBanterIdleRate, b.soulBanterIdleRate)
                || !Objects.equals(a.soulBanterActiveRate, b.soulBanterActiveRate)
                || !Objects.equals(a.soulLocalRate, b.soulLocalRate)
                || !Objects.equals(a.dialogueScriptedRate, b.dialogueScriptedRate)
                || !Objects.equals(a.textDialogueEnabled, b.textDialogueEnabled)
                || !Objects.equals(a.voicedDialogueEnabled, b.voicedDialogueEnabled);
    }
}
