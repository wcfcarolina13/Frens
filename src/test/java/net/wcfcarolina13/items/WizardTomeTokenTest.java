package net.wcfcarolina13.items;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spell token checks accept only the Wizard's Tome item, never a vanilla book by its name. */
class WizardTomeTokenTest {

    private static final List<Path> FILES = List.of(
            Path.of("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"),
            Path.of("src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java"),
            Path.of("src/main/java/net/wcfcarolina13/FrensClient.java"));

    @Test
    void tokenChecksMatchTheItemNotABookName() throws IOException {
        for (Path file : FILES) {
            String src = Files.readString(file);
            for (String method : List.of("boolean hasSpellbookToken(", "boolean hasWizardTomeToken(",
                    "boolean isHoldingSpellTriggerItem(")) {
                int at = src.indexOf(method);
                if (at < 0) {
                    continue;
                }
                int end = src.indexOf("\n    }\n", at);
                String body = src.substring(at, end);
                assertTrue(body.contains("WIZARD_TOME"), file + " " + method + " must check the Tome item");
                assertFalse(body.contains("WRITTEN_BOOK") || body.contains("ENCHANTED_BOOK"),
                        file + " " + method + " must not accept a vanilla book");
            }
        }
    }
}
