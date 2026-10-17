package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source pins: mounted leaf clearing never prefers shears and picks its item through ToolSelector. */
class MountedLeafClearingServiceSourceTest {

    private static String source() throws IOException {
        return Files.readString(Paths.get(
                "src/main/java/net/wcfcarolina13/GameAI/services/MountedLeafClearingService.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void noShearsPreference() throws IOException {
        String src = source();
        assertFalse(src.contains("Items.SHEARS"), "shears drop the leaf block instead of saplings and sticks");
        assertFalse(src.contains("selectLeafTool"), "the shears-first picker is gone");
        assertFalse(src.contains("SlotActionType.SWAP"), "no inventory swap on the server tick");
    }

    @Test
    void picksThroughToolSelectorAndSkipsWhileShearsHeld() throws IOException {
        String src = source();
        assertTrue(src.contains("ToolSelector.selectBestToolForBlock(bot, leafState)"));
        assertTrue(src.contains("instanceof ShearsItem"), "a pass is skipped while the main hand holds shears");
    }
}
