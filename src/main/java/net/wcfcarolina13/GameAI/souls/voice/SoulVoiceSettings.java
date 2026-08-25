package net.wcfcarolina13.GameAI.souls.voice;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import java.util.Locale;

/**
 * Validated, immutable snapshot of the soul generated-voice settings derived from
 * {@link ManualConfig} — mirrors {@code SoulSettings.from}. Structural validation only
 * (non-blank paths, known engine); file existence is checked by the enable command and at
 * engine start, never here (this must stay pure).
 *
 * <p>Two engines: {@code "piper"} (CPU, needs {@code piperBinary} + {@code voiceModel}) and
 * {@code "dreamsleeve"} (the Qwen3-TTS voice-clone warm server from the OpenMW Dreamsleeve
 * project — needs {@code dreamsleeveDir} plus a voice anchor: {@code refAudio} reference clip
 * and its {@code refText} transcript, which the model clones per line).
 */
public record SoulVoiceSettings(boolean enabled, boolean valid, String validationError,
                                String engine, String piperBinary, String voiceModel,
                                String dreamsleeveDir, String refAudio, String refText,
                                int maxChars, long synthTimeoutMs, float radioGain) {

    public static final String ENGINE_PIPER = "piper";
    public static final String ENGINE_DREAMSLEEVE = "dreamsleeve";

    public static SoulVoiceSettings from(ManualConfig config) {
        if (config == null) {
            return new SoulVoiceSettings(false, false, "Frens configuration is unavailable.",
                    ENGINE_PIPER, "", "", "", "", "", 400, 8000L, 0.6f);
        }
        boolean enabled = config.isSoulVoiceEnabled();
        String engine = config.getSoulVoiceEngine().toLowerCase(Locale.ROOT);
        String binary = config.getSoulVoicePiperBinary();
        String model = config.getSoulVoiceModel();
        String dsDir = config.getSoulVoiceDreamsleeveDir();
        String refAudio = config.getSoulVoiceRefAudio();
        String refText = config.getSoulVoiceRefText();
        int maxChars = config.getSoulVoiceMaxChars();
        long timeoutMs = config.getSoulVoiceSynthTimeoutMs();
        float radioGain = config.getSoulVoiceRadioGain();

        String error = validationErrorFor(engine, binary, model, dsDir, refAudio);
        return new SoulVoiceSettings(enabled, error.isEmpty(), error, engine, binary, model,
                dsDir, refAudio, refText, maxChars, timeoutMs, radioGain);
    }

    /** Pure structural validation shared with the command layer; "" means valid. */
    static String validationErrorFor(String engine, String piperBinary, String voiceModel,
                                      String dreamsleeveDir, String refAudio) {
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
            default -> {
                return "Unknown soul voice engine: " + engine;
            }
        }
    }
}
