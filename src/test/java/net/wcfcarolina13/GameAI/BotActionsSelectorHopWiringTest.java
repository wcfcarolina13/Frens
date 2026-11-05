package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skills call these hotbar selectors from worker threads. Each swaps stacks or changes the selected
 * slot, so each must run on the server thread (inline when already there), the same pattern as
 * selectHarvestToolOrHands since 1.1.289.
 */
class BotActionsSelectorHopWiringTest {

    @Test
    void everyPublicSelectorHops() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotActions.java"));
        String[][] selectors = {
                {"public static boolean selectBestWeapon(", "selectBestWeaponOnServer(bot)"},
                {"public static boolean selectBestMeleeWeapon(", "selectBestMeleeWeaponOnServer(bot)"},
                {"public static boolean selectBestTool(", "selectBestToolOnServer(bot, preferKeyword, avoidKeyword)"},
                {"public static boolean selectHarvestToolOrHands(", "selectHarvestToolOrHandsOnServer(bot, preferKeyword)"},
                {"public static boolean ensureHotbarItem(", "ensureHotbarItemOnServer(bot, desired)"},
                {"public static void selectHotbarSlot(", "selectHotbarSlotOnServer(bot, index)"},
        };
        for (String[] s : selectors) {
            int start = src.indexOf(s[0]);
            assertTrue(start >= 0, s[0]);
            int end = src.indexOf("\n    }\n", start);
            String body = src.substring(start, end);
            assertTrue(body.contains("callOnServerThread(bot, () ->"), s[0] + " must hop");
            assertTrue(body.contains(s[1]), s[0] + " must delegate to " + s[1]);
            assertTrue(!body.contains("setSelectedSlot") && !body.contains("swapInventoryStacks"),
                    s[0] + " must not mutate before the hop");
        }
    }
}
