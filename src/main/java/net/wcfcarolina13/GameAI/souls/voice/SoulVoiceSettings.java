package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import java.util.Locale;

/**
 * Validated, immutable snapshot of the soul generated-voice settings derived from
 * {@link ManualConfig} — mirrors {@code SoulSettings.from}. Structural validation only
 * (non-blank paths, known engine); file existence is checked by the enable command and at
 * engine start, never here (this must stay pure).
 *
 * <p>Three engines: {@code "piper"} (CPU, needs {@code piperBinary} + {@code voiceModel}),
 * {@code "dreamsleeve"} (the Qwen3-TTS voice-clone warm server from the OpenMW Dreamsleeve
 * project — needs {@code dreamsleeveDir} plus a voice anchor: {@code refAudio} reference clip
 * and its {@code refText} transcript, which the model clones per line), and {@code "pocket"}
 * (Kyutai Pocket TTS — CPU, warm HTTP server on loopback, English preset voices; needs
 * {@code pocketDir}, the install dir holding the managed venv, and a default
 * {@code pocketVoice} preset).
 */
public record SoulVoiceSettings(boolean enabled, boolean valid, String validationError,
                                String engine, String piperBinary, String voiceModel,
                                String dreamsleeveDir, String refAudio, String refText,
                                String pocketDir, String pocketVoice,
                                int maxChars, long synthTimeoutMs, float radioGain) {

    public static final String ENGINE_PIPER = "piper";
    public static final String ENGINE_DREAMSLEEVE = "dreamsleeve";
    public static final String ENGINE_POCKET = "pocket";
    static final String DEFAULT_POCKET_VOICE = "charles";

    /** Disabled + structurally invalid snapshot carrying {@code reason} as the validation error. */
    public static SoulVoiceSettings disabled(String reason) {
        return new SoulVoiceSettings(false, false, reason == null ? "" : reason,
                ENGINE_PIPER, "", "", "", "", "", "", DEFAULT_POCKET_VOICE, 400, 8000L, 0.6f);
    }

    public static SoulVoiceSettings from(ManualConfig config) {
        if (config == null) {
            return disabled("Frens configuration is unavailable.");
        }
        boolean enabled = config.isSoulVoiceEnabled();
        String engine = config.getSoulVoiceEngine().toLowerCase(Locale.ROOT);
        String binary = config.getSoulVoicePiperBinary();
        String model = config.getSoulVoiceModel();
        String dsDir = config.getSoulVoiceDreamsleeveDir();
        String refAudio = config.getSoulVoiceRefAudio();
        String refText = config.getSoulVoiceRefText();
        String pocketDir = config.getSoulVoicePocketDir();
        String pocketVoice = config.getSoulVoicePocketVoice();
        int maxChars = config.getSoulVoiceMaxChars();
        long timeoutMs = config.getSoulVoiceSynthTimeoutMs();
        float radioGain = config.getSoulVoiceRadioGain();

        String error = validationErrorFor(engine, binary, model, dsDir, refAudio, pocketDir);
        return new SoulVoiceSettings(enabled, error.isEmpty(), error, engine, binary, model,
                dsDir, refAudio, refText, pocketDir, pocketVoice, maxChars, timeoutMs, radioGain);
    }

    /** Pure structural validation shared with the command layer; "" means valid. */
    static String validationErrorFor(String engine, String piperBinary, String voiceModel,
                                      String dreamsleeveDir, String refAudio, String pocketDir) {
        switch (engine) {
            case ENGINE_PIPER -> {
                if (piperBinary.isBlank()) {
                    return "Configure the piper binary path first.";
                }
                if (voiceModel.isBlank()) {
                    return "Configure a piper voice model first.";
                }
                return "";
            }
            case ENGINE_DREAMSLEEVE -> {
                if (dreamsleeveDir.isBlank()) {
                    return "Configure the dreamsleeve directory first.";
                }
                if (refAudio.isBlank()) {
                    return "Configure a voice reference clip (soulVoiceRefAudio) first.";
                }
                return "";
            }
            case ENGINE_POCKET -> {
                return pocketDir.isBlank() ? "Install Pocket TTS first (Soul Voice -> Engine)." : "";
            }
            default -> {
                return "Unknown soul voice engine: " + engine;
            }
        }
    }
}
