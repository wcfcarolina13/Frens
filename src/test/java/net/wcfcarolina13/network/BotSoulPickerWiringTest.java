package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker actions are wired through the same server-side gate and re-check as ENABLE_BOT, and
 * validate before they write. Reads the source: AiSetupNetworkManager touches server types.
 */
class BotSoulPickerWiringTest {

    private static final Path MANAGER = Path.of("src/main/java/net/wcfcarolina13/network/AiSetupNetworkManager.java");
    private static final Path CHECKLIST = Path.of(
            "src/main/java/net/wcfcarolina13/GraphicalUserInterface/AiSetupChecklistScreen.java");

    private static String section(String src, String startMarker, String endMarker) {
        int start = src.indexOf(startMarker);
        int end = src.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start, "section not found: " + startMarker);
        return src.substring(start, end);
    }

    @Test
    void handleActionResolvesTheBotForEveryBotScopedActionBeforeTheGate() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static void handleAction(", "// ── Async re-check");
        int resolve = body.indexOf("if (action.isBotScoped()) {");
        int uuid = body.indexOf("BotSoulPickerPolicy.botUuidOf(action, arg)");
        int decide = body.indexOf("BotAccessGate.decide(player, bot)");
        int gate = body.indexOf("AiSetupActionPolicy.gate(action, canEditServer(server, player)");
        int persona = body.indexOf("case SET_BOT_PERSONA -> setBotPersona(server, ticket, bot);");
        int voice = body.indexOf("case SET_BOT_VOICE -> setBotVoice(server, ticket, bot);");
        assertTrue(resolve >= 0 && uuid > resolve && decide > uuid && gate > decide, "bot resolved, then gated");
        assertTrue(persona > gate && voice > gate, "both pickers dispatch after the gate");
    }

    @Test
    void recheckCoversThePickers() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static Recheck recheck(", "private static final String SUPERSEDED_MESSAGE");
        assertTrue(body.contains("if (ticket.action().isBotScoped()) {"));
        assertTrue(body.contains("BotSoulPickerPolicy.botUuidOf(ticket.action(), ticket.arg())"));
    }

    @Test
    void personaIsValidatedBeforeItIsBound() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static void setBotPersona(", "private static void setBotVoice(");
        int check = body.indexOf("BotSoulPickerPolicy.checkPersona(profileId,");
        int registry = body.indexOf("SoulProfileRegistry.registeredIds()");
        int reject = body.indexOf("if (check != BotSoulPickerPolicy.PersonaCheck.OK) {");
        int bind = body.indexOf("runtime.bindPickedProfile(botId, profileId)");
        int stillValid = body.indexOf("stillValid(server, ticket)");
        assertTrue(check >= 0 && registry > check && reject > registry && bind > reject, "check, reject, then bind");
        assertTrue(stillValid > bind, "the reply re-checks the ticket after the async bind");
        assertTrue(!body.contains("setActive("), "a persona pick never changes the active flag");
    }

    private static final Path COMMANDS = Path.of("src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java");
    private static final Path RUNTIME = Path.of("src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java");

    @Test
    void checklistEnableKeepsAPickedPersona() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static void enableBot(", "public static void applyNewBotSoulDefault(");
        int decide = body.indexOf("runtime.enableBinding(botId,");
        int bind = body.indexOf("runtime.bindForEnable(botId, binding)");
        assertTrue(decide >= 0 && bind > decide, "rebind-or-keep decided, then applied");
        assertTrue(!body.contains("runtime.bindProfile("), "no unconditional rebind by name");
    }

    @Test
    void commandEnableKeepsAPickedPersona() throws IOException {
        String body = section(Files.readString(COMMANDS),
                "private static int executeEnable(", "private static int executeDisable(");
        int decide = body.indexOf("runtime.enableBinding(botId,");
        int bind = body.indexOf("runtime.bindForEnable(botId, binding)");
        assertTrue(decide >= 0 && bind > decide, "rebind-or-keep decided, then applied");
        assertTrue(!body.contains("runtime.bindProfile("), "no unconditional rebind by name");
    }

    @Test
    void legacyMigrationSkipsPickedBindings() throws IOException {
        String body = section(Files.readString(RUNTIME),
                "void migrateLegacyBindings(", "void prewarmVoices(");
        int skip = body.indexOf("PersonaBindingPolicy.shouldMigrateLegacy(");
        int picked = body.indexOf("isPickedBinding(bot.getUuid())", skip);
        int bind = body.indexOf("bindProfile(botId, own.get())");
        assertTrue(skip >= 0 && picked > skip && bind > picked, "the pick is checked before the rebind");
    }

    @Test
    void keptBindingWritesNothing() throws IOException {
        String body = section(Files.readString(RUNTIME),
                "public CompletableFuture<SoulTypes.SoulState> bindForEnable(", "/**");
        int keep = body.indexOf("if (binding.keep()) {");
        int completed = body.indexOf("CompletableFuture.completedFuture(", keep);
        int bind = body.indexOf("return bindProfile(botId, binding.profileId());");
        assertTrue(keep >= 0 && completed > keep && bind > completed);
    }

    @Test
    void voiceIsValidatedAgainstTheServersPresetsBeforeItIsSaved() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static void setBotVoice(", "public static void applyNewBotSoulDefault(");
        int check = body.indexOf("BotSoulPickerPolicy.checkVoice(choice.get().value(),");
        int presets = body.indexOf("voicePresets(config)");
        int write = body.indexOf("config.setSoulProfileVoice(botName,");
        int save = body.indexOf("config.save();");
        assertTrue(check >= 0 && presets > check && write > presets && save > write, "check, write, save");
        int reject = body.indexOf("default -> {");
        int returnInReject = body.indexOf("return;", reject);
        assertTrue(reject > check && returnInReject > reject && returnInReject < save, "a rejected voice saves nothing");
    }

    @Test
    void voicePresetsAndAssignmentsReachOnlyOperatorsAndHosts() throws IOException {
        String body = section(Files.readString(MANAGER),
                "private static AiSetupStatus snapshot(", "// ── Persona / voice picker data");
        assertTrue(body.contains("canEdit ? assignedPreset(config, botName) : \"\""));
        assertTrue(body.contains("canEdit ? voicePresets(config) : List.of()"));
        assertTrue(body.contains("personaList()"));
    }

    @Test
    void checklistOffersPickOnlyWhenTheServerDoes() throws IOException {
        String src = Files.readString(CHECKLIST);
        int guard = src.indexOf("if (status != null && status.offersPickers()) {");
        int open = src.indexOf("new BotSoulPickerScreen(this, row.uuid())");
        assertTrue(guard >= 0 && open > guard);
    }
}
