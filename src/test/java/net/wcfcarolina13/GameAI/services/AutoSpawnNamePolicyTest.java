package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoSpawnNamePolicyTest {

    @Test
    void savedNameWins() {
        assertEquals("Alex", AutoSpawnNamePolicy.spawnName("alex", "Alex", List.of("ALEX")));
        assertEquals("bea", AutoSpawnNamePolicy.spawnName("bea", "bea", List.of("Bea")));
    }

    @Test
    void cacheNameRecoversTheSpellingOfAnOlderEntry() {
        assertEquals("RotiBot", AutoSpawnNamePolicy.spawnName("rotibot", null, List.of("RotiBot")));
    }

    @Test
    void aSpelledCacheNameBeatsTheLowerCasePlayerTheBugCreated() {
        assertEquals("Guardy", AutoSpawnNamePolicy.spawnName("guardy", null, List.of("guardy", "Guardy")));
    }

    @Test
    void anotherBotsNameIsIgnored() {
        // A user-cache name for a UUID in another bot's file ("alexa_<uuid>") is not this bot's name.
        assertEquals("alex", AutoSpawnNamePolicy.spawnName("alex", "Alexa", List.of("Alexa")));
    }

    @Test
    void noCandidateKeepsTheAlias() {
        assertEquals("bea", AutoSpawnNamePolicy.spawnName("bea", null, List.of()));
        assertEquals("bea", AutoSpawnNamePolicy.spawnName(" bea ", null, null));
        assertEquals("bea", AutoSpawnNamePolicy.spawnName("bea", null, Arrays.asList(null, " ")));
        assertEquals("bea", AutoSpawnNamePolicy.spawnName("bea", null, List.of("bea")));
    }
}
