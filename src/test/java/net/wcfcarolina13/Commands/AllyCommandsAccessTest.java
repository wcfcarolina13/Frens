package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An alliance needs both players to run the invite, and {@code /bot} needs an operator or the
 * host, so non-operators could never ally. {@code /frens ally} is open to every player.
 */
class AllyCommandsAccessTest {

    @Test
    void frensAllyNeedsOnlyAPlayer() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Commands/AllyCommands.java"));
        assertTrue(src.contains(".requires(source -> source.getPlayer() != null)"));
        assertFalse(src.contains("hasBotCommandPermission"));
        String frens = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        assertTrue(frens.contains("AllyCommands.register();"));
    }

    @Test
    void acceptAndDeclineButtonsRunTheOpenCommands() {
        assertEquals("/frens ally invite Sam", AllyCommands.inviteCommand("Sam"));
        assertEquals("/frens ally revoke Sam", AllyCommands.revokeCommand("Sam"));
    }
}
