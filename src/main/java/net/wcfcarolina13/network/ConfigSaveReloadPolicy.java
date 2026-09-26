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
        // SoulRuntime.reloadSettings snapshots SoulSettings.from and SoulVoiceSettings.from.
        // Only these two SharedConfig fields feed those snapshots; live suppliers need no reload,
        // which would cancel active and queued generations when replacing the pipeline.
        return !Objects.equals(a.soulsEnabled, b.soulsEnabled)
                || !Objects.equals(a.soulVoiceEnabled, b.soulVoiceEnabled);
    }
}
