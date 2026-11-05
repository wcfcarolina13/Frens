package net.wcfcarolina13.GameAI.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The farming skills keep private hotbar helpers that swap stacks and change the selected slot.
 * They are called from skill workers, so each hops to the server thread (inline when already there).
 */
class FarmHotbarHelperHopWiringTest {
    private static final String DIR = "src/main/java/net/wcfcarolina13/GameAI/skills/impl/";

    @Test
    void privateHotbarHelpersHop() throws IOException {
        String[][] cases = {
                {"FarmSkill.java", "() -> ensureHotbarAccessOnServer(bot, slot), 750L, -1)"},
                {"FarmSkill.java", "selectHotbarSlotOnServer(bot, slot);"},
                {"HarvestCropSkill.java", "() -> ensureSeedHotbarOnServer(bot, targetSeed), 750L, -1)"},
                {"HarvestCropSkill.java", "() -> findAnySeedHotbarSlotOnServer(bot), 750L, -1)"},
                {"HarvestCropSkill.java", "selectHotbarSlotOnServer(bot, slot);"},
                {"PlantSeedsSkill.java", "() -> ensureAnySeedHotbarOnServer(bot), 750L, -1)"},
                {"PlantSeedsSkill.java", "selectHotbarSlotOnServer(bot, slot);"},
        };
        for (String[] c : cases) {
            String src = Files.readString(Path.of(DIR + c[0]));
            int at = src.indexOf(c[1]);
            assertTrue(at >= 0, c[0] + ": " + c[1]);
            assertTrue(src.lastIndexOf("BotActions.callOnServerThread", at) > at - 200, c[0] + " hop around " + c[1]);
        }
    }

    @Test
    void slotLookupsRetryOnceOnATimeout() throws IOException {
        // Callers end a planting pass on -1, so one server stall must not read as "no seeds".
        for (String f : new String[]{"FarmSkill.java", "HarvestCropSkill.java", "PlantSeedsSkill.java"}) {
            assertTrue(Files.readString(Path.of(DIR + f)).contains("BotActions.callOnServerThreadRetrying(bot, () ->"), f);
        }
        String farm = Files.readString(Path.of(DIR + "FarmSkill.java"));
        int access = farm.indexOf("private static int ensureHotbarAccess(ServerPlayerEntity bot, int slot) {");
        assertTrue(farm.indexOf("if (slot >= 0 && slot < 9)", access) < farm.indexOf("callOnServerThreadRetrying", access),
                "a hotbar slot returns before any hop");
    }
}
