package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookCommandArgsPolicyTest {

    @Test
    void aBotNameInTheFuelSlotIsTheBot() {
        assertTrue(CookCommandArgsPolicy.fuelSlotNamesBot("Bob", false, true));
    }

    @Test
    void fuelWordsAndItemsStayFuel() {
        assertFalse(CookCommandArgsPolicy.fuelSlotNamesBot("auto", false, true), "a bot called auto still means auto");
        assertFalse(CookCommandArgsPolicy.fuelSlotNamesBot("none", false, false));
        assertFalse(CookCommandArgsPolicy.fuelSlotNamesBot("coal", true, true), "an item wins over a bot name");
        assertFalse(CookCommandArgsPolicy.fuelSlotNamesBot("Steve", false, false), "not a bot: left as fuel, as before");
        assertFalse(CookCommandArgsPolicy.fuelSlotNamesBot("", false, true));
    }

    @Test
    void theItemFuelBranchAsksThePolicy() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
        assertTrue(src.contains("executeCookItemAndSecondWord(context,"), "item+fuel branch goes through the resolver");
        assertTrue(src.contains("CookCommandArgsPolicy.fuelSlotNamesBot("), "resolver uses the policy");
    }
}
