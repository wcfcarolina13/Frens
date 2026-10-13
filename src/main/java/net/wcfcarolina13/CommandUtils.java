package net.wcfcarolina13;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper utilities for running Minecraft commands from server-side code.
 */
public final class CommandUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-command-utils");
    private static final AtomicBoolean CARPET_SKIP_LOGGED = new AtomicBoolean(false);

    private CommandUtils() {
    }

    /** True when the Carpet mod is loaded, which is what provides the {@code /player} command. */
    public static boolean isCarpetLoaded() {
        return FabricLoader.getInstance().isModLoaded("carpet");
    }

    /**
     * Executes a command string on the provided {@link ServerCommandSource}.
     * Leading slashes are ignored to mirror the legacy executeWithPrefix behaviour.
     * Carpet {@code /player} commands are skipped when Carpet is not installed
     * (see {@link CarpetCommandPolicy}).
     */
    public static void run(ServerCommandSource source, String rawCommand) {
        if (source == null || rawCommand == null) {
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        if (!CarpetCommandPolicy.shouldDispatch(rawCommand, isCarpetLoaded())) {
            if (CarpetCommandPolicy.isCarpetPlayerCommand(rawCommand)
                    && CARPET_SKIP_LOGGED.compareAndSet(false, true)) {
                LOGGER.debug("Skipping Carpet /player command because Carpet is not loaded: '{}' (logged once)", rawCommand);
            }
            return;
        }
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        server.getCommandManager().parseAndExecute(source, command);
    }
}
