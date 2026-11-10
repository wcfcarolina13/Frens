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

    @Test
    void collectDirtsOwnZoneGateHonoursTheOverride() throws IOException {
        String src = Files.readString(SKILLS.resolve("CollectDirtSkill.java"));
        assertTrue(!src.contains("CompanionSafeZoneService.isProtected(world"),
                "Collect Dirt's stair/hand-dig mining refused zone cells even after Resume (review of #121)");
        assertEquals(2, src.split("CompanionSafeZoneService.isProtectedForBot\\(world, ", -1).length - 1);
        String safe = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/CompanionSafeZoneService.java"));
        assertTrue(safe.contains("if (!explicitZonesOverridden && isInExplicitProtectedZone(world, pos, botOwner))"),
                "the override lifts explicit zones");
        assertTrue(safe.contains("if (exceptOwner != null && exceptOwner.toString().equals(base.ownerUuid())) continue;")
                        && safe.contains("overridden ? baseOwner : null"),
                "and only the owner's own bases (their auto-zone is where the promise came from), never others' bases");
        assertEquals(2, src.split(java.util.regex.Pattern.quote("BotTerritoryAuthorizationService.resolveBotOwnerUuid(player))"), -1).length - 1,
                "Collect Dirt passes the real owner for its own bases");
        String come = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        assertTrue(come.contains("rawResult.endsWith(net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.RESUME_OVERRIDE_SUFFIX)"),
                "come recovery has nothing to resume, so it drops the promise");
    }

    @Test
    void resumeIsPromisedOnlyWhereTheOverrideCanWork() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/skills/support/MiningHazardDetector.java"));
        assertTrue(src.contains("(ownerMayMutate(botUuid, pos, world) ? RESUME_OVERRIDE_SUFFIX : \"\")"));
        assertTrue(src.contains("hazard.failureMessage().endsWith(RESUME_OVERRIDE_SUFFIX)"));
        String resume = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java"));
        assertTrue(resume.contains("if (uuid != null && LAST_SKILL_BY_BOT.containsKey(uuid)) {\n            ZONE_OVERRIDE_PENDING.add(uuid);"),
                "no zone-override flag without a paused skill to resume");
    }
}
