package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The respawn abort reason must be spoken through the NEW bot entity: the ticket's source holds
 * the entity vanilla removed on respawn, and a removed bot's chat is dropped by
 * CompanionCommunicationPolicy (1.1.293 sandbox finding).
 */
class TaskServiceRespawnAnnounceWiringTest {
    @Test
    void respawnAbortAnnouncesThroughTheNewBotsCommandSource() throws IOException {
        String respawn = method("onBotRespawn");
        assertTrue(respawn.contains("abortTicket(ticket, reason, false)"),
                "the ticket abort itself must not announce through ticket.source()");
        int abort = respawn.indexOf("if (ticket != null && abortTicket(ticket, reason, false))");
        int send = respawn.indexOf("dispatchMessage(bot.getCommandSource(),");
        assertTrue(abort >= 0 && send > abort, "announce only when this call cancelled the ticket");
        assertFalse(respawn.contains("ticket.source()"));
        assertFalse(respawn.contains("forceAbort("));
        assertTrue(respawn.contains("ABORT_LATCH.put(key(bot.getUuid())"),
                "the latch is still set when no ticket is active");
    }

    @Test
    void abortTicketReportsWhetherItCancelledAndKeepsItsDispatchGate() throws IOException {
        String source = source();
        String abort = block(source, "private static boolean abortTicket(TaskTicket ticket, String reason, boolean dispatchMessage)");
        assertTrue(abort.contains("boolean cancelled = ticket.requestCancel(reason);"));
        assertTrue(block(abort, "if (cancelled)").contains("if (dispatchMessage)"));
        assertTrue(abort.contains("return cancelled;"));
    }

    @Test
    void sourceTakingDispatchKeepsTheStoppingAndBlankGuards() throws IOException {
        String source = source();
        assertTrue(block(source, "private static void dispatchMessage(TaskTicket ticket, String message)")
                .contains("dispatchMessage(ticket.source(), message)"));
        String dispatch = block(source, "private static void dispatchMessage(ServerCommandSource source, String message)");
        assertTrue(dispatch.contains(
                "if (SERVER_STOPPING || source == null || message == null || message.isBlank())"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/TaskService.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }

    private static String method(String name) throws IOException {
        String source = source();
        var declaration = Pattern.compile("public static [\\w.<>]+ " + name + "\\([^)]*\\)\\s*\\{")
                .matcher(source);
        assertTrue(declaration.find(), "Missing method: " + name);
        return block(source, declaration.group());
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
