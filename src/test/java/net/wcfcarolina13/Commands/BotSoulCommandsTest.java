package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void onlyJakeProfileAliasIsAcceptedInPilot() {
        assertEquals("frens:jake", BotSoulCommands.profileId("jake").orElseThrow());
        assertEquals("frens:jake", BotSoulCommands.profileId("frens:jake").orElseThrow());
        assertTrue(BotSoulCommands.profileId("bob").isEmpty());
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
                "/ds", "/ds/refs/calm.wav", exists).isEmpty());
        assertEquals("Dreamsleeve TTS server not found: /missing/scripts/tts_server.py",
                BotSoulCommands.validateVoiceConfig("dreamsleeve", "", "",
                        "/missing", "/ds/refs/calm.wav", exists).orElseThrow());
        assertEquals("Configure a voice reference clip (soulVoiceRefAudio) first.",
                BotSoulCommands.validateVoiceConfig("dreamsleeve", "", "",
                        "/ds", "", exists).orElseThrow());
        // piper engine delegates to the original path validation
        assertTrue(BotSoulCommands.validateVoiceConfig("piper", "/ok/piper", "/ok/jake.onnx",
                "", "", exists).isEmpty());
    }
}
