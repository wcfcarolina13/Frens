package net.wcfcarolina13.GameAI.services;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time migration of config/data files from the old {@code ai-player} mod-ID
 * directory to the new {@code frens} directory.  Called once at startup so that
 * users upgrading from the old mod keep their home data, world state, crafting
 * history, etc.
 */
public final class LegacyDataMigrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-migration");
    private static final String LEGACY_DIR = "ai-player";
    private static final String NEW_DIR = "frens";
    private static final AtomicBoolean MIGRATED = new AtomicBoolean(false);

    private LegacyDataMigrationService() {}

    /**
     * Copies any files from {@code config/ai-player/} into {@code config/frens/}
     * that do not already exist under the new path.  Safe to call multiple times;
     * only the first invocation performs work.
     */
    public static void migrateConfigDir() {
        if (MIGRATED.getAndSet(true)) return;

        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path legacyDir = configDir.resolve(LEGACY_DIR);
        Path newDir    = configDir.resolve(NEW_DIR);

        if (!Files.isDirectory(legacyDir)) {
            LOGGER.debug("No legacy config dir found at {}; nothing to migrate.", legacyDir);
            return;
        }

        LOGGER.info("Found legacy config directory '{}'. Migrating to '{}'...", LEGACY_DIR, NEW_DIR);

        try {
            Files.createDirectories(newDir);
            try (var stream = Files.walk(legacyDir)) {
                stream.forEach(src -> {
                    Path relative = legacyDir.relativize(src);
                    Path dest = newDir.resolve(relative);
                    try {
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dest);
                        } else if (!Files.exists(dest)) {
                            Files.copy(src, dest);
                            LOGGER.info("  Migrated: {}", relative);
                        } else if (Files.size(dest) == 0 && Files.size(src) > 0) {
                            // New file is empty but old one has data — overwrite
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                            LOGGER.info("  Replaced empty file with legacy data: {}", relative);
                        }
                    } catch (IOException e) {
                        LOGGER.warn("  Failed to migrate {}: {}", relative, e.getMessage());
                    }
                });
            }
            LOGGER.info("Legacy config migration complete.");
        } catch (IOException e) {
            LOGGER.warn("Legacy config migration failed: {}", e.getMessage());
        }
    }
}
