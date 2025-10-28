package net.shasankp000;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Helper utilities for running Minecraft commands from server-side code.
 */
public final class CommandUtils {

    private CommandUtils() {
    }

    /**
     * Executes a command string on the provided {@link ServerCommandSource}.
     * Leading slashes are ignored to mirror the legacy executeWithPrefix behaviour.
     */
    public static void run(ServerCommandSource source, String rawCommand) {
        if (source == null) {
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        server.getCommandManager().parseAndExecute(source, command);
    }
}
