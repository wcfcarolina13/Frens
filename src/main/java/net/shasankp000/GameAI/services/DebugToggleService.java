package net.shasankp000.GameAI.services;

import org.slf4j.Logger;

public final class DebugToggleService {

    private static final String ENV_VERBOSE = "AI_PLAYER_DEBUG";
    private static final String PROP_VERBOSE = "aiplayer.debug";
    private static final String PROP_VERBOSE_ALT = "ai-player.debug";

    private DebugToggleService() {}

    public static boolean verbose() {
        String env = System.getenv(ENV_VERBOSE);
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env.trim());
        }
        String prop = System.getProperty(PROP_VERBOSE);
        if (prop == null || prop.isBlank()) {
            prop = System.getProperty(PROP_VERBOSE_ALT);
        }
        return prop != null && Boolean.parseBoolean(prop.trim());
    }

    /**
     * Logs a debug message even when the runtime logger is configured without DEBUG level.
     * Enable via env `AI_PLAYER_DEBUG=true` or system property `aiplayer.debug=true`.
     */
    public static void debug(Logger logger, String message, Object... args) {
        if (logger == null || message == null) {
            return;
        }
        if (!verbose()) {
            return;
        }
        logger.info("[DBG] " + message, args);
    }
}
