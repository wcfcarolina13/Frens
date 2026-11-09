package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aSingleBotNameIsTheBot() {
        assertEquals(CookCommandArgsPolicy.FirstWordRole.BOT, CookCommandArgsPolicy.firstWordRole("Bob", false, true));
    }

    @Test
    void aSingleWordStaysItemOrFuelOtherwise() {
        assertEquals(CookCommandArgsPolicy.FirstWordRole.ITEM, CookCommandArgsPolicy.firstWordRole("beef", true, false));
        assertEquals(CookCommandArgsPolicy.FirstWordRole.ITEM, CookCommandArgsPolicy.firstWordRole("fish", false, false), "name filters stay items");
        assertEquals(CookCommandArgsPolicy.FirstWordRole.ITEM, CookCommandArgsPolicy.firstWordRole("beef", true, true), "an item wins over a bot of the same name");
        assertEquals(CookCommandArgsPolicy.FirstWordRole.FUEL, CookCommandArgsPolicy.firstWordRole("auto", false, true), "a fuel word stays fuel");
        assertEquals(CookCommandArgsPolicy.FirstWordRole.ITEM, CookCommandArgsPolicy.firstWordRole(" ", false, true));
    }

    @Test
    void theSingleWordBranchAsksThePolicy() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
        assertTrue(src.contains("executeCookFirstWord(context, StringArgumentType.getString(context, \"item\"))"));
        assertTrue(src.contains("CookCommandArgsPolicy.firstWordRole("));
    }

    @Test
    void theItemFuelBranchAsksThePolicy() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"));
        assertTrue(src.contains("executeCookItemAndSecondWord(context,"), "item+fuel branch goes through the resolver");
        assertTrue(src.contains("CookCommandArgsPolicy.fuelSlotNamesBot("), "resolver uses the policy");
    }
}
