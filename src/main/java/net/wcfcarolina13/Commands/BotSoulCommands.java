package net.wcfcarolina13.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.wcfcarolina13.GameAI.souls.SoulTypes;
import net.wcfcarolina13.GameAI.souls.voice.PocketVoiceEngine;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;
import net.wcfcarolina13.GameAI.souls.voice.VoiceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Explicit `/bot soul` pilot controls: {@code status [bot]}, {@code system <on|off>},
 * {@code model <model>}, {@code enable <bot>}, {@code disable <bot>}, {@code reset <bot>},
 * {@code memory <bot>}, {@code digest <on|off|status>}.
 *
 * <p>{@code system}/{@code model} require {@link Frens#isOperator(ServerCommandSource)}: they
 * write {@link ManualConfig}, call {@link ManualConfig#save()}, and then AWAIT
 * {@link SoulRuntime#reloadSettings(ManualConfig)} before reporting success, so the live pipeline
 * never drifts from the persisted config. The bot-specific commands ({@code status} with an
 * explicit bot, {@code enable}, {@code disable}, {@code reset}) require the command actor be
 * either the bot's exact recorded owner or an operator
 * ({@link CompanionCommunicationPolicy#isPrivateSoulAuthorized}), and only ever act on the
 * actor's own identity -- {@code reset} in particular archives only the actor's own direct thread
 * with the target bot, so an operator resetting a bot never erases another player's private
 * conversation.
 *
 * <p>Every async completion from {@link SoulRuntime} (a {@link java.util.concurrent.CompletableFuture}
 * that resolves on whatever thread finishes the underlying store/scheduler work, never the server
 * thread) schedules its chat feedback back onto the server thread via
 * {@code source.getServer().execute(...)} before touching {@link ServerCommandSource#sendError}
 * or sending a chat message -- mirroring {@code SoulMessageDelivery}'s own thread discipline.
 */
final class BotSoulCommands {

    // Shared with the rest of the soul-communication pilot -- never Frens.LOGGER (see
    // SoulRuntime's class Javadoc for why).
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    private static final int MAX_MODEL_LENGTH = 128;

    /**
     * The pilot's one supported profile id, derived from {@link #profileId(String)} itself so the
     * literal used by {@code enable} can never drift from the alias table the tests pin down.
     */
    private static final String JAKE_PROFILE_ID = profileId("jake").orElseThrow();

    private BotSoulCommands() {
    }

    static ArgumentBuilder<ServerCommandSource, ?> build() {
        return CommandManager.literal("soul")
                .then(CommandManager.literal("status")
                        .executes(context -> executeStatus(context, null))
                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                .executes(context -> executeStatus(context,
                                        EntityArgumentType.getPlayer(context, "bot")))))
                .then(CommandManager.literal("system")
                        .then(CommandManager.argument("mode", StringArgumentType.word())
                                .executes(context -> executeSystem(context,
                                        StringArgumentType.getString(context, "mode")))))
                .then(CommandManager.literal("model")
                        // greedyString: Ollama model names contain ':' (tags) and '/'
                        // (hf.co/... repos), which word()/unquoted string() reject at parse
                        // time. Model is the final argument, so greedy is safe; validatedModel
                        // still trims and rejects blank/control/overlong input.
                        .then(CommandManager.argument("model", StringArgumentType.greedyString())
                                .executes(context -> executeModel(context,
                                        StringArgumentType.getString(context, "model")))))
                .then(CommandManager.literal("enable")
                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                .executes(context -> executeEnable(context,
                                        EntityArgumentType.getPlayer(context, "bot")))))
                .then(CommandManager.literal("disable")
                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                .executes(context -> executeDisable(context,
                                        EntityArgumentType.getPlayer(context, "bot")))))
                .then(CommandManager.literal("reset")
                        .then(CommandManager.literal("party").executes(BotSoulCommands::executePartyReset))
                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                .executes(context -> executeReset(context,
                                        EntityArgumentType.getPlayer(context, "bot")))))
                .then(CommandManager.literal("memory")
                        .then(CommandManager.argument("bot", EntityArgumentType.player())
                                .executes(context -> executeMemory(context,
                                        EntityArgumentType.getPlayer(context, "bot")))))
                .then(CommandManager.literal("digest")
                        .then(CommandManager.literal("on").executes(ctx -> executeDigestToggle(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> executeDigestToggle(ctx, false)))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeDigestStatus)))
                .then(CommandManager.literal("voice")
                        .then(CommandManager.literal("on").executes(BotSoulCommands::executeVoiceOn))
                        .then(CommandManager.literal("off").executes(BotSoulCommands::executeVoiceOff))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeVoiceStatus))
                        // Per-bot voices (2026-08-29): what is installed, fetch a pinned catalogue
                        // voice, and pick a voice per soul profile.
                        .then(CommandManager.literal("list").executes(BotSoulCommands::executeVoiceList))
                        .then(CommandManager.literal("install")
                                .then(CommandManager.argument("voice", StringArgumentType.word())
                                        .executes(BotSoulCommands::executeVoiceInstall)))
                        .then(CommandManager.literal("assign")
                                .then(CommandManager.argument("profile", StringArgumentType.word())
                                        .then(CommandManager.literal("default")
                                                .executes(ctx -> executeVoiceAssign(ctx, null)))
                                        .then(CommandManager.literal("clone")
                                                .then(CommandManager.argument("ref", StringArgumentType.greedyString())
                                                        .executes(BotSoulCommands::executeVoiceAssignClone)))
                                        .then(CommandManager.argument("voice", StringArgumentType.greedyString())
                                                .executes(ctx -> executeVoiceAssign(ctx,
                                                        StringArgumentType.getString(ctx, "voice")))))))
                .then(CommandManager.literal("banter")
                        .then(CommandManager.literal("on").executes(ctx -> executeBanterToggle(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> executeBanterToggle(ctx, false)))
                        .then(CommandManager.literal("now").executes(ctx -> executePrime(ctx, true)))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeBanterStatus)))
                .then(CommandManager.literal("local")
                        .then(CommandManager.literal("on").executes(ctx -> executeLocalToggle(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> executeLocalToggle(ctx, false)))
                        .then(CommandManager.literal("now").executes(ctx -> executePrime(ctx, false)))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeLocalStatus)));
    }

    // === Pure helpers (unit-tested; no Minecraft types) ===

    /**
     * Resolves a user-typed alias to a registered profile id ("jake"/"frens:jake" and, since
     * 2026-08-29, "bob"/"frens:bob"), or {@link Optional#empty()} for anything else. A name
     * alone never activates anything — activation still requires an explicit
     * {@code /bot soul enable <bot>}, which binds a name-matched profile when one is
     * registered and falls back to {@link #JAKE_PROFILE_ID} otherwise.
     */
    static Optional<String> profileId(String rawAlias) {
        if (rawAlias == null) {
            return Optional.empty();
        }
        String normalized = rawAlias.trim().toLowerCase(Locale.ROOT);
        // Resolved against the registry rather than a hard-coded name list, so a newly shipped
        // built-in profile (silas.json, 2026-09-04) is addressable the moment it loads.
        String bare = normalized.startsWith("frens:") ? normalized.substring("frens:".length()) : normalized;
        return net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.profileIdForBotName(bare);
    }

    /**
     * Sanitizes a user-typed model name: trimmed, non-blank, free of control characters, and at
     * most {@value #MAX_MODEL_LENGTH} characters (measured after trimming).
     */
    static Optional<String> validatedModel(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_MODEL_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(trimmed);
    }

    /**
     * Validates the piper binary + voice model paths before {@code /bot soul voice on} flips the
     * config switch, so a bad config never silently produces a "voice on" that never actually
     * speaks. Reports the first concrete problem found, in order: binary configured, model
     * configured, binary exists, model exists.
     */
    static Optional<String> validateVoicePaths(String binary, String model, Predicate<String> isFile) {
        if (binary == null || binary.isBlank()) {
            return Optional.of("Configure the piper binary path first.");
        }
        if (model == null || model.isBlank()) {
            return Optional.of("Configure a piper voice model first.");
        }
        if (!isFile.test(binary)) {
            return Optional.of("Piper binary not found: " + binary);
        }
        if (!isFile.test(model)) {
            return Optional.of("Voice model not found: " + model);
        }
        return Optional.empty();
    }

    /**
     * Engine-aware validation for {@code /bot soul voice on}: piper checks binary + model,
     * dreamsleeve checks the repo's {@code scripts/tts_server.py} and the voice-anchor
     * reference clip, pocket checks the installed {@code pocket-tts} binary. Reports the first
     * concrete problem found.
     */
    static Optional<String> validateVoiceConfig(String engine, String binary, String model,
                                                 String dreamsleeveDir, String refAudio,
                                                 String pocketDir, Predicate<String> isFile) {
        if (SoulVoiceSettings.ENGINE_POCKET.equals(engine)) {
            return isFile.test(PocketVoiceEngine.binaryPath(pocketDir == null ? "" : pocketDir).toString())
                    ? Optional.empty()
                    : Optional.of("Pocket TTS is not installed (Soul Voice → Eng… → Install).");
        }
        if (SoulVoiceSettings.ENGINE_DREAMSLEEVE.equals(engine)) {
            if (dreamsleeveDir == null || dreamsleeveDir.isBlank()) {
                return Optional.of("Configure the dreamsleeve directory first.");
            }
            if (refAudio == null || refAudio.isBlank()) {
                return Optional.of("Configure a voice reference clip (soulVoiceRefAudio) first.");
            }
            String server = dreamsleeveDir + "/scripts/tts_server.py";
            if (!isFile.test(server)) {
                return Optional.of("Dreamsleeve TTS server not found: " + server);
            }
            if (!isFile.test(refAudio)) {
                return Optional.of("Voice reference clip not found: " + refAudio);
            }
            return Optional.empty();
        }
        return validateVoicePaths(binary, model, isFile);
    }

    private static Optional<Boolean> parseOnOff(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on" -> Optional.of(true);
            case "off" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    // === system / model (operator-only, config + live reload) ===

    private static int executeSystem(CommandContext<ServerCommandSource> context, String modeArg) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the soul system switch."));
            return 0;
        }
        Optional<Boolean> parsed = parseOnOff(modeArg);
        if (parsed.isEmpty()) {
            source.sendError(Text.literal("Use on or off."));
            return 0;
        }
        boolean enabled = parsed.get();
        ManualConfig config = Frens.CONFIG;
        config.setSoulsEnabled(enabled);
        config.save();
        return awaitReloadThenReport(source, "Soul system set to " + (enabled ? "on" : "off") + ".");
    }

    private static int executeModel(CommandContext<ServerCommandSource> context, String rawModel) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the soul model."));
            return 0;
        }
        Optional<String> validated = validatedModel(rawModel);
        if (validated.isEmpty()) {
            source.sendError(Text.literal(
                    "Invalid model name: must be non-blank, free of control characters, and at most "
                            + MAX_MODEL_LENGTH + " characters."));
            return 0;
        }
        String model = validated.get();
        ManualConfig config = Frens.CONFIG;
        config.setSoulModel(model);
        config.save();
        return awaitReloadThenReport(source, "Soul model set to '" + model + "'.");
    }

    /**
     * Persists (already done by the caller) then awaits {@link SoulRuntime#reloadSettings} so the
     * live pipeline matches the just-saved config before reporting success. If no runtime is
     * currently installed, reports success immediately since there is no live pipeline to await --
     * the saved config still takes effect the next time {@link SoulRuntime#start} runs.
     */
    private static int awaitReloadThenReport(ServerCommandSource source, String successMessage) {
        MinecraftServer server = source.getServer();
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            ChatUtils.sendSystemMessage(source, successMessage + " (soul runtime not currently running.)");
            return 1;
        }
        maybeRuntime.get().reloadSettings(Frens.CONFIG).whenComplete((v, err) -> server.execute(() -> {
            if (err != null) {
                LOGGER.warn("[souls] reloadSettings failed after config change: {}", err.toString());
                ChatUtils.sendSystemMessage(source, successMessage + " Reload reported an error; check server log.");
            } else {
                ChatUtils.sendSystemMessage(source, successMessage);
            }
        }));
        return 1;
    }

    // === voice on / off / status (operator-only for on/off, config + live reload) ===

    private static int executeVoiceOn(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the soul voice switch."));
            return 0;
        }
        Optional<String> problem = validateVoiceConfig(
                Frens.CONFIG.getSoulVoiceEngine(),
                Frens.CONFIG.getSoulVoicePiperBinary(),
                Frens.CONFIG.getSoulVoiceModel(),
                Frens.CONFIG.getSoulVoiceDreamsleeveDir(),
                Frens.CONFIG.getSoulVoiceRefAudio(),
                Frens.CONFIG.getSoulVoicePocketDir(),
                path -> Files.isRegularFile(Path.of(path)));
        if (problem.isPresent()) {
            source.sendError(Text.literal(problem.get()));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        config.setSoulVoiceEnabled(true);
        config.save();
        return awaitReloadThenReport(source, "Soul voice enabled.");
    }

    private static int executeVoiceOff(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the soul voice switch."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        config.setSoulVoiceEnabled(false);
        config.save();
        return awaitReloadThenReport(source, "Soul voice disabled.");
    }

    private static int executeVoiceStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "Soul system: runtime not currently running.");
            return 1;
        }
        SoulRuntime runtime = maybeRuntime.get();
        SoulVoiceSettings voiceSettings = runtime.voiceSettings();
        boolean alive = runtime.voiceEngineAlive();
        String line = "Soul voice: " + (voiceSettings.enabled() ? "on" : "off")
                + " engine=" + voiceSettings.engine()
                + " valid=" + voiceSettings.valid()
                + (voiceSettings.valid() ? "" : " (" + voiceSettings.validationError() + ")")
                + " engineAlive=" + alive;
        ChatUtils.sendSystemMessage(source, line);
        return 1;
    }

    // === per-bot voices ===

    /**
     * Voice assignments are keyed by BOT name first (two bots may share a profile — Bob was
     * still bound to frens:jake in the field), then by profile id. "bob" → the registered bot's
     * exact display name; anything with a ':' is a profile id; otherwise "frens:<name>" if such
     * a profile exists. Returns null (after sending an error) when nothing matches.
     */
    private static String resolveVoiceTarget(ServerCommandSource source, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.contains(":")) {
            java.util.List<String> known = net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.registeredIds();
            if (!known.contains(s)) {
                source.sendError(Text.literal("Unknown soul profile '" + s + "'. Known: " + String.join(", ", known)));
                return null;
            }
            return s;
        }
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(source.getServer())) {
            if (bot != null && bot.getName().getString().equalsIgnoreCase(s)) {
                return bot.getName().getString();
            }
        }
        Optional<String> profile = net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.profileIdForBotName(s);
        if (profile.isPresent()) {
            return profile.get();
        }
        source.sendError(Text.literal("No bot or soul profile named '" + s + "'. Bots must be spawned; profiles: "
                + String.join(", ", net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.registeredIds())));
        return null;
    }

    static String describeSpec(net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec spec) {
        StringBuilder sb = new StringBuilder();
        if (!spec.voice().isEmpty()) {
            sb.append("voice ").append(spec.voice());
            if (spec.speaker() >= 0) {
                sb.append('#').append(spec.speaker());
            }
        }
        if (!spec.refAudio().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("clone ").append(Path.of(spec.refAudio()).getFileName());
        }
        return sb.toString();
    }

    private static String describeEffectiveVoice(ManualConfig cfg, String botName, String profileId) {
        ManualConfig.SoulVoiceAssignment byBot = SoulRuntime.assignmentFor(cfg, botName);
        if (byBot != null) {
            return describeSpec(SoulRuntime.toSpec(byBot)) + " (assigned to bot)";
        }
        ManualConfig.SoulVoiceAssignment assigned = SoulRuntime.assignmentFor(cfg, profileId);
        if (assigned != null) {
            return describeSpec(SoulRuntime.toSpec(assigned)) + " (assigned to profile)";
        }
        net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec authored =
                net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.voiceFor(profileId);
        if (!authored.isEmpty()) {
            return describeSpec(authored) + " (profile)";
        }
        return "global voice";
    }

    /** {@code /bot soul voice list} — the active engine's catalogue, then each bot's/profile's pick. */
    private static int executeVoiceList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ManualConfig cfg = Frens.CONFIG;
        String engine = cfg.getSoulVoiceEngine();
        if (SoulVoiceSettings.ENGINE_POCKET.equals(engine)) {
            StringBuilder catalogue = new StringBuilder("Pocket TTS voices (all available, no download needed):");
            for (VoiceCatalog.Entry entry : VoiceCatalog.forEngine(engine)) {
                catalogue.append("\n  ").append(entry.name()).append(" — ").append(entry.description());
            }
            ChatUtils.sendSystemMessage(source, catalogue.toString());
        } else if (SoulVoiceSettings.ENGINE_DREAMSLEEVE.equals(engine)) {
            ChatUtils.sendSystemMessage(source,
                    "Dreamsleeve clones a reference clip: /bot soul voice assign <bot> clone <ref.wav>");
        } else {
            Path dir = net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.voicesDir();
            java.util.List<String> installed = net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.installedVoiceNames(dir);
            ChatUtils.sendSystemMessage(source, "Piper voices in " + dir + ": "
                    + (installed.isEmpty() ? "none" : String.join(", ", installed)));
            StringBuilder catalogue = new StringBuilder("Catalogue (/bot soul voice install <name>):");
            for (VoiceCatalog.Entry entry : VoiceCatalog.forEngine(SoulVoiceSettings.ENGINE_PIPER)) {
                catalogue.append("\n  ").append(entry.name()).append(" — ").append(entry.description())
                        .append(installed.contains(entry.name()) ? " [installed]" : "");
            }
            ChatUtils.sendSystemMessage(source, catalogue.toString());
        }
        StringBuilder picks = new StringBuilder("Voices (/bot soul voice assign <bot|profile> <voice[#speaker]> | clone <ref.wav> | default):");
        Optional<SoulRuntime> rt = SoulRuntime.current();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(source.getServer())) {
            if (bot == null) {
                continue;
            }
            String name = bot.getName().getString();
            String profile = rt.flatMap(r -> r.cachedState(bot.getUuid()))
                    .map(net.wcfcarolina13.GameAI.souls.SoulTypes.SoulState::profileId).orElse("");
            picks.append("\n  ").append(name).append(profile.isEmpty() ? " (no soul)" : " [" + profile + "]")
                    .append(" → ").append(describeEffectiveVoice(cfg, name, profile));
        }
        for (String id : net.wcfcarolina13.GameAI.souls.SoulProfileRegistry.registeredIds()) {
            picks.append("\n  ").append(id).append(" → ").append(describeEffectiveVoice(cfg, "", id));
        }
        ChatUtils.sendSystemMessage(source, picks.toString());
        return 1;
    }

    /** {@code /bot soul voice install <name>} — background download of one pinned Piper catalogue voice. */
    private static int executeVoiceInstall(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may install voices."));
            return 0;
        }
        String engine = Frens.CONFIG.getSoulVoiceEngine();
        if (!VoiceCatalog.needsDownload(engine)) {
            ChatUtils.sendSystemMessage(source, "No download needed — " + engine
                    + " voices are ready. /bot soul voice assign <bot> <name>.");
            return 1;
        }
        String name = StringArgumentType.getString(context, "voice");
        java.util.Optional<net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.CatalogVoice> maybe =
                net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.findCatalogVoice(name);
        if (maybe.isEmpty()) {
            source.sendError(Text.literal("Unknown voice '" + name + "'. See /bot soul voice list for the catalogue."));
            return 0;
        }
        net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.CatalogVoice voice = maybe.get();
        Path dir = net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.voicesDir();
        net.minecraft.server.MinecraftServer server = source.getServer();
        ChatUtils.sendSystemMessage(source, "Downloading " + voice.name() + " ("
                + (voice.downloadBytes() / (1024 * 1024)) + " MB, sha256-verified)…");
        Thread worker = new Thread(() -> {
            long[] lastPct = {-1L};
            try {
                net.wcfcarolina13.GameAI.souls.voice.PiperInstaller.installVoice(voice, dir, (stage, done, total) -> {
                    if (total <= 0) {
                        return;
                    }
                    long pct = done * 100 / total;
                    if (pct / 25 != lastPct[0] / 25) {
                        lastPct[0] = pct;
                        server.execute(() -> ChatUtils.sendSystemMessage(source, stage + " " + pct + "%"));
                    }
                });
                server.execute(() -> ChatUtils.sendSystemMessage(source, "Installed " + voice.name()
                        + ". Assign it with /bot soul voice assign <bot> " + voice.name()
                        + (voice.name().contains("libritts") ? "#<speaker>" : "")));
            } catch (Exception ex) {
                server.execute(() -> source.sendError(Text.literal("Voice install failed: " + ex.getMessage())));
            }
        }, "frens-voice-install");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /** {@code /bot soul voice assign <bot> <voice[#speaker]>} or {@code … default}. */
    private static int executeVoiceAssign(CommandContext<ServerCommandSource> context, String voiceOrNull) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may assign voices."));
            return 0;
        }
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            source.sendError(Text.literal("Config not loaded."));
            return 0;
        }
        String profileId = resolveVoiceTarget(source, StringArgumentType.getString(context, "profile"));
        if (profileId == null) {
            return 0;
        }
        if (voiceOrNull == null || voiceOrNull.isBlank() || voiceOrNull.trim().equalsIgnoreCase("default")) {
            cfg.setSoulProfileVoice(profileId, null);
            cfg.save();
            ChatUtils.sendSystemMessage(source, profileId + " now uses the global voice.");
            return 1;
        }
        net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec spec =
                net.wcfcarolina13.GameAI.souls.SoulTypes.VoiceSpec.parse(voiceOrNull);
        String engine = cfg.getSoulVoiceEngine();
        if (SoulVoiceSettings.ENGINE_POCKET.equals(engine)
                && !VoiceCatalog.isKnown(SoulVoiceSettings.ENGINE_POCKET, spec.voice())) {
            source.sendError(Text.literal("Unknown Pocket voice '" + spec.voice() + "'. /bot soul voice list."));
            return 0;
        }
        String defaultModel = cfg.getSoulVoiceModel();
        if (SoulVoiceSettings.ENGINE_PIPER.equals(engine) && defaultModel != null && !defaultModel.isBlank()) {
            String resolved = net.wcfcarolina13.GameAI.souls.voice.PiperVoiceEngine.resolveModelPath(
                    defaultModel, spec.voice(), Files::isRegularFile);
            String wanted = net.wcfcarolina13.GameAI.souls.voice.PiperVoiceEngine.resolveModelPath(
                    defaultModel, spec.voice(), p -> true);
            if (!resolved.equals(wanted)) {
                source.sendError(Text.literal("Voice '" + spec.voice() + "' is not installed (looked for "
                        + wanted + "). /bot soul voice install <name> first, or /bot soul voice list."));
                return 0;
            }
        }
        cfg.setSoulProfileVoice(profileId, new ManualConfig.SoulVoiceAssignment(
                spec.voice(), spec.speaker(), "", ""));
        cfg.save();
        ChatUtils.sendSystemMessage(source, profileId + " → " + describeSpec(spec)
                + ". Takes effect on the next spoken line.");
        return 1;
    }

    /** {@code /bot soul voice assign <bot> clone <ref.wav>[ | <transcript>]} — Dreamsleeve anchor. */
    private static int executeVoiceAssignClone(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may assign voices."));
            return 0;
        }
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            source.sendError(Text.literal("Config not loaded."));
            return 0;
        }
        String profileId = resolveVoiceTarget(source, StringArgumentType.getString(context, "profile"));
        if (profileId == null) {
            return 0;
        }
        String raw = StringArgumentType.getString(context, "ref");
        int bar = raw.indexOf('|');
        String refAudio = (bar >= 0 ? raw.substring(0, bar) : raw).trim();
        String refText = bar >= 0 ? raw.substring(bar + 1).trim() : "";
        if (refAudio.isEmpty() || !Files.isRegularFile(Path.of(refAudio))) {
            source.sendError(Text.literal("Reference clip not found: " + refAudio));
            return 0;
        }
        ManualConfig.SoulVoiceAssignment existing = cfg.getSoulProfileVoices().get(profileId);
        cfg.setSoulProfileVoice(profileId, new ManualConfig.SoulVoiceAssignment(
                existing == null ? "" : existing.getVoice(),
                existing == null ? -1 : existing.getSpeaker(),
                refAudio, refText));
        cfg.save();
        ChatUtils.sendSystemMessage(source, profileId + " → clone " + Path.of(refAudio).getFileName()
                + (refText.isEmpty() ? " (no transcript — cloning quality is better with one)" : "")
                + ". Takes effect on the next spoken line.");
        return 1;
    }

    // === status ===

    private static int executeStatus(CommandContext<ServerCommandSource> context, ServerPlayerEntity explicitBot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "Soul system: runtime not currently running.");
            return 1;
        }
        if (explicitBot != null && !BotEventHandler.isRegisteredBot(explicitBot)) {
            source.sendError(Text.literal(explicitBot.getName().getString() + " is not a Frens bot."));
            return 0;
        }
        SoulRuntime runtime = maybeRuntime.get();
        MinecraftServer server = source.getServer();
        ServerPlayerEntity bot = explicitBot != null ? explicitBot : resolveDefaultBot(server, runtime, actor);
        if (bot == null) {
            ChatUtils.sendSystemMessage(source,
                    "No soul-bound bot found for you; specify one: /bot soul status <bot>.");
            return 0;
        }
        if (!CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, bot)) {
            source.sendError(Text.literal(
                    "You are not authorized to view soul status for " + bot.getName().getString() + "."));
            return 0;
        }
        UUID botId = bot.getUuid();
        UUID actorId = actor.getUuid();
        String botName = bot.getName().getString();
        runtime.status(botId, actorId).whenComplete((status, err) -> server.execute(() -> {
            if (err != null) {
                LOGGER.warn("[souls] status failed for bot {}: {}", botId, err.toString());
                ChatUtils.sendSystemMessage(source, "Failed to read soul status for " + botName + ".");
                return;
            }
            ChatUtils.sendSystemMessage(source, formatStatus(botName, status));
        }));
        return 1;
    }

    /**
     * When no bot was named, picks the actor's own soul-bound bot: the first currently-online bot
     * (per {@link BotRegistry#getPlayers}) with an active soul profile that the actor is authorized
     * to see. Never guesses across other players' bots -- {@code isPrivateSoulAuthorized} always
     * passes an operator through, but for a non-operator this only ever matches their own.
     */
    private static ServerPlayerEntity resolveDefaultBot(MinecraftServer server, SoulRuntime runtime,
                                                          ServerPlayerEntity actor) {
        if (server == null) {
            return null;
        }
        for (ServerPlayerEntity candidate : BotRegistry.getPlayers(server)) {
            if (runtime.hasActiveProfile(candidate.getUuid())
                    && CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String formatStatus(String botName, SoulRuntime.Status status) {
        return String.format(Locale.ROOT,
                "Soul status: system=%s settingsValid=%s ready=%s provider=%s model=%s host=%s "
                        + "providerHealthy=%s queueDepth=%d | %s uuid=%s profile=%s active=%s directEpoch=%d",
                status.systemEnabled() ? "on" : "off", status.settingsValid(), status.ready(),
                status.provider(), status.model(), status.ollamaHost().isBlank() ? "(unknown)" : status.ollamaHost(),
                status.providerHealthy(), status.queueDepth(),
                botName, status.botId(), status.profileId().isBlank() ? "(none)" : status.profileId(),
                status.profileActive(), status.conversationEpoch());
    }

    // === enable / disable / reset (owner-or-operator, actor-scoped) ===

    private static int executeEnable(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            source.sendError(Text.literal(bot.getName().getString() + " is not a Frens bot."));
            return 0;
        }
        if (!CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, bot)) {
            source.sendError(Text.literal(
                    "You are not authorized to enable soul communication for " + bot.getName().getString() + "."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        SoulRuntime runtime = maybeRuntime.get();
        MinecraftServer server = source.getServer();
        UUID botId = bot.getUuid();
        String botName = bot.getName().getString();
        // A bot named after a registered profile gets its own persona (Bob → frens:bob);
        // anything else keeps the Jake fallback that the pilot shipped with. Field-reported
        // 2026-08-29: "Bob was speaking as Jake" — because every enable bound frens:jake.
        String boundProfileId = profileId(botName).orElse(JAKE_PROFILE_ID);
        String personaName = boundProfileId.equals(JAKE_PROFILE_ID) ? "Jake" : botName;
        runtime.bindProfile(botId, boundProfileId).whenComplete((boundState, bindErr) -> {
            if (bindErr != null) {
                server.execute(() -> {
                    LOGGER.warn("[souls] enable (bind {}) failed for bot {}: {}", boundProfileId, botId,
                            bindErr.toString());
                    ChatUtils.sendSystemMessage(source, "Failed to enable soul communication for " + botName + ".");
                });
                return;
            }
            runtime.setActive(botId, true).whenComplete((activeState, activeErr) -> server.execute(() -> {
                if (activeErr != null) {
                    LOGGER.warn("[souls] enable (activate) failed for bot {}: {}", botId, activeErr.toString());
                    ChatUtils.sendSystemMessage(source, "Bound " + botName + " to " + personaName
                            + " but failed to activate.");
                } else {
                    ChatUtils.sendSystemMessage(source, botName + " is now speaking as " + personaName
                            + (boundProfileId.equals(JAKE_PROFILE_ID) && !botName.equalsIgnoreCase("Jake")
                                    ? " (no profile of their own is registered yet)." : "."));
                }
            }));
        });
        return 1;
    }

    private static int executeDisable(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            source.sendError(Text.literal(bot.getName().getString() + " is not a Frens bot."));
            return 0;
        }
        if (!CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, bot)) {
            source.sendError(Text.literal(
                    "You are not authorized to disable soul communication for " + bot.getName().getString() + "."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        SoulRuntime runtime = maybeRuntime.get();
        MinecraftServer server = source.getServer();
        UUID botId = bot.getUuid();
        String botName = bot.getName().getString();
        // setActive(false) only flips the active flag (and cancels in-flight generation) -- it
        // never touches the store's on-disk conversation/profile files, so disabling preserves them.
        runtime.setActive(botId, false).whenComplete((state, err) -> server.execute(() -> {
            if (err != null) {
                LOGGER.warn("[souls] disable failed for bot {}: {}", botId, err.toString());
                ChatUtils.sendSystemMessage(source, "Failed to disable soul communication for " + botName + ".");
            } else {
                ChatUtils.sendSystemMessage(source,
                        botName + "'s soul communication is now disabled (conversation files preserved).");
            }
        }));
        return 1;
    }

    private static int executeReset(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            source.sendError(Text.literal(bot.getName().getString() + " is not a Frens bot."));
            return 0;
        }
        if (!CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, bot)) {
            source.sendError(Text.literal(
                    "You are not authorized to reset soul communication for " + bot.getName().getString() + "."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        SoulRuntime runtime = maybeRuntime.get();
        MinecraftServer server = source.getServer();
        String botName = bot.getName().getString();
        // Always the ACTOR's own direct thread with this bot -- never a targeted player's, so an
        // operator running this on someone else's bot only ever erases their own conversation.
        SoulTypes.ConversationKey key =
                new SoulTypes.ConversationKey(bot.getUuid(), actor.getUuid(), SoulTypes.Channel.DIRECT);
        runtime.reset(key).whenComplete((newEpoch, err) -> server.execute(() -> {
            if (err != null) {
                LOGGER.warn("[souls] reset failed for bot {} actor {}: {}", bot.getUuid(), actor.getUuid(),
                        err.toString());
                ChatUtils.sendSystemMessage(source, "Failed to reset your conversation with " + botName + ".");
            } else {
                ChatUtils.sendSystemMessage(source,
                        "Archived your conversation with " + botName + "; new epoch " + newEpoch + ".");
            }
        }));
        return 1;
    }

    /**
     * {@code /bot soul memory <bot>} — what this bot remembers about YOU. Same guard sequence as
     * {@code reset}: the actor's own memories only, so an operator running it on someone else's
     * bot still never reads another player's memories.
     */
    private static int executeMemory(CommandContext<ServerCommandSource> context, ServerPlayerEntity bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            source.sendError(Text.literal(bot.getName().getString() + " is not a Frens bot."));
            return 0;
        }
        if (!CompanionCommunicationPolicy.isPrivateSoulAuthorized(actor, bot)) {
            source.sendError(Text.literal(
                    "You are not authorized to read soul memories for " + bot.getName().getString() + "."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        String botName = bot.getName().getString();
        List<SoulTypes.PlayerMemory> mine = maybeRuntime.get().cachedMind(bot.getUuid())
                .map(SoulTypes.SoulMind::playerMemories)
                .orElse(List.of())
                .stream()
                .filter(m -> actor.getUuid().equals(m.playerId()))
                .sorted(Comparator.comparingInt(SoulTypes.PlayerMemory::day).reversed())
                .toList();
        if (mine.isEmpty()) {
            ChatUtils.sendSystemMessage(source, botName + " doesn't remember anything about you yet.");
            return 1;
        }
        for (SoulTypes.PlayerMemory m : mine) {
            ChatUtils.sendSystemMessage(source, "day " + m.day() + " · salience " + m.salience()
                    + " · " + m.fact());
        }
        return 1;
    }

    /**
     * {@code /bot soul digest on|off} — operator-only kill switch for the day-rollover player
     * memory digest. Like the banter switch, the service reads it through a live supplier, so no
     * pipeline reload is needed; the change lands at the next day rollover.
     */
    private static int executeDigestToggle(CommandContext<ServerCommandSource> context, boolean enabled) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the memory digest switch."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        if (config == null) {
            source.sendError(Text.literal("Config not loaded."));
            return 0;
        }
        config.setSoulMemoryDigestEnabled(enabled);
        config.save();
        ChatUtils.sendSystemMessage(source, "Memory digest set to " + (enabled ? "on" : "off") + ".");
        return 1;
    }

    /** {@code /bot soul digest status} — enablement plus when the digest actually runs. */
    private static int executeDigestStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ManualConfig config = Frens.CONFIG;
        boolean enabled = config == null || config.isSoulMemoryDigestEnabled();
        ChatUtils.sendSystemMessage(source, "Memory digest is " + (enabled ? "ON" : "OFF")
                + ". Runs at each Minecraft day rollover for soul-bound bots.");
        return 1;
    }

    /**
     * {@code /bot soul banter|local now} — field-test lever: clears the actor's cooldown so the
     * next evaluation may fire immediately (every other gate — quiet window, budget, surfaces,
     * roster, danger — still applies; this only removes the wait).
     */
    private static int executePrime(CommandContext<ServerCommandSource> context, boolean banter) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may prime a cooldown."));
            return 0;
        }
        java.util.Optional<SoulRuntime> runtime = SoulRuntime.current();
        if (runtime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        if (banter) {
            runtime.get().primeBanter(actor.getUuid());
            ChatUtils.sendSystemMessage(source, "Banter cooldown cleared — if things are calm"
                    + " and quiet, expect a scene within ~10 seconds.");
        } else {
            runtime.get().primeLocal(actor.getUuid());
            ChatUtils.sendSystemMessage(source, "Local-chat cooldown cleared — your next"
                    + " substantive unaddressed line may draw a reaction.");
        }
        return 1;
    }

    /**
     * Reply-routing note (engagement spec §5 limitation): the reply window and its continuation
     * live in the local-chat director, so with banter ON but local chat OFF a companion remark
     * is one-way — the bot speaks, but answering it routes nowhere special.
     */
    private static String replyRoutingNote() {
        ManualConfig config = Frens.CONFIG;
        if (config != null && !config.isSoulLocalChatEnabled()) {
            return " Note: Local chat is OFF, so you can't answer companion remarks —"
                    + " /bot soul local on enables replies.";
        }
        return "";
    }

    /**
     * {@code /bot soul banter on|off} — operator-only kill switch for autonomous banter scenes.
     * No pipeline reload needed: the director reads the config through a live supplier.
     */
    private static int executeBanterToggle(CommandContext<ServerCommandSource> context, boolean enabled) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the banter switch."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        config.setSoulBanterEnabled(enabled);
        config.save();
        ChatUtils.sendSystemMessage(source, "Companion banter set to " + (enabled ? "on" : "off")
                + (enabled ? ". They'll chat when things are calm, or speak to you when one is alone."
                        + replyRoutingNote() : "."));
        return 1;
    }

    /** {@code /bot soul banter status} — enablement plus the actor's live eligibility verdict. */
    private static int executeBanterStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        boolean enabled = config != null && config.isSoulBanterEnabled();
        boolean active = config != null && config.isSoulBanterActiveEnabled();
        String verdict = SoulRuntime.current()
                .map(rt -> rt.banterStatus(actor.getUuid()))
                .orElse("Soul runtime is not currently running.");
        ChatUtils.sendSystemMessage(source,
                "Banter is " + (enabled ? "ON" : "OFF") + ", active banter is " + (active ? "ON" : "OFF")
                        + ". " + verdict
                        + ((enabled || active) ? replyRoutingNote() : ""));
        return 1;
    }

    /**
     * {@code /bot soul local on|off} — operator-only switch for ambient/local chat. No pipeline
     * reload needed: the director reads the config through a live supplier.
     */
    private static int executeLocalToggle(CommandContext<ServerCommandSource> context, boolean enabled) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the local-chat switch."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        config.setSoulLocalChatEnabled(enabled);
        config.save();
        ChatUtils.sendSystemMessage(source, "Companion local chat set to " + (enabled ? "on" : "off")
                + (enabled ? ". They may chime in on what they overhear." : "."));
        return 1;
    }

    /** {@code /bot soul local status} — enablement plus the actor's live eligibility verdict. */
    private static int executeLocalStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        boolean enabled = config != null && config.isSoulLocalChatEnabled();
        String verdict = SoulRuntime.current()
                .map(rt -> rt.localStatus(actor.getUuid()))
                .orElse("Soul runtime is not currently running.");
        ChatUtils.sendSystemMessage(source,
                "Local chat is " + (enabled ? "ON" : "OFF") + ". " + verdict);
        return 1;
    }

    /**
     * {@code /bot soul reset party} — archives the ACTOR's own party-channel (group scene)
     * transcript and cancels any scene still playing. Owner-scoped by construction: the party
     * thread is keyed by the actor's own UUID, so there is nothing here an operator could erase
     * for someone else.
     */
    private static int executePartyReset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        Optional<SoulRuntime> maybeRuntime = SoulRuntime.current();
        if (maybeRuntime.isEmpty()) {
            source.sendError(Text.literal("Soul runtime is not currently running."));
            return 0;
        }
        SoulRuntime runtime = maybeRuntime.get();
        MinecraftServer server = source.getServer();
        runtime.resetParty(actor.getUuid()).whenComplete((newEpoch, err) -> server.execute(() -> {
            if (err != null) {
                LOGGER.warn("[souls] party reset failed for actor {}: {}", actor.getUuid(), err.toString());
                ChatUtils.sendSystemMessage(source, "Failed to reset your group conversation.");
            } else {
                ChatUtils.sendSystemMessage(source,
                        "Archived your group conversation; new epoch " + newEpoch + ".");
            }
        }));
        return 1;
    }
}
