package net.wcfcarolina13.GameAI.services;

/**
 * Pure world keys for {@link BotChestRegistryService} (no Minecraft types, so JUnit can test them).
 *
 * <p>Since 1.1.274 the registry keys a world by save fingerprint + dimension, the same spelling
 * as the bot-home store ({@link WorldStoreKeyPolicy#fingerprintKey}). Before that it used
 * {@code levelName/dimension}, which two saves named "New World" share, so their chests mixed.
 */
public final class ChestRegistryWorldKeys {

    private ChestRegistryWorldKeys() {}

    /** Current key: {@code "New World#1a2b3c:minecraft:overworld"}. */
    public static String key(String fingerprint, String dimensionId) {
        return WorldStoreKeyPolicy.fingerprintKey(fingerprint, dimensionId);
    }

    /**
     * Pre-1.1.274 key, exactly as older builds wrote it: raw (untrimmed) level name + "/" +
     * dimension, with {@code "unknown"} for a missing server or dimension. Read only for adoption.
     */
    public static String legacyKey(String rawLevelName, String dimensionId) {
        String level = rawLevelName != null ? rawLevelName : "unknown";
        String dim = dimensionId != null ? dimensionId : "unknown";
        return level + "/" + dim;
    }
}
