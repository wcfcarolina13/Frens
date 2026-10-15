package net.wcfcarolina13.LauncherDetection;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Detects the current launcher environment and provides appropriate paths
 */
public class LauncherEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger("frens");

    public enum LauncherType {
        VANILLA_LAUNCHER("Vanilla Minecraft Launcher"),
        MODRINTH_APP("Modrinth App"),
        MULTIMC("MultiMC"),
        PRISM_LAUNCHER("Prism Launcher"),
        CURSEFORGE("CurseForge"),
        ATLAUNCHER("ATLauncher"),
        UNKNOWN("Unknown Launcher");

        private final String displayName;

        LauncherType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static LauncherType detectedLauncher = null;
    private static String detectedGameDir = null;

    /**
     * Detect the current launcher environment
     */
    public static LauncherType detectLauncher() {
        if (detectedLauncher != null) {
            return detectedLauncher; // Cache result
        }

        String gameDir = FabricLoader.getInstance().getGameDir().toString();
        String userHome = System.getProperty("user.home");
        String osName = System.getProperty("os.name").toLowerCase();

        LOGGER.info("Detecting launcher environment...");
        LOGGER.info("Game directory: {}", gameDir);
        LOGGER.info("User home: {}", userHome);
        LOGGER.info("OS: {}", osName);

        // Normalize paths for comparison
        String normalizedGameDir = gameDir.replace("\\", "/").toLowerCase();
        String normalizedUserHome = userHome.replace("\\", "/").toLowerCase();

        // Detection logic based on path patterns
        if (normalizedGameDir.contains("modrinthapp")) {
            detectedLauncher = LauncherType.MODRINTH_APP;
        } else if (normalizedGameDir.contains("multimc")) {
            detectedLauncher = LauncherType.MULTIMC;
        } else if (normalizedGameDir.contains("prismlauncher")) {
            detectedLauncher = LauncherType.PRISM_LAUNCHER;
        } else if (normalizedGameDir.contains("curseforge")) {
            detectedLauncher = LauncherType.CURSEFORGE;
        } else if (normalizedGameDir.contains("atlauncher")) {
            detectedLauncher = LauncherType.ATLAUNCHER;
        } else if (normalizedGameDir.equals(normalizedUserHome + "/.minecraft") ||
                normalizedGameDir.equals(normalizedUserHome + "/appdata/roaming/.minecraft")) {
            detectedLauncher = LauncherType.VANILLA_LAUNCHER;
        } else {
            detectedLauncher = LauncherType.UNKNOWN;
        }

        LOGGER.info("Detected launcher: {}", detectedLauncher.getDisplayName());
        detectedGameDir = gameDir;

        return detectedLauncher;
    }

    /**
     * Get the appropriate storage directory based on the detected launcher
     */
    public static String getStorageDirectory(String subDirectory) {
        LauncherType launcher = detectLauncher();
        String gameDir = FabricLoader.getInstance().getGameDir().toString();

        switch (launcher) {
            case MODRINTH_APP:
                return handleModrinthApp(gameDir, subDirectory);

            case MULTIMC:
            case PRISM_LAUNCHER:
                // These launchers use instance-specific directories
                return gameDir + File.separator + subDirectory;

            case CURSEFORGE:
                // CurseForge also uses instance-specific directories
                return gameDir + File.separator + subDirectory;

            case ATLAUNCHER:
                // ATLauncher uses instance-specific directories
                return gameDir + File.separator + subDirectory;

            case VANILLA_LAUNCHER:
                // Standard .minecraft directory
                return gameDir + File.separator + subDirectory;

            case UNKNOWN:
            default:
                LOGGER.warn("Unknown launcher detected, using game directory");
                return gameDir + File.separator + subDirectory;
        }
    }

    /**
     * Handle Modrinth App specific path resolution
     */
    private static String handleModrinthApp(String gameDir, String subDirectory) {
        // Modrinth App path typically looks like:
        // C:\Users\{USERNAME}\AppData\Roaming\ModrinthApp\profiles\{PROFILE_NAME}\

        if (gameDir.contains("{COMPUTER_USERNAME}")) {
            // Replace the placeholder with actual username
            String username = System.getProperty("user.name");
            String resolvedPath = gameDir.replace("{COMPUTER_USERNAME}", username);
            LOGGER.info("Resolved Modrinth path: {} -> {}", gameDir, resolvedPath);
            return resolvedPath + File.separator + subDirectory;
        }

        // If no placeholder, use as-is
        return gameDir + File.separator + subDirectory;
    }

}

