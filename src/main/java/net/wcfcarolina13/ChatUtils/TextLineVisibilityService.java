package net.wcfcarolina13.ChatUtils;

import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

/**
 * Single decision point for whether a scripted text line (chat, overhead hologram,
 * subtitle) may be shown.
 *
 * <p>Same rule as the voice mask, deliberately: the Scripted Text master is a hard kill
 * switch; while it is ON, categories muted in the Text "Adv…" menu are hidden. (An
 * earlier iteration used inverse "keep-visible when master off" semantics — Bradley
 * flagged the two Adv menus as conflicting duals, so both now share this one model.)
 * Soul Chat replies bypass this entirely — see {@code SoulMessageDelivery}.
 */
public final class TextLineVisibilityService {

    private TextLineVisibilityService() {
    }

    /** @return true if text of this category should be shown right now. */
    public static boolean isTextAllowed(VoiceLineCategory category) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return true;
        }
        if (!cfg.isTextDialogueEnabled()) {
            return false;
        }
        return category == null || !cfg.isTextCategoryMuted(category.id());
    }
}
