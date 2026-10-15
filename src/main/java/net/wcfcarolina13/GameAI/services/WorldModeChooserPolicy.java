package net.wcfcarolina13.GameAI.services;

import java.util.Locale;

/**
 * What a world whose mode may still be unset does for a player who could choose it, given the
 * server's {@code questingModeEnabled} flag (1.1.235: Questing is hidden until its direction is
 * decided).
 *
 * <p>Flag on: today's chooser, exactly. Flag off: an unset world becomes Admin on its own, and a
 * world already saved as Questing keeps it. The flag only gates switching to Questing and
 * advertising it, never a world that already is Questing.
 */
public final class WorldModeChooserPolicy {

    public enum Decision {
        /** Mode already set, or this player cannot choose: nothing to do. */
        NONE,
        /** Open the World Mode chooser (Questing or Admin). */
        SHOW_CHOOSER,
        /** Questing is off: set the world to Admin, say so in chat, open the roster. */
        AUTO_ADMIN
    }

    public static final String QUESTING_OFF_LINE = "Questing mode is turned off on this server.";
    public static final String AUTO_ADMIN_LINE =
            "Frens is in Admin mode: open the roster with [-] to bring in a companion.";

    private WorldModeChooserPolicy() {
    }

    /**
     * @param questingModeEnabled the server flag
     * @param selectionDone       the world's {@code modeSelectionDone}
     * @param savedMode           the world's {@code selectedWorldMode} ("questing", "admin" or null)
     * @param canChoose           {@link WorldModeAccessPolicy#canChoose} for this player
     */
    public static Decision decide(boolean questingModeEnabled, boolean selectionDone, String savedMode, boolean canChoose) {
        if (!canChoose) {
            return Decision.NONE;
        }
        boolean unset = !selectionDone || savedMode == null || savedMode.isBlank();
        if (!unset) {
            return Decision.NONE;
        }
        return questingModeEnabled ? Decision.SHOW_CHOOSER : Decision.AUTO_ADMIN;
    }

    /**
     * The chat line after {@code /bot worldmode reset}, from the mode actually in effect once the
     * recruitment state was re-sent (legacy migration can restore Questing on a recruited world,
     * and with the flag off a joined op or host already set Admin). No em dashes or semicolons.
     *
     * @param questingModeEnabled the server flag
     * @param selectionDone       the world's {@code modeSelectionDone} after the re-send
     * @param savedMode           the world's {@code selectedWorldMode} after the re-send
     */
    public static String resetLine(boolean questingModeEnabled, boolean selectionDone, String savedMode) {
        String mode = savedMode == null ? "" : savedMode.trim().toLowerCase(Locale.ROOT);
        if (selectionDone && "questing".equals(mode)) {
            return "World mode reset, but this world already has Questing progress, so it stays in Questing mode.";
        }
        if (selectionDone && "admin".equals(mode)) {
            return questingModeEnabled
                    ? "World mode reset. The world is in Admin mode."
                    : "World mode reset. Questing mode is turned off on this server, so the world is in Admin mode.";
        }
        return questingModeEnabled
                ? "World mode reset. Press [-] or rejoin to reopen the mode selection screen."
                : "World mode reset. Questing mode is turned off on this server, so the world switches to Admin mode when an op or the host joins.";
    }

    /**
     * Whether a request to set the world to {@code requestedQuesting ? Questing : Admin} may go
     * ahead. Admin is always allowed. Questing needs the flag, unless the world already is
     * Questing (re-setting it changes nothing).
     */
    public static boolean allowsWorldMode(boolean questingModeEnabled, boolean requestedQuesting, String savedMode) {
        if (!requestedQuesting || questingModeEnabled) {
            return true;
        }
        return savedMode != null && "questing".equals(savedMode.trim().toLowerCase(Locale.ROOT));
    }
}
