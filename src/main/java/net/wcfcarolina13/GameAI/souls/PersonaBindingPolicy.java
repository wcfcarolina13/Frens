package net.wcfcarolina13.GameAI.souls;

/**
 * Pure "rebind or keep" rules for a bot's persona binding. A binding the player picked on purpose
 * (the AI setup persona picker, {@code SET_BOT_PERSONA}) survives re-enabling the bot and the
 * once-per-runtime legacy migration; a bot with no pick behaves as before the picker: every
 * enable binds the persona that matches its name, else Jake. No Minecraft types, so it is
 * unit-tested.
 */
public final class PersonaBindingPolicy {

    private PersonaBindingPolicy() {
    }

    /** What an enable binds: {@code keep} true means the existing binding stays untouched. */
    public record EnableBinding(String profileId, boolean keep) {
    }

    /**
     * The profile an enable binds. {@code picked}: the existing binding was picked on purpose and
     * is still the current one. A picked binding to a still-registered profile is kept; anything
     * else rebinds to {@code nameMatchedProfileId} (the name match, or the Jake fallback).
     */
    public static EnableBinding onEnable(boolean picked, String existingProfileId, boolean existingRegistered,
                                         String nameMatchedProfileId) {
        if (picked && existingProfileId != null && !existingProfileId.isBlank() && existingRegistered) {
            return new EnableBinding(existingProfileId, true);
        }
        return new EnableBinding(nameMatchedProfileId, false);
    }

    /**
     * Whether a stored pick still applies: it names the profile the bot is bound to now. A later
     * plain rebind (an enable of a bot with no pick, a command) leaves a stale pick behind; it
     * never counts.
     */
    public static boolean isPicked(String pickedProfileId, String currentProfileId) {
        return pickedProfileId != null && !pickedProfileId.isBlank() && pickedProfileId.equals(currentProfileId);
    }

    /**
     * {@code SoulRuntime.migrateLegacyBindings}: a bot still bound to {@code jakeProfileId} whose
     * name has a profile of its own is moved to it, unless the Jake binding was picked on purpose.
     */
    public static boolean shouldMigrateLegacy(boolean picked, String currentProfileId, String ownProfileId,
                                              String jakeProfileId) {
        return !picked && ownProfileId != null && !ownProfileId.isBlank()
                && currentProfileId != null && currentProfileId.equals(jakeProfileId)
                && !ownProfileId.equals(currentProfileId);
    }
}
