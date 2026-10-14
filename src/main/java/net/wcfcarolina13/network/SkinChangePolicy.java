package net.wcfcarolina13.network;

/**
 * Minecraft-free decision for a bot skin change request ({@link BotSkinPayload}).
 *
 * <ul>
 *   <li>Server admins (operator or integrated-server host) may set any skin on any bot.</li>
 *   <li>The bot's owner may always set a preset skin on their own bot. A custom URL skin needs
 *       {@code allowCustomSkins}; without it the request falls back to the safe preset.</li>
 *   <li>Anyone else is refused, including when {@code allowEveryoneSkinChange} is on: the owner
 *       check is the security rule, so nobody reskins another player's bot.</li>
 * </ul>
 *
 * <p>{@code allowEveryone} stays an input so the call site documents the config it reads, but
 * under these rules it no longer changes the outcome.
 */
public final class SkinChangePolicy {

    private SkinChangePolicy() {}

    public enum Decision {
        /** Refused: tell the player and change nothing. */
        DENY,
        /** Presets only: a custom URL request is replaced with the safe preset. */
        PRESET_ONLY,
        /** Any skin, custom URLs included. */
        ALLOW
    }

    public static Decision decide(boolean isOp, boolean isHost, boolean isOwner,
                                  boolean allowEveryone, boolean allowCustom, boolean wantsCustomUrl) {
        if (isOp || isHost) {
            return Decision.ALLOW;
        }
        if (!isOwner) {
            return Decision.DENY;
        }
        // An owner's custom URL needs allowCustomSkins. wantsCustomUrl does not change the
        // decision (PRESET_ONLY already lets a preset through), it is here so the caller passes
        // the whole request.
        return allowCustom ? Decision.ALLOW : Decision.PRESET_ONLY;
    }

    /** Short chat line for a refused request (no em dashes, no semicolons). */
    public static final String DENY_MESSAGE = "You can only change the skin of your own bot.";
}
