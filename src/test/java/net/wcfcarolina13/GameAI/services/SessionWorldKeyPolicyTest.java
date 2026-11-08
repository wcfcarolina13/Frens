package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionWorldKeyPolicyTest {
    private static final String BOT = "00000000-0000-0000-0000-00000000000a";

    @Test
    void sessionsAreKeyedPerWorld() {
        assertEquals("A#1|" + BOT, SessionWorldKeyPolicy.key("A#1", BOT));
        assertEquals(BOT, SessionWorldKeyPolicy.key(null, BOT), "unknown world: the bare id");
        assertEquals(BOT, SessionWorldKeyPolicy.key(" ", BOT));
    }

    @Test
    void anotherWorldsSessionIsNotFound() {
        Set<String> keys = Set.of("A#1|" + BOT);
        assertEquals("B#2|" + BOT, SessionWorldKeyPolicy.find(keys, "B#2", BOT), "world B looks only at its own key");
        assertFalse(keys.contains(SessionWorldKeyPolicy.find(keys, "B#2", BOT)));
        assertEquals("A#1|" + BOT, SessionWorldKeyPolicy.find(keys, "A#1", BOT));
    }

    @Test
    void aLegacyBareIdEntryStillMatchesUntilReplaced() {
        assertEquals(BOT, SessionWorldKeyPolicy.find(Set.of(BOT), "B#2", BOT));
        assertEquals("B#2|" + BOT, SessionWorldKeyPolicy.find(Set.of(BOT, "B#2|" + BOT), "B#2", BOT),
                "this world's own entry wins over the legacy one");
    }

    @Test
    void bothSessionStoresUseThePolicyForEveryLookup() throws IOException {
        for (String f : new String[]{"HuntSessionService", "FishingSessionService"}) {
            String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/" + f + ".java"));
            assertFalse(src.contains("DATA.get(botId.toString())") || src.contains("DATA.remove(botId.toString())"), f);
            assertTrue(src.contains("DATA.put(SessionWorldKeyPolicy.key(currentWorldKey(), bot.getUuid().toString()), "), f);
        }
    }
}
