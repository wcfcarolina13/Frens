package net.wcfcarolina13.ChatUtils;

import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

/**
 * Single decision point for whether a scripted text line (chat, overhead hologram,
 * subtitle) may be shown.
 *
 * <p>Semantics (Bradley's ruling 2026-08-25): the global Text Chat master ON shows
 * everything; OFF hides text EXCEPT categories the user checked as keep-visible
 * exceptions in the Text Chat "Adv…" menu (danger warnings and status lines by
 * default). Soul Chat replies bypass this entirely — see {@code SoulMessageDelivery}.
 */
public final class TextLineVisibilityService {

    private TextLineVisibilityService() {
    }

    /** @return true if text of this category should be shown right now. */
    public static boolean isTextAllowed(VoiceLineCategory category) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null || cfg.isTextDialogueEnabled()) {
            return true;
        }
        return category != null && cfg.isTextCategoryException(category.id());
    }
}
