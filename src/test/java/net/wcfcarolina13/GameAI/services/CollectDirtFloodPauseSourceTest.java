package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 48 fix wave: the flood-pause skip in CollectDirtSkill's ascent is for system surface
 * recovery only. A player-issued "/bot skill collect_dirt ... surface" sets ascentToSurface too,
 * so the skip must hang on an explicit flag only SurfaceRecoveryService sets, not on stopAtSurface.
 * Works from source text because CollectDirtSkill needs the Minecraft runtime.
 */
class CollectDirtFloodPauseSourceTest {

    private static final String SKILL = "src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java";
    private static final String RECOVERY = "src/main/java/net/wcfcarolina13/GameAI/services/SurfaceRecoveryService.java";
    private static final String COMMANDS = "src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java";

    private static String read(String path) throws IOException {
        return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
    }

    @Test
    void floodSkipRequiresTheSystemRecoveryFlag() throws IOException {
        String src = read(SKILL).replaceAll("\\s+", " ");
        assertTrue(src.contains("final boolean floodIsTheReason = getBooleanParameter(context, FLOOD_ESCAPE_PARAM, false)"
                        + " && stopAtSurface && isTouchingWater(player);"),
                "floodIsTheReason must require the explicit system-recovery flag");
        assertTrue(src.contains("if (!floodIsTheReason && handleWaterHazard(player, source))"),
                "the ascent must still run the flood pause when the flag is absent");
    }

    @Test
    void onlySurfaceRecoveryServiceSetsTheFlag() throws IOException {
        assertTrue(read(RECOVERY).contains("params.put(CollectDirtSkill.FLOOD_ESCAPE_PARAM, true);"),
                "SurfaceRecoveryService must set the flood-escape flag");
        List<Path> setters;
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            setters = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p, StandardCharsets.UTF_8);
                            return s.contains("FLOOD_ESCAPE_PARAM, true") || s.contains("\"surfaceRecoveryFloodEscape\", true");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }
        assertEquals(List.of(Paths.get(RECOVERY)), setters, "only SurfaceRecoveryService may set the flag");
    }

    @Test
    void userSurfaceCommandStillPausesOnFlood() throws IOException {
        String commands = read(COMMANDS);
        assertTrue(commands.contains("params.put(\"ascentToSurface\", true);"),
                "the user collect_dirt surface path is expected to set ascentToSurface");
        assertTrue(!commands.contains("FLOOD_ESCAPE_PARAM") && !commands.contains("surfaceRecoveryFloodEscape"),
                "the user command must not set the flood-escape flag");
    }
}
