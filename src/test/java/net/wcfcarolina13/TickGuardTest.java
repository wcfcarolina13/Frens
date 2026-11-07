package net.wcfcarolina13;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickGuardTest {

    @Test
    void firstFailureIsLoggedThenOncePerMinuteWithTheHeldBackCount() {
        TickGuardLogPolicy log = new TickGuardLogPolicy();
        assertEquals(0, log.onFailure("a", 0));
        assertEquals(-1, log.onFailure("a", 1_000));
        assertEquals(-1, log.onFailure("a", 59_999));
        assertEquals(2, log.onFailure("a", 60_000), "two held back since the last report");
        assertEquals(0, log.onFailure("b", 60_001), "handlers are throttled separately");
    }

    @Test
    void everyServerTickHandlerIsGuarded() throws IOException {
        // A RuntimeException escaping END_SERVER_TICK crashes the server: each registration
        // in the mod goes through TickGuard.
        Pattern register = Pattern.compile("ServerTickEvents\\.END_SERVER_TICK\\.register\\((?!\\s*(net\\.wcfcarolina13\\.)?TickGuard\\.)");
        List<String> unguarded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p).replaceAll("(?m)//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
                Matcher m = register.matcher(src);
                while (m.find()) {
                    unguarded.add(p + " @" + m.start());
                }
            }
        }
        assertEquals(List.of(), unguarded);
    }

    @Test
    void damageDeathAndEntityUseAreGuarded() throws IOException {
        String frens = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        assertTrue(frens.contains("ServerLivingEntityEvents.ALLOW_DAMAGE.register(TickGuard.allowDamage(\"allow-damage\","));
        assertTrue(frens.contains("ServerLivingEntityEvents.AFTER_DEATH.register(TickGuard.afterDeath(\"after-death\","));
        assertTrue(frens.contains("UseEntityCallback.EVENT.register(TickGuard.useEntity(\"use-entity\","));
        assertTrue(frens.contains("ServerLivingEntityEvents.AFTER_DAMAGE.register(TickGuard.afterDamage(\"after-damage\","));
        assertTrue(frens.contains("AttackEntityCallback.EVENT.register(TickGuard.attackEntity(\"attack-entity\","));
    }

    @Test
    void survivalLoopsIsolateEachBot() throws IOException {
        // Hazard escape, eating and autosave: one bot's exception must not skip the bots after it.
        String[][] sites = {
                {"src/main/java/net/wcfcarolina13/GameAI/services/BotHazardService.java", "TickGuard.perBot(\"BotHazardService::onServerTick\", bot,"},
                {"src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java", "TickGuard.perBot(\"BotEventHandler::tickHunger\", player,"},
                {"src/main/java/net/wcfcarolina13/GameAI/services/BotPersistenceService.java", "TickGuard.perBot(\"BotPersistenceService::onServerTick\", player,"},
        };
        for (String[] site : sites) {
            assertTrue(Files.readString(Path.of(site[0])).contains(site[1]), site[0]);
        }
        String persistence = Files.readString(Path.of(sites[2][0]));
        int guard = persistence.indexOf(sites[2][1]);
        assertTrue(persistence.lastIndexOf("active.add(player.getUuid());", guard) > 0,
                "the bot counts as active even when its save throws, so its LAST_SAVE_TICK entry is kept");
    }
}
