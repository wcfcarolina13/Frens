package net.wcfcarolina13.GameAI.services;

import java.util.Map;

/**
 * Pure format check for one alias entry of {@code mount_state.json}.
 *
 * <p>The pre-per-world format stored MountState fields directly under the alias
 * ({@code alias -> {mountUuid, worldId, ...}}). The current format nests them by save
 * world ({@code alias -> {worldKey -> MountState}}). Since 1.1.246 old-format entries
 * are skipped on load instead of being converted to a {@code _legacy} world entry.
 */
public final class MountStateFormatPolicy {

    private MountStateFormatPolicy() {
    }

    /** True when the alias entry holds MountState fields directly (pre-per-world format). */
    public static boolean isPreWorldFormat(Map<?, ?> aliasEntry) {
        return aliasEntry != null
                && (aliasEntry.containsKey("mountUuid") || aliasEntry.containsKey("worldId"));
    }
}
