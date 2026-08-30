package net.wcfcarolina13.Commands;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what is testable about {@link BotSoulCommands} without a running Minecraft server.
 *
 * <p>{@link BotSoulCommands#build()} and its command executors take
 * {@code com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource>}
 * and touch {@code ServerCommandSource}/{@code MinecraftServer}/{@code ServerPlayerEntity} -- none
 * of which this harness can mock (see {@code SoulChatRouterTest}'s class Javadoc for the confirmed
 * Mockito/ByteBuddy failure on those Minecraft types). Those paths are exercised in-game instead.
 *
 * <p>What IS covered here: the two pure, Minecraft-free static helpers the brief mandates --
 * {@link BotSoulCommands#profileId(String)} (the "jake"/"frens:jake" alias gate that keeps the
 * pilot's single supported profile from being confused with an arbitrary bot display name) and
 * {@link BotSoulCommands#validatedModel(String)} (the model-name sanitizer shared by
 * {@code /bot soul model}).
 */
class BotSoulCommandsTest {

    @Test
    void registeredProfileAliasesResolveAndUnknownNamesStayEmpty() {
        assertEquals("frens:jake", BotSoulCommands.profileId("jake").orElseThrow());
        assertEquals("frens:jake", BotSoulCommands.profileId("frens:jake").orElseThrow());
        // Since 2026-08-29 Bob has his own registered persona (frens:bob).
        assertEquals("frens:bob", BotSoulCommands.profileId("bob").orElseThrow());
        assertEquals("frens:bob", BotSoulCommands.profileId("frens:bob").orElseThrow());
        // An arbitrary bot display name still maps to no profile — enable falls back to Jake.
        assertTrue(BotSoulCommands.profileId("Steve").isEmpty());
    }

    @Test
    void modelNameRejectsBlankAndControlCharacters() {
        assertEquals("qwen3:14b", BotSoulCommands.validatedModel(" qwen3:14b ").orElseThrow());
        assertTrue(BotSoulCommands.validatedModel("\n").isEmpty());
        assertTrue(BotSoulCommands.validatedModel("x".repeat(129)).isEmpty());
    }

    @Test
    void profileIdIsCaseInsensitiveAndRejectsBlank() {
        assertEquals("frens:jake", BotSoulCommands.profileId("JAKE").orElseThrow());
        assertEquals("frens:jake", BotSoulCommands.profileId("Frens:Jake").orElseThrow());
        assertTrue(BotSoulCommands.profileId("").isEmpty());
        assertTrue(BotSoulCommands.profileId(null).isEmpty());
    }

    @Test
    void validatedModelRejectsEmbeddedControlCharacterAndAcceptsMaxLength() {
        // Bell character (0x07) embedded mid-string -- not stripped by trim(), unlike the
        // leading/trailing whitespace covered by modelNameRejectsBlankAndControlCharacters.
        assertTrue(BotSoulCommands.validatedModel("qwen314b").isEmpty());
        assertEquals("x".repeat(128), BotSoulCommands.validatedModel("x".repeat(128)).orElseThrow());
        assertTrue(BotSoulCommands.validatedModel(null).isEmpty());
        assertTrue(BotSoulCommands.validatedModel("   ").isEmpty());
    }

    @Test
    void voicePathValidationReportsTheFirstConcreteProblem() {
        java.util.function.Predicate<String> exists = path -> path.equals("/ok/piper") || path.equals("/ok/jake.onnx");
        assertTrue(BotSoulCommands.validateVoicePaths("/ok/piper", "/ok/jake.onnx", exists).isEmpty());
        assertEquals("Piper binary not found: /missing/piper",
                BotSoulCommands.validateVoicePaths("/missing/piper", "/ok/jake.onnx", exists).orElseThrow());
        assertEquals("Voice model not found: /missing/jake.onnx",
                BotSoulCommands.validateVoicePaths("/ok/piper", "/missing/jake.onnx", exists).orElseThrow());
        assertEquals("Configure the piper binary path first.",
                BotSoulCommands.validateVoicePaths("", "/ok/jake.onnx", exists).orElseThrow());
    }

    @Test
    void voiceConfigValidationBranchesPerEngine() {
        java.util.function.Predicate<String> exists = path ->
                path.equals("/ds/scripts/tts_server.py") || path.equals("/ds/refs/calm.wav")
                        || path.equals("/ok/piper") || path.equals("/ok/jake.onnx");
        // dreamsleeve: server script + ref clip, piper paths irrelevant
        assertTrue(BotSoulCommands.validateVoiceConfig("dreamsleeve", "", "",
                "/ds", "/ds/refs/calm.wav", "", exists).isEmpty());
        assertEquals("Dreamsleeve TTS server not found: /missing/scripts/tts_server.py",
                BotSoulCommands.validateVoiceConfig("dreamsleeve", "", "",
                        "/missing", "/ds/refs/calm.wav", "", exists).orElseThrow());
        assertEquals("Configure a voice reference clip (soulVoiceRefAudio) first.",
                BotSoulCommands.validateVoiceConfig("dreamsleeve", "", "",
                        "/ds", "", "", exists).orElseThrow());
        // piper engine delegates to the original path validation
        assertTrue(BotSoulCommands.validateVoiceConfig("piper", "/ok/piper", "/ok/jake.onnx",
                "", "", "", exists).isEmpty());
        // pocket: only the installed pocket-tts binary matters
        java.util.function.Predicate<String> pocketInstalled = path ->
                path.equals("/pocket/venv/bin/pocket-tts");
        assertTrue(BotSoulCommands.validateVoiceConfig("pocket", "", "",
                "", "", "/pocket", pocketInstalled).isEmpty());
        assertEquals("Pocket TTS is not installed (Soul Voice → Eng… → Install).",
                BotSoulCommands.validateVoiceConfig("pocket", "", "",
                        "", "", "/elsewhere", pocketInstalled).orElseThrow());
    }

    /**
     * Builds a real (non-mocked) ManualConfig instance via its private constructor.
     * ManualConfig() only assigns selectedLanguageModel from a system property — no file I/O,
     * no FILE_PATH resolution — so this is safe to call from a plain unit test JVM (mirrors
     * SoulFoundationTest#newRealConfig).
     */
    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void localChatDefaultsOffAndRoundTrips() throws Exception {
        ManualConfig config = newRealConfig();
        assertFalse(config.isSoulLocalChatEnabled(), "ambient speech must be opt-in");
        config.setSoulLocalChatEnabled(true);
        assertTrue(config.isSoulLocalChatEnabled());
    }
}
