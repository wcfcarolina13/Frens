package net.wcfcarolina13.GameAI.services.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps sounds.json, BotDialogueSounds and TextOnlyLinePolicy in step (ruling 2026-10-23: lines
 * with only silent placeholders are text only). Works from source text because loading
 * BotDialogueSounds needs the Minecraft registry.
 */
class TextOnlyLineResourcesTest {

    private static final String SOUNDS_JSON = "src/main/resources/assets/frens/sounds.json";
    private static final String SOUNDS_JAVA = "src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java";
    private static final String PLAYER_JAVA = "src/main/java/net/wcfcarolina13/ChatUtils/BotDialoguePlayer.java";
    private static final String AMBIENT_JAVA = "src/main/java/net/wcfcarolina13/ChatUtils/BotAmbientChatter.java";
    private static final Pattern REGISTER = Pattern.compile("([A-Z0-9_]+)\\s*=\\s*register\\(\"([^\"]+)\"\\)");

    private static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    private static Map<String, String> registeredPathToConstant() throws IOException {
        Map<String, String> out = new HashMap<>();
        Matcher m = REGISTER.matcher(read(SOUNDS_JAVA));
        while (m.find()) {
            out.put(m.group(2), m.group(1));
        }
        assertFalse(out.isEmpty(), "register(...) pattern no longer matches BotDialogueSounds");
        return out;
    }

    @Test
    void textOnlyPathsHaveNoSoundsJsonBlock() throws IOException {
        JsonNode sounds = new ObjectMapper().readTree(read(SOUNDS_JSON));
        List<String> problems = new ArrayList<>();
        for (String path : TextOnlyLinePolicy.TEXT_ONLY_PATHS) {
            if (sounds.has(path)) {
                problems.add(path);
            }
        }
        assertTrue(problems.isEmpty(), "text-only lines still in sounds.json: " + problems);
    }

    @Test
    void everyRemainingSoundsJsonBlockHasSounds() throws IOException {
        JsonNode sounds = new ObjectMapper().readTree(read(SOUNDS_JSON));
        List<String> problems = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = sounds.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode list = e.getValue().get("sounds");
            if (list == null || !list.isArray() || list.isEmpty()) {
                problems.add(e.getKey());
            }
        }
        assertTrue(problems.isEmpty(),
                "sounds.json events with no sounds (make them text only in TextOnlyLinePolicy): " + problems);
    }

    @Test
    void everyTextOnlyPathKeepsItsRegisterLine() throws IOException {
        Map<String, String> registered = registeredPathToConstant();
        List<String> problems = new ArrayList<>();
        for (String path : TextOnlyLinePolicy.TEXT_ONLY_PATHS) {
            if (!registered.containsKey(path)) {
                problems.add(path);
            }
        }
        assertTrue(problems.isEmpty(), "text-only lines with no register(...) in BotDialogueSounds: " + problems);
    }

    @Test
    void everyRegisteredPathIsVoicedOrTextOnly() throws IOException {
        JsonNode sounds = new ObjectMapper().readTree(read(SOUNDS_JSON));
        List<String> problems = new ArrayList<>();
        for (String path : registeredPathToConstant().keySet()) {
            if (!sounds.has(path) && !TextOnlyLinePolicy.isTextOnly(path)) {
                problems.add(path);
            }
        }
        assertTrue(problems.isEmpty(),
                "registered sound events missing from sounds.json and not text only: " + problems);
    }

    @Test
    void ambientTextOnlyLinesHaveSubtitleText() throws IOException {
        // BotAmbientChatter's text-only branch shows the subtitle text; without it the line is silent.
        Map<String, String> registered = registeredPathToConstant();
        String ambient = read(AMBIENT_JAVA);
        String player = read(PLAYER_JAVA);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String path : TextOnlyLinePolicy.TEXT_ONLY_PATHS) {
            String constant = registered.get(path);
            if (constant == null || !ambient.contains("BotDialogueSounds." + constant + ",")) {
                continue;
            }
            checked++;
            if (!player.contains("SUBTITLE_MAP.put(BotDialogueSounds." + constant + ",")) {
                problems.add(constant);
            }
        }
        assertTrue(checked > 0, "no text-only lines found in BotAmbientChatter pools");
        assertTrue(problems.isEmpty(), "ambient text-only lines without SUBTITLE_MAP text: " + problems);
    }
}
