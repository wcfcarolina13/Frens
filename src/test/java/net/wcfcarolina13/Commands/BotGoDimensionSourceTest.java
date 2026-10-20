package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /bot go_to used to plan every path on the Overworld, so a bot in the Nether or End followed a
 * route planned on the wrong terrain. botGo must plan in the bot's own world and refuse when the
 * commander is in another dimension. Works from source text because the command needs the
 * Minecraft runtime.
 */
class BotGoDimensionSourceTest {

    private static final String COMMANDS = "src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java";

    private static String method(String signature) throws IOException {
        String src = Files.readString(Paths.get(COMMANDS), StandardCharsets.UTF_8);
        int start = src.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, "could not find the end of " + signature);
        return src.substring(start, end).replaceAll("\\s+", " ");
    }

    @Test
    void botGoPlansInTheBotsOwnWorld() throws IOException {
        String body = method("private static void botGo(");
        assertFalse(body.contains("getOverworld()"), "botGo must not plan on the Overworld");
        assertTrue(body.contains("bot.getEntityWorld() instanceof ServerWorld world"),
                "botGo must plan in the bot's own world");
    }

    @Test
    void botGoRefusesAcrossDimensions() throws IOException {
        String body = method("private static void botGo(");
        assertTrue(body.contains("commander.getEntityWorld().getRegistryKey() != world.getRegistryKey()"),
                "botGo must compare the commander's dimension with the bot's");
        assertTrue(body.contains("\" can't go there across dimensions.\""),
                "botGo must tell the commander why it refused");
    }

    @Test
    void goToLookTellsWhenANamedBotIsInAnotherDimension() throws IOException {
        String body = method("static int executeGoToLookTargets(");
        assertTrue(body.contains("if (targetArg != null && !isAll) { ChatUtils.sendSystemMessage(source, "
                        + "bot.getName().getString() + \" can't go there across dimensions.\"); }"),
                "go_to_look must say why an explicitly named bot was skipped");
    }
}
