package net.wcfcarolina13.ChatUtils;

import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

/**
 * Single decision point for whether a voiced-dialogue category is muted.
 *
 * <p>Today this is global-only: the mask lives in settings.json5
 * ({@link ManualConfig}) and applies to every listener. The {@code viewer}
 * parameter is the multiplayer seam — per-player masks (synced from each
 * client) can be consulted here later without touching any call site.
 */
public final class VoiceLineMuteService {

    private VoiceLineMuteService() {
    }

    /**
     * @param category the resolved category of the line about to play
     * @param viewer   the player who would hear the line; currently ignored
     *                 (global mask only), reserved for per-player muting
     * @return true if playback of this category should be skipped
     */
    public static boolean isMuted(VoiceLineCategory category, ServerPlayerEntity viewer) {
        if (category == null) {
            return false;
        }
        ManualConfig cfg = Frens.CONFIG;
        return cfg != null && cfg.isVoiceCategoryMuted(category.id());
    }
}
