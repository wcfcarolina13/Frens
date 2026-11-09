package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fall clutch must not empty a water bucket where water evaporates (the Nether). Its old
 * guard reflected on {@code DimensionType.isUltrawarm()}, which 1.21.11 no longer has, so the
 * guard always answered false. Reads the source: BotFallSafetyService touches server types.
 */
class FallClutchEvaporationWiringTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java"));
    }

    @Test
    void guardReadsTheEnvironmentAttributeNotReflection() throws IOException {
        String src = source();
        assertTrue(src.contains("EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY"), "uses the 1.21.11 attribute");
        assertFalse(src.contains("getMethod(\"isUltrawarm\")"), "no reflective ultrawarm lookup");
        assertFalse(src.contains("getMethod(\"ultrawarm\")"), "no reflective ultrawarm lookup");
    }

    @Test
    void clutchSkipsBeforeUsingTheBucketAndPlacementChecksTheLandingCell() throws IOException {
        String src = source();
        int clutch = src.indexOf("private static void maybeClutchFall(");
        int skip = src.indexOf("waterEvaporatesAt(world, bot.getBlockPos())", clutch);
        int bucket = src.indexOf("BotActions.ensureHotbarItem(bot, Items.WATER_BUCKET)", clutch);
        assertTrue(clutch >= 0 && skip > clutch && bucket > skip, "evaporation skip comes before the bucket swap");
        int place = src.indexOf("private static boolean canPlaceWaterAt(");
        assertTrue(src.indexOf("waterEvaporatesAt(world, placePos)", place) > place, "landing cell is checked too");
    }
}
