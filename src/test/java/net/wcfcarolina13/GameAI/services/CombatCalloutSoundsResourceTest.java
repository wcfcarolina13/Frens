package net.wcfcarolina13.GameAI.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every sound the combat callout pools play must have at least one ogg in sounds.json
 * (1.1.273: post_explosion_less_boom had an empty sounds[] and logged a missing sound).
 * Works from source text because loading BotDialogueSounds needs the Minecraft registry.
 */
class CombatCalloutSoundsResourceTest {

    private static final Pattern REGISTER = Pattern.compile("(LINE_[A-Z0-9_]+)\\s*=\\s*register\\(\"([^\"]+)\"\\)");
    private static final Pattern REFERENCE = Pattern.compile("BotDialogueSounds\\.(LINE_[A-Z0-9_]+)");

    private static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void everyCalloutSoundHasAnOgg() throws IOException {
        Map<String, String> constantToPath = new HashMap<>();
        Matcher reg = REGISTER.matcher(read("src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java"));
        while (reg.find()) {
            constantToPath.put(reg.group(1), reg.group(2));
        }
        assertFalse(constantToPath.isEmpty(), "register(...) pattern no longer matches BotDialogueSounds");

        TreeSet<String> referenced = new TreeSet<>();
        Matcher ref = REFERENCE.matcher(read("src/main/java/net/wcfcarolina13/GameAI/services/BotCombatCalloutService.java"));
        while (ref.find()) {
            referenced.add(ref.group(1));
        }
        assertFalse(referenced.isEmpty(), "no BotDialogueSounds.LINE_* references found in BotCombatCalloutService");

        JsonNode sounds = new ObjectMapper().readTree(read("src/main/resources/assets/frens/sounds.json"));
        List<String> problems = new ArrayList<>();
        for (String constant : referenced) {
            String path = constantToPath.get(constant);
            if (path == null) {
                problems.add(constant + ": no register(...) in BotDialogueSounds");
                continue;
            }
            JsonNode event = sounds.get(path);
            if (event == null) {
                problems.add(path + ": missing from sounds.json");
            } else if (!event.path("sounds").isArray() || event.path("sounds").isEmpty()) {
                problems.add(path + ": empty sounds[]");
            }
        }
        assertTrue(problems.isEmpty(), "callout sounds without oggs: " + problems);
    }

    @Test
    void lessBoomLineIsGoneFromCallouts() throws IOException {
        String src = read("src/main/java/net/wcfcarolina13/GameAI/services/BotCombatCalloutService.java");
        assertFalse(src.contains("LINE_POST_EXPLOSION_LESS_BOOM"));
    }
}
