package net.wcfcarolina13.GameAI.souls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persona pick persists per bot UUID beside soul.json, survives a relaunch, and a plain bind drops it. */
class PersonaPickStoreTest {

    @TempDir
    Path worldRoot;

    private ExecutorService executor;
    private SoulStore store;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        store = new SoulStore(worldRoot, executor);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private Path botDir(UUID bot) {
        return worldRoot.resolve("frens/souls/v1").resolve(bot.toString());
    }

    @Test
    void pickSurvivesARelaunchAndKeepsTheActiveFlag() throws Exception {
        UUID bot = UUID.randomUUID();
        store.bindProfile(bot, "frens:bob").get(2, SECONDS);
        store.setActive(bot, true).get(2, SECONDS);
        SoulTypes.SoulState picked = store.bindPickedProfile(bot, "frens:silas").get(2, SECONDS);
        assertEquals("frens:silas", picked.profileId());
        assertTrue(picked.active(), "a pick never changes the active flag");
        assertEquals("frens:silas", store.cachedPickedProfile(bot).orElseThrow());

        SoulStore relaunched = new SoulStore(worldRoot, executor);
        relaunched.preloadIndex().join();
        assertEquals("frens:silas", relaunched.cachedPickedProfile(bot).orElseThrow());
        assertEquals("frens:silas", relaunched.cachedState(bot).orElseThrow().profileId());
    }

    @Test
    void soulJsonKeepsItsOldShapeSoAnOlderJarStillReadsIt() throws Exception {
        UUID bot = UUID.randomUUID();
        store.bindPickedProfile(bot, "frens:silas").get(2, SECONDS);
        var node = new ObjectMapper().readTree(botDir(bot).resolve("soul.json").toFile());
        java.util.Set<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        assertEquals(java.util.Set.of("schemaVersion", "botId", "profileId", "active", "conversations"), fields);
        assertTrue(Files.isRegularFile(botDir(bot).resolve(SoulStore.PICKED_PERSONA_FILE)));
    }

    @Test
    void aPlainBindDropsThePick() throws Exception {
        UUID bot = UUID.randomUUID();
        store.bindPickedProfile(bot, "frens:silas").get(2, SECONDS);
        store.bindProfile(bot, "frens:bob").get(2, SECONDS);
        assertTrue(store.cachedPickedProfile(bot).isEmpty());
        assertFalse(Files.exists(botDir(bot).resolve(SoulStore.PICKED_PERSONA_FILE)));
    }

    @Test
    void anUnreadableSidecarIsIgnored() throws Exception {
        UUID bot = UUID.randomUUID();
        store.bindProfile(bot, "frens:bob").get(2, SECONDS);
        Files.writeString(botDir(bot).resolve(SoulStore.PICKED_PERSONA_FILE), "frens:\u0000bad");
        SoulStore relaunched = new SoulStore(worldRoot, executor);
        relaunched.preloadIndex().join();
        assertTrue(relaunched.cachedPickedProfile(bot).isEmpty());
    }
}
