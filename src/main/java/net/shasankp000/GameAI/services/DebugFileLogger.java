package net.shasankp000.GameAI.services;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Simple file logger for debugging when stdout/log4j is unreliable.
 */
public final class DebugFileLogger {
    private static final String FILE_NAME = "skill_debug.log";
    private static final Object LOCK = new Object();

    private DebugFileLogger() {
    }

    public static void log(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            try {
                Path file = FabricLoader.getInstance().getConfigDir()
                        .resolve("ai-player")
                        .resolve(FILE_NAME);
                Files.createDirectories(file.getParent());
                String line = Instant.now() + " " + message + System.lineSeparator();
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        }
    }
}
