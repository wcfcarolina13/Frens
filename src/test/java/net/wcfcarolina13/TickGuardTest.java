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
}
