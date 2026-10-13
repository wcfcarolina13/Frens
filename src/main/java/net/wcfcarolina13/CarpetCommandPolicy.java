package net.wcfcarolina13;

/**
 * Decides whether a command string that targets Carpet's {@code /player} command may be dispatched.
 *
 * <p>Several legacy code paths drive fake players through Carpet ({@code player <bot> stop},
 * {@code player <bot> move forward}, {@code player <bot> turn left}). Frens does not depend on
 * Carpet, so without it every such command fails with "Unknown or incomplete command", which a
 * player sees in red when the source is theirs. {@link CommandUtils#run} consults this policy and
 * skips the dispatch instead. Pure so it can be tested without Minecraft types.
 */
public final class CarpetCommandPolicy {

    private static final String PLAYER_PREFIX = "player ";

    private CarpetCommandPolicy() {
    }

    /** True when the command, ignoring surrounding whitespace and one leading slash, is a Carpet {@code /player} command. */
    public static boolean isCarpetPlayerCommand(String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        String command = rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        // Brigadier literals are case-sensitive, so only the exact lowercase literal names Carpet's command.
        return command.startsWith(PLAYER_PREFIX);
    }

    /** False for a null/blank command, and for a Carpet {@code /player} command while Carpet is not loaded. */
    public static boolean shouldDispatch(String rawCommand, boolean carpetLoaded) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }
        return carpetLoaded || !isCarpetPlayerCommand(rawCommand);
    }
}
