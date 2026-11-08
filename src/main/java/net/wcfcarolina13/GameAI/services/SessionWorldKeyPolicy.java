package net.wcfcarolina13.GameAI.services;

import java.util.Set;

/**
 * Hunt and fishing sessions live in one file under config/frens for every save. Entries are keyed
 * {@code worldKey|botId} so a bot's session in one world is neither resumed in another world nor
 * overwritten by it. Entries written before this change are keyed by the bot id alone; they still
 * match in any world (the old behaviour) until that bot's next save replaces them. Pure.
 */
public final class SessionWorldKeyPolicy {
    private SessionWorldKeyPolicy() {
    }

    /** The key a session is saved under; the bare id when the world is unknown. */
    public static String key(String worldKey, String botId) {
        return worldKey == null || worldKey.isBlank() ? botId : worldKey + "|" + botId;
    }

    /** The key to read or remove: this world's entry, else a legacy bare-id entry, else this world's key. */
    public static String find(Set<String> keys, String worldKey, String botId) {
        String own = key(worldKey, botId);
        if (keys.contains(own)) {
            return own;
        }
        return keys.contains(botId) ? botId : own;
    }
}
