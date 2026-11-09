package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #120: a protected-zone hazard tells the player "Press Resume to override", so every skill
 * that pauses on a blocking mining hazard must flag the zone override with the Resume. Only
 * Stripmine did; Collect Dirt (ascent, descent, hand-dig stairs) and Dirt Shovel stopped again at
 * the same zone.
 */
class ZoneResumeOverrideWiringTest {

    private static final Path SKILLS = Paths.get("src/main/java/net/wcfcarolina13/GameAI/skills/impl");

    @Test
    void everySkillPausedByAMiningHazardFlagsTheZoneOverrideWithResume() throws IOException {
        List<String> missing = new ArrayList<>();
        int blocks = 0;
        try (Stream<Path> files = Files.walk(SKILLS)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(file);
                if (!src.contains("MiningHazardDetector.detect(")) {
                    continue;
                }
                int from = 0;
                while (true) {
                    int at = src.indexOf("Hazard hazard = ", from);
                    if (at < 0) {
                        break;
                    }
                    int ret = src.indexOf("return ", at);
                    String block = src.substring(at, ret < 0 ? src.length() : ret);
                    if (block.contains("blockingHazard().get()")) {
                        blocks++;
                        if (!block.contains("SkillResumeService.flagManualResumeForHazard(player, MiningHazardDetector.isProtectedZoneHazard(hazard))")) {
                            missing.add(file.getFileName() + " @" + at);
                        }
                    }
                    from = at + 1;
                }
            }
        }
        assertEquals(6, blocks, "Stripmine 1, Collect Dirt 3, Dirt Shovel 2");
        assertTrue(missing.isEmpty(), "blocking-hazard pauses without the zone override: " + missing);
    }

    @Test
    void theHelperFlagsResumeAndTheOverrideOnlyForAZone() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java"));
        int helper = src.indexOf("public static void flagManualResumeForHazard(");
        String body = src.substring(helper, src.indexOf("\n    }\n", helper));
        assertTrue(body.indexOf("flagManualResume(bot);") < body.indexOf("if (protectedZoneHazard)"),
                "Resume is flagged first, then the override for a zone");
        assertTrue(body.contains("flagZoneOverridePending(bot);"));
    }
}
